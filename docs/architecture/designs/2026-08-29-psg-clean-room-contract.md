# PSG Clean-Room Engine Contract

## Context

`com.openggf.audio.synth.PsgChip` is the engine's only SN76489 core. It is being
rewritten from scratch against the reference core rather than patched. This
document is the contract the rewritten class must satisfy so that every existing
caller, every rewind path, and every behavioural test keeps working without
edits, while the implementation-coupled tests are retired and replaced.

The contract was written from the chip's public surface and its callers only.
The private body of the current `PsgChip.java` was deliberately not read; nothing
below describes how the current implementation works, only what its consumers
require of it. Figures stamped with a commit hash were measured at
`8290558c4` (worktree `feature/ai-psg-clean-room`, based on `develop`).

### Provenance note (added after the provenance audit)

This contract describes the **pre-existing public API** and the semantics its
callers already depend on. Those semantics were historically GPGX-shaped, so
GPGX names appear below as *compatibility targets and validation comparators*
— not as a source. The rewritten `PsgChip` was implemented sources-closed from
the hardware specification
(`docs/architecture/research/audio/2026-08-29-sn76489-clean-room-spec.md`)
alone; the implementer did not open this document's reference-core section,
the previous class body, GPGX, libvgm, MAME or BizHawk. An independent
provenance audit compared the result against the old class and GPGX `psg.c`
and found no shared identifiers, comments, state decomposition or clock
bookkeeping (see
`docs/architecture/validation/2026-08-29-psg-clean-room-capture-comparison.md`).
Where a line below says "mirrors `psg_config`" or "GPGX behaviour", read it as
"must remain call-compatible with what callers expect" — the behaviour itself
is derived from the spec.

### Reference core

- **Primary:** Genesis Plus GX `core/sound/psg.c` as vendored in BizHawk
  `427556b5ef3ac437eba754d90c5e7e9096c9a8df` (`waterbox/gpgx/Genesis-Plus-GX/`),
  the exact revision `tools/bizhawk-headless/native/gpgx-audio-observer/fetch-source.sh`
  pins and whose PSG update loop `0003-s3k-chip-pcm-events.patch` already
  instruments. The existing class Javadoc names this provenance and the
  `INTEGRATED` / `DISCRETE` chip-type enum mirrors GPGX's `PSG_TYPE`.
- **Secondary:** the SMS Power SN76489 hardware notes (provenance stub
  `docs/architecture/research/audio/SN76489 - Development - SMS Power!.md`) for
  register semantics, and MAME `sn76496.cpp` only for the every-toggle noise
  clocking variant that GPGX does not implement.
- Nothing under `docs/` or the reference cores is a runtime asset source
  (hard rule 1); the reference is used to derive behaviour and golden vectors.

## Public API to preserve

Exact signatures, package `com.openggf.audio.synth`. Every member below has a
compile-time consumer unless marked otherwise; none may be removed or retyped.

```java
public class PsgChip {
    public enum ChipType { INTEGRATED, DISCRETE }

    public PsgChip();                                   // 44100 Hz, INTEGRATED
    public PsgChip(double sampleRate);
    public PsgChip(double sampleRate, ChipType type);

    public void setSampleRate(double sampleRate);
    public void setChipType(ChipType type);             // no production caller
    public void setHqMode(boolean hq);
    public boolean isHqMode();
    public void setNoiseShiftOnEveryToggle(boolean everyToggle);
    public boolean isNoiseShiftOnEveryToggle();

    public Snapshot captureSnapshot();
    public void restoreSnapshot(Snapshot snapshot);
    SfxAdmissionState captureSfxAdmissionState(int affectedChannelMask); // package-private
    void restoreSfxAdmissionState(SfxAdmissionState state);              // package-private

    public void setMute(int ch, boolean mute);
    public void configure(int preamp, int panning);     // no production caller
    public void reset();                                // no production caller
    public void silenceAll();
    void setWriteObserver(ChipWriteObserver observer);  // package-private

    public void write(int value);
    public void renderStereo(int[] left, int[] right);
    public void renderStereo(int[] left, int[] right, int len);

    public record Snapshot(...);                        // see "Snapshot and rewind"
    record SfxAdmissionState(...);                      // package-private
}
```

