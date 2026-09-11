# Sonic 1 complete-run audio parity implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a deterministic, naturally replayed S1 all-emeralds audio capture that matches REV01 byte-for-byte across requests, priority decisions, driver state, and YM2612/PSG transactions, including exact 1-up takeover and restore.

**Architecture:** Extend the verified S1 68K observer to the complete gameplay epoch and adapt it to the shared chunk schema. Correct OpenGGF at the owning boundaries: a source-accurate request mailbox/service cadence and a single-chip temporary-music save/restore path.

**Tech Stack:** Java 21, JUnit Jupiter, Lua 5.1/BizHawk 2.11, GPGX, Bash, shared complete-run capture infrastructure.

## Global Constraints

- Complete the shared plan before this plan.
- Use S1 World REV01 only: SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`, CRC32 `AFE05EEE`.
- Pin the committed all-emeralds BK2 SHA-256 `f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b` and 225,101 rows.
- Compare `[860,225101)` and retain all transition and terminal-tail rows.
- Model `FixBugs=0`; do not add chip resets or reorder ports to hide differences.
- The real GHZ1 frames 3698–3910 are mandatory 1-up acceptance evidence.
- Preserve the focused GHZ sound-test and GHZ1 semantic commands as regressions.
- Do not merge or push; humans must listen first.

---

### Task 1: S1 complete-run fixture and state profile

**Files:**
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfile.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunAudioProfile.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunStateNormalizer.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCli.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s1/TestS1CompleteRunAudioFixture.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s1/TestS1CompleteRunStateNormalizer.java`

**Interfaces:**
- Produces: profile id `s1_rev01_complete_emeralds.v1` and strict S1 live/saved-state inventory.
- The fixed profile class statically registers that exact immutable profile so
  fresh production CLI JVMs can resolve it through the closed dispatcher.
- Reserves the closed Task 9 dispatcher profile. Tasks 2 and 5 supply its fixed
  reference and OpenGGF adapters only when their production capture owners exist.
- Producer bindings are typed `UNAVAILABLE` or immutable `PINNED`; zero hashes and
  synthetic placeholder identities are forbidden. Task 1 registers both bindings
  unavailable, and validation/publication rejects them before metadata comparison.
- Consumes: shared profile/model and existing `S1AudioStateNormalizer` field knowledge without importing GHZ recurrence constants.

- [ ] **Step 1: Write failing fixture identity tests**

Assert exact ROM hashes, BK2 hash/row count, manifest hash, 34 monotonic
segments, six special stages, epoch `[860,225101)`, 208,586 segment rows, and
15,655 gap/tail rows. Assert the last segment end is 214,158 and the terminal
tail is 10,943 rows.

- [ ] **Step 2: Write failing state vectors**

Build one active-music/SFX state and one 1-up saved-state vector. Require roles
`DAC,FM1,FM2,FM3,FM4,FM5,FM6,PSG1,PSG2,PSG3` in fixed order, pointer fields as
asset key plus relative cursor, and a fixed source-slot inventory: ten music,
six normal-SFX, and two special-SFX slots may coexist even when they target the
same hardware role. Derive effective hardware ownership separately; do not
collapse or reject shadowed source slots. The saved `$220` projection contains
exactly the ten music slots.

Retain every future-affecting global in live and saved state: priority, main
tempo/timeout, pause, explicit fade-out counter/delay, `v_sound_id`, three queue
slots, normalized music/special voice pointers, explicit fade-in flag/delay/
counter, `f_1up_playing`, tempo modifier, speed-up tempo/flag, ring-speaker and
push latches. Track state additionally retains rest, tempo divider, note-fill
timeout/master, full modulation cursor/wait/speed/delta/steps/value, and
PSG-noise/FM-feedback-algorithm storage alongside the existing sequence/voice/frequency fields;
canonical future state retains only the algorithm bits read by the driver, while chip snapshots
own the current feedback setting.
`f_updating_dac` and `f_voice_selector` may be omitted only with completed-
service invariant assertions; communication and unused padding are omitted.
Mutate each retained field once and assert canonical bytes change; mutations
of live or saved inactive capacity must not change canonical bytes.

