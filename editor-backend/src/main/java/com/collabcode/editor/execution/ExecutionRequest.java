package com.collabcode.editor.execution;

import java.util.List;
import java.util.Locale;

/**
 * A validated request to run code.
 *
 * Construct through {@link #of} rather than the canonical constructor so that the
 * language, every file path, and the entrypoint are validated exactly once, at
 * the edge. Strategies can then trust the payload.
 */
public record ExecutionRequest(
        Language language,
        String entrypoint,
        List<SourceFile> files
) {
    public record SourceFile(String path, String content) {
    }

    public static ExecutionRequest of(String rawLanguage, String entrypoint, List<SourceFile> files) {
        Language language = Language.fromId(rawLanguage);

        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("No entrypoint specified. Set one file as Main.");
        }
        if (!ExecutionPathValidator.isValid(entrypoint)) {
            throw new IllegalArgumentException(
                    "Invalid entrypoint: '" + entrypoint
                  + "'. Use a plain path like main.py or src/main.py (folders are fine).");
        }
        // Match the language against the file NAME, not the full path, so that
        // "src/main.cpp" is a valid cpp entrypoint.
        if (!baseName(entrypoint).toLowerCase(Locale.ROOT).endsWith(language.extension())) {
            throw new IllegalArgumentException(
                    "Entrypoint '" + entrypoint + "' does not match language '" + language.id()
                  + "' (expected a " + language.extension() + " file).");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files supplied.");
        }

        List<SourceFile> safe = files.stream()
                .map(ExecutionRequest::validateFile)
                .toList();

        boolean entryPresent = safe.stream().anyMatch(f -> f.path().equals(entrypoint));
        if (!entryPresent) {
            throw new IllegalArgumentException(
                    "Entrypoint '" + entrypoint + "' is not among the submitted files.");
        }

        return new ExecutionRequest(language, entrypoint, safe);
    }

    /** File name of the entrypoint, used for compiler output and Java class names. */
    public String baseName() {
        return baseName(entrypoint);
    }

    private static SourceFile validateFile(SourceFile file) {
        String path = file.path();
        if (!ExecutionPathValidator.isValid(path)) {
            throw new IllegalArgumentException(
                    "Illegal file path: '" + path
                  + "'. Use plain names like 'main.py' or 'src/main.py'.");
        }
        return new SourceFile(path, file.content() == null ? "" : file.content());
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
