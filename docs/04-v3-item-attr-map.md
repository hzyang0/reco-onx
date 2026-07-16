# V3：从 List<ItemAttr> 到 Map<AttrName, ItemAttr>

这一版对应快手项目里的另一个核心点：

```text
重构前：item attr 是 list，查一个 attr 需要遍历。
重构后：item attr 是 map，按 attr name 直接查。
```

## V1/V2 的问题

之前 `Item` 里是这样存属性的：

```java
private final List<ItemAttr> attrs = new ArrayList<>();
```

查库存时要遍历：

```java
for (ItemAttr attr : attrs) {
    if (attr.getName().equals("stock")) {
        return attr.getValue();
    }
}
```

这有两个问题。

## 1. 查询复杂度是 O(n)

如果一个 item 上有 20 个属性，要查 `stock`，最坏要比较 20 次。

单个 item 看起来不多，但推荐链路里一次请求可能有很多 item：

```text
500 个 item * 每个 item 20 个 attr = 10000 次属性比较
```

如果多个算子都要查价格、库存、状态，这个成本会被放大。

## 2. attr key 仍然是字符串

之前代码里有：

```java
item.findAttr("stock")
item.findAttr("status")
item.putAttr("recall_reason", "fallback")
```

风险和 Big Map 一样：

- 字符串写错，编译器发现不了。
- key 散落在多个文件里。
- 新增 attr 没有统一注册入口。

## V3 怎么改

V3 新增了枚举：

```text
src/main/java/com/interview/minireco/domain/AttrName.java
```

核心字段变成：

```java
private final Map<AttrName, ItemAttr> attrs = new EnumMap<>(AttrName.class);
```

现在查询库存变成：

```java
item.findAttr(AttrName.STOCK)
```

设置价格变成：

```java
item.putAttr(AttrName.PRICE, "99")
```

## 为什么用 EnumMap

`EnumMap` 是 Java 专门给 enum key 设计的 Map。

它的特点：

- key 只能是指定枚举类型，类型更安全。
- 内部实现紧凑，适合 enum key 场景。
- 通过枚举 key 查询，不需要遍历 attr list。

这就对应你项目里说的：

```text
用 map 结构替代 list 结构，以 O(1) 复杂度获取指定 attr。
```

严格说，真实工程里 HashMap/EnumMap 的 O(1) 是平均意义上的快速查找；`EnumMap` 对 enum key 更稳定、更轻量。

## 外部 JSON 为什么没变

内部从：

```text
List<ItemAttr>
```

改成：

```text
Map<AttrName, ItemAttr>
```

但返回给 HTTP 客户端的 JSON 仍然是：

```json
{
  "price": "180",
  "stock": "20",
  "status": "ONLINE",
  "recall_reason": "preferred_category"
}
```

这是工程里常见的做法：

```text
内部结构可以为性能和可维护性重构；
外部协议尽量保持兼容。
```

## 面试表达

可以这样说：

> 除了一次请求级别的大 Map，我们还治理了 item 级别的 attr list。原来每个 item 的属性是 list 结构，算子想查价格、库存、状态时都要遍历，而且 attr name 也是字符串硬编码。重构后我们把 attr name 收敛成枚举，并用 map 结构存储 item attr，使指定属性查询从遍历 list 变成按 key 直接获取。同时新增 attr 需要在枚举中注册，避免 key 散落在代码各处。

注意面试里不要只说“性能优化”，还要说“可维护性和类型安全”。
