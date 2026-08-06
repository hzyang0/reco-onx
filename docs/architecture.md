# 架构与请求链路

## 系统边界

```text
Browser / API client
        |
  HttpServer handlers
        |
 RecommendationFacade
        |
 Parallel DAG executor
        |
 Operators -> downstream interfaces -> JDBC repository -> MySQL
```

`MiniRecoApplication` 负责 HTTP；`ApplicationWiring` 负责把实现装配成一张图；业务顺序由 DAG 表达，而不是散落在一个很长的方法里。

控制台启动时先调用 `/api/console-data`，由 `ConsoleDataHttpHandler` 通过 `JdbcDataRepository` 返回用户画像和候选总数；选择用户后才调用 `/recommend`。因此用户列表和“300 条候选”统计来自 MySQL，不是前端常量。

自助画像表单调用 `POST /api/users`。`UserProfileHttpHandler` 完成白名单和长度校验，`JdbcDataRepository` 在一个事务中写入画像、实验分组以及可选的用户行为；任一步失败都会回滚。写入成功后页面重新加载用户列表，并用新画像立即发起推荐。

## 一条请求如何流动

1. `RecommendHttpHandler` 校验 query 参数并创建 `RecommendRequest`。
2. `RecommendService` 为这次请求新建 `RecommendContext`，交给 DAG。
3. `PrepareOperator` 读取用户画像、用户行为、实验分组和地址，写入 Context。
4. `RecallOperator` 同时执行 goods、live、ad 三个 `JdbcRecallService`；每路候选来自 `catalog_items`。
5. 召回完成后，`OnlineFeatureOperator` 一次 SQL 批量读取 `inventory_snapshots`，写入价格、库存、状态。
6. `MixRankOperator` 同时计算本地规则分数：偏好类目加分、实验组中商品加分、广告轻微扣分。
7. `FilterOperator` 等待在线特征和混排都完成，移除库存为 0 或状态非 ONLINE 的 Item。
8. `PostProcessOperator` 只对库存、状态都有效的数据库候选按请求上限截断，`RecommendResponse` 序列化成 JSON。

## Context 为什么重要

`RecommendContext` 是“单次请求的工作台”。它用明确字段保存用户特征、AB 参数、召回列表、排序列表和调试信息，避免把所有数据塞进 `Map<String, Object>`。

Item 的属性使用 `EnumMap<AttrName, ItemAttr>`，属性名集中在枚举中管理，减少字符串硬编码带来的拼写错误。

## 两层并行

- 召回层：goods、live、ad 三路互不依赖，`ParallelRecallFanout` 并行发起，并共享一个总超时预算。三路候选库各 100 条，每路按偏好和基础分召回 20 条，响应中的 `itemCountBySource` 和控制台会展示 20/20/20 的实际结果。
- DAG 层：在线特征和混排都只依赖 Recall，因此可以并行；Filter 同时依赖这两个节点，所以会在两者完成后运行。

```text
Prepare -> Recall -> OnlineFeature --+
                  -> MixRank -------+-> Filter -> PostProcess
```

## 失败策略

- 单路召回失败或超时：保留其他成功来源的候选，并记录到 `debug.recallFanout`。
- 关键 Operator 失败：当前请求失败，HTTP 层返回 500。
- 启动时数据库不可用：应用不会启动成功。这样不会出现“健康但无法推荐”的假状态。
