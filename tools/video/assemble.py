"""Builds the demo video from narration and the recorded picture (spec 11, task 17).

    python -I tools/video/assemble.py narrate <narration.json> <audio-dir>
    python -I tools/video/assemble.py build <audio-dir> <out-dir> [ffmpeg-dir]

`narrate` voices each segment with edge-tts (Microsoft's online neural voices; only the narration text is
sent) and writes <id>.mp3, <id>.vtt (sentence cues) and durations.json. Then record the picture with
`node frontend/scripts/video.mjs <config.json>`, which writes picture.webm and timeline.json. `build` places
each segment's audio where its scene starts, burns in captions, and encodes realitycheck-demo.mp4 (1920 x 1080,
H.264 and AAC).
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
        subprocess.run([sys.executable, "-m", "edge_tts", "--voice", n["voice"], f"--rate={n['rate']}",
                        "--text", s["text"], "--write-media", str(mp3),
                        "--write-subtitles", str(audio / f"{s['id']}.vtt")], check=True, capture_output=True)
        durations[s["id"]] = probe(mp3, "ffprobe")
        print(f"{s['id']}: {durations[s['id']]:.1f} s")
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
    print(f"{out / 'realitycheck-demo.mp4'}: {n - 1} captions, {timeline['length']:.1f} s")


if __name__ == "__main__":
    if sys.argv[1] == "narrate":
        narrate(Path(sys.argv[2]), Path(sys.argv[3]))
    else:
        build(Path(sys.argv[2]), Path(sys.argv[3]), sys.argv[4] if len(sys.argv) > 4 else "")
