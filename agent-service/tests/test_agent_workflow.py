import unittest
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
from app import main


class WorkflowTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.history = {}
        self.preferences = {}
        self.preference_writes = []
        self.backend_calls = 0
        self.fail_backend = False
        self.items = [
            {"itemId": 1, "title": "家居商品", "source": "goods", "category": "home", "attrs": {"price": 150}},
            {"itemId": 2, "title": "运动直播", "source": "live", "category": "sports", "attrs": {}},
            {"itemId": 3, "title": "数码广告", "source": "ad", "category": "digital", "attrs": {}},
        ]
        def backend(request):
            self.backend_calls += 1
            if self.fail_backend:
                return httpx.Response(503, json={"error": "unavailable"})
            if request.url.path == "/api/console-data":
                return httpx.Response(200, json={"users": [{"userId": 123, "preferredCategory": "home"}]})
            return httpx.Response(200, json={"requestId": "fixture-request", "items": self.items})
        client = httpx.AsyncClient
        def persist(user, session, role, content):
            self.history.setdefault((user, session), []).append({"role": role, "content": content})
        def preferences(user, updates):
            self.preference_writes.append((user, updates))
        patches = [patch.object(main, "PLANNER_MODE", "local"),
                   patch.object(main, "local_session_active", return_value=False),
                   patch.object(main, "persist_memory", side_effect=persist),
                   patch.object(main, "short_memory", side_effect=lambda u, s: list(self.history.get((u, s), []))[-8:]),
                   patch.object(main, "long_memory", side_effect=lambda u: self.preferences.get(u, {})),
                   patch.object(main, "persist_preferences", side_effect=preferences),
                   patch.object(main.httpx, "AsyncClient", side_effect=lambda **kw: client(transport=httpx.MockTransport(backend), **kw))]
        for item in patches:
            item.start()
            self.addCleanup(item.stop)

    async def ask(self, message, session="test"):
        return await main.execute(main.ChatRequest(user_id=123, session_id=session, message=message))

    async def test_recommendation_is_grounded_and_temporary_budget_not_durable(self):
        result = await self.ask("这次推荐家居商品，预算200，不要广告")
        self.assertEqual(result["status"], "completed")
        self.assertEqual([i["itemId"] for i in result["items"]], [1])
        self.assertEqual(self.preference_writes, [])
        self.assertEqual(result["metrics"]["llmCalls"], 0)
        self.assertEqual(result["metrics"]["modelCostUsd"], 0)

    async def test_no_match_does_not_relax_or_retry(self):
        result = await self.ask("推荐家居直播")
        self.assertEqual(result["status"], "no_match")
        self.assertEqual(result["items"], [])
        self.assertEqual(result["intent"]["preferred_source"], "live")
        self.assertIn("哪一项", result["answer"])
        self.assertEqual(self.backend_calls, 2)  # one profile and one recommendation

    async def test_invalid_explicit_budget_clarifies_without_tool_calls_or_memory_write(self):
        result = await self.ask("记住，以后推荐家居商品预算-1")
        self.assertEqual(result["status"], "clarification")
        self.assertEqual(result["items"], [])
        self.assertEqual(self.preference_writes, [])
        self.assertEqual(self.backend_calls, 0)

    async def test_followup_changes_budget_only(self):
        await self.ask("推荐家居商品，预算200，不要广告")
        result = await self.ask("预算改为100")
        self.assertEqual(result["status"], "no_match")
        self.assertEqual(result["intent"]["preferred_category"], "home")
        self.assertEqual(result["intent"]["preferred_source"], "goods")
        self.assertEqual(result["intent"]["max_price"], 100)
        self.assertTrue(result["intent"]["exclude_ads"])
        result = await self.ask("取消预算，允许广告")
        self.assertEqual(result["status"], "completed")
        self.assertIsNone(result["intent"]["max_price"])
        self.assertFalse(result["intent"]["exclude_ads"])

    async def test_explicit_durable_write_contains_only_requested_fields(self):
        self.preferences[123] = {"preferred_category": "digital", "max_price": "500"}
        await self.ask("记住，以后允许广告，不限预算")
        self.assertEqual(self.preference_writes, [(123, {"max_price": None, "exclude_ads": False})])

    async def test_clarification_cannot_return_checkpoint_items(self):
        await self.ask("推荐家居商品")
        self.history.clear()  # expired conversation; same public session id
        result = await self.ask("推荐")
        self.assertEqual(result["status"], "clarification")
        self.assertEqual(result["items"], [])
        self.assertIsNone(result["recommendationRequestId"])

    async def test_tool_error_ends_task_without_stale_recommendations(self):
        await self.ask("推荐家居商品")
        self.fail_backend = True
        result = await self.ask("推荐家居商品")
        self.assertEqual(result["status"], "tool_error")
        self.assertEqual(result["items"], [])
        self.assertIsNone(result["recommendationRequestId"])

    async def test_malformed_model_plan_falls_back(self):
        with patch.object(main, "PLANNER_MODE", "openai"), patch.object(main, "LLM_API_KEY", "test-placeholder"), \
                patch.object(main, "llm_intent", new=AsyncMock(return_value={"limit": "invalid", "max_price": -10})):
            result = await self.ask("推荐家居商品预算200")
        self.assertEqual(result["planner"], "local-fallback")
        self.assertEqual(result["status"], "completed")

    async def test_model_cannot_weaken_current_explicit_constraints(self):
        with patch.object(main, "PLANNER_MODE", "openai"), patch.object(main, "LLM_API_KEY", "test-placeholder"), \
                patch.object(main, "llm_intent", new=AsyncMock(return_value={"preferred_source": None, "max_price": 9999, "exclude_ads": False})):
            result = await self.ask("推荐家居商品预算100，不要广告")
        self.assertEqual(result["status"], "no_match")
        self.assertEqual(result["intent"]["max_price"], 100)
        self.assertTrue(result["intent"]["exclude_ads"])


