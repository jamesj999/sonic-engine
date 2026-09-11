# Phase 4 Common Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract remaining cross-game duplication into shared utilities, base classes, and behavioral helpers across 5 dependency-ordered phases.

**Architecture:** Structural refactoring only — no behavioral changes. Each phase depends only on prior phases; items within a phase are independent. Tests must pass identically before and after each task.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, Maven

**Spec:** `docs/architecture/designs/2026-03-21-phase4-common-refactoring-design.md`

---

## File Structure

**New files (Phase 1):**
- None (move only: `SubpixelMotion.java` relocates from `game.sonic3k.objects` → `level.objects`)

**New files (Phase 2):**
- None (methods added to existing `AbstractObjectInstance.java`)

**New files (Phase 3):**
- `src/main/java/com/openggf/level/scroll/AbstractZoneScrollHandler.java`
- `src/main/java/com/openggf/game/AbstractZoneRegistry.java`
- `src/main/java/com/openggf/level/objects/AbstractObjectRegistry.java`
- `src/main/java/com/openggf/level/animation/AniPlcScriptState.java`
- `src/main/java/com/openggf/level/animation/AniPlcParser.java`
- `src/main/java/com/openggf/audio/AbstractAudioProfile.java`
- `src/main/java/com/openggf/audio/debug/AbstractSoundTestCatalog.java`

**New files (Phase 4):**
- `src/main/java/com/openggf/level/objects/AbstractProjectileInstance.java`
- `src/main/java/com/openggf/level/objects/AbstractSpikeObjectInstance.java`
- `src/main/java/com/openggf/level/objects/AbstractMonitorObjectInstance.java`
- `src/main/java/com/openggf/level/objects/AbstractPointsObjectInstance.java`
- `src/main/java/com/openggf/level/objects/PlatformBobHelper.java`
- `src/main/java/com/openggf/level/objects/GravityDebrisChild.java`

**Moved files:**
- `AbstractBadnikInstance.java`: `game.sonic2.objects.badniks` → `level.objects`

---

## Phase 1: Migrations to Existing Infrastructure

### Task 1: Promote SubpixelMotion to neutral package

**Files:**
- Move: `src/main/java/com/openggf/game/sonic3k/objects/SubpixelMotion.java` → `src/main/java/com/openggf/level/objects/SubpixelMotion.java`
- Modify: All files that use `SubpixelMotion` (including same-package users with no import)

- [ ] **Step 1: Identify ALL files that use SubpixelMotion**

Run: `grep -rn "SubpixelMotion" src/main/java --include="*.java" -l`

**Important:** Many S3K files are in the SAME package (`com.openggf.game.sonic3k.objects`) and have NO import statement. They will break after the move unless an import is added. Expected ~10 S3K files: `BreakableWallObjectInstance`, `FloatingPlatformObjectInstance`, `CorkFloorObjectInstance`, `LightningSparkObjectInstance`, `Sonic3kMonitorObjectInstance`, `AizPlaneIntroInstance`, `RockDebrisChild`, `AizRockFragmentChild`, `CutsceneKnucklesAiz1Instance`, `AizEmeraldScatterInstance`.

- [ ] **Step 2: Move the file**

Move `src/main/java/com/openggf/game/sonic3k/objects/SubpixelMotion.java` to `src/main/java/com/openggf/level/objects/SubpixelMotion.java`. Change the package declaration from `package com.openggf.game.sonic3k.objects;` to `package com.openggf.level.objects;`.

- [ ] **Step 3: Update all imports and add missing imports**

