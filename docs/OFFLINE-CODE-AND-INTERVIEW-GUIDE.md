# reco-onx 离线精读：约束决策、Agent 工程与面试追问

版本依据：`8059134`，整理日期：2026-11-11。

本文面向无法在本机运行项目的读者，不含环境搭建、启动或部署指令。重要实现直接摘录当前源码，并在代码后解释执行过程、设计依据、边界及面试追问。可以只阅读本文件完成主线学习；源码位置用于之后核对。

阅读目标是形成可解释的项目知识，不能保证覆盖所有面试题；尤其不要把文档中的扩展方案说成已经实现。

- [1. 先分清三条链路，避免讲错整个项目](#chapter-1)
- [2. 架构总图：界面、编排、工具和事实来源](#chapter-2)
- [3. 一条请求的纸上执行：为什么标价便宜反而更贵](#chapter-3)
- [4. 输入模型：把模糊话变成可验证字段](#chapter-4)
- [5. 文本解析：中文金额、时间与完整商品名](#chapter-5)
- [6. 约束合并：省略、清除、临时修改和长期偏好](#chapter-6)
- [7. 饮食与过敏：为什么必须持续阻止推荐](#chapter-7)
- [8. LangGraph 到底做了什么，为什么当前不并行](#chapter-8)
- [9. 工具层：真实 SQL、候选上限与责任边界](#chapter-9)
- [10. 报价计算：完整金额、边界和可解释性](#chapter-10)
- [11. 完整匹配、同类替代、硬过滤与排序](#chapter-11)
- [12. 事务与幂等：请求为什么不能重复提交结果](#chapter-12)
- [13. 一次提交到底保存哪些内容，为什么必须原子](#chapter-13)
- [14. 故障恢复：能恢复什么，不能恢复什么](#chapter-14)
- [15. 异步与线程：FastAPI 为什么仍要 to_thread](#chapter-15)
- [16. 电脑端表单、弱网重试与多标签页隔离](#chapter-16)
- [17. 可选大模型和历史路径：如何回答 LLM 追问](#chapter-17)
- [18. Java 推荐 DAG：依赖、并行、超时与降级](#chapter-18)
- [19. 评测：不要让解析器给自己判满分](#chapter-19)
- [20. 数据库、故障与规模化的深问清单](#chapter-20)
- [21. 面试开场与追问链：从业务到代码](#chapter-21)
- [22. 离线练习与掌握标准](#chapter-22)


<a id="chapter-1"></a>

## 1. 先分清三条链路，避免讲错整个项目

reco-onx 当前面向校园合成本地生活数据：用户提出区域、预算、商品、数量、送达时限，系统调用商家、商品、配送报价工具，严格过滤，再按完整到手价比较。用户可以多轮改条件，也可以在电脑页面直接编辑识别后的结构化字段。

仓库不是只有一条路径：

| 路径 | 实际职责 | 是否调用大模型 |
| --- | --- | --- |
| Python 本地生活主路径 | 约束解析、SQL 工具、完整到手价、会话快照、任务幂等恢复 | 当前不调用 |
| Python 历史推荐／问答路径 | goods/live/ad 推荐的编排、通用项目问答、可选模型抽取 | 配置和密钥存在时可调用，否则规则／降级 |
| Java 推荐 DAG | 用户特征、并行召回、排序、过滤与场景混排，另有历史 Java Agent 代码 | DAG 本身不是大模型 |

**面试主线优先讲第一条。**Java DAG 用于补充推荐工程和并发编排；不要把三条链路画成“每次咖啡询价都经过 Java 三路召回”。本地生活的商家／商品／配送工具目前直接查询 MySQL，Java 服务承担迁移及保留推荐接口等职责。

**一分钟介绍：**“我做了一个受约束的本地生活推荐服务。重点是把用户需求变为明确约束，用工具获得价格和配送证据，由确定性代码计算到手价和执行硬过滤。多轮条件保存在会话快照，长期偏好需要明确授权；任务、快照和对话在 MySQL 事务中原子保存，支持原键重试及已提交结果恢复。当前目录是合成校园数据，主路径是 LangGraph 工作流加规则解析，没有真实下单。”

这是一项 Agent 工程演示，不是经过任意语言验证的自治采购系统。理解和边界说清楚比把“Agent”叫得更大重要。


<a id="chapter-2"></a>

## 2. 架构总图：界面、编排、工具和事实来源

```text
电脑页面 index.html
  ├─ WebSocket：token + 消息 + request_id + 可选 constraints
  └─ HTTP：历史、偏好、任务和演示身份
        ↓
main.py：请求模型 / 路由 / asyncio.to_thread
        ↓
local_life_store.execute_local：事务 / 锁 / 幂等 / 快照 / 审计
        ↓
local_life：约束合并 + LangGraph
  merchants → products → quotes → decide
        ↓
LocalTools：参数化 SQL → MySQL 合成目录和规则

MySQL：约束快照、任务结果、对话、长期偏好的持久化依据
Redis：演示登录与部分历史路径短期上下文；不是本地生活快照唯一来源
```

先读 `local_life.py`，理解输入模型和业务决策；再读 `local_language.py`，理解文本如何变字段；再读 `local_life_store.py`，理解锁和原子保存；最后读 `main.py` 与页面，理解请求如何进入、如何显示和重试。

LangGraph 负责节点依赖与 state 传递，数据库负责跨进程事实；两者不互相替代。`workflow.compile()` 不自动让每个任务持久化，也不自动保证工具幂等。

所谓“三个独立工具”是职责接口独立，共用 MySQL；不是三个分别部署的供应商微服务。查商家后才知道查谁的商品，再为候选报价，所以当前图是有依赖的串行链。


### 阅读 Python 代码前的最小基础

- `BaseModel` 是校验模型；实例化时检查类型、范围和未知字段，`model_dump()` 转成字典。
- `dict | dict` 右侧键覆盖左侧；这直接决定多轮条件的优先级。
- 字典没某个键和该键为 `None` 不相同：前者通常沿用，后者可能明确清除限制。
- `TypedDict` 描述 state 字典预期结构，主要帮助静态理解，不等于每次运行自动严格验证所有节点结果。
- `async def` 表示协程；`await` 在等待可异步操作时让出事件循环，不让同步数据库代码自动非阻塞。
- `with connect()` 管理连接生命周期，不应假定所有库的 with 都会自动提交；本项目明确调用 conn.begin/commit/rollback。
- `cur.execute(SQL, params)` 发送参数化 SQL；`fetchone/fetchall` 获取结果。它们不是 LLM 工具的魔法语法，而是普通 Python 函数封装。
- `state["tools"]` 是本轮事务 cursor 的工具对象，`state["now"]` 固定本轮报价的参考时间；不要把这些运行对象直接当持久化任务 JSON。

离线读函数时先问：依赖哪个 state 字段、返回更新哪些字段、是否读写数据库、异常由谁处理。理解这些就能跟完一条请求，不需要在本机启动服务。

<a id="chapter-3"></a>

## 3. 一条请求的纸上执行：为什么标价便宜反而更贵

用户说：“北区咖啡预算20元，30分钟内送达。”初始约束为空，解析为 north、coffee、2000 分、30 分钟、quantity=1。

合成目录中两款美式：

| 候选 | 商品 | 包装 | 配送 | 优惠 | 到手 | 时效 |
| --- | --- | --- | --- | --- | --- | --- |
| 经典美式 101 | 800 | 100 | 800 | 0 | 1700 分 | 18 分钟 |
| 便利店美式 201 | 1200 | 0 | 200 | 0 | 1400 分 | 25 分钟 |

先检查库存、商家、区域、起送、报价有效性，再检查到手预算和时限。两款都满足，按 1400、1700 排，201 优先。不是因为它的商品标价更低。

下一轮“20分钟内送达”：保留北区、咖啡、20 元，时限改 20；201 被过滤，101 17 元成为匹配。

再说“只有十六块九”：预算改 1690 分，101 超 10 分，201 又超时，没有匹配。系统可以问是否放宽预算或时限，但不能擅自返回 17 元。

再说“多久送都可以”：时限显式清空，保留 1690 分预算，201 又可返回。这个四轮故事同时考察解析、状态合并、完整价格与硬约束，比只背节点名字有用。


<a id="chapter-4"></a>

## 4. 输入模型：把模糊话变成可验证字段

模型是各入口共享的契约，不是展示字段列表。预算单位分，时间单位分钟，数量是同一 SKU 份数，而不是总购物车商品数。


源码：`agent-service/app/local_life.py`，起始行 13（连续原文节选，未改写）。

```python
class LocalConstraints(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    zone: Literal["north", "south", "east"] | None = None
    category: Literal["coffee", "meal", "grocery"] | None = None
    product_query: str | None = Field(default=None, min_length=1, max_length=40)
    product_match_mode: Literal["exact", "prefer"] = "prefer"
    max_total_cents: StrictInt | None = Field(default=None, ge=0, le=1_000_000)
    max_delivery_minutes: StrictInt | None = Field(default=None, ge=1, le=180)
    quantity: StrictInt = Field(default=1, ge=1, le=10)
```


逐行解释：

- `extra="forbid"`：未知字段拒绝，防止客户端传了 lactose_free 却被无声忽略。
- `Literal`：区域和品类限定为已建模范围；north 不是经纬度坐标，不能答成真实地图配送。
- `StrictInt`：预算、时间和数量必须严格整数，避免 True 被当成 1 等宽松类型转换。
- `max_total_cents=None`：无限预算；`0` 是零预算，两者不能混淆。
- `max_delivery_minutes=None`：无时限；值为 0 不合法。
- `product_query`：保留完整标题词；`product_match_mode`：exact 严格，prefer 完整优先并单列待确认替代。
- 数量 1～10；当前只比较单 SKU，未实现跨店组合和购物车最优解。

Python 的 `dict | dict` 是右侧覆盖左侧。Pydantic 的校验不会知道“燕麦是否零乳糖”，只保证字段形状、枚举和范围；食品证据需要另外建模。

**追问：结构化输入是不是绕过校验？**不是。HTTP 请求模型和 merge 后最终模型都校验；结构化字段只是优先级更高，不拥有绕过类型和范围的权力。


<a id="chapter-5"></a>

## 5. 文本解析：中文金额、时间与完整商品名

当前自然语言识别是有限中文规则。它可以处理已支持的口语表达，不是大模型语义理解。看解析代码时要区分“识别到了什么”和“没有证据但被默默忽略了什么”。

中文金额用 Decimal 转整数分，避免从浮点 16.9 得到不稳定的边界。十六块九是 16.90 元；十八元五分是 18.05 元；一百二存在 102/120 歧义时澄清，不猜成 102。

时间词先转换为分钟：一刻钟=15，半小时=30，一个钟头=60。“20分钟后送到”更像指定延后时刻，不等于“20分钟内”；当前对预约配送时间澄清。清除时间限制要产生显式 None，而不是不输出字段。


源码：`agent-service/app/local_language.py`，起始行 65（连续原文节选，未改写）。

```python
def number(text):
    text = text.replace("两", "二").replace("俩", "二").replace("〇", "零").replace("负", "-")
    try:
        return Decimal(text)
    except Exception:
        pass
    if text.startswith("-"):
        return -number(text[1:])
    if "点" in text:
        left, right = text.split("点", 1)
        digits = "零一二三四五六七八九"
        if not right or any(c not in digits for c in right):
            raise ValueError("invalid decimal")
        return number(left) + Decimal("0." + "".join(str(digits.index(c)) for c in right))
    sign = -1 if text.startswith("-") else 1
    text = text.lstrip("-")
    digits, units = "零一二三四五六七八九", {"十": 10, "百": 100, "千": 1000, "万": 10000}
    # Reject colloquial missing units (一百二), repeated units (十十), and
    # adjacent non-zero Chinese digits (一二). Guessing can change a hard budget.
    if re.search(r"[一二三四五六七八九]{2}|[百千万][一二三四五六七八九]$", text):
        raise ValueError("ambiguous Chinese number; use explicit units or digits")
    unit_values = [units[c] for c in text if c in units]
    if any(a <= b for a, b in zip(unit_values, unit_values[1:])):
        raise ValueError("invalid unit order")
    total = current = 0
    if not text:
        raise ValueError("empty number")
    for char in text:
        if char in digits:
            current = digits.index(char)
        elif char in units:
            total += (current or 1) * units[char]
            current = 0
        else:
            raise ValueError("invalid number")
    return Decimal(sign * (total + current))
```


先尝试 Decimal 处理数字文本；带中文“点”时，整数部分递归解析，小数部分逐位转换；有百千万单位时累计，并拒绝已知歧义／非法顺序。这段代码仍是受限数字语法，不要说支持所有中文财务大写和口语省略。下游范围校验还会拒绝负预算等业务无效值。

完整商品名曾有实测问题：把燕麦拿铁只存为拿铁，下一轮改预算后就丢了“燕麦”。当前按较长目录词优先、避免重叠命中，并对明确点名／引号内的咖啡名保留全词。


源码：`agent-service/app/local_language.py`，起始行 143（连续原文节选，未改写）。

```python
    # Preserve an explicitly named coffee SKU even when it is outside the seed
    # dictionary. Do not silently downgrade a named flavour to its family.
    named = re.search(r'[“「"]([^”」"]{1,40})[”」"]', text)
    if not named:
        named = re.search(r'(?:只要|只找|只喝|想喝|来一杯)([^，,。；;\s]{1,20}?(?:拿铁|美式|冷萃))', text)
    if named and any(family in named[1] for family in ('拿铁', '美式', '冷萃')):
        query = named[1]
        patch['category'] = 'coffee'
    if query:
        patch['product_query'] = query
        if re.search(r"只要|只找|只喝|只买|只吃|仅限|必须|别给我别的|不能替换|不要其他", text):
            patch['product_match_mode'] = 'exact'
        elif re.search(r'优先|首选|其他.*也行|可以替换', text):
            patch['product_match_mode'] = 'prefer'
```


这里保留名字并不表示目录存在该商品，也不表示有成分证明。北区“只要焦糖拿铁”可以正确保留 query，但目录没收录，结果应 no_match。不能退化成普通拿铁让任务看似成功。

代码对引号和点名的支持仍有限，任意品牌、超长复合要求、否定范围都需要新测试。更可靠的产品补救是让用户在可编辑面板确认完整名称，而不是继续无限追加关键词。


<a id="chapter-6"></a>

## 6. 约束合并：省略、清除、临时修改和长期偏好

四层优先级是：长期偏好 < 当前会话 < 本轮文本 < 本轮结构化参数。用户本轮输入比旧记忆更新，但“这次”不能改永久偏好。

省略字段和清除字段必须分开：patch 没有 max_delivery_minutes 代表沿用；有这个键且值 None 代表取消时限。`exclude_unset=True` 用于保留这种差别，不能先用默认 None 填满所有字段再合并。


源码：`agent-service/app/local_life.py`，起始行 52（连续原文节选，未改写）。

```python
def merge_constraints(message, previous, preferences, structured=None):
    patch, issues = parse_constraints(message)
    if structured is not None:
        patch.update(structured)
    old = dict(preferences) | dict(previous)
    if "product_query" in patch and patch["product_query"] != old.get("product_query") and "product_match_mode" not in patch:
        patch["product_match_mode"] = "prefer"
    if "category" in patch and patch["category"] != old.get("category") and "product_query" not in patch:
        patch.update(product_query=None, product_match_mode="prefer")
    values = dict(preferences) | dict(previous) | patch
    try:
        constraints = LocalConstraints(**values).model_dump()
    except ValidationError:
        return LocalConstraints(**(dict(preferences) | dict(previous))).model_dump(), {}, ["条件格式或数值范围无效"]
    sources = {key: "long_term" for key in preferences}
    sources.update({key: "session" for key in previous})
    sources.update({key: "current" for key in patch})
    return constraints, sources, issues
```


按步骤拆解：

1. `parse_constraints` 输出 patch 和 issues，不直接覆盖持久状态。
2. structured 覆盖 patch，用户表单确认优先；文本已有问题也不会因为传表单就无条件抹掉所有 issues。
3. 对照旧状态，换商品且没有显式匹配方式时回到 prefer，避免残留上次另一个商品的 exact。
4. 真正换品类且没提供新商品时清空旧 query；同品类只改数量则保留完整商品。
5. 三个 dict 叠加后再次校验；不合法时返回旧有效状态与澄清，不把坏 patch 写进业务快照。
6. sources 记录每个字段来自 long_term / session / current，用于解释和明确记忆写入。

例：长期偏好东区，当前会话北区，用户说“改成两杯”，最终仍北区；新会话再按长期偏好回东区。若用户明确说“记住以后北区”，才改长期偏好。

**容易忽略：**如果没有 issues 但缺少区域／品类，流程仍可能保存已识别的部分条件，再继续澄清。不是所有 clarification 都丢弃本轮所有字段；有非法问题与只是缺少必要字段是不同情况。


<a id="chapter-7"></a>

## 7. 饮食与过敏：为什么必须持续阻止推荐

当前商品表没有乳糖、糖分、麸质或过敏原依据。“燕麦拿铁”不能证明零乳糖，标题不是食品安全证据。用户说“必须零乳糖”，即使预算、时限都满足，也必须澄清而非推荐。

未解决要求写到 `pending_requirements`，属于会话业务状态。下一轮只说“继续”不能把它忘掉；读取旧成功任务也必须被阻止，否则用户可以通过重放绕过新约束。

撤销必须按范围：同时有“必须零乳糖”“坚果过敏”，取消前者不能把过敏一起清掉；“不要取消”不能被识别成取消；一个复合句没有足够证据拆开时宁可保留。


源码：`agent-service/app/local_language.py`，起始行 33（连续原文节选，未改写）。

```python
def retain_requirements(pending, message):
    """Remove only explicitly named requirements; ambiguous retractions fail closed.

    A compound stored clause is retained unless all its topics are retracted.
    This deliberately asks for a clearer withdrawal instead of losing an allergy.
    """
    remaining = list(pending)
    for clause in re.split(r"[，,。；;]|但是|但|不过", message):
        if not withdraws_requirements(clause):
            continue
        if re.search(r"(?:所有|全部).*(?:饮食|要求|条件)|(?:饮食|要求|条件).*(?:所有|全部)", clause):
            remaining = []
            continue
        named = {topic for topic in _REQUIREMENT_TOPICS if topic in clause}
        if named:
            remaining = [item for item in remaining
                         if not (topics := {t for t in _REQUIREMENT_TOPICS if t in item})
                         or not topics.issubset(named)]
        elif len(remaining) == 1 and re.search(r"刚才|上条|上一条|那条", clause):
            remaining = []
    return remaining
```


`remaining` 从旧 pending 开始；先判断是不是肯定的撤销表达；“所有／全部饮食要求”明确全清；否则提取主题，只删除主题被完整覆盖的旧条目。复合条目包含乳糖与过敏时，仅说乳糖不会满足 topics.issubset(named)，因此继续保留。

这个主题词匹配同样不是通用语义逻辑。它的设计是保守地保留未明确撤销的条件，而不是声称已经完美理解全部否定。新会话和过期处理仍受 TTL 边界约束，不是永久保存个人过敏档案。


源码：`agent-service/app/local_life_store.py`，起始行 63（连续原文节选，未改写）。

```python
def guard_pending(result, pending):
    if not pending:
        return result
    return result | {"status": "clarification", "originalStatus": result["status"], "items": [],
                     "alternativeItems": [], "suggestions": [], "pendingRequirements": pending,
                     "answer": "仍有未解决的要求：" + "；".join(pending) + "。数据不足以确认，不能推荐；请明确撤回这些要求或结束查询。"}
```


重放也走 guard：清空 items、alternativeItems 和 suggestions，返回 clarification 与 pendingRequirements。只保护主推荐列表而保留替代列表，会从另一通道泄漏不符合要求的推荐；所以必须同时清理。

**后续真实改进：**引入来源可靠的成分字段、检测／供应商声明与更新时间，定义未知值如何处理。不能让模型凭常识替商家做食品保证。


<a id="chapter-8"></a>

## 8. LangGraph 到底做了什么，为什么当前不并行

LangGraph 的 LocalState 存约束、工具、时间、候选、报价、结果和轨迹。每个节点返回更新字段，后继读取已合并 state。当前没有循环式自主规划，也没有任意工具探索。


源码：`agent-service/app/local_life.py`，起始行 212（连续原文节选，未改写）。

```python
workflow = StateGraph(LocalState)
workflow.add_node("search_merchants", search_merchants_node)
workflow.add_node("search_products", search_products_node)
workflow.add_node("quotes", quote_delivery_node)
workflow.add_node("decide", decide_node)
workflow.add_edge(START, "search_merchants")
workflow.add_edge("search_merchants", "search_products")
workflow.add_edge("search_products", "quotes")
workflow.add_edge("quotes", "decide")
workflow.add_edge("decide", END)
local_graph = workflow.compile()
```


边表达依赖：START → merchants → products → quotes → decide → END。商家覆盖决定候选商家 ID，商品筛选决定要报价的 SKU；盲目并行前三个节点会缺输入。

节点内部可以在未来对互不依赖的远程报价做有界并行，但要处理供应商限流、共享时限和结果版本。当前报价循环调用 SQL 工具，仍在数据库事务内，不能直接把一个 cursor 丢给多线程并发使用。

**不用 LangGraph 能做吗？**可以写四个函数顺序调用，当前图并不复杂。采用图让状态边界和轨迹更明确，便于将来加澄清分支或工具路由；代价是额外依赖和学习成本。业务正确性不来自框架名字。


<a id="chapter-9"></a>

## 9. 工具层：真实 SQL、候选上限与责任边界

三个工具职责：search_merchants 查开放商家和区域覆盖；search_products 查品类、可售与足够库存；quote_delivery 查对应商家区域的报价规则。它们返回证据，决策节点不凭空编价格。


源码：`agent-service/app/local_life_store.py`，起始行 23（连续原文节选，未改写）。

```python
class LocalTools:
    def __init__(self, cursor):
        self.cursor = cursor

    def search_merchants(self, zone):
        self.cursor.execute("SELECT m.merchant_id,m.name,m.data_source FROM local_merchants m "
                            "JOIN local_delivery_rules d ON d.merchant_id=m.merchant_id "
                            "WHERE m.enabled=TRUE AND d.zone=%s ORDER BY m.merchant_id", (zone,))
        return self.cursor.fetchall()

    def search_products(self, merchant_ids, category, quantity):
        if not merchant_ids:
            return []
        placeholders = ",".join(["%s"] * len(merchant_ids))
        self.cursor.execute("SELECT p.*,m.name AS merchant_name,m.data_source FROM local_products p "
                            "JOIN local_merchants m ON m.merchant_id=p.merchant_id "
                            f"WHERE p.merchant_id IN ({placeholders}) AND p.category=%s "
                            "AND p.enabled=TRUE AND p.stock>=%s ORDER BY p.product_id LIMIT 201",
                            (*merchant_ids, category, quantity))
        rows = self.cursor.fetchall()
        if len(rows) > 200:
            raise CatalogLimitError("candidate set exceeds current 200-product execution budget")
        return rows

    def quote_delivery(self, merchant_id, zone):
        self.cursor.execute("SELECT * FROM local_delivery_rules WHERE merchant_id=%s AND zone=%s", (merchant_id, zone))
        rule = self.cursor.fetchone()
        if rule is None:
            raise QuoteRejected("unknown_delivery_quote")
        return rule
```


SQL 值使用参数绑定；IN 的占位符数量由商家 ID 列表长度生成，值仍通过参数传入，不是拼入用户字符串。

`LIMIT 201` 是哨兵：当前支持最多 200 个候选，多出一个就报 CatalogLimitError。它没有悄悄取前 200 并声称全目录最低价。对“最便宜”这种目标，静默截断可能改变赢家，明确失败更可解释。

商家工具与商品工具都依赖数据库实际数据。产品预筛 enabled 和 stock，make_quote 再做字段级防御，不因数据库种子有 CHECK 就忽略未来适配器返回坏数据。

**边界：**报价库存是读取的状态，没有预占或扣减。并发查询者都可能看到相同库存；当前只提供推荐，不能保证随后能买到。真实下单应在订单／库存服务再次验证或预占。


<a id="chapter-10"></a>

## 10. 报价计算：完整金额、边界和可解释性

所有金额使用人民币整数分；数量乘单价，单件包装乘数量，门槛依据优惠前商品小计。这个门槛规则是当前业务定义，不一定适用于真实平台。

完整公式：商品小计 + 包装 + 配送 − 优惠。免配送、优惠门槛在相等时满足；优惠不能超过商品小计。报价有效期取当前加 120 秒与配送规则截止的更早值。


源码：`agent-service/app/local_life.py`，起始行 76（连续原文节选，未改写）。

```python
def make_quote(product: dict, rule: dict, quantity: int, now: datetime) -> dict:
    """One SKU, quantity 1..10. Thresholds use goods subtotal before discounts."""
    if type(quantity) is not int or not 1 <= quantity <= 10:
        raise QuoteRejected("quantity")
    for key in ["unit_price_cents", "packaging_cents", "stock"]:
        if type(product.get(key)) is not int or product[key] < 0:
            raise QuoteRejected("unknown_price_or_stock")
    for key in ["delivery_cents", "eta_minutes", "minimum_goods_cents", "coupon_discount_cents"]:
        if type(rule.get(key)) is not int or rule[key] < 0:
            raise QuoteRejected("unknown_delivery_quote")
    for key in ["free_delivery_goods_cents", "coupon_threshold_cents"]:
        if rule.get(key) is not None and (type(rule[key]) is not int or rule[key] < 0):
            raise QuoteRejected("unknown_delivery_quote")
    if rule["eta_minutes"] == 0:
        raise QuoteRejected("unknown_delivery_quote")
    if not product.get("enabled") or product["stock"] < quantity:
        raise QuoteRejected("stock")
    valid_until = now + timedelta(seconds=120)
    if rule.get("valid_until"):
        expiry = rule["valid_until"]
        expiry = expiry.replace(tzinfo=timezone.utc) if expiry.tzinfo is None else expiry
        if expiry <= now:
            raise QuoteRejected("quote_expired")
        valid_until = min(valid_until, expiry)
    subtotal = product["unit_price_cents"] * quantity
    if subtotal < rule["minimum_goods_cents"]:
        raise QuoteRejected("minimum_order")
    delivery = rule["delivery_cents"]
    if rule.get("free_delivery_goods_cents") is not None and subtotal >= rule["free_delivery_goods_cents"]:
        delivery = 0
    discount = 0
    if rule.get("coupon_threshold_cents") is not None and subtotal >= rule["coupon_threshold_cents"]:
        discount = min(rule["coupon_discount_cents"], subtotal)
    packaging = product["packaging_cents"] * quantity
    return {"quoteId": str(uuid.uuid4()), "quantity": quantity, "unitPriceCents": product["unit_price_cents"],
            "goodsSubtotalCents": subtotal, "packagingFeeCents": packaging, "deliveryFeeCents": delivery,
            "discountCents": discount, "totalCents": subtotal + packaging + delivery - discount,
            "etaMinutes": rule["eta_minutes"], "zone": rule["zone"], "currency": "CNY",
            "validUntil": valid_until.isoformat(), "quotedAt": now.isoformat(), "dataSource": product["data_source"]}
```


第一部分防御类型：`type(x) is int` 不接受 bool 或浮点，拒绝缺价格／缺库存；负数无效，eta=0 视为未知。quantity 仍必须 1～10。不能把缺失值默认成 0，否则未知配送费会伪装成免费。

第二部分判断规则过期、库存和起送门槛。起送不足不是放宽预算可以修复的；必须增加商品数量或换商家。第三部分计算门槛与总价，最后返回费用拆分、时间、货币、数据源及有效期。

**纸上算两杯经典美式：**商品 800×2=1600，包装 100×2=200，配送 800；未满 2000 优惠门槛，总计 2600。不能把单杯到手 1700 直接乘 2，因为配送不是每杯重复收取；也不能误用两杯 1600 达到 2000 优惠门槛。

**三杯经典美式：**商品 2400，包装 300，配送 800，优惠 300，总计 3200。**两杯便利店美式：**商品 2400，包装 0，配送 200，优惠 200，总计 2400。

这里没有真实平台优惠券叠加、红包分摊、会员价、退款舍入。以后增加这些，应把规则版本与报价来源纳入证据，而不是在当前公式里塞无来源的模型推测。


<a id="chapter-11"></a>

## 11. 完整匹配、同类替代、硬过滤与排序

用户要“燕麦拿铁”，完整 query 仍为燕麦拿铁；family 只用来找拿铁家族的替代。完整标题匹配采用字面包含，不是语义向量检索，也不保证商品变体完全一致。


源码：`agent-service/app/local_life.py`，起始行 138（连续原文节选，未改写）。

```python
def search_products_node(state):
    c = state["constraints"]
    products = state["tools"].search_products([m["merchant_id"] for m in state["merchants"]], c["category"], c["quantity"])
    if c.get("product_query"):
        query = c["product_query"].casefold()
        family = product_family(c["product_query"]).casefold()
        products = [p | {"match_type": "exact" if query in p["title"].casefold() else "alternative"}
                    for p in products if query in p["title"].casefold()
                    or (c.get("product_match_mode") == "prefer" and family != query and family in p["title"].casefold())]
    return {"products": products, "tool_trace": state["tool_trace"] + [{"tool": "search_products", "status": "success", "resultSummary": {"count": len(products), "category": c["category"]}}]}
```


exact 只保留 query 命中的商品；prefer 额外允许已知 family 命中。未见 query 没有家族映射时，family=query，不自动凭空猜一个替代家族。

替代项必须有 matchType=alternative，不能与完整匹配一起按最低价混排，否则便宜普通拿铁可能压在用户指定款前面。


源码：`agent-service/app/local_life.py`，起始行 165（连续原文节选，未改写）。

```python
def decide_node(state):
    accepted, alternatives, similar = [], [], []
    c, rejected = state["constraints"], Counter(state["rejected"])
    for offer in state["offers"]:
        q, changes = offer["quote"], {}
        if c["max_total_cents"] is not None and q["totalCents"] > c["max_total_cents"]:
            rejected["budget"] += 1
            changes["max_total_cents"] = q["totalCents"]
        if c["max_delivery_minutes"] is not None and q["etaMinutes"] > c["max_delivery_minutes"]:
            rejected["delivery_time"] += 1
            changes["max_delivery_minutes"] = q["etaMinutes"]
        if offer.get("matchType") == "alternative":
            if not changes:
                similar.append(offer | {"requiresConfirmation": True, "requestedProduct": c["product_query"]})
            continue
        if changes:
            alternatives.append({"changes": changes, "requiresConfirmation": True, "exampleProductId": offer["itemId"]})
        else:
            accepted.append(offer)
    accepted.sort(key=lambda item: (item["quote"]["totalCents"], item["quote"]["etaMinutes"], item["itemId"]))
    similar.sort(key=lambda item: (item["quote"]["totalCents"], item["quote"]["etaMinutes"], item["itemId"]))
    alternatives.sort(key=lambda alt: (len(alt["changes"]), alt["changes"].get("max_total_cents", 0), alt["exampleProductId"]))
```


逐步看：

1. 超预算、超时分别记录 rejected，不能只看商品标价。
2. 计算如果放宽要改变的字段 changes，但这只是建议，并没有写回 constraints。
3. 替代商品即便满足硬约束，也进入 similar 并要求确认；不进入 accepted。
4. 完整匹配不满足时，形成 suggestions 候选；满足时进入 accepted。
5. accepted / similar 分别按 totalCents、etaMinutes、itemId 排序，稳定且可复现。

状态 completed 的依据是 accepted 非空；只有替代商品时仍可 no_match，因为原指定商品确实没有匹配。前端可以展示独立替代区并请用户确认，不应伪装成原任务完成。

返回最多三项。无匹配不表示现实所有商家都没货，只表示当前模型和目录范围内未找到。

**追问：先排序再过滤有什么问题？**在大候选集取 TopK 后再过滤可能漏掉后面的合格项；当前本地生活先做硬过滤再取前三。Java 历史 DAG 的顺序另有设计，不要把两条路径混用。


<a id="chapter-12"></a>

## 12. 事务与幂等：请求为什么不能重复提交结果

幂等键不是 UUID 本身带魔法，而是服务器把“这个键对应什么内容和结果”持久化。作用域是 user_id + session_id + request_id。任务 task_id 是生成的结果身份，和客户端重试标识用途不同。

payload 指纹涵盖原始 message 与 structured constraints，以排序键和固定 JSON 分隔符序列化，再做 SHA-256。相同语义但文本不同不一定同指纹；这里校验精确请求内容，不做语义等价归一化。


源码：`agent-service/app/local_life_store.py`，起始行 88（连续原文节选，未改写）。

```python
def execute_local(connect, user_id, session_id, request_id, message, structured=None, ttl=86400):
    began = time.perf_counter()
    payload = json.dumps({"message": message, "constraints": structured}, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    fingerprint = hashlib.sha256(payload.encode()).hexdigest()
    with connect() as conn:
        try:
            with conn.cursor(pymysql.cursors.DictCursor) as cur:
                cur.execute("SET time_zone = '+00:00'")
                cur.execute("SET SESSION innodb_lock_wait_timeout=3")
                cur.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
                conn.begin()
                cur.execute("SELECT user_id FROM user_profiles WHERE user_id=%s", (user_id,))
                if not cur.fetchone():
                    raise HTTPException(404, "user not found")
                cur.execute("INSERT INTO local_agent_sessions(user_id,session_id,constraints_json,expires_at) "
                            "VALUES(%s,%s,'{}',TIMESTAMPADD(SECOND,%s,UTC_TIMESTAMP())) "
                            "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)", (user_id, session_id, ttl))
                cur.execute("SELECT constraints_json,pending_requirements,expires_at>UTC_TIMESTAMP() AS fresh FROM local_agent_sessions "
                            "WHERE user_id=%s AND session_id=%s FOR UPDATE", (user_id, session_id))
                session = cur.fetchone()
                pending = json.loads(session["pending_requirements"]) if session["pending_requirements"] and session["fresh"] else []
                cur.execute("SELECT payload_hash,result_json FROM local_agent_tasks WHERE user_id=%s AND session_id=%s AND request_id=%s",
                            (user_id, session_id, request_id))
                existing = cur.fetchone()
                if existing:
                    if existing["payload_hash"] != fingerprint:
                        raise HTTPException(409, "同一请求标识不能更改内容或条件")
                    result = guard_pending(expire_quotes(json.loads(existing["result_json"])), pending)
                    conn.commit()
                    return result | {"replayed": True}
```


逐段说明：

- 事务使用 READ COMMITTED，设置时区 UTC 与有界行锁等待；不是默认隔离级别想当然。
- 创建或触碰 session 行，再 FOR UPDATE 锁这个用户会话。相同会话的两轮并发会串行读取／更新快照。
- 先查已完成同键任务；指纹不同 409，同内容返回旧任务并标 replayed。
- 重放前重新判断报价过期和当前 pending，不能把保存的成功 JSON 原封不动当作仍可买的方案。
- commit 之后才返回正常结果。except 统一 rollback，避免半条任务或半个对话。

为什么先 upsert session？新会话可能并发第一次请求，需要先有可锁行；主键(user,session)和事务让首次创建也可协调。`ON DUPLICATE ...` 会参与行锁竞争，后面 FOR UPDATE 明确锁定当前快照。

**为什么按会话锁而不是全用户锁？**同会话多轮不能丢更新；不同会话可并行。代价是它们同时修改同一个长期偏好键时，当前没有额外版本冲突协议，最终写入可能覆盖。不能说全用户记忆更新已经完全串行化。


<a id="chapter-13"></a>

## 13. 一次提交到底保存哪些内容，为什么必须原子

事务中读取旧快照／偏好、解析条件、更新 pending、调用本地 SQL 工具、保存结果。只有没有解析 issues 时才更新有效约束快照；明确记住时保存本轮 current 字段，不把整个旧会话无差别永久化。

随后插 task，写用户和助手两条对话，再提交。下面是核心保存片段。


源码：`agent-service/app/local_life_store.py`，起始行 148（连续原文节选，未改写）。

```python
                if not issues:
                    cur.execute("UPDATE local_agent_sessions SET constraints_json=%s,expires_at=TIMESTAMPADD(SECOND,%s,UTC_TIMESTAMP()) "
                                "WHERE user_id=%s AND session_id=%s", (json.dumps(constraints), ttl, user_id, session_id))
                    if remember_explicitly(message):
                        patch = {key: constraints[key] for key, source in sources.items() if source == "current"}
                        for key, value in patch.items():
                            memory_updated.append(key)
                            if value is None:
                                cur.execute("DELETE FROM agent_long_term_memories WHERE user_id=%s AND memory_key=%s", (user_id, "local." + key))
                            else:
                                cur.execute("INSERT INTO agent_long_term_memories(user_id,memory_key,memory_value,confidence,source_name) "
                                            "VALUES(%s,%s,%s,1,'explicit_local_preference') ON DUPLICATE KEY UPDATE "
                                            "memory_value=VALUES(memory_value),source_name=VALUES(source_name),expires_at=NULL,updated_at=CURRENT_TIMESTAMP",
                                            (user_id, "local." + key, json.dumps(value)))
```


源码：`agent-service/app/local_life_store.py`，起始行 171（连续原文节选，未改写）。

```python
                cur.execute("INSERT INTO local_agent_tasks(task_id,user_id,session_id,request_id,payload_hash,result_json) VALUES(%s,%s,%s,%s,%s,%s)",
                            (result["taskId"], user_id, session_id, request_id, fingerprint, json.dumps(result, ensure_ascii=False)))
                for role, content in [("user", message), ("assistant", result["answer"])]:
                    cur.execute("INSERT INTO agent_conversations(session_id,user_id,role_name,content,expires_at) "
                                "VALUES(%s,%s,%s,%s,TIMESTAMPADD(SECOND,%s,UTC_TIMESTAMP()))", (session_id, user_id, role, content, ttl))
                conn.commit()
                return result
        except Exception:
            conn.rollback()
            raise
```


不能把“先任务、后记忆、最后对话”拆成互相独立的自动提交：否则用户收到失败却保存了长期偏好，或者任务成功但下一轮仍读取旧快照。

长期偏好值为 None 时删除该偏好；非空则 upsert 并清除旧过期标记，让明确重新确认的偏好生效。数据过期过滤使用数据库 UTC 时间。

当前把本地 SQL 工具放在持锁事务内，是因为它们没有远程副作用且数据规模有限。即便如此，执行仍可能占锁；接入几十秒外部网络工具时必须拆分，不应把事务设计原样搬过去。

候选、报价读取是在 RC 下多个语句读取，不是对整个目录形成一个永久一致快照；当前演示数据稳定，报价还有有效期。真实供应商需要版本号／有效期／最终确认来处理目录变化。


<a id="chapter-14"></a>

## 14. 故障恢复：能恢复什么，不能恢复什么

| 场景 | 当前保证 | 要保留的边界 |
| --- | --- | --- |
| 提交前崩溃 | 事务回滚，原键可重试 | 没有恢复图中执行栈 |
| 提交后响应丢失 | 查已提交任务、返回原 taskId | 报价可能过期 |
| 同键并发 | 会话锁和唯一键收敛为一个任务 | 不同键不是同一次请求 |
| 工具 SQL 报错 | 回滚并返回 tool_error | 不代表外部下单可安全重跑 |
| 报价过期 | 清空方案并提示重新询价 | 不锁库存／价格 |
| 新增未解决饮食要求后读旧任务 | guard_pending 阻止旧推荐 | pending 仍受会话 TTL 边界影响 |

“恢复”是持久化结果重放与结构化状态恢复，不是 LangGraph checkpointer 自动续跑中断节点。当前 compile 没有接入持久检查点，这一点在 Agent 面试里很容易被追问。


源码：`agent-service/app/local_life_store.py`，起始行 55（连续原文节选，未改写）。

```python
def expire_quotes(result, now=None):
    now = now or datetime.now(timezone.utc)
    if any(datetime.fromisoformat(item["quote"]["validUntil"]) <= now for item in result.get("items", []) + result.get("alternativeItems", [])):
        return result | {"status": "quote_expired", "originalStatus": result["status"], "items": [], "alternativeItems": [],
                         "answer": "该任务的报价已过期，请使用新的请求标识重新询价；旧报价不会作为当前可购买方案返回。"}
    return result
```


完整匹配和替代都检查 validUntil；只要其中存在过期报价，当前实现会把该任务整批报价清空，而不是只删除一条过期项。这是保守任务级失效策略，可讨论是否未来改成逐项失效。

已提交原键重放继续看到过期，不应通过换一个响应字段把它伪装成新报价；用户真正重新询价要发新 request_id。

**加下单会怎样？**外部订单接口有副作用，仅靠本地事务不足。需要持久订单状态、供应商幂等键、超时后查单、补偿与对账；如果每次重跑都重新下单，会扣两次钱。本文不能让你声称已有支付级恢复。


<a id="chapter-15"></a>

## 15. 异步与线程：FastAPI 为什么仍要 to_thread

FastAPI 的 async 函数不让同步 PyMySQL 自动变异步。阻塞数据库调用直接放在事件循环，会阻塞其他连接。入口把 execute_local 放进 asyncio.to_thread，使同步事务在工作线程执行，事件循环继续处理其他请求。


源码：`agent-service/app/main.py`，起始行 373（连续原文节选，未改写）。

```python
async def execute(request: ChatRequest) -> dict[str, Any]:
    session_id = request.session_id or f"web-{request.user_id}"
    try:
        use_local = request.workflow == "local_life" or (request.workflow == "auto" and (
            request.constraints is not None or is_local_request(request.message)
            or ((request_mode(request.message) != "general" or is_local_followup(request.message))
                and await asyncio.to_thread(local_session_active, request.user_id, session_id))))
        if not use_local:
            return await execute_legacy(request)
        result = await asyncio.to_thread(local_life_store.execute_local, db_connection, request.user_id, session_id,
                                         request.request_id or uuid.uuid4().hex, request.message,
                                         request.constraints.model_dump(exclude_unset=True) if request.constraints is not None else None, SHORT_TTL)
        try:
            await asyncio.to_thread(redis_client.delete, f"agent:session:{request.user_id}:{session_id}")
        except redis.RedisError:
            pass  # The local workflow recovers from its MySQL snapshot, not Redis.
        return result
    except (pymysql.MySQLError, local_life_store.CatalogLimitError):
        return {"agent": "local-life-agent", "mode": "local_life", "status": "tool_error", "sessionId": session_id,
                "answer": "本次任务因数据服务不可用或查询范围超出上限而结束，未返回方案。请保留同一请求标识重试；已提交的结果可重放。",
                "items": [], "toolTrace": [{"tool": "local_data_tools", "status": "error"}]}
```


显式 workflow=local_life 优先；auto 结合本轮信号与有效本地会话；否则走历史推荐／问答。产品应尽量显式选模式，避免“继续”究竟继续哪个任务的歧义。

`model_dump(exclude_unset=True)` 是文本与表单合并语义的重要一环。调用后 Redis 删除是辅助缓存处理，Redis 故障在这段本地路径可被忽略，因为持久事实在 MySQL；不代表登录解析和历史路径也不依赖 Redis。

捕获 MySQL 错误和候选超限返回 tool_error，无推荐，提示保留原标识。HTTPException 如 409 幂等冲突则由入口转成对应错误，不应该被统一吞成“工具成功”。

**性能边界：**to_thread 不等于无限并发，线程池、数据库连接和行锁都是容量约束。当前没有给每个工作流设置完整跨节点截止时间，也不应把 Java DAG 的 500ms 总预算套用到这条 Python 路径。


<a id="chapter-16"></a>

## 16. 电脑端表单、弱网重试与多标签页隔离

页面把完整商品名、区域、品类、预算、时间、数量和匹配方式显示为可编辑字段。清空预算／时间明确传 null；换品类清空旧商品；修改字段发新请求，原样重试保留 request_id。修改预算不清除 pending 饮食要求。

结构化参数通过 WebSocket 进入 ChatRequest，必须服务端也传给 execute；只把 input 显示成“已修改”而不传 constraints 是假修复。


源码：`agent-service/app/static/index.html`，起始行 83（连续原文节选，未改写）。

```javascript
    function pendingStorageKey(){return pendingKey+':'+auth?.userId+':'+auth?.sessionId}
    function pending(){try{return JSON.parse(localStorage.getItem(pendingStorageKey())||'null')}catch{return null}}
    function showPendingRetry(){const p=pending();$('retry').style.display=p&&auth&&p.sessionId===auth.sessionId&&p.workflow==='local_life'?'inline-block':'none'}
    function applyConstraints(){
      if($('send').disabled || !$('constraint-editor').reportValidity())return;
      const budget=$('edit-budget').value;
      const constraints={zone:$('edit-zone').value,category:$('edit-category').value,product_query:$('edit-product').value.trim()||null,product_match_mode:$('edit-match').value,max_total_cents:budget===''?null:Math.round(Number(budget)*100),max_delivery_minutes:$('edit-time').value===''?null:Number($('edit-time').value),quantity:Number($('edit-quantity').value)};
      $('workflow').value='local_life';$('input').value='按已确认的条件重新查询';send(false,constraints);
    }
```


源码：`agent-service/app/main.py`，起始行 476（连续原文节选，未改写）。

```python
async def chat_ws(websocket: WebSocket):
    await websocket.accept()
    try:
        while True:
            try:
                payload = await websocket.receive_json()
            except ValueError:
                await websocket.send_json({"event": "agent_error", "message": "请求必须为有效 JSON 对象。"})
                continue
            if not isinstance(payload, dict):
                await websocket.send_json({"event": "agent_error", "message": "请求必须为 JSON 对象。"})
                continue
            try:
                current = resolve_demo_session(payload.get("token"))
            except HTTPException as exc:
                await websocket.send_json({"event": "agent_error", "message": exc.detail})
                continue
            try:
                request = ChatRequest(user_id=int(current["user_id"]), message=payload.get("message", ""), session_id=current["session_id"],
                                      workflow=payload.get("workflow", "auto"), request_id=payload.get("request_id"), constraints=payload.get("constraints"))
                await websocket.send_json({"event": "agent_started", "sessionId": request.session_id})
                result = await execute(request)
            except ValidationError:
                await websocket.send_json({"event": "agent_error", "message": "请求格式无效，请检查消息和请求标识。"})
                continue
            except HTTPException as exc:
                await websocket.send_json({"event": "agent_error", "message": exc.detail})
                continue
            for trace in result["toolTrace"]:
                await websocket.send_json({"event": "tool_completed", "data": trace})
            await websocket.send_json({"event": "agent_completed", "data": result})
    except WebSocketDisconnect: return
```


WS 首先处理非法 JSON／非对象帧，再从 token 解析实际用户与会话，而不是相信 payload 中自报 user_id。模型校验失败返回 agent_error 并继续接收；成功后发送 trace 和 agent_completed。WS 展示事件不等于逐 token 模型流式输出。

浏览器保留待重试 payload。服务端已完成但完成消息丢失时，连接关闭显示重试；原键返回同 taskId 且 replayed=true。新版本按 userId + sessionId 分别存待重试记录，避免不同会话覆盖同一个全局槽。

桌面专项曾复现旧偏好／历史迟到覆盖新账号。修复原则：每个异步加载捕获身份和 session，收到后比较；登录／恢复还加操作代次防竞态；WebSocket 回调同样检查当前会话。其他标签页改共享身份时，本页清理旧展示返回画像选择。前端隔离不等于后端授权，二者都需要。

电脑页面刷新后恢复身份和对话，但历史报价卡片／全部表单状态不等于持久重建；旧报价还必须按有效期检查。别说刷新后已经自动恢复每个 UI 细节。

演示身份通过选择画像获得，不是生产密码／SSO。HTTP 本地推荐入口可直接使用演示 user_id，偏好接口也不是完整生产授权体系；不能因为 WS token 正确就说整个系统已满足生产鉴权。


<a id="chapter-17"></a>

## 17. 可选大模型和历史路径：如何回答 LLM 追问

如果面试官问“模型在哪里”，先区分本地生活主路径和历史路径。主路径当前规则解析、LangGraph 和确定性计算，llmCalls=0；有模型适配代码不代表每个请求都用了模型。

历史 Python plan_node 可用 OpenAI-compatible 接口抽意图，结果仍经 Intent 模型验证，显式文本条件最后覆盖模型输出，防止模型擅自削弱已识别的预算／去广告条件。失败降级到规则 baseline。


源码：`agent-service/app/main.py`，起始行 262（连续原文节选，未改写）。

```python
async def plan_node(state: AgentState):
    history = [turn for turn in state["short_memory"]
               if turn["role"] == "user" and request_mode(turn["content"]) == "recommendation"]
    try:
        baseline = local_intent(state["message"], state["long_memory"], history)
    except ValidationError:
        return {"intent": Intent(needs_clarification=True).model_dump(), "planner": "constraint-validation",
                "plan_error": "预算须为 0～1000000 的有效金额，请修正条件后再推荐。"}
    if PLANNER_MODE == "openai" and LLM_API_KEY:
        try:
            patch = await llm_intent(state["message"], state["long_memory"], state["short_memory"])
            # Validate even if a different model adapter is injected. Explicit
            # deterministic constraints cannot be weakened by model output.
            patch = Intent(**patch).model_dump(exclude_unset=True)
            intent = Intent(**(baseline | patch | explicit_constraints(state["message"]))).model_dump()
            planner = "openai-function-calling"
        except Exception:
            intent, planner = baseline, "local-fallback"
    else:
        intent, planner = baseline, "local-rule-planner"
    return {"intent": intent, "planner": planner}
```


try/except 保证模型不可用时有明确 planner 标记，不假装模型调用成功。但规则 baseline 本身也有有限语义覆盖；降级可用不等于降级后效果与模型相同。

**如果要把模型接入本地生活：**让模型输出 LocalConstraints patch 及必要证据，保留明确的清除语义、完整商品名和未解决要求；Pydantic 校验后再合并，工具和报价仍是事实来源。增加独立语言集、模型调用次数／token／费用与失败降级评测。不能只替换函数再引用此前零模型费用报告。

**提示注入追问：**当前主路径不是把商家文本当 LLM 指令，所以风险模型不同；接模型后，要把供应商描述作为不可信数据，工具白名单与参数模式独立验证，严禁“忽略预算”的文本越权。不能用 system prompt 代替服务端硬过滤。

**RAG 追问：**当前本地生活不是向量检索知识库。查结构化目录和报价更适合 SQL；项目问答代码也不能等同于已构建高质量 RAG 评测体系。不要为追热点虚构 embedding、reranker 或向量数据库。


<a id="chapter-18"></a>

## 18. Java 推荐 DAG：依赖、并行、超时与降级

Java 部分保留传统推荐工程链：Prepare 读取基础特征和实验配置；Recall 召回 goods/live/ad；OnlineFeature 补状态／容量，MixRank 排序；Filter 等两者都完成再过滤；PostProcess 按场景组织结果。

不要把 MixRank 说成机器学习模型推理。RuleBasedMixRankService 是可替换的确定性规则打分，按偏好／冷启动和实验加减分，再排序。

下面的图装配直接揭示哪些算子可并行。


源码：`src/main/java/io/github/hzyang0/minireco/service/ApplicationWiring.java`，起始行 66（连续原文节选，未改写）。

```java
        DagGraph graph = new DagGraph(List.of(
                DagNode.of(prepare),
                DagNode.of(recall, PrepareOperator.NAME),
                DagNode.of(onlineFeature, RecallOperator.NAME),
                DagNode.of(mixRank, RecallOperator.NAME),
                DagNode.of(filter, OnlineFeatureOperator.NAME, MixRankOperator.NAME),
                DagNode.of(postProcess, FilterOperator.NAME)
        ));
```


Recall 完成后，onlineFeature 和 mixRank 可并行；filter 同时依赖二者，避免状态尚未准备就开始过滤。PostProcess 等 filter。

执行器以剩余依赖计数推动就绪队列：入度为 0 的节点先提交；完成一个节点，就把其依赖者计数减一，减到 0 才提交。构造时做无环校验，避免运行中永远等不到依赖。


源码：`src/main/java/io/github/hzyang0/minireco/service/operator/graph/ParallelDagOperatorExecutor.java`，起始行 101（连续原文节选，未改写）。

```java
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    cancelSubmitted(submittedFutures);
                    throw new RequestTimeoutException("recommendation request exceeded " + overallTimeoutMs + " ms");
                }
                Future<String> completedFuture = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (completedFuture == null) {
                    cancelSubmitted(submittedFutures);
                    throw new RequestTimeoutException("recommendation request exceeded " + overallTimeoutMs + " ms");
                }
                completedNodeName = completedFuture.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelSubmitted(submittedFutures);
                throw new IllegalStateException("parallel DAG execution interrupted", e);
            } catch (ExecutionException e) {
                cancelSubmitted(submittedFutures);
                if (e.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("parallel DAG node failed", e.getCause());
            }
```


使用 System.nanoTime 计算相对截止，不受墙上时钟校准影响；每次等待都从同一个总体 deadline 减剩余时间，不给每节点重新发一份完整预算。默认总预算 500ms，是配置默认值，不是测得线上 P99。

节点异常或总体超时会 cancelSubmitted；`cancel(true)` 是中断请求，不保证底层 JDBC／网络立刻停止，所以连接／socket 超时仍必要。不能宣称 future.cancel 已实现强制终止所有外部副作用。

召回内部还有独立 fanout 层，固定工作线程 + 有界 ArrayBlockingQueue + AbortPolicy；过载时将来源标 failed，而不是无限堆积召回任务。它与外层 DAG 线程池不同：外层使用固定线程池的默认队列，并不能说“整个系统所有线程池都已严格有界”。


源码：`src/main/java/io/github/hzyang0/minireco/service/operator/impl/ParallelRecallFanout.java`，起始行 41（连续原文节选，未改写）。

```java
        this.executor = new ThreadPoolExecutor(
                config.parallelism(),
                config.parallelism(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
```


多路召回并行使用共享截止，按完成顺序接收已完成来源，超时／失败来源记录 partial，合并时仍按配置来源顺序保证确定性。单来源失败可以返回其余候选，和核心工具不可用时不编造报价是一致的工程原则：降级也不能违背事实。

Filter 要 ONLINE 且对应容量大于 0：goods 看 STOCK，live 看 HEAT，ad 看 REMAINING_BUDGET_CENTS，未知字段缺失按不合格。后处理会按场景混排，商城／视频／综合偏重不同来源；与本地生活“完整到手价最小”是不同目标。

**深问：共享 RecommendContext 是否线程安全？**要分析各节点依赖、字段独占写、容器并发安全和 Future 完成后的可见性，不能仅说“用了线程安全 Map 所以所有对象安全”。现有图允许不同阶段写不同职责字段；未来新增同时修改 Item 的节点时需重新检查数据竞争。

**排序先于过滤是否浪费？**Java 历史 DAG 选择排序与特征补齐并行，Filter 等两者；在预取足够候选时可获得延迟收益，但截断可能影响结果数量。不要把这一取舍说成本地生活主路径的算法。


<a id="chapter-19"></a>

## 19. 评测：不要让解析器给自己判满分

语言理解失败可能表现为程序正常返回，但漏掉预算或把商品缩短；仅检查“返回的商品满足系统自己解析出的条件”会给错误理解打满分。

评测应先固定独立预期，再发送原话，分别检查状态、intent 字段、实际商品、价格算式、预算、时限和有效期。


源码：`scripts/evaluate-language.py`，起始行 21（连续原文节选，未改写）。

```python
def assess(result, expected):
    errors, valid_items = [], 0
    gold, actual = expected.get('intent', {}), result.get('intent', {})
    if result.get('status') != expected['status']:
        errors.append('wrong_status')
    for key, value in gold.items():
        if actual.get(key) != value:
            errors.append('intent:' + key)
```


源码：`scripts/evaluate-language.py`，起始行 37（连续原文节选，未改写）。

```python
        # Use the dataset's requirements, not the agent's possibly lossy intent.
        for field, value in gold.items():
            if value is None:
                continue
            if field == 'category' and item.get('category') != value:
                failures.append('category')
            elif field in ('zone', 'quantity') and quote.get(field) != value:
                failures.append(field)
            elif field == 'product_query' and value.casefold() not in item.get('title', '').casefold():
                failures.append('product_query')
            elif field == 'max_total_cents' and quote.get('totalCents', math.inf) > value:
                failures.append('budget')
            elif field == 'max_delivery_minutes' and quote.get('etaMinutes', math.inf) > value:
                failures.append('delivery_time')
```


`gold` 来自冻结用例，不是 result.intent。若用户只剩 16.9 元，但系统漏预算返回 17 元，intent 检查会指出字段遗漏，商品检查还会指出预算不符。金额拆分自己复算，不信任 totalCents 单字段。

区分四种指标：约束满足率看推荐项是否合规；任务成功率看整场多轮状态和结果是否正确；延迟看真实 HTTP 路径 P50/P95；模型费用看实际模型调用。当前主路径模型费用 0，不含基础设施、人力和评测出题成本，不能答成“零成本服务”。

result.metrics.elapsedMs 在事务提交前构建，不能当作包括网络与提交的全链路延迟；HTTP 评测计时更接近客户端体验，仍是本地样本。

已知证据：Python 81、Java 20；业务 36 场景／45 请求；原语言集 24／33；新模拟集首次 10/12 场景、18/20 请求，修复后 12/12、20/20。首次失败报告保留，修过的新集已转回归，不能继续叫新的盲测。

同一开发代理编题并修复不是第三方独立评测；模拟用户不是满意度调查。真实试用要让没看代码的同学自由组织语言，记录错误、卡点、修改轮数，并保留失败案例。


<a id="chapter-20"></a>

## 20. 数据库、故障与规模化的深问清单

**为什么 MySQL 而不是只用 Redis？**任务、请求指纹、约束快照、偏好和对话需要跨进程持久且原子提交；Redis 在系统中有用途，但不能把缓存当唯一任务事实。

**为什么不只保存聊天记录？**文本含噪声且会截断、混有助手建议；结构化快照记录当前有效业务状态，pending 单独保存不能验证的要求。恢复不能靠模型重读若干条聊天再猜。

**会话锁长事务会怎样？**同会话后续请求等待，连接被占用；当前 SQL 工具规模有限。真实远程工具应改成“记录任务／版本 → 释放事务 → 有界取数 → 校验版本 → 提交结果”，配合 lease、幂等与冲突处理。版本变化时不能提交旧约束结果。

**RC 能保证不会读到变化的目录吗？**不能。它保证语句级已提交视图，而不是整个多工具过程都同一业务版本；报价需有效期、来源和最终验证。更高隔离也不能让外部供应商参与本地快照。

**同用户两会话同时记住不同偏好？**当前可并行，长期键没有业务版本检查，存在最后写覆盖；可扩展偏好版本或用户维度写锁，再定义冲突优先级。

**所有工具失败都自动重试吗？**当前主路径回滚并明确 tool_error，客户端原键重试。未来对读工具可有界重试，对下单等副作用必须先有幂等协议；重试可能放大故障。

**候选超过 200 为什么报错？**保护执行预算且不伪称全目录最优。可扩展分页、索引候选生成和分批比较，但需要明确覆盖率与最优性保证。

**如何防止价格过期？**短有效期、读取时重验、重新询价；不靠页面倒计时单独保护，不能把旧任务当新购买承诺。

**能做跨店套餐最优吗？**尚未实现。会变成带配送、起送和优惠的组合优化，搜索空间更大，还要处理商家数量、拆单和库存，不是把 quantity 改成 list 就完成。

**哪些索引重要？**session 主键(user,session)定位锁行；task 唯一键(user,session,request)支持幂等；delivery 的(merchant,zone)精确报价及(zone,merchant)找覆盖；product(category,enabled,merchant)支持品类可售筛选。具体性能仍要结合 EXPLAIN 和真实分布，不能假定复合索引每个列都完全被利用。

**鉴权做到生产级了吗？**没有；画像选择与演示 user_id 入口边界明确。需要认证服务、用户范围授权、限流、隐私管理，再对公网开放。解释这一点不会削弱项目，反而避免被一个接口追问击穿。

**要不要上微服务／向量数据库？**先找瓶颈与数据形态。当前主要是结构化规则与短事务；拆服务增加一致性、超时、追踪和部署成本，向量检索不替代报价校验。


<a id="chapter-21"></a>

## 21. 面试开场与追问链：从业务到代码

**30 秒版本：**“我做了本地生活约束推荐，工具查商家、商品和配送，再用完整到手价比较。难点在多轮约束不丢失、指定商品不被替换、未知成分不乱推荐，以及重试和恢复。主路径用规则和 LangGraph，结果与会话在 MySQL 事务中原子保存。”

**2 分钟版本：**先讲 20 元／30 分钟例子和标价不等于到手价；讲取消时限、完整燕麦拿铁、过敏要求；再讲同会话锁、请求指纹和报价过期；最后给模拟测试证据与有限语言边界。

**5 分钟版本：**选 merge_constraints、execute_local 或 make_quote 中一段，逐条解释输入、检查、状态、返回与失败路径。不要为了显得复杂，把 LLM、Java DAG、SQL 工具全部塞成同一个请求链。

典型追问链一：

- 为什么用 Agent？→ 可解释工具工作流，让需求、工具证据、结果形成闭环。
- Agent 自主在哪里？→ 当前自治有限，图固定，业务决策受约束；不虚构自主规划。
- 不用框架能做？→ 可以，当前图小；框架提供状态编排边界，但正确性由类型、规则、事务和测试提供。
- 模型如何保证不幻觉？→ 当前主路径不调用模型；接模型后仍不让其编价格与越过硬约束。

追问链二：

- 燕麦拿铁为什么不能直接查拿铁？→ 完整意图会丢失。
- 想找便宜替代呢？→ prefer 可以单列待确认替代，exact 不替换。
- 燕麦是不是零乳糖？→ 数据没有证据，不能确认。
- 下一轮没再提过敏呢？→ pending 持久保留直到明确撤销或相应会话过期。

追问链三：

- 用户重复点发送？→ 相同 request_id 同内容重放，不同内容 409。
- 响应丢失？→ 原键查原任务；客户端保留 structured payload。
- 进程重启？→ MySQL 已提交结果仍在；未提交回滚。
- 是否恢复到图中断处？→ 没有，那是另一种持久检查点与外部副作用协议设计。

追问链四：

- 测试全绿有什么用？→ 证明当前断言覆盖范围；已修集是回归，不是泛化结论。
- 新输入还会失败吗？→ 可能，所以提供可编辑约束和明确澄清，继续新集／用户试用。
- 费用与耗时多少？→ 主路径模型调用为零；时间引用报告的本地样本和测量口径，不报虚构线上 SLA。

你可以承认边界，但每次都给一个下一步设计与验证方法。面试的稳定感来自因果解释，不是保证能答所有未知问题。


<a id="chapter-22"></a>

## 22. 离线练习与掌握标准

**练习一：合并快照。**偏好 east/coffee/2000；会话 north/coffee/1500/20 分钟；本轮“改成两杯，时间不限”。答案：north、coffee、1500、2、时间 None，长期偏好仍 east。若本轮表单预算 1800，则本轮 structured 最优先。

**练习二：算报价。**两杯经典美式到手 2600 分，不是 3400；三杯 3200；两杯便利店美式 2400。写出每一步门槛判断。

**练习三：恢复时序。**把 crash 放在工具结束、task insert、commit、WebSocket 完成事件四处，以 commit 为分界：提交前退出会回滚；提交成功后退出，原键能重放；不要说“insert 执行完成就一定持久成功”。

**练习四：前端隔离。**A 的历史／偏好发起后，切换 B，再收到 A 响应。服务端权限可以完全正确，客户端仍可能展示错误数据；解决是捕获身份与会话并在渲染前比较，多标签页要处理共享存储变更。

**练习五：加入远程配送供应商。**列出超时、共享截止、限流、规则来源、有效期、报价失败、重试与事务拆分。再说明下单接口与纯报价不同，需要外部幂等和查单。

最终自检：能画两套图——本地生活 Python 工具链与 Java 推荐 DAG；能解释完整商品名、None 与省略、pending、价格公式、事务、幂等和恢复；能指出当前没有真实履约、生产身份、通用语言理解、支付与节点续跑。做到这些，再深入一段源码，就能清晰介绍实现并应对多数围绕项目的技术追问。
