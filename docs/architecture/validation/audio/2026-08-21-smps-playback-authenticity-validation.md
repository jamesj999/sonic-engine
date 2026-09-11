# SMPS Playback Authenticity Validation

## Decision surface

This report evaluates the bounded playback work described by the
[SMPS playback authenticity roadmap](../../plans/audio/2026-08-21-smps-playback-authenticity-roadmap.md).
It does not treat complete-run semantic evidence, observer ABI expansion, or
architecture cleanup as delivery requirements. The question is whether the
engine sends more authentic timing, ownership, and sample data to its YM2612,
PSG, and DAC paths without introducing an attributable regression.

## Source-backed improvements

The implementation follows the shipped `FixBugs = 0`, `FixDriverBugs = 0`,
and `fix_sndbugs = 0` paths and adds focused tests for:

- S1, S2, and locked-on S3K tempo, duration, PAL, and speed-up service cadence;
- S1/S2 global SFX priority and S3K's priority-free per-channel admission;
- same-service takeover/release, ordinary BGM replacement, pause, fade, and
  1-up save/restore behavior;
- S2 spindash request/transposition bugs and S3K modulation-envelope bugs;
- region-correct chip clocks, reference PSG/DAC defaults, S2 DPCM cadence,
  and S3K's exclusive StopAll SEGA PCM path through the YM2612 DAC;
- the native chip-mix output level, without the former shared synthetic
  `-6 dB` headroom attenuation applied after otherwise matching YM2612 output;
- exact retail song/SFX/envelope/DAC catalog framing for all three verified
  ROMs, with malformed or unreadable tables failing closed.

Game differences are represented by typed profile/configuration policy. Shared
runtime code contains no game-name or zone carve-out. Runtime content remains
ROM-owned.

## Roadmap traceability

| Phase | Primary automated evidence | State |
|---|---|---|
| 0 — small accuracy harness | `TestChipWriteObserver`, `TestYm2612ChipGpgxParity`, `TestPsgChipGpgxParity`, `TestRomAudioIntegration`, and the pinned S1 BizHawk parity tool | Objective checks pass; [human checklist](2026-08-21-smps-playback-listening-checklist.md) pending |
| 1 — scheduler and tempo | `TestSmpsSequencerDriverCadence`, `TestS3kPalDriverCadence`, `TestSmpsSequencerTempoMath`, and `TestAudioDiagnosticObservers` | Automated acceptance covered |
| 2 — admission and takeover | `TestSmpsGlobalSfxPriority`, `TestPreparedSfxAdmission`, `TestS1SfxTakeoverOrder`, `TestSmpsDriverServiceOrder`, and `TestSmpsCompositeVoice` | Automated acceptance covered |
| 3 — pause/fade/1-up | `TestSmpsPauseProtocol`, `TestSmpsSequencerFadeTiming`, `TestMusicOverrideRestore`, `TestAudioVoiceRegistry`, and `TestUnifiedAudioPresentationIntegration` | Automated acceptance covered; subjective transitions pending |
| 4 — bytecode/envelopes | `TestSonic3kModEnvelopeRetailBugs`, `TestSonic3kCoordFlagParity`, `TestSonic2SpindashRevRequest`, `TestSmpsSequencer`, and the ROM catalog tests | Automated acceptance covered |
| 5 — chip/DAC/PCM/region | `TestRegionalChipClocks`, `TestAudioHardwareDefaults`, `TestYm2612ChipGpgxParity`, `TestSegaPcmCommandRouting`, `TestAudioPresentationSourceParity`, and `TestRomAudioIntegration` | Automated acceptance covered |
| 6 — ROM content | the S1/S2/S3K catalog-resolution and loader-framing suites | Automated acceptance covered on all three pinned ROMs |

Phase 7 is deliberately not an acceptance item. It is cleanup after audible
parity and remains deferred.

## Verification

All commands used Maven on JDK 21.0.11 with the exact S1 REV01, S2 REV01, and
locked-on S3K ROM properties.

### Focused playback gates

- Final merged changed-test selection: 44 classes, 567 tests, 0 failures,
  0 errors, 0 skips.
- Broad audio regression selection: 80 classes, 687 tests, 0 failures,
  0 errors, 1 existing skip.
- Newly added audio tests followed by the affected GameLoop classes: 101
  tests, 0 failures, 0 errors.
- The exact upstream trace-session failure sequence followed by the hardened
  rewind boundary tests leaves the upstream class red, while both downstream
  classes pass 12/12. This proves their cleanup is test isolation rather than
  a runtime workaround.

### Fresh completion audit

On commit `683b1a3984958b4b6ae53baf383a81bee6727078`, JDK 21.0.11 and the three
pinned ROMs produced these additional results:

- the exact 43 runnable classes in the changed 44-path test manifest ran 414
  tests with 0 failures, 0 errors, and 0 skips;
- the pinned `s1-soundtest-ghz.bk2` comparison matched all 14,690 ordered
  driver ticks against BizHawk 2.11 / Genesis Plus GX;
- the two BizHawk captures were byte-identical at SHA-256
  `5941958c4eb38da4f71e1e5860b49b2d13d6fa0aaedcf244fa7b8d4ecb5d6efc`;
  the two independent OpenGGF captures were byte-identical at SHA-256
  `06f2fda57779b6e1ec53078bc3040ff49135ff89ddb37bb325ef5d4f5e65187a`.

The external comparison covers S1 GHZ's normal music path. It is intentionally
not presented as evidence for SFX contention, pause, fade, speed, or 1-up; those
remain explicit human checklist rows.

The older S1 GHZ1 semantic gameplay-timeline runner was also probed, but it is
not accepted as playback evidence for this delivery. Its OpenGGF reducer still
turns an `AudioCommandTimeline.PlaySfx` submission into an admission after the
current runtime has correctly blocked that SFX during the 1-up lifecycle, then
later reconstructs a restored SFX owner from a driver-local ordinal rather than
the original semantic request identity. The run therefore fails inside the
observer before cross-producer comparison. No runtime or timeline workaround
was retained: repairing that legacy semantic reducer would expand the paused
evidence program and would not establish audibility. The human transition rows
remain the bounded acceptance gate.

### Clean full-suite comparison

Both sides started from empty `target/` directories, used Maven's serial
`-Pci` profile, JDK 21.0.11, and the same three pinned ROMs. The baseline is
the exact pre-playback commit `7039887187948f86089e39a9f0fff0f17b26bdab`;
the candidate is `683b1a3984958b4b6ae53baf383a81bee6727078`.

| Tree | Surefire summary | Unique failing/error methods |
|---|---:|---:|
| pre-playback baseline | 15,171 tests; 54 failures; 69 errors; 18 skips | 123 |
| playback candidate | 15,251 tests; 52 failures; 64 errors; 18 skips | 116 |

The exact method-set comparison has no failure or error absent from the clean
baseline and removes seven baseline-red special-stage presentation/rewind
methods. The full suite remains red from pre-existing failures, but the
candidate introduces no new failing/error method, no baseline failure worsens,
and no changed or audio-focused test is red.

## Limits and follow-up

The validation is based on disassembly-owned state transitions, ordered chip
writes, deterministic PCM/register fixtures, real-ROM catalog traversal, and
integration playback. It does not claim a subjective listening test was
performed by the automation. Human A/B approval is tracked explicitly in the
[listening checklist](2026-08-21-smps-playback-listening-checklist.md); it must
not reopen complete-game semantic tracing or broad backend cleanup without a
named audible mismatch.

The first Blue Sphere listening candidate (`5f5232441`) did not pass that
human gate. Its same-ID retrigger trace proved the expected `$05`/`$0A` TL
sequence but omitted the different-ID shared-track boundary used when a spring,
bumper, goal, or other FM5 SFX precedes the pickup. The bounded presentation
trace now covers that boundary and reproduced the defect before correction:
the engine uploaded the interrupted music carrier TLs `[22,21,31]` before the
Blue Sphere voice `[5,5,5]`. Retail `zPlaySound` overwrites the shared SFX RAM
track after its admission key-off and never exposes music between the two SFX.
The corrected handoff retains FM5 ownership across that replacement. Human
listening remains pending and still blocks integration.

A subsequent native headless GPGX capture found the remaining timing mismatch.
On retail S3K, `zUpdateEverything` services existing SFX before `zUpdateMusic`
processes the sound queues, so admission performs the FM5 key-off/SSG-EG clear
on one driver update and `cfSetVoice` performs its maximum-release writes,
voice upload, and key-on on the next. The engine previously admitted and
serviced the new track in one update. The finite special-stage replay now joins
each Blue Sphere request to those two ordered YM bus phases, while the bounded
chip observer retains key-on attenuation and one selected channel's samples for
diagnosis rather than treating route-specific envelope values as constants.

S1 and S2 were checked separately rather than inheriting the S3K delay. S1's
`UpdateMusic` processes `PlaySoundID` before its music/SFX loops, and S2's
`zUpdateEverything` cycles and plays the queue before `zUpdateMusic` and the
SFX loops. Their fresh SFX therefore receives its first service in the same
driver update. The typed `SfxStartTiming` policy and focused tests preserve
that distinction, including direct/batched advancement and rewind state. S1
and S2 also retain their own voice-write profiles and do not receive S3K's
pre-upload `RR=FF` sequence.

