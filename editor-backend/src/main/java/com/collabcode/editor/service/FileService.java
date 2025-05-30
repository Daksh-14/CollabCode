package com.collabcode.editor.service;

import com.collabcode.editor.model.CodeFile;
import com.collabcode.editor.model.FileEntity;
import com.collabcode.editor.model.FileId;
import com.collabcode.editor.repository.FileRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class FileService {

    private final FileRepository fileRepo;

    public FileService(FileRepository fileRepo) {
        this.fileRepo = fileRepo;
    }

    public List<CodeFile> getFilesForSession(String sessionId) {
        return fileRepo.findAllByIdSessionId(sessionId).stream()
                .map(fe -> new CodeFile(fe.getId().getPath(), fe.getContent()))
                .collect(Collectors.toList());
    }

    public List<FileEntity> getFilesBySessionId(String sessionId) {
        return fileRepo.findAllByIdSessionId(sessionId);
    }

    public FileEntity saveOrUpdateFile(String sessionId, String path, String content) {
        FileEntity fe = new FileEntity(sessionId, path, content);
        return fileRepo.save(fe);
    }

    public List<FileEntity> saveFiles(List<FileEntity> files) {
        return fileRepo.saveAll(files);
    }

    public Optional<FileEntity> getFile(String sessionId, String path) {
        return fileRepo.findById(new FileId(sessionId, path));
    }

    public void deleteFile(String sessionId, String path) {
        fileRepo.deleteById(new FileId(sessionId, path));
    }

    public void deleteAllFilesInSession(String sessionId) {
        fileRepo.findAllByIdSessionId(sessionId)
                .forEach(fe -> fileRepo.delete(fe));
    }

    public void renameFile(String sessionId, String oldPath, String newPath) {
        FileId oldId = new FileId(sessionId, oldPath);
        fileRepo.findById(oldId).ifPresent(fe -> {
            fileRepo.delete(fe);
            fileRepo.save(new FileEntity(sessionId, newPath, fe.getContent()));
        });
    }
}
