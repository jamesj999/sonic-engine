# Bounded YM replay and release-slice evidence

## Scope

This extends the existing physical diagnostic capture without changing chip
clocking, bus writes, audio selection or the production FM backend. It also
exercises repeated extra-life restoration and a mid-fade snapshot round trip
for AIZ1, AIZ2, HCZ1 and HCZ2 through the real presentation path.

## Narrow replay contract

The JSONL format remains `openggf-physical-chip-bus-v1`, with three additive,
nullable header fields:

- `rendered_output_frames`: output frames actually consumed by the tool;
- `ym_replay_start_ordinal`: the first event after a verified reset-state
  initialization prefix;
- `terminal_ym_cycle`: the exclusive raw YM clock endpoint, only when proven.

The complete original diagnostic prefix stays in the artifact. It may contain
PSG setup writes and zero-clock YM configuration boundaries, but no YM bus
strobes before the declared segment. At that boundary, the live Nuked core
must equal a fresh hardware reset, the facade must select YM2612/read mode,
internal output rate, no interpolation or mutes, no buffered or partial PCM,
and only deferred ordinary bus writes. The last condition matters: a queued
force-silence operation can leave the current core reset while hiding a future
non-bus mutation. A regression reproduces and rejects that case. Queue-kind
interpretation remains in `Ym2612Chip.Snapshot`, not in the tooling.

Native-rate rendering can clock ahead while draining queued register writes.
The endpoint is therefore:

```text
(consumed output frames + still-queued direct frames) * 24 + partial core cycles
```

A test with four paced writes and only one consumed output frame reconstructs
the raw bus into a fresh Nuked core and compares the entire terminal core
state. Multiplying requested frames by 24 alone fails this contract.

`OUTPUT_GATE_CHANGE` now distinguishes the session's final PCM silence gate
from actual chip `MODEL_MUTATION`. Tests prove that changing this gate changes
neither chip snapshot. An explicitly raw-YM-pin consumer may cross this typed
boundary. It cannot infer final presentation PCM: the gate value and PSG mix
are not reconstructed. Reset, restore, rollback and every actual YM model
mutation still invalidate the bounded raw replay. PSG remains a separate
clock domain, retained in the artifact but outside a YM-only comparison.

Consumers must reject missing/null proof fields, overflow, invalid event
ordering, unsupported origins and unknown boundaries. Older diagnostic files
are not silently promoted to complete replay inputs. This is engine-generated
Nuked evidence, not a recording of retail hardware or proof of SMPS parity.

## Test evidence

Base is `bbf28b7dc`; working changes were tested in the isolated coordination
worktree on JDK 21.0.11. Logs stay under that worktree's `target/`.

- `audio-next-capture-red.log`: existing export test fails because duration
  and endpoint proof are absent (1 failure, no errors).
- `audio-next-output-gate-red.log`: real device gate emits generic model
  mutation instead of the typed presentation-only boundary (1 failure).
- `audio-next-capture-queue-red.log`: queued force-silence is incorrectly
  accepted as a reset-origin segment (1 failure).
- `audio-next-capture-final.log`: 40 focused tests, zero failures/errors/skips,
  including endpoint replay, deferred mutation rejection, device/port, chip
  snapshot/observer and repeated extra-life tests.
- `audio-next-slice-focused.log`: 81 focused tests, zero failures/errors/skips,
  including producer rewind, suppression, held-rewind restore deferral and live
  rewind cleanup. This run preceded the observation-only gate taxonomy change.

