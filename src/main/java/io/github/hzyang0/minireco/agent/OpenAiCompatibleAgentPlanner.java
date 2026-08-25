package io.github.hzyang0.minireco.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible function-calling planner. Failures are handled by the local fallback planner. */
public final class OpenAiCompatibleAgentPlanner implements AgentPlanner {
    private final AgentRuntimeConfig config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleAgentPlanner(AgentRuntimeConfig config) { this.config = config; }

    @Override
    public AgentIntent plan(long userId, String message, String rememberedScene, Map<String, String> longTermMemory,
                            List<AgentToolDefinition> tools, List<String> shortTermContext) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", config.model());
            request.put("temperature", 0);
            request.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt(tools)),
                    Map.of("role", "user", "content", context(userId, message, rememberedScene, longTermMemory, shortTermContext))
            ));
            request.put("tools", List.of(functionSchema()));
            request.put("tool_choice", Map.of("type", "function", "function", Map.of("name", "plan_recommendation")));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(trimSlash(config.baseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("llm status " + response.statusCode());
            JsonNode arguments = mapper.readTree(response.body()).at("/choices/0/message/tool_calls/0/function/arguments");
            if (!arguments.isTextual()) throw new IllegalStateException("llm did not return tool arguments");
            return toIntent(userId, mapper.readTree(arguments.asText()), rememberedScene);
        } catch (Exception exception) {
            throw new IllegalStateException("llm planner unavailable", exception);
        }
    }

    private AgentIntent toIntent(long userId, JsonNode json, String rememberedScene) {
        String scene = allowedScene(text(json, "scene", rememberedScene));
        String source = nullableText(json, "preferredSource");
        if (source != null && !List.of("goods", "live", "ad").contains(source)) source = null;
        String category = nullableText(json, "preferredCategory");
        Integer price = json.hasNonNull("maxPrice") && json.get("maxPrice").canConvertToInt() ? json.get("maxPrice").asInt() : null;
        if (price != null && (price < 0 || price > 100_000)) price = null;
        int limit = json.path("limit").canConvertToInt() ? json.path("limit").asInt() : 5;
        limit = Math.max(1, Math.min(limit, 10));
        boolean clarification = json.path("needsClarification").asBoolean(false);
        return new AgentIntent(userId, scene, source, category, price, json.path("excludeAds").asBoolean(false),
                limit, clarification, clarification ? "为了给出更准确的推荐，请补充品类、预算或内容类型。" : null);
    }

    private Map<String, Object> functionSchema() {
        Map<String, Object> properties = Map.of(
                "scene", Map.of("type", "string", "enum", List.of("mall", "video_feed", "buy_first")),
                "preferredSource", Map.of("type", "string", "enum", List.of("goods", "live", "ad")),
                "preferredCategory", Map.of("type", "string"), "maxPrice", Map.of("type", "integer"),
                "excludeAds", Map.of("type", "boolean"), "limit", Map.of("type", "integer"),
                "needsClarification", Map.of("type", "boolean")
        );
        return Map.of("type", "function", "function", Map.of("name", "plan_recommendation",
                "description", "Convert the recommendation request into validated constraints.",
                "parameters", Map.of("type", "object", "properties", properties, "required", List.of("scene", "excludeAds", "limit", "needsClarification"))));
    }

    private String systemPrompt(List<AgentToolDefinition> tools) {
        return "You are a planner only. Never invent products, call no external actions, and use plan_recommendation. "
                + "Available backend tools after planning: " + tools + ".";
    }

    private String context(long userId, String message, String scene, Map<String, String> memories, List<String> shortContext) {
        return "userId=" + userId + "\nmessage=" + message + "\nlastScene=" + scene
                + "\nlongTermMemory=" + memories + "\nrecentConversation=" + shortContext;
    }

    private String text(JsonNode node, String name, String fallback) { return node.hasNonNull(name) ? node.get(name).asText() : fallback; }
    private String nullableText(JsonNode node, String name) { return node.hasNonNull(name) && !node.get(name).asText().isBlank() ? node.get(name).asText() : null; }
    private String allowedScene(String scene) { return List.of("mall", "video_feed", "buy_first").contains(scene) ? scene : "mall"; }
    private String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    @Override public String name() { return "openai-compatible-function-calling"; }
}
