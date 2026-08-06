# 数据库与数据边界

## 为什么要用数据库

服务不再从代码里按用户 ID 拼接候选或库存。现在用户、行为、实验分组、候选和库存保存在 PostgreSQL 中，应用通过 JDBC 查询它们。这使“改库存、加候选、改实验分组”成为数据变更，而不是重新改 Java 代码和打包。

内置数据仍是示例数据：它是真实运行的 PostgreSQL 数据库，但不是生产数据源。

## 表与职责

| 表 | 用途 | 被谁读取 |
| --- | --- | --- |
| `user_profiles` | 年龄、新老用户、默认类目、收货地 | 用户特征、地址 |
| `user_events` | 点击、加购、购买行为 | 用户偏好推断 |
| `experiment_assignments` | 用户在场景下的 AB 分组 | Prepare |
| `catalog_items` | goods/live/ad 候选 | 三路召回 |
| `inventory_snapshots` | 价格、库存、状态 | 在线特征与过滤 |

初始化 SQL 位于 `db/init/001-schema-and-seed.sql`。数据库容器第一次创建数据卷时会执行它。

## 访问分层

`JdbcDataRepository` 是唯一直接写 SQL 的类。`JdbcUserFeatureService`、`JdbcRecallService` 等下游实现只做“把数据库记录转换成业务对象”的工作；Operator 不知道 SQL 的存在，只依赖下游接口。

这种分层的价值是：未来替换成 MySQL、远程召回服务或特征库时，编排图、Context 和 HTTP 协议不需要跟着改。

## PostgreSQL 与 MySQL

本项目选 PostgreSQL 的原因是它的 SQL 标准支持、约束能力和后续扩展空间较好；但这里没有使用 PostgreSQL 独占且不可替换的业务能力，MySQL 同样可用。

迁移到 MySQL 的工作主要集中在：替换 Maven JDBC 驱动、修改 `JDBC_URL`、将 Compose 镜像和初始化 DDL 调整为 MySQL 方言。`JdbcDataRepository` 之外的代码无需改变。

## 当前实现与下一步

当前示例为清晰起见使用 `DriverManager` 按仓库调用获取连接。正式高并发服务通常会接入连接池（例如 HikariCP），并监控连接数、慢查询和数据库错误。

库存已经采用“按批查询”，避免一个候选一次 SQL 的 N+1 查询。若候选规模继续增长，还需要分页、索引优化、缓存和独立的实时库存服务。
