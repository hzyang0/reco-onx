package com.interview.minireco.resilience;

public class DownstreamTimeoutException extends RuntimeException {
    public DownstreamTimeoutException(String source, long timeoutMs) {
        super(source + " recall timed out after " + timeoutMs + "ms");
    }
}
