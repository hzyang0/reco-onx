# 架构与请求链路

## 1. 系统边界

Mini Reco 位于 HTTP 请求与多个推荐数据来源之间，负责流程编排，不负责训练推荐模型。

```text
Client
  -> Embedded Console or HTTP API
  -> HTTP Handler
  -> Recommendation Service
  -> DAG Execution Engine
  -> Operators
  -> Local Downstream Implementations
```

本地实现模拟了用户特征、AB 参数、地址、三路召回、在线特征和混排服务，因此项目无需额外基础设施即可运行。

根路径 `/` 由 `DashboardHttpHandler` 返回内置 HTML，CSS 和 JavaScript 同样从 classpath 读取。控制台只负责展示和交互，真实推荐请求仍调用 `/recommend`，状态与指标分别调用 `/health` 和 `/metrics`。因此页面层和推荐链路之间仍以 HTTP JSON 契约解耦。

## 2. RecommendContext

`RecommendContext` 表示一次推荐请求的运行状态，保存：

- 原始请求；
- 用户特征、AB 参数和地址；
- 召回、排序、过滤和最终结果；
- 各阶段耗时；
- 调试信息。

这些数据按用途拆成明确字段，避免所有数据混在 `Map<String, Object>` 中。Item 属性使用 `EnumMap<AttrName, ItemAttr>`，固定 Key 由枚举统一管理。

Context 是请求级对象，不会在不同请求之间共享。DAG 中并行节点应写入不同字段；同步方法用于保护共享的耗时和调试 Map。

## 3. Operator

每个处理阶段都实现同一个接口：

```java
public interface Operator {
    String name();
    void execute(RecommendContext context);
}
```

六个业务算子：

| 算子 | 作用 |
| --- | --- |
| `PrepareOperator` | 校验参数并获取用户特征、AB 参数、地址 |
| `RecallOperator` | 并行调用商品、直播和广告召回 |
| `OnlineFeatureOperator` | 补充价格、库存和状态 |
| `MixRankOperator` | 根据基础分、用户偏好和来源调整排序 |
| `FilterOperator` | 移除无库存或已下线 Item |
| `PostProcessOperator` | 截断结果并在数量不足时增加兜底 Item |

拆分后，每个阶段可以独立测试、替换和重新编排。

## 4. DAG

`DemoWiring` 中构建的 DAG：

```text
Prepare
  -> Recall
       ├─ OnlineFeature ─┐
       └─ MixRank ───────┴─ Filter
                              -> PostProcess
```

`DagGraph` 在构造时检查：

- 节点名称是否重复；
- 依赖节点是否存在。

`ParallelDagOperatorExecutor` 还会使用拓扑遍历检查环。

执行时，执行器维护每个节点的剩余依赖数：

1. 依赖数为 0 的节点进入就绪队列；
2. 就绪节点提交到线程池；
3. 节点完成后，下游节点的剩余依赖数减 1；
4. 下游依赖数归零后即可提交；
5. 任一节点异常时，取消本次执行已提交但未完成的任务。

## 5. 并行召回

`ParallelRecallFanout` 使用固定大小线程池和 `ExecutorCompletionService`：

1. 商品、直播和广告分别提交为独立任务；
2. 完成队列按实际完成顺序返回 `Future`；
3. 整个召回阶段共享一个 deadline；
4. deadline 到达后取消未完成任务；
5. 保留已经成功返回的结果；
6. 合并时仍按照 goods、live、ad 的配置顺序输出，保证结果稳定。

单路异常或超时会产生部分结果，并写入：

```text
debug.recallFanout
```

其中包含完成来源、超时来源、失败来源、来源耗时和结果数量。

`Future.cancel(true)` 只发送线程中断信号，任务是否能及时结束取决于任务本身是否响应中断。

## 6. 两层并行

项目包含两层不同粒度的并行：

- 召回层：三个数据来源并行；
- DAG 层：在线特征和混排并行。

前者减少等待多个下游的总耗时，后者缩短整条请求链路的关键路径。并行并非没有成本，它会增加线程调度、队列和共享下游的压力。

## 7. 可观测性

`RecommendService` 和执行器记录：

- 请求成功、失败和总耗时；
- 算子成功、失败和耗时；
- 召回来源耗时、超时和失败；
- 返回、召回和过滤结果数量。

`StructuredLogger` 输出 JSON 行，`MetricsRegistry` 使用线程安全的内存数据结构保存计数器与耗时分桶。

## 8. 错误处理

- 非 GET 请求返回 405；
- 参数格式或场景非法返回 400；
- 单路召回失败时保留其他来源的结果；
- 召回整体超时时取消未完成任务并返回部分结果；
- DAG 关键节点异常时终止本次请求并记录错误指标。

## 9. 当前取舍

- 使用 JDK `HttpServer`，减少框架层代码；
- 生产代码只依赖 Java 17 标准库；
- 下游实现为本地模拟，便于稳定复现并发行为；
- 指标为进程内存实现，重启后不会保留；
- Context 和 Item 是可变对象，并行节点通过明确的读写边界协作。