- [ ] **Step 3: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.tools.audio.completerun.s1.TestS1CompleteRunAudioFixture,com.openggf.tools.audio.completerun.s1.TestS1CompleteRunStateNormalizer' test
```

- [ ] **Step 4: Implement profile and normalizer**

Register native S1 sound IDs without remapping. Normalize ROM pointers relative
to their validated ROM-backed song/SFX asset and normalize engine positions
from the same loader coordinates. Inactive tracks emit only role/hardware and
`active=false`; saved inactive capacity never leaks stale bytes.
Add fresh-JVM/dispatcher tests proving the registered profile resolves while both
producer bindings remain unavailable and publication leaves no output.

- [ ] **Step 5: Run GREEN and shared schema tests**

Run Step 3 plus `TestCompleteRunAudioTrace`. Expected: all pass.

- [ ] **Step 6: Commit the S1 profile**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfile.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java \
        src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java \
        src/main/java/com/openggf/tools/audio/completerun/s1 \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java \
        src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCli.java \
        src/test/java/com/openggf/tools/audio/completerun/s1
git commit -m "feat(tools): define S1 complete-run audio profile"
```

### Task 2: Complete-run S1 reference observer

**Files:**
- Modify: `src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunAudioProfile.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunReferenceProducer.java`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/src/Program.cs`
- Create: `tools/bizhawk-headless/src/Audio/S1CompleteRunAudioReferenceCapture.cs`
- Create: `tools/bizhawk-headless/tests/S1CompleteRunAudioReferenceCaptureTests.cs`
- Create: `tools/bizhawk-headless/fixtures/s1-audio-service-manifest-v1.json`
- Create: `tools/bizhawk/audio/s1_complete_run_audio_contract.lua`
- Create: `tools/bizhawk/audio/s1_complete_run_audio_contract_test.lua`
- Create: `tools/bizhawk/probes/s1_complete_run_audio_probe.lua`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s1/TestS1CompleteRunLuaContract.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s1/TestS1CompleteRunProbeContract.java`

**Interfaces:**
- Produces: a bounded typed raw stream from the fixed headless executable,
  then canonical Java store records for frames 860–225100.
- Consumes: verified `probe_runtime.lua` callbacks, the exact Task 7 buffered
  observer bridge for typed Z80 DAC services, and the shared Java publisher.
- Atomically replaces the S1 reference binding from `UNAVAILABLE` with immutable
  `PINNED`, using the actual fixed producer class/artifact hashes; the OpenGGF
  binding remains unavailable. The same evidence pass replaces Task 1's
  publication-inert baseline-owner/cutoff policy with the actual row-860
  baseline and row-225101 canonical cutoff literals. A fresh-JVM bootstrap
  test proves that exact state; no capture validates against invented bootstrap
  values.
- The fixed C# production mode owns one GPGX execution and combines reviewed
  M68K execute callbacks with the buffered native observer. Lua remains the
  pure source/contract proof; it is not a second emulator pass and cannot
  supply asynchronous Z80 DAC writes. Use a separate pinned S1 manifest so the
  reviewed S2/S3K Task 8 capability fixture and manifest hash do not roll.

- [ ] **Step 1: Write RED Lua lifecycle and priority cases**

The pure Lua harness must cover queue writes consumed on a later service,
duplicate IDs, deferred queue0, lower/equal priority, normal/special SFX,
stop-all, death/restart, act transitions, multiple services in one frame, zero
services, and 1-up save/block/restore. Encode the real 3698/3699/3702/3910
oracle as a contract fixture. Prove the shipped `FixBugs=0` queue trigger:
queue2 alone does not invoke `CycleSoundQueue`, while queue2 participates in
source order when queue0 or queue1 triggers the cycle.
Distinguish the blocked paths: normal SFX tests at `$721C6/$721CA` (and fade
tests `$721D6/$721DA`) converge at `$722C6` and clear global priority; special
SFX tests at `$7230C/$72310` (fade `$7231C/$72320`) return at `$723C6` without
clearing it. Prove both during `$88` and its 40-step restore fade.

```lua
local oneup = contract.newPriorityModel()
oneup:request(115, 0x88, "music")
local admitted = oneup:service()
assert(#admitted.decisions == 1 and admitted.decisions[1].accepted)
assert(oneup:request(116, 0xB5, "sfx").blocked_by == "one_up")
```

- [ ] **Step 2: Run RED**

