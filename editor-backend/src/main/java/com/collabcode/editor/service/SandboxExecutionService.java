package com.collabcode.editor.service;

import com.collabcode.editor.model.CodeSession;
import com.collabcode.editor.model.CodeFile;
import com.collabcode.editor.util.FileUtils;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class SandboxExecutionService {

    public String executeCode(CodeSession request) throws Exception {
        Path tempDir = Files.createTempDirectory("sess-" + UUID.randomUUID());
        try {
            for (CodeFile f : request.getFiles()) {
                if (!f.getPath().equals(request.getEntrypoint())) continue;
                Path p = tempDir.resolve(f.getPath());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.getContent());
            }

            String lang = request.getLanguage().toLowerCase();
            String entry = request.getEntrypoint();
            String firejailCmd;

            switch (lang) {
                case "java":
                    System.out.println("reached here");
                    firejailCmd = String.join(" ",
                            "firejail --private --net=none --rlimit-cpu=2 --rlimit-as=268435456 --quiet",
                            "bash -c \"javac", entry, "&& java -cp", tempDir.toAbsolutePath().toString(),
                            entry.replace(".java",""), "\""
                    );
                    break;
                case "py":
                case "python":
                    firejailCmd = String.join(" ",
                            "firejail --private --net=none --rlimit-cpu=2 --rlimit-as=268435456 --quiet",
                            "bash -c \"python3", entry, "\""
                    );
                    break;
                case "cpp":
                case "c++":
                    firejailCmd = String.join(" ",
                            "firejail --private --net=none --rlimit-cpu=2 --rlimit-as=268435456 --quiet",
                            "bash -c \"g++ -std=c++17", entry, "-o main && ./main\""
                    );
                    break;
                default:
                    return "Unsupported language: " + request.getLanguage();
            }

            Process process = new ProcessBuilder("bash", "-c", firejailCmd)
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "Error: execution timed out";
            }

            StringBuilder out = new StringBuilder();
            try (BufferedReader rdr = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = rdr.readLine()) != null) {
                    out.append(line).append("\n");
                }
            }

            return out.toString();
        } finally {
            FileUtils.deleteDirectory(tempDir);
        }
    }
}
