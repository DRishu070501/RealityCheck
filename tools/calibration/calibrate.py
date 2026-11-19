"""Measure a real factory's patterns from Trybe extracts (spec 11, task 3).

Reads CSV extracts produced by read-only queries and a department grouping, and writes a detailed
calibration file. Inputs and output must live outside the repository (or under the ignored `local/`):
they hold client data. This module never connects to a database.

Usage:
    python -I tools/calibration/calibrate.py <extract-dir> <grouping.json> <output.json>

Extract files (see spec 11): departments.csv, history.csv, worker_moves.csv, dispatched_with_root.csv,
route_movements.csv.

Lead time runs from the lot's first arrival at an INWARD department to the earliest of: arrival at an END
department, or the Final Dispatch process. Time in PARKING departments is reported separately and excluded
from manufacturing lead time.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

FINAL_DISPATCH_ACTION = 792
DECILES = [0.05, 0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95]


def deciles(hours: pd.Series) -> list[float]:
    """Ten representative values (midpoints of each tenth), in hours, rounded to 0.1."""
    s = hours.dropna()
    if s.empty:
        return []
    return [round(float(v), 1) for v in s.quantile(DECILES)]


def load(extract: Path, grouping: dict[int, dict]) -> dict[str, pd.DataFrame]:
    def csv(name, dates=()):
        return pd.read_csv(extract / f"{name}.csv", parse_dates=list(dates))

    return {
        "history": csv("history", ["createdDate"]),
        "workers": csv("worker_moves", ["createdDate", "receivedOn"]),
        "dispatched": csv("dispatched_with_root", ["lotCreation"]),
        "routes": csv("route_movements", ["createdDate", "receivedOn"]),
        "grouping": pd.DataFrame.from_dict(grouping, orient="index").rename_axis("dept").reset_index()
        .astype({"dept": int}),
    }


def piece_paths(data: dict[str, pd.DataFrame]) -> list[dict]:
    """Every dispatched piece's dated path from inward to its end.

    Each path is {piece, packetType, root, start, end, visits}, where visits are (dept, arrive, leave)
    clipped to [start, end], in time order, END departments left out.
    """
    g = data["grouping"].set_index("dept")
    kind_of = g["kind"].to_dict()
    h = data["history"]
    final = h[(h.actionId == FINAL_DISPATCH_ACTION) & (h.status == 1)].groupby("packetId").createdDate.min()
    pieces = data["dispatched"].copy()
    pieces["finalDispatch"] = pieces.id.map(final)
    by_packet = dict(tuple(data["routes"].sort_values(["packetId", "createdDate", "id"]).groupby("packetId")))

    paths = []
    for p in pieces.itertuples():
        if pd.isna(p.finalDispatch) or pd.isna(p.lotCreation):
            continue
        own = by_packet.get(p.id)
        parts = [] if own is None else [own]
        if p.root != p.id and p.root in by_packet:
            root = by_packet[p.root]
            start = own.createdDate.min() if own is not None else p.finalDispatch
            parts.append(root[root.createdDate < start])
        if not parts:
            continue
        mv = pd.concat(parts).sort_values(["createdDate", "id"])
        mv = mv[mv.toDept.notna()]
        arrive = mv.receivedOn.fillna(mv.createdDate).to_numpy()
        sent = mv.createdDate.to_numpy()
        depts = mv.toDept.astype(int).to_numpy()
        lo = np.datetime64(p.lotCreation)
        end = np.datetime64(p.finalDispatch)
        for dept, a in zip(depts, arrive):
            if kind_of.get(dept) == "END" and a >= lo:
                end = min(end, a)
                break
        if end <= lo:
            continue
        leave = np.append(sent[1:], end)
        visits = []
        for dept, a, l in zip(depts, arrive, leave):
            a2, l2 = max(a, lo), min(l, end)
            if l2 <= a2 or kind_of.get(dept) == "END":
                continue
            visits.append((int(dept), a2, l2))
        paths.append({"piece": p.id, "packetType": p.packetType, "root": p.root, "start": lo, "end": end,
                      "visits": visits})
    return paths


def lineage_intervals(data: dict[str, pd.DataFrame]) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Every dispatched piece's path from inward to its end, as (piece, stage, kind, hours) intervals."""
    g = data["grouping"].set_index("dept")
    kind_of = g["kind"].to_dict()
    stage_of = g["stage"].to_dict()
    rows, leads = [], []
    for path in piece_paths(data):
        parked = 0.0
        for dept, a, l in path["visits"]:
            hours = (l - a) / np.timedelta64(1, "h")
            kind = kind_of.get(dept, "OTHER")
            rows.append((path["piece"], path["packetType"], stage_of.get(dept, "Other"), kind, hours))
            if kind == "PARKING":
                parked += hours
        total = (path["end"] - path["start"]) / np.timedelta64(1, "h")
        leads.append((path["piece"], path["packetType"], total / 24, (total - parked) / 24, parked / 24))
    intervals = pd.DataFrame(rows, columns=["piece", "packetType", "stage", "kind", "hours"])
    lead = pd.DataFrame(leads, columns=["piece", "packetType", "leadDays", "manufacturingDays", "parkedDays"])
    return intervals, lead


