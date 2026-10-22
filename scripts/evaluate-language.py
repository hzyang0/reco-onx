#!/usr/bin/env python3
"""Evaluate a frozen language challenge against independent expected constraints.

No LLM judge: explicit labels, money arithmetic and product facts are checked.
Expected failures remain visible; exit 1 means the challenge is not fully passed.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def assess(result, expected):
    errors, valid_items = [], 0
    gold, actual = expected.get('intent', {}), result.get('intent', {})
    if result.get('status') != expected['status']:
        errors.append('wrong_status')
    for key, value in gold.items():
        if actual.get(key) != value:
            errors.append('intent:' + key)
    items = result.get('items', [])
    if expected['status'] == 'completed' and not items:
        errors.append('missing_offers')
    previous_total = -1
    for item in items:
        failures, quote = [], item.get('quote', {})
        if expected['status'] != 'completed':
            failures.append('offers_without_satisfied_request')
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
        amounts = [quote.get(k) for k in ('goodsSubtotalCents', 'packagingFeeCents', 'deliveryFeeCents', 'discountCents', 'totalCents')]
        if any(type(v) is not int or v < 0 for v in amounts):
            failures.append('invalid_money')
        else:
            goods, package, delivery, discount, total = amounts
            if goods + package + delivery - discount != total:
                failures.append('money_arithmetic')
            if total < previous_total:
                failures.append('price_order')
            previous_total = total
        try:
            if datetime.fromisoformat(quote['validUntil']) <= datetime.now(timezone.utc):
                failures.append('expired_quote')
        except (KeyError, ValueError, TypeError):
            failures.append('invalid_quote_expiry')
        valid_items += not failures
        errors.extend('offer:' + failure for failure in failures)
    if expected.get('firstId') is not None and (not items or items[0].get('itemId') != expected['firstId']):
        errors.append('wrong_winner')
    if expected.get('firstTotal') is not None and (not items or items[0].get('quote', {}).get('totalCents') != expected['firstTotal']):
        errors.append('wrong_total')
    return sorted(set(errors)), valid_items


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:18081')
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('output/language-evaluation.json'))
    args = parser.parse_args()
    raw = args.cases.read_bytes()
    suite, run_id = json.loads(raw), uuid.uuid4().hex[:12]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    rows, successful_cases, times = [], 0, []
    for index, case in enumerate(suite['cases']):
        case_passed = True
        for turn, expected in enumerate(case['turns']):
            body = {'user_id': suite['userId'], 'session_id': f'lang-{run_id}-{index}',
                    'request_id': f'lang-{run_id}-{turn}', 'message': expected['message']}
            started, result, errors, valid = time.perf_counter(), {}, [], 0
            try:
                request = urllib.request.Request(args.base_url.rstrip('/') + '/api/local-life/recommend',
                                                 data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
                with opener.open(request, timeout=30) as response:
                    result = json.load(response)
                errors, valid = assess(result, expected)
            except (urllib.error.URLError, OSError, ValueError) as error:
                errors = ['transport_or_json:' + type(error).__name__]
            elapsed = round((time.perf_counter() - started) * 1000, 2)
            times.append(elapsed)
            case_passed &= not errors
            rows.append({'case': case['id'], 'category': case.get('category', 'other'), 'turn': turn + 1,
                         'expected': expected, 'actual': result, 'passed': not errors, 'errors': errors,
                         'validItemsAgainstLabels': valid, 'elapsedMs': elapsed})
        successful_cases += case_passed
    returned = sum(len(r['actual'].get('items', [])) for r in rows)
    valid = sum(r['validItemsAgainstLabels'] for r in rows)
    clarification = [r for r in rows if r['expected']['status'] == 'clarification']
    model_costs = [r['actual'].get('metrics', {}).get('modelCostUsd') for r in rows]
    try:
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    except subprocess.CalledProcessError:
        revision = 'unknown'
    summary = {'cases': len(suite['cases']), 'successfulCases': successful_cases,
               'taskSuccessRate': successful_cases / len(suite['cases']),
               'requests': len(rows), 'passedRequests': sum(r['passed'] for r in rows),
               'requestSuccessRate': sum(r['passed'] for r in rows) / len(rows),
               'returnedItems': returned, 'validItemsAgainstLabels': valid,
               'constraintSatisfactionRateAgainstLabels': valid / returned if returned else None,
               'clarificationCases': len(clarification), 'correctClarifications': sum(r['passed'] for r in clarification),
               'latencyMs': {'p50': statistics.median(times), 'p95': sorted(times)[math.ceil(len(times) * .95) - 1]},
               'llmCalls': sum(r['actual'].get('metrics', {}).get('llmCalls', 0) for r in rows),
               'modelCostUsd': sum(model_costs) if all(isinstance(cost, (int, float)) for cost in model_costs) else None,
               'modelCostScope': 'service-reported model costs; excludes evaluation authoring and infrastructure'}
    categories = {}
    for row in rows:
        counter = categories.setdefault(row['category'], {'requests': 0, 'passed': 0})
        counter['requests'] += 1
        counter['passed'] += row['passed']
    report = {'schemaVersion': 1, 'createdAt': datetime.now(timezone.utc).isoformat(), 'runId': run_id,
              'sourceRevision': revision, 'datasetVersion': suite['version'],
              'datasetSha256': hashlib.sha256(raw).hexdigest(), 'scope': suite['scope'],
              'summary': summary, 'categories': categories, 'results': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if all(r['passed'] for r in rows) else 1


if __name__ == '__main__':
    raise SystemExit(main())
