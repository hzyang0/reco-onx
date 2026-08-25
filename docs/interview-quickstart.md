# Mini Reco Agent：新手快速入门与秋招面试备战手册

> 目标：不要求背完全部代码；完成本手册后，你应能独立运行项目、画出主链路、解释关键取舍，并在后端/Agent 面试中讲清楚自己的设计。

## 1. 用一句话理解项目

这是一个以 **LangGraph Agent 为主入口** 的智能推荐系统。它先理解用户自然语言，再根据问题类型进入“推荐执行”或“常规问答”分支；推荐分支会受控调用 Java 推荐后端，得到真实候选，而不是由模型编造商品。

```text
浏览器（登录、多轮对话、推荐卡片）
  -> FastAPI + WebSocket :18081
  -> LangGraph：记忆 -> 路由 -> Tool 编排 -> 持久化
       ├─ 常规问答：本地领域知识 / 可选 LLM
       └─ 推荐执行：画像 -> recommend -> 过滤 -> 基于事实回答
                              |
                              v
                   Java 推荐后端 :18082
                   Prepare -> 三路并行召回 -> 特征/混排 -> 过滤
  -> Redis：短期上下文；MySQL：用户、候选、长期偏好、对话审计
```

## 2. 当前真实技术边界

| 已实际使用 | 当前未使用，不应写入简历 |
| --- | --- |
| Python 3.11、FastAPI、LangGraph、WebSocket、OpenAI Python SDK（可选）、Redis、MySQL、Java 17、Flyway、Docker Compose | React、SQLite、RAG、向量数据库、HNSW、LangChain Agent Executor、ReAct 多 Agent |

模型密钥未配置时，项目仍能完整跑推荐 Agent 和项目领域问答。只有配置支持聊天的 OpenAI 兼容模型后，常规问答才可扩展为开放问答。

## 3. 第一次运行：先体验，再读代码

### 3.1 启动

```powershell
cd D:\User\Desktop\实习\mini-reco-access-layer
mvn -DskipTests package
docker compose up --build -d
```

打开 <http://localhost:18081/>。后端调试地址为 <http://localhost:18082/health>。

健康检查：

```powershell
Invoke-RestMethod http://localhost:18081/health
docker compose ps
```

### 3.2 必做体验清单

1. 选择“数码发烧友”进入对话页。
2. 输入：`预算 500 元以内的数码商品，不要广告`。观察推荐卡片与 Tool 轨迹。
3. 继续输入：`预算改为 300`。理解同一 `sessionId` 的多轮上下文。
4. 输入：`召回是什么？`、`混排是什么？`、`DAG 是什么？`，观察它们进入常规问答，不推荐商品。
5. 刷新页面，确认已完成对话仍可恢复。
6. 点击“新建对话”，确认新会话不带旧对话上下文。

### 3.3 首次验收命令

```powershell
mvn clean verify
docker compose exec -T agent python -m compileall app
```

`mvn clean verify` 中可能有 Testcontainers 测试被跳过：这通常是本机未配置 Docker/Testcontainers 条件导致，并不等同于单元测试失败。看 Maven 最后的 `BUILD SUCCESS` 和 Failure/Error 数量。

## 4. 七天学习计划

### 第 1 天：只讲产品，不看代码

回答三个问题：用户从哪里进入？能做什么？答案从哪里来？

你应该能说：

> 用户通过体验登录选择画像后进入 WebSocket 多轮对话。推荐类请求返回真实推荐卡片，候选来自 Java 推荐服务和 MySQL；解释类请求进入常规问答。页面不会直接呈现后端 JSON，而是将回答、卡片、偏好和 Tool 轨迹分别渲染。

### 第 2 天：理解 Agent 状态图

阅读 [agent-service/app/main.py](../agent-service/app/main.py)，只看以下部分：

1. `AgentState`：每轮工作流携带的状态。
2. `load_memory_node`：读取短期和长期记忆。
3. `route_request_node`、`request_mode`：分流推荐与问答。
4. 最后的 `StateGraph`：节点和边如何连起来。

画出这个图：

```text
START -> load_memory -> route_request
  ├─ general -> answer_general -> persist -> END
  └─ recommendation -> plan
       ├─ clarify -> persist -> END
       └─ load_profile -> run_recommendation -> filter_candidates
             ├─ build_answer -> persist -> END
             └─ relax -> run_recommendation（最多一次）
```

**面试答法：** LangGraph 用于把有状态、带条件分支和有限重试的业务流程显式建模；相比一串 if/else，更容易限制循环、观察节点和扩展新分支。

### 第 3 天：理解 Tool 编排

重点函数：`profile_node`、`recommend_node`、`filter_node`、`answer_node`、`general_answer_node`。

