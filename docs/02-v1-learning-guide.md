# 第一版学习指南

这版不要急着看所有代码，按下面顺序看。

## 1. 先看入口

文件：

```text
src/main/java/com/interview/minireco/MiniRecoApplication.java
```

你要理解：

- 服务监听 8080 端口。
- `/health` 是健康检查。
- `/recommend` 是推荐接口。
- `DemoWiring.createRecommendService()` 负责组装依赖。

## 2. 再看 HTTP 层

文件：

```text
src/main/java/com/interview/minireco/http/RecommendHttpHandler.java
```

你要理解：

- 从 URL 里解析 `userId`、`scene`、`limit`。
- 构造 `RecommendRequest`。
- 调用 `RecommendService.recommend()`。
- 把结果转成 JSON 返回。

## 3. 重点看 RecommendService

文件：

```text
src/main/java/com/interview/minireco/service/RecommendService.java
```

这是第一版最重要的文件。

你要把它对应到快手项目：

```text
prepare        -> 准备阶段
recall         -> 召回阶段
onlineFeature  -> 取在线特征
filter         -> 过滤
mixRank        -> 混排
postProcess    -> 后处理和兜底
```

## 4. 注意第一版的“坏味道”

这些地方后面都要优化：

- `Map<String, Object> context`
- `"user_feature"`、`"ab_params"` 这类字符串 key
- `(UserFeature) context.get("user_feature")` 这种强制类型转换
- `item.findAttr("stock")` 内部需要遍历属性列表
- 所有流程都在 `RecommendService` 里
- 在线特征和混排串行执行

这正好对应简历里的重构点。

## 5. 单测怎么看

文件：

```text
src/test/java/com/interview/minireco/service/RecommendServiceTest.java
```

你要理解：

- `@Test` 是 JUnit 的测试方法。
- `@Mock` 是 Mockito 创建假对象。
- `when(...).thenReturn(...)` 是规定假对象返回值。
- `assertEquals(...)` 是判断结果是否符合预期。

## 6. 下一步优化目标

下一版建议做：

```text
V2：把 Big Map 改成强类型 RecommendContext
```

这样你会非常直观地理解：

```text
为什么大厂项目里要重构数据结构？
为什么 string key hard code 会出事故？
为什么类型安全和可维护性很重要？
```