The equivalent S1 and S2 loaders also overwrite their shared per-channel SFX
track RAM without restoring music between the displaced and replacement SFX
(`Sound_PlaySFX` / `zPlaySound`). Their engine profiles still use the older
force-reset takeover policy, so this S3K candidate deliberately does not alter
them. That mismatch is a separate source-backed follow-up with its own game
tests; folding it into the Blue Sphere correction would broaden the listening
surface without helping diagnose this report.

The follow-up audio regression selection ran 179 tests with 0 failures, 0
errors, and 0 skips. A clean full-suite comparison against current `develop`
ran 15,283 candidate tests (52 failures, 64 errors, 18 skips) versus 15,280
baseline tests (56 failures, 64 errors, 18 skips). The candidate introduced no
new failing or error method and removed four baseline-red methods; all changed
playback tests were green.

The deferred-first-service follow-up ran 138 focused scheduler, admission,
rewind, YM2612, bounded-playback, and real special-stage replay tests with no
failures, errors, or skips. Ten additional S1/S2 ROM-backed catalog, onset,
takeover, and request-transform tests also passed. Its JDK 21 `-Pci` full run
reported 15,274 tests, 52 failures, 64 errors, and 19 skips across exactly the
same 116 known failing/error methods recorded for this branch; no modified
audio or trace test failed.

### Blue Sphere output-level follow-up

The remaining listening rejection was outside the S3K SFX lifecycle. A
temporary diagnostic build of the pinned Genesis Plus GX core emitted FM5's
per-sample output and key-on operator attenuation during the same reviewed BK2
special-stage interval. OpenGGF matched the native FM5 envelope, including a
completed pickup restarting at attenuation `1023` and the ROM effect's
intentional carrier progression from `$05` to `$0A`. The shared
`VirtualSynthesizer` then divided the complete YM2612+PSG mix by two before
the 16-bit clamp; Genesis Plus GX does not perform that extra division.

The pre-fix OpenGGF segment measured `-22.18 dB` RMS against the native
`-16.34 dB` RMS. Removing only that synthetic headroom stage produced
`-16.16 dB` RMS and retained the exact register, envelope, service-order, and
selected-channel traces. `TestVirtualSynthesizerOutputLevel` independently
guards the shared S1/S2/S3K boundary by comparing an isolated YM2612 render
with the final synthesizer output; its RED was an exact `784 -> 392` halving.
The fresh JDK 21 full suite ran 15,275 tests with 52 failures, 64 errors, and
18 skips across the same 116 known baseline-red methods; the added test passed
and no audio-focused test failed.

### Blue Sphere key-on follow-up

The next listening rejection was narrowed to isolated pickups after the prior
effect had ended. The original finite replay skipped lag rows entirely, which
also skipped the continuously clocked Z80/YM audio frame. It now advances audio
on every recorded frame while advancing gameplay only on non-lag rows. The
bounded observer also records YM2612 key-ons in the same internal-sample domain
as its selected FM-channel samples, allowing every pickup window to start at
the actual FM5 key-on instead of at the earlier high-level request.

A scratch-only diagnostic build of the pinned Genesis Plus GX core showed that
operator envelope attenuation at key-on is legitimately history-dependent; it
must not be normalized to one trace-derived value. The durable comparison
therefore checks the source-owned admission writes and that each isolated
restart retains at least 95% of the preceding pickup's fixed 5,334-sample FM5
RMS energy. It also exposed one deterministic register-order difference:
OpenGGF wrote the base frequency and pan, then the modulated frequency, whereas
retail `zUpdateFMorPSGTrack` runs `zPrepareModulation`, `zDoModulation`, one
`zFMSendFreq`, then `zFMNoteOn`. The S3K `MOD_Z80` path now emits only the final
modulated frequency before key-on. A ROM-backed S1 onset test caught and
prevented this rule from changing S1/S2 behavior.

The focused post-fix selection ran 93 tests with no failures, errors, or skips,
including the real special-stage replay, S1 and S2 ROM presentation paths, S1
FM5 onset ordering, all three voice-write profiles, modulation, admission,
snapshot, and bounded-observer tests. Human listening remains the release gate;
this evidence establishes a positive source-level correction but does not claim
that the reported sound is subjectively resolved.

Two fresh JDK 21 full-suite runs executed 15,275 and 15,276 tests. Each reported
53 failures, 64 errors, and 18 skips, with every modified audio and trace test
green. The one failure beyond this branch's previously recorded 52/64 baseline
is
`TestObjectManagerLifecycle#s2ExecThenLoadBypassesVerticalFilterWithoutPreExecLoad`;
it also fails in isolation, and this candidate changes no object-loading source
or test. It remains an unrelated baseline defect rather than being hidden or
treated as evidence that the listening issue is resolved.

Phase 7 cleanup remains deferred. The rejected semantic-observer worktree and
its protected stashes are not part of this delivery.
