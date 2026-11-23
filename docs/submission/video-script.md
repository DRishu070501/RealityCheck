# Video script (under three minutes)

Target: under **3 minutes**; the generated version runs 2:53. Screen recording of the app at 1920 × 1080
(or 1440 × 900), with narration recorded separately or live. The numbers below are from a run on 19 November 2026.
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

Nine scenes, about 405 spoken words. Each scene describes what the viewer sees, how the moment should feel,
and the line that is spoken. The spoken lines are kept in `narration.json`, which the generated video reads;
a human narrator should follow the feeling described for each scene. Times are from the generated version.

### Scene 1: The question (0:02–0:22)

**Feeling:** curious, a little puzzled, as if noticing something odd for the first time.

**On screen:** The Overview of Surat Diamond Works. Fifty-six orders in progress, packets queued at every stage. Everything looks busy and normal. After a few seconds the view drifts down to the stage-by-stage queues, inviting the viewer to wonder what is really going on.

**Spoken:** "Seventeen days. That's how long a rough diamond packet takes to reach dispatch in this factory... yet people work on it for only a few hours. So where does all that time go? And what if you could go back to last week... and change one decision? Meet RealityCheck: a time machine for the factory floor."

**Delivery:** Open slowly on "Seventeen days." Let the pause after "dispatch in this factory" hang before the contrast. Lift on "Meet RealityCheck".

### Scene 2: The creature (0:22–0:45)

**Feeling:** playful and dramatic, a Frankenstein reveal.

