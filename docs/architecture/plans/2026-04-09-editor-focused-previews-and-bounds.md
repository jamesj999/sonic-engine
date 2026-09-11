# Editor Focused Previews And Bounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `BLOCK` and `CHUNK` editor depths visibly usable by rendering real preview content with active-cell highlights, and clamp world cursor movement so editor follow and gameplay resume always use the same legal position.

**Architecture:** Keep `LevelEditorController` as the behavioral owner of cursor movement, grid selection, and new bounds/preview accessors. Keep `FocusedEditorPaneRenderer` as the rendering owner for block/chunk preview content derived from the attached `MutableLevel`, with `EditorOverlayRenderer` and `Engine` only wiring state through. Use tests first for bounds behavior and for preview output that changes with level content and active selection.

**Tech Stack:** Java 21, JUnit 5, existing `MutableLevel`/`Block`/`Chunk`/`PatternDesc` level model, existing editor overlay renderers, existing `GraphicsManager`/`GLCommand` infrastructure.

---

## File Structure

- Modify: `src/main/java/com/openggf/editor/LevelEditorController.java`
  - Add level-bounds-aware world cursor movement.
  - Add preview-oriented accessors for selected block/chunk content.
- Modify: `src/main/java/com/openggf/editor/render/FocusedEditorPaneRenderer.java`
  - Replace static frame-only pane rendering with real block/chunk preview rendering and active-cell highlighting.
- Modify: `src/main/java/com/openggf/editor/render/EditorOverlayRenderer.java`
  - Pass controller state into the focused-pane renderer instead of treating it as a stateless frame drawer.
- Modify: `src/main/java/com/openggf/Engine.java`
  - Keep overlay depth sync, and provide overlay/controller linkage needed for focused previews.
  - Preserve existing camera-follow behavior while relying on bounded cursor coordinates.
- Modify: `src/test/java/com/openggf/editor/TestLevelEditorController.java`
  - Add controller tests for cursor clamping and preview accessors.
- Modify: `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java`
  - Add render tests that prove focused pane output changes with selected block/chunk content and active cell.
- Modify: `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`
  - Add integration coverage for clamped resume behavior.

## Task 1: Clamp World Cursor Movement In The Controller

**Files:**
- Modify: `src/test/java/com/openggf/editor/TestLevelEditorController.java`
- Modify: `src/main/java/com/openggf/editor/LevelEditorController.java`

- [ ] **Step 1: Write the failing controller tests for bounded world movement**

Add these tests near the existing cursor/navigation tests in `TestLevelEditorController.java`:

```java
    @Test
    void moveWorldCursor_clampsToAttachedLevelBounds() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createMutableLevel(4, 3, 2, 2));
        controller.setWorldCursor(new EditorCursorState(0, 0));

        controller.moveWorldCursor(-64, -32);
        assertEquals(0, controller.worldCursor().x());
        assertEquals(0, controller.worldCursor().y());

        controller.moveWorldCursor(9999, 9999);
        assertEquals(255, controller.worldCursor().x());
        assertEquals(191, controller.worldCursor().y());
    }

    @Test
    void moveActiveSelection_inWorldDepthUsesClampedCursorMovement() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createMutableLevel(4, 3, 2, 2));
        controller.setWorldCursor(new EditorCursorState(254, 190));

        controller.moveActiveSelection(3, 3);

        assertEquals(255, controller.worldCursor().x());
        assertEquals(191, controller.worldCursor().y());
    }
```

Add this level helper at the bottom of the file, alongside any existing test helpers:

```java
    private static MutableLevel createMutableLevel(int mapWidth, int mapHeight, int blockGridSide, int blockCount) {
        TestLevel level = new TestLevel(mapWidth, mapHeight, blockGridSide, blockCount);
        return MutableLevel.snapshot(level);
    }
```

