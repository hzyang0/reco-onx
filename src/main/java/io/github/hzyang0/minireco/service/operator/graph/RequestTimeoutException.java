package io.github.hzyang0.minireco.service.operator.graph;

public final class RequestTimeoutException extends RuntimeException {
    public RequestTimeoutException(String message) {
        super(message);
    }
}