- For files that had `import com.openggf.game.sonic3k.objects.SubpixelMotion;`: replace with `import com.openggf.level.objects.SubpixelMotion;`
- For same-package S3K files that had NO import: ADD `import com.openggf.level.objects.SubpixelMotion;`

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: move SubpixelMotion to level.objects package"
```

### Task 2: Migrate S1/S2 objects to SubpixelMotion

**Files:**
- Modify: ~33 S1/S2 object files that inline the 8-line 16:8 fixed-point math

This is a large mechanical migration. The pattern to find and replace is:

```java
// FIND this 8-line block (with variable names varying):
int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
xPos24 += xVelocity;
yPos24 += yVelocity;
currentX = xPos24 >> 8;
currentY = yPos24 >> 8;
xSubpixel = xPos24 & 0xFF;
ySubpixel = yPos24 & 0xFF;
```

Replace with: a `SubpixelMotion.State` field initialized in the constructor, and a call to `SubpixelMotion.moveSprite2(motionState)` (no gravity) or `SubpixelMotion.moveSprite(motionState, gravity)` (with gravity). After the call, sync local `currentX`/`currentY` from `motionState.x`/`motionState.y`.

- [ ] **Step 1: Find all files with the inline pattern**

Search for the characteristic pattern. The key indicator is the 8-shift + OR merge on adjacent lines:

Run: `grep -rn "xSubpixel & 0xFF\|xSub & 0xFF\|xFraction & 0xFF" src/main/java/com/openggf/game/sonic1 src/main/java/com/openggf/game/sonic2 --include="*.java" -l`

Also check for the unsigned mask variant:
Run: `grep -rn "<< 8) |.*& 0xFF" src/main/java/com/openggf/game/sonic1 src/main/java/com/openggf/game/sonic2 --include="*.java" -l`

- [ ] **Step 2: Migrate each file**

For each file:
1. Add `import com.openggf.level.objects.SubpixelMotion;`
2. Replace the `xSubpixel`/`ySubpixel` fields with a `SubpixelMotion.State motionState` field
3. Initialize in constructor: `motionState = new SubpixelMotion.State(currentX, currentY, 0, 0, xVelocity, yVelocity);`
4. Before calling motion: sync velocities into `motionState.xVel`/`motionState.yVel` if they change dynamically
5. Replace the 8-line block with `SubpixelMotion.moveSprite2(motionState);` (or `moveSprite` for gravity)
6. After motion call: `currentX = motionState.x; currentY = motionState.y;`
7. Remove the now-unused `xSubpixel`/`ySubpixel` fields

**Important:** Some files use only X motion (no Y). For these, use `SubpixelMotion.moveX(motionState)`. Some apply gravity separately before the motion block — use `SubpixelMotion.moveSprite(motionState, gravity)` for those.

- [ ] **Step 3: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor: migrate S1/S2 objects to SubpixelMotion utility"
```

### Task 3: Migrate older objects to inherited getRenderer()

**Files:**
- Modify: S1/S2 object files whose `appendRenderCommands()` uses the verbose 5-line pattern

The verbose pattern to replace:
```java
ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
if (renderManager == null) { return; }
PatternSpriteRenderer renderer = renderManager.getRenderer(SOME_KEY);
if (renderer == null || !renderer.isReady()) { return; }
```

Replace with the inherited helper already on `AbstractObjectInstance`:
```java
PatternSpriteRenderer renderer = getRenderer(SOME_KEY);
if (renderer == null) { return; }
```

- [ ] **Step 1: Find all candidate files**

Run: `grep -rn "LevelManager.getInstance().getObjectRenderManager()" src/main/java/com/openggf/game/sonic1/objects src/main/java/com/openggf/game/sonic2/objects --include="*.java" -l`

Filter to files where the usage is the simple single-renderer pattern in `appendRenderCommands()`.

- [ ] **Step 2: Migrate each file**

For each file: replace the verbose 4-5 line block with the 2-line `getRenderer()` call. Remove the now-unused `import com.openggf.level.objects.ObjectRenderManager;` and `import com.openggf.level.LevelManager;` if no other usages remain.

- [ ] **Step 3: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor: migrate S1/S2 objects to inherited getRenderer() helper"
```

### Task 4: Relocate AbstractBadnikInstance to neutral package

**Files:**
- Move: `src/main/java/com/openggf/game/sonic2/objects/badniks/AbstractBadnikInstance.java` → `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java`
- Modify: All files that import the old location (~30 S1/S2 badnik files)

- [ ] **Step 1: Move the file and update package**

Move the file. Change package declaration from `package com.openggf.game.sonic2.objects.badniks;` to `package com.openggf.level.objects;`.

- [ ] **Step 2: Remove S2-specific hardcoded dependency**

The class currently has:
```java
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.objects.PointsObjectInstance;

private static final DestructionConfig S2_DESTRUCTION_CONFIG = new DestructionConfig(
    Sonic2Sfx.EXPLOSION.id, true, false,
    (spawn, lm, pts) -> new PointsObjectInstance(spawn, lm, pts));
```

Change this to accept `DestructionConfig` via constructor parameter with S2 config as default:

```java
private final DestructionConfig destructionConfig;

protected AbstractBadnikInstance(ObjectSpawn spawn, LevelManager levelManager,
        String name, DestructionConfig destructionConfig) {
    super(spawn, name);
    this.levelManager = levelManager;
    this.destructionConfig = destructionConfig;
    // ... existing field init
}

// Convenience constructor preserving existing API for S2 callers:
protected AbstractBadnikInstance(ObjectSpawn spawn, LevelManager levelManager, String name) {
    this(spawn, levelManager, name, null);
}

