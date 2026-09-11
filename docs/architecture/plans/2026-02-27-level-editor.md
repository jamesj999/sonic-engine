# Level Editor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add an in-engine level editor mode that allows browsing and placing chunks/blocks on the level grid via keyboard.

**Architecture:** New `GameMode.LEVEL_EDITOR` with a `LevelEditorManager` singleton coordinator. Rendering uses dual coordinate spaces: game-space (scaled viewport) for level tilemap + grid overlay + cursor, and screen-space (1:1 DPI-scaled pixels) for the side panel and tooltip bar. The editor reuses the existing tilemap renderer and `PatternAtlas` for chunk previews.

**Tech Stack:** Java 21, LWJGL/OpenGL (existing), JOML (existing), PixelFont + TexturedQuadRenderer (existing)

**Reference:** Design doc at `docs/architecture/designs/2026-02-27-level-editor-design.md`

---

### Task 1: Add LEVEL_EDITOR GameMode and Block.copy()

Add the enum value and a copy method on Block for later copy-on-write chunk editing.

**Files:**
- Modify: `src/main/java/com/openggf/game/GameMode.java`
- Modify: `src/main/java/com/openggf/level/Block.java`
- Create: `src/test/java/com/openggf/level/TestBlockCopy.java`

**Step 1: Write the failing test for Block.copy()**

```java
package com.openggf.level;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestBlockCopy {

    @Test
    void copyCreatesIndependentBlock() {
        Block original = new Block(8);
        // Set a known chunk desc at position (2,3)
        original.getChunkDesc(2, 3).set(0x1234);

        Block copy = original.copy();

        // Copy has the same value
        assertEquals(0x1234, copy.getChunkDesc(2, 3).get());

        // Modifying copy does not affect original
        copy.getChunkDesc(2, 3).set(0x5678);
        assertEquals(0x1234, original.getChunkDesc(2, 3).get());
        assertEquals(0x5678, copy.getChunkDesc(2, 3).get());
    }

    @Test
    void copiedBlockPreservesGridSide() {
        Block original = new Block(16); // S1-style 16x16 grid
        Block copy = original.copy();
        // Should not throw for valid S1 coordinates
        assertNotNull(copy.getChunkDesc(15, 15));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestBlockCopy -pl .`
Expected: FAIL — `copy()` method does not exist.

**Step 3: Implement Block.copy() and add GameMode.LEVEL_EDITOR**

In `Block.java`, add:
```java
/**
 * Creates a deep copy of this block. Each ChunkDesc is independently copied
 * so modifications to the copy do not affect this block.
 */
public Block copy() {
    Block clone = new Block(this.gridSide);
    for (int i = 0; i < chunkDescs.length; i++) {
        clone.chunkDescs[i] = new ChunkDesc(this.chunkDescs[i].get());
    }
    return clone;
}
```

The `chunkDescs` field must be accessible from within the class — it already is (same class).

In `Block.java`, also expose `gridSide` for the copy (already accessible — same class, private field).

