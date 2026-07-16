# V10：下游稳定性治理——超时、重试、熔断、隔离与兜底

这一版要解决的问题是：

> 推荐接入层需要调用很多下游服务。如果直播召回突然变慢或不可用，怎样避免它拖垮整个推荐接口？

## 1. 先理解什么叫“雪崩”

假设推荐接口每秒收到 1,000 个请求，每个请求都同步等待直播召回。

正常情况下直播召回 35ms 返回，线程很快释放。如果它突然需要 10 秒：

1. 推荐服务里的工作线程开始堆积；
2. 新请求拿不到线程，只能继续排队；
3. 内存、线程数、连接数逐渐耗尽；
4. 商品召回本来是正常的，也会因为共享资源耗尽而无法工作；
5. 最终一个下游故障扩散成整个推荐服务不可用。

这就是故障传播或服务雪崩。稳定性治理的目标不是保证下游永远不出错，而是把错误限制在一个小范围内。

## 2. 五个能力分别解决什么问题

| 能力 | 通俗理解 | 解决的问题 |
|---|---|---|
| 超时 Timeout | 最多等 80ms，过时不候 | 防止请求无限等待 |
| 重试 Retry | 临时失败后再试 1 次 | 消化网络抖动等偶发故障 |
| 熔断 Circuit Breaker | 连续失败后暂时不再调用 | 防止持续攻击已经故障的下游 |
| 隔离 Bulkhead | 每个召回源使用独立小线程池 | 防止一个下游耗尽全部线程 |
| 兜底 Fallback | 直播失败就用商品和广告结果继续返回 | 保证核心接口仍然可用 |

这五个能力不是互相替代，而是按顺序共同工作。

## 3. 当前调用链

```text
RecallOperator
  |
  +-- ResilientRecallService(goods)
  |     +-- goods 独立线程池
  |
  +-- ResilientRecallService(live)
  |     +-- 熔断检查
  |     +-- live 独立线程池
  |     +-- 80ms 超时
  |     +-- 最多重试 1 次
  |     +-- 失败返回空列表
  |
  +-- ResilientRecallService(ad)
        +-- ad 独立线程池
```

这里使用了装饰器模式：`ResilientRecallService` 和原服务一样实现 `RecallService`，内部再调用真正的 `GoodsRecallService`、`LiveRecallService` 或 `AdRecallService`。

因此 `RecallOperator` 不需要知道超时和熔断的细节，也不需要修改原来的业务实现。

## 4. 超时是怎样实现的

核心流程位于 `ResilientRecallService.executeOnce`：

```java
Future<List<Item>> future = executor.submit(() -> delegate.recall(context));
return future.get(config.timeoutMs(), TimeUnit.MILLISECONDS);
```

下游调用先提交到独立线程池，当前线程最多等待指定时间。如果超过 80ms：

```java
future.cancel(true);
throw new DownstreamTimeoutException(source(), config.timeoutMs());
```

`cancel(true)` 会向任务线程发送中断信号。但要注意，中断不是强制杀死线程。如果下游 SDK 完全不处理中断，任务仍可能继续占用后台线程。因此真实项目还必须在 HTTP/RPC 客户端本身配置连接超时和读取超时。

## 5. 为什么重试不能无限进行

当前配置 `maxRetries=1`，代表最多执行两次：

```text
第一次调用失败
  -> 再试一次
      -> 成功：正常返回
      -> 仍失败：进入兜底并累计一次熔断失败
```

如果一个服务已经过载，每个请求都重试 10 次，下游收到的流量会放大 10 倍，故障会更加严重，这叫重试风暴。

线上重试通常遵循几个原则：

- 只重试超时、连接重置等临时错误；
- 参数错误、权限错误等确定性失败不重试；
- 写操作必须考虑幂等性，避免重复扣款或重复下单；
- 重试次数很少，并配合指数退避和随机抖动；
- 总重试时间不能超过上游请求的总超时预算。

## 6. 熔断器三个状态

