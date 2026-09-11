# Runtime Ownership Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finalize gameplay runtime ownership around a durable `WorldSession`, introduce swappable mode contexts, remove gameplay-time global module/singleton dependence, and ship a stub `EditorModeContext` that proves gameplay/editor switching can preserve the loaded world while rebuilding gameplay state.

**Architecture:** Introduce `EngineContext`, `WorldSession`, and `ModeContext` as first-class ownership layers, then re-scope the current `GameRuntime` responsibilities into a gameplay-mode projection over a persistent world. Keep bootstrap-only globals for pre-session startup, but route gameplay-time access through session-owned services and contexts, with a stub `EditorModeContext` establishing the future editor boundary.

**Tech Stack:** Java 17, Maven, JUnit 5 / Jupiter only, LWJGL engine runtime, existing source-scan architecture guard tests

---

## File Map

### Create

- `src/main/java/com/openggf/game/session/EngineContext.java`
- `src/main/java/com/openggf/game/session/WorldSession.java`
- `src/main/java/com/openggf/game/session/ModeContext.java`
- `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- `src/main/java/com/openggf/game/session/EditorModeContext.java`
- `src/main/java/com/openggf/game/session/EditorCursorState.java`
- `src/main/java/com/openggf/game/session/SessionManager.java`
- `src/test/java/com/openggf/game/session/TestSessionManager.java`
- `src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java`
- `src/test/java/com/openggf/game/TestGameModuleRegistryUsageGuard.java`

### Modify

- `src/test/java/com/openggf/game/sonic3k/objects/TestS3kShieldPriorityParity.java`
- `src/test/java/com/openggf/level/TestGumballTilePriorityMask.java`
- `src/main/java/com/openggf/game/RuntimeManager.java`
- `src/main/java/com/openggf/game/GameRuntime.java`
- `src/main/java/com/openggf/game/GameServices.java`
- `src/main/java/com/openggf/game/GameModuleRegistry.java`
- `src/main/java/com/openggf/game/RomDetectionService.java`
- `src/main/java/com/openggf/Engine.java`
- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/game/GameMode.java`
- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/level/ParallaxManager.java`
- `src/main/java/com/openggf/level/WaterSystem.java`
- `src/main/java/com/openggf/level/rings/RingManager.java`
- `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- `src/main/java/com/openggf/level/objects/ObjectServices.java`
- `src/main/java/com/openggf/graphics/GraphicsManager.java`
- `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- `src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java`
- `src/main/java/com/openggf/level/objects/InvincibilityStarsObjectInstance.java`
- `src/main/java/com/openggf/level/LevelFrameStep.java`
- `src/main/java/com/openggf/debug/DebugObjectArtViewer.java`
- `src/main/java/com/openggf/debug/DebugRenderer.java`
- `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java`
- `src/main/java/com/openggf/game/sonic2/objects/PointPokeyObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/BubbleShieldObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/FireShieldObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/InstaShieldObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/LightningShieldObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java`
- `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`
- `src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java`

### Verification Commands Used Repeatedly

- `mvn -q -DskipTests compile`
- `mvn -q -DskipTests test-compile`
- `mvn -q "-Dtest=com.openggf.game.session.TestSessionManager" test`
- `mvn -q "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle" test`
- `mvn -q "-Dtest=com.openggf.game.TestGameModuleRegistryUsageGuard,com.openggf.game.TestRuntimeSingletonGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard" test`

---

### Task 1: Restore a Working Test-Compile Baseline

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kShieldPriorityParity.java`
- Modify: `src/test/java/com/openggf/level/TestGumballTilePriorityMask.java`

- [ ] **Step 1: Reproduce the current `test-compile` failure**

Run:

```bash
mvn -q -DskipTests test-compile
```

Expected: FAIL with `cannot find symbol` in `TestS3kShieldPriorityParity` and `TestGumballTilePriorityMask`.

- [ ] **Step 2: Fix `TestS3kShieldPriorityParity` package/import resolution**

