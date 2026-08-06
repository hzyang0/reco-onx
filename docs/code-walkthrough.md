# 代码导读

目标不是一次读完全部代码，而是沿一条真实请求读懂“输入、处理、输出”三个问题。

## 第一轮：跑通请求

1. `MiniRecoApplication.java`：HTTP 服务在哪里注册。
2. `http/ConsoleDataHttpHandler.java`：控制台如何读取用户画像和候选总数。
3. `http/UserProfileHttpHandler.java`：自定义画像怎样校验并写入数据库。
4. `http/RecommendHttpHandler.java`：URL 参数如何变成 `RecommendRequest`。
5. `service/RecommendService.java`：请求如何创建 Context 并调用执行器。
6. `domain/RecommendResponse.java`：最终 JSON 的数据来自哪里。

完成这一轮后，应能解释 `/recommend?userId=123&scene=mall&limit=5` 的输入和输出。

## 第二轮：理解编排

1. `service/ApplicationWiring.java`：读 DAG 依赖关系。
2. `service/operator/Operator.java`：所有阶段的统一契约。
3. `service/operator/graph/ParallelDagOperatorExecutor.java`：如何按依赖调度节点。
4. `service/context/RecommendContext.java`：阶段之间如何传递数据。
5. `service/operator/impl/*.java`：逐个看 Prepare、Recall、OnlineFeature、MixRank、Filter、PostProcess。

重点理解：为什么 OnlineFeature 和 MixRank 可以并行，而 Filter 必须等二者完成。

## 第三轮：理解数据库边界

1. `db/init/001-schema-and-seed.sql`：每张表保存什么。
2. `service/data/DatabaseConfig.java`：连接配置从哪里来。
3. `service/data/JdbcDataRepository.java`：所有 SQL 为什么集中在这里。
4. `service/downstream/impl/Jdbc*.java`：数据库记录如何映射成领域对象。

`JdbcOnlineFeatureService` 值得重点阅读：它先收集全部 itemId，再调用 `findInventoryByItemIds` 做一条批量 SQL，避免每个候选各查一次库存。混排会保留比最终返回数量更宽的窗口，过滤掉无货或下线候选后再由后处理截断，因此不会在代码中伪造兜底商品。

## 建议练习

- 修改一条 `inventory_snapshots` 的库存为 0，重新请求，观察该 Item 被 Filter 移除。
- 在 `catalog_items` 新增一个 goods 候选和库存，观察它进入召回结果。
- 为一个新场景写入 `experiment_assignments`，观察 `debug` 中的 AB 参数和排序变化。
- 给 `JdbcDataRepository` 的查询打断点，观察一次请求访问了哪些表。
- 在控制台分别创建冷启动和高意向用户，对比 `user_events` 记录和推荐结果。
