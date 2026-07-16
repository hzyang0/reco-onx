# V4：从大服务到算子执行框架

这一版对应快手项目里的：

```text
重新设计算子执行框架；
支持算子参数配置、图参数配置、中间结果存储；
拆分大算子，抽象通用算子。
```

本项目 V4 先做最关键的第一步：

```text
把 RecommendService 里的大流程拆成多个 Operator，
再由 OperatorExecutor 统一执行。
```

## V3 的问题

V3 已经解决了两个数据结构问题：

- 请求级别：`Map<String, Object>` -> `RecommendContext`
- item 级别：`List<ItemAttr>` -> `Map<AttrName, ItemAttr>`

但主链路仍然集中在 `RecommendService` 里：

```text
prepare
recall
onlineFeature
filter
mixRank
postProcess
```

这会带来几个问题：

- 一个类承担太多职责。
- 阶段逻辑难单独复用。
- 阶段耗时统计、跳过、降级等通用逻辑容易散落。
- 后续想改成 DAG 执行时，没有统一节点抽象。

## V4 怎么改

新增统一接口：

```java
public interface Operator {
    String name();

    void execute(RecommendContext context);
}
```

每个阶段都是一个算子：

```text
PrepareOperator
RecallOperator
OnlineFeatureOperator
FilterOperator
MixRankOperator
PostProcessOperator
```

主服务现在只做三件事：

```text
1. 创建 RecommendContext
2. 调用 OperatorExecutor 执行流水线
3. 从 Context 中取 finalItems 组装响应
```

## OperatorExecutor 的职责

`OperatorExecutor` 统一处理这些横切逻辑：

```text
1. 按配置顺序执行算子
2. 根据 OperatorConfig 判断算子是否启用
3. 记录每个算子的耗时
4. 把中间结果留在 RecommendContext
```

这就把“业务逻辑”和“执行框架逻辑”分开了。

## 为什么这是架构重构

如果只是把代码从一个文件复制到多个文件里，那只是文件拆分。

V4 的关键不是文件变多，而是系统多了一层稳定抽象：

```text
Operator       标准节点抽象
OperatorConfig 节点配置
OperatorExecutor 执行框架
RecommendContext 节点间通信和中间结果存储
```

有了这层抽象后，后续才能自然演进到：

```text
线性流水线 -> DAG 图执行
串行执行 -> 可并行执行
普通日志 -> 统一算子日志
手动耗时 -> 统一算子监控
硬编码流程 -> 可配置流程
```

## 和真实大厂项目的关系

真实项目里的算子框架通常更复杂，会包含：

- 算子输入输出声明
- 算子参数配置
- 图参数配置
- 超时控制
- 降级策略
- 异常隔离
- 线程池选择
- 监控打点
- trace 日志
- 中间结果存储

本项目 V4 只实现最小闭环：

```text
算子接口 + 算子配置 + 执行器 + 中间结果 Context
```

这样先把核心思想跑通，后续再补 DAG、并行、监控和降级。

## 面试表达

可以这样说：

> 在代码重构阶段，我们不是简单拆文件，而是抽象了一套算子执行框架。每个业务阶段都实现统一的 Operator 接口，由执行器负责按图或配置调度执行，并统一处理耗时统计、参数配置、中间结果传递等横切逻辑。这样业务逻辑沉淀在具体算子里，执行逻辑沉淀在框架里，降低了大算子的复杂度，也为后续 DAG 图重构和并行优化打基础。

这段话的核心是：

```text
业务逻辑和执行框架解耦。
```
