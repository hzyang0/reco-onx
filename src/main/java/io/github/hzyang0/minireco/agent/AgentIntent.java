package io.github.hzyang0.minireco.agent;

/** Structured constraints produced by the conversational recommendation agent. */
public record AgentIntent(
        long userId,
        String scene,
        String preferredSource,
        String preferredCategory,
        Integer maxPrice,
        boolean excludeAds,
        int limit,
        boolean needsClarification,
        String clarificationQuestion
) {
}
