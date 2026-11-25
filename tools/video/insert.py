"""Splices the Import Data insert into the finished film, between Scene 8 (accuracy) and Scene 9 (Kiro).

    python -I tools/video/insert.py <film-dir> <voice.mp3> [<clip.mp4>]

<film-dir> holds the film of 19 November: "RealityCheck Film.mp4" (picture only), "RealityCheck Film - voice and
captions.mp4" (voice, captions burned in) and "captions-film.srt" (its captions). <voice.mp3> is the insert's
narration (ElevenLabs, the same voice as the other scenes); <clip.mp4> defaults to <film-dir>/import-data.mp4,
recorded by frontend/scripts/import-clip.mjs. Writes, next to them:

    RealityCheck Film v2 - voice and captions.mp4      captions burned in, as before
    RealityCheck Film v2 - voice, captions optional.mp4 a separate caption track
    captions-v2.srt                                     for the upload

The film is cut where Scene 8's voice has ended and its fade to the Kiro card begins. The insert fades in from
the accuracy table; the Kiro card then fades in over the end of the insert, from its first clean frame, so the
rest of the film keeps its own timing against its own voice. The result stays under three minutes.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

CUT = 152.36  # Scene 8's last word has ended; its fade to the Kiro card starts here
CLEAN = 153.99  # the Kiro card's first frame with no trace of the fade
BRIDGE = 0.8  # the Kiro card fades in over this long; its audio starts this much before CLEAN
FADE_IN = 0.4  # accuracy table to the insert
LEAD = 0.35  # silence before the insert's voice
TAIL = 0.45  # silence after it, before the Kiro scene's audio
LIMIT = 179.8  # under three minutes
TEXT = ("And any factory can bring its own history: two spreadsheets, checked by the same rules... "
        "and imported in one click.")
STYLE = ("FontName=Segoe UI,FontSize=17,PrimaryColour=&H00FFFFFF,OutlineColour=&H80000000,BorderStyle=4,"
         "BackColour=&H99000000,Outline=1,Shadow=0,MarginV=28")  # as tools/video/assemble.py burns them
# The clip is 1600 x 900; show the content area, most of the sidebar cropped as in the film's other scenes,
# panning down as the clip scrolls to the Check button.
CROP_W, CROP_H, CROP_X = 1440, 810, 160


def run(*args, cwd=None):
    subprocess.run(["ffmpeg", "-y", "-v", "error", *args], cwd=cwd, check=True)


def probe(path: Path) -> float:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                          str(path)], capture_output=True, text=True, check=True).stdout
    return float(out.strip())


def speech(path: Path) -> tuple[float, float, list[tuple[float, float]]]:
    """Where speech starts and ends in a clip, and the pauses inside it."""
    err = subprocess.run(["ffmpeg", "-v", "info", "-i", str(path), "-af", "silencedetect=n=-40dB:d=0.15", "-f",
                          "null", "-"], capture_output=True, text=True).stderr
    starts = [float(x) for x in re.findall(r"silence_start: ([\d.]+)", err)]
    ends = [float(x) for x in re.findall(r"silence_end: ([\d.]+)", err)]
    length = probe(path)
    quiet = list(zip(starts, ends + [length] * (len(starts) - len(ends))))
    first = quiet[0][1] if quiet and quiet[0][0] <= 0.05 else 0.0
    last = quiet[-1][0] if quiet and quiet[-1][1] >= length - 0.05 else length
    return first, last, [(a, b) for a, b in quiet if a > first + 0.3 and b < last - 0.3]


def stamp(t: float) -> str:
    ms = round(t * 1000)
    return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"


def seconds(text: str) -> float:
    h, m, s = text.replace(",", ".").split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def main(film: Path, voice: Path, clip: Path) -> None:
    first, last, pauses = speech(voice)
    spoken = last - first
    # Insert length on screen (from the start of its fade-in to the start of the Kiro card's fade-in).
    insert = FADE_IN + LEAD + spoken + TAIL + BRIDGE
    end = probe(film / "RealityCheck Film.mp4")
    total = CUT - FADE_IN + insert + (end - CLEAN)
    print(f"voice {spoken:.2f} s, insert {insert:.2f} s, film {total:.2f} s")
    if total > LIMIT:
        sys.exit(f"The film would run {total:.1f} s; shorten the insert's line by {total - LIMIT:.1f} s")
    shift = CUT - FADE_IN + insert - CLEAN  # added to every time after the insert
    voice_at = CUT + LEAD  # where the insert's speech starts in the new film

    # Captions: the film's own, the insert's (split at its longest pause, if any), the rest shifted.
    cues = re.findall(r"(\d\d:\d\d:\d\d,\d\d\d) --> (\d\d:\d\d:\d\d,\d\d\d)\r?\n(.+?)(?:\r?\n\r?\n|\s*\Z)",
                      (film / "captions-film.srt").read_text(encoding="utf-8-sig"), re.S)
    before = [(seconds(a), seconds(b), t.strip()) for a, b, t in cues if seconds(a) < CUT]
    after = [(seconds(a) + shift, seconds(b) + shift, t.strip()) for a, b, t in cues if seconds(a) >= CUT]
    # One caption per phrase (split after ":" and "..."), each break at one of the voice's longest pauses.
    parts = [p.strip() for p in re.split(r"(?:(?<=:)|(?<=\.\.\.))\s+", TEXT)]
    breaks = sorted(sorted(pauses, key=lambda p: p[0] - p[1])[:len(parts) - 1])
    if len(breaks) == len(parts) - 1:
        edges = [first] + [x for a, b in breaks for x in (a, b)] + [last]
        mine = [(voice_at + edges[2 * i] - first, voice_at + edges[2 * i + 1] - first, t) for i, t in enumerate(parts)]
    else:
        mine = [(voice_at, voice_at + spoken, TEXT)]
    every = before + mine + after
    srt = "\n".join(f"{i + 1}\n{stamp(a)} --> {stamp(b)}\n{t}\n" for i, (a, b, t) in enumerate(every))
    (film / "captions-v2.srt").write_text(srt, encoding="utf-8")
    # The insert's own captions, timed within the insert, for burning in.
    local = "\n".join(f"{i + 1}\n{stamp(a - CUT + FADE_IN)} --> {stamp(b - CUT + FADE_IN)}\n{t}\n"
                      for i, (a, b, t) in enumerate(mine))
    (film / "insert.srt").write_text(local, encoding="utf-8")

    # The insert's picture: the clip sped up to fit, cropped, panned as it scrolls, scaled to the film.
    length = probe(clip)
    speed = length / insert
    pan = f"30+60*min(max((t-{insert * 0.55:.2f})/{insert * 0.15:.2f},0),1)"
    picture = (f"setpts=PTS/{speed:.4f},fps=30,crop={CROP_W}:{CROP_H}:{CROP_X}:'{pan}',"
               f"scale=1920:1080:flags=lanczos,setsar=1,trim=duration={insert:.3f},settb=1/30")

    # Audio: the film's voice to CUT, the insert's voice after LEAD, then the film's voice from the Kiro scene.
    resume = CLEAN - BRIDGE
    gap = insert - FADE_IN - BRIDGE  # audio time from CUT to the Kiro scene's audio
    audio = (f"[0:a]atrim=0:{CUT},asetpts=PTS-STARTPTS[a0];"
             f"[2:a]atrim={first:.3f}:{last:.3f},asetpts=PTS-STARTPTS,aresample=44100,"
             f"aformat=channel_layouts=mono,adelay={int(LEAD * 1000)},apad,atrim=0:{gap:.3f}[a1];"
             f"[0:a]atrim={resume},asetpts=PTS-STARTPTS,apad,atrim=0:{end - resume:.3f}[a2];"
             f"[a0][a1][a2]concat=n=3:v=0:a=1[aout]")

    def video(source: int, burn: bool) -> str:
        insert_v = f"[1:v]{picture}" + (f",subtitles=insert.srt:force_style='{STYLE}'" if burn else "") + "[ins]"
        return (f"[{source}:v]trim=0:{CUT},setpts=PTS-STARTPTS,fps=30,settb=1/30,setsar=1[va];"
                f"{insert_v};"
                f"[{source}:v]trim=start={CLEAN},setpts=PTS-STARTPTS,tpad=start_duration={BRIDGE}:start_mode=clone,fps=30,settb=1/30,setsar=1[vb];"
                f"[va][ins]xfade=transition=fade:duration={FADE_IN}:offset={CUT - FADE_IN:.3f}[v1];"
                f"[v1][vb]xfade=transition=fade:duration={BRIDGE}:offset={CUT - FADE_IN + insert - BRIDGE:.3f}[vout]")

    encode = ["-map", "[vout]", "-map", "[aout]", "-c:v", "libx264", "-preset", "slow", "-crf", "18",
              "-pix_fmt", "yuv420p", "-r", "30", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart"]
    voiced = "RealityCheck Film - voice and captions.mp4"
    out_burned = "RealityCheck Film v2 - voice and captions.mp4"
    (film / "filter-burned.txt").write_text(video(0, True) + ";" + audio)
    run("-i", voiced, "-i", str(clip.resolve()), "-i", str(voice.resolve()),
        "-/filter_complex", "filter-burned.txt", *encode, out_burned, cwd=film)
    # Same cut from the picture-only film, with the voice of the voiced one and a caption track.
    (film / "filter-plain.txt").write_text(video(3, False) + ";" + audio)
    run("-i", voiced, "-i", str(clip.resolve()), "-i", str(voice.resolve()), "-i", "RealityCheck Film.mp4",
        "-/filter_complex", "filter-plain.txt", *encode, "plain.mp4", cwd=film)
    out_plain = "RealityCheck Film v2 - voice, captions optional.mp4"
    run("-i", "plain.mp4", "-i", "captions-v2.srt", "-map", "0", "-map", "1", "-c", "copy", "-c:s", "mov_text",
        "-metadata:s:s:0", "language=eng", "-movflags", "+faststart", out_plain, cwd=film)
    (film / "plain.mp4").unlink()
    for name in (out_burned, out_plain):
        print(f"{name}: {probe(film / name):.2f} s")
    print(json.dumps({"insert_starts": round(CUT - FADE_IN, 2), "insert_seconds": round(insert, 2),
                      "voice": [round(voice_at, 2), round(voice_at + spoken, 2)]}))


if __name__ == "__main__":
    film_dir = Path(sys.argv[1])
    main(film_dir, Path(sys.argv[2]), Path(sys.argv[3]) if len(sys.argv) > 3 else film_dir / "import-data.mp4")
