# Sonic 3 & Knuckles complete-run audio parity implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement locked-on S3K Z80 audio tracing and make the natural Knuckles all-super-emeralds run match the shipped ROM byte-for-byte across requests, queue behavior, temporary music, tempo services, and every chip transaction.

**Architecture:** Reuse the verified GPGX Z80 boundary with an S3K-specific source profile. Model its two-slot request bridge, continuous SFX, one-chip 1-up state, frame-multiplied services, DPCM, and SEGA-PCM pauses through S3K-owned policies rather than S1/S2 priority assumptions.

**Tech Stack:** Java 21, JUnit Jupiter, C#/.NET BizHawk 2.11 headless host, Bash, shared complete-run infrastructure.

## Global Constraints

- Complete the shared infrastructure and real Z80 callback proof first.
- Use locked-on S3&K only: SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6`, CRC32 `63522553`.
- Pin BK2 SHA-256 `aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc`, 434,417 rows, 67 segments, and fourteen special stages.
- Compare `[810,434417)`, including bonus stages, bridge segments, gaps, and terminal tail.
- Use locked-on S&K-half addresses and shipped `fix_sndbugs=0`.
- Do not add an SFX priority table: S3K admission is two-slot/source-order based.
- Include DPCM and SEGA-PCM `$2A` writes; represent normal-driver pauses explicitly.
- The operator/write profile remains `S3K_Z80`; do not revisit the completed operator-order work.
- Do not merge or push.

---

### Task 1: Repair and pin the canonical S3K run fixture

**Files:**
- Add: `src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds/s3k-knuckles-complete-superemeralds.bk2`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kCompleteRunAudioFixture.java`

**Interfaces:**
- Produces: a self-contained run directory whose manifest-resolved BK2 is byte-identical to the canonical `_movies` source.

- [ ] **Step 1: Write the failing run-local movie test**

Assert `run.resolve(manifest.sourceBk2())` exists, hashes to the exact value
above, contains 434,417 input rows, and is byte-identical to
`src/test/resources/traces/s3k/_movies/s3k-knuckles-complete-superemeralds.bk2`.
Assert 67 monotonic segments, first `[810,2463)`, last `[412501,433942)`, and
exclusive movie end 434417.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.audio.completerun.s3k.TestS3kCompleteRunAudioFixture test
```

Expected: missing run-local BK2.

- [ ] **Step 3: Add the exact existing BK2 without rewriting it**

Use a normal filesystem copy of the tracked `_movies` BK2 into the manifest's
declared path, then verify `cmp` and SHA-256. This is an input movie, not a ROM
or an uncompressed physics/aux payload.

- [ ] **Step 4: Run GREEN and compression guards**

```bash
mvn -Dmse=off -Dtest='com.openggf.tools.audio.completerun.s3k.TestS3kCompleteRunAudioFixture,com.openggf.trace.TestTraceFixtureCompressionGuard,com.openggf.trace.TestTraceFixtureMovieAlignmentGuard' test
```

- [ ] **Step 5: Commit the fixture repair**

```bash
git add src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds \
        src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kCompleteRunAudioFixture.java
git commit -m "test(trace): complete the S3K super-emerald run fixture"
```

### Task 2: S3K state profile and native identity resolver

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunAudioProfile.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kNativeSoundResolver.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunStateNormalizer.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kNativeSoundResolver.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kCompleteRunStateNormalizer.java`

**Interfaces:**
- Produces: profile `s3k_locked_on_knuckles_superemeralds.v1`, content resolver, and strict Z80 state inventory.
- The fixed profile class statically registers that exact immutable profile so
  fresh production CLI JVMs can resolve it through the closed dispatcher.
- Reserves the closed Task 9 dispatcher profile. Tasks 3 and 6 supply its fixed
  reference and OpenGGF adapters only when their production capture owners exist.

- [ ] **Step 1: Write failing content/state vectors**

Resolve native music/SFX/commands to locked-on S&K-half ROM assets. Cover globals
at `$1C00`, queue0..2, tempo speedup, next/music/SFX IDs, nine music tracks,
SFX tracks/save overlap, bank/voice pointers, continuous SFX, spindash, DPCM
index, fade-to-previous, and saved tempo/speed state. Require roles
`DAC_FM6,FM1,FM2,FM3,FM4,FM5,PSG1,PSG2,PSG3`.

- [ ] **Step 2: Add a RED command-map check**

Assert the shipped Z80 command map `$E1` fade, `$E2` stop-all, `$E3` mute PSG,
`$E4` stop SFX, `$E5` duplicate fade. If `Sonic3kSmpsConstants` disagrees,
prove whether the constants affect runtime before changing them and add the
runtime call-site regression in the same task.

