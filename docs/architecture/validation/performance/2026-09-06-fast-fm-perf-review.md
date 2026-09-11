# Fast FM core performance review (2026-09-06)

Review of the clean-room fast YM2612 core's CPU cost against the retired
GPGX-derived core, measured as a black box only (its source was not read), and
the output-preserving changes landed in `perf(audio): skip idle fast FM work
with bitmasks and fold operator tables`. Whole-game confirmation:
TraceBenchmarkTool audio section S1 GHZ1 0.1725 to 0.1508 ms/frame and S3K AIZ
0.1640 to 0.1467 ms/frame with identical trajectory digests; the retired core
measured 137 ns per output sample on the same bench, the accurate Nuked port
1090 ns.


Worktree `.worktrees/bg-fixes`, branch `feature/ai-perf-p3-fixes`, HEAD b8840841c.
Harness: `XChipBench` (copied into `src/test/java/com/openggf/audio/synth/`, not committed),
44.1 kHz stereo, six FM voices, LFO AM/PM, retriggered keys, median of 7 runs of 441 000 output samples.
Commands: `mvn -Dmse=off -q -o -Dsurefire.forkCount=1 -Dtest=XChipBench -Dx.fast=true test`,
JFR via `-Dsurefire.argLine="-Xshare:off -Xmx1g -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints
-XX:StartFlightRecording=...,settings=profile,jdk.ExecutionSample#period=1ms"`.
Clean-room rule observed: nothing under `synth/nuked/**`, the synthesis body of `Ym2612Chip.java`,
any retired core, or any external emulator source was opened; only the fast core, facade,
resampler, and the bench were read.

## Result

| State | ns per output sample (median) | min / max | ms per 60 Hz frame |
|---|---|---|---|
| HEAD b8840841c, unchanged (this host, today) | 200.2 | 200.0 / 200.5 | 0.147 |
| + A scheduled-write stage bitmask + B keyed-slot mask | 172.3 | 172.1 / 172.9 | 0.127 |
| + C operatorSample table folding and per-frame hoists | 153.7 | 153.4 / 156.9 | 0.113 |
| re-run of A+B+C after reverting D | 155.5 | 154.8 / 156.0 | 0.114 |
| + D resampler straight-run FIR (rejected, reverted) | 159.3 | 157.8 / 160.1 | 0.117 |

Reference points from the lead's earlier measurement: retired GPGX-derived core 137 ns, fast core 210 ns,
Nuked port 1090 ns. Today's baseline on this host measured 200 ns, so the fast core is now ~23 % faster
and about 13 % from the retired core.

Verification of A+B+C: `TestFastFmCoreTolerance,TestFastFmLfoTransitions,TestFastFmAmplitudeModulation,
TestFastFmOutputTiming,TestFastFmFeedbackTransitions` = 232 tests, 0 failures, 0 errors, 0 skipped
(178/0/0/0 for the tolerance class); all 178 `fastDigest` values identical to the baseline list
(`perf-digest-before.txt` vs `perf-digest-abc.txt`, diff empty). Allocation profile
(`jdk.ObjectAllocationSample`): zero samples in `com.openggf.audio` before and after.

## Profile, HEAD unchanged (781 samples at 1 ms, self time)

| Method | Self % | Notes |
|---|---|---|
| FastYm2612Dsp.operatorSample | 38.2 | 24 calls/frame: phase, EG select, TL, AM, log-sin reflect, exp, shift |
| FastYm2612Dsp.renderChannel | 12.0 | silent check, feedback, algorithm switch, output history |
| BlipResampler.getOutputStereoPacked | 11.1 | 16-tap double FIR, L and R, per output sample |
| FastYm2612Dsp.admitScheduledWrites | 7.6 | 24 slots x 3 arrays every frame, almost always idle |
| FastYm2612Dsp.advanceEnvelope | 7.6 | every third frame, 24 slots |
| FastYm2612Dsp.renderFrame (self) | 6.3 | keyedThisFrame loop (24 slots), SSG mask, DAC, cursor |
| FastYm2612Dsp.advanceEnvelopes | 4.2 | sampledEg fill for 24 slots |
| FastYm2612Dsp.egOutput | 3.8 | called per operator and per envelope tick |
| FastYm2612Chip.renderInternalFrame | 2.3 | mute/pan/scale loop, bus credit |
| FastYm2612Chip.renderStereo + hasOutputSample + channelOutput | 3.1 | |
| advanceLfo, advanceTimers, addInputSample | < 0.5 | |

Groups: DSP core 80.9 % inclusive (operators 40.7, envelopes 13.1, admission 7.6), facade 3.9 %,
resampler ~12 %.

