package com.collabcode.editor.controller;

import com.collabcode.editor.model.CodeFile;
import com.collabcode.editor.model.CodeSession;
import com.collabcode.editor.model.FileEntity;
import com.collabcode.editor.model.SessionEntity;
import com.collabcode.editor.service.DockerExecutionService;
import com.collabcode.editor.service.FileService;
import com.collabcode.editor.service.SandboxExecutionService;
import com.collabcode.editor.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sessions")
@CrossOrigin
public class SessionController {

    private final SessionService sessionService;
    private final FileService fileService;
    private final DockerExecutionService dockerExecutionService;
    private final SandboxExecutionService sandboxExecutionService;

    public SessionController(SessionService sessionService, FileService fileService, DockerExecutionService dockerExecutionService,SandboxExecutionService sandboxExecutionService) {
        this.sessionService = sessionService;
        this.fileService = fileService;
        this.dockerExecutionService = dockerExecutionService;
        this.sandboxExecutionService= sandboxExecutionService;
    }

    @PostMapping
    public ResponseEntity<?> createSession(@RequestBody Map<String, String> payload) {
        try {
            SessionEntity session = sessionService.createSession(payload.get("name"));
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to create session: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        try {
            Optional<SessionEntity> session = sessionService.getSessionById(id);
            return session.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to fetch session: " + e.getMessage());
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
            return ResponseEntity.internalServerError().body("Failed to rename session: " + e.getMessage());
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<?> saveFiles(@RequestBody List<FileEntity> files) {
        try {
            List<FileEntity> savedFiles = fileService.saveFiles(files);
            return ResponseEntity.ok(savedFiles);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to save files: " + e.getMessage());
        }
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable String id) {
        try {
            sessionService.deleteSession(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to delete session: " + e.getMessage());
        }
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executeSession(@RequestBody CodeSession request) {
        try {
            String result = sandboxExecutionService.executeCode(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Execution failed: " + e.getMessage());
        }
    }
}