Replace the file header with:

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.ShieldType;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
```

- [ ] **Step 3: Fix `TestGumballTilePriorityMask` package/import resolution**

Replace the file header with:

```java
package com.openggf.level;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
```

- [ ] **Step 4: Re-run `test-compile`**

Run:

```bash
mvn -q -DskipTests test-compile
```

Expected: PASS.

- [ ] **Step 5: Commit the preflight fix**

Run:

```bash
git add src/test/java/com/openggf/game/sonic3k/objects/TestS3kShieldPriorityParity.java src/test/java/com/openggf/level/TestGumballTilePriorityMask.java
git commit -m "test: fix runtime migration test compile blockers"
```

---

### Task 2: Introduce Session, World, and Mode Containers

**Files:**
- Create: `src/main/java/com/openggf/game/session/EngineContext.java`
- Create: `src/main/java/com/openggf/game/session/WorldSession.java`
- Create: `src/main/java/com/openggf/game/session/ModeContext.java`
- Create: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Create: `src/main/java/com/openggf/game/session/EditorModeContext.java`
- Create: `src/main/java/com/openggf/game/session/EditorCursorState.java`
- Create: `src/main/java/com/openggf/game/session/SessionManager.java`
- Create: `src/test/java/com/openggf/game/session/TestSessionManager.java`

- [ ] **Step 1: Write the failing session-container tests**

Create:

```java
package com.openggf.game.session;

import com.openggf.game.GameMode;
import com.openggf.game.GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestSessionManager {

    @Test
    void openGameplaySession_setsCurrentWorldAndGameplayMode() {
        GameModule module = new Sonic2GameModule();
        GameplayModeContext gameplay = SessionManager.openGameplaySession(module);

        assertNotNull(gameplay);
        assertNotNull(SessionManager.getCurrentWorldSession());
        assertSame(gameplay, SessionManager.getCurrentGameplayMode());
        assertEquals(GameMode.LEVEL, gameplay.getGameMode());
        assertSame(module, SessionManager.getCurrentWorldSession().getGameModule());
    }

    @Test
    void openEditorStub_replacesModeButPreservesWorld() {
        GameplayModeContext gameplay = SessionManager.openGameplaySession(new Sonic2GameModule());
        WorldSession world = SessionManager.getCurrentWorldSession();

        EditorModeContext editor = SessionManager.enterEditorMode(
                new EditorCursorState(128, 256));

        assertNotNull(editor);
        assertSame(world, SessionManager.getCurrentWorldSession());
        assertSame(editor, SessionManager.getCurrentEditorMode());
        assertNull(SessionManager.getCurrentGameplayMode());
        assertNotSame(gameplay, editor);
    }
}
```

- [ ] **Step 2: Run the new test to verify it fails**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.session.TestSessionManager" test
```

Expected: FAIL with missing `SessionManager`, `WorldSession`, `GameplayModeContext`, or `EditorModeContext`.

- [ ] **Step 3: Add the core container types**

Create:

```java
package com.openggf.game.session;

public record EngineContext() {
}
```

```java
package com.openggf.game.session;

public record EditorCursorState(int x, int y) {
}
```

```java
package com.openggf.game.session;

import com.openggf.game.GameMode;

public interface ModeContext {
    GameMode getGameMode();
    void destroy();
}
```

```java
package com.openggf.game.session;

import com.openggf.game.GameModule;

public final class WorldSession {
    private final GameModule gameModule;

    public WorldSession(GameModule gameModule) {
        this.gameModule = gameModule;
    }

    public GameModule getGameModule() {
        return gameModule;
    }
}
```

```java
package com.openggf.game.session;

import com.openggf.game.GameMode;

public final class GameplayModeContext implements ModeContext {
    private final WorldSession worldSession;

    public GameplayModeContext(WorldSession worldSession) {
        this.worldSession = worldSession;
    }

    public WorldSession getWorldSession() {
        return worldSession;
    }

    @Override
    public GameMode getGameMode() {
        return GameMode.LEVEL;
    }

    @Override
    public void destroy() {
    }
}
```

```java
package com.openggf.game.session;

import com.openggf.game.GameMode;

public final class EditorModeContext implements ModeContext {
    private final WorldSession worldSession;
    private final EditorCursorState cursor;

    public EditorModeContext(WorldSession worldSession, EditorCursorState cursor) {
        this.worldSession = worldSession;
        this.cursor = cursor;
    }

    public WorldSession getWorldSession() {
        return worldSession;
    }

    public EditorCursorState getCursor() {
        return cursor;
    }

    @Override
    public GameMode getGameMode() {
        return GameMode.EDITOR;
    }

    @Override
    public void destroy() {
    }
}
```

