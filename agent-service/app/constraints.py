"""Deterministic constraint policy shared by local and model planners.

Precedence: durable preference < recent user turns < this turn. Neither an empty
candidate set nor a model failure authorizes relaxing a constraint.
"""
import math
import re
from collections import Counter
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool


class Intent(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    scene: Literal["mall", "video_feed", "buy_first"] = "mall"
    preferred_source: Literal["goods", "live", "ad"] | None = None
    preferred_category: Literal["digital", "home", "food", "fashion", "sports", "beauty"] | None = None
    max_price: float | None = Field(default=None, ge=0, le=1_000_000)
    exclude_ads: StrictBool = False
    limit: int = Field(default=5, ge=1, le=10)
    needs_clarification: StrictBool = False


def explicit_constraints(message: str) -> dict[str, Any]:
    """Only emit recognized fields, including explicit clears/false values."""
    text = message.lower().strip()
    patch: dict[str, Any] = {}
    categories = {"数码": "digital", "电脑": "digital", "耳机": "digital", "家居": "home",
                  "收纳": "home", "美食": "food", "咖啡": "food", "穿搭": "fashion",
                  "运动": "sports", "跑步": "sports", "露营": "sports", "美妆": "beauty", "护肤": "beauty"}
    matches = [(text.rfind(word), category) for word, category in categories.items() if word in text]
    if matches:
        patch["preferred_category"] = max(matches)[1]
    if any(word in text for word in ["不限品类", "所有品类"]):
        patch["preferred_category"] = None
    sources = [(text.rfind(word), source) for word, source in
               {"直播": "live", "视频": "live", "商品": "goods", "买": "goods", "商城": "goods"}.items() if word in text]
    if sources:
        source = max(sources)[1]
        patch.update(scene="video_feed" if source == "live" else "mall", preferred_source=source)
    if "首页" in text or "综合" in text:
        patch["scene"] = "buy_first"
    if any(word in text for word in ["不限来源", "不限类型", "所有类型"]):
        patch["preferred_source"] = None
    budget = re.search(r"(?:预算|不超过|低于|以内)\s*(?:改为|改成|提高到|降到)?\s*[¥￥]?\s*(-?\d+(?:\.\d{1,2})?)", text)
    if not budget:
        budget = re.search(r"(-?\d+(?:\.\d{1,2})?)\s*元?以内", text)
    if budget:
        patch["max_price"] = float(budget.group(1))
    if any(word in text for word in ["不限预算", "取消预算", "不限制价格"]):
        patch["max_price"] = None
    if any(word in text for word in ["不要广告", "无广告", "不看广告"]):
        patch["exclude_ads"] = True
    if any(word in text for word in ["允许广告", "可以有广告", "可以看广告"]):
        patch["exclude_ads"] = False
    return patch


def durable_updates(message: str) -> dict[str, Any]:
    if any(word in message for word in ["不要记住", "别记住", "不用记住", "不必记住", "不要保存"]):
        return {}
    if any(word in message for word in ["这次", "本次", "今天", "临时"]):
        return {}
    if not any(word in message for word in ["记住", "以后", "长期偏好"]):
        return {}
    return explicit_constraints(message)


def local_intent(message: str, remembered: dict[str, str], short: list[dict[str, str]] | None = None) -> dict[str, Any]:
    merged: dict[str, Any] = {}
    # Old or manually edited memory must not crash the planner.
    for key, value in remembered.items():
        field = "scene" if key == "last_scene" else key
        if field not in {"scene", "preferred_category", "preferred_source", "max_price", "exclude_ads"}:
            continue
        value = value == "true" if field == "exclude_ads" else value
        try:
            merged[field] = getattr(Intent(**{field: value}), field)
        except ValueError:
            continue
    for turn in short or []:
        if turn.get("role") == "user":
            merged.update(explicit_constraints(turn["content"]))
    merged.update(explicit_constraints(message))
    merged["needs_clarification"] = not merged and (
        message.strip() in {"推荐", "推荐一下", "帮我推荐", "给我推荐", "来点推荐"} or len(message.strip()) < 3)
    return Intent(**merged).model_dump()


def rejection_reasons(item: dict[str, Any], intent: dict[str, Any]) -> list[str]:
    reasons = []
    if intent.get("exclude_ads") and item.get("source") == "ad":
        reasons.append("exclude_ads")
    if intent.get("preferred_source") and item.get("source") != intent["preferred_source"]:
        reasons.append("preferred_source")
    if intent.get("preferred_category") and item.get("category") != intent["preferred_category"]:
        reasons.append("preferred_category")
    if intent.get("max_price") is not None:
        # A missing/invalid price is unknown, never a free product. The current
        # backend supplies goods prices only, not delivery-inclusive quotes.
        raw = item.get("attrs", {}).get("price")
        try:
            price = float(raw)
            if isinstance(raw, bool) or not math.isfinite(price) or price < 0:
                raise ValueError("invalid price")
        except (TypeError, ValueError):
            reasons.append("unknown_price")
        else:
            if price > intent["max_price"]:
                reasons.append("max_price")
    return reasons


def filter_candidates(items: list[dict[str, Any]], intent: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, int]]:
    accepted, rejected = [], Counter()
    for item in items:
        reasons = rejection_reasons(item, intent)
        if reasons:
            rejected.update(reasons)
        else:
            accepted.append(item)
    return accepted[:intent["limit"]], dict(rejected)