class StorageTests(unittest.TestCase):
    def test_cache_append_does_not_mask_history_after_eviction(self):
        for exists in [False, True]:
            with self.subTest(cache_exists=exists):
                cache, connection = MagicMock(), MagicMock()
                cache.exists.return_value = exists
                with patch.object(main, "db_connection", return_value=connection), patch.object(main, "redis_client", cache):
                    main.persist_memory(123, "session", "user", "预算100")
                pipe = cache.pipeline.return_value.__enter__.return_value
                if exists:
                    pipe.rpush.assert_called_once()
                    pipe.ltrim.assert_called_once_with("agent:session:123:session", -8, -1)
                else:
                    cache.pipeline.assert_not_called()

    def test_restore_scopes_sql_and_cache_to_user_and_session(self):
        connection = MagicMock()
        cursor = connection.__enter__.return_value.cursor.return_value.__enter__.return_value
        cursor.fetchall.return_value = [("user", "推荐家居商品")]
        cache = MagicMock()
        cache.lrange.return_value = []
        with patch.object(main, "db_connection", return_value=connection), patch.object(main, "redis_client", cache):
            restored = main.short_memory(456, "shared-session-name")
        self.assertEqual(restored, [{"role": "user", "content": "推荐家居商品"}])
        sql, values = cursor.execute.call_args.args
        self.assertIn("user_id=%s", sql)
        self.assertEqual(values, ("shared-session-name", 456))
        cache.lrange.assert_called_once_with("agent:session:456:shared-session-name", -8, -1)

    def test_false_and_clear_are_persisted_instead_of_ignored(self):
        connection = MagicMock()
        cursor = connection.__enter__.return_value.cursor.return_value.__enter__.return_value
        with patch.object(main, "db_connection", return_value=connection):
            main.persist_preferences(123, {"max_price": None, "exclude_ads": False})
        calls = cursor.execute.call_args_list
        self.assertIn("DELETE", calls[0].args[0])
        self.assertEqual(calls[0].args[1], (123, "max_price"))
        self.assertEqual(calls[1].args[1][2], "false")


if __name__ == "__main__":
    unittest.main()