推荐分支的 Agent Tool 链：

```text
get_user_profile -> recommend -> filter_candidates -> generate_grounded_answer
```

Tool 不是“任意函数”。一个合格 Tool 应有明确名称、输入、输出、权限、超时、审计和失败处理。当前 `recommend` Tool 通过 HTTP 调 Java 后端；模型本身不能拼 SQL、不能任意访问数据库、不能直接决定商品。

**面试答法：** 我把推荐后端当作领域 Tool，而不是让 LLM 直接查询数据。这样模型只负责计划，真实数据读取和过滤由确定性服务完成，结果可通过 requestId 和 Tool Trace 追溯。

### 第 4 天：理解推荐 Tool 内部

阅读 [architecture.md](architecture.md) 和 Java 推荐模块。只需记住：

```text
Prepare
  -> goods / live / ad 并行 Recall
  -> Online Feature 与 MixRank
  -> Filter
  -> PostProcess
```

要能解释：

- **Prepare**：收集用户画像、AB 参数、地址等公共上下文。
- **Recall**：从不同候选源快速粗筛，目标是覆盖和速度。
- **并行召回**：多路彼此独立，串行会把耗时相加，并行由最慢一路决定。
- **Online Feature**：补齐价格、库存、状态等可能实时变化的数据。
- **MixRank/混排**：将不同来源候选按场景策略合并排序。
- **Filter**：落实预算、品类、内容来源、广告等硬约束。
- **PostProcess**：输出统计、打点、最终结果。

### 第 5 天：理解记忆与恢复

重点函数：`persist_memory`、`short_memory`、`persist_preferences`、`conversation_history`。

| 数据 | 位置 | 为什么 |
| --- | --- | --- |
| 短期上下文 | Redis List + TTL | 高频、最近几轮、自动过期，例如“预算再低一点” |
| 对话审计 | MySQL `agent_conversations` | 可查询、刷新页面恢复、Redis 丢失后回填 |
| 长期偏好 | MySQL `agent_long_term_memories` | 只保存明确、稳定、可解释的偏好 |

恢复逻辑：Redis 有会话键时直接读取；Redis 丢失时查询 MySQL 最近 8 条有效消息并写回 Redis。用户消息在工作流开始前先写入 MySQL，因此 Agent 中断时最后一个问题仍可被看到和重试。

当前 `MemorySaver` 是进程内 LangGraph checkpoint，不能从宕机节点自动续跑。生产环境需要持久化 checkpointer、Tool 幂等键、执行状态表和重试策略。

### 第 6 天：理解 FastAPI、WebSocket 与前端交互

重点文件：`main.py` 的 FastAPI 路由、[index.html](../agent-service/app/static/index.html)。

### 第 7 天：不看代码口述与模拟追问

先用 3 分钟讲完整链路，再用 1 分钟分别讲 LangGraph、Tool、Redis/MySQL、推荐 DAG、WebSocket。最后录音复听，删除“就是”“大概”“可能”等含糊表述。

## 5. FastAPI：你需要掌握什么

FastAPI 是 Python 的 Web 框架。它负责把 HTTP/WebSocket 请求接收进来、校验输入、调用 Agent 工作流并返回结果。

本项目关键接口：

| 接口 | 用途 |
| --- | --- |
| `GET /` | 返回内置对话页面 |
| `GET /health` | 检查 Agent、Redis、Java 后端是否可用 |
| `GET /api/users` | 获取体验用户画像 |
| `POST /api/auth/login` | 创建演示会话 token 与 sessionId |
| `POST /api/chat` | 便于接口调试的 HTTP 对话入口 |
| `WS /ws/chat` | 前端实时对话主入口 |
| `GET /api/conversations/{sessionId}` | 恢复历史消息 |
| `GET /api/memory/{userId}` | 查询长期偏好 |

### Pydantic 输入校验

`ChatRequest`、`LoginRequest` 是 Pydantic 模型。它们限制 `user_id > 0`、消息长度、sessionId 长度等。

**为什么需要它？** 不要相信浏览器传来的数据。输入校验能在业务逻辑前阻止空消息、超长文本、非法 ID，避免后续数据库、模型和工具承担无效负载。

### FastAPI 常见追问

**为什么用 async？**

Agent 会等待 HTTP 调 Java 服务、模型 API、WebSocket I/O 等网络操作；`async/await` 能让线程在等待 I/O 时处理其他请求。注意：PyMySQL 是同步驱动，重 CPU 或同步数据库操作很多时，应使用线程池、异步驱动或拆到独立服务，不能误以为所有代码都“天然异步”。

**为什么 health 要检查依赖？**

