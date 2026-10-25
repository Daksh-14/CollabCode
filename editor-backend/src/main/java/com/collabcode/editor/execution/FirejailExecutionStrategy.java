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
 * Runs code inside a firejail sandbox on a Linux host.
 *
 * firejail needs a Linux kernel with working namespaces, so this strategy is
 * unavailable on Windows and on Docker Desktop for Windows.
 *
 * Enabled by default; set {@code collab.execution.type=containerized} on a
 * Docker-capable host.
 */
@Component
@ConditionalOnProperty(name = "collab.execution.type", havingValue = "firejail", matchIfMissing = true)
public class FirejailExecutionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(FirejailExecutionStrategy.class);

    private static final long TIMEOUT_SECONDS = 15;
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;
    private static final long AVAILABILITY_CACHE_MILLIS = 60_000;

    /*
     * Compile-time constants. The entrypoint is passed as "$1" and the Java class
     * name as "$2" - separate argv elements, never spliced into the script text.
     */
    private static final String SCRIPT_CPP =
            "set -e; g++ -std=c++17 \"$1\" -o /tmp/main; exec /tmp/main";
    private static final String SCRIPT_PYTHON =
            "exec python3 \"$1\"";
    private static final String SCRIPT_JAVA =
            "set -e; javac -d /tmp \"$1\"; exec java -cp /tmp \"$2\"";

    private volatile Boolean availableCache;
    private volatile long availableCheckedAt;

    @Override
    public String name() {
        return "firejail";
    }

    @Override
    public boolean available() {
        long now = System.currentTimeMillis();
        Boolean cached = availableCache;
        if (cached != null && now - availableCheckedAt < AVAILABILITY_CACHE_MILLIS) {
            return cached;
        }
        boolean available = probeFirejail();
        availableCache = available;
        availableCheckedAt = now;
        return available;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        if (!available()) {
            throw new ExecutionUnavailableException(
                    "firejail is not available on this host. Install it "
                  + "(e.g. 'sudo apt install firejail') on Linux, or set "
                  + "EXECUTION_TYPE=containerized where Docker is available.");
        }

        Path workDir = Files.createTempDirectory("collabcode-exec-");
        long started = System.currentTimeMillis();
        try {
            for (ExecutionRequest.SourceFile file : request.files()) {
                ExecutionPathValidator.writeSourceFile(workDir, file.path(), file.content());
            }

            List<String> command = buildArgv(request);
            log.info("firejail exec in {}: {}", workDir, String.join(" ", command));

            Process process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            boolean[] truncated = {false};
            Thread reader = new Thread(() -> drain(process.getInputStream(), output, truncated));
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                // firejail forks a child sandbox process; killing only the parent
                // would leave it running. Take the whole tree down.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
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
     * firejail is invoked directly - no shell wraps it. The only shell involved is
     * the one firejail runs to sequence compile-then-run, and its script is a
     * constant; the entrypoint arrives as a separate positional argument.
     */
    private static List<String> buildArgv(ExecutionRequest request) {
        List<String> cmd = new ArrayList<>();
        cmd.add("firejail");
        cmd.add("--private");
        cmd.add("--net=none");
        cmd.add("--rlimit-cpu=2");
        cmd.add("--rlimit-as=268435456");
        cmd.add("--quiet");
        cmd.add("/bin/sh");
        cmd.add("-c");
        cmd.add(scriptFor(request));
        cmd.add("sh");
        cmd.add(request.entrypoint());
        if (request.language() == Language.JAVA) {
            // javac writes <Class>.class; java takes a class name, not a path.
            cmd.add(request.baseName().replace(".java", ""));
        }
        return cmd;
    }

    private static String scriptFor(ExecutionRequest request) {
        return switch (request.language()) {
            case CPP -> SCRIPT_CPP;
            case PYTHON -> SCRIPT_PYTHON;
            case JAVA -> SCRIPT_JAVA;
        };
    }

    private static boolean probeFirejail() {
        try {
            Process process = new ProcessBuilder("firejail", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            // Not on PATH (e.g. Windows): cannot run here.
            return false;
        }
    }

    /**
     * Bounded chunked read. readLine() would buffer an unbounded single line
     * before the cap could be applied, which is the memory-exhaustion case this
     * cap exists to prevent.
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
            // stream closed by destroyForcibly; expected on timeout
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
