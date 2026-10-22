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
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

@Component
public class WebSocketHandler extends TextWebSocketHandler {

    @Value("${spring.data.redis.url}")
    private String redisUrl;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    private static final Logger log = LoggerFactory.getLogger(WebSocketHandler.class);
    private static final String CHANNEL = "collabcode-updates";

    private final FileService fileService;
    private final HashOperations<String, String, String> hashOps;
    private final JedisPubSub pubsub;
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor();

    /** Created in {@link #initSubscriber()}; @Value fields are not populated until after construction. */
    private volatile Jedis subscriberClient;

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
    }

    private void runSubscriberLoop() {
        int attempts = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Jedis client = subscriberClient;
                if (client == null) {
                    // Startup could not reach Redis (degraded boot). Try again here
                    // so the app recovers on its own once Redis comes back.
                    client = buildSubscriberClient();
                    subscriberClient = client;
                    log.info("Redis subscriber client (re)established");
                }
                client.subscribe(pubsub, CHANNEL);
            } catch (Exception e) {
                attempts++;
                long backoff = Math.min(30, attempts * 5);
                // The client is likely in an unknown state after a failure.
                Jedis stale = subscriberClient;
                subscriberClient = null;
                if (stale != null) {
                    try { stale.close(); } catch (Exception ignored) { }
                }
                log.warn("Redis subscribe failed (attempt {}), retrying in {}s", attempts, backoff, e);
                try { TimeUnit.SECONDS.sleep(backoff); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @PostConstruct
    public void initSubscriber() {
        log.info("WebSocketHandler initialised, connecting Redis subscriber to {}", redisUrl);

        // Never let a Redis outage kill the application context. The subscriber
        // loop already retries with backoff, so the correct behaviour is to start
        // degraded and recover, not to refuse to boot. (Throwing here also meant
        // the retry loop never got to run - the very code that would have fixed
        // the outage was skipped by the failure.)
        this.subscriberClient = buildSubscriberClientQuietly();

        saver.scheduleAtFixedRate(this::flushModifiedFiles, 10, 10, TimeUnit.SECONDS);
        subscriberExecutor.submit(this::runSubscriberLoop);
    }

    private Jedis buildSubscriberClientQuietly() {
        try {
            return buildSubscriberClient();
        } catch (Exception e) {
            log.error("Could not connect to Redis at startup. The app will start degraded and "
                    + "the subscriber will keep retrying.", e);
            return null;
        }
    }

    private Jedis buildSubscriberClient() {
        URI uri = URI.create(redisUrl);
        Jedis client = new Jedis(uri);
        // Credentials may already be embedded in the URI (Upstash rediss:// style);
        // only issue an explicit AUTH when they are not.
        if (uri.getUserInfo() == null && redisPassword != null && !redisPassword.isBlank()) {
            client.auth(redisPassword);
        }
        return client;
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down WebSocketHandler...");

        pubsub.unsubscribe();
        subscriberExecutor.shutdownNow();
        saver.shutdownNow();

        Jedis client = subscriberClient;
        if (client != null) {
            client.close();
        }
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
        String payload;
        try {
            payload = new ObjectMapper().writeValueAsString(msg);
        } catch (Exception ex) {
            log.error("Failed to serialise WS message", ex);
            return;
        }

        try (Jedis p = new Jedis(URI.create(redisUrl))) {
            URI uri = URI.create(redisUrl);
            boolean credentialsInUri = uri.getUserInfo() != null;
            if (!credentialsInUri && redisPassword != null && !redisPassword.isBlank()) {
                p.auth(redisPassword);
            }
            p.publish(CHANNEL, payload);
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