```java
package com.openggf.game.session;

import com.openggf.game.GameModule;

public final class SessionManager {
    private static WorldSession currentWorldSession;
    private static GameplayModeContext currentGameplayMode;
    private static EditorModeContext currentEditorMode;

    private SessionManager() {
    }

    public static synchronized GameplayModeContext openGameplaySession(GameModule module) {
        currentWorldSession = new WorldSession(module);
        currentEditorMode = null;
        currentGameplayMode = new GameplayModeContext(currentWorldSession);
        return currentGameplayMode;
    }

    public static synchronized EditorModeContext enterEditorMode(EditorCursorState cursor) {
        if (currentGameplayMode != null) {
            currentGameplayMode.destroy();
            currentGameplayMode = null;
        }
        currentEditorMode = new EditorModeContext(currentWorldSession, cursor);
        return currentEditorMode;
    }

    public static synchronized void clear() {
        if (currentGameplayMode != null) currentGameplayMode.destroy();
        if (currentEditorMode != null) currentEditorMode.destroy();
        currentGameplayMode = null;
        currentEditorMode = null;
        currentWorldSession = null;
    }

    public static synchronized WorldSession getCurrentWorldSession() {
        return currentWorldSession;
    }

    public static synchronized GameplayModeContext getCurrentGameplayMode() {
        return currentGameplayMode;
    }

    public static synchronized EditorModeContext getCurrentEditorMode() {
        return currentEditorMode;
    }
}
```

- [ ] **Step 4: Add the new editor enum constant**

Modify `src/main/java/com/openggf/game/GameMode.java`:

```java
public enum GameMode {
    LEVEL,
    TITLE_CARD,
    SPECIAL_STAGE,
    SPECIAL_STAGE_RESULTS,
    TITLE_SCREEN,
    LEVEL_SELECT,
    CREDITS_TEXT,
    CREDITS_DEMO,
    MASTER_TITLE_SCREEN,
    TRY_AGAIN_END,
    ENDING_CUTSCENE,
    BONUS_STAGE,
    EDITOR
}
```

- [ ] **Step 5: Run the session-container tests**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.session.TestSessionManager" test
```

Expected: PASS.

- [ ] **Step 6: Commit the container scaffold**

Run:

```bash
git add src/main/java/com/openggf/game/GameMode.java src/main/java/com/openggf/game/session src/test/java/com/openggf/game/session/TestSessionManager.java
git commit -m "feat: add session world and mode containers"
```

---

### Task 3: Move Active GameModule Ownership Into WorldSession

**Files:**
- Modify: `src/main/java/com/openggf/game/session/WorldSession.java`
- Modify: `src/main/java/com/openggf/game/session/SessionManager.java`
- Modify: `src/main/java/com/openggf/game/GameModuleRegistry.java`
- Modify: `src/main/java/com/openggf/game/RomDetectionService.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Create: `src/test/java/com/openggf/game/TestGameModuleRegistryUsageGuard.java`

- [ ] **Step 1: Write a failing guard that limits `GameModuleRegistry` to bootstrap-only use**

Create:

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

class TestGameModuleRegistryUsageGuard {

    @Test
    void gameplayCodeShouldNotReadGameModuleRegistryDirectly() throws IOException {
        Path srcMain = Path.of("src/main/java");
        List<String> allowed = List.of(
                "src/main/java/com/openggf/game/GameModuleRegistry.java",
                "src/main/java/com/openggf/game/RomDetectionService.java"
        );
        List<String> violations = new ArrayList<>();

        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String normalized = path.toString().replace('\\', '/');
                        if (allowed.contains(normalized)) {
                            return;
                        }
                        String content = Files.readString(path);
                        if (content.contains("GameModuleRegistry.getCurrent(")) {
                            violations.add(normalized);
                        }
                    } catch (IOException ignored) {
                    }
                });

        if (!violations.isEmpty()) {
            fail("Direct gameplay GameModuleRegistry usage remains:\n  " + String.join("\n  ", violations));
        }
    }
}
```

- [ ] **Step 2: Run the guard and verify it fails**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.TestGameModuleRegistryUsageGuard" test
```

