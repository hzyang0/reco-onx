import asyncio
import json
import os
import time
import uuid
from contextvars import ContextVar
from typing import Any, Literal, TypedDict

import httpx
import pymysql
import redis
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from langgraph.graph import END, START, StateGraph
from openai import AsyncOpenAI
from pydantic import BaseModel, Field, ValidationError
from app.constraints import Intent, durable_updates, explicit_constraints, filter_candidates, local_intent
from app.local_life import LocalConstraints, is_local_request, is_local_followup
from app import local_life_store

BACKEND_URL = os.getenv("RECO_BACKEND_URL", "http://app:8080")
MYSQL = dict(host=os.getenv("MYSQL_HOST", "db"), port=int(os.getenv("MYSQL_PORT", "3306")),
             user=os.getenv("MYSQL_USER", "mini_reco"), password=os.getenv("MYSQL_PASSWORD", "mini_reco"),
             database=os.getenv("MYSQL_DATABASE", "mini_reco"), charset="utf8mb4", autocommit=True,
             init_command="SET time_zone = '+00:00'",
             connect_timeout=3, read_timeout=3, write_timeout=3)
REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379/0")
PLANNER_MODE = os.getenv("AGENT_PLANNER", "local")
LLM_MODEL = os.getenv("LLM_MODEL", "gpt-4o-mini")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.openai.com/v1")
SHORT_TTL = int(os.getenv("AGENT_SHORT_MEMORY_TTL_SECONDS", "86400"))

redis_client = redis.Redis.from_url(REDIS_URL, decode_responses=True, socket_timeout=3, socket_connect_timeout=3)
llm_usage: ContextVar[dict[str, int] | None] = ContextVar("llm_usage", default=None)


def record_model_call(response=None):
    usage = llm_usage.get()
    if usage is None:
        return
    if response is None:
        usage["llmCalls"] += 1
    elif response.usage:
        usage["inputTokens"] += response.usage.prompt_tokens
        usage["outputTokens"] += response.usage.completion_tokens

TOOL_DEFINITIONS = [
    {"name": "get_user_profile", "description": "Read user profile from Java recommendation backend", "requiredArguments": ["userId"]},
    {"name": "recommend", "description": "Run Java DAG with parallel goods/live/ad recall", "requiredArguments": ["userId", "scene"]},
    {"name": "filter_candidates", "description": "Apply price/category/source/ad constraints to real candidates", "requiredArguments": []},
    {"name": "generate_grounded_answer", "description": "Generate an answer only from real filtered candidates", "requiredArguments": []},
    {"name": "answer_general_question", "description": "Answer a non-recommendation question with an LLM or the local knowledge fallback", "requiredArguments": []},
]


class ChatRequest(BaseModel):
    user_id: int = Field(gt=0)
    message: str = Field(min_length=1, max_length=1000)
    session_id: str | None = Field(default=None, max_length=64)
    request_id: str | None = Field(default=None, min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    workflow: Literal["auto", "local_life", "recommendation"] = "auto"
    constraints: LocalConstraints | None = None


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
    rejected_constraints: dict[str, int]
    planner: str
    request_mode: str
    plan_error: str


def db_connection():
    return pymysql.connect(**MYSQL)


def long_memory(user_id: int) -> dict[str, str]:
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT memory_key, memory_value FROM agent_long_term_memories WHERE user_id=%s AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)", (user_id,))
        return {key: value for key, value in cur.fetchall()}


def persist_preferences(user_id: int, updates: dict[str, Any]):
    with db_connection() as conn, conn.cursor() as cur:
        for key, value in updates.items():
            memory_key = "last_scene" if key == "scene" else key
            if value is None:
                cur.execute("DELETE FROM agent_long_term_memories WHERE user_id=%s AND memory_key=%s", (user_id, memory_key))
            else:
                memory_value = str(value).lower() if isinstance(value, bool) else str(value)
                cur.execute("INSERT INTO agent_long_term_memories (user_id,memory_key,memory_value,confidence,source_name) VALUES (%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE memory_value=VALUES(memory_value), confidence=VALUES(confidence), source_name=VALUES(source_name), expires_at=NULL, updated_at=CURRENT_TIMESTAMP", (user_id, memory_key, memory_value, 1.0, "explicit_user_preference"))


