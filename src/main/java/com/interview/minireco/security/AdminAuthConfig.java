package com.interview.minireco.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class AdminAuthConfig {
    private static final int MIN_SECRET_LENGTH = 16;

    private final Map<String, AdminCredential> credentials;
    private final long maxSkewSeconds;

    private AdminAuthConfig(Map<String, AdminCredential> credentials, long maxSkewSeconds) {
        this.credentials = Map.copyOf(credentials);
        this.maxSkewSeconds = maxSkewSeconds;
    }

    public static AdminAuthConfig fromEnvironment() {
        return fromMap(System.getenv());
    }

    public static AdminAuthConfig fromMap(Map<String, String> env) {
        long skew = parsePositiveLong(env.get("ADMIN_AUTH_MAX_SKEW_SECONDS"), 60);
        Map<String, AdminCredential> credentials = new LinkedHashMap<>();
        addCredential(credentials, env.get("ADMIN_RELEASE_KEY_ID"), env.get("ADMIN_RELEASE_SECRET"),
                "RELEASE", Set.of(AdminPermission.CONFIG_WRITE, AdminPermission.ROLLOUT_WRITE));
        addCredential(credentials, env.get("ADMIN_OPS_KEY_ID"), env.get("ADMIN_OPS_SECRET"),
                "OPS", Set.of(AdminPermission.DEGRADATION_WRITE, AdminPermission.RESILIENCE_WRITE,
                        AdminPermission.CACHE_RESET));
        return new AdminAuthConfig(credentials, skew);
    }

    public boolean enabled() {
        return !credentials.isEmpty();
    }

    AdminCredential credential(String keyId) {
        return credentials.get(keyId);
    }

    public long maxSkewSeconds() {
        return maxSkewSeconds;
    }

    public Map<String, Object> safeSnapshot() {
        return Map.of(
                "enabled", enabled(),
                "keyIds", credentials.keySet().stream().sorted().toList(),
                "maxSkewSeconds", maxSkewSeconds
        );
    }

    private static void addCredential(
            Map<String, AdminCredential> target,
            String keyId,
            String secret,
            String role,
            Set<AdminPermission> permissions
    ) {
        if (secret == null || secret.isBlank()) {
            return;
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("admin key id is required when its secret is configured");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("admin secret must contain at least " + MIN_SECRET_LENGTH + " characters");
        }
        AdminCredential previous = target.put(keyId, new AdminCredential(keyId, secret, role, permissions));
        if (previous != null) {
            throw new IllegalArgumentException("duplicate admin key id: " + keyId);
        }
    }

    private static long parsePositiveLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        long value = Long.parseLong(raw);
        if (value <= 0) {
            throw new IllegalArgumentException("ADMIN_AUTH_MAX_SKEW_SECONDS must be positive");
        }
        return value;
    }
}
