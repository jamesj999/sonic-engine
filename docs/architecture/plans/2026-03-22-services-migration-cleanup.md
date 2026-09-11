# Services Migration Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the two-tier service architecture migration by fixing layering violations, expanding ObjectServices, and migrating remaining GameServices calls in object instance methods.

**Architecture:** ObjectServices is the injectable context-specific service handle; GameServices stays global-only. This cleanup adds zone feature methods to ObjectServices, makes boss SFX abstract, removes game-specific imports from the game-agnostic level/objects layer, and migrates ~60 remaining instance-method GameServices calls to services().

**Tech Stack:** Java 21, Maven

**Spec:** `docs/architecture/designs/2026-03-22-services-migration-cleanup-design.md`

---

### Task 1: Make AbstractBossInstance SFX methods abstract

Remove the `Sonic2Sfx` import from the game-agnostic boss base class by making `getBossHitSfxId()` and `getBossExplosionSfxId()` abstract. Add overrides to all concrete boss subclasses.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java:171-185`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/AbstractS1EggmanBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2EHZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2CPZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2HTZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2ARZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2CNZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MCZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MTZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2WFZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java` (already has getBossHitSfxId; add getBossExplosionSfxId)

- [ ] **Step 1: Make methods abstract in AbstractBossInstance**

In `AbstractBossInstance.java`:
- Remove `import com.openggf.game.sonic2.audio.Sonic2Sfx;`
- Replace the two method bodies with abstract declarations:

```java
// Line 171-177: replace with
protected abstract int getBossHitSfxId();

// Line 179-185: replace with
protected abstract int getBossExplosionSfxId();
```

- [ ] **Step 2: Add overrides to AbstractS1EggmanBossInstance**

This covers GHZ, MZ, SYZ, SLZ, LZ bosses (all extend this class). Add:

```java
import com.openggf.game.sonic1.audio.Sonic1Sfx;

@Override
protected int getBossHitSfxId() {
    return Sonic1Sfx.HIT_BOSS.id;
}

@Override
protected int getBossExplosionSfxId() {
    return Sonic1Sfx.BOSS_EXPLOSION.id;
}
```

- [ ] **Step 3: Add overrides to Sonic1FZBossInstance**

FZ boss extends AbstractBossInstance directly (not AbstractS1EggmanBossInstance). Add same two methods with `Sonic1Sfx` constants.

- [ ] **Step 4: Add overrides to all S2 boss classes**

Add to each of the 10 S2 boss classes:

```java
import com.openggf.game.sonic2.audio.Sonic2Sfx;

@Override
protected int getBossHitSfxId() {
    return Sonic2Sfx.BOSS_HIT.id;
}

@Override
protected int getBossExplosionSfxId() {
    return Sonic2Sfx.BOSS_EXPLOSION.id;
}
```

Files: `Sonic2EHZBossInstance`, `Sonic2CPZBossInstance`, `Sonic2HTZBossInstance`, `Sonic2ARZBossInstance`, `Sonic2CNZBossInstance`, `Sonic2MCZBossInstance`, `Sonic2MTZBossInstance`, `Sonic2WFZBossInstance`, `Sonic2MechaSonicInstance`, `Sonic2DeathEggRobotInstance`.

- [ ] **Step 5: Add overrides to S3K boss classes**

`AizMinibossInstance` — add:
```java
import com.openggf.game.sonic3k.audio.Sonic3kSfx;

@Override
protected int getBossHitSfxId() {
    return Sonic3kSfx.BOSS_HIT.id;
}

@Override
protected int getBossExplosionSfxId() {
    return Sonic3kSfx.EXPLODE.id;
}
```

`AizMinibossCutsceneInstance` — already has `getBossHitSfxId()` override. Add only:
```java
@Override
protected int getBossExplosionSfxId() {
    return Sonic3kSfx.EXPLODE.id;
}
```

- [ ] **Step 6: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (no compilation errors)

---

### Task 2: Remove EHZ boss convenience methods from ObjectRenderManager

Remove game-specific convenience methods and update S2 callers to use key-based lookup.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectRenderManager.java:232-238`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2EHZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/EHZBossGroundVehicle.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/EHZBossSpike.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/EHZBossWheel.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/EHZBossPropeller.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/EHZBossVehicleTop.java`

- [ ] **Step 1: Remove convenience methods from ObjectRenderManager**

