import asyncio
import json
import os
import re
import time
import uuid
from typing import Any, AsyncIterator, Literal, TypedDict

import httpx
import pymysql
import redis
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from openai import AsyncOpenAI
from pydantic import BaseModel, Field

BACKEND_URL = os.getenv("RECO_BACKEND_URL", "http://app:8080")
MYSQL = dict(host=os.getenv("MYSQL_HOST", "db"), port=int(os.getenv("MYSQL_PORT", "3306")),
             user=os.getenv("MYSQL_USER", "mini_reco"), password=os.getenv("MYSQL_PASSWORD", "mini_reco"),
             database=os.getenv("MYSQL_DATABASE", "mini_reco"), charset="utf8mb4", autocommit=True)
REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379/0")
PLANNER_MODE = os.getenv("AGENT_PLANNER", "local")
LLM_MODEL = os.getenv("LLM_MODEL", "gpt-4o-mini")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.openai.com/v1")
SHORT_TTL = int(os.getenv("AGENT_SHORT_MEMORY_TTL_SECONDS", "86400"))
MAX_RETRY = 1

redis_client = redis.Redis.from_url(REDIS_URL, decode_responses=True)

TOOL_DEFINITIONS = [
    {"name": "get_user_profile", "description": "Read user profile from Java recommendation backend", "requiredArguments": ["userId"]},
    {"name": "recommend", "description": "Run Java DAG with parallel goods/live/ad recall", "requiredArguments": ["userId", "scene"]},
    {"name": "filter_candidates", "description": "Apply price/category/source/ad constraints to real candidates", "requiredArguments": []},
    {"name": "generate_grounded_answer", "description": "Generate an answer only from real filtered candidates", "requiredArguments": []},
]


class ChatRequest(BaseModel):
    user_id: int = Field(gt=0)
    message: str = Field(min_length=1, max_length=1000)
    session_id: str | None = Field(default=None, max_length=64)


class LoginRequest(BaseModel):
    user_id: int = Field(gt=0)
    resume_session_id: str | None = Field(default=None, max_length=64)


class AgentState(TypedDict, total=False):
    user_id: int
    message: str
    session_id: str
    short_memory: list[dict[str, str]]
    long_memory: dict[str, str]
    intent: dict[str, Any]
    profile: dict[str, Any]
    raw_items: list[dict[str, Any]]
    items: list[dict[str, Any]]
    request_id: str
    answer: str
    tool_trace: list[dict[str, Any]]
    retry_count: int
    planner: str


def db_connection():
    return pymysql.connect(**MYSQL)


def long_memory(user_id: int) -> dict[str, str]:
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT memory_key, memory_value FROM agent_long_term_memories WHERE user_id=%s AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)", (user_id,))
        return {key: value for key, value in cur.fetchall()}


def persist_memory(user_id: int, session_id: str, role: str, text: str, intent: dict[str, Any] | None = None):
    key = f"agent:session:{session_id}"
    redis_client.rpush(key, json.dumps({"role": role, "content": text}, ensure_ascii=False))
    redis_client.expire(key, SHORT_TTL)
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("INSERT INTO agent_conversations (session_id,user_id,role_name,content,expires_at) VALUES (%s,%s,%s,%s,TIMESTAMPADD(SECOND,%s,CURRENT_TIMESTAMP))", (session_id, user_id, role, text, SHORT_TTL))
        if intent:
            pairs = {"last_scene": intent.get("scene"), "preferred_category": intent.get("preferred_category"),
                     "preferred_source": intent.get("preferred_source"), "max_price": str(intent["max_price"]) if intent.get("max_price") else None,
                     "exclude_ads": "true" if intent.get("exclude_ads") else None}
            for memory_key, memory_value in pairs.items():
                if memory_value:
                    cur.execute("INSERT INTO agent_long_term_memories (user_id,memory_key,memory_value,confidence,source_name) VALUES (%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE memory_value=VALUES(memory_value), confidence=VALUES(confidence), source_name=VALUES(source_name), updated_at=CURRENT_TIMESTAMP", (user_id, memory_key, memory_value, 0.8, "langgraph_agent"))


def short_memory(session_id: str) -> list[dict[str, str]]:
    values = redis_client.lrange(f"agent:session:{session_id}", -8, -1)
    if values:
        return [json.loads(value) for value in values]
    # Redis is the hot session store. If it was restarted/evicted, restore the
    # recent context from the durable MySQL audit log and warm Redis again.
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT role_name, content FROM agent_conversations WHERE session_id=%s AND expires_at > CURRENT_TIMESTAMP ORDER BY message_id DESC LIMIT 8", (session_id,))
        restored = [{"role": role, "content": content} for role, content in reversed(cur.fetchall())]
    if restored:
        key = f"agent:session:{session_id}"
        redis_client.rpush(key, *[json.dumps(message, ensure_ascii=False) for message in restored])
        redis_client.expire(key, SHORT_TTL)
    return restored