Expected: FAIL listing `GameLoop`, `LevelManager`, `ParallaxManager`, `WaterSystem`, and other gameplay files.

- [ ] **Step 3: Promote WorldSession to own the active module and expose it centrally**

Replace `WorldSession` with:

```java
package com.openggf.game.session;

import com.openggf.game.GameModule;

public final class WorldSession {
    private final GameModule gameModule;

    public WorldSession(GameModule gameModule) {
        this.gameModule = gameModule;
    }

    public GameModule getGameModule() {
        return gameModule;
    }
}
```

Extend `SessionManager` with:

```java
public static synchronized GameModule requireCurrentGameModule() {
    if (currentWorldSession == null) {
        throw new IllegalStateException("No active WorldSession");
    }
    return currentWorldSession.getGameModule();
}
```

- [ ] **Step 4: Make `GameModuleRegistry` a compatibility facade over SessionManager**

Modify `GameModuleRegistry` to:

```java
public final class GameModuleRegistry {
    private static GameModule bootstrapDefault = new Sonic2GameModule();

    public static synchronized GameModule getCurrent() {
        var world = com.openggf.game.session.SessionManager.getCurrentWorldSession();
        return world != null ? world.getGameModule() : bootstrapDefault;
    }

    public static synchronized void setCurrent(GameModule module) {
        bootstrapDefault = module != null ? module : bootstrapDefault;
    }

    public static boolean detectAndSetModule(Rom rom) {
        boolean detected = RomDetectionService.getInstance().detectAndSetModule(rom);
        if (!detected) {
            setCurrent(new Sonic2GameModule());
        }
        return detected;
    }

    public static void reset() {
        bootstrapDefault = new Sonic2GameModule();
    }
}
```

- [ ] **Step 5: Bootstrap the session from detected module in `Engine.initializeGame()`**

Replace the runtime bootstrap block with:

```java
Rom rom;
try {
    rom = GameServices.rom().getRom();
} catch (IOException e) {
    throw new RuntimeException("Failed to load ROM during game initialization", e);
}

GameModule module = RomDetectionService.getInstance()
        .detectAndCreateModule(rom)
        .orElseGet(com.openggf.game.sonic2.Sonic2GameModule::new);

com.openggf.game.session.GameplayModeContext gameplay =
        com.openggf.game.session.SessionManager.openGameplaySession(module);
runtime = com.openggf.game.RuntimeManager.createGameplay(gameplay);
gameLoop.setRuntime(runtime);
```

- [ ] **Step 6: Re-run the registry guard**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.TestGameModuleRegistryUsageGuard" test
```

Expected: still FAIL, but now the list is smaller and limited to gameplay consumers that have not yet been migrated.

- [ ] **Step 7: Commit module ownership groundwork**

Run:

```bash
git add src/main/java/com/openggf/game/session/WorldSession.java src/main/java/com/openggf/game/session/SessionManager.java src/main/java/com/openggf/game/GameModuleRegistry.java src/main/java/com/openggf/game/RomDetectionService.java src/main/java/com/openggf/Engine.java src/test/java/com/openggf/game/TestGameModuleRegistryUsageGuard.java
git commit -m "feat: move active game module ownership into world session"
```

---

### Task 4: Re-scope GameRuntime Around GameplayModeContext

**Files:**
- Modify: `src/main/java/com/openggf/game/GameRuntime.java`
- Modify: `src/main/java/com/openggf/game/RuntimeManager.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/test/java/com/openggf/game/session/TestSessionManager.java`

- [ ] **Step 1: Add a failing test for gameplay/runtime compatibility**

Append to `TestSessionManager`:

```java
    @Test
    void runtimeManager_returnsCurrentGameplayContextFacade() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        assertNotNull(com.openggf.game.RuntimeManager.getCurrent());
        assertNotNull(com.openggf.game.RuntimeManager.getCurrent().getWorldSession());
        assertSame(SessionManager.getCurrentWorldSession(),
                com.openggf.game.RuntimeManager.getCurrent().getWorldSession());
    }
