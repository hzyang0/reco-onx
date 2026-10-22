from copy import deepcopy
from datetime import datetime, timedelta, timezone
import unittest

from pydantic import ValidationError
from app.local_life import LocalConstraints, QuoteRejected, local_graph, make_quote, merge_constraints, parse_constraints, remember_explicitly
from app.local_life_store import expire_quotes


NOW = datetime(2026, 10, 21, 0, 0, tzinfo=timezone.utc)
PRODUCT = {"product_id": 101, "merchant_id": 1, "merchant_name": "样例店A", "title": "美式", "category": "coffee",
           "unit_price_cents": 800, "packaging_cents": 100, "stock": 10, "enabled": True, "data_source": "synthetic-test"}
RULE = {"zone": "north", "delivery_cents": 800, "eta_minutes": 18, "minimum_goods_cents": 800,
        "free_delivery_goods_cents": 5000, "coupon_threshold_cents": 2000, "coupon_discount_cents": 300, "valid_until": None}


class FixtureTools:
    def __init__(self):
        self.calls = []

    def search_merchants(self, zone):
        self.calls.append(("merchants", zone))
        return [{"merchant_id": 1}, {"merchant_id": 2}]

    def search_products(self, merchant_ids, category, quantity):
        self.calls.append(("products", category, quantity))
        return [deepcopy(PRODUCT), PRODUCT | {"product_id": 201, "merchant_id": 2, "merchant_name": "样例店B", "unit_price_cents": 1200, "packaging_cents": 0}]

    def quote_delivery(self, merchant_id, zone):
        return RULE if merchant_id == 1 else RULE | {"delivery_cents": 200, "eta_minutes": 25}


class QuoteTests(unittest.TestCase):
    def test_integer_cent_breakdown(self):
        q = make_quote(PRODUCT, RULE, 1, NOW)
        self.assertEqual(q["totalCents"], 1700)
        self.assertEqual(q["goodsSubtotalCents"] + q["packagingFeeCents"] + q["deliveryFeeCents"] - q["discountCents"], q["totalCents"])

    def test_coupon_only_at_threshold_and_no_negative_total(self):
        p = PRODUCT | {"unit_price_cents": 1000}
        self.assertEqual(make_quote(p, RULE, 1, NOW)["discountCents"], 0)
        self.assertEqual(make_quote(p, RULE, 2, NOW)["discountCents"], 300)
        q = make_quote(p, RULE | {"coupon_discount_cents": 99999}, 2, NOW)
        self.assertEqual(q["discountCents"], 2000)
        self.assertGreaterEqual(q["totalCents"], 0)

    def test_free_delivery_threshold_uses_goods_subtotal(self):
        p = PRODUCT | {"unit_price_cents": 1000}
        self.assertEqual(make_quote(p, RULE, 4, NOW)["deliveryFeeCents"], 800)
        self.assertEqual(make_quote(p, RULE, 5, NOW)["deliveryFeeCents"], 0)

    def test_minimum_order_not_met(self):
        with self.assertRaisesRegex(QuoteRejected, "minimum_order"):
            make_quote(PRODUCT, RULE | {"minimum_goods_cents": 1000}, 1, NOW)

    def test_stock_and_quantity_fail_closed(self):
        for qty in [0, 11, True]:
            with self.subTest(qty=qty), self.assertRaises(QuoteRejected):
                make_quote(PRODUCT, RULE, qty, NOW)
        with self.assertRaisesRegex(QuoteRejected, "stock"):
            make_quote(PRODUCT | {"stock": 0}, RULE, 1, NOW)

    def test_missing_or_invalid_price_and_delivery_are_not_zero(self):
        for key in ["unit_price_cents", "packaging_cents", "stock"]:
            with self.subTest(key=key), self.assertRaises(QuoteRejected):
                make_quote(PRODUCT | {key: None}, RULE, 1, NOW)
        for key in ["delivery_cents", "eta_minutes", "minimum_goods_cents", "coupon_discount_cents"]:
            with self.subTest(key=key), self.assertRaises(QuoteRejected):
                make_quote(PRODUCT, RULE | {key: None}, 1, NOW)

    def test_expired_policy_is_not_quoted(self):
        with self.assertRaisesRegex(QuoteRejected, "quote_expired"):
            make_quote(PRODUCT, RULE | {"valid_until": NOW}, 1, NOW)

    def test_quote_expires_at_earlier_policy_expiry(self):
        deadline = NOW + timedelta(seconds=30)
        q = make_quote(PRODUCT, RULE | {"valid_until": deadline}, 1, NOW)
        self.assertEqual(datetime.fromisoformat(q["validUntil"]), deadline)

    def test_expired_task_replay_cannot_return_old_items(self):
        item = {"quote": make_quote(PRODUCT, RULE, 1, NOW)}
        result = {"status": "completed", "items": [item]}
        expired = expire_quotes(result, NOW + timedelta(seconds=120))
        self.assertEqual(expired["status"], "quote_expired")
        self.assertEqual(expired["items"], [])
        self.assertEqual(len(result["items"]), 1)  # persisted historical result is immutable