```bash
lua tools/bizhawk/audio/s1_complete_run_audio_contract_test.lua
mvn -Dmse=off -Dtest='com.openggf.tools.audio.completerun.s1.TestS1CompleteRunLuaContract,com.openggf.tools.audio.completerun.s1.TestS1CompleteRunProbeContract' test
```

- [ ] **Step 3: Implement the source-derived contract**

Port the proven request correlation and YM decoder, but remove GHZ `$81`
arming, cycle convergence, one-tick-per-frame, and `[860,4975)` assumptions.
Explicitly model the abnormal-return sites already verified by the GHZ1 probe.
Every hook has exact REV01 opcode bytes and a source label. Give `$E1`
`PlaySegaSound` an explicit service outcome: prove it is outside the armed
epoch for the pinned movie, or emit its abnormal close rather than silently
abandoning an open service. Do not inherit the GHZ probe's hardcoded six-role
music ownership: hook the real `$72098` FM/DAC and `$72126` PSG track-load
loops and derive ownership from each song header. The complete movie includes
7-FM/DAC songs `$89/$93`, zero-PSG song `$92`, and 6-FM/DAC `$87/$88`.
The M68K `DACUpdateTrack` writes sample IDs to ZRAM; asynchronous Z80 `$2A`
data writes belong to typed native DAC services. Consume and validate those
Task 7 events directly, and remove the GHZ-only assumption that every bus
write occurs inside the M68K `UpdateMusic` callback. Model accepted-sample
setup from `$003A`: it owns the DAC-enable writes at `$0066/$006F` and tails
atomically into DPCM at `$0077` or SEGA PCM at `$00C1`; beginning only at the
format loop leaves the physical setup pair orphaned. Prove zero orphan,
opcode-mismatch, and overflow events across the complete interval.

- [ ] **Step 4: Implement the read-only full-run probe**

Configure and drain the native observer from power-on. Discard pre-epoch
publication while retaining its bounded service stack, pending descendants,
YM latches, and arm state. Use the fixed ABI-v2 prepublication flag so those
fully validated/drained frames do not consume continuation ages. Immediately
before row 860, at an empty drained READY boundary, invoke the one-shot native
publication transition; preserve tokens/stack/arm/latches, reset only carried
continuation ages and host publication coordinates/inventories, and reject a
duplicate or faulted/in-frame transition. Then emit the mandatory
baseline `BoundaryFrontier`; mark every crossing service explicitly
`CARRIED_IN_OPEN`, retain its true native begin proof only in the reference
sidecar, and restart published coordinates/chip inventories so the carried
service owns the first row-860 write and closes normally. Sample the normalized
baseline state at that boundary and close at row 225100. Record requests at queue writes, decisions at actual
dispatch, complete service state/writes at lifecycle close, and transition-gap
frames even when they contain no service. Reject unbracketed post-arm closes,
callback contamination, speed-up/fade lifecycle contradictions, missing rows,
and missing terminal.

The production C# runner emits a bounded, strictly typed raw stream; the fixed
Java producer validates it and writes `CompleteRunAudioCaptureStore`. Do not
teach C# to synthesize Java's canonical store or run Lua and native capture in
separate emulation passes.

- [ ] **Step 5: Run Lua and Java GREEN**

Run Step 2. Expected: pure Lua and all probe shape/opcode tests pass.

- [ ] **Step 6: Run two reference captures and byte gate**

Invoke the generic runner's reference producer twice with the pinned ROM/BK2.
Require identical capture manifests and chunks. Check the real 1-up frames with
the strict Java reader, not `jq` alone.

- [ ] **Step 7: Commit the S1 observer**

```bash
git add tools/bizhawk/audio/s1_complete_run_audio_contract.lua \
        tools/bizhawk/audio/s1_complete_run_audio_contract_test.lua \
        tools/bizhawk/probes/s1_complete_run_audio_probe.lua \
        tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj \
        tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj \
        tools/bizhawk-headless/src/Program.cs \
        tools/bizhawk-headless/src/Audio/S1CompleteRunAudioReferenceCapture.cs \
        tools/bizhawk-headless/tests/S1CompleteRunAudioReferenceCaptureTests.cs \
        tools/bizhawk-headless/fixtures/s1-audio-service-manifest-v1.json \
        src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunAudioProfile.java \
        src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunReferenceProducer.java \
        src/test/java/com/openggf/tools/audio/completerun/s1
git commit -m "feat(tools): observe complete S1 gameplay audio"
```