- [ ] **Step 3: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.tools.audio.completerun.s3k.TestS3kNativeSoundResolver,com.openggf.tools.audio.completerun.s3k.TestS3kCompleteRunStateNormalizer' test
```

- [ ] **Step 4: Implement profile/normalizer and run GREEN**

Normalize pointers as locked-on ROM asset key plus relative cursor. Encode the
overlapping 1-up/SFX RAM by semantic live/saved fields, never by duplicating
stale capacity. Preserve the shipped save-loop bug in expected vectors.

- [ ] **Step 5: Commit the S3K profile**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/s3k \
        src/test/java/com/openggf/tools/audio/completerun/s3k
git commit -m "feat(tools): define S3K complete-run audio profile"
```

### Task 3: Native S3K Z80 reference observer

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunReferenceProducer.java`
- Create: `tools/bizhawk-headless/src/Audio/S3kAudioObserverProfile.cs`
- Create: `tools/bizhawk-headless/src/Recording/S3kCompleteAudioCaptureRunner.cs`
- Create: `tools/bizhawk-headless/tests/S3kAudioObserverProfileTests.cs`
- Create: `tools/bizhawk-headless/tests/S3kCompleteAudioCaptureRunnerTests.cs`
- Modify: `tools/bizhawk-headless/src/Program.cs`

**Interfaces:**
- Produces: raw S3K staging records and positive Z80 callback proof.
- Consumes: shared `CompleteRunAudioObserver` and verified host domains.

- [ ] **Step 1: Write failing S3K semantic cases**

Cover first/second unique SFX slots, slot-0 duplicate ignore, third request
behavior, source-order contention, continuous SFX extension, spindash state,
1-up queue clear/save/suppress/restore, post-restore SFX eligibility,
speedup extra services, DPCM writes, and SEGA-PCM normal-service pause/resume.

- [ ] **Step 2: Run RED**

```bash
tools/bizhawk-headless/test.sh --filter 'S3kAudioObserverProfileTests|S3kCompleteAudioCaptureRunnerTests'
```

- [ ] **Step 3: Implement locked-on source hooks**

Use `docs/skdisasm/Sound/Z80 Sound Driver.asm` and locked-on
`docs/skdisasm/sonic3k.lst` addresses. Verify every opcode/operand before row
810. Observe `Play_Music` `$1358`, `Play_SFX` `$1380`, and
`Change_Music_Tempo` `$13C2` on the 68K side only for raw requests; derive
admissions/services from Z80 sites.

- [ ] **Step 4: Capture all chip paths**

Decode generic FMI/FMII and PSG writers plus direct DPCM and SEGA-PCM `$2A`
sites. When SEGA PCM disables normal services, emit explicit enter/leave
lifecycle records and attach every intervening `$2A` byte in order.

- [ ] **Step 5: Run real AIZ capability and duplicate full captures**

Require positive service/FM0/FM1/PSG/DPCM counts and a proven SEGA-PCM path.
Then capture the 434,417-row movie twice and require byte-identical directories
and a strict `-Xmx32m` read.

- [ ] **Step 6: Commit the S3K reference producer**

```bash
git add tools/bizhawk-headless/src/Audio/S3kAudioObserverProfile.cs \
        tools/bizhawk-headless/src/Recording/S3kCompleteAudioCaptureRunner.cs \
        src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunReferenceProducer.java \
        tools/bizhawk-headless/tests/S3kAudioObserverProfileTests.cs \
        tools/bizhawk-headless/tests/S3kCompleteAudioCaptureRunnerTests.cs \
        tools/bizhawk-headless/src/Program.cs
git commit -m "feat(tools): observe complete S3K Z80 audio"
```

### Task 4: S3K two-slot request admission and continuous SFX

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSoundMailbox.java`
- Create: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSfxAdmissionPolicy.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kAudioProfile.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Create: `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kSoundMailbox.java`
- Create: `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kContinuousSfxAdmission.java`

**Interfaces:**
- Produces: two-slot source-order admission without a priority table and identity-preserving continuous retrigger.

- [ ] **Step 1: Write failing two-slot tests**

In one 68K frame submit A, B, duplicate A, and C. Assert only the shipped two
slot outcomes, exact source order, duplicate rule, and stable request ordinals.
Assert no priority before/after field is emitted for S3K.

- [ ] **Step 2: Write failing continuous/retrigger tests**

