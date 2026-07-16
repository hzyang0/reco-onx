package com.interview.minireco.service.operator;

public class OperatorConfig {
    private final String name;
    private final boolean enabled;

    public OperatorConfig(String name, boolean enabled) {
        this.name = name;
        this.enabled = enabled;
    }

    public static OperatorConfig enabled(String name) {
        return new OperatorConfig(name, true);
    }

    public static OperatorConfig disabled(String name) {
        return new OperatorConfig(name, false);
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
