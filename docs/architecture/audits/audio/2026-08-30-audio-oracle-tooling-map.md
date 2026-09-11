# Audio oracle tooling map

**Date:** 2026-08-30
**Branch / worktree:** `feature/ai-sdre-map-oracle` (worktree `.worktrees/sdre-map-oracle`),
branched from `feature/ai-sound-driver-re` at `f087b8947`.
**TraceChaser gitlink:** `9e51ff79e7a542f3c50d96618a7e24e6fc72397e` (`tools/tracechaser`),
initialised for this audit with `git submodule update --init tools/tracechaser`.
**Source rule:** the disassemblies are the behavioural authority. Nothing in this
audit was taken from SMPSPlay, libvgm, GPGX's sound code, the reverted
`feature/ai-smps-transaction-parity` branch, or third-party SMPS write-ups. Engine
code and tooling were read only to establish what they do today.

## Purpose

Inventory every mechanism that exists for comparing the engine's sound driver
against the real driver running on emulated hardware, say what each one
captures and at what granularity, and record which of them actually run on this
machine today, with the exact commands that worked and failed. The last section
lists the blockers to a per-game oracle.

## Where the tooling now lives

Commit `4667f2c3b` ("chore(trace): switch authority to TraceChaser") removed
`tools/bizhawk-headless/`, `tools/bizhawk/`, `tools/retro/` and `tools/traces/`
from OpenGGF and replaced them with the `tools/tracechaser` gitlink. Every
emulator-facing audio piece named in the task brief therefore lives in the
pinned TraceChaser checkout, at these paths:

| Brief path | Actual path at gitlink `9e51ff7` |
|---|---|
| `tools/bizhawk-headless/native/gpgx-audio-observer/` | `tools/tracechaser/bizhawk-headless/native/gpgx-audio-observer/` |
| `tools/bizhawk-headless/src/Audio/` | `tools/tracechaser/bizhawk-headless/src/Audio/` |
| Lua audio probes under `tools/bizhawk/` | `tools/tracechaser/bizhawk/probes/s1_*audio*.lua`, contracts under `tools/tracechaser/bizhawk/audio/` |

OpenGGF retains the Java side (`src/main/java/com/openggf/tools/audio/**`), the
shell orchestration (`tools/audio/*.sh`), and the single committed fixture pair
(`src/test/resources/audio/parity/s1/`). The submodule is not initialised in the
main checkout (`git submodule status` shows `-9e51ff7…`), so on a fresh worktree
every emulator-facing command fails at `tools/tracechaser-bootstrap.sh` until the
submodule is fetched.

## Inventory

### 1. GPGX native audio observer (`gpgx-audio-observer/`)

Three patches against BizHawk 2.11's Waterbox GPGX core, plus a reproducible
build/install toolchain (`fetch-source.sh`, `prepare-toolchain.sh`,
`build-core.sh`, `install-core.sh`, `reproduce-stock-pair.sh`) that runs under
`/usr/bin/bash -p` with every input hash-locked (`TRUST.md`). The managed side
is `REFLECTION` against the stock BizHawk assemblies; no patched managed DLL is
built.

**Patch 0001 `buffer-z80-audio-events` (ordinary observer, ABI v4, generic).**
Hooks `m68k_run`, `z80_run`, and wraps `fm_write`/PSG writes. It records, per
frame, a bounded ring of 32-byte events (capacity 65,536):

```
ordinal u32 | service_token u16 | parent_token u16 | pc u32 | subject u16 |
offset u16 | kind u8 | service_kind u8 | depth u8 | source_cpu u8 |
payload_length u8 | value u8 | flags u8 | reserved u8 | payload[8]
```

Event kinds are service begin/end/promote (driven by configured PC+opcode
hooks on either CPU), YM2612/PSG chip writes attributed to the owning service
token, Z80 RAM range snapshots, and reset. **There is no cycle stamp on any
ABI v1–v4 event**: `fm_write(cycles, address, data)` discards `cycles` before
calling `gpgx_audio_trace_fm_write(address, data)`. Ordering is native and
dense (both CPUs interleave in execution order), and the drain boundary is the
managed `BeginFrame`/`EndFrame` pair, so the finest time resolution this
observer offers is *frame + execution order*. The only register-level time
sample is the ABI v4 action-7 marker's A7 payload, which is a stack pointer,
not a clock.

