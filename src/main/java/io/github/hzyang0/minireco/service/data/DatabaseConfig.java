package io.github.hzyang0.minireco.service.data;

/** Connection settings supplied through environment variables. */
public record DatabaseConfig(String jdbcUrl, String username, String password) {
    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3307/mini_reco"
            + "?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    private static final String DEFAULT_USER = "mini_reco";
    private static final String DEFAULT_PASSWORD = "mini_reco";

    public static DatabaseConfig fromEnvironment() {
        return new DatabaseConfig(
                env("JDBC_URL", DEFAULT_URL),
                env("DB_USER", DEFAULT_USER),
                env("DB_PASSWORD", DEFAULT_PASSWORD)
        );
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
