# 测试说明

## Agent 约束与工作流回归

从仓库根目录执行（Python 3.11+，建议使用虚拟环境）：

```bash
python -m pip install -r agent-service/requirements.txt
cd agent-service
python -m unittest discover -s tests -v
cd ..
```

默认运行46项离线测试，另外13项真实MySQL测试按环境变量启用；离线测试使用固定候选和模拟工具，不依赖MySQL、Redis或模型密钥。覆盖约束优先级、预算边界、明确记忆写入、模型输出校验、会话隔离、工具失败与旧结果隔离；CI的 `agent-policy` job 会运行它们。

启动完整Compose服务后，在仓库根目录验证真实接口：

```bash
python scripts/evaluate-agent.py --base-url http://127.0.0.1:18081 --output output/agent-evaluation.json
```

脚本需要原始演示用户123/456和种子商品，创建独立前缀的会话并写入普通对话审计，不要求或写入长期偏好。任一断言失败返回非零退出码。报告区分任务结果、返回候选约束满足率、串行请求耗时与模型调用；外部模型费用没有价格依据时保留null。本地规则评测不代表LLM质量、生产成功率或负载容量。

恢复复核：保留评测报告中的 `runId`，重启Agent，删除仅属于该次运行的 Redis 键 `agent:session:456:eval-<runId>-digital`，再对原session发送“预算改为0”。应恢复数码、商品、去广告条件并返回 `no_match`；长期偏好保持不变。这验证对话恢复，未验证中断任务自动续跑。不要清空整个Redis或业务数据库。

本轮结果与未实现目标见 [项目评审](PROJECT-REVIEW-20261021.md)。

## 本地生活集成测试与评测

先启动完整Compose，使V3迁移和种子数据就绪，再执行：

```bash
cd agent-service
RUN_LOCAL_LIFE_DB_TESTS=true MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 python -m unittest discover -s tests -v
cd ..
python scripts/evaluate-local-life.py --base-url http://127.0.0.1:18081 --output output/local-life-evaluation.json
```

数据库测试使用随机独立用户并在结束后删除该用户测试记录，不修改商品种子。HTTP评测使用演示用户789和独立会话，写入普通审计与任务数据，不写长期偏好。评测依赖原始合成价格及规则，不应对真实业务数据运行。每个失败断言都会使脚本非零退出。

覆盖报价算术、优惠/免配送门槛、起送限制、库存、有效期、同键并发与冲突、事务回滚、跨会话偏好、多轮条件恢复。固定回归集为36个场景45次请求；报告保存任务结果、约束满足率、串行耗时和模型费用。数据和解释见 [本地生活指南](LOCAL-LIFE-GUIDE.md)。CI的 `local-life-integration` job 在全新MySQL中迁移后执行这59项测试和真实HTTP评测，并保存评测报告为artifact。

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

## 浏览器交互回归

需要Node.js和Playwright（`npm install --no-save --package-lock=false playwright`，必要时运行 `npx playwright install chromium`），服务启动后从仓库根目录执行：

```bash
node scripts/check-agent-ui.cjs
# 使用系统Chromium时：
CHROMIUM_PATH=/usr/bin/chromium node scripts/check-agent-ui.cjs
```

脚本验证真实页面的指定商品报价和费用卡片，再用受控延迟响应验证切换会话后旧结果不会污染新会话。仅使用合成演示用户；`AGENT_URL` 可指定服务地址。
