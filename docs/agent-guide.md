# 智能推荐 Agent

当前版本采用“Agent 主、后端工具次”的架构：Python FastAPI + LangGraph 是外部主入口，Java 推荐服务作为内部 HTTP Tool 后端。完整的架构、学习顺序和面试问答见：[LangGraph Agent 主架构与学习路线](langgraph-agent-main.md)。

## 对外接口

- `POST /api/chat`：HTTP 对话入口，JSON 字段为 `user_id`、`message`、可选 `session_id`。
- `GET /api/memory/{user_id}`：查看可解释的 MySQL 长期偏好。
- `WS /ws/chat`：控制台使用的 WebSocket 对话入口，返回工具轨迹和最终结果事件。
- `GET /health`：同时检查 Agent、Redis 和 Java 工具后端。

主入口是 `http://localhost:18081/`；Java 后端调试地址是 `http://localhost:18082/`。

## 运行模式

默认是本地规则规划器，因此无需模型密钥；设置 `AGENT_PLANNER=openai` 和 OpenAI 兼容模型配置后，模型仅通过 Function Calling 生成受限的推荐意图。无论使用哪种规划器，用户画像、召回、混排、过滤和最终候选始终由后端工具与数据库提供。
