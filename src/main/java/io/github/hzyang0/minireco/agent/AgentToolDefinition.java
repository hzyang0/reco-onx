package io.github.hzyang0.minireco.agent;

import java.util.List;
import java.util.Map;

/** Public, allow-listed tool schema exposed to a model planner and returned in the trace. */
public record AgentToolDefinition(String name, String description, List<String> requiredArguments) {
    public Map<String, Object> toMap() {
        return Map.of("name", name, "description", description, "requiredArguments", requiredArguments);
    }
}
