# 测试说明

## 1. 使用的测试工具

- JUnit 5：运行测试、组织测试生命周期和断言结果；
- Mockito：创建下游接口的替身，并指定调用时的返回值或行为。

生产代码只使用 Java 17 标准库，JUnit 和 Mockito 仅在测试阶段参与构建。

## 2. 运行全部测试

```powershell
mvn clean test
```

或：

```powershell
.\scripts\run-tests.ps1
```

## 3. 关键测试

### `RecommendServiceTest`

使用 Mockito 替换用户特征、AB 参数、地址、召回、在线特征和混排服务，验证：

- 正常请求能够返回排序后的 Item；
- 在线属性能够写入结果；
- 不支持的场景会被拒绝。

### `ParallelDagOperatorExecutorTest`

使用两个 `CountDownLatch`：

1. 两个兄弟节点启动后分别递减 `bothStarted`；
2. 测试线程确认两个节点都已启动；
3. 再通过 `release` 同时释放它们。

这种方式直接验证“同时进入执行状态”，比只比较运行时间更稳定。

### `RecallOperatorTest`

覆盖：

- 一路异常时保留其他成功来源；
- 三路召回并行启动；
- 合并结果保持配置顺序；
- 到达整体 deadline 后快速返回部分结果；
- 未完成任务能够收到中断信号。

### `MetricsRegistryTest`

验证计数器、耗时统计、标签隔离和线程安全累加。

### `DashboardHttpHandlerTest`

启动随机端口的真实 `HttpServer`，验证：

- 根路径能返回控制台 HTML；
- CSS 和 JavaScript 能从 JAR 资源路径读取并返回正确类型；
- 响应包含内容安全策略等安全头；
- 未知资源返回 404，非 GET/HEAD 请求返回 405。

## 4. JUnit 与 Mockito 的分工

```java
when(userFeatureService.getUserFeature(123L))
        .thenReturn(new UserFeature(123L, false, "digital", 25));

RecommendResponse response = recommendService.recommend(request);

assertEquals(2, response.getItems().size());
```

在这个例子中：

- Mockito 的 `when(...).thenReturn(...)` 规定假服务返回什么；
- 业务代码照常调用接口，但不会访问真实下游；
- JUnit 的 `assertEquals` 判断最终结果是否符合预期。

## 5. 完整验收

```powershell
.\scripts\run-tests.ps1
mvn -DskipTests package
.\scripts\run-smoke-test.ps1
```

验收同时覆盖单元测试、编译打包、进程启动和真实 HTTP 调用。

## 6. 新增算子时

至少补充：

1. 算子自身的正常输入测试；
2. 空输入或边界输入测试；
3. 与 Context 读写字段相关的断言；
4. DAG 依赖关系测试；
5. 冒烟测试，确认完整请求仍能运行。
