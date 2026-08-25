# 智能推荐 Agent：新手入门、代码导读与面试手册

> 适用对象：会一点 Java/MySQL，但第一次系统学习 Agent 的同学。目标不是背概念，而是能独立跑通项目、读懂链路、讲清设计取舍，并应对秋招追问。

## 0. 先用一句话理解项目

这是一个把“自然语言需求”转成“真实推荐动作”的智能推荐 Agent：模型或本地规划器负责理解用户想要什么，后端只允许它调用白名单工具；工具从 MySQL 读取真实用户画像和候选内容，复用三路并行召回 DAG，经过过滤后给出可追溯的推荐结果和解释。

它不是“聊天机器人套一个商品列表”。关键约束是：LLM 不直接访问数据库、不直接决定最终商品、不执行任意代码；它只能输出受限的计划，真正的数据查询与执行由后端完成。

## 1. 简历怎么写

### 推荐版本（后端/Agent 方向）

**Mini Reco Agent - 智能推荐编排系统**  
技术栈：Java 17、MySQL、Flyway、HikariCP、Docker、DAG、OpenAI-Compatible Function Calling

项目描述：面向电商推荐场景构建智能推荐 Agent，将自然语言需求转化为结构化约束，通过受限 Tool Calling 调用用户画像、三路并行召回、在线特征、候选过滤与推荐诊断能力；支持 MySQL 短期/长期记忆、模型异常降级、行为反馈闭环和可观测性。

核心技术：

1. **Agent 规划与安全执行**：设计本地规则规划器与 OpenAI 兼容 Function Calling 双模式；LLM 仅生成受约束的 `AgentIntent`，服务端通过工具白名单、参数校验和最大工具步数限制执行 `get_user_profile → recommend → filter_candidates → generate_grounded_explanation`，避免模型越权访问与结果幻觉。
2. **推荐工具化与并行编排**：将已有推荐链路封装为 Agent Tool，复用 Prepare、goods/live/ad 三路并行召回、在线特征、混排、过滤、后处理 DAG；场景化控制商城、视频流和买家首页的内容结构，并返回 requestId 与 Tool Trace 实现可追溯。
3. **短期/长期记忆**：使用 MySQL 设计 `agent_conversations` 与 `agent_long_term_memories`；短期记忆按 session 保存最近对话并设置 TTL，长期记忆只沉淀明确的品类、预算、去广告等稳定偏好，携带置信度与来源，支持跨进程恢复。
4. **工程化闭环**：通过 Flyway 管理 9 张业务表，HikariCP 管理连接池；行为反馈实时更新用户画像并影响后续推荐，提供 health/live/Prometheus 指标、Docker 部署、单元测试和 MySQL 集成验收。

### 30 秒口述版

“我做的是一个智能推荐 Agent。用户输入‘预算 500 的数码商品，不要广告’后，规划器先把需求变成结构化意图；随后服务端按白名单调用用户画像、推荐 DAG、候选过滤和解释工具。最终结果来自 MySQL 和真实三路召回，不是模型生成的。系统还有两类记忆：短期记忆保存会话上下文，长期记忆保存明确偏好；模型不可用时自动回退到本地规则规划，保证推荐主链路可用。”

## 2. Agent 到底是什么

一个实用 Agent 至少包括五件事：目标、规划器、工具、状态/记忆、执行控制。

| 概念 | 本项目对应实现 | 用大白话解释 |
| --- | --- | --- |
| Goal | 推荐或诊断 | 用户要解决的问题 |
| Planner | `RuleBasedAgentPlanner` / `OpenAiCompatibleAgentPlanner` | 决定“用户到底想要什么” |
| Tools | 用户画像、推荐、过滤、解释 | Agent 能动手使用的能力 |
| Memory | 两张 MySQL 记忆表 | 记住刚聊了什么、长期喜欢什么 |
| Executor | `RecommendationAgentService` | 检查计划、按顺序调用工具、限制风险 |
| Observability | Tool Trace、Metrics、requestId | 出问题时知道每一步做了什么 |

不要把 Agent 理解成“必须有 LLM”。LLM 解决复杂语言理解和规划；Tools 负责真实动作；Memory 提供上下文；Executor 负责安全和确定性。没有 Tools 的 LLM 更像聊天机器人；没有执行边界的 LLM 不能直接上线。

## 3. 一次请求完整发生了什么

以“我想看数码直播，预算 500，不要广告”为例：

