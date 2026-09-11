# Audio Top Wins Step Results 2026-04-17

## Baseline Reference

- Audio render median: `9.1555 ms/sec`
- Results tally stress median: `28.14445 ms/run`

## Step Template

### Step N - Name

- Files changed:
- Regression gate:
- Benchmark runs:
- Audio median before:
- Audio median after:
- Audio delta:
- Tally median before:
- Tally median after:
- Tally delta:
- Notes:
- Decision:

## Step 1 - PSG Hot-Loop Cleanup

- Files changed:
  - `src/test/java/com/openggf/tests/TestPsgChipGpgxParity.java`
  - `src/main/java/com/openggf/audio/synth/PsgChipGPGX.java` during the experiment, then reverted
- Regression gate:
  - Narrow gate (`TestPsgChipGpgxParity,AudioRegressionTest`): PASS
  - Full gate: PASS
- Benchmark runs:
  - First 10-run set completed
  - Second 10-run confirmation set completed because the first set was mixed
- Audio median before: `9.1555 ms/sec`
- Audio median after, set 1: `9.6557 ms/sec`
- Audio median after, set 2: `10.1633 ms/sec`
- Audio delta:
  - Set 1: `+0.5002 ms/sec`
  - Set 2: `+1.0078 ms/sec`
- Tally median before: `28.14445 ms/run`
- Tally median after, set 1: `27.9371 ms/run`
- Tally median after, set 2: `30.8011 ms/run`
- Tally delta:
  - Set 1: `-0.20735 ms/run`
  - Set 2: `+2.65665 ms/run`
- Notes:
  - Added exact-output PSG characterization tests for deterministic tone and noise renders in fast and HQ modes.
  - The first benchmark set was inconclusive because the main audio median regressed while tally improved slightly.
  - The confirmation set regressed on both medians.
- Decision:
  - Revert the production hot-loop split.
  - Keep the added PSG exact-output tests as permanent coverage.

## Step 2 - BlipResampler Fused Stereo Interpolation

- Files changed:
  - `src/test/java/com/openggf/tests/TestBlipResampler.java`
  - `src/main/java/com/openggf/audio/synth/BlipResampler.java` during the experiment, then reverted
- Regression gate:
  - Narrow gate (`TestBlipResampler,AudioRegressionTest`): PASS
  - Full gate: PASS
- Benchmark runs:
  - First 10-run set completed
  - Second 10-run confirmation set completed because the first set was mixed
- Audio median before: `9.1555 ms/sec`
- Audio median after, set 1: `9.28565 ms/sec`
- Audio median after, set 2: `8.87255 ms/sec`
- Audio delta:
  - Set 1: `+0.13015 ms/sec`
  - Set 2: `-0.28295 ms/sec`
- Tally median before: `28.14445 ms/run`
- Tally median after, set 1: `27.1926 ms/run`
- Tally median after, set 2: `28.26825 ms/run`
- Tally delta:
  - Set 1: `-0.95185 ms/run`
  - Set 2: `+0.12380 ms/run`
- Notes:
  - Added exact-output `BlipResampler` characterization tests for deterministic stereo output and repeated same-position reads.
  - The production experiment fused left/right interpolation and added a small output cache.
  - The two 10-run benchmark sets disagreed on which metric improved.
  - Because the result stayed inconclusive and the change added extra cache state to the resampler hot path, it was not accepted.
- Decision:
  - Revert the production fused/cached interpolation change.
  - Keep the added exact-output resampler tests as permanent coverage.

## Step 3 - YM Per-Sample Channel Pass Fusion