Callers, by member (all `src/main/java` unless noted):

| Member | Callers |
|---|---|
| `PsgChip(double, ChipType)` | `VirtualSynthesizer` constructor (`INTEGRATED`, output rate) |
| `PsgChip()` | `TestChipWriteObserver`, `TestPreparedSfxAdmission` (oracle chip) |
| `setSampleRate` | `VirtualSynthesizer.setOutputSampleRate` (constructor and later device-rate changes via `AbstractSmpsAudioBackend.getSmpsOutputRate`) |
| `setWriteObserver` | `VirtualSynthesizer.setChipWriteObserver`, `TestChipWriteObserver` |
| `silenceAll` | `VirtualSynthesizer.silenceAll` (constructor, `SmpsDriver.restoreSnapshot` with a null synth snapshot, backend stop paths) |
| `write` | `VirtualSynthesizer.writePsg` (fed by `SmpsDriver.writePsg` and `writeRawPsg`, which are fed by `SmpsSequencer`) |
| `renderStereo(int[], int[], int)` | `VirtualSynthesizer.renderFrames` |
| `setMute` | `VirtualSynthesizer.setPsgMute` (audio voice registry user masks) |
| `setNoiseShiftOnEveryToggle` / `is...` | `VirtualSynthesizer` pass-through, driven by `AbstractSmpsAudioBackend.applyPsgNoiseConfig` and `AudioPresentationSourceFactory` from `audio.psgNoiseShiftEveryToggle` |
| `setHqMode` / `isHqMode` | tests only (`TestPsgChipSnapshot`, `TestPsgChipGpgxParity`) |
| `captureSnapshot` / `restoreSnapshot` | `VirtualSynthesizer.captureSynthSnapshot` / `restoreSynthSnapshot`; `TestPsgChipSnapshot`; `TestPreparedSfxAdmission` |
| `captureSfxAdmissionState` / `restore...` | `VirtualSynthesizer.captureSfxAdmissionState` / `restoreSfxAdmissionState` (SFX admission journal rollback) |
| `configure`, `reset`, `setChipType` | none in `src/`; keep as public API with GPGX semantics (`psg_config`, `psg_reset`, `psg_init`) |

`ChipWriteObserver` (`onPsgWrite(int value)`) is the only other type the chip
depends on besides `BlipDeltaBuffer` / `BlipResampler`, which the rewrite may
use or replace at will. `Sonic2PsgEnvelopes` and
`TestSonic2PsgEnvelopesAgainstRom` are sequencer-side envelope tables and do not
touch the chip.

## Behavioural contract

### Register interface

- `write(value)` accepts one SN76489 data-bus byte. Only the low eight bits are
  significant: `write(0x19F)` must behave as, and be observed as, `0x9F`
  (`TestChipWriteObserver.directChipWritesReportResolvedUnsignedValuesExactlyOnce`).
- Latch/data semantics are the hardware's: bit 7 set selects channel
  `(value >> 5) & 3` and type bit 4 (volume vs tone/noise) and carries the low
  four bits; bit 7 clear is a data byte for the latched register, supplying the
  upper six bits of a tone period (or, per hardware, the low four bits again for
  volume and noise registers). The latched channel must be part of the snapshot
  and readable as `Snapshot.latch()`; `TestPreparedSfxAdmission` asserts the
  final latch after a commit is channel 1 and channel 3 respectively.
- Noise register: bits 0-1 select the shift rate (`0..2` fixed, `3` = tone 2
  period); bit 2 selects white (feedback) vs periodic noise; writing the noise
  register resets the LFSR, as on hardware and in GPGX.
- Tone period 0 (and, on the integrated chip, period 1) must produce the
  GPGX behaviour for that chip type, not a divide-by-zero or a silent channel.