class LocalPlanTests(unittest.TestCase):
    def test_parse_full_request_and_exact_decimal(self):
        patch, issues = parse_constraints("北区2杯咖啡，预算29.99元，30分钟内送达")
        self.assertEqual(issues, [])
        self.assertEqual(patch, {"zone": "north", "category": "coffee", "product_query": None, "quantity": 2, "max_total_cents": 2999, "max_delivery_minutes": 30})

    def test_chinese_numbers_and_product_name_are_hard_constraints(self):
        patch, issues = parse_constraints("北区两杯拿铁，预算二十九元，三十分钟内送达")
        self.assertFalse(issues)
        self.assertEqual((patch["quantity"], patch["max_total_cents"], patch["max_delivery_minutes"], patch["product_query"]), (2, 2900, 30, "拿铁"))

    def test_unparsed_or_ambiguous_requirements_clarify(self):
        for text in ["预算便宜点", "预算二百元", "1小时送达", "北区南区咖啡", "咖啡和简餐",
                     "不要拿铁", "几杯咖啡", "1.5杯咖啡", "-2杯咖啡", "-5分钟送达", "预算20到30元"]:
            with self.subTest(text=text):
                self.assertTrue(parse_constraints(text)[1])

    def test_product_constraint_persists_and_can_be_cleared(self):
        previous = {"category": "coffee", "product_query": "拿铁"}
        self.assertEqual(merge_constraints("预算30", previous, {})[0]["product_query"], "拿铁")
        for text, structured in [("不限款式", None), ("改成简餐", None), ("更换品类", {"category": "meal"})]:
            with self.subTest(text=text):
                self.assertIsNone(merge_constraints(text, previous, {}, structured)[0]["product_query"])

    def test_session_constraints_survive_without_chat_messages(self):
        c, sources, issues = merge_constraints("预算改为20", {"zone": "north", "category": "coffee", "max_delivery_minutes": 30}, {"max_total_cents": 5000})
        self.assertEqual((c["max_total_cents"], c["max_delivery_minutes"]), (2000, 30))
        self.assertEqual(sources["max_total_cents"], "current")
        self.assertEqual(sources["zone"], "session")
        self.assertFalse(issues)

    def test_clear_overrides_session_and_long_term(self):
        for message in ["不限预算，不限时间", "预算不限，时间不限", "取消预算，取消送达限制"]:
            with self.subTest(message=message):
                c, _, issues = merge_constraints(message, {"max_total_cents": 1000}, {"max_delivery_minutes": 20})
                self.assertIsNone(c["max_total_cents"])
                self.assertIsNone(c["max_delivery_minutes"])
                self.assertFalse(issues)

    def test_unsupported_constraints_require_clarification(self):
        for message in ["西区咖啡", "送到北京的咖啡", "北区无糖咖啡", "不要咖啡", "北区手机", "北区咖啡预算-1", "预算1.001"]:
            with self.subTest(message=message):
                self.assertTrue(parse_constraints(message)[1])

    def test_durable_preference_requires_explicit_non_temporary_intent(self):
        self.assertTrue(remember_explicitly("以后送到北区"))
        for message in ["北区咖啡预算30", "记住这次预算30", "不要记住北区"]:
            self.assertFalse(remember_explicitly(message))

    def test_structured_patch_has_priority(self):
        c, _, _ = merge_constraints("预算30", {}, {}, {"zone": "south", "category": "meal", "max_total_cents": 2500})
        self.assertEqual(c["max_total_cents"], 2500)

    def test_strict_structured_schema(self):
        for value in [{"zone": "west"}, {"max_total_cents": 10.5}, {"quantity": True}, {"quantity": 0}, {"temperature": "hot"}]:
            with self.subTest(value=value), self.assertRaises(ValidationError):
                LocalConstraints(**value)


class LocalGraphTests(unittest.TestCase):
    def test_unavailable_named_product_cannot_be_substituted(self):
        state, _ = self.run_graph(product_query="拿铁")
        self.assertEqual(state["status"], "no_match")
        self.assertEqual(state["items"], [])
        self.assertEqual(state["suggestions"], [])

    def run_graph(self, **constraints):
        tools = FixtureTools()
        state = local_graph.invoke({"constraints": LocalConstraints(zone="north", category="coffee", **constraints).model_dump(), "tools": tools, "now": NOW})
        return state, tools

    def test_sort_by_delivered_cost_not_sticker_price(self):
        state, tools = self.run_graph(max_total_cents=2000)
        self.assertEqual([i["itemId"] for i in state["items"]], [201, 101])
        self.assertEqual(state["items"][0]["quote"]["totalCents"], 1400)
        self.assertIn(("products", "coffee", 1), tools.calls)

    def test_hard_eta_can_select_more_expensive_option(self):
        state, _ = self.run_graph(max_delivery_minutes=20)
        self.assertEqual([i["itemId"] for i in state["items"]], [101])

    def test_no_match_preserves_conditions_and_suggests_explicit_changes(self):
        state, _ = self.run_graph(max_total_cents=1600, max_delivery_minutes=20)
        self.assertEqual(state["status"], "no_match")
        self.assertEqual(state["items"], [])
        self.assertEqual(state["constraints"]["max_total_cents"], 1600)
        self.assertEqual(state["constraints"]["max_delivery_minutes"], 20)
        self.assertTrue(all(s["requiresConfirmation"] for s in state["suggestions"]))

    def test_exact_budget_boundary(self):
        state, _ = self.run_graph(max_total_cents=1400)
        self.assertEqual([i["itemId"] for i in state["items"]], [201])
        state, _ = self.run_graph(max_total_cents=1399)
        self.assertEqual(state["status"], "no_match")


if __name__ == "__main__":
    unittest.main()