def persist_memory(user_id: int, session_id: str, role: str, text: str):
    key = f"agent:session:{user_id}:{session_id}"
    # Commit the durable record first. Append only to an existing cache: creating
    # a one-message cache after eviction would hide the older durable context.
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("INSERT INTO agent_conversations (session_id,user_id,role_name,content,expires_at) VALUES (%s,%s,%s,%s,TIMESTAMPADD(SECOND,%s,CURRENT_TIMESTAMP))", (session_id, user_id, role, text, SHORT_TTL))
    if redis_client.exists(key):
        with redis_client.pipeline() as pipe:
            pipe.rpush(key, json.dumps({"role": role, "content": text}, ensure_ascii=False))
            pipe.ltrim(key, -8, -1)
            pipe.expire(key, SHORT_TTL)
            pipe.execute()


def short_memory(user_id: int, session_id: str) -> list[dict[str, str]]:
    key = f"agent:session:{user_id}:{session_id}"
    values = redis_client.lrange(key, -8, -1)
    if values:
        return [json.loads(value) for value in values]
    # Redis is the hot session store. If it was restarted/evicted, restore the
    # recent context from the durable MySQL audit log and warm Redis again.
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT role_name, content FROM agent_conversations WHERE session_id=%s AND user_id=%s AND expires_at > CURRENT_TIMESTAMP ORDER BY message_id DESC LIMIT 8", (session_id, user_id))
        restored = [{"role": role, "content": content} for role, content in reversed(cur.fetchall())]
    if restored:
        with redis_client.pipeline() as pipe:
            pipe.delete(key)
            pipe.rpush(key, *[json.dumps(message, ensure_ascii=False) for message in restored])
            pipe.expire(key, SHORT_TTL)
            pipe.execute()
    return restored


def conversation_history(session_id: str, user_id: int) -> list[dict[str, str]]:
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT role_name, content, created_at FROM agent_conversations WHERE session_id=%s AND user_id=%s AND expires_at > CURRENT_TIMESTAMP ORDER BY message_id DESC LIMIT 50", (session_id, user_id))
        return [{"role": role, "content": content, "createdAt": created.isoformat()} for role, content, created in reversed(cur.fetchall())]


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


async def llm_intent(message: str, memory: dict[str, str], short: list[dict[str, str]]) -> dict[str, Any]:
    tool = {"type": "function", "function": {"name": "plan_recommendation", "description": "Extract explicitly stated recommendation constraints. Omit fields not stated; null clears a prior optional constraint.", "parameters": Intent.model_json_schema()}}
    async with AsyncOpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL, timeout=8, max_retries=0) as client:
        record_model_call()
        response = await client.chat.completions.create(model=LLM_MODEL, temperature=0, tools=[tool], tool_choice={"type": "function", "function": {"name": "plan_recommendation"}}, messages=[
            {"role": "system", "content": "Only extract constraints stated in this request. Omit unstated fields. Never relax a constraint to get results. Never invent products, SQL, URLs or actions. Backend tools execute separately."},
            {"role": "user", "content": json.dumps({"request": message, "long_memory": memory, "short_memory": short}, ensure_ascii=False)}])
        record_model_call(response)
    calls = response.choices[0].message.tool_calls or []
    if len(calls) != 1 or calls[0].function.name != "plan_recommendation":
        raise ValueError("model did not return the expected function call")
    return Intent.model_validate_json(calls[0].function.arguments).model_dump(exclude_unset=True)


def request_mode(message: str) -> Literal["recommendation", "general"]:
    """Keep recommendation execution explicit; everything else is normal Q&A."""
    # Explanation questions contain recommendation vocabulary, but they should
    # explain the system rather than accidentally invoke a fresh recommendation.
    explanation_words = ["推荐链路", "推荐系统", "推荐机制", "召回", "混排", "在线特征", "dag", "d a g", "架构", "怎么工作", "如何工作"]
    if any(word in message.lower() for word in explanation_words):
        return "general"
    recommendation_words = ["推荐", "商品", "直播", "视频", "广告", "预算", "购买", "加购", "数码", "家居", "美食", "咖啡", "穿搭", "运动", "跑步", "露营", "美妆", "护肤", "我的偏好", "为什么给我"]
    return "recommendation" if any(word in message.lower() for word in recommendation_words) else "general"