- A write is applied at the chip's current position in time: the point reached
  by the previous `renderStereo`, synchronised to the next PSG clock boundary
  (GPGX `PSG_MCYCLES_RATIO`, 16 x 15 master cycles). Writes are never applied
  retroactively into already-rendered samples. Intra-buffer timing is the
  driver's responsibility (it splits `renderFrames` around ticks), not the
  chip's; the chip has no timestamp parameter and must not grow one.
- `silenceAll()` is exactly four `write()` calls in order `0x9F, 0xBF, 0xDF,
  0xFF` (ROM `zPSGSilenceAll`), each reported to the observer
  (`TestChipWriteObserver.silenceAllReportsEveryYmAndPsgWriteInProductionOrder`
  and `constructorObserverSeesTheCompleteInitialSilenceInExactOrder`, which
  counts exactly 4 `PSG:` events out of 202).

### Write observer

- `setWriteObserver(null)` must be tolerated and equivalent to
  `ChipWriteObserver.NONE`.
- Every write, whatever its origin (`write`, `silenceAll`, or any internal
  helper that emits register bytes), is reported exactly once, with the masked
  8-bit value, in production order.
- Observation is side-effect free: an observed chip and an unobserved chip fed
  the same writes produce Jackson-equal snapshots and bit-identical output
  (`TestChipWriteObserver.observationLeavesSnapshotsAndFutureOutputBitExact`).
  The observer is never part of the snapshot.

### Rendering

- `renderStereo(left, right, len)` **accumulates** into the caller's arrays;
  `VirtualSynthesizer.renderFrames` pre-fills them with the YM2612 output and
  the chip must add, not assign. `len` is clamped to
  `min(len, left.length, right.length)`; the two-argument overload renders
  `min(left.length, right.length)` samples. `len <= 0` is a no-op that does not
  advance time.
- The chip emits `int` samples with no clipping and no master gain (see
  "Mixer-level policy"). Clipping to 16 bits and `MASTER_GAIN_SHIFT` belong to
  `VirtualSynthesizer`.
- **Chunking invariance.** The output stream must not depend on how it is
  sliced: rendering 200,000 one-sample buffers must produce the same samples
  as one 200,000-sample render, and the chip's internal timebase must stay
  bounded (no drift, no growing backlog) under either pattern. This is the
  black-box form of the two current reflection tests on `clocks` and
  `blip.offsetFp`.
- Output is band-limited (GPGX-style delta buffer). Two quality modes exist:
  `setHqMode(false)` (default, GPGX linear/"fast" kernel) and `setHqMode(true)`
  (sinc). The mode is snapshot state. `TestPsgChipGpgxParity` asserts the
  default is fast.
- With default panning both channels are identical for every render
  (`assertArrayEquals(left, right)` in the parity tests).
- `setSampleRate` may be called at any time, before or after writes, and must
  never throw. Register, latch, mute and mode state survive; only the
  resampling timebase is re-derived. Supported rates include 44100 (default),
  48000, and the YM internal rate (`Ym2612Chip.getInternalRate()`, ~53.267 kHz)
  when `audio.internalRateOutput` is on.
- `setMute(ch, mute)` silently ignores `ch` outside `0..3`; a muted channel
  contributes nothing but keeps advancing so that unmuting is seamless. Mutes
  are snapshot state (`Snapshot.mutes()`, asserted by `TestAudioVoiceRegistry`).

### Noise LFSR modes

- `setNoiseShiftOnEveryToggle(false)`: GPGX / libvgm behaviour, the LFSR
  advances on positive edges of the noise clock only.
- `setNoiseShiftOnEveryToggle(true)`: MAME-style, the LFSR advances on every
  polarity toggle, i.e. exactly twice as often for the same noise clock.
- The chip's own default at `8290558c4` is `true`
  (`TestPsgChipGpgxParity.noiseLfsrClocksOnEveryToggle` uses a default chip),
  and production always overrides it from `audio.psgNoiseShiftEveryToggle`
  (shipped `src/main/resources/config.yaml` says `true`;
  `AudioPresentationTuning.DEFAULT` says `false`). The rewrite keeps the chip
  default `true` so no caller changes behaviour; the config-default question is
  out of scope here.
- Both modes are snapshot state.