Game-neutral by construction: the service kinds, hooks and snapshot ranges come
from `fixtures/gpgx-audio-service-manifests-v1.json` (S2 and S3K entries) and
`fixtures/s1-audio-service-manifest-v1.json`. Pinned identity family (all one
build): raw core `f57b7a94…`, compressed `e6531574…`, Build ID `cba4d8c88cf968a9`.

**Patch 0002 `s3k-audio-parity-events` (S3K-only, diagnostic).** Adds a second
buffer (capacity 32,768) of 38-byte events:

```
event_ordinal u32 | master_cycle u32 | vint_ordinal u32 |
service_entry_master_cycle u32 | transaction_id u32 | service_ordinal u16 |
generation u16 | track_base u16 | source_pointer u16 | source_pc u16 |
service_kind u8 | track_type u8 | channel_id u8 | bank u8 | chip u8 | port u8 |
register_id u8 | value u8
```

**This is the only cycle-stamped chip-write stream in the toolchain.** Every
YM/PSG write carries the master cycle, the V-int ordinal, the master cycle at
which the owning service was entered, and the Z80 track (`IX`) that issued it.
It is S3K-only because the configuration hard-codes the locked-on Z80 driver's
RAM geometry (song tracks `$1C40..$1DF0`, SFX tracks `$1DF0..$1F40`, `$30`-byte
tracks, bank byte at `$1C3E`, fixed SFX bank `$1F`) and the transaction
descriptors are Z80 begin/end PC+opcode pairs inside that driver. Lock
`s3k-parity-artifact-lock.json` says `publication: DIAGNOSTIC_S3K_PARITY_ONLY`,
`production_lock_eligible: false`. It is driven only from a C# test
(`GpgxS3kAudioParityManifestTests.CaptureInjectedFirstSlice`), which boots the
ROM at GHZ1 sync settings, runs 600 silent frames, and **injects a sound ID by
writing Z80 RAM `$1C0A` directly** — there is no BK2 in that path.

**Patch 0003 `s3k-chip-pcm-events` (S3K-only, diagnostic).** Adds a 28-byte
PCM tap stream (capacity 16,384): `event_ordinal u32 | sample_ordinal u32 |
master_cycle u64 | left i32 | right i32 | tap u8`, with three taps — YM2612
stereo after panning/mixing on the 1,008-master-cycle sample ordinal, the held
DAC code on the same ordinal, and PSG stereo on the 240-master-cycle native
clock. Lock `s3k-pcm-artifact-lock.json`, `DIAGNOSTIC_S3K_PCM_ONLY`. Same test
driver as 0002. The capacity bounds a capture to a few hundred milliseconds of
chip output, i.e. one SFX slice, not a level.

### 2. C# headless host (`bizhawk-headless/src/Audio/`)

Non-SDK C# 7 projects built by Mono `xbuild` with a hash-locked Roslyn
(`build.sh`). Audio-relevant classes:

| Class | Role |
|---|---|
| `GpgxAudioTraceNative` / `IGpgxAudioTraceApi` / `GpgxAudioTraceEvent` | P/Invoke surface for patch 0001 (configure, begin/end frame, drain, first fault) |
| `GpgxAudioServiceManifest` | loads the per-game service manifest JSON into the native config |
| `CompleteRunAudioObserver` (1,391 lines) | shared collector: reconstructs service ancestry from raw events, projects chip writes per service, handles the row-boundary "publication epoch", cutoff frontier |
| `S1CompleteRunAudioReferenceCapture` (3,132 lines) | S1 REV01 complete-run reference recorder; managed M68K callbacks correlated 1:1 with native markers; writes `audio_reference_raw.jsonl` (`openggf.s1-complete-run-audio-raw.v1`); reachable from `Program.cs` as `--trace-profile complete_run_audio_reference`; interval rows `[860, 225101)` of the all-emeralds movie |
| `S2AudioObserverProfile`, `S2CompleteAudioCaptureRunner`, `S2CompleteAudioRawSink` | S2 REV01 profile (publication from row 769) and bounded runner |
| `S3kAudioObserverProfile`, `S3kCompleteAudioCaptureRunner`, `S3kCompleteAudioRawSink` | locked-on S3K profile (publication from row 810) and bounded runner |
| `GpgxS3kAudioParityDepartures` | P/Invoke structs for patches 0002/0003 |

