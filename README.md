# Mini Reco

一个轻量级 Java 推荐请求编排服务。它接收用户和场景参数，使用 DAG 组织参数准备、多路召回、在线特征、混排、过滤和后处理，并返回 JSON 推荐结果。

当前运行时会连接 MySQL 8.4。用户画像、行为、实验分组、候选商品和库存都从数据库读取；这些是仓库内置的示例数据，方便在本地重复运行，不是生产用户数据。数据集包含 5 个特点鲜明的用户画像和 100 条商品、直播、广告候选。

## 运行链路

```text
HTTP /recommend
  -> Prepare (画像、行为、AB、地址)
  -> Recall (goods / live / ad 并行)
  -> OnlineFeature (批量读取库存) --+
  -> MixRank (规则混排)           --+-> Filter -> PostProcess -> JSON
```

## 快速启动

需要 Docker Desktop、JDK 17 和 Maven 3.9+。以下命令会打包应用、启动 MySQL、初始化示例数据，并启动应用：

```powershell
mvn -DskipTests package
docker compose up --build -d
```

打开内置控制台：<http://localhost:18081/>。

控制台会从 MySQL 加载全部用户，可直接切换居家品质党、数码发烧友、美食探索家、潮流新用户和运动健康型用户，并自动选择各自的默认场景。也可以点击“创建自己的用户画像”，选择偏好类目、行为阶段、默认场景和排序策略；保存后画像、行为和实验分组会事务写入 MySQL，并立即执行推荐。

也可以调用接口：

```powershell
Invoke-RestMethod "http://localhost:18081/recommend?userId=123&scene=mall&limit=5"
Invoke-RestMethod "http://localhost:18081/health"
Invoke-RestMethod "http://localhost:18081/metrics"
Invoke-RestMethod "http://localhost:18081/api/console-data"
```

创建接口为 `POST /api/users`，控制台使用表单编码调用。行为阶段支持冷启动（无行为）、兴趣用户（浏览和点击）以及高意向用户（浏览、点击、加购和购买）。

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
```

前者是无需数据库的单元测试；后者会启动 Compose 中的 MySQL，并通过真实 HTTP 请求和 SQL 查询验证数据库版本。

## 文档

- [开始运行](docs/getting-started.md)
- [架构与请求链路](docs/architecture.md)
- [数据库与数据边界](docs/database.md)
- [测试说明](docs/testing.md)
- [代码导读](docs/code-walkthrough.md)

## 当前边界

- MySQL 中存储可重复运行的示例数据；并没有接入真实用户数据、实时消息流或外部模型服务。
- `JdbcDataRepository` 是数据边界；将来替换成 RPC 或特征库时，不需要改动 Operator 和 DAG。
- 混排是可解释的本地规则，不是机器学习模型。
- 指标当前保存在进程内存中，重启后会清空。
- 自助创建画像是本地演示能力，当前没有登录、权限、删除和修改接口；对公网部署前需要补齐鉴权、限流和管理边界。
