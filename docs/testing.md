# 测试说明

## 单元测试

```powershell
./scripts/run-tests.ps1
```

单元测试不依赖 MySQL，重点验证编排逻辑：

- `RecommendServiceTest`：使用 Mockito 替代下游服务，验证正常链路和非法场景。
- `RecallOperatorTest`：验证三路并行、单路失败保留部分结果和总超时。
- `PostProcessOperatorTest`：验证商城、视频流和买家首页的来源比例及广告槽位。
- `ParallelDagOperatorExecutorTest`：验证 DAG 中独立节点可以并发执行，以及请求总预算会取消慢节点。
- `DashboardHttpHandlerTest`：验证控制台页面、静态资源和 HTTP 方法限制。
- `MetricsRegistryTest`：验证指标计数与隔离。
- `JdbcDataRepositoryIntegrationTest`：当 `RUN_TESTCONTAINERS=true` 时由 Testcontainers 启动 MySQL 8.4，验证 Flyway、三张来源详情表和反馈闭环；GitHub Actions 会启用，本地默认由下一节的 Compose 集成脚本覆盖。

JUnit 负责运行测试和断言；Mockito 负责制造“可控的假下游”。例如 `when(service.getUserFeature(123L)).thenReturn(...)` 规定假服务的返回值，再用 `assertEquals` 判断业务结果。

## 数据库集成验证

```powershell
./scripts/run-database-integration-test.ps1
```

脚本会构建 JAR、启动 Compose 中的 MySQL，再以临时端口启动 JAR，检查：7 张业务表、Flyway 历史和 3 个业务索引；三张来源详情表各 100 行；至少 5 个画像和 300 条候选；标题唯一性、20/20/20 召回、场景槽位、中文编码、来源专属可用性过滤、数据库健康和 Prometheus 指标。脚本还会创建 sports 用户，再通过 `POST /api/events` 写入 3 次 digital 购买，验证后续推荐从 sports 切换到 digital，最后自动清理测试用户。

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
