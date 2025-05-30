package com.collabcode.editor.service;

import com.collabcode.editor.model.SessionEntity;
import com.collabcode.editor.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {

    private final SessionRepository sessionRepository;

    public SessionService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public SessionEntity createSession(String sessionName) {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setSessionName(sessionName);
        session.setCreatedAt(Instant.now());
        return sessionRepository.save(session);
    }

    public Optional<SessionEntity> getSessionById(String id) {
        return sessionRepository.findById(id);
    }

    public List<SessionEntity> getAllSessions() {
        return sessionRepository.findAll();
    }

    public void deleteSession(String id) {
        sessionRepository.deleteById(id);
    }

    public SessionEntity saveSession(SessionEntity session) {
        return sessionRepository.save(session);
    }
}
