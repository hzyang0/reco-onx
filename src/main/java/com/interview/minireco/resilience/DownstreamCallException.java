package com.interview.minireco.resilience;

public class DownstreamCallException extends RuntimeException {
    public DownstreamCallException(String source, Throwable cause) {
        super(source + " recall failed", cause);
    }
}
