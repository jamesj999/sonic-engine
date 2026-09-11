# Nuked-OPN2 FM Core Port: Engine Contract

Status: design contract for replacing `com.openggf.audio.synth.Ym2612Chip` with
a Java port of the pinned Nuked-OPN2 (`tools/audio/nuked-opn2/PIN.md`, upstream
commit `335747d78cb0abbc3b55b004e62dad9763140115`, `ym3438.c` 1.0.12,
LGPL-2.1-or-later). Branch `feature/ai-nuked-opn2-fm-core`, measured at
`10cad0c2a` (develop `8290558c4`).

## Context

The engine's FM core is a Java port of the SMPSPlay/libvgm GPGX `ym2612.c`
(header comment of `Ym2612Chip.java`). The port replaces it with a derivative of
Nuked-OPN2 and nothing else. This document fixes what the surrounding engine
requires of the replacement so the port stage can be written against the
contract without reading the old core's body. Everything below was established
from the callers, the public signatures of `Ym2612Chip`, the tests, and
black-box probes through the public API only; no line of the old core's body,
GPGX, libvgm, MAME or BizHawk was consulted.

### Source rule

The only emulator source the port may read is the pinned `ym3438.c` /
`ym3438.h`. `tools/audio/nuked-opn2/fetch-source.sh --output <abs dir>`
reproduces and hash-verifies the tree; the pinned public API is reproduced in
`PIN.md`. The pinned revision has **no** `OPN2_GenerateResampled`,
`OPN2_WriteBuffered`, write-buffer queue, or ladder-effect switch: the chip is
clocked one internal cycle at a time with `OPN2_Clock(chip, Bit16s[2])`,
written with `OPN2_Write(chip, port, data)` where `port` is the 2-bit A0/A1
bus address (0 = port-0 address, 1 = port-0 data, 2 = port-1 address, 3 =
port-1 data), and configured by the process-global
`OPN2_SetChipType(ym3438_mode_ym2612 | ym3438_mode_readmode)`. Anything the
engine needs beyond that (resampling, write pacing, DAC streaming, mutes,
snapshots) is adapter code written from this contract, and the adapter must
say so in its file header. The Java derivative keeps the Nuke.YKT copyright
notice and the LGPL-2.1-or-later grant in its header (project licence is
GPL-3.0, which accepts it).

## Public API to preserve

Every symbol below is used by production code or by a test this contract keeps.
The class name, package and nesting stay `com.openggf.audio.synth.Ym2612Chip`
so no caller changes.

### Construction, configuration, lifecycle

| Member | Callers | Contract |
|---|---|---|
| `Ym2612Chip()` | `VirtualSynthesizer`, all chip tests | Constructs a reset chip at `getDefaultOutputRate()` (44100). Must not touch any global; the process-global `OPN2_SetChipType` of the C source becomes a per-instance field. |
| `void reset()` | tests | Full hardware reset (`OPN2_Reset`), rate and mode settings retained. |
| `static double getInternalRate()` | `AbstractSmpsAudioBackend.getSmpsOutputRate`, `SoundTestApp`, `TestBlipResamplerBitExactness`, `TestSmpsSequencerTempoMath`, parity tests | Returns 7670453.0 / 144 = 53267.0347222 (probe at `10cad0c2a`: `53267.03472222222`). `TestBlipResampler` hard-codes `53267.041666666664` for its own resampler instance; the chip value stays as it is. |
| `static double getDefaultOutputRate()` | `VirtualSynthesizer`, `SoundTestApp`, several tests | 44100.0. |
| `double getOutputSampleRate()` / `void setOutputSampleRate(double)` | `VirtualSynthesizer.setOutputSampleRate` (called from the constructor and on every backend rate negotiation) | Rate change re-targets the resampler without resetting register state. Production values: device rate (48000 under LWJGL/headless) or `getInternalRate()` when `audio.internalRateOutput` is true. |
| `void setChipType(int)` | `TestYm2612ChipGpgxParity` (2), `TestYm2612TimerCSM` (1) | Old meaning: 0 = discrete YM2612 (ladder DC offset), 1 = integrated, 2 = enhanced. See "Chip type" below for the mapping onto `ym3438_mode_*`. |
| `void setUseBlipResampler(boolean)` | none in production; probe only | Keep as a test/diagnostic hook or delete together with its callers; nothing depends on it. |
| `void setDacInterpolate(boolean)` | `VirtualSynthesizer.setDacInterpolate` (config `audio.dacInterpolate`, default true) | Engine-side DAC streaming quality flag (see DAC). |
| `void setDacHighpassEnabled(boolean)` | `TestYm2612ChipSnapshot` only | Engine-side DAC high-pass toggle; same status as `setUseBlipResampler`. |
| `void setDacData(DacData)` / `DacData liveDacDataReference()` (package-private) | `VirtualSynthesizer.setDacData`, `captureLiveDacDataReference`, `restoreLiveDacDataReference` | Identity-bearing DAC bank; not part of the snapshot. |
| `void setWriteObserver(ChipWriteObserver)` (package-private) | `VirtualSynthesizer.setChipWriteObserver` | See "Write observer". |