```

- [ ] **Step 2: Run the updated test and verify it fails**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.session.TestSessionManager" test
```

Expected: FAIL because `GameRuntime` has no `getWorldSession()` and `RuntimeManager` does not bridge session state.

- [ ] **Step 3: Turn `GameRuntime` into a gameplay facade over `WorldSession`**

Modify `GameRuntime`:

```java
public final class GameRuntime {
    private final com.openggf.game.session.WorldSession worldSession;
    private final com.openggf.game.session.GameplayModeContext gameplayMode;
    // existing manager fields remain for now

    GameRuntime(
            com.openggf.game.session.WorldSession worldSession,
            com.openggf.game.session.GameplayModeContext gameplayMode,
            Camera camera,
            TimerManager timers,
            GameStateManager gameState,
            FadeManager fadeManager,
            WaterSystem waterSystem,
            ParallaxManager parallaxManager,
            TerrainCollisionManager terrainCollisionManager,
            CollisionSystem collisionSystem,
            SpriteManager spriteManager,
            LevelManager levelManager) {
        this.worldSession = worldSession;
        this.gameplayMode = gameplayMode;
        this.camera = camera;
        this.timers = timers;
        this.gameState = gameState;
        this.fadeManager = fadeManager;
        this.waterSystem = waterSystem;
        this.parallaxManager = parallaxManager;
        this.terrainCollisionManager = terrainCollisionManager;
        this.collisionSystem = collisionSystem;
        this.spriteManager = spriteManager;
        this.levelManager = levelManager;
    }

    public com.openggf.game.session.WorldSession getWorldSession() {
        return worldSession;
    }

    public com.openggf.game.session.GameplayModeContext getGameplayModeContext() {
        return gameplayMode;
    }
}
```

- [ ] **Step 4: Make `RuntimeManager` build and expose the current gameplay facade**

Modify `RuntimeManager`:

```java
public static synchronized GameRuntime createGameplay(
        com.openggf.game.session.GameplayModeContext gameplayMode) {
    Camera camera = new Camera();
    TimerManager timers = new TimerManager();
    GameStateManager gameState = new GameStateManager();
    FadeManager fadeManager = new FadeManager();
    WaterSystem waterSystem = new WaterSystem();
    ParallaxManager parallaxManager = new ParallaxManager();
    TerrainCollisionManager terrainCollisionManager = new TerrainCollisionManager();
    CollisionSystem collisionSystem = new CollisionSystem(terrainCollisionManager);
    SpriteManager spriteManager = new SpriteManager();
    LevelManager levelManager = new LevelManager(
            camera, spriteManager, parallaxManager, collisionSystem, waterSystem, gameState);

    current = new GameRuntime(
            gameplayMode.getWorldSession(),
            gameplayMode,
            camera,
            timers,
            gameState,
            fadeManager,
            waterSystem,
            parallaxManager,
            terrainCollisionManager,
            collisionSystem,
            spriteManager,
            levelManager);
    return current;
}
```

- [ ] **Step 5: Keep `GameLoop.setRuntime()` and `Engine.initializeGame()` compiling against the new facade**

Use this `GameLoop` method body:

```java
public void setRuntime(com.openggf.game.GameRuntime runtime) {
    this.runtime = runtime;
    if (runtime != null) {
        this.spriteManager = runtime.getSpriteManager();
        this.camera = runtime.getCamera();
        this.timerManager = runtime.getTimers();
        this.levelManager = runtime.getLevelManager();
        this.gameState = runtime.getGameState();
        this.fadeManager = runtime.getFadeManager();
        this.waterSystem = runtime.getWaterSystem();
    }
}
```

Expected result: no behavior change yet, but the runtime now carries explicit world/mode identity.