Granularity: one drained event batch per emulated frame; within a frame, native
execution order. `Program.cs` exposes **only the S1 audio reference capture** as
a CLI mode. The S2 and S3K runners are referenced from nothing but their tests
(`S2CompleteAudioCaptureRunnerTests`, `S3kCompleteAudioCaptureRunnerTests`,
`GpgxZ80AudioCapabilityTests`); there is no command line that records an S2 or
S3K reference capture.

### 3. Lua probes (`bizhawk/probes/`, contracts under `bizhawk/audio/`)

| Probe | Captures | Granularity |
|---|---|---|
| `s1_audio_driver_parity_probe.lua` (747 lines) | S1 REV01 sound-test GHZ music: at every `UpdateMusic` (`$71B4C`) invocation, the normalized driver RAM state (`$F000..`) for DAC/FM1-6/PSG1-3 plus every YM/PSG bus write attributed to that invocation, via `event.onmemorywrite` on `$A04000..$A04003`/`$C00011` (with a 20-site PC-manifest fallback verified against opcode bytes) | **one record per driver invocation** (ordinal), events in execution order, no cycle stamps; stops at proven recurrence |
| `s1_ghz1_gameplay_audio_timeline_probe.lua` (354 lines) | S1 GHZ1 gameplay: sound requests at `QueueSound`, admissions at `PlaySoundID`/`Sound_PlayBGM`/`Sound_PlaySFX`, owner arbitration, via `onmemoryexecute` at 20 pinned addresses | per request/admission, keyed by emulator frame |
| `s1_complete_run_audio_probe.lua` (211 lines) | source/shape observer for the S1 complete-run audio contract (M68K boundaries); the C# host is the production owner | per M68K boundary event |

All three are S1-only; no S2 or S3K Lua audio probe exists. The launcher
`bizhawk/run_bizhawk_lua.sh` now requires `OGGF_WORKDIR` (outside the
TraceChaser tree), `OGGF_INPUT_REPOSITORY_ROOT`, and an `OGGF_OUT` that
`traces/output_policy.py` accepts — i.e. **outside both the TraceChaser and the
OpenGGF checkout**.

### 4. Java parity tooling (`com.openggf.tools.audio.parity`)

| Class | Role |
|---|---|
| `AudioParitySchema` | `openggf.s1_audio_parity_reference.v1`; pins ROM SHA-1/CRC32, BK2 SHA-256/opaque hash/989 rows, roles DAC/FM1-6/PSG1-3, gating vs diagnostic field lists |
| `S1AudioFieldRegistry` | executable inventory of the S1 snapshot fields the contract considers |
| `S1AudioStateNormalizer` | `SmpsSequencerSnapshot` → versioned gating state |
| `S1OpenGgfAudioCapture` | headless engine host: loads the ROM song through the real `SmpsDriver`/`SmpsSequencer` (S1 config), advances one NTSC frame per tick, records `ChipWriteObserver` writes per tick, emits ticks up to the reference's `terminal_record_count` |
| `AudioParityComparator` | validation-first, no-realignment tick-by-tick comparison; exit 0 match / 3 mismatch / 4 invalid |
| `S1AudioParityTool` | CLI `validate | capture | compare`; **output must be under `<repo>/target/audio-parity`** |
| `AudioParityJsonl`, `AudioParityTick`, `AudioParityChipWrite`, `AudioParityTrackState` | interchange records |

Granularity: one tick per driver invocation (matches the Lua probe). S1 GHZ
music only; no SFX, no DAC sample timing, no PCM.

### 5. Complete-run profiles (`com.openggf.tools.audio.completerun`)

`CompleteRunAudioProducerRegistry` is a closed dispatcher naming three profiles
(`s1_rev01_complete_emeralds.v1`, `s2_rev01_complete_emeralds.v1`,
`s3k_locked_on_knuckles_superemeralds.v1`) and, for each, a
`*CompleteRunReferenceProducer` and `*CompleteRunOpenGgfProducer` class. **None
of those six producer classes exist in the tree.** Every profile's
`producerBindings()` returns `UnavailableProducerBinding`:

| Profile | REFERENCE | OPENGGF |
|---|---|---|
| S1 | "Task 2 reference producer is not installed" | "Task 5 OpenGGF producer is not installed" |
| S2 | "Task 2 S2 reference adapter is not installed" | "Task 5 S2 OpenGGF producer is not installed" |
| S3K | unavailable | unavailable |

What does exist: the schema/store/comparator (`CompleteRunAudioTrace`,
`CompleteRunAudioCaptureStore`, `CompleteRunAudioComparator`), the S1 state
normalizer, the S2/S3K strict raw-staging readers
(`S2CompleteRunReferenceRawAdapter`, `S3kCompleteRunReferenceRawAdapter`), the
S2/S3K state decoders/normalizers, and `S3kCompleteRunReferencePreflight`, which
by its own Javadoc "intentionally stops before translating native ABI events
into canonical requests, services, lifecycles, and cutoff coordinates". The
shell entry `tools/audio/run_complete_audio_parity.sh` runs
`CompleteRunAudioTool` from the fat JAR and will return `PRODUCER_UNAVAILABLE`
for every profile. The two opt-in decode gates
(`TestS2CompleteRunRealRow769DecodeGate`, `TestS3kCompleteRunRealRow810DecodeGate`)
prove a single boundary row can be decoded; they do not compare anything to the
engine.

### 6. Committed fixture (`src/test/resources/audio/parity/s1/`)

`s1-soundtest-ghz.bk2` (SHA-256 `622ff642…`, 989 input rows, `Genplus-gx`,
`Version 2.11`, opaque hash `09DADB50…`) and `normalization-contract-v1.json`
(vector: bus events + engine snapshot + raw ROM state → expected canonical
JSON). It is the only audio fixture; there is no S2 or S3K audio fixture and
no committed reference capture for any game.

### 7. Engine-side renderers and chip taps

| Tool | Captures |
|---|---|
| `FmSfxRenderTool` | one ROM SFX/song through the real driver: mix WAV, FM-only WAV, and `ym-writes.txt` (`frame port reg val`, frame = output frames rendered before the write; exact at `--rate internal`). Internally scheduled DAC `0x2A` writes are not observed. Games s1/s2/s3k |
| `PsgSfxRenderTool` | same for PSG: mix WAV, PSG-only WAV, `psg-writes.txt` (`frame byte`) |
| `ChipWriteObserver` | `onYm2612Write(port, register, value)` / `onPsgWrite(value)`; implemented by `Ym2612Chip`, `PsgChip`, `VirtualSynthesizer`, `SmpsDriver`, the two render tools and `S1OpenGgfAudioCapture` |

These are *engine* captures. Their time base is the output-frame counter, i.e.
the render sample position at which a write landed, not the driver's own
cycle or invocation clock. They are the inputs the 2026-08-29 chip clean-room
comparisons replayed into the pinned reference cores; they are not an oracle
for driver behaviour.

## What was run on this machine today

`$OPENGGF_MAIN_WORKSPACE` below is the main OpenGGF checkout; `s1.gen` there is the pinned S1 World REV01 image (SHA-1 `69e10285…`).

Environment: CachyOS, Mono 6.12.0, Roslyn `csc.exe` SHA-256 `81e98ade…`
(matches `build.sh`), Lua 5.4, Python 3, Maven 3.9.16 on JDK 21.0.11,
`DISPLAY=:0` (XWayland). Stock BizHawk 2.11 Linux at
`$OPENGGF_MAIN_WORKSPACE/docs/BizHawk-2.11-linux-x64` with
`EmuHawk.exe` `b2d4be5e…`, `gpgx.wbx.zst` `c4231296…`,
`BizHawk.Emulation.Cores.dll` `0144e6e2…` — byte-identical to the identities
recorded in the 2026-08-09 results. `bizhawk/preflight_bizhawk_2_11.sh
--bizhawk-home <that>` → `PASS`, 30 Lua capabilities verified.

