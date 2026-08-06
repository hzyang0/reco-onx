# 开始运行

## 1. 一键体验

安装 Docker Desktop、JDK 17 和 Maven 3.9+ 后，在项目根目录执行：

```powershell
mvn -DskipTests package
docker compose up --build -d
```

Compose 会先启动 PostgreSQL，执行 `db/init/001-schema-and-seed.sql` 建表和写入示例数据；数据库健康后才启动应用。应用对外端口是 `18080`，避免与本地其他 8080 服务冲突。

浏览器打开 <http://localhost:18080/>，填写 `userId=123`、`scene=mall`、`limit=5`，点击运行推荐。

## 2. 接口验证

```powershell
Invoke-RestMethod "http://localhost:18080/health"
Invoke-RestMethod "http://localhost:18080/recommend?userId=123&scene=mall&limit=5"
Invoke-RestMethod "http://localhost:18080/metrics"
```

`/recommend` 返回 JSON。重点看：

- `items`：最终通过库存和状态过滤后的结果；
- `debug.operatorCostMs`：各个 Operator 的耗时；
- `debug.recallFanout`：三路召回的完成、超时或失败状态；
- 每个 Item 的 `attrs`：召回原因、价格、库存、商品状态等。

## 3. 本地调试 Java 进程

先只启动数据库：

```powershell
docker compose up -d db
mvn clean test
mvn -DskipTests package
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

或执行 `./scripts/start-local.ps1` 在后台启动，并等待 `/health` 返回 `UP`。

这时服务端口是默认的 `8080`，控制台地址为 <http://localhost:8080/>。

数据库连接配置来自环境变量：

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `JDBC_URL` | `jdbc:postgresql://localhost:5432/mini_reco` | JDBC 连接地址 |
| `DB_USER` | `mini_reco` | 数据库用户名 |
| `DB_PASSWORD` | `mini_reco` | 数据库密码 |
| `RECALL_FANOUT_TIMEOUT_MS` | `120` | 三路召回共享的超时预算（毫秒） |
| `LOG_LEVEL` | `INFO` | 结构化日志级别 |

## 4. 常用排查命令

```powershell
docker compose ps
docker compose logs -f app
docker compose logs -f db
docker compose exec db psql -U mini_reco -d mini_reco -c "SELECT item_id, title, stock, status FROM catalog_items JOIN inventory_snapshots USING (item_id) ORDER BY item_id LIMIT 5"
```

首次建库只会执行一次初始化 SQL。若希望完全重新创建本地示例数据，请明确执行 `docker compose down -v` 后再 `up`；这会删除 Docker 卷中的全部本地数据库数据。
