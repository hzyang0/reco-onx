# V12：新旧链路双跑、结果 Diff、灰度发布与指标打平

这一版解决的是重构项目最后、也是最容易被低估的一段工作：

> 新代码已经写完并通过单测，为什么不能直接全量上线？为什么还要花几个月打平指标？

因为“代码逻辑看起来正确”和“线上几亿次请求的业务结果没有发生不可接受的变化”是两件事。

## 1. 什么叫指标打平

重构目标通常是降低复杂度或提高性能，而不是改变推荐策略。因此新旧链路至少要比较：

- 最终 item ID 和顺序是否一致；
- item 分数和关键属性是否一致；
- 各路召回量、过滤量、最终返回量是否一致；
- 点击率、成交率、GMV 等线上业务指标是否在可接受范围；
- P50、P95、P99、CPU、内存和下游 QPS 是否改善或没有恶化。

“打平”不一定要求所有字节 100% 相同。例如异步获取在线特征时，价格或库存可能刚好发生变化；浮点计算也可能存在极小误差。团队需要先定义哪些差异必须为零，哪些差异可以落在阈值内。

## 2. V12 保留了两条什么链路

```text
LEGACY pipeline
  prepare -> degradation -> 顺序召回 -> onlineFeature / mixRank -> filter -> postProcess

NEW pipeline
  prepare -> degradation -> 并行召回 -> onlineFeature / mixRank -> filter -> postProcess
```

两个版本只有召回编排方式不同：

- LEGACY：goods、live、ad 依次调用；
- NEW：三路同时调用，有 120ms 整体截止时间。

其他算子和业务规则保持一致，这样 Diff 出现差异时，排查范围比较明确。

代码入口：

- `SequentialRecallOperator`：旧链路基线；
- `RecallOperator + ParallelRecallFanout`：新链路；
- `MigrationRecommendationFacade`：选择主链路并安排影子执行。

## 3. 影子流量是什么

在影子阶段，用户仍然只看到旧链路的结果：

```text
用户请求
   |
   +-- LEGACY 主链路 --------> 立即返回给用户
   |
   +-- NEW 影子链路 ---------> 异步执行，只做 Diff，不返回给用户
```

新链路即使报错或产生不同结果，也不会直接影响用户。它的输出只进入对比系统。

V12 的测试会先把影子任务保存起来但不运行，确认旧链路响应已经返回；随后手动运行影子任务，Diff 计数才从 0 变成 1。这证明影子执行不在用户响应的同步等待路径上。

## 4. 为什么不能对所有流量长期双跑

影子流量不是免费的。100% 双跑大约会带来：

- 两倍的推荐计算；
- 两倍的召回、特征和混排下游 QPS；
- 更多线程、连接、日志和指标；
- 影子系统故障反过来影响主链路的风险。

因此生产系统通常：

- 只采样 1%～10% 的代表性请求；
- 给影子任务使用独立有界线程池和队列；
- 队列满时丢弃影子任务，绝不能阻塞主链路；
- 对下游提前申请影子流量容量；
- 给日志和监控打上 shadow 标签，避免污染正式业务指标。

本项目影子调度线程池有 2 个线程和 50 个排队位置。队列满会记录 `migration.shadow.skipped`。primary legacy/new 和 shadow legacy/new 还会分别创建管线实例，因此下游 bulkhead 线程池和熔断状态不会互相污染；故障注入配置仍然共享，方便进行一致的故障实验。

## 5. 灰度路由为什么按用户分桶

V12 使用：

```text
userBucket = userId % 100
userBucket < newPipelinePercent -> NEW
否则 -> LEGACY
```

设置 `newPipelinePercent=5` 时，bucket 0～4 进入新链路，bucket 5～99 留在旧链路。

使用稳定用户分桶而不是每次随机数有三个好处：

1. 同一个用户连续请求始终进入同一版本，体验稳定；
2. 出现问题时可以通过 userId 稳定复现；
3. 方便比较实验组与对照组的长期业务指标。

当前示例只用 `userId % 100` 帮助理解。真实系统通常使用带实验 ID 和盐值的一致性哈希，避免不同实验的流量高度重合。

## 6. 推荐结果 Diff 比较什么

`RecommendationDiffEngine` 当前比较：

