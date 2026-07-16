# V11：多路并行召回、统一截止时间与部分结果

这一版对应原实习描述中的一句关键话：

> 接入层携带准备阶段得到的参数，并行调用商品、直播和广告等多个召回服务，拿到多路召回 item。

“并行调用”不是简单地创建几个线程。还必须处理整体截止时间、部分结果、线程池满、异常隔离、结果顺序和监控问题。

## 1. 为什么顺序召回浪费时间

项目中三路召回的模拟耗时是：

| 召回源 | 耗时 |
|---|---:|
| goods | 45ms |
| live | 35ms |
| ad | 20ms |

顺序执行时：

```text
goods 45ms -> live 35ms -> ad 20ms
总耗时约 45 + 35 + 20 = 100ms
```

并行执行时：

```text
时间 0ms   goods 开始 |-----------------------------| 45ms
时间 0ms   live  开始 |----------------------| 35ms
时间 0ms   ad    开始 |------------| 20ms

总耗时约 max(45, 35, 20) = 45ms
```

三路服务之间没有数据依赖，所以没有必要互相等待。这是并行优化成立的前提。

## 2. V11 之后的整体并行结构

```text
prepare
   |
degradation
   |
recall fan-out（内部并行 goods/live/ad，整体截止 120ms）
   |\
   | +------------------+
   |                    |
onlineFeature        mixRank       <- DAG 层并行
   |                    |
   +---------+----------+
             |
           filter
             |
        postProcess
```

这里有两层并行：

- 算子内部并行：多路召回同时执行；
- DAG 算子并行：召回完成后，在线特征和混排同时执行。

因此面试时说“做了并行优化”，必须明确是哪一层、哪些任务之间没有依赖，以及最终如何汇合。

代码仍然遵循前面版本的“大算子拆分”原则：

- `RecallOperator`：只处理降级跳过规则、调用 fan-out、把结果写回 Context；
- `ParallelRecallFanout`：处理线程池、任务提交、完成队列、截止时间、取消和结果聚合；
- `RecallFanoutConfig`：保存整体超时、并行度和队列容量。

并行细节增加后，没有重新把 `RecallOperator` 变成难以维护的大文件。

## 3. 为什么不能这样写

一种常见的错误写法是先提交三个任务，再按照提交顺序 `get()`：

```java
Future<List<Item>> goods = submit(goodsRecall);
Future<List<Item>> live = submit(liveRecall);
Future<List<Item>> ad = submit(adRecall);

goods.get();
live.get();
ad.get();
```

虽然任务确实同时运行，但如果 `goods` 很慢，当前线程会一直卡在第一个 `get()`，无法及时消费已经完成的 live 和 ad，也很难正确实现统一截止时间。

V11 使用 `ExecutorCompletionService`：

```java
CompletionService<RecallResult> completionService =
    new ExecutorCompletionService<>(executor);
```

谁先执行完，谁先进入完成队列。接入层使用剩余时间等待下一个完成任务：

```java
long remainingNanos = deadlineNanos - System.nanoTime();
Future<RecallResult> completed =
    completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
```

这样不关心提交顺序，只关心谁已经产生可用结果。

## 4. 单路超时和整体截止时间有什么区别

V10 已经给每一路召回设置了 80ms 单次超时，并允许重试一次。最坏情况下，一路服务可能消耗：

```text
第一次 80ms 超时 + 第二次 80ms 超时 = 160ms
```

但 V11 给整个召回阶段的预算只有 120ms：

```text
整个 recall 阶段最多 120ms
  ├─ goods 可以在预算内完成
  ├─ ad 可以在预算内完成
  └─ live 如果重试仍未完成，到 120ms 就被取消
```

这是分层超时预算：

- 单路超时保护一个下游调用；
- 整体截止时间保护推荐主链路；
- 上游 HTTP 超时还应该大于整条推荐链路预算。

不能让每一层都使用相同的 500ms 超时，否则最内层多次重试后，外层早已超时，后续计算全部变成无用功。

## 5. 什么叫部分结果

如果 120ms 到达时 goods 和 ad 已完成、live 未完成，接入层不会把整个请求判为失败，而是：

