"""Builds the demo video from narration and the recorded picture (spec 11, task 17).

    python -I tools/video/assemble.py narrate <narration.json> <audio-dir>
    python -I tools/video/assemble.py voiceover <narration.json> <clips-dir> <audio-dir> [ffmpeg-dir]
    python -I tools/video/assemble.py build <audio-dir> <out-dir> [ffmpeg-dir]

The narration comes from one of two places:

- `narrate` voices each segment with edge-tts (Microsoft's online neural voices; only the narration text is
  sent), at the segment's own rate and pitch.
- `voiceover` takes recorded clips, one per segment, named <id>.mp3 (for example from ElevenLabs). It finds
  each sentence's start from the pauses in the clip, matched to the segment's sentences in narration.json.

Both write <id>.mp3, <id>.vtt (sentence cues) and durations.json. Then record the picture with
`node frontend/scripts/video.mjs <config.json>`, which times each scene to those files and writes picture.webm
and timeline.json. `build` merges the clips into one narration track (each at the moment its scene starts),
burns in captions, and encodes realitycheck-demo.mp4 (1920 x 1080, H.264 and AAC) and narration.m4a.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path


def narrate(narration: Path, audio: Path) -> None:
    audio.mkdir(parents=True, exist_ok=True)
    n = json.loads(narration.read_text(encoding="utf-8"))
    durations = {}
    for s in n["segments"]:
        mp3 = audio / f"{s['id']}.mp3"
        rate = s.get("rate", n.get("rate", "+0%"))
        pitch = s.get("pitch", n.get("pitch", "+0Hz"))
        subprocess.run([sys.executable, "-m", "edge_tts", "--voice", s.get("voice", n["voice"]), f"--rate={rate}",
                        f"--pitch={pitch}",
                        "--text", s["text"], "--write-media", str(mp3),
                        "--write-subtitles", str(audio / f"{s['id']}.vtt")], check=True, capture_output=True)
        durations[s["id"]] = probe(mp3, "ffprobe")
        print(f"{s['id']}: {durations[s['id']]:.1f} s ({s.get('tone', '')})")
    (audio / "durations.json").write_text(json.dumps(durations, indent=1))
    print(f"speech {sum(durations.values()):.1f} s")


def probe(path: Path, ffprobe: str) -> float:
    out = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                         capture_output=True, text=True, check=True).stdout
    return float(out)


def stamp(seconds: float) -> str:
    h, rest = divmod(seconds, 3600)
    m, s = divmod(rest, 60)
    return f"{int(h):02}:{int(m):02}:{s:06.3f}".replace(".", ",")


def cue_seconds(value: str) -> float:
    return int(value[:2]) * 3600 + int(value[3:5]) * 60 + float(value[6:].replace(",", "."))


def sentences(text: str) -> list[str]:
    """A segment's sentences, split as the captions show them (a "..." inside a sentence does not split it)."""
    return [t.strip() for t in re.split(r"(?<=[.?!])\s+(?=[A-Z])", text) if t.strip()]


def silences(clip: Path, ffmpeg: str) -> list[tuple[float, float]]:
    log = subprocess.run([ffmpeg, "-hide_banner", "-i", str(clip), "-af", "silencedetect=noise=-35dB:d=0.18",
                          "-f", "null", "-"], capture_output=True, text=True).stderr
    starts = [float(v) for v in re.findall(r"silence_start: ([\d.]+)", log)]
    ends = [float(v) for v in re.findall(r"silence_end: ([\d.]+)", log)]
    return list(zip(starts, ends))


