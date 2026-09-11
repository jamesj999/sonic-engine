# Audio Batching Design

**Date:** 2026-04-16

## Goal

Reduce CPU usage in the SMPS audio render path, especially in GraalVM native builds, without increasing audio latency and without introducing gameplay-audio timing regressions.

## Problem

The current SMPS render path advances sequencers and renders the synthesizer one sample at a time:

- `SmpsDriver.read(short[] buffer)` iterates per output sample
- each sample advances every active `SmpsSequencer`
- each sample then renders the shared synthesizer through `VirtualSynthesizer.render(...)` using a 2-sample scratch buffer

`VirtualSynthesizer.render(...)` already accepts arbitrary-length stereo buffers, so the main bottleneck is not the synth API shape. The bottleneck is the per-sample sequencer advance loop in `SmpsDriver.read(...)`, which prevents the existing batch-capable synth path from being used effectively.

At the default 44.1 kHz output rate and 60 Hz NTSC tempo base, one tempo frame is 735 samples. That is a large enough steady-state window to justify a more careful batching design.

The codebase already contains `SmpsSequencer.advanceBatch(...)` and `SmpsSequencer.getSamplesUntilNextTempoFrame()`, but those helpers are currently tempo-only scaffolding, not evidence of a previously completed batching design.

## Constraints

### 1. No Added Latency

The design must not increase OpenAL queue depth, stream buffer size, or any other user-visible audio latency. Audio remains tightly coupled to gameplay actions.

### 2. Event Timing Is The Primary Contract

The batching change must not delay or reorder note starts, note stops, envelopes, modulation, DAC changes, or SFX/music transitions relative to gameplay timing.

### 3. Hardware Accuracy Matters More Than Matching Current Output

Current engine output is not assumed to be perfect hardware parity already. A small waveform difference is acceptable only if:

- timing remains correct
- no latency is added
- the new behavior is plausibly closer to hardware or at least not observably worse

For the batching experiment itself, batch mode and fallback mode should be bit-identical for the same active sequencer state. Any PCM difference between those two paths is treated as a batching bug unless proven otherwise.

### 4. Correctness Fallback Must Remain Available

The current single-sample render path stays in the codebase as the correctness fallback. The new batching logic is opportunistic, not mandatory.

### 5. No Cross-Call Audio Cache

The design must not introduce any persistent rendered-audio cache across `read()` calls. SFX start/stop and music changes must continue to become visible on the next normal buffer fill, as they do today.

## Recommended Approach

Use a hybrid batching scheduler inside `SmpsDriver.read(...)`.

The scheduler renders in chunks only when it can prove that the chunk does not cross an observable sequencer boundary. When it cannot prove safety, it falls back to the existing single-sample path.

This is preferred over:

- tempo-only batching, which is too weak because envelopes, modulation, track commands, and SFX completion can still create unsafe mid-frame boundaries
- full block-based rendering everywhere, which raises correctness risk too much for the initial attempt

## Proposed Architecture

### 1. `SmpsDriver.read(...)` Becomes A Hybrid Scheduler

`SmpsDriver.read(short[] buffer)` continues to own the full render loop, but instead of always processing one sample at a time, it repeatedly chooses one of two modes:

- `batch mode` for a safe chunk of samples
- `fallback mode` for one sample using the current logic

The driver loops until the requested output buffer is filled.

The existing `sequencersLock` remains held for the full `read()` call, matching current behavior. That lock scope is load-bearing because sequencer advancement, completion removal, lock release, and synth writes all currently occur under one consistent snapshot of active driver state.

### 2. Safe Chunk Planning

Before rendering the next region, the driver computes a safe chunk size across all active sequencers.

The chunk size is the minimum of:

- remaining output samples requested by the caller
- samples until the next tempo boundary for every active sequencer
- samples until the next observable event for every active sequencer

If the resulting chunk is above a minimum batching threshold, the driver uses batch mode. Otherwise it uses the existing single-sample path.

The initial threshold is a fixed implementation constant, `MIN_BATCH_SAMPLES = 32`. This value is intentionally conservative:

- it is far smaller than a full 735-sample tempo frame
- it avoids spending planning overhead on tiny windows
- it is small enough that most steady-state stretches can still batch

The value may be tuned later by measurement, but the first implementation should ship with an explicit constant rather than an implicit heuristic.

### 3. Observable Event Boundary API

`SmpsSequencer` needs a new query that answers, conservatively, how many samples remain until the next event that would change observable synth output or track state.

The API should be intentionally conservative. Returning too small a value only reduces performance. Returning too large a value risks broken timing.

Examples of boundaries that must stop a batch:

- fade processing step or fade completion
- note start
- note end
- fill-based note-off
- note-duration expiry
- PSG envelope step
- FM volume envelope step
- modulation step
- track command execution that writes synth state
- DAC start/stop or DAC rate-affecting change
- SFX `maxTicks` self-completion
- speed-multiplier-driven extra tempo processing
- sequencer completion

The boundary query must not try to predict cross-call gameplay events. It only reasons about the already-active sequencer state inside the current `read()` call.

### 4. Batch Advancement

When a chunk is safe:

- every active sequencer advances by the same chunk size using batch-aware advancement
- completed sequencers are removed after the advancement step
- the shared synthesizer renders that chunk in one call into the output buffer slice

The driver then recomputes the next chunk. It does not assume the next chunk has the same size, because active sequencers may have changed.

Sequencer completion during a projected chunk is itself a hard boundary. A batch may end at the exact sample where completion becomes true, but it may not cross beyond it. After the completion-boundary chunk is rendered:

- the completed sequencer is removed
- `releaseLocks(...)` is run immediately
- any `stopNote(...)`-driven chip silencing implied by completion has already happened inside the sequencer step that reached the boundary
- the completed sequencer must not contribute to the next rendered sample

This keeps chip state transitions and channel ownership changes aligned with the same sample boundary in both batched and fallback execution.

### 5. Fallback Mode

The current single-sample algorithm remains available and is used when:

- the computed safe chunk is too small
- a boundary query cannot prove safety
- a sequencer is in an active fade state
- a sequencer has `speedMultiplier > 1` and the batch planner has not yet proven that extra in-frame ticks are modeled safely
- a sequencer enters any other state that the batch planner does not model yet
- a regression or debug flag disables batching

This fallback path is part of the design, not a temporary crutch.

## Data Flow

For each `read(short[] buffer)` call:

1. inspect active sequencers under the existing driver lock
2. compute safe chunk size
3. if safe chunk is large enough:
   - advance all sequencers by the chunk
   - remove completed sequencers
   - render the chunk with the shared synthesizer
4. otherwise:
   - run one iteration of the current sample-by-sample path
5. repeat until the output buffer is filled

No rendered PCM is cached beyond the current `read()` call.

## Sound Effect And Music State Changes

This design does not add a long-lived chunk cache, so `playSfx`, `stopAllSfx`, override music restore, and similar operations do not invalidate cached PCM.

Those state changes continue to behave as they do today:

- they affect the driver state before the next normal buffer fill
- the next `read()` call sees the new sequencer set
- after each emitted chunk, the driver recomputes boundaries from current sequencer state

This keeps SFX start/stop semantics aligned with the existing main-thread-driven audio model.

## Error Handling And Safety Policy

The batching planner must be biased toward correctness:

- unknown or ambiguous states force fallback
- conservative boundary estimates are acceptable
- optimistic estimates are not

If a new sequencer feature cannot be modeled safely yet, the correct result is reduced batching coverage, not speculative batching.

## Testing Strategy

### 1. Unit Tests For Boundary Queries

Add focused tests for `SmpsSequencer` boundary reporting around:

- tempo boundaries
- fade processing step and fade completion
- note duration expiry
- fill-based note-off
- PSG envelope transitions
- FM volume envelope transitions
- modulation updates
- SFX `maxTicks` self-completion
- `speedMultiplier > 1` extra in-frame ticks
- track completion

These tests verify that batch windows stop before the first sample where observable behavior would change.

### 2. PCM Regression Tests

Extend audio regression coverage so the same SMPS inputs are rendered through:

- existing fallback path
- hybrid batching path

Compare output for exact identity. Because the synth path is deterministic and already accepts arbitrary-length buffers, any batch-vs-fallback PCM mismatch is a failure for this experiment.

### 3. Action-Coupled Audio Checks

Run targeted behavioral checks around sounds that are easy to notice in gameplay:

- jump
- ring collection
- spindash charge/release
- springs
- rapid overlapping SFX
- override music transitions

The primary acceptance criterion is unchanged perceived sync with gameplay actions.

### 4. Native Performance Measurement

Measure the audio section already recorded in the game loop profiler before and after the change, with special attention to GraalVM native builds.

The change should not ship without a measurable reduction in audio CPU cost.

## Rollout Plan

1. add boundary-query tests first
2. add conservative boundary-query support in `SmpsSequencer`
3. implement hybrid batching behind a guarded flag or local constant in `SmpsDriver`
4. run regression and behavioral checks
5. enable by default only if correctness and performance both improve

## Acceptance Criteria

The batching path is acceptable only if all of the following are true:

- no additional latency is introduced
- no gameplay-audio sync regression is observed
- batched output is bit-identical to fallback output in regression coverage
- GraalVM native builds show a meaningful CPU improvement in the audio section

If those conditions are not met, the implementation should be abandoned and the single-sample path should remain the default.