def conversation_history(session_id: str, user_id: int) -> list[dict[str, str]]:
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT role_name, content, created_at FROM agent_conversations WHERE session_id=%s AND user_id=%s AND expires_at > CURRENT_TIMESTAMP ORDER BY message_id ASC LIMIT 50", (session_id, user_id))
        return [{"role": role, "content": content, "createdAt": created.isoformat()} for role, content, created in cur.fetchall()]


async def available_users() -> list[dict[str, Any]]:
    async with httpx.AsyncClient(timeout=3) as client:
        data = (await client.get(f"{BACKEND_URL}/api/console-data")).json()
    return data.get("users", [])


async def issue_demo_session(user_id: int, resume_session_id: str | None = None) -> dict[str, str | int]:
    user = next((item for item in await available_users() if int(item["userId"]) == user_id), None)
    if not user:
        raise HTTPException(404, "user not found")
    session_id = resume_session_id or f"chat-{uuid.uuid4().hex[:20]}"
    token = uuid.uuid4().hex
    payload = {"user_id": user_id, "session_id": session_id, "display_name": user.get("personaName", user.get("nickname", f"用户 {user_id}"))}
    redis_client.setex(f"agent:auth:{token}", SHORT_TTL, json.dumps(payload, ensure_ascii=False))
    return {"token": token, "sessionId": session_id, "userId": user_id, "displayName": payload["display_name"]}


def resolve_demo_session(token: str | None) -> dict[str, Any]:
    if not token:
        raise HTTPException(401, "login required")
    payload = redis_client.get(f"agent:auth:{token}")
    if not payload:
        raise HTTPException(401, "session expired; sign in again")
    redis_client.expire(f"agent:auth:{token}", SHORT_TTL)
    return json.loads(payload)


def local_intent(message: str, remembered: dict[str, str]) -> dict[str, Any]:
    text = message.lower().strip()
    categories = {"数码": "digital", "电脑": "digital", "耳机": "digital", "家居": "home", "收纳": "home", "美食": "food", "咖啡": "food", "穿搭": "fashion", "运动": "sports", "跑步": "sports", "露营": "sports", "美妆": "beauty", "护肤": "beauty"}
    category = next((value for key, value in categories.items() if key in text), remembered.get("preferred_category"))
    scene = "video_feed" if "直播" in text or "视频" in text else "buy_first" if "首页" in text or "综合" in text else "mall"
    source = "live" if "直播" in text or "视频" in text else "goods" if "商品" in text or "买" in text else remembered.get("preferred_source")
    budget = re.search(r"(?:预算|不超过|以内|低于)\s*(\d{1,5})", text)
    max_price = int(budget.group(1)) if budget else int(remembered["max_price"]) if remembered.get("max_price") else None
    exclude_ads = any(x in text for x in ["不要广告", "无广告", "不看广告"]) or remembered.get("exclude_ads") == "true"
    vague = text in {"推荐", "推荐一下", "帮我推荐", "给我推荐", "来点推荐"} or len(text) < 3
    return {"scene": scene, "preferred_source": source, "preferred_category": category, "max_price": max_price,
            "exclude_ads": exclude_ads, "limit": 5, "needs_clarification": vague}


async def llm_intent(message: str, memory: dict[str, str], short: list[dict[str, str]]) -> dict[str, Any]:
    client = AsyncOpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL)
    tool = {"type": "function", "function": {"name": "plan_recommendation", "description": "Plan a safe recommendation request only.", "parameters": {"type": "object", "properties": {
        "scene": {"type": "string", "enum": ["mall", "video_feed", "buy_first"]}, "preferred_source": {"type": "string", "enum": ["goods", "live", "ad"]},
        "preferred_category": {"type": "string"}, "max_price": {"type": "integer"}, "exclude_ads": {"type": "boolean"}, "limit": {"type": "integer"}, "needs_clarification": {"type": "boolean"}}, "required": ["scene", "exclude_ads", "limit", "needs_clarification"]}}}
    response = await client.chat.completions.create(model=LLM_MODEL, temperature=0, tools=[tool], tool_choice={"type": "function", "function": {"name": "plan_recommendation"}}, messages=[
        {"role": "system", "content": "Only plan. Never invent products, SQL, URLs or actions. Backend tools execute separately."},
        {"role": "user", "content": json.dumps({"request": message, "long_memory": memory, "short_memory": short}, ensure_ascii=False)}])
    calls = response.choices[0].message.tool_calls or []
    if not calls: raise RuntimeError("model did not return a function call")
    return json.loads(calls[0].function.arguments)