```text
浏览器 POST /api/agent/chat
  ↓
读取短期记忆（最近 8 条）+ 长期记忆（品类/预算/去广告）
  ↓
Planner 输出 AgentIntent：video_feed + live + digital + 500 + excludeAds
  ↓
Executor 校验参数、记录用户消息
  ↓
Tool 1: get_user_profile（MySQL）
Tool 2: recommend（复用推荐 DAG，goods/live/ad 并行召回）
Tool 3: filter_candidates（预算、品类、来源、广告约束）
Tool 4: generate_grounded_explanation（只根据真实候选解释）
  ↓
写入长期偏好 + 写入助手消息 + 上报指标
  ↓
返回 items、intent、tools、toolTrace、requestId
```

注意：即使用户要求“不看广告”，底层三路召回仍可用于观测和统一链路；最终过滤工具会移除广告。这样既保留系统的召回能力，也保证用户约束真正生效。

## 4. 新手学习路线（建议 7 天）

### 第 1 天：先跑起来，不读代码

```powershell
cd D:\User\Desktop\实习\mini-reco-access-layer
mvn -DskipTests package
docker compose up --build -d
```

打开 `http://localhost:18081/`。先体验三件事：切换用户并运行推荐；在 Agent 输入框输入“预算 500 的数码商品，不要广告”；点击“查看长期记忆”。

验收命令：

```powershell
Invoke-RestMethod http://localhost:18081/health
Invoke-RestMethod 'http://localhost:18081/api/agent/memory?userId=456'
```

### 第 2 天：理解传统推荐主链路

依次读：`MiniRecoApplication`、`RecommendHttpHandler`、`RecommendService`、`ApplicationWiring`、`ParallelDagOperatorExecutor`。先画出 DAG：Prepare → Recall → (OnlineFeature 与 MixRank 并行) → Filter → PostProcess。

必须能回答：为什么三路召回要并行？为什么 OnlineFeature 和 MixRank 可以并行？为什么 Filter 必须等二者完成？

### 第 3 天：理解数据与行为反馈

读 `JdbcDataRepository`、`V1__schema_and_seed.sql`、`UserEventHttpHandler`。用控制台点击、加购、购买，观察用户画像和后续推荐变化。

必须能回答：为什么曝光不直接改用户偏好？为什么行为上报需要幂等？为什么不能把所有候选数据写成一张大表？

### 第 4 天：理解 Agent 的入口与意图

读 `AgentHttpHandler`、`AgentIntent`、`AgentIntentParser`、`RuleBasedAgentPlanner`。尝试输入“推荐”“我想看跑步直播”“预算 300 的家居商品”，对照返回的 `intent`。

重点不是正则本身，而是理解：自然语言必须先收敛为可校验的结构化参数，才能安全执行。

### 第 5 天：理解 Tools 和 Executor

精读 `RecommendationAgentService`。它是 Agent 的核心：读取记忆、选择 Planner、按工具白名单执行、过滤候选、生成基于事实的解释、写回记忆和指标。

在控制台或 JSON 中查看 `toolTrace`。每条 trace 里有工具名、参数摘要、结果摘要和状态。

### 第 6 天：理解 LLM 与降级

读 `AgentRuntimeConfig` 和 `OpenAiCompatibleAgentPlanner`。默认不要配置 Key，先理解本地模式。再看 `.env.example`，理解 `AGENT_MODE=openai_compatible` 时为什么模型只被允许调用 `plan_recommendation`。

必须能回答：为什么不让模型直接生成商品？为什么 LLM 超时后要降级？为什么密钥不能写进 `application.yml` 或 Git？

### 第 7 天：复盘、压测与讲解

运行：

```powershell
mvn clean verify
./scripts/run-database-integration-test.ps1
```

最后不用看代码，用 3 分钟讲完整链路；再用 1 分钟分别讲 MySQL、Tools、Memory、LLM 降级。

## 5. 关键文件与职责

| 文件 | 作用 | 阅读重点 |
| --- | --- | --- |
| `agent/AgentIntent.java` | 结构化意图 | scene、source、category、预算、去广告、澄清标志 |
| `agent/RuleBasedAgentPlanner.java` | 离线规划器 | 显式需求优先，长期记忆补充缺失约束 |
| `agent/OpenAiCompatibleAgentPlanner.java` | LLM Function Calling | 函数 schema、HTTP 超时、返回 JSON 校验 |
| `agent/RecommendationAgentService.java` | Agent Executor | 工具白名单、Trace、记忆读写、降级、限制步数 |
| `http/AgentHttpHandler.java` | 对话/诊断 API | 输入长度、HTTP 方法、错误码 |
| `http/AgentMemoryHttpHandler.java` | 记忆透明接口 | 只读查看、生产环境需鉴权 |
| `service/RecommendationFacade.java` | 推荐工具抽象 | Agent 不依赖具体 DAG 实现 |
| `service/data/JdbcDataRepository.java` | 数据库边界 | PreparedStatement、事务、记忆 SQL |
| `db/migration/V2__add_agent_memory.sql` | 记忆表迁移 | TTL、索引、主键、外键 |

