# Two-Tier Service Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand ObjectServices as the context-specific service layer for all game objects, establishing a two-tier pattern (ObjectServices for context-specific, GameServices for global) that enables future level editor support.

**Architecture:** ObjectServices gains 3 new methods: `camera()`, `gameState()`, `sidekicks()`. DefaultObjectServices delegates to existing singletons. ~169 object files containing migratable patterns are mechanically migrated from GameServices/getInstance() to services(). GameServices remains unchanged for global and non-object code. Files that only use GameServices for game-mode transitions or global services are not migrated.

**Tech Stack:** Java 21, Maven, JUnit 5

**Spec:** `docs/architecture/designs/2026-03-22-two-tier-service-architecture-design.md`

---

### Task 1: Expand ObjectServices Interface

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectServices.java`

- [ ] **Step 1: Add the 3 new method signatures to ObjectServices**

Add these after the existing `spawnLostRings` method:

```java
import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import java.util.List;

// Add to interface body:

// Context-specific managers
Camera camera();
GameStateManager gameState();

// Player/sidekick access
List<PlayableEntity> sidekicks();
```

- [ ] **Step 2: Verify compilation fails (DefaultObjectServices doesn't implement new methods)**

Run: `mvn compile -Dmse=off 2>&1 | grep "error"` — expect compilation errors in DefaultObjectServices.

- [ ] **Step 3: Commit interface change**

```bash
git add src/main/java/com/openggf/level/objects/ObjectServices.java
git commit -m "feat: expand ObjectServices interface with camera(), gameState(), sidekicks()"
```

---

### Task 2: Implement DefaultObjectServices Delegation

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`

- [ ] **Step 1: Add imports and implement the 3 new methods**

Add imports:
```java
import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayableEntity;
import com.openggf.sprites.managers.SpriteManager;
import java.util.List;
```

Add implementations:
```java
@Override
public Camera camera() {
    return Camera.getInstance();
}

@Override
public GameStateManager gameState() {
    return GameStateManager.getInstance();
}

@Override
public List<PlayableEntity> sidekicks() {
    return List.copyOf(SpriteManager.getInstance().getSidekicks());
}
```

- [ ] **Step 2: Verify compilation succeeds**

Run: `mvn compile -Dmse=off` — expect BUILD SUCCESS.

- [ ] **Step 3: Run existing tests to verify no regressions**

Run: `mvn test -Dmse=off` — expect same results as baseline (1920 tests, 1 failure in GHZ1 ROM data).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/DefaultObjectServices.java
git commit -m "feat: implement camera(), gameState(), sidekicks() in DefaultObjectServices"
```

---

### Task 3: Add Verification Test

**Files:**
- Create: `src/test/java/com/openggf/level/objects/TestObjectServicesExpansion.java`

- [ ] **Step 1: Write test verifying the new ObjectServices methods delegate correctly**

```java
package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the expanded ObjectServices methods delegate to the correct singletons.
 */
class TestObjectServicesExpansion {

    @Test
    void defaultObjectServices_camera_returnsSingleton() {
        DefaultObjectServices services = new DefaultObjectServices();
        assertSame(Camera.getInstance(), services.camera(),
                "camera() should delegate to Camera.getInstance()");
    }

    @Test
    void defaultObjectServices_gameState_returnsSingleton() {
        DefaultObjectServices services = new DefaultObjectServices();
        assertSame(GameStateManager.getInstance(), services.gameState(),
                "gameState() should delegate to GameStateManager.getInstance()");
    }

    @Test
    void defaultObjectServices_sidekicks_returnsUnmodifiableList() {
        DefaultObjectServices services = new DefaultObjectServices();
        var sidekicks = services.sidekicks();
        assertNotNull(sidekicks, "sidekicks() should never return null");
        assertThrows(UnsupportedOperationException.class, () -> sidekicks.add(null),
                "sidekicks() should return an unmodifiable list");
    }
}
```

- [ ] **Step 2: Run the new test**

Run: `mvn test -Dtest=TestObjectServicesExpansion -Dmse=off` — expect 3 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/level/objects/TestObjectServicesExpansion.java
git commit -m "test: verify expanded ObjectServices delegation methods"
```

---

### Task 4: Migrate Game-Agnostic level/objects/ Files (11 files)

**Files:**
- Modify: 11 files in `src/main/java/com/openggf/level/objects/` (excluding ObjectServices.java, DefaultObjectServices.java, AbstractObjectInstance.java, and interfaces)

