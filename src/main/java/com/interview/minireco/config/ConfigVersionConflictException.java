package com.interview.minireco.config;

public class ConfigVersionConflictException extends RuntimeException {
    public ConfigVersionConflictException(long expected, long actual) {
        super("expectedVersion=" + expected + " does not match currentVersion=" + actual);
    }
}
