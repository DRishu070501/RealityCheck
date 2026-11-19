"""Rounded, dateless figures from a private backtest, for docs/accuracy.md (spec 11, task 11).

Usage:
    python -I tools/import/summarise.py <backtest.json>

Prints percentages rounded to whole numbers and counts rounded to tens, with dates replaced by their order
("first date", ...). Nothing else from the backtest is printed.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ORDINALS = ["First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth"]


def pct(value) -> str:
    return "n/a" if value is None else f"{float(value) * 100:+.0f}%"


def abs_pct(value) -> str:
    return "n/a" if value is None else f"{abs(float(value)) * 100:.0f}%"


def tens(value) -> str:
    return "n/a" if value is None else f"about {round(int(value), -1):,}"


def summarise(result: dict) -> str:
    o = result["overall"]
    lines = [f"Typical error over {o['forks']} dates: finished {abs_pct(o['finishedError'])}, "
             f"median lead time {abs_pct(o['leadTimeError'])}, work in progress by stage {abs_pct(o['wipError'])}.",
             "", "| Date | Batches compared | Finished error | Lead time error | Work in progress error | Cut at last event |",
             "|---|---|---|---|---|---|"]
    for i, f in enumerate(result["forks"]):
        lines.append(f"| {ORDINALS[i]} | {tens(f['batches'])} | {pct(f['finished']['percent'])} | "
                     f"{pct(f['medianLeadSeconds']['percent'])} | {abs_pct(f['wipError'])} | "
                     f"{'yes' if f['cutAtLastEvent'] else 'no'} |")
    lines += ["", "Work in progress at the end of each window, summed over the dates:", ""]
    totals: dict[str, list[int]] = {}
    for f in result["forks"]:
        for s in f["wip"]:
            t = totals.setdefault(s["code"], [s["sequence"], 0, 0])
            t[1] += s["simulated"]
            t[2] += s["recorded"]
    lines += ["| Stage | Simulated | Recorded |", "|---|---|---|"]
    for code, (_, sim, rec) in sorted(totals.items(), key=lambda kv: kv[1][0]):
        lines.append(f"| {code} | {tens(sim)} | {tens(rec)} |")
    return "\n".join(lines)


if __name__ == "__main__":
    print(summarise(json.loads(Path(sys.argv[1]).read_text())))
