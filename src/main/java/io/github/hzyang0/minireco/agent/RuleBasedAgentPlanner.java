package io.github.hzyang0.minireco.agent;

import java.util.List;
import java.util.Map;

/** Deterministic fallback planner used if an LLM key is absent or unavailable. */
public final class RuleBasedAgentPlanner implements AgentPlanner {
    private final AgentIntentParser parser = new AgentIntentParser();

    @Override
    public AgentIntent plan(long userId, String message, String rememberedScene, Map<String, String> longTermMemory,
                            List<AgentToolDefinition> tools, List<String> shortTermContext) {
        AgentIntent explicit = parser.parse(userId, message, rememberedScene);
        if (explicit.needsClarification()) return explicit;
        String category = explicit.preferredCategory() != null ? explicit.preferredCategory()
                : longTermMemory.get("preferred_category");
        String source = explicit.preferredSource() != null ? explicit.preferredSource()
                : longTermMemory.get("preferred_source");
        Integer budget = explicit.maxPrice() != null ? explicit.maxPrice() : integer(longTermMemory.get("max_price"));
        boolean excludeAds = explicit.excludeAds() || Boolean.parseBoolean(longTermMemory.get("exclude_ads"));
        return new AgentIntent(userId, explicit.scene(), source, category, budget, excludeAds,
                explicit.limit(), false, null);
    }

    private Integer integer(String raw) {
        try { return raw == null ? null : Integer.parseInt(raw); } catch (NumberFormatException ignored) { return null; }
    }

    @Override
    public String name() { return "rule-based-planner"; }
}
