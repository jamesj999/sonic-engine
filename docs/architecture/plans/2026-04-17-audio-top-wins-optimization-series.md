# Audio Top Wins Optimization Series Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a reliable audio performance baseline, then implement the approved Top Wins one at a time with regression protection and before/after benchmark comparisons after every accepted change.

**Architecture:** Use the existing audio benchmark methods in `AudioRegressionTest` as the performance source of truth and the existing audio regression/parity tests as the correctness gate. Each optimization lands in isolation, is verified in isolation, and is benchmarked in isolation so performance deltas can be attributed to a single code change.

**Tech Stack:** Java, Maven Surefire, JUnit 5, Sonic 2 SMPS audio stack, local ROM-backed regression assets.

---

## File Map

**Plan / Notes**
- Create: `docs/architecture/plans/2026-04-17-audio-top-wins-optimization-series.md`
- Create: `docs/architecture/validation/audio-top-wins-baseline-2026-04-17.md`
- Create: `docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md`

**Benchmark / Regression Entry Points**
- Use: `src/test/java/com/openggf/audio/AudioRegressionTest.java`
- Use: `src/test/java/com/openggf/tests/TestPsgChipGpgxParity.java`
- Use: `src/test/java/com/openggf/tests/TestYm2612ChipBasics.java`
- Use: `src/test/java/com/openggf/tests/TestYm2612Attack.java`
- Use: `src/test/java/com/openggf/tests/TestYm2612InstrumentTone.java`
- Use: `src/test/java/com/openggf/tests/TestYm2612SsgEg.java`
- Use: `src/test/java/com/openggf/tests/TestYm2612TimerCSM.java`
- Use: `src/test/java/com/openggf/tests/TestYm2612VoiceLengths.java`
- Use: `src/test/java/com/openggf/tests/TestYm2612AlgorithmRouting.java`
- Use: `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java`
- Use: `src/test/java/com/openggf/tests/TestSmpsSequencerBatchBoundaries.java`
- Use: `src/test/java/com/openggf/tests/TestRomAudioIntegration.java`
- Use: `src/test/java/com/openggf/tests/TestTitleScreenAudioRegression.java`
- Use: `src/test/java/com/openggf/tests/TestSonic2PsgEnvelopesAgainstRom.java`

**Optimization Targets**
- Modify: `src/main/java/com/openggf/audio/synth/PsgChipGPGX.java`
- Modify: `src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java`
- Modify: `src/main/java/com/openggf/audio/synth/BlipResampler.java`
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`

## Shared Commands

- [ ] **Step 1: Define the regression gate command**

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest,TestPsgChipGpgxParity,TestYm2612ChipBasics,TestYm2612Attack,TestYm2612InstrumentTone,TestYm2612SsgEg,TestYm2612TimerCSM,TestYm2612VoiceLengths,TestYm2612AlgorithmRouting,TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries,TestRomAudioIntegration,TestTitleScreenAudioRegression,TestSonic2PsgEnvelopesAgainstRom" test
```

Expected:
- PASS on all intended gate tests
- If existing unrelated failures occur, stop and triage before any optimization work

- [ ] **Step 2: Define the benchmark command**

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkAudioRendering+benchmarkResultsTallyStressRendering" test
```

Expected output fragments:
- `Audio render time:`
- `Real-time factor:`
- `Results tally stress render time:`
- `Results tally stress real-time factor:`

---

### Task 1: Establish Baseline

**Files:**
- Create: `docs/architecture/validation/audio-top-wins-baseline-2026-04-17.md`
- Use: `src/test/java/com/openggf/audio/AudioRegressionTest.java`

- [ ] **Step 1: Verify ROM and reference asset prerequisites**

Run:

```powershell
Get-ChildItem "src/test/resources/audio-reference"
Get-ChildItem *.gen
```

Expected:
- Audio reference WAVs exist under `src/test/resources/audio-reference`
- At least one matching Sonic 2 ROM exists in the repo root so `AudioRegressionTest` does not skip

- [ ] **Step 2: Run the regression gate on the unmodified codebase**

Run:

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest,TestPsgChipGpgxParity,TestYm2612ChipBasics,TestYm2612Attack,TestYm2612InstrumentTone,TestYm2612SsgEg,TestYm2612TimerCSM,TestYm2612VoiceLengths,TestYm2612AlgorithmRouting,TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries,TestRomAudioIntegration,TestTitleScreenAudioRegression,TestSonic2PsgEnvelopesAgainstRom" test
```