1. 取消 live 对应的任务；
2. 保留 goods 和 ad 的 17 个 item；
3. 继续执行在线特征、混排和过滤；
4. 最终不足时由后处理热门商品兜底；
5. 在 debug、指标和告警中明确记录这是部分结果。

响应中的 `debug.recallFanout` 类似：

```json
{
  "status": "PARTIAL",
  "partialResult": true,
  "costMs": 120,
  "submittedSources": ["goods", "live", "ad"],
  "completedSources": ["goods", "ad"],
  "timedOutSources": ["live"],
  "fallbackSources": ["live"],
  "itemCountBySource": {
    "goods": 12,
    "ad": 5
  }
}
```

用户仍然拿到推荐结果，但系统不会把效果退化隐藏起来。

## 6. 超时、熔断和降级不是一回事

| 机制 | 谁触发 | 是否异常 | 例子 |
|---|---|---|---|
| 降级 | 系统策略主动决定 | 否 | HEAVY 主动跳过 live、ad |
| 整体截止 | 本次请求时间预算耗尽 | 是 | live 120ms 未完成 |
| 熔断 | 连续失败达到阈值 | 是 | live 后续请求直接返回空结果 |

因此 `degradationSkippedSources` 不会让 fan-out 变成 `PARTIAL`。这是系统主动选择的合法执行图。

而 `timedOutSources`、`failedSources`、`fallbackSources` 表示计划执行的召回没有给出正常结果，会被标记为 `PARTIAL`。

## 7. 为什么完成顺序和拼接顺序要分开

并发任务的完成顺序不稳定：这一次可能 ad、live、goods，下一次可能 live、ad、goods。

如果直接按照完成顺序拼接 item，后续逻辑可能因为线程调度差异产生不同结果，问题难以复现。

V11 收集结果时按完成顺序提升效率，但最终拼接时重新按配置顺序：

```text
固定顺序：goods -> live -> ad
```

这叫确定性。并发系统中，能并行执行不代表可以接受随机业务结果。

## 8. 线程池为什么也要有界

fan-out 层使用：

| 参数 | 当前值 |
|---|---:|
| 整体截止时间 | 120ms |
| 工作线程 | 12 |
| 排队容量 | 100 |

如果无限创建线程或使用无限队列，高峰期请求会不断堆积，最终耗尽内存。队列满时任务会被拒绝，记录为 `fanout_pool_full`，主链路继续使用其他来源的结果。

当前项目存在两层线程池：

- fan-out 池负责并发编排多路召回；
- 每个下游的 bulkhead 池负责资源隔离和单次超时。

这种写法便于学习同步接口下的稳定性组合。真实高并发项目通常优先使用异步 RPC/HTTP 客户端和 `CompletableFuture`，减少“一个等待线程套另一个等待线程”的成本。

## 9. 取消任务不等于强杀线程

整体截止时调用：

```java
future.cancel(true);
```

这只会发送中断信号。下游代码或 SDK 必须正确响应中断，才能真正停止工作。

V10 的 `ResilientRecallService` 在收到外层取消后会：

- 取消正在等待的内部任务；
- 停止继续重试；
- 记录一次 `cancelled` 兜底；
- 给熔断器累计失败。

如果一个 SDK 忽略中断，仍然需要依靠它自身的网络超时、连接池回收和请求取消 API。

## 10. 本地真实运行结果

一条命令运行健康和超时两种场景：

```powershell
.\scripts\run-parallel-recall-demo.ps1
```

本地一次运行得到：

| 场景 | 总耗时 | recall 墙钟耗时 | 各路耗时之和 | fan-out | 完成来源 | 超时来源 | 召回量 | 返回量 |
|---|---:|---:|---:|---|---|---|---:|---:|
| healthy | 237ms | 51ms | 113ms | SUCCESS | goods+live+ad | - | 25 | 10 |
| live_timeout | 294ms | 125ms | - | PARTIAL | goods+ad | live | 17 | 10 |

健康场景最关键的证据是：

```text
recall 墙钟耗时 51ms < 各路耗时之和 113ms
```

