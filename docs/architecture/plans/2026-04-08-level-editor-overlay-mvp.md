# Level Editor Overlay MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first in-engine level editor overlay MVP with hierarchical block/chunk editing, stashed play-test toggling, and focused overlay panes over the live world.

**Architecture:** Extend the new session/mode ownership model instead of reviving the old singleton `LevelEditorManager` design. Keep `WorldSession` durable, make `EditorModeContext` own editor overlay state plus a stashed play-test snapshot, and add a focused `com.openggf.editor` package for hierarchy navigation, derive-new editing, history, and rendering. Integrate incrementally with the existing `MutableLevel` and dirty-region pipeline so the normal `Shift+Tab` toggle is low-hitch.

**Tech Stack:** Java 17, JUnit 5, Maven, LWJGL/OpenGL via existing `GraphicsManager`, existing `MutableLevel`/`LevelManager`/`SessionManager` runtime architecture

---

## File Structure

| File | Responsibility |
|------|----------------|
| `src/main/java/com/openggf/game/session/EditorModeContext.java` | Upgrade stub into real editor-mode owner for cursor, hierarchy state, play-test stash, and mutable-level activation |
| `src/main/java/com/openggf/game/session/EditorCursorState.java` | Continue as immutable cursor record; extend usage, not shape |
| `src/main/java/com/openggf/game/session/EditorPlaytestStash.java` | New immutable stash for player-centric runtime state preserved across edit/play-test toggles |
| `src/main/java/com/openggf/game/session/SessionManager.java` | Replace rebuild-only editor switching with explicit stash/toggle/fresh-start APIs |
| `src/main/java/com/openggf/game/RuntimeManager.java` | Add runtime park/resume behavior so normal editor toggles do not destroy the gameplay runtime |
| `src/main/java/com/openggf/game/GameRuntime.java` | Expose the state capture/apply hooks needed for stashing and cursor-based resume |
| `src/main/java/com/openggf/editor/EditorHierarchyDepth.java` | Enum for `WORLD`, `BLOCK`, `CHUNK` editing depth |
| `src/main/java/com/openggf/editor/EditorSelectionState.java` | Current block/chunk/pattern selection + breadcrumb data |
| `src/main/java/com/openggf/editor/LevelEditorController.java` | Primary editor state coordinator for hierarchy navigation, derive-new operations, and mode-specific actions |
| `src/main/java/com/openggf/editor/EditorHistory.java` | Undo/redo stack over editor mutations |
| `src/main/java/com/openggf/editor/EditorCommand.java` | Small command interface for reversible editor actions |
| `src/main/java/com/openggf/editor/commands/PlaceBlockCommand.java` | Reversible world-level block placement |
| `src/main/java/com/openggf/editor/commands/DeriveBlockFromChunksCommand.java` | Reversible derive-new block mutation |
| `src/main/java/com/openggf/editor/commands/DeriveChunkFromPatternsCommand.java` | Reversible derive-new chunk mutation |
| `src/main/java/com/openggf/editor/EditorInputHandler.java` | Keyboard dispatch for `Tab`, `Enter`, `Esc`, arrows, `Space`, `E`, `Shift+Tab` |
| `src/main/java/com/openggf/editor/render/EditorOverlayRenderer.java` | High-level overlay render orchestration |
| `src/main/java/com/openggf/editor/render/EditorToolbarRenderer.java` | Toolbar with breadcrumb, undo/redo, and play-test commands |
| `src/main/java/com/openggf/editor/render/EditorCommandStripRenderer.java` | Bottom help strip |
| `src/main/java/com/openggf/editor/render/EditorWorldOverlayRenderer.java` | World-space grid and cursor rendering |
| `src/main/java/com/openggf/editor/render/FocusedEditorPaneRenderer.java` | Fade-to-white world treatment plus centered block/chunk edit pane |
| `src/main/java/com/openggf/Engine.java` | Draw editor canvas and overlay when `GameMode.EDITOR` is active |
| `src/main/java/com/openggf/GameLoop.java` | Route input/update for editor mode and `Shift+Tab` toggling |
| `src/test/java/com/openggf/game/session/TestSessionManager.java` | Session-mode toggle semantics |
| `src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java` | Editor lifecycle and stash semantics |
| `src/test/java/com/openggf/editor/TestLevelEditorController.java` | Hierarchy navigation, selection, and breadcrumb tests |
| `src/test/java/com/openggf/editor/TestEditorHistory.java` | Undo/redo behavior |
| `src/test/java/com/openggf/editor/TestEditorCommands.java` | Derive-new commands over `MutableLevel` |
| `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java` | Toggle/resume behavior from cursor and fresh-start behavior |

## Task 1: Session Stash Model

