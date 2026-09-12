package com.openggf.editor;

import com.openggf.camera.Camera;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.control.InputActionMasks;
import com.openggf.editor.commands.StrokeCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.MutableLevel;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_INSERT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

@com.openggf.game.ModApi
public final class EditorInputHandler {
    @com.openggf.game.ModApi
    public enum Action {
        DESCEND,
        ASCEND,
        CYCLE_FOCUS_REGION,
        APPLY_PRIMARY_ACTION,
        PERFORM_EYEDROP,
        TOGGLE_LAYER,
        SAVE,
        EXPORT,
        UNDO,
        REDO,
        CYCLE_SPAWN_EDIT_MODE,
        NEXT_OBJECT,
        PREVIOUS_OBJECT,
        INCREMENT_SUBTYPE,
        DECREMENT_SUBTYPE,
        DELETE_SPAWN,
        MOVE_SELECTED_SPAWN_TO_CURSOR,
        TOGGLE_COLLISION_OVERLAY,
        TOGGLE_COLLISION_PATH,
        CYCLE_COLLISION_MODE,
        INCREMENT_SOLID_TILE_INDEX,
        DECREMENT_SOLID_TILE_INDEX,
        BROWSE_LIBRARY_NEXT,
        BROWSE_LIBRARY_PREVIOUS,
        BROWSE_LIBRARY_ROW_NEXT,
        BROWSE_LIBRARY_ROW_PREVIOUS,
        BROWSE_LIBRARY_PAGE_NEXT,
        BROWSE_LIBRARY_PAGE_PREVIOUS
    }

    private static final int WORLD_MOVE_SPEED = 3;

    private final LevelEditorController controller;
    private final Supplier<Camera> cameraSupplier;
    private final Supplier<GraphicsManager> graphicsSupplier;
    private final Runnable saveAction;
    private final Runnable exportAction;
    private DragStroke activeStroke;

    public EditorInputHandler(LevelEditorController controller) {
        this(controller, () -> null, () -> null, () -> {});
    }

    public EditorInputHandler(LevelEditorController controller,
                              Supplier<Camera> cameraSupplier,
                              Supplier<GraphicsManager> graphicsSupplier) {
        this(controller, cameraSupplier, graphicsSupplier, () -> {});
    }

    public EditorInputHandler(LevelEditorController controller,
                              Supplier<Camera> cameraSupplier,
                              Supplier<GraphicsManager> graphicsSupplier,
                              Runnable saveAction) {
        this(controller, cameraSupplier, graphicsSupplier, saveAction, () -> {});
    }

