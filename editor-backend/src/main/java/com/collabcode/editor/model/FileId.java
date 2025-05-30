package com.collabcode.editor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

@Getter
@Setter
@Embeddable
public class FileId implements Serializable {
    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "path", nullable = false)
    private String path;

    protected FileId() { }
    public FileId(String sessionId, String path) {
        this.sessionId = sessionId;
        this.path      = path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileId)) return false;
        FileId that = (FileId) o;
        return Objects.equals(sessionId, that.sessionId)
                && Objects.equals(path,      that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, path);
    }
}