def voiceover(narration: Path, clips: Path, audio: Path, ffmpeg_dir: str = "") -> None:
    ffmpeg = str(Path(ffmpeg_dir) / "ffmpeg") if ffmpeg_dir else "ffmpeg"
    ffprobe = str(Path(ffmpeg_dir) / "ffprobe") if ffmpeg_dir else "ffprobe"
    audio.mkdir(parents=True, exist_ok=True)
    n = json.loads(narration.read_text(encoding="utf-8"))
    durations = {}
    for s in n["segments"]:
        clip = clips / f"{s['id']}.mp3"
        (audio / clip.name).write_bytes(clip.read_bytes())
        length = probe(clip, ffprobe)
        durations[s["id"]] = length
        quiet = silences(clip, ffmpeg)
        # Speech runs from the end of any leading silence to the start of any trailing one.
        first = quiet[0][1] if quiet and quiet[0][0] <= 0.05 else 0.0
        last = quiet[-1][0] if quiet and quiet[-1][1] >= length - 0.05 else length
        gaps = [(a, b) for a, b in quiet if a > first + 0.3 and b < last - 0.3]
        parts = sentences(s["text"])
        # Each sentence boundary is expected where its share of the characters falls; take the nearest pause.
        total = sum(len(t) for t in parts)
        bounds, used, done = [], set(), 0
        for t in parts[:-1]:
            done += len(t)
            expected = first + (last - first) * done / total
            options = [(abs((a + b) / 2 - expected), i) for i, (a, b) in enumerate(gaps)
                       if i not in used and (not bounds or (a + b) / 2 > bounds[-1][1])]
            if options and min(options)[0] < 2.5:
                i = min(options)[1]
                used.add(i)
                bounds.append(gaps[i])
            else:
                bounds.append((expected, expected))
        starts = [first] + [b for _, b in bounds]
        ends = [a for a, _ in bounds] + [last]
        cues = [f"{k + 1}\n{stamp(a)} --> {stamp(b)}\n{t}\n" for k, (a, b, t) in enumerate(zip(starts, ends, parts))]
        (audio / f"{s['id']}.vtt").write_text("\n".join(cues), encoding="utf-8")
        matched = sum(1 for a, b in bounds if a != b)
        print(f"{s['id']}: {length:.1f} s, {len(parts)} sentences, {matched}/{len(parts) - 1} pauses found")
    (audio / "durations.json").write_text(json.dumps(durations, indent=1))
    print(f"speech {sum(durations.values()):.1f} s")


def build(audio: Path, out: Path, ffmpeg_dir: str = "") -> None:
    ffmpeg = str(Path(ffmpeg_dir) / "ffmpeg") if ffmpeg_dir else "ffmpeg"
    timeline = json.loads((out / "timeline.json").read_text())
    starts = timeline["segments"]

    captions, n = [], 1
    for seg, start in starts.items():
        text = (audio / f"{seg}.vtt").read_text(encoding="utf-8")
        for a, b, line in re.findall(r"(\d\d:\d\d:\d\d,\d\d\d) --> (\d\d:\d\d:\d\d,\d\d\d)\r?\n(.+?)(?:\r?\n\r?\n|\Z)",
                                     text, re.S):
            captions.append(f"{n}\n{stamp(start + cue_seconds(a))} --> {stamp(start + cue_seconds(b) - 0.05)}\n"
                            f"{line.strip()}\n")
            n += 1
    (out / "captions.srt").write_text("\n".join(captions), encoding="utf-8")

    style = ("FontName=Segoe UI,FontSize=17,PrimaryColour=&H00FFFFFF,OutlineColour=&H80000000,BorderStyle=4,"
             "BackColour=&H99000000,Outline=1,Shadow=0,MarginV=28")
    graph = [f"[0:v]scale=1920:1080:flags=lanczos,subtitles=captions.srt:force_style='{style}'[vout]"]
    graph += [f"[{i + 1}:a]adelay={int(t * 1000)}|{int(t * 1000)}[a{i}]" for i, t in enumerate(starts.values())]
    graph.append("".join(f"[a{i}]" for i in range(len(starts))) + f"amix=inputs={len(starts)}:normalize=0[aout]")
    (out / "filter.txt").write_text(";".join(graph))

    inputs = ["-i", "picture.webm"]
    for seg in starts:
        inputs += ["-i", str((audio / f"{seg}.mp3").resolve())]
    subprocess.run([ffmpeg, "-y", "-v", "error", *inputs, "-/filter_complex", "filter.txt", "-map", "[vout]",
                    "-map", "[aout]", "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-r", "30", "-c:a", "aac", "-b:a", "160k", "-t", f"{timeline['length']:.2f}",
                    "-movflags", "+faststart", "realitycheck-demo.mp4"], cwd=out, check=True)
    subprocess.run([ffmpeg, "-y", "-v", "error", "-i", "realitycheck-demo.mp4", "-vn", "-c:a", "copy",
                    "narration.m4a"], cwd=out, check=True)
    print(f"{out / 'realitycheck-demo.mp4'}: {n - 1} captions, {timeline['length']:.1f} s; merged track narration.m4a")


if __name__ == "__main__":
    if sys.argv[1] == "narrate":
        narrate(Path(sys.argv[2]), Path(sys.argv[3]))
    elif sys.argv[1] == "voiceover":
        voiceover(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), sys.argv[5] if len(sys.argv) > 5 else "")
    else:
        build(Path(sys.argv[2]), Path(sys.argv[3]), sys.argv[4] if len(sys.argv) > 4 else "")
