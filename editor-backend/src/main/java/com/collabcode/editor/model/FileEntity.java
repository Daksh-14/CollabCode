package com.collabcode.editor.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "files")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileEntity {

    @EmbeddedId
    private FileId id;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    public FileEntity(String sessionId, String path, String content) {
        this.id = new FileId(sessionId, path);
        this.content = content;
    }
}
