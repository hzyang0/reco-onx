#!/usr/bin/env python3
"""Run fixed local-life HTTP regression tasks against the seeded demo service."""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import statistics
import time
import urllib.error
import urllib.request
import uuid


def check_items(result, expected):
    errors, valid = [], 0
    intent = result.get("intent", {})
    for key, value in expected.get("intent", {}).items():
        if intent.get(key) != value:
            errors.append(f"intent:{key}")
    items = result.get("items", [])
    if expected.get("status") == "completed" and not items:
        errors.append("expected nonempty offers")
    if expected.get("status") in {"no_match", "clarification"} and items:
        errors.append("unexpected offers")
    if expected.get("firstId") is not None and (not items or items[0].get("itemId") != expected["firstId"]):
        errors.append("wrong best product")
    if expected.get("firstTotal") is not None and (not items or items[0].get("quote", {}).get("totalCents") != expected["firstTotal"]):
        errors.append("wrong delivered price")
    previous_total = -1
    for item in items:
        problems, q = [], item.get("quote", {})
        cents = [q.get(k) for k in ["goodsSubtotalCents", "packagingFeeCents", "deliveryFeeCents", "discountCents", "totalCents"]]
        if any(type(n) is not int or n < 0 for n in cents):
            problems.append("money type/range")
        else:
            subtotal, packaging, delivery, discount, total = cents
            if subtotal + packaging + delivery - discount != total:
                problems.append("quote arithmetic")
            if total < previous_total:
                problems.append("delivered-price ordering")
            previous_total = total
            if intent.get("max_total_cents") is not None and total > intent["max_total_cents"]:
                problems.append("budget violated")
        if q.get("quantity") != intent.get("quantity") or item.get("category") != intent.get("category") or q.get("zone") != intent.get("zone"):
            problems.append("quantity/category/zone violated")
        if intent.get("product_query") and intent["product_query"].casefold() not in item.get("title", "").casefold():
            problems.append("requested product violated")
        if intent.get("max_delivery_minutes") is not None and q.get("etaMinutes", math.inf) > intent["max_delivery_minutes"]:
            problems.append("delivery time violated")
        if datetime.fromisoformat(q["validUntil"]) <= datetime.now(timezone.utc):
            problems.append("expired quote")
        if q.get("dataSource") != "synthetic-campus-v1":
            problems.append("unexpected data provenance")
        valid += not problems
        errors.extend(problems)
    if any(not suggestion.get("requiresConfirmation") for suggestion in result.get("suggestions", [])):
        errors.append("relaxation without confirmation")
    return errors, valid, len(items)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--cases", type=Path, default=Path(__file__).resolve().parents[1] / "agent-service/evaluation/local_life_cases.json")
    parser.add_argument("--output", type=Path, default=Path("output/local-life-evaluation.json"))
    args = parser.parse_args()
    suite = json.loads(args.cases.read_text())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    run_id = uuid.uuid4().hex[:12]
    results, elapsed_values = [], []
    total_items = valid_items = 0
    for case_index, case in enumerate(suite["cases"]):
        bodies, responses = [], []
        for turn_index, expected in enumerate(case["turns"]):
            if "replayOf" in expected:
                body = dict(bodies[expected["replayOf"]])
                if "message" in expected:
                    body["message"] = expected["message"]
            else:
                body = {"user_id": suite["userId"], "session_id": f"life-eval-{run_id}-{case_index}",
                        "request_id": f"request-{run_id}-{turn_index}", "message": expected["message"]}
            bodies.append(body)
            began, errors, result = time.perf_counter(), [], {}
            try:
                request = urllib.request.Request(args.base_url.rstrip("/") + "/api/local-life/recommend", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
                try:
                    with opener.open(request, timeout=30) as response:
                        status, result = response.status, json.load(response)
                except urllib.error.HTTPError as error:
                    status = error.code
                if status != expected.get("httpStatus", 200):
                    errors.append(f"HTTP {status}")
                elif status == 200:
                    if result.get("status") != expected["status"]:
                        errors.append(f"status:{result.get('status')}")
                    failures, valid, count = check_items(result, expected)
                    errors.extend(failures)
                    total_items += count
                    valid_items += valid
                    if "replayOf" in expected and (not result.get("replayed") or result.get("taskId") != responses[expected["replayOf"]].get("taskId")):
                        errors.append("idempotency failure")
            except Exception as error:
                errors.append(type(error).__name__)
            elapsed = round((time.perf_counter() - began) * 1000, 2)
            elapsed_values.append(elapsed)
            responses.append(result)
            results.append({"case": case["id"], "turn": turn_index + 1, "passed": not errors, "errors": errors,
                            "elapsedMs": elapsed, "status": result.get("status"), "returnedItems": len(result.get("items", [])), "metrics": result.get("metrics")})
    passed = sum(row["passed"] for row in results)
    metrics = [r["metrics"] for r in results if r["metrics"] is not None]
    report = {"schemaVersion": 1, "createdAt": datetime.now(timezone.utc).isoformat(), "runId": run_id,
              "datasetVersion": suite["version"], "scope": suite["scope"], "cases": len(suite["cases"]),
              "requests": len(results), "passed": passed, "requestSuccessRate": passed / len(results),
              "returnedItems": total_items, "constraintSatisfiedItems": valid_items,
              "constraintSatisfactionRate": valid_items / total_items if total_items else None,
              "latencyMs": {"p50": statistics.median(elapsed_values), "p95": sorted(elapsed_values)[math.ceil(.95 * len(elapsed_values)) - 1]},
              "llmCalls": sum(m["llmCalls"] for m in metrics), "modelCostUsd": sum(m["modelCostUsd"] for m in metrics), "results": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "results"}, ensure_ascii=False, indent=2))
    if passed != len(results):
        print(json.dumps([r for r in results if not r["passed"]], ensure_ascii=False, indent=2))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