## 6. Tools 怎么理解和实现

Tool 是“具有明确输入、明确输出、可被审计的后端能力”，不是普通的任意 Java 方法。

本项目中每个 Tool 都有名称、描述和必需参数：

| Tool | 输入 | 输出 | 为什么需要 |
| --- | --- | --- | --- |
| `get_user_profile` | userId | 偏好、新老用户、地域 | 推荐不能脱离用户画像 |
| `recommend` | userId、scene | 真实 Item、requestId、召回 debug | 复用既有 DAG，不让模型造数据 |
| `filter_candidates` | 意图约束、候选 | 符合预算/品类/来源的候选 | 保证自然语言约束落地 |
| `generate_grounded_explanation` | 真实候选 | 推荐理由 | 解释必须有事实依据 |

安全规则：Tool 必须 allow-list；参数必须校验；模型不能拼 SQL；限制最大调用步数；Trace 中不记录密钥或完整敏感数据；写工具必须有幂等键和权限控制。

## 7. Memory 怎么规划

### 短期记忆

短期记忆服务于“当前对话”。例如第一轮说“预算 500 的数码商品”，第二轮说“换成直播”，系统应保留预算和数码偏好，只把内容类型/场景改成直播。

表：`agent_conversations`。字段包括 `session_id`、`user_id`、`role_name`、`content`、`created_at`、`expires_at`。默认保留 24 小时，只取最近 8 条。

为什么要 TTL 和上限：避免表无限增长；避免历史无关上下文挤占模型 token；避免用户很久前的临时想法影响今天。

### 长期记忆

长期记忆服务于“跨会话的稳定偏好”。例如用户多次明确说“不看广告”“预算 500”“偏好数码”，可以在下次打开页面时继续使用。

表：`agent_long_term_memories`。使用 `(user_id, memory_key)` 作为主键，可以覆盖更新同一偏好；`confidence` 表示可信度；`source_name` 记录来源，便于审计。

不要把所有聊天原文都写成长期记忆。只保存明确、稳定、低风险的结构化结论。生产环境还应提供查看、修改、删除记忆的用户能力。

### 为什么使用 MySQL，不先使用 Redis 或向量库

| 存储 | 适合什么 | 当前是否主存储 | 原因 |
| --- | --- | --- | --- |
| MySQL | 结构化偏好、审计、事务、跨进程持久化 | 是 | 项目已有 MySQL；数据小且关系清晰 |
| Redis | 热会话、临时上下文、限流、缓存 | 后续可加 | 高吞吐、TTL 方便，但不适合作为唯一事实来源 |
| 向量库 | 商品描述/知识库/长文本语义检索 | 当前不需要 | 预算、品类等 key-value 偏好不需要 embedding |

面试中不要说“MySQL 比 Redis 好”。正确说法是：它们解决的问题不同。当前先用 MySQL 保证正确性、可审计性和部署简单；流量上来后用 Redis 缓存短期记忆；有非结构化知识检索需求时才引入向量库。

## 8. LLM Function Calling 是什么

Function Calling 的本质是：让模型返回结构化调用意图，而不是让它执行函数。

```text
模型输出：我要调用 plan_recommendation，参数是 {...}
↓
后端校验 JSON、枚举、数值范围
↓
后端自己执行允许的 Tools
↓
把真实结果传回给用户
```

本项目 `OpenAiCompatibleAgentPlanner` 只暴露 `plan_recommendation` 函数。模型不能调用任意数据库、Shell、HTTP 接口。这是最重要的安全边界之一。

默认本地模式的价值：项目无需 API Key 即可演示；单元测试稳定；模型服务故障不影响推荐；规则对常见需求可解释。启用 LLM 后，复杂表达会更强，但仍要保留规则降级。

## 9. 诊断 Agent

诊断 Agent 面向开发和运营人员，回答“为什么推荐了这些内容”。它会读取：用户画像、最近行为、一次真实推荐结果、三路召回 Trace。返回的是基于数据的结论，不是模型猜测。

典型问题：

- 用户为什么从冷启动变成行为用户？
- 哪一路召回贡献了多少候选？
- 当前场景为什么插入广告？
- 用户近期什么行为影响了偏好？
- 为什么某些候选被过滤？

## 10. 可观测性、测试和上线边界

### 观测

关注 `agent.chat.request`、`agent.chat.success`、`agent.chat.clarification`、`agent.planner.fallback`、`agent.diagnose.request`。再结合推荐服务的 requestId、阶段耗时、三路召回耗时和 HikariCP 连接池指标排查问题。

