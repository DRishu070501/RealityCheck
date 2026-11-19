"""Turn a real factory's extracts into an anonymised import plan (spec 11, task 11).

The plan is everything RealityCheck needs to hold the factory: pools, stages with their kinds and measured
delays, routes, orders (lots) with their batches (pieces), and each batch's dated stage visits. It holds no
department, person, party, lot or packet name or identifier: lots become codes (L0001, ...) in order of
arrival, stages are the generic grouping names. It does hold real dates and carat weights, so it must stay
on this machine, under the ignored `local/` directory.

Usage:
    python -I tools/import/plan.py <extract-dir> <grouping.json> <output-plan.json> [--sample PERCENT]

What a batch is. A batch is one dispatched piece, from its lot's arrival at the inward department to its
end (as in tools/calibration). Before a lot splits, its pieces share one physical packet; each piece is
still its own batch, so early stages see one batch per piece. Capacity is measured on the same batches, so
the two agree.

Routes. Pieces of the full-manufacturing packet type follow FULL; every other type follows TOPS. Each
route lists every stage in workflow order; the share of its batches that visit a stage is measured, and
steps visited by fewer than 1% are dropped. A piece's real path is laid onto its route: a visit to a later
step starts that step; a return to an earlier step, or time in a department outside the routes, counts as
staying where it is. The first and last steps are always visited; a piece that skipped one gets a visit of
no length.

Capacity. A working stage gets one pool whose units each finish one batch per processing hour: its size is
the busy-day (90th percentile) number of batches leaving the stage in a working day, divided by the
working hours in a day. What a batch measured at a stage beyond that hour becomes its handling delay
(deciles). Holding stages (inward, parking, job work, dispatch waiting) hold a batch for a measured
calendar delay (deciles).

Sampling. With --sample, only that percent of lots are kept, chosen by a hash of the lot, so whole lots stay
together and the same input always gives the same plan. Capacity is measured after sampling.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "calibration"))
from calibrate import DECILES, load, piece_paths  # noqa: E402

FULL_PACKET_TYPE = 3
# Grouping stage -> (code, kind) in workflow order. Departments of any other grouping stage are not steps.
STAGES = [
    ("Inward", "INWARD", "WAITING"),
    ("Imaging", "IMAGING", "WORKING"),
    ("Lab", "LAB", "WORKING"),
    ("Quality check", "INSPECTION", "WORKING"),
    ("Planning", "PLANNING", "WORKING"),
    ("Parking", "PARKING", "PARKING"),
    ("Laser cutting", "SAWING", "WORKING"),
    ("Job work", "JOB_WORK", "JOB_WORK"),
    ("Manufacturing", "POLISHING", "WORKING"),
    ("Recut", "RECUT", "WORKING"),
    ("Dispatch waiting", "DISPATCH_WAIT", "WAITING"),
]
ROUTES = {"FULL": ("Full manufacturing", "DISPATCH_WAIT"), "TOPS": ("Tops", "RECUT")}
WORKING_HOURS_PER_DAY = 8
PROCESSING_SECONDS = 3600
MIN_VISIT_SHARE = 0.01


def kept(root: int, percent: float) -> bool:
    digest = hashlib.sha256(f"lot:{int(root)}".encode()).digest()
    return int.from_bytes(digest[:4], "big") % 10_000 < percent * 100


def iso(t: np.datetime64) -> str:
    return pd.Timestamp(t).tz_localize("Asia/Kolkata").tz_convert("UTC").strftime("%Y-%m-%dT%H:%M:%SZ")


def longest_in_order(seen: list[tuple[int, np.datetime64]]) -> list[tuple[int, np.datetime64]]:
    """The longest run of visits, in time order, whose steps strictly ascend; the earliest such run."""
    best: list[int] = [1] * len(seen)
    back: list[int] = [-1] * len(seen)
    for j in range(len(seen)):
        for i in range(j):
            if seen[i][0] < seen[j][0] and best[i] + 1 > best[j]:
                best[j], back[j] = best[i] + 1, i
    if not seen:
        return []
    j = max(range(len(seen)), key=lambda k: (best[k], -k))
    chain = []
    while j != -1:
        chain.append(seen[j])
        j = back[j]
    return chain[::-1]


def project(path: dict, step_codes: list[str], stage_code_of: dict[int, str]) -> list[tuple[str, np.datetime64]]:
    """The piece's visits laid onto a route: (step code, start), in step order; each visit ends where the next
    one starts, and the last at the piece's end."""
    index = {code: i for i, code in enumerate(step_codes)}
    seen: list[tuple[int, np.datetime64]] = []
    for dept, arrive, _leave in path["visits"]:
        code = stage_code_of.get(dept)
        if code is None or code not in index:
            continue
        if seen and seen[-1][0] == index[code]:
            continue
        seen.append((index[code], arrive))
    visits: list[tuple[str, np.datetime64]] = []
    for i, arrive in longest_in_order(seen):
        visits.append((step_codes[i], max(arrive, visits[-1][1]) if visits else arrive))
    first, last = step_codes[0], step_codes[-1]
    if not visits or visits[0][0] != first:
        visits.insert(0, (first, path["start"]))
    else:
        visits[0] = (first, path["start"])
    if visits[-1][0] != last:
        visits.append((last, path["end"]))
    return visits


def deciles_seconds(values: list[float]) -> list[int]:
    if not values:
        return [0] * 10
    q = np.quantile(np.asarray(values, dtype=float), DECILES)
    return [int(round(v / 60.0)) * 60 for v in np.maximum.accumulate(q)]