async def load_memory_node(state: AgentState):
    return {"short_memory": short_memory(state["session_id"]), "long_memory": long_memory(state["user_id"]), "tool_trace": [], "retry_count": 0}


async def plan_node(state: AgentState):
    if PLANNER_MODE == "openai" and LLM_API_KEY:
        try:
            intent = await llm_intent(state["message"], state["long_memory"], state["short_memory"])
            planner = "openai-function-calling"
        except Exception:
            intent, planner = local_intent(state["message"], state["long_memory"]), "local-fallback"
    else:
        intent, planner = local_intent(state["message"], state["long_memory"]), "local-rule-planner"
    intent["scene"] = intent["scene"] if intent.get("scene") in {"mall", "video_feed", "buy_first"} else "mall"
    intent["limit"] = min(max(int(intent.get("limit", 5)), 1), 10)
    return {"intent": intent, "planner": planner}


def clarification_route(state: AgentState) -> Literal["clarify", "profile"]:
    return "clarify" if state["intent"].get("needs_clarification") else "profile"


async def clarification_node(state: AgentState):
    return {"answer": "请补充品类、预算或内容类型，例如：预算 500 的数码商品，或想看运动直播。"}


async def profile_node(state: AgentState):
    async with httpx.AsyncClient(timeout=3) as client:
        data = (await client.get(f"{BACKEND_URL}/api/console-data")).json()
    profile = next((user for user in data.get("users", []) if int(user["userId"]) == state["user_id"]), None)
    if not profile: raise HTTPException(404, "user not found")
    trace = state["tool_trace"] + [{"tool": "get_user_profile", "status": "success", "resultSummary": {"preferredCategory": profile["preferredCategory"]}}]
    return {"profile": profile, "tool_trace": trace}


async def recommend_node(state: AgentState):
    intent = state["intent"]
    async with httpx.AsyncClient(timeout=4) as client:
        response = await client.get(f"{BACKEND_URL}/recommend", params={"userId": state["user_id"], "scene": intent["scene"], "limit": 20})
        response.raise_for_status(); data = response.json()
    trace = state["tool_trace"] + [{"tool": "recommend", "status": "success", "arguments": {"scene": intent["scene"]}, "resultSummary": {"requestId": data["requestId"], "candidateCount": len(data["items"])}}]
    return {"raw_items": data["items"], "request_id": data["requestId"], "tool_trace": trace}


async def filter_node(state: AgentState):
    intent = state["intent"]
    def valid(item):
        if intent.get("exclude_ads") and item["source"] == "ad": return False
        if intent.get("preferred_source") and item["source"] != intent["preferred_source"]: return False
        if intent.get("preferred_category") and item["category"] != intent["preferred_category"]: return False
        price = int(item.get("attrs", {}).get("price", 0))
        return not (intent.get("max_price") and item["source"] == "goods" and price > int(intent["max_price"]))
    items = [item for item in state["raw_items"] if valid(item)][:intent["limit"]]
    trace = state["tool_trace"] + [{"tool": "filter_candidates", "status": "success", "resultSummary": {"returnedCount": len(items)}}]
    return {"items": items, "tool_trace": trace}


def filter_route(state: AgentState) -> Literal["answer", "relax"]:
    return "answer" if state["items"] or state["retry_count"] >= MAX_RETRY else "relax"


async def relax_node(state: AgentState):
    relaxed = dict(state["intent"]); relaxed["preferred_source"] = None
    return {"intent": relaxed, "retry_count": state["retry_count"] + 1}


async def answer_node(state: AgentState):
    items = state.get("items", [])
    if not items: answer = "真实推荐链路没有返回满足当前条件的候选；可以放宽预算、换一个品类或允许其他内容类型。"
    else:
        top = items[0]; answer = f"已通过真实用户画像、推荐 DAG 与候选过滤得到 {len(items)} 条结果。首选是「{top['title']}」，来源为 {top['source']}，品类为 {top['category']}。"
    trace = state["tool_trace"] + [{"tool": "generate_grounded_answer", "status": "success", "resultSummary": {"grounded": True}}]
    return {"answer": answer, "tool_trace": trace}