def local_general_answer(message: str) -> str:
    text = message.lower()
    if any(word in text for word in ["agent", "智能体", "作用", "怎么工作", "你是谁"]):
        return "这个 Agent 将自然语言需求路由到不同工作流：推荐问题会调用画像、召回、过滤等 Tool；非推荐问题进入常规问答分支。LangGraph 负责记忆、分支和持久化，Java 推荐服务负责真实候选。"
    if any(word in text for word in ["记忆", "上下文", "恢复"]):
        return "短期上下文保存在 Redis，并在键丢失时由 MySQL 对话审计恢复；只有明确说‘记住’或‘以后’才更新长期偏好，本次预算不会自动写入长期记忆。页面刷新可恢复已完成对话。"
    if "召回" in text:
        return "召回是推荐的第一轮粗筛：系统从商品、直播和广告等多个候选库中，快速找出一批可能感兴趣的内容。这里三路召回会并行执行，目的是在较短耗时内扩大候选覆盖；后面的混排和过滤再决定最终展示哪些内容。"
    if "混排" in text:
        return "混排发生在多路召回之后：它把商品、直播、广告等候选放到同一个列表，按场景策略和分数决定展示顺序，同时控制广告插入位置和不同内容类型的比例。它解决的是“各路内容如何一起排”的问题。"
    if any(word in text for word in ["在线特征", "实时特征", "库存", "价格"]):
        return "在线特征是在推荐请求过程中实时补齐的数据，例如价格、库存和商品状态。召回结果可能来自稍早的索引，因此这些实时变化的信息要在后续阶段重新读取，避免给用户展示已下架、无库存或价格过期的内容。"
    if "dag" in text:
        return "DAG 是有向无环图。项目把准备、召回、在线特征、混排、过滤等步骤建模为节点，并用依赖边描述执行顺序；没有依赖关系的节点可以并行，从而让流程更清晰、可观测，也便于定位性能瓶颈。"
    if any(word in text for word in ["推荐", "召回", "混排", "dag", "在线特征"]):
        return "推荐链路分为：准备用户画像、AB 参数等上下文；goods/live/ad 三路并行召回；补齐价格、库存等在线特征；混排与过滤；最后返回真实候选。Agent 只负责理解问题、选择工具和编排分支，Java 后端负责这条确定性的推荐 DAG。"
    return "当前未配置外部 LLM，因此本地常规问答仅覆盖本项目、推荐链路、Agent、记忆与使用方式。配置支持聊天的 OpenAI 兼容模型后，可回答更开放的常规问题。"


async def llm_general_answer(message: str, short: list[dict[str, str]]) -> str:
    async with AsyncOpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL, timeout=8, max_retries=0) as client:
        record_model_call()
        response = await client.chat.completions.create(
            model=LLM_MODEL, temperature=0.3,
            messages=[
                {"role": "system", "content": "You are a helpful general assistant. Answer the user directly and concisely. Do not claim access to private data, databases, tools, browsing, or real-time facts unless supplied in context."},
                *[{"role": item["role"], "content": item["content"]} for item in short[-6:]],
                {"role": "user", "content": message},
            ])
        record_model_call(response)
    answer = response.choices[0].message.content
    if not answer:
        raise RuntimeError("model returned empty general answer")
    return answer


async def load_memory_node(state: AgentState):
    history = await asyncio.to_thread(short_memory, state["user_id"], state["session_id"])
    # execute() already durably appended this request; do not send it twice to the model.
    if history and history[-1] == {"role": "user", "content": state["message"]}:
        history = history[:-1]
    return {"short_memory": history, "long_memory": await asyncio.to_thread(long_memory, state["user_id"]),
            "tool_trace": [], "items": [], "raw_items": [], "intent": {}, "request_id": None,
            "rejected_constraints": {}}


async def route_request_node(state: AgentState):
    mode = request_mode(state["message"])
    if mode == "general" and explicit_constraints(state["message"]) and any(
        turn["role"] == "user" and request_mode(turn["content"]) == "recommendation" for turn in state["short_memory"]
    ):
        mode = "recommendation"
    return {"request_mode": mode}


def route_request(state: AgentState) -> Literal["recommendation", "general"]:
    return state["request_mode"]


async def plan_node(state: AgentState):
    history = [turn for turn in state["short_memory"]
               if turn["role"] == "user" and request_mode(turn["content"]) == "recommendation"]
    try:
        baseline = local_intent(state["message"], state["long_memory"], history)
    except ValidationError:
        return {"intent": Intent(needs_clarification=True).model_dump(), "planner": "constraint-validation",
                "plan_error": "预算须为 0～1000000 的有效金额，请修正条件后再推荐。"}
    if PLANNER_MODE == "openai" and LLM_API_KEY:
        try:
            patch = await llm_intent(state["message"], state["long_memory"], state["short_memory"])
            # Validate even if a different model adapter is injected. Explicit
            # deterministic constraints cannot be weakened by model output.
            patch = Intent(**patch).model_dump(exclude_unset=True)
            intent = Intent(**(baseline | patch | explicit_constraints(state["message"]))).model_dump()
            planner = "openai-function-calling"
        except Exception:
            intent, planner = baseline, "local-fallback"
    else:
        intent, planner = baseline, "local-rule-planner"
    return {"intent": intent, "planner": planner}