### `configure(preamp, panning)` and `reset()`

These have no production caller today but are public and must keep GPGX
semantics so the mixer can adopt them:

- `configure(preamp, panning)` mirrors `psg_config`: `preamp` is a percentage
  (100 = unity), `panning` is the SN76489 stereo byte where bits 7..4 enable
  channels 3..0 on the left and bits 3..0 enable them on the right. The
  constructor default is `configure(100, 0xFF)`: unity, all channels both sides.
- `reset()` mirrors `psg_reset`: registers to their power-on values (tone
  periods 0, all attenuations 0xF, noise register 0, LFSR seeded), timebase
  restarted, mutes and modes untouched, no writes emitted (so nothing observed).

### Call order in production

1. `VirtualSynthesizer(rate, observer)`: `new PsgChip(rate, INTEGRATED)` ->
   `setWriteObserver(observer)` -> `setSampleRate(rate)` -> `silenceAll()`.
   The observer therefore sees the initial four silence writes; no render has
   happened yet.
2. Steady state: the driver performs zero or more `write()` calls per tick,
   then `renderStereo` for the tick's frame span; `setMute` may interleave
   from the presentation layer.
3. Rewind or SFX rollback: `captureSnapshot` / `restoreSnapshot` or the
   masked `captureSfxAdmissionState` / `restoreSfxAdmissionState`, always
   between renders, possibly followed immediately by more writes.
4. `setSampleRate` again only when the device rate changes.

## Snapshot and rewind obligations

### Where the snapshot travels

`PsgChip.Snapshot` -> `VirtualSynthesizer.Snapshot.psg()` ->
`SmpsDriverSnapshot.synthSnapshot()` -> `SmpsCompositeVoice` presentation
snapshot -> `AudioPresentationSnapshot` inside `AudioLogicalSnapshot`, which
`RewindController` keeps in in-memory audio keyframes. It is never serialised to
disk; equality in tests is by Jackson `valueToTree` (`TestChipWriteObserver`,
`TestSfxAdmissionMutationJournal`, `TestSmpsSfxConstructionPurity`) or by
`assertDeepEquals` (`TestPreparedSfxAdmission`).

### What the record must satisfy

- Remain a `public record Snapshot` nested in `PsgChip`. Its component list is
  implementation-defined and **may change freely** in the rewrite, subject to:
  - it exposes `int latch()` and `boolean[] mutes()` under exactly those
    names (asserted by name in `TestPreparedSfxAdmission` and
    `TestAudioVoiceRegistry`);
  - every component is Jackson-serialisable without configuration
    (primitives, arrays, nested records — no cycles, no `Object`);
  - array components are defensively copied on construction and on access;
  - it carries everything needed for bit-exact continuation, including sample
    rate, quality mode, noise mode, chip type, gain/pan configuration, mutes,
    registers, latch, per-channel counters and polarities, LFSR state, clock
    remainder, and the band-limited buffer tail.
- `restoreSnapshot` is total: restoring into a freshly constructed chip of any
  rate/mode, or into a chip that has diverged, yields output bit-identical to
  the uninterrupted chip for all subsequent writes and renders
  (`TestPsgChipSnapshot.restoreSnapshotProducesBitExactFutureSamples`,
  `TestVirtualSynthesizerSnapshot.restoreSnapshotProducesBitExactMixedFutureFrames`).
  The snapshot, not the target chip, is authoritative for rate and modes:
  `TestPreparedSfxAdmission` restores a driver snapshot into `new PsgChip()`.
- `captureSnapshot` is pure: two captures with no intervening call are equal,
  and capturing does not perturb future output
  (`TestSmpsSfxConstructionPurity`).

### SFX admission rollback

- `captureSfxAdmissionState(mask)` captures only what is needed to undo
  writes to the channels in `mask` (bits 0..3). For every `mask` in `0..15`,
  after a latch+volume write to each masked channel,
  `restoreSfxAdmissionState` must make the full snapshot Jackson-equal to the
  pre-mutation snapshot (`TestSfxAdmissionMutationJournal`).
