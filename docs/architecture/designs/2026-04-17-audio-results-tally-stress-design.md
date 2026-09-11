# Audio Results Tally Stress Design

**Date:** 2026-04-17

## Goal

Add a deterministic Sonic 2 results-tally audio stress case that:

- reproduces the real rapid `BLIP` SFX cadence used during bonus tally
- serves as a hybrid-vs-sample-accurate identity regression
- serves as a repeatable benchmark focused on frequent SFX starts rather than steady music rendering

## Problem

The current audio regression suite covers:

- music-only rendering
- a light mixed music/SFX case
- a music-heavy benchmark (`benchmarkAudioRendering()`)

It does not cover the specific workload created by the Sonic 2 results tally, where the game repeatedly starts the same short SFX at a fixed cadence while score bonuses count down into the main score.

That pattern is a useful stress case because it exercises:

- repeated `playSfx`-style sequencer creation
- frequent SFX overlap
- the no-music, SFX-dominated path that the current benchmark does not represent well

The existing Sonic 2 results logic already defines the target cadence:

- `AbstractResultsScreen.updateTally()` plays the tick sound every `getTallyTickInterval()` frames while tallying
- the default tally tick interval is `4` frames
- `SpecialStageResultsScreenObjectInstance` uses the same `4`-frame interval through `Sonic2SpecialStageConstants.RESULTS_TALLY_TICK_INTERVAL`

So the desired audio schedule is already known. The missing piece is a test/benchmark harness that reproduces that schedule directly in the audio layer.

## Constraints

### 1. Audio Pattern Must Match Gameplay Cadence

The stress case should reproduce the same SFX trigger cadence as the Sonic 2 tally path:

- `BLIP` every `4` game frames
- `60` Hz game-frame timing
- no background music
- one final `TALLY_END` trigger after the repeated tally ticks

The harness does not need to instantiate the real results-screen object as long as the trigger schedule, SFX IDs, and rendered audio pattern are the same.

### 2. Exact Trigger Timing Matters

The helper must schedule SFX starts at the intended frame boundaries, not merely at the next outer `driver.read(buffer)` call boundary.

If the harness only checks triggers once per `BUFFER_SIZE` chunk, the actual start times become buffer-aligned rather than results-screen-aligned. That would weaken both the benchmark and the identity regression.

### 3. Hybrid And Sample-Accurate Output Must Match Exactly

This new stress case is intended to validate the batching work under rapid SFX churn. `HYBRID` and `SAMPLE_ACCURATE` rendering must therefore be bit-identical for the same trigger schedule.

Any PCM mismatch is treated as a batching bug.

### 4. Keep The Benchmark Deterministic

The benchmark should not depend on score bookkeeping, object slide-in state, camera state, or any unrelated results-screen presentation behavior.

Only the trigger schedule matters for the audio workload.

## Recommended Approach

Add a direct audio-driver tally-schedule harness to `AudioRegressionTest`.

This harness will not construct a real results-screen object. Instead, it will:

- load the Sonic 2 `BLIP` and `TALLY_END` SFX data
- build an explicit results-tally trigger schedule in game frames
- convert those frame points into exact audio sample-frame boundaries
- render audio while injecting new SFX sequencers exactly at those boundaries

This approach is preferred over driving the real results-screen object because:

- the audio output depends only on the trigger schedule, not on rendering or UI state
- the test stays isolated inside the existing audio regression suite
- it avoids unnecessary runtime/object setup
- it keeps the benchmark deterministic and easy to compare across commits

## Proposed Schedule

Use a fixed Sonic 2 tally-stress schedule:

- repeated `BLIP` triggers at game frames `0, 4, 8, ... , 176`
- one `TALLY_END` trigger at game frame `180`
- render long enough for the final SFX tail to complete cleanly

This produces:

- `45` repeated `BLIP` starts over `3` seconds of game time
- one end-of-tally SFX
- a dense, repeatable overlap pattern without requiring any music

At the current default output rate of `44100` Hz and `60` Hz game timing:

- one game frame is `735` audio frames
- one tally interval is `2940` audio frames

Those values are exact at the current default sample rate, so the test can schedule the starts without fractional ambiguity.

## Proposed Architecture

### 1. Add Sonic 2 Tally SFX Constants To The Test

`AudioRegressionTest` should define numeric IDs for:

- `SFX_BLIP`
- `SFX_TALLY_END`

These sit alongside the existing ring/jump/spring constants already used by the file.

### 2. Add A Dedicated Tally Render Helper

Add a helper that renders a stress stream from:

- `SmpsDriver.ReadMode mode`
- total sample count
- a list of `BLIP` trigger game frames
- an optional `TALLY_END` trigger game frame

The helper should:

1. create a fresh `SmpsDriver`
2. set region to `NTSC`
3. set the requested read mode
4. load `BLIP` and `TALLY_END` SFX data once
5. walk forward through the output timeline
6. inject sequencers exactly when the current render position reaches each scheduled trigger boundary
7. copy rendered PCM into the final output array

### 3. Render In Subchunks Aligned To The Next Trigger Boundary

The helper must not simply call `driver.read(buffer)` in fixed `BUFFER_SIZE` chunks and then check whether a trigger was crossed.

Instead, each loop iteration should render only up to the next pending trigger boundary or the end of the output, whichever comes first. That keeps trigger timing exact.

Concretely:

- compute the next pending trigger frame in audio sample frames
- compute how many stereo samples remain until that boundary
- read only that many samples
- then inject the scheduled SFX sequencer

This makes the trigger stream independent of the outer benchmark buffer size.

### 4. Add A Hybrid Identity Regression

Add a new regression test:

- `testHybridReadModeMatchesFallbackForResultsTallyStress()`

It should:

- render the same tally stress schedule in `SAMPLE_ACCURATE`
- render the same tally stress schedule in `HYBRID`
- require `assertArrayEquals(...)`

This is the main correctness gate for the new stress workload.

### 5. Add A Dedicated Stress Benchmark

Add a new benchmark test:

- `benchmarkResultsTallyStressRendering()`

It should follow the same structure as the existing benchmark:

- optional warmup pass
- fresh driver for the timed section
- console output of `Audio render time: ...`
- console output of `Real-time factor: ...`
- a lenient upper-bound assertion so the benchmark still acts as a sanity guard

The benchmark should measure the full tally-stress schedule, not just one isolated `BLIP`.

### 6. Keep The Existing Music Benchmark

`benchmarkAudioRendering()` remains in place.

After this change, the suite should have two performance lenses:

- music-heavy rendering
- rapid SFX-start tally stress

That gives better coverage of the main real-world audio workloads.

## Test Plan

### 1. Identity Regression

Add:

- `testHybridReadModeMatchesFallbackForResultsTallyStress()`

Pass condition:

- exact PCM identity between hybrid and sample-accurate rendering

### 2. Benchmark

Add:

- `benchmarkResultsTallyStressRendering()`

Pass condition:

- benchmark completes successfully
- timing is printed in the same format as the existing benchmark
- performance remains below a broad sanity threshold

### 3. Existing Coverage Must Stay Green

The new stress case should not replace the current audio tests. It adds coverage alongside:

- music reference comparisons
- single-SFX reference comparisons
- mixed music/SFX identity regression
- the current music-heavy benchmark

## Rollout

1. Add the tally trigger schedule helper and the new SFX constants.
2. Add the new hybrid-vs-fallback identity regression first.
3. Run the single new test and verify that it fails if the scheduling is intentionally perturbed.
4. Keep the helper implementation only once the test passes cleanly in both read modes.
5. Add the dedicated tally-stress benchmark.
6. Run the new benchmark and the existing music benchmark together so before/after comparisons can use both.

## Non-Goals

- Driving the real results-screen object graph inside the audio benchmark
- Benchmarking score accumulation or UI animation
- Replacing the existing music benchmark
- Generalizing this into a cross-game results-screen benchmark in the first iteration
