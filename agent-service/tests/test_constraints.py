import unittest

from pydantic import ValidationError
from app.constraints import Intent, durable_updates, filter_candidates, local_intent


class ConstraintTests(unittest.TestCase):
    def test_explicit_source_change_uses_the_new_source(self):
        intent = local_intent("从商品换成直播", {})
        self.assertEqual(intent["preferred_source"], "live")
        self.assertEqual(intent["scene"], "video_feed")

    def test_current_turn_overrides_history_and_long_term(self):
        intent = local_intent("这次预算200元，推荐家居商品", {"max_price": "900", "preferred_category": "digital"},
                              [{"role": "user", "content": "预算500的运动商品，不要广告"}])
        self.assertEqual((intent["max_price"], intent["preferred_category"], intent["preferred_source"]), (200, "home", "goods"))
        self.assertTrue(intent["exclude_ads"])

    def test_followup_preserves_unmodified_constraints(self):
        intent = local_intent("预算改为199.50", {}, [{"role": "user", "content": "推荐数码商品，不要广告，预算500"}])
        self.assertEqual(intent["max_price"], 199.5)
        self.assertEqual(intent["preferred_category"], "digital")
        self.assertTrue(intent["exclude_ads"])

    def test_explicit_clears_override_memory(self):
        intent = local_intent("不限预算，不限品类，不限来源，允许广告", {
            "max_price": "100", "preferred_source": "goods", "preferred_category": "digital", "exclude_ads": "true"})
        for field in ["max_price", "preferred_source", "preferred_category"]:
            self.assertIsNone(intent[field])
        self.assertFalse(intent["exclude_ads"])

    def test_only_explicit_durable_preferences_are_written(self):
        self.assertEqual(durable_updates("这次预算100，推荐家居商品"), {})
        self.assertEqual(durable_updates("预算100，推荐家居商品"), {})
        self.assertEqual(durable_updates("记住这次预算100"), {})
        self.assertEqual(durable_updates("不要记住我的预算100"), {})
        self.assertEqual(durable_updates("以后允许广告，不限预算"), {"exclude_ads": False, "max_price": None})

    def test_invalid_legacy_memory_is_ignored(self):
        intent = local_intent("推荐家居商品", {"max_price": "NaN", "last_scene": "invalid"})
        self.assertIsNone(intent["max_price"])
        self.assertEqual(intent["scene"], "mall")

    def test_decimal_budget_does_not_truncate_prices(self):
        intent = local_intent("推荐数码商品，预算199.50", {})
        items = [{"source": "goods", "category": "digital", "attrs": {"price": price}} for price in ["199.50", 199.9, 200]]
        accepted, reasons = filter_candidates(items, intent)
        self.assertEqual(len(accepted), 1)
        self.assertEqual(reasons, {"max_price": 2})

    def test_unknown_or_invalid_prices_fail_closed(self):
        intent = local_intent("推荐商品预算0", {})
        items = [{"source": "goods", "attrs": {"price": price}} for price in [None, "bad", float("nan"), -1, True, 0]]
        accepted, reasons = filter_candidates(items, intent)
        self.assertEqual(accepted, [items[-1]])
        self.assertEqual(reasons, {"unknown_price": 5})

    def test_source_is_a_hard_constraint_even_when_no_matches(self):
        intent = local_intent("推荐运动直播", {})
        original = dict(intent)
        accepted, reasons = filter_candidates([{"source": "goods", "category": "sports"}], intent)
        self.assertEqual(accepted, [])
        self.assertEqual(reasons, {"preferred_source": 1})
        self.assertEqual(intent, original)

    def test_model_schema_rejects_invalid_types_ranges_and_fields(self):
        for payload in [{"limit": 0}, {"limit": 11}, {"max_price": -1}, {"max_price": float("inf")},
                        {"exclude_ads": "false"}, {"preferred_source": "sql"}, {"command": "anything"}]:
            with self.subTest(payload=payload), self.assertRaises(ValidationError):
                Intent(**payload)


if __name__ == "__main__":
    unittest.main()
