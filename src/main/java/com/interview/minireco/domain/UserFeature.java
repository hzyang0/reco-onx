package com.interview.minireco.domain;

public class UserFeature {
    private final long userId;
    private final boolean newUser;
    private final String preferredCategory;
    private final int age;

    public UserFeature(long userId, boolean newUser, String preferredCategory, int age) {
        this.userId = userId;
        this.newUser = newUser;
        this.preferredCategory = preferredCategory;
        this.age = age;
    }

    public long getUserId() {
        return userId;
    }

    public boolean isNewUser() {
        return newUser;
    }

    public String getPreferredCategory() {
        return preferredCategory;
    }

    public int getAge() {
        return age;
    }
}
