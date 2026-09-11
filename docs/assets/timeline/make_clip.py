#!/usr/bin/env python3
"""Encode one dev-build recording into a house-style timeline clip.

Produces either a short, palette-optimised GIF or (for sound-only builds) a
short MP3, using the exact settings the rest of the gallery was built with so
new contributions stay visually/sonically consistent. See README.md in this
folder for the full conventions and how to wire the result into
docs/project/development-timeline.md.

Requires ffmpeg + ffprobe on PATH.

Examples
--------
  # a 4s GIF, centred on the middle of the source recording
  python make_clip.py --src "raw/cool-bug 2026-05-01 12-00-00.mp4" --slug cnz-flipper

  # fast motion that looks choppy at 8 fps -> bump the frame rate
  python make_clip.py --src raw/vine.mp4 --slug aiz-vine --fps 18

  # a sound-only build -> short mp3 instead of a silent GIF
  python make_clip.py --src raw/psg.mp4 --slug psg-out-of-tune --audio
"""
import argparse
import os
import subprocess
import sys

# ---- house style (keep these in sync with README.md) ------------------------
WIDTH = 320            # px; height auto-derived, kept even
GIF_FPS = 8            # default; override with --fps for fast-motion clips
GIF_LEN = 4.0          # seconds
AUDIO_LEN = 12.0       # seconds
AUDIO_BITRATE = "112k"
GIF_MAX_COLORS = 64
DITHER = "dither=bayer:bayer_scale=4"
# -----------------------------------------------------------------------------


def run(cmd):
    subprocess.run(cmd, check=True)


def probe_duration(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", path],
        capture_output=True, text=True,
    )
    try:
        return float(out.stdout.strip())
    except ValueError:
        return 0.0


def centred_start(duration, length):
    """Centre the window on the middle of the clip.

    Recordings usually have a few dead seconds at the start (waiting to confirm
    the capture is rolling) and trailing nothing at the end, so the midpoint is
    where the interesting moment almost always lives.
    """
    return max(0.0, (duration - length) / 2.0)


def vf(fps):
    return f"fps={fps},scale={WIDTH}:-2:flags=lanczos"


def make_gif(src, out, start, length, fps):
    pal = out + ".pal.png"
    base = ["ffmpeg", "-v", "error", "-ss", f"{start:.2f}", "-t", f"{length}", "-i", src]
    try:
        run(base + ["-vf", vf(fps) + f",palettegen=max_colors={GIF_MAX_COLORS}", pal, "-y"])
        run(base + ["-i", pal,
                    "-lavfi", vf(fps) + f"[x];[x][1:v]paletteuse={DITHER}", out, "-y"])
    finally:
        if os.path.exists(pal):
            os.remove(pal)


def make_mp3(src, out, start, length):
    run(["ffmpeg", "-v", "error", "-ss", f"{start:.2f}", "-t", f"{length}", "-i", src,
         "-vn", "-c:a", "libmp3lame", "-b:a", AUDIO_BITRATE, out, "-y"])


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", required=True, help="source recording (.mp4)")
    ap.add_argument("--slug", required=True, help="kebab-case name, e.g. cnz-flipper")
    ap.add_argument("--audio", action="store_true", help="emit an .mp3 (sound-only build)")
    ap.add_argument("--fps", type=int, default=GIF_FPS, help=f"GIF frame rate (default {GIF_FPS})")
    ap.add_argument("--len", type=float, dest="length", default=None,
                    help=f"clip length s (default {GIF_LEN} gif / {AUDIO_LEN} audio)")
    ap.add_argument("--start", type=float, default=None,
                    help="start offset s (default: centred on the clip midpoint)")
    ap.add_argument("--out-dir", default=here, help="output dir (default: this folder)")
    args = ap.parse_args()

    if not os.path.exists(args.src):
        sys.exit(f"source not found: {args.src}")

    length = args.length if args.length is not None else (AUDIO_LEN if args.audio else GIF_LEN)
    duration = probe_duration(args.src)
    start = args.start if args.start is not None else centred_start(duration, length)
    os.makedirs(args.out_dir, exist_ok=True)

    if args.audio:
        out = os.path.join(args.out_dir, args.slug + ".mp3")
        make_mp3(args.src, out, start, length)
    else:
        out = os.path.join(args.out_dir, args.slug + ".gif")
        make_gif(args.src, out, start, length, args.fps)

    size = os.path.getsize(out)
    print(f"wrote {out}  ({size/1e6:.2f} MB)  start={start:.2f}s len={length}s")
    print("\nmarkdown to paste into docs/project/development-timeline.md:")
    if args.audio:
        print(f'**YYYY-MM-DD** — <caption>.\n\n'
              f'<audio controls src="assets/timeline/{args.slug}.mp3"></audio>\n\n'
              f'> ▶ **[Listen (mp3)](assets/timeline/{args.slug}.mp3)**')
    else:
        print(f'**YYYY-MM-DD** — <caption>.\n\n![{args.slug}](assets/timeline/{args.slug}.gif)')


if __name__ == "__main__":
    main()
