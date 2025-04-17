package com.collabcode.editor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class CodeExecutionRequest {
    private String language;
    private String entrypoint;
    private List<CodeFile> files;

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class CodeFile{
        private String filename;
        private String content;
    }
}
