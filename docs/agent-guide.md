# 智能推荐 Agent

## 完整 Agent 架构

当前版本具备目标、规划、标准工具、执行循环、短期/长期记忆、真实数据约束、可观测性与安全降级。默认 `local` 模式使用确定性规划器，因此不配置模型密钥也能完整运行；设置 `AGENT_MODE=openai_compatible` 和模型配置后，会使用 OpenAI 兼容的 Function Calling 完成意图规划。模型不可用时自动降级到本地规划器。

原有 `/recommend` 接口要求调用方已经知道 `userId`、`scene` 和 `limit`。Agent 层让用户用自然语言表达需求，例如：

> 给我推荐预算 500 以内的数码商品，不要广告。

Agent 不会直接编造商品。它先把输入解析为结构化意图，再调用已有的用户画像、推荐 DAG 和候选过滤能力，最后只依据真实返回的候选生成说明。

```text
自然语言
  -> Intent Parser（场景、品类、预算、内容类型、去广告）
  -> Tool: get_user_profile
  -> Tool: recommend（已有三路并行召回 DAG）
  -> Tool: filter_candidates（预算 / 品类 / 来源 / 去广告）
  -> Tool: generate_grounded_explanation
  -> 结果、工具轨迹、可追溯 requestId
```

## 记忆设计

短期记忆保存在 `agent_conversations`。每条消息关联 `session_id`、角色、用户、过期时间；每次规划只读最近 8 条，默认 24 小时过期。它用于理解“换成视频场景”“预算再低一点”之类的上下文，并避免无限把聊天记录塞进模型上下文。

长期记忆保存在 `agent_long_term_memories`。它只保存稳定、可解释、可覆盖的偏好键值，例如 `preferred_category`、`preferred_source`、`max_price`、`exclude_ads` 和 `last_scene`，同时记录置信度和来源。短期对话不会直接变成长期偏好；当前版本只有解析出明确约束后才写入长期记忆。控制台的“查看长期记忆”可直接看到 MySQL 中保存的内容。

为什么用 MySQL：项目本来已经以 MySQL 保存用户、候选、行为与实验数据；记忆需要事务、可查询、可审计、可按用户关联和可设置 TTL，关系型表最直接，开发与部署成本最低。Redis 更适合高并发、秒级过期的热会话缓存，可作为未来短期记忆的缓存层；向量数据库适合大量非结构化知识的语义检索，而本项目目前存的是结构化用户偏好，不应为了“用了向量库”而引入额外复杂度。

## Tool Calling 与执行循环

模型或本地规划器只能产出受约束的 `AgentIntent`，无权直接访问数据库或执行 SQL。真正执行前由后端 allow-list 校验，并按最多 `AGENT_MAX_TOOL_STEPS` 次调用以下工具：

1. `get_user_profile`
2. `recommend`
3. `filter_candidates`
4. `generate_grounded_explanation`

每一步都会生成 `toolTrace`，记录输入摘要、输出摘要和状态；最终解释只使用真实候选。这是防止模型编造商品、越权调用和提示词诱导工具执行的第一层边界。

## 两个 Agent 接口

### 对话式推荐

`POST /api/agent/chat`，表单编码参数：

- `userId`：已有用户 ID。
- `message`：自然语言需求，最长 1000 字符。
- `sessionId`：可选。相同会话会保留最近场景，便于多轮补充。

```powershell
$body = @{ userId = 456; sessionId = 'demo-456'; message = '给我推荐预算 500 以内的数码商品，不要广告' }
Invoke-RestMethod 'http://localhost:18081/api/agent/chat' -Method Post -Body $body
```

返回包含 `intent`（解析结果）、`tools`（实际调用的工具顺序）、`items`（真实候选）和 `recommendationRequestId`（可回溯原推荐请求）。如果需求过于模糊，例如只输入“推荐”，会返回澄清问题而不会擅自推荐。

`GET /api/agent/memory?userId=456` 可查看该用户的长期记忆，演示环境为只读；真实产品还应由认证身份推导用户 ID，而不能信任 query 参数。

### 推荐诊断

`GET /api/agent/diagnose?userId=456&scene=mall`

诊断 Agent 会读取用户画像、最近行为、一次真实推荐结果及召回 trace，输出“为什么会推荐这些内容”的结构化结论。它面向运营或研发排障，不会修改用户数据。

## 当前可识别条件

| 条件 | 示例 |
| --- | --- |
| 品类 | 数码、家居、美食、穿搭、运动/露营、美妆 |
| 预算 | `预算 500`、`500 以内`、`不超过 500` |
| 内容 | 商品、直播、视频、广告 |
| 场景 | 商城、视频流、首页/买首 |
| 广告约束 | 不要广告、无广告 |

## LLM 配置与降级

默认 `AGENT_MODE=local` 使用 `RuleBasedAgentPlanner`。启用模型时配置：

```text
AGENT_MODE=openai_compatible
LLM_BASE_URL=https://api.openai.com/v1
LLM_MODEL=<支持 Function Calling 的模型名>
LLM_API_KEY=<仅保存在环境变量/密钥管理系统中>
```

`OpenAiCompatibleAgentPlanner` 要求模型只能调用 `plan_recommendation` 函数以产生受限 JSON；实际工具仍由服务端执行。模型网络异常、返回格式异常或超时时，会记录 `agent.planner.fallback` 指标并切回本地规划器，不中断推荐服务。

生产接入 LLM 后还需增加：网关鉴权、提示词注入检测、速率限制、密钥管理、模型内容审核、敏感信息脱敏、会话删除权、离线评测集和人工反馈审核。

## 学习路线

1. 先读 `AgentIntentParser`：理解自然语言如何被收敛为可校验的约束。
2. 再读 `RecommendationAgentService`：理解 Tool 调用顺序、候选过滤和基于事实的解释。
3. 阅读 `AgentHttpHandler`：理解 Agent API 的输入校验与错误边界。
4. 最后回到 `ApplicationWiring` 与 DAG 算子：理解 Agent 复用的真实推荐能力。