### Register interface

| Member | Callers | Contract |
|---|---|---|
| `void write(int port, int reg, int val)` | `VirtualSynthesizer.writeFm` (every `SmpsSequencer` register write), `silenceAll`, `setInstrument`, tests | Combined address+data write. **Port resolution:** the effective port is `port` OR bit 8 of `reg`: `write(0, 0x1B4, 0x1C7)` reaches port 1 register `0xB4` with value `0xC7` (`TestChipWriteObserver.directChipWritesReportResolvedUnsignedValuesExactlyOnce`). Register and value are masked to 8 bits before anything else sees them. |
| `void writeAddress(int port, int reg)` / `void writeData(int port, int val)` | none outside the class | Split bus writes. Keep them as the primitive `write` is built on (they map directly onto `OPN2_Write(chip, port*2, reg)` and `OPN2_Write(chip, port*2+1, val)`). |
| `int readStatus()` | `TestYm2612ChipBasics` | Status byte (`OPN2_Read(chip, 0)` with `ym3438_mode_readmode`): bit 0 timer A overflow, bit 1 timer B, bit 7 busy. |
| `void setInstrument(int ch, byte[] voice)` | `VirtualSynthesizer.setInstrument` (every SMPS voice load) | SMPS voice unpacking is **engine-side** and must be carried over unchanged; its exact write stream is pinned by `TestChipWriteObserver.setInstrumentReportsExistingKeyOffB0AndOperatorExpansionOrder`: key-off `0x28` for the channel first, then `0xB0+ch` (voice[0]), then per slot in slot order 0,1,2,3 with voice indices {1,3,2,4} for `0x30`, {21,23,22,24} for `0x40` (TL, only present in 25-byte voices; 19-byte S2 voices leave TL untouched, `TestYm2612VoiceLengths`), {5,7,6,8} `0x50`, {9,11,10,12} `0x60`, {13,15,14,16} `0x70`, {17,19,18,20} `0x80`, and `0x90` = 0. Port is `ch / 3`, register low bits `ch % 3`. |
| `void playDac(int note)` / `void stopDac()` | `VirtualSynthesizer.playDac/stopDac` from `SmpsSequencer` DAC tracks | Engine-side PCM streaming (see DAC). |

### Mute and silence

| Member | Callers | Contract |
|---|---|---|
| `void setMute(int ch, boolean)` | `VirtualSynthesizer.setFmMute` (sound test, presentation voice registry, `PsgSfxRenderTool`-style capture with FM muted) | Output-stage mute: the channel keeps running (registers, EG, timers all advance) and contributes nothing to the mix. Mutes are captured in `Snapshot.mutes()` and read by name in `TestAudioVoiceRegistry`. |
| `void silenceAll()` | `VirtualSynthesizer.silenceAll` (constructor, `SmpsDriver.stopAll`) | Issues exactly this write stream, in this order, through the observer: `0x28` = `00,04,01,05,02,06`, then for each register `0x30..0x8F` a port-0 write of `0xFF` followed by a port-1 write of `0xFF` (198 writes; `TestChipWriteObserver.silenceAllReportsEveryYmAndPsgWriteInProductionOrder` and `constructorObserverSeesTheCompleteInitialSilenceInExactOrder`). It is a register-level silence (ROM `zFMSilenceAll`), not a reset. |
| `void forceSilenceChannel(int ch)` | `SmpsDriver` (line 2179) when an SFX steals a channel | Resets the channel's envelope to silence immediately to avoid a chirp. Nuked has no such operation; the adapter implements it by key-off plus forcing the four operators' EG to the release-complete state in the port's own state, and must document the deviation from hardware (it is an engine policy, not chip behaviour). |

