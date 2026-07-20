package com.interview.minireco.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AdminRequestAuthenticator {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AdminAuthConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<String, Long> usedNonces = new ConcurrentHashMap<>();

    public AdminRequestAuthenticator(AdminAuthConfig config) {
        this(config, Clock.systemUTC());
    }

    AdminRequestAuthenticator(AdminAuthConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public AdminPrincipal authenticate(
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            AdminPermission requiredPermission
    ) {
        if (!config.enabled()) {
            return new AdminPrincipal("auth-disabled", "LOCAL");
        }

        String keyId = requiredHeader(headers, "X-Admin-Key-Id");
        String timestampRaw = requiredHeader(headers, "X-Admin-Timestamp");
        String nonce = requiredHeader(headers, "X-Admin-Nonce");
        String signatureRaw = requiredHeader(headers, "X-Admin-Signature");
        if (!nonce.matches("[A-Za-z0-9._-]{16,128}")) {
            throw unauthorized("invalid nonce");
        }

        AdminCredential credential = config.credential(keyId);
        if (credential == null) {
            throw unauthorized("unknown key id");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampRaw);
        } catch (NumberFormatException e) {
            throw unauthorized("invalid timestamp");
        }
        long now = Instant.now(clock).getEpochSecond();
        if (Math.abs(now - timestamp) > config.maxSkewSeconds()) {
            throw unauthorized("stale timestamp");
        }

        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(signatureRaw);
        } catch (IllegalArgumentException e) {
            throw unauthorized("invalid signature encoding");
        }
        String canonical = canonical(method, path, rawQuery, timestampRaw, nonce);
        byte[] expected = hmac(credential.secret(), canonical);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw unauthorized("invalid signature");
        }
        if (!credential.permissions().contains(requiredPermission)) {
            throw new AdminAuthException(403, "permission denied for " + requiredPermission);
        }

        evictExpired(now);
        String replayKey = keyId + ":" + nonce;
        Long previous = usedNonces.putIfAbsent(replayKey, now + config.maxSkewSeconds());
        if (previous != null && previous >= now) {
            throw unauthorized("replayed nonce");
        }
        return new AdminPrincipal(keyId, credential.role());
    }

    public static String sign(
            String secret,
            String method,
            String path,
            String rawQuery,
            String timestamp,
            String nonce
    ) {
        return HexFormat.of().formatHex(hmac(secret, canonical(method, path, rawQuery, timestamp, nonce)));
    }

    private static String canonical(String method, String path, String rawQuery, String timestamp, String nonce) {
        return method.toUpperCase(Locale.ROOT) + "\n"
                + path + "\n"
                + (rawQuery == null ? "" : rawQuery) + "\n"
                + timestamp + "\n"
                + nonce;
    }

    private static byte[] hmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC is unavailable", e);
        }
    }

    private void evictExpired(long now) {
        usedNonces.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private String requiredHeader(Map<String, String> headers, String name) {
        String value = headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (value == null || value.isBlank()) {
            throw unauthorized("missing header " + name);
        }
        return value;
    }

    private AdminAuthException unauthorized(String message) {
        return new AdminAuthException(401, message);
    }
}