### Task 3: Source-accurate S1 request mailbox and service cadence

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/audio/Sonic1SoundMailbox.java`
- Modify: `src/main/java/com/openggf/game/sonic1/audio/Sonic1AudioProfile.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/audio/runtime/AudioFrameClock.java`
- Create: `src/test/java/com/openggf/game/sonic1/audio/TestSonic1SoundMailbox.java`
- Modify: `src/test/java/com/openggf/tools/audio/timeline/TestS1Ghz1OpenGgfAudioTimelineReduction.java`

**Interfaces:**
- Produces: a game-profile-owned three-slot mailbox whose requests are consumed at the S1 driver service boundary.
- Consumes: native IDs and existing high-level request observation.

- [ ] **Step 1: Write failing mailbox tests**

Cover source-order slots, deferred queue0, duplicate IDs retaining request
ordinal, StopAllSound queue clear, later service admission, and a frame-958
request admitted at 959. Assert the request observer still fires at submission
while the admission observer fires only at consumption.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic1.audio.TestSonic1SoundMailbox,com.openggf.tools.audio.timeline.TestS1Ghz1OpenGgfAudioTimelineReduction' test
```

- [ ] **Step 3: Implement the mailbox at the game-owned boundary**

`Sonic1SoundMailbox` stores immutable request tokens in three slots and exposes
only `submit` and `consumeService`. `GameAudioProfile` gains a typed optional
mailbox/service policy; shared `AudioManager` contains no S1 check. Do not use
BK2 frame numbers or zones.

- [ ] **Step 4: Run GREEN and the real GHZ1 timeline**

Run Step 2, then the pinned GHZ1 four-capture command. The first mismatch must
move beyond the old same-frame frame-958 admission frontier without changing
the raw request frame.

- [ ] **Step 5: Commit mailbox timing**

```bash
git add src/main/java/com/openggf/game/sonic1/audio/Sonic1SoundMailbox.java \
        src/main/java/com/openggf/game/sonic1/audio/Sonic1AudioProfile.java \
        src/main/java/com/openggf/audio/AudioManager.java \
        src/main/java/com/openggf/audio/runtime/AudioFrameClock.java \
        src/test/java/com/openggf/game/sonic1/audio/TestSonic1SoundMailbox.java \
        src/test/java/com/openggf/tools/audio/timeline/TestS1Ghz1OpenGgfAudioTimelineReduction.java
git commit -m "fix(audio): consume S1 requests at driver service boundaries"
```

### Task 4: Single-chip S1 extra-life save and restore

**Files:**
- Create: `src/main/java/com/openggf/audio/driver/TemporaryMusicState.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/game/sonic1/audio/Sonic1AudioProfile.java`
- Create: `src/test/java/com/openggf/audio/driver/TestS1TemporaryMusicState.java`
- Create: `src/test/java/com/openggf/game/sonic1/audio/TestSonic1ExtraLifePriority.java`

**Interfaces:**
- Produces: `SmpsDriver.saveTemporaryMusic(...)`, `startTemporaryMusic(...)`, and `restoreTemporaryMusic(...)` on the same synthesizer/chip instance.
- Replaces: S1's whole-driver/chip `MusicState` swap for `$88`; other profiles remain unchanged until their plans migrate them.

- [ ] **Step 1: Write failing one-chip identity test**

Start `$87` plus FM/PSG SFX, retain `driver.synthesizerForTesting()` identity,
start `$88`, request SFX while blocked, finish the jingle, and restore. Assert
the exact same driver and synth objects throughout, the six normal-SFX tracks
(FM3/FM4/FM5/PSG1/PSG2/PSG3) removed, the two special-SFX tracks preserved and
applied as FM4/PSG3 overrides to the jingle at `$72182/$7218E`, blocked normal and special-SFX requests
rejected with their distinct priority side effects (normal clears priority;
special preserves it), both during the jingle and 40-step fade, and
saved music tracks restored. Do not synthesize special override bits on the
restored `$87`: `$71FE6` cleared music override bits before the save, so later
driver updates and `cfStopSpecial` own the post-restore ordering.

- [ ] **Step 2: Write failing ordered-write regression**

