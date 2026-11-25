# Video script (under three minutes)

The published film is version 2, **2:56**, at https://youtu.be/Rw4WrUri5G0. It uses an ElevenLabs
voice-over over the RealityCheck Film picture. It adds Scene 9, Import Data, to the first version (2:49),
and stays under Devpost's three-minute limit.

The times below are the ElevenLabs voice timings (`local/video/captions-elevenlabs.srt`). Scenes after the
insert move by its length. The numbers are from a run on 19 November 2026. The demonstration is seeded
relative to the day it starts, so **read the figures on screen** when recording and change the narration to
match. Don't read them aloud from this page.

## Before recording

1. Reset the demonstration: Settings, Demonstration data, **Reset demo data** (or `POST /api/demo/reset`).
2. Turn Claude on (`AI_PROVIDER=anthropic`, key in `.env`), so the analyst's answer is Claude's.
3. In Scenario Lab, run **Current factory operations**, **Park half as long** and **Three more inspectors**
   once each, so that results are ready and the recording does not wait.
4. On Accuracy, run a backtest once.
5. On AI Analyst, with run *Park half as long* and comparison *Current factory operations*, ask:
   *"Why does halving parking time shorten completion so much, and what should we watch?"*
6. Set the browser zoom to 100% and the professional theme. Use a clean profile (no extensions or bookmarks
   bar).

## Script

Ten scenes, about 420 spoken words. Each scene describes what the viewer sees, how the moment should feel,
and the line that is spoken. Scenes 1 to 8 and 10 are in `narration.json`. Scene 9 is spliced in by
`tools/video/insert.py`.

### Scene 1: The question (0:02–0:22)

**Feeling:** curious and a little puzzled, as if noticing something odd for the first time.

**On screen:** The Overview of Surat Diamond Works: fifty-six orders in progress, packets queued at every stage. Everything looks busy and normal. After a few seconds the view drifts down to the stage-by-stage queues, inviting the viewer to wonder what is really going on.

**Spoken:** "Seventeen days. That's how long a rough diamond packet takes to reach dispatch in this factory... yet people work on it for only a few hours. So where does all that time go? And what if you could go back to last week... and change one decision? Meet RealityCheck: a time machine for the factory floor."

**Delivery:** Open slowly on "Seventeen days." Let the pause after "dispatch in this factory" hang before the contrast. Lift on "Meet RealityCheck".

### Scene 2: The creature (0:23–0:46)

**Feeling:** playful and dramatic, a Frankenstein reveal.

