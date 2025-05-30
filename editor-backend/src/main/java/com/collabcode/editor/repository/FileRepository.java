package com.collabcode.editor.repository;

import com.collabcode.editor.model.FileEntity;
import com.collabcode.editor.model.FileId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, FileId> {
    List<FileEntity> findAllByIdSessionId(String sessionId);
}