- Files changed:
  - `src/test/java/com/openggf/tests/TestYm2612ChipBasics.java`
  - `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Regression gate:
  - Narrow gate (`TestYm2612ChipBasics,AudioRegressionTest`): PASS
  - Full gate: PASS
- Benchmark runs:
  - First 10-run set completed
  - Second 10-run confirmation set completed because the first set was mixed
- Audio median before: `9.1555 ms/sec`
- Audio median after, set 1: `9.60965 ms/sec`
- Audio median after, set 2: `9.1559 ms/sec`
- Audio delta:
  - Set 1: `+0.45415 ms/sec`
  - Set 2: `+0.00040 ms/sec`
- Tally median before: `28.14445 ms/run`
- Tally median after, set 1: `27.06005 ms/run`
- Tally median after, set 2: `26.0248 ms/run`
- Tally delta:
  - Set 1: `-1.08440 ms/run`
  - Set 2: `-2.11965 ms/run`
- Notes:
  - Added an exact-output YM characterization test covering multi-channel pan routing plus DAC on channel 5.
  - The render/mix passes were fused in `renderOneSample()` while preserving discrete-chip bias behavior for muted and DAC-skipped channels by treating them as `out = 0`.
  - The first benchmark set regressed the main audio median, so a confirmation set was required.
  - The confirmation set brought the main audio benchmark back to baseline while further improving the tally benchmark.
- Decision:
  - Keep the fused YM channel pass.
  - Keep the added exact-output YM test as permanent coverage.

## Step 4 - SMPS Chunk Scratch Exact-Frame Reuse

- Files changed:
  - `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java` during the experiment, then reverted
  - `src/main/java/com/openggf/audio/driver/SmpsDriver.java` during the experiment, then reverted
  - `src/test/java/com/openggf/tests/TestVirtualSynthesizerRender.java` during the experiment, then reverted
- Regression gate:
  - Narrow gate (`TestVirtualSynthesizerRender,TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries`): PASS during the experiment
  - Full gate: PASS during the experiment
- Benchmark runs:
  - First 10-run set completed
  - Second 10-run confirmation set completed because the first set was mixed
- Audio median before: `9.1555 ms/sec`
- Audio median after, set 1: `10.3079 ms/sec`
- Audio median after, set 2: `9.53545 ms/sec`
- Audio delta:
  - Set 1: `+1.15240 ms/sec`
  - Set 2: `+0.37995 ms/sec`
- Tally median before: `28.14445 ms/run`
- Tally median after, set 1: `27.61145 ms/run`
- Tally median after, set 2: `26.39745 ms/run`
- Tally delta:
  - Set 1: `-0.53300 ms/run`
  - Set 2: `-1.74700 ms/run`
- Notes:
  - The experiment added an exact-frame `VirtualSynthesizer.render(...)` overload and changed `SmpsDriver.renderChunk()` to reuse oversized scratch with power-of-two growth.
  - Existing batching tests and a new frame-limited render test all passed, and audio output stayed byte-identical under regression coverage.
  - Despite the cleaner allocation story, both benchmark sets regressed the main audio median.
- Decision:
  - Revert the exact-frame render API and the chunk-scratch reuse change.
  - Revert the temporary test added only for that experimental API.

## Step 5 - VirtualSynthesizer Shared Stereo Scratch

- Files changed:
  - `src/test/java/com/openggf/tests/TestVirtualSynthesizerMix.java`
  - `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Regression gate:
  - Narrow gate (`TestVirtualSynthesizerMix,AudioRegressionTest`): PASS
  - Full gate: PASS
- Benchmark runs:
  - First 10-run set completed
- Audio median before: `9.1555 ms/sec`
- Audio median after: `8.70065 ms/sec`
- Audio delta: `-0.45485 ms/sec`
- Tally median before: `28.14445 ms/run`
- Tally median after: `28.05695 ms/run`
- Tally delta: `-0.08750 ms/run`
- Notes:
  - Added an exact-output `VirtualSynthesizer` fixture covering a mixed FM+PSG render.
  - Simplified `VirtualSynthesizer.render()` so PSG accumulates directly into the shared stereo scratch, removing the extra PSG scratch buffers and the explicit mix loop.
  - Audio regression references remained byte-identical after the change.
- Decision:
  - Keep the shared-stereo-scratch `VirtualSynthesizer` change.
  - Keep the added exact-output mixed-output test as permanent coverage.
