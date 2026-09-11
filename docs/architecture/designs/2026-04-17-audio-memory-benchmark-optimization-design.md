# Audio Memory Benchmark Optimization Design

**Date:** 2026-04-17

## Goal

Extend the existing audio benchmark workflow so every benchmark run reports meaningful memory metrics alongside throughput, then use that benchmark to drive a one-at-a-time optimization campaign for four audio-memory candidates while preserving sample-accurate output.

## Scope

This design covers:

- Expanding the existing benchmark tests in `AudioRegressionTest`
- Defining the benchmark memory metrics and how they are measured
- Defining the baseline and comparison workflow
- Defining the execution order and acceptance flow for ranked candidates 1-4

This design does not cover:

- External profiling tooling such as JFR
- Gameplay-wide memory instrumentation outside the existing audio benchmark tests
- Ranked candidates beyond 1-4

## Existing Context

The current benchmark entry points live in `src/test/java/com/openggf/audio/AudioRegressionTest.java`:

- `benchmarkAudioRendering()` measures steady-state throughput for music rendering
- `benchmarkResultsTallyStressRendering()` measures a more allocation-sensitive stress workload with exact scheduled read boundaries

Sample-accuracy and batching parity are already protected by exact-output and identity tests, especially:

- `AudioRegressionTest` hybrid vs sample-accurate identity checks
- `TestSmpsDriverBatching`
- `TestSmpsSequencerBatchBoundaries`
- `TestBlipResampler`
- `TestVirtualSynthesizerMix`
- YM exact-output tests

The codebase also already contains reusable memory-measurement logic in `com.openggf.debug.MemoryStats`, but that class is oriented around runtime overlay reporting and rolling windows. It is not a good direct fit for isolated benchmark runs because it uses reused snapshot objects, cumulative JVM totals, and rolling-window allocation-rate calculations.

## Benchmark Design

### Benchmark Principle

The timed benchmark workload must remain unchanged. Memory measurement must not perturb the hot loop enough to invalidate timing comparisons.

To preserve that:

- The timed section will only capture pre-run and post-run snapshots
- Heap sampling for peak measurements will happen in a separate untimed replay
- No `System.gc()` calls will be used
- No benchmark read sizes, warmup counts, driver modes, or scheduled boundaries will change

### Metrics To Add

Each benchmark run will report:

- Elapsed wall-clock time
- Existing throughput summary (`ms per second of audio` or `ms per stress run`)
- Main-thread allocated bytes delta during the timed run
- Heap used before the timed run
- Heap used after the timed run
- Heap used delta across the timed run
- GC count delta across the timed run
- GC time delta across the timed run
- Derived normalization:
  - bytes per read
  - MB per audio-second for the music benchmark
  - MB per stress run for the tally benchmark
- Peak heap used during a separate untimed replay of the same workload

### Metrics To Avoid As Primary Decision Inputs

These values may still be printed, but they will not drive keep/revert decisions by themselves:

- Raw heap delta without allocation delta context
- Rolling allocation-rate metrics from the runtime overlay stack
- Any metric requiring forced GC

The most important memory metric for comparison is allocated-bytes delta because it is not invalidated by whether GC happened during the run.

## Measurement Shape

### Whole-Run Measurement

For both benchmark entry points:

1. Build the exact benchmark workload as today
2. Warm up exactly as today
3. Build a fresh timed context exactly as today
4. Capture a `before` snapshot
5. Run the timed benchmark unchanged
6. Capture an `after` snapshot
7. Print timing and memory deltas

### Untimed Peak-Heap Replay

After the timed section completes:

1. Rebuild the same workload in a fresh untimed context
2. Replay it without measuring elapsed time
3. Sample heap-used after each `driver.read(...)`
4. Record the maximum observed heap-used value
5. Print this as `peak heap during replay`

This gives a retained-memory signal without contaminating the timed result.

## Benchmark Implementation Approach

### Recommended Approach

Add a small benchmark-only helper in test code that captures memory snapshots and computes benchmark-grade deltas.

This helper should be:

- Instance-based, not singleton-based
- Engine-agnostic
- Safe to use from JUnit tests
- Focused on benchmark snapshots, not rolling overlay reporting

### Why Not Reuse `MemoryStats` Directly

`MemoryStats` is useful as a reference implementation for:

- thread allocated bytes
- heap used
- GC totals

