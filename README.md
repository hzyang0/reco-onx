# Mini Reco

一个轻量级 Java 推荐请求编排服务。它接收用户和场景参数，使用 DAG 组织参数准备、多路召回、在线特征、混排、过滤和后处理，并返回 JSON 推荐结果。项目还内置了对话式推荐 Agent 与推荐诊断 Agent：自然语言只负责理解需求，用户画像、候选召回、过滤和最终结果仍由真实本地服务与 MySQL 数据驱动。

当前运行时会连接 MySQL 8.4，并通过 Flyway 管理数据库版本、HikariCP 复用连接。用户画像、行为、实验分组、候选内容和在线状态都从数据库读取；这些是仓库内置的示例数据，方便在本地重复运行，不是生产用户数据。数据集包含 5 个特点鲜明的用户画像，以及 goods、live、ad 各 100 条候选，共 300 条。统一候选表只保存公共字段，商品、直播和广告分别拥有独立详情表，三路标题互不复用。

## 运行链路

```text
HTTP /recommend
  -> Prepare (画像、行为、AB、地址)
  -> Recall (goods / live / ad 并行)
  -> OnlineFeature (批量读取来源专属状态) --+
  -> MixRank (规则混排)           --+-> Filter -> PostProcess -> JSON
```

## 快速启动

需要 Docker Desktop、JDK 17 和 Maven 3.9+。以下命令会打包应用、启动 MySQL、初始化示例数据，并启动应用：

```powershell
mvn -DskipTests package
docker compose up --build -d
```

打开内置控制台：<http://localhost:18081/>。

控制台会从 MySQL 加载全部用户，可直接切换内置画像或创建自定义画像。推荐结果曝光会自动写入 `user_events`；商品卡片支持点击、加购、购买，直播和广告支持“感兴趣”。显式反馈会更新画像并立即重新推荐，从而形成可观察的数据闭环。召回区域会显示 goods、live、ad 三路各自的耗时和召回数量，默认窗口为 20/20/20。

三路候选都会并行召回，但最终结果由场景策略编排。`mall` 返回商品并在第 4、9 位穿插广告；`video_feed` 合并原本的单双列语义，返回直播/视频内容并穿插广告；`buy_first` 表示买家首页，按商品、直播和广告混合展示。新用户不是场景：`newUser=true` 会在所选场景内启用“声明偏好 + 热度”的冷启动排序。

也可以调用接口：

```powershell
Invoke-RestMethod "http://localhost:18081/recommend?userId=123&scene=mall&limit=5"
Invoke-RestMethod "http://localhost:18081/health"
Invoke-RestMethod "http://localhost:18081/live"
Invoke-RestMethod "http://localhost:18081/metrics"
Invoke-WebRequest "http://localhost:18081/metrics/prometheus"
Invoke-RestMethod "http://localhost:18081/api/console-data"
$body = @{ userId = 456; sessionId = 'demo-456'; message = '给我推荐预算 500 以内的数码商品，不要广告' }
Invoke-RestMethod "http://localhost:18081/api/agent/chat" -Method Post -Body $body
Invoke-RestMethod "http://localhost:18081/api/agent/diagnose?userId=456&scene=mall"
```

创建接口为 `POST /api/users`，行为上报接口为 `POST /api/events`。行为支持曝光、浏览、点击、加购和购买；曝光不改变偏好，显式反馈会退出冷启动并参与后续偏好计算。

停止容器但保留数据库数据：

```powershell
docker compose down
```

## 本地开发

需要 JDK 17、Maven 3.9+ 和 Docker Desktop。

```powershell
docker compose up -d db
mvn clean test
mvn -DskipTests package
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT-all.jar
```

也可在后台启动并等待健康检查：`./scripts/start-local.ps1`。

独立启动 JAR 时默认连接本机 `3307` 端口上的 `mini_reco` 数据库。可使用 `JDBC_URL`、`DB_USER` 和 `DB_PASSWORD` 环境变量覆盖，示例见 [.env.example](.env.example)。

## 验证

```powershell
./scripts/run-tests.ps1
./scripts/run-database-integration-test.ps1
./scripts/run-recall-benchmark.ps1
```

第一条运行单元测试和可用时的 Testcontainers 测试；第二条通过真实 MySQL、HTTP 和 SQL 完成全链路验收；第三条对比单线程串行召回与三线程并行召回。当前机器 300 次请求结果为：串行 P95 8.91ms，并行 P95 5.49ms，降低 38.38%；数据仅代表本机环境，应通过脚本复测。

## 文档

- [开始运行](docs/getting-started.md)
- [架构与请求链路](docs/architecture.md)
- [数据库与数据边界](docs/database.md)
- [测试说明](docs/testing.md)
- [代码导读](docs/code-walkthrough.md)
- [性能压测](docs/performance.md)
- [生产化边界](docs/production-readiness.md)
- [智能推荐 Agent](docs/agent-guide.md)

## 当前边界

- MySQL 中存储可重复运行的示例数据；并没有接入真实用户数据、实时消息流或外部模型服务。
- `JdbcDataRepository` 是数据边界；将来替换成 RPC 或特征库时，不需要改动 Operator 和 DAG。
- 混排是可解释的本地规则，不是机器学习模型。
- 场景编排采用固定、可测试的来源槽位；生产系统通常会把它替换成可配置策略或模型约束。
- `/metrics/prometheus` 可被 Prometheus 抓取，但指标仍保存在进程内存中，重启后会清空。
- 自助创建画像是本地演示能力，当前没有登录、权限、删除和修改接口；对公网部署前需要补齐鉴权、限流和管理边界。
