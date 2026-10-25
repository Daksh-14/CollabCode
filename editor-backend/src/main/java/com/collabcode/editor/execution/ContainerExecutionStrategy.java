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
import java.util.UUID;
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

    /*
     * These scripts are compile-time constants. The entrypoint is never
     * interpolated into them: it arrives as a positional parameter ("$1"), i.e.
     * a separate argv element. That removes the historical RCE where a crafted
     * filename could end the quoting and inject commands. The language's
     * extension is also fixed, so "$1" is always a path we validated.
     */
    private static final String SCRIPT_CPP =
            "set -e; g++ -std=c++17 \"$1\" -o /tmp/main; exec /tmp/main";
    private static final String SCRIPT_PYTHON =
            "exec python3 \"$1\"";
    private static final String SCRIPT_JAVA =
            "set -e; javac -d /tmp \"$1\"; exec java -cp /tmp \"$2\"";

    private final String dockerBinary;
    private final DockerProbe dockerProbe;

    public ContainerExecutionStrategy(
            org.springframework.core.env.Environment env, DockerProbe dockerProbe) {
        this.dockerBinary = env.getProperty("collab.execution.docker-binary", "docker");
        this.dockerProbe = dockerProbe;
    }

    @Override
    public String name() {
        return "containerized";
    }

    @Override
    public boolean available() {
        return dockerProbe.isAvailable();
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        if (!dockerProbe.isAvailable()) {
            throw new ExecutionUnavailableException(
                    "Docker is not reachable from the backend. Start Docker Desktop, or set "
                  + "collab.execution.type to a strategy this host supports.");
        }

        Path workDir = Files.createTempDirectory("collabcode-exec-");
        String containerName = "collabcode-exec-" + UUID.randomUUID();
        long started = System.currentTimeMillis();
        try {
            for (ExecutionRequest.SourceFile file : request.files()) {
                // The validator rejects traversal and refutes symlinks; this also
                // creates the parent folders so nested paths materialise.
                ExecutionPathValidator.writeSourceFile(workDir, file.path(), file.content());
            }

            List<String> command = buildArgv(request, workDir, containerName);
            log.info("docker exec: {}", String.join(" ", command));

            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().remove("DOCKER_HOST");
            Process process = builder.start();

            StringBuilder output = new StringBuilder();
            boolean[] truncated = {false};
            Thread reader = new Thread(() -> drain(process.getInputStream(), output, truncated));
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                // Killing the `docker` CLI does NOT stop the container: the daemon
                // owns it. Kill it by name, otherwise a runaway program keeps
                // consuming CPU after the request has returned.
                killContainer(containerName);
                process.destroyForcibly();
                reader.join(1_000);
                return new ExecutionResult(
                        "Error: execution timed out after " + TIMEOUT_SECONDS + "s",
                        -1, System.currentTimeMillis() - started, false);
            }
            reader.join(2_000);

            String text = output.toString();
            int exitCode = process.exitValue();
            if (text.isBlank() && exitCode == 137) {
                // 128 + SIGKILL. The kernel kills an over-limit process without
                // writing anything, and `exec` means no shell is left to print
                // "Killed", so say it here instead of returning empty output.
                text = "Process was killed (out of memory, or the process limit was reached).";
            }
            if (truncated[0]) {
                text = text + "\n... output truncated at " + (MAX_OUTPUT_CHARS / 1024) + " KB ...";
            }
            return new ExecutionResult(text, exitCode,
                    System.currentTimeMillis() - started, truncated[0]);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Builds the argument vector.
     *
     * Everything Docker-specific is a fixed flag; the only variable parts are the
     * entrypoint (a separate argv element) and the Java class name derived from
     * the entrypoint's basename. No user data is ever spliced into a string that
     * a shell later parses.
     */
    private List<String> buildArgv(ExecutionRequest request, Path workDir, String containerName) {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        // A unique name lets the timeout path stop a container the CLI no longer
        // controls.
        cmd.add("--name");         cmd.add(containerName);
        cmd.add("--network");      cmd.add("none");
        cmd.add("--memory");       cmd.add("256m");
        cmd.add("--memory-swap");  cmd.add("256m");   // no swap escape hatch
        cmd.add("--cpus");         cmd.add("1");
        cmd.add("--pids-limit");   cmd.add("128");
        cmd.add("--cap-drop");     cmd.add("ALL");
        cmd.add("--security-opt"); cmd.add("no-new-privileges");
        cmd.add("--user");         cmd.add("1000:1000");
        cmd.add("--read-only");
        // Writable and EXECUTABLE. Docker force-adds `noexec` to --tmpfs unless
        // `exec` is given explicitly, which makes the compiled binary fail with
        // "Permission denied" even though it compiled fine.
        cmd.add("--tmpfs");        cmd.add("/tmp:rw,exec,nosuid,nodev,size=64m");
        cmd.add("--workdir");      cmd.add("/src");
        cmd.add("-v");             cmd.add(workDir.toAbsolutePath() + ":/src:ro");
        cmd.add(request.language().image());
        cmd.add("/bin/sh");
        cmd.add("-c");

        String entry = "/src/" + request.entrypoint();
        switch (request.language()) {
            case CPP -> {
                cmd.add(SCRIPT_CPP);
                cmd.add("sh");
                cmd.add(entry);
            }
            case PYTHON -> {
                cmd.add(SCRIPT_PYTHON);
                cmd.add("sh");
                cmd.add(entry);
            }
            case JAVA -> {
                cmd.add(SCRIPT_JAVA);
                cmd.add("sh");
                cmd.add(entry);
                // javac writes /tmp/<Class>.class for a package-less class, and
                // java takes a class name, not a path.
                cmd.add(request.baseName().replace(".java", ""));
            }
        }
        return cmd;
    }

    /**
     * Drains the process output into a bounded buffer.
     *
     * Reads a fixed-size char chunk rather than via BufferedReader.readLine(),
     * because a single output "line" can be arbitrarily large: readLine() would
     * materialise it in full before the cap was ever consulted, which is exactly
     * the memory-exhaustion case the cap exists to prevent.
     */
    private static void drain(InputStream in, StringBuilder out, boolean[] truncated) {
        char[] buffer = new char[8192];
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int n;
            while ((n = reader.read(buffer)) != -1) {
                synchronized (out) {
                    int remaining = MAX_OUTPUT_CHARS - out.length();
                    if (remaining <= 0) {
                        truncated[0] = true;
                        return;
                    }
                    if (n <= remaining) {
                        out.append(buffer, 0, n);
                    } else {
                        out.append(buffer, 0, remaining);
                        truncated[0] = true;
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
            // Stream closed by destroyForcibly(); expected on timeout.
        }
    }

    /**
     * Stops a container by name. `--rm` removes it once it stops; the explicit
     * `rm -f` covers the case where `kill` lost a race with the container exiting.
     */
    private void killContainer(String containerName) {
        try {
            Process kill = new ProcessBuilder(dockerBinary, "kill", containerName)
                    .redirectErrorStream(true)
                    .start();
            if (!kill.waitFor(10, TimeUnit.SECONDS)) {
                kill.destroyForcibly();
            }
        } catch (Exception e) {
            log.warn("Could not kill container {}: {}", containerName, e.getMessage());
        }
        try {
            new ProcessBuilder(dockerBinary, "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception ignored) {
            // Already gone.
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
