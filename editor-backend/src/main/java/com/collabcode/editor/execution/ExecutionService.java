package com.collabcode.editor.execution;

import com.collabcode.editor.model.CodeSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Entry point for code execution.
 *
 * Validates the request once, at the boundary, then hands it to whichever
 * {@link ExecutionStrategy} the environment selected. The controller depends on
 * this class rather than on a concrete strategy, so switching strategies is a
 * configuration change rather than a code change.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionStrategy strategy;

    public ExecutionService(ExecutionStrategy strategy) {
        this.strategy = strategy;
        log.info("Code execution strategy: {}", strategy.name());
    }

    public String strategyName() {
        return strategy.name();
    }

    /** Whether the selected strategy can run code on this host right now. */
    public boolean strategyAvailable() {
        return strategy.available();
    }

    public ExecutionResult execute(CodeSession request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("Empty execution request.");
        }

        List<ExecutionRequest.SourceFile> files = request.getFiles() == null
                ? List.of()
                : request.getFiles().stream()
                        .map(f -> new ExecutionRequest.SourceFile(f.getPath(), f.getContent()))
                        .toList();

        ExecutionRequest validated =
                ExecutionRequest.of(request.getLanguage(), request.getEntrypoint(), files);

        return strategy.execute(validated);
    }
}
