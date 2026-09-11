# S3K sound-driver oracle: reference capture, fixture, comparator

**Date:** 2026-08-30
**Branch / worktree:** `feature/ai-sdre-oracle-s3k` (`.worktrees/sdre-oracle-s3k`),
from `feature/ai-sound-driver-re` `f087b8947` with `feature/ai-sdre-gaps` and
`feature/ai-sdre-spec-s3k` merged.
**Kind:** design + delivery record (phase-B oracle lane, S3K).
**Inputs:** `2026-08-30-sound-driver-re-gap-analysis.md` (oracle lane),
`2026-08-30-s3k-sound-driver-behaviour-spec.md`,
`2026-08-30-s3k-sound-driver-routine-map.md`,
`docs/architecture/audits/audio/2026-08-30-audio-oracle-tooling-map.md`.
**Source rule:** sources-closed — driver behaviour statements cite the
skdisasm-backed routine map/spec only; TraceChaser/engine code was read for
"what the tooling does today".

## 1. What was built

A committed, re-runnable capture with a complete-service comparison projection
for the S3K Z80 sound driver (driver-RAM track state + ordered YM/PSG writes),
with the reference produced by the
native headless GPGX harness rather than the S1 lane's Lua/EmuHawk path
(which cannot see Z80-issued chip writes and is barred for this lane).