In `GameMode.java`, add at the end before the closing brace:
```java
/** In-engine level editor for placing chunks/blocks */
LEVEL_EDITOR
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestBlockCopy -pl .`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/GameMode.java src/main/java/com/openggf/level/Block.java src/test/java/com/openggf/level/TestBlockCopy.java
git commit -m "feat: add LEVEL_EDITOR GameMode and Block.copy() for copy-on-write editing"
```

---

### Task 2: LevelEditorManager skeleton with focus state and cursor

Create the coordinator singleton with input focus toggling and cursor movement logic.

**Files:**
- Create: `src/main/java/com/openggf/editor/LevelEditorManager.java`
- Create: `src/test/java/com/openggf/editor/TestLevelEditorManager.java`

**Step 1: Write failing tests for focus toggle and cursor movement**

```java
package com.openggf.editor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestLevelEditorManager {

    private LevelEditorManager editor;

    @BeforeEach
    void setUp() {
        editor = new LevelEditorManager();
        // Initialize with a level grid of 128 blocks wide, 16 blocks tall
        // and 256 chunks, 128 blocks (typical S2 level)
        editor.initForLevel(128, 16, 256, 128, 8);
    }

    @Test
    void defaultFocusIsGrid() {
        assertEquals(LevelEditorManager.Focus.GRID, editor.getFocus());
    }

    @Test
    void tabTogglesFocus() {
        editor.toggleFocus();
        assertEquals(LevelEditorManager.Focus.PANEL, editor.getFocus());
        editor.toggleFocus();
        assertEquals(LevelEditorManager.Focus.GRID, editor.getFocus());
    }

    @Test
    void defaultEditModeIsChunk() {
        assertEquals(LevelEditorManager.EditMode.CHUNK, editor.getEditMode());
    }

    @Test
    void switchToBlockMode() {
        editor.setEditMode(LevelEditorManager.EditMode.BLOCK);
        assertEquals(LevelEditorManager.EditMode.BLOCK, editor.getEditMode());
    }

    @Test
    void gridCursorMovesInChunkMode() {
        // Chunk mode: cursor moves in 16px steps
        assertEquals(0, editor.getCursorX());
        assertEquals(0, editor.getCursorY());

        editor.moveCursorRight();
        assertEquals(1, editor.getCursorX()); // 1 chunk unit = 16px

        editor.moveCursorDown();
        assertEquals(1, editor.getCursorY());
    }

    @Test
    void gridCursorClampsToLevelBounds() {
        // Map is 128 blocks wide = 1024 chunks wide (128*8)
        editor.moveCursorLeft(); // Already at 0, should stay
        assertEquals(0, editor.getCursorX());

        editor.moveCursorUp(); // Already at 0, should stay
        assertEquals(0, editor.getCursorY());
    }

    @Test
    void gridCursorInBlockModeMovesInBlockSteps() {
        editor.setEditMode(LevelEditorManager.EditMode.BLOCK);
        editor.moveCursorRight();
        // In block mode, cursor X is in block units
        assertEquals(1, editor.getCursorX());
    }

    @Test
    void panelCursorMoves() {
        editor.toggleFocus(); // Switch to panel
        assertEquals(0, editor.getPanelSelection());

        editor.movePanelSelectionDown();
        assertEquals(1, editor.getPanelSelection());
    }

    @Test
    void panelSelectionClampsToItemCount() {
        editor.toggleFocus();
        editor.movePanelSelectionUp(); // At 0, should stay
        assertEquals(0, editor.getPanelSelection());
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestLevelEditorManager -pl .`
Expected: FAIL — class does not exist.

**Step 3: Implement LevelEditorManager**

```java
package com.openggf.editor;

/**
 * Coordinates the in-engine level editor. Manages cursor position, focus state,
 * edit mode, and panel selection. Does NOT handle rendering (see Task 5+).
 */
public class LevelEditorManager {

    public enum Focus { GRID, PANEL }
    public enum EditMode { CHUNK, BLOCK }

    private Focus focus = Focus.GRID;
    private EditMode editMode = EditMode.CHUNK;

    // Grid cursor position (in chunk units for CHUNK mode, block units for BLOCK mode)
    private int cursorX;
    private int cursorY;

    // Level dimensions
    private int mapWidthBlocks;   // Map width in blocks
    private int mapHeightBlocks;  // Map height in blocks
    private int chunksPerBlock;   // 8 for S2, 16 for S1

    // Panel state
    private int panelSelection;
    private int chunkCount;
    private int blockCount;

    // Singleton
    private static LevelEditorManager instance;

    public LevelEditorManager() {}

    public static LevelEditorManager getInstance() {
        if (instance == null) {
            instance = new LevelEditorManager();
        }
        return instance;
    }

    public static void resetInstance() {
        instance = null;
    }

    public void initForLevel(int mapWidthBlocks, int mapHeightBlocks,
                             int chunkCount, int blockCount, int chunksPerBlock) {
        this.mapWidthBlocks = mapWidthBlocks;
        this.mapHeightBlocks = mapHeightBlocks;
        this.chunkCount = chunkCount;
        this.blockCount = blockCount;
        this.chunksPerBlock = chunksPerBlock;
        this.cursorX = 0;
        this.cursorY = 0;
        this.panelSelection = 0;
        this.focus = Focus.GRID;
        this.editMode = EditMode.CHUNK;
    }

    // --- Focus ---

    public Focus getFocus() { return focus; }

    public void toggleFocus() {
        focus = (focus == Focus.GRID) ? Focus.PANEL : Focus.GRID;
    }

    // --- Edit Mode ---

    public EditMode getEditMode() { return editMode; }

    public void setEditMode(EditMode mode) {
        if (mode == editMode) return;
        // Convert cursor position when switching modes
        if (mode == EditMode.BLOCK && editMode == EditMode.CHUNK) {
            cursorX = cursorX / chunksPerBlock;
            cursorY = cursorY / chunksPerBlock;
        } else if (mode == EditMode.CHUNK && editMode == EditMode.BLOCK) {
            cursorX = cursorX * chunksPerBlock;
            cursorY = cursorY * chunksPerBlock;
        }
        editMode = mode;
    }

    // --- Grid Cursor ---

    public int getCursorX() { return cursorX; }
    public int getCursorY() { return cursorY; }

    /** Cursor position in pixels (for rendering). */
    public int getCursorPixelX() {
        return editMode == EditMode.CHUNK ? cursorX * 16 : cursorX * 128;
    }

    /** Cursor position in pixels (for rendering). */
    public int getCursorPixelY() {
        return editMode == EditMode.CHUNK ? cursorY * 16 : cursorY * 128;
    }

    /** Cursor cell size in pixels (for rendering). */
    public int getCursorCellSize() {
        return editMode == EditMode.CHUNK ? 16 : chunksPerBlock * 16;
    }

    private int maxCursorX() {
        return editMode == EditMode.CHUNK
                ? mapWidthBlocks * chunksPerBlock - 1
                : mapWidthBlocks - 1;
    }

    private int maxCursorY() {
        return editMode == EditMode.CHUNK
                ? mapHeightBlocks * chunksPerBlock - 1
                : mapHeightBlocks - 1;
    }

    public void moveCursorLeft()  { cursorX = Math.max(0, cursorX - 1); }
    public void moveCursorRight() { cursorX = Math.min(maxCursorX(), cursorX + 1); }
    public void moveCursorUp()    { cursorY = Math.max(0, cursorY - 1); }
    public void moveCursorDown()  { cursorY = Math.min(maxCursorY(), cursorY + 1); }

    // --- Panel Selection ---

    public int getPanelSelection() { return panelSelection; }

    private int panelItemCount() {
        return editMode == EditMode.CHUNK ? chunkCount : blockCount;
    }

    public void movePanelSelectionUp() {
        panelSelection = Math.max(0, panelSelection - 1);
    }

    public void movePanelSelectionDown() {
        panelSelection = Math.min(panelItemCount() - 1, panelSelection + 1);
    }

    public void movePanelSelectionPageUp(int pageSize) {
        panelSelection = Math.max(0, panelSelection - pageSize);
    }

    public void movePanelSelectionPageDown(int pageSize) {
        panelSelection = Math.min(panelItemCount() - 1, panelSelection + pageSize);
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TestLevelEditorManager -pl .`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/LevelEditorManager.java src/test/java/com/openggf/editor/TestLevelEditorManager.java
git commit -m "feat: add LevelEditorManager with focus state, cursor, and panel selection"
```

---

### Task 3: Edit operations — place, clear, eyedropper

Add the data-manipulation methods: place block, place chunk (with copy-on-write), clear, eyedropper.

**Files:**
- Modify: `src/main/java/com/openggf/editor/LevelEditorManager.java`
- Create: `src/test/java/com/openggf/editor/TestLevelEditorOperations.java`

**Step 1: Write failing tests**

```java
package com.openggf.editor;

import com.openggf.level.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestLevelEditorOperations {

    private LevelEditorManager editor;
    private Map map;
    private Block[] blocks;
    private Chunk[] chunks;

    @BeforeEach
    void setUp() {
        editor = new LevelEditorManager();
        // Create a small test level: 4x4 blocks, 8 chunks per block side
        map = new Map(2, 4, 4); // 2 layers, 4 wide, 4 tall
        map.setValue(0, 0, 0, (byte) 0); // Block 0 at (0,0)

        blocks = new Block[4];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new Block(8);
        }
        // Set a known chunk in block 1
        blocks[1].getChunkDesc(0, 0).set(0x0042);

        chunks = new Chunk[64];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = new Chunk();
        }

        editor.initForLevel(4, 4, chunks.length, blocks.length, 8);
    }

    @Test
    void placeBlockChangesMapCell() {
        editor.setEditMode(LevelEditorManager.EditMode.BLOCK);
        editor.moveCursorRight(); // Move to (1, 0)

        editor.placeBlock(map, 2); // Place block index 2

        assertEquals(2, map.getValue(0, 1, 0) & 0xFF);
    }

    @Test
    void clearBlockSetsToZero() {
        map.setValue(0, 1, 1, (byte) 5);
        editor.setEditMode(LevelEditorManager.EditMode.BLOCK);
        editor.moveCursorRight();
        editor.moveCursorDown();

        editor.clearCell(map, blocks);

        assertEquals(0, map.getValue(0, 1, 1) & 0xFF);
    }

    @Test
    void eyedropperInBlockModeSelectsBlockIndex() {
        map.setValue(0, 2, 0, (byte) 3);
        editor.setEditMode(LevelEditorManager.EditMode.BLOCK);
        editor.moveCursorRight();
        editor.moveCursorRight(); // At (2, 0)

        editor.eyedropper(map, blocks);

        assertEquals(3, editor.getPanelSelection());
    }

    @Test
    void placeChunkUseCopyOnWrite() {
        // Block 0 is at map position (0,0)
        map.setValue(0, 0, 0, (byte) 0);
        editor.setEditMode(LevelEditorManager.EditMode.CHUNK);
        // Cursor at (0,0) = chunk (0,0) within block at map (0,0)

        int originalBlockCount = blocks.length;
        Block[] expandedBlocks = editor.placeChunk(map, blocks, 42);

        // Should have created a new block (copy-on-write)
        assertEquals(originalBlockCount + 1, expandedBlocks.length);

        // Map cell should now point to the new block
        int newBlockIndex = map.getValue(0, 0, 0) & 0xFF;
        assertEquals(originalBlockCount, newBlockIndex);

        // The new block's chunk at (0,0) should have the placed chunk index
        assertEquals(42, expandedBlocks[newBlockIndex].getChunkDesc(0, 0).getChunkIndex());
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestLevelEditorOperations -pl .`
Expected: FAIL — methods do not exist.

**Step 3: Implement edit operations**

Add to `LevelEditorManager.java`:

```java
/**
 * Place a block at the current cursor position (BLOCK mode).
 * Directly sets the map cell to the given block index.
 */
public void placeBlock(Map map, int blockIndex) {
    int bx = cursorX;
    int by = cursorY;
    map.setValue(0, bx, by, (byte) blockIndex);
}

/**
 * Place a chunk at the current cursor position (CHUNK mode).
 * Uses copy-on-write: clones the block at this position, modifies the chunk,
 * appends the new block to the array, and updates the map cell.
 *
 * @return the (possibly expanded) block array
 */
public Block[] placeChunk(Map map, Block[] blocks, int chunkIndex) {
    // Determine which block and which chunk within it
    int blockX = cursorX / chunksPerBlock;
    int blockY = cursorY / chunksPerBlock;
    int chunkLocalX = cursorX % chunksPerBlock;
    int chunkLocalY = cursorY % chunksPerBlock;

    int currentBlockIndex = map.getValue(0, blockX, blockY) & 0xFF;
    Block original = blocks[currentBlockIndex];

    // Copy-on-write
    Block clone = original.copy();
    clone.getChunkDesc(chunkLocalX, chunkLocalY).set(chunkIndex);

    // Append to blocks array
    Block[] expanded = java.util.Arrays.copyOf(blocks, blocks.length + 1);
    expanded[blocks.length] = clone;

    // Update map cell to point to new block
    map.setValue(0, blockX, blockY, (byte) blocks.length);

    return expanded;
}

/**
 * Clear the cell at the current cursor position (set to index 0).
 */
public void clearCell(Map map, Block[] blocks) {
    if (editMode == EditMode.BLOCK) {
        map.setValue(0, cursorX, cursorY, (byte) 0);
    } else {
        int blockX = cursorX / chunksPerBlock;
        int blockY = cursorY / chunksPerBlock;
        int chunkLocalX = cursorX % chunksPerBlock;
        int chunkLocalY = cursorY % chunksPerBlock;
        int blockIndex = map.getValue(0, blockX, blockY) & 0xFF;
        blocks[blockIndex].getChunkDesc(chunkLocalX, chunkLocalY).set(0);
    }
}

/**
 * Eyedropper: read the chunk/block under the cursor and set it as the panel selection.
 */
public void eyedropper(Map map, Block[] blocks) {
    if (editMode == EditMode.BLOCK) {
        panelSelection = map.getValue(0, cursorX, cursorY) & 0xFF;
    } else {
        int blockX = cursorX / chunksPerBlock;
        int blockY = cursorY / chunksPerBlock;
        int chunkLocalX = cursorX % chunksPerBlock;
        int chunkLocalY = cursorY % chunksPerBlock;
        int blockIndex = map.getValue(0, blockX, blockY) & 0xFF;
        panelSelection = blocks[blockIndex].getChunkDesc(chunkLocalX, chunkLocalY).getChunkIndex();
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TestLevelEditorOperations -pl .`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/LevelEditorManager.java src/test/java/com/openggf/editor/TestLevelEditorOperations.java
git commit -m "feat: add level editor place/clear/eyedropper operations with copy-on-write"
```

---

### Task 4: EditorInputHandler — keyboard dispatch

Wire keyboard input to LevelEditorManager actions. This is a thin dispatcher that reads `InputHandler` state and calls the appropriate manager methods.

**Files:**
- Create: `src/main/java/com/openggf/editor/EditorInputHandler.java`

**Step 1: Implement EditorInputHandler**

```java
package com.openggf.editor;

import com.openggf.control.InputHandler;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Translates keyboard input into level editor actions.
 * Called each frame from the editor's update loop.
 */
public class EditorInputHandler {

    private static final int KEY_PLACE = GLFW_KEY_SPACE;
    private static final int KEY_CLEAR = GLFW_KEY_DELETE;
    private static final int KEY_EYEDROP = GLFW_KEY_E;
    private static final int KEY_TOGGLE_FOCUS = GLFW_KEY_TAB;
    private static final int KEY_BLOCK_MODE = GLFW_KEY_B;
    private static final int KEY_CHUNK_MODE = GLFW_KEY_C;
    private static final int KEY_CONFIRM = GLFW_KEY_ENTER;
    private static final int KEY_PAGE_UP = GLFW_KEY_PAGE_UP;
    private static final int KEY_PAGE_DOWN = GLFW_KEY_PAGE_DOWN;

    private final LevelEditorManager editor;

    public EditorInputHandler(LevelEditorManager editor) {
        this.editor = editor;
    }

    /**
     * Process one frame of input. Returns an action if a data-mutating
     * operation was requested, or null for navigation-only input.
     */
    public EditorAction update(InputHandler input) {
        // Mode switches (available in both focus states)
        if (input.isKeyPressed(KEY_BLOCK_MODE)) {
            editor.setEditMode(LevelEditorManager.EditMode.BLOCK);
        }
        if (input.isKeyPressed(KEY_CHUNK_MODE)) {
            editor.setEditMode(LevelEditorManager.EditMode.CHUNK);
        }
        if (input.isKeyPressed(KEY_TOGGLE_FOCUS)) {
            editor.toggleFocus();
        }

        if (editor.getFocus() == LevelEditorManager.Focus.GRID) {
            return updateGridInput(input);
        } else {
            updatePanelInput(input);
            return null;
        }
    }

    private EditorAction updateGridInput(InputHandler input) {
        // Navigation
        if (input.isKeyPressed(GLFW_KEY_LEFT)) editor.moveCursorLeft();
        if (input.isKeyPressed(GLFW_KEY_RIGHT)) editor.moveCursorRight();
        if (input.isKeyPressed(GLFW_KEY_UP)) editor.moveCursorUp();
        if (input.isKeyPressed(GLFW_KEY_DOWN)) editor.moveCursorDown();

        // Actions
        if (input.isKeyPressed(KEY_PLACE) || input.isKeyPressed(KEY_CONFIRM)) {
            return EditorAction.PLACE;
        }
        if (input.isKeyPressed(KEY_CLEAR)) {
            return EditorAction.CLEAR;
        }
        if (input.isKeyPressed(KEY_EYEDROP)) {
            return EditorAction.EYEDROP;
        }

        return null;
    }

    private void updatePanelInput(InputHandler input) {
        if (input.isKeyPressed(GLFW_KEY_UP)) editor.movePanelSelectionUp();
        if (input.isKeyPressed(GLFW_KEY_DOWN)) editor.movePanelSelectionDown();
        if (input.isKeyPressed(KEY_PAGE_UP)) editor.movePanelSelectionPageUp(8);
        if (input.isKeyPressed(KEY_PAGE_DOWN)) editor.movePanelSelectionPageDown(8);

        // Confirm selection and return to grid
        if (input.isKeyPressed(KEY_CONFIRM)) {
            editor.toggleFocus();
        }
    }

    public enum EditorAction {
        PLACE, CLEAR, EYEDROP
    }
}
```

No unit test for this class — it's a thin input dispatcher tested through integration.

**Step 2: Commit**

```bash
git add src/main/java/com/openggf/editor/EditorInputHandler.java
git commit -m "feat: add EditorInputHandler for keyboard-to-action dispatch"
```

---

### Task 5: Grid overlay renderer

Render chunk boundary lines (fine) and block boundary lines (thick) in game-space, plus the cursor highlight.

**Files:**
- Create: `src/main/java/com/openggf/editor/GridOverlayRenderer.java`

**Step 1: Implement GridOverlayRenderer**

This renders using `GLCommandable` so it integrates with the existing `GraphicsManager.registerCommand()` pipeline.

```java
package com.openggf.editor;

import com.openggf.graphics.GLCommandable;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renders grid overlay lines and cursor highlight in game-space.
 * Registered as a GLCommandable so it participates in the camera-aware flush.
 */
public class GridOverlayRenderer implements GLCommandable {

    private final LevelEditorManager editor;

    public GridOverlayRenderer(LevelEditorManager editor) {
        this.editor = editor;
    }

    @Override
    public void execute(short cameraX, short cameraY, short cameraWidth, short cameraHeight) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(0); // Fixed-function pipeline for simple lines/quads

        // Set up model-view matrix with camera offset
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        // Flip Y for top-down coordinates and apply camera offset
        glTranslatef(-cameraX, -(cameraHeight - 1) + cameraY, 0);
        glScalef(1, -1, 0);

        drawCursorHighlight();
        drawChunkGrid(cameraX, cameraY, cameraWidth, cameraHeight);
        drawBlockGrid(cameraX, cameraY, cameraWidth, cameraHeight);

        glPopMatrix();
        glDisable(GL_BLEND);
    }

    private void drawCursorHighlight() {
        int px = editor.getCursorPixelX();
        int py = editor.getCursorPixelY();
        int size = editor.getCursorCellSize();

        boolean gridFocused = editor.getFocus() == LevelEditorManager.Focus.GRID;

        // Semi-transparent fill
        if (gridFocused) {
            glColor4f(0.2f, 0.6f, 1.0f, 0.25f); // Blue tint when grid focused
        } else {
            glColor4f(1.0f, 0.6f, 0.2f, 0.15f); // Orange tint when panel focused
        }
        glBegin(GL_QUADS);
        glVertex2f(px, py);
        glVertex2f(px + size, py);
        glVertex2f(px + size, py + size);
        glVertex2f(px, py + size);
        glEnd();

        // Bright outline
        if (gridFocused) {
            glColor4f(0.3f, 0.7f, 1.0f, 0.9f);
        } else {
            glColor4f(1.0f, 0.7f, 0.3f, 0.6f);
        }
        glLineWidth(2.0f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(px, py);
        glVertex2f(px + size, py);
        glVertex2f(px + size, py + size);
        glVertex2f(px, py + size);
        glEnd();
    }

    private void drawChunkGrid(short camX, short camY, short camW, short camH) {
        glColor4f(1.0f, 1.0f, 1.0f, 0.15f); // Subtle white, 15% opacity
        glLineWidth(1.0f);
        glBegin(GL_LINES);

        // Vertical lines at 16px intervals
        int startX = (camX / 16) * 16;
        int endX = camX + camW + 16;
        for (int x = startX; x <= endX; x += 16) {
            glVertex2f(x, camY);
            glVertex2f(x, camY + camH);
        }

        // Horizontal lines at 16px intervals
        int startY = (camY / 16) * 16;
        int endY = camY + camH + 16;
        for (int y = startY; y <= endY; y += 16) {
            glVertex2f(camX, y);
            glVertex2f(camX + camW, y);
        }

        glEnd();
    }

    private void drawBlockGrid(short camX, short camY, short camW, short camH) {
        int blockSize = editor.getCursorCellSize() > 16 ? editor.getCursorCellSize() : 128;
        // Always draw block grid at 128px regardless of edit mode

        glColor4f(1.0f, 1.0f, 1.0f, 0.35f); // Brighter white, 35% opacity
        glLineWidth(2.0f);
        glBegin(GL_LINES);

        int startX = (camX / 128) * 128;
        int endX = camX + camW + 128;
        for (int x = startX; x <= endX; x += 128) {
            glVertex2f(x, camY);
            glVertex2f(x, camY + camH);
        }

        int startY = (camY / 128) * 128;
        int endY = camY + camH + 128;
        for (int y = startY; y <= endY; y += 128) {
            glVertex2f(camX, y);
            glVertex2f(camX + camW, y);
        }

        glEnd();
    }
}
```

**Note:** The exact camera transform (`glTranslatef`/`glScalef`) may need adjustment based on how the existing debug overlay renderer handles Y-flip. Check `DebugOverlayManager` or the existing collision debug renderer for the correct transform. The code above uses a common pattern; verify at integration time.

**Step 2: Commit**

```bash
git add src/main/java/com/openggf/editor/GridOverlayRenderer.java
git commit -m "feat: add GridOverlayRenderer for chunk/block grid lines and cursor"
```

---

### Task 6: Tooltip bar renderer

Renders the contextual key-hint bar at the bottom of the screen in screen-space using PixelFont.

**Files:**
- Create: `src/main/java/com/openggf/editor/TooltipBarRenderer.java`

**Step 1: Implement TooltipBarRenderer**

```java
package com.openggf.editor;

import com.openggf.graphics.PixelFont;
import com.openggf.graphics.TexturedQuadRenderer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renders a contextual tooltip bar at the bottom of the screen in screen-space.
 * Uses the master title screen PixelFont for text rendering.
 *
 * Rendered in screen-space (1:1 DPI-scaled pixels), NOT game-space.
 * Requires its own orthographic projection set before draw().
 */
public class TooltipBarRenderer {

    private static final int BAR_HEIGHT = 20;  // pixels
    private static final int TEXT_Y_OFFSET = 5; // from top of bar
    private static final int TEXT_X_MARGIN = 8;

    // Key text colors (brighter)
    private static final float KEY_R = 1.0f, KEY_G = 1.0f, KEY_B = 0.6f, KEY_A = 1.0f;
    // Description text colors (softer)
    private static final float DESC_R = 0.8f, DESC_G = 0.8f, DESC_B = 0.8f, DESC_A = 1.0f;

    private final LevelEditorManager editor;
    private PixelFont font;
    private TexturedQuadRenderer quadRenderer;

    public TooltipBarRenderer(LevelEditorManager editor) {
        this.editor = editor;
    }

    public void init(PixelFont font, TexturedQuadRenderer quadRenderer) {
        this.font = font;
        this.quadRenderer = quadRenderer;
    }

    /**
     * Draw the tooltip bar. Must be called with a screen-space orthographic
     * projection active (origin top-left, size = actual screen pixels / DPI scale).
     *
     * @param screenWidth  screen width in projection units
     * @param screenHeight screen height in projection units
     */
    public void draw(int screenWidth, int screenHeight) {
        // Draw semi-transparent dark background strip
        glUseProgram(0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        int barY = screenHeight - BAR_HEIGHT;
        float glBarY = 0; // Bottom of screen in OpenGL coords (y=0)
        float glBarTop = BAR_HEIGHT;

        glColor4f(0.0f, 0.0f, 0.0f, 0.7f);
        glBegin(GL_QUADS);
        glVertex2f(0, glBarY);
        glVertex2f(screenWidth, glBarY);
        glVertex2f(screenWidth, glBarTop);
        glVertex2f(0, glBarTop);
        glEnd();

        // Draw tooltip text
        String tooltip = getTooltipText();
        int textY = screenHeight - BAR_HEIGHT + TEXT_Y_OFFSET;
        font.drawText(tooltip, TEXT_X_MARGIN, textY, DESC_R, DESC_G, DESC_B, DESC_A);
    }

    private String getTooltipText() {
        String mode = editor.getEditMode() == LevelEditorManager.EditMode.CHUNK
                ? "Chunk" : "Block";

        if (editor.getFocus() == LevelEditorManager.Focus.GRID) {
            return "Arrows:Move  Space:Place  Del:Clear  E:Eyedrop  B/C:" + mode + "  Tab:Panel";
        } else {
            return "Arrows:Browse  PgUp/Dn:Scroll  Enter:Select  B/C:" + mode + "  Tab:Grid";
        }
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/openggf/editor/TooltipBarRenderer.java
git commit -m "feat: add TooltipBarRenderer for contextual key hints"
```

---

### Task 7: Chunk panel renderer

Renders the side panel in screen-space showing chunk/block previews using the PatternAtlas.

**Files:**
- Create: `src/main/java/com/openggf/editor/ChunkPanelRenderer.java`

**Step 1: Implement ChunkPanelRenderer**

This is the most complex renderer. It draws chunk/block thumbnails from the PatternAtlas.

```java
package com.openggf.editor;

import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlas;
import com.openggf.graphics.PixelFont;
import com.openggf.graphics.TexturedQuadRenderer;
import com.openggf.level.*;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renders the chunk/block selection panel on the right side of the screen.
 * Operates in screen-space (1:1 DPI-scaled pixels).
 */
public class ChunkPanelRenderer {

    private static final int PANEL_WIDTH = 200;     // pixels
    private static final int HEADER_HEIGHT = 24;    // pixels
    private static final int FOOTER_HEIGHT = 20;    // pixels
    private static final int ITEM_PADDING = 2;      // pixels between items
    private static final int CHUNK_PREVIEW_SIZE = 16; // 16px for chunks
    private static final int GRID_COLS = 10;        // chunks per row in panel

    private final LevelEditorManager editor;
    private PixelFont font;
    private TexturedQuadRenderer quadRenderer;
    private Level level;

    public ChunkPanelRenderer(LevelEditorManager editor) {
        this.editor = editor;
    }

    public void init(PixelFont font, TexturedQuadRenderer quadRenderer) {
        this.font = font;
        this.quadRenderer = quadRenderer;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public static int getPanelWidth() {
        return PANEL_WIDTH;
    }

    /**
     * Draw the panel. Called with screen-space projection active.
     *
     * @param screenWidth  total screen width in projection units
     * @param screenHeight total screen height in projection units
     * @param zoneName     display name of the current zone
     */
    public void draw(int screenWidth, int screenHeight, String zoneName) {
        if (level == null) return;

        int panelX = screenWidth - PANEL_WIDTH;
        boolean isChunkMode = editor.getEditMode() == LevelEditorManager.EditMode.CHUNK;
        int itemCount = isChunkMode ? level.getChunkCount() : level.getBlockCount();

        // Panel background
        glUseProgram(0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glColor4f(0.1f, 0.1f, 0.15f, 0.9f);
        drawQuad(panelX, 0, PANEL_WIDTH, screenHeight);

        // Header
        String modeLabel = isChunkMode ? "Chunks" : "Blocks";
        String header = modeLabel + " - " + zoneName;
        font.drawText(header, panelX + 4, 4, 1.0f, 1.0f, 1.0f, 1.0f);

        String countText = itemCount + " items";
        font.drawText(countText, panelX + 4, 14, 0.6f, 0.6f, 0.6f, 1.0f);

        // Grid area — draw item indices as text placeholders
        // Full chunk/block preview rendering will use PatternAtlas in a later refinement.
        int gridY = HEADER_HEIGHT;
        int visibleRows = (screenHeight - HEADER_HEIGHT - FOOTER_HEIGHT) / (CHUNK_PREVIEW_SIZE + ITEM_PADDING);
        int scrollOffset = Math.max(0, editor.getPanelSelection() - visibleRows / 2);

        for (int i = 0; i < visibleRows * GRID_COLS && scrollOffset * GRID_COLS + i < itemCount; i++) {
            int itemIndex = scrollOffset * GRID_COLS + i;
            if (itemIndex >= itemCount) break;

            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int ix = panelX + 4 + col * (CHUNK_PREVIEW_SIZE + ITEM_PADDING);
            int iy = gridY + row * (CHUNK_PREVIEW_SIZE + ITEM_PADDING);

            // Highlight selected item
            if (itemIndex == editor.getPanelSelection()) {
                boolean panelFocused = editor.getFocus() == LevelEditorManager.Focus.PANEL;
                if (panelFocused) {
                    glColor4f(0.3f, 0.7f, 1.0f, 0.5f);
                } else {
                    glColor4f(0.5f, 0.5f, 0.5f, 0.3f);
                }
                // Convert to GL coords for the highlight quad
                float glY = screenHeight - iy - CHUNK_PREVIEW_SIZE;
                glBegin(GL_QUADS);
                glVertex2f(ix, glY);
                glVertex2f(ix + CHUNK_PREVIEW_SIZE, glY);
                glVertex2f(ix + CHUNK_PREVIEW_SIZE, glY + CHUNK_PREVIEW_SIZE);
                glVertex2f(ix, glY + CHUNK_PREVIEW_SIZE);
                glEnd();
            }

            // TODO: Render actual chunk/block tile graphics from PatternAtlas.
            // For now, draw item index as text for each item.
            // This will be replaced with PatternAtlas-based rendering in a refinement pass.
        }

        // Footer — selected item info
        int sel = editor.getPanelSelection();
        String footer = "Selected: " + sel;
        if (isChunkMode && sel < level.getChunkCount()) {
            Chunk chunk = level.getChunk(sel);
            if (chunk != null) {
                footer += "  Sol:" + chunk.getSolidTileIndex();
            }
        }
        font.drawText(footer, panelX + 4, screenHeight - FOOTER_HEIGHT + 4,
                0.7f, 0.7f, 0.7f, 1.0f);
    }

    private void drawQuad(float x, float glY, float w, float h) {
        glBegin(GL_QUADS);
        glVertex2f(x, glY);
        glVertex2f(x + w, glY);
        glVertex2f(x + w, glY + h);
        glVertex2f(x, glY + h);
        glEnd();
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/openggf/editor/ChunkPanelRenderer.java
git commit -m "feat: add ChunkPanelRenderer for side panel with chunk/block grid"
```

---

### Task 8: Wire editor into Engine — mode entry, update, draw

Connect all editor components into the Engine's game mode dispatch. This is the integration task.

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/editor/LevelEditorManager.java`

**Step 1: Add editor lifecycle methods to LevelEditorManager**

Add fields and an `update()` method that coordinates input, camera, and rendering:

```java
// New fields in LevelEditorManager
private EditorInputHandler inputHandler;
private GridOverlayRenderer gridOverlayRenderer;
private TooltipBarRenderer tooltipBarRenderer;
private ChunkPanelRenderer chunkPanelRenderer;
private boolean initialized;

// Editor camera position (in pixels, independent of the gameplay camera)
private int cameraX;
private int cameraY;
private int cameraSpeed = 4; // pixels per frame

public void initialize(PixelFont font, TexturedQuadRenderer quadRenderer) {
    this.inputHandler = new EditorInputHandler(this);
    this.gridOverlayRenderer = new GridOverlayRenderer(this);
    this.tooltipBarRenderer = new TooltipBarRenderer(this);
    this.chunkPanelRenderer = new ChunkPanelRenderer(this);
    this.tooltipBarRenderer.init(font, quadRenderer);
    this.chunkPanelRenderer.init(font, quadRenderer);
    this.initialized = true;
}

public boolean isInitialized() { return initialized; }

public void setLevel(Level level, int mapWidth, int mapHeight) {
    this.chunkPanelRenderer.setLevel(level);
    initForLevel(mapWidth, mapHeight,
            level.getChunkCount(), level.getBlockCount(),
            level.getBlock(0) != null ? /* derive gridSide */ 8 : 8);
}

public void update(InputHandler input) {
    EditorInputHandler.EditorAction action = this.inputHandler.update(input);
    // Handle camera panning (when grid focused, arrow keys move cursor;
    // camera auto-follows cursor)
    updateCameraFollow();
}

private void updateCameraFollow() {
    // Keep cursor visible: scroll camera if cursor moves out of view
    int cursorPx = getCursorPixelX();
    int cursorPy = getCursorPixelY();
    int cellSize = getCursorCellSize();
    // (Camera follow logic - ensure cursor cell is within viewport)
}

public GridOverlayRenderer getGridOverlayRenderer() { return gridOverlayRenderer; }
public TooltipBarRenderer getTooltipBarRenderer() { return tooltipBarRenderer; }
public ChunkPanelRenderer getChunkPanelRenderer() { return chunkPanelRenderer; }
public int getCameraX() { return cameraX; }
public int getCameraY() { return cameraY; }
```

**Step 2: Add editor mode entry in GameLoop.step()**

At the top of `GameLoop.step()`, before other mode handling, add:

```java
// Shift+Tab toggles level editor mode
if (inputHandler.isKeyDown(GLFW_KEY_LEFT_SHIFT) && inputHandler.isKeyPressed(GLFW_KEY_TAB)) {
    if (currentGameMode == GameMode.LEVEL_EDITOR) {
        setGameMode(GameMode.LEVEL); // Exit editor
    } else if (currentGameMode == GameMode.LEVEL || currentGameMode == GameMode.LEVEL_SELECT) {
        setGameMode(GameMode.LEVEL_EDITOR); // Enter editor
    }
    return; // Consume this frame
}

// Editor update
if (currentGameMode == GameMode.LEVEL_EDITOR) {
    LevelEditorManager.getInstance().update(inputHandler);
    inputHandler.update();
    return; // Skip normal game logic
}
```

**Step 3: Add editor rendering in Engine.draw()**

Before the existing `debugViewEnabled` else-branch (~line 793), add:

```java
} else if (getCurrentGameMode() == GameMode.LEVEL_EDITOR) {
    // Render level tilemap in game-space (existing renderer)
    levelManager.drawLevel(); // Draw tilemap without sprites

    // Register grid overlay for camera-aware flush
    LevelEditorManager editorMgr = LevelEditorManager.getInstance();
    graphicsManager.registerCommand(editorMgr.getGridOverlayRenderer());

    // Flush game-space rendering
    Camera cam = Camera.getInstance();
    cam.setX((short) editorMgr.getCameraX());
    cam.setY((short) editorMgr.getCameraY());
    graphicsManager.flush();
```

**Step 4: Add screen-space editor UI in Engine.display()**

After the `graphicsManager.flush()` call in display() (~line 635), add screen-space
editor UI rendering when in editor mode:

```java
// Editor screen-space UI (panel + tooltip)
if (getCurrentGameMode() == GameMode.LEVEL_EDITOR) {
    // Switch to full-window viewport for screen-space rendering
    glViewport(0, 0, windowWidth, windowHeight);
    // Set screen-space orthographic projection (1:1 pixels)
    projectionMatrix.identity().ortho2D(0, windowWidth, 0, windowHeight);
    projectionMatrix.get(matrixBuffer);
    // Update TexturedQuadRenderer projection
    quadRenderer.setProjectionMatrix(matrixBuffer);

    LevelEditorManager editorMgr = LevelEditorManager.getInstance();
    String zoneName = "Zone " + levelManager.getCurrentZone();
    editorMgr.getChunkPanelRenderer().draw(windowWidth, windowHeight, zoneName);
    editorMgr.getTooltipBarRenderer().draw(windowWidth, windowHeight);

    // Restore game viewport
    glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
    projectionMatrix.identity().ortho2D(0, (float) projectionWidth, 0, (float) realHeight);
    projectionMatrix.get(matrixBuffer);
}
```

**Step 5: Adjust game viewport to leave room for panel**

In the editor mode branch of `display()` (or `reshape()`), shrink the game viewport:

```java
if (getCurrentGameMode() == GameMode.LEVEL_EDITOR) {
    int panelPx = ChunkPanelRenderer.getPanelWidth();
    int availableWidth = windowWidth - panelPx;
    int nativeW = (int) realWidth;
    int nativeH = (int) realHeight;
    int scale = Math.max(1, Math.min(availableWidth / nativeW, windowHeight / nativeH));
    viewportWidth = scale * nativeW;
    viewportHeight = scale * nativeH;
    viewportX = (availableWidth - viewportWidth) / 2;
    viewportY = (windowHeight - viewportHeight) / 2;
}
```

**Step 6: Test manually**

Run: `java -jar target/sonic-engine-0.4.prerelease-jar-with-dependencies.jar`
1. Load any level (EHZ Act 1)
2. Press `Shift+Tab` — should enter editor mode
3. Arrow keys move cursor, grid overlay visible
4. Side panel visible on right
5. Tooltip bar at bottom
6. `Shift+Tab` again exits back to gameplay

**Step 7: Commit**

```bash
git add src/main/java/com/openggf/Engine.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/editor/LevelEditorManager.java
git commit -m "feat: wire level editor into Engine with mode entry, rendering, and input"
```

---

### Task 9: Integration polish — camera follow, placement wiring, tilemap refresh

Wire the edit actions (place/clear/eyedropper) to actually modify the live level data, and ensure the tilemap renderer refreshes after edits.

**Files:**
- Modify: `src/main/java/com/openggf/editor/LevelEditorManager.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java` (if tilemap dirty flag needed)

**Step 1: Wire edit actions to live level data**

In `LevelEditorManager.update()`, after getting the action from `inputHandler.update()`:

```java
if (action != null && currentLevel != null) {
    Map map = currentLevel.getMap();
    switch (action) {
        case PLACE -> {
            if (editMode == EditMode.BLOCK) {
                placeBlock(map, panelSelection);
            } else {
                currentBlocks = placeChunk(map, currentBlocks, panelSelection);
            }
            tilemapDirty = true;
        }
        case CLEAR -> {
            clearCell(map, currentBlocks);
            tilemapDirty = true;
        }
        case EYEDROP -> {
            eyedropper(map, currentBlocks);
        }
    }
}
```

**Step 2: Add camera-follow logic**

```java
private void updateCameraFollow() {
    int px = getCursorPixelX();
    int py = getCursorPixelY();
    int cellSize = getCursorCellSize();
    int viewW = 320; // game viewport width
    int viewH = 224; // game viewport height
    int margin = 32; // keep cursor this far from viewport edge

    if (px < cameraX + margin) cameraX = px - margin;
    if (px + cellSize > cameraX + viewW - margin) cameraX = px + cellSize - viewW + margin;
    if (py < cameraY + margin) cameraY = py - margin;
    if (py + cellSize > cameraY + viewH - margin) cameraY = py + cellSize - viewH + margin;

    cameraX = Math.max(0, cameraX);
    cameraY = Math.max(0, cameraY);
}
```

**Step 3: Notify tilemap renderer to rebuild after edits**

Check how `LevelManager` marks the foreground tilemap dirty. If there's a dirty flag
(e.g., `fgTilemapDirty`), set it after each edit. If not, add one. Look at
`LevelManager.ensureForegroundTilemapData()` — it likely rebuilds from the Map each frame
or on demand. If it caches, expose `invalidateForegroundTilemap()`.

**Step 4: Test manually**

1. Enter editor, select a chunk in the panel
2. Place it on the grid with Space
3. The tilemap should visually update to show the new chunk
4. Eyedropper (E) should pick the chunk under cursor
5. Delete should clear the cell

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/LevelEditorManager.java src/main/java/com/openggf/level/LevelManager.java
git commit -m "feat: wire edit actions to live level data with tilemap refresh"
```

---

### Task 10: Run full test suite and verify no regressions

**Step 1: Run all tests**

Run: `mvn test -pl .`
Expected: All existing tests pass. No regressions from the new code (editor code is isolated behind `GameMode.LEVEL_EDITOR` checks).

**Step 2: Verify S3K tests specifically**

Run: `mvn test -Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils -pl .`
Expected: PASS (these are flagged as critical in CLAUDE.md).

**Step 3: Commit any fixes if needed**

If any test fails, diagnose and fix before proceeding.

---

## Summary

| Task | Description | Type |
|------|-------------|------|
| 1 | GameMode enum + Block.copy() | Data + TDD |
| 2 | LevelEditorManager skeleton | Logic + TDD |
| 3 | Edit operations (place/clear/eyedrop) | Logic + TDD |
| 4 | EditorInputHandler | Input glue |
| 5 | GridOverlayRenderer | Rendering |
| 6 | TooltipBarRenderer | Rendering |
| 7 | ChunkPanelRenderer | Rendering |
| 8 | Wire into Engine (mode entry/draw) | Integration |
| 9 | Camera follow + placement wiring | Integration |
| 10 | Full regression test | Verification |