Detailed logs: scratch `sdre/map-oracle/` (attempt logs, headless build/test
logs, captures). Nothing from it is committed.

### S1 GHZ reference capture — the documented path fails, the direct path works

1. `tools/audio/run_s1_audio_parity.sh --rom "<S1 REV01>" --bizhawk-home <stock>`
   → exit 4. `reference-1.log`:
   `run_bizhawk_lua.sh: line 81: OGGF_INPUT_REPOSITORY_ROOT: set OGGF_INPUT_REPOSITORY_ROOT to the explicit consumer checkout`.
   The OpenGGF wrapper only exports `OGGF_OUT` and `BIZHAWK_HOME`; the pinned
   launcher now demands the consumer root and workdir.
2. Same command with `OGGF_INPUT_REPOSITORY_ROOT=<worktree> OGGF_WORKDIR=<scratch>`
   → exit 4. `reference-1.log`:
   `output_policy.py: error: output root must remain outside both source trees`.
   The wrapper writes the reference into `target/audio-parity/s1-ghz/run.*`
   because `S1AudioParityTool.resolveSafeOutputRoot` **requires** output under
   `<repo>/target/audio-parity`, while TraceChaser's `output_policy.py`
   **forbids** any output under the consumer checkout. The two policies are
   mutually exclusive, so `run_s1_audio_parity.sh` cannot succeed at gitlink
   `9e51ff7` under any environment.
3. Direct launcher inside the agent sandbox (`--chromeless`, hardware GL):
   exit 0 in under a minute, **0-byte output**, only the non-fatal X11
   `BadMatch` diagnostics on stderr. Under `--chromeless` a Lua failure is
   swallowed into a silent no-output run, exactly as the harness README warns.
4. Direct launcher with `BIZHAWK_ALLOW_SLOW_LUA=1 OGGF_BIZHAWK_SOFTGL=1`:
   killed by the 590 s timeout, 0 bytes. The launcher's own comment says
   software GL "has not been observed to load a movie on this box".
5. Direct launcher **outside the sandbox**, `--chromeless`, hardware GL,
   `OGGF_AUDIO_CAPTURE_DEBUG=1`:

   ```bash
   DISPLAY=:0 OGGF_AUDIO_CAPTURE_DEBUG=1 \
   OGGF_OUT=/abs/scratch/reference-direct-5.jsonl \
   OGGF_INPUT_REPOSITORY_ROOT=/abs/worktree \
   OGGF_WORKDIR=/abs/scratch/workdir \
   BIZHAWK_HOME=$OPENGGF_MAIN_WORKSPACE/docs/BizHawk-2.11-linux-x64 \
   tools/tracechaser/bizhawk/run_bizhawk_lua.sh \
     tools/tracechaser/bizhawk/probes/s1_audio_driver_parity_probe.lua \
     src/test/resources/audio/parity/s1/s1-soundtest-ghz.bk2 \
     "$OPENGGF_MAIN_WORKSPACE/s1.gen"
   ```

   → exit 0 in 2 m 15 s, 127,834,759 bytes: **14,690 ticks** (ordinals
   0–14,689), `cycle_start 5473`, `period 4608`, `launch_update_music_invocations
   514`, callback proof `fm_port0_pairs 26143 / fm_port1_pairs 4363 /
   psg_writes 23530`, `source: memory_callback` — every figure identical to the
   2026-08-09 result. The `debug` records are the only difference from a
   production capture.
6. Same as 5 without the debug flag, to obtain a production-shape reference for
   the Java side — see "Engine comparison" below.

Conclusion: the S1 GHZ reference oracle **is producible today**, but only by
calling the TraceChaser launcher directly, from outside the agent sandbox, with
an output path outside both checkouts, and then copying the file into
`target/audio-parity/...` by hand for the Java tool. The committed wrapper is
broken by the extraction.

### Engine comparison

Step 6 (no debug flag) produced `reference-direct-6.jsonl`: 117,646,785 bytes,
SHA-256 `5941958c4eb38da4f71e1e5860b49b2d13d6fa0aaedcf244fa7b8d4ecb5d6efc` —
**byte-identical to the 2026-08-09 reference**. Copied by hand to
`target/audio-parity/s1-ghz/run.manual/reference-1.jsonl` (the Java tool
requires the reference to be a child of a run root under
`target/audio-parity`), then:

```bash
CP="target/classes:$(cat target/s1-audio-parity.classpath)"   # written by the wrapper's mvn step
java -cp "$CP" com.openggf.tools.audio.parity.S1AudioParityTool capture \
  --repo "$PWD" --run-root "$PWD/target/audio-parity/s1-ghz/run.manual" \
  --reference "$PWD/target/audio-parity/s1-ghz/run.manual/reference-1.jsonl" \
  --rom "$OPENGGF_MAIN_WORKSPACE/s1.gen" \
  --output "$PWD/target/audio-parity/s1-ghz/run.manual/openggf-1.jsonl"
java -cp "$CP" com.openggf.tools.audio.parity.S1AudioParityTool compare \
  --repo "$PWD" --run-root "$PWD/target/audio-parity/s1-ghz/run.manual" \
  --reference .../reference-1.jsonl --openggf .../openggf-1.jsonl \
  --human-report .../parity-report.txt --json-report .../parity-report.json
```

Engine capture: 14,690 ticks in about one second, 45,876,731 bytes, SHA-256
`06f2fda57779b6e1ec53078bc3040ff49135ff89ddb37bb325ef5d4f5e65187a` — identical
to the 2026-08-09 `openggf-29/30.jsonl`. Compare: **`S1 audio parity: MATCH
(14690 ticks)`**, exit 0. The engine on `feature/ai-sound-driver-re` at
`f087b8947` therefore still holds full invocation-level GHZ music parity; the
only thing that broke since 08-09 is the wrapper.

### Native headless harness

- `tools/tracechaser/bizhawk-headless/build.sh` (with `BIZHAWK_HOME` set) →
  exit 0; `bin/Release/BizHawk.Headless.Gpgx.exe` and `…Tests.exe` produced.
- `./test.sh --no-gates` → **558 passed, 57 failed, 35 skipped**, exit 1. All 57
  failures are path resolution: `EndToEndTests.RepositoryRoot` walks four
  levels up from `bin/Release` and then appends `tools/bizhawk-headless`, so
  from the submodule path it looks for
  `<worktree>/tools/tools/bizhawk-headless/...` and
  `<worktree>/tools/src/test/resources/...`. Every audio test is in that set:
  `S2AudioObserverProfileTests`, `S3kAudioObserverProfileTests`,
  `S2/S3kCompleteAudioCaptureRunnerTests`, `S2/S3kCompleteAudioRawSinkTests`,
  `GpgxZ80AudioCapabilityTests`, `GpgxAudioObserverBuildTests`,
  `GpgxAudioObserverSourceLockTests` (cannot find
  `fixtures/gpgx-audio-service-manifests-v1.json`, the S3K movie, or the
  observer lock files). The pinned TraceChaser test suite therefore cannot
  exercise any audio observer from the layout OpenGGF pins.
- Task 7 observer core: no installed observer tree exists on this machine. A
  raw `gpgx.wbx` / `gpgx.wbx.zst` pair whose SHA-256 equals the pinned
  `f57b7a94…` / `e6531574…` survives under
  `<scratch>/agent-tmp/tasks/s3k-collapse-dash-audio-build2-…/`, but without
  the `install-core.sh` output tree (`identity.json`, source bundle, evidence)
  the C# profile tests reject it ("Missing observer installation artifact:
  gpgx-audio-observer-source/identity.json"). Rebuilding needs
  `prepare-toolchain.sh --packages <locked SDK+114 NuGet packages>`; no such
  package directory was found outside quarantine, and the full
  `reproduce-stock-pair.sh` gate was not attempted.
- The S3K diagnostic cores for patches 0002/0003 (`DIAGNOSTIC_S3K_*_ONLY`) exist
  only as scratch task outputs from 2026-08-23/24 with no lock-matching install
  tree; they were not run.

### Documentation state after extraction

