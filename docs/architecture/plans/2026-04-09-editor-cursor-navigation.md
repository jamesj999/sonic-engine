# Editor Cursor Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the intended editor navigation loop so editor mode has a free-moving world cursor with camera follow in `WORLD`, composition-grid navigation in `BLOCK` and `CHUNK`, and cursor-based play-test resume.

**Architecture:** Keep `EditorModeContext` as the durable session owner for the current editor cursor and play-test stash, but move interactive navigation behavior into `LevelEditorController`. `EditorInputHandler` becomes depth-aware, `GameLoop` keeps editor updates flowing every frame, `Engine` keeps the controller/session cursor and camera in sync, and the world overlay renders from the current cursor instead of a passive bootstrap-only state.

**Tech Stack:** Java 17, Maven, JUnit 5, LWJGL/OpenGL, existing `GameLoop`/`Engine` editor toggle path, existing `MutableLevel` hierarchy model

---

## File Structure

| File | Responsibility |
|------|----------------|
| `src/main/java/com/openggf/editor/LevelEditorController.java` | Own world cursor position, block/chunk grid selection, hierarchy depth, and navigation methods |
| `src/main/java/com/openggf/editor/EditorInputHandler.java` | Translate held keys into depth-aware world-cursor or grid-navigation actions |
| `src/main/java/com/openggf/game/session/EditorModeContext.java` | Hold mutable current cursor state so editor movement persists across render/toggle paths |
| `src/main/java/com/openggf/Engine.java` | Seed controller cursor on editor entry, sync controller cursor into session state, and drive camera follow while editing |
| `src/main/java/com/openggf/GameLoop.java` | Continue editor-mode updates each frame and keep the existing Shift+Tab toggle behavior intact |
| `src/main/java/com/openggf/editor/render/EditorWorldOverlayRenderer.java` | Render the editor cursor marker from the current synchronized cursor state |
| `src/test/java/com/openggf/editor/TestLevelEditorController.java` | Unit coverage for world cursor movement, grid navigation, and input mapping |
| `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java` | Integration coverage for enter editor, move cursor, camera follow, and resume gameplay from moved cursor |
| `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java` | Smoke coverage proving world overlay command generation still follows the cursor state |

## Task 1: Add Cursor and Grid Navigation State to `LevelEditorController`

**Files:**
- Modify: `src/main/java/com/openggf/editor/LevelEditorController.java`
- Modify: `src/test/java/com/openggf/editor/TestLevelEditorController.java`

- [ ] **Step 1: Write the failing controller tests**

```java
@Test
void controller_movesWorldCursorInWorldDepth() {
    LevelEditorController controller = new LevelEditorController();

    controller.setWorldCursor(new EditorCursorState(320, 448));
    controller.moveWorldCursor(-3, 6);

    assertEquals(317, controller.worldCursor().x());
    assertEquals(454, controller.worldCursor().y());
}

@Test
void controller_arrowNavigationInBlockDepthMovesChunkSelectionInsteadOfWorldCursor() {
    LevelEditorController controller = new LevelEditorController();

    controller.setWorldCursor(new EditorCursorState(320, 448));
    controller.selectBlock(12);
    controller.descend();
    controller.moveActiveSelection(1, 0, 8);

    assertEquals(new EditorCursorState(320, 448), controller.worldCursor());
    assertEquals(1, controller.selectedBlockCellX());
    assertEquals(0, controller.selectedBlockCellY());
}

@Test
void controller_arrowNavigationInChunkDepthMovesPatternSelectionInsteadOfWorldCursor() {
    LevelEditorController controller = new LevelEditorController();

    controller.setWorldCursor(new EditorCursorState(320, 448));
    controller.selectBlock(12);
    controller.descend();
    controller.selectChunk(3);
    controller.descend();
    controller.moveActiveSelection(1, 1, 2);

    assertEquals(new EditorCursorState(320, 448), controller.worldCursor());
    assertEquals(1, controller.selectedChunkCellX());
    assertEquals(1, controller.selectedChunkCellY());
}
```

- [ ] **Step 2: Run the controller tests to verify they fail**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#controller_movesWorldCursorInWorldDepth+controller_arrowNavigationInBlockDepthMovesChunkSelectionInsteadOfWorldCursor+controller_arrowNavigationInChunkDepthMovesPatternSelectionInsteadOfWorldCursor" test`

