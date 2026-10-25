package com.collabcode.editor.controller;

import com.collabcode.editor.execution.DockerProbe;
import com.collabcode.editor.execution.ExecutionService;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports which execution strategy actually bound and whether its dependencies
 * are reachable.
 *
 * The strategy varies by environment (firejail on a Linux host, containers on a
 * Docker host), so "why did Run fail?" must be answerable from outside without
 * reading the logs or guessing.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class HealthController {

    private final ExecutionService executionService;
    private final DockerProbe dockerProbe;
    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    public HealthController(ExecutionService executionService,
                            DockerProbe dockerProbe,
                            DataSource dataSource,
                            RedisConnectionFactory redisConnectionFactory) {
        this.executionService = executionService;
        this.dockerProbe = dockerProbe;
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean dbConnected = dbConnected();
        boolean redisConnected = redisConnected();
        boolean executionAvailable = executionService.strategyAvailable();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionType", executionService.strategyName());
        body.put("executionAvailable", executionAvailable);
        body.put("dockerAvailable", dockerProbe.isAvailable());
        body.put("redisConnected", redisConnected);
        body.put("dbConnected", dbConnected);
        // Execution is what the user cares about; Redis is the collaboration
        // channel and the DB is the session store, so all three are required.
        body.put("status", executionAvailable && redisConnected && dbConnected ? "ok" : "degraded");
        return body;
    }

    private boolean dbConnected() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean redisConnected() {
        RedisConnection connection = null;
        try {
            connection = redisConnectionFactory.getConnection();
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // Already closed.
                }
            }
        }
    }
}
