"""MySQL tools and atomic local-life session/result persistence.

All tools currently read this database. Do not hold this transaction open while
adding a remote delivery provider: introduce task leases/idempotent tool calls first.
"""
from datetime import datetime, timezone
import hashlib
import json
import time
import uuid

import pymysql
from fastapi import HTTPException

from app.local_life import LocalConstraints, QuoteRejected, local_graph, merge_constraints, parse_constraints, remember_explicitly


class CatalogLimitError(RuntimeError):
    pass


class LocalTools:
    def __init__(self, cursor):
        self.cursor = cursor

    def search_merchants(self, zone):
        self.cursor.execute("SELECT m.merchant_id,m.name,m.data_source FROM local_merchants m "
                            "JOIN local_delivery_rules d ON d.merchant_id=m.merchant_id "
                            "WHERE m.enabled=TRUE AND d.zone=%s ORDER BY m.merchant_id", (zone,))
        return self.cursor.fetchall()

    def search_products(self, merchant_ids, category, quantity):
        if not merchant_ids:
            return []
        placeholders = ",".join(["%s"] * len(merchant_ids))
        self.cursor.execute("SELECT p.*,m.name AS merchant_name,m.data_source FROM local_products p "
                            "JOIN local_merchants m ON m.merchant_id=p.merchant_id "
                            f"WHERE p.merchant_id IN ({placeholders}) AND p.category=%s "
                            "AND p.enabled=TRUE AND p.stock>=%s ORDER BY p.product_id LIMIT 201",
                            (*merchant_ids, category, quantity))
        rows = self.cursor.fetchall()
        if len(rows) > 200:
            raise CatalogLimitError("candidate set exceeds current 200-product execution budget")
        return rows

    def quote_delivery(self, merchant_id, zone):
        self.cursor.execute("SELECT * FROM local_delivery_rules WHERE merchant_id=%s AND zone=%s", (merchant_id, zone))
        rule = self.cursor.fetchone()
        if rule is None:
            raise QuoteRejected("unknown_delivery_quote")
        return rule


def expire_quotes(result, now=None):
    now = now or datetime.now(timezone.utc)
    if any(datetime.fromisoformat(item["quote"]["validUntil"]) <= now for item in result.get("items", [])):
        return result | {"status": "quote_expired", "originalStatus": result["status"], "items": [],
                         "answer": "该任务的报价已过期，请使用新的请求标识重新询价；旧报价不会作为当前可购买方案返回。"}
    return result


def session_active(connect, user_id, session_id):
    with connect() as conn, conn.cursor() as cursor:
        cursor.execute("SELECT 1 FROM local_agent_sessions WHERE user_id=%s AND session_id=%s AND expires_at>UTC_TIMESTAMP()", (user_id, session_id))
        return cursor.fetchone() is not None


def read_task(connect, user_id, task_id):
    with connect() as conn, conn.cursor() as cursor:
        cursor.execute("SELECT result_json FROM local_agent_tasks WHERE user_id=%s AND task_id=%s", (user_id, task_id))
        row = cursor.fetchone()
        if not row:
            raise HTTPException(404, "task not found")
        return expire_quotes(json.loads(row[0]))