**On screen:** A dark title card, "RealityCheck: a Frankenstein of five parts". The parts appear one at a time, each as it is named: the skeleton (append-only history), the heart (deterministic simulation engine), the blood (a real factory's patterns), the voice (the AI analyst) and the face (one app where every number is tagged).

**Spoken:** "It's a Frankenstein. Five parts that were never meant to share a body. A history that can never change the past. A simulation engine that never guesses. Patterns from a real factory's month of data. An AI analyst that is forbidden to calculate. And one app, where every number tells you exactly where it came from."

**Delivery:** A slightly lower, theatrical voice. Give each part its own beat, and lean on "forbidden".

### Scene 3: The haunting (0:45–1:03)

**Feeling:** revealing, slightly ominous, like uncovering where the trouble hides.

**On screen:** Where Time Goes: lead time with and without parking, and the bar chart of where packet time is spent, with the long purple "held" bar for the rough store. Then the Workflow screen glides down to **Haunted stages**, with the rough store ranked number one.

**Spoken:** "Start with what really happened. Packets spend a huge share of their lives... parked in the rough store. On the workflow, these are the haunted stages: where packets lose the most time, with nobody working on them. And the worst one? Parking."

**Delivery:** Quieter, as if sharing a secret. A short pause before "Parking." so it lands as the answer.

### Scene 4: Back in time (1:03–1:23)

**Feeling:** excited; this is the idea the whole product is built on.

**On screen:** Scenario Lab with its six scenarios. Then **Park half as long**: its fork date, and its single change, the rough store's holding time cut by 50%. Then **Three more inspectors**: inspection capacity from three to six people.

**Spoken:** "Now, let's go back in time. A scenario forks from a recorded moment... and changes just one decision. In this one, we halve the time packets sit parked. In this one, we hire three more inspectors instead. Then the engine replays the factory, from that moment forward, under each decision."

**Delivery:** Brighter and a touch quicker. Emphasise "one decision", and give the two "In this one" lines the same rhythm so they feel like a fair contest.

### Scene 5: The payoff (1:23–1:44)

**Feeling:** triumphant at the numbers, then a moment of wonder at the ghosts.

**On screen:** Simulation Results for Park half as long against the factory as it is: the headline metrics, with completion time about 31% shorter and on-time delivery up. Then the view glides to **Ghost futures**: every order's real finish as a solid dot, and its ghost where it would have finished, joined by a faint line. Green diamonds mark what history actually recorded.

**Spoken:** "And the result? Halving parking finishes the work about thirty percent sooner... and on-time delivery jumps from fifty-seven to sixty-two percent. Each solid dot is when an order really finishes. Its ghost... is when it would have finished. And where history already knows, a diamond shows what truly happened."

**Delivery:** A confident lift on the numbers. Then slow down and soften for the ghost lines; "Its ghost..." is the most atmospheric moment in the film.

### Scene 6: The twist (1:44–1:56)

**Feeling:** wry, a gentle surprise that undercuts the obvious answer.

**On screen:** The same results screen, now for Three more inspectors. Completion time is unchanged. Total cost is marked worse, in red.

**Spoken:** "And three more inspectors? Completion doesn't move. Not by a minute. Cost goes up nine percent. More people only help where a stage is actually waiting for capacity."

**Delivery:** A small, knowing smile in the voice. Short sentences, each with its own beat.

### Scene 7: An honest voice (1:56–2:17)

**Feeling:** calm and trustworthy; the AI is the careful one in the room.

**On screen:** The AI Analyst with Claude's stored answer to "Why does halving parking time shorten completion so much?", tagged AI interpretation. The pointer opens **Evidence behind this answer**, and every figure Claude used is listed with its source.

**Spoken:** "Claude explains the result, but only from stored evidence, and every number it writes is checked against the simulation. Look closely: it says the evidence does not isolate a cause. It refuses to invent one. And if it ever states a number the results can't support, the built-in analyst steps in instead."

**Delivery:** Even and reassuring. Slow down on "Look closely". "It refuses to invent one." is said with quiet pride.

### Scene 8: The proof (2:17–2:34)

**Feeling:** confident but honest; no overselling.

**On screen:** The Accuracy screen: the typical error across four past dates, simulated against recorded. Then the per-date chart and table, with the misses shown as plainly as the hits.

**Spoken:** "But can you trust the engine? A backtest simulates past dates, changing nothing, and compares the result with what was recorded. On one real factory, imported privately, it's within about a fifth on packets finished... and we publish exactly where it's wrong."

**Delivery:** Treat the opening question as the one judges are thinking. End firmly on "exactly where it's wrong"; it is a point of pride, not an apology.

### Insert: Your own data (about 15 s, between Scenes 8 and 9)

**Feeling:** practical and inviting; the answer to "could my factory use this?".

**On screen:** The Import Data screen (`local/video/import-data.mp4`, recorded by `frontend/scripts/import-clip.mjs`): the two templates download, the orders and movements files are chosen and read, Check reports "Ready to import 2 orders, 3 packets, 4 stage visits", and the pointer comes to rest on Import.

**Spoken:** "And any factory can bring its own history. Two spreadsheets: orders, and where each packet went. One check runs every row through the same rules as live recording... and one click makes it history."

**Delivery:** Brisk and light after the weight of Scene 8. A small lift on "one click".

**Timing:** the film is 2:53, and Devpost asks for three minutes or less. Make room by trimming about 10 seconds elsewhere, for example the pause after the creature's five parts in Scene 2 or the glide in Scene 5.

### Scene 9: The close (2:34–2:53)

**Feeling:** warm and uplifting, with a little Halloween magic.

**On screen:** A "Built with Kiro" card: steering rules, eleven specs, hooks and agents. It fades to reveal the ghost futures chart, and as the narrator says "a night theme" the whole screen switches live to the Halloween palette: same chart, same numbers, darker night. The film ends on a card: RealityCheck, the tagline, Kiroween, Frankenstein category, and the repository link.

**Spoken:** "Built spec-first with Kiro: steering rules, eleven specs, and hooks. And for Kiroween, a night theme, with exactly the same contrast. RealityCheck. See the future... before you change the present."

**Delivery:** Unhurried and warm. Pause after "RealityCheck." and let the final line breathe.

## On screen, never spoken

- Small caption at 0:35: *"Demonstration factory: synthetic, calibrated on rounded patterns from one real
  factory."*
- Small caption at 2:20: *"Real-factory figures: 7-day windows, rounded; see docs/accuracy.md."*

## Checks before uploading

- Under 3:00 (the generated version is 2:53).
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

To use a recorded voice-over instead (the final video uses ElevenLabs clips, one per scene, named `01-hook.mp3`
to `09-close.mp3`), replace the `narrate` step with:

```sh
python -I tools/video/assemble.py voiceover docs/submission/narration.json <clips-dir> <work>/audio
```

It finds where each sentence starts from the pauses in the clips, so scenes and captions follow that voice.
`build` then merges the clips into one narration track (`narration.m4a`) and maps it onto the picture.

The output is `<work>/out/realitycheck-demo.mp4` (1920 × 1080, about 2:53) and `captions.srt` for the
upload. The video built on 19 November is kept outside the repository, in `local/video/`.
