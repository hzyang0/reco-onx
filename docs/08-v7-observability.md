# V7：白盒化、监控指标和基础告警

这一版对应简历里的：

```text
白盒化建设；
日志分级上报框架；
全量算子监控打点梳理；
报警策略和监控面板建设；
问题定位时间从天级提升到分钟级。
```

## 为什么需要稳定性建设

前几版解决的是架构问题：

```text
V2 数据结构治理
V3 item attr 查询优化
V4 算子框架
V5 DAG 图执行
V6 DAG 并行优化
```

但线上系统只“能跑”是不够的。

真正上线后最重要的问题是：

```text
慢在哪里？
错在哪里？
哪个场景错？
哪个算子错？
影响了多少请求？
什么时候开始变坏？
```

如果没有日志、指标、告警，这些问题只能靠人工翻日志、猜链路、复现请求，定位时间会非常长。

## V7 做了什么

V7 新增了 `observability` 包：

```text
LogLevel
StructuredLogger
MetricsRegistry
MetricSample
AlertManager
```

并新增两个 HTTP 端点：

```text
/metrics
/alerts
```

## 1. 结构化日志

新增：

```text
StructuredLogger
```

日志不再是随手打印：

```text
requestId=xxx cost=123
```

而是统一输出 JSON 风格结构化日志：

```json
{
  "time": "2026-07-16T03:43:44Z",
  "level": "INFO",
  "logger": "RecommendService",
  "event": "request_success",
  "requestId": "xxx",
  "scene": "mall",
  "costMs": 290
}
```

这样做的好处是：

- 日志可以按字段检索。
- 可以通过 `requestId` 串起一次请求。
- 可以按 `event` 分析请求成功、失败、算子失败。
- 可以按 `scene`、`operator` 聚合问题。

## 2. 日志分级

支持：

```text
DEBUG
INFO
WARN
ERROR
```

默认是 `INFO`。

可以通过环境变量控制：

```powershell
$env:LOG_LEVEL="DEBUG"
java -jar target\mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

代码里使用了 `Supplier<Map<String, Object>>` 延迟构造日志字段。

意思是：

```text
如果当前日志级别不需要打印 DEBUG，
那 DEBUG 日志里的字段 Map 就不会被创建。
```

这对应你项目里提到的：

```text
lazy init / 延迟构造，降低日志额外开销。
```

## 3. 算子级指标

执行器会记录：

```text
operator.cost
operator.success
operator.error
operator.skipped
```

标签包括：

```text
operator
status
```

比如：

```json
{
  "name": "operator.cost",
  "tags": {
    "operator": "mixRank",
    "status": "success"
  },
  "count": 1,
  "total": 124,
  "avg": 124.0,
  "max": 124
}
```

这就能回答：

```text
哪个算子最慢？
哪个算子错误最多？
哪个算子被跳过了？
```

## 4. 请求级指标

`RecommendService` 会记录：

```text
request.cost
request.success
request.error
```

标签包括：

```text
scene
status
```

这就能回答：

```text
mall 场景是否更慢？
某个场景是否错误率升高？
请求整体耗时是否超过阈值？
```

## 5. 基础告警

新增：

```text
AlertManager
```

当前规则包括：

```text
request.cost max > 500ms -> WARN
operator.cost max > 150ms -> WARN
operator.error count > 0 -> ERROR
request.error count > 0 -> ERROR
```

访问：

```text
http://localhost:8080/alerts
```

可以看到当前触发的告警。

真实公司里告警通常接入 Prometheus、Grafana、夜莺、内部监控平台、飞书/企微机器人等。本项目只是实现最小闭环：有指标、有规则、有输出。

## 6. `/metrics` 和 `/alerts`

启动服务后访问：

```powershell
Invoke-RestMethod "http://localhost:8080/recommend?userId=123&scene=mall&limit=10"
Invoke-RestMethod "http://localhost:8080/metrics"
Invoke-RestMethod "http://localhost:8080/alerts"
```

`/metrics` 用来看指标快照。

`/alerts` 用来看当前哪些规则被触发。

## 面试表达

可以这样说：

> 重构完成后，我们继续做了白盒化和稳定性建设。首先统一了结构化日志，按 requestId、scene、operator、event 等字段输出，支持按级别控制和延迟构造日志字段，降低日志开销。其次在算子执行框架中统一埋点，记录每个算子的耗时、成功、失败和跳过情况，同时在请求层记录整体耗时和成功失败。最后基于这些指标配置告警规则和监控面板，使问题能从“用户反馈后人工排查”变成“指标异常后直接定位到场景和算子”。

核心记忆点：

```text
日志解决“发生了什么”。
指标解决“整体是否异常”。
告警解决“异常时主动通知”。
监控面板解决“快速定位范围”。
```

面试官如果问“为什么问题定位从天级到分钟级”，可以回答：

```text
因为以前只能看大链路日志，无法快速判断是哪个阶段、哪个算子、哪个场景异常；
白盒化后每个算子都有耗时、成功率、错误数和关键业务量指标，
报警直接指向异常场景和异常算子，所以定位路径大幅缩短。
```
