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
