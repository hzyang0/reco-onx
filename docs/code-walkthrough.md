# Mini Reco 代码导读

这份导读的目标是让第一次接触项目的人，沿着一条真实请求看懂核心代码；不需要先读完全部文件。

## 先建立全局认识

Mini Reco 是一个 Java 17 编写的推荐请求编排服务。它接收 `userId`、`scene` 和 `limit`，把一次推荐请求拆成准备、三路召回、在线特征、混排、过滤、后处理等小步骤，再以 DAG（有向无环图）决定它们的执行顺序和可并行关系。

```text
浏览器控制台或调用方
        |
        v
HTTP /recommend
        |
        v
RecommendHttpHandler -> RecommendService -> ParallelDagOperatorExecutor
                                             |
                    Prepare -> Recall -> +-- OnlineFeature --+
                                        +-- MixRank --------+--> Filter -> PostProcess
```

其中还有一层更细的并行：`Recall` 内部会同时调用 goods、live、ad 三个召回源。两个层次的并行解决的是不同问题：前者缩短整条链路的关键路径，后者缩短等待多个下游服务的时间。

## 15 分钟跑通

在项目根目录执行：

```powershell
mvn clean test
mvn -DskipTests package
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

浏览器访问 `http://localhost:8080/`。控制台会调用三个 JSON 接口：

- `GET /recommend?userId=123&scene=mall&limit=5`：推荐结果和调试信息；
- `GET /health`：服务存活状态；
- `GET /metrics`：内存指标快照。

在控制台里把场景切换为 `double_column`、数量调为 8，再点击“运行推荐链路”。观察 `debug.stageCostMs`：`onlineFeature` 与 `mixRank` 的耗时可以重叠，而不是相加。

## 推荐阅读路线

### 第一轮：只跟一条请求（40 分钟）

1. `MiniRecoApplication.java`：应用入口、HTTP 路由和线程池；
2. `http/RecommendHttpHandler.java`：查询参数如何变成 `RecommendRequest`，异常如何变成 400；
3. `domain/RecommendRequest.java`、`RecommendResponse.java`：输入和输出的边界；
4. `service/RecommendService.java`：创建每请求独享的 `RecommendContext`、执行 DAG、记录请求指标；
5. `service/DemoWiring.java`：把算子和依赖关系装配成 DAG。

完成这一轮后，应能回答：一次 `/recommend` 请求从哪里进来，在哪里结束，返回的 `debug` 又从哪里产生。

### 第二轮：看懂 DAG 执行（50 分钟）

1. `service/operator/Operator.java`：每个阶段统一成 `name + execute(context)`；
2. `service/operator/graph/DagNode.java`、`DagGraph.java`：节点和依赖的建模与校验；
3. `service/operator/graph/ParallelDagOperatorExecutor.java`：剩余依赖计数、就绪队列、完成队列、失败取消；
4. `service/context/RecommendContext.java`：阶段之间如何通过强类型字段传递数据。

执行器不是按照文件顺序运行，而是遵循依赖：`prepare` 完成后 `recall` 才能开始；`recall` 完成后 `onlineFeature` 和 `mixRank` 都就绪，可以同时提交；`filter` 必须等待两者完成。

### 第三轮：看懂业务算子（60 分钟）

| 算子 | 文件 | 读什么 | 写什么 | 职责 |
| --- | --- | --- | --- | --- |
| Prepare | `PrepareOperator` | 请求参数 | 用户特征、AB 参数、地址 | 校验场景并准备上下文 |
| Recall | `RecallOperator`、`ParallelRecallFanout` | 上下文 | `recalledItems`、召回调试信息 | 并行获取候选 Item |
| OnlineFeature | `OnlineFeatureOperator` | 召回 Item | Item 的价格、库存、状态 | 补实时属性 |
| MixRank | `MixRankOperator` | 召回 Item | `rankedItems` | 规则混排 |
| Filter | `FilterOperator` | 已排序或召回 Item | `filteredItems` | 去掉无库存、非 ONLINE 的 Item |
| PostProcess | `PostProcessOperator` | 过滤结果 | `finalItems` | 截断并在不足时补兜底 Item |

注意这个实现的一个刻意选择：`OnlineFeature` 与 `MixRank` 并行。混排只读取候选的基础分数和用户上下文；过滤在它们都结束后才读取库存、状态，因此不会读取尚未补齐的在线属性。

### 第四轮：看懂召回并行与超时（45 分钟）

重点读 `ParallelRecallFanout.java` 和 `RecallOperatorTest.java`。

它为每个 `RecallService` 提交一个任务，使用 `ExecutorCompletionService` 按“谁先完成就先取谁”的方式收集结果，同时给整个 fan-out 设置同一个 deadline。到期后：

1. 已完成的来源保留结果；
2. 未完成任务调用 `Future.cancel(true)`；
3. 调试信息记录 `completedSources`、`timedOutSources`、`failedSources`；
4. 返回 `SUCCESS` 或 `PARTIAL`，而不是因为单一路慢或失败就让整个请求失败。

结果合并时仍按配置顺序 goods、live、ad 组织，避免“完成顺序不同导致返回顺序随机”。

### 第五轮：观测、测试和控制台（45 分钟）

- `MetricsRegistry.java`：线程安全地维护计数器、耗时、最大值和桶统计；
- `StructuredLogger.java`：输出 JSON 行日志，字段在日志级别关闭时通过 `Supplier` 延迟构造；
- `DashboardHttpHandler.java`：从 JAR classpath 中读取 HTML、CSS、JS 和 favicon，设置安全头；
- `resources/dashboard/dashboard.js`：调用三个 JSON API，把结果渲染到控制台；
- `RecommendServiceTest.java`：用 Mockito 替换下游服务，验证业务结果；
- `ParallelDagOperatorExecutorTest.java`：用两个 `CountDownLatch` 证明兄弟节点确实同时开始；
- `RecallOperatorTest.java`：验证并行、失败保留、总超时和中断；
- `DashboardHttpHandlerTest.java`：真实启动随机端口 HTTP Server，验证页面资源和状态码。

## 最值得掌握的五个知识点

1. **DAG 编排**：把流程依赖显式写出来，分支可并行、汇合可控制，新增阶段不会把一个大函数继续堆长。
2. **请求上下文**：`RecommendContext` 是一次请求的“工作台”。它替代松散的 `Map<String, Object>`，让数据来源、类型和读写位置更清楚。
3. **并行 fan-out 与 deadline**：多个下游不是各自无边界等待；它们共享整段预算，并允许部分结果返回。
4. **在线特征与过滤时机**：价格、库存、上下架等实时信息在召回后补齐；依赖这些字段的过滤必须在补齐后执行。
5. **可观测性与可测试性**：指标、日志、调试快照和单元测试不是最后附加的功能，而是使并发链路可验证、可定位的基础。

## 建议的三个小练习

1. 在 `PrepareOperator` 新增一个合法场景，并让控制台可选择它；
2. 新增一个 `RecallService`，验证来源名称唯一、结果合并顺序和控制台召回状态；
3. 把某个召回的模拟延迟调大于 `RECALL_FANOUT_TIMEOUT_MS`，观察接口和控制台的 `PARTIAL` 结果。

每次改动后都执行：

```powershell
mvn clean test
mvn -DskipTests package
.\scripts\run-smoke-test.ps1
```

## 当前实现边界

下游服务、特征、排序策略和指标存储都在进程内模拟；它的重点是请求编排、并发控制、接口边界和可观测性。若扩展为真实服务，可优先替换 `service/downstream` 下的本地实现，并保持上层 Operator 接口不变。