**On screen:** A dark title card, "RealityCheck: a Frankenstein of five parts". The parts appear one at a time, each as it is named:
- the skeleton (append-only history);
- the heart (deterministic simulation engine);
- the blood (a real factory's patterns);
- the voice (the AI analyst);
- the face (one app where every number is tagged).

**Spoken:** "It's a Frankenstein. Five parts that were never meant to share a body. A history that can never change the past. A simulation engine that never guesses. Patterns from a real factory's month of data. An AI analyst that is forbidden to calculate. And one app, where every number tells you exactly where it came from."

**Delivery:** A slightly lower, theatrical voice. Give each part its own beat, and lean on "forbidden".

### Scene 3: The haunting (0:47–1:02)

**Feeling:** revealing and slightly ominous, like uncovering where the trouble hides.

**On screen:** Where Time Goes shows lead time with and without parking, and a bar chart of where packet time is spent. The rough store has the long purple "held" bar. Then the Workflow screen glides down to **Haunted stages**, with the rough store ranked first.

**Spoken:** "Start with what really happened. Packets spend a huge share of their lives... parked in the rough store. On the workflow, these are the haunted stages: where packets lose the most time, with nobody working on them. And the worst one? Parking."

**Delivery:** Quieter, as if sharing a secret. A short pause before "Parking." so it lands as the answer.

### Scene 4: Back in time (1:03–1:21)

**Feeling:** excited; this is the idea the whole product is built on.

**On screen:** Scenario Lab with its six scenarios. Then **Park half as long**: its fork date and its single change, the rough store's holding time cut by 50%. Then **Three more inspectors**: inspection capacity from three to six people.

**Spoken:** "Now, let's go back in time. A scenario forks from a recorded moment... and changes just one decision. In this one, we halve the time packets sit parked. In this one, we hire three more inspectors instead. Then the engine replays the factory, from that moment forward, under each decision."

**Delivery:** Brighter and a touch quicker. Emphasise "one decision", and give the two "In this one" lines the same rhythm so they feel like a fair contest.

### Scene 5: The payoff (1:22–1:43)

**Feeling:** triumphant at the numbers, then a moment of wonder at the ghosts.

**On screen:** Simulation Results for Park half as long against the factory as it is. The headline metrics show completion time about 31% shorter and on-time delivery up. Then the view glides to **Ghost futures**:
- each order's real finish is a solid dot;
- its ghost is where it would have finished, joined to the dot by a faint line;
- green diamonds mark what history actually recorded.

**Spoken:** "And the result? Halving parking finishes the work about thirty percent sooner... and on-time delivery jumps from fifty-seven to sixty-two percent. Each solid dot is when an order really finishes. Its ghost... is when it would have finished. And where history already knows, a diamond shows what truly happened."

**Delivery:** A confident lift on the numbers. Then slow down and soften for the ghost lines; "Its ghost..." is the most atmospheric moment in the film.

### Scene 6: The twist (1:43–1:56)

**Feeling:** wry, a gentle surprise that undercuts the obvious answer.

**On screen:** The same results screen, now for Three more inspectors. Completion time is unchanged. Total cost is marked worse, in red.

**Spoken:** "And three more inspectors? Completion doesn't move. Not by a minute. Cost goes up nine percent. More people only help where a stage is actually waiting for capacity."

**Delivery:** A small, knowing smile in the voice. Short sentences, each with its own beat.

### Scene 7: An honest voice (1:56–2:16)

**Feeling:** calm and trustworthy; the AI is the careful one in the room.

**On screen:** The AI Analyst shows Claude's stored answer to "Why does halving parking time shorten completion so much?", tagged AI interpretation. The pointer opens **Evidence behind this answer**, which lists every figure Claude used with its source.

**Spoken:** "Claude explains the result, but only from stored evidence, and every number it writes is checked against the simulation. Look closely: it says the evidence does not isolate a cause. It refuses to invent one. And if it ever states a number the results can't support, the built-in analyst steps in instead."

**Delivery:** Even and reassuring. Slow down on "Look closely". Say "It refuses to invent one." with quiet pride.

### Scene 8: The proof (2:17–2:32)

**Feeling:** confident but honest; no overselling.

**On screen:** The Accuracy screen shows the typical error across four past dates, simulated against recorded. Then the per-date chart and table, with the misses shown as plainly as the hits.

**Spoken:** "But can you trust the engine? A backtest simulates past dates, changing nothing, and compares the result with what was recorded. On one real factory, imported privately, it's within about a fifth on packets finished... and we publish exactly where it's wrong."

**Delivery:** Treat the opening question as the one judges are thinking. End firmly on "exactly where it's wrong"; it is a point of pride, not an apology.

### Scene 9: Your own data (2:32–2:40, new in version 2)

**Feeling:** practical and inviting; the answer to "could my factory use this?".

**On screen:** The Import Data screen (`local/video/import-data.mp4`, recorded by `frontend/scripts/import-clip.mjs`):
1. The two templates download.
2. The orders and movements files are chosen and read.
3. Check reports "Ready to import 2 orders, 3 packets, 4 stage visits".
4. The pointer comes to rest on Import.

**Spoken:** "And any factory can bring its own history: two spreadsheets, checked by the same rules... and imported in one click."

**Delivery:** Brisk and light after the weight of Scene 8. A small lift on "one click".

**Timing:** the line runs 6.5 seconds, cut from the full ElevenLabs take of 25 November. The scene adds that line plus 2 seconds, so the film becomes 2:56. Keep the line under about 10 seconds. `tools/video/insert.py` splices it in: it speeds the clip up to fit, adds the captions both burned in and as a track, and refuses a line that would run over three minutes.

### Scene 10: The close (2:40–2:56)

**Feeling:** warm and uplifting, with a little Halloween magic.

**On screen:**
1. A "Built with Kiro" card: steering rules, eleven specs, hooks and agents.
2. It fades to reveal the ghost futures chart. As the narrator says "a night theme", the whole screen switches live to the Halloween palette: same chart, same numbers, darker night.
3. The film ends on a card:
   - RealityCheck and the tagline;
   - Kiroween, Frankenstein category;
   - the repository link.

**Spoken:** "Built spec-first with Kiro: steering rules, eleven specs, and hooks. And for Kiroween, a night theme, with exactly the same contrast. RealityCheck. See the future... before you change the present."

**Delivery:** Unhurried and warm. Pause after "RealityCheck." and let the final line breathe.

## On screen, never spoken

- A small caption at 0:35: *"Demonstration factory: synthetic, calibrated on rounded patterns from one real
  factory."*
- A small caption at 2:20: *"Real-factory figures: 7-day windows, rounded; see docs/accuracy.md."*

## Checks before uploading

- Under 3:00. The published film, version 2 with Scene 9, is 2:56.
- No real factory names, dates or screens from the private import appear. Only the demonstration factory is
  shown, and the Import Data clip uses the template's sample rows.
- No API key, `.env` file or terminal with secrets in frame.
- Captions on, as the uploaded `.srt` or the burned-in version.
- The video is public on YouTube, with its link in Devpost.

## How the film is built

The final film is the user's own RealityCheck Film picture with the ElevenLabs voice-over: one clip per
scene, named `01-hook.mp3` to `09-close.mp3`. The captions come from the pauses in those clips. Scene 9 is
added afterwards with `tools/video/insert.py`.

The earlier generated version can still be rebuilt. It uses Microsoft's neural voice *en-US-AvaNeural*
through `edge-tts`, and only the narration text is sent to the service. The picture is a scripted browser run
timed to each sentence.

```sh
pip install edge-tts                      # once; ffmpeg must also be installed (winget install Gyan.FFmpeg)
python -I tools/video/assemble.py narrate docs/submission/narration.json <work>/audio
# config.json: {"base": "http://localhost:3000", "runs": {"base", "park", "insp"}, "scenarios": {"park", "insp"},
#               "audioDir": "<work>/audio", "outDir": "<work>/out", "repo": "github.com/…"}
node frontend/scripts/video.mjs <work>/config.json      # from frontend/; prepare the app as above first
python -I tools/video/assemble.py build <work>/audio <work>/out
```

To use a recorded voice-over instead, replace the `narrate` step with:

```sh
python -I tools/video/assemble.py voiceover docs/submission/narration.json <clips-dir> <work>/audio
```

It finds where each sentence starts from the pauses in the clips, so scenes and captions follow that voice.
`build` then merges the clips into one narration track (`narration.m4a`) and maps it onto the picture.

The videos are kept outside the repository, in `local/video/`.