def build(extract: Path, grouping: dict[int, dict], sample_percent: float = 100.0) -> dict:
    data = load(extract, grouping)
    stage_name_to_code = {name: code for name, code, _ in STAGES}
    stage_code_of = {dept: stage_name_to_code[g["stage"]] for dept, g in grouping.items()
                     if g["stage"] in stage_name_to_code}
    all_codes = [code for _, code, _ in STAGES]

    paths = [p for p in piece_paths(data) if kept(p["root"], sample_percent)]
    route_steps = {}
    for route, (_, last) in ROUTES.items():
        route_steps[route] = all_codes[: all_codes.index(last) + 1]

    batches = []
    for p in paths:
        route = "FULL" if int(p["packetType"]) == FULL_PACKET_TYPE else "TOPS"
        visits = project(p, route_steps[route], stage_code_of)
        batches.append({"root": int(p["root"]), "route": route, "start": p["start"], "end": p["end"],
                        "visits": visits})

    # Visit shares per route step; rare optional steps are dropped and the batches re-laid without them.
    routes = []
    for route, (name, _) in ROUTES.items():
        on = [b for b in batches if b["route"] == route]
        if not on:
            continue
        counts = Counter(code for b in on for code, _ in b["visits"])
        steps = []
        for i, code in enumerate(route_steps[route]):
            share = counts[code] / len(on)
            if i in (0, len(route_steps[route]) - 1):
                steps.append({"stageCode": code, "visitPercent": 100})
            elif share >= MIN_VISIT_SHARE:
                steps.append({"stageCode": code, "visitPercent": max(1, min(99, round(share * 100)))})
        codes = {s["stageCode"] for s in steps}
        for b in on:
            b["visits"] = [v for v in b["visits"] if v[0] in codes]
        routes.append({"code": route, "name": name, "steps": steps})

    # Durations of each visit, and batches leaving each stage per working day.
    stays: dict[str, list[float]] = defaultdict(list)
    leaving: dict[str, Counter] = defaultdict(Counter)
    for b in batches:
        ends = [v[1] for v in b["visits"][1:]] + [b["end"]]
        b["visits"] = [(code, start, end) for (code, start), end in zip(b["visits"], ends)]
        for code, start, end in b["visits"]:
            stays[code].append(float((end - start) / np.timedelta64(1, "s")))
            day = pd.Timestamp(end).normalize()
            if day.dayofweek != 6:
                leaving[code][day] += 1

    pools, stages = [], []
    for sequence, (name, code, kind) in enumerate(STAGES, start=1):
        stage = {"code": code, "name": name, "sequence": sequence, "kind": kind}
        if kind == "WORKING":
            per_day = list(leaving[code].values())
            busy = float(np.quantile(per_day, 0.9)) if per_day else 1.0
            units = max(1, math.ceil(busy / WORKING_HOURS_PER_DAY))
            pools.append({"category": f"{code}_CREW", "name": f"{name} crew", "capacity": units})
            stage["pool"] = f"{code}_CREW"
            stage["handlingDeciles"] = deciles_seconds([max(0.0, s - PROCESSING_SECONDS) for s in stays[code]])
        else:
            stage["delayDeciles"] = deciles_seconds(stays[code])
        stages.append(stage)

    # Lots as orders, numbered by arrival; at most 50 batches per order.
    first_arrival: dict[int, np.datetime64] = {}
    for b in batches:
        first_arrival[b["root"]] = min(first_arrival.get(b["root"], b["start"]), b["start"])
    lot_code = {root: f"L{i:04d}" for i, root in enumerate(sorted(first_arrival, key=lambda r: (first_arrival[r], r)),
                                                           start=1)}
    by_lot: dict[int, list[dict]] = defaultdict(list)
    for b in batches:
        by_lot[b["root"]].append(b)
    orders = []
    for root, code in sorted(lot_code.items(), key=lambda kv: kv[1]):
        lot = sorted(by_lot[root], key=lambda b: (b["start"], b["end"]))
        for part, chunk_start in enumerate(range(0, len(lot), 50), start=1):
            chunk = lot[chunk_start: chunk_start + 50]
            number = code if len(lot) <= 50 else f"{code}-{part}"
            # A lot's batches arrive together: the order is placed at its earliest piece's arrival, and each
            # batch's first visit starts no earlier.
            placed = min(b["start"] for b in chunk)
            orders.append({
                "orderNumber": number,
                "placedAt": iso(placed),
                "batches": [{"route": b["route"],
                             "visits": [{"stage": c, "start": iso(s), "end": iso(e)} for c, s, e in b["visits"]]}
                            for b in chunk],
            })

    return {
        "factory": {"name": "Factory A (private import)", "timeZone": "Asia/Kolkata", "currencyCode": "INR"},
        "samplePercent": sample_percent,
        "pools": pools,
        "stages": stages,
        "routes": routes,
        "orders": orders,
    }


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("extract", type=Path)
    parser.add_argument("grouping", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--sample", type=float, default=100.0, help="percent of lots to keep")
    args = parser.parse_args(argv[1:])
    grouping = {int(k): v for k, v in json.loads(args.grouping.read_text()).items()}
    plan = build(args.extract, grouping, args.sample)
    args.output.write_text(json.dumps(plan))
    batches = sum(len(o["batches"]) for o in plan["orders"])
    visits = sum(len(b["visits"]) for o in plan["orders"] for b in o["batches"])
    print(f"{len(plan['orders'])} orders, {batches} batches, {visits} stage visits")


if __name__ == "__main__":
    main(sys.argv)
