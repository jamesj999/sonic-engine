# JVM benchmark harness design

**Date:** 2026-07-27
**Status:** implemented

## Problem

The engine has to hold a frame budget on whatever JVM a player happens to have
installed, and we have no way to say which runtime it holds that budget best on.
The live performance overlay already times every subsystem, but its numbers are
rolling 60-frame means sampled from an interactive session — the right shape for
a human reading a HUD, and unusable as a measurement:

- The live loop is **paced to 60Hz**. The engine finishes a frame well inside its
  16.6ms budget, so a paced run reports ~16.6ms on every runtime and resolves
  nothing.
- A **mean erases the tail**, and the tail is where runtimes differ. Two JVMs can
  post identical mean frame times while one stalls for 40ms twice a second.
- An interactive session is **not reproducible**. Different input means different
  work, so two sessions are not comparable even under one JVM.

## Approach

Replay a recorded trace headlessly, as fast as the machine allows, keeping every
frame's raw timings; summarise afterwards.

A trace is the right workload because it is the code that actually ships, on
input that actually occurs, and because every runtime is handed a bit-identical
sequence of frames. Nothing synthetic is needed and nothing is simulated twice.

### Measurement discipline

| Decision | Why |
|---|---|
| No pacing, no vsync, no sleep | A paced loop measures the pacing |
| Fixed frame window (warmup + measure), identical every iteration | Compares identical *work*, not identical wall time |
| Raw per-frame samples, percentiles computed afterwards | p99/max is where JVMs differ; a mean hides it |
| Per-section allocation tracking off by default | It costs a JMX call inside every measured section, and that call's cost is itself JVM-dependent — it would read as a fake timing difference |
| Fresh session per iteration | A second replay over a session whose objects have already run is a cheaper workload |
| Iteration 0 flagged cold, later iterations warm | The cold pass is the only place JIT climb is visible; folding it into a mean destroys both numbers |
| Best-of-warm, not mean-of-all | Desktop noise is additive — preemption can only make a pass slower — so the minimum is the closest estimate of true cost |
| Trajectory digest per run | See below |

### Determinism as a precondition

A cross-JVM timing table is only valid if every runtime executed the same work.
If one diverges mid-replay, the "faster" runtime may simply have simulated a
shorter route, and the whole table is meaningless while looking entirely healthy.

Each run therefore folds the player and camera state of every compared frame into
an FNV-1a digest. Matching digests mean the timings describe identical work; a
mismatch invalidates the comparison, and the generated report says so in place of
a conclusion. This is a determinism check for the benchmark, not a correctness
check for the engine — trace-replay tests own accuracy — but a trace that
diverges under one JVM is an engine bug worth knowing about.

### Modes

`--mode update` (default) drives gameplay with no rendering. This is the real JVM
comparison: physics, collision, object execution, and audio synthesis.

`--mode full` additionally renders each frame through the real `LevelRenderer`
path, reporting `render.bg`, `render.fg`, `render.sprites`, `render.hud`,
`render.fbo_compose`, `render.water_setup`, and `render.gpu_wait`.

The `render.*` sections other than `gpu_wait` measure the **CPU-side** work of
building and submitting draw calls; those calls return as soon as the commands
are queued. `render.gpu_wait` is the blocking `glFinish` — where the GPU cost
actually lands, and typically the largest single section in the whole frame
(0.137ms of a 0.432ms frame on an RX 9070 XT). It exists because without it that
cost sat inside the frame total while belonging to no section, so the breakdown
visibly failed to add up.

There is deliberately **no enclosing `render` section**. Profiler sections are
flat, not nested, so an outer `render` is implicitly closed by `LevelRenderer`'s
first inner section and reports a meaningless sliver — it read as 0.0017ms
against 0.0164ms for sprites alone before it was removed.

### What `full` mode does not cover

It renders the scene at 320×224 into a hidden offscreen window. Relative to
`Engine.draw`, it omits:

- the display shader library (`applyDisplayShaderPhase` at both SCENE and
  PRESENTATION phases) — CRT presets and similar
- the upscale to the player's real window size
- `uiPipeline.renderFadePass`, the VHS rewind effect pass
- `graphicsManager.runPendingRenderThreadTasks`
- the debug and performance overlays
- buffer swap and vsync

The omissions matter most together: a display shader's fragment cost scales with
output resolution, so the pass most likely to dominate a real player's frame is
both absent and, at 320×224, would be measured at a fraction of its true cost
even if present. `full` mode answers "does the renderer's CPU-side work differ
between JVMs" (it barely does) and "how much GPU wait does this scene incur on
this machine". It does not answer "how fast does OpenGGF render on this GPU."

Neither mode is a graphics benchmark. Ranking JVMs on `full` mode timings would
mostly rank graphics drivers.

Audio is presented once per gameplay frame in both modes. SMPS/FM synthesis is
tight scalar arithmetic and among the most JIT-sensitive work the engine does;
leaving it out would quietly measure a different engine. `--no-audio` opts out,
and a run that cannot present says so rather than reporting an audio-free frame
time as though audio had been included.

## What was built

### Instrumentation (reused, not duplicated)

- `debug/FrameSampleSink` — receives the raw, unaveraged per-frame timings the
  profiler has already collected, at the moment the frame closes.