- The `SfxAdmissionState` record must not retain any field whose type is
  `VirtualSynthesizer.Snapshot` or whose simple type name is `Snapshot`
  (`assertRetainsNoSnapshot`); keep it a flat per-channel record plus the
  latch and clock remainder. Restoring an admission state after further
  unrelated renders is not required.

### Rewind guards

No structural rewind guard scans `com.openggf.audio.synth`:
`StaticStateRewindCoverageAnalyzer.SCAN_ROOT` is `com/openggf/game`,
`ObjectClasspathScan` covers the object packages only, and
`TestRewindFieldDispositionGuard` audits default-capture object classes. A
change to `Snapshot`'s fields therefore fires **no** guard; the only things
that fail are the behavioural tests above and compilation of the two accessors
named by tests. The audio-side guards that do run
(`TestAudioPresentationProducerRewind`, `TestAudioManagerRewindSuppression`,
`TestRewindHistoryArming`, `TestAudioBackendBypassGuard`,
`TestAudioPresentationArchitectureGuard`) exercise the chip only through the
driver and must stay green.

## Tests: keep, retire, replace

### Keep unchanged (behavioural, black-box)

| Test | Why it stays |
|---|---|
| `audio/synth/TestPsgChipSnapshot` | Snapshot completeness via future-output equality; uses only the public API |
| `audio/synth/TestVirtualSynthesizerSnapshot` | Same, through the mixer |
| `audio/synth/TestChipWriteObserver` | Observer masking, ordering, counts, and side-effect freedom |
| `audio/driver/TestPreparedSfxAdmission` | Contention writes and `latch()` through the driver, with a `PsgChip` oracle driven by public writes |
| `audio/driver/TestSfxAdmissionMutationJournal` | Masked rollback for all 64 x 16 masks; snapshot-retention check |
| `audio/smps/TestSmpsSfxConstructionPurity` | Capture purity |
| `audio/presentation/TestAudioVoiceRegistry` | `mutes()` propagation |
| `audio/driver/TestSmpsFadeAudioThroughput` | Measurement harness, sanity assertions only |
| `tests/TestPsgChipGpgxParity.defaultsToFastModeForCrisperGenesisParity` | Public default |

### Retire (implementation-coupled) and replace

`tests/TestPsgChipGpgxParity` is the only class that reflects into the chip. It
reads private fields `clocks`, `noiseShiftValue`, `noiseShiftWidth`, `regs`,
`freqCounter`, `freqInc`, `polarity`, `blip`, `blip.offsetFp`,
`blip.FACTOR_FP_BITS`, writes into private arrays, and invokes the private
method `psgUpdate(int)`. Those tests pin the old core's internals and cannot
survive a clean room. The bit-exact vectors in the same class
(`toneRenderOutputStaysExactInFastAndHqModes`,
`noiseRenderOutputStaysExactInFastAndHqModes`) were produced by the old
implementation, not by the reference core, so they are an oracle for the old
code rather than for the hardware. Replace the class with
`tests/TestPsgChipReferenceParity`:

| Retired test | Replacement (black box) |
|---|---|
| `renderKeepsClockCarryBoundedToPsgCycleRemainder` | Chunking invariance: 200k x 1-sample renders equal one 200k render at 44100 and 48000 |
| `blipTimingDoesNotAccumulateLargeBacklogAt48khz` | Same test; add a wall-time bound so a backlog shows up as a slowdown |
| `noiseLfsrClocksOnEveryToggle` / `noiseLfsrCanBeConfiguredToPositiveEdgeOnly` | Periodic-noise mode (`regs[6] = 0x03` via `write(0xE3)`, tone 2 driving) rendered in both modes; count output transitions over a fixed span — every-toggle must produce exactly twice the positive-edge count, and the positive-edge stream must match the GPGX golden vector |
| `toneRenderOutputStaysExactInFastAndHqModes`, `noiseRenderOutputStaysExactInFastAndHqModes` | Same write scripts, expected arrays regenerated from the GPGX harness (below) and cited to it in the test |

