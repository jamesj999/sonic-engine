# Sonic 2 complete-run audio parity implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement S2 Z80 audio observation and make the natural Sonic-and-Tails all-emeralds run match REV01 byte-for-byte, including global SFX priority and 1-up restore behavior.

**Architecture:** A verified native GPGX Z80 observer produces the shared canonical trace. A Sonic 2-owned admission policy evaluates global request priority before sequencer insertion; the live driver retains source-accurate queue, temporary-music, DAC/FM6, and restore state.

**Tech Stack:** Java 21, JUnit Jupiter, C#/.NET BizHawk 2.11 headless host, Bash, shared complete-run infrastructure.

## Global Constraints

- Complete the shared infrastructure plan and its real Z80 callback proof first.
- Use S2 World REV01 only: SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`, CRC32 `7B905383`.
- Pin BK2 SHA-256 `e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5`, 259,590 rows, 35 segments, and seven special stages.
- Compare `[769,259590)`, including gaps and terminal tail.
- Model shipped `fixBugs=0`, including the fourth queue-transfer overwrite, stale priority restore, and missing PSG-noise restore.
- S2 has no S1 special-SFX class; do not introduce one.
- Native-vs-engine music IDs compare through validated ROM-backed content identity, never an ad hoc equality exception.
- Include every YM `$2A` DAC byte; final PCM remains outside the contract.
- Do not merge or push.

---

### Task 1: S2 fixture, identity resolver, and state profile

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunAudioProfile.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2NativeSoundResolver.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunStateNormalizer.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s2/TestS2CompleteRunAudioFixture.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s2/TestS2NativeSoundResolver.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s2/TestS2CompleteRunStateNormalizer.java`

**Interfaces:**
- Produces: profile id `s2_rev01_complete_emeralds.v1`, native content resolver, and strict Z80 state inventory.
- The fixed profile class statically registers that exact immutable profile so
  fresh production CLI JVMs can resolve it through the closed dispatcher.
- Reserves the closed Task 9 dispatcher profile. Tasks 2 and 5 supply its fixed
  reference and OpenGGF adapters only when their production capture owners exist.
- Consumes: ROM-backed S2 loader addresses and shared schema.

- [ ] **Step 1: Write failing fixture and resolver tests**

Assert ROM/BK2/manifest hashes, 259,590 rows, 35 segments, 34 transitions,
seven special stages, first row 769, last segment `[239443,245021)`, and end
259590. Table-test EHZ and Extra Life native IDs against current engine IDs and
require the same ROM asset key while retaining different diagnostic API IDs.

```java
assertEquals(resolver.fromNativeId(0x98).contentKey(),
        resolver.fromEngineMusic(Sonic2Music.EXTRA_LIFE.id).contentKey());
```

- [ ] **Step 2: Write failing raw-Z80 normalization vectors**

Cover `zAbsVar` global fields, three queues, QueueToPlay, priority, tempo,
pause/fade, DACUpdating/DACEnabled, 1upPlaying, ten music tracks, six SFX tracks,
saved music/global state, ring speaker, gloop and continuous spindash state.
Roles are `DAC,FM1,FM2,FM3,FM4,FM5,FM6,PSG1,PSG2,PSG3`.

- [ ] **Step 3: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.tools.audio.completerun.s2.TestS2CompleteRunAudioFixture,com.openggf.tools.audio.completerun.s2.TestS2NativeSoundResolver,com.openggf.tools.audio.completerun.s2.TestS2CompleteRunStateNormalizer' test
```

- [ ] **Step 4: Implement profile/resolver/normalizer and run GREEN**

Derive asset keys from `Sonic2SmpsLoader` ROM spans. Native pointers normalize
as asset key plus relative cursor; engine state uses the same coordinate.
Validate every field against the S2 inventory and suppress inactive stale bytes.
Run Step 3 until green.

- [ ] **Step 5: Commit the S2 profile**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/s2 \
        src/test/java/com/openggf/tools/audio/completerun/s2
git commit -m "feat(tools): define S2 complete-run audio profile"
```

