package io.github.hzyang0.minireco.agent;

import io.github.hzyang0.minireco.domain.AttrName;
import io.github.hzyang0.minireco.domain.Item;
import io.github.hzyang0.minireco.domain.RecommendRequest;
import io.github.hzyang0.minireco.domain.RecommendResponse;
import io.github.hzyang0.minireco.observability.MetricsRegistry;
import io.github.hzyang0.minireco.service.RecommendationFacade;
import io.github.hzyang0.minireco.service.data.JdbcDataRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates intent parsing, recommendation tools, deterministic filtering and grounded explanation. */
public final class RecommendationAgentService {
    private static final int SHORT_CONTEXT_LIMIT = 8;
    private static final List<AgentToolDefinition> TOOL_DEFINITIONS = List.of(
            new AgentToolDefinition("get_user_profile", "Read the current user profile from MySQL.", List.of("userId")),
            new AgentToolDefinition("recommend", "Call the real recommendation DAG.", List.of("userId", "scene")),
            new AgentToolDefinition("filter_candidates", "Filter returned candidates by source, category, price and ad policy.", List.of()),
            new AgentToolDefinition("generate_grounded_explanation", "Explain only the filtered real candidates.", List.of())
    );
    private final RecommendationFacade recommendationFacade;
    private final JdbcDataRepository repository;
    private final AgentIntentParser intentParser;
    private final AgentRuntimeConfig config;
    private final AgentPlanner localPlanner;
    private final AgentPlanner llmPlanner;

    public RecommendationAgentService(
            RecommendationFacade recommendationFacade,
            JdbcDataRepository repository,
            AgentIntentParser intentParser
    ) {
        this(recommendationFacade, repository, intentParser, AgentRuntimeConfig.fromEnvironment());
    }

    public RecommendationAgentService(
            RecommendationFacade recommendationFacade,
            JdbcDataRepository repository,
            AgentIntentParser intentParser,
            AgentRuntimeConfig config
    ) {
        this.recommendationFacade = recommendationFacade;
        this.repository = repository;
        this.intentParser = intentParser;
        this.config = config;
        this.localPlanner = new RuleBasedAgentPlanner();
        this.llmPlanner = new OpenAiCompatibleAgentPlanner(config);
    }

    public Map<String, Object> chat(long userId, String sessionId, String message) {
        ensureUser(userId);
        String resolvedSessionId = sessionId == null || sessionId.isBlank() ? "web-" + userId : sessionId;
        List<JdbcDataRepository.AgentConversation> conversation = repository
                .findRecentAgentConversation(resolvedSessionId, SHORT_CONTEXT_LIMIT);
        Map<String, String> longTermMemory = repository.findAgentLongTermMemories(userId);
        String rememberedScene = longTermMemory.getOrDefault("last_scene", "mall");
        PlannerSelection selection = plan(userId, message, rememberedScene, longTermMemory, conversation);
        AgentIntent intent = selection.intent();
        MetricsRegistry.global().increment("agent.chat.request", Map.of("scene", intent.scene()));
        repository.appendAgentConversation(resolvedSessionId, userId, "user", message, config.shortMemoryTtlHours());
        List<String> tools = new ArrayList<>();
        List<Map<String, Object>> toolTrace = new ArrayList<>();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agent", "mini-reco-agent");
        response.put("mode", selection.planner().name());
        response.put("sessionId", resolvedSessionId);
        response.put("intent", intentToMap(intent));
        response.put("toolDefinitions", TOOL_DEFINITIONS.stream().map(AgentToolDefinition::toMap).toList());
        if (intent.needsClarification()) {
            MetricsRegistry.global().increment("agent.chat.clarification", Map.of("scene", intent.scene()));
            response.put("tools", tools);
            response.put("answer", intent.clarificationQuestion());
            response.put("items", List.of());
            repository.appendAgentConversation(resolvedSessionId, userId, "assistant", intent.clarificationQuestion(), config.shortMemoryTtlHours());
            return response;
        }

        JdbcDataRepository.UserProfile profile = repository.findUser(userId).orElseThrow();
        tools.add("get_user_profile");
        toolTrace.add(trace("get_user_profile", Map.of("userId", userId), Map.of("category", profile.defaultCategory(), "newUser", profile.newUser())));
        if (tools.size() >= config.maxToolSteps()) throw new IllegalStateException("agent tool step limit exceeded");
        RecommendResponse raw = recommendationFacade.recommend(new RecommendRequest(userId, intent.scene(), 20));
        tools.add("recommend");
        toolTrace.add(trace("recommend", Map.of("scene", intent.scene(), "limit", 20),
                Map.of("requestId", raw.getRequestId(), "candidateCount", raw.getItems().size())));
        List<Item> items = applyConstraints(raw.getItems(), intent);
        tools.add("filter_candidates");
        toolTrace.add(trace("filter_candidates", intentToMap(intent), Map.of("returnedCount", items.size())));
        tools.add("generate_grounded_explanation");
        String answer = explain(intent, items);
        toolTrace.add(trace("generate_grounded_explanation", Map.of("itemCount", items.size()), Map.of("grounded", true)));
        persistLongTermMemory(userId, intent);
        repository.appendAgentConversation(resolvedSessionId, userId, "assistant", answer, config.shortMemoryTtlHours());
        response.put("tools", tools);
        response.put("toolTrace", toolTrace);
        response.put("recommendationRequestId", raw.getRequestId());
        response.put("answer", answer);
        response.put("items", items.stream().map(this::itemToMap).toList());
        response.put("trace", Map.of("sourceRequestId", raw.getRequestId(), "costMs", raw.getCostMs(),
                "time", Instant.now().toString()));
        MetricsRegistry.global().increment("agent.chat.success", Map.of("scene", intent.scene()));
        return response;
    }