Expected: FAIL because `setWorldCursor(...)`, `worldCursor()`, `moveWorldCursor(...)`, `moveActiveSelection(...)`, and the grid-selection getters do not exist.

- [ ] **Step 3: Implement the minimal controller state and navigation methods**

Add these imports and members near the top of `LevelEditorController.java`:

```java
import com.openggf.game.session.EditorCursorState;

private EditorCursorState worldCursor = new EditorCursorState(0, 0);
private int blockGridSide = 8;
private int selectedBlockCellX;
private int selectedBlockCellY;
private int selectedChunkCellX;
private int selectedChunkCellY;
```

Add these methods to `LevelEditorController.java`:

```java
public void setWorldCursor(EditorCursorState cursor) {
    this.worldCursor = Objects.requireNonNull(cursor, "cursor");
}

public EditorCursorState worldCursor() {
    return worldCursor;
}

public int blockGridSide() {
    return blockGridSide;
}

public int chunkGridSide() {
    return 2;
}

public void moveWorldCursor(int dx, int dy) {
    worldCursor = new EditorCursorState(worldCursor.x() + dx, worldCursor.y() + dy);
}

public void moveActiveSelection(int dx, int dy, int gridSide) {
    requirePositive(gridSide, "gridSide");
    if (depth == EditorHierarchyDepth.WORLD) {
        moveWorldCursor(dx, dy);
        return;
    }
    if (depth == EditorHierarchyDepth.BLOCK) {
        selectedBlockCellX = clamp(selectedBlockCellX + dx, 0, gridSide - 1);
        selectedBlockCellY = clamp(selectedBlockCellY + dy, 0, gridSide - 1);
        return;
    }
    selectedChunkCellX = clamp(selectedChunkCellX + dx, 0, gridSide - 1);
    selectedChunkCellY = clamp(selectedChunkCellY + dy, 0, gridSide - 1);
}

public int selectedBlockCellX() {
    return selectedBlockCellX;
}

public int selectedBlockCellY() {
    return selectedBlockCellY;
}

public int selectedChunkCellX() {
    return selectedChunkCellX;
}

public int selectedChunkCellY() {
    return selectedChunkCellY;
}

private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
}

private static void requirePositive(int value, String name) {
    if (value <= 0) {
        throw new IllegalArgumentException(name + " must be > 0");
    }
}
```

Reset navigation state inside `attachLevel(...)`:

```java
public void attachLevel(MutableLevel level) {
    this.level = Objects.requireNonNull(level, "level");
    history.clear();
    depth = EditorHierarchyDepth.WORLD;
    selection = EditorSelectionState.empty();
    worldCursor = new EditorCursorState(0, 0);
    blockGridSide = level.getChunksPerBlockSide();
    selectedBlockCellX = 0;
    selectedBlockCellY = 0;
    selectedChunkCellX = 0;
    selectedChunkCellY = 0;
}
```

- [ ] **Step 4: Run the controller tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#controller_movesWorldCursorInWorldDepth+controller_arrowNavigationInBlockDepthMovesChunkSelectionInsteadOfWorldCursor+controller_arrowNavigationInChunkDepthMovesPatternSelectionInsteadOfWorldCursor" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/LevelEditorController.java src/test/java/com/openggf/editor/TestLevelEditorController.java
git commit -m "feat: add editor cursor and grid navigation state"
```

## Task 2: Make `EditorInputHandler` Depth-Aware and Debug-Style in `WORLD`

**Files:**
- Modify: `src/main/java/com/openggf/editor/EditorInputHandler.java`
- Modify: `src/test/java/com/openggf/editor/TestLevelEditorController.java`

- [ ] **Step 1: Write the failing input tests**

Add these tests to `TestLevelEditorController.java`:

```java
@Test
void inputHandler_updateMovesWorldCursorWithHeldArrowKeys() {
    LevelEditorController controller = new LevelEditorController();
    EditorInputHandler handler = new EditorInputHandler(controller);
    InputHandler input = new InputHandler();

    controller.setWorldCursor(new EditorCursorState(100, 200));
    input.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
    input.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);

    handler.update(input);

    assertEquals(103, controller.worldCursor().x());
    assertEquals(203, controller.worldCursor().y());
}

