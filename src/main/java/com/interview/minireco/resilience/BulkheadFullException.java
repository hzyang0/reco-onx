package com.interview.minireco.resilience;

public class BulkheadFullException extends RuntimeException {
    public BulkheadFullException(String source, Throwable cause) {
        super(source + " recall bulkhead is full", cause);
    }
}
