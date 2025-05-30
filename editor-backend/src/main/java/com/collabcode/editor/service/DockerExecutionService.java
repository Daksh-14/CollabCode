package com.collabcode.editor.service;

import com.collabcode.editor.model.CodeSession;
import com.collabcode.editor.model.CodeFile;
import com.collabcode.editor.util.FileUtils;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

@Service
public class DockerExecutionService {

    public String executeCode(CodeSession request) throws IOException, InterruptedException {
        String folderId = UUID.randomUUID().toString();
        Path tempDir = Files.createTempDirectory("code-" + UUID.randomUUID().toString().replace("-", ""));
        StringBuilder output = new StringBuilder();

        try {
            for (CodeFile file : request.getFiles()) {
                Path filePath = tempDir.resolve(file.getPath());
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, file.getContent());
            }


            String image = getDockerImage(request.getLanguage());
            if (image == null) return "Unsupported language: " + request.getLanguage();

            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

            String volumePath = tempDir.toAbsolutePath().toString();
            if (isWindows) {
                volumePath = volumePath.replace("/", "\\");
            } else {
                volumePath = volumePath.replace("\\", "/");
            }
            String dockerCmd = String.format(
                    "docker run --rm -v \"%s:/app\" -w /app %s bash -c \"%s\"",
                    volumePath, image, getRunCommand(request.getLanguage(), request.getEntrypoint())
            );

            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd.exe", "/c", dockerCmd)
                    : new ProcessBuilder("bash", "-c", dockerCmd);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();

        } finally {
            FileUtils.deleteDirectory(tempDir);
        }

        return output.toString();
    }

    private String getDockerImage(String language) {
        return switch (language.toLowerCase()) {
            case "java" -> "java-runner";
            case "py" -> "python-runner";
            case "cpp" -> "cpp-runner";
            default -> null;
        };
    }

    private String getRunCommand(String language, String entrypoint) {
        return switch (language.toLowerCase()) {
            case "java" -> "find . -name \"*.java\" | xargs javac && java " + entrypoint.replace(".java", "");
            case "py" -> String.format("python %s", entrypoint);
            case "cpp" -> "g++ -std=c++17 -o main $(find . -name \"*.cpp\") && ./main";
            default -> "";
        };
    }
}
