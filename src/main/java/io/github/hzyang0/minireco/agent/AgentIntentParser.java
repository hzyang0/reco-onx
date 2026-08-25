package io.github.hzyang0.minireco.agent;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, deterministic intent parser. It deliberately has no network dependency,
 * so the Agent works without an LLM key and is easy to test. An LLM can later
 * replace this boundary while keeping the downstream tools unchanged.
 */
public final class AgentIntentParser {
    private static final Pattern BUDGET = Pattern.compile("(?:预算|不超过|以内|低于|小于)\\s*(\\d{1,5})");
    private static final Pattern COUNT = Pattern.compile("(?:给我|推荐|来)\\s*(\\d{1,2})\\s*(?:个|件|条)?");
    private static final Map<String, String> CATEGORY_KEYWORDS = Map.ofEntries(
            Map.entry("数码", "digital"), Map.entry("电脑", "digital"), Map.entry("耳机", "digital"),
            Map.entry("家居", "home"), Map.entry("收纳", "home"), Map.entry("家具", "home"),
            Map.entry("美食", "food"), Map.entry("零食", "food"), Map.entry("咖啡", "food"),
            Map.entry("穿搭", "fashion"), Map.entry("服饰", "fashion"), Map.entry("通勤", "fashion"),
            Map.entry("运动", "sports"), Map.entry("跑步", "sports"), Map.entry("露营", "sports"),
            Map.entry("美妆", "beauty"), Map.entry("护肤", "beauty")
    );

    public AgentIntent parse(long userId, String message, String rememberedScene) {
        String text = message == null ? "" : message.trim();
        if (text.isBlank()) {
            return new AgentIntent(userId, rememberedSceneOrDefault(rememberedScene), null, null,
                    null, false, 5, true, "请告诉我想推荐什么，例如：预算 500 的数码商品，或想看运动直播。");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String scene = scene(lower, rememberedScene);
        String source = source(lower);
        String category = category(lower);
        Integer budget = number(BUDGET, lower);
        Integer requestedCount = number(COUNT, lower);
        int limit = requestedCount == null ? 5 : Math.max(1, Math.min(requestedCount, 10));
        boolean excludeAds = lower.contains("不要广告") || lower.contains("无广告") || lower.contains("不看广告");
        boolean vague = category == null && source == null && budget == null
                && (lower.equals("推荐") || lower.equals("帮我推荐") || lower.length() < 3);
        return new AgentIntent(userId, scene, source, category, budget, excludeAds, limit, vague,
                vague ? "你更关注商品、直播，还是某个品类？也可以补充预算。" : null);
    }

    private String scene(String text, String rememberedScene) {
        if (text.contains("直播") || text.contains("视频") || text.contains("视频流")) {
            return "video_feed";
        }
        if (text.contains("首页") || text.contains("买首") || text.contains("综合")) {
            return "buy_first";
        }
        if (text.contains("商城") || text.contains("商品") || text.contains("购买")) {
            return "mall";
        }
        return rememberedSceneOrDefault(rememberedScene);
    }

    private String source(String text) {
        if (text.contains("直播") || text.contains("视频")) return "live";
        if (text.contains("商品") || text.contains("买") || text.contains("购买")) return "goods";
        if (text.contains("广告")) return "ad";
        return null;
    }

    private String category(String text) {
        return CATEGORY_KEYWORDS.entrySet().stream()
                .filter(entry -> text.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private Integer number(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private String rememberedSceneOrDefault(String scene) {
        return scene == null || scene.isBlank() ? "mall" : scene;
    }
}