| Piece | Path |
|---|---|
| Reference capture entry point (C#, compiled against the pinned TraceChaser harness sources) | `tools/audio/s3k/S3kAudioOracleReferenceCapture.cs` |
| Capture runner (verifies stock + observer-core hashes, compiles, runs headless) | `tools/audio/run_s3k_audio_oracle_reference.sh` |
| Committed reference fixture (gzip JSONL, 5,400 invocations) | `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz` |
| Fixture identity sidecar | `src/test/resources/audio/parity/s3k/s3k-aiz1-intro-metadata-v1.json` |
| Reference reader/decoder (RAM → zTrack vocabulary; digest-validating) | `com.openggf.tools.audio.parity.s3k.S3kAudioReferenceReader` |
| Field inventory (GATE vs DIAGNOSTIC, ROM + engine source per field) | `S3kAudioFieldRegistry` |
| Engine capture host (drives `SmpsDriver` with the recorded request timeline) | `S3kOpenGgfAudioCapture` |
| Engine state normalizer (snapshots → driver-RAM vocabulary) | `S3kAudioStateNormalizer` |
| Comparator (validation-first, no realignment, first divergence) | `S3kAudioParityComparator` |
| CLI (`validate` / `compare`, exit 0/2/3/4/5) | `S3kAudioParityTool` |
| Fixture integrity + break-it tests | `TestS3kAudioOracleFixtureContract`, `TestS3kAudioParityComparator` |
| Frontier record | `docs/status/audio-frontier-log.md` |

## 2. Reference capture semantics

One reference row = one emulated frame. After boot, each ordinary row contains
one `zVInt` driver invocation (spec §1: the whole interrupt update runs inside
vertical blank; the DAC loop between interrupts touches no track RAM). This is
not true before the driver is installed: power-on rows contain no driver, and
the initial `zStopAllSound` service crosses frames 13-14. Per row:

- **`mailbox` (pre-frame)** — `zMusicNumber`/`zSFXNumber0`/`zSFXNumber1`
  (`1C0A-1C0C`) read *before* the frame advances: the 68k-written requests
  the coming invocation consumes (`zFillSoundQueue`, map §4.2). These are
  driver *inputs*, recorded so the engine can be driven with the same request
  timeline; they are never copied into engine state.
- **`writes`** — the frame's ordered, CPU-tagged YM/PSG bus writes from the patch-0001
  observer's kind-3/kind-4 chip events, decoded with the production YM
  address-latch rule (subjects 0/2 latch a register address per port and
  survive frames; subjects 1/3 emit `port/register/value`); PSG events emit
  the raw byte. Each write also records the issuing CPU. The committed fixture
  retains this complete authenticated bus stream; the Java driver's comparison
  projection admits only CPU 1 (Z80) writes and excludes CPU 2 (68k) host
  writes. No finer service-ownership projection is available in this v1
  frame-shaped capture.
- **`ram` (post-frame)** — Z80 `1C00h-1FA0h`
  (`zDataStart..zTracksSaveEnd`), the driver's variable + track + 1-up-save
  RAM, read from the core's `Z80 RAM` memory domain after the frame. Post-pass
  sampling matches the S1 recorder convention (trace row N is after frame N).
- A `metadata` first row pins schema, ROM SHA-1/CRC32, movie name + SHA-256 +
  frame count, RAM window, observer core hash; a `terminal` row pins tick
  count, decoded write count, and a SHA-256 over the tick-row bytes.

Determinism: two consecutive 5,400-frame captures were byte-identical.

### Observer provenance

The capture uses the **production** patch-0001 (`buffer-z80-audio-events`)
observer core, verified against the committed
`native/gpgx-audio-observer/artifact-lock.json` identity
(compressed SHA-256 `e65315743a6a1228…`, build id `cba4d8c88cf968a9`) and a
stock BizHawk 2.11 home verified against `install-core.sh`'s `LOCKED_STOCK`
list. The runner script re-verifies both hashes on every run and assembles
the BizHawk home in the caller's scratch directory. The S3K-only diagnostic
patches (0002/0003, cycle-stamped) were **not** used: the frame oracle needs
frame + execution order, which the production core provides,
and the diagnostic locks are `production_lock_eligible: false`.

The C# entry point compiles against the pinned TraceChaser harness sources
(gitlink `9e51ff79e`) with the same hash-pinned Roslyn `csc.exe` the harness
build uses; it lives in OpenGGF (not the submodule) because the submodule
cannot be pushed from this lane. The harness's own `Bk2Reader`,
`GpgxHost`, manifest loader and `ApplyFrame` are reused unchanged.

## 3. Fixture

`s3k-aiz1-intro-reference-v1`: movie frames 0-5399 (90 s) of the committed
`s3k-complete-sonic-tails.bk2` from power-on — boot, SEGA chant, title music
(`25h`), file select, Knuckles intro theme (`1Fh`), AIZ1 music (`01h`,
≈54 s) and 10+ distinct gameplay SFX (`33h 3Ch 3Dh 48h 4Ah 59h 62h AFh B1h
B7h BAh …`), satisfying the ≥10 s music + ≥6 SFX brief. 1.4 MB gzipped
(23 MB raw), within the few-MB budget;
`TestTraceFixtureCompressionGuard`'s scope is `src/test/resources/traces/`
and the fixture is compressed regardless. `TestS3kAudioOracleFixtureContract`
re-verifies gzip SHA-256, uncompressed SHA-256, sidecar agreement, source-BK2
identity and the stream's terminal digest on every suite run.

## 4. Comparator semantics

`S3kAudioParityTool compare` decodes the reference (validation first: schema,
ROM identity, RAM window, ordinal continuity, terminal digest), replays the
recorded request timeline into the engine's real `SmpsDriver` +
`Sonic3kSmpsLoader`/`Sonic3kSmpsSequencerConfig` (music `01h-32h`, credits
`DCh`, SFX `33h-DBh`, `E0h/E2h/E6h-FEh` stop-all, `E4h` stop-SFX, mirroring
`zPlaySoundByIndex` D:1641-1665; unimplemented command tails are reported),
projects boot by the source-owned `zPalDblUpdCounter = 5` completion store and
then advances one outer-frame driver service per tick, captures chip
writes via `ChipWriteObserver`, and normalizes
`SmpsDriverSnapshot`/`SmpsSequencerSnapshot` into the driver-RAM vocabulary.
Before semantic comparison, the comparator consumes the reference tick's typed
`ProducerInputEvidence`. An
`UNAVAILABLE_DURING_PRODUCER_SUSPENSION` value is authenticated producer
evidence, not a guessed match and not an engine state. It stops comparison at
that service with `REFERENCE_LIMITATION`; the comparator never realigns to a
later service. When the evidence is `AVAILABLE`, comparison per tick proceeds
in order: GATE globals (`zCurrentTempo`,
`zTempoAccumulator`, `zTempoSpeedup`, `zSpeedupTimeout`), GATE track fields
for the sixteen fixed slots (nine music, seven SFX, ROM slot order), then the
ordered Z80-owned write stream. First difference wins; nothing realigns.
`S3kAudioFieldRegistry` is the executable inventory of which zTrack/global
bytes are compared and which stay DIAGNOSTIC (unmapped engine coordinate:
`DataPointer`, modulation phase bytes, `zDACIndex`, fades, queue bytes).

The CLI's machine output is one stable JSON object for `compare --format json`
(fixed key order; nullable location fields are emitted as JSON `null`):

```json
{"schema":"openggf.s3k_audio_oracle_report.v1","kind":"REFERENCE_LIMITATION","ticks_compared":128,"tick":128,"role":null,"field":"producer_input","event":null,"reference":"mailbox input was unavailable for the first observable service after reference producer interrupt services suspended","openggf":"<unavailable>"}
```

The same schema and keys are used for `MATCH` and ordinary mismatch reports;
`event` is the decoded-write index for an event mismatch and remains `null`
when the limitation is attached to producer input. Human output uses the
`S3K audio oracle: REFERENCE_LIMITATION` status. Exit status `0` means all
compared services matched, `2` is usage, `3` is an ordinary semantic mismatch
(including an un-evidenced missing write), `4` is an invalid capture or tool
failure, and `5` is an authenticated reference limitation. Automation must
therefore accept `5` as a valid, fail-closed comparison outcome rather than
classifying it as a capture/tool failure.

## 5. Broken on purpose (evidence)

- Baseline on real data: `--ticks 3` → `MATCH (3 ticks)`.
- One corrupted RAM byte (`zCurrentTempo`, tick 1) in a temp copy with the
  terminal digest recomputed → `GLOBAL_STATE_MISMATCH / tick 1 /
  currentTempo / reference 64 / openggf 0`, exit 3.
- Same corruption without recomputing the digest → refused:
  `terminal body digest mismatch`, exit 4.
- One corrupted engine write → `EVENT_VALUE_DIFFERENT` at its exact
  tick/event (`TestS3kAudioParityComparator`, committed).

The 260-service production check (`--ticks 260`) reaches services 0-127 and
then stops at service/tick **128**. The underlying reference burst is event 0
(`YM part II 82h = FFh`), but the report is deliberately typed as
`REFERENCE_LIMITATION`, with `field=producer_input`, because the first service
after the producer's interrupt-suspension interval carries
`UNAVAILABLE_DURING_PRODUCER_SUSPENSION` and the exact reason
`mailbox input was unavailable for the first observable service after
reference producer interrupt services suspended`. It exits **5**, reports no
engine divergence, and does not realign to service 129. A synthetic missing
write without that evidence remains `EVENT_MISSING` and exits **3**.

## 6. Frontier progression (corrected 2026-08-31)

The initial tick-3 attribution was wrong: its four PSG writes carry observer
CPU tag 2 and exactly match the 68k `PSGInitValues` power-on loop
(`sonic3k.asm:175-184,260`), not the Z80 driver's `zStopAllSound`. The reader
now validates the CPU tag and projects only CPU 1 writes without modifying the
committed fixture. Ticks 0-12 then match; the first driver-owned divergence is
tick **13**, `EVENT_MISSING` event 0, reference YM part II `82h = FFh`.

That write begins the real `zInitAudioDriver -> zStopAllSound` boot service
(spec §1 and §5, `D:523-551,2460-2521`), which spans capture frames 13-14
before normal `zVInt` service. The current frame-shaped engine host has no
source-owned driver-installation boundary. A valid next revision must model or
capture that boot as a service boundary; it must not trigger engine behavior
from tick 13 or from the reference write stream.

The service projection now implements that revision without changing the
fixture: frames before `zPalDblUpdCounter` becomes 5 are grouped into one boot
service, because the store is the final source-owned initialization marker
before `ei` (`D:523-551`). The engine
host emits the exact 85-write `zStopAllSound` boot sequence and matches the
following `E1h` fade-init service, including its unconditional four-write PSG
silence. Service 49's `FFh` command also matches its 84-write stop-all prefix.
The following `zPlaySEGAPCM` loop clears its flag and disables interrupts for
100 frame rows; the projection excludes its `2Ah` sample transport and
`2Bh=80` DAC entry and emits no fictional `zVInt` services during that span.
The resulting stream has 5,286 services. Services 0-127 match in the 260-service
check. Service 128 (source frame 242) contains another stop-all burst whose
`FEh` input was written and consumed inside the captured frame. The v1
pre-frame mailbox misses that boundary, so it cannot authorize the same engine
request. Because the service carries the authenticated producer-input
availability reason above, this is `REFERENCE_LIMITATION` (exit 5), not an
engine divergence; comparison stops there without realignment. The exact
84-write stop prefix remains proven at service 49 for `FFh` and at service 138
for the next music activation, so those proofs are retained while this
producer-input frontier remains open.

## 7. Known limits and open questions

1. The engine capture host models the immediate PSG-silence edge of fades but
   not their active-song envelope. `E3h` PSG-mute is source-closed in both
   production and the engine host: the typed command applies exactly
   `9F BF DF FF` through the session-owned physical device without changing
   music, SFX ownership, override, tempo, or pending-service state. This is a
   product-gap closure backed by `zPlaySoundByIndex` / `zPSGSilenceAll`, not a
   claim that the authenticated fixture contains an E3 request. `E4h` releases logical SFX
   ownership, but the shipped seven-slot conditional physical write/
   restoration walk remains the next exact-write frontier. `FFh` owns the
   implemented 84-write stop prefix and raw SEGA PCM transport exactly once;
   full shipped `FFh` control-flow parity beyond that transport, including the
   producer-side pre-consumption mailbox needed at service 128, remains open.
   Unsupported tails are logged.
   The SEGA PCM transport is intentionally a separate tier; its source loop
   disables interrupts, so transport-only frame rows are not driver services.
   A producer-side pre-consumption mailbox probe is still required for 68k
   requests written and consumed within one frame.
   Divergences beyond such ticks are only meaningful once those transforms
   are modelled or the passage avoids them.
2. `voiceIndex`/`modulationCtrl` engine mappings are best-effort composites
   (registry documents both); if the first state divergence lands on one of
   them, verify the mapping against the routine map before treating it as a
   driver bug.
3. The reference records lag frames (`lag` flag); the S3K driver still runs
   its `zVInt` on 68k-lag frames, so no row is skipped on either side.
4. PAL, pause, 1-up and speed-shoes passages are not in this fixture; they
   need their own movies (the capture runner takes any BK2).
5. The oracle compares from power-on; per-song oracles (like S1 GHZ) can be
   cut from any tick range later. The committed fixture keeps the power-on
   origin and authenticates both CPUs' writes, while the comparison projection
   admits only Z80 writes and groups the split boot service semantically.