- [ ] **Step 1: Migrate all context-specific GameServices/getInstance() calls in level/objects/**

Apply these substitutions across all object files in `level/objects/`:

| Pattern | Replacement |
|---------|-------------|
| `GameServices.camera()` | `services().camera()` |
| `GameServices.gameState()` | `services().gameState()` |
| `GameServices.audio().playSfx(` | `services().playSfx(` |
| `GameServices.audio().playMusic(` | `services().playMusic(` |
| `GameServices.audio().fadeOutMusic()` | `services().fadeOutMusic()` |
| `GameServices.level().getObjectManager()` | `services().objectManager()` |
| `GameServices.level().getObjectRenderManager()` | `services().renderManager()` |
| `GameServices.level().getRomZoneId()` | `services().romZoneId()` |
| `GameServices.level().getCurrentAct()` | `services().currentAct()` |
| `GameServices.level().getCurrentLevel()` | `services().currentLevel()` |
| `GameServices.level().getCheckpointState()` | `services().checkpointState()` |
| `GameServices.level().getLevelGamestate()` | `services().levelGamestate()` |
| `SpriteManager.getInstance().getSidekicks()` | `services().sidekicks()` |

**Do NOT migrate:**
- `GameServices.rom()` — global
- `GameServices.level().requestZoneAndAct()` — game-mode transition
- `GameServices.level().requestSpecialStageEntry()` — game-mode transition
- `GameServices.level().requestCreditsTransition()` — game-mode transition
- `GameServices.level().advanceToNextLevel()` — game-mode transition
- `GameServices.level().invalidateForegroundTilemap()` — rendering control
- `GameServices.level().updatePalette()` — rendering control
- Any `SonicConfigurationService.getInstance()` — global config
- Any GameServices usage inside constructors — must stay (services not yet injected)

After each file: remove now-unused imports (`import com.openggf.game.GameServices`, `import com.openggf.sprites.managers.SpriteManager`, `import com.openggf.game.GameStateManager`, `import com.openggf.camera.Camera`) but only if NO remaining references exist in the file.

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -Dmse=off` — expect BUILD SUCCESS.

- [ ] **Step 3: Run full test suite**

Run: `mvn test -Dmse=off` — expect same baseline results.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/
git commit -m "refactor: migrate level/objects/ from GameServices to services() (11 files)"
```

---

### Task 5: Migrate Sonic 2 Object Files (77 files)

**Files:**
- Modify: 77 files in `src/main/java/com/openggf/game/sonic2/objects/` (including subdirectories badniks/, bosses/)

- [ ] **Step 1: Apply same substitution rules from Task 4 to all S2 object files**

Same migration rules as Task 4. For S2 specifically, watch for:
- Boss files that call `GameServices.level().requestZoneAndAct()` — keep these on GameServices
- `TornadoObjectInstance.java` — likely uses camera extensively, migrate camera() calls
- Monitor instances — migrate `gameState().addScore()` and `gameState().addLife()`
- All badniks — migrate `audio().playSfx()` calls for destruction sounds

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -Dmse=off` — expect BUILD SUCCESS.

- [ ] **Step 3: Run full test suite**

Run: `mvn test -Dmse=off` — expect same baseline results.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/
git commit -m "refactor: migrate S2 objects from GameServices/getInstance to services() (77 files)"
```

---

### Task 6: Migrate Sonic 1 Object Files (55 files)

**Files:**
- Modify: 55 files in `src/main/java/com/openggf/game/sonic1/objects/` (including subdirectories bosses/)

- [ ] **Step 1: Apply same substitution rules from Task 4 to all S1 object files**

Same migration rules. For S1 specifically, watch for:
- `Sonic1SwitchManager.getInstance()` calls — these are game-specific singletons, keep as-is
- `Sonic1ConveyorState.getInstance()` calls — game-specific, keep as-is
- Boss files that call `GameServices.level()` for transitions — keep on GameServices

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -Dmse=off` — expect BUILD SUCCESS.

- [ ] **Step 3: Run full test suite**

Run: `mvn test -Dmse=off` — expect same baseline results.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/objects/
git commit -m "refactor: migrate S1 objects from GameServices/getInstance to services() (55 files)"
```

---

### Task 7: Migrate Sonic 3&K Object Files (26 files)

**Files:**
- Modify: 26 files in `src/main/java/com/openggf/game/sonic3k/objects/` (including subdirectories bosses/)

- [ ] **Step 1: Apply same substitution rules from Task 4 to all S3K object files**

Same migration rules. For S3K specifically, watch for:
- `Sonic3kLevelEventManager.getInstance()` calls — game-specific, keep as-is
- `Sonic3kTitleCardManager.getInstance()` calls — game-specific, keep as-is
- Any files referencing `CrossGameFeatureProvider` — keep as GameServices/getInstance

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -Dmse=off` — expect BUILD SUCCESS.

- [ ] **Step 3: Run full test suite**

Run: `mvn test -Dmse=off` — expect same baseline results.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/
git commit -m "refactor: migrate S3K objects from GameServices/getInstance to services() (26 files)"
```

---

### Task 8: Verification and Metrics

- [ ] **Step 1: Verify migration completeness**

Run these verification commands from the spec:

```bash
# Context-specific GameServices calls remaining in objects (target: near 0)
grep -rl "GameServices\.camera()\|GameServices\.gameState()\|GameServices\.audio()" \
  --include="*.java" src/main/java/com/openggf/game/sonic*/objects/ \
  src/main/java/com/openggf/level/objects/ | wc -l

# SpriteManager.getInstance() remaining in objects (target: 0)
grep -rl "SpriteManager\.getInstance()" --include="*.java" \
  src/main/java/com/openggf/game/sonic*/objects/ \
  src/main/java/com/openggf/level/objects/ | wc -l

# services() adoption count (target: ~169)
grep -rl "services()\." --include="*.java" \
  src/main/java/com/openggf/game/sonic*/objects/ \
  src/main/java/com/openggf/level/objects/ | wc -l
```

Any remaining GameServices.camera()/gameState()/audio() calls should be constructor-time access (documented exception) or game-mode-only code.

- [ ] **Step 2: Run full test suite one final time**

Run: `mvn test -Dmse=off` — expect same baseline (1920 tests, 1 failure in GHZ1 ROM data, 36 skipped).

- [ ] **Step 3: Final commit if any cleanup needed**

```bash
git add -u
git commit -m "refactor: complete two-tier service architecture migration"
```
