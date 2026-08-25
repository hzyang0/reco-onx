# Mini Reco LangGraph Agent

一个以智能 Agent 为主入口的推荐系统示例。用户用自然语言提出需求，Agent 负责读取记忆、规划、调用工具、处理条件分支并返回有事实依据的回答；Java 服务只承担画像、召回、混排、过滤和行为数据等确定性后端能力。

## 架构

```text
浏览器 / HTTP / WebSocket
          |
          v
Python FastAPI + LangGraph Agent :18081
  |-- Redis：短期会话记忆（TTL）
  |-- MySQL：长期偏好、对话审计、推荐业务数据
  `-- HTTP Tools
          |
          v
Java 推荐后端 :18082（内部工具服务）
  Prepare -> goods/live/ad 并行召回 -> MixRank -> Filter -> PostProcess
```

LangGraph 状态图为：`load_memory -> plan -> (clarify | load_profile -> recommend -> filter -> (answer | relax -> recommend)) -> persist`。当长期偏好与本轮约束冲突导致无结果时，图会走 `relax` 分支，有选择地放宽旧偏好后再次调用推荐工具。

请求会先经过意图路由：推荐问题进入真实 Tool 链路；其他问题进入 `answer_general_question` 常规问答分支。配置 LLM 后可处理开放问答；未配置模型时，只回答项目、Agent、记忆和推荐机制相关问题，并明确提示能力边界。

## 实际技术栈

- Agent：Python 3.11、FastAPI、LangGraph、OpenAI Python SDK（可选 Function Calling）、WebSocket
- 状态：Redis（短期会话）、MySQL 8.4（长期偏好、审计和业务事实）
- 工具后端：Java 17、Maven、Flyway、HikariCP、自研 DAG 执行器
- 部署：Docker Compose

没有把未实现的技术包装进项目：当前不使用 React、SQLite、向量数据库、RAG 或 LangChain Agent Executor。

## 快速启动

需要 Docker Desktop、JDK 17 和 Maven 3.9+：

```powershell
mvn -DskipTests package
docker compose up --build -d
```

打开 Agent 控制台：<http://localhost:18081/>。

控制台包含体验登录页与对话主界面：用户选择一个内置画像后进入多轮对话，聊天区展示自然语言回答和推荐卡片，右侧展示长期偏好和本轮 Tool 轨迹，左侧展示最近短期上下文。当前“登录”是本地演示身份选择；生产环境必须接入真实 OIDC/SSO、密码策略与权限控制。

检查服务：

```powershell
Invoke-RestMethod http://localhost:18081/health
Invoke-RestMethod http://localhost:18082/health
```

调用 Agent：

```powershell
$body = @{ user_id = 456; session_id = 'demo-456'; message = '给我推荐预算 500 元以内的数码商品，不要广告' } | ConvertTo-Json
Invoke-RestMethod http://localhost:18081/api/chat -Method Post -ContentType 'application/json' -Body $body
Invoke-RestMethod http://localhost:18081/api/memory/456
```

`18081` 是对用户开放的主入口；`18082` 仅用于开发调试 Java 工具后端。控制台通过 WebSocket 展示本次执行的工具轨迹和最终结果。

同一登录会话会复用 `sessionId`，因此可以多轮追问。Redis 保存最近上下文作为热数据；同时 MySQL 持久化对话审计。若 Redis 重启或键被淘汰，Agent 会从仍在有效期内的 MySQL 记录恢复最近 8 条消息并回填 Redis。长期记忆则只保存明确的结构化偏好，不等同于完整聊天记录。

## LLM 规划模式

默认 `AGENT_PLANNER=local`，使用确定性规则规划器，离线即可运行。配置一个支持 Function Calling 的 OpenAI 兼容模型后可以启用：

```text
AGENT_PLANNER=openai
LLM_BASE_URL=https://api.openai.com/v1
LLM_MODEL=<模型名>
LLM_API_KEY=<只放环境变量或密钥系统>
```

模型只能调用 `plan_recommendation` 生成受限意图，不能执行 SQL 或任意工具；模型调用失败会回退本地规划器。工具调用、候选过滤和最终结果仍由服务端完成。

## 验证

```powershell
mvn clean verify
docker compose exec -T agent python -m compileall app
```

详见：[Agent 主架构与学习路线](docs/langgraph-agent-main.md)、[新手快速入门与秋招面试手册](docs/interview-quickstart.md)、[传统推荐工具后端](docs/architecture.md)、[测试说明](docs/testing.md)。
