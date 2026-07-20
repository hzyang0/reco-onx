package com.interview.minireco.security;

public class AdminAuthException extends RuntimeException {
    private final int status;

    AdminAuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
