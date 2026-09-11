# Phase 0 + Phase 1: Code Hygiene & Provider Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate production debug output, fix error handling, remove dead code, standardize the GameModule provider pattern, fix feature flag leaks, and optimize hot-path service lookups.

**Architecture:** Phase 0 (Tasks 1-8) and Phase 1 (Tasks 9-14) are independent and can run in parallel. Within each phase, all tasks are independent commits. No new features — only structural improvements.

**Tech Stack:** Java 21, Maven

**Spec:** `docs/architecture/designs/2026-03-23-unified-execution-roadmap-design.md`

---

### Task 1: Replace production System.out.println with LOGGER (0-1)

Three production sites write to stdout during normal gameplay.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java:1543`
- Modify: `src/main/java/com/openggf/graphics/BatchedPatternRenderer.java:378,649-651`

- [ ] **Step 1: Fix ObjectManager hurt logging**

In `ObjectManager.java` at line 1543, replace:
```java
System.out.println(">>> SONIC HURT by: " + className + " (ID: 0x" + Integer.toHexString(objectId) + ")");
```
with:
```java
LOG.fine(() -> "Touch hurt by: " + className + " (ID: 0x" + Integer.toHexString(objectId) + ")");
```
Verify `LOG` is already declared (it should be — check for `private static final Logger LOG`). If not, add the import and field.

- [ ] **Step 2: Fix BatchedPatternRenderer debug logging**

In `BatchedPatternRenderer.java` at line 378, replace the `System.out.println("[BatchedPatternRenderer] endBatch: ...")` and its guard (`if (debugFrameCounter < 10)`) with `LOGGER.fine(...)` or remove entirely.

At lines 649-651, replace the `System.out.println("[BatchRenderCommand] execute: ...")` and its guard (`if (executeDebugCounter < 5)`) with `LOGGER.fine(...)` or remove entirely.

If no `LOGGER` field exists in the class, add:
```java
private static final Logger LOGGER = Logger.getLogger(BatchedPatternRenderer.class.getName());
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Verify no remaining System.out in production**

Run: `grep -rn "System.out.println" src/main/java/com/openggf/ --include="*.java" | grep -v "//"`
Expected: No matches (or only in tools/debug utilities, not in gameplay-path classes like ObjectManager, BatchedPatternRenderer)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java src/main/java/com/openggf/graphics/BatchedPatternRenderer.java
git commit -m "$(cat <<'EOF'
fix: replace production System.out.println with LOGGER.fine()

ObjectManager was printing ">>> SONIC HURT by:" to stdout on every
player hurt event. BatchedPatternRenderer was printing debug info
on the first 5-10 frames after initialization. Both now use
java.util.logging at FINE level.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Migrate e.printStackTrace() to structured logging (0-2)

22 call sites bypass the JUL logging framework. Replace each with `LOGGER.log(Level.SEVERE, "...", e)` or `Level.WARNING` as appropriate.

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java:56,158`
- Modify: `src/main/java/com/openggf/debug/DebugArtViewer.java:39,75`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java:59`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java:625`
- Modify: `src/main/java/com/openggf/game/sonic1/levelselect/Sonic1LevelSelectDataLoader.java:91`
- Modify: `src/main/java/com/openggf/game/sonic1/audio/smps/Sonic1SmpsLoader.java:93,138,233,275`
- Modify: `src/main/java/com/openggf/game/sonic2/audio/smps/Sonic2SmpsLoader.java:310,453,482,549,746`
- Modify: `src/main/java/com/openggf/game/sonic1/titlescreen/Sonic1TitleScreenDataLoader.java:165`
- Modify: `src/main/java/com/openggf/game/sonic2/titlescreen/TitleScreenDataLoader.java:158`
- Modify: `src/main/java/com/openggf/game/sonic2/titlecard/TitleCardManager.java:341`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackFrameDecoder.java:289`
- Modify: `src/main/java/com/openggf/game/sonic2/menu/MenuBackgroundDataLoader.java:62`
- Modify: `src/main/java/com/openggf/game/sonic2/levelselect/LevelSelectDataLoader.java:134`
- Modify: `src/main/java/com/openggf/graphics/ShaderProgram.java:94`
- Modify: `src/main/java/com/openggf/graphics/ShaderLoader.java:34`