Agent 本身进程还活着不代表能推荐。健康检查同时 ping Redis、请求 Java `/health`，才能避免流量进入一个无法完成 Tool 调用的实例。

**生产环境 FastAPI 还要补什么？**

认证鉴权、CORS 白名单、请求限流、统一异常处理、结构化日志、OpenTelemetry Trace、请求超时、优雅关闭、多 worker 和反向代理。

## 6. WebSocket：你需要掌握什么

HTTP 是“一问一答”；WebSocket 建立连接后，服务端和浏览器可双向发送多条消息。项目用它把 Agent 过程呈现给用户。

当前事件：

```text
agent_started
tool_completed（可多次）
agent_completed
agent_error
```

浏览器发送 `{token, message}`；服务端从 token 中解析登录用户和 `sessionId`，不信任浏览器额外传来的 userId。

### WebSocket 常见问题与回答

**为什么不用轮询？**

轮询会不断发无效 HTTP 请求，过程状态有延迟；WebSocket 更适合对话、流式输出和 Tool 进度。当前服务发送的是工具完成事件；若接入流式 LLM，可继续增加 token 流事件。

**断连怎么办？**

页面刷新后通过 sessionId 从 MySQL 恢复已完成消息；当前前端没有自动指数退避重连机制，生产应补心跳 ping/pong、断线重连、消息序号和幂等 clientMessageId。

**安全注意点？**

WebSocket 握手后同样要鉴权。本项目使用演示 token；生产需要 HttpOnly Cookie 或短期 JWT、Origin 校验、权限检查、单连接消息限流，且不能让客户端伪造 userId/sessionId。

## 7. LLM 与本地规则：怎么讲才真实

### 本地规则做什么

- `request_mode`：区分推荐执行与常规问答。
- `local_intent`：提取品类、预算、广告、场景、来源。
- `local_general_answer`：解释 Agent、记忆、召回、混排、在线特征、DAG 等项目知识。

规则存放在 `agent-service/app/main.py`，不是数据库配置。它的优点是离线、确定、方便验收；缺点是覆盖有限、扩展时维护成本增加。

### LLM 模式做什么

设置：

```text
AGENT_PLANNER=openai
LLM_BASE_URL=https://api.openai.com/v1
LLM_MODEL=<支持 Function Calling 的模型>
LLM_API_KEY=<密钥管理系统提供，不提交 Git>
```

推荐分支中 LLM 仅调用 `plan_recommendation` 生成受约束的意图；实际 Tool 仍由服务端执行。常规问答分支则可调用聊天模型；模型异常自动回退本地知识回答。

**不能说：** “LLM 直接查 MySQL 并生成推荐结果。”

**应该说：** “LLM 负责理解与计划；服务端按白名单执行 Tool；最终候选和过滤结果来自确定性后端。”

## 8. 高频面试问答

### Q1：为什么用 LangGraph，不直接写 if/else？

推荐请求有记忆、路由、澄清、候选为空后的有限放宽、持久化等状态与分支。LangGraph 把这些显式表达成节点和边，便于查看流程、限制循环、增加节点和测试分支。

### Q2：什么是 Tool？为什么不让模型直连数据库？

Tool 是输入输出明确、可鉴权、可审计、可限制的领域能力。本项目的 Tool 是画像、推荐、过滤、基于事实回答等。模型直连数据库会带来 SQL 注入、越权、数据泄露和难以审计的问题；Tool 边界能把不确定的模型和确定的业务隔开。

### Q3：`recommend` Tool 和推荐 DAG 是什么关系？

`recommend` 是 Agent 视角的一个 Tool；其内部调用 Java 推荐服务，Java 服务执行 Prepare、并行 Recall、在线特征、混排、过滤和后处理 DAG。前者负责“何时调用”，后者负责“怎样产生候选”。

### Q4：为什么 Redis 和 MySQL 都要用？

Redis 适合低延迟、带 TTL 的热会话；MySQL 适合用户偏好、审计、查询和恢复。只用 Redis 会丢失审计和恢复能力；只用 MySQL 则不适合每轮高频读取短期上下文。

### Q5：长期记忆为什么不保存所有聊天？

所有聊天包含临时需求、噪声甚至错误输入，会污染偏好。长期记忆只保存用户明确提出的稳定约束，例如预算、品类、是否去广告；它可覆盖更新、可解释、可删除。

### Q6：候选为空怎么办？

系统最多进入一次 `relax` 节点，只放宽与当前显式需求冲突的旧来源偏好，然后再次召回；仍为空则明确告知用户调整预算或条件。有限次数能避免无限循环和不可控成本。

### Q7：为什么要同时有 `requestId` 和 `toolTrace`？

