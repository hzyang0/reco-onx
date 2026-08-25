package io.github.hzyang0.minireco.agent;

import java.util.List;
import java.util.Map;

/** Chooses a structured intent; only the allow-listed executor may invoke tools. */
public interface AgentPlanner {
    AgentIntent plan(long userId, String message, String rememberedScene, Map<String, String> longTermMemory,
                     List<AgentToolDefinition> tools, List<String> shortTermContext);

    String name();
}