- [ ] **Step 1: Fix AbstractPlayableSprite contradictory catch block**

At `AbstractPlayableSprite.java:623-627`, the catch block does `LOGGER.fine()` + `e.printStackTrace()` + `throw e`. Remove only the `e.printStackTrace()` line. The `LOGGER.fine()` and rethrow are sufficient.

- [ ] **Step 2: Fix config service**

In `SonicConfigurationService.java`, at both line 56 and line 158, replace `e.printStackTrace()` with:
```java
LOGGER.log(Level.WARNING, "Failed to load/save configuration", e);
```
Add `private static final Logger LOGGER = Logger.getLogger(SonicConfigurationService.class.getName());` if not present. Add `import java.util.logging.Level;` and `import java.util.logging.Logger;` if needed.

- [ ] **Step 3: Fix audio loaders**

In `Sonic1SmpsLoader.java` at lines 93, 138, 233, 275 — replace each `e.printStackTrace()` with:
```java
LOGGER.log(Level.SEVERE, "Failed to load SMPS data", e);
```
Add LOGGER field if not present.

In `Sonic2SmpsLoader.java` at lines 310, 453, 482, 549, 746 — same pattern.

In `AudioManager.java` at line 59 — replace with `LOGGER.log(Level.SEVERE, "Failed to initialize audio backend", e);`

- [ ] **Step 4: Fix data loaders**

Apply the same pattern to:
- `Sonic1LevelSelectDataLoader.java:91` → `Level.WARNING`
- `Sonic1TitleScreenDataLoader.java:165` → `Level.WARNING`
- `TitleScreenDataLoader.java:158` → `Level.WARNING`
- `TitleCardManager.java:341` → `Level.WARNING`
- `Sonic2TrackFrameDecoder.java:289` → `Level.WARNING`
- `MenuBackgroundDataLoader.java:62` → `Level.WARNING`
- `LevelSelectDataLoader.java:134` → `Level.WARNING`

- [ ] **Step 5: Fix debug utilities**

In `DebugArtViewer.java` at lines 39 and 75 — replace with `LOGGER.log(Level.WARNING, "...", e);`

- [ ] **Step 6: Fix shader error handling (System.err.println)**

In `ShaderLoader.java:34`, replace `System.err.println("Shader compilation failed:\n" + log)` with `LOGGER.severe("Shader compilation failed:\n" + log);`. Add LOGGER if needed.

In `ShaderProgram.java:94`, replace `System.err.println("Shader linking failed:\n" + log)` with `LOGGER.severe("Shader linking failed:\n" + log);`. Add LOGGER if needed.

- [ ] **Step 7: Verify no remaining e.printStackTrace() in production**

Run: `grep -rn "e\.printStackTrace()" src/main/java/ --include="*.java"`
Expected: No matches

Run: `grep -rn "System\.err\.println" src/main/java/ --include="*.java"`
Expected: No matches (or only in tool/utility classes)

- [ ] **Step 8: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add -A src/main/java/
git commit -m "$(cat <<'EOF'
fix: migrate 22 e.printStackTrace() calls to structured logging

All production code now uses java.util.logging instead of printing
stack traces to stderr. Audio loaders use Level.SEVERE, data loaders
use Level.WARNING. Also fixed the contradictory catch block in
AbstractPlayableSprite that was logging + printing + rethrowing.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Synchronize WaterSystem.getInstance() (0-3)

**Files:**
- Modify: `src/main/java/com/openggf/level/WaterSystem.java:184`

- [ ] **Step 1: Add synchronized keyword**

