# 测试说明

## 单元测试

```powershell
./scripts/run-tests.ps1
```

单元测试不依赖 MySQL，重点验证编排逻辑：

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

脚本会构建 JAR、启动 Compose 中的 MySQL，再以临时端口启动 JAR，检查：5 张表均使用 InnoDB、两个业务索引存在、5 个差异化画像和 100 条候选已初始化、中文种子数据没有乱码、`/api/console-data` 计数正确、推荐结果不含代码兜底/无货/下线 Item、控制台和指标接口可访问。它不会删除 Docker 卷中的数据。

## 完整 Compose 验证

```powershell
mvn -DskipTests package
docker compose up --build -d --wait
Invoke-RestMethod "http://localhost:18081/health"
```

如果数据库集成测试通过，而应用镜像构建阶段提示无法从 Docker Hub 拉取 JRE 基础镜像，说明是镜像仓库网络问题，不是 JDBC 或业务代码问题。可以先执行 `docker pull eclipse-temurin:17-jre-jammy`，网络恢复后再重试 Compose；同时可用 `./scripts/start-local.ps1` 验证宿主机 JAR。

## 冒烟测试

当本机数据库已经启动后：

```powershell
mvn -DskipTests package
./scripts/run-smoke-test.ps1
```

脚本会在临时端口启动 JAR，调用控制台、控制台数据、推荐、健康和指标接口，然后停止该临时 Java 进程。