def clarification_route(state: AgentState) -> Literal["clarify", "profile"]:
    return "clarify" if state["intent"].get("needs_clarification") else "profile"


async def clarification_node(state: AgentState):
    return {"answer": state.get("plan_error") or "请补充品类、预算或内容类型，例如：预算 500 的数码商品，或想看运动直播。", "items": [], "request_id": None}


async def general_answer_node(state: AgentState):
    if PLANNER_MODE == "openai" and LLM_API_KEY:
        try:
            answer, planner = await llm_general_answer(state["message"], state["short_memory"]), "openai-general-chat"
        except Exception:
            answer, planner = local_general_answer(state["message"]), "local-general-fallback"
    else:
        answer, planner = local_general_answer(state["message"]), "local-general-knowledge"
    trace = state["tool_trace"] + [{"tool": "answer_general_question", "status": "success", "resultSummary": {"mode": planner}}]
    # General answers never carry recommendation-only output.
    return {"answer": answer, "planner": planner, "tool_trace": trace, "items": [], "intent": {}, "request_id": None}


async def profile_node(state: AgentState):
    async with httpx.AsyncClient(timeout=3) as client:
        response = await client.get(f"{BACKEND_URL}/api/console-data")
        response.raise_for_status()
        data = response.json()
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
    items, rejected = filter_candidates(state["raw_items"], state["intent"])
    trace = state["tool_trace"] + [{"tool": "filter_candidates", "status": "success", "resultSummary": {"returnedCount": len(items), "rejectedConstraints": rejected}}]
    return {"items": items, "tool_trace": trace, "rejected_constraints": rejected}


async def answer_node(state: AgentState):
    items = state.get("items", [])
    if not items:
        names = {"max_price": "预算", "unknown_price": "价格未知", "preferred_source": "内容类型", "preferred_category": "品类", "exclude_ads": "去广告"}
        conflicts = "、".join(names[key] for key in state.get("rejected_constraints", {})) or "候选不足"
        answer = f"本次召回候选未满足条件（{conflicts}），不代表全库无货。已保留你的约束；你愿意调整预算、品类或内容类型中的哪一项？"
    else:
        top = items[0]; answer = f"已通过真实用户画像、推荐 DAG 与候选过滤得到 {len(items)} 条结果。首选是「{top['title']}」，来源为 {top['source']}，品类为 {top['category']}。"
    trace = state["tool_trace"] + [{"tool": "generate_grounded_answer", "status": "success", "resultSummary": {"grounded": True}}]
    return {"answer": answer, "tool_trace": trace}


async def persist_node(state: AgentState):
    updates = durable_updates(state["message"]) if state.get("request_mode") == "recommendation" and not state.get("intent", {}).get("needs_clarification") else {}
    if updates:
        await asyncio.to_thread(persist_preferences, state["user_id"], updates)
    await asyncio.to_thread(persist_memory, state["user_id"], state["session_id"], "assistant", state["answer"])
    return {}


workflow = StateGraph(AgentState)
workflow.add_node("load_memory", load_memory_node); workflow.add_node("route_request", route_request_node); workflow.add_node("plan", plan_node); workflow.add_node("clarify", clarification_node)
workflow.add_node("load_profile", profile_node); workflow.add_node("run_recommendation", recommend_node); workflow.add_node("filter_candidates", filter_node)
workflow.add_node("build_answer", answer_node); workflow.add_node("answer_general", general_answer_node); workflow.add_node("persist", persist_node)
workflow.add_edge(START, "load_memory"); workflow.add_edge("load_memory", "route_request")
workflow.add_conditional_edges("route_request", route_request, {"recommendation": "plan", "general": "answer_general"})
workflow.add_conditional_edges("plan", clarification_route, {"clarify": "clarify", "profile": "load_profile"})
workflow.add_edge("load_profile", "run_recommendation"); workflow.add_edge("run_recommendation", "filter_candidates")
workflow.add_edge("filter_candidates", "build_answer")
workflow.add_edge("clarify", "persist"); workflow.add_edge("build_answer", "persist"); workflow.add_edge("answer_general", "persist"); workflow.add_edge("persist", END)
# Each invocation starts fresh. Conversation recovery uses user-scoped durable
# messages; an in-process checkpoint must not leak an earlier turn's results.
graph = workflow.compile()

app = FastAPI(title="Mini Reco LangGraph Agent")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

def local_session_active(user_id, session_id):
    return local_life_store.session_active(db_connection, user_id, session_id)


