package com.collabcode.editor.execution;

import java.util.Locale;

/**
 * The languages this backend can execute.
 *
 * An enum rather than a free-form string: the value selects the compiler and the
 * command that is built for it. A string would let an unknown value reach a
 * switch, and a typo would surface as a runtime crash instead of a 400.
 */
public enum Language {

    JAVA("java", ".java", "eclipse-temurin:17-jdk"),
    PYTHON("python", ".py", "python:3.12-slim"),
    CPP("cpp", ".cpp", "gcc:12");

    private final String id;
    private final String extension;
    private final String image;

    Language(String id, String extension, String image) {
        this.id = id;
        this.extension = extension;
        this.image = image;
    }

    /** Canonical identifier reported back to clients. */
    public String id() {
        return id;
    }

    /** Extension an entrypoint must carry to be runnable in this language. */
    public String extension() {
        return extension;
    }

    /** Docker image used by the containerized strategy. */
    public String image() {
        return image;
    }

    /** Accepts the aliases the frontend already sends ("py", "c++"). */
    public static Language fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("No language specified. Use a file extension.");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "java" -> JAVA;
            case "py", "python" -> PYTHON;
            case "cpp", "c++", "cxx" -> CPP;
            default -> throw new IllegalArgumentException(
                    "Unsupported language: '" + raw + "'. Supported: java, py, cpp.");
        };
    }
}
