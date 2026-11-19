# Video script (under three minutes)

Target: under **3 minutes** (the generated version runs 2:53), about 440 spoken words at a calm pace. Screen recording of the app at 1920 × 1080 (or
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

The narration is `narration.json`; this table is generated from it and from the recorded timing. Emotion
comes from the wording, pauses ("...") and each scene's own pace and pitch (`rate`, `pitch` in the file), since
the voice service takes no emotion styles. A human narrator should follow the Tone column.

| Time | Tone | Screen | Narration |
|---|---|---|---|
| 0:02–0:22 | curious, a little puzzled | Overview | "Seventeen days. That's how long a rough diamond packet takes to reach dispatch in this factory... yet people work on it for only a few hours. So where does all that time go? And what if you could go back to last week... and change one decision? Meet RealityCheck: a time machine for the factory floor." |
| 0:22–0:45 | playful, dramatic | Title card: the five parts appear one by one as they are named | "It's a Frankenstein. Five parts that were never meant to share a body. A history that can never change the past. A simulation engine that never guesses. Patterns from a real factory's month of data. An AI analyst that is forbidden to calculate. And one app, where every number tells you exactly where it came from." |
| 0:45–1:03 | revealing, slightly ominous | Where Time Goes, then Workflow scrolled to **Haunted stages** | "Start with what really happened. Packets spend a huge share of their lives... parked in the rough store. On the workflow, these are the haunted stages: where packets lose the most time, with nobody working on them. And the worst one? Parking." |
| 1:03–1:23 | excited | Scenario Lab list, then **Park half as long**, then **Three more inspectors** | "Now, let's go back in time. A scenario forks from a recorded moment... and changes just one decision. In this one, we halve the time packets sit parked. In this one, we hire three more inspectors instead. Then the engine replays the factory, from that moment forward, under each decision." |
| 1:23–1:44 | triumphant, then wonder | Simulation Results (Park half as long against the baseline): headline metrics, then **Ghost futures** | "And the result? Halving parking finishes the work about thirty percent sooner... and on-time delivery jumps from fifty-seven to sixty-two percent. Each solid dot is when an order really finishes. Its ghost... is when it would have finished. And where history already knows, a diamond shows what truly happened." |
| 1:44–1:56 | wry | Simulation Results for Three more inspectors: headline metrics | "And three more inspectors? Completion doesn't move. Not by a minute. Cost goes up nine percent. More people only help where a stage is actually waiting for capacity." |
| 1:56–2:17 | calm, trustworthy | AI Analyst: the stored Claude answer; **Evidence behind this answer** opens | "Claude explains the result, but only from stored evidence, and every number it writes is checked against the simulation. Look closely: it says the evidence does not isolate a cause. It refuses to invent one. And if it ever states a number the results can't support, the built-in analyst steps in instead." |
| 2:17–2:34 | confident, honest | Accuracy: typical error, then the per-date table | "But can you trust the engine? A backtest simulates past dates, changing nothing, and compares the result with what was recorded. On one real factory, imported privately, it's within about a fifth on packets finished... and we publish exactly where it's wrong." |
| 2:34–2:53 | warm, uplifting | "Built with Kiro" card, lifting to Ghost futures; the Halloween theme switches on live; end card | "Built spec-first with Kiro: steering rules, eleven specs, and hooks. And for Kiroween, a night theme, with exactly the same contrast. RealityCheck. See the future... before you change the present." |

About 405 words; 2:53 in the generated version.

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

## Generated version

A complete version is generated rather than recorded by hand. The narration is `narration.json`, voiced by
Microsoft's neural voice *en-US-AvaNeural* through `edge-tts`; only the narration text is sent to the service.
The picture is a scripted browser run timed to each sentence, and the captions come from the voice's sentence
timings. To rebuild after editing a line or re-seeding the demonstration:

```sh
pip install edge-tts                      # once; ffmpeg must also be installed (winget install Gyan.FFmpeg)
python -I tools/video/assemble.py narrate docs/submission/narration.json <work>/audio
# config.json: {"base": "http://localhost:3000", "runs": {"base", "park", "insp"}, "scenarios": {"park", "insp"},
#               "audioDir": "<work>/audio", "outDir": "<work>/out", "repo": "github.com/…"}
node frontend/scripts/video.mjs <work>/config.json      # from frontend/; prepare the app as above first
python -I tools/video/assemble.py build <work>/audio <work>/out
```

The output is `<work>/out/realitycheck-demo.mp4` (1920 × 1080, about 2:53) and `captions.srt` for the
upload. The video built on 19 November is kept outside the repository, in `local/video/`.
