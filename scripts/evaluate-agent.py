#!/usr/bin/env python3
"""Small, live HTTP regression set; uses seeded demo data, never a production benchmark."""
import argparse
import datetime
import json
import math
import statistics
import time
import urllib.request
import uuid
from decimal import Decimal, InvalidOperation
from pathlib import Path


CASES = [
    {"id": "home-goods", "session": "home", "user": 123, "message": "推荐家居商品，不限预算，不要广告",
     "status": "completed", "expected": {"preferred_category": "home", "preferred_source": "goods", "max_price": None, "exclude_ads": True}},
    {"id": "tighten-budget", "session": "home", "user": 123, "message": "预算改为0",
     "status": "no_match", "expected": {"preferred_category": "home", "preferred_source": "goods", "max_price": 0, "exclude_ads": True}},
    {"id": "explicitly-clear-budget", "session": "home", "user": 123, "message": "不限预算，允许广告",
     "status": "completed", "expected": {"preferred_category": "home", "preferred_source": "goods", "max_price": None, "exclude_ads": False}},
    {"id": "digital-budget", "session": "digital", "user": 456, "message": "推荐数码商品，预算500，不要广告",
     "status": "completed", "expected": {"preferred_category": "digital", "preferred_source": "goods", "max_price": 500, "exclude_ads": True}},
    {"id": "live-source", "session": "live", "user": 123, "message": "推荐家居直播，不限预算，不要广告",
     "status": "completed", "expected": {"preferred_category": "home", "preferred_source": "live", "max_price": None, "exclude_ads": True}},
    {"id": "general-after-recommendation", "session": "home", "user": 123, "message": "什么是召回？",
     "status": "completed", "expected": {}, "general": True},
]


def violations(item, expected):
    errors = []
    for key, item_key in [("preferred_source", "source"), ("preferred_category", "category")]:
        if expected.get(key) and item.get(item_key) != expected[key]:
            errors.append(key)
    if expected.get("exclude_ads") and item.get("source") == "ad":
        errors.append("exclude_ads")
    if expected.get("max_price") is not None:
        price = item.get("attrs", {}).get("price")
        # Java's attrs contract is Map<String, String>; accept numeric strings
        # while checking against the expected budget independently of the Agent.
        try:
            amount = Decimal(str(price))
            valid = amount.is_finite() and 0 <= amount <= Decimal(str(expected["max_price"]))
        except InvalidOperation:
            valid = False
        if not valid:
            errors.append("max_price")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--output", type=Path, default=Path("output/agent-evaluation.json"))
    args = parser.parse_args()
    run_id = uuid.uuid4().hex[:12]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    results, latencies, costs = [], [], []
    item_count = valid_count = model_calls = 0
    for case in CASES:
        began = time.perf_counter()
        errors, result = [], {}
        try:
            request = urllib.request.Request(args.base_url.rstrip("/") + "/api/chat", data=json.dumps({
                "user_id": case["user"], "session_id": f"eval-{run_id}-{case['session']}", "message": case["message"]
            }).encode(), headers={"Content-Type": "application/json"})
            with opener.open(request, timeout=30) as response:
                result = json.load(response)
            if result.get("status") != case["status"]:
                errors.append(f"expected status {case['status']}, got {result.get('status')}")
            for key, value in case["expected"].items():
                if result.get("intent", {}).get(key) != value:
                    errors.append(f"intent mismatch: {key}")
            items = result.get("items", [])
            if case.get("general"):
                if items or result.get("recommendationRequestId"):
                    errors.append("general answer leaked recommendation state")
            elif case["status"] == "completed" and not items:
                errors.append("expected nonempty recommendations")
            elif case["status"] == "no_match" and items:
                errors.append("no-match result must be empty")
            for item in items:
                item_count += 1
                problems = violations(item, case["expected"])
                valid_count += not problems
                errors.extend(problems)
            metrics = result.get("metrics", {})
            model_calls += metrics.get("llmCalls", 0)
            costs.append(metrics.get("modelCostUsd"))
        except Exception as exc:
            errors.append(type(exc).__name__)
            costs.append(None)
        elapsed = round((time.perf_counter() - began) * 1000, 2)
        latencies.append(elapsed)
        results.append({"case": case["id"], "passed": not errors, "errors": errors, "elapsedMs": elapsed,
                        "status": result.get("status"), "planner": result.get("planner"), "returnedItems": len(result.get("items", []))})
    passed = sum(row["passed"] for row in results)
    report = {"schemaVersion": 1, "runId": run_id, "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scope": "6 live demo regression requests; seeded catalog; not a held-out model evaluation or load test",
              "total": len(results), "passed": passed, "taskSuccessRate": passed / len(results),
              "returnedItems": item_count, "constraintSatisfiedItems": valid_count,
              "constraintSatisfactionRate": valid_count / item_count if item_count else None,
              "latencyMs": {"p50": statistics.median(latencies), "p95": sorted(latencies)[math.ceil(.95 * len(latencies)) - 1]},
              "llmCalls": model_calls, "modelCostUsd": sum(costs) if all(c is not None for c in costs) else None,
              "results": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
