# 文档索引

建议按下面顺序阅读：

1. [开始运行](getting-started.md)：启动数据库和服务，调用接口。
2. [架构与请求链路](architecture.md)：理解 Context、Operator、DAG 和并行关系。
3. [数据库与数据边界](database.md)：理解表、SQL、配置和数据来源。
4. [代码导读](code-walkthrough.md)：沿一条请求阅读关键文件。
5. [测试说明](testing.md)：理解单元测试、Mock 和数据库集成验证。
6. [性能压测](performance.md)：复现串行和并行召回对照。
7. [生产化边界](production-readiness.md)：区分已实现能力和外部平台能力。

应用入口是 `MiniRecoApplication`，提供：

- `GET /`：内置控制台。
- `GET /recommend`：推荐接口。
- `GET /health`：服务状态。
- `GET /live`：不依赖数据库的进程存活状态。
- `GET /metrics`：进程内指标。
- `GET /metrics/prometheus`：Prometheus 文本指标。
- `POST /api/users`：创建用户画像。
- `POST /api/events`：上报曝光、点击、加购和购买行为。