Expected:
- PASS
- If FAIL, capture the failing test names in `docs/architecture/validation/audio-top-wins-baseline-2026-04-17.md` and stop optimization work

- [ ] **Step 3: Run the benchmark command 10 times**

Run:

```powershell
1..10 | ForEach-Object { mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkAudioRendering+benchmarkResultsTallyStressRendering" test }
```

Expected:
- 10 benchmark runs with timing output from both benchmark methods

- [ ] **Step 4: Record the 10 baseline runs and summary statistics**

Record in `docs/architecture/validation/audio-top-wins-baseline-2026-04-17.md`:

```text
Run | Audio ms/sec | Audio x realtime | Tally ms/run | Tally x realtime
1   |              |                  |              |
...
10  |              |                  |              |

Summary:
- Audio median:
- Audio mean:
- Audio min/max:
- Tally median:
- Tally mean:
- Tally min/max:
```

- [ ] **Step 5: Commit the baseline notes only if they are intended to stay in the branch**

```powershell
git add docs/architecture/plans/2026-04-17-audio-top-wins-optimization-series.md docs/architecture/validation/audio-top-wins-baseline-2026-04-17.md
git commit -m "docs: record audio top wins optimization plan and baseline"
```

---

### Task 2: Implement PSG Hot-Loop Cleanup

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/PsgChipGPGX.java`
- Modify: `src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java`
- Create/Modify if needed: `src/test/java/com/openggf/tests/TestPsgChipGpgxParity.java`
- Create/Modify if needed: `src/test/java/com/openggf/audio/AudioRegressionTest.java`
- Update notes: `docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md`

- [ ] **Step 1: Add or tighten regression coverage for the touched PSG path if needed**

Cover:
- HQ vs fast path emits identical timestamps and deltas as before
- Noise stepping mode still matches current parity expectations

- [ ] **Step 2: Implement only the PSG dispatch/hoisting optimization**

Scope:
- Split HQ vs fast dispatch outside the inner loops
- Hoist stable per-call PSG state out of hot loops
- Do not change clock arithmetic, timestamps, or emitted delta values

- [ ] **Step 3: Run the regression gate**

Run:

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest,TestPsgChipGpgxParity,TestYm2612ChipBasics,TestYm2612Attack,TestYm2612InstrumentTone,TestYm2612SsgEg,TestYm2612TimerCSM,TestYm2612VoiceLengths,TestYm2612AlgorithmRouting,TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries,TestRomAudioIntegration,TestTitleScreenAudioRegression,TestSonic2PsgEnvelopesAgainstRom" test
```

Expected:
- PASS

- [ ] **Step 4: Run the benchmark command 10 times**

```powershell
1..10 | ForEach-Object { mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkAudioRendering+benchmarkResultsTallyStressRendering" test }
```

- [ ] **Step 5: Compare to the original baseline and record the delta**

Record in `docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md`:

```text
Step 1 - PSG hot-loop cleanup
- Audio median before:
- Audio median after:
- Audio delta:
- Tally median before:
- Tally median after:
- Tally delta:
- Regression gate: PASS/FAIL
- Decision: keep / retry / revert
```

- [ ] **Step 6: Commit the isolated change**

```powershell
git add src/main/java/com/openggf/audio/synth/PsgChipGPGX.java src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java src/test/java/com/openggf/tests/TestPsgChipGpgxParity.java src/test/java/com/openggf/audio/AudioRegressionTest.java docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md
git commit -m "perf: reduce PSG hot-loop branching"
```

---