### Task 2: Native S2 Z80 reference observer

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunReferenceProducer.java`
- Create: `tools/bizhawk-headless/src/Audio/S2AudioObserverProfile.cs`
- Create: `tools/bizhawk-headless/src/Recording/S2CompleteAudioCaptureRunner.cs`
- Create: `tools/bizhawk-headless/tests/S2AudioObserverProfileTests.cs`
- Create: `tools/bizhawk-headless/tests/S2CompleteAudioCaptureRunnerTests.cs`
- Modify: `tools/bizhawk-headless/src/Program.cs`

**Interfaces:**
- Produces: raw S2 staging records and callback proof for the shared Java publisher.
- Consumes: verified `Z80 RAM`/`Z80 BUS` host API and the pinned BK2.

- [ ] **Step 1: Write failing source-semantic observer tests**

Use synthetic Z80 RAM/services to prove three-slot source order, QueueToPlay
gate, lower reject/equal replace/jump transient priority, four-byte bridge
overwrite, ordinary music stopping SFX, ring alternation, gloop suppression,
spindash extension, 1-up save/block/restore, and 40-step fade.

- [ ] **Step 2: Run RED**

```bash
tools/bizhawk-headless/test.sh --filter 'S2AudioObserverProfileTests|S2CompleteAudioCaptureRunnerTests'
```

- [ ] **Step 3: Implement opcode-verified S2 observation**

Read only exact 8 KiB Z80 RAM offsets derived from
`docs/s2disasm/s2.sounddriver.asm`. Correlate 68K bridge queue writes to Z80
consumption and actual driver decision sites. Decode generic FM/PSG writes and
direct `$2A` writes in source order. Every PC hook validates pinned opcode and
operand bytes before row 769; mismatch aborts before output.

- [ ] **Step 4: Validate real callback coverage**

Run a short EHZ capture and assert positive Z80 service, FM port 0/1, PSG, and
DAC counts. Cross-check resolved register/value pairs at generic writer PCs.
Record selected callback source and proof counts in metadata; zero coverage or
mixed sources fail.

- [ ] **Step 5: Run two complete reference captures**

Use the exact 259,590-row BK2 and require byte-identical duplicate capture
directories. Strictly read the full output at `-Xmx32m`.

- [ ] **Step 6: Commit the S2 reference producer**

```bash
git add tools/bizhawk-headless/src/Audio \
        tools/bizhawk-headless/src/Recording/S2CompleteAudioCaptureRunner.cs \
        src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunReferenceProducer.java \
        tools/bizhawk-headless/tests/S2AudioObserverProfileTests.cs \
        tools/bizhawk-headless/tests/S2CompleteAudioCaptureRunnerTests.cs \
        tools/bizhawk-headless/src/Program.cs
git commit -m "feat(tools): observe complete S2 Z80 audio"
```

### Task 3: Whole-request global S2 priority policy

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/audio/Sonic2SfxAdmissionPolicy.java`
- Modify: `src/main/java/com/openggf/game/sonic2/audio/Sonic2AudioProfile.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Create: `src/test/java/com/openggf/game/sonic2/audio/TestSonic2SfxAdmissionPolicy.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestSfxContentionObserver.java`

**Interfaces:**
- Produces: an S2 implementation of the shared request-level `SmpsRequestAdmissionPolicy` before sequencer construction/insertion.
- Consumes: exact `Sonic2SmpsConstants.SFX_PRIORITY_TABLE`.

- [ ] **Step 1: Write failing global-priority tests**

Start an SFX holding one role while other roles are free. Assert a lower
priority challenger acquires no role and produces one rejected decision. Assert
equal priority replaces the request as a whole. Assert jump priority `$80` is
transient and does not latch as the new global priority.

```java
AdmissionResult result = policy.evaluate(context(0xA0, 0x70, 0x60));
assertFalse(result.accepted());
assertEquals(List.of(), driver.activeRolesFor(result.requestIdentity()));
```

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic2.audio.TestSonic2SfxAdmissionPolicy,com.openggf.audio.driver.TestSfxContentionObserver' test
```

- [ ] **Step 3: Implement policy before sequencer mutation**

S2 returns its global policy through `GameAudioProfile`.
`AbstractSmpsAudioBackend.playSfxSmps` evaluates before creating a
sequencer or touching continuous/DAC/lock state, emits the admission observer
decision, and returns on rejection. Per-role arbitration remains for accepted
requests and contains no S2 name check.

- [ ] **Step 4: Model queue and identity transforms**

Add S2-owned state for ring alternation, gloop suppression, and spindash
extension so resolved identity and priority are determined before policy
evaluation. Preserve request ordinal across a transformed ID.