TraceChaser's `docs/` (`native-headless.md`, `capture-s1.md`, `lua-probes.md`,
…) contains no audio section; the only audio documentation in the pinned
checkout is `bizhawk-headless/README.md` §"S2/S3K native audio observer gates"
and the observer `README.md`/`TRUST.md`. The 2026-08-11 checkpoint's row
frontiers (S1 row 12525 `$72C24`, S2 row 769, S3K row 810) are observer
lifecycle evidence, not reference-vs-engine matches, and the S1/S2 cycle-stamped
YM write "diagnostic lab" cited by the 2026-08-22 audit used a diagnostic
patch/core (`aa36d6e7…`/`f34616f5…`) that is not one of the three committed
patches; its Java consumer `TestS1S2YmWriteTimingAudit` was removed by
`b4c8fbd8a` ("revert(audio): defer SMPS playback authenticity programme to
0.7") and is not in this tree.

## Runnable-today matrix

"Runnable today" means: produced real output on this machine during this
audit with the commands above, or was blocked by a specific, reproduced error.

| Piece | Captures | Granularity | Cycle stamps | Games | Runnable today | Blocker / note |
|---|---|---|---|---|---|---|
| Lua `s1_audio_driver_parity_probe` via direct launcher | driver RAM state + YM/PSG writes per `UpdateMusic` | per driver invocation | no | S1 (GHZ sound test only) | **yes** (outside sandbox, external `OGGF_OUT`) | 14,690 ticks in 2 m 15 s; identical proof counts to 2026-08-09 |
| `tools/audio/run_s1_audio_parity.sh` | orchestrates probe ×2 + engine ×2 + compare | per invocation | no | S1 | **no** | launcher needs `OGGF_INPUT_REPOSITORY_ROOT`; then `output_policy.py` rejects `target/` while `S1AudioParityTool` requires it |
| `S1AudioParityTool capture` / `compare` (Java) | engine ticks; tick-by-tick diff | per invocation | no | S1 | **yes** — `MATCH (14690 ticks)` | needs the reference copied by hand under `target/audio-parity` |
| Lua `s1_ghz1_gameplay_audio_timeline_probe` + `run_s1_ghz1_gameplay_audio_timeline.sh` | request/admission/owner timeline | per event, frame-keyed | no | S1 GHZ1 | not run; wrapper has the same launcher/policy conflict as above | 2026-08-09 result: first mismatch `ADMISSION_EXTRA` frame 958 |
| C# `--trace-profile complete_run_audio_reference` | S1 complete-run raw service/chip stream, rows `[860,225101)` | per frame, native order | no | S1 | build OK; not run (requires Task 7 observer install + `s1-audio-service-manifest-v1.json`) | last known frontier row 12525 `$72C24`, no published capture |
| C# `S2CompleteAudioCaptureRunner` / `S3kCompleteAudioCaptureRunner` | S2/S3K complete-run raw stream from row 769/810 | per frame, native order | no | S2, S3K | **no** — no CLI entry; tests fail on path resolution from the submodule | needs observer install + `OPENGGF_GPGX_Z80_CAPABILITY=1` + ROM/BK2 env |
| Patch 0001 ordinary observer | service lifecycle + attributed chip writes + RAM snapshots | per frame, native order | **no** | generic (manifests for S1/S2/S3K) | build not attempted; no install tree on disk | `prepare-toolchain.sh` package inputs absent |
| Patch 0002 S3K parity events | cycle-stamped YM/PSG writes bound to Z80 track transactions | per write, `master_cycle` u32 | **yes** | S3K only | **no** — diagnostic core not installed; driver test injects `$1C0A`, no BK2 | `DIAGNOSTIC_S3K_PARITY_ONLY`, `production_lock_eligible: false` |
| Patch 0003 S3K PCM taps | YM stereo / DAC / PSG stereo samples | per chip sample, `master_cycle` u64 | **yes** | S3K only | **no** — same as 0002; 16,384-event capacity ≈ one SFX slice | `DIAGNOSTIC_S3K_PCM_ONLY` |
| `CompleteRunAudioTool` + `run_complete_audio_parity.sh` | canonical complete-run capture comparison | per frame | no | S1/S2/S3K profiles | **no** | every producer binding is `UnavailableProducerBinding`; the six producer classes do not exist |
| `FmSfxRenderTool` / `PsgSfxRenderTool` | engine chip write logs + WAV | per output frame | no | S1/S2/S3K | yes (Java only; not an oracle) | engine-side only |
| Native headless `build.sh` | — | — | — | — | **yes** (exit 0) | Roslyn hash matches |
| Native headless `test.sh --no-gates` | — | — | — | — | **partial**: 558/57/35 | all failures are `tools/tools/...` path resolution |

## Blockers to a per-game oracle

Listed in the order they would have to fall.

1. **The pinned TraceChaser layout breaks both OpenGGF audio wrappers and its
   own audio tests.** `run_s1_audio_parity.sh` and
   `run_s1_ghz1_gameplay_audio_timeline.sh` pass neither
   `OGGF_INPUT_REPOSITORY_ROOT` nor `OGGF_WORKDIR`, and the Java tools' "output
   under `target/audio-parity`" rule contradicts `output_policy.py`. The C#
   suite resolves its repository root as if it still lived at
   `tools/bizhawk-headless`. One of the two sides has to move: either the Java
   tools accept an external run root, or TraceChaser's policy admits a
   consumer-owned `target/` directory, and the C# root derivation must become
   layout-independent (or take `TRACECHASER_TEST_FIXTURE_ROOT` everywhere).
2. **No installed Task 7 observer core.** Every native path beyond the plain
   Lua probe (S1 complete-run reference, S2/S3K runners, capability gates)
   needs `install-core.sh`'s output tree with the pinned identity. The build
   needs a locked package input directory (`toolchain-lock.json`,
   `managed-nuget-manifest.json`: SDK archive + 114 NuGet packages) that is not
   on this machine outside quarantine. The only lock-matching core bytes are
   loose scratch files without the install tree.
3. **No S2 or S3K reference capture path is reachable from a command line.**
   The S2/S3K runners exist only behind tests; `Program.cs` dispatches only the
   S1 audio reference profile. Reaching an S2/S3K capture means adding a CLI
   mode (TraceChaser change) and a reviewed observer install.
4. **No engine-side producer for any complete-run profile.** All six
   `*ReferenceProducer` / `*OpenGgfProducer` classes named by
   `CompleteRunAudioProducerRegistry` are absent, so even a captured reference
   has nothing to compare against beyond the row-769/810 decode gates.
5. **Cycle-stamped writes exist only for S3K and only as a diagnostic.** Patch
   0002 is the only source of `master_cycle` on chip writes; it is S3K-only by
   configuration, `production_lock_eligible: false`, and its only driver injects
   sound IDs into Z80 RAM rather than replaying a movie. S1 (68K driver) and S2
   (Z80 driver with a different RAM map) have no equivalent, and the reverted
   diagnostic lab that produced S1/S2 cycle data is not in this tree. For S1 and
   S2 the finest reference clock available today is "driver invocation +
   execution order".
6. **Scope of the one working oracle is narrow.** The S1 probe covers GHZ music
   in the sound test: no SFX, no DAC timing, no music/SFX contention, no PCM. The
   gameplay-timeline probe covers requests/admissions for one GHZ1 segment. A
   per-game oracle needs, per game, a BK2 that exercises music+SFX+DAC and a
   probe/manifest for that game's driver; only S1 has any of the three.
7. **Sandbox and display.** The launcher needs a reachable X server and, on this
   box, hardware GL; under the agent sandbox the movie-driven probe exits
   silently with no output. Any automation has to run outside the sandbox on
   `DISPLAY=:0` (or a verified Xvfb).
8. **Reference capture identity is not pinned anywhere runnable.** The
   2026-08-09 reference SHA-256 (`5941958c…`) lives only in prose; no committed
   capture, digest lock, or regenerable fixture exists for any game, so a
   future regression of the probe or launcher would not be detected by the
   suite.

## Open questions the disassembly does not settle here

- Whether a frame-granular (invocation-ordered) reference is sufficient for S1
  and S2 depends on whether any engine/driver divergence hides inside a single
  `UpdateMusic`/Z80 frame. The observer cannot answer that; only a cycle-stamped
  capture for those games could, and none exists.
- The patch 0002 `service_entry_master_cycle` semantics (measured at the first
  instruction after the service token changes) are an observer definition, not
  a hardware event; whether it corresponds to the driver's own notion of
  "service start" in `sonic3k.asm` was not verified in this audit.