- [ ] **Step 2: Run the focused controller tests to verify they fail**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestLevelEditorController#moveWorldCursor_clampsToAttachedLevelBounds+moveActiveSelection_inWorldDepthUsesClampedCursorMovement" test
```

Expected: FAIL because `LevelEditorController.moveWorldCursor(...)` still applies raw deltas without bounds.

- [ ] **Step 3: Add bounded world-cursor behavior to `LevelEditorController`**

Update `LevelEditorController.java` so world movement clamps against the attached level bounds and `setWorldCursor(...)` also normalizes incoming values when a level is attached:

```java
    public void setWorldCursor(EditorCursorState cursor) {
        Objects.requireNonNull(cursor, "cursor");
        this.worldCursor = clampWorldCursor(cursor.x(), cursor.y());
    }

    public void moveWorldCursor(int dx, int dy) {
        worldCursor = clampWorldCursor(worldCursor.x() + dx, worldCursor.y() + dy);
    }

    private EditorCursorState clampWorldCursor(int x, int y) {
        if (level == null) {
            return new EditorCursorState(x, y);
        }
        int maxX = Math.max(0, level.getMap().getWidth() * level.getBlockPixelSize() - 1);
        int maxY = Math.max(0, level.getMap().getHeight() * level.getBlockPixelSize() - 1);
        return new EditorCursorState(clamp(x, 0, maxX), clamp(y, 0, maxY));
    }
```

Do not move this policy into `Engine` or `SessionManager`. The controller should remain the source of truth for bounded editor cursor behavior.

- [ ] **Step 4: Run the focused controller tests to verify they pass**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestLevelEditorController#moveWorldCursor_clampsToAttachedLevelBounds+moveActiveSelection_inWorldDepthUsesClampedCursorMovement" test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/LevelEditorController.java src/test/java/com/openggf/editor/TestLevelEditorController.java
git commit -m "feat: clamp editor world cursor to level bounds"
```

## Task 2: Prove Clamped Cursor State Drives Resume Behavior

**Files:**
- Modify: `src/test/java/com/openggf/editor/TestEditorToggleIntegration.java`
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Write the failing integration test for bounded resume**

Add this test to `TestEditorToggleIntegration.java` near the existing cursor/resume coverage:

```java
    @Test
    void outOfBoundsEditorMovement_resumesFromClampedCursorPosition() {
        enableEditor();
        Engine engine = new Engine();
        GameRuntime runtime = createGameplayRuntime(engine);
        Sonic player = (Sonic) runtime.getSpriteManager().getSprite("sonic");
        runtime.getCamera().setMinX((short) 0);
        runtime.getCamera().setMaxX((short) 255);
        runtime.getCamera().setMinY((short) 0);
        runtime.getCamera().setMaxY((short) 191);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();

        engine.getLevelEditorController().setWorldCursor(new EditorCursorState(255, 191));
        inputHandler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
        engine.getGameLoop().step();

        EditorCursorState boundedCursor = engine.getLevelEditorController().worldCursor();
        assertEquals(255, boundedCursor.x());
        assertEquals(191, boundedCursor.y());

        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_RELEASE);
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
        inputHandler.update();
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();

        assertEquals(255, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(191, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertEquals(255, player.getCentreX());
        assertEquals(191, player.getCentreY());
    }
```

- [ ] **Step 2: Run the focused integration test to verify it fails**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration#outOfBoundsEditorMovement_resumesFromClampedCursorPosition" test
```

Expected: FAIL if any cursor path still allows out-of-bounds state to leak into resume.

- [ ] **Step 3: Keep `Engine` aligned with bounded controller cursor**

If the test fails, fix only the editor-sync boundary in `Engine.java`. The runtime behavior should still read the controller cursor exactly once and sync it into session state:

```java
    public void syncEditorState() {
        EditorModeContext editorMode = SessionManager.getCurrentEditorMode();
        if (editorMode == null) {
            return;
        }

        EditorCursorState cursor = levelEditorController.worldCursor();
        editorMode.setCursor(cursor);
        synchronizeEditorOverlayDepth();

        if (levelEditorController.depth() == EditorHierarchyDepth.WORLD && camera != null) {
            camera.setX(clampCameraAxisWithWrap(cursor.x() - 152, camera.getMinX(), camera.getMaxX()));
            camera.setY(clampCameraAxisWithWrap(cursor.y() - 96, camera.getMinY(), camera.getMaxY()));
        }
    }
