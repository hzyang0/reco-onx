package com.interview.minireco.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminRequestAuthenticatorTest {
    private static final String RELEASE_SECRET = "release-secret-123456789";
    private static final String OPS_SECRET = "operations-secret-123456";
    private static final String NOW = "1770000000";

    private AdminRequestAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        AdminAuthConfig config = AdminAuthConfig.fromMap(Map.of(
                "ADMIN_RELEASE_KEY_ID", "release-bot",
                "ADMIN_RELEASE_SECRET", RELEASE_SECRET,
                "ADMIN_OPS_KEY_ID", "ops-bot",
                "ADMIN_OPS_SECRET", OPS_SECRET,
                "ADMIN_AUTH_MAX_SKEW_SECONDS", "60"
        ));
        authenticator = new AdminRequestAuthenticator(
                config,
                Clock.fixed(Instant.ofEpochSecond(Long.parseLong(NOW)), ZoneOffset.UTC)
        );
    }

    @Test
    void shouldAuthenticateReleaseRequest() {
        Map<String, String> headers = signedHeaders(
                "release-bot", RELEASE_SECRET, NOW, "nonce-release-0001", "/api/config", "expectedVersion=1"
        );

        AdminPrincipal principal = authenticator.authenticate(
                "POST", "/api/config", "expectedVersion=1", headers, AdminPermission.CONFIG_WRITE
        );

        assertEquals("release-bot", principal.keyId());
        assertEquals("RELEASE", principal.role());
    }

    @Test
    void shouldRejectMissingSignature() {
        AdminAuthException error = assertThrows(AdminAuthException.class, () -> authenticator.authenticate(
                "POST", "/api/config", "", Map.of(), AdminPermission.CONFIG_WRITE
        ));
        assertEquals(401, error.status());
    }

    @Test
    void shouldRejectTamperedQuery() {
        Map<String, String> headers = signedHeaders(
                "release-bot", RELEASE_SECRET, NOW, "nonce-tampered-001", "/api/config", "expectedVersion=1"
        );
        AdminAuthException error = assertThrows(AdminAuthException.class, () -> authenticator.authenticate(
                "POST", "/api/config", "expectedVersion=2", headers, AdminPermission.CONFIG_WRITE
        ));
        assertEquals(401, error.status());
    }

    @Test
    void shouldRejectStaleTimestamp() {
        String stale = Long.toString(Long.parseLong(NOW) - 61);
        Map<String, String> headers = signedHeaders(
                "release-bot", RELEASE_SECRET, stale, "nonce-stale-000001", "/api/config", ""
        );
        AdminAuthException error = assertThrows(AdminAuthException.class, () -> authenticator.authenticate(
                "POST", "/api/config", "", headers, AdminPermission.CONFIG_WRITE
        ));
        assertEquals(401, error.status());
    }

    @Test
    void shouldRejectReplay() {
        Map<String, String> headers = signedHeaders(
                "release-bot", RELEASE_SECRET, NOW, "nonce-replayed-001", "/api/config", ""
        );
        authenticator.authenticate("POST", "/api/config", "", headers, AdminPermission.CONFIG_WRITE);

        AdminAuthException error = assertThrows(AdminAuthException.class, () -> authenticator.authenticate(
                "POST", "/api/config", "", headers, AdminPermission.CONFIG_WRITE
        ));
        assertEquals(401, error.status());
    }

    @Test
    void shouldEnforceRolePermissions() {
        Map<String, String> headers = signedHeaders(
                "ops-bot", OPS_SECRET, NOW, "nonce-ops-denied01", "/api/config", ""
        );
        AdminAuthException error = assertThrows(AdminAuthException.class, () -> authenticator.authenticate(
                "POST", "/api/config", "", headers, AdminPermission.CONFIG_WRITE
        ));
        assertEquals(403, error.status());
    }

    @Test
    void shouldAllowUnsignedLocalModeWhenNoSecretsAreConfigured() {
        AdminRequestAuthenticator local = new AdminRequestAuthenticator(
                AdminAuthConfig.fromMap(Map.of()),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );
        AdminPrincipal principal = local.authenticate(
                "POST", "/rollout", "newPercent=5", Map.of(), AdminPermission.ROLLOUT_WRITE
        );
        assertEquals("LOCAL", principal.role());
    }

    private Map<String, String> signedHeaders(
            String keyId,
            String secret,
            String timestamp,
            String nonce,
            String path,
            String query
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Admin-Key-Id", keyId);
        headers.put("X-Admin-Timestamp", timestamp);
        headers.put("X-Admin-Nonce", nonce);
        headers.put("X-Admin-Signature", AdminRequestAuthenticator.sign(
                secret, "POST", path, query, timestamp, nonce
        ));
        return headers;
    }
}