At `WaterSystem.java:184`, change:
```java
public static WaterSystem getInstance() {
```
to:
```java
public static synchronized WaterSystem getInstance() {
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/WaterSystem.java
git commit -m "$(cat <<'EOF'
fix: synchronize WaterSystem.getInstance()

WaterSystem was the only core manager singleton without synchronized
on getInstance(), creating a theoretical data race. Matches the
pattern used by all other manager singletons.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Remove dead CollisionSystem flags (0-4)

**Files:**
- Modify: `src/main/java/com/openggf/physics/CollisionSystem.java:34,37,175-189`

- [ ] **Step 1: Check for usages of the dead fields**

Run: `grep -rn "unifiedPipelineEnabled\|shadowModeEnabled\|setUnifiedPipelineEnabled\|setShadowModeEnabled" src/ --include="*.java"`

Identify all references. If any production code sets these to `true`, do NOT remove — abort this task.

- [ ] **Step 2: Remove dead fields and setters**

In `CollisionSystem.java`:
- Remove `private boolean unifiedPipelineEnabled = false;` (line 34)
- Remove `private boolean shadowModeEnabled = false;` (line 37)
- Remove any setter methods for these fields
- In `postResolutionAdjustments()` (lines 175-189), remove the no-op headroom computation block that computes `headroom` but never acts on it. Keep the method signature if it's called elsewhere (as a no-op shell), or remove entirely if no callers exist.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/physics/CollisionSystem.java
git commit -m "$(cat <<'EOF'
refactor: remove dead CollisionSystem flags

unifiedPipelineEnabled and shadowModeEnabled were never set to true.
postResolutionAdjustments() computed headroom but never acted on it.
All three were scaffold from an incomplete migration that is no longer
planned in its original form.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Remove deprecated SmpsSequencerConfig constructors (0-5)

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencerConfig.java:133-206`

- [ ] **Step 1: Find callers of deprecated constructors**

Run: `grep -rn "new SmpsSequencerConfig(" src/ --include="*.java"`

List all call sites. Each must be migrated to the Builder before the constructors can be deleted.

- [ ] **Step 2: Migrate callers to Builder**

For each caller found in Step 1, replace the constructor call with the equivalent `SmpsSequencerConfig.builder()...build()` chain. The Builder should already exist — check for `SmpsSequencerConfig.builder()` or `SmpsSequencerConfig.Builder`.

- [ ] **Step 3: Delete deprecated constructors**

Remove the three `@Deprecated(forRemoval = true)` constructors at lines 133-176, 182-192, and 198-206 from `SmpsSequencerConfig.java`.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run audio tests**

Run: `mvn test -q -pl . -Dtest="*Smps*,*Audio*,*Ym2612*,*Psg*"`
Expected: All pass

- [ ] **Step 6: Commit**

```bash
git add -A src/
git commit -m "$(cat <<'EOF'
refactor: remove deprecated SmpsSequencerConfig constructors

Three constructors marked @Deprecated(forRemoval=true) silently used
S2 defaults for S3K-specific fields (volMode, modAlgo, etc). All
callers now use the Builder, which requires explicit field values.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Add shader compilation fail-fast (0-6)

**Files:**
- Modify: `src/main/java/com/openggf/graphics/ShaderLoader.java:34`
- Modify: `src/main/java/com/openggf/graphics/ShaderProgram.java:94`

- [ ] **Step 1: Add throw after shader compile failure**

In `ShaderLoader.java`, after the log message at line 34 (which should now be `LOGGER.severe(...)` from Task 2), add:
```java
throw new RuntimeException("Shader compilation failed: " + log);
```

- [ ] **Step 2: Add throw after shader link failure**

In `ShaderProgram.java`, after the log message at line 94 (which should now be `LOGGER.severe(...)` from Task 2), add:
```java
throw new RuntimeException("Shader linking failed: " + log);
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/graphics/ShaderLoader.java src/main/java/com/openggf/graphics/ShaderProgram.java
git commit -m "$(cat <<'EOF'
fix: fail fast on shader compilation/linking errors

Previously, shader failures logged to stderr but continued execution,
silently binding a broken or zero program. Now throws RuntimeException
for immediate diagnosis at startup.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Fix stale comments and minor hygiene (0-7)

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java:436`
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java:35-38`

- [ ] **Step 1: Fix Ym2612Chip stale comment**

At `Ym2612Chip.java:436`, change:
```java
private boolean useBlipResampler = true;  // Disabled for testing - set true to enable band-limited resampling
```
to:
```java
private boolean useBlipResampler = true;  // Band-limited resampling via BlipResampler (GPGX-quality output)
```

- [ ] **Step 2: Fix SonicConfiguration SCALE Javadoc**

