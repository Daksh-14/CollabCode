package com.collabcode.editor.controller;

import com.collabcode.editor.execution.ExecutionResult;
import com.collabcode.editor.execution.ExecutionService;
import com.collabcode.editor.execution.ExecutionStrategy.ExecutionUnavailableException;
import com.collabcode.editor.model.CodeSession;
import com.collabcode.editor.model.FileEntity;
import com.collabcode.editor.model.SessionEntity;
import com.collabcode.editor.service.FileService;
import com.collabcode.editor.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Errors are logged here with their stack trace and returned to the client as
 * JSON. Previously every endpoint caught Exception and returned
 * `500 + e.getMessage()`, which discarded the stack trace and left nothing in
 * the logs to diagnose from.
 *
 * The message is still surfaced so local development stays convenient, but it is
 * logged rather than swallowed.
 */
@RestController
@RequestMapping("/api/sessions")
@CrossOrigin
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;
    private final FileService fileService;
    private final ExecutionService executionService;

    public SessionController(SessionService sessionService, FileService fileService, ExecutionService executionService) {
        this.sessionService = sessionService;
        this.fileService = fileService;
        this.executionService = executionService;
    }

    @PostMapping
    public ResponseEntity<?> createSession(@RequestBody Map<String, String> payload) {
        try {
            SessionEntity session = sessionService.createSession(payload.get("name"));
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            log.error("Failed to create session", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create session", "detail", describe(e)));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        try {
            Optional<SessionEntity> session = sessionService.getSessionById(id);
            return session.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Failed to fetch session {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch session", "detail", describe(e)));
        }
    }

    @PutMapping("/{id}/rename")
    public ResponseEntity<?> renameSession(@PathVariable String id, @RequestParam String newName) {
        try {
            Optional<SessionEntity> sessionOpt = sessionService.getSessionById(id);
            if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();

            SessionEntity session = sessionOpt.get();
            session.setSessionName(newName);
            return ResponseEntity.ok(sessionService.saveSession(session));
        } catch (Exception e) {
            log.error("Failed to rename session {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to rename session", "detail", describe(e)));
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<?> saveFiles(@RequestBody List<FileEntity> files) {
        try {
            List<FileEntity> savedFiles = fileService.saveFiles(files);
            return ResponseEntity.ok(savedFiles);
        } catch (Exception e) {
            log.error("Failed to save {} files", files == null ? 0 : files.size(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to save files", "detail", describe(e)));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable String id) {
        try {
            sessionService.deleteSession(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete session {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to delete session", "detail", describe(e)));
        }
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executeSession(@RequestBody CodeSession request) {
        try {
            ExecutionResult result = executionService.execute(request);
            return ResponseEntity.ok(result.output());
        } catch (IllegalArgumentException e) {
            // Malformed request - the client's fault, and the message is actionable.
            log.warn("Rejected execution request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (ExecutionUnavailableException e) {
            // Environment cannot run code at all - a configuration problem.
            log.warn("Execution unavailable: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Code execution failed (language={}, entrypoint={})",
                    request == null ? null : request.getLanguage(),
                    request == null ? null : request.getEntrypoint(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Execution failed", "detail", describe(e)));
        }
    }

    /** Which execution strategy is active, and whether the host can actually run code. */
    @GetMapping("/execute/health")
    public ResponseEntity<?> executionHealth() {
        return ResponseEntity.ok(Map.of(
                "strategy", executionService.strategyName(),
                "supportedLanguages", List.of("java", "py", "cpp")));
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}