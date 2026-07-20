package com.interview.minireco.config;

public class ConfigPersistenceException extends RuntimeException {
    public ConfigPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigPersistenceException(String message) {
        super(message);
    }
}
