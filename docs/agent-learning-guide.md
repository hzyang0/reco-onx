# 智能推荐 Agent 学习手册

请以 [LangGraph Agent 主架构与学习路线](langgraph-agent-main.md) 为准。该文档已按当前实现说明 Python、FastAPI、LangGraph、Redis、MySQL、Java Tool 后端及 Function Calling 的真实边界。

建议学习顺序：

1. 运行 `docker compose up --build -d`，在 `http://localhost:18081/` 输入一条带预算、品类或去广告条件的需求。
2. 查看 `agent-service/app/main.py` 的 `AgentState` 和 `StateGraph`，把工作流手画出来。
3. 看 `profile_node`、`recommend_node`、`filter_node`，理解 Agent 如何用 HTTP 调 Java Tools。
4. 查看 Redis 的 session key、MySQL 的 `agent_conversations` 与 `agent_long_term_memories`，区分短期和长期记忆。
5. 最后再读 Java DAG，理解 Tool 内部如何做三路并行召回和混排。

面试时只描述当前真实使用的技术：Python、FastAPI、LangGraph、Redis、MySQL、WebSocket、Java 推荐工具后端和 Docker Compose；不要写 React、SQLite、RAG、向量数据库或 LangChain Agent Executor。