At `SonicConfiguration.java:35-38`, change:
```java
/**
 * Scale used with BufferedImage TODO: Work out what this does
 */
SCALE,
```
to:
```java
/**
 * Scale factor for BufferedImage rendering (used for AWT-based debug viewers).
 */
SCALE,
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/audio/synth/Ym2612Chip.java src/main/java/com/openggf/configuration/SonicConfiguration.java
git commit -m "$(cat <<'EOF'
docs: fix stale comments in Ym2612Chip and SonicConfiguration

Ym2612Chip resampler comment said "Disabled for testing" but the field
was true. SonicConfiguration SCALE had a lingering TODO.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Fill remaining singleton lifecycle gaps (0-8)

**Files:**
- Modify: `src/main/java/com/openggf/debug/DebugOverlayManager.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`

- [ ] **Step 1: Add DebugOverlayManager.resetState()**

`DebugOverlayManager` has mutable state: `pendingObjectDebugText` (line ~25) and `states` EnumMap (line ~16). Add a `resetState()` method:

```java
public void resetState() {
    states.clear();
    pendingObjectDebugText = List.of();
}
```

- [ ] **Step 2: Add SonicConfigurationService.resetToDefaults()**

`SonicConfigurationService` stores all config in `Map<String, Object> config` (line ~19). Add:

```java
public void resetToDefaults() {
    config = new HashMap<>();
    applyDefaults();
}
```

This resets config to the same state as if no `config.json` was loaded.

- [ ] **Step 3: Wire into test reset path**

Check `AbstractLevelInitProfile.perTestResetSteps()` and `TestEnvironment.resetPerTest()`. If `DebugOverlayManager.resetState()` is not already called, add it. `SonicConfigurationService.resetToDefaults()` should go in `TestEnvironment.resetAll()` (full reset only, not per-test — config should be stable within a test class).

- [ ] **Step 4: Verify compilation and tests**

Run: `mvn compile -q && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "$(cat <<'EOF'
refactor: add resetState() to DebugOverlayManager and SonicConfigurationService

Completes the singleton lifecycle coverage. DebugOverlayManager clears
its overlay toggle states and pending debug text. SonicConfigurationService
resets to defaults so test config modifications don't bleed.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Unify null-returning providers to NoOp sentinels (1-1)

**Files:**
- Create: `src/main/java/com/openggf/game/NoOpRomOffsetProvider.java`
- Create: `src/main/java/com/openggf/game/NoOpDebugModeProvider.java`
- Create: `src/main/java/com/openggf/game/NoOpZoneArtProvider.java`
- Modify: `src/main/java/com/openggf/game/GameModule.java:153,161,177`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java:167-184`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java:144-161`

- [ ] **Step 1: Identify the three provider interfaces**

Read the existing `RomOffsetProvider`, `DebugModeProvider`, and `ZoneArtProvider` interfaces (or the types returned by the three methods). Determine all abstract methods each declares.

Also read `NoOpTitleScreenProvider.java` and `NoOpLevelSelectProvider.java` to confirm the established NoOp pattern: final class, private constructor, `public static final INSTANCE` field, no-op method bodies returning safe defaults.

- [ ] **Step 2: Create NoOpRomOffsetProvider**