async def persist_node(state: AgentState):
    # Do not turn a vague request into a durable preference such as "mall".
    durable_intent = None if state.get("intent", {}).get("needs_clarification") else state.get("intent")
    persist_memory(state["user_id"], state["session_id"], "user", state["message"], durable_intent)
    persist_memory(state["user_id"], state["session_id"], "assistant", state["answer"])
    return {}


workflow = StateGraph(AgentState)
workflow.add_node("load_memory", load_memory_node); workflow.add_node("plan", plan_node); workflow.add_node("clarify", clarification_node)
workflow.add_node("load_profile", profile_node); workflow.add_node("run_recommendation", recommend_node); workflow.add_node("filter_candidates", filter_node)
workflow.add_node("relax", relax_node); workflow.add_node("build_answer", answer_node); workflow.add_node("persist", persist_node)
workflow.add_edge(START, "load_memory"); workflow.add_edge("load_memory", "plan")
workflow.add_conditional_edges("plan", clarification_route, {"clarify": "clarify", "profile": "load_profile"})
workflow.add_edge("load_profile", "run_recommendation"); workflow.add_edge("run_recommendation", "filter_candidates")
workflow.add_conditional_edges("filter_candidates", filter_route, {"answer": "build_answer", "relax": "relax"}); workflow.add_edge("relax", "run_recommendation")
workflow.add_edge("clarify", "persist"); workflow.add_edge("build_answer", "persist"); workflow.add_edge("persist", END)
graph = workflow.compile(checkpointer=MemorySaver())

app = FastAPI(title="Mini Reco LangGraph Agent")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

async def execute(request: ChatRequest) -> dict[str, Any]:
    session_id = request.session_id or f"web-{request.user_id}"
    state = await graph.ainvoke({"user_id": request.user_id, "message": request.message, "session_id": session_id}, config={"configurable": {"thread_id": session_id}})
    return {"agent": "langgraph-reco-agent", "planner": state.get("planner"), "sessionId": session_id, "intent": state.get("intent", {}), "answer": state.get("answer"), "items": state.get("items", []), "tools": TOOL_DEFINITIONS, "toolTrace": state.get("tool_trace", []), "recommendationRequestId": state.get("request_id")}

@app.get("/")
async def console(): return FileResponse("app/static/index.html")

@app.get("/health")
async def health():
    try:
        redis_client.ping()
        async with httpx.AsyncClient(timeout=2) as client: backend = (await client.get(f"{BACKEND_URL}/health")).json()
        return {"status": "UP", "service": "langgraph-agent", "backend": backend.get("status"), "redis": "UP"}
    except Exception as exc: raise HTTPException(503, f"dependency unavailable: {exc}")

@app.post("/api/chat")
async def chat(request: ChatRequest): return await execute(request)

@app.get("/api/memory/{user_id}")
async def memory(user_id: int): return {"userId": user_id, "longTermMemory": long_memory(user_id)}

@app.get("/api/users")
async def users():
    """Demo identity options. A production deployment must replace this with real SSO/OIDC."""
    return {"users": await available_users()}

@app.post("/api/auth/login")
async def login(request: LoginRequest):
    return await issue_demo_session(request.user_id, request.resume_session_id)

@app.get("/api/auth/session")
async def current_session(token: str):
    return resolve_demo_session(token)

@app.post("/api/auth/new-conversation")
async def new_conversation(token: str):
    current = resolve_demo_session(token)
    return await issue_demo_session(int(current["user_id"]))

@app.get("/api/conversations/{session_id}")
async def conversations(session_id: str, token: str):
    current = resolve_demo_session(token)
    if session_id != current["session_id"]:
        raise HTTPException(403, "session does not belong to current login")
    return {"sessionId": session_id, "messages": conversation_history(session_id, int(current["user_id"]))}

@app.websocket("/ws/chat")
async def chat_ws(websocket: WebSocket):
    await websocket.accept()
    try:
        while True:
            payload = await websocket.receive_json()
            try:
                current = resolve_demo_session(payload.get("token"))
            except HTTPException as exc:
                await websocket.send_json({"event": "agent_error", "message": exc.detail})
                continue
            request = ChatRequest(user_id=int(current["user_id"]), message=payload.get("message", ""), session_id=current["session_id"])
            await websocket.send_json({"event": "agent_started", "sessionId": request.session_id})
            result = await execute(request)
            for trace in result["toolTrace"]:
                await websocket.send_json({"event": "tool_completed", "data": trace})
            await websocket.send_json({"event": "agent_completed", "data": result})
    except WebSocketDisconnect: return