```text
                  连续失败达到阈值
        CLOSED ----------------------> OPEN
          ^                             |
          | 探测成功                    | 等待 3 秒
          |                             v
          +------------------------- HALF_OPEN
                      探测失败 -> 重新 OPEN
```

- `CLOSED`：闭合，正常放行调用；
- `OPEN`：断开，直接走兜底，不访问下游；
- `HALF_OPEN`：等待窗口结束后，只允许一个探测请求；成功则恢复，失败则再次断开。

本项目连续 2 个逻辑请求失败就打开熔断器，3 秒后进入半开探测。

注意：一次请求内部即使重试两次，最终只给熔断器记录一次失败。因为熔断统计的是用户请求是否成功，而不是内部尝试次数。

## 7. 为什么线程池隔离叫 Bulkhead

Bulkhead 原意是船舱隔板。船的一间舱进水，隔板可以防止水流遍整艘船。

项目为 `goods`、`live`、`ad` 分别建立：

- 2 个线程；
- 长度为 8 的等待队列；
- 队列满后立即拒绝，不再无限排队。

因此 live 线程池被慢请求占满时，goods 和 ad 仍然拥有自己的线程。队列满产生 `bulkhead_full`，系统会立即兜底。

真实项目还会让不同服务使用独立连接池、限流器和资源配额，隔离不仅限于线程。

## 8. 兜底以后为什么还能返回 10 个结果

直播召回失败时，`ResilientRecallService` 返回空列表，而不是把异常继续抛给 `RecallOperator`。

接入层仍然可以使用商品、广告召回结果完成在线特征、混排和过滤。如果最终结果不足，`PostProcessOperator` 还会补充热门商品。

这体现了两类依赖：

- 强依赖：失败后核心业务无法继续，例如下单时无法确认商品价格；
- 弱依赖：失败后效果变差，但仍可提供服务，例如一路推荐召回失败。

只有弱依赖适合直接返回空列表兜底。不能为了“可用性”给价格、库存等关键数据随便造一个结果。

## 9. 当前配置

代码在 `ResilienceConfig.recallDefaults()`：

| 参数 | 当前值 | 含义 |
|---|---:|---|
| `timeoutMs` | 80ms | 单次调用最多等待时间 |
| `maxRetries` | 1 | 首次失败后最多再试一次 |
| `failureThreshold` | 2 | 连续两个逻辑请求失败后熔断 |
| `openDurationMs` | 3000ms | 熔断后等待三秒 |
| `threadPoolSize` | 2 | 每个召回源独立线程数 |
| `queueCapacity` | 8 | 每个召回源最大排队任务数 |

真实参数不能拍脑袋，需要根据历史 P95/P99、机器资源、下游容量和整条链路的超时预算确定。

## 10. 亲手观察一次熔断

先运行完整测试：

```powershell
mvn test
mvn -DskipTests package
```

启动服务：

