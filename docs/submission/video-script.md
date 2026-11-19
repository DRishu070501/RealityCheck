# Video script (under three minutes)

Target: **2 min 45 s**, about 400 spoken words at a calm pace. Screen recording of the app at 1920 × 1080 (or
1440 × 900), with narration recorded separately or live. The numbers below are from a run on 19 November 2026.
The demonstration is seeded relative to the day it starts, so **read the figures on screen** when recording
and change the narration to match. Don't read these aloud from the page.

## Before recording

1. Reset the demonstration: Settings, Demonstration data, **Reset demo data** (or `POST /api/demo/reset`).
2. Claude on (`AI_PROVIDER=anthropic`, key in `.env`), so the analyst answer is Claude's.
3. In Scenario Lab, run **Current factory operations**, **Park half as long** and **Three more inspectors**
   once each, so that results are ready and the recording does not wait.
4. On Accuracy, run a backtest once.
5. On AI Analyst, with run *Park half as long* and comparison *Current factory operations*, ask:
   *"Why does halving parking time shorten completion so much, and what should we watch?"*
6. Browser zoom 100%, professional theme, a clean profile (no extensions or bookmarks bar).

## Script

| Time | Screen | Narration |
|---|---|---|
| 0:00–0:15 | Overview | "In this diamond factory a rough packet takes about seventeen days to reach dispatch. People spend hours on it, not weeks. So where does the time go, and what would have happened if we'd changed something last week? RealityCheck is a time machine for the factory floor." |
| 0:15–0:35 | README "The creature" table, or a title card with five labels | "It's a Frankenstein: five parts that were never meant to share a body. An append-only history that can't change the past. A deterministic simulation engine. Patterns measured from a real factory's month of data. An AI analyst that isn't allowed to calculate. And one app that tags every number as Actual, Simulated or AI." |
| 0:35–0:55 | Where Time Goes (lead time with and without parking), then Workflow, scrolled to **Haunted stages** | "Recorded history first. Packets spend a large share of their time parked in the rough store. On the workflow, the haunted stages are where packets lose the most time with nobody working on them. Parking is number one." |
| 0:55–1:20 | Scenario Lab: open **Park half as long**, show the fork date and the single change; then **Three more inspectors** | "Now we go back in time. A scenario forks from a recorded moment last week and changes one decision. Here we halve the time packets sit parked. Here we hire three more inspectors instead. The engine replays the factory from that moment, forward, under each decision." |
| 1:20–1:50 | Simulation Results: run *Park half as long* against *Current factory operations*; headline metrics, then scroll to **Ghost futures** | "Halving parking finishes the work about thirty percent sooner, and on-time delivery rises from fifty-seven to sixty-two percent. Each solid dot is when an order really finishes; its ghost is when it would have finished. Where history already knows, a diamond shows what really happened." |
| 1:50–2:00 | Switch the run to *Three more inspectors* | "Three more inspectors? Completion doesn't move at all. Cost goes up nine percent. More people only help where a stage is waiting for capacity, and the data says it isn't." |
| 2:00–2:20 | AI Analyst: the stored answer; open **Evidence behind this answer** | "Claude explains the result, but only from stored evidence. Every number it writes is checked against the simulation. Notice it says the evidence doesn't isolate a cause. It won't invent one. If it ever states a number the results don't support, the built-in analyst answers instead." |
| 2:20–2:35 | Accuracy: typical error card, then the per-date table | "Can you trust the engine? Accuracy runs a backtest: it simulates from past dates, changing nothing, and compares with what was recorded. On one real factory, imported privately, it's within about a fifth on packets finished, and we publish where it's wrong." |
| 2:35–2:45 | Settings → Appearance → **Halloween**; Ghost futures in the night theme; end card with the public link | "Built spec-first with Kiro: steering rules, eleven specs, hooks. And for Kiroween, a night theme with the same contrast. RealityCheck: see the future before you change the present." |

About 400 words. If a take runs long, cut the second sentence of 0:55–1:20 and the last sentence of 2:00–2:20.

## On screen, never spoken

- Small caption at 0:35: *"Demonstration factory: synthetic, calibrated on rounded patterns from one real
  factory."*
- Small caption at 2:20: *"Real-factory figures: 7-day windows, rounded; see docs/accuracy.md."*

## Checks before uploading

- Under 3:00 (aim for 2:45).
- No real factory names, dates or screens from the private import appear. Only the demonstration factory is
  shown.
- No API key, `.env` file or terminal with secrets in frame.
- Captions on (YouTube auto-captions corrected, or an uploaded `.srt`).
- Public on YouTube or Vimeo, link in Devpost.