**Files:**
- Create: `src/main/java/com/openggf/game/session/EditorPlaytestStash.java`
- Modify: `src/main/java/com/openggf/game/session/EditorModeContext.java`
- Test: `src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.session;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.RuntimeManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestEditorModeContextLifecycle {

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void editorModeContext_retainsCursorAndPlaytestStash() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        EditorPlaytestStash stash = new EditorPlaytestStash(
                100, 200, 0x0400, -0x0080, true, 53, 2);

        EditorModeContext editor = new EditorModeContext(world, new EditorCursorState(320, 640), stash);

        assertSame(world, editor.getWorldSession());
        assertEquals(320, editor.getCursor().x());
        assertEquals(640, editor.getCursor().y());
        assertNotNull(editor.getPlaytestStash());
        assertEquals(53, editor.getPlaytestStash().rings());
        assertEquals(2, editor.getPlaytestStash().shieldState());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle#editorModeContext_retainsCursorAndPlaytestStash" test`

Expected: FAIL because `EditorPlaytestStash` and the new `EditorModeContext` constructor/getter do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.session;

public record EditorPlaytestStash(
        int playerX,
        int playerY,
        int xVelocity,
        int yVelocity,
        boolean facingRight,
        int rings,
        int shieldState
) {
}
```

```java
package com.openggf.game.session;

import com.openggf.game.GameMode;

import java.util.Objects;

public final class EditorModeContext implements ModeContext {
    private final WorldSession worldSession;
    private EditorCursorState cursor;
    private final EditorPlaytestStash playtestStash;

    public EditorModeContext(WorldSession worldSession,
                             EditorCursorState cursor,
                             EditorPlaytestStash playtestStash) {
        this.worldSession = Objects.requireNonNull(worldSession, "worldSession");
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        this.playtestStash = playtestStash;
    }

    public WorldSession getWorldSession() {
        return worldSession;
    }

    public EditorCursorState getCursor() {
        return cursor;
    }

    public void setCursor(EditorCursorState cursor) {
        this.cursor = Objects.requireNonNull(cursor, "cursor");
    }