## Profile after A+B+C (636 samples)

| Method | Self % |
|---|---|
| operatorSample | 33.2 |
| renderChannel | 23.4 (more of the inlined operator work is now attributed here) |
| BlipResampler.getOutputStereoPacked | 10.7 |
| advanceEnvelope | 8.2 |
| renderFrame (self) | 5.5 |
| renderInternalFrame | 3.6 |
| egOutput | 3.3 |
| advanceEnvelopes | 2.5 |
| admitScheduledWrites | 1.7 (was 7.6) |

## What was prototyped (uncommitted in the worktree, `FastYm2612Dsp.java` only)

A. **Scheduled-write stage bitmask** (output-preserving, measured with B: -28 ns). New
   `scheduledMask[WRITE_PIPELINE_FRAMES]`, one bit per slot, set by `markScheduled` wherever
   `scheduledIncrements`/`scheduledLevels`/`scheduledKeys` are written. `admitScheduledWrites`
   returns immediately when the current stage's mask is zero and otherwise walks only the set
   bits in ascending slot order (same order as before). The array is in `arrays()` so
   `copyStateTo`/`equals`/`hashCode`/rewind carry it.

B. **Keyed-slot mask** (output-preserving, part of the -28 ns). `scalar[S_KEYED_MASK]`
   (scalar grows 19 -> 20) marks slots whose `keyedThisFrame` hold is counting down; the
   per-frame 24-slot decrement loop walks only set bits and clears the bit at zero. The only
   writers of `keyedThisFrame` are `keyOnSlot` (sets 1, now also sets the bit) and the admission
   `+= key >> 1` which only fires when already nonzero.

C. **operatorSample table folding and per-frame hoists** (output-preserving, -18 ns).
   - `LOG_SIN_HALF[512]`: quarter-wave table with the second-quarter reflection folded, indexed
     by `phaseIndex & 0x1FF`; removes a branch and a subtract per operator.
   - `EXP_MANTISSA[256] = (EXP[255 - f] + 1024) << 2` indexed by `attenuation12 & 0xFF`; removes
     the complement, add and shift per operator.
   - `renderFrame` reads `scalar[S_EG_FRAME] == 0` and the two doubled AM triangles once and
     passes them to `renderChannel`, which applies `AM_SHIFT[amSensitivity[channel]]` once per
     channel and passes `am0`/`am1` plus the sampled-EG flag into `operatorSample`. OP1 passes
     `false` for the sampled-EG flag, matching the old `(slot & 3) != 0 &&` condition. Reason it
     matters: every state array is `int[]`, so each store to `phase[]`/`output[]` forces the JIT
     to reload every other `int[]` value; hoisting into locals removes those reloads.

D. **Resampler straight-run FIR** (output-preserving, rejected): splitting the 16-tap loop into
   an unmasked run when the ring window does not wrap measured 159 ns vs 154, so it was reverted.
   The masked loop is already cheap; the cost is the double multiply-accumulate chain.

## Ranked remaining proposals

| # | Proposal | Est. gain | Risk | Output-preserving |
|---|---|---|---|---|
| 1 | **Array-of-structs operator state.** Pack the ~25 per-slot `int[24]` arrays into one `int[24 * STRIDE]` (phase, increment, attenuation, egState, totalLevel, amEnabled, ssgMode, keyOn, ssgInvert, sampledEg at fixed offsets). One array length, one cache line per operator instead of ~10, and the JIT can prove bounds once per operator. `arrays()`/`copyStateTo` shrink to a few arrays. | 10-20 ns | Medium: large mechanical refactor of the whole class; rewind snapshot shape changes | yes |
| 2 | **Skip fully released operators in `advanceEnvelopes`.** Maintain an "envelope active" mask (cleared when release reaches 0x3FF, set on key-on/SSG restart) so the per-tick loop and the `sampledEg` fill touch only live slots. In music most operators are released most of the time; in the bench nearly all are live, so the gain here is small but real in game. | 2-8 ns in game, ~0 in bench | Low-medium: must mirror every path that changes state (keyOn, keyOff, handleSsgBoundary, applySsgRestartEffects) | yes |
| 3 | **Per-channel activity mask instead of the eight-load silent check** in `renderChannel`; same source of truth as #2. | 1-2 ns | Low | yes |
| 4 | **Envelope branch order in `advanceEnvelope`**: test the released/held early-outs before the DECAY->SUSTAIN write so silent slots leave after two loads. | ~1 ns | Low | yes (the DECAY branch cannot fire for RELEASE state) |
| 5 | **Facade mix loop**: precompute per-channel left/right multipliers (0 or 3) once when pan/mute change and drop the two branches per channel; `(x * 3) >> 2` unchanged. | ~1-2 ns | Low | yes |
| 6 | **Resampler in fixed point**: 16-tap FIR with 32-bit coefficients and a 64-bit accumulator instead of doubles. | 8-12 ns (resampler is ~11 %) | Medium | **no**: rounding differs from `Math.round(double)`; would need a tolerance-based re-validation of the output, not the digest |
| 7 | **Coarser envelope service**: hoist the `EG_SHIFT`/`EG_INCREMENT` lookup out of the slot loop by iterating per rate bucket. | small | Medium | yes in principle, but complicates the SSG paths; not worth it before #1 |
| 8 | Branchless sign in `operatorSample` (`(linear ^ s) - s`). | ~0 | Low | yes; C2 likely already emits a cmov, not measured |

