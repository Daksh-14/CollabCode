package com.collabcode.editor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeSession {
    private String sessionId;
    private String language;
    private String entrypoint;
    private List<CodeFile> files;
}
