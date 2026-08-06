# 数据库与数据边界

## 为什么使用 MySQL

项目使用 MySQL 8.4 保存用户画像、行为、实验分组、召回候选和库存快照。对这个规模的 Java 后端来说，MySQL 的使用门槛低、生态成熟，也更容易在本地和常见服务器环境中部署。

仓库中的数据是可重复初始化的示例数据，不是生产用户数据；但应用访问的是实际运行的 MySQL，而不是在 Java 代码中临时生成结果。

## 表与职责

| 表 | 主要字段 | 用途 |
| --- | --- | --- |
| `user_profiles` | `age`、`new_user`、`default_category`、地址 | 构造用户画像和默认地址 |
| `user_events` | `category`、`event_type`、`event_time` | 按行为权重推断偏好类目 |
| `experiment_assignments` | `scene`、`recall_exp`、`rank_exp` | 获取用户在场景下的实验分组 |
| `catalog_items` | `source`、`category`、`base_score` | goods/live/ad 三路召回候选 |
| `inventory_snapshots` | `price`、`stock`、`status` | 补充在线特征并过滤无货、下线 Item |

初始化文件是 `db/init/001-schema-and-seed.sql`。新的 MySQL 数据卷第一次启动时会自动执行，创建 5 张表、索引、外键、5 个差异化用户画像和 100 条候选记录。候选包含 80 种名称唯一的 goods、10 个直播间和 10 个广告活动；每种商品只保留一个款式。

5 个画像分别覆盖居家、数码、美食、穿搭和运动偏好，其中潮流新用户没有历史行为，用于展示冷启动；其余用户具有不同的浏览、点击、加购或购买序列。控制台通过 `/api/console-data` 实时读取这些画像和候选总数，不在前端硬编码用户列表。

控制台还允许创建自定义画像。冷启动阶段只写入画像和实验分组；兴趣阶段额外写入 view、click；高意向阶段写入 view、click、cart、purchase。三类数据在同一个 JDBC 事务中提交，避免只创建了一半的用户。自定义画像保存在当前 Docker 数据卷中，重启应用不会丢失。

## 访问分层

`JdbcDataRepository` 是唯一直接写 SQL 的类。它使用 `PreparedStatement` 绑定参数，负责把 `ResultSet` 映射成 record。`JdbcUserFeatureService`、`JdbcRecallService` 等适配器把数据库记录变成领域对象；Operator 只依赖下游接口，不知道 JDBC 和 SQL 的细节。

这样做的价值是：数据访问变化被限制在仓库和适配器层，不会蔓延到 HTTP、Context、DAG 或 Operator。

## 连接和字符集

- 容器内部地址：`jdbc:mysql://db:3306/mini_reco...`。
- 宿主机默认地址：`jdbc:mysql://localhost:3307/mini_reco...`。
- 端口使用 `3307`，避免与本机已有的 MySQL `3306` 冲突。
- 数据库和表使用 `utf8mb4`、`utf8mb4_0900_ai_ci`，JDBC URL 明确指定 UTF-8 和时区。
- 用户名、密码和 URL 可以通过 `DB_USER`、`DB_PASSWORD`、`JDBC_URL` 覆盖。

## 性能点与边界

库存查询使用一个 `IN (?, ?, ...)` 语句批量查询候选，避免 N 个 Item 发出 N 条 SQL 的 N+1 问题。`user_events(user_id, event_time)` 与 `catalog_items(source, base_score)` 建有组合索引。

当前版本为了让数据链路容易阅读，仍使用 `DriverManager` 按操作获取连接。更高并发的生产实现应增加 HikariCP 连接池、连接与查询超时、慢 SQL 监控、缓存以及数据库降级策略。

## 常用 SQL

```powershell
docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco mini_reco
```

进入客户端后可以执行：

```sql
SELECT * FROM user_profiles WHERE user_id = 123;
SELECT * FROM user_events WHERE user_id = 123 ORDER BY event_time DESC;
SELECT item_id, title, source, base_score FROM catalog_items ORDER BY base_score DESC;
SELECT c.title, i.price, i.stock, i.status
FROM catalog_items c JOIN inventory_snapshots i USING (item_id)
ORDER BY c.item_id;
```
