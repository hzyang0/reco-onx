package io.github.hzyang0.minireco.service.data;

public final class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(long userId) {
        super("user not found: " + userId);
    }
}