def typical_routes(intervals: pd.DataFrame, min_share: float = 0.5) -> dict:
    """Per packet type: stages visited by at least `min_share` of its pieces, in median first-visit order."""
    out = {}
    iv = intervals.copy()
    iv["order"] = iv.groupby("piece").cumcount()
    for ptype, grp in iv.groupby("packetType"):
        pieces = grp.piece.nunique()
        first = grp.groupby(["piece", "stage"]).order.min().reset_index()
        stats = first.groupby("stage").agg(visits=("piece", "nunique"), order=("order", "median"))
        stats = stats[stats.visits / pieces >= min_share].sort_values("order")
        out[str(int(ptype))] = {"pieces": int(pieces),
                                "stages": [{"stage": st, "share": round(float(r.visits / pieces), 2)}
                                           for st, r in stats.iterrows()]}
    return out


def stage_capacity(extract: Path, grouping: pd.DataFrame) -> dict:
    """Busy-day (90th percentile) packets leaving each stage per working day, and median packets inward per day."""
    stage_of = grouping.set_index("dept")["stage"].to_dict()
    m = pd.read_csv(extract / "movements.csv", parse_dates=["createdDate"],
                    usecols=["packetId", "fromDept", "toDept", "createdDate"])
    t = m[(m.fromDept != m.toDept) & m.fromDept.notna()].copy()
    t["fromStage"] = t.fromDept.astype(int).map(stage_of)
    t["toStage"] = t.toDept.map(lambda x: stage_of.get(int(x)) if pd.notna(x) else None)
    t["day"] = t.createdDate.dt.normalize()
    t = t[t.day.dt.dayofweek != 6]
    leaving = t[t.fromStage != t.toStage].groupby(["fromStage", "day"]).packetId.nunique()
    a = m[m.toDept.notna()].copy()
    a["toStage"] = a.toDept.astype(int).map(stage_of)
    a["fromStage"] = a.fromDept.map(lambda x: stage_of.get(int(x)) if pd.notna(x) else None)
    a["day"] = a.createdDate.dt.normalize()
    arrivals = a[(a.toStage == "Inward") & (a.fromStage != "Inward") & (a.day.dt.dayofweek != 6)]
    inward_day = arrivals.groupby("day").packetId.nunique().median() if len(arrivals) else None
    return {"inwardPerDayMedian": None if inward_day is None else round(float(inward_day)),
            "busyDayOutput": {st: int(v.quantile(0.9)) for st, v in leaving.groupby(level=0)},
            "typicalDayOutput": {st: int(v.median()) for st, v in leaving.groupby(level=0)}}


def route_mix(intervals: pd.DataFrame) -> dict:
    """Most common stage sequences per packet type (consecutive repeats collapsed)."""
    out = {}
    for ptype, grp in intervals.groupby("packetType"):
        seqs = grp.groupby("piece").stage.apply(
            lambda s: " > ".join(v for i, v in enumerate(s) if i == 0 or v != s.iloc[i - 1]))
        counts = seqs.value_counts()
        out[str(int(ptype))] = {
            "pieces": int(len(seqs)),
            "topRoutes": [{"route": r, "share": round(float(n / len(seqs)), 3)} for r, n in counts.head(5).items()],
        }
    return out