@Override
protected DestructionConfig getDestructionConfig() {
    return destructionConfig;  // Callers must supply; null = caller overrides getDestructionConfig()
}
```

Remove `import com.openggf.game.sonic2.audio.Sonic2Sfx;` and `import com.openggf.game.sonic2.objects.PointsObjectInstance;`. Move the S2 default config to `Sonic2ObjectRegistry` or each S2 badnik factory.

- [ ] **Step 3: Fix field shadowing**

Remove `protected boolean destroyed;` (line 41 in current file). The parent `AbstractObjectInstance` already has `isDestroyed()`/`setDestroyed()`. Update `destroyBadnik()` to use only `setDestroyed(true)`. Update `update()` to check `isDestroyed()` instead of the local `destroyed` field.

- [ ] **Step 4: Update all imports**

Find ALL files that reference `AbstractBadnikInstance` (not just explicit imports — same-package S2 badniks have no import):

Run: `grep -rn "extends AbstractBadnikInstance" src/main/java --include="*.java" -l`
Also: `grep -rn "import com.openggf.game.sonic2.objects.badniks.AbstractBadnikInstance" src/main/java --include="*.java" -l`

**Critical:** ~24 S2 badniks in `com.openggf.game.sonic2.objects.badniks` are same-package users with NO import statement. After the move, every one of them needs `import com.openggf.level.objects.AbstractBadnikInstance;` added. ~12 S1 badniks have explicit imports that need updating. Total: ~36 files.

For S2 badniks that relied on the default `S2_DESTRUCTION_CONFIG`: the `getDestructionConfig()` method should return a fallback when `destructionConfig` is `null`, to avoid NPE. Change `getDestructionConfig()` to:

```java
protected DestructionConfig getDestructionConfig() {
    return destructionConfig;  // Subclasses that use 3-arg constructor must override this
}
```

The simplest fix: keep a package-private `static final DestructionConfig S2_BADNIK_CONFIG` in a new `Sonic2BadnikConstants` class or in `Sonic2ObjectConstants`, and have each S2 badnik factory pass it via the 4-arg constructor. Alternatively, each S2 badnik that doesn't override `getDestructionConfig()` can simply pass the config explicitly.

S1 badniks already override `getDestructionConfig()` to return their S1 config, so they are unaffected.

- [ ] **Step 5: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: move AbstractBadnikInstance to level.objects, inject DestructionConfig"
```

---

## Phase 2: New Base Methods on AbstractObjectInstance

### Task 5: Add buildSpawnAt() to AbstractObjectInstance

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Modify: Object files with `getSpawn()` overrides and `refreshDynamicSpawn()` methods

- [ ] **Step 1: Add the helper method**

Add to `AbstractObjectInstance.java` before the closing brace:

```java
/**
 * Builds an ObjectSpawn at the given position, preserving all other fields from the
 * original spawn. Use in getSpawn() overrides and dynamic spawn tracking.
 */
protected ObjectSpawn buildSpawnAt(int x, int y) {
    return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(),
            spawn.renderFlags(), spawn.respawnTracked(), spawn.rawYWord());
}
```

- [ ] **Step 2: Find migration candidates**

Run: `grep -rn "new ObjectSpawn(.*currentX.*spawn.objectId\|new ObjectSpawn(.*x,.*y,.*spawn.objectId" src/main/java --include="*.java" -l`

Also: `grep -rn "refreshDynamicSpawn" src/main/java --include="*.java" -l`

- [ ] **Step 3: Migrate getSpawn() overrides**

For each file with a `getSpawn()` that constructs `new ObjectSpawn(currentX, currentY, spawn.objectId(), spawn.subtype(), spawn.renderFlags(), spawn.respawnTracked(), spawn.rawYWord())`:

Replace with: `return buildSpawnAt(currentX, currentY);`

**Note:** Only migrate files where the construction matches the standard 7-arg pattern exactly. Skip files that pass different values (e.g., modified subtype or objectId).

- [ ] **Step 4: Migrate refreshDynamicSpawn()**

For each file with `refreshDynamicSpawn()`, replace the `new ObjectSpawn(...)` call inside it with `buildSpawnAt(x, y)`.

- [ ] **Step 5: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: add buildSpawnAt() helper, migrate getSpawn() overrides"
```

### Task 6: Add isPlayerRiding() to AbstractObjectInstance

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Modify: ~15 platform/block object files

- [ ] **Step 1: Add the helper method**

Add to `AbstractObjectInstance.java`:

```java
/**
 * Returns true if any player is currently riding (standing on) this object.
 * Safe to call in test environments where LevelManager/ObjectManager may be null.
 */