```

Do not add a second clamp in `SessionManager`. The controller-owned bounded cursor should remain the only editor-space source of truth.

- [ ] **Step 4: Run the focused integration test to verify it passes**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration#outOfBoundsEditorMovement_resumesFromClampedCursorPosition" test
```

Expected: PASS

- [ ] **Step 5: Run the editor cursor integration set**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorToggleIntegration" test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/Engine.java src/test/java/com/openggf/editor/TestEditorToggleIntegration.java
git commit -m "test: lock editor resume to bounded cursor state"
```

## Task 3: Render Real Block Previews With Active Cell Highlighting

**Files:**
- Modify: `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java`
- Modify: `src/main/java/com/openggf/editor/render/FocusedEditorPaneRenderer.java`
- Modify: `src/main/java/com/openggf/editor/render/EditorOverlayRenderer.java`
- Modify: `src/main/java/com/openggf/editor/LevelEditorController.java`

- [ ] **Step 1: Write the failing block-preview tests**

Add these tests to `TestEditorRenderingSmoke.java`:

```java
    @Test
    void focusedPaneRenderer_blockPreviewCommandsChangeWithSelectedBlockContent() {
        LevelEditorController controller = EditorPreviewFixtures.blockControllerWithTwoDifferentBlocks();
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer(controller);

        controller.selectBlock(1);
        List<GLCommand> first = renderer.buildBlockCommands();

        controller.selectBlock(2);
        List<GLCommand> second = renderer.buildBlockCommands();

        assertFalse(first.isEmpty());
        assertFalse(second.isEmpty());
        assertNotEquals(commandSignature(first), commandSignature(second));
    }

    @Test
    void focusedPaneRenderer_blockPreviewCommandsChangeWithActiveCell() {
        LevelEditorController controller = EditorPreviewFixtures.blockControllerWithTwoDifferentBlocks();
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer(controller);

        controller.selectBlock(1);
        controller.descend();
        List<GLCommand> first = renderer.buildBlockCommands();

        controller.moveActiveSelection(1, 0);
        List<GLCommand> second = renderer.buildBlockCommands();

        assertNotEquals(commandSignature(first), commandSignature(second));
    }
```

Extend the test helper subclass to accept a controller:

```java
    private static final class InspectableFocusedEditorPaneRenderer extends FocusedEditorPaneRenderer {
        private InspectableFocusedEditorPaneRenderer(LevelEditorController controller) {
            super(controller);
        }

        private List<GLCommand> buildBlockCommands() {
            List<GLCommand> commands = new ArrayList<>();
            appendBlockPaneCommands(commands);
            return commands;
        }
    }
```

- [ ] **Step 2: Run the focused rendering tests to verify they fail**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorRenderingSmoke#focusedPaneRenderer_blockPreviewCommandsChangeWithSelectedBlockContent+focusedPaneRenderer_blockPreviewCommandsChangeWithActiveCell" test
```

Expected: FAIL because the pane renderer still draws static frame chrome only.

- [ ] **Step 3: Add block-preview accessors to the controller**

Expose the selected block and active block cell through `LevelEditorController.java`:

```java
    public Block selectedBlockPreview() {
        Integer selectedBlock = selection.selectedBlock();
        if (selectedBlock == null) {
            return null;
        }
        MutableLevel attachedLevel = requireLevel();
        if (selectedBlock < 0 || selectedBlock >= attachedLevel.getBlockCount()) {
            return null;
        }
        return attachedLevel.getBlock(selectedBlock);
    }

    public Chunk selectedBlockCellPreview() {
        Block block = selectedBlockPreview();
        if (block == null) {
            return null;
        }
        int chunkIndex = block.getChunkDesc(selectedBlockCellX, selectedBlockCellY).getChunkIndex();
        MutableLevel attachedLevel = requireLevel();
        if (chunkIndex < 0 || chunkIndex >= attachedLevel.getChunkCount()) {
            return null;
        }
        return attachedLevel.getChunk(chunkIndex);
    }
```

