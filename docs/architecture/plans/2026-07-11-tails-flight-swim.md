# Tails Flight, Swimming, and Carry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ROM-accurate S3K Tails flight, swimming, and main-character carry behavior, with availability replaced by the active native/donor character source.

**Architecture:** Typed rules decide whether manual flight exists. A per-sprite `TailsFlightController` owns S3K flight math/animation/audio while retaining phase/time in existing ROM fields; a `TailsCarryController` owns carry scalars and resolves the main player from `SpriteManager`. CPU/CNZ/MGZ decisions remain in `SidekickCpuController` and event code while delegating shared mechanics.

**Tech Stack:** Java 17, Maven, JUnit 5/Jupiter, Mockito, OpenGGF `GameRules`, playable controller stack, rewind schema, S2/S3K trace replay tests.

**Design:** `docs/architecture/designs/2026-07-11-tails-flight-swim-design.md`

---

## File Map

**Create:**

- `src/main/java/com/openggf/sprites/managers/TailsFlightController.java`
- `src/main/java/com/openggf/sprites/playable/TailsCarryController.java`
- `src/test/java/com/openggf/sprites/managers/TestTailsFlightController.java`
- `src/test/java/com/openggf/sprites/managers/TestPlayableSpriteMovementTailsFlight.java`
- `src/test/java/com/openggf/sprites/playable/TestTailsCarryController.java`
- `src/test/java/com/openggf/sprites/playable/TestSidekickCpuManualFlight.java`
- `src/test/java/com/openggf/game/sonic3k/constants/TestSonic3kTailsFlightAnimations.java`
- `src/test/java/com/openggf/game/sonic3k/audio/TestSonic3kAudioProfile.java`
- `src/test/java/com/openggf/sprites/managers/TestTailsTailsFlightSelection.java`

**Modify:**

- `src/main/java/com/openggf/game/rules/PlayerCapabilityRules.java`
- `src/main/java/com/openggf/game/rules/GameRules.java`
- `src/main/java/com/openggf/game/DonorCapabilities.java`
- `src/main/java/com/openggf/game/rules/CrossGameRuleComposer.java`
- `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- `src/main/java/com/openggf/game/CanonicalAnimation.java`
- `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kAnimationIds.java`
- `src/main/java/com/openggf/audio/GameSound.java`
- `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kAudioProfile.java`
- `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- `src/main/java/com/openggf/sprites/managers/TailsTailsController.java` only if `$20-$28` selection tests expose a gap
- `src/main/java/com/openggf/sprites/playable/PlayableSpriteController.java`
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`
- Tests: existing rule/donor, CPU carry/flight, CNZ/MGZ, art/translation, animation, and rewind suites named below.
- Documentation: `CHANGELOG.md`; `docs/status/trace-frontier-log.md` only when trace policy requires it.

## Task 1: Add source-owned flight capability

**Files:** rule/capability classes above; S1/S2/S3K `*GameModule.java`; `TestCrossGameRuleComposer`, `TestGameRulesConstants`, and `TestDonorCapabilities`.

- [ ] **Step 1: Write failing native and donor-replacement tests**

```java
assertFalse(GameRules.SONIC_1.playerCapability().tailsFlightEnabled());
assertFalse(GameRules.SONIC_2.playerCapability().tailsFlightEnabled());
assertTrue(GameRules.SONIC_3K.playerCapability().tailsFlightEnabled());
assertFalse(CrossGameRuleComposer.compose(GameRules.SONIC_3K, GameRules.SONIC_2, s2Caps)
        .playerCapability().tailsFlightEnabled());
assertTrue(CrossGameRuleComposer.compose(GameRules.SONIC_2, GameRules.SONIC_3K, s3kCaps)
        .playerCapability().tailsFlightEnabled());