protected boolean isPlayerRiding() {
    LevelManager lm = LevelManager.getInstance();
    if (lm == null) return false;
    var om = lm.getObjectManager();
    return om != null && om.isAnyPlayerRiding(this);
}
```

Add import if needed: `import com.openggf.level.LevelManager;`

- [ ] **Step 2: Find files with private copies**

Run: `grep -rn "private boolean isPlayerRiding\|private boolean isStanding\|isAnyPlayerRiding(this)" src/main/java --include="*.java" -l`

- [ ] **Step 3: Migrate each file**

Delete the private `isPlayerRiding()` or `isStanding()` method. Call sites now use the inherited method. If the private method had a different name (e.g., `isStanding()`), update call sites to use `isPlayerRiding()`.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: add isPlayerRiding() helper, delete 15+ private copies"
```

---

## Phase 3: Infrastructure Base Classes

### Task 7: AbstractZoneScrollHandler

**Files:**
- Create: `src/main/java/com/openggf/level/scroll/AbstractZoneScrollHandler.java`
- Modify: ~20 scroll handler files across `level.scroll`, `game.sonic1.scroll`, `game.sonic3k.scroll`

- [ ] **Step 1: Identify all scroll handlers with the duplicated fields**

Run: `grep -rn "private int minScrollOffset" src/main/java --include="*.java" -l`

- [ ] **Step 2: Read the ZoneScrollHandler interface**

Read `src/main/java/com/openggf/level/scroll/ZoneScrollHandler.java` to understand the full interface contract.

- [ ] **Step 3: Create AbstractZoneScrollHandler**

```java
package com.openggf.level.scroll;

public abstract class AbstractZoneScrollHandler implements ZoneScrollHandler {

    protected int minScrollOffset;
    protected int maxScrollOffset;
    protected short vscrollFactorBG;

    protected void resetScrollTracking() {
        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;
    }

    protected void trackOffset(short fgScroll, short bgScroll) {
        int offset = bgScroll - fgScroll;
        if (offset < minScrollOffset) minScrollOffset = offset;
        if (offset > maxScrollOffset) maxScrollOffset = offset;
    }

    @Override
    public int getMinScrollOffset() { return minScrollOffset; }

    @Override
    public int getMaxScrollOffset() { return maxScrollOffset; }

    @Override
    public short getVscrollFactorBG() { return vscrollFactorBG; }
}
```

- [ ] **Step 4: Migrate each scroll handler**

For each file:
1. Change `implements ZoneScrollHandler` to `extends AbstractZoneScrollHandler`
2. Delete private `minScrollOffset`, `maxScrollOffset`, `vscrollFactorBG` fields
3. Delete private `trackOffset()` method (or replace inline tracking code with `trackOffset()` calls)
4. Delete `getMinScrollOffset()`, `getMaxScrollOffset()`, `getVscrollFactorBG()` methods
5. Replace `minScrollOffset = Integer.MAX_VALUE; maxScrollOffset = Integer.MIN_VALUE;` at top of `update()` with `resetScrollTracking();`

- [ ] **Step 5: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: extract AbstractZoneScrollHandler, migrate ~20 scroll handlers"
```

### Task 8: AbstractZoneRegistry

**Files:**
- Create: `src/main/java/com/openggf/game/AbstractZoneRegistry.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1ZoneRegistry.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ZoneRegistry.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneRegistry.java`

- [ ] **Step 1: Read all three registries to identify shared methods**

Read each registry file to confirm which methods are identical and which differ.

- [ ] **Step 2: Create AbstractZoneRegistry**

Extract the shared methods: `getZoneCount()`, `getActCount()`, `getZoneName()`, `getLevelDataForZone()`, `getAllZones()`. Constructor takes `zones` list and `zoneNames` array. Leave `getMusicId()` abstract (S3K uses per-act, S1/S2 use per-zone).

- [ ] **Step 3: Migrate three registries to extend it**

Each game registry extends the abstract base, passes its data arrays to `super()`, and keeps only game-specific methods.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract AbstractZoneRegistry base class"
```

### Task 9: AbstractLevelInitProfile core steps

**Files:**
- Modify: `src/main/java/com/openggf/game/AbstractLevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`

- [ ] **Step 1: Read all three init profiles to identify the shared step list**

Read each `levelLoadSteps()` method. Note which steps are identical and which have game-specific overrides.

- [ ] **Step 2: Add IORunnable and ioStep helper to AbstractLevelInitProfile**

