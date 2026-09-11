# Development-timeline clips — house style

These are the GIFs and audio clips shown in
[`docs/project/development-timeline.md`](../../project/development-timeline.md)
(the visual companion to
[`docs/project/ai-journey.md`](../../project/ai-journey.md)). Each one is a real
dev-build screen recording, trimmed and converted to a small, consistent format.

If you're adding a clip, use [`make_clip.py`](make_clip.py) so it matches the rest of the
gallery exactly — same size, frame rate, palette, and trim behaviour.

## Prerequisites

- **ffmpeg + ffprobe** on `PATH` (Windows: `winget install Gyan.FFmpeg`).
- **Python 3** (standard library only).

## The house style

| Setting | GIF | Audio (`--audio`) |
|---|---|---|
| Width | 320px (`scale=320:-2:flags=lanczos`) | — |
| Frame rate | **8 fps** (bump to ~18 for fast motion) | — |
| Length | **4.0 s** | **12.0 s** |
| Trim window | centred on the source's **midpoint** | centred on the **midpoint** |
| Palette | `palettegen=max_colors=64` | — |
| Dither | `paletteuse=dither=bayer:bayer_scale=4` | — |
| Codec | GIF | `libmp3lame -b:a 112k` |

Why the midpoint trim: recordings usually have a few dead seconds at the start (waiting to
confirm the capture is rolling) and trailing nothing at the end, so the middle is where the
interesting moment almost always is.

When a build is only interesting for its **sound** (early audio-engine work), ship a short
`.mp3` instead of a pointless silent GIF — pass `--audio`.

## Adding a clip

1. Grab the source recording (`.mp4`).
2. Encode it to the house style:
   ```bash
   python make_clip.py --src "path/to/recording.mp4" --slug my-clip-name
   #   fast motion: add --fps 18
   #   sound-only:  add --audio
   ```
   It writes `my-clip-name.gif` (or `.mp3`) into this folder and prints the exact
   markdown snippet to paste.
3. Paste that snippet into the right chronological section of
   [`docs/project/development-timeline.md`](../../project/development-timeline.md), filling in the date and caption.
4. Keep the whole gallery in a **~15–30 MB** budget. If a clip is heavy, drop the frame
   rate or length before reaching for a bigger size.

## Conventions

- **Slugs** are kebab-case and double as the filename (`cnz-flipper` → `cnz-flipper.gif`).
- Captions are short, plain, and honest — bugs and all. No self-congratulation.
- The **prologue section** at the top of `DEVELOPMENT_TIMELINE.md` (the 2015/2024 clips that
  also live in `../ai-journey/`) is **hand-maintained** — don't auto-regenerate over it.

The original ~40-clip gallery was batch-generated from a manifest; that one-off script lives
in the git history if you ever need to reproduce the whole set at once. For day-to-day
contributions, `make_clip.py` is the supported path.