`tests/TestVirtualSynthesizerMix.mixedFmAndPsgOutputRemainsBitExact` pins a
32-sample mixed FM+PSG vector generated by the old chip. Keep the test but
regenerate its expected array **only after** the golden-vector suite passes; the
FM half of the vector must be unchanged (verify by rendering with the PSG muted
before and after).

`audio/AudioRegressionTest` compares against `src/test/resources/audio-reference/*.wav`,
which do not exist in the tree (the tests skip). Do not regenerate them from
either chip; they would only pin an implementation.

## Mixer-level policy

The chip emits hardware-relative levels; balancing lives in the mixer.

- The chip's native scale is GPGX's: per-channel sample =
  attenuation-table value x `chanAmp / 100`, with the constructor default
  `configure(100, 0xFF)`. The rewrite must not embed any private gain, offset,
  or FM-balancing constant; if achieving the engine's existing FM:PSG balance
  needs a non-unity preamp, it is expressed as an explicit
  `psg.configure(preamp, 0xFF)` call in `VirtualSynthesizer` with a cited
  justification (GPGX's own defaults, `psg_preamp = 150` against
  `fm_preamp = 100`, are the citable precedent).
- `VirtualSynthesizer.renderFrames` is the only place that sums chips, applies
  `MASTER_GAIN_SHIFT` (1, -6 dB at `8290558c4`) and clips to 16 bits. The YM
  side already clips per channel to +8191/-8192 inside `Ym2612Chip`; the PSG
  side must not clip.