- [ ] **Step 5: Run GREEN and S1/S3K regressions**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic2.audio.TestSonic2SfxAdmissionPolicy,com.openggf.audio.driver.TestSfxContentionObserver,com.openggf.game.sonic1.audio.TestSonic1SoundMailbox,com.openggf.game.sonic1.audio.TestSonic1ExtraLifePriority,com.openggf.audio.smps.TestSmpsFmVoiceWriteProfiles' test
```

- [ ] **Step 6: Commit the S2 admission policy**

```bash
git add src/main/java/com/openggf/game/sonic2/audio/Sonic2SfxAdmissionPolicy.java \
        src/main/java/com/openggf/game/sonic2/audio/Sonic2AudioProfile.java \
        src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
        src/main/java/com/openggf/audio/driver/SmpsDriver.java \
        src/test/java/com/openggf/game/sonic2/audio/TestSonic2SfxAdmissionPolicy.java \
        src/test/java/com/openggf/audio/driver/TestSfxContentionObserver.java
git commit -m "fix(audio): enforce S2 global SFX priority"
```

### Task 4: S2 queue-transfer and temporary-music state

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/audio/Sonic2SoundMailbox.java`
- Modify: `src/main/java/com/openggf/game/sonic2/audio/Sonic2AudioProfile.java`
- Modify: `src/main/java/com/openggf/audio/driver/TemporaryMusicState.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Create: `src/test/java/com/openggf/game/sonic2/audio/TestSonic2SoundMailbox.java`
- Create: `src/test/java/com/openggf/game/sonic2/audio/TestSonic2ExtraLifeRestore.java`

**Interfaces:**
- Produces: exact 68K queue transfer and S2-specific temporary-music payload on one chip.
- Consumes: shared live-driver temporary state introduced by the S1 plan.

- [ ] **Step 1: Write failing fourth-byte overwrite test**

Submit four SFX bridge bytes and assert the first three reach Queue0..2 while
the fourth overwrites the first voice-table byte exactly as shipped. The fixed
driver alternative must not execute.

- [ ] **Step 2: Write failing 1-up save/restore tests**

Start music plus six SFX roles with nonzero global priority and PSG noise.
Assert `$98` kills all SFX, saves priority before clearing live priority, blocks
new SFX, retains one chip, restores stale priority/DAC/music state, begins a
40-step fade, blocks SFX through that fade, and deliberately does not restore
PSG noise type under `fixBugs=0`.

- [ ] **Step 3: Run RED**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic2.audio.TestSonic2SoundMailbox,com.openggf.game.sonic2.audio.TestSonic2ExtraLifeRestore' test
```

- [ ] **Step 4: Implement S2-owned mailbox and saved payload**

Keep the bug-compatible overwrite inside `Sonic2SoundMailbox` with a comment
naming `fixBugs=0`. Extend `TemporaryMusicState` through a profile-supplied
payload for global priority, DAC flags/data, fade counters and noise behavior;
shared code contains no S2 condition.

- [ ] **Step 5: Run GREEN and snapshot tests**

```bash
mvn -Dmse=off -Dtest='com.openggf.game.sonic2.audio.TestSonic2SoundMailbox,com.openggf.game.sonic2.audio.TestSonic2ExtraLifeRestore,com.openggf.audio.driver.TestSmpsDriverSnapshot' test
```

- [ ] **Step 6: Commit queue and 1-up behavior**

```bash
git add src/main/java/com/openggf/game/sonic2/audio/Sonic2SoundMailbox.java \
        src/main/java/com/openggf/game/sonic2/audio/Sonic2AudioProfile.java \
        src/main/java/com/openggf/audio/driver/TemporaryMusicState.java \
        src/main/java/com/openggf/audio/driver/SmpsDriver.java \
        src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
        src/test/java/com/openggf/game/sonic2/audio
git commit -m "fix(audio): preserve S2 queue and extra-life state"
```

### Task 5: Natural S2 complete-run OpenGGF producer

