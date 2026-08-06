package io.github.hzyang0.minireco.service.data;

/** Connection, pooling, migration and timeout settings supplied through environment variables. */
public record DatabaseConfig(
        String jdbcUrl,
        String username,
        String password,
        int maximumPoolSize,
        long connectionTimeoutMs,
        String flywayLocations
) {
    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3307/mini_reco"
            + "?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"
            + "&connectTimeout=2000&socketTimeout=1000";
    private static final String DEFAULT_USER = "mini_reco";
    private static final String DEFAULT_PASSWORD = "mini_reco";

    public static DatabaseConfig fromEnvironment() {
        return new DatabaseConfig(
                env("JDBC_URL", DEFAULT_URL),
                env("DB_USER", DEFAULT_USER),
                env("DB_PASSWORD", DEFAULT_PASSWORD),
                envInt("DB_POOL_SIZE", 12),
                envLong("DB_CONNECTION_TIMEOUT_MS", 2_000),
                env("FLYWAY_LOCATIONS", "filesystem:db/migration")
        );
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int envInt(String name, int defaultValue) {
        return Math.toIntExact(envLong(name, defaultValue));
    }

    private static long envLong(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        long value = Long.parseLong(raw);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