Use `ChipWriteObserver` to assert the ROM sequence: SFX stop/key-off, jingle
voice/note writes on the same chip, restore active FM voices, PSG note-offs,
then 40-step fade. Pin the exact ordered writes/counts of the one ROM-authentic
`InitMusicPlayback` `FMSilenceAll`/`PSGSilenceAll` sequence on that existing
chip, and reject only an additional constructor/host reset or silence burst.
Assert there is no `refreshAllVoices()` approximation. Include the
`FixBugs=0` FM6/DAC behavior. Keep the mandatory real `$87 -> $88 -> $87`
frame-3698..3910 oracle for the DAC/fade omission, and add an independent
7-FM/DAC `$89` or `$93 -> $88 -> restore` vector: `$87` has no FM6 and cannot
prove the shipped omission of the bug-fixed `$2B=0` write at `$72B24`.

- [ ] **Step 3: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.audio.driver.TestS1TemporaryMusicState,com.openggf.game.sonic1.audio.TestSonic1ExtraLifePriority' test
```

- [ ] **Step 4: Implement temporary music inside the live driver**

Snapshot only the source-equivalent global/music-track state and ROM-backed
dependencies. Remove the six normal-SFX tracks while preserving the two
special-SFX tracks; apply their FM4/PSG3 overrides only to the jingle as the
ROM does, and do not synthesize them on restored music; retain one `VirtualSynthesizer`, install the
jingle music sequencer into that driver, and restore the saved music sequencer
state on the coord-flag boundary. Keep rewind snapshots deterministic and
capture the saved temporary state explicitly.

- [ ] **Step 5: Run GREEN and snapshot/observer regressions**

```bash
mvn -Dmse=off -Dtest='com.openggf.audio.driver.TestS1TemporaryMusicState,com.openggf.game.sonic1.audio.TestSonic1ExtraLifePriority,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.audio.synth.TestChipWriteObserver,com.openggf.tools.audio.timeline.TestS1Ghz1OpenGgfAudioTimelineReduction' test
```

- [ ] **Step 6: Commit the S1 temporary-music path**

```bash
git add src/main/java/com/openggf/audio/driver/TemporaryMusicState.java \
        src/main/java/com/openggf/audio/driver/SmpsDriver.java \
        src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
        src/main/java/com/openggf/game/sonic1/audio/Sonic1AudioProfile.java \
        src/test/java/com/openggf/audio/driver/TestS1TemporaryMusicState.java \
        src/test/java/com/openggf/game/sonic1/audio/TestSonic1ExtraLifePriority.java
git commit -m "fix(audio): preserve one chip across S1 extra-life music"
```

### Task 5: Natural S1 complete-run OpenGGF producer

**Files:**
- Modify: `src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunAudioProfile.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunOpenGgfCapture.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunOpenGgfProducer.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s1/TestS1CompleteRunOpenGgfCapture.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s1/TestS1CompleteRunPublication.java`
- Create: `tools/audio/run_s1_complete_audio_parity.sh`

**Interfaces:**
- Produces: validated S1 OpenGGF capture directory from only run path, ROM property, and output path.
- Consumes: natural complete-run replay, Task 1 normalizer, and pre-construction observers.
- Atomically replaces the S1 OpenGGF binding from `UNAVAILABLE` with immutable
  `PINNED`, using the actual fixed producer class/artifact hashes. Publication is
  enabled only after both closed bindings are pinned and fresh-JVM verified.

- [ ] **Step 1: Write failing producer isolation tests**

Assert constructor/method signatures cannot accept a reference path, reader,
expected event callback, or request sidecar. Use a compact synthetic run to
assert baseline, gap rows, zero/multi-service frames, terminal counts, and
observer removal after capture.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.audio.completerun.s1.TestS1CompleteRunOpenGgfCapture test
```

- [ ] **Step 3: Implement the capture reducer**

Correlate raw requests by stable ordinal, consume actual backend decisions,
open/close driver-service records from the service observer, append chip writes
to the currently open service, and normalize state at service end. Reject
orphan writes, decisions without requests, self-displacement, duplicate roles,
and writes outside service unless the S1 profile declares a lifecycle site.

- [ ] **Step 4: Run the focused real GHZ1 interval**

Capture through frame 4974 and assert the 3698–3910 oracle exactly before
attempting the whole run. Expected: no false SFX admission while `$88` is active
and all six roles restore to `$87`.