```

- [ ] **Step 2: Verify red**

Run `mvn "-Dtest=TestCrossGameRuleComposer,TestGameRulesConstants,TestDonorCapabilities" test`.

Expected: missing `tailsFlightEnabled()` and `hasTailsFlight()` compilation errors.

- [ ] **Step 3: Implement typed values**

Add `boolean tailsFlightEnabled` after `instaShieldEnabled` in `PlayerCapabilityRules`, including value semantics. Add `boolean hasTailsFlight()` to `DonorCapabilities`. Set native and donor S1=false, S2=false, S3K=true. In `CrossGameRuleComposer`, pass `donorCapabilities.hasTailsFlight()`, not the host value. Update every constructor/stub reported by:

```powershell
rg -n "new PlayerCapabilityRules|implements DonorCapabilities|new DonorCapabilities" src/main/java src/test/java
```

- [ ] **Step 4: Verify green and commit**

Run Step 2; expected PASS. Commit `feat: gate Tails flight by character source` with all required trailers, using `Changelog: n/a: consolidated final entry deferred to Task 8` unless `CHANGELOG.md` is staged now.

## Task 2: Add and validate animation/audio/art contracts

**Files:** canonical/S3K animation and audio classes, `CrossGameFeatureProvider`, and the Task 2 tests in the File Map plus `TestCrossGameFeatureProviderRefactor` and `TestAnimationTranslator`.

- [ ] **Step 1: Write failing exact mapping tests**

Add canonical values `TAILS_FLY`, `TAILS_FLY_ASCEND`, `TAILS_FLY_CARRY`, `TAILS_FLY_CARRY_ASCEND`, `TAILS_FLY_TIRED`, `TAILS_SWIM`, `TAILS_SWIM_ASCEND`, `TAILS_SWIM_CARRY`, `TAILS_SWIM_TIRED`. Assert `Sonic3kAnimationIds.fromCanonical(...)` returns `$20-$28`. Assert S3K's sound map maps `GameSound.TAILS_FLYING` and `TAILS_FLY_TIRED` to `Sonic3kSfx.FLYING.id` and `FLY_TIRED.id`.

- [ ] **Step 2: Write failing provider and appendage tests**

In `TestCrossGameFeatureProviderRefactor`, create an S3K-like donor with `hasTailsFlight()==true` and one required canonical ID returning `-1`; assert `loadPlayerSpriteArt("tails")` throws `IllegalStateException` naming it. Add a complete donor success case. In `TestAnimationTranslator`, assert all nine S3K IDs survive translation into S1/S2 hosts. In `TestTailsTailsFlightSelection`, drive parent IDs `$20-$28`, call `update()`/`draw()` with a mocked renderer, and assert nonblank flight-tail frames. Add an S3K ROM-backed assertion that `$20-$28` scripts and DPLC/mapping frames resolve.

- [ ] **Step 3: Verify red**

Run:

```powershell
mvn "-Dtest=TestSonic3kTailsFlightAnimations,TestSonic3kAudioProfile,TestCrossGameFeatureProviderRefactor,TestAnimationTranslator,TestTailsTailsFlightSelection" "-Ds3k.rom.path=s3k.gen" test
```

- [ ] **Step 4: Implement exact contracts and validation**

Add nine S3K enum constants at `$20-$28` and map each in `toCanonical()`. Retain legacy `CanonicalAnimation.FLY`/`Sonic3kAnimationIds.FLY` for CPU recovery and `TAILS_CARRIED` for Sonic's carried pose; aliases share native IDs but serve distinct consumers. Add the two canonical sounds. In `CrossGameFeatureProvider.loadPlayerSpriteArt`, when `characterCode.equals("tails") && donorCapabilities.hasTailsFlight()`, require all nine IDs through `resolveNativeId`, throwing descriptively before returning art if any is `<0`. S2 donors do not require them.

- [ ] **Step 5: Verify green and commit**

Run Step 3; expected PASS. Commit `feat: define Tails flight art and audio contracts` with required trailers, using `Changelog: n/a: consolidated final entry deferred to Task 8` unless `CHANGELOG.md` is staged now.

## Task 3: Characterize existing CPU/CNZ/MGZ behavior

**Files:** `TestSidekickCpuControllerCarry`, `TestSidekickCpuControllerFlightAutoRecovery`, `TestSidekickCpuControllerCatchUpFlight`, `TestSonic3kCnzCarryTrigger`, `TestSonic3kMgz2EndBossEvents`, and `TestMgzEndBossHandoffHeadless`.

- [ ] **Step 1: Add passing characterization assertions**

At both existing `applyFlyingCarryVerticalVelocity()` phases, assert once-per-frame execution. Assert ready state 1 plus jump becomes 2 and adds `$08`; flap state 2 becomes 3 and subtracts `$20` without `$08`; odd frame decrements time; underwater carry blocks flap without clearing time. Record animation/audio before horizontal movement/collision/carry and next-frame Tails carry animation after a grab. For CNZ/MGZ, assert current carry/latch/cooldown/parentage and MGZ sequencing fields round-trip.

- [ ] **Step 2: Verify baseline green**

```powershell
mvn "-Dtest=TestSidekickCpuControllerCarry,TestSidekickCpuControllerFlightAutoRecovery,TestSidekickCpuControllerCatchUpFlight,TestSonic3kCnzCarryTrigger,TestSonic3kMgz2EndBossEvents,TestMgzEndBossHandoffHeadless" "-Ds3k.rom.path=s3k.gen" test
```

Expected: PASS before extraction. Isolate any actual known mismatch in a separately named expected-red/disabled test; do not weaken existing expectations.

- [ ] **Step 3: Commit characterization**

Commit `test: characterize existing Tails scripted flight` with required trailers.

## Task 4: Extract shared flight and delegate existing callers

**Files:** create `TailsFlightController` and its test; modify `PlayableSpriteController`, `AbstractPlayableSprite`, `SidekickCpuController`, and only the two characterized `PlayableSpriteMovement` call sites.

- [ ] **Step 1: Write failing isolated controller tests**

Test `$F0` activation, odd-frame decrement, ready press plus `$08`, flap `$20` without `$08`, pre-subtraction `<-$100`, `$1F->$20` reset, exhaustion, camera clamp, `$20-$28`, silent swimming, on-screen `((frame+8)&$F)==0` air SFX, and prior-frame carry animation.

```java
tails.setDoubleJumpFlag(2);
tails.setYSpeed((short) 0);
flight.updateVertical(false, false, romFrame);
assertEquals(3, tails.getDoubleJumpFlag());
assertEquals((short) -0x20, tails.getYSpeed());
```

- [ ] **Step 2: Verify red**

Run `mvn "-Dtest=TestTailsFlightController" test`; expected missing class.

- [ ] **Step 3: Implement focused controller**

```java
public final class TailsFlightController {
    public static final int FLIGHT_TIME = (8 * 60) / 2;
    private final AbstractPlayableSprite sprite;

