# JVM benchmark results — 2026-07-27

First run of the harness added in
[`../designs/2026-07-27-jvm-benchmark-harness-design.md`](../designs/2026-07-27-jvm-benchmark-harness-design.md).

## Setup

| | |
|---|---|
| Trace | `cnz` (Sonic 2, Casino Night 1) — its replay test `TestS2CnzLevelSelectTraceReplay` is **green**, unlike every S3K replay |
| Mode | `update` (no rendering) |
| Window | 2000 warmup + **7469** measured frames per iteration |
| Repeats | 3 interleaved rounds × 3 in-process iterations = 36 measured passes |
| Machine | 32-core, pinned to CPUs 8–11 with `taskset`; Linux 7.1.4 |
| Engine | `d228ac56d`, one shared jar under every runtime |

The measured window is 7469 rather than the requested 10000 because the trace
ends there. The harness reported that per iteration rather than presenting a
short window as a full one; it is identical for every runtime, so the comparison
is unaffected.

**Determinism: one trajectory digest (`cf6995fe1dc1a47d`) across all 36 passes.**
Every runtime simulated identical work, so the timings compare like for like.

## Results (median across 3 rounds, best warm iteration)

| Runtime | warm p50 | vs base | p99 | max | throughput | cold p50 | cold penalty | GC count |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| Temurin 21 (G1) | 0.115 ms | — | 0.367 ms | 3.134 ms | 8157 fps | 0.139 ms | +21.4% | 5 |
| Temurin 25 (G1) | 0.114 ms | −0.3% | 0.360 ms | 3.110 ms | 8192 fps | 0.138 ms | +20.6% | 5 |
| **GraalVM 21 (G1)** | **0.104 ms** | **−8.7%** | **0.248 ms** | 2.672 ms | **9179 fps** | 0.158 ms | +50.8% | 2 |
| OpenJ9 21 (Semeru) | 0.183 ms | +59.9% | 0.518 ms | **1.843 ms** | 5190 fps | 0.204 ms | **+11.3%** | 20 |

Round-to-round spread of warm p50: Temurin 25 0.9%, Temurin 21 1.5%, OpenJ9 3.9%,
GraalVM 6.4%. Differences below ~2% are not resolvable here; the GraalVM and
OpenJ9 gaps are far outside it.

## Findings

**GraalVM wins, and wins by more in the tail than at the median.** 8.7% at p50 but
**32% at p99** (0.248 vs 0.367 ms). For a game the tail is the number that matters
— it is what a player perceives as a hitch — so the real gap is larger than a
median comparison suggests. It also caused the fewest collections (2 vs 5).

**Java 25 is a no-op for this workload.** −0.3% against Java 21 is inside the
noise floor. No reason to move for performance; no reason to avoid it either.

**OpenJ9 is the interesting trade, not simply the loser.** It is 60% slower at
the median and 4× the collections — but it has the **lowest worst-case frame time
of any runtime tested** (1.843 ms vs GraalVM's 2.672 and Temurin's 3.134), and by
far the smallest cold penalty (+11% vs GraalVM's +51%). It is doing many small
collections instead of few large ones, which is the right shape for a game even
though the throughput cost here is far too high to justify it.

**Warm-up is where GraalVM pays.** Its cold pass is the *slowest* of the three
HotSpot-family runtimes (0.158 ms, worse than Temurin's 0.139) before becoming
the fastest warm. That is the expected shape for a more aggressive JIT, and it is
a real cost for a program someone launches and plays for ten minutes.

**Audio dominates, which decides what this benchmark is measuring.** Per-section
medians (Temurin 21): `audio` 0.093 ms of a 0.115 ms frame — **~80%**. `objects`
0.017, `physics` 0.0015, everything else below 1 µs. So a JVM ranking on this
trace is largely a statement about how well each runtime JITs SMPS/FM synthesis.
GraalVM's win is mostly there (0.080 vs 0.093 ms).

## Caveats

- **The frames-to-steady-state column is not usable from this run.** Every runtime
  reported 9149–9169 of 9469 total frames — i.e. right at the end of the series,
  which per the detector's documented reading means the run never demonstrated
  settling, not that it settled at frame 9149. The cold-vs-warm p50 comparison
  above is the usable warm-up signal. A longer trace is needed for the other.
- **`physics` inverts the overall ranking** (GraalVM 0.0041 ms vs Temurin 0.0015).
  At single-digit microseconds this is near the measurement floor and inlining
  decisions move work across section boundaries, so it should not be read as
  "GraalVM is slower at physics" without a dedicated measurement.
- **This is one trace on one machine.** S2 CNZ was chosen because its replay test
  is green; the S3K release slice could not be benchmarked honestly because all 13
  S3K replays are currently red, and a diverging trace measures whichever route
  the engine wandered down.
- Results are `update` mode. Nothing here says anything about rendering.

## Recommendation

No engine change follows from this. If a runtime is ever bundled or recommended,
**GraalVM 21** is the current best choice on throughput and tail latency, with the
caveat that it warms up slowest. Re-run before any such decision — and re-run on
an S3K trace once the S3K replays are green, since S3K is the release slice and
this measurement is a Sonic 2 proxy for it.

Raw reports (JVM, flags, trace, and window all self-recorded) are reproducible via:

```bash
scripts/bench-jvms.sh --trace cnz --mode update --rounds 3 --iterations 3 --cpus 8-11 \
    --jvm 'temurin21|<jdk>|-XX:+UseG1GC' --jvm 'temurin25|<jdk>|-XX:+UseG1GC' \
    --jvm 'graalvm21|<jdk>|-XX:+UseG1GC' --jvm 'openj9-21|<jdk>|'
```