Commands (set `S3K_ROM` to the discovered verified absolute ROM path):

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Ds3k.rom.path=$S3K_ROM" -Dtest=TestPhysicalChipCapture,TestSmpsPhysicalDevice,TestSmpsPhysicalPort,TestYm2612ChipSnapshot,TestChipWriteObserver,TestS3kOneUpRestoreRom test
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Ds3k.rom.path=$S3K_ROM" -Dtest=TestS3kOneUpRestoreRom,TestPhysicalChipCapture,TestChipWriteObserver,TestAudioPresentationProducerRewind,TestAudioManagerRewindSuppression,TestRewindControllerAudioSuppression,TestHeldRewindAudioRestoreDeferral,TestLiveRewindManagerAudioCleanup test
```

## Listening and reference status

Standalone engine renders are useful audition and chip-performance inputs,
not a synchronized AIZ-to-HCZ gameplay comparison. The existing TraceChaser
host can drain diagnostic PCM, but its supported S3K complete-audio path does
not by itself provide a matching end-to-end WAV pair for this slice. The native
PCM observer patch and selftest files do not establish that such a supported
capture has been run. No canonical fixtures were replaced.

Human A/B listening, equivalent reference clips for the complete slice, and
lower-end/platform validation remain pending. The existing listening checklist
remains the release gate; these automated results do not sign it off.

The first 15-second engine AIZ1 input passed the independent strict consumer:
799,005 raw stereo frames, 233,222 YM strobes including streamed DAC, and zero
Java/C Nuked sample mismatches. Its endpoint is 19,176,120 internal clocks;
7,715 PSG events are retained but excluded from this explicitly YM-only check.
One typed output-gate boundary is crossed without claiming presentation PCM.
The initial artifact records `bbf28b7dc` plus a dirty working tree; final
archive provenance must retain the exact capture rather than relabel it.

### Reference producer follow-on

Read-only inspection of TraceChaser `4fb6d0802` confirms the missing boundary:
`GpgxHost.Advance()` disables sound, while `AdvanceDiagnosticAudio()` and
`DrainDiagnosticAudio()` already provide sound-producing advance and stereo
sample extraction. `S3kCompleteAudioCaptureRunner` calls the former and its
supported CLI writes raw-event JSONL for the pinned Knuckles movie, not WAV.
The Sonic/Tails request runner is bounded to 5,400 rows and has no CLI. Native
PCM tests inject requests and therefore cannot establish authentic movie audio.

The smallest follow-on is a diagnostic-only continuous Sonic/Tails BK2 WAV
adapter with row/sample-offset provenance, verified sound-on/off state
equivalence, exact packet concatenation, and no-replace publication. Faithful
paired replay also needs actual external tempo-write observation and ordering
at the stopped-Z80 `$1C08` boundary. The current approved observer exception
is mailbox `$1C0A` only; extending it requires a scoped producer/input-contract
review. Later comparison RAM must not be converted into invented commands.
No TraceChaser code or canonical fixtures changed in this milestone.

### Standalone audition corpus

An external task archive contains `slice-music/s3k-music-{01,02,03,04}` FM and
mixed WAVs, physical JSONL and logical-write logs: AIZ1, AIZ2, HCZ1 and HCZ2.
The manifest SHA-256 is
`75e226ac3301e51e6e238566cb136f621bac03dddd3260b02dc63e13db4c6ea3`.
All sixteen file hashes/sizes were independently rechecked. Each WAV contains
3,196,022 stereo PCM16 frames (approximately 60 seconds); its integer header
rate is 53,267 Hz, versus the producer's exact internal rate 53,267.034722 Hz.
All logs identify producer `25a8c34f31ddb84d0f4a24964ff3c8c4c577493a`, clean,
and the verified locked-on ROM SHA-1. All have start ordinal 21, terminal cycle
76,704,528, capacity 3,000,000 and no overflow or dropped events.

| Music | Physical events | Validated YM events | Retained PSG events |
|---|---:|---:|---:|
| AIZ1 (`01`) | 982,278 | 951,050 | 31,209 |
| AIZ2 (`02`) | 1,645,318 | 1,616,872 | 28,427 |
| HCZ1 (`03`) | 808,627 | 778,720 | 29,888 |
| HCZ2 (`04`) | 1,073,278 | 1,042,094 | 31,165 |

The maintained validator accepted every log, with one typed output-gate
boundary each. These are audition inputs, not a claimed listening result.
Reproduce in a new external output directory, setting `S3K_ROM` and
`AUDIO_ARCHIVE` to verified absolute paths (substitute IDs `02`–`04`):

```bash
mvn -o -Dmse=off -B org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
  -Dexec.mainClass=com.openggf.tools.audio.FmSfxRenderTool \
  "-Dexec.args=--game s3k --rom $S3K_ROM --music 01 --rate internal --max-seconds 60 --physical-writes --physical-capacity 3000000 --out $AUDIO_ARCHIVE/slice-music"
```

Build the intended producer revision before using this command. Preserve its
generated identity rather than labeling old compiled classes as a later commit.
