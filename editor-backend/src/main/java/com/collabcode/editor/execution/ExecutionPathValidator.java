package com.collabcode.editor.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The single traversal guard shared by every {@link ExecutionStrategy}.
 *
 * Folders are a supported feature of the editor, so {@code /} itself is allowed:
 * {@code src/main.cpp} is a legitimate entrypoint. What is rejected is everything
 * that can escape the run directory or be reinterpreted by a shell:
 *
 * <ul>
 *   <li>absolute paths and trailing separators</li>
 *   <li>{@code ..} and {@code .} segments - traversal, at any depth</li>
 *   <li>backslashes (a separator on Windows) and NUL bytes (truncate in syscalls)</li>
 *   <li>shell metacharacters, so the historical RCE in the old implementation
 *       cannot return through a filename</li>
 * </ul>
 *
 * Validation is per segment, not on the whole string. Checking
 * "does the path start with something safe" would accept
 * {@code src/../../../etc/passwd}, because the first segment is harmless; the
 * {@code ..} can appear anywhere.
 */
public final class ExecutionPathValidator {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern METACHARS =
            Pattern.compile(".*[;&|`$<>{}\\[\\]\"':*?!#%^,+=~\\\\].*");
    private static final int MAX_PATH_LENGTH = 512;

    private ExecutionPathValidator() {
    }

    /**
     * True when {@code path} is a relative sequence of plain-name segments.
     *
     * Note that the segment pattern permits '.', so it matches {@code .} and
     * {@code ..} by itself. The explicit check after the pattern is what makes
     * dots-as-traversal illegal; the pattern alone is not sufficient.
     */
    public static boolean isValid(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        if (path.startsWith("/") || path.endsWith("/")) {
            return false;
        }
        if (path.contains("\\") || path.contains("\0")) {
            return false;
        }
        if (METACHARS.matcher(path).matches()) {
            return false;
        }
        // limit -1 keeps trailing/empty segments so "a//b" cannot collapse into
        // "a/b" and slip an empty segment past the check.
        for (String segment : path.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                return false;
            }
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a validated relative path against {@code runDir}, asserting that
     * the result stays inside it.
     *
     * String validation already makes escape impossible; this is the
     * filesystem-level assertion that catches a future regression, and the place
     * to fail if the rules are ever loosened carelessly.
     */
    public static Path resolveInside(Path runDir, String relativePath) {
        if (!isValid(relativePath)) {
            throw new IllegalArgumentException(
                    "Illegal file path: '" + relativePath
                  + "'. Use plain names like 'main.py' or 'src/main.py'.");
        }
        Path resolved = runDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(runDir)) {
            throw new IllegalArgumentException("Path escaped the run directory: " + relativePath);
        }
        return resolved;
    }

    /**
     * Writes one submitted source file into the run directory, creating parent
     * folders as needed.
     *
     * Refuses a pre-existing symlink at the target and asserts the resolved
     * parent still lives under the run directory, so a symlinked component
     * cannot redirect the write outside it.
     */
    public static void writeSourceFile(Path runDir, String relativePath, String content)
            throws IOException {
        Path target = resolveInside(runDir, relativePath);
        Path parent = target.getParent();
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("Refusing to write through a symlink: " + relativePath);
        }
        if (!parent.toRealPath().startsWith(runDir.toRealPath())) {
            throw new IllegalArgumentException("Path escaped the run directory: " + relativePath);
        }
        Files.writeString(target, content == null ? "" : content);
    }
}