@Test
void inputHandler_updateMovesBlockGridSelectionInsteadOfWorldCursorOutsideWorldDepth() {
    LevelEditorController controller = new LevelEditorController();
    EditorInputHandler handler = new EditorInputHandler(controller);
    InputHandler input = new InputHandler();

    controller.setWorldCursor(new EditorCursorState(100, 200));
    controller.selectBlock(12);
    controller.descend();
    input.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);

    handler.update(input);

    assertEquals(new EditorCursorState(100, 200), controller.worldCursor());
    assertEquals(1, controller.selectedBlockCellX());
    assertEquals(0, controller.selectedBlockCellY());
}
```

- [ ] **Step 2: Run the input tests to verify they fail**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#inputHandler_updateMovesWorldCursorWithHeldArrowKeys+inputHandler_updateMovesBlockGridSelectionInsteadOfWorldCursorOutsideWorldDepth" test`

Expected: FAIL because `EditorInputHandler.update(...)` does not handle arrow keys or depth-aware movement.

- [ ] **Step 3: Implement depth-aware movement in `EditorInputHandler.java`**

Add the arrow-key imports at the top of the file:

```java
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
```

Add a movement constant and replace `update(...)` with:

```java
private static final int WORLD_MOVE_SPEED = 3;

public void update(InputHandler inputHandler) {
    Objects.requireNonNull(inputHandler, "inputHandler");

    int dx = 0;
    int dy = 0;
    if (inputHandler.isKeyDown(GLFW_KEY_LEFT)) {
        dx -= 1;
    }
    if (inputHandler.isKeyDown(GLFW_KEY_RIGHT)) {
        dx += 1;
    }
    if (inputHandler.isKeyDown(GLFW_KEY_UP)) {
        dy -= 1;
    }
    if (inputHandler.isKeyDown(GLFW_KEY_DOWN)) {
        dy += 1;
    }

    if (dx != 0 || dy != 0) {
        if (controller.depth() == EditorHierarchyDepth.WORLD) {
            controller.moveWorldCursor(dx * WORLD_MOVE_SPEED, dy * WORLD_MOVE_SPEED);
        } else if (controller.depth() == EditorHierarchyDepth.BLOCK) {
            controller.moveActiveSelection(dx, dy, controller.blockGridSide());
        } else {
            controller.moveActiveSelection(dx, dy, controller.chunkGridSide());
        }
    }

    if (inputHandler.isKeyPressed(GLFW_KEY_ENTER)) {
        handleAction(Action.DESCEND);
    }
    if (inputHandler.isKeyPressed(GLFW_KEY_ESCAPE)) {
        handleAction(Action.ASCEND);
    }
}
```

Update the action-surface test in `TestLevelEditorController.java` so it still asserts only the public action enum:

```java
assertArrayEquals(
        new EditorInputHandler.Action[] {
                EditorInputHandler.Action.DESCEND,
                EditorInputHandler.Action.ASCEND
        },
        EditorInputHandler.Action.values());
```

- [ ] **Step 4: Run the input tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#inputHandler_updateMovesWorldCursorWithHeldArrowKeys+inputHandler_updateMovesBlockGridSelectionInsteadOfWorldCursorOutsideWorldDepth" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/EditorInputHandler.java src/test/java/com/openggf/editor/TestLevelEditorController.java
git commit -m "feat: add depth-aware editor cursor input"
```

## Task 3: Make `EditorModeContext` Cursor Mutable and Syncable

**Files:**
- Modify: `src/main/java/com/openggf/game/session/EditorModeContext.java`
- Modify: `src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java`

- [ ] **Step 1: Write the failing session-state test**

Add this test to `TestEditorModeContextLifecycle.java`:

```java
@Test
void editorModeContext_cursorCanBeUpdatedAfterEntry() {
    WorldSession world = new WorldSession(new Sonic2GameModule());
    EditorModeContext editor = new EditorModeContext(world, new EditorCursorState(320, 640), null);

    editor.setCursor(new EditorCursorState(400, 768));

    assertEquals(400, editor.getCursor().x());
    assertEquals(768, editor.getCursor().y());
}
```

- [ ] **Step 2: Run the session-state test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle#editorModeContext_cursorCanBeUpdatedAfterEntry" test`

Expected: FAIL because `EditorModeContext` currently stores `cursor` as `final` and exposes no setter.

- [ ] **Step 3: Implement cursor mutation in `EditorModeContext.java`**

