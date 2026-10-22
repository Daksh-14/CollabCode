package com.collabcode.editor.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 * unavailable on Windows (including under WSL in practice) and on Docker
 * Desktop for Windows.
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

    @Override
    public String name() {
        return "firejail";
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        String shell = resolveShell();
        if (shell == null) {
            throw new ExecutionUnavailableException(
                    "No POSIX shell found. firejail execution needs a Linux host; "
                  + "set collab.execution.type=containerized if Docker is available.");
        }
        if (!hasFirejail(shell)) {
            throw new ExecutionUnavailableException(
                    "firejail is not installed inside '" + shell + "'. Install it "
                  + "(e.g. 'sudo apt install firejail'), or set collab.execution.type=containerized.");
        }

        Path workDir = Files.createTempDirectory("collabcode-exec-");
        long started = System.currentTimeMillis();
        try {
            for (ExecutionRequest.SourceFile f : request.files()) {
                Path target = workDir.resolve(f.path()).normalize();
                if (!target.startsWith(workDir)) {
                    throw new IllegalArgumentException("Path escaped the run directory: " + f.path());
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.content());
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(shell);
            cmd.add("-c");
            cmd.add(buildCommand(request, workDir));

            log.info("firejail exec in {}", workDir);

            Process process = new ProcessBuilder(cmd)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> drain(process, output));
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

            synchronized (output) {
                boolean truncated = output.length() >= MAX_OUTPUT_CHARS;
                String text = output + (truncated ? "\n... output truncated ..." : "");
                return new ExecutionResult(text, process.exitValue(),
                        System.currentTimeMillis() - started, truncated);
            }
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static void drain(Process process, StringBuilder output) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (output) {
                    if (output.length() >= MAX_OUTPUT_CHARS) return;
                    output.append(line).append('\n');
                }
            }
        } catch (Exception ignored) {
            // stream closed by destroyForcibly; expected on timeout
        }
    }

    private static String buildCommand(ExecutionRequest r, Path workDir) {
        String entry = r.entrypoint();
        String base = entry.substring(entry.lastIndexOf('/') + 1);
        String limits = "firejail --private --net=none --rlimit-cpu=2 --rlimit-as=268435456 --quiet";
        return switch (r.language()) {
            case "cpp", "c++" -> limits + " /bin/sh -c \"g++ -std=c++17 " + entry
                    + " -o /tmp/main && /tmp/main\"";
            case "py", "python" -> limits + " /bin/sh -c \"python3 " + entry + "\"";
            case "java" -> limits + " /bin/sh -c \"javac -d /tmp " + entry
                    + " && java -cp /tmp " + base.replace(".java", "") + "\"";
            default -> throw new IllegalArgumentException("No command for language: " + r.language());
        };
    }

    private static String resolveShell() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (onPath("wsl.exe")) return "wsl.exe";
            if (onPath("bash.exe")) return "bash.exe";
            return null;
        }
        return onPath("bash") ? "bash" : null;
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (new java.io.File(dir, exe).canExecute()) return true;
        }
        return false;
    }

    private static boolean hasFirejail(String shell) {
        try {
            Process p = new ProcessBuilder(shell, "-c", "command -v firejail")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return p.exitValue() == 0 && r.readLine() != null;
            }
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
