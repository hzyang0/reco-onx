# JUnit、Mockito、Groovy 在这个项目里的位置

## 1. 你的理解基本正确

JUnit 可以理解成“测试执行器 + 结果裁判”：

1. 自己构造测试参数。
2. 调用要测试的方法或算子。
3. 用 `assertEquals`、`assertTrue`、`assertThrows` 等断言判断结果是否符合预期。

Mockito 可以理解成“外部依赖模拟器”：

1. 当前算子如果依赖 AB 服务、特征服务、召回服务，就不真的调用它们。
2. Mockito 创建一个假的服务对象。
3. 你规定这个假对象被调用时返回什么。
4. 再执行当前算子或服务。
5. 最后仍然用 JUnit 断言结果是否符合预期。

一句话：

```text
JUnit 管“测试怎么跑、结果对不对”。
Mockito 管“外部服务怎么假装返回”。
```

## 2. 本项目里的例子

看 `RecommendServiceTest`。

这里模拟了一个用户：

```java
when(userFeatureService.getUserFeature(123L))
        .thenReturn(new UserFeature(123L, false, "digital", 25));
```

意思是：

```text
当被测服务调用用户特征服务，并传入 userId=123 时，
不要真的访问外部服务，直接返回一个偏好 digital 类目的用户特征。
```

再比如：

```java
doAnswer(invocation -> {
    List<Item> items = invocation.getArgument(0);
    for (Item item : items) {
        item.putAttr("stock", "10");
        item.putAttr("status", "ONLINE");
        item.putAttr("price", "99");
    }
    return null;
}).when(onlineFeatureService).fillOnlineFeatures(anyList());
```

意思是：

```text
当被测服务调用在线特征服务时，
我们用 Mockito 假装它给每个 item 补上库存、状态和价格。
```

最后用 JUnit 判断：

```java
assertEquals(2, response.getItems().size());
```

这就是：

```text
构造输入 -> mock 外部依赖 -> 调用被测服务 -> 断言结果
```

## 3. Groovy 在后续版本怎么引入

第一版暂时不真正引入 Groovy 运行时，避免一开始依赖太多东西。

后续我们会模拟一段“图上 Groovy 脚本”：

```groovy
if (abParams["recall_exp"] == "B") {
    context.recallLimit = 500
} else {
    context.recallLimit = 200
}
```

然后把它迁移成标准 Java 算子：

```java
public class RecallLimitOperator implements Operator {
    public void execute(RecommendContext context) {
        if ("B".equals(context.getAbParams().get("recall_exp"))) {
            context.setRecallLimit(500);
        } else {
            context.setRecallLimit(200);
        }
    }
}
```

这样你能真正理解：

```text
Groovy 是灵活的图上脚本。
算子是工程化、可测试、可监控的标准节点。
```