- `PerformanceProfiler.setSampleSink` — one hook in `endFrame()`. Every existing
  instrumentation site (`render.*`, `rewind.*`, `audio`, `input`, `timers`) feeds
  the harness for free; the profiler takes on no storage policy of its own.
- `PerformanceProfiler.setAllocationTrackingEnabled` — separates allocation
  counting from section timing, for the bias described above.
- `MemoryStats` — the `com.sun.management.ThreadMXBean` cast is now guarded.
  Per-thread allocation counters are a HotSpot extension, so an unconditional
  cast made a foreign JVM a hard startup failure — precisely the JVM a
  cross-runtime benchmark wants to run on.
- `RecordingFrameDriver.setStepWrapper` — headless replay bypasses `GameLoop`, so
  without this the update side of the frame reports no sections at all. The
  wrapper defaults to the direct runner, so tests and capture pay nothing.

### `com.openggf.bench` (leaf package)

`SectionTimeline` (allocation-free raw recorder), `SectionTiming` (percentiles),
`SteadyStateDetector`, `TrajectoryDigest`, `GcSnapshot`, `JvmEnvironment`,
`BenchmarkReport` + Jackson IO, `BenchmarkComparison` (Markdown renderer).

The package depends only on `debug`, and only `tools` depends on it. Measurement
code must never become something gameplay code reaches for; the ArchUnit edge
ratchet records both edges deliberately.

### Tools

- `tools/TraceBenchmarkTool` — the CLI.
- `tools/BenchmarkCompareTool` — pure post-processing over report JSON, so it can
  run under any JVM without affecting what is being compared.
- `tools/TraceReplayDrive` — the per-frame drive extracted from
  `TraceCaptureTool` and now shared with the benchmark. Capture has already been
  burned once by reimplementing this: routing captures through the live
  `GameLoop` playback path silently desynced them from the recorded trajectory
  (AIZ rings 19 instead of 97 at the battleship loop). A second tool hand-rolling
  the same phase rules would drift the same way, and a benchmark that quietly
  simulates a shorter route produces numbers that look fine and mean nothing.
- `scripts/bench-jvms.sh` — the runtime matrix.

### Steady state

Frames-to-steady-state is arguably the more interesting cross-JVM number than
peak throughput. Every runtime gets to the same place eventually; what separates
them in practice is how long the player watches the interpreter first. A JVM 3%
slower at steady state that settles in a third of the frames is the better choice
for a program someone launches, plays for ten minutes, and quits.

Method: take the median of the final window as the settled cost, then find the
earliest window after which *every* later sampled window stays within tolerance.
Requiring all later windows to hold — rather than the first qualifying one —
stops a transient dip during warmup being read as convergence.

It is measured over the warmup and measured frames as one series. Measuring it
over the measured window alone would report prompt convergence for every runtime,
because the warmup already did the settling that was the thing worth timing.

A **short** run can report a settle point close to its own end — that is the
honest answer (the
engine was still getting faster when the run stopped), not a defect, but it is
not a useful comparison between runtimes. A 500+2000-frame smoke run settled at
frame 2140 of 2500 on both collectors tested. Use the defaults (2000 warmup,
10000 measured) for any figure that will be quoted.

## Usage

```bash
# one runtime
mvn exec:java "-Dexec.mainClass=com.openggf.tools.TraceBenchmarkTool" \
    "-Dexec.args=--trace aiz1 --json target/bench/temurin21-g1.json"

# the matrix, interleaved and core-pinned
scripts/bench-jvms.sh --trace aiz1 \
    --jvm 'temurin21-g1|/usr/lib/jvm/temurin-21|-XX:+UseG1GC' \
    --jvm 'temurin21-zgc|/usr/lib/jvm/temurin-21|-XX:+UseZGC -XX:+ZGenerational' \
    --jvm 'graal21|/usr/lib/jvm/graalvm-21|'
```

The script builds one jar and runs it under every runtime, so all of them execute
identical bytecode. Rounds are interleaved (A,B,C then A,B,C) rather than grouped
per runtime: CPU frequency and thermals drift over minutes, and grouping charges
that drift entirely to whichever runtime went last. Each round is written out
separately so the round-to-round spread is visible in the comparison instead of
being averaged into invisibility. Runs are pinned with `taskset` where available.

## Suggested matrix

| Runtime | What it tests |
|---|---|
| Temurin 21, G1 | Baseline — what most players have |
| Temurin 21, generational ZGC | Pause tails; the `rewind.*` sections are the allocation-heavy ones |
| GraalVM CE/EE 21 | A different JIT; often stronger on tight object loops |
| Temurin 25 | Newer C2 |
| OpenJ9 | Very different trade-offs — usually lower peak, lower footprint |

## Caveats

- **Do not tune the engine from these numbers without re-running the trace
  tests.** Accuracy outranks throughput; the trace-replay suite owns the former.
- `full` mode timings are a graphics-driver measurement wearing a JVM's name.
- The harness reports a truncated run rather than presenting a prefix as the
  whole thing, and the comparison surfaces that alongside trace/mode/window
  mismatches, above the tables those problems invalidate.

## Follow-ups

- Commit a baseline report per release so runtime regressions are visible across
  versions, not just across JVMs.
- A section-level allocation comparison (`--track-allocations`) is a separate
  question from timing and deserves its own report shape.
