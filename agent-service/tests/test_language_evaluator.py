"""Ensure an agent cannot receive a perfect score by dropping a user condition."""
from datetime import datetime, timedelta, timezone
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[2] / 'scripts/evaluate-language.py'
spec = importlib.util.spec_from_file_location('language_evaluator', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LanguageEvaluatorTests(unittest.TestCase):
    def result(self):
        return {'status': 'completed', 'intent': {'max_total_cents': None}, 'items': [{
            'title': '美式', 'category': 'coffee', 'quote': {'goodsSubtotalCents': 1200,
            'packagingFeeCents': 0, 'deliveryFeeCents': 200, 'discountCents': 0, 'totalCents': 1400,
            'validUntil': (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat()}}]}

    def test_ignored_budget_is_checked_against_external_label(self):
        errors, valid = module.assess(self.result(), {'status': 'completed', 'intent': {'max_total_cents': 1000}})
        self.assertIn('intent:max_total_cents', errors)
        self.assertIn('offer:budget', errors)
        self.assertEqual(valid, 0)

    def test_clarification_cannot_be_counted_as_successful_offers(self):
        errors, valid = module.assess(self.result(), {'status': 'clarification'})
        self.assertIn('wrong_status', errors)
        self.assertEqual(valid, 0)

    def test_empty_result_is_not_success_when_solution_is_expected(self):
        errors, valid = module.assess({'status': 'completed', 'items': []}, {'status': 'completed'})
        self.assertIn('missing_offers', errors)
        self.assertEqual(valid, 0)
