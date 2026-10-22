package com.collabcode.editor.execution;

import java.util.List;

/**
 * Outcome of running a user's code.
 *
 * @param output     combined stdout+stderr, possibly truncated
 * @param exitCode   process exit code, or -1 if it never ran
 * @param durationMs wall-clock time spent
 * @param truncated  true when output hit the cap and was cut short
 */
public record ExecutionResult(
        String output,
        int exitCode,
        long durationMs,
        boolean truncated
) {
    public boolean succeeded() {
        return exitCode == 0;
    }
}