But it should not be the benchmark API itself because:

- its snapshots are reused mutable objects
- its allocation rate is a rolling-window metric, not a run delta
- its top-allocator reporting is frame-window oriented
- it is designed around runtime update cadence rather than benchmark lifecycle

The benchmark helper may still mirror its underlying data sources and formulas where appropriate.

## Optimization Campaign Workflow

### Baseline Phase

1. Implement the benchmark memory metrics
2. Run the audio regression gate
3. Run the benchmark tests 10 times
4. Record raw runs and summary statistics in a baseline document

The baseline will become the comparison anchor for every later step.

### Candidate Execution Phase

Candidates will be handled one at a time in this order:

1. `BlipResampler` history-window/right-sizing investigation
2. `SmpsDriver.renderChunk()` scratch reuse fix
3. `SmpsSequencer` hot-path inline-array hoisting
4. `VirtualSynthesizer` scratch/mix storage consolidation

For each candidate:

1. Add or tighten regression coverage if needed
2. Implement only that candidate
3. Run the audio regression gate
4. Run the benchmark tests 10 times
5. Compare results against:
   - the original baseline
   - the most recent accepted step
6. Decide whether to keep or revert the candidate
7. Record the result in the step-results document

### Acceptance Rules

A candidate may be kept only if:

- the regression gate passes
- sample-accurate behavior remains intact
- there is no unacceptable benchmark regression

Correctness is the hard gate. Performance and memory wins are secondary.

If a candidate improves memory metrics but materially regresses the primary timing benchmark, it should be reverted unless there is an explicit decision to prioritize memory over throughput for that step.

## Candidate-Specific Constraints

### Candidate 1: `BlipResampler` History Window

This is the highest-risk candidate because it touches the biggest retained-memory block in the audited audio path and sits near exact-output behavior.

Constraints:

- Do not change interpolation arithmetic, coefficient type, phase math, or rounding
- Prove the required history horizon before shrinking or adapting the ring
- Preserve exact-output behavior under existing `TestBlipResampler` coverage

### Candidate 2: `SmpsDriver.renderChunk()` Scratch Reuse

This is the clearest allocation-churn issue and the lowest-risk candidate with likely visible memory results.

Constraints:

- Do not change chunk-boundary logic
- Do not change hybrid vs sample-accurate decision points
- Preserve exact read counts and output parity

### Candidate 3: `SmpsSequencer` Inline Array Hoisting

This is low risk and should be treated as an allocation-cleanup step.

Constraints:

- No behavioral logic changes
- Only remove repeated hot-path array construction
- Preserve all existing exact-output and batching behavior

### Candidate 4: `VirtualSynthesizer` Scratch/Mix Consolidation

This candidate is moderate risk because mix ordering and clamp behavior are observable.

Constraints:

- Preserve exact FM+PSG output
- Do not change final clamp/store behavior
- Keep the existing mixed-output exact-output tests green

## Reporting

Two benchmark documents will be maintained:

- Baseline document with 10 raw runs plus summary statistics
- Step-results document with before/after deltas and keep/revert decisions for each candidate

Each step entry will include:

- files changed
- regression status
- benchmark-run count
- timing before/after/delta
- memory before/after/delta
- notes
- keep/revert decision

## Risks

- Heap-used metrics are noisy and GC-sensitive
- Allocation metrics may be unavailable on some JVMs if thread allocation counters are unsupported
- Candidate 1 may expose hidden assumptions about minimum resampler history depth
- Candidate 4 can accidentally change observable mixing semantics even if the code looks mechanically equivalent
- Benchmark-time memory instrumentation can invalidate timing if sampling leaks into the timed hot loop

## Risk Mitigations

- Treat allocated-bytes delta as the primary memory signal
- Print `N/A` rather than failing when a JVM memory counter is unsupported
- Keep peak-heap sampling in a separate untimed replay
- Keep candidate scope isolated to one optimization at a time
- Run the full audio regression gate after every candidate

## Success Criteria

This work is successful if:

1. The benchmark suite reports meaningful timing and memory metrics for every run
2. A 10-run baseline is recorded using those new metrics
3. Ranked candidates 1-4 are implemented and evaluated one at a time
4. Every kept candidate preserves sample-accurate output under the regression gate
5. Final results clearly distinguish throughput wins from memory wins