| 字段 | 含义 |
|---|---|
| `exactMatch` | item 顺序、分数和召回量是否完全符合规则 |
| `overlapRate` | 新旧结果共有 item 数 / 较长列表长度 |
| `firstMismatchIndex` | 第一个不同的位置，完全相同为 -1 |
| `scoreMismatchCount` | 相同位置同一 item 的分数差异数 |
| `legacyRecallCount/newRecallCount` | 候选量是否变化 |
| `legacyCostMs/newCostMs` | 性能收益 |
| `mismatchReasons` | item、score、recall_count 等原因分类 |

requestId 和耗时不会参与业务一致性判断。两次执行本来就会产生不同 requestId，耗时改善更是重构目标的一部分。

### exactMatch 和 overlapRate 为什么都要看

假设旧结果是：

```text
[A, B, C, D, E]
```

新结果是：

```text
[B, A, C, D, E]
```

两者 overlapRate 是 100%，但 exactMatch 是 false，firstMismatchIndex 是 0。推荐顺序决定曝光位置，因此不能只看集合是否相同。

另一方面，如果实时特征导致少量尾部 item 波动，exactMatch 可能很低，但 TopK overlap 和线上业务指标仍可接受。所以排查时需要多层指标，不能只盯一个数字。

## 7. 完整发布阶段

推荐的上线过程是：

```text
阶段 1：0% NEW + 少量 shadow
  -> 用户全部走旧链路，新链路只做 Diff

阶段 2：1% NEW
  -> 检查错误率、耗时、业务指标

阶段 3：5% -> 20% -> 50% NEW
  -> 每一级观察足够时间再扩大

阶段 4：100% NEW
  -> 旧链路仍保留一段时间，随时回滚

阶段 5：稳定后删除旧代码
```

不能从 1% 指标正常直接跳到 100%。有些并发、容量、热点和长尾问题只有流量扩大后才会出现。

## 8. 怎样操作本项目

查看当前状态：

```powershell
Invoke-RestMethod "http://localhost:8080/rollout"
```

旧链路主返回，新链路 100% 影子双跑：

```powershell
Invoke-RestMethod "http://localhost:8080/rollout?newPercent=0&shadowPercent=100&clear=true"
```

灰度 5%，同时采样 20% 做反向影子比较：

```powershell
Invoke-RestMethod "http://localhost:8080/rollout?newPercent=5&shadowPercent=20"
```

全量新链路：

```powershell
Invoke-RestMethod "http://localhost:8080/rollout?newPercent=100&shadowPercent=0"
```

发现问题立即回滚旧链路：

```powershell
Invoke-RestMethod "http://localhost:8080/rollout?newPercent=0&shadowPercent=0"
```

`/rollout` 是学习项目的演示接口。生产环境必须通过有鉴权、审批、审计和配置版本管理的发布平台操作。

## 9. 一键运行完整发布演示

```powershell
.\scripts\run-rollout-demo.ps1
```

脚本会自动：

1. 构建并启动本地 JAR；
2. 设置 0% NEW、100% shadow；
3. 对三个用户逐一执行新旧 Diff；
4. 等待异步对比完成并断言结果一致；
5. 切到 5% 灰度，验证 bucket 0 和 bucket 5 边界；
6. 推到 100% NEW；
7. 停止本地进程。

## 10. 本地真实结果

本地运行三个影子请求得到：

| 指标 | 结果 |
|---|---:|
| comparisons | 3 |
| exact matches | 3 |
| exact match rate | 100% |
| average overlap | 100% |
| legacy average cost | 290.7ms |
| new average cost | 221.3ms |
| average saving | 69.3ms |

5% 灰度边界：

| userId | bucket | 主链路 |
|---:|---:|---|
| 100 | 0 | NEW |
| 105 | 5 | LEGACY |

这组结果说明：在当前固定输入下，新链路保持了业务结果，同时获得约 69ms 性能收益。

三次请求只是学习演示，不能代表真实线上结论。生产环境需要足够的样本量、完整场景覆盖和更长观察周期。

## 11. Diff 不平时怎么排查

不要看到指标不平就直接修改代码。先把差异分类：

### item 内容不同

可能原因：

- 新旧链路调用的召回源或参数不同；
- 整体截止时间让某一路变成部分结果；
- 降级、AB 参数或用户特征没有保持一致；
- 线程安全问题导致共享数据被并发覆盖。

### item 相同但顺序不同

可能原因：

- 并行完成顺序被误当成拼接顺序；
- 同分 item 缺少稳定的第二排序字段；
- HashMap 遍历顺序影响了结果；
- 浮点计算顺序变化。

### 召回量相同但最终结果不同

重点检查在线特征、过滤、混排和后处理阶段的中间结果，而不是只看最终列表。