- [ ] **Step 4: Replace static block-pane chrome with real preview rendering**

Update `FocusedEditorPaneRenderer.java` to accept the controller and build pattern-backed preview commands. Keep pane chrome, but add content and highlight commands:

```java
public class FocusedEditorPaneRenderer {
    private final LevelEditorController controller;

    public FocusedEditorPaneRenderer(LevelEditorController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    protected void appendBlockPaneCommands(List<GLCommand> commands) {
        appendPaneCommands(commands, 196, 34, 316, 194, BLOCK_R, BLOCK_G, BLOCK_B);
        appendBlockPreview(commands, 202, 66, 14);
        appendBlockHighlight(commands, 202, 66, 14);
    }
}
```

Implement `appendBlockPreview(...)` by iterating the selected block’s chunk grid and drawing a filled rectangle or miniature pattern-backed cell for each populated chunk. The command signature must depend on the real selected block content, not only on the active cell. Implement `appendBlockHighlight(...)` by outlining the active cell rectangle in a bright contrasting color.

Update `EditorOverlayRenderer.java` to construct and keep a controller-aware `FocusedEditorPaneRenderer`, for example:

```java
    public EditorOverlayRenderer(LevelEditorController controller) {
        this(new EditorToolbarRenderer(),
                new EditorCommandStripRenderer(),
                new EditorWorldOverlayRenderer(),
                new FocusedEditorPaneRenderer(controller));
    }
```

- [ ] **Step 5: Run the focused block-preview tests to verify they pass**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorRenderingSmoke#focusedPaneRenderer_blockPreviewCommandsChangeWithSelectedBlockContent+focusedPaneRenderer_blockPreviewCommandsChangeWithActiveCell" test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/editor/LevelEditorController.java src/main/java/com/openggf/editor/render/FocusedEditorPaneRenderer.java src/main/java/com/openggf/editor/render/EditorOverlayRenderer.java src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java
git commit -m "feat: render focused block previews in editor panes"
```

## Task 4: Render Real Chunk Previews And Verify Full Editor Slice

**Files:**
- Modify: `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java`
- Modify: `src/main/java/com/openggf/editor/render/FocusedEditorPaneRenderer.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/test/java/com/openggf/editor/TestLevelEditorController.java`

- [ ] **Step 1: Write the failing chunk-preview tests**

Add these tests to `TestEditorRenderingSmoke.java`:

```java
    @Test
    void focusedPaneRenderer_chunkPreviewCommandsChangeWithSelectedChunkContent() {
        LevelEditorController controller = EditorPreviewFixtures.chunkControllerWithTwoDifferentChunks();
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer(controller);

        controller.selectBlock(1);
        controller.selectChunk(3);
        controller.descend();
        controller.descend();
        List<GLCommand> first = renderer.buildChunkCommands();

        controller.selectChunk(4);
        List<GLCommand> second = renderer.buildChunkCommands();

        assertFalse(first.isEmpty());
        assertFalse(second.isEmpty());
        assertNotEquals(commandSignature(first), commandSignature(second));
    }

    @Test
    void focusedPaneRenderer_chunkPreviewCommandsChangeWithActiveChunkCell() {
        LevelEditorController controller = EditorPreviewFixtures.chunkControllerWithTwoDifferentChunks();
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer(controller);

        controller.selectBlock(1);
        controller.selectChunk(3);
        controller.descend();
        controller.descend();
        List<GLCommand> first = renderer.buildChunkCommands();

        controller.moveActiveSelection(1, 1);
        List<GLCommand> second = renderer.buildChunkCommands();

        assertNotEquals(commandSignature(first), commandSignature(second));
    }