### Rendering

| Member | Callers | Contract |
|---|---|---|
| `void renderStereo(int[] left, int[] right)` / `renderStereo(int[] left, int[] right, int frames)` | `VirtualSynthesizer.renderFrames`, all chip tests | **Accumulates** `frames` output-rate stereo samples into the arrays (the PSG then accumulates on top). Chip-native scale: the old core's channel outputs are 14-bit (+8191/-8192 per channel clip; muted-mix probe values below are in this scale). The caller applies no gain before `MASTER_GAIN_SHIFT`. |

### Snapshot and rollback types

`public record Snapshot(...)`, `public record ChannelSnapshot(...)`,
`public record OperatorSnapshot(...)`, package-private
`record SfxAdmissionState(...)`, `Snapshot captureSnapshot()`,
`void restoreSnapshot(Snapshot)`, `SfxAdmissionState
captureSfxAdmissionState(int mask)`, `void restoreSfxAdmissionState(SfxAdmissionState)`.
See "Snapshot obligations".

### Test-only accessors

`getOperatorTotalLevelForTest`, `getChannelAlgorithmForTest`,
`getSsgEgActiveCountForTest`, `getSinTabForTest`, `getTlTabForTest`,
`getEnvTabForTest`, `computeModulationInput`, `computeModulationInputWithMem`,
`computeCarrierSum`. These expose GPGX-shaped internals (double-valued
algorithm routing, GPGX table checksums, an SSG-EG active counter) and are
retired with their tests (see "Tests"). `getOperatorTotalLevelForTest` and
`getChannelAlgorithmForTest` are the only ones whose tests are worth keeping
in spirit; re-express them over Nuked's register file (`TestYm2612VoiceLengths`
rewrite below).

## Call-order expectations

### Threads and locking

`SmpsDriver.read(short[], int)` is the only production render entry
(`SmpsCompositeVoice.read` -> `driver.read(scratch, samples)` on the audio
presentation producer). All sequencer stepping and chip writes happen under
`SmpsDriver.sequencersLock` inside `read`; SFX admission and mute changes from
the game thread also take that lock. The chip therefore never sees concurrent
access and needs no internal synchronisation.

### Write/render interleaving

Two read modes exist (`SmpsDriver.ReadMode`), and `AudioRegressionTest`,
`TestSmpsFadeHybridParity` require them to be PCM-identical:

- `SAMPLE_ACCURATE`: for each output frame, `advanceSequencersBatch(1)` (which
  may issue any number of `writeFm`/`setInstrument`/`playDac` calls), then
  `VirtualSynthesizer.render` for **one** frame (`renderStereo(..., 1)`).
- `HYBRID` (production default): the driver computes a safe chunk (never
  crossing a tempo frame or an observable sequencer event, minimum 32 frames
  else it falls back to single frames), advances all sequencers by the chunk,
  then renders the chunk in one `renderStereo(..., n)` call.

Consequences for the port:

1. Writes are applied **between** output samples and land before the next
   rendered sample. A run of writes issued between two samples (a whole voice
   load is 30 writes, `silenceAll` is 198) must all take effect before that
   next output sample in *both* modes, and rendering `n` frames in one call
   must equal rendering `1` frame `n` times. Nuked applies a write over its
   internal cycles (address latch, data latch, busy), so the adapter must drain
   each write's internal cycles synchronously at write time (clock the core
   without emitting output-rate samples, buffering the native samples it
   produces into the resampler) rather than deferring writes onto the render
   clock. The exact number of `OPN2_Clock` calls a write needs, and the
   busy-flag window, are read from `ym3438.c` and cited in the adapter; a
   write issued while busy on real hardware is ignored, and the Z80 drivers
   respect busy, so the adapter must *never* drop a write on the engine's
   behalf. The drained cycles count toward the resampler's native-sample
   stream so that pitch and timing are not disturbed.
2. The SMPS sequencer writes `0x28` key-off, then `0xA4`/`0xA0` (high byte
   first, `writeFmFreq`), then `0x28` key-on for a note; tie-notes skip both
   key writes; `0xB4` pan and `0x40` TL writes arrive mid-note. Ordering within
   the write stream is the sequencer's; the chip only has to honour hardware
   latching (`0xA4` high-byte latch before `0xA0`).