Not pursued: any change that would alter the algorithm evaluation order, the feedback ages, the
EG counter coordinate, or the pipeline delays, since those are oracle-fitted and any edit there
changes samples.

## Excluded by the clean-room rule

- No comparison with how the Nuked port, the retired core, Genesis Plus GX, MAME, BlastEm, ymfm or
  fmgen lay out tables or batch envelope work; proposals above come only from the fast core's own
  profile and general JIT behaviour (int[] aliasing, bounds checks, branch structure).
- The retired core's 137 ns is treated as a black-box target only.

## Round 2: activity masks and whole-game measurement

Both changes are in the worktree now, uncommitted, in `FastYm2612Dsp.java` on top of A+B+C
(applied together by `perf-patch-de.py`; total diff 136+/48-). Verified as one step:
232 tests, 0 failures/errors/skips (tolerance 178/0/0/0), digest diff before vs `abcde` empty.

D. **Envelope-active mask** (output-preserving, in tree). `scalar[S_EG_ACTIVE_MASK]` (bit per
   slot; `scalar` grows to 22 entries) starts at all-set on reset. `advanceEnvelopes` walks only
   set bits; when a tick finds a slot keyed off, in release and at 0x3FF it writes `sampledEg`
   one last time (egOutput = 0x3FF, which is what every later tick would write) and drops the
   bit, because from then on `advanceEnvelope` has no side effect. `keyOnSlot` is the only
   re-entry and sets the bit. The lazy drop (at the tick, not at the transition) keeps the
   pre-tick `sampledEg` coordinate identical.

E. **Per-channel silent skip** (output-preserving, in tree). `scalar[S_CHANNEL_SILENT_MASK]`
   is set by `renderChannel`'s existing silent path (which zeroes OP1 history, feedback history,
   operator outputs and, via `channelOutput`, the delayed output) and cleared by the active path.
   `renderFrame` skips `renderChannel` and writes 0 when the bit is set and all four slots are out
   of the envelope-active set: the skipped call would rewrite zeros and return zero, so the
   odd-channel delay, the OP1 carrier history and the AM history (still updated in `renderFrame`)
   are unaffected. Keyed-on operators parked at 0x3FF do not qualify and keep taking the
   ordinary silent path every frame.

| State | XChipBench ns per output sample | S1 GHZ1 audio ms/frame | S3K AIZ audio ms/frame |
|---|---|---|---|
| HEAD b8840841c | 200.2 | 0.1725 | 0.1640 |
| A+B+C | 153.7 to 155.5 | | |
| A+B+C+D+E (current tree) | 147.6 (min 147.3, max 148.2) | 0.1508 | 0.1467 |

Whole-game runs: `TraceBenchmarkTool --mode update --warmup-frames 500 --measure-frames 3000
--fm-core fast` via `perf-game.sh`, explicit ROM paths, three iterations; trajectory digests
f87df78271bbb0d7 (S1) and aa99e56f4ccbf249 (S3K) identical between HEAD and the prototype tree
(note: the trajectory digest covers game state, not audio; audio identity rests on the 178
script digests). Logs: `perf-game-head-*.log`, `perf-game-proto-*.log`.

Not done: step 3 (array-of-structs operator state) was not started; it remains proposal #1
above. A useful further guard before any commit: run the remaining fast-core classes
(`TestFastFmDetune`, `TestFastFmEnvelopeSampling`, `TestFastFmFrequencySampling`,
`TestFastFmFrequencyTransitions`, `TestFastFmReleaseContracts`, `TestFastFmTimersAndChannelThree`,
`TestFastFmWaveformAlignment`, `TestFastYm2612Chip`), since `scalar` and `arrays()` changed shape
and `TestFastYm2612Chip` exercises snapshot equality.
