package io.github.hzyang0.minireco.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIntentParserTest {
    private final AgentIntentParser parser = new AgentIntentParser();

    @Test
    void parsesBudgetCategorySourceAndAdConstraint() {
        AgentIntent intent = parser.parse(456, "给我推荐预算 500 以内的数码商品，不要广告", "mall");

        assertEquals("mall", intent.scene());
        assertEquals("goods", intent.preferredSource());
        assertEquals("digital", intent.preferredCategory());
        assertEquals(500, intent.maxPrice());
        assertTrue(intent.excludeAds());
        assertFalse(intent.needsClarification());
    }

    @Test
    void mapsLiveRequestToVideoFeed() {
        AgentIntent intent = parser.parse(2024, "我想看跑步直播", "mall");

        assertEquals("video_feed", intent.scene());
        assertEquals("live", intent.preferredSource());
        assertEquals("sports", intent.preferredCategory());
    }

    @Test
    void requestsClarificationForVaguePrompt() {
        AgentIntent intent = parser.parse(123, "推荐", "buy_first");

        assertTrue(intent.needsClarification());
        assertEquals("buy_first", intent.scene());
        assertNull(intent.preferredCategory());
    }
}
