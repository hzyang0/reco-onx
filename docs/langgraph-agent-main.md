# LangGraph Agent 主架构与学习路线

> 本地生活主流程、完整报价与任务恢复已经实现，先读 [本地生活指南](LOCAL-LIFE-GUIDE.md)。本文主要解释保留的通用推荐分支。

## 1. 项目定位

当前项目的主产品是“自然语言驱动的推荐 Agent”，而不是一个 Java 推荐接口集合。用户访问 `:18081` 的 FastAPI 服务；Agent 将意图转为一个受控工作流，并把 Java 推荐系统当作可调用的领域工具后端。

这一区分很重要：Agent 负责不确定的语言理解、状态编排和分支决策；Java 后端负责确定的数据读取、召回、过滤与排序。模型不直接碰数据库，也不直接决定某个商品能否被推荐。

## 2. 一次请求如何执行

用户输入：`给我推荐预算 500 元以内的数码商品，不要广告`。

在进入推荐图之前，`route_request` 会做意图路由。命中商品、直播、品类、预算、广告等词的请求进入推荐工作流；其他请求进入 `answer_general_question`。当配置 `AGENT_PLANNER=openai` 和模型密钥时，后者使用独立的聊天调用处理开放问答；未配置模型时则只回答项目内置知识，明确提示能力边界，不伪造通用答案。

```text
FastAPI POST /api/chat 或 WebSocket /ws/chat
  -> Redis 读取同 session 最近消息
  -> MySQL 读取可解释的长期偏好
  -> plan：本地规则 / 可选 LLM Function Calling 生成结构化 intent
  -> get_user_profile Tool：调用 Java 后端
  -> recommend Tool：调用 Java DAG（三路并行召回）
  -> filter_candidates Tool：执行预算、品类、来源、广告约束
  -> generate_grounded_answer：仅基于实际候选生成回答
  -> Redis 写短期消息；MySQL 写长期显式偏好与对话审计
```

如果候选为空，直接返回 `no_match`，说明本次候选被哪些约束排除，并询问用户愿意调整哪项；不擅自放宽条件，也不把本次召回为空说成全库无货。工具或存储不可用时返回 `tool_error`，不会携带上一轮结果。

## 3. 代码从哪里读起

| 优先级 | 文件 | 你要理解什么 |
| --- | --- | --- |
| 1 | `agent-service/app/main.py` 的 `AgentState` | 一次工作流要携带哪些状态：意图、画像、候选、轨迹、回答 |
| 2 | 同文件的 `workflow = StateGraph(...)` | 推荐、澄清与一般问答分支如何终止 |
| 3 | `app/constraints.py` 与 `plan_node` | 自然语言先被收敛为 `scene/category/budget/exclude_ads` 等可验证字段 |
| 4 | `profile_node`、`recommend_node`、`filter_node` | Tool 不直接访问表，而是经 HTTP 调 Java 服务，形成明确边界 |
| 5 | `load_memory_node`、`persist_node` | Redis 短期记忆与 MySQL 长期记忆的职责区别 |
| 6 | `src/main/java/...`、`db/migration/` | Java 工具后端怎样完成三路召回和事实数据读取 |
| 7 | `compose.yaml` | 四个服务如何部署、端口为什么分为 18081/18082 |

建议先把 `main.py` 的流程画成图，再读 Java；不要一开始钻进 Java 的每个算子。

## 4. Agent 的关键部件是否齐全

| Agent 部件 | 当前实现 | 说明 |
| --- | --- | --- |
| Goal | 推荐满足用户约束的真实内容 | 由 `/api/chat` 请求表达 |
| Planner | `local_intent`；可选 OpenAI Function Calling | 前者可离线验收，后者处理更复杂表达 |
| Orchestrator | LangGraph `StateGraph` | 编排节点、条件分支与终止 |
| Tools | 画像、推荐、过滤、基于事实回答 | 均有受限输入输出和 `toolTrace` |
| Short-term memory | Redis List + TTL | 保存当前会话的最近消息，自动过期 |
| Long-term memory | MySQL `agent_long_term_memories` | 保存明确、稳定、可解释的偏好 |
| Audit | MySQL `agent_conversations` + requestId | 用于追溯每次对话和推荐请求 |
| UI / transport | FastAPI HTTP + WebSocket 控制台 | 便于用户看到工具轨迹 |
| Fallback | 模型异常时切到本地 Planner | 不让外部模型故障阻塞推荐主链路 |

当前没有 RAG、向量库或多 Agent 协作，因为业务事实是结构化的用户、商品和行为数据；引入它们不会提升该场景的首要价值。未来接入商品长描述、运营知识库或帮助文档时，再用向量检索更合理。

## 5. 记忆为什么这样设计