    public EditorPlaytestStash getPlaytestStash() {
        return playtestStash;
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

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle#editorModeContext_retainsCursorAndPlaytestStash" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/session/EditorPlaytestStash.java src/main/java/com/openggf/game/session/EditorModeContext.java src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java
git commit -m "feat: add editor playtest stash model"
```

## Task 2: SessionManager Toggle Semantics

**Files:**
- Modify: `src/main/java/com/openggf/game/session/SessionManager.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify: `src/test/java/com/openggf/game/session/TestSessionManager.java`
- Modify: `src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java`

- [ ] **Step 1: Write the failing session tests**

```java
@Test
void enterEditorMode_stashesGameplayStateWithoutDroppingWorld() {
    SessionManager.openGameplaySession(new Sonic2GameModule());

    EditorPlaytestStash stash = new EditorPlaytestStash(100, 200, 12, -4, true, 25, 1);
    EditorModeContext editor = SessionManager.enterEditorMode(new EditorCursorState(128, 256), stash);

    assertSame(SessionManager.getCurrentWorldSession(), editor.getWorldSession());
    assertSame(editor, SessionManager.getCurrentEditorMode());
    assertNull(SessionManager.getCurrentGameplayMode());
    assertEquals(25, editor.getPlaytestStash().rings());
}

@Test
void resumeGameplayFromEditor_reusesStashButAppliesCursorPosition() {
    SessionManager.openGameplaySession(new Sonic2GameModule());
    SessionManager.enterEditorMode(
            new EditorCursorState(320, 640),
            new EditorPlaytestStash(10, 20, 5, 6, true, 42, 2));

    GameplayModeContext gameplay = SessionManager.resumeGameplayFromEditor();

    assertEquals(320, gameplay.getSpawnX());
    assertEquals(640, gameplay.getSpawnY());
    assertEquals(42, gameplay.getResumeStash().rings());
}

@Test
void restartGameplayFromBeginning_discardsEditorStash() {
    SessionManager.openGameplaySession(new Sonic2GameModule());
    SessionManager.enterEditorMode(
            new EditorCursorState(320, 640),
            new EditorPlaytestStash(10, 20, 5, 6, true, 42, 2));

    GameplayModeContext gameplay = SessionManager.restartGameplayFromBeginning();

    assertEquals(0, gameplay.getSpawnX());
    assertEquals(0, gameplay.getSpawnY());
    assertNull(gameplay.getResumeStash());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q "-Dtest=com.openggf.game.session.TestSessionManager,com.openggf.game.session.TestEditorModeContextLifecycle" test`

Expected: FAIL because `enterEditorMode(EditorCursorState, EditorPlaytestStash)`, `resumeGameplayFromEditor()`, `restartGameplayFromBeginning()`, and `GameplayModeContext.getResumeStash()` do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.session;

import com.openggf.game.GameMode;

import java.util.Objects;

public final class GameplayModeContext implements ModeContext {
    private final WorldSession worldSession;
    private final int spawnX;
    private final int spawnY;
    private final EditorPlaytestStash resumeStash;

    public GameplayModeContext(WorldSession worldSession) {
        this(worldSession, 0, 0, null);
    }

    public GameplayModeContext(WorldSession worldSession, int spawnX, int spawnY) {
        this(worldSession, spawnX, spawnY, null);
    }

    public GameplayModeContext(WorldSession worldSession, int spawnX, int spawnY, EditorPlaytestStash resumeStash) {
        this.worldSession = Objects.requireNonNull(worldSession, "worldSession");
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.resumeStash = resumeStash;
    }

    public WorldSession getWorldSession() { return worldSession; }
    public int getSpawnX() { return spawnX; }
    public int getSpawnY() { return spawnY; }
    public EditorPlaytestStash getResumeStash() { return resumeStash; }

    @Override public GameMode getGameMode() { return GameMode.LEVEL; }
    @Override public void destroy() { }
}
```

```java
public static synchronized EditorModeContext enterEditorMode(EditorCursorState cursor, EditorPlaytestStash stash) {
    Objects.requireNonNull(cursor, "cursor");
    if (currentWorldSession == null) {
        throw new IllegalStateException("Cannot enter editor mode without an active world session.");
    }
    destroyCurrentMode();
    currentEditorMode = new EditorModeContext(currentWorldSession, cursor, stash);
    return currentEditorMode;
}

public static synchronized GameplayModeContext resumeGameplayFromEditor() {
    if (currentEditorMode == null) {
        throw new IllegalStateException("Cannot resume gameplay without an active editor mode.");
    }
    EditorCursorState cursor = currentEditorMode.getCursor();
    EditorPlaytestStash stash = currentEditorMode.getPlaytestStash();
    WorldSession worldSession = currentEditorMode.getWorldSession();
    destroyCurrentMode();
    currentGameplayMode = new GameplayModeContext(worldSession, cursor.x(), cursor.y(), stash);
    return currentGameplayMode;
}

public static synchronized GameplayModeContext restartGameplayFromBeginning() {
    if (currentWorldSession == null) {
        throw new IllegalStateException("Cannot restart gameplay without an active world session.");
    }
    destroyCurrentMode();
    currentGameplayMode = new GameplayModeContext(currentWorldSession, 0, 0, null);
    return currentGameplayMode;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.game.session.TestSessionManager,com.openggf.game.session.TestEditorModeContextLifecycle" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/session/SessionManager.java src/main/java/com/openggf/game/session/GameplayModeContext.java src/test/java/com/openggf/game/session/TestSessionManager.java src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java
git commit -m "feat: add stashed editor toggle session APIs"
```

## Task 3: Runtime Park/Resume Hooks

**Files:**
- Modify: `src/main/java/com/openggf/game/GameRuntime.java`
- Modify: `src/main/java/com/openggf/game/RuntimeManager.java`
- Create: `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`

- [ ] **Step 1: Write the failing toggle integration test**

```java
package com.openggf.editor;

import com.openggf.game.RuntimeManager;
import com.openggf.game.session.EditorPlaytestStash;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestEditorToggleIntegration {

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
        SessionManager.clear();
    }

    @Test
    void runtimeManager_reusesParkedRuntimeOnResume() {
        GameplayModeContext gameplay = SessionManager.openGameplaySession(new Sonic2GameModule());
        var runtime = RuntimeManager.createGameplay(gameplay);

        RuntimeManager.parkCurrent(new EditorPlaytestStash(64, 96, 0, 0, true, 12, 1));
        SessionManager.enterEditorMode(new com.openggf.game.session.EditorCursorState(320, 640),
                new EditorPlaytestStash(64, 96, 0, 0, true, 12, 1));

        GameplayModeContext resumed = SessionManager.resumeGameplayFromEditor();
        var resumedRuntime = RuntimeManager.resumeParked(resumed);

        assertSame(runtime, resumedRuntime);
        assertEquals(320, resumedRuntime.getGameplayModeContext().getSpawnX());
        assertEquals(12, resumedRuntime.getGameplayModeContext().getResumeStash().rings());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorToggleIntegration#runtimeManager_reusesParkedRuntimeOnResume" test`

Expected: FAIL because `parkCurrent(...)` and `resumeParked(...)` do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
public final class RuntimeManager {
    private static GameRuntime current;
    private static GameRuntime parked;
    private static GameplayModeContext suppressedGameplayMode;

    public static synchronized void parkCurrent(EditorPlaytestStash stash) {
        if (current == null) {
            return;
        }
        parked = current;
        current = null;
        suppressedGameplayMode = SessionManager.getCurrentGameplayMode();
    }

    public static synchronized GameRuntime resumeParked(GameplayModeContext gameplayMode) {
        if (parked == null) {
            return createGameplay(gameplayMode);
        }
        parked.updateGameplayModeContext(gameplayMode);
        current = parked;
        parked = null;
        suppressedGameplayMode = null;
        return current;
    }
}
```

```java
public final class GameRuntime {
    private GameplayModeContext gameplayMode;

    public GameplayModeContext getGameplayModeContext() { return gameplayMode; }

    public void updateGameplayModeContext(GameplayModeContext gameplayMode) {
        this.gameplayMode = Objects.requireNonNull(gameplayMode, "gameplayMode");
    }
}
```

Add the required `import java.util.Objects;` to `GameRuntime`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorToggleIntegration#runtimeManager_reusesParkedRuntimeOnResume" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/RuntimeManager.java src/main/java/com/openggf/game/GameRuntime.java src/test/java/com/openggf/editor/TestEditorToggleIntegration.java
git commit -m "feat: add runtime park and resume hooks for editor toggles"
```

## Task 4: Editor Controller and Hierarchy State

**Files:**
- Create: `src/main/java/com/openggf/editor/EditorHierarchyDepth.java`
- Create: `src/main/java/com/openggf/editor/EditorSelectionState.java`
- Create: `src/main/java/com/openggf/editor/LevelEditorController.java`
- Create: `src/test/java/com/openggf/editor/TestLevelEditorController.java`

- [ ] **Step 1: Write the failing controller tests**

```java
package com.openggf.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestLevelEditorController {

    @Test
    void controller_descendsAndAscendsHierarchyWithBreadcrumbs() {
        LevelEditorController controller = new LevelEditorController();

        controller.selectBlock(12);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();

        assertEquals(EditorHierarchyDepth.CHUNK, controller.depth());
        assertEquals("World > Block 12 > Chunk 3", controller.breadcrumb());

        controller.ascend();
        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());
        assertEquals("World > Block 12", controller.breadcrumb());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#controller_descendsAndAscendsHierarchyWithBreadcrumbs" test`

Expected: FAIL because the controller and related types do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.editor;

public enum EditorHierarchyDepth {
    WORLD,
    BLOCK,
    CHUNK
}
```

```java
package com.openggf.editor;

public record EditorSelectionState(
        Integer selectedBlock,
        Integer selectedChunk
) {
    public static EditorSelectionState empty() {
        return new EditorSelectionState(null, null);
    }
}
```

```java
package com.openggf.editor;

public final class LevelEditorController {
    private EditorHierarchyDepth depth = EditorHierarchyDepth.WORLD;
    private EditorSelectionState selection = EditorSelectionState.empty();

    public void selectBlock(int blockIndex) {
        selection = new EditorSelectionState(blockIndex, selection.selectedChunk());
    }

    public void selectChunk(int chunkIndex) {
        selection = new EditorSelectionState(selection.selectedBlock(), chunkIndex);
    }

    public void descend() {
        if (depth == EditorHierarchyDepth.WORLD && selection.selectedBlock() != null) {
            depth = EditorHierarchyDepth.BLOCK;
        } else if (depth == EditorHierarchyDepth.BLOCK && selection.selectedChunk() != null) {
            depth = EditorHierarchyDepth.CHUNK;
        }
    }

    public void ascend() {
        if (depth == EditorHierarchyDepth.CHUNK) {
            depth = EditorHierarchyDepth.BLOCK;
        } else if (depth == EditorHierarchyDepth.BLOCK) {
            depth = EditorHierarchyDepth.WORLD;
        }
    }

    public EditorHierarchyDepth depth() {
        return depth;
    }

    public String breadcrumb() {
        if (depth == EditorHierarchyDepth.WORLD) {
            return "World";
        }
        if (depth == EditorHierarchyDepth.BLOCK) {
            return "World > Block " + selection.selectedBlock();
        }
        return "World > Block " + selection.selectedBlock() + " > Chunk " + selection.selectedChunk();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#controller_descendsAndAscendsHierarchyWithBreadcrumbs" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/EditorHierarchyDepth.java src/main/java/com/openggf/editor/EditorSelectionState.java src/main/java/com/openggf/editor/LevelEditorController.java src/test/java/com/openggf/editor/TestLevelEditorController.java
git commit -m "feat: add hierarchical editor controller"
```

## Task 5: Undo/Redo and Reversible Commands

**Files:**
- Create: `src/main/java/com/openggf/editor/EditorCommand.java`
- Create: `src/main/java/com/openggf/editor/EditorHistory.java`
- Create: `src/main/java/com/openggf/editor/commands/PlaceBlockCommand.java`
- Create: `src/main/java/com/openggf/editor/commands/DeriveBlockFromChunksCommand.java`
- Create: `src/main/java/com/openggf/editor/commands/DeriveChunkFromPatternsCommand.java`
- Create: `src/test/java/com/openggf/editor/TestEditorHistory.java`
- Create: `src/test/java/com/openggf/editor/TestEditorCommands.java`

- [ ] **Step 1: Write the failing history test**

```java
package com.openggf.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestEditorHistory {

    @Test
    void history_undoAndRedo_callsCommandHooks() {
        StringBuilder log = new StringBuilder();
        EditorHistory history = new EditorHistory();

        history.execute(new EditorCommand() {
            @Override public void apply() { log.append("A"); }
            @Override public void undo() { log.append("U"); }
        });

        history.undo();
        history.redo();

        assertEquals("AUA", log.toString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorHistory#history_undoAndRedo_callsCommandHooks" test`

Expected: FAIL because `EditorHistory` and `EditorCommand` do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.editor;

public interface EditorCommand {
    void apply();
    void undo();
}
```

```java
package com.openggf.editor;

import java.util.ArrayDeque;
import java.util.Deque;

public final class EditorHistory {
    private final Deque<EditorCommand> undo = new ArrayDeque<>();
    private final Deque<EditorCommand> redo = new ArrayDeque<>();

    public void execute(EditorCommand command) {
        command.apply();
        undo.push(command);
        redo.clear();
    }

    public void undo() {
        if (undo.isEmpty()) return;
        EditorCommand command = undo.pop();
        command.undo();
        redo.push(command);
    }

    public void redo() {
        if (redo.isEmpty()) return;
        EditorCommand command = redo.pop();
        command.apply();
        undo.push(command);
    }
}
```

```java
package com.openggf.editor.commands;

import com.openggf.editor.EditorCommand;
import com.openggf.level.MutableLevel;

public final class PlaceBlockCommand implements EditorCommand {
    private final MutableLevel level;
    private final int layer;
    private final int x;
    private final int y;
    private final int before;
    private final int after;

    public PlaceBlockCommand(MutableLevel level, int layer, int x, int y, int before, int after) {
        this.level = level;
        this.layer = layer;
        this.x = x;
        this.y = y;
        this.before = before;
        this.after = after;
    }

    @Override public void apply() { level.setBlockInMap(layer, x, y, after); }
    @Override public void undo() { level.setBlockInMap(layer, x, y, before); }
}
```

```java
package com.openggf.editor.commands;

import com.openggf.editor.EditorCommand;
import com.openggf.level.ChunkDesc;
import com.openggf.level.MutableLevel;

public final class DeriveBlockFromChunksCommand implements EditorCommand {
    private final MutableLevel level;
    private final int mapLayer;
    private final int mapX;
    private final int mapY;
    private final int sourceBlockIndex;
    private final int derivedBlockIndex;
    private final int[] beforeState;
    private final ChunkDesc replacementChunk;
    private final int replaceX;
    private final int replaceY;

    public DeriveBlockFromChunksCommand(MutableLevel level, int mapLayer, int mapX, int mapY,
                                        int sourceBlockIndex, int derivedBlockIndex,
                                        int[] beforeState, ChunkDesc replacementChunk,
                                        int replaceX, int replaceY) {
        this.level = level;
        this.mapLayer = mapLayer;
        this.mapX = mapX;
        this.mapY = mapY;
        this.sourceBlockIndex = sourceBlockIndex;
        this.derivedBlockIndex = derivedBlockIndex;
        this.beforeState = beforeState;
        this.replacementChunk = replacementChunk;
        this.replaceX = replaceX;
        this.replaceY = replaceY;
    }

    @Override
    public void apply() {
        level.getBlock(derivedBlockIndex).restoreState(beforeState);
        level.setChunkInBlock(derivedBlockIndex, replaceX, replaceY, replacementChunk);
        level.setBlockInMap(mapLayer, mapX, mapY, derivedBlockIndex);
    }

    @Override
    public void undo() {
        level.setBlockInMap(mapLayer, mapX, mapY, sourceBlockIndex);
    }
}
```

```java
package com.openggf.editor.commands;

import com.openggf.editor.EditorCommand;
import com.openggf.level.MutableLevel;
import com.openggf.level.ChunkDesc;
import com.openggf.level.PatternDesc;

public final class DeriveChunkFromPatternsCommand implements EditorCommand {
    private final MutableLevel level;
    private final int blockIndex;
    private final int blockX;
    private final int blockY;
    private final int sourceChunkIndex;
    private final int derivedChunkIndex;
    private final int[] beforeState;
    private final PatternDesc replacementPattern;
    private final int replaceX;
    private final int replaceY;

    public DeriveChunkFromPatternsCommand(MutableLevel level, int blockIndex, int blockX, int blockY,
                                          int sourceChunkIndex, int derivedChunkIndex,
                                          int[] beforeState, PatternDesc replacementPattern,
                                          int replaceX, int replaceY) {
        this.level = level;
        this.blockIndex = blockIndex;
        this.blockX = blockX;
        this.blockY = blockY;
        this.sourceChunkIndex = sourceChunkIndex;
        this.derivedChunkIndex = derivedChunkIndex;
        this.beforeState = beforeState;
        this.replacementPattern = replacementPattern;
        this.replaceX = replaceX;
        this.replaceY = replaceY;
    }

    @Override
    public void apply() {
        level.getChunk(derivedChunkIndex).restoreState(beforeState);
        level.setPatternDescInChunk(derivedChunkIndex, replaceX, replaceY, replacementPattern);
        level.setChunkInBlock(blockIndex, blockX, blockY, new ChunkDesc(derivedChunkIndex));
    }

    @Override
    public void undo() {
        level.setChunkInBlock(blockIndex, blockX, blockY, new ChunkDesc(sourceChunkIndex));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorHistory" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/EditorCommand.java src/main/java/com/openggf/editor/EditorHistory.java src/main/java/com/openggf/editor/commands/PlaceBlockCommand.java src/main/java/com/openggf/editor/commands/DeriveBlockFromChunksCommand.java src/main/java/com/openggf/editor/commands/DeriveChunkFromPatternsCommand.java src/test/java/com/openggf/editor/TestEditorHistory.java src/test/java/com/openggf/editor/TestEditorCommands.java
git commit -m "feat: add editor history and reversible commands"
```

## Task 6: Input Handling and Editor-Mode Update Loop

**Files:**
- Create: `src/main/java/com/openggf/editor/EditorInputHandler.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/test/java/com/openggf/editor/TestLevelEditorController.java`

- [ ] **Step 1: Write the failing input test**

```java
@Test
void inputHandler_mapsEnterAndEscapeToHierarchyNavigation() {
    LevelEditorController controller = new LevelEditorController();
    EditorInputHandler handler = new EditorInputHandler(controller);

    controller.selectBlock(7);
    handler.handleAction(EditorInputHandler.Action.DESCEND);
    assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());

    handler.handleAction(EditorInputHandler.Action.ASCEND);
    assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#inputHandler_mapsEnterAndEscapeToHierarchyNavigation" test`

Expected: FAIL because `EditorInputHandler` and `Action` do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.editor;

public final class EditorInputHandler {
    public enum Action { DESCEND, ASCEND, APPLY, EYEDROP, SWITCH_REGION }

    private final LevelEditorController controller;

    public EditorInputHandler(LevelEditorController controller) {
        this.controller = controller;
    }

    public void handleAction(Action action) {
        switch (action) {
            case DESCEND -> controller.descend();
            case ASCEND -> controller.ascend();
            default -> { }
        }
    }
}
```

In `GameLoop.step()`, replace the current editor-mode early return:

```java
if (currentGameMode == GameMode.EDITOR) {
    Engine.getInstance().getEditorOverlay().update(inputHandler);
    inputHandler.update();
    return;
}
```

Also add a `Shift+Tab` branch before the editor early return:

```java
if (inputHandler.isKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB)
        && (inputHandler.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
        || inputHandler.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT))) {
    Engine.getInstance().toggleEditorPlaytestMode();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/EditorInputHandler.java src/main/java/com/openggf/GameLoop.java src/test/java/com/openggf/editor/TestLevelEditorController.java
git commit -m "feat: wire editor input handling into game loop"
```

## Task 7: Overlay Rendering

**Files:**
- Create: `src/main/java/com/openggf/editor/render/EditorOverlayRenderer.java`
- Create: `src/main/java/com/openggf/editor/render/EditorToolbarRenderer.java`
- Create: `src/main/java/com/openggf/editor/render/EditorCommandStripRenderer.java`
- Create: `src/main/java/com/openggf/editor/render/EditorWorldOverlayRenderer.java`
- Create: `src/main/java/com/openggf/editor/render/FocusedEditorPaneRenderer.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Create: `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java`

- [ ] **Step 1: Write the failing smoke test**

```java
package com.openggf.editor;

import com.openggf.editor.render.FocusedEditorPaneRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TestEditorRenderingSmoke {

    @Test
    void focusedPaneRenderer_buildsWithoutPermanentSidebarAssumptions() {
        assertDoesNotThrow(FocusedEditorPaneRenderer::new);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorRenderingSmoke#focusedPaneRenderer_buildsWithoutPermanentSidebarAssumptions" test`

Expected: FAIL because the renderer types do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.editor.render;

public final class FocusedEditorPaneRenderer {
    public void renderBlockEditorPane() {
        // Render a translucent white wash over the world canvas,
        // then a centered block-editor panel with a navigable grid.
    }

    public void renderChunkEditorPane() {
        // Same composition as block editor, but with pattern-grid content.
    }
}
```

```java
package com.openggf.editor.render;

public final class EditorOverlayRenderer {
    private final EditorToolbarRenderer toolbar = new EditorToolbarRenderer();
    private final EditorCommandStripRenderer commandStrip = new EditorCommandStripRenderer();
    private final EditorWorldOverlayRenderer worldOverlay = new EditorWorldOverlayRenderer();
    private final FocusedEditorPaneRenderer focusedPane = new FocusedEditorPaneRenderer();

    public void renderWorldPlacement() {
        worldOverlay.render();
        toolbar.render();
        commandStrip.render();
    }

    public void renderFocusedBlockEdit() {
        toolbar.render();
        focusedPane.renderBlockEditorPane();
        commandStrip.render();
    }

    public void renderFocusedChunkEdit() {
        toolbar.render();
        focusedPane.renderChunkEditorPane();
        commandStrip.render();
    }
}
```

In `Engine.draw()`, replace the current editor no-op:

```java
if (getCurrentGameMode() == GameMode.EDITOR) {
    camera.setX((short) 0);
    camera.setY((short) 0);
    gameLoop.getEditorOverlay().draw();
    return;
}
```

In `Engine.display()`, keep the existing editor clear color and add the overlay render/flush:

```java
if (getCurrentGameMode() == GameMode.EDITOR) {
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    update();
    draw();
    graphicsManager.flush();
    return;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorRenderingSmoke" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/render/EditorOverlayRenderer.java src/main/java/com/openggf/editor/render/EditorToolbarRenderer.java src/main/java/com/openggf/editor/render/EditorCommandStripRenderer.java src/main/java/com/openggf/editor/render/EditorWorldOverlayRenderer.java src/main/java/com/openggf/editor/render/FocusedEditorPaneRenderer.java src/main/java/com/openggf/Engine.java src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java
git commit -m "feat: add editor overlay render pipeline"
```

## Task 8: MutableLevel Editing Integration

**Files:**
- Modify: `src/main/java/com/openggf/editor/LevelEditorController.java`
- Modify: `src/main/java/com/openggf/editor/commands/PlaceBlockCommand.java`
- Modify: `src/main/java/com/openggf/editor/commands/DeriveBlockFromChunksCommand.java`
- Modify: `src/main/java/com/openggf/editor/commands/DeriveChunkFromPatternsCommand.java`
- Modify: `src/test/java/com/openggf/editor/TestEditorCommands.java`

- [ ] **Step 1: Write the failing command integration tests**

```java
package com.openggf.editor;

import com.openggf.editor.commands.PlaceBlockCommand;
import com.openggf.level.MutableLevel;
import com.openggf.tests.MutableMockLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestEditorCommands {

    @Test
    void placeBlockCommand_mutatesMapAndUndoRestoresPreviousValue() {
        MutableLevel level = MutableLevel.snapshot(new MutableMockLevel());
        int before = Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1));
        PlaceBlockCommand command = new PlaceBlockCommand(level, 0, 1, 1, before, 7);

        command.apply();
        assertEquals(7, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));

        command.undo();
        assertEquals(before, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorCommands#placeBlockCommand_mutatesMapAndUndoRestoresPreviousValue" test`

Expected: FAIL until the command wiring and test fixtures compile together.

- [ ] **Step 3: Write minimal implementation**

```java
public final class LevelEditorController {
    private final EditorHistory history = new EditorHistory();
    private MutableLevel level;

    public void attachLevel(MutableLevel level) {
        this.level = level;
    }

    public void placeBlock(int layer, int x, int y, int blockIndex) {
        int before = Byte.toUnsignedInt(level.getMap().getValue(layer, x, y));
        history.execute(new PlaceBlockCommand(level, layer, x, y, before, blockIndex));
    }

    public void undo() { history.undo(); }
    public void redo() { history.redo(); }
}
```

Keep the derive-new commands narrow for this task:
- derive block by cloning current block state, editing chunk refs, and repointing the selected map cell
- derive chunk by cloning current chunk state, editing pattern refs, and repointing the selected block cell

Do not add object/ring edits in this task.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorCommands,com.openggf.editor.TestEditorHistory" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/LevelEditorController.java src/main/java/com/openggf/editor/commands/PlaceBlockCommand.java src/main/java/com/openggf/editor/commands/DeriveBlockFromChunksCommand.java src/main/java/com/openggf/editor/commands/DeriveChunkFromPatternsCommand.java src/test/java/com/openggf/editor/TestEditorCommands.java
git commit -m "feat: connect editor commands to mutable level edits"
```

## Task 9: Toggle Commands and Fresh Start

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/game/session/SessionManager.java`
- Modify: `src/main/java/com/openggf/game/RuntimeManager.java`
- Modify: `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`

- [ ] **Step 1: Write the failing integration tests**

```java
@Test
void shiftTabToggle_entersEditorWithCursorFromPlayerAndResumesFromCursor() {
    Engine engine = Engine.getInstance();
    SessionManager.openGameplaySession(new Sonic2GameModule());
    RuntimeManager.createGameplay(SessionManager.getCurrentGameplayMode());

    engine.enterEditorFromCurrentPlayer(
            new EditorPlaytestStash(100, 200, 9, -3, true, 47, 1),
            100, 200);

    assertEquals(GameMode.EDITOR, engine.getCurrentGameMode());
    SessionManager.getCurrentEditorMode().setCursor(new EditorCursorState(320, 640));

    engine.resumePlaytestFromEditor();

    assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
    assertEquals(320, SessionManager.getCurrentGameplayMode().getSpawnX());
    assertEquals(640, SessionManager.getCurrentGameplayMode().getSpawnY());
    assertEquals(47, SessionManager.getCurrentGameplayMode().getResumeStash().rings());
}

@Test
void startFromBeginning_ignoresResumeStashAndUsesCanonicalSpawn() {
    Engine engine = Engine.getInstance();
    SessionManager.openGameplaySession(new Sonic2GameModule());
    RuntimeManager.createGameplay(SessionManager.getCurrentGameplayMode());

    engine.enterEditorFromCurrentPlayer(
            new EditorPlaytestStash(100, 200, 9, -3, true, 47, 1),
            100, 200);

    engine.startGameplayFromBeginning();

    assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
    assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnX());
    assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnY());
    assertNull(SessionManager.getCurrentGameplayMode().getResumeStash());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorToggleIntegration" test`

Expected: FAIL until Engine/GameLoop expose helper APIs for testable toggling.

- [ ] **Step 3: Write minimal implementation**

In `Engine`, add small orchestrator methods:

```java
public void enterEditorFromCurrentPlayer(EditorPlaytestStash stash, int playerX, int playerY) {
    RuntimeManager.parkCurrent(stash);
    SessionManager.enterEditorMode(new EditorCursorState(playerX, playerY), stash);
    gameLoop.setCurrentGameMode(GameMode.EDITOR);
}

public void resumePlaytestFromEditor() {
    GameplayModeContext gameplay = SessionManager.resumeGameplayFromEditor();
    RuntimeManager.resumeParked(gameplay);
    gameLoop.setCurrentGameMode(GameMode.LEVEL);
}

public void startGameplayFromBeginning() {
    GameplayModeContext gameplay = SessionManager.restartGameplayFromBeginning();
    RuntimeManager.destroyCurrent();
    RuntimeManager.createGameplay(gameplay);
    gameLoop.setCurrentGameMode(GameMode.LEVEL);
}
```

Add a single `toggleEditorPlaytestMode()` helper that dispatches between those methods based on `GameMode`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorToggleIntegration" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/Engine.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/game/session/SessionManager.java src/main/java/com/openggf/game/RuntimeManager.java src/test/java/com/openggf/editor/TestEditorToggleIntegration.java
git commit -m "feat: add editor playtest toggle and fresh-start commands"
```

## Task 10: Full Verification Pass

**Files:**
- Modify: `docs/architecture/designs/2026-04-08-level-editor-overlay-design.md` (only if the implementation exposed a spec mismatch)
- Modify: `docs/architecture/plans/2026-04-08-level-editor-overlay-mvp.md` (check off completed steps during execution only)

- [ ] **Step 1: Run focused editor/session test suite**

Run: `mvn -q "-Dtest=com.openggf.game.session.TestSessionManager,com.openggf.game.session.TestEditorModeContextLifecycle,com.openggf.editor.TestLevelEditorController,com.openggf.editor.TestEditorHistory,com.openggf.editor.TestEditorCommands,com.openggf.editor.TestEditorToggleIntegration,com.openggf.editor.TestEditorRenderingSmoke" test`

Expected: PASS

- [ ] **Step 2: Run broader regression coverage**

Run: `mvn -q "-Dtest=com.openggf.tests.TestLevelManager,com.openggf.level.TestMutableLevel,com.openggf.game.TestGameRuntime" test`

Expected: PASS

- [ ] **Step 3: Run full Maven test suite**

Run: `mvn test`

Expected: PASS

- [ ] **Step 4: Manual smoke test**

Run: `java -jar target/sonic-engine-0.4.prerelease-jar-with-dependencies.jar`

Expected manual checks:

- load any normal level
- press `Shift+Tab` to enter editor at current player position
- verify world placement overlay appears
- place a block and see the live tilemap update
- descend into block edit and confirm the world fades toward white behind the focused pane
- edit a chunk and return to world
- press `Shift+Tab` to resume play-test from the editor cursor with rings/shield preserved
- use `Start From Beginning` and verify a clean restart from level start

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ship level editor overlay MVP"
```

## Self-Review

### Spec coverage

- hierarchy-first world/block/chunk editing: Tasks 4, 5, 8
- focused overlay pane with faded world: Task 7
- stashed low-hitch play-test toggle: Tasks 1, 2, 3, 9
- `Start From Beginning`: Tasks 2 and 9
- undo/redo: Task 5 and Task 8
- narrow MVP scope without object/ring editing: enforced by Tasks 5, 8, and explicit non-goals

### Placeholder scan

No `TODO`, `TBD`, or "similar to Task N" placeholders remain. Earlier shorthand around the derive-new commands, editor display branch, and toggle tests was expanded into concrete code snippets in the final plan text.

### Type consistency

Core types are consistent across tasks:

- `EditorPlaytestStash`
- `EditorModeContext`
- `GameplayModeContext.getResumeStash()`
- `SessionManager.resumeGameplayFromEditor()`
- `SessionManager.restartGameplayFromBeginning()`
- `RuntimeManager.parkCurrent(...)`
- `RuntimeManager.resumeParked(...)`
- `LevelEditorController`
- `EditorHistory`