- [ ] **Step 6: Re-run the session tests**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.session.TestSessionManager" test
```

Expected: PASS.

- [ ] **Step 7: Commit the runtime/context bridge**

Run:

```bash
git add src/main/java/com/openggf/game/GameRuntime.java src/main/java/com/openggf/game/RuntimeManager.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/Engine.java src/test/java/com/openggf/game/session/TestSessionManager.java
git commit -m "refactor: bridge runtime through gameplay mode context"
```

---

### Task 5: Rewire Services and Migrate Gameplay Module Reads

**Files:**
- Modify: `src/main/java/com/openggf/game/GameServices.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectServices.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/level/LevelFrameStep.java`
- Modify: `src/main/java/com/openggf/level/ParallaxManager.java`
- Modify: `src/main/java/com/openggf/level/WaterSystem.java`
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Modify: `src/main/java/com/openggf/debug/DebugObjectArtViewer.java`
- Modify: `src/main/java/com/openggf/debug/DebugRenderer.java`
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java`
- Modify: `src/main/java/com/openggf/level/objects/InvincibilityStarsObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/PointPokeyObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/BubbleShieldObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/FireShieldObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/InstaShieldObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/LightningShieldObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java`

- [ ] **Step 1: Add session-owned service accessors to `GameServices`**

Extend `GameServices` with:

```java
public static com.openggf.game.session.WorldSession worldSession() {
    GameRuntime rt = requireRuntime("worldSession");
    return rt.getWorldSession();
}

public static GameModule module() {
    return worldSession().getGameModule();
}
```

- [ ] **Step 2: Add world/module accessors to `ObjectServices`**

Extend `ObjectServices`:

```java
default com.openggf.game.session.WorldSession worldSession() { return null; }
default GameModule gameModule() { return null; }
```

Implement in `DefaultObjectServices`:

```java
@Override
public com.openggf.game.session.WorldSession worldSession() {
    GameRuntime runtime = RuntimeManager.getCurrent();
    return runtime != null ? runtime.getWorldSession() : null;
}

@Override
public GameModule gameModule() {
    var world = worldSession();
    return world != null ? world.getGameModule() : null;
}
```

- [ ] **Step 3: Replace top-level `GameModuleRegistry.getCurrent()` reads in gameplay coordination**

Apply this replacement pattern:

```java
GameModule module = GameServices.module();
LevelEventProvider eventProvider = module.getLevelEventProvider();
```

Use it in:

- `GameLoop.java`
- `LevelManager.java`
- `LevelFrameStep.java`
- `ParallaxManager.java`
- `WaterSystem.java`
- `RingManager.java`
- `CrossGameFeatureProvider.java`

- [ ] **Step 4: Replace direct module-registry reads in object-related and rendering helpers**

Apply this replacement pattern:

```java
GameModule module = GameServices.module();
```

or, inside objects:

```java
GameModule module = services().gameModule();
```

Use it in:

- `DefaultPowerUpSpawner.java`
- `InvincibilityStarsObjectInstance.java`
- `Sonic2ObjectArt.java`
- `PointPokeyObjectInstance.java`
- `BubbleShieldObjectInstance.java`
- `FireShieldObjectInstance.java`
- `InstaShieldObjectInstance.java`
- `LightningShieldObjectInstance.java`
- `S3kResultsScreenObjectInstance.java`
- `DebugObjectArtViewer.java`
- `DebugRenderer.java`

- [ ] **Step 5: Re-run the registry guard**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.TestGameModuleRegistryUsageGuard" test
```

Expected: PASS.

- [ ] **Step 6: Commit the service and module-access migration**

Run:

```bash
git add src/main/java/com/openggf/game/GameServices.java src/main/java/com/openggf/level/objects/ObjectServices.java src/main/java/com/openggf/level/objects/DefaultObjectServices.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/level/LevelManager.java src/main/java/com/openggf/level/LevelFrameStep.java src/main/java/com/openggf/level/ParallaxManager.java src/main/java/com/openggf/level/WaterSystem.java src/main/java/com/openggf/level/rings/RingManager.java src/main/java/com/openggf/sprites/managers/SpriteManager.java src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java src/main/java/com/openggf/debug/DebugObjectArtViewer.java src/main/java/com/openggf/debug/DebugRenderer.java src/main/java/com/openggf/game/CrossGameFeatureProvider.java src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java src/main/java/com/openggf/level/objects/InvincibilityStarsObjectInstance.java src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java src/main/java/com/openggf/game/sonic2/objects/PointPokeyObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/BubbleShieldObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/FireShieldObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/InstaShieldObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/LightningShieldObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java src/test/java/com/openggf/game/TestGameModuleRegistryUsageGuard.java
git commit -m "refactor: resolve active game module through world session"
```

---

### Task 6: Remove Gameplay-Time Singleton Rebinding and Fallback Dependence

**Files:**
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/game/GameStateManager.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Modify: `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java`

- [ ] **Step 1: Tighten the runtime guard to reject compatibility hacks**

Update `TestRuntimeSingletonGuard` allowlist by removing:

```java
"Engine.java",
"GameLoop.java",
"ObjectManager.java",
"Sonic2ObjectRegistry.java",
"AizIntroArtLoader.java",
"AizIntroBoosterChild.java",
"AizIntroPaletteCycler.java",
"AizIntroTerrainSwap.java"
```

Keep only true bootstrap or explicit facade files while migrating.

- [ ] **Step 2: Run the guard and verify it fails**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.TestRuntimeSingletonGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard" test
```