**Files:**
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunOpenGgfCapture.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunOpenGgfProducer.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s2/TestS2CompleteRunOpenGgfCapture.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s2/TestS2CompleteRunPublication.java`
- Create: `tools/audio/run_s2_complete_audio_parity.sh`

**Interfaces:**
- Produces: natural S2 capture using only run/ROM/output/profile inputs.

- [ ] **Step 1: Write failing producer and authority tests**

Assert no reference input, exact frame/gap cadence, native content resolution,
global accepted/rejected decisions, one owner per role, multiple Z80 services,
and `$2A` writes attached to the right service/lifecycle.

- [ ] **Step 2: Run RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.audio.completerun.s2.TestS2CompleteRunOpenGgfCapture test
```

- [ ] **Step 3: Implement capture and run focused EHZ parity**

Use pre-construction request/admission/service/chip observers and Task 1's
normalizer. First run a bounded EHZ slice that includes FM/PSG SFX, ring
alternation, priority rejection/replacement, one DAC sample, and one 1-up.
Resolve every first mismatch before scaling to the full run.

- [ ] **Step 4: Commit the S2 producer**

```bash
git add src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunOpenGgfCapture.java \
        src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunOpenGgfProducer.java \
        src/test/java/com/openggf/tools/audio/completerun/s2 \
        tools/audio/run_s2_complete_audio_parity.sh
git commit -m "feat(tools): capture natural S2 complete-run audio"
```

### Task 6: Drive S2 complete-run parity to MATCH

**Files:**
- Modify only source owners proved by each first mismatch.
- Modify: `CHANGELOG.md`
- Create: `docs/architecture/research/audio/2026-08-10-s2-complete-run-audio-parity-result.md`
- Create: `docs/architecture/validation/audio/2026-08-10-s2-complete-run-audio-parity-validation.md`

**Interfaces:**
- Produces: deterministic reference/OpenGGF pairs and cross-producer `MATCH` for `[769,259590)`.

- [ ] **Step 1: Run the complete S2 command and preserve first evidence**

```bash
tools/audio/run_s2_complete_audio_parity.sh --rom "$S2_ROM" \
    --bizhawk-home docs/BizHawk-2.11-linux-x64
```

- [ ] **Step 2: Move the natural replay frontier source-first**

For each capture abort or mismatch, cite the exact S2 68K bridge/Z80 routine,
write RED, implement the shipped path at its owner, run GREEN, and restart from
row 769. Do not bypass red trace segments, inject events, or restart audio per
manifest segment.

- [ ] **Step 3: Assert real-run inventory before accepting MATCH**

Require all 258,821 rows, 35 segments, seven special stages and terminal tail;
whole-request lower rejection/equal replacement; queue overwrite; ordinary
music stopping SFX; 1-up save/block/40-step restore; ring/gloop/spindash
transforms; DAC/FM6 state; and exact `$2A` byte counts/order.

- [ ] **Step 4: Run focused and full JDK21 verification**

```bash
mvn -Dmse=off -Dsonic2.rom.path="$S2_ROM" \
  -Dtest='com.openggf.tools.audio.completerun.s2.TestS2CompleteRunAudioFixture,com.openggf.tools.audio.completerun.s2.TestS2NativeSoundResolver,com.openggf.tools.audio.completerun.s2.TestS2CompleteRunStateNormalizer,com.openggf.tools.audio.completerun.s2.TestS2CompleteRunOpenGgfCapture,com.openggf.tools.audio.completerun.TestCompleteRunAudioTrace,com.openggf.tools.audio.completerun.TestCompleteRunAudioCaptureStore,com.openggf.tools.audio.completerun.TestCompleteRunAudioComparator,com.openggf.game.sonic2.audio.TestSonic2SfxAdmissionPolicy,com.openggf.game.sonic2.audio.TestSonic2SoundMailbox,com.openggf.game.sonic2.audio.TestSonic2ExtraLifeRestore,com.openggf.audio.driver.TestSmpsDriverSnapshot,com.openggf.audio.driver.TestSfxContentionObserver,com.openggf.audio.smps.TestSmpsFmVoiceWriteProfiles' test
tools/bizhawk-headless/test.sh
mvn -Pci -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" \
  -Ds3k.rom.path="$S3K_ROM" test
```

Compare exact full-suite red IDs with baseline.

- [ ] **Step 5: Publish compact evidence and commit**

```bash
git add CHANGELOG.md docs/architecture/research/audio/2026-08-10-s2-complete-run-audio-parity-result.md \
        docs/architecture/validation/audio/2026-08-10-s2-complete-run-audio-parity-validation.md
git commit -m "fix(audio): match the complete S2 emerald audio run"
```
