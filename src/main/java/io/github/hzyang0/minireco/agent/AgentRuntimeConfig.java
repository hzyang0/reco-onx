package io.github.hzyang0.minireco.agent;

/** Runtime switch. Local mode is safe and fully functional without a model key. */
public record AgentRuntimeConfig(
        String mode,
        String baseUrl,
        String model,
        String apiKey,
        int shortMemoryTtlHours,
        int maxToolSteps
) {
    public static AgentRuntimeConfig fromEnvironment() {
        return new AgentRuntimeConfig(
                env("AGENT_MODE", "local"),
                env("LLM_BASE_URL", "https://api.openai.com/v1"),
                env("LLM_MODEL", "gpt-4o-mini"),
                env("LLM_API_KEY", ""),
                positive("AGENT_SHORT_MEMORY_TTL_HOURS", 24),
                positive("AGENT_MAX_TOOL_STEPS", 4)
        );
    }

    public boolean llmEnabled() {
        return "openai_compatible".equalsIgnoreCase(mode) && !apiKey.isBlank();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int positive(String key, int fallback) {
        int value = Integer.parseInt(env(key, String.valueOf(fallback)));
        if (value < 1 || value > 48) throw new IllegalArgumentException(key + " must be between 1 and 48");
        return value;
    }
}