async def execute(request: ChatRequest) -> dict[str, Any]:
    session_id = request.session_id or f"web-{request.user_id}"
    try:
        use_local = request.workflow == "local_life" or (request.workflow == "auto" and (
            request.constraints is not None or is_local_request(request.message)
            or ((request_mode(request.message) != "general" or is_local_followup(request.message))
                and await asyncio.to_thread(local_session_active, request.user_id, session_id))))
        if not use_local:
            return await execute_legacy(request)
        result = await asyncio.to_thread(local_life_store.execute_local, db_connection, request.user_id, session_id,
                                         request.request_id or uuid.uuid4().hex, request.message,
                                         request.constraints.model_dump(exclude_unset=True) if request.constraints is not None else None, SHORT_TTL)
        try:
            await asyncio.to_thread(redis_client.delete, f"agent:session:{request.user_id}:{session_id}")
        except redis.RedisError:
            pass  # The local workflow recovers from its MySQL snapshot, not Redis.
        return result
    except (pymysql.MySQLError, local_life_store.CatalogLimitError):
        return {"agent": "local-life-agent", "mode": "local_life", "status": "tool_error", "sessionId": session_id,
                "answer": "本次任务因数据服务不可用或查询范围超出上限而结束，未返回方案。请保留同一请求标识重试；已提交的结果可重放。",
                "items": [], "toolTrace": [{"tool": "local_data_tools", "status": "error"}]}


async def execute_legacy(request: ChatRequest) -> dict[str, Any]:
    session_id = request.session_id or f"web-{request.user_id}"
    began = time.perf_counter()
    usage = {"llmCalls": 0, "inputTokens": 0, "outputTokens": 0}
    usage_token = llm_usage.set(usage)
    try:
        # The incoming message is durable before orchestration starts. Recovery
        # restores the conversation, not an automatically resumed graph task.
        await asyncio.to_thread(persist_memory, request.user_id, session_id, "user", request.message)
        state = await graph.ainvoke({"user_id": request.user_id, "message": request.message, "session_id": session_id})
        status = "clarification" if state.get("intent", {}).get("needs_clarification") else (
            "no_match" if state.get("request_mode") == "recommendation" and not state.get("items") else "completed")
        result = {"agent": "langgraph-reco-agent", "status": status, "mode": state.get("request_mode"), "planner": state.get("planner"), "sessionId": session_id, "intent": state.get("intent", {}), "answer": state.get("answer"), "items": state.get("items", []), "tools": TOOL_DEFINITIONS, "toolTrace": state.get("tool_trace", []), "recommendationRequestId": state.get("request_id")}
    except (httpx.HTTPError, pymysql.MySQLError, redis.RedisError, TimeoutError):
        answer = "本次任务因工具或存储暂时不可用而结束，未返回推荐。请稍后重试；已保存的对话可恢复，但不会自动续跑中断节点。"
        try:
            await asyncio.to_thread(persist_memory, request.user_id, session_id, "assistant", answer)
        except (pymysql.MySQLError, redis.RedisError):
            pass
        result = {"agent": "langgraph-reco-agent", "status": "tool_error", "sessionId": session_id,
                  "answer": answer, "items": [], "intent": {}, "recommendationRequestId": None,
                  "toolTrace": [{"tool": "workflow_dependency", "status": "error"}]}
    finally:
        llm_usage.reset(usage_token)
    result["metrics"] = {"elapsedMs": round((time.perf_counter() - began) * 1000, 2), **usage,
                         "modelCostUsd": 0 if usage["llmCalls"] == 0 else None}
    return result

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

@app.post("/api/local-life/recommend")
async def local_recommend(request: ChatRequest):
    return await execute(request.model_copy(update={"workflow": "local_life"}))

@app.get("/api/local-life/tasks/{task_id}")
async def local_task(task_id: str, token: str):
    current = resolve_demo_session(token)
    return await asyncio.to_thread(local_life_store.read_task, db_connection, int(current["user_id"]), task_id)

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
            try:
                request = ChatRequest(user_id=int(current["user_id"]), message=payload.get("message", ""), session_id=current["session_id"],
                                      workflow=payload.get("workflow", "auto"), request_id=payload.get("request_id"))
                await websocket.send_json({"event": "agent_started", "sessionId": request.session_id})
                result = await execute(request)
            except ValidationError:
                await websocket.send_json({"event": "agent_error", "message": "请求格式无效，请检查消息和请求标识。"})
                continue
            except HTTPException as exc:
                await websocket.send_json({"event": "agent_error", "message": exc.detail})
                continue
            for trace in result["toolTrace"]:
                await websocket.send_json({"event": "tool_completed", "data": trace})
            await websocket.send_json({"event": "agent_completed", "data": result})
    except WebSocketDisconnect: return