    public Map<String, Object> diagnose(long userId, String scene) {
        ensureUser(userId);
        String resolvedScene = scene == null || scene.isBlank() ? "mall" : scene;
        MetricsRegistry.global().increment("agent.diagnose.request", Map.of("scene", resolvedScene));
        RecommendResponse response = recommendationFacade.recommend(new RecommendRequest(userId, resolvedScene, 10));
        JdbcDataRepository.UserProfile profile = repository.findUser(userId).orElseThrow();
        List<JdbcDataRepository.UserEvent> events = repository.findEvents(userId);
        Map<String, Long> eventsByType = events.stream().collect(java.util.stream.Collectors.groupingBy(
                JdbcDataRepository.UserEvent::eventType, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        Map<String, Long> sources = response.getItems().stream().collect(java.util.stream.Collectors.groupingBy(
                Item::getSource, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        List<String> findings = new ArrayList<>();
        findings.add("用户当前偏好为 " + profile.defaultCategory() + "，新用户状态=" + profile.newUser() + "。");
        findings.add("本次 " + resolvedScene + " 场景返回 " + response.getItems().size() + " 条，来源分布=" + sources + "。");
        findings.add("最近行为共 " + events.size() + " 条，类型分布=" + eventsByType + "。");
        findings.add("三路召回明细=" + response.getDebug().getOrDefault("recallFanout", Map.of()) + "。");
        return Map.of(
                "agent", "mini-reco-diagnosis-agent", "mode", "grounded-diagnosis",
                "tools", List.of("get_user_profile", "get_user_events", "recommend", "read_recall_trace"),
                "userId", userId, "scene", resolvedScene, "requestId", response.getRequestId(),
                "findings", findings, "items", response.getItems().stream().map(this::itemToMap).toList(),
                "debug", response.getDebug()
        );
    }

    private List<Item> applyConstraints(List<Item> candidates, AgentIntent intent) {
        return candidates.stream()
                .filter(item -> !intent.excludeAds() || !"ad".equals(item.getSource()))
                .filter(item -> intent.preferredSource() == null || intent.preferredSource().equals(item.getSource()))
                .filter(item -> intent.preferredCategory() == null || intent.preferredCategory().equals(item.getCategory()))
                .filter(item -> fitsBudget(item, intent.maxPrice()))
                .sorted(Comparator.comparingDouble(Item::getScore).reversed())
                .limit(intent.limit())
                .toList();
    }

    private boolean fitsBudget(Item item, Integer maxPrice) {
        if (maxPrice == null || !"goods".equals(item.getSource())) return true;
        return item.findAttr(AttrName.PRICE).map(Integer::parseInt).map(price -> price <= maxPrice).orElse(false);
    }

    private String explain(AgentIntent intent, List<Item> items) {
        if (items.isEmpty()) {
            return "已调用真实推荐链路，但没有候选同时满足当前约束。可以放宽预算、换一个品类，或允许其他内容类型。";
        }
        Item top = items.get(0);
        String reason = "top item=" + top.getTitle() + "，来源=" + top.getSource() + "，品类=" + top.getCategory();
        if (intent.maxPrice() != null) reason += "，已按预算 " + intent.maxPrice() + " 过滤";
        return "已通过用户画像、推荐 DAG 和候选过滤工具得到 " + items.size() + " 条结果；" + reason + "。";
    }

    private Map<String, Object> intentToMap(AgentIntent intent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", intent.userId()); result.put("scene", intent.scene());
        result.put("preferredSource", intent.preferredSource()); result.put("preferredCategory", intent.preferredCategory());
        result.put("maxPrice", intent.maxPrice()); result.put("excludeAds", intent.excludeAds());
        result.put("limit", intent.limit()); result.put("needsClarification", intent.needsClarification());
        return result;
    }

    private Map<String, Object> itemToMap(Item item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", item.getItemId()); result.put("title", item.getTitle()); result.put("source", item.getSource());
        result.put("category", item.getCategory()); result.put("score", Math.round(item.getScore() * 1000.0) / 1000.0);
        Map<String, String> attrs = new LinkedHashMap<>();
        item.getAttrs().forEach((name, value) -> attrs.put(name.key(), value.getValue()));
        result.put("attrs", attrs);
        return result;
    }

    private void ensureUser(long userId) {
        if (repository.findUser(userId).isEmpty()) throw new IllegalArgumentException("user not found: " + userId);
    }

    private PlannerSelection plan(long userId, String message, String rememberedScene, Map<String, String> longTermMemory,
                                  List<JdbcDataRepository.AgentConversation> conversation) {
        List<String> context = conversation.stream().map(item -> item.role() + ": " + item.content()).toList();
        if (config.llmEnabled()) {
            try {
                return new PlannerSelection(llmPlanner, llmPlanner.plan(userId, message, rememberedScene, longTermMemory, TOOL_DEFINITIONS, context));
            } catch (IllegalStateException ignored) {
                MetricsRegistry.global().increment("agent.planner.fallback", Map.of("reason", "llm_unavailable"));
            }
        }
        return new PlannerSelection(localPlanner, localPlanner.plan(userId, message, rememberedScene, longTermMemory, TOOL_DEFINITIONS, context));
    }

    private void persistLongTermMemory(long userId, AgentIntent intent) {
        repository.upsertAgentLongTermMemory(userId, "last_scene", intent.scene(), 1.0, "agent_intent");
        if (intent.preferredCategory() != null) repository.upsertAgentLongTermMemory(userId, "preferred_category", intent.preferredCategory(), 0.8, "agent_intent");
        if (intent.preferredSource() != null) repository.upsertAgentLongTermMemory(userId, "preferred_source", intent.preferredSource(), 0.8, "agent_intent");
        if (intent.maxPrice() != null) repository.upsertAgentLongTermMemory(userId, "max_price", String.valueOf(intent.maxPrice()), 0.6, "agent_intent");
        if (intent.excludeAds()) repository.upsertAgentLongTermMemory(userId, "exclude_ads", "true", 0.8, "agent_intent");
    }

    private Map<String, Object> trace(String tool, Map<String, ?> arguments, Map<String, ?> summary) {
        return Map.of("tool", tool, "arguments", arguments, "resultSummary", summary, "status", "success");
    }

    private record PlannerSelection(AgentPlanner planner, AgentIntent intent) { }
}