### Task 3: Implement BlipResampler Fused Stereo Interpolation

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/BlipResampler.java`
- Modify if needed: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Create/Modify if needed: `src/test/java/com/openggf/audio/AudioRegressionTest.java`
- Update notes: `docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md`

- [ ] **Step 1: Add or tighten exact-output tests around stereo interpolation**

Cover:
- Left and right channels still round independently
- Output remains identical for the existing reference scenarios

- [ ] **Step 2: Implement only the fused stereo interpolation optimization**

Scope:
- Share phase/start/tap setup across left and right
- Preserve per-channel tap order and `Math.round`
- Do not change coefficient type or phase arithmetic

- [ ] **Step 3: Run the regression gate**
- [ ] **Step 4: Run the benchmark command 10 times**
- [ ] **Step 5: Record the before/after deltas**
- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/audio/synth/BlipResampler.java src/main/java/com/openggf/audio/synth/Ym2612Chip.java src/test/java/com/openggf/audio/AudioRegressionTest.java docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md
git commit -m "perf: share stereo blip resampler setup"
```

---

### Task 4: Implement YM Per-Sample Channel Pass Fusion

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Create/Modify if needed: `src/test/java/com/openggf/audio/AudioRegressionTest.java`
- Create/Modify if needed: `src/test/java/com/openggf/tests/TestYm2612ChipBasics.java`
- Update notes: `docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md`

- [ ] **Step 1: Add or tighten FM-side coverage for stereo mix and DAC exclusion behavior**
- [ ] **Step 2: Implement only the single-pass YM render/mix fusion**
- [ ] **Step 3: Run the regression gate**
- [ ] **Step 4: Run the benchmark command 10 times**
- [ ] **Step 5: Record the before/after deltas**
- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/audio/synth/Ym2612Chip.java src/test/java/com/openggf/audio/AudioRegressionTest.java src/test/java/com/openggf/tests/TestYm2612ChipBasics.java docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md
git commit -m "perf: fuse YM channel render and mix pass"
```

---

### Task 5: Eliminate SmpsDriver Chunk Scratch Churn

**Files:**
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify if needed: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Create/Modify if needed: `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java`
- Update notes: `docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md`

- [ ] **Step 1: Add a regression test that proves no allocation workaround changes frame count or output**
- [ ] **Step 2: Implement only the chunk scratch reuse fix**

Scope:
- Remove the local `new short[sampleCount]` churn for smaller-following-larger chunks
- If required, add an exact-frame render API rather than using oversized scratch unsafely
- Do not relax chunk boundary logic

- [ ] **Step 3: Run the regression gate**
- [ ] **Step 4: Run the benchmark command 10 times**
- [ ] **Step 5: Record the before/after deltas**
- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/audio/driver/SmpsDriver.java src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java src/test/java/com/openggf/tests/TestSmpsDriverBatching.java docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md
git commit -m "perf: reuse SMPS chunk scratch buffers"
```

---

### Task 6: Consolidate VirtualSynthesizer Scratch / Mix Passes

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Create/Modify if needed: `src/test/java/com/openggf/audio/AudioRegressionTest.java`
- Update notes: `docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md`

- [ ] **Step 1: Add coverage that protects FM+PSG mixed output**
- [ ] **Step 2: Implement only the scratch/mix consolidation**

Scope:
- Reduce extra clears and extra whole-buffer passes
- Only share stereo scratch if additive semantics are verified first
- Preserve final clamp/store behavior exactly

- [ ] **Step 3: Run the regression gate**
- [ ] **Step 4: Run the benchmark command 10 times**
- [ ] **Step 5: Record the before/after deltas**
- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java src/test/java/com/openggf/audio/AudioRegressionTest.java docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md
git commit -m "perf: reduce VirtualSynthesizer scratch passes"
```

---

### Task 7: Final Review

**Files:**
- Review: `docs/architecture/validation/audio-top-wins-baseline-2026-04-17.md`
- Review: `docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md`

- [ ] **Step 1: Produce the final comparison table**

Include:
- Baseline medians
- Per-step medians
- Net delta from baseline
- Keep/revert decision for each optimization

- [ ] **Step 2: Stop and present results before taking any further audio optimization**

Do not continue into:
- Fixed-point resampler rewrites
- Backend threading changes
- Blip ring-buffer redesign
- More aggressive hybrid batching changes