3. No production code writes timers (`0x24..0x27`), LFO (`0x22`; only tests
   and `VirtualSynthesizer` exercise it), or reads status. Timers/CSM must
   still work because `readStatus`, `TestYm2612ChipBasics` and
   `TestYm2612TimerCSM` exercise them and Nuked implements them natively;
   channel-3 special mode (`0x27` bit 6) is used by tests only.

### Rate and resampling

The old core resamples internally from `clock/144` to `outputRate` (a
`BlipResampler` with a `Snapshot` inside `Ym2612Chip.Snapshot`, plus a linear
`resampleAccum` path when `useBlipResampler` is off). **Decision: the adapter
keeps internal resampling.** `VirtualSynthesizer`, `PsgChip` and the
sequencers' `samplesPerFrame = rate / frameRate` all assume the chip emits
output-rate frames; changing that would move the resampler into
`VirtualSynthesizer` and re-open every snapshot and hybrid-parity test for no
gain. The native stream is one stereo sample per 24 internal cycles
(`OPN2_Clock` produces MOL/MOR each cycle; the output sample is the one at the
cycle the C source designates, verify and cite), at `7670453 / 144` Hz. Feed
the `BlipResampler` the same way the PSG does; the resampler is engine code
already proven bit-exact (`TestBlipResamplerBitExactness`).

### Chip type

Old `setChipType`: 0 discrete YM2612 (production default), 1 integrated, 2
enhanced. Nuked exposes `ym3438_mode_ym2612` (YM2612 DAC/output path with the
9-bit DAC and ladder behaviour modelled in the C source) versus YM3438 output
(the flag clear). Mapping: 0 -> `ym3438_mode_ym2612 | ym3438_mode_readmode`;
1 and 2 -> `ym3438_mode_readmode` only. The `readmode` flag is always set
because the engine reads status through `readStatus()` on a single accessor
and never selects a port. The default constructed type stays 0 (Mega Drive
model 1 discrete YM2612) because the traces and reference captures are of a
model-1 ROM run; this is what makes the DC offset below chip-inherent.

## DAC (register 0x2A/0x2B) contract

`SmpsSequencer` never writes `0x2A`. It writes `0x2B = 0x80` when a music
sequencer is constructed (DAC enable; SFX programs must not), `0x2B = 0x00`
when `audio.fm6DacOff` is set and a note plays on FM6, and otherwise drives PCM
through `playDac(note)` / `stopDac()`. `playDac` resolves `note` through the
`DacData` bank (`DacEntry(sampleId, rate)`, `baseCycles`) into a stream of
unsigned 8-bit samples that the old core feeds to the DAC at a pitch derived
from `rate` and `baseCycles` (the Z80 `zPlaySample` loop timing), with
optional interpolation (`dacInterpolate`) and a high-pass. This is
**engine-side**; Nuked only knows `0x2A`.

The adapter keeps `playDac`/`stopDac`/`setDacData`/`setDacInterpolate`/
`setDacHighpassEnabled` and implements them by scheduling `0x2A` data writes
onto the core at the sample's native cadence, expressed in chip internal
cycles so the write pacing is exact rather than output-rate quantised. The
cadence derivation must cite the ROM's DAC playback loop (Z80 driver
`zPlaySample` / `zDACDecodeTbl`-equivalent for each game) rather than measure
the old core; the old core's `Snapshot.dacStep()` value is public and may be
used as a *cross-check*, never as the source of the constant. DAC
interpolation and high-pass are presentation options with no hardware
counterpart: preserve their defaults (`dacInterpolate: true`, highpass off in
production, both on in `TestYm2612ChipSnapshot`) and note them in
`docs/status/known-discrepancies.md` if they are kept. `0x2B` bit 7 gating
(FM6 vs DAC) is native Nuked behaviour and must not be duplicated in the
adapter.

## Write observer