Follow the established pattern. Implement all interface methods with no-op returns (return `0`, `null`, or empty as appropriate for each method's return type).

- [ ] **Step 3: Create NoOpDebugModeProvider**

Same pattern.

- [ ] **Step 4: Create NoOpZoneArtProvider**

Same pattern.

- [ ] **Step 5: Convert abstract methods to defaults in GameModule**

In `GameModule.java`, change the three methods from abstract to default:
```java
// Line ~153
default RomOffsetProvider getRomOffsetProvider() {
    return NoOpRomOffsetProvider.INSTANCE;
}

// Line ~161
default DebugModeProvider getDebugModeProvider() {
    return NoOpDebugModeProvider.INSTANCE;
}

// Line ~177
default ZoneArtProvider getZoneArtProvider() {
    return NoOpZoneArtProvider.INSTANCE;
}
```

- [ ] **Step 6: Remove null-returning overrides from S1 and S3K modules**

Delete the `getRomOffsetProvider()`, `getDebugModeProvider()`, and `getZoneArtProvider()` methods from `Sonic1GameModule.java` (lines ~167-184) and `Sonic3kGameModule.java` (lines ~144-161) since they just returned null and the default now handles it.

- [ ] **Step 7: Audit and remove null checks at call sites**

Run: `grep -rn "getRomOffsetProvider\|getDebugModeProvider\|getZoneArtProvider" src/main/java/ --include="*.java"`

At each call site, remove `if (provider != null)` guards since the NoOp sentinel guarantees a non-null return.

- [ ] **Step 8: Verify compilation and tests**

Run: `mvn compile -q && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add -A src/main/java/com/openggf/game/
git commit -m "$(cat <<'EOF'
refactor: unify null-returning providers to NoOp sentinels

getRomOffsetProvider(), getDebugModeProvider(), and getZoneArtProvider()
were abstract methods returning null from S1 and S3K, forcing null
checks at every call site. Now they are default methods returning
NoOp singletons, matching the pattern used by TitleScreenProvider,
LevelSelectProvider, and other optional providers.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Make GameModule.getGameId() abstract (1-2)

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java:360-367`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`

- [ ] **Step 1: Check existing getGameId() overrides**

Run: `grep -rn "getGameId" src/main/java/ --include="*.java"`

Check whether each module already overrides `getGameId()`. If they do, simply remove the default. If any module does NOT override, add the override before removing the default.

- [ ] **Step 2: Replace default with abstract**

In `GameModule.java` at lines 360-367, replace the default method with:
```java
GameId getGameId();
```

- [ ] **Step 3: Add overrides to any modules that lack them**

Ensure each module has:
- `Sonic1GameModule`: `@Override public GameId getGameId() { return GameId.S1; }`
- `Sonic2GameModule`: `@Override public GameId getGameId() { return GameId.S2; }`
- `Sonic3kGameModule`: `@Override public GameId getGameId() { return GameId.S3K; }`

- [ ] **Step 4: Verify compilation and tests**

Run: `mvn compile -q && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/openggf/game/
git commit -m "$(cat <<'EOF'
refactor: make GameModule.getGameId() abstract

The default method contained a string switch over magic literals that
broke the open/closed principle. Each module now explicitly declares
its GameId, removing the hidden coupling to getIdentifier() strings.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Replace CrossGameFeatureProvider string comparisons (1-3)

**Files:**
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java:106,197,220,287,336`

- [ ] **Step 1: Identify the donorGameId parameter type**

Read the methods containing lines 106, 197, 220, 287, 336. Determine where `donorGameId` (String) is declared — it's likely a field or method parameter. Check how it's set (probably from `getIdentifier()` or similar).

- [ ] **Step 2: Change donorGameId type from String to GameId**

If `donorGameId` is a field, change its type to `GameId`. Update the setter/constructor accordingly. If it's derived from a String, convert at the assignment point using `GameId` enum lookup or `GameModule.getGameId()`.

- [ ] **Step 3: Replace string comparisons with enum comparisons**

At each of the 5 call sites, replace:
```java
if ("s3k".equalsIgnoreCase(donorGameId))
```
with:
```java
if (donorGameId == GameId.S3K)
```

And at line 336:
```java
if (!"s3k".equalsIgnoreCase(donorGameId) ...)
```
with:
```java
if (donorGameId != GameId.S3K ...)
```

- [ ] **Step 4: Verify compilation and tests**

Run: `mvn compile -q && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Verify no remaining string comparisons**

Run: `grep -rn "equalsIgnoreCase.*s3k\|equalsIgnoreCase.*s2\|equalsIgnoreCase.*sonic" src/main/java/ --include="*.java" -i`
Expected: No matches in CrossGameFeatureProvider

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/com/openggf/game/
git commit -m "$(cat <<'EOF'
refactor: replace CrossGameFeatureProvider string comparisons with GameId enum

Five "s3k".equalsIgnoreCase() calls replaced with GameId.S3K enum
comparison. The donorGameId field/parameter is now typed as GameId
rather than String, matching the pattern used by resolveDonorCapabilities().

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Cache LevelManager reference in DefaultObjectServices (1-4)

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`

- [ ] **Step 1: Add cached field**

Add a private final field and constructor:
```java
private final LevelManager levelManager;

public DefaultObjectServices(LevelManager levelManager) {
    this.levelManager = levelManager;
}
```

- [ ] **Step 2: Replace all LevelManager.getInstance() calls**

Replace all 11 `LevelManager.getInstance()` calls in the class methods (lines ~27-98) with `this.levelManager`.

- [ ] **Step 3: Update the construction site**

Find where `DefaultObjectServices` is constructed (likely in `ObjectManager`). Pass `LevelManager.getInstance()` to the constructor:
```java
new DefaultObjectServices(LevelManager.getInstance())
```

Run: `grep -rn "new DefaultObjectServices" src/ --include="*.java"` to find the site.

- [ ] **Step 4: Verify compilation and tests**

Run: `mvn compile -q && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/openggf/level/objects/
git commit -m "$(cat <<'EOF'
perf: cache LevelManager reference in DefaultObjectServices

Every method was calling LevelManager.getInstance() — 11 synchronized
singleton lookups per invocation. Since DefaultObjectServices is created
once per ObjectManager (once per level load), caching the reference in
the constructor is safe and eliminates ~8+ redundant lookups per player
per frame in hot paths like SolidContacts.update().

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Deduplicate TouchResponses update/updateSidekick (1-5)

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java:1019-1203`

- [ ] **Step 1: Read both methods carefully**

Read `TouchResponses.update()` (lines 1019-1126) and `TouchResponses.updateSidekick()` (lines 1134-1203) in full. Identify:
- The shared hitbox calculation logic
- The shared multi-region routing
- The shared touch flag decoding and shield deflect check
- The shared overlap detection
- The KEY DIFFERENCES: player breaks after first hit, sidekick continues; different response handlers; player has insta-shield expansion; player uses temp-swap double buffering

- [ ] **Step 2: Extract shared collision loop**

Create a private method that encapsulates the shared logic:
```java
private void processTouch(AbstractPlayableSprite player, boolean isSidekick) {
    // Shared hitbox calculation
    // Shared multi-region routing
    // Shared touch flag decoding
    // Shared overlap detection
    // Branch: if (isSidekick) handleTouchResponseSidekick() else handleTouchResponse()
    // Branch: if (!isSidekick) break after first hit
}
```

- [ ] **Step 3: Delegate from both public methods**

`update()` calls `processTouch(player, false)` with the player-specific double-buffer swap and insta-shield setup around it.

`updateSidekick()` calls `processTouch(sidekick, true)` with the sidekick-specific buffer swap.

- [ ] **Step 4: Verify compilation and tests**

Run: `mvn compile -q && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java
git commit -m "$(cat <<'EOF'
refactor: deduplicate TouchResponses update() and updateSidekick()

~150 lines of near-duplicate collision loop code extracted into a
shared processTouch() method. The key differences (break-on-first-hit
for player, different response handlers, insta-shield expansion) are
parameterized via a boolean isSidekick flag.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: Document Sonic2GameModule lazy-init strategy (1-6)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`

- [ ] **Step 1: Add clarifying comments to fresh-allocation providers**

At each `get*Provider()` method that creates a fresh instance (lines ~156, ~174, ~179, ~191), add a comment explaining the contract:

```java
/**
 * Returns a fresh instance per call. Callers (LevelManager) store
 * the returned instance for the level's lifetime. Stateless provider.
 */
```

- [ ] **Step 2: Add clarifying comments to cached providers**

At each lazily-cached provider (ZoneFeatureProvider ~161, ObjectArtProvider ~220, PhysicsProvider ~228), add a comment:

```java
/**
 * Lazily cached — same instance across level loads. Accumulates
 * registered art/state. Reset via module replacement in GameModuleRegistry.
 */
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java
git commit -m "$(cat <<'EOF'
docs: document Sonic2GameModule provider caching strategy

Some providers are lazily cached (ZoneFeature, ObjectArt, Physics),
others create fresh instances per call (Scroll, RomOffset, DebugMode,
ZoneArt). Added comments documenting the contract at each method.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```