- Redis 短期记忆：高频读写、按 userId + session 隔离、天然 TTL，适合“刚才说不要广告”“换成视频”的上下文。
- MySQL 长期记忆：需要可查询、审计、覆盖更新和与用户业务数据关联，适合明确表达的长期品类、预算、去广告等偏好。
- 不能把每句聊天都沉淀为长期偏好：临时需求会污染用户画像。因此仅在“记住／以后／长期偏好”且没有临时标记时更新本轮明确字段，支持 false 和删除旧值，并保留来源与更新时间。

选择 MySQL 并不表示 Redis 不重要：MySQL 是事实与审计层，Redis 是热状态层。相比 SQLite，MySQL 更适合多容器共享、连接并发和现有业务数据共存；相比向量数据库，它更适合本项目的精确结构化条件查询。

### 对话、上下文与中断恢复

页面登录后会生成并保存一个 `sessionId`，后续每一轮 WebSocket 消息都由服务端从登录会话中取出该 ID，而不是相信浏览器传来的 userId。相同 `sessionId` 表示同一段多轮对话；“新建对话”会显式生成新的 ID，避免旧上下文影响新问题。

用户界面不展示原始 JSON：聊天区渲染用户气泡、Agent 的自然语言回答和推荐卡片；右侧单独显示长期偏好与工具执行轨迹；左侧摘要显示最近短期上下文。后端仍返回结构化结果，目的是让前端可稳定渲染、测试与追溯。

中断恢复分两层：

1. 浏览器刷新：浏览器保存体验会话信息，重新进入后查询 MySQL `agent_conversations`，恢复历史气泡。
2. Redis 键失效或重启：`short_memory` 发现 Redis 没有会话键后，从有效期内的 MySQL 审计记录读取最近 8 条消息，写回 Redis，再交给 Planner 使用。

用户消息会在工作流开始前写入审计，因此 Agent 执行中断时，重新登录仍能看到最后一个问题并重新发送。每次调用使用新的图状态，已移除跨轮累积的进程内 `MemorySaver`，避免澄清时残留上一轮候选。当前恢复的是有效期内最近 8 条消息，不是从宕机节点自动续跑。需要任务级恢复时，应增加持久化任务状态、Tool 幂等键及受总预算约束的重试。

这里的“短期记忆”是最近会话上下文，不应无限累积；“长期记忆”是用户明确说出的、可覆盖更新的偏好键值，例如数码、预算 500、不要广告。两者混在一起会造成上下文膨胀和偏好污染。

## 6. LLM 在哪里、为什么可选

没有 `LLM_API_KEY` 时，项目仍是一个完整可运行的 Agent：本地 Planner 产出结构化意图，LangGraph 调 Tools、记忆与分支仍真实执行。配置模型后，OpenAI SDK 通过 Function Calling 限制模型只能调用 `plan_recommendation`，返回 JSON 意图。

不要说“LLM 直接调用数据库”。正确表述是：LLM 负责计划，应用根据白名单执行工具。这样可验证参数、限制步骤、记录审计，并避免提示注入导致越权。

## 7. 面试的 60 秒讲法

“我把项目改造成 Agent 主、推荐后端次的架构。Python FastAPI 是唯一主入口，LangGraph 把一次会话拆成读记忆、规划、获取画像、调用推荐、过滤、回答和持久化节点；本轮明确约束覆盖历史条件，候选为空时解释约束冲突并请求用户选择，不自动放宽。Java 服务没有被丢掉，而是被封装为推荐 Tool，继续完成真实的用户画像、goods/live/ad 并行召回和混排。Redis 保存带 TTL 的会话记忆，MySQL 保存长期偏好和审计。LLM 是可选的 Function Calling Planner，不能访问 SQL；调用失败会回退本地规划器，所以推荐链路不会因为模型不可用而中断。”

## 8. 常见追问

**为什么不把 Agent 全写在 Java？**

可以，但 LangGraph/Python 生态更适合表达状态图、检查点和 LLM 工具调用；保留 Java 可复用已有高性能推荐领域代码。关键不是语言，而是 Agent 与领域工具的边界。

**为什么不用 ReAct 无限循环？**

推荐场景工具链固定且风险可控，显式状态图更容易限制循环、压测和排障。当前每轮最多调用一次推荐工具；复杂任务也必须明确步数、超时和费用预算。

**为什么返回结果可信？**

候选来自 Java 后端和 MySQL 的真实种子业务数据；过滤由确定性代码完成；答案仅引用过滤后的候选；每次都有 toolTrace 和 requestId。

**如何上线？**

还需补充身份认证、按登录态绑定 userId、限流、密钥托管、模型内容安全、分布式 LangGraph checkpoint、Redis 高可用、数据库备份、链路追踪、更大规模的独立评测及灰度发布。当前是可运行演示工程，不应直接暴露到公网。