Start a `$BC+` SFX, retrigger it, and assert one live source identity with the
extension counter/flag updated rather than a new sequencer. Cover spindash's
separate state and overlapping ordinary SFX contention.

- [ ] **Step 3: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic3k.audio.TestSonic3kSoundMailbox,com.openggf.game.sonic3k.audio.TestSonic3kContinuousSfxAdmission' test
```

- [ ] **Step 4: Implement S3K-owned mailbox/policy before mutation**

Select through `GameAudioProfile`; shared backend code uses only the typed
policy. Evaluate duplicate/slot/continuous outcomes before constructing a new
sequencer. Keep per-role contention for an accepted ordinary request.

- [ ] **Step 5: Run GREEN plus S1/S2 policy regressions**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic3k.audio.TestSonic3kSoundMailbox,com.openggf.game.sonic3k.audio.TestSonic3kContinuousSfxAdmission,com.openggf.game.sonic2.audio.TestSonic2SfxAdmissionPolicy,com.openggf.game.sonic1.audio.TestSonic1SoundMailbox' test
```

- [ ] **Step 6: Commit request behavior**

```bash
git add src/main/java/com/openggf/game/sonic3k/audio \
        src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
        src/main/java/com/openggf/audio/driver/SmpsDriver.java \
        src/test/java/com/openggf/game/sonic3k/audio
git commit -m "fix(audio): model S3K two-slot SFX admission"
```

### Task 5: S3K 1-up, tempo services, DPCM, and SEGA PCM

**Files:**
- Modify: `src/main/java/com/openggf/audio/driver/TemporaryMusicState.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/runtime/AudioFrameClock.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kAudioProfile.java`
- Create: `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kExtraLifeRestore.java`
- Create: `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kTempoServiceOrder.java`
- Create: `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kPcmDriverLifecycle.java`

**Interfaces:**
- Produces: S3K temporary payload, multi-service frame cadence, and explicit PCM lifecycle events on one chip.

- [ ] **Step 1: Write failing 1-up tests**

Assert input/internal queue clear, saved tracks/bank/voice/tempo/speed, normal
speed during jingle, request suppression except repeated 1-up, shipped
`fix_sndbugs=0` save-loop bit behavior, restore, and SFX eligibility on the first
driver cycle after restore rather than after the fade.

- [ ] **Step 2: Write failing tempo and PCM tests**

For speed value `$08`, assert the exact frame accumulator produces multiple
ordered music services without duplicating SFX services. Assert DPCM sample
bytes reach `$2A`. Enter SEGA PCM, assert normal services stop while direct
bytes continue, then resume with the next contiguous service ordinal.

- [ ] **Step 3: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic3k.audio.TestSonic3kExtraLifeRestore,com.openggf.game.sonic3k.audio.TestSonic3kTempoServiceOrder,com.openggf.game.sonic3k.audio.TestSonic3kPcmDriverLifecycle' test
```

- [ ] **Step 4: Implement profile payload and service scheduler**

Extend the existing live-driver temporary state with S3K's fields. Make
`AudioFrameClock` expose each actual driver service to the observer instead of
one folded frame callback. Route DAC/SEGA PCM through the existing chip write
observer at the resolved `$2A` boundary; do not synthesize expected bytes.

- [ ] **Step 5: Run GREEN and snapshot/chip tests**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic3k.audio.TestSonic3kExtraLifeRestore,com.openggf.game.sonic3k.audio.TestSonic3kTempoServiceOrder,com.openggf.game.sonic3k.audio.TestSonic3kPcmDriverLifecycle,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.audio.synth.TestChipWriteObserver' test
```

- [ ] **Step 6: Commit S3K service behavior**

```bash
git add src/main/java/com/openggf/audio/driver \
        src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
        src/main/java/com/openggf/audio/runtime/AudioFrameClock.java \
        src/main/java/com/openggf/game/sonic3k/audio/Sonic3kAudioProfile.java \
        src/test/java/com/openggf/game/sonic3k/audio
git commit -m "fix(audio): preserve S3K temporary music and PCM cadence"
```

### Task 6: Natural S3K OpenGGF producer

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunOpenGgfCapture.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunOpenGgfProducer.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kCompleteRunOpenGgfCapture.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kCompleteRunPublication.java`
- Create: `tools/audio/run_s3k_complete_audio_parity.sh`

**Interfaces:**
- Produces: natural S3K capture from run/ROM/output/profile inputs only.

- [ ] **Step 1: Write failing authority and reducer tests**

Assert no reference inputs; gap/bonus/bridge rows retained; two-slot decisions
ordered; same-ID/continuous identities stable; zero/multi-service frames legal;
1-up save/restore state visible; and DPCM/SEGA-PCM writes/lifecycle correctly
attached.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.audio.completerun.s3k.TestS3kCompleteRunOpenGgfCapture test
```

