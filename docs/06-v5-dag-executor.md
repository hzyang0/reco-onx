# V5：从线性算子流水线到 DAG 图执行

这一版对应简历里的：

```text
ONX 是基于 DAG 图的执行引擎；
每个算子是图上的一个节点；
DAG 图重构降低了图复杂度，也为后续并行优化做准备。
```

## V4 的状态

V4 已经有了算子框架：

```text
PrepareOperator
RecallOperator
OnlineFeatureOperator
FilterOperator
MixRankOperator
PostProcessOperator
```

但执行方式还是线性列表：

```text
prepare -> recall -> onlineFeature -> filter -> mixRank -> postProcess
```

线性列表的问题是：

- 只能表达固定顺序。
- 不能显式表达“谁依赖谁”。
- 后续不方便判断哪些节点可以并行。
- 图结构复杂后，靠人工维护顺序容易出错。

## V5 做了什么

V5 新增了 DAG 图模型：

```text
DagNode
DagGraph
DagOperatorExecutor
```

现在每个算子节点声明自己的依赖。

当前推荐链路的图是：

```text
prepare
  -> recall
    -> onlineFeature
      -> filter
        -> mixRank
          -> postProcess
```

代码上是：

```java
DagGraph graph = new DagGraph(List.of(
    DagNode.of(prepareOperator),
    DagNode.of(recallOperator, PrepareOperator.NAME),
    DagNode.of(onlineFeatureOperator, RecallOperator.NAME),
    DagNode.of(filterOperator, OnlineFeatureOperator.NAME),
    DagNode.of(mixRankOperator, FilterOperator.NAME),
    DagNode.of(postProcessOperator, MixRankOperator.NAME)
));
```

意思是：

```text
recall 依赖 prepare
onlineFeature 依赖 recall
filter 依赖 onlineFeature
mixRank 依赖 filter
postProcess 依赖 mixRank
```

## DAG 执行器怎么执行

`DagOperatorExecutor` 使用拓扑排序。

拓扑排序可以理解成：

```text
每次找出“依赖都已经完成”的节点执行；
执行完后，释放依赖它的下游节点；
直到所有节点都执行完成。
```

如果图里出现环，比如：

```text
A 依赖 B
B 又依赖 A
```

就无法确定谁先执行，执行器会直接报错：

```text
DAG contains cycle
```

如果某个节点依赖了不存在的节点，也会在建图时失败。

## 为什么 V5 暂时不并行

DAG 不等于并行。

DAG 只是把依赖关系表达清楚：

```text
谁必须先执行
谁可以后执行
谁理论上可以并行
```

真正并行还需要额外处理：

- 线程池
- 超时控制
- 异常传播
- 上下文线程安全
- 多节点同时写中间结果的冲突
- 关键路径耗时统计

所以本项目分两步：

```text
V5：先做 DAG 图表达和拓扑执行
V6：再做可并行节点并行执行
```

这更接近真实工程改造节奏。

## 这一步的价值

V5 的核心价值不是性能提升，而是“执行关系显式化”。

重构前：

```text
顺序藏在代码顺序里
```

重构后：

```text
依赖关系写在图里
```

这会带来几个收益：

- 图结构可校验。
- 缺失依赖能提前发现。
- 循环依赖能提前发现。
- 后续可以基于依赖关系做并行化。
- 面试时能自然过渡到 120ms 并行优化。

## 面试表达

可以这样说：

> 在算子框架抽象完成后，我们进一步把原来的线性执行流程改造成 DAG 图执行。每个算子节点声明自己的上游依赖，执行器通过拓扑排序决定执行顺序，并在建图和执行前校验缺失依赖、循环依赖等问题。这一步的直接收益不是性能，而是把执行关系从代码顺序中显式抽象出来，为后续图重构、冗余节点删除和算子并行化打基础。

注意这里要强调：

```text
DAG 图重构是并行优化的前置条件，但不是并行本身。
```
