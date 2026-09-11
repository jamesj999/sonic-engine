---
name: trace-capture
description: Render an existing OpenGGF trace replay to synchronized lossless MKV video with the headless TraceCaptureTool.
---

# Trace capture

`com.openggf.tools.TraceCaptureTool` replays deterministically with offscreen GL
and encodes FFV1 video plus FLAC audio in MKV. It renders existing evidence;
it does not record a new ROM trace or prove parity.

Have the relevant user-supplied ROM discoverable by `RomManager`, `ffmpeg` on
`PATH`, and an existing trace directory. Do not rename/copy a ROM to satisfy an
example. The trace catalog defaults to `src/test/resources/traces`.

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.TraceCaptureTool" \
  "-Dexec.args=--trace <id|name|dir> --out-dir target/trace-videos"
```

Output is `capture-<trace-name>-<UTC timestamp>.mkv`. Use a task directory outside
the repository for durable captures; `target/trace-videos` is disposable output.

| Flag | Default | Meaning |
| --- | --- | --- |
| `--trace <id\|name\|dir>` | Required | Zero-based catalog index, directory name, or path |
| `--out-dir <dir>` | `CAPTURE_OUTPUT_DIR` | Destination directory |
| `--scale <n>` | `CAPTURE_SCALE=4` | Integer nearest-neighbor scaling of 320×224 |
| `--fps <n>` | `CAPTURE_FPS=60` | Capture/engine cadence; a region-pinned rate such as PAL 50 takes precedence |
| `--codec <name>` | `CAPTURE_CODEC=ffv1` | Video codec; changing it may change losslessness |
| `--no-ghosts` / `--ghosts` | `TRACE_SHOW_DESYNC_GHOSTS=true` | Desync ghost visibility |

HUD visibility uses config rather than CLI flags:
`TRACE_SHOW_GAME_HUD=true`, `TRACE_SHOW_DEBUG_HUD=false`; enabled debug panels
also obey `DebugOverlayToggle`. These visibility flags apply to active trace
sessions in both live playback and capture.

Audio uses the no-device `HeadlessSmpsAudioBackend`, fixed at 48 kHz stereo.
The capture runtime must be the sole PCM consumer: a live device backend draining
the same FIFO causes fast/out-of-sync audio. For silence, check `AUDIO_ENABLED`
and backend installation; inspect the output with
`ffmpeg -i <mkv> -af volumedetect -f null -`. Unexpected speaker output likewise
indicates that the wrong backend was installed.

For pipeline changes, follow `TraceCaptureTool` → `HeadlessGameBoot` →
`TraceReplayDriver` → `TraceCaptureSession` → `CaptureRecorder`/`FfmpegEncoder`.
`GlReadPixelsGrabber` captures RGBA; ffmpeg flips/scales it.
`DrainPcmAudioTap` drains the samples produced by each capture frame.
`TraceGhostHook` registers ghost rendering for `LevelRenderer`.

The driver deliberately continues through mismatches instead of pausing, with a
bounded capture loop. Check `target/trace-reports/` for parity evidence even when
the video finishes. For queue/dynamic-art debugging, use
`trace-replay-bug-fixing`; regenerate missing audit observations with
`bizhawk-headless-trace`. A video cannot supply missing fixture capabilities.
