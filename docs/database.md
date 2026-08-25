# 数据库与数据边界

## 为什么使用 MySQL

项目使用 MySQL 8.4 保存用户画像、行为、实验分组、召回候选和三类来源专属在线状态。对这个规模的 Java 后端来说，MySQL 的使用门槛低、生态成熟，也更容易在本地和常见服务器环境中部署。

仓库中的数据是可重复初始化的示例数据，不是生产用户数据；但应用访问的是实际运行的 MySQL，而不是在 Java 代码中临时生成结果。

## 表与职责

| 表 | 主要字段 | 用途 |
| --- | --- | --- |
| `user_profiles` | `age`、`new_user`、`default_category`、地址 | 构造用户画像和默认地址 |
| `user_events` | `item_id`、`category`、`event_type`、`request_id` | 曝光追踪与按行为权重推断偏好 |
| `experiment_assignments` | `scene`、`recall_exp`、`rank_exp` | 获取用户在场景下的实验分组 |
| `catalog_items` | `source`、`category`、`base_score` | goods/live/ad 三路召回候选 |
| `goods_details` | `price`、`stock`、`sale_status` | 商品价格、库存和售卖状态 |
| `live_details` | `room_id`、`anchor_id`、`heat`、`live_status` | 直播专属在线状态 |
| `ad_creatives` | `creative_id`、`campaign_id`、`bid_cents`、`remaining_budget_cents` | 广告创意、出价和预算 |
| `agent_conversations` | `session_id`、`role_name`、`content`、`expires_at` | 带 TTL 的短期对话记忆 |
| `agent_long_term_memories` | `memory_key`、`memory_value`、`confidence`、`source_name` | 用户长期偏好和来源审计 |

迁移文件是 `db/migration/V1__schema_and_seed.sql` 与 `V2__add_agent_memory.sql`。应用启动时 Flyway 校验并执行尚未应用的版本，创建 9 张业务表、索引、外键、5 个差异化用户画像和 300 条候选记录；执行历史保存在 `flyway_schema_history`。goods、live、ad 各有 100 条，每一路内部标题唯一，来源之间也不复用标题。

三种来源共享候选主结构，但专属状态位于不同表中。Repository 通过一次批量 JOIN 读取并转换成接入层统一 Item；控制台因此会为商品显示价格/库存，为直播显示直播间/热度，为广告显示创意 ID/广告计划。

5 个画像分别覆盖居家、数码、美食、穿搭和运动偏好，其中潮流新用户没有历史行为，用于展示冷启动；其余用户具有不同的浏览、点击、加购或购买序列。实验表中的默认场景只使用 `mall`、`video_feed` 和 `buy_first`；新用户由画像字段表示，不作为场景。控制台通过 `/api/console-data` 实时读取这些画像和候选总数，不在前端硬编码用户列表。

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

当前版本使用 HikariCP，默认最大连接数为 12，连接获取超时为 2 秒；JDBC URL 还设置连接和 socket 超时。生产环境仍需根据压测调整池大小，并接入慢 SQL、缓存、读写分离和数据库降级策略。

## 常用 SQL

```powershell
docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco mini_reco
```

进入客户端后可以执行：

```sql
SELECT * FROM user_profiles WHERE user_id = 123;
SELECT * FROM user_events WHERE user_id = 123 ORDER BY event_time DESC;
SELECT item_id, title, source, base_score FROM catalog_items ORDER BY base_score DESC;
SELECT c.title, g.price, g.stock, g.sale_status
FROM catalog_items c JOIN goods_details g USING (item_id)
ORDER BY c.item_id;
```