`ChipWriteObserver.onYm2612Write(port, register, value)` fires once per
*resolved* write (after the `0x100` port fold and 8-bit masking), for direct
`write` calls and for every write `setInstrument` and `silenceAll` expand to.
Observing must not change state or output
(`observationLeavesSnapshotsAndFutureOutputBitExact` compares snapshots by
Jackson `valueToTree` and 256 rendered frames). Internally scheduled `0x2A` DAC
writes from `playDac` are **not** observed today (the observer sees the
sequencer's `0x2B` only); keep it that way so write-log fixtures stay stable.

## Snapshot obligations

### Where the snapshot travels

`Ym2612Chip.Snapshot` -> `VirtualSynthesizer.Snapshot.ym()` ->
`SmpsDriverSnapshot.synthSnapshot()` -> `SmpsCompositeVoice` /
`PresentationVoiceSnapshot` -> `AudioPresentationSnapshot` inside
`AudioLogicalSnapshot`, kept in memory by the rewind controller. Also used by
`SmpsDriver`'s admission rollback token and the `AbstractSmpsAudioBackend`
music-override save/restore. Never serialised to disk; equality in tests is
Jackson `valueToTree` (`TestChipWriteObserver`,
`TestSfxAdmissionMutationJournal`).

### What the records must satisfy

- `public record Snapshot` nested in `Ym2612Chip`, with a `boolean[] mutes()`
  component (read by name in `TestAudioVoiceRegistry` lines 1777/1795) and
  `int currentDacSampleId()` / `double dacPos()` (asserted in
  `TestYm2612ChipSnapshot`). Every other component is implementation-defined
  and **will** change: the port replaces the GPGX field list with the Nuked
  `ym3438_t` state (cycle counter, slot/channel arrays, EG/LFO/timer state,
  write latches, busy, DAC latch/test bits, mode flags) plus the adapter's
  own state (output rate, resampler snapshot, DAC streaming state, mutes,
  chip type).
- Every component is Jackson-serialisable without configuration (primitives,
  arrays, nested records); arrays defensively copied on construction and
  access, as the current records do.
- `restoreSnapshot` is total and authoritative: restoring into a freshly
  constructed chip or a diverged one yields bit-identical future output
  (`TestYm2612ChipSnapshot.restoreSnapshotProducesBitExactFutureSamplesForFmDacAndResampler`
  covers FM, DAC and resampler tail after 41 frames of pre-roll and a
  perturbation).
- `captureSnapshot` is pure (two captures equal, no output perturbation).
- `ChannelSnapshot`/`OperatorSnapshot` may be reshaped or dropped; nothing
  outside the chip names their components. If Nuked's slot-major layout makes
  per-channel records awkward, a flat `Snapshot` with `int[]` slot arrays is
  acceptable provided `SfxAdmissionState` can still restore a channel subset.

### SFX admission rollback

`captureSfxAdmissionState(mask)` (bits 0..5) captures what is needed to undo
writes to the masked channels and the shared latches; after
`restoreSfxAdmissionState` the full `VirtualSynthesizer` snapshot must be
Jackson-equal to the pre-mutation one for every mask (`TestSfxAdmissionMutationJournal`).
The record must not retain a field of type `VirtualSynthesizer.Snapshot` or
whose simple type name is `Snapshot` (`assertRetainsNoSnapshot`). With Nuked's
slot-major state the per-channel capture must cover the four slots of each
masked channel plus the channel's algorithm/feedback/pan/fnum registers, the
address latch and busy state, the DAC streaming state (an SFX can own FM6),
and any per-chip counter the masked writes can perturb; a channel-selective
restore that leaves a shared counter stale will fail the Jackson equality.

### Rewind guards

No structural rewind guard scans `com.openggf.audio.synth`:
`StaticStateRewindCoverageAnalyzer.SCAN_ROOT` is `com/openggf/game`, and
`TestRewindCoverageGuard` / `TestRewindFieldDispositionGuard` audit object
classes only. Changing every field of `Ym2612Chip` and its records fires **no**
guard; the only failures are the behavioural tests named here and compilation
of the three accessors named by tests. The audio-side guards that do run
(`TestAudioPresentationProducerRewind`, `TestAudioManagerRewindSuppression`,
`TestRewindHistoryArming`, `TestAudioBackendBypassGuard`,
`TestAudioPresentationArchitectureGuard`, `TestAudioPresentationSnapshotParity`)
exercise the chip only through the driver and must stay green. If the port
introduces any `static` mutable state (Nuked's global chip type is the
candidate), `TestStaticStateRewindCoverageGuard` does not see it but the
per-instance rule above forbids it anyway.

## Tests: keep, retire, replace

### Keep unchanged (behavioural, black-box)

| Test | Why it survives |
|---|---|
| `audio/synth/TestChipWriteObserver` | Pins port folding, masking, `setInstrument` and `silenceAll` write streams, observer purity. Pure engine contract. |
| `audio/synth/TestYm2612ChipSnapshot` | Bit-exact restore of FM + DAC + resampler; names only `currentDacSampleId`, `dacPos`. |
| `audio/driver/TestSfxAdmissionMutationJournal`, `TestSmpsDriverSnapshot`, `presentation/TestAudioVoiceRegistry`, `TestAudioPresentationSnapshotParity` | Drive the chip through the driver; name only `mutes()`. |
| `audio/synth/TestBlipResamplerBitExactness`, `tests/TestBlipResampler`, `TestBlipResamplerTailSnapshot` | Resampler, unchanged by the port (uses `getInternalRate()` as an input rate). |
| `audio/smps/TestSmpsSequencerTempoMath` | Uses `getInternalRate()` only. |
| `audio/driver/TestSmpsFadeHybridParity`, `AudioRegressionTest` hybrid-vs-sample-accurate cases | Render-mode equivalence; independent of which core, but the strongest check that "n frames in one call == 1 frame n times" holds for the new adapter. Cost: tens of seconds, ROM-gated. |
| `tests/TestYm2612InstrumentTone`, `tests/TestYm2612Attack` | "Voice + key-on produces audible PCM" style sanity; do not compare values. Re-read their thresholds after the port (they assume the +8191 scale). |
| `tests/TestYm2612ChipBasics` | Timer A flag through `readStatus`, DAC latch audibility, key-on audibility. The SSG-EG active-count assertion inside it (`getSsgEgActiveCountForTest`) is retired (below); the rest stays. |
| `tests/TestYm2612TimerCSM`, `tests/TestYm2612SsgEg` | Black-box (CSM key-on after timer A; SSG-EG repeat keeps the envelope looping). They call `setChipType(1)` to avoid the type-0 offset and use loose thresholds; keep, re-verify thresholds. |
| `audio/driver/TestSmpsFadeAudioThroughput` | `performance-measurement` tag; the per-frame bench below reuses its harness style. |

`AudioRegressionTest`'s reference-file cases (`testMusicEhzMatchesReference`
etc.) compare against `src/test/resources/audio-reference/*.wav`, which does
not exist in the tree; the helper returns `null` and the cases skip. They are
irrelevant to the port unless references are regenerated, in which case they
must be regenerated *after* the port from the validated build.

