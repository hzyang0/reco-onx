package com.interview.minireco.degradation;

public final class UserLayer {
    private UserLayer() {
    }

    public static int bucket(long userId) {
        return Math.floorMod(userId, 100);
    }
}