```java
@FunctionalInterface
protected interface IORunnable {
    void run() throws IOException;
}

protected static InitStep ioStep(String name, String desc, IORunnable action) {
    return new InitStep(name, desc, () -> {
        try { action.run(); } catch (IOException e) { throw new UncheckedIOException(e); }
    });
}
```

- [ ] **Step 3: Add buildCoreSteps()**

Add `protected List<InitStep> buildCoreSteps(LevelLoadContext ctx)` that constructs the ~12 shared steps using `ioStep()`. Each step calls the appropriate `LevelManager` method.

- [ ] **Step 4: Migrate three profiles**

Each game's `levelLoadSteps()` calls `buildCoreSteps(ctx)` then appends/overrides game-specific steps.

- [ ] **Step 5: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: extract buildCoreSteps() in AbstractLevelInitProfile"
```

### Task 10: AbstractObjectRegistry

**Files:**
- Create: `src/main/java/com/openggf/level/objects/AbstractObjectRegistry.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1ObjectRegistry.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/Sonic2ObjectRegistry.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`

- [ ] **Step 1: Read the ObjectRegistry interface and all three implementations**

Identify: `create(ObjectSpawn)`, `reportCoverage(List<ObjectSpawn>)`, `getPrimaryName(int)` as interface methods. Confirm which are shared vs. game-specific.

- [ ] **Step 2: Create AbstractObjectRegistry**

Contains: `factories` map, `loaded` flag, `ensureLoaded()`, `create(ObjectSpawn)`, `registerFactory(int, ObjectFactory)`. Provides no-op default for `reportCoverage()`. Leaves `registerDefaultFactories()` and `getPrimaryName(int)` abstract.

- [ ] **Step 3: Migrate three registries**

Each extends `AbstractObjectRegistry`, implements `registerDefaultFactories()` and `getPrimaryName()`. S2 overrides `reportCoverage()` with its coverage tracking. Delete duplicated `ensureLoaded()`, `create()`, `registerFactory()`, `factories`, `loaded` from each.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract AbstractObjectRegistry base class"
```

### Task 11: AniPlcScriptState + AniPlcParser

**Files:**
- Create: `src/main/java/com/openggf/level/animation/AniPlcScriptState.java`
- Create: `src/main/java/com/openggf/level/animation/AniPlcParser.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2PatternAnimator.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java`

**Note:** S1's `Sonic1PatternAnimator` is NOT affected (uses hardcoded per-zone `AnimHandler` approach).

- [ ] **Step 1: Read both pattern animators**

Read `Sonic2PatternAnimator.java` and `Sonic3kPatternAnimator.java` fully. Identify the `ScriptState` inner class, `parseList()`/`parseAniPlc()`, `loadArtPatterns()`, `ensurePatternCapacity()`, `primeScripts()`.

- [ ] **Step 2: Create AniPlcScriptState**

Extract the `ScriptState` inner class to `com.openggf.level.animation.AniPlcScriptState`. Make it package-accessible or public. Fields: `globalDuration`, `destTileIndex`, `frameTileIds`, `frameDurations`, `tilesPerFrame`, `artPatterns`, `timer`, `frameIndex`. Methods: `tick()`, `prime()`, `applyFrame()`, `requiredPatternCount()`.

The `tick()`, `prime()`, and `applyFrame()` methods take `AbstractLevel` and `GraphicsManager` parameters since they write patterns into the level.

- [ ] **Step 3: Create AniPlcParser**

Extract the shared binary parser loop to `AniPlcParser.parseScripts(RomByteReader reader, int addr)`. Also extract `loadArtPatterns()`. Use `reader.readU32BE()` instead of the private `readU32BE()` copies. Add `ensurePatternCapacity()` and `primeScripts()` as static utility methods.

- [ ] **Step 4: Migrate both animators**

Replace inner `ScriptState` class with `AniPlcScriptState` import. Replace `parseList()`/`parseAniPlc()` with `AniPlcParser.parseScripts()`. Replace `loadArtPatterns()` with `AniPlcParser.loadArtPatterns()`. Delete the private `readU32BE()` copies.