def worker_stats(data: dict[str, pd.DataFrame]) -> pd.DataFrame:
    g = data["grouping"].set_index("dept")
    w = data["workers"].sort_values(["packetId", "createdDate", "id"]).copy()
    w["next"] = w.groupby("packetId").createdDate.shift(-1)
    w = w[w.toUser.notna() & w.toDept.notna()].copy()
    w["start"] = w.receivedOn.fillna(w.createdDate)
    w["holdHours"] = (w.next - w.start).dt.total_seconds() / 3600
    w = w[(w.holdHours >= 0) & (w.holdHours < 24 * 14)]
    w["stage"] = w.toDept.astype(int).map(g["stage"]).fillna("Other")
    w["day"] = w.start.dt.date
    daily = w.groupby(["stage", "day"]).toUser.nunique().groupby("stage").mean()
    per_worker = w.groupby(["stage", "day", "toUser"]).size().groupby("stage").mean()
    hold = w.groupby("stage").holdHours.apply(deciles)
    return pd.DataFrame({"workersPerDay": daily.round(1), "packetsPerWorkerDay": per_worker.round(1),
                         "withWorkerHoursDeciles": hold})


def yields(data: dict[str, pd.DataFrame]) -> pd.Series:
    g = data["grouping"].set_index("dept")
    h = data["history"]
    done = h[(h.status == 1) & h.issueCts.notna() & h.returnCts.notna() & h.deptId.notna()
             & (h.actionId != FINAL_DISPATCH_ACTION)].copy()
    done["stage"] = done.deptId.astype(int).map(g["stage"]).fillna("Other")
    s = done.groupby("stage").agg(issued=("issueCts", "sum"), returned=("returnCts", "sum"))
    return (s.returned / s.issued).round(3)


def calibrate(extract: Path, grouping: dict[int, dict]) -> dict:
    data = load(extract, grouping)
    intervals, lead = lineage_intervals(data)
    workers = worker_stats(data)
    yld = yields(data)
    stages = {}
    for (stage, kind), grp in intervals.groupby(["stage", "kind"]):
        per_piece = grp.groupby("piece").hours.sum()
        entry = {
            "kind": kind,
            "pieces": int(per_piece.size),
            "shareOfPieces": round(per_piece.size / lead.piece.nunique(), 3),
            "dwellHoursDeciles": deciles(per_piece),
            "shareOfLeadTime": round(float(grp.hours.sum() / (lead.leadDays.sum() * 24)), 3),
        }
        if stage in workers.index:
            row = workers.loc[stage]
            entry.update(workersPerDay=float(row.workersPerDay), packetsPerWorkerDay=float(row.packetsPerWorkerDay),
                         withWorkerHoursDeciles=row.withWorkerHoursDeciles)
        if stage in yld.index:
            entry["caratYield"] = float(yld[stage])
        stages[stage] = entry
    parked = lead[lead.parkedDays > 0]
    return {
        "source": "local extract",
        "pieces": int(len(lead)),
        "leadDays": {"median": round(float(lead.leadDays.median()), 1), "deciles": [round(v / 24, 1) for v in deciles(lead.leadDays * 24)]},
        "manufacturingDays": {"median": round(float(lead.manufacturingDays.median()), 1),
                              "deciles": [round(v / 24, 1) for v in deciles(lead.manufacturingDays * 24)]},
        "parking": {"share": round(len(parked) / len(lead), 3),
                    "daysDeciles": [round(v / 24, 1) for v in deciles(parked.parkedDays * 24)]},
        "byPacketType": {str(int(k)): {"pieces": int(v.piece.size), "leadMedianDays": round(float(v.leadDays.median()), 1),
                                       "manufacturingMedianDays": round(float(v.manufacturingDays.median()), 1)}
                         for k, v in lead.groupby("packetType")},
        "stages": stages,
        "routes": route_mix(intervals),
        "typicalRoutes": typical_routes(intervals),
        "capacity": stage_capacity(extract, data["grouping"]),
    }


def main(argv: list[str]) -> None:
    extract, grouping_path, output = Path(argv[1]), Path(argv[2]), Path(argv[3])
    grouping = {int(k): v for k, v in json.loads(grouping_path.read_text()).items()}
    result = calibrate(extract, grouping)
    output.write_text(json.dumps(result, indent=1))
    print(f"{result['pieces']} pieces; lead median {result['leadDays']['median']} d; "
          f"manufacturing median {result['manufacturingDays']['median']} d; parked {result['parking']['share']:.0%}")


if __name__ == "__main__":
    main(sys.argv)