### Retire (implementation-coupled) and replace

| Test | Coupling | Replacement |
|---|---|---|
| `audio/synth/TestYm2612ChipGpgxParity` | Sample values hand-captured from GPGX at type 2 and type 0; cannot hold for Nuked (different EG/phase/DAC pipeline by design). | `audio/synth/TestYm2612ChipNukedParity`: same register scripts (S1 bomb voice, ch3 special mode, partial key-on, algorithm 4 discrete), expected values generated by the pinned C build (validation stage 1). `TestBuildToolingGuard.TASK4_SUPPORT_FILES` lists the old file by path: update that inventory in the same commit. |
| `tests/audio/Ym2612TableDumper` | GPGX `sinTab`/`tlTab`/`envTab` checksums via `get*TabForTest`. | Delete with the accessors. Nuked's tables (`logsinrom`, `exprom`, `eg_stephi`, `fn_note`, `lfo_*`) are pinned by the golden-vector tests instead; a checksum test over the port's tables against values computed from the C arrays is optional. |
| `tests/TestYm2612AlgorithmRouting` | Asserts the `double`-valued GPGX routing helpers `computeModulationInput*`/`computeCarrierSum`. | Delete with the helpers. Algorithm routing is exercised per algorithm by the golden vectors (extend stage 1 scripts to all eight algorithms with a distinct TL per operator). |
| `tests/TestYm2612VoiceLengths` | `getOperatorTotalLevelForTest`/`getChannelAlgorithmForTest`. | Rewrite over the write observer: assert the resolved write stream for a 19-byte and a 25-byte voice (which already implies which registers were reached), or keep the two accessors as thin reads of Nuked's register file. |
| `TestYm2612ChipBasics.ssgEg*` case | `getSsgEgActiveCountForTest`. | Drop the counter assertion; the SSG-EG behaviour is covered by `TestYm2612SsgEg` and stage-1 vectors with `0x90..0x9F` set. |

## Mixer-level policy and the +384 LSB finding

