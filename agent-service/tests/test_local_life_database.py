"""Opt-in MySQL tests; create and remove only isolated test-user records."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import os
import unittest
from unittest.mock import patch
import uuid

from fastapi import HTTPException
import pymysql
from app.main import db_connection, conversation_history
from app.local_life_store import LocalTools, execute_local, read_task, session_active


@unittest.skipUnless(os.getenv("RUN_LOCAL_LIFE_DB_TESTS") == "true", "requires migrated demo MySQL")
class LocalDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.user = 10_000_000 + int(uuid.uuid4().hex[:10], 16)
        with db_connection() as conn, conn.cursor() as cur:
            cur.execute("INSERT INTO user_profiles(user_id,age,new_user,default_category,province,city,persona_name,persona_summary) "
                        "VALUES(%s,22,TRUE,'food','test','test','isolated-test','local-life regression')", (self.user,))

    def tearDown(self):
        with db_connection() as conn, conn.cursor() as cur:
            for table in ["local_agent_tasks", "local_agent_sessions", "agent_conversations", "agent_long_term_memories", "user_profiles"]:
                cur.execute(f"DELETE FROM {table} WHERE user_id=%s", (self.user,))

    def ask(self, message="北区咖啡预算20元，30分钟内送达", key="request-001", session="local-test", structured=None):
        return execute_local(db_connection, self.user, session, key, message, structured)

    def count(self, table):
        with db_connection() as conn, conn.cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {table} WHERE user_id=%s", (self.user,))
            return cur.fetchone()[0]

    def test_real_tools_choose_delivered_price_winner(self):
        result = self.ask()
        self.assertEqual(result["status"], "completed")
        self.assertEqual(result["items"][0]["itemId"], 201)
        self.assertEqual(result["items"][0]["quote"]["totalCents"], 1400)
        self.assertEqual([t["tool"] for t in result["toolTrace"]], ["search_merchants", "search_products", "quote_delivery"])

    def test_named_product_survives_followups_without_cheaper_substitution(self):
        result = self.ask(message="北区拿铁预算30元")
        self.assertEqual([i["itemId"] for i in result["items"]], [102])
        self.assertEqual(result["items"][0]["quote"]["totalCents"], 2300)
        result = self.ask(message="预算改为20元", key="latte-budget")
        self.assertEqual(result["status"], "no_match")
        self.assertEqual(result["intent"]["product_query"], "拿铁")
        result = self.ask(message="不限款式", key="clear-product")
        self.assertEqual(result["items"][0]["itemId"], 201)

    def test_invalid_followup_does_not_overwrite_valid_snapshot(self):
        self.ask()
        self.assertEqual(self.ask(message="预算便宜点", key="vague-budget")["status"], "clarification")
        result = self.ask(message="继续查询", key="valid-after-clarify")
        self.assertEqual(result["intent"]["max_total_cents"], 2000)

    def test_reaffirmed_expired_preference_is_visible_in_new_session(self):
        self.ask(message="记住以后北区拿铁预算30元")
        with db_connection() as conn, conn.cursor() as cur:
            cur.execute("UPDATE agent_long_term_memories SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE user_id=%s", (self.user,))
        self.ask(message="记住以后东区咖啡预算20元", key="refresh-preferences")
        result = self.ask(message="继续查询", key="new-preferences", session="new-session")
        self.assertEqual((result["intent"]["zone"], result["intent"]["max_total_cents"]), ("east", 2000))
        self.assertIsNone(result["intent"]["product_query"])

    def test_long_history_restores_latest_messages_in_chronological_order(self):
        with db_connection() as conn, conn.cursor() as cur:
            cur.executemany("INSERT INTO agent_conversations(user_id,session_id,role_name,content,expires_at) "
                            "VALUES(%s,%s,'user',%s,UTC_TIMESTAMP()+INTERVAL 1 HOUR)",
                            [(self.user, "long-history", f"message-{i}") for i in range(60)])
        history = conversation_history("long-history", self.user)
        self.assertEqual(len(history), 50)
        self.assertEqual((history[0]["content"], history[-1]["content"]), ("message-10", "message-59"))

    def test_connections_use_utc_and_expired_session_is_not_active(self):
        self.ask()
        with db_connection() as conn, conn.cursor() as cur:
            cur.execute("SELECT @@session.time_zone")
            self.assertEqual(cur.fetchone()[0], "+00:00")
            cur.execute("UPDATE local_agent_sessions SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE user_id=%s", (self.user,))
        self.assertFalse(session_active(db_connection, self.user, "local-test"))

    def test_replay_does_not_duplicate_audit_and_changed_payload_conflicts(self):
        first, second = self.ask(), self.ask()
        self.assertEqual(first["taskId"], second["taskId"])
        self.assertTrue(second["replayed"])
        self.assertEqual(self.count("agent_conversations"), 2)
        self.assertEqual(self.count("local_agent_tasks"), 1)
        with self.assertRaises(HTTPException) as error:
            self.ask(message="预算100")
        self.assertEqual(error.exception.status_code, 409)

    def test_concurrent_replays_are_one_task_and_one_message_pair(self):
        with ThreadPoolExecutor(max_workers=6) as executor:
            results = list(executor.map(lambda _: self.ask(), range(6)))
        self.assertEqual(len({r["taskId"] for r in results}), 1)
        self.assertEqual(sum(not r["replayed"] for r in results), 1)
        self.assertEqual(self.count("agent_conversations"), 2)

    def test_snapshot_survives_more_than_eight_messages(self):
        self.ask()
        for i in range(6):
            self.ask(message="继续查询", key=f"continue-{i}")
        result = self.ask(message="预算改为14", key="last-turn")
        self.assertEqual(result["intent"]["zone"], "north")
        self.assertEqual(result["intent"]["category"], "coffee")
        self.assertEqual(result["intent"]["max_delivery_minutes"], 30)
        self.assertEqual(result["items"][0]["quote"]["totalCents"], 1400)

    def test_only_explicit_memory_changes_cross_sessions(self):
        self.ask(message="记住以后北区咖啡预算20")
        self.ask(message="这次预算10", key="temporary-budget")
        result = self.ask(message="继续查询", key="another-request", session="new-session")
        self.assertEqual(result["intent"]["max_total_cents"], 2000)
        self.ask(message="以后不限预算", key="clear-budget")
        result = self.ask(message="继续查询", key="third-request", session="third-session")
        self.assertIsNone(result["intent"]["max_total_cents"])

    def test_failure_rolls_back_task_session_and_audit_before_retry(self):
        with patch.object(LocalTools, "quote_delivery", side_effect=pymysql.OperationalError(2013, "injected failure")):
            with self.assertRaises(pymysql.OperationalError):
                self.ask()
        self.assertEqual(self.count("local_agent_tasks"), 0)
        self.assertEqual(self.count("local_agent_sessions"), 0)
        self.assertEqual(self.count("agent_conversations"), 0)
        self.assertEqual(self.ask()["status"], "completed")

    def test_task_read_is_user_scoped(self):
        result = self.ask()
        self.assertEqual(read_task(db_connection, self.user, result["taskId"])["taskId"], result["taskId"])
        with self.assertRaises(HTTPException) as error:
            read_task(db_connection, self.user + 1, result["taskId"])
        self.assertEqual(error.exception.status_code, 404)

    def test_expired_completed_quote_is_not_recommended_on_replay(self):
        result = self.ask()
        for item in result["items"]:
            item["quote"]["validUntil"] = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
        with db_connection() as conn, conn.cursor() as cur:
            cur.execute("UPDATE local_agent_tasks SET result_json=%s WHERE task_id=%s AND user_id=%s", (json.dumps(result), result["taskId"], self.user))
        replay = self.ask()
        self.assertTrue(replay["replayed"])
        self.assertEqual(replay["status"], "quote_expired")
        self.assertEqual(replay["items"], [])


if __name__ == "__main__":
    unittest.main()