    public TailsFlightController(AbstractPlayableSprite sprite) {
        this.sprite = Objects.requireNonNull(sprite, "sprite");
    }

    public boolean isActive();
    public void activate();
    public void updateVertical(boolean jumpPressed,
            boolean carryingMainCharacter, int romVisibleLevelFrameCounter);
    public void clear();
}
```

`updateVertical` performs timer, exclusive ready/flap math, camera clamp, then animation/audio—never horizontal movement, integration, collision, or carry. `activate` preserves locked-on behavior: add `oldYRadius-defaultYRadius` even under reverse gravity (`s3k.gen:$15182 = 44 40`). State remains in `doubleJumpFlag/property`. Own it in `PlayableSpriteController`; expose `AbstractPlayableSprite#getTailsFlightController()`.

- [ ] **Step 4: Delegate characterized callers exactly once**

Inside `SidekickCpuController.applyFlyingCarryVerticalVelocity()`, pass the controller's existing `inputJumpPress` field, existing `flyingCarryingFlag` field, and `romVisibleLevelFrameCounter()` method exactly:

```java
sidekick.getTailsFlightController().updateVertical(inputJumpPress, flyingCarryingFlag,
        romVisibleLevelFrameCounter());
```

at each existing call phase. Preserve animation/audio order. Remove helper math only after both call sites delegate. Task 6 later replaces `flyingCarryingFlag` with carry-controller state atomically.

- [ ] **Step 5: Verify and commit**

Run Task 4 tests and Task 3's command; expected PASS. Commit `refactor: extract shared Tails flight routine` with `Changelog: n/a: consolidated final entry deferred to Task 8` unless staging the changelog now.

## Task 5: Integrate manual activation

**Files:** `PlayableSpriteMovement`, `SidekickCpuController`, `TestPlayableSpriteMovementTailsFlight`, and `TestSidekickCpuManualFlight`.

- [ ] **Step 1: Write failing capability/input tests**

Test native S3K main true, native S2 false, S3K donor into S2 true, S2 donor into S3K false, Sonic false, P2-manual sidekick true, CPU sidekick outside manual window false; plus release/re-press, existing-flight rejection, rolling/radius restoration, and reverse-gravity delta.

- [ ] **Step 2: Verify red**

Run `mvn "-Dtest=TestPlayableSpriteMovementTailsFlight,TestSidekickCpuManualFlight" test`; expected no activation.

- [ ] **Step 3: Implement exact gate**

