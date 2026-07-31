# 开始运行

## 1. 准备环境

确认已安装 JDK 17 和 Maven：

```powershell
java -version
mvn -version
```

## 2. 测试与打包

在项目根目录执行：

```powershell
.\scripts\run-tests.ps1
mvn -DskipTests package
```

生成文件：

```text
target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

## 3. 启动服务

```powershell
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
```

也可以指定端口：

```powershell
java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar 18080
```

## 4. 调用接口

```powershell
Invoke-RestMethod "http://localhost:8080/health"
Invoke-RestMethod "http://localhost:8080/recommend?userId=123&scene=mall&limit=5"
Invoke-RestMethod "http://localhost:8080/metrics"
```

推荐响应包含：

- `requestId`：本次请求的唯一标识；
- `userId`、`scene`：原始请求信息；
- `costMs`：整条链路耗时；
- `items`：最终结果；
- `debug`：算子耗时、召回完成情况和结果数量。

## 5. 一键冒烟测试

打包后执行：

```powershell
.\scripts\run-smoke-test.ps1
```

脚本会：

1. 在 18080 端口启动 JAR；
2. 等待健康检查成功；
3. 调用推荐和指标接口；
4. 校验返回 5 个 Item；
5. 停止测试进程。

## 6. 代码阅读顺序

不要从目录第一行开始逐个文件阅读。沿着一次请求阅读：

1. `domain/RecommendRequest.java`
2. `http/RecommendHttpHandler.java`
3. `service/RecommendService.java`
4. `service/context/RecommendContext.java`
5. `service/operator/Operator.java`
6. `service/operator/impl` 下的六个算子
7. `service/DemoWiring.java`
8. `service/operator/graph/ParallelDagOperatorExecutor.java`
9. `service/operator/impl/ParallelRecallFanout.java`
10. `domain/RecommendResponse.java`

每个阶段只需先回答四个问题：

- 输入从哪里读取？
- 做了什么处理？
- 输出写到哪里？
- 失败或超时会怎样？

## 7. 本地调试建议

在以下位置设置断点即可观察完整链路：

- `RecommendHttpHandler.handle`
- `RecommendService.recommend`
- `ParallelDagOperatorExecutor.execute`
- 六个 Operator 的 `execute`
- `ParallelRecallFanout.collectUntilDeadline`

修改 `GoodsRecallService`、`LiveRecallService` 或 `AdRecallService` 的模拟延迟，可以观察并行召回和整体截止时间的行为。
