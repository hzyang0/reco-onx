# 开始运行

## 1. 一键体验

安装 Docker Desktop、JDK 17 和 Maven 3.9+ 后，在项目根目录执行：

```powershell
mvn -DskipTests package
docker compose up --build -d
```

Compose 会先启动 MySQL 8.4；应用启动时由 Flyway 执行 `db/migration/V1__schema_and_seed.sql` 建表和写入示例数据。数据库 readiness 通过后应用才对外可用。MySQL 映射到本机 `3307`，应用映射到 `18081`。

浏览器打开 <http://localhost:18081/>，从 5 个差异化用户画像中选择一个。页面会自动填入该用户的默认场景；设置返回数量后点击运行推荐。

切换场景时重点观察最终 Item 的 `source`：商城是 goods 为主，视频流是 live 为主，买家首页混合 goods 与 live；三种场景都会在第 4、9 位穿插 ad。召回面板仍显示三路 `20/20/20`，因为“并行取候选”和“按场景组织最终页面”是两个阶段。

如需自定义，点击“创建自己的用户画像”，填写名称、描述、年龄和地区，选择偏好类目、行为阶段、默认场景与排序策略。保存成功后新用户会自动选中并立即执行一次推荐，刷新或重启应用后仍然存在。

每次结果展示都会自动上报曝光；点击商品卡片上的“点击、加购、购买”，或直播/广告卡片上的“感兴趣”，会调用 `POST /api/events`。显式反馈将用户退出冷启动、更新偏好并立即重新推荐。

## 2. 接口验证

```powershell
Invoke-RestMethod "http://localhost:18081/health"
Invoke-RestMethod "http://localhost:18081/api/console-data"
Invoke-RestMethod "http://localhost:18081/recommend?userId=123&scene=mall&limit=5"
Invoke-RestMethod "http://localhost:18081/metrics"
Invoke-WebRequest "http://localhost:18081/metrics/prometheus"
```

`/recommend` 返回 JSON。重点看：

- `items`：最终通过库存和状态过滤后的结果；
- `debug.stageCostMs`：各个 Operator 的耗时；
- `debug.recallFanout`：三路召回的完成、超时或失败状态；
- `debug.scenePolicy`：当前场景的来源槽位和最终各来源数量；
- `debug.rankingPolicy`：当前是否启用新用户冷启动排序；
- `debug.userFeature`：本次请求实际使用的新用户状态和偏好类目；
- 每个 Item 的 `attrs`：商品价格/库存、直播间/热度或广告计划/预算等来源专属属性。

## 3. 本地调试 Java 进程

先只启动数据库：

```powershell
docker compose up -d db
mvn clean test
mvn -DskipTests package
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT-all.jar
```

或执行 `./scripts/start-local.ps1` 在后台启动，并等待 `/health` 返回 `UP`。

这时服务端口是默认的 `8080`，控制台地址为 <http://localhost:8080/>。

数据库连接配置来自环境变量：

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `JDBC_URL` | `jdbc:mysql://localhost:3307/mini_reco?...` | JDBC 连接地址 |
| `DB_USER` | `mini_reco` | 数据库用户名 |
| `DB_PASSWORD` | `mini_reco` | 数据库密码 |
| `DB_POOL_SIZE` | `12` | HikariCP 最大连接数 |
| `DB_CONNECTION_TIMEOUT_MS` | `2000` | 获取数据库连接的超时 |
| `REQUEST_TIMEOUT_MS` | `500` | 整条 DAG 请求的总预算 |
| `RECALL_FANOUT_TIMEOUT_MS` | `120` | 三路召回共享的超时预算（毫秒） |
| `RECALL_FANOUT_PARALLELISM` | `3` | 召回线程数；设为 1 可做串行对照 |
| `LOG_LEVEL` | `INFO` | 结构化日志级别 |

## 4. 常用排查命令

```powershell
docker compose ps
docker compose logs -f app
docker compose logs -f db
docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT c.item_id,c.title,g.price,g.stock,g.sale_status FROM catalog_items c JOIN goods_details g USING(item_id) ORDER BY c.item_id LIMIT 5" mini_reco
```

Flyway 只执行尚未应用的版本。若希望完全重新创建本地示例数据，请明确执行 `docker compose down -v` 后再 `up`；这会删除 Docker 卷中的全部本地数据库数据。
