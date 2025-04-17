package com.collabcode.editor.controller;

import com.collabcode.editor.model.CodeExecutionRequest;
import com.collabcode.editor.service.DockerExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/execute")
@CrossOrigin
public class CodeExecutionController {

    private final DockerExecutionService dockerExecutionService;

    public CodeExecutionController(DockerExecutionService dockerExecutionService) {
        this.dockerExecutionService = dockerExecutionService;
    }

    @PostMapping
    public ResponseEntity<?> executeCode(@RequestBody CodeExecutionRequest request) {
        try {
            String result = dockerExecutionService.executeCode(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Execution failed: " + e.getMessage());
        }
    }
}