Expected: FAIL with remaining singleton access and object-package violations.

- [ ] **Step 3: Remove `GraphicsManager.rebindRuntimeFadeManager()` and initialize fade/camera from current session**

Delete the method and update initialization to:

```java
com.openggf.game.GameRuntime rt = com.openggf.game.RuntimeManager.getCurrent();
this.fadeManager = rt != null ? rt.getFadeManager() : null;
this.camera = rt != null ? rt.getCamera() : null;
if (this.fadeManager != null && fadeShaderProgram != null) {
    this.fadeManager.setFadeShader(fadeShaderProgram);
}
```

Update `Engine.initializeGame()` to remove:

```java
graphicsManager.rebindRuntimeFadeManager();
```

- [ ] **Step 4: Convert core manager accessors to fail fast when misused during active sessions**

Use this pattern in `GameStateManager`, `LevelManager`, and `SpriteManager`:

```java
public static synchronized SpriteManager getInstance() {
    var runtime = RuntimeManager.getCurrent();
    if (runtime != null) {
        return runtime.getSpriteManager();
    }
    if (bootstrapInstance == null) {
        bootstrapInstance = new SpriteManager();
    }
    return bootstrapInstance;
}
```

Then remove gameplay callers that still intentionally rely on the bootstrap path after session creation.

- [ ] **Step 5: Update `AbstractPlayableSprite` and related helpers to read only through runtime-aware accessors**

Use:

```java
public final GameStateManager currentGameState() {
    var runtime = RuntimeManager.getCurrent();
    if (runtime == null) {
        throw new IllegalStateException("currentGameState() requires active gameplay runtime");
    }
    return runtime.getGameState();
}
```

Apply the same fail-fast pattern to:

- `currentCamera()`
- `currentLevelManager()`
- `currentTimerManager()`
- `currentCollisionSystem()`
- `currentWaterSystem()`

- [ ] **Step 6: Re-run the architecture guards**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.TestRuntimeSingletonGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard" test
```

Expected: PASS.

- [ ] **Step 7: Commit fallback cleanup**

Run:

```bash
git add src/main/java/com/openggf/graphics/GraphicsManager.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/game/GameStateManager.java src/main/java/com/openggf/level/LevelManager.java src/main/java/com/openggf/sprites/managers/SpriteManager.java src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java
git commit -m "refactor: remove gameplay singleton fallback dependencies"
```

---

### Task 7: Add Stub EditorModeContext Lifecycle

**Files:**
- Modify: `src/main/java/com/openggf/game/session/SessionManager.java`
- Modify: `src/main/java/com/openggf/game/session/EditorModeContext.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Create: `src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java`

- [ ] **Step 1: Write the failing editor-lifecycle test**

Create:

```java
package com.openggf.game.session;

import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestEditorModeContextLifecycle {

    @Test
    void exitEditorMode_rebuildsFreshGameplayAtCursor() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        EditorModeContext editor = SessionManager.enterEditorMode(new EditorCursorState(320, 640));

        GameplayModeContext gameplay = SessionManager.exitEditorMode();

        assertNotNull(gameplay);
        assertSame(editor.getWorldSession(), gameplay.getWorldSession());
        assertEquals(320, gameplay.getSpawnX());
        assertEquals(640, gameplay.getSpawnY());
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle" test
```