In `ObjectRenderManager.java`:
- Remove `import com.openggf.game.sonic2.Sonic2ObjectArtKeys;`
- Remove the `getEHZBossRenderer()` method (lines 232-234)
- Remove the `getEHZBossSheet()` method (lines 236-238)

- [ ] **Step 2: Update EHZ boss callers**

In each of the 6 caller files, replace:
- `renderManager.getEHZBossRenderer()` with `renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS)`
- `renderManager.getEHZBossSheet()` with `renderManager.getSheet(Sonic2ObjectArtKeys.EHZ_BOSS)`

Add `import com.openggf.game.sonic2.Sonic2ObjectArtKeys;` to each file.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

### Task 3: Document DefaultPowerUpSpawner bridge role

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java:22-29`

- [ ] **Step 1: Update class javadoc**

Replace the existing class javadoc with:

```java
/**
 * Default implementation of {@link PowerUpSpawner} that creates concrete
 * power-up objects and registers them with the {@link ObjectManager}.
 * <p>
 * <b>Intentional bridge class:</b> This class imports game-specific types
 * (S1 splash, S3K elemental shields) from the game-agnostic layer. This is
 * an accepted layering violation because extracting a per-game shield factory
 * would add indirection with no practical benefit — the shield types are
 * stable and the spawner logic is shared across all games.
 */
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

### Task 4: Commit layering violation fixes

- [ ] **Step 1: Commit**

```bash
git add -A
git commit -m "refactor: fix layering violations in level/objects base classes

- AbstractBossInstance: make getBossHitSfxId()/getBossExplosionSfxId() abstract,
  remove Sonic2Sfx import. All 14 boss subclasses now provide their own SFX IDs.
- ObjectRenderManager: remove getEHZBossRenderer()/getEHZBossSheet() convenience
  methods, remove Sonic2ObjectArtKeys import. 6 EHZ boss callers use key-based lookup.
- DefaultPowerUpSpawner: document intentional bridge role.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Expand ObjectServices with zone feature methods

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectServices.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`

- [ ] **Step 1: Add methods to ObjectServices interface**

Add import and 3 new methods:

```java
import com.openggf.game.ZoneFeatureProvider;

// Under the "// Level state" section, after currentAct():
int featureZoneId();
int featureActId();
ZoneFeatureProvider zoneFeatureProvider();
```

- [ ] **Step 2: Add implementations to DefaultObjectServices**

Add import and 3 new method implementations:

```java
import com.openggf.game.ZoneFeatureProvider;

@Override
public int featureZoneId() {
    return LevelManager.getInstance().getFeatureZoneId();
}

@Override
public int featureActId() {
    return LevelManager.getInstance().getFeatureActId();
}

@Override
public ZoneFeatureProvider zoneFeatureProvider() {
    return LevelManager.getInstance().getZoneFeatureProvider();
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectServices.java src/main/java/com/openggf/level/objects/DefaultObjectServices.java
git commit -m "feat: expand ObjectServices with zone feature methods

Add featureZoneId(), featureActId(), zoneFeatureProvider() to ObjectServices
interface and DefaultObjectServices implementation.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Migrate remaining level/objects GameServices calls to services()

Migrate instance-method GameServices calls in the game-agnostic `level/objects` package.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/BreathingBubbleInstance.java`

**Exclusion notes:**
- `ExplosionObjectInstance` — only GameServices call is in the constructor (services() not injected)
- `HudRenderManager` — not an ObjectInstance subclass, stays on GameServices
- `SkidDustObjectInstance` / `SplashObjectInstance` — remaining calls are in static `create()` methods
- `SignpostSparkleObjectInstance` — only GameServices call is `getRingManager()` which is not in ObjectServices
- Calls in constructors stay on GameServices

- [ ] **Step 1: Migrate BreathingBubbleInstance**

Replace `GameServices.level()` calls in instance methods with `services()` equivalents. Use `services().currentLevel()`, `services().objectManager()`, `services().renderManager()`, `services().camera()` as appropriate. Remove `GameServices` import if no longer needed.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/objects/
git commit -m "refactor: migrate remaining level/objects GameServices calls to services()

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Migrate remaining S1 object GameServices calls to services()

Migrate instance-method GameServices calls in S1 objects where ObjectServices equivalents exist.

