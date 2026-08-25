# 智能推荐 Agent

## 它解决什么问题

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

## 为什么先使用本地解析器

当前版本用 `AgentIntentParser` 完成确定性意图解析，因此没有 API Key 或网络也能完整演示、测试和部署。核心价值在于 Tool Calling 和真实数据闭环，而不是让模型编造结果。

后续接入任意 LLM 时，只需用模型的结构化 JSON 输出替换 `AgentIntentParser`；`RecommendationAgentService` 中的用户画像、推荐、过滤、诊断和可追溯机制保持不变。生产接入 LLM 后还需增加：鉴权、提示词注入防护、速率限制、模型超时/降级、敏感信息脱敏、会话存储、离线评测和人工反馈审核。

## 学习路线

1. 先读 `AgentIntentParser`：理解自然语言如何被收敛为可校验的约束。
2. 再读 `RecommendationAgentService`：理解 Tool 调用顺序、候选过滤和基于事实的解释。
3. 阅读 `AgentHttpHandler`：理解 Agent API 的输入校验与错误边界。
4. 最后回到 `ApplicationWiring` 与 DAG 算子：理解 Agent 复用的真实推荐能力。