- [ ] **Step 5: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: extract AniPlcScriptState and AniPlcParser from pattern animators"
```

### Task 12: AbstractAudioProfile

**Files:**
- Create: `src/main/java/com/openggf/audio/AbstractAudioProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic1/audio/Sonic1AudioProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic2/audio/Sonic2AudioProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kAudioProfile.java`

- [ ] **Step 1: Read all three audio profiles**

Identify shared methods and the `handleSystemCommand()` pattern. Note that S3K passes explicit fade parameters while S1 uses no-arg `fadeOutMusic()`.

- [ ] **Step 2: Create AbstractAudioProfile**

Provide `handleSystemCommand()` that accepts a `Runnable fadeAction` parameter (not just command IDs) so S1 and S3K can supply their different fade behaviors. Add shared `SOUND_MAP` builder method.

- [ ] **Step 3: Migrate three profiles**

Each extends the abstract base, supplies command IDs and fade action via constructor or abstract methods.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract AbstractAudioProfile base class"
```

### Task 13: AbstractSoundTestCatalog

**Files:**
- Create: `src/main/java/com/openggf/audio/debug/AbstractSoundTestCatalog.java`
- Modify: `src/main/java/com/openggf/game/sonic1/audio/Sonic1SoundTestCatalog.java`
- Modify: `src/main/java/com/openggf/game/sonic2/audio/Sonic2SoundTestCatalog.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/Sonic3kSoundTestCatalog.java`

- [ ] **Step 1: Read all three catalogs and the interface**

Confirm structural identity: `lookupTitle`, `getValidSongs`, `getSfxNames`, `getSfxIdBase`, `getSfxIdMax`, `getDefaultSongId`, `getGameName`.

- [ ] **Step 2: Create AbstractSoundTestCatalog**

Constructor takes `titleMap`, `sfxNames`, `defaultSongId`, `sfxBase`, `sfxMax`, `gameName`. Implements all 6-7 interface methods.

- [ ] **Step 3: Migrate three catalogs**

Each becomes a thin wrapper passing its static maps to `super()`.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract AbstractSoundTestCatalog base class"
```

### Task 14: ParallaxShaderProgram extends ShaderProgram

**Files:**
- Modify: `src/main/java/com/openggf/graphics/ParallaxShaderProgram.java`

- [ ] **Step 1: Read both ShaderProgram and ParallaxShaderProgram**

Read `ShaderProgram.java` fully to understand the constructor, `programId`, `cacheUniformLocations()`, `use()`, `stop()`, `cleanup()` methods. Then read `ParallaxShaderProgram.java` to identify duplicated code.

- [ ] **Step 2: Make ParallaxShaderProgram extend ShaderProgram**

1. Add `extends ShaderProgram` to class declaration
2. The current constructor takes only `fragmentShaderPath` (vertex shader is hardcoded as a constant). Replace constructor body with `super(FULLSCREEN_VERTEX_SHADER, fragmentShaderPath)` — keep the single-arg constructor signature
3. Delete duplicated: `programId` field, `uniformsCached` field, the entire GL create/link/attach/detach/delete sequence, `use()`/`stop()`, `cleanup()`
4. Override `cacheUniformLocations()`: call `super.cacheUniformLocations()` first (which handles the `uniformsCached` guard), then cache parallax-specific uniforms. Remove the local `uniformsCached` field and its guard check — the parent handles it
5. Keep all parallax-specific uniform setters and rendering logic

- [ ] **Step 3: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor: ParallaxShaderProgram extends ShaderProgram, delete lifecycle duplication"
```

---

## Phase 4: Behavioral Object Base Classes

### Task 15: AbstractProjectileInstance

**Files:**
- Create: `src/main/java/com/openggf/level/objects/AbstractProjectileInstance.java`
- Modify: S1 missile/projectile classes, S2 `BadnikProjectileInstance`, S3K `S3kBadnikProjectileInstance`

- [ ] **Step 1: Read existing projectile classes**

Read at least 3 representative projectile classes to confirm the shared pattern:
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1BuzzBomberMissileInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/badniks/BadnikProjectileInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/badniks/S3kBadnikProjectileInstance.java`

- [ ] **Step 2: Create AbstractProjectileInstance**

```java
package com.openggf.level.objects;

