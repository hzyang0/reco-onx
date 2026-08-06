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
 Operators -> downstream interfaces -> JDBC repository -> HikariCP -> MySQL
```

`MiniRecoApplication` 负责 HTTP；`ApplicationWiring` 负责把实现装配成一张图；业务顺序由 DAG 表达，而不是散落在一个很长的方法里。

控制台启动时先调用 `/api/console-data`，由 `ConsoleDataHttpHandler` 通过 `JdbcDataRepository` 返回用户画像和候选总数；选择用户后才调用 `/recommend`。因此用户列表和“300 条候选”统计来自 MySQL，不是前端常量。

自助画像表单调用 `POST /api/users`。`UserProfileHttpHandler` 完成白名单和长度校验，`JdbcDataRepository` 在一个事务中写入画像、实验分组以及可选的用户行为；任一步失败都会回滚。写入成功后页面重新加载用户列表，并用新画像立即发起推荐。

推荐结果展示后，控制台通过 `POST /api/events` 批量记录曝光；点击、加购、购买等显式反馈会更新用户状态与偏好并触发下一次推荐。曝光用于链路追踪但不改变偏好权重，避免系统因为自己的展示结果不断强化同一类目。

## 一条请求如何流动

1. `RecommendHttpHandler` 校验 query 参数并创建 `RecommendRequest`。
2. `RecommendService` 为这次请求新建 `RecommendContext`，交给 DAG。
3. `PrepareOperator` 读取用户画像、用户行为、实验分组和地址，写入 Context。
4. `RecallOperator` 同时执行 goods、live、ad 三个 `JdbcRecallService`；每路候选来自 `catalog_items`。
5. 召回完成后，`OnlineFeatureOperator` 一次批量 SQL 读取来源专属属性：goods 使用价格/库存，live 使用直播间/主播/热度，ad 使用创意/计划/出价/预算；三路统一投影成接入层 Item 属性。
6. `MixRankOperator` 同时计算本地规则分数：偏好类目加分、实验组中商品加分、广告轻微扣分；并保留三路排序候选供后续编排。
7. `FilterOperator` 等待在线特征和混排都完成，移除库存为 0 或状态非 ONLINE 的 Item。
8. `PostProcessOperator` 对有效候选执行场景编排：商城为 goods + ad，视频流为 live + ad，买家首页为 goods + live + ad；然后按请求上限截断并序列化成 JSON。

## 场景和用户状态

`scene` 描述请求入口，当前只保留三个清晰场景：

- `mall`：货架商城，10 条结果默认是 8 个 goods 和 2 个 ad，广告位于第 4、9 位。
- `video_feed`：合并单列和双列的内容流，10 条结果默认是 8 个 live 和 2 个 ad。
- `buy_first`：买家首页综合入口，10 条结果默认是 4 个 goods、4 个 live 和 2 个 ad。

`newUser` 是用户特征，不是流量场景。新用户仍需选择上述入口，但排序时使用声明偏好与候选热度完成冷启动；有行为用户使用画像和行为偏好。响应中的 `debug.rankingPolicy` 与 `debug.scenePolicy` 会分别说明这两个决策。

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
- 关键 Operator 失败：当前请求失败；整图超时返回 504，依赖不可用返回 503，未分类内部错误返回 500。
- 启动时数据库不可用：应用不会启动成功。这样不会出现“健康但无法推荐”的假状态。

## 超时、健康与关闭

- `REQUEST_TIMEOUT_MS` 是整张 DAG 的总预算，超时会取消已经提交的节点并返回 504。
- `RECALL_FANOUT_TIMEOUT_MS` 是三路召回共享的子预算；单路超时保留其他来源结果。
- `/live` 只说明 JVM 和 HTTP 进程存活；`/health` 会执行数据库探测并报告 HikariCP 连接池状态。
- JVM 关闭钩子会停止 HTTP 服务、DAG/召回线程池和 HikariCP，避免请求或连接泄漏。
