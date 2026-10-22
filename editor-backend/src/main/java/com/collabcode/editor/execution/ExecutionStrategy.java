package com.collabcode.editor.execution;

/**
 * Strategy for running untrusted user code.
 *
 * Implementations differ in how they isolate the process, not in the contract.
 * Every implementation MUST:
 *   - never build a shell command string from user input (use an argv list)
 *   - enforce a wall-clock timeout and an output cap
 *   - run as a non-root user with no network access
 *
 * Selection is via {@code collab.execution.type}.
 */
public interface ExecutionStrategy {

    /** Stable identifier, reported by the health endpoint. */
    String name();

    /**
     * Runs the request.
     *
     * @throws IllegalArgumentException if the request is malformed (client error)
     * @throws ExecutionUnavailableException if this strategy cannot run here
     */
    ExecutionResult execute(ExecutionRequest request) throws Exception;

    /** Thrown when the strategy itself is not usable in the current environment. */
    class ExecutionUnavailableException extends RuntimeException {
        public ExecutionUnavailableException(String message) {
            super(message);
        }
    }
}
