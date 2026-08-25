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
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates intent parsing, recommendation tools, deterministic filtering and grounded explanation. */
public final class RecommendationAgentService {
    private static final int MAX_HISTORY = 8;
    private final RecommendationFacade recommendationFacade;
    private final JdbcDataRepository repository;
    private final AgentIntentParser intentParser;
    private final Map<String, SessionMemory> sessions = new ConcurrentHashMap<>();

    public RecommendationAgentService(
            RecommendationFacade recommendationFacade,
            JdbcDataRepository repository,
            AgentIntentParser intentParser
    ) {
        this.recommendationFacade = recommendationFacade;
        this.repository = repository;
        this.intentParser = intentParser;
    }

    public Map<String, Object> chat(long userId, String sessionId, String message) {
        ensureUser(userId);
        String resolvedSessionId = sessionId == null || sessionId.isBlank() ? "web-" + userId : sessionId;
        SessionMemory memory = sessions.computeIfAbsent(resolvedSessionId, ignored -> new SessionMemory());
        AgentIntent intent = intentParser.parse(userId, message, memory.lastScene);
        MetricsRegistry.global().increment("agent.chat.request", Map.of("scene", intent.scene()));
        List<String> tools = new ArrayList<>(List.of("get_user_profile", "recommend"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agent", "mini-reco-agent");
        response.put("mode", "local-tool-agent");
        response.put("sessionId", resolvedSessionId);
        response.put("intent", intentToMap(intent));
        if (intent.needsClarification()) {
            MetricsRegistry.global().increment("agent.chat.clarification", Map.of("scene", intent.scene()));
            remember(memory, message, intent.scene());
            response.put("tools", tools);
            response.put("answer", intent.clarificationQuestion());
            response.put("items", List.of());
            return response;
        }

        RecommendResponse raw = recommendationFacade.recommend(new RecommendRequest(userId, intent.scene(), 20));
        tools.add("filter_candidates");
        List<Item> items = applyConstraints(raw.getItems(), intent);
        tools.add("generate_grounded_explanation");
        remember(memory, message, intent.scene());
        response.put("tools", tools);
        response.put("recommendationRequestId", raw.getRequestId());
        response.put("answer", explain(intent, items));
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

    private void remember(SessionMemory memory, String message, String scene) {
        memory.lastScene = scene;
        memory.messages.add(message == null ? "" : message);
        while (memory.messages.size() > MAX_HISTORY) memory.messages.remove(0);
    }

    private static final class SessionMemory {
        private String lastScene = "mall";
        private final List<String> messages = new ArrayList<>();
    }
}
