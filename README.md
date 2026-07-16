# Mini Reco Access Layer

这是一个“迷你版电商推荐接入层”，用来把简历里的快手电商推荐接入层项目拆成可学习、可运行、可重构的小项目。

第一版刻意保留了一些历史包袱：

- 所有请求上下文放在 `Map<String, Object>` 里。
- context key 使用字符串，存在拼写错误和类型转换风险。
- 准备、召回、在线特征、过滤、混排、后处理都集中在一个大服务里。
- 在线特征和混排是串行执行，后续可以做并行优化。
- item 的属性使用 `List<ItemAttr>`，查找指定属性需要遍历。

这些不是“写错了”，而是为了复刻真实业务系统早期能跑但逐渐复杂的状态。后续我们会一步步把它重构成算子化、DAG 化、可观测、可测试的版本。

## 运行

```powershell
mvn test
mvn -DskipTests package
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

打开另一个终端请求：

```powershell
Invoke-RestMethod "http://localhost:8080/recommend?userId=123&scene=mall&limit=10"
```

健康检查：

```powershell
Invoke-RestMethod "http://localhost:8080/health"
```

## Docker 部署

```powershell
mvn -DskipTests package
docker build -t mini-reco-access-layer:0.1.0 .
docker run --rm -p 8080:8080 mini-reco-access-layer:0.1.0
```

## 第一版链路

```text
HTTP 请求
  -> 准备阶段：解析参数、获取用户特征、AB 参数、地址
  -> 召回阶段：商品召回、直播召回、广告召回
  -> 在线特征：补价格、库存、状态
  -> 过滤阶段：过滤无库存/下架 item
  -> 混排阶段：按用户特征、AB 参数、基础分排序
  -> 后处理：兜底、统计耗时、返回 JSON