    public EditorInputHandler(LevelEditorController controller,
                              Supplier<Camera> cameraSupplier,
                              Supplier<GraphicsManager> graphicsSupplier,
                              Runnable saveAction,
                              Runnable exportAction) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.cameraSupplier = Objects.requireNonNull(cameraSupplier, "cameraSupplier");
        this.graphicsSupplier = Objects.requireNonNull(graphicsSupplier, "graphicsSupplier");
        this.saveAction = Objects.requireNonNull(saveAction, "saveAction");
        this.exportAction = Objects.requireNonNull(exportAction, "exportAction");
    }

    public void update(InputHandler inputHandler) {
        Objects.requireNonNull(inputHandler, "inputHandler");
        if (EditorCommandPalette.forController(controller).update(inputHandler, this::handleAction)) {
            finishActiveStroke();
            return;
        }
        boolean capturesText = controller.isLibraryFilterInputActive();
        if(!capturesText)handleMouseInput(inputHandler);
        var logical = inputHandler.logical();
        int logicalActions = logical.player1().actionPressedMask();
        int dx = 0;
        int dy = 0;
        if (controller.isLibraryBrowserFocused()) {
            if (controller.focusRegion() == EditorFocusRegion.SPAWN_PALETTE) {
                if (capturesText ? MenuInput.textLeft(inputHandler) : MenuInput.left(inputHandler))
                    handleAction(Action.BROWSE_LIBRARY_PREVIOUS);
                if (capturesText ? MenuInput.textRight(inputHandler) : MenuInput.right(inputHandler))
                    handleAction(Action.BROWSE_LIBRARY_NEXT);
                if (capturesText ? MenuInput.textUp(inputHandler) : MenuInput.up(inputHandler))
                    handleAction(capturesText?Action.BROWSE_LIBRARY_ROW_PREVIOUS:Action.INCREMENT_SUBTYPE);
                if (capturesText ? MenuInput.textDown(inputHandler) : MenuInput.down(inputHandler))
                    handleAction(capturesText?Action.BROWSE_LIBRARY_ROW_NEXT:Action.DECREMENT_SUBTYPE);
            } else {
                if (capturesText ? MenuInput.textLeft(inputHandler) : MenuInput.left(inputHandler))
                    handleAction(Action.BROWSE_LIBRARY_PREVIOUS);
                if (capturesText ? MenuInput.textRight(inputHandler) : MenuInput.right(inputHandler))
                    handleAction(Action.BROWSE_LIBRARY_NEXT);
                if (capturesText ? MenuInput.textUp(inputHandler) : MenuInput.up(inputHandler))
                    handleAction(Action.BROWSE_LIBRARY_ROW_PREVIOUS);
                if (capturesText ? MenuInput.textDown(inputHandler) : MenuInput.down(inputHandler))
                    handleAction(Action.BROWSE_LIBRARY_ROW_NEXT);
            }
        } else if (controller.focusRegion() == EditorFocusRegion.SPAWN_PALETTE
                && controller.spawnEditMode() == EditorSpawnEditMode.OBJECTS) {
            if (capturesText ? MenuInput.textLeft(inputHandler) : MenuInput.left(inputHandler)) handleAction(Action.PREVIOUS_OBJECT);
            if (capturesText ? MenuInput.textRight(inputHandler) : MenuInput.right(inputHandler)) handleAction(Action.NEXT_OBJECT);
            if (capturesText ? MenuInput.textUp(inputHandler) : MenuInput.up(inputHandler)) handleAction(Action.INCREMENT_SUBTYPE);
            if (capturesText ? MenuInput.textDown(inputHandler) : MenuInput.down(inputHandler)) handleAction(Action.DECREMENT_SUBTYPE);
        } else {
            if (inputHandler.isDirectionHeld(GLFW_KEY_LEFT, AbstractPlayableSprite.INPUT_LEFT)) dx -= 1;
            if (inputHandler.isDirectionHeld(GLFW_KEY_RIGHT, AbstractPlayableSprite.INPUT_RIGHT)) dx += 1;
            if (inputHandler.isDirectionHeld(GLFW_KEY_UP, AbstractPlayableSprite.INPUT_UP)) dy -= 1;
            if (inputHandler.isDirectionHeld(GLFW_KEY_DOWN, AbstractPlayableSprite.INPUT_DOWN)) dy += 1;
        }
        if ((dx != 0 || dy != 0) && activeStroke == null) {
            if (controller.depth() == EditorHierarchyDepth.WORLD) {
                controller.moveWorldCursor(dx * WORLD_MOVE_SPEED, dy * WORLD_MOVE_SPEED);
            } else {
                controller.moveActiveSelection(dx, dy);
            }
        }
        if (controller.isLibraryBrowserFocused() && inputHandler.isKeyPressed(GLFW_KEY_INSERT)) {
            controller.toggleLibraryFilterInput();
        }
        if (controller.isLibraryBrowserFocused() && inputHandler.isKeyPressed(GLFW_KEY_PAGE_DOWN)) {
            handleAction(Action.BROWSE_LIBRARY_PAGE_NEXT);
        }
        if (controller.isLibraryBrowserFocused() && inputHandler.isKeyPressed(GLFW_KEY_PAGE_UP)) {
            handleAction(Action.BROWSE_LIBRARY_PAGE_PREVIOUS);
        }
        boolean controlDown = inputHandler.isKeyDown(GLFW_KEY_LEFT_CONTROL)
                || inputHandler.isKeyDown(GLFW_KEY_RIGHT_CONTROL);
        if (controller.isLibraryBrowserFocused() && MenuInput.textKeyRepeated(inputHandler, GLFW_KEY_BACKSPACE)) {
            if (controlDown) controller.setLibraryFilter(""); else controller.backspaceLibraryFilter();
        }
        capturesText=controller.isLibraryFilterInputActive();
        if(capturesText && MenuInput.textBack(inputHandler)) {
            controller.endLibraryFilterInput();
            return;
        }
        if(capturesText)return;
        boolean shiftDown = inputHandler.isKeyDown(GLFW_KEY_LEFT_SHIFT)
                || inputHandler.isKeyDown(GLFW_KEY_RIGHT_SHIFT);
        if (inputHandler.isKeyPressed(GLFW_KEY_TAB) && !shiftDown) {
            handleAction(Action.CYCLE_FOCUS_REGION);
        }
        boolean exportChord = controlDown && shiftDown && inputHandler.isKeyPressed(GLFW_KEY_E);
        boolean rawPrimary = !capturesText&&inputHandler.isKeyPressed(GLFW_KEY_SPACE);
        boolean rawEyedrop = !capturesText&&!exportChord && inputHandler.isKeyPressed(GLFW_KEY_E);
        boolean rawModeCycle = !capturesText&&inputHandler.isKeyPressed(GLFW_KEY_O);
        boolean rawDelete = inputHandler.isKeyPressed(GLFW_KEY_DELETE);
        if (rawPrimary && !controller.isSpawnEditing()) {
            handleAction(Action.APPLY_PRIMARY_ACTION);
        }
        if (rawEyedrop && !controller.isSpawnEditing()) {
            handleAction(Action.PERFORM_EYEDROP);
        }
        if (!capturesText&&inputHandler.isKeyPressed(GLFW_KEY_L)) {
            handleAction(Action.TOGGLE_LAYER);
        }
        if (rawModeCycle || logical.menuStart()) {
            handleAction(Action.CYCLE_SPAWN_EDIT_MODE);
        }
        if (rawDelete && !controller.isSpawnEditing()) {
            handleAction(Action.DELETE_SPAWN);
        }
        if (!capturesText&&inputHandler.isKeyPressed(GLFW_KEY_M)) {
            handleAction(Action.MOVE_SELECTED_SPAWN_TO_CURSOR);
        }
        if (!capturesText&&inputHandler.isKeyPressed(GLFW_KEY_C)) {
            handleAction(Action.TOGGLE_COLLISION_OVERLAY);
        }
        if (!capturesText&&inputHandler.isKeyPressed(GLFW_KEY_P)) {
            handleAction(Action.TOGGLE_COLLISION_PATH);
        }
        if (!capturesText&&inputHandler.isKeyPressed(GLFW_KEY_V)) {
            handleAction(Action.CYCLE_COLLISION_MODE);
        }
        if (inputHandler.isKeyPressed(GLFW_KEY_RIGHT_BRACKET)) {
            handleAction(Action.INCREMENT_SOLID_TILE_INDEX);
        }
        if (inputHandler.isKeyPressed(GLFW_KEY_LEFT_BRACKET)) {
            handleAction(Action.DECREMENT_SOLID_TILE_INDEX);
        }
        if (controller.isSpawnEditing()) {
            if (rawPrimary || (logicalActions & InputActionMasks.ACTION_A) != 0) {
                handleAction(Action.APPLY_PRIMARY_ACTION);
            }
            if (rawEyedrop || (logicalActions & InputActionMasks.ACTION_B) != 0) {
                handleAction(Action.PERFORM_EYEDROP);
            }
            if (rawDelete || (logicalActions & InputActionMasks.ACTION_C) != 0) {
                handleAction(Action.DELETE_SPAWN);
            }
        }
        if (controlDown && inputHandler.isKeyPressed(GLFW_KEY_Z)) {
            handleAction(Action.UNDO);
        }
        if (!capturesText&&controlDown && inputHandler.isKeyPressed(GLFW_KEY_S)) {
            handleAction(Action.SAVE);
        }
        if (exportChord) {
            handleAction(Action.EXPORT);
        }
        if (controlDown && inputHandler.isKeyPressed(GLFW_KEY_Y)) {
            handleAction(Action.REDO);
        }
        if (inputHandler.isKeyPressed(GLFW_KEY_ENTER)) {
            handleAction(Action.DESCEND);
        }
        if (inputHandler.isKeyPressed(GLFW_KEY_ESCAPE)) {
            if(capturesText)controller.endLibraryFilterInput(); else handleAction(Action.ASCEND);
        }
    }

    public void handleAction(Action action) {
        Objects.requireNonNull(action, "action");
        switch (action) {
            case DESCEND -> controller.descend();
            case ASCEND -> controller.ascend();
            case CYCLE_FOCUS_REGION -> controller.cycleFocusRegion();
            case APPLY_PRIMARY_ACTION -> controller.applyPrimaryAction();
            case PERFORM_EYEDROP -> controller.performEyedrop();
            case TOGGLE_LAYER -> controller.toggleActiveLayer();
            case SAVE -> saveAction.run();
            case EXPORT -> exportAction.run();
            case UNDO -> controller.undo();
            case REDO -> controller.redo();
            case CYCLE_SPAWN_EDIT_MODE -> controller.cycleSpawnEditMode();
            case NEXT_OBJECT -> controller.objectPalette().navigate(EditorStockObjectPalette.Navigation.NEXT_OBJECT);
            case PREVIOUS_OBJECT -> controller.objectPalette().navigate(EditorStockObjectPalette.Navigation.PREVIOUS_OBJECT);
            case INCREMENT_SUBTYPE -> controller.objectPalette().navigate(EditorStockObjectPalette.Navigation.INCREMENT_SUBTYPE);
            case DECREMENT_SUBTYPE -> controller.objectPalette().navigate(EditorStockObjectPalette.Navigation.DECREMENT_SUBTYPE);
            case DELETE_SPAWN -> controller.deleteSpawnAtCursor();
            case MOVE_SELECTED_SPAWN_TO_CURSOR -> controller.moveSelectedSpawn(
                    controller.worldCursor().x(), controller.worldCursor().y());
            case TOGGLE_COLLISION_OVERLAY -> controller.toggleCollisionOverlay();
            case TOGGLE_COLLISION_PATH -> controller.toggleCollisionPath();
            case CYCLE_COLLISION_MODE -> controller.cycleSelectedCellCollisionMode();
            case INCREMENT_SOLID_TILE_INDEX -> controller.adjustSelectedChunkSolidTileIndex(1);
            case DECREMENT_SOLID_TILE_INDEX -> controller.adjustSelectedChunkSolidTileIndex(-1);
            case BROWSE_LIBRARY_NEXT -> controller.browseLibrary(1);
            case BROWSE_LIBRARY_PREVIOUS -> controller.browseLibrary(-1);
            case BROWSE_LIBRARY_ROW_NEXT -> controller.libraryBrowser().move2d(0, 1);
            case BROWSE_LIBRARY_ROW_PREVIOUS -> controller.libraryBrowser().move2d(0, -1);
            case BROWSE_LIBRARY_PAGE_NEXT -> controller.libraryBrowser().page(1);
            case BROWSE_LIBRARY_PAGE_PREVIOUS -> controller.libraryBrowser().page(-1);
        }
    }

    public void finishActiveStroke() {
        if (activeStroke == null) {
            return;
        }
        MutableLevel level = controller.currentLevel();
        StrokeCommand command = level != null ? activeStroke.toCommand(level) : null;
        activeStroke = null;
        if (command != null && !command.isEmpty()) {
            controller.executeCommand(command);
        }
    }

    /** GLFW character-input callback seam; filtering is active only while a library pane owns focus. */
    public void handleTextInputCodepoint(int codepoint) {
        if (EditorCommandPalette.forController(controller).isOpen()
                || !controller.isLibraryBrowserFocused() || !controller.isLibraryFilterInputActive()
                || !Character.isValidCodePoint(codepoint)
                || Character.isISOControl(codepoint)) return;
        controller.appendLibraryFilterText(new String(Character.toChars(codepoint)));
    }

    private void handleMouseInput(InputHandler inputHandler) {
        if (!inputHandler.hasMouseInputSeen()) {
            return;
        }
        MutableLevel level = controller.currentLevel();
        Camera camera = cameraSupplier.get();
        GraphicsManager graphics = graphicsSupplier.get();
        if (level == null || camera == null || graphics == null) {
            return;
        }

        EditorMouseTransform.Result hover = EditorMouseTransform.toWorldTile(inputHandler, camera, graphics, level);
        if (hover.inViewport()) {
            controller.setWorldCursor(new com.openggf.game.session.EditorCursorState(hover.worldX(), hover.worldY()));
        }

        if (inputHandler.isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT) && hover.inViewport()) {
            controller.performEyedrop();
        }

        if (controller.isSpawnEditing()) {
            if (inputHandler.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT) && hover.inViewport()) {
                controller.placeSpawnAtCursor();
            }
            return;
        }

        boolean leftDown = inputHandler.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT);
        if (leftDown && activeStroke == null && hover.inViewport()) {
            activeStroke = new DragStroke(controller.activeLayer());
        }
        if (leftDown && activeStroke != null && hover.inViewport()) {
            activeStroke.record(level, hover.tileX(), hover.tileY(), controller.selectedBlockIndex());
        }
        if (!leftDown && activeStroke != null) {
            finishActiveStroke();
        }
    }

    private static final class DragStroke {
        private final int layer;
        private final Map<CellKey, StrokeCommand.CellDelta> deltas = new LinkedHashMap<>();

        private DragStroke(int layer) {
            this.layer = layer;
        }

        private void record(MutableLevel level, int x, int y, Integer selectedBlock) {
            if (selectedBlock == null) {
                return;
            }
            CellKey key = new CellKey(layer, x, y);
            deltas.computeIfAbsent(key, ignored -> {
                int before = Byte.toUnsignedInt(level.getMap().getValue(layer, x, y));
                return new StrokeCommand.CellDelta(layer, x, y, before, selectedBlock);
            });
        }

        private StrokeCommand toCommand(MutableLevel level) {
            return new StrokeCommand(level, java.util.List.copyOf(deltas.values()));
        }
    }

    private record CellKey(int layer, int x, int y) {
    }
}
