"""Load an import plan into a RealityCheck instance through its public API, then backtest it (spec 11, task 11).

Usage:
    python -I tools/import/load.py <plan.json> <base-url> <results.json> [--fork 2026-10-01 ...] [--horizon DAYS]
        [--names FILE] [--factory ID]

Run it against a private, local instance only (see tools/import/README.md): the plan holds real dates. With
--names (one real name per line), every request body is checked first and the load stops before sending
anything that contains one of them. Use 127.0.0.1 rather than localhost in the URL: on Windows, localhost
tries IPv6 first and adds about two seconds to every request.

The factory works Monday to Saturday, 09:00 to 18:00 with an hour's break, in its own time zone: the real
factory's shifts are not in the extracts, and only working stages use the calendar.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, time
from pathlib import Path
from zoneinfo import ZoneInfo


class NameLeak(Exception):
    pass


class Api:
    """JSON over HTTP. `send` is replaceable so tests can record requests without a server."""

    def __init__(self, base_url: str, names: list[str] | None = None, send=None):
        self.base = base_url.rstrip("/")
        self.names = [n.strip().lower() for n in (names or []) if len(n.strip()) >= 3]
        self.send = send or self._send

    def _send(self, method: str, path: str, body: bytes | None) -> tuple[int, dict]:
        request = urllib.request.Request(self.base + path, data=body, method=method,
                                         headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    def call(self, method: str, path: str, payload: dict | None = None) -> dict:
        body = None if payload is None else json.dumps(payload).encode()
        if body is not None and self.names:
            text = body.decode().lower()
            found = [n for n in self.names if n in text]
            if found:
                raise NameLeak(f"{method} {path} would send {len(found)} real name(s); nothing sent")
        for attempt in range(5):
            status, data = self.send(method, path, body)
            if status != 409:
                break
            time.sleep(0.05 * (attempt + 1))  # another batch of the same order was written at the same moment
        if status >= 400:
            raise RuntimeError(f"{method} {path} answered {status}: {data.get('detail') or data}")
        return data


def calendar() -> dict:
    return {"shifts": [{"dayOfWeek": d, "startTime": "09:00", "endTime": "18:00",
                        "breaks": [{"startTime": "13:00", "endTime": "14:00"}]} for d in range(1, 7)]}


def load(plan: dict, api: Api, workers: int = 8) -> str:
    """Creates the factory, its configuration, orders and history. Returns the factory id."""
    f = plan["factory"]
    factory = api.call("POST", "/api/factories", {
        "name": f["name"], "timeZone": f["timeZone"], "currencyCode": f["currencyCode"], "calendar": calendar(),
        "description": "Private import of a real factory: lots are codes, stages are generic. Local only."})
    factory_id = factory["id"]

    pool_ids = {}
    for pool in plan["pools"]:
        created = api.call("POST", "/api/resources", {
            "factoryId": factory_id, "kind": "EMPLOYEE", "category": pool["category"], "name": pool["name"],
            "capacity": pool["capacity"], "hourlyCost": "0"})
        pool_ids[pool["category"]] = created["id"]

    stages = []
    for s in plan["stages"]:
        stages.append({
            "code": s["code"], "name": s["name"], "sequence": s["sequence"], "stonesPerHour": "1",
            "setupSeconds": 0, "stoneYield": "1", "caratYield": "1", "fixedCostPerJob": "0",
            "requirements": [{"resourceId": pool_ids[s["pool"]], "unitsPerSlot": 1}] if "pool" in s else []})
    workflow = api.call("POST", "/api/workflows", {"factoryId": factory_id, "name": "Imported", "status": "ACTIVE",
                                                   "stages": stages})
    stage_ids = {s["code"]: s["id"] for s in workflow["stages"]}
    api.call("PUT", f"/api/workflows/{workflow['id']}/configuration", {
        "stages": [{"code": s["code"], "kind": s["kind"], "delayDeciles": s.get("delayDeciles"),
                    "handlingDeciles": s.get("handlingDeciles")} for s in plan["stages"]],
        "routes": plan["routes"]})

    def place(order: dict) -> list[tuple[str, list[dict]]]:
        created = api.call("POST", "/api/orders", {
            "factoryId": factory_id, "orderNumber": order["orderNumber"], "priority": 3,
            "placedAt": order["placedAt"],
            "batches": [{"stoneCount": 1, "roughCarats": "1", "routeCode": b["route"]} for b in order["batches"]]})
        detail = api.call("GET", f"/api/orders/{created['id']}")
        ids = [b["id"] for b in sorted(detail["batches"], key=lambda b: b["batchNumber"])]
        return list(zip(ids, (b["visits"] for b in order["batches"])))

    def history(batch_id: str, visits: list[dict]) -> int:
        for i, visit in enumerate(visits):
            api.call("POST", "/api/events", {"eventType": "STAGE_STARTED", "batchId": batch_id,
                                             "occurredAt": visit["start"]})
            completed = {"eventType": "STAGE_COMPLETED", "batchId": batch_id, "occurredAt": visit["end"],
                         "stoneQuantity": 1, "caratQuantity": 1}
            if i + 1 < len(visits):
                completed["nextStageId"] = stage_ids[visits[i + 1]["stage"]]
            api.call("POST", "/api/events", completed)
        return 2 * len(visits)

    def order_history(batches: list[tuple[str, list[dict]]]) -> int:
        # One order's batches one after another: they all update the order.
        return sum(history(batch_id, visits) for batch_id, visits in batches)

    with ThreadPoolExecutor(workers) as pool:
        placed = list(pool.map(place, plan["orders"]))
        print(f"{len(plan['orders'])} orders placed, {sum(map(len, placed))} batches", flush=True)
        events = 0
        for done, n in enumerate(pool.map(order_history, placed), start=1):
            events += n
            if done % 500 == 0:
                print(f"  {done} orders, {events} events", flush=True)
    print(f"history loaded: {events} events", flush=True)
    return factory_id


def fork_instants(days: list[str], zone: str) -> list[str]:
    tz = ZoneInfo(zone)
    return [datetime.combine(date.fromisoformat(d), time(0), tz).astimezone(ZoneInfo("UTC"))
            .strftime("%Y-%m-%dT%H:%M:%SZ") for d in days]


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("plan", type=Path)
    parser.add_argument("base_url")
    parser.add_argument("results", type=Path)
    parser.add_argument("--fork", action="append", default=[])
    parser.add_argument("--names", type=Path)
    parser.add_argument("--factory", help="an already loaded factory: run the backtest only")
    parser.add_argument("--horizon", type=int, help="compare at most this many days after each date")
    args = parser.parse_args(argv[1:])
    plan = json.loads(args.plan.read_text())
    names = args.names.read_text(encoding="utf-8").splitlines() if args.names and args.names.exists() else []
    api = Api(args.base_url, names)
    factory_id = args.factory or load(plan, api)
    body = {"factoryId": factory_id}
    if args.fork:
        body["forkAts"] = fork_instants(args.fork, plan["factory"]["timeZone"])
    if args.horizon:
        body["horizonDays"] = args.horizon
    result = api.call("POST", "/api/backtests", body)
    args.results.write_text(json.dumps(result, indent=1))
    o = result["overall"]
    print("typical error: finished {}, lead time {}, work in progress {}".format(
        o.get("finishedError"), o.get("leadTimeError"), o.get("wipError")))


if __name__ == "__main__":
    main(sys.argv)