**Files to migrate (instance-method calls with services() equivalents):**
- `Sonic1BubblesObjectInstance.java` — `.getFeatureZoneId()` / `.getFeatureActId()` -> `services().featureZoneId()` / `services().featureActId()`
- `Sonic1FlappingDoorObjectInstance.java` — `.getZoneFeatureProvider()` -> `services().zoneFeatureProvider()`
- `Sonic1GlassBlockObjectInstance.java` — `.getObjectManager()` -> `services().objectManager()`
- `Sonic1LabyrinthBlockObjectInstance.java` — `.getFeatureZoneId()` / `.getFeatureActId()` -> `services().featureZoneId()` / `services().featureActId()`
- `Sonic1LamppostObjectInstance.java` — `.getFeatureZoneId()` / `.getFeatureActId()` / `.getZoneFeatureProvider()` -> `services().featureZoneId()` etc. (instance-method calls only, not constructor)
- `Sonic1PoleThatBreaksObjectInstance.java` — `.getZoneFeatureProvider()` -> `services().zoneFeatureProvider()`
- `Sonic1SpringObjectInstance.java` — `.getObjectRenderManager()` -> `services().renderManager()`
- `Sonic1BossBlockInstance.java` — `.getObjectManager()` -> `services().objectManager()`
- `Sonic1SplashObjectInstance.java` — `.getFeatureZoneId()` / `.getFeatureActId()` -> `services().featureZoneId()` / `services().featureActId()`
- `Sonic1WaterfallObjectInstance.java` — `.getFeatureZoneId()` / `.getFeatureActId()` -> `services().featureZoneId()` / `services().featureActId()`
- `Sonic1LavaBallObjectInstance.java` — `.getRomZoneId()` -> `services().romZoneId()` (instance method only, not constructor)
- `Sonic1GargoyleObjectInstance.java` — `.playSfx(...)` -> `services().playSfx(...)`
- `Sonic1MonitorObjectInstance.java` — audio call in instance method -> `services().playSfx(...)` (constructor calls stay)
- `Sonic1SignpostObjectInstance.java` — `.getCurrentAct()` / `.getObjectManager()` -> `services().currentAct()` / `services().objectManager()` (instance method only)

**Files to SKIP (out of scope per spec — level-transition methods):**
- `Sonic1EndingSTHObjectInstance.java` — `requestCreditsTransition()` — global operation
- `Sonic1EndingSonicObjectInstance.java` — `invalidateForegroundTilemap()` — global operation
- `Sonic1FZBossInstance.java` — `requestZoneAndAct()` — global operation
- `Sonic1ResultsScreenObjectInstance.java` — `advanceZoneActOnly()` / `advanceToNextLevel()` / `requestSpecialStageFromCheckpoint()` / `getCurrentZone()` — global operations and methods not in ObjectServices

- [ ] **Step 1: Migrate each file**

For each file listed above:
1. Replace `GameServices.level().getXxx()` with the `services().xxx()` equivalent
2. Replace `GameServices.audio().playSfx(...)` with `services().playSfx(...)`
3. Remove `GameServices` import if no remaining calls in the file
4. Keep `GameServices` import if constructor/static calls remain

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/objects/
git commit -m "refactor: migrate remaining S1 object GameServices calls to services()

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Migrate remaining S2 object GameServices calls to services()

Migrate instance-method GameServices calls in S2 objects where ObjectServices equivalents exist.

**Files to migrate:**
- `BubbleObjectInstance.java` — `.getFeatureZoneId()` / `.getFeatureActId()` -> `services().featureZoneId()` / `services().featureActId()`
- `BreakableBlockObjectInstance.java` — `.getCurrentLevel().getZoneIndex()` -> `services().currentLevel().getZoneIndex()`
- `SmashableGroundObjectInstance.java` — `.getObjectManager()` -> `services().objectManager()`
- `SpringObjectInstance.java` — `.getObjectRenderManager()` -> `services().renderManager()`
- `SpringboardObjectInstance.java` — `.getObjectRenderManager()` -> `services().renderManager()`
- `TippingFloorObjectInstance.java` — `.getObjectRenderManager()` -> `services().renderManager()`
- `SuperSonicStarsObjectInstance.java` — migrate calls that map to services() equivalents
- `SolBadnikInstance.java` — `.getObjectManager()` -> `services().objectManager()`
- `TurtloidBadnikInstance.java` — `.getObjectManager()` -> `services().objectManager()`
- `SignpostObjectInstance.java` — `.getCurrentAct()` -> `services().currentAct()`, `.getObjectManager()` -> `services().objectManager()` (instance method calls only; `getCurrentZone()` and `areAllRingsCollected()` are NOT in ObjectServices — leave those)