`VirtualSynthesizer.renderFrames` is the only mix point: both chips accumulate
into shared `int` buffers, then `MASTER_GAIN_SHIFT = 1` (-6 dB) and a 16-bit
clip. There is no FM preamp, no PSG preamp call, and no per-game logic. The
FM:PSG balance is therefore entirely "each chip at its own hardware-relative
scale", and the PSG contract fixes the PSG scale at GPGX's `configure(100,
0xFF)`. The Nuked port must emit at the same nominal scale as the old core
(per-channel full scale of +-8191/8192, summed) so the balance does not move;
if Nuked's native 9-bit-DAC-times-channel scale differs, the adapter applies
one explicit, cited shift and nothing else.

**DC offset.** `docs/architecture/validation/2026-08-29-psg-clean-room-capture-comparison.md`
(branch `feature/ai-psg-clean-room`) found every FM-muted or FM-silent render
resting at a constant +384 LSB. Black-box probe of the current core at
`10cad0c2a` (`silenceAll`, all channels muted, 44.1 kHz and internal rate):

| Chip type | Resting output (chip scale) | After `MASTER_GAIN_SHIFT` |
|---|---|---|
| 0 (discrete, production default) | +768 on both channels from sample 16 (723, 774 on the first two output samples while the resampler settles) | +384 |
| 1 (integrated) | 0 | 0 |
| 2 (enhanced) | 0 | 0 |

So the offset is **chip-inherent to the discrete-YM2612 model** (6 channels
times +128, the model-1 ladder/DAC resting level), not a mixer bug; it sits
below `MASTER_GAIN_SHIFT` and is identical with all channels muted, which is
why muting removes signal but not the offset. Consequences for the port:

- Nuked in `ym3438_mode_ym2612` models the YM2612 DAC path in its own way.
  The port must not add a +128-per-channel constant to reproduce the old 768;
  whatever resting level the pinned C source produces for a silenced,
  DAC-disabled chip is the correct one and is recorded here (stage 1 measures
  it). If it is non-zero, the mix-path control (stage 3) compares after
  subtracting each core's own resting level, and `known-discrepancies.md`
  gets the new number.
- Mutes stay at the output stage *after* the chip's DAC/ladder model, so a
  muted channel's resting contribution matches hardware (a keyed-off channel on
  real silicon still sits at its resting level). Do not "fix" muted channels to
  contribute zero.

## Performance budget

Probe at `10cad0c2a` (JDK 21, this machine, JIT-warm, 6 channels keyed with
the S3K test voice, 44.1 kHz output, one 60 Hz game frame = 735 output frames):

| Path | Cost per game frame |
|---|---|
| `Ym2612Chip.renderStereo` alone, blip on | 79 us |
| same, blip off | 70 us |
| same, 735 single-frame calls (sample-accurate mode) | 80 us |
| `VirtualSynthesizer.render` (YM + PSG + mix) | 87 us |

The existing measurement harness (`TestSmpsFadeAudioThroughput`, tag
`performance-measurement`, run with `-Dgroups=performance-measurement`) reports
180 rendered-seconds per wall-second for the full driver stack at
`2026-06-11-baseline.md`, i.e. about 93 us per game frame of audio.

A cycle-accurate core clocks 7670453 / 60 = 127841 internal cycles per game
frame across 24 slots, so a Nuked port is expected to be roughly an order of
magnitude slower than the table-per-sample old core. **Budget:** the port must
render one game frame of 6-channel FM at 44.1 kHz in <= 1.5 ms JIT-warm on the
reference machine (about 9 % of a 16.7 ms frame, leaving the presentation
producer's headroom intact), and the single-frame-call path must not exceed
1.2x the batched path (the driver falls back to single frames whenever a
sequencer event is near). If the first port lands above 1.5 ms, the
optimisation stage may restructure loops but must keep bit-exactness with the
C build (stage 1 is re-run after every optimisation commit). Rewind playback
re-renders audio, so the budget also bounds held-rewind step cost.

## Validation plan

### Stage 1: bit-exactness against the pinned C build (no ROM)

- **Reference harness:** `tools/audio/nuked-opn2/harness/` builds the pinned
  `ym3438.c` (fetched with `fetch-source.sh`, never vendored into the repo)
  into a small C program that reads a script of `type <flags>` /
  `write <port> <reg> <val>` / `clock <n>` / `render <n>` lines, drives
  `OPN2_SetChipType`, `OPN2_Write`, `OPN2_Clock`, and prints the native
  MOL/MOR `Bit16s` stream at every output cycle plus the status byte on
  `status` lines. The write pacing in the script (how many internal cycles
  each write is given) is the same rule the adapter uses, so both sides see
  identical bus timing.
- **Java runner:** `audio/synth/TestYm2612ChipNukedParity` feeds the same
  script to the port with resampling disabled (output rate =
  `getInternalRate()`, the mode the old parity test already used) and compares
  the native stream sample-exactly (`int` equality) for at least 2 s per
  script. Scripts: the S1 bomb voice (all eight algorithms, feedback 0..7),
  ch3 special mode, partial key-on bit order, LFO on with AMS/PMS per channel,
  SSG-EG each mode, timer A/B overflow and CSM, DAC enable with a ramp of
  `0x2A` writes, `silenceAll`, and a random 10 k-write fuzz seeded and
  recorded. Both chip flags settings (type 0 and type 1 mapping).
- **Write logs from the engine:** the `ChipWriteObserver` log of a real SFX or
  song (the S1 `A6`, `B5`, `C6`, `CE`, `BE`, `AF`, `CF` FM-only SFX from the
  PSG comparison, plus one music loop per game) replayed through both the C
  harness and the port with identical pacing must be sample-exact. This is the
  "identical write logs" proof and it needs only the log, not a ROM at test
  time (logs are committed as text fixtures under
  `src/test/resources/audio/nuked-opn2/`).

### Stage 2: tolerance comparison against the old core through the real driver

- **Tool:** generalise `PsgSfxRenderTool` (branch `feature/ai-psg-clean-room`,
  `src/main/java/com/openggf/tools/audio/PsgSfxRenderTool.java`) into
  `FmSfxRenderTool`: same loaders (`--game s1|s2|s3k --rom --sfx --out
  [--rate] [--max-seconds]`), writing `<game>-<id>-mix.wav`, `<game>-<id>-fm.wav`
  (all PSG channels muted), and `<game>-<id>-ym-writes.txt` (`<frame> <port>
  <reg> <val>` per line at one-frame read granularity). Add `--music <id>`
  for a 30 s song render. Run at `10cad0c2a` (old core) and at the port
  commit, with `-Dmse=off` and the three absolute ROM properties; outputs to
  a task directory outside the repository.
- **Metrics** per write-delimited segment (the PSG comparison's Python
  analysis, reused): dominant frequency within 0.5 %, RMS level within 1.0 dB
  (wider than the PSG's 0.1 dB because the two cores' EG models differ by
  design), note onset/offset positions within 1 output frame, and a
  cross-correlation >= 0.95 at constant lag for sustained tones. Divergences
  above tolerance are triaged against the C build first (stage 1 says whether
  the port or the old core is wrong); an old-core inaccuracy is recorded in
  `known-discrepancies.md` as closed, a port defect blocks.
- **Control:** PSG-only SFX (`A0`, `A4`, `CD`, `B6` on S1; `A0`, `A4` on S2;
  `62`, `36` on S3K) must render byte-identically before and after apart from
  the resting-level constant, proving the mix path did not move.

### Stage 3: suite and human gate

- `mvn -Dmse=off "-Dsonic1.rom.path=..." "-Dsonic2.rom.path=..." "-Ds3k.rom.path=..." test`
  naming the kept tests, then `mvn -Dmse=off -Pguards test -B`, then the
  `performance-measurement` group for the bench line.
- Listening checklist
  `docs/architecture/validation/audio/2026-08-21-smps-playback-listening-checklist.md`:
  re-run every row that names FM (cross-game rows 44-45; S1 57-58; S2 69; S3K
  78, 80-82) and record results there with the port commit hash.

### Stage 4: per-frame performance bench

`audio/driver/TestYm2612FrameCostMeasurement` (tag `performance-measurement`):
constructs the chip, keys six channels with the stage-1 voice, warms 2000
frames, then times 6000 renders of 735 frames at 44.1 kHz for batched and
single-frame calls and prints one `YM_FRAME_COST median_us=... single_us=...`
line, alongside the existing `FADE_THROUGHPUT` line. Both numbers go into the
port's validation report under `docs/architecture/validation/audio/` with the
budget verdict.

## Out of scope

- Moving the resampler out of the chip, or changing `MASTER_GAIN_SHIFT`.
- YM3438 (model 2) output as a user option; the mapping exists but no config
  key is added by the port.
- Reproducing the old core's `setChipType(2)` "enhanced" mode as anything
  other than the YM3438 flag mapping.
- Any change to `SmpsSequencer`, `SmpsDriver` or `PsgChip`.