这能证明三路不是顺序执行。具体毫秒数会随机器变化，但这个关系应该保持。

## 11. 指标和告警

V11 新增：

- `recall.source.cost`：每一路任务从提交执行到完成的耗时；
- `recall.fanout.cost`：整个并行召回阶段的墙钟耗时；
- `recall.fanout.request`：SUCCESS/PARTIAL 请求数量；
- `recall.fanout.timeout`：达到整体截止时间的来源；
- `recall.fanout.failure`：线程池拒绝或调用异常。

出现整体截止超时后，`/alerts` 会产生：

```text
recall_fanout_timeout
```

`run-parallel-recall-demo.ps1` 会自动验证这个告警存在。

## 12. 单元测试怎样证明真的并行

单纯断言“总耗时小于 100ms”容易受机器调度影响。V11 使用 `CountDownLatch`：

```java
CountDownLatch allStarted = new CountDownLatch(3);

allStarted.countDown();
allStarted.await();
```

每一路召回启动后先把计数减一，然后等待三路全部启动。如果代码是顺序执行，第一路永远等不到第二、第三路，测试就会失败；只有真正并行，三路才能一起通过屏障。

另一个测试构造一个快速 goods 和一个休眠一秒的 live，设置 50ms 整体截止，然后判断：

- 算子没有等待一秒；
- live 线程收到了中断；
- goods item 被保留；
- `timedOutSources` 是 live；
- fan-out 状态是 PARTIAL。

## 13. 为什么不用 parallelStream

`parallelStream()` 写起来短，但默认使用 JVM 公共 `ForkJoinPool`：

- 很难为推荐召回单独限制线程和队列；
- 可能和进程里的其他并行任务争抢公共线程；
- 不方便实现统一截止时间、按完成顺序收集和逐任务取消；
- 监控与线程命名不清晰。

接入层需要明确的资源边界，因此使用专用线程池。

## 14. 面试常见追问

### 并行路数越多越好吗？

不是。并行会增加线程、连接、CPU 和下游 QPS。新增召回源必须评估收益、资源成本和下游容量，并受线程池与整体截止时间限制。

### 一路超时为什么不让整个请求失败？

多路召回通常是可降级的弱依赖。其他召回仍能提供候选，部分结果比完全失败更符合推荐场景。但必须监控业务指标，避免长期效果退化。

### 为什么不等慢服务再多等 20ms？

整体截止时间来自端到端预算。给召回多 20ms，就会压缩在线特征、混排、网络返回的预算，并推高整条链路 P95/P99。是否值得需要通过实验决定，不能每层都自行延长。

### 并发写 RecommendContext 安全吗？

召回服务主要读取准备阶段产生的只读数据，每一路创建自己的 item。需要共享写入的 resilience debug 使用同步方法。并发改造前必须梳理哪些数据只读、哪些线程独占、哪些需要同步。

### 为什么最终还按固定顺序拼接，混排不是会重新排序吗？

即使后面会排序，确定性输入仍有利于调试、回放和处理同分场景。不能让线程调度成为隐式业务规则。

## 15. 面试表达模板

> 原链路的商品、直播和广告召回是顺序执行，耗时近似三路之和。我把 RecallOperator 改造成基于 CompletionService 的 fan-out/fan-in 模型，三路同时提交，谁先完成先收集，正常耗时从约 100ms 的累加值降为最慢一路约 45ms。召回阶段设置 120ms 统一截止时间，到点取消未完成任务并使用已完成的部分结果，最终再按固定来源顺序拼接，保证结果确定性。降级主动跳过的来源不算异常；超时、失败或熔断兜底会标记 PARTIAL，并上报来源级耗时、fan-out 耗时、超时指标和告警。测试中使用 CountDownLatch 验证任务确实同时启动，并通过故障注入验证 live 超时时 goods、ad 结果仍可进入后续链路。

真正掌握这一版，需要你能自己回答三个问题：为什么顺序 `Future.get()` 不理想、单路超时和整体截止有什么区别、为什么收集顺序可以随机但拼接顺序必须固定。
