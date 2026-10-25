package com.collabcode.editor.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Cached "is the Docker daemon reachable?" probe.
 *
 * {@code docker info} is a real RPC to the daemon: it can be slow, and it can
 * fail transiently under load. Probing once per request turned a healthy host
 * into spurious 503s, so the answer is cached briefly. A capability does not
 * change between two requests seconds apart.
 *
 * Shared by the containerized strategy and the health endpoint so both report
 * the same thing.
 */
@Component
public class DockerProbe {

    private static final Logger log = LoggerFactory.getLogger(DockerProbe.class);
    private static final long CACHE_MILLIS = 30_000;

    private final String dockerBinary;
    private volatile Boolean cached;
    private volatile long checkedAt;

    public DockerProbe(@Value("${collab.execution.docker-binary:docker}") String dockerBinary) {
        this.dockerBinary = dockerBinary;
    }

    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        Boolean value = cached;
        if (value != null && now - checkedAt < CACHE_MILLIS) {
            return value;
        }
        boolean up = probe();
        cached = up;
        checkedAt = now;
        if (!up) {
            log.warn("Docker is not reachable from the backend");
        }
        return up;
    }

    private boolean probe() {
        try {
            Process process = new ProcessBuilder(
                    dockerBinary, "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