```powershell
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

注入直播召回超时：

```powershell
Invoke-RestMethod "http://localhost:8080/resilience?source=live&fault=TIMEOUT"
```

连续请求三次：

```powershell
Invoke-RestMethod "http://localhost:8080/recommend?userId=123&scene=mall&limit=10"
```

你会看到：

| 请求 | live 状态 | attempts | 熔断状态 | 实际含义 |
|---:|---|---:|---|---|
| 1 | FALLBACK | 2 | CLOSED | 两次超时，本次兜底，累计失败 1 次 |
| 2 | FALLBACK | 2 | OPEN | 再次失败，达到阈值，熔断打开 |
| 3 | FALLBACK | 0 | OPEN | 不再访问直播服务，直接兜底 |

本地脚本的一次真实运行结果如下。具体耗时会随机器波动，状态变化和调用次数才是必须满足的正确性条件：

| 请求 | 总耗时 | 返回量 | 原因 | attempts | circuit |
|---:|---:|---:|---|---:|---|
| 1 | 446ms | 10 | timeout | 2 | CLOSED |
| 2 | 435ms | 10 | timeout | 2 | OPEN |
| 3 | 251ms | 10 | circuit_open | 0 | OPEN |

脚本会对这些逻辑条件做自动断言，并确认 `/alerts` 已产生 `downstream_fallback_happened`，因此它不仅是展示脚本，也是一个轻量的端到端测试。

恢复故障并重置熔断器：

```powershell
Invoke-RestMethod "http://localhost:8080/resilience?reset=true"
```

也可以一条命令完成打包、启动、故障注入、三次请求和恢复：

```powershell
.\scripts\run-resilience-demo.ps1
```

`/resilience` 是学习项目中的演示管理接口。真实生产系统必须有鉴权、审计，并通过配置中心或运维平台操作，不能把故障注入接口直接暴露给普通用户。

## 11. 如何观察问题

推荐响应中的 `debug.resilience.live` 会包含：

```json
{
  "status": "FALLBACK",
  "reason": "timeout",
  "attempts": 2,
  "costMs": 161,
  "circuit": {
    "state": "OPEN",
    "consecutiveFailures": 2
  }
}
```

`/metrics` 会新增：

- `downstream.call.cost`：下游每次尝试的耗时；
- `downstream.call.success`：调用成功数；
- `downstream.call.error`：按 timeout/error/bulkhead_full 分类的失败数；
- `downstream.retry`：重试次数；
- `downstream.fallback`：兜底次数和原因。

发生兜底后，`/alerts` 会出现 `downstream_fallback_happened` 告警。

## 12. JUnit 和 Mockito 在这一版怎样配合

`ResilientRecallServiceTest` 没有真的连接直播服务，而是用 Mockito 构造下游行为：

```java
when(delegate.recall(any(RecommendContext.class)))
    .thenThrow(new IllegalStateException("temporary error"))
    .thenReturn(List.of(item));
```

它表达的是：第一次调用抛异常，第二次调用成功。然后 JUnit 判断最终结果正常，并让 Mockito 验证下游确实调用了两次：

```java
assertEquals(List.of(item), result);
verify(delegate, times(2)).recall(context);
```

这正是你之前理解的方式：JUnit 构造输入并判断结果，Mockito 控制外部依赖的返回或异常，让我们稳定复现现实中很难等待的故障。

`CircuitBreakerTest` 则注入了一个可控制的假时钟，不需要真的等待三秒，就能验证 `OPEN -> HALF_OPEN -> CLOSED`。

## 13. 面试常见追问

### 超时设置得越短越好吗？

不是。太长会拖慢整个链路，太短会把正常的慢请求误判成故障。应基于下游耗时分布和总链路预算设置，并为网络抖动保留少量空间。

### 有了超时为什么还要熔断？

超时仍然会等待 80ms并占用线程。下游持续故障时，熔断可以直接在 0ms 左右失败，减少无意义调用和资源占用。

### 有了熔断为什么还要隔离？

熔断需要积累失败才能打开，而且半开探测期间仍会调用下游。隔离保证故障发生的第一刻就不会耗尽其他服务的资源。

### 什么时候不能重试？

确定性错误不重试；非幂等写操作在没有幂等机制时不能随便重试；剩余超时预算不足时也不应重试。

### 兜底是不是把异常吃掉？

用户请求层面可以不失败，但系统必须记录日志、指标和告警。否则服务表面正常，推荐效果却可能长期退化而无人知道。

## 14. 面试表达模板

> 推荐接入层依赖多路召回，为避免单个下游故障扩散，我用装饰器统一增加了超时、有限重试、熔断、线程池隔离和空结果兜底。每路召回使用独立的有界线程池，单次超时 80ms，偶发故障最多重试一次；连续两个逻辑请求失败后熔断三秒，之后通过半开请求探测恢复。召回属于弱依赖，失败时返回空列表，主链路继续使用其他召回结果。整个过程会上报调用耗时、失败原因、重试和兜底指标，并配置兜底告警。我还通过故障注入验证了前两次请求执行重试和兜底，第三次请求在熔断打开后不再访问故障服务。

不要只背这段话。打开 `ResilientRecallServiceTest`，自己修改失败次数、超时时间和熔断阈值，再观察测试与接口结果，才能真正把这些概念变成自己的能力。
