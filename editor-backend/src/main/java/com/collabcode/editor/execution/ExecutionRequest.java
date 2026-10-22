package com.collabcode.editor.execution;

import java.util.List;

/**
 * A validated request to run code.
 *
 * Construct through {@link ExecutionRequest#of} rather than the constructor so
 * that every path and the entrypoint are validated exactly once, at the edge.
 */
public record ExecutionRequest(
        String language,
        String entrypoint,
        List<SourceFile> files
) {
    public record SourceFile(String path, String content) {}

    private static final java.util.Set<String> ALLOWED_LANGUAGES =
            java.util.Set.of("java", "py", "python", "cpp", "c++");

    public static ExecutionRequest of(String language, String entrypoint, List<SourceFile> files) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("No language specified. Use a file extension.");
        }
        String lang = language.toLowerCase();
        if (!ALLOWED_LANGUAGES.contains(lang)) {
            throw new IllegalArgumentException(
                    "Unsupported language: '" + language + "'. Supported: java, py, cpp.");
        }
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("No entrypoint specified. Set one file as Main.");
        }
        if (!isValidPath(entrypoint)) {
            throw new IllegalArgumentException(
                    "Invalid entrypoint: '" + entrypoint
                  + "'. Use a plain path like main.py or src/main.py (folders are fine).");
        }
        // Validate the file NAME against the language, not the full path, so
        // "src/main.cpp" is accepted for cpp.
        if (!baseName(entrypoint).toLowerCase().endsWith(extensionFor(lang))) {
            throw new IllegalArgumentException(
                    "Entrypoint '" + entrypoint + "' does not match language '" + language
                  + "' (expected a " + extensionFor(lang) + " file).");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files supplied.");
        }

        List<SourceFile> safe = files.stream()
                .map(ExecutionRequest::validatePath)
                .toList();

        boolean entryPresent = safe.stream().anyMatch(f -> f.path().equals(entrypoint));
        if (!entryPresent) {
            throw new IllegalArgumentException(
                    "Entrypoint '" + entrypoint + "' is not among the submitted files.");
        }

        return new ExecutionRequest(lang, entrypoint, safe);
    }

    /**
     * A path is valid when every {@code /}-separated segment is a plain name.
     *
     * Folders are a supported feature (the file tree is a real tree), so
     * "src/main.cpp" must be allowed. Rejecting "/" wholesale was wrong - the
     * dangerous part is the traversal and metacharacters, not the separator.
     */
    private static final java.util.regex.Pattern SEGMENT =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private static boolean isValidPath(String p) {
        if (p == null || p.isBlank() || p.length() > 512) return false;
        if (p.startsWith("/") || p.endsWith("/") || p.contains("\\") || p.contains("\0")) return false;
        // Rejected outright: shell metacharacters and anything that could escape
        // the run directory once interpreted by a shell or a filesystem.
        if (p.matches(".*[;&|`$<>{}\\[\\]\"':*?!#%^,+=~\\\\].*")) return false;
        for (String seg : p.split("/", -1)) {
            if (!SEGMENT.matcher(seg).matches()) return false;
            if (seg.equals(".") || seg.equals("..")) return false;
        }
        return true;
    }

    private static SourceFile validatePath(SourceFile f) {
        String p = f.path();
        if (!isValidPath(p)) {
            throw new IllegalArgumentException(
                    "Illegal file path: '" + p + "'. Use plain names like 'main.py' or 'src/main.py'.");
        }
        return new SourceFile(p, f.content() == null ? "" : f.content());
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    public static String extensionFor(String lang) {
        return switch (lang) {
            case "java" -> ".java";
            case "py", "python" -> ".py";
            case "cpp", "c++" -> ".cpp";
            default -> "";
        };
    }
}