- [ ] **Step 3: Implement producer and focused AIZ capture**

Install all observers before bootstrap, use Task 2 normalization, and reject
orphan events/state. Run a focused AIZ interval containing ordinary contention,
continuous SFX, speedup, DPCM and one temporary-music lifecycle before the full
run.

- [ ] **Step 4: Commit producer/runner**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunOpenGgfCapture.java \
        src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunOpenGgfProducer.java \
        src/test/java/com/openggf/tools/audio/completerun/s3k \
        tools/audio/run_s3k_complete_audio_parity.sh
git commit -m "feat(tools): capture natural S3K complete-run audio"
```

### Task 7: Drive S3K complete-run parity to MATCH

**Files:**
- Modify only source owners proved by each mismatch.
- Modify: `CHANGELOG.md`
- Modify when a real discrepancy closes: `docs/status/s3k-known-discrepancies.md`
- Create: `docs/architecture/research/audio/2026-08-10-s3k-complete-run-audio-parity-result.md`
- Create: `docs/architecture/validation/audio/2026-08-10-s3k-complete-run-audio-parity-validation.md`

**Interfaces:**
- Produces: deterministic reference/OpenGGF pairs and cross-producer `MATCH` for `[810,434417)`.

- [ ] **Step 1: Run the complete S3K command**

```bash
tools/audio/run_s3k_complete_audio_parity.sh --rom "$S3K_ROM" \
    --bizhawk-home docs/BizHawk-2.11-linux-x64
```

- [ ] **Step 2: Resolve every natural replay/audio frontier source-first**

Cite locked-on `sonic3k.asm`/Z80 source and the exact `fix_sndbugs=0` branch,
write RED, implement the owning state, run GREEN, and restart at row 810. Never
substitute standalone S3 addresses, inject requests, skip transitions, or reset
audio per segment.

- [ ] **Step 3: Require complete real-run evidence**

Require all 433,607 rows, 67 segments, fourteen special stages, pachinko,
gumball and slots bonus stages, DEZ bridge segments, terminal tail, two-slot
unique/duplicate decisions, continuous retrigger, overlapping contention,
1-up suppression/restore and immediate post-restore SFX, speedup multi-service
frames, DPCM, SEGA-PCM pause/resume, and exact chip transaction counts/order.

- [ ] **Step 4: Run focused and full JDK21 verification**

```bash
mvn -Dmse=off -Ds3k.rom.path="$S3K_ROM" \
  -Dtest='com.openggf.tools.audio.completerun.s3k.TestS3kCompleteRunAudioFixture,com.openggf.tools.audio.completerun.s3k.TestS3kNativeSoundResolver,com.openggf.tools.audio.completerun.s3k.TestS3kCompleteRunStateNormalizer,com.openggf.tools.audio.completerun.s3k.TestS3kCompleteRunOpenGgfCapture,com.openggf.tools.audio.completerun.TestCompleteRunAudioTrace,com.openggf.tools.audio.completerun.TestCompleteRunAudioCaptureStore,com.openggf.tools.audio.completerun.TestCompleteRunAudioComparator,com.openggf.game.sonic3k.audio.TestSonic3kSoundMailbox,com.openggf.game.sonic3k.audio.TestSonic3kContinuousSfxAdmission,com.openggf.game.sonic3k.audio.TestSonic3kExtraLifeRestore,com.openggf.game.sonic3k.audio.TestSonic3kTempoServiceOrder,com.openggf.game.sonic3k.audio.TestSonic3kPcmDriverLifecycle,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.audio.smps.TestSmpsFmVoiceWriteProfiles,com.openggf.tests.trace.runs.TestCompleteRunAudioReplayCadence' test
tools/bizhawk-headless/test.sh
mvn -Pci -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" \
  -Ds3k.rom.path="$S3K_ROM" test
```

Compare exact red IDs with the pre-feature baseline and rerun core S3K release
slice tests named in `AGENTS.md`.

- [ ] **Step 5: Publish compact evidence and commit**

```bash
git add CHANGELOG.md docs/status/s3k-known-discrepancies.md \
        docs/architecture/research/audio/2026-08-10-s3k-complete-run-audio-parity-result.md \
        docs/architecture/validation/audio/2026-08-10-s3k-complete-run-audio-parity-validation.md
git commit -m "fix(audio): match the complete S3K super-emerald audio run"
```
