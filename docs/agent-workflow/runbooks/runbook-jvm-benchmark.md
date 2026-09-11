# Runbook: benchmarking the engine (JVM comparison / performance regression)

Use when you need a defensible performance number: comparing Java runtimes,
checking whether a change cost frame time, or finding which subsystem dominates
a frame.

Do **not** use this to justify a gameplay change. Accuracy outranks throughput —
the `*TraceReplay` suite owns correctness, and a "faster" engine that fails a
trace is simply a broken engine.

Design rationale:
[`docs/architecture/designs/2026-07-27-jvm-benchmark-harness-design.md`](../../architecture/designs/2026-07-27-jvm-benchmark-harness-design.md)

## 0. Preconditions

- A ROM for the trace's game is discoverable in the project root (see the ROM
  table in [`CLAUDE.md`](../../../CLAUDE.md); do not rename or copy one).
- The trace you pick **passes its `*TraceReplay` test**. Benchmarking a trace
  that diverges measures whatever route the engine wandered down, not the game.
- Close anything that competes for CPU. A background build will show up as tail
  latency and read as a JVM difference.

## 1. Single run

```bash
mvn package -DskipTests
java -cp target/OpenGGF-*-jar-with-dependencies.jar \
    com.openggf.tools.TraceBenchmarkTool \
    --trace aiz1 --json target/bench/baseline.json
```

Useful flags:

| Flag | Default | Notes |
|---|---|---|
| `--mode update\|full` | `update` | `update` = no rendering; the real JVM comparison. `full` adds the renderer — see the caveat below. |
| `--warmup-frames N` | 2000 | Driven but not measured. |
| `--measure-frames N` | 10000 | Measured window. Same frames every iteration. |
| `--iterations N` | 3 | Iteration 0 is cold; the rest run warm off a fresh session. |
| `--label <name>` | JVM short label | What the comparison table calls this run. |
| `--json` / `--markdown` | — | Report output. |
| `--track-allocations` | off | Per-section allocation counts. **Skews timings** — see below. |
| `--no-audio` | audio on | Audio synthesis is real per-frame work and is measured by default. |
| `--paced` | off | Sleeps each *measured* frame to a 60 Hz cadence. Measures nothing about throughput; use it to give off-frame work (level preparers, save writer) the wall time it has in a real session before judging a frame hitch. |
| `--frame-log PATH` | off | CSV of every measured frame of iteration 0: trace row, act, frame ms and each profiler section. Percentiles hide a single slow frame; this is how to find and attribute one. |

## 2. JVM matrix

```bash
scripts/bench-jvms.sh --trace aiz1 \
    --jvm 'temurin21-g1|/usr/lib/jvm/temurin-21|-XX:+UseG1GC' \
    --jvm 'temurin21-zgc|/usr/lib/jvm/temurin-21|-XX:+UseZGC -XX:+ZGenerational' \
    --jvm 'graal21|/usr/lib/jvm/graalvm-21|'
```

Builds one jar and runs it under every runtime (identical bytecode), interleaves
rounds (A,B,C then A,B,C — grouping charges thermal drift to whoever went last),
pins with `taskset`, and renders the comparison. `--help` lists the rest.

## 3. Read the report in this order

1. **Determinism table first.** If the trajectory digests differ, the runtimes
   did not execute the same work and every timing above it is void. Stop and
   find the divergence — that is an engine bug, not a benchmarking artefact.
2. **Any "not directly comparable" banner** — different trace, mode, frame
   window, or a truncated sample.
3. **p99 and max**, not the mean. Two runtimes can post identical medians while
   one stalls for 12ms. That gap is the entire reason this harness exists.
4. **Warm-up table.** Frames-to-steady-state often matters more than peak for a
   program someone launches, plays, and quits.

## 4. Interpreting sections

- Sections are flat and mutually exclusive; there is deliberately no enclosing
  `render` section (it would be implicitly closed by the first inner one and
  report a meaningless sliver).
- `render.gpu_wait` is the blocking `glFinish` — where GPU cost lands, and
  usually the largest single section in a `full`-mode frame. The other
  `render.*` sections measure only CPU-side draw-call submission.
- `audio` is typically the largest section in `update` mode (~75% of a CNZ
  frame). SMPS/FM synthesis is tight scalar arithmetic, so a JVM ranking is
  largely a statement about how well each runtime JITs it.

## 5. Traps

- **Never pace the loop.** The engine finishes a frame well inside its 16.6ms
  budget, so a paced run reports 16.6ms on every runtime and measures nothing.
  The tool does not pace; do not "fix" that.
- **`--track-allocations` skews timing.** It costs a JMX call inside every
  measured section, and that call's cost is itself JVM-dependent, so it shows up
  as a fake timing difference. Use it when allocation is the question, not when
  timing is.
- **`full` mode is not a graphics benchmark.** It renders 320×224 offscreen and
  omits the display shader library, the upscale to the real window size, the
  fade/VHS passes, the overlays, and buffer swap. Ranking JVMs on it would
  mostly rank graphics drivers.
- **Short runs give a useless steady-state figure.** A settle point near the end
  of the series means the run was still getting faster when it stopped. Use the
  defaults for anything you intend to quote.
- **Iteration 0 is cold on purpose.** Do not average it in; the comparison
  already quotes the fastest warm pass.

## 6. Documentation obligations

A benchmark run on its own changes no code and needs no changelog. If you act on
one:

- Re-run the affected `*TraceReplay` tests and confirm the frontier did not move.
- Record the numbers in an audit under `docs/architecture/audits/` — a result
  without the JVM, flags, trace, and frame window is not reproducible. The JSON
  report already carries all four; commit it alongside.
- Follow [`../documentation-obligation-checklist.md`](../documentation-obligation-checklist.md)
  for trailers.