public abstract class AbstractProjectileInstance extends AbstractObjectInstance
        implements TouchResponseProvider {

    protected int currentX, currentY;
    protected final SubpixelMotion.State motionState;
    protected final int gravity;
    protected final int collisionSizeIndex;

    protected AbstractProjectileInstance(ObjectSpawn spawn, String name,
            int xVel, int yVel, int gravity, int collisionSizeIndex) {
        super(spawn, name);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.gravity = gravity;
        this.collisionSizeIndex = collisionSizeIndex;
        this.motionState = new SubpixelMotion.State(currentX, currentY, 0, 0, xVel, yVel);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (gravity != 0) {
            SubpixelMotion.moveSprite(motionState, gravity);
        } else {
            SubpixelMotion.moveSprite2(motionState);
        }
        currentX = motionState.x;
        currentY = motionState.y;

        if (!isOnScreen(48)) {
            setDestroyed(true);
        }
    }

    @Override public int getX() { return currentX; }
    @Override public int getY() { return currentY; }
    @Override public int getCollisionFlags() { return 0x80 | (collisionSizeIndex & 0x3F); }
    @Override public int getCollisionProperty() { return 0; }

    @Override
    public ObjectSpawn getSpawn() { return buildSpawnAt(currentX, currentY); }

    protected abstract String getArtKey();
    protected abstract int getMappingFrame();
}
```

- [ ] **Step 3: Migrate S1 missile classes**

Each S1 missile class (e.g., `Sonic1BuzzBomberMissileInstance`) extends `AbstractProjectileInstance`, passes velocities/gravity to super(), keeps only its `appendRenderCommands()` and any unique behavior.

- [ ] **Step 4: Migrate S2 and S3K projectiles**

For S2/S3K projectile classes that can cleanly extend the base, migrate them. For classes with significant extra logic (e.g., `BadnikProjectileInstance`'s subtype enum), keep the class but delegate motion to `SubpixelMotion` (already done in Task 2) or selectively extend.

- [ ] **Step 5: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: add AbstractProjectileInstance, migrate S1 missile classes"
```

### Task 16: AbstractSpikeObjectInstance

**Files:**
- Create: `src/main/java/com/openggf/level/objects/AbstractSpikeObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/SpikeObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kSpikeObjectInstance.java`

- [ ] **Step 1: Read both spike implementations**

Read both files fully. Identify identical constants, methods, and the S3K-only push-mode extension.

- [ ] **Step 2: Create AbstractSpikeObjectInstance**

Extract shared: constants (`SPIKE_RETRACT_STEP`, `SPIKE_RETRACT_MAX`, `SPIKE_RETRACT_DELAY`, `WIDTH_PIXELS[]`, `Y_RADIUS[]`), methods (`moveSpikesDelay()`, `moveSpikesVertical()`, `moveSpikesHorizontal()`, `shouldHurt()`, `isSideways()`, `isUpsideDown()`, `getEntryValue()`, `getSolidParams()`). Leave `moveSpikes()` as a template method that S3K overrides to add push behavior.

- [ ] **Step 3: Migrate both spike classes**

Each extends the abstract base. S2 spike class becomes thin. S3K adds `moveSpikesPush()` override.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract AbstractSpikeObjectInstance for S2/S3K spikes"
```

### Task 17: AbstractMonitorObjectInstance

**Files:**
- Create: `src/main/java/com/openggf/level/objects/AbstractMonitorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1MonitorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/MonitorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kMonitorObjectInstance.java`

- [ ] **Step 1: Read all three monitor implementations**

Identify shared: icon-rise physics constants, `updateIcon()` state machine, falling-when-hit physics. Note game-specific differences in power-up sets.

- [ ] **Step 2: Create AbstractMonitorObjectInstance**

Extract shared icon-rise physics (`ICON_INITIAL_VELOCITY = -0x300`, `ICON_RISE_ACCEL = 0x18`, `ICON_WAIT_FRAMES = 0x1D`), `updateIcon()` method, `iconVelY`/`iconSubY` fields. Abstract `applyPowerup(AbstractPlayableSprite player)`.

- [ ] **Step 3: Migrate three monitor classes**

Each extends the abstract base, provides `applyPowerup()` and renderer key.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract AbstractMonitorObjectInstance base class"
```

### Task 18: AbstractPointsObjectInstance

**Files:**
- Create: `src/main/java/com/openggf/level/objects/AbstractPointsObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1PointsObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/PointsObjectInstance.java`

- [ ] **Step 1: Read both points implementations**

Confirm identical physics: `INITIAL_Y_VEL = -0x300`, `GRAVITY = 0x18`, destroy when `yVel >= 0`.

- [ ] **Step 2: Create AbstractPointsObjectInstance**

Extract physics constants and `update()`. Abstract `getFrameForScore(int score)`.

- [ ] **Step 3: Migrate both classes**

Each extends the base, providing only its score→frame table.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract AbstractPointsObjectInstance base class"
```

### Task 19: PlatformBobHelper

**Files:**
- Create: `src/main/java/com/openggf/level/objects/PlatformBobHelper.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1PlatformObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/ARZPlatformObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/FloatingPlatformObjectInstance.java`

- [ ] **Step 1: Read the nudge/bob code in all three platform files**

Identify the shared pattern: increment angle when standing, decrement when not, apply sine displacement.

- [ ] **Step 2: Create PlatformBobHelper**

```java
package com.openggf.level.objects;