Change the field and add the setter:

```java
private EditorCursorState cursor;
```

```java
public void setCursor(EditorCursorState cursor) {
    this.cursor = Objects.requireNonNull(cursor, "cursor");
}
```

Keep the constructor validation:

```java
this.cursor = Objects.requireNonNull(cursor, "cursor");
```

- [ ] **Step 4: Run the session-state test to verify it passes**

Run: `mvn -q "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle#editorModeContext_cursorCanBeUpdatedAfterEntry" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/session/EditorModeContext.java src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java
git commit -m "feat: allow editor cursor state to update in session context"
```

## Task 4: Sync Cursor State and Camera Follow Through `Engine`

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`

- [ ] **Step 1: Write the failing integration tests**

Add these tests to `TestEditorToggleIntegration.java`:

```java
@Test
void syncEditorState_keepsSessionCursorAlignedWithControllerCursor() {
    enableEditor();
    Engine engine = new Engine();
    createGameplayRuntime(engine);

    engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 0, 0, true, 0, 0), 100, 200);
    engine.getLevelEditorController().setWorldCursor(new EditorCursorState(160, 224));
    engine.syncEditorState();

    assertEquals(160, SessionManager.getCurrentEditorMode().getCursor().x());
    assertEquals(224, SessionManager.getCurrentEditorMode().getCursor().y());
}

@Test
void syncEditorState_inWorldDepthMovesCameraToEditorCursor() {
    enableEditor();
    Engine engine = new Engine();
    GameRuntime runtime = createGameplayRuntime(engine);

    engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 0, 0, true, 0, 0), 100, 200);
    engine.getLevelEditorController().setWorldCursor(new EditorCursorState(192, 288));
    engine.syncEditorState();

    assertEquals(192, runtime.getCamera().getX());
    assertEquals(288, runtime.getCamera().getY());
}
```

- [ ] **Step 2: Run the integration tests to verify they fail**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorToggleIntegration#syncEditorState_keepsSessionCursorAlignedWithControllerCursor+syncEditorState_inWorldDepthMovesCameraToEditorCursor" test`

Expected: FAIL because `Engine` does not expose `getLevelEditorController()` or `syncEditorState()`, and editor-mode camera follow is not implemented.

- [ ] **Step 3: Implement cursor seeding and sync helpers in `Engine.java`**

Add these helpers to `Engine.java`:

```java
public LevelEditorController getLevelEditorController() {
    return levelEditorController;
}

public void syncEditorState() {
    EditorModeContext editorMode = SessionManager.getCurrentEditorMode();
    if (editorMode == null) {
        return;
    }

    editorMode.setCursor(levelEditorController.worldCursor());
    editorOverlayRenderer.setHierarchyDepth(levelEditorController.depth());

    if (levelEditorController.depth() == EditorHierarchyDepth.WORLD && camera != null) {
        camera.setX((short) levelEditorController.worldCursor().x());
        camera.setY((short) levelEditorController.worldCursor().y());
    }
}
```

Inside `enterEditorFromCurrentPlayer(...)`, seed the controller immediately after entering editor:

```java
levelEditorController.setWorldCursor(new EditorCursorState(editorX, editorY));
syncEditorState();
```

Inside `resumePlaytestFromEditor()`, use the synchronized cursor source directly before resuming:

```java
syncEditorState();
GameplayModeContext gameplay = SessionManager.resumeGameplayFromEditor();
```

- [ ] **Step 4: Run the integration tests to verify they pass**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorToggleIntegration#syncEditorState_keepsSessionCursorAlignedWithControllerCursor+syncEditorState_inWorldDepthMovesCameraToEditorCursor" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/Engine.java src/test/java/com/openggf/editor/TestEditorToggleIntegration.java
git commit -m "feat: sync editor cursor state and camera follow"
```

## Task 5: Run Editor-State Sync During the Editor Update Loop

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/test/java/com/openggf/editor/TestLevelEditorController.java`

- [ ] **Step 1: Write the failing game-loop integration test**

Add this test to `TestLevelEditorController.java`:

```java
@Test
void gameLoop_editorModeUpdatesCursorMovementBeforeAdvancingInputState() {
    RuntimeManager.createGameplay();
    try {
        InputHandler inputHandler = new InputHandler();
        GameLoop gameLoop = new GameLoop(inputHandler);
        LevelEditorController controller = new LevelEditorController();
        EditorInputHandler editorInputHandler = new EditorInputHandler(controller);

        gameLoop.setEditorInputHandler(editorInputHandler);
        gameLoop.setGameMode(GameMode.EDITOR);
        controller.setWorldCursor(new EditorCursorState(100, 200));
        inputHandler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);

        gameLoop.step();

        assertEquals(103, controller.worldCursor().x());
        assertFalse(inputHandler.isKeyPressed(GLFW_KEY_RIGHT));
    } finally {
        RuntimeManager.destroyCurrent();
    }
}
```

- [ ] **Step 2: Run the game-loop test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#gameLoop_editorModeUpdatesCursorMovementBeforeAdvancingInputState" test`

Expected: FAIL if editor-mode updates are not yet syncing controller movement through the normal frame path.

- [ ] **Step 3: Wire editor sync after input handling**

Add this method to `GameLoop.java`:

```java
private void updateEditorMode() {
    if (editorInputHandler != null) {
        editorInputHandler.update(inputHandler);
    }
    Engine.getInstance().syncEditorState();
}
```

Replace the current editor-mode branch in `step()` with:

```java
if (currentGameMode == GameMode.EDITOR) {
    updateEditorMode();
    inputHandler.update();
    return;
}
```

`GameLoop` can call `Engine.getInstance().syncEditorState()` directly once the method is public.

- [ ] **Step 4: Run the game-loop test to verify it passes**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController#gameLoop_editorModeUpdatesCursorMovementBeforeAdvancingInputState" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/Engine.java src/test/java/com/openggf/editor/TestLevelEditorController.java
git commit -m "feat: run editor cursor sync during editor mode updates"
```

## Task 6: Keep Rendering Aligned With the Current Cursor

**Files:**
- Modify: `src/main/java/com/openggf/editor/render/EditorWorldOverlayRenderer.java`
- Modify: `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java`

- [ ] **Step 1: Write the failing rendering smoke test**

Add this test to `TestEditorRenderingSmoke.java`:

```java
@Test
void worldOverlayRenderer_buildsDifferentCursorCommandsForDifferentCursorPositions() {
    InspectableWorldOverlayRenderer renderer = new InspectableWorldOverlayRenderer();

    List<GLCommand> left = renderer.buildCursorCommands(new EditorCursorState(64, 96));
    List<GLCommand> right = renderer.buildCursorCommands(new EditorCursorState(96, 96));

    assertFalse(left.isEmpty());
    assertFalse(right.isEmpty());
    assertNotEquals(left.get(0).getX1(), right.get(0).getX1());
}
```

- [ ] **Step 2: Run the rendering smoke test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorRenderingSmoke#worldOverlayRenderer_buildsDifferentCursorCommandsForDifferentCursorPositions" test`

Expected: FAIL if the current renderer does not expose stable cursor-driven command differences.

- [ ] **Step 3: Ensure `EditorWorldOverlayRenderer` continues to render from the synchronized session cursor**

Keep `render()` structured like this in `EditorWorldOverlayRenderer.java`:

```java
public void render() {
    EditorModeContext editorMode = SessionManager.getCurrentEditorMode();
    if (editorMode == null) {
        return;
    }
    List<GLCommand> commands = new ArrayList<>();
    appendCursorCommands(commands, editorMode.getCursor());
    if (!commands.isEmpty()) {
        GraphicsManager.getInstance().registerCommand(new GLCommandGroup(GL_LINES, commands));
    }
}
```

Keep `appendCursorCommands(...)` deterministic and cursor-coordinate-based:

```java
protected void appendCursorCommands(List<GLCommand> commands, EditorCursorState cursor) {
    int x = cursor.x();
    int y = cursor.y();
    int outer = 16;
    int inner = 6;

    EditorToolbarRenderer.appendRectOutline(commands, x - outer, y - outer, x + outer, y + outer,
            CURSOR_R, CURSOR_G, CURSOR_B);
    EditorToolbarRenderer.appendLine(commands, x - outer, y, x - inner, y, CURSOR_R, CURSOR_G, CURSOR_B);
    EditorToolbarRenderer.appendLine(commands, x + inner, y, x + outer, y, CURSOR_R, CURSOR_G, CURSOR_B);
    EditorToolbarRenderer.appendLine(commands, x, y - outer, x, y - inner, CURSOR_R, CURSOR_G, CURSOR_B);
    EditorToolbarRenderer.appendLine(commands, x, y + inner, x, y + outer, CURSOR_R, CURSOR_G, CURSOR_B);
}
```

