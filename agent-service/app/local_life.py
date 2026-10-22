"""Campus demo decision policy: hard constraints first, integer-cent quotes second."""
from collections import Counter
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import re
from typing import Any, Literal, TypedDict
import uuid

from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, ConfigDict, Field, StrictInt, ValidationError


class LocalConstraints(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    zone: Literal["north", "south", "east"] | None = None
    category: Literal["coffee", "meal", "grocery"] | None = None
    product_query: str | None = Field(default=None, min_length=1, max_length=40)
    max_total_cents: StrictInt | None = Field(default=None, ge=0, le=1_000_000)
    max_delivery_minutes: StrictInt | None = Field(default=None, ge=1, le=180)
    quantity: StrictInt = Field(default=1, ge=1, le=10)


ZONES = {"北区": "north", "南区": "south", "东区": "east"}
CATEGORIES = {"咖啡": "coffee", "美式": "coffee", "拿铁": "coffee", "简餐": "meal",
              "米饭": "meal", "午餐": "meal", "晚餐": "meal", "零食": "grocery",
              "便利食品": "grocery", "牛奶": "grocery", "面包": "grocery"}
PRODUCT_WORDS = ["美式", "拿铁", "冷萃", "牛奶", "面包", "坚果", "鸡肉", "时蔬"]
CATEGORIES.update({"冷萃": "coffee", "坚果": "grocery", "鸡肉": "meal", "时蔬": "meal"})


def normalize_numbers(message: str) -> str:
    """Normalize bounded Chinese integers only where a numeric unit follows."""
    digits = {c: i for i, c in enumerate("零一二三四五六七八九")}
    digits.update({"两": 2, "俩": 2})

    def convert(match):
        value = match.group(0)
        if len(value) == 1 and value in digits:
            return str(digits[value])
        if value.count("十") == 1:
            left, right = value.split("十")
            if (not left or left in digits) and (not right or right in digits):
                return str(digits.get(left, 1) * 10 + digits.get(right, 0))
        return value  # Unsupported expressions remain visible for clarification.

    return re.sub(r"[零一二两俩三四五六七八九十百千]+(?=\s*(?:元|块|分钟|杯|份|件))", convert, message)


def is_local_request(message: str) -> bool:
    return any(word in message for word in ["本地生活", "到手价", "配送", "送达", "送到", *ZONES])


def remember_explicitly(message: str) -> bool:
    return (any(word in message for word in ["记住", "以后", "长期偏好"])
            and not any(word in message for word in ["这次", "本次", "临时", "今天", "不要记住", "别记住", "不用记住", "不要保存"]))


def parse_constraints(message: str) -> tuple[dict[str, Any], list[str]]:
    message = normalize_numbers(message)
    patch: dict[str, Any] = {}
    issues = []
    for words, field in [(ZONES, "zone"), (CATEGORIES, "category")]:
        found = [(message.rfind(word), value) for word, value in words.items() if word in message]
        if found:
            patch[field] = max(found)[1]
            if len({value for _, value in found}) > 1:
                issues.append("一次查询只支持一个区域和一种品类，请明确选择")
    specific = [word for word in PRODUCT_WORDS if word in message]
    if len(specific) > 1:
        issues.append("一次只支持一种具体商品，请分别查询或使用明确的商品名称参数")
    if "category" in patch:
        patch["product_query"] = specific[0] if len(specific) == 1 else None
    if "不限款式" in message or "取消商品限制" in message:
        patch["product_query"] = None
    if any("不要" + word in message or "不喝" + word in message for word in PRODUCT_WORDS):
        issues.append("暂不支持排除某种商品，请直接说明想要的商品")
    budget = re.search(r"(?:预算|到手价不超过|最多花)\s*(?:改为|改成|提高到|降到)?\s*[¥￥]?\s*(-?\d+(?:\.\d+)?)", message)
    if not budget:
        budget = re.search(r"(-?\d+(?:\.\d+)?)\s*元以内", message)
    if budget:
        amount = Decimal(budget.group(1)) * 100
        if amount != amount.to_integral_value():
            issues.append("预算最多支持两位小数")
        else:
            patch["max_total_cents"] = int(amount)
    if any(word in message for word in ["不限预算", "预算不限", "取消预算"]):
        patch["max_total_cents"] = None
    if "预算" in message and "max_total_cents" not in patch:
        issues.append("请用明确金额表达预算，例如预算20元；或明确说不限预算")
    if re.search(r"\d+\s*(?:到|至|[-~～])\s*\d+\s*(?:元|块)", message):
        issues.append("暂不支持价格区间，请明确最高到手预算")
    eta = re.search(r"(-?\d+)\s*分钟(?:内|之内)?(?:送达|送到|到达)?", message)
    if eta:
        patch["max_delivery_minutes"] = int(eta.group(1))
    elif "半小时" in message:
        patch["max_delivery_minutes"] = 30
    if any(word in message for word in ["不限时间", "时间不限", "取消送达限制"]):
        patch["max_delivery_minutes"] = None
    if re.search(r"小时|分钟", message) and "max_delivery_minutes" not in patch:
        issues.append("请用分钟表达送达时限，例如30分钟内送达")
    quantity = re.search(r"(-?\d+)\s*(?:杯|份|件)", message)
    if quantity:
        patch["quantity"] = int(quantity.group(1))
    if re.search(r"\d+\.\d+\s*(?:杯|份|件|分钟)", message):
        issues.append("数量和送达分钟必须为整数")
    if re.search(r"[零一二两俩三四五六七八九十百千几多]+\s*(?:杯|份|件)", message):
        issues.append("请明确单品数量，支持1～10件")
    if re.search(r"(?:送到|我在|地址是|配送到)", message) and "zone" not in patch:
        issues.append("目前只支持演示区域北区、南区、东区，请明确选择")
    if "西区" in message:
        issues.append("西区不在已建模的演示区域中，不能确认配送")
    if any(word in message for word in ["无糖", "低糖", "去冰", "过敏", "不含", "不要辣", "免配送费", "素食"]):
        issues.append("暂不支持口味、过敏或指定免运费条件，不能确认这些要求已满足")
    if any(word in message for word in ["不要咖啡", "不要简餐", "不要零食"]):
        issues.append("请明确改选咖啡、简餐或零食中的一类")
    if any(word in message for word in ["数码", "手机", "鲜花", "药品"]):
        issues.append("本地生活演示目前只支持咖啡、简餐和便利食品")
    try:
        LocalConstraints(**patch)
    except ValidationError:
        issues.append("预算须为0～10000元，数量1～10，送达限制1～180分钟")
    return patch, issues


def merge_constraints(message, previous, preferences, structured=None):
    patch, issues = parse_constraints(message)
    if structured is not None:
        patch.update(structured)
        if "category" in structured and "product_query" not in structured:
            patch["product_query"] = None
    values = dict(preferences) | dict(previous) | patch
    try:
        constraints = LocalConstraints(**values).model_dump()
    except ValidationError:
        return LocalConstraints(**(dict(preferences) | dict(previous))).model_dump(), {}, ["条件格式或数值范围无效"]
    sources = {key: "long_term" for key in preferences}
    sources.update({key: "session" for key in previous})
    sources.update({key: "current" for key in patch})
    return constraints, sources, issues


class QuoteRejected(ValueError):
    pass


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


class LocalState(TypedDict, total=False):
    constraints: dict
    tools: Any
    now: datetime
    merchants: list
    products: list
    offers: list
    items: list
    tool_trace: list
    rejected: dict
    suggestions: list
    status: str
    answer: str


def search_merchants_node(state):
    merchants = state["tools"].search_merchants(state["constraints"]["zone"])
    return {"merchants": merchants, "tool_trace": [{"tool": "search_merchants", "status": "success", "resultSummary": {"count": len(merchants)}}]}


def search_products_node(state):
    c = state["constraints"]
    products = state["tools"].search_products([m["merchant_id"] for m in state["merchants"]], c["category"], c["quantity"])
    if c.get("product_query"):
        products = [p for p in products if c["product_query"].casefold() in p["title"].casefold()]
    return {"products": products, "tool_trace": state["tool_trace"] + [{"tool": "search_products", "status": "success", "resultSummary": {"count": len(products), "category": c["category"]}}]}


def quote_delivery_node(state):
    offers, rejected = [], Counter()
    for product in state["products"]:
        try:
            rule = state["tools"].quote_delivery(product["merchant_id"], state["constraints"]["zone"])
            quote = make_quote(product, rule, state["constraints"]["quantity"], state["now"])
            offers.append({"itemId": product["product_id"], "title": product["title"], "source": "local_life",
                           "category": product["category"], "merchant": {"id": product["merchant_id"], "name": product["merchant_name"]},
                           "quote": quote})
        except QuoteRejected as error:
            rejected[str(error)] += 1
    return {"offers": offers, "rejected": dict(rejected), "tool_trace": state["tool_trace"] + [
        {"tool": "quote_delivery", "status": "success", "resultSummary": {"quoted": len(offers), "rejected": dict(rejected)}}]}


def decide_node(state):
    accepted, alternatives = [], []
    c, rejected = state["constraints"], Counter(state["rejected"])
    for offer in state["offers"]:
        q, changes = offer["quote"], {}
        if c["max_total_cents"] is not None and q["totalCents"] > c["max_total_cents"]:
            rejected["budget"] += 1
            changes["max_total_cents"] = q["totalCents"]
        if c["max_delivery_minutes"] is not None and q["etaMinutes"] > c["max_delivery_minutes"]:
            rejected["delivery_time"] += 1
            changes["max_delivery_minutes"] = q["etaMinutes"]
        if changes:
            alternatives.append({"changes": changes, "requiresConfirmation": True, "exampleProductId": offer["itemId"]})
        else:
            accepted.append(offer)
    accepted.sort(key=lambda item: (item["quote"]["totalCents"], item["quote"]["etaMinutes"], item["itemId"]))
    alternatives.sort(key=lambda alt: (len(alt["changes"]), alt["changes"].get("max_total_cents", 0), alt["exampleProductId"]))
    if accepted:
        best = accepted[0]
        q = best["quote"]
        answer = f"找到{len(accepted)}个符合条件的演示方案。最低到手价是「{best['merchant']['name']}」的{best['title']}，{q['quantity']}件共¥{q['totalCents']/100:.2f}，预计{q['etaMinutes']}分钟送达。已计入包装、配送与适用优惠；有效期以卡片为准，库存未锁定。"
    else:
        names = {"budget": "到手价超预算", "delivery_time": "预计送达超时", "minimum_order": "未达到起送价",
                 "stock": "库存不足", "quote_expired": "配送规则已过期", "unknown_price_or_stock": "价格或库存未知", "unknown_delivery_quote": "配送报价信息不完整"}
        reason = "、".join(names.get(key, key) for key in rejected) or "该区域没有满足商品名称、品类和数量要求的可配送商品"
        answer = f"当前演示数据中没有满足全部条件的方案：{reason}。未自动放宽条件。"
        if alternatives:
            first = alternatives[0]["changes"]
            changes = []
            if "max_total_cents" in first:
                changes.append(f"预算提高到¥{first['max_total_cents']/100:.2f}")
            if "max_delivery_minutes" in first:
                changes.append(f"送达限制改为{first['max_delivery_minutes']}分钟")
            answer += "你是否愿意" + "并且".join(changes) + "？请明确修改条件后重新查询。"
        else:
            answer += "可修改区域、品类或数量后再查询；这不代表现实商家无货。"
    return {"items": accepted[:3], "suggestions": alternatives[:3] if not accepted else [], "rejected": dict(rejected),
            "status": "completed" if accepted else "no_match", "answer": answer}


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