def execute_local(connect, user_id, session_id, request_id, message, structured=None, ttl=86400):
    began = time.perf_counter()
    payload = json.dumps({"message": message, "constraints": structured}, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    fingerprint = hashlib.sha256(payload.encode()).hexdigest()
    with connect() as conn:
        try:
            with conn.cursor(pymysql.cursors.DictCursor) as cur:
                cur.execute("SET time_zone = '+00:00'")
                cur.execute("SET SESSION innodb_lock_wait_timeout=3")
                cur.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
                conn.begin()
                cur.execute("SELECT user_id FROM user_profiles WHERE user_id=%s", (user_id,))
                if not cur.fetchone():
                    raise HTTPException(404, "user not found")
                cur.execute("INSERT INTO local_agent_sessions(user_id,session_id,constraints_json,expires_at) "
                            "VALUES(%s,%s,'{}',TIMESTAMPADD(SECOND,%s,UTC_TIMESTAMP())) "
                            "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)", (user_id, session_id, ttl))
                cur.execute("SELECT constraints_json,expires_at>UTC_TIMESTAMP() AS fresh FROM local_agent_sessions "
                            "WHERE user_id=%s AND session_id=%s FOR UPDATE", (user_id, session_id))
                session = cur.fetchone()
                cur.execute("SELECT payload_hash,result_json FROM local_agent_tasks WHERE user_id=%s AND session_id=%s AND request_id=%s",
                            (user_id, session_id, request_id))
                existing = cur.fetchone()
                if existing:
                    if existing["payload_hash"] != fingerprint:
                        raise HTTPException(409, "同一请求标识不能更改内容或条件")
                    result = expire_quotes(json.loads(existing["result_json"]))
                    conn.commit()
                    return result | {"replayed": True}
                previous = json.loads(session["constraints_json"]) if session["fresh"] else {}
                cur.execute("SELECT memory_key,memory_value FROM agent_long_term_memories WHERE user_id=%s "
                            "AND memory_key LIKE %s AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP())", (user_id, "local.%"))
                preferences = {}
                for row in cur.fetchall():
                    key = row["memory_key"].removeprefix("local.")
                    try:
                        value = json.loads(row["memory_value"])
                        LocalConstraints(**{key: value})
                        preferences[key] = value
                    except ValueError:
                        continue
                constraints, sources, issues = merge_constraints(message, previous, preferences, structured)
                missing = [label for key, label in [("zone", "配送区域（北区/南区/东区）"), ("category", "品类（咖啡/简餐/便利食品）")] if constraints[key] is None]
                if issues or missing:
                    state = {"status": "clarification", "answer": "；".join(issues or ["请补充" + "、".join(missing)]),
                             "items": [], "tool_trace": [], "suggestions": [], "rejected": {}}
                else:
                    state = local_graph.invoke({"constraints": constraints, "tools": LocalTools(cur), "now": datetime.now(timezone.utc)})
                memory_updated = []
                if not issues:
                    cur.execute("UPDATE local_agent_sessions SET constraints_json=%s,expires_at=TIMESTAMPADD(SECOND,%s,UTC_TIMESTAMP()) "
                                "WHERE user_id=%s AND session_id=%s", (json.dumps(constraints), ttl, user_id, session_id))
                    if remember_explicitly(message):
                        patch, _ = parse_constraints(message)
                        patch.update(structured or {})
                        if structured and "category" in structured and "product_query" not in structured:
                            patch["product_query"] = None
                        for key, value in patch.items():
                            memory_updated.append(key)
                            if value is None:
                                cur.execute("DELETE FROM agent_long_term_memories WHERE user_id=%s AND memory_key=%s", (user_id, "local." + key))
                            else:
                                cur.execute("INSERT INTO agent_long_term_memories(user_id,memory_key,memory_value,confidence,source_name) "
                                            "VALUES(%s,%s,%s,1,'explicit_local_preference') ON DUPLICATE KEY UPDATE "
                                            "memory_value=VALUES(memory_value),source_name=VALUES(source_name),expires_at=NULL,updated_at=CURRENT_TIMESTAMP",
                                            (user_id, "local." + key, json.dumps(value)))
                result = {"agent": "local-life-agent", "mode": "local_life", "planner": "local-constraint-planner",
                          "status": state["status"], "taskId": str(uuid.uuid4()), "requestId": request_id, "sessionId": session_id,
                          "intent": constraints, "constraintSources": sources, "answer": state["answer"], "items": state["items"],
                          "suggestions": state["suggestions"], "rejectedConstraints": state["rejected"], "toolTrace": state["tool_trace"],
                          "dataSource": "synthetic-campus-v1", "memoryUpdated": memory_updated, "replayed": False,
                          "metrics": {"elapsedMs": round((time.perf_counter() - began) * 1000, 2), "llmCalls": 0,
                                      "inputTokens": 0, "outputTokens": 0, "modelCostUsd": 0}}
                result = expire_quotes(result)
                cur.execute("INSERT INTO local_agent_tasks(task_id,user_id,session_id,request_id,payload_hash,result_json) VALUES(%s,%s,%s,%s,%s,%s)",
                            (result["taskId"], user_id, session_id, request_id, fingerprint, json.dumps(result, ensure_ascii=False)))
                for role, content in [("user", message), ("assistant", result["answer"])]:
                    cur.execute("INSERT INTO agent_conversations(session_id,user_id,role_name,content,expires_at) "
                                "VALUES(%s,%s,%s,%s,TIMESTAMPADD(SECOND,%s,UTC_TIMESTAMP()))", (session_id, user_id, role, content, ttl))
                conn.commit()
                return result
        except Exception:
            conn.rollback()
            raise