```

## 后续重构路线

1. `Map<String, Object>` 改成强类型 `RecommendContext`。
2. 字符串 key 改成枚举和统一注册。
3. `List<ItemAttr>` 改成 `Map<AttrName, ItemAttr>`。
4. 大服务拆成多个 `Operator`。
5. 增加一个简单 DAG 执行器。
6. 将模拟 Groovy 脚本逻辑迁移成标准算子。
7. 在线特征和混排做并行优化，复刻“大约 120ms 收益”的关键路径优化。
8. 补齐更完整的日志、指标、报警和单测。

## 当前进度

- V1：完成可运行的推荐接入层链路。
- V2：已将运行态大 Map 重构为强类型 `RecommendContext`。
- V3：已将 item attr 从 `List<ItemAttr>` 重构为 `Map<AttrName, ItemAttr>`。
- V4：已拆分阶段算子，并新增 `OperatorExecutor` 执行框架。
- V5：已引入 DAG 图模型和 `DagOperatorExecutor` 拓扑执行器。
- V6：已引入 `ParallelDagOperatorExecutor`，并将 `onlineFeature` 与 `mixRank` 并行执行。
- V7：已新增结构化日志、算子级指标、请求级指标、基础告警和 `/metrics`、`/alerts` 端点。

V2 学习文档见：

```text
docs/03-v2-context-refactor.md
```

V3 学习文档见：

```text
docs/04-v3-item-attr-map.md
```

V4 学习文档见：

```text
docs/05-v4-operator-framework.md
```

V5 学习文档见：

```text
docs/06-v5-dag-executor.md
```

V6 学习文档见：

```text
docs/07-v6-parallel-dag.md
```

V7 学习文档见：

```text
docs/08-v7-observability.md
```

## V8：U 分层动态降级

V8 新增了 `DegradationManager`、`DegradationOperator` 和 `/degradation` 端点，用来模拟大促/高峰期的动态降级能力。

核心效果：

- `LIGHT`：对 bucket 80-99 的用户跳过 `ad` 召回，返回上限最多 8 个。
- `HEAVY`：对 bucket 50-99 的用户跳过 `ad`、`live` 召回，返回上限最多 6 个。
- 降级决策会写入 `RecommendContext`，并出现在 `/recommend` 返回的 `debug.degradation` 中。

学习文档：

```text
docs/09-v8-degradation.md
```

运行时查看/切换：

```powershell
Invoke-RestMethod "http://localhost:8080/degradation"
Invoke-RestMethod "http://localhost:8080/degradation?level=HEAVY"
Invoke-RestMethod "http://localhost:8080/recommend?userId=185&scene=mall&limit=10"
```

## V9：降级压测与效果对比

V9 新增了可重复运行的本地 benchmark，对比 `NONE`、`LIGHT`、`HEAVY` 三种状态下的平均耗时、P50、P95、召回量、返回量和降级命中率。

一条命令完成单测、打包和实验：

```powershell
.\scripts\run-degradation-benchmark.ps1
```

实验结果会写入：

```text
target/degradation-benchmark.csv
```

学习文档：

```text
docs/10-v9-degradation-benchmark.md
```

## V10：下游稳定性治理

V10 为商品、直播和广告召回统一增加：

- 80ms 单次超时；
- 最多一次有限重试；
- CLOSED、OPEN、HALF_OPEN 三态熔断器；
- 每个召回源独立的有界线程池；
- 弱依赖失败后的空结果兜底；
- 故障注入、指标和告警。

一条命令观察直播召回从超时重试到熔断：

```powershell
.\scripts\run-resilience-demo.ps1
```

学习文档：

```text
docs/11-v10-resilience.md
```

## V11：多路并行召回

V11 将 goods、live、ad 从顺序调用改为真正的 fan-out/fan-in 并行召回：

- 使用完成队列收集先返回的来源；
- 召回阶段设置 120ms 统一截止时间；
- 超时后保留已完成的部分结果；
- 最终按固定来源顺序拼接，保证确定性；
- 补充来源耗时、整体耗时、部分结果指标和告警。

一条命令观察健康并行和直播超时两种场景：

```powershell
.\scripts\run-parallel-recall-demo.ps1
```

学习文档：

```text
docs/12-v11-parallel-recall.md
```

## V12：影子双跑、结果 Diff 与灰度发布

V12 保留 legacy 顺序召回和 new 并行召回两条链路，新增：

- 基于用户 bucket 的稳定灰度路由；
- 不阻塞用户响应的异步 shadow 双跑；
- item 顺序、分数、召回量、重合率和耗时 Diff；
- 0%～100% 动态发布与一键回滚；
- mismatch 指标、最近差异记录和告警。

一条命令演示影子打平、5% 灰度和 100% 全量：

```powershell
.\scripts\run-rollout-demo.ps1
```

学习文档：

```text
docs/13-v12-shadow-rollout.md
```

## V13：真实 Protobuf 与统一内部 Item

V13 增加商品、直播、广告三套真实下游协议，并通过 Adapter 收敛为接入层统一 `InternalItemPb`：

- Maven 自动调用 `protoc` 生成类型安全的 Java 类；
- 不同 ID、分数和属性语义在各自防腐层内完成转换；
- repeated 属性列表转为 map，重复 key 使用最后值；
- 内部 PB 再转换为领域 `Item`，核心算子不依赖外部协议；
- 新增 `/recommend-pb` 二进制接口和上游 PB；
- 覆盖未知字段、协议演进和二进制 round-trip 测试；
- 使用 fat JAR 确保 Protobuf 运行依赖可直接部署。

一条命令完成代码生成、单测、打包、启动、请求和解码：

```powershell
.\scripts\run-protobuf-demo.ps1
```

学习文档：

```text
docs/14-v13-protobuf-adapters.md
```

## V14：真实 gRPC 多进程召回

V14 将 goods、live、ad 运行成三个独立 gRPC 服务进程，推荐接入层作为第四个进程通过真实 TCP/HTTP2 调用：

- `.proto` 同时生成消息类、service 和三种 stub；
- 三路 blocking RPC 由 fan-out 工作线程并行执行；
- 每个下游长期复用一个 ManagedChannel；
- 70ms gRPC deadline 与 80ms 外层 timeout 分层保护；
- requestId 通过 metadata 和 server Context 跨进程透传；
- gRPC status 复用已有重试、熔断和兜底；
- local/grpc 两种 transport 可配置切换；
- 修复 fat JAR 中 gRPC SPI 服务文件合并问题；
- 真实验证 live 进程宕机后的 17 条部分结果及重启恢复。

一条命令启动四个 JVM、制造故障并验证恢复：

```powershell
.\scripts\run-grpc-multiprocess-demo.ps1
```

学习文档：

```text
docs/15-v14-grpc-multiprocess.md
```

## V15：Docker Compose、服务治理与分布式链路追踪

V15 将网关、三路召回和 Trace Collector 容器化并统一编排：

- Compose DNS 服务发现和一键部署；
- 标准 gRPC Health Checking 与 Server Reflection；
- HTTP、线程池和 gRPC Metadata 中的 OpenTelemetry Context 传播；
- OTLP/gRPC 异步导出和按 TraceId 查询；
- 非 root、只读根文件系统、资源限制与健康依赖；
- 真实停止 live 容器，验证 25 → 17 → 25 的降级和恢复过程。

一条命令完成测试、构建、部署、追踪、故障注入和恢复验收：

```powershell
.\scripts\run-docker-compose-demo.ps1
```

保留容器用于手动学习：

```powershell
.\scripts\run-docker-compose-demo.ps1 -KeepRunning
```

学习文档：

```text
docs/16-v15-docker-otel.md
```

## V16：Prometheus、Grafana 与可执行告警

V16 在 V15 的日志和 Trace 基础上补齐真实指标监控闭环：

- `/metrics/prometheus` 标准文本暴露端点；
- Counter 与毫秒耗时 Histogram，支持平均值和 P95；
- 网关及三路召回共四个 Prometheus 抓取目标；
- 三条 Prometheus 告警规则及 Pending/Firing/Resolved 状态；
- 自动预置 Prometheus 数据源和 7 面板 Grafana Dashboard；
- `monitoring` Compose profile，不影响默认 V15 五容器演示；
- 自动停止 live，验证 `up=0`、告警触发、17 条兜底及恢复。

一条命令完成测试、七容器部署、流量生成、PromQL 查询、Dashboard 验证和告警实验：

```powershell
.\scripts\run-monitoring-demo.ps1
```

保留监控环境用于手动学习：

```powershell
.\scripts\run-monitoring-demo.ps1 -KeepRunning
```

Grafana：`http://localhost:13000/d/mini-reco-overview`

Prometheus：`http://localhost:19090`

学习文档：

```text
docs/17-v16-prometheus-grafana.md
```
