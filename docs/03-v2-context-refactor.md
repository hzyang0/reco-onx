# V2：从 Big Map 到强类型 RecommendContext

这一版对应快手项目里的一个核心重构点：

```text
重构前：一次请求中的所有数据都放在一个 Map<String, Object> 里。
重构后：按来源和用途收敛到专门的数据结构中。
```

在本项目里，V1 的主链路是：

```java
Map<String, Object> context = new HashMap<>();
context.put("user_feature", userFeature);
context.put("ab_params", abParams);
context.put("address", address);
```

召回和混排服务再这样读取：

```java
UserFeature feature = (UserFeature) context.get("user_feature");
```

这个写法能跑，但有三个问题。

## 1. 字符串 key 容易写错

比如写入时是：

```java
context.put("user_feature", userFeature);
```

读取时如果写成：

```java
context.get("userFeature");
```

编译器不会报错，只有运行时才发现拿不到数据。

这类问题在大厂核心链路里风险很高，因为线上流量大，问题影响面会被放大。

## 2. Object 需要强制类型转换

V1 里读取用户特征要写：

```java
UserFeature feature = (UserFeature) context.get("user_feature");
```

如果某个 key 里实际放的是 AB 参数，但代码强转成 `UserFeature`，编译期也不一定能发现，运行时才会抛异常。

这就是“类型不安全”。

## 3. 所有逻辑都耦合到同一个 Map

所有算子、服务都知道这个大 Map 的 key。

这会导致：

- 新增字段时不知道影响哪些地方。
- 删除字段时不知道谁还在用。
- key 散落在很多文件里。
- 代码 review 很难判断数据流是否正确。

## V2 怎么改

V2 新增了：

```text
src/main/java/com/interview/minireco/service/context/RecommendContext.java
```

现在请求上下文变成强类型字段：

```java
private UserFeature userFeature;
private Map<String, String> abParams;
private Address address;
private List<Item> recalledItems;
private List<Item> filteredItems;
```

读取时变成：

```java
UserFeature feature = context.getUserFeature();
Map<String, String> abParams = context.getAbParams();
```

这样有几个直接收益：

- 没有 `"user_feature"` 这种硬编码 key。
- 不需要 `(UserFeature)` 强制类型转换。
- IDE 可以跳转字段和方法。
- 编译器可以检查类型。
- 数据流更清楚，后续拆算子更容易。

## 这和真实大厂项目的关系

真实项目里不一定只有一个 `RecommendContext`，通常会继续按来源和用途拆分：

```text
RequestContext       上游请求参数
UserContext          用户特征、画像、地址
AbContext            AB 实验参数
FeatureContext       多来源特征
OperatorResultStore  算子中间结果
ItemContext          召回 item 和 item 特征
```

本项目 V2 先做第一步：把最危险、最混乱的大 Map 收敛成一个强类型上下文。

后续 V3 可以继续把 `RecommendContext` 再拆细。

## 面试表达

可以这样说：

> 早期系统为了开发方便，把一次请求里的上游参数、AB 参数、用户特征、地址、召回结果和算子中间结果都放在一个 `Map<String, Object>` 里。这样虽然灵活，但会带来字符串 key 硬编码、类型不安全和数据耦合问题。重构时我们将这类运行态数据收敛到强类型 Context 中，通过明确字段和 getter/setter 替代散落的 map key，减少运行时错误，也为后续算子化和 DAG 化改造打基础。

这句话比单纯说“我优化了数据结构”更具体。
