# V6：DAG 并行执行与关键路径优化

这一版对应简历里最容易被追问的点：

```text
梳理图中算子之间的数据依赖关系，进行算子并行化改造；
P99 耗时下降 120ms，主要来自并行化。
```

## V5 的链路

V5 虽然已经有 DAG 图，但图仍然是串行依赖：

```text
prepare
  -> recall
    -> onlineFeature
      -> filter
        -> mixRank
          -> postProcess
```

对应耗时大致是：

```text
prepare 50ms
recall 110ms
onlineFeature 125ms
mixRank 125ms

总耗时约 410ms
```

这里的问题是：

```text
onlineFeature 和 mixRank 串行相加了。
```

## V6 的改造

V6 新增并行 DAG 执行器：

```text
ParallelDagOperatorExecutor
```

并且把业务图改成：

```text
prepare
  -> recall
       -> onlineFeature
       -> mixRank
  -> filter
  -> postProcess
```

更准确地说：

```text
recall 完成后，onlineFeature 和 mixRank 同时执行；
onlineFeature 和 mixRank 都完成后，filter 才执行；
filter 完成后，postProcess 执行。
```

代码里的 DAG 依赖是：

```java
DagGraph graph = new DagGraph(List.of(
    DagNode.of(prepareOperator),
    DagNode.of(recallOperator, PrepareOperator.NAME),
    DagNode.of(onlineFeatureOperator, RecallOperator.NAME),
    DagNode.of(mixRankOperator, RecallOperator.NAME),
    DagNode.of(filterOperator, OnlineFeatureOperator.NAME, MixRankOperator.NAME),
    DagNode.of(postProcessOperator, FilterOperator.NAME)
));
```

## 为什么能省时间

串行时：

```text
onlineFeature 125ms + mixRank 125ms = 250ms
```

并行后：

```text
max(onlineFeature 125ms, mixRank 125ms) = 125ms
```

所以大约节省：

```text
250ms - 125ms = 125ms
```

这就是关键路径优化。

注意：

```text
并行优化不是让 onlineFeature 自己变快，
也不是让 mixRank 自己变快，
而是让它们重叠执行，从而缩短端到端耗时。
```

## 本项目实测

V5 串行链路请求 `/recommend`，总耗时约：

```text
410ms
```

V6 并行链路请求 `/recommend`，总耗时约：

```text
290ms
```

阶段耗时示例：

```json
{
  "prepare": 50,
  "recall": 110,
  "mixRank": 124,
  "onlineFeature": 125,
  "filter": 0,
  "postProcess": 0
}
```

端到端耗时不是这些阶段简单相加，因为 `mixRank` 和 `onlineFeature` 已经并行执行。

## 为什么要调整过滤位置

原来的链路是：

```text
onlineFeature -> filter -> mixRank
```

也就是说，混排前已经把无库存、下架 item 过滤掉。

V6 改成：

```text
onlineFeature 和 mixRank 并行 -> filter -> postProcess
```

也就是说，混排可能会先看到一些后面会被过滤掉的 item。

这就是你快手项目里真实存在的风险：

```text
可能改变混排返回结果。
```

本项目里也能看到类似现象：

```text
mixRank 先返回 top 10；
filter 后只剩 8 个；
postProcess 用 fallback 补齐 10 个。
```

这就是“混排后过滤”的代价。

## 为什么这个方案仍然可接受

这种改造能否上线，取决于业务验证。

真实项目里需要看：

- 被过滤 item 占比是否足够小。
- fallback 补齐比例是否足够小。
- GMV、CTR、CVR 等核心指标是否打平。
- 混排同学是否接受过滤位置变化。
- AB 实验和灰度期间是否出现异常波动。

你面试时不能只说“并行后快了”，还要主动讲风险：

```text
过滤位置后移可能影响混排结果，需要通过打点和 AB 实验验证。
```

这是工程判断，不是单纯写代码。

## ParallelDagOperatorExecutor 做了什么

并行执行器的核心逻辑是：

```text
1. 统计每个节点还有多少依赖没完成。
2. 找出依赖数为 0 的节点，提交到线程池执行。
3. 某个节点执行完成后，减少下游节点的依赖计数。
4. 如果某个下游节点依赖都完成了，就提交执行。
5. 所有节点完成后，请求结束。
```

这比串行拓扑执行多了几个工程问题：

- 需要线程池。
- 需要等待任意一个节点完成。
- 需要传播节点异常。
- 需要处理线程中断。
- 需要让 `RecommendContext` 的 debug 和 stageCost 写入具备线程安全性。

本项目里把 `addStageCostMs`、`putDebug`、`buildDebugSnapshot` 做了同步，避免多个并发算子同时写 debug 或耗时 map。

## 面试表达

可以这样说：

> 在 DAG 图重构之后，我们进一步梳理了算子之间的数据依赖。原链路中在线特征算子和混排算子是串行执行的，扩召回后在线特征耗时会随 item 数量上升，导致端到端耗时明显增加。排查后发现混排并不强依赖在线特征结果，因此可以把在线特征和混排改成并行执行，等两者都完成后再做过滤和后处理。这个改造把关键路径从 onlineFeature + mixRank 变成 max(onlineFeature, mixRank)，因此拿到了约 120ms 的耗时收益。风险是过滤位置后移可能影响混排结果，所以需要通过打点、结果 diff、AB 实验和灰度验证确认被过滤比例足够小、核心指标可打平。

核心记忆点：

```text
不是单算子优化，是关键路径优化。
不是无风险优化，风险是混排后过滤改变结果。
```