`requestId` 用于跨推荐后端追踪一次请求；`toolTrace` 描述 Agent 在这轮对话中调用过什么 Tool、是否成功、结果摘要是什么。前者偏链路关联，后者偏 Agent 决策可解释性。

### Q8：常规问答为什么会有本地降级？

模型服务可能超时、限流、密钥失效或成本受限。项目知识问答可用本地规则提供确定性兜底；开放问题会明确提示未配置模型，而不是编造答案。

### Q9：当前项目距离生产还差什么？

真实登录/SSO、权限体系、WebSocket 重连与限流、持久化 LangGraph checkpoint、Tool 幂等与重试、LLM 安全审核与密钥管理、观测/告警、灰度发布、压测、真实召回索引和模型排序。

## 9. 常见排错手册

| 现象 | 排查顺序 | 常见原因与解决 |
| --- | --- | --- |
| 打不开 `18081` | `docker compose ps`、`docker compose logs agent` | Agent 未启动或端口被占用；重建 `docker compose up --build -d` |
| health 返回 503 | 看 agent/app/redis 日志 | Redis 或 Java 后端未健康；先检查 `docker compose ps` |
| 没有推荐卡片 | 强制刷新、查看 WebSocket 事件 | 浏览器缓存旧静态页面；使用 `Ctrl+F5`，确认 `agent_completed` 有 items |
| 页面提示会话失效 | 重新选择画像登录 | 演示 token 在 Redis 中带 TTL；生产应接入真实身份系统与刷新 token |
| 问“召回”却推荐商品 | 检查 `request_mode` 关键词规则 | 路由规则需补充解释型词；最终应改为 LLM 分类器或配置化 Intent 分类 |
| 常规问题只返回本地提示 | 检查 `.env` 与 compose 环境变量 | 未配置 `AGENT_PLANNER=openai`/模型 Key；不要把 Key 提交到 Git |
| Redis 丢失上下文 | 查询 MySQL 对话记录 | 有效期内会自动回填最近 8 条；超过 TTL 后按产品策略删除或归档 |
| Java 服务 404/超时 | `docker compose logs app`，访问 `18082/health` | Java 容器未就绪、接口路径变化、网络超时；Agent Tool 应设置 timeout 和失败降级 |

## 10. 面试前最后准备

### 必须亲手完成

- 从零执行一次 `docker compose up --build -d`。
- 用浏览器走完推荐、多轮追问、领域问答、刷新恢复、新建对话。
- 用 Postman 或 PowerShell 调一次 `/api/chat`。
- 停掉 Redis 后观察健康检查与恢复逻辑（只在本地演示环境操作）。
- 画一张 Agent 状态图和一张推荐 DAG 图。
- 不看文档录制一段 3 分钟项目介绍。

### 90 秒项目介绍模板

> 我做的是一个 LangGraph 智能推荐 Agent。用户从 FastAPI 的 WebSocket 对话入口进入，Agent 先读取 Redis 短期上下文和 MySQL 长期偏好，然后将请求路由为推荐执行或常规问答。推荐分支会按白名单编排画像、推荐、过滤和基于事实回答等 Tool，其中 `recommend` Tool 调用 Java 后端的三路并行召回和混排 DAG。候选来自真实数据库和后端服务，模型不直接访问数据库。系统用 Redis 保存会话热数据，用 MySQL 保存审计和稳定偏好；Redis 丢失时能从 MySQL 恢复最近对话。前端通过 WebSocket 展示回答、推荐卡片和 Tool 轨迹，整体以 Docker Compose 部署。

### 最容易被追问的三个点

1. **“这真是 Agent 吗？”** 回答 Goal、Router/Planner、Tools、State/Memory、Orchestrator、Observability 六个组成部分，并对应到项目实现。
2. **“为什么不直接用大模型？”** 回答可控性、真实数据、权限边界、成本、稳定性与降级。
3. **“如果让我继续做？”** 优先说持久化 checkpoint、真实认证、Tool 幂等、流式输出、配置化路由、离线评测和真实排序模型；不要泛泛地说“加 RAG”。

## 11. 阅读优先级清单

必须精读：

1. `agent-service/app/main.py`
2. `agent-service/app/static/index.html`
3. `compose.yaml`
4. `docs/architecture.md`
5. `docs/langgraph-agent-main.md`

了解即可：

1. Java 每个 Operator 的全部实现细节。
2. 全部 SQL 种子数据。
3. 所有前端 CSS。

最终标准不是“能背所有类名”，而是能用自己的话说明：请求怎么进来、Agent 如何决定做什么、Tool 如何拿到真实结果、状态在哪里、失败如何恢复、为什么这样取舍。
