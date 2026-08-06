# 测试说明

## 单元测试

```powershell
./scripts/run-tests.ps1
```

单元测试不依赖 PostgreSQL，重点验证编排逻辑：

- `RecommendServiceTest`：使用 Mockito 替代下游服务，验证正常链路和非法场景。
- `RecallOperatorTest`：验证三路并行、单路失败保留部分结果和总超时。
- `ParallelDagOperatorExecutorTest`：验证 DAG 中独立节点可以并发执行。
- `DashboardHttpHandlerTest`：验证控制台页面、静态资源和 HTTP 方法限制。
- `MetricsRegistryTest`：验证指标计数与隔离。

JUnit 负责运行测试和断言；Mockito 负责制造“可控的假下游”。例如 `when(service.getUserFeature(123L)).thenReturn(...)` 规定假服务的返回值，再用 `assertEquals` 判断业务结果。

## 数据库集成验证

```powershell
./scripts/run-database-integration-test.ps1
```

脚本会构建 JAR、启动 Compose 中的 PostgreSQL，再以临时端口启动 JAR，检查：数据库已写入用户与候选数据、健康接口可用、推荐接口返回指定数量的真实数据库候选、控制台和指标接口可访问。它不会删除 Docker 卷中的数据。

## 冒烟测试

当本机数据库已经启动后：

```powershell
mvn -DskipTests package
./scripts/run-smoke-test.ps1
```

脚本会在临时端口启动 JAR，调用控制台、推荐、健康和指标接口，然后停止该临时 Java 进程。
