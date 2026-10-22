package com.collabcode.editor.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs each submission in a throwaway container: no network, dropped
 * capabilities, hard memory/CPU/pid limits, a non-root user, and a wall-clock
 * timeout enforced by the caller.
 *
 * This is the strategy to use on any machine with Docker, including Windows
 * with Docker Desktop, where firejail is unavailable.
 *
 * Enabled with {@code collab.execution.type=containerized}.
 */
@Component
@ConditionalOnProperty(name = "collab.execution.type", havingValue = "containerized")
public class ContainerExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ContainerExecutionStrategy.class);

    private static final long TIMEOUT_SECONDS = 15;
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;

    private final String dockerBinary;

    public ContainerExecutionStrategy(
            org.springframework.core.env.Environment env) {
        this.dockerBinary = env.getProperty("collab.execution.docker-binary", "docker");
    }

    @Override
    public String name() {
        return "containerized";
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        if (!dockerIsUp()) {
            throw new ExecutionUnavailableException(
                    "Docker is not reachable from the backend. Start Docker Desktop, or set "
                  + "collab.execution.type to a strategy this host supports.");
        }

        Path workDir = Files.createTempDirectory("collabcode-exec-");
        long started = System.currentTimeMillis();
        try {
            for (ExecutionRequest.SourceFile f : request.files()) {
                Path target = workDir.resolve(f.path()).normalize();
                // Belt and braces: validation already rejected traversal, this
                // asserts containment so a future regression cannot escape.
                if (!target.startsWith(workDir)) {
                    throw new IllegalArgumentException("Path escaped the run directory: " + f.path());
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.content());
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(dockerBinary);
            cmd.add("run");
            cmd.add("--rm");
            cmd.add("--network");            cmd.add("none");
            cmd.add("--memory");             cmd.add("256m");
            cmd.add("--memory-swap");        cmd.add("256m");   // no swap escape hatch
            cmd.add("--cpus");               cmd.add("1");
            cmd.add("--pids-limit");         cmd.add("128");
            cmd.add("--cap-drop");           cmd.add("ALL");
            cmd.add("--security-opt");       cmd.add("no-new-privileges");
            cmd.add("--user");               cmd.add("1000:1000");
            cmd.add("--read-only");
            // Writable and EXECUTABLE. Docker force-adds `noexec` to --tmpfs unless
            // `exec` is given explicitly, which makes the compiled binary fail with
            // "Permission denied" even though it compiled fine. Verified: omitting
            // `exec` here yields a noexec mount and exit code 126.
            // Isolation comes from --network none, --cap-drop ALL, --read-only,
            // --user, and the memory/cpu/pids limits.
            cmd.add("--tmpfs");              cmd.add("/tmp:rw,exec,nosuid,nodev,size=64m");
            cmd.add("--workdir");            cmd.add("/src");
            cmd.add("-v");                   cmd.add(workDir.toAbsolutePath() + ":/src:ro");
            cmd.add(imageFor(request.language()));
            cmd.add("/bin/sh");
            cmd.add("-c");
            // Fixed per-language command. The entrypoint was already validated
            // against [A-Za-z0-9._-]+ and the language's extension, so it is safe
            // to interpolate here, and it is never quoted into a user-visible
            // shell fragment.
            cmd.add(buildCommand(request));

            log.info("docker exec: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().remove("DOCKER_HOST");
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            boolean[] truncated = {false};
            Thread reader = new Thread(() -> drain(process.getInputStream(), output, truncated));
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1_000);
                return new ExecutionResult(
                        "Error: execution timed out after " + TIMEOUT_SECONDS + "s",
                        -1, System.currentTimeMillis() - started, false);
            }
            reader.join(2_000);

            String text = output.toString();
            if (truncated[0]) {
                text = text + "\n... output truncated at " + (MAX_OUTPUT_CHARS / 1024) + " KB ...";
            }
            return new ExecutionResult(text, process.exitValue(),
                    System.currentTimeMillis() - started, truncated[0]);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Drains the process output into a bounded buffer.
     *
     * Reads a fixed-size char chunk rather than via BufferedReader.readLine(),
     * because a single output "line" can be arbitrarily large: readLine() would
     * materialise it in full before the cap was ever consulted, which is exactly
     * the memory-exhaustion case the cap exists to prevent.
     *
     * Once the cap is reached the stream is left unread on purpose - the caller
     * destroys the process on timeout, which closes it.
     */
    private static void drain(InputStream in, StringBuilder out, boolean[] truncated) {
        char[] buf = new char[8192];
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int n;
            while ((n = r.read(buf)) != -1) {
                synchronized (out) {
                    int remaining = MAX_OUTPUT_CHARS - out.length();
                    if (remaining <= 0) {
                        truncated[0] = true;
                        return;
                    }
                    if (n <= remaining) {
                        out.append(buf, 0, n);
                    } else {
                        out.append(buf, 0, remaining);
                        truncated[0] = true;
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
            // Stream closed by destroyForcibly(); expected on timeout.
        }
    }

    private static String imageFor(String lang) {
        return switch (lang) {
            case "cpp", "c++" -> "gcc:12";
            case "py", "python" -> "python:3.12-slim";
            case "java" -> "eclipse-temurin:17-jdk";
            default -> throw new IllegalArgumentException("No image for language: " + lang);
        };
    }

    private static String buildCommand(ExecutionRequest r) {
        // The entrypoint may live in a folder ("src/main.cpp"), so compile the
        // full path but key the output/class off the basename.
        String entry = r.entrypoint();
        String base = entry.substring(entry.lastIndexOf('/') + 1);
        return switch (r.language()) {
            case "cpp", "c++" -> "g++ -std=c++17 /src/" + entry + " -o /tmp/main && /tmp/main";
            case "py", "python" -> "python3 /src/" + entry;
            case "java" ->
                    "javac -d /tmp /src/" + entry + " && java -cp /tmp "
                  + base.replace(".java", "");
            default -> throw new IllegalArgumentException("No command for language: " + r.language());
        };
    }

    /**
     * Docker reachability is cached: `docker info` is a real RPC to the daemon and
     * can be slow or briefly fail under load, which turned a healthy host into a
     * spurious 503. A capability does not change between two requests seconds apart.
     */
    private volatile Boolean dockerUp;
    private volatile long dockerCheckedAt;

    private boolean dockerIsUp() {
        long now = System.currentTimeMillis();
        Boolean cached = dockerUp;
        if (cached != null && now - dockerCheckedAt < 30_000) {
            return cached;
        }
        boolean up = probeDocker();
        dockerUp = up;
        dockerCheckedAt = now;
        if (!up) {
            log.warn("Docker is not reachable from the backend");
        }
        return up;
    }

    private boolean probeDocker() {
        try {
            Process p = new ProcessBuilder(dockerBinary, "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