**Files to SKIP (out of scope):**
- `ARZPlatformObjectInstance.java` — `getCurrentZone()` not in ObjectServices
- `MTZLongPlatformObjectInstance.java` — `getCurrentZone()` not in ObjectServices
- `CheckpointStarInstance.java` — `requestSpecialStageEntry()` — global operation
- `EggPrisonObjectInstance.java` — `areAllRingsCollected()` not in ObjectServices
- `PointPokeyObjectInstance.java` — `findPatternOffset()` not in ObjectServices
- `ResultsScreenObjectInstance.java` — `advanceToNextLevel()` — global operation
- `RingPrizeObjectInstance.java` — `getRingManager()` not in ObjectServices
- `RivetObjectInstance.java` — `invalidateForegroundTilemap()` — global operation
- `SidewaysPformObjectInstance.java` — `getGame().getRom()` — ROM access
- `TornadoObjectInstance.java` — `requestZoneAndAct()` / `invalidateForegroundTilemap()` — global operations
- `Sonic2ARZBossInstance.java` — `getCurrentLevelMusicId()` — out of scope
- `Sonic2DeathEggRobotInstance.java` — `requestCreditsTransition()` — global operation

- [ ] **Step 1: Migrate each file**

For each file listed above:
1. Replace `GameServices.level().getXxx()` with the `services().xxx()` equivalent
2. Remove `GameServices` import if no remaining calls in the file
3. Keep `GameServices` import if constructor/static/out-of-scope calls remain
4. For `SignpostObjectInstance`: only migrate `getCurrentAct()` and `getObjectManager()` calls; leave `getCurrentZone()` and `areAllRingsCollected()` on GameServices

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/
git commit -m "refactor: migrate remaining S2 object GameServices calls to services()

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Migrate remaining S3K object GameServices calls to services()

Migrate instance-method GameServices calls in S3K objects where ObjectServices equivalents exist.

**Files to migrate:**
- `AizFallingLogObjectInstance.java` — only migratable call is `getCurrentAct()` in `getWaterLevel()` instance method -> `services().currentAct()`. Constructor call at line 89 stays. `getCurrentZone()` calls stay (not in ObjectServices).
- `AizIntroBoosterChild.java` — `GameServices.camera()` -> `services().camera()`
- `AizIntroPaletteCycler.java` — migrate calls that map to services() equivalents
- `AizLrzRockObjectInstance.java` — migrate calls that map to services() equivalents
- `AizMinibossBarrelShotChild.java` — `GameServices.audio().playSfx(...)` -> `services().playSfx(...)`

**Files to SKIP:**
- `AizIntroArtLoader.java` — all calls are in static methods
- `AizIntroTerrainSwap.java` — all calls are in static methods and/or are `GameServices.rom()` calls
- `AizMinibossCutsceneInstance.java` — `getBackend().restoreMusic()` not in ObjectServices

- [ ] **Step 1: Migrate each file**

For each file:
1. Replace `GameServices.level().getXxx()` with the `services().xxx()` equivalent
2. Replace `GameServices.camera()` with `services().camera()`
3. Replace `GameServices.audio().playSfx(...)` with `services().playSfx(...)`
4. Remove `GameServices` import if no remaining calls in the file

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/
git commit -m "refactor: migrate remaining S3K object GameServices calls to services()

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Final verification

- [ ] **Step 1: Run full test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Verify remaining GameServices calls are all justified**

Run: `grep -r "GameServices\." src/main/java/com/openggf/game/sonic1/objects/ src/main/java/com/openggf/game/sonic2/objects/ src/main/java/com/openggf/game/sonic3k/objects/ src/main/java/com/openggf/level/objects/ --include="*.java" -l | wc -l`

Expected: remaining files should only contain calls in constructors, static fields, factory lambdas, level-transition methods, ROM access, or methods not exposed on ObjectServices.

- [ ] **Step 3: Verify no game-specific imports in level/objects (except bridge)**

Run: `grep -r "import com.openggf.game.sonic" src/main/java/com/openggf/level/objects/ --include="*.java"`

Expected: only `DefaultPowerUpSpawner.java` should appear (documented bridge).