Add `SidekickCpuController#isUnderManualControl()` returning `controlCounter != 0`. Activate only when secondary ability is FLY, typed `tailsFlightEnabled` is true, and the sprite is main or a manually controlled CPU sidekick. Use the logical repress edge, call `activate()`, bypass normal air gravity, and execute flight vertical logic once before horizontal movement/bounds/integration/collision.

- [ ] **Step 4: TDD lifecycle cleanup**

Test and implement landing, reset, death, and object-control takeover clearing flight. Takeover is an engine integration contract, not claimed ROM evidence.

- [ ] **Step 5: Verify and commit**

Run Task 5 tests plus `TestSidekickCpuControllerCarry` and `TestS3kMgzF498AirRollPhysics`; expected PASS. Commit `feat: activate Tails flight from player input` with `Changelog: n/a: consolidated final entry deferred to Task 8` unless staging the changelog now.

## Task 6: Implement main-player carry and migrate scripts

**Files:** create `TailsCarryController` and its test; modify `SpriteManager`, `PlayableSpriteController`, `AbstractPlayableSprite`, `PlayableSpriteMovement`, `SidekickCpuController`; extend Task 3 CNZ/MGZ tests.

- [ ] **Step 1: TDD main-player query**

Add `SpriteManager#getMainPlayable()` returning the unique registered non-CPU playable or null. Test multiple CPU sidekicks, absent/changed camera focus, no main, and multiple non-CPU entries (throw descriptively). Carry uses `GameServices.sprites().getMainPlayable()` and rejects the carrier.

- [ ] **Step 2: TDD contact and attachment**

Test X `-$10..+$0F`, normal Y `$20..$2F` below, reverse Y `$21..$30` above; routine/control/debug/spindash/cooldown rejection; grab sound; control lock; carried animation; facing; gravity-relative center; clear motion/angle then seed X/Y velocity from Tails. Implement query/bounds/`tryGrabMainCharacter()` only and run `TestTailsCarryController` to green for this slice.

- [ ] **Step 3: TDD follow/collision/external release**

Test per-frame position, velocity latches, participant collision after attachment, invalid-state release, and external-velocity mismatch with `$3C` cooldown. Implement `updateAfterTailsCollision(mainHeldInput)` without moving animation order.

- [ ] **Step 4: TDD jump release**

Test neutral `$12` cooldown; directional `$3C`; `-$380` Y; `±$200` X; jumping/roll/roll-jump/radii/animation/control fields. Implement release/countdown.

- [ ] **Step 5: TDD lifecycle cleanup**

Test landing clears flight before grounded release; reset/death/takeover unlock and clear. Implement `clearAndReleaseMain()` at Task 5 lifecycle sites.

- [ ] **Step 6: TDD scripted context and migrate CNZ/MGZ**

```java
public enum CarryContext { NONE, MANUAL, CNZ, MGZ_BOSS }
public record Snapshot(short latchX, short latchY, boolean carrying,
        boolean parentagePending, int cooldown, CarryContext context) {}
private final AbstractPlayableSprite carrier;

public TailsCarryController(AbstractPlayableSprite carrier) {
    this.carrier = Objects.requireNonNull(carrier, "carrier");
}

public boolean isCarryingMainCharacter();
public boolean tryGrabMainCharacter();
public void updateAfterTailsCollision(int mainHeldInput);
public void forceScriptedCarry(CarryContext context); // internally resolves main
public void clearAndReleaseMain();
public Snapshot capture();
public void restore(Snapshot snapshot);
```

Move latch X/Y, carrying flag, `carryParentagePending`, and cooldown ownership from CPU. Replace Task 4's second flight argument with `sidekick.getTailsCarryController().isCarryingMainCharacter()`. Keep MGZ intro/flap/released-chase sequencing in CPU. Migrate CNZ/MGZ entry/exit to `forceScriptedCarry(CNZ/MGZ_BOSS)` and shared release. Context resets to NONE. CPU recovery ignores manual capability.

- [ ] **Step 7: Verify and commit**

```powershell
mvn "-Dtest=TestTailsCarryController,TestPlayableSpriteMovementTailsFlight,TestSidekickCpuControllerCarry,TestSonic3kCnzCarryTrigger,TestSonic3kMgz2EndBossEvents,TestMgzEndBossHandoffHeadless" "-Ds3k.rom.path=s3k.gen" test
```

