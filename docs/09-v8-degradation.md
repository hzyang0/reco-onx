# V8：U 分层动态降级

这一版对应简历里常见的一类稳定性建设：

```text
在大促或高峰期，通过动态降级和用户分层保护核心链路，降低超时率和下游压力。
```

## 1. 什么是降级

降级不是“系统坏了之后随便少返回一点结果”。

更准确地说，降级是系统在压力变大时主动减少非核心工作量，让核心链路继续可用。

在推荐接入层里，常见可降级点包括：

- 跳过低优先级召回，比如广告召回、直播召回。
- 减少候选 item 数量。
- 减少返回数量。
- 关闭部分昂贵特征。
- 关闭部分复杂排序策略。

本项目选择了两个最容易观察的点：

- 跳过部分召回源。
- 降低最终返回上限。

## 2. 什么是 U 分层

U 分层可以理解成“不是所有用户都用同一个降级策略”。

例如系统压力很大时，不能直接让所有用户体验都变差。更合理的做法是：

```text
核心用户 / 高价值用户：尽量保持完整体验
普通用户 / 低优先级流量：先做轻量降级
```

真实业务里，用户层级可能来自用户价值、活跃度、会员等级、实时流量策略等。

本项目用一个简单、稳定、可复现的方式模拟：

```java
bucket = userId % 100
```

`bucket` 越大，代表越容易被降级命中。

## 3. V8 的降级规则

当前实现有三个等级：

| 等级 | 命中用户 | 跳过召回 | 返回上限 |
| --- | --- | --- | --- |
| NONE | 无 | 不跳过 | 使用请求原始 limit |
| LIGHT | bucket 80-99 | ad | 最多 8 个 |
| HEAVY | bucket 50-99 | ad、live | 最多 6 个 |

例子：

```text
userId=185
bucket=85
```

当降级等级为 `LIGHT` 时：

- 命中 bucket 80-99。
- 跳过广告召回。
- 如果请求 limit=10，实际 effectiveLimit=8。

当降级等级为 `HEAVY` 时：

- 命中 bucket 50-99。
- 跳过广告召回和直播召回。
- 如果请求 limit=10，实际 effectiveLimit=6。

## 4. 代码结构

新增包：

```text
com.interview.minireco.degradation
```

核心类：

```text
DegradationLevel      降级等级：NONE / LIGHT / HEAVY
UserLayer             根据 userId 计算用户 bucket
DegradationDecision   一次请求的降级决策结果
DegradationManager    当前全局降级开关和策略决策入口
```

新增算子：

```text
DegradationOperator
```

它在 DAG 中位于：

```text
prepare -> degradation -> recall
```

这样做的原因是：

- `prepare` 先完成请求参数、用户特征、AB 参数准备。
- `degradation` 基于请求上下文生成本次请求的降级决策。
- `recall` 之后的所有算子都读取同一份决策。

## 5. 为什么要把降级决策写进 RecommendContext

如果每个算子都自己判断一次是否降级，会出现几个问题：

- 策略散落在多个地方，后续不好维护。
- 不同算子可能判断结果不一致。
- 排查问题时不知道一次请求到底采用了什么降级策略。

所以 V8 把一次请求的降级结果封装成：

```text
DegradationDecision
```

并写入：

```text
RecommendContext
```

后续算子只读这个结果。

这类似真实系统里的“请求级策略快照”。

## 6. 运行方式

启动服务：

```powershell
mvn -DskipTests package
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

查看当前降级状态：

```powershell
Invoke-RestMethod "http://localhost:8080/degradation"
```

开启轻度降级：

```powershell
Invoke-RestMethod "http://localhost:8080/degradation?level=LIGHT"
```

开启重度降级：

```powershell
Invoke-RestMethod "http://localhost:8080/degradation?level=HEAVY"
```

关闭降级：

```powershell
Invoke-RestMethod "http://localhost:8080/degradation?level=NONE"
```

## 7. 对比观察

先关闭降级：

```powershell
Invoke-RestMethod "http://localhost:8080/degradation?level=NONE"
Invoke-RestMethod "http://localhost:8080/recommend?userId=185&scene=mall&limit=10"
```

再开启重度降级：

```powershell
Invoke-RestMethod "http://localhost:8080/degradation?level=HEAVY"
Invoke-RestMethod "http://localhost:8080/recommend?userId=185&scene=mall&limit=10"
```

你应该能在返回的 `debug.degradation` 中看到：

```json
{
  "level": "HEAVY",
  "userBucket": 85,
  "degraded": true,
  "originalLimit": 10,
  "effectiveLimit": 6,
  "skippedRecallSources": ["ad", "live"]
}
```

同时 `returnedItemCount` 会变成 6。

## 8. 面试表达

可以这样讲：

> 我们在接入层做了动态降级能力。核心思路是把降级策略集中到一个 DegradationManager 中，根据当前全局降级等级和用户 U 分层生成请求级 DegradationDecision，并写入 RecommendContext。后续召回、混排、后处理算子不再各自判断策略，而是统一读取这份决策。比如 LIGHT 降级时只对低优先级用户跳过广告召回并降低返回上限，HEAVY 降级时进一步跳过直播召回。这样在高峰期可以减少下游调用和候选 item 数量，同时保护核心用户体验。

面试官如果问“为什么要 U 分层，而不是所有用户一起降级”，可以回答：

```text
因为降级本质上是体验和稳定性的权衡。
如果所有用户一起降级，虽然系统压力会下降，但核心用户体验也会受损。
U 分层可以把降级优先施加到低优先级流量上，在降低系统压力的同时保护核心用户。
```

面试官如果问“降级策略为什么放在独立 Manager 里”，可以回答：

```text
为了让策略集中、可观测、可测试。
如果策略散落在多个算子里，后续新增规则或排查问题都很困难。
集中生成请求级决策后，每个算子只消费决策，不关心策略细节。
```

## 9. 本版学习重点

这一版你需要真正理解：

- 降级是稳定性建设，不是简单少返回数据。
- U 分层是为了保护不同优先级用户。
- 请求级决策比算子内部分散判断更稳定。
- 动态开关必须能运行时查看和切换。
- 降级要配合指标观察，否则无法判断是否真的生效。
