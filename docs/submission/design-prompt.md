# Animated demo video: brief for Claude Design

Recreate the RealityCheck demo video as an **animated** product film, keeping the existing voice-over timing
exactly. Paste this brief together with `design-timeline.srt`, the screenshots in `docs/screenshots/`, and the
narration audio (`narration-elevenlabs.m4a`).

## The timeline is fixed

- `design-timeline.srt` is the master timeline. The audio starts at 0:00 and the film is **2:49.4** long
  (1920 × 1080, 30 fps).
- Each cue starting `VOICE:` is a spoken sentence, timed to the audio to the millisecond. Its visuals start at
  the cue's start time and finish by its end time. Never move, stretch or reorder a cue.
- `[NO VOICE]` cues are transitions between scenes, plus the opening and the outro.
- Between two spoken cues there is often a gap of about half a second. Hold the previous cue's last frame, or
  let its motion settle, through that gap.
- When a direction says "on the word …", place that beat where the word falls inside the cue. Estimate it from
  the word's position in the sentence; the audio is the final reference.

## Each cue says

- **SCREEN:** which app screen, or which title card, to show. Screenshot names refer to `docs/screenshots/`.
- **CAMERA:** zoom and pan, where 100% is the full 1920 × 1080 screen.
- **CURSOR:** what the pointer does.
- **MOTION:** what animates.

## Motion style

- **Camera.** Smooth, eased moves (ease-in-out, 0.6–2 s), zooming between 100% and 150%. No hard cuts inside a
  scene. Keep a gentle drift, about 1–2% per second, so the screen is never frozen. The one exception is cue
  "Not by a minute.", where the stillness is the point.
- **Cursor.** A clean arrow pointer on curved, natural paths at human speed. It never jumps. Hide it on title
  cards and full-screen overlays.
- **Hover.** Whenever the voice mentions part of the app, the cursor hovers it and the element reacts: it lifts
  (scale 1.03, 150 ms), takes a soft accent-colour glow border and a deeper shadow, and nearby content dims
  slightly. Buttons and links brighten.
- **Click.** A small press (scale 0.97), then a circular ripple from the pointer. Pages slide in from the right
  (300 ms). Dropdowns open and close with a short fade-and-drop.
- **Data.** Numbers roll up to their value. Bars grow from zero. Chart points pop in staggered. Badges such as
  "Actual", "Simulated" and "AI interpretation" pop with a light bounce.
- **Mood.** Professional and modern, with a light Halloween and Frankenstein flavour: lightning flickers,
  stitch marks, ghost wisps and glints. Keep these on the title cards and the key moments, never over the data.

## Look

- Match the screenshots: dark navy background, white text, a sidebar on the left, and a sans-serif typeface
  close to Inter.
- Accents as in the app: blue for simulated, green for actual and improved, amber for highlights, red for
  worse, purple for the held-time bar.
- The Halloween theme appears only from the cue "a night theme" onward, as in `09-halloween-ghost-futures.png`.
- No burned-in captions; captions go up separately as `captions-elevenlabs.srt`. Burn in only the two small
  text captions marked "not spoken" in the timeline.

## Content rules (do not break)

- Show only figures that appear in the screenshots or the narration. Don't invent numbers, orders, names or
  charts. If a figure is not visible in a screenshot, leave it out rather than making one up.
- Keep the tags that say where a number comes from ("Synthetic demo data", "Actual", "Simulated",
  "AI interpretation") visible wherever they appear in the app.
- The factory shown is the demonstration factory, *Surat Diamond Works*. Show no other factory names, logos or
  screens.
- Don't use any other company's logos or characters; "Built with Kiro" is plain text on a card.