import com.openggf.physics.TrigLookupTable;

public final class PlatformBobHelper {
    private final int stepSize;
    private final int maxAngle;
    private final int amplitudeShift;
    private int angle;

    public PlatformBobHelper(int stepSize, int maxAngle, int amplitudeShift) {
        this.stepSize = stepSize;
        this.maxAngle = maxAngle;
        this.amplitudeShift = amplitudeShift;
    }

    public void update(boolean isStanding) {
        if (isStanding) {
            angle = Math.min(angle + stepSize, maxAngle);
        } else {
            angle = Math.max(angle - stepSize, 0);
        }
    }

    public int getOffset() {
        return TrigLookupTable.sinHex(angle) >> amplitudeShift;
    }
}
```

- [ ] **Step 3: Migrate three platform files**

Replace inline nudge/bob logic with `PlatformBobHelper` field + `update(isPlayerRiding())` + `getOffset()`.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: add PlatformBobHelper, migrate 3 platform objects"
```

### Task 20: GravityDebrisChild

**Files:**
- Create: `src/main/java/com/openggf/level/objects/GravityDebrisChild.java`
- Modify: S3K `AizRockFragmentChild.java`, `RockDebrisChild.java`
- Modify: S1 `Sonic1BombShrapnelInstance.java` (if feasible)

- [ ] **Step 1: Read existing debris/fragment classes**

Read `AizRockFragmentChild`, `RockDebrisChild`, `Sonic1BombShrapnelInstance` to confirm shared pattern.

- [ ] **Step 2: Create GravityDebrisChild**

```java
package com.openggf.level.objects;

public abstract class GravityDebrisChild extends AbstractObjectInstance {
    protected final SubpixelMotion.State motionState;
    protected final int gravity;

    protected GravityDebrisChild(ObjectSpawn spawn, String name,
            int xVel, int yVel, int gravity) {
        super(spawn, name);
        this.gravity = gravity;
        this.motionState = new SubpixelMotion.State(
                spawn.x(), spawn.y(), 0, 0, xVel, yVel);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        SubpixelMotion.moveSprite(motionState, gravity);
        if (!isOnScreen(32)) {
            setDestroyed(true);
        }
    }

    @Override public int getX() { return motionState.x; }
    @Override public int getY() { return motionState.y; }

    @Override
    public ObjectSpawn getSpawn() { return buildSpawnAt(motionState.x, motionState.y); }
}
```

- [ ] **Step 3: Migrate debris classes**

Each extends `GravityDebrisChild`, providing only `appendRenderCommands()` and any unique logic (e.g., `Sonic1BombShrapnelInstance` also implements `TouchResponseProvider`).

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: add GravityDebrisChild, migrate fragment classes"
```

---

## Phase 5: Low-Priority Cleanup

### Task 21: SmpsSequencerConfig defaults + loadArtTiles + shader constant

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencerConfig.java`
- Modify: 3 per-game `*SmpsSequencerConfig.java` files
- Modify: `src/main/java/com/openggf/game/common/CommonSpriteDataLoader.java`
- Modify: `src/main/java/com/openggf/game/sonic1/S1SpriteDataLoader.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/S3kSpriteDataLoader.java`
- Modify: `src/main/java/com/openggf/graphics/ShaderProgram.java`
- Modify: `src/main/java/com/openggf/graphics/ParallaxShaderProgram.java`
- Modify: `src/main/java/com/openggf/graphics/TilemapShaderProgram.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`

- [ ] **Step 1: Move SmpsSequencerConfig constants**

Add `TEMPO_MOD_BASE`, `FM_CHANNEL_ORDER`, `PSG_CHANNEL_ORDER` as static defaults to `SmpsSequencerConfig`. Update 3 per-game configs to reference them instead of redeclaring.

- [ ] **Step 2: Consolidate loadArtTiles**

Move the `loadArtTiles()` method from `S1SpriteDataLoader` and `S3kSpriteDataLoader` to `CommonSpriteDataLoader`. Update callers.

- [ ] **Step 3: Shared FULLSCREEN_VERTEX_SHADER**

Add `protected static final String FULLSCREEN_VERTEX_SHADER = "shaders/shader_fullscreen.vert";` to `ShaderProgram`. Delete copies from `ParallaxShaderProgram`, `TilemapShaderProgram`, and `GraphicsManager` (`FULLSCREEN_VERTEX_SHADER_PATH`). Update references.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: Phase 5 cleanup — shared constants, loadArtTiles, shader path"
```