Expected PASS. Commit `feat: add shared Tails carry controller` with `Changelog: n/a: consolidated final entry deferred to Task 8` unless staging the changelog now.

## Task 7: Atomically migrate carry rewind

**Files:** `PerObjectRewindSnapshot`, `AbstractPlayableSprite`, `SidekickCpuController`, `TestAbstractPlayableSpriteRewindCapture`, and `TestSidekickCpuControllerRewindCapture`.

- [ ] **Step 1: Write failing round trips**

Test manual/CNZ/MGZ contexts, parentage-pending, jump release, cooldown, missing main then later resolution, exhaustion, and swimming. Assert existing flight fields already restore and no participant reference exists.

- [ ] **Step 2: Verify red**

Run `mvn "-Dtest=TestAbstractPlayableSpriteRewindCapture,TestSidekickCpuControllerRewindCapture" test`; expected missing carry-controller restoration.

- [ ] **Step 3: Make carry controller sole owner atomically**

Add latch X/Y, carrying, parentage pending, cooldown, and `CarryContext` to `PlayerRewindExtra` and all constructors. Remove the five migrated fields from `SidekickCpuRewindExtra`, CPU capture/restore, and fixtures. Retain MGZ sequencing fields. Restore carry before CPU state because CPU consumes context. Missing main preserves pending state; no raw reference.

- [ ] **Step 4: Verify and commit**

```powershell
mvn "-Dtest=TestAbstractPlayableSpriteRewindCapture,TestSidekickCpuControllerRewindCapture,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard" test
```

Expected PASS/no baseline additions. Commit `feat: rewind shared Tails carry state` with `Changelog: n/a: consolidated final entry deferred to Task 8` unless staging the changelog now.

## Task 8: Verification, traces, and docs

- [ ] **Step 1: Run focused feature tests**

```powershell
mvn "-Dtest=TestCrossGameRuleComposer,TestGameRulesConstants,TestDonorCapabilities,TestSonic3kTailsFlightAnimations,TestSonic3kAudioProfile,TestCrossGameFeatureProviderRefactor,TestAnimationTranslator,TestTailsTailsFlightSelection,TestTailsFlightController,TestPlayableSpriteMovementTailsFlight,TestSidekickCpuManualFlight,TestTailsCarryController,TestSidekickCpuControllerCarry,TestAbstractPlayableSpriteRewindCapture,TestSidekickCpuControllerRewindCapture" "-Ds3k.rom.path=s3k.gen" test
```

- [ ] **Step 2: Run vertical-slice/script regressions**

```powershell
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestSonic3kCnzCarryTrigger,TestSonic3kMgz2EndBossEvents,TestMgzEndBossHandoffHeadless,TestS3kMgzF498AirRollPhysics" "-Ds3k.rom.path=s3k.gen" test
```

- [ ] **Step 3: Invoke `trace-replay-bug-fixing`, then run exact traces**

```powershell
mvn "-Dtest=TestS2Arz2LevelSelectTraceReplay,TestS2Mcz2LevelSelectTraceReplay,TestS2Mtz2LevelSelectTraceReplay,TestS3kCnzTraceReplay,TestS3kCnzCompleteRunTraceReplay,TestS3kMgzTraceReplay,TestS3kMgzCompleteRunTraceReplay" "-Ds2.rom.path=s2.gen" "-Ds3k.rom.path=s3k.gen" test
```

No prior green may regress; no trace hydration or carve-outs. Update `TRACE_FRONTIER_LOG.md` when policy requires.

- [ ] **Step 4: Run full verification/policy**

```powershell
mvn test
git diff --check
mvn validate
git status --short
```

- [ ] **Step 5: Update docs and commit**

Update/stage `CHANGELOG.md`. Stage `TRACE_FRONTIER_LOG.md` only if changed. Commit `docs: record Tails flight and swim support` with truthful trailers.

## Task 9: Independent completion review

- [ ] **Step 1: Invoke `superpowers:requesting-code-review`** and review capability direction, ROM math/order, all art/audio contracts, main-only carry, coordinate writes, lifecycle, rewind ownership, CPU/script ownership, trace invariants, docs, and policy.
- [ ] **Step 2: Correct each finding test-first** and repeat independent review until green.
- [ ] **Step 3: Invoke `superpowers:verification-before-completion`** and rerun fresh focused/regression/trace/full/policy commands.
- [ ] **Step 4: Present integration evidence for human review**; do not merge to `develop` without confirmation.
