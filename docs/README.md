# Mini Reco 文档

当前文档只描述仓库中正在运行的实现。

建议阅读顺序：

1. [开始运行](getting-started.md)：环境、启动、接口调用和代码阅读入口；
2. [架构与请求链路](architecture.md)：Context、Operator、DAG、并行召回和异常处理；
3. [测试说明](testing.md)：JUnit、Mockito、并行测试和验收命令。

项目入口为 `MiniRecoApplication`，默认注册：

- `GET /`（内置控制台）
- `GET /recommend`
- `GET /health`
- `GET /metrics`
