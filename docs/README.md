# 文档索引

建议按下面顺序阅读：

1. [开始运行](getting-started.md)：启动数据库和服务，调用接口。
2. [架构与请求链路](architecture.md)：理解 Context、Operator、DAG 和并行关系。
3. [数据库与数据边界](database.md)：理解表、SQL、配置和数据来源。
4. [代码导读](code-walkthrough.md)：沿一条请求阅读关键文件。
5. [测试说明](testing.md)：理解单元测试、Mock 和数据库集成验证。

应用入口是 `MiniRecoApplication`，提供：

- `GET /`：内置控制台。
- `GET /recommend`：推荐接口。
- `GET /health`：服务状态。
- `GET /metrics`：进程内指标。