- [ ] **Step 5: Commit the S1 producer and runner**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunOpenGgfCapture.java \
        src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunOpenGgfProducer.java \
        src/main/java/com/openggf/tools/audio/completerun/s1/S1CompleteRunAudioProfile.java \
        src/test/java/com/openggf/tools/audio/completerun/s1 \
        tools/audio/run_s1_complete_audio_parity.sh
git commit -m "feat(tools): capture natural S1 complete-run audio"
```

### Task 6: Drive the complete S1 frontier to byte parity

**Files:**
- Modify only source owners identified by each first mismatch.
- Modify: `CHANGELOG.md`
- Create: `docs/architecture/research/audio/2026-08-10-s1-complete-run-audio-parity-result.md`
- Create: `docs/architecture/validation/audio/2026-08-10-s1-complete-run-audio-parity-validation.md`

**Interfaces:**
- Produces: two deterministic reference captures, two deterministic OpenGGF captures, and `MATCH` for `[860,225101)`.

- [ ] **Step 1: Run the complete four-capture command**

```bash
tools/audio/run_s1_complete_audio_parity.sh --rom "$S1_ROM" \
    --bizhawk-home docs/BizHawk-2.11-linux-x64
```

Expected initially: exit 3 with a typed first mismatch, or exit 4 at the first
natural run frontier. Preserve the fresh ignored run directory.

- [ ] **Step 2: Resolve each mismatch source-first**

For every frontier, cite the exact S1 disassembly routine and `FixBugs=0` path,
write a minimal failing unit/fixture regression, reproduce RED, implement at
the owning game/profile/driver boundary, run GREEN, then rerun from the start.
Never realign, skip a row/service, weaken state inventory, or exclude a write.

- [ ] **Step 3: Require explicit real-run evidence**

The final capture must contain all 224,241 rows, all 34 segments, six special
stages, every gap and the 10,943-row terminal tail. Assert at least one lower
priority rejection, equal replacement, music/SFX contention, SFX/SFX
contention, the frame-3698 1-up, blocked SFX, exact frame-3910 restore, speed
change, drowning/act-clear/emerald cues, DAC writes, ending, and credits.

- [ ] **Step 4: Run focused and full verification on JDK 21**

```bash
mvn -Dmse=off -Dsonic1.rom.path="$S1_ROM" \
  -Dtest='com.openggf.tools.audio.completerun.s1.TestS1CompleteRunAudioFixture,com.openggf.tools.audio.completerun.s1.TestS1CompleteRunStateNormalizer,com.openggf.tools.audio.completerun.s1.TestS1CompleteRunLuaContract,com.openggf.tools.audio.completerun.s1.TestS1CompleteRunProbeContract,com.openggf.tools.audio.completerun.s1.TestS1CompleteRunOpenGgfCapture,com.openggf.tools.audio.completerun.TestCompleteRunAudioTrace,com.openggf.tools.audio.completerun.TestCompleteRunAudioCaptureStore,com.openggf.tools.audio.completerun.TestCompleteRunAudioComparator,com.openggf.tools.audio.parity.TestAudioParityComparator,com.openggf.tools.audio.timeline.TestS1GameplayAudioTimelineComparator,com.openggf.game.sonic1.audio.TestSonic1SoundMailbox,com.openggf.game.sonic1.audio.TestSonic1ExtraLifePriority,com.openggf.audio.driver.TestS1TemporaryMusicState,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.audio.synth.TestChipWriteObserver,com.openggf.tests.trace.runs.TestS1CompleteEmeraldVisualRun' test
mvn -Pci -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" \
  -Ds3k.rom.path="$S3K_ROM" test
```

Compare the full-suite exact failure/error ID set with the recorded baseline;
no formerly green test may regress.

- [ ] **Step 5: Publish compact evidence and commit**

Record hashes, sizes/counts, interval, 1-up evidence, comparison result, test
commands, baseline comparison, and listening checklist without reconstructive
event payloads.

```bash
git add CHANGELOG.md docs/architecture/research/audio/2026-08-10-s1-complete-run-audio-parity-result.md \
        docs/architecture/validation/audio/2026-08-10-s1-complete-run-audio-parity-validation.md
git commit -m "fix(audio): match the complete S1 emerald audio run"
```