### 只有少量实时属性不同

检查两条链路调用在线特征的时间点。双跑本身存在时间差，库存或价格可能真的发生变化，需要设置合理的字段白名单和时间容忍度。

这就是为什么重构完成后还要花大量时间补充阶段打点和中间结果 Diff。

## 12. 监控与止损

V12 新增指标：

- `migration.primary.request{pipeline=legacy/new}`：正式流量分布；
- `migration.shadow.submitted`：影子任务数；
- `migration.shadow.skipped`：影子队列满导致的丢弃；
- `migration.shadow.error`：影子执行异常；
- `migration.diff{exact=true/false}`：一致和不一致数量。

出现不一致时，`/alerts` 会产生：

```text
migration_result_mismatch
```

线上自动止损通常还会配置：

- 新链路错误率超过旧链路一定阈值；
- P99 超时或线程池拒绝率突增；
- CTR、CVR、GMV 等核心业务指标显著下降；
- Diff mismatch 或关键字段差异超过阈值。

达到条件后应自动停止扩量或回滚，而不是等人工第二天发现。

## 13. 当前演示实现和生产系统的差距

本项目的比较统计保存在内存中，服务重启就会清空。生产环境应写入指标平台、日志系统或专门的 Diff 数据存储。

此外还需要：

- 按场景、用户层级、AB 实验、设备和地域分组统计；
- 对用户数据和请求参数脱敏；
- 设置 Diff 样本保存期限和访问权限；
- 用消息队列异步处理大量对比任务；
- 支持配置版本、审批记录和一键回滚；
- 对影子请求做明确标记，避免触发计费、曝光上报等副作用。

最后一点非常重要：有副作用的接口不能直接双跑。例如下单、扣款、发送消息必须使用专门的 dry-run 或回放环境。

## 14. JUnit 和 Mockito 在这一版的作用

`MigrationRecommendationFacadeTest` 使用两个 Mockito：

- legacy mock：模拟旧链路；
- new mock：模拟新链路。

测试可以精确验证某个 bucket 调用了哪个版本，以及影子版本有没有被调用。

其中一个测试使用“只保存任务、不立即执行”的假 Executor：

```text
调用 facade
  -> legacy 已调用并返回
  -> new 尚未调用
手动执行保存的 Runnable
  -> new 才开始调用
  -> comparison 计数增加
```

它比简单测耗时更稳定地证明了影子任务不阻塞主响应。

## 15. 面试常见追问

### 为什么影子流量不能直接用来判断 CTR？

影子结果没有真正展示给用户，因此没有真实点击和成交。它适合做离线结果 Diff、错误率和性能分析。业务指标仍要依靠小流量真实灰度实验。

### 新旧结果必须 100% 一致吗？

取决于重构目标和数据实时性。纯代码重构应尽量一致；涉及并行、超时或模型升级时，应定义 TopK overlap、关键字段、业务指标等分层阈值。

### 为什么 shadow 要异步？

如果同步等待，新链路慢或失败会直接增加旧链路用户的延迟，失去影子隔离的意义。异步任务必须使用独立、有界资源。

### 5% 灰度出问题怎样回滚？

把 `newPipelinePercent` 立即改回 0，同一套稳定分桶会让所有用户回到旧链路。旧链路在观察期内不能过早删除。

### 指标打平为什么可能花几个月？

差异可能只出现在某个场景、用户层、AB 实验或极少量长尾请求；实时特征和并发还会带来非确定性。需要完善打点、保存中间结果、分类差异、逐个修复，并经历多轮灰度观察。

## 16. 面试表达模板

> 重构完成后我们没有直接全量，而是保留旧链路作为基线，增加稳定用户分桶和异步 shadow 双跑。正式请求只返回主链路结果，另一版本在独立有界线程池中执行，完成后对 item ID、顺序、分数、召回量和耗时做 Diff。先在 0% 新流量阶段观察结果一致性，再按 1%、5%、20%、50%、100% 逐级灰度，同时监控错误率、P95/P99、线程池、下游 QPS 和业务指标。出现 mismatch 或核心指标恶化就停止扩量并把比例切回 0。这个阶段往往比写重构代码更久，因为需要处理实时特征、并发顺序、AB 配置和长尾场景造成的差异。

学完这一版，你应该能解释：shadow 和真实灰度的区别、为什么必须稳定分桶、为什么 Diff 要同时看 exact 和 overlap、为什么旧链路不能在全量当天立即删除。