- [ ] **Step 4: Run the rendering smoke test to verify it passes**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorRenderingSmoke#worldOverlayRenderer_buildsDifferentCursorCommandsForDifferentCursorPositions" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/render/EditorWorldOverlayRenderer.java src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java
git commit -m "test: lock editor cursor overlay rendering to cursor state"
```

## Task 7: Prove End-to-End Cursor Movement and Resume Behavior

**Files:**
- Modify: `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Write the failing end-to-end test**

Add this test to `TestEditorToggleIntegration.java`:

```java
@Test
void movedEditorCursor_becomesResumePositionWhenReturningToGameplay() {
    enableEditor();
    Engine engine = new Engine();
    GameRuntime runtime = createGameplayRuntime(engine);
    InputHandler inputHandler = new InputHandler();
    engine.setInputHandler(inputHandler);

    engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 0, 0, true, 0, 0), 100, 200);
    inputHandler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
    inputHandler.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
    engine.getGameLoop().step();

    inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_RELEASE);
    inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
    inputHandler.update();
    inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
    inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
    engine.getGameLoop().step();

    assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
    assertSame(runtime, RuntimeManager.getCurrent());
    assertEquals(103, SessionManager.getCurrentGameplayMode().getSpawnX());
    assertEquals(203, SessionManager.getCurrentGameplayMode().getSpawnY());
}
```

- [ ] **Step 2: Run the end-to-end test to verify it fails**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorToggleIntegration#movedEditorCursor_becomesResumePositionWhenReturningToGameplay" test`

Expected: FAIL because editor cursor movement is not yet flowing all the way through the toggle/resume path.

- [ ] **Step 3: Ensure resume uses the synchronized editor cursor**

Keep `resumePlaytestFromEditor()` in `Engine.java` aligned with the synced session cursor:

```java
public void resumePlaytestFromEditor() {
    syncEditorState();
    GameplayModeContext gameplay = SessionManager.resumeGameplayFromEditor();
    com.openggf.game.GameRuntime resumedRuntime = RuntimeManager.resumeParked(gameplay);
    bindRuntime(resumedRuntime);
    applyResumeState(resumedRuntime, gameplay.getResumeStash().orElse(null));
    gameLoop.setGameMode(GameMode.LEVEL);
}
```

If the method already has that structure, do not broaden it; just keep the `syncEditorState()` call at the top and preserve existing runtime-resume logic.

- [ ] **Step 4: Run the end-to-end test to verify it passes**

Run: `mvn -q "-Dtest=com.openggf.editor.TestEditorToggleIntegration#movedEditorCursor_becomesResumePositionWhenReturningToGameplay" test`

Expected: PASS

- [ ] **Step 5: Run the focused verification set**

Run: `mvn -q "-Dtest=com.openggf.editor.TestLevelEditorController,com.openggf.game.session.TestEditorModeContextLifecycle,com.openggf.editor.TestEditorRenderingSmoke,com.openggf.editor.TestEditorToggleIntegration" test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/Engine.java src/test/java/com/openggf/editor/TestEditorToggleIntegration.java
git commit -m "feat: resume gameplay from moved editor cursor"
```

## Self-Review

### Spec coverage

- Free-moving cursor in `WORLD`: Task 1 and Task 2
- Debug-style fixed-step movement: Task 2
- Grid navigation in `BLOCK` and `CHUNK`: Task 1 and Task 2
- Mutable session cursor: Task 3
- Camera follow in `WORLD`: Task 4 and Task 5
- World overlay remains cursor-driven: Task 6
- Resume gameplay from moved cursor: Task 7

No spec gaps remain for this slice.

### Placeholder scan

- No `TODO`, `TBD`, or deferred implementation markers remain.
- Each code-changing step includes concrete code.
- Every verification step names an exact Maven command and expected result.

### Type consistency

- `LevelEditorController` uses `worldCursor()`, `setWorldCursor(...)`, and `moveActiveSelection(...)` consistently throughout the plan.
- `Engine.syncEditorState()` is introduced once and reused consistently.
- `EditorModeContext.setCursor(...)` matches the session-sync method used later in the plan.