### 测试分层

1. 单元测试：意图解析、DAG 算子、过滤和页面资源。
2. Testcontainers：真实 MySQL 中执行 Flyway、验证表结构和记忆读写。
3. Docker 集成测试：启动 MySQL 和服务，通过 HTTP 验证 9 张表、三路召回、反馈闭环、Agent Tools、Memory、Prometheus。

### 真实上线仍需补齐

鉴权与用户身份绑定、密钥管理、API 限流、模型内容审核、Prompt Injection 防护、个人数据删除权、Redis 缓存、模型评测集、灰度发布、消息队列和分布式追踪。项目已经实现本地工程闭环，但不能把示例数据和无鉴权接口当成生产系统。

## 11. 高频面试问答

### Q1：这个项目为什么叫 Agent，不是普通推荐接口？

因为用户不再传固定参数，而是用自然语言表达目标；系统会结合会话和长期状态形成计划，并调用多个受限工具完成任务。它有 Planner、Tools、Memory、Executor、Trace 和反馈闭环，而不是一次固定 HTTP 参数映射。

### Q2：LLM 在哪里？没有 Key 时算 Agent 吗？

LLM 是可选规划器。默认使用规则规划器以保证离线可运行和稳定测试；配置 OpenAI 兼容模型后，LLM 通过 Function Calling 生成计划。Agent 的本质是“根据目标使用工具完成任务”，LLM 是提高语言理解与规划能力的组件，不应成为唯一依赖。

### Q3：为什么不让 LLM 直接返回推荐商品？

模型不知道实时价格、库存、商品状态和广告预算，直接生成会产生幻觉。我们让模型只输出约束，实际候选从 MySQL 和推荐 DAG 获取，再基于真实候选解释，保证可追溯和可验证。

### Q4：Tools 如何防止越权？

服务端只注册固定工具；对 userId、scene、价格、limit 做校验；模型不能传 SQL 或 URL；工具步数有限制；写操作需要独立接口、幂等和鉴权；Trace 可审计。

### Q5：短期和长期记忆有什么区别？

短期记忆是当前会话上下文，有 TTL、数量上限，允许快速变化；长期记忆是跨会话的稳定偏好，以 key-value 存储并带置信度/来源。前者帮助理解“换成直播”，后者帮助记住“偏好数码”。

### Q6：为什么短期记忆不用 Redis？

当前先用 MySQL，是为了减少基础设施、保证持久化和审计。高并发时可以采用 MySQL 为事实源、Redis 为会话缓存的两层设计；失效后从 MySQL 回源。

### Q7：为什么不使用向量数据库？

当前记忆是结构化偏好，使用关系模型最合适。向量库适合“从大量商品介绍、评论、帮助文档中按语义检索”的场景；需要 RAG 时再引入，并让向量召回成为一个独立 Tool。

### Q8：LLM 超时或返回非法 JSON 怎么办？

`OpenAiCompatibleAgentPlanner` 设置 HTTP 超时，异常后记录 `agent.planner.fallback` 指标并切换规则规划器。所有模型输出还会做场景枚举、来源枚举、预算范围、limit 范围校验，非法结果不能进入工具执行层。

### Q9：如何避免 Prompt Injection？

不把用户文本当系统指令；模型只拥有一个受限规划函数；服务端不相信模型输出，严格校验；不允许模型执行 SQL、Shell 或任意 HTTP；对外接知识库时还要隔离不可信文本并做内容审核。

### Q10：如果面试官问“你真正做了 LLM 调用吗”？

如实回答：项目内置 OpenAI 兼容 Function Calling Adapter，默认不开启以保证本地可重复运行；配置 API Key 后可切换模型规划。当前本地验收覆盖的是规则规划、Tools、记忆和降级边界；真实模型调用需在有授权的 Key 环境做额外集成测试。不要虚构模型调用结果。

## 12. 最后检查清单

- 能讲清从自然语言到推荐结果的 8 个步骤。
- 能说清 4 个 Tools 的输入、输出和安全限制。
- 能解释短期/长期记忆的表、TTL、上限和写入原则。
- 能解释为什么 MySQL 是事实源、Redis 是缓存候选、向量库按需引入。
- 能解释 LLM 只规划、不直接拿数据和执行动作。
- 能说清模型异常为何降级、怎么监控。
- 能运行 `mvn clean verify` 与 `./scripts/run-database-integration-test.ps1`。

如果以上都能讲清，这个项目就不仅是“会调模型”，而是一个具备推荐工程、Agent 编排、数据边界和生产意识的完整后端项目。
