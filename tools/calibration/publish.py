"""Turn a detailed, private calibration into the publishable one (spec 11, task 5).

The output holds only rounded patterns: no department, person, party, lot, packet or order name or
identifier, and no dates. Stage names are the generic codes below; capacity is relative to inward volume,
so the factory's real size is not published either.

Usage:
    python -I tools/calibration/publish.py <private-calibration.json> <output.json> [names.txt]

If a names file is given (one real name per line, kept locally), the script refuses to write output that
contains any of them.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# Grouping stage → public code, name and kind. Stages not listed are left out of the public file.
STAGES = {
    "Inward": ("INTAKE", "Rough intake", "INWARD"),
    "Imaging": ("IMAGING", "Imaging and scanning", "WORKING"),
    "Lab": ("LAB", "Lab grading", "WORKING"),
    "Quality check": ("INSPECTION", "Inspection", "WORKING"),
    "Planning": ("PLANNING", "Planning", "WORKING"),
    "Parking": ("PARKING", "Rough store (parked)", "HOLDING"),
    "Laser cutting": ("SAWING", "Laser sawing", "WORKING"),
    "Job work": ("JOB_WORK", "Outside job work", "JOB_WORK"),
    "Manufacturing": ("POLISHING", "Polishing", "WORKING"),
    "Recut": ("RECUT", "Recut", "WORKING"),
    "Dispatch waiting": ("DISPATCH_WAIT", "Dispatch", "HOLDING"),
}

# Packet types merged into public routes: code, name, packet types.
ROUTES = [
    ("TOPS", "Tops", ["1", "5", "2", "4"]),
    ("FULL", "Full manufacturing", ["3"]),
]


def r(value: float, step: float) -> float:
    return round(round(value / step) * step, 3)


def publish(private: dict) -> dict:
    total = sum(v["pieces"] for v in private["typicalRoutes"].values())
    inward = private["capacity"]["inwardPerDayMedian"]
    routes = []
    for code, name, types in ROUTES:
        pieces = sum(private["typicalRoutes"][t]["pieces"] for t in types if t in private["typicalRoutes"])
        main = max(types, key=lambda t: private["typicalRoutes"].get(t, {}).get("pieces", 0))
        steps = [{"stage": STAGES[s["stage"]][0], "visitShare": r(s["share"], 0.05)}
                 for s in private["typicalRoutes"][main]["stages"] if s["stage"] in STAGES]
        lead = [private["byPacketType"][t] for t in types if t in private["byPacketType"]]
        weight = sum(x["pieces"] for x in lead)
        routes.append({
            "code": code, "name": name, "share": r(pieces / total, 0.05), "stages": steps,
            "leadMedianDays": r(sum(x["leadMedianDays"] * x["pieces"] for x in lead) / weight, 0.5),
            "manufacturingMedianDays": r(sum(x["manufacturingMedianDays"] * x["pieces"] for x in lead) / weight, 0.5),
        })
    stages = {}
    for real, (code, name, kind) in STAGES.items():
        s = private["stages"].get(real)
        if s is None:
            continue
        entry = {"name": name, "kind": kind,
                 "dwellHoursDeciles": [max(0, round(v)) for v in s["dwellHoursDeciles"]],
                 "caratYield": r(s.get("caratYield", 1.0), 0.01)}
        busy = private["capacity"]["busyDayOutput"].get(real)
        typical = private["capacity"]["typicalDayOutput"].get(real)
        if kind == "WORKING" and busy and typical:
            entry["busyDayOutputPerInward"] = r(busy / inward, 0.1)
            entry["typicalDayLoad"] = r(typical / busy, 0.05)
        stages[code] = entry
    return {
        "description": "Rounded production patterns measured in one real diamond factory over one month. "
                       "Generic stage names; no names, identifiers or dates; capacity relative to inward volume.",
        "leadDays": {"median": r(private["leadDays"]["median"], 0.5),
                     "deciles": [r(v, 0.5) for v in private["leadDays"]["deciles"]]},
        "manufacturingDays": {"median": r(private["manufacturingDays"]["median"], 0.5),
                              "deciles": [r(v, 0.5) for v in private["manufacturingDays"]["deciles"]]},
        "parking": {"share": r(private["parking"]["share"], 0.05),
                    "daysDeciles": [r(v, 0.5) for v in private["parking"]["daysDeciles"]]},
        "routes": routes,
        "stages": stages,
    }


def check_names(text: str, names: list[str]) -> list[str]:
    low = text.lower()
    return [n for n in names if len(n) >= 4 and n.lower() in low]


def main(argv: list[str]) -> None:
    private = json.loads(Path(argv[1]).read_text())
    out = json.dumps(publish(private), indent=1)
    if len(argv) > 3:
        names = [n.strip() for n in Path(argv[3]).read_text(encoding="utf-8").splitlines() if n.strip()]
        leaked = check_names(out, names)
        if leaked:
            raise SystemExit(f"refusing to write: {len(leaked)} real names found")
    Path(argv[2]).write_text(out + "\n")
    print("written", argv[2])


if __name__ == "__main__":
    main(sys.argv)