Expected: FAIL because `exitEditorMode()`, `getSpawnX()`, or `getSpawnY()` do not exist.

- [ ] **Step 3: Add cursor-based gameplay rebuild data to `GameplayModeContext`**

Modify `GameplayModeContext`:

```java
public final class GameplayModeContext implements ModeContext {
    private final WorldSession worldSession;
    private final int spawnX;
    private final int spawnY;

    public GameplayModeContext(WorldSession worldSession) {
        this(worldSession, 0, 0);
    }

    public GameplayModeContext(WorldSession worldSession, int spawnX, int spawnY) {
        this.worldSession = worldSession;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
    }

    public int getSpawnX() {
        return spawnX;
    }

    public int getSpawnY() {
        return spawnY;
    }
}
```

- [ ] **Step 4: Add `exitEditorMode()` to `SessionManager`**

Implement:

```java
public static synchronized GameplayModeContext exitEditorMode() {
    if (currentEditorMode == null) {
        throw new IllegalStateException("No active EditorModeContext");
    }
    EditorCursorState cursor = currentEditorMode.getCursor();
    currentEditorMode.destroy();
    currentEditorMode = null;
    currentGameplayMode = new GameplayModeContext(currentWorldSession, cursor.x(), cursor.y());
    return currentGameplayMode;
}
```

- [ ] **Step 5: Add a stub `EDITOR` branch to `GameLoop` and `Engine`**

Use these minimal branches:

```java
if (currentGameMode == GameMode.EDITOR) {
    return;
}
```

and in render dispatch:

```java
} else if (getCurrentGameMode() == GameMode.EDITOR) {
    levelManager.update();
}
```

Goal: compile and keep the stub mode explicit without shipping editor behavior.

- [ ] **Step 6: Re-run the editor lifecycle test**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle" test
```

Expected: PASS.

- [ ] **Step 7: Commit the editor stub lifecycle**

Run:

```bash
git add src/main/java/com/openggf/game/session/SessionManager.java src/main/java/com/openggf/game/session/EditorModeContext.java src/main/java/com/openggf/game/session/GameplayModeContext.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/Engine.java src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java
git commit -m "feat: add stub editor mode lifecycle over world session"
```

---

### Task 8: Final Verification and Guard Closure

**Files:**
- Modify: `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java`
- Modify: `docs/architecture/designs/2026-04-07-runtime-ownership-migration-design.md` only if implementation diverged from design

- [ ] **Step 1: Run production compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: PASS.

- [ ] **Step 2: Run test-compile**

Run:

```bash
mvn -q -DskipTests test-compile
```

Expected: PASS.

- [ ] **Step 3: Run the new session/editor tests**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.session.TestSessionManager,com.openggf.game.session.TestEditorModeContextLifecycle" test
```

Expected: PASS.

- [ ] **Step 4: Run architecture guard tests**

Run:

```bash
mvn -q "-Dtest=com.openggf.game.TestGameModuleRegistryUsageGuard,com.openggf.game.TestRuntimeSingletonGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.tests.TestNoServicesInObjectConstructors" test
```

Expected: PASS.

- [ ] **Step 5: Commit the finished migration**

Run:

```bash
git add src/main/java/com/openggf src/test/java/com/openggf docs/architecture/designs/2026-04-07-runtime-ownership-migration-design.md
git commit -m "refactor: finalize runtime ownership migration"
```

---

## Self-Review

### Spec Coverage

- Session/world/mode split: covered by Tasks 2-4.
- Move active `GameModule` off global process state: covered by Tasks 3 and 5.
- Remove gameplay singleton fallbacks: covered by Task 6.
- Stub editor mode and cursor handoff: covered by Task 7.
- Tighten enforcement and verification: covered by Task 8.

### Placeholder Scan

No `TODO`, `TBD`, "similar to Task", or generic "handle edge cases" placeholders remain. Every task names exact files, commands, and concrete code snippets.

### Type Consistency

- Session ownership is consistently `WorldSession`.
- Gameplay ownership is consistently `GameplayModeContext`.
- Stub editor ownership is consistently `EditorModeContext`.
- The compatibility bridge remains `GameRuntime` until call sites are fully moved.
