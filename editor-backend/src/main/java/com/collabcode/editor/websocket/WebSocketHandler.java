package com.collabcode.editor.websocket;

import com.collabcode.editor.service.FileService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Component
public class WebSocketHandler extends TextWebSocketHandler {

    @Value("${upstash.data.redis.url}")
    private String redisUrl;

    @Value("${upstash.data.redis.password}")
    private String redisPassword;
    private static final Logger log = LoggerFactory.getLogger(WebSocketHandler.class);
    private static final String CHANNEL = "collabcode-updates";

    private final FileService fileService;
    private final HashOperations<String, String, String> hashOps;
    private final Jedis subscriberClient;
    private final JedisPubSub pubsub;
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor();

    private final Map<String, Set<WebSocketSession>> roomClients = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, String> sessionIds = new ConcurrentHashMap<>();
    private final Set<String> modifiedFiles = ConcurrentHashMap.newKeySet();

    @Autowired
    public WebSocketHandler(FileService fileService,
                            RedisTemplate<String, String> redisTemplate) {
        this.fileService = fileService;
        this.hashOps = redisTemplate.opsForHash();

        this.pubsub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msg = new ObjectMapper()
                            .readValue(message, new TypeReference<>() {});
                    String sessionId = (String) msg.get("sessionId");
                    String senderId  = (String) msg.get("senderId");

                    roomClients.getOrDefault(sessionId, Set.of()).forEach(ws -> {
                        String clientId = sessionIds.get(ws);
                        if (ws.isOpen() && !Objects.equals(clientId, senderId)) {
                            try {
                                ws.sendMessage(new TextMessage(message));
                            } catch (IOException ioe) {
                                log.warn("Failed to send WS message to client {}", clientId, ioe);
                            }
                        }
                    });
                } catch (Exception ex) {
                    log.error("Failed to handle pub/sub message", ex);
                }
            }
        };

        this.subscriberClient = new Jedis(redisUrl);
        this.subscriberClient.auth(redisPassword);

        saver.scheduleAtFixedRate(this::flushModifiedFiles, 10, 10, TimeUnit.SECONDS);

        subscriberExecutor.submit(this::runSubscriberLoop);
    }

    private void runSubscriberLoop() {
        int attempts = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                subscriberClient.subscribe(pubsub, CHANNEL);
            } catch (Exception e) {
                attempts++;
                long backoff = Math.min(30, attempts * 5);
                log.warn("Redis subscribe failed (attempt {}), retrying in {}s", attempts, backoff, e);
                try { TimeUnit.SECONDS.sleep(backoff); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @PostConstruct
    public void initSubscriber() {
        log.info("WebSocketHandler initialized, starting Redis subscriber thread");
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down WebSocketHandler...");

        pubsub.unsubscribe();
        subscriberExecutor.shutdownNow();

        saver.shutdownNow();

        subscriberClient.close();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);
        sessionIds.put(session, UUID.randomUUID().toString());
        roomClients
                .computeIfAbsent(sessionId, id -> ConcurrentHashMap.newKeySet())
                .add(session);

        String redisKey = "session:" + sessionId + ":files";
        Map<String, String> fileMap = hashOps.entries(redisKey);
        if (fileMap.isEmpty()) {
            fileService.getFilesBySessionId(sessionId).forEach(fe -> {
                String path = fe.getId().getPath();
                String content = fe.getContent();
                fileMap.put(path, content);
                hashOps.put(redisKey, path, content);
            });
        }

        Map<String, Object> initMsg = Map.of("type", "init", "files", fileMap);
        session.sendMessage(new TextMessage(new ObjectMapper().writeValueAsString(initMsg)));
        log.info("WS connection established for session {}", sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = new ObjectMapper()
                    .readValue(message.getPayload(), new TypeReference<>() {});
            String type      = (String) msg.get("type");
            String sessionId = (String) msg.get("sessionId");
            String senderId  = sessionIds.get(session);
            switch (type) {
                case "edit"       -> handleEdit(sessionId, msg, senderId);
                case "createFile" -> handleCreateFile(sessionId, msg, senderId);
                case "deleteFile" -> handleDeleteFile(sessionId, msg, senderId);
                case "renameFile" -> handleRenameFile(sessionId, msg, senderId);
                case "cursor"     -> publish(sessionId, msg, senderId);
                default           -> log.warn("Unknown message type: {}", type);
            }
        } catch (IOException ex) {
            log.error("Failed to parse incoming WS message", ex);
        } catch (Exception ex) {
            log.error("Error handling WS message", ex);
        }
    }

    private void handleEdit(String sessionId, Map<String,Object> msg, String senderId) {
        String filename = (String) msg.get("filename");
        String content  = (String) msg.get("content");
        String redisKey = "session:" + sessionId + ":files";

        hashOps.put(redisKey, filename, content);
        modifiedFiles.add(sessionId + "::" + filename);

        publish(sessionId, msg, senderId);
    }

    private void handleCreateFile(String sessionId, Map<String,Object> msg, String senderId) {
        String filename = (String) msg.get("filename");
        String redisKey = "session:" + sessionId + ":files";

        hashOps.put(redisKey, filename, "");
        fileService.saveOrUpdateFile(sessionId, filename, "");

        msg.put("type","add");
        msg.put("content","");
        publish(sessionId, msg, senderId);
    }

    private void handleDeleteFile(String sessionId, Map<String,Object> msg, String senderId) {
        String filename = (String) msg.get("filename");
        String redisKey = "session:" + sessionId + ":files";

        hashOps.delete(redisKey, filename);
        fileService.deleteFile(sessionId, filename);

        msg.put("type","delete");
        publish(sessionId, msg, senderId);
    }

    private void handleRenameFile(String sessionId, Map<String,Object> msg, String senderId) {
        String oldName = (String) msg.get("oldName");
        String newName = (String) msg.get("newName");
        String redisKey = "session:" + sessionId + ":files";

        String content = hashOps.get(redisKey, oldName);
        if (content != null) {
            hashOps.delete(redisKey, oldName);
            hashOps.put(redisKey, newName, content);
            fileService.renameFile(sessionId, oldName, newName);
        }
    }

    private void publish(String sessionId, Map<String,Object> msg, String senderId) {
        msg.put("sessionId", sessionId);
        msg.put("senderId", senderId);
        publishRaw(msg);
    }

    private void publishRaw(Map<String,Object> msg) {
        try (Jedis p = new Jedis(redisUrl)) {
            p.auth(redisPassword);
            p.publish(CHANNEL, new ObjectMapper().writeValueAsString(msg));
        } catch (Exception ex) {
            log.error("Failed to publish WS message", ex);
        }
    }

    private void flushModifiedFiles() {
        for (String key : List.copyOf(modifiedFiles)) {
            String[] parts   = key.split("::", 2);
            String sessionId = parts[0];
            String filename  = parts[1];
            String redisKey  = "session:" + sessionId + ":files";
            String content   = hashOps.get(redisKey, filename);
            if (content != null) {
                try {
                    fileService.saveOrUpdateFile(sessionId, filename, content);
                } catch (Exception e) {
                    log.error("Failed to flush file {} for session {}", filename, sessionId, e);
                }
            }
            modifiedFiles.remove(key);
        }
    }

    private String extractSessionId(WebSocketSession session) {
        String uri = session.getUri().getQuery();
        for (String pair : uri.split("&")) {
            if (pair.startsWith("sessionId=")) {
                return pair.substring("sessionId=".length());
            }
        }
        throw new IllegalArgumentException("Missing sessionId in query: " + uri);
    }
}