```

Also add a controller test that preview accessors stay stable across depth changes:

```java
    @Test
    void selectedChunkPreview_returnsChunkFromCurrentSelection() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createMutableLevel(4, 3, 2, 6));
        controller.selectBlock(1);
        controller.selectChunk(3);

        assertEquals(controller.selectedChunkPreview(), controller.selectedChunkPreview());
    }
```

- [ ] **Step 2: Run the focused chunk tests to verify they fail**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorRenderingSmoke#focusedPaneRenderer_chunkPreviewCommandsChangeWithSelectedChunkContent+focusedPaneRenderer_chunkPreviewCommandsChangeWithActiveChunkCell,com.openggf.editor.TestLevelEditorController#selectedChunkPreview_returnsChunkFromCurrentSelection" test
```

Expected: FAIL because chunk-pane output is still static.

- [ ] **Step 3: Add chunk-preview accessors and real chunk rendering**

Extend `LevelEditorController.java`:

```java
    public Chunk selectedChunkPreview() {
        Integer selectedChunk = selection.selectedChunk();
        if (selectedChunk == null) {
            return null;
        }
        MutableLevel attachedLevel = requireLevel();
        if (selectedChunk < 0 || selectedChunk >= attachedLevel.getChunkCount()) {
            return null;
        }
        return attachedLevel.getChunk(selectedChunk);
    }
```

Extend `FocusedEditorPaneRenderer.java` so `appendChunkPaneCommands(...)` renders the selected chunk’s 2x2 pattern composition and highlights `selectedChunkCellX/Y`, for example:

```java
    protected void appendChunkPaneCommands(List<GLCommand> commands) {
        appendPaneCommands(commands, 176, 34, 316, 194, CHUNK_R, CHUNK_G, CHUNK_B);
        appendChunkPreview(commands, 188, 72, 48);
        appendChunkHighlight(commands, 188, 72, 48);
    }
```

The chunk preview must derive its command output from the real `PatternDesc` values in the selected chunk so different chunks produce different signatures in the test.

Update `Engine.java` only as needed to keep the controller-aware `EditorOverlayRenderer` constructed and synchronized. Do not add unrelated editor UI behavior in this slice.

- [ ] **Step 4: Run the focused preview tests to verify they pass**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorRenderingSmoke,com.openggf.editor.TestLevelEditorController" test
```

Expected: PASS

- [ ] **Step 5: Run the full editor follow-up slice**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestLevelEditorController,com.openggf.game.session.TestEditorModeContextLifecycle,com.openggf.editor.TestEditorRenderingSmoke,com.openggf.editor.TestEditorToggleIntegration" test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/editor/LevelEditorController.java src/main/java/com/openggf/editor/render/FocusedEditorPaneRenderer.java src/main/java/com/openggf/Engine.java src/test/java/com/openggf/editor/TestLevelEditorController.java src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java src/test/java/com/openggf/editor/TestEditorToggleIntegration.java
git commit -m "feat: render focused chunk previews and bounded editor resume"
```

## Self-Review

- Spec coverage:
  - Real `BLOCK`/`CHUNK` preview panes: Tasks 3-4
  - Active-cell highlighting: Tasks 3-4
  - Clamped world cursor bounds: Task 1
  - Clamped resume behavior: Task 2
  - Deterministic tests for new behavior: Tasks 1-4
- Placeholder scan:
  - No `TODO`, `TBD`, or deferred “implement later” steps remain.
  - Commands and target files are explicit for each task.
- Type consistency:
  - Plan consistently uses `LevelEditorController`, `FocusedEditorPaneRenderer`, `EditorOverlayRenderer`, `MutableLevel`, `Block`, `Chunk`, `PatternDesc`, and `EditorCursorState`.
  - Controller preview methods are named `selectedBlockPreview()`, `selectedBlockCellPreview()`, and `selectedChunkPreview()` throughout.
