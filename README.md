# Mini Reco

Mini Reco 是一个轻量级 Java 推荐请求编排服务。它接收用户和场景参数，通过 DAG 组织参数准备、多路召回、在线特征、混排、过滤和后处理，并返回最终推荐结果。

项目聚焦请求编排本身。商品、直播和广告召回均使用本地可重复的模拟实现，不依赖外部数据库或推荐模型。

## 核心能力

- 使用强类型 `RecommendContext` 保存一次请求的参数、特征和中间结果；
- 将推荐流程拆分为职责独立的 `Operator`；
- 使用 DAG 表达算子依赖、分支和汇合；
- 并行执行商品、直播和广告三路召回；
- 并行执行互不依赖的在线特征与混排节点；
- 为多路召回设置整体截止时间，保留已成功返回的部分结果；
- 输出结构化日志、请求指标和算子指标；
- 提供随 JAR 打包的轻量级控制台，可直接观察推荐结果、DAG 耗时和指标；
- 提供 JUnit 5、Mockito、Maven、Docker 和 GitHub Actions 验证链路。

## 请求流程

```text
HTTP /recommend
  -> Prepare
  -> Recall
       ├─ goods
       ├─ live
       └─ ad
  -> OnlineFeature ─┐
                    ├─ Filter
  -> MixRank ───────┘
  -> PostProcess
  -> JSON response
```

`Recall` 内部使用 `ExecutorCompletionService` 并行收集三路结果。DAG 执行器根据节点依赖调度任务，因此 `OnlineFeature` 和 `MixRank` 可以同时执行，`Filter` 会等待两者完成。

## 环境要求

- JDK 17
- Maven 3.9+
- 可选：Docker 或 Docker Compose

## 快速运行

```powershell
mvn clean test
mvn -DskipTests package
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

浏览器打开 `http://localhost:8080/`，可以在内置控制台中修改用户、场景和返回数量，发起推荐请求并观察完整结果。

请求推荐结果：

```powershell
Invoke-RestMethod "http://localhost:8080/recommend?userId=123&scene=mall&limit=5"
```

查看服务状态和指标：

```powershell
Invoke-RestMethod "http://localhost:8080/health"
Invoke-RestMethod "http://localhost:8080/metrics"
```

也可以使用脚本：

```powershell
.\scripts\run-tests.ps1
mvn -DskipTests package
.\scripts\run-smoke-test.ps1
```

## HTTP API

### `GET /`

返回内置控制台页面。页面使用原生 HTML、CSS 和 JavaScript，资源随 JAR 一起打包，不需要额外安装 Node.js 或启动前端服务。

### `GET /recommend`

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `userId` | long | 必填 | 正整数用户标识 |
| `scene` | string | `mall` | 支持 `mall`、`buy_first`、`single_column`、`double_column`、`new_user_card` |
| `limit` | int | `10` | 返回数量，范围 1～50 |

### `GET /health`

返回服务状态和当前时间。

### `GET /metrics`

返回请求、算子和召回阶段的内存指标快照。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | ---: | --- |
| `RECALL_FANOUT_TIMEOUT_MS` | `120` | 多路召回整体截止时间 |
| `LOG_LEVEL` | `INFO` | 结构化日志级别 |

## Docker

```powershell
mvn -DskipTests package
docker build -t mini-reco:local .
docker run --rm -p 8080:8080 mini-reco:local
```

或使用 Compose：

```powershell
docker compose up --build
```

## 代码结构

```text
src/main/java/io/github/hzyang0/minireco
├─ domain                 请求、响应、Item 和属性模型
├─ http                   HTTP 参数解析和请求处理
├─ observability          结构化日志和内存指标
├─ service/context        单次请求上下文
├─ service/downstream     下游接口和本地实现
├─ service/operator       算子接口与配置
├─ service/operator/graph DAG 模型与并行执行器
├─ service/operator/impl  六个业务算子和并行召回
└─ util                   JSON 与延迟模拟工具

src/main/resources/dashboard
├─ index.html             控制台页面结构
├─ dashboard.css          页面样式与响应式布局
└─ dashboard.js           接口调用与结果渲染
```

## 文档

- [开始运行](docs/getting-started.md)
- [架构与请求链路](docs/architecture.md)
- [测试说明](docs/testing.md)
- [代码导读](docs/code-walkthrough.md)

## 实现边界

- 召回、特征和混排服务在进程内模拟；
- 排序逻辑为可解释规则，不包含机器学习模型；
- 指标保存在单进程内存中；
- 当前实现用于演示请求编排与并发控制，不包含持久化和分布式部署。