- Panning is a chip property (the SN76489 stereo byte on Mega Drive is held
  in the chip's output stage), so `configure`'s panning stays in the chip;
  there is no separate mixer pan for PSG.
- No per-game or per-zone level logic anywhere in this path (hard rule 2).

## Validation plan

### Reference captures on hand

`docs/architecture/research/audio/` holds these recordings:
`2f-shield-reference.wav`, `3c-spindash-release-reference.wav`,
`a6-hurt-by-spikes-{reference,emu,emu-internal}.wav`, `af-shield-emu.wav`,
`b5-ring-right-{reference,emu,emu-internal}.wav`,
`be-roll-{reference,emu,emu-internal}.wav`,
`c6-ring-loss-{reference,emu,emu-internal}.wav`,
`ce-ring-left-{reference,emu,emu-internal}.wav`, `inhaling-bubble-ours.wav`,
`inhaling-bubble-reference.mp3`, `ring.mp3`, `s2-4f.mp3`, `Signpost.wav`,
`Signpost_fixed.wav`, `sonic-the-hedgehog-signpost.mp3`.

**None of them is PSG-dominated.** Checked against the disassembly headers
(`docs/s1disasm/sound/sfx/`): Ring `B5` is FM5 only, Ring Left `CE` is FM4,
Ring Loss `C6` is FM4+FM5, Hit Spikes `A6` is FM5, Roll `BE` is FM4, Shield
`AF` is FM5, Get Bubble `AD` is FM5, Signpost `CF` is FM4+FM5; the S3K ring
(`Sound/SFX/33`, `34`) is likewise FM4/FM5. They are still useful as a
**no-regression control** for the mix path (below) but cannot validate the PSG.

PSG-carrying SFX that do exist in the ROMs, from the same headers:

| Game | SFX | Channels |
|---|---|---|
| S1 | `A0` Jump | PSG1 only |
| S1 | `A4` Skid | PSG2 + PSG3 |
| S1 | `CD` Switch, `B6` Spikes Move | PSG3 only |
| S1 | `AA` Splash, `C1` Break Item | PSG3 + FM5 |
| S2 | `A0` Jump | PSG1 only |
| S2 | `A4` Skidding | PSG |
| S2 | `BC` Spin Dash Release | FM5 + PSG3 (noise) |
| S3K | `62` Jump | PSG1 only |
| S3K | `36` Skid | PSG1 + PSG2 |

### Stage 1: golden-vector parity against the reference core (no ROM)

- **Tool:** a small standalone C harness built from the pinned GPGX
  `core/sound/psg.c` and `core/sound/blip_buf.c` (source obtained with
  `tools/bizhawk-headless/native/gpgx-audio-observer/fetch-source.sh`), living
  under `tools/audio/psg-reference/`. It reads a script of
  `write <byte>` / `render <n>` lines, drives `psg_init(PSG_INTEGRATED)`,
  `psg_config(100, 0xFF)`, `psg_write`, `psg_update`/`psg_end_frame`, and
  prints the interleaved `int` stereo stream. A Java-side runner
  (`tests/TestPsgChipReferenceParity`) feeds the same script to `PsgChip` and
  compares.
- **Scripts:** the three write scripts already in the retired tests (tone
  period 0x200 at volume 0; white noise `E3`/`F0`; periodic noise driven by
  tone 2), the `prime` script from `TestPsgChipSnapshot`, a full attenuation
  sweep on each channel, tone periods 0, 1, 2 and 0x3FF, and a noise-register
  rewrite mid-stream. Each at 44100 and 48000 Hz, fast and HQ mode,
  positive-edge noise.
- **Metric:** sample-exact `int` equality of the chip's accumulated output for
  at least 2 s per script. HQ mode is compared to GPGX with its HQ (sinc) blip
  kernel enabled; if the engine's kernel is not GPGX's, the HQ criterion
  degrades to max |diff| <= 1 LSB with identical zero-crossing counts and the
  divergence is recorded in `docs/status/known-discrepancies.md`.
- Every-toggle noise mode has no GPGX counterpart: it is validated by the
  2x transition-count property against the positive-edge stream.

### Stage 2: SFX PCM through the real driver against GPGX

- **Engine tool:** a headless renderer shaped like
  `src/test/java/com/openggf/audio/AudioReferenceGenerator` (which today renders
  S2 SFX to WAV through `SmpsDriver` + `SmpsSequencer`), generalised to the S1
  and S3K loaders (`Sonic1SmpsLoader.loadSfx`, `Sonic3kSmpsLoader.loadSfx`) as
  `com.openggf.tools.audio.PsgSfxRenderTool`. Invoked with `-Dmse=off` and the
  three absolute ROM properties; output WAVs go to a task directory outside
  the repository, never under `docs/`.
- **Reference:** BizHawk headless GPGX with `0003-s3k-chip-pcm-events.patch`,
  whose `gpgx_s3k_pcm_psg_sample` tap records the PSG stereo stream on the
  240-master-cycle native clock after attenuation and panning and before Blip
  resampling. Play each SFX in the table above from a short BK2 that triggers
  it over silence (sound test or a title-screen jump).
- **Metric:** (a) native-clock stream: sample-exact after aligning to the first
  non-zero sample; (b) resampled 16-bit output: max |diff| <= 1 LSB, identical
  zero-crossing count per channel, and identical dominant frequency per note
  (`f = 3579545 / (32 x period)`) from a short-window FFT; (c) durations equal
  to the sample.

### Stage 3: no-regression control for the mix

Render the FM-only captures' SFX (`B5`, `CE`, `C6`, `A6`, `BE`, `AF`, `CF`)
with the tool from stage 2 before and after the rewrite. Because the PSG is
silent for these, the output must be byte-identical; any difference means the
rewrite changed the mixer or the silence level, not the PSG.

### Stage 4: suite and human gate

- `mvn -Dmse=off "-Dsonic1.rom.path=..." "-Dsonic2.rom.path=..." "-Ds3k.rom.path=..." test`
  with the kept tests above explicitly named, then `mvn -Dmse=off -Pguards test -B`.
- Listening checklist rows in
  `docs/architecture/validation/audio/2026-08-21-smps-playback-listening-checklist.md`
  that name the PSG (FM/PSG balance, overlapping FM/PSG SFX, pause/resume PSG
  silence, fade-out PSG stop) are re-run and the results recorded there.

## Out of scope

- Regional (PAL) PSG clocks: the constructor takes no region and the reverted
  `d550382ab` work is deferred to 0.7 with the rest of the playback
  authenticity programme.
- Config default for `audio.psgNoiseShiftEveryToggle`.
- Any change to `VirtualSynthesizer`'s master gain.
