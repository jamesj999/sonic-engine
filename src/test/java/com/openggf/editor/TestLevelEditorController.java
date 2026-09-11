package com.openggf.editor;

import com.openggf.GameLoop;
import com.openggf.control.InputHandler;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.GameMode;
import com.openggf.game.session.EditorCursorState;
import com.openggf.game.session.SessionManager;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Map;
import com.openggf.level.MutableLevel;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestLevelEditorController {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void controller_defaultsToWorldCanvasFocusRegionAndCyclesThroughConcreteWorldRegions() {
        LevelEditorController controller = new LevelEditorController();

        assertEquals(EditorFocusRegion.WORLD_CANVAS, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.BLOCK_PANE, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.COMMAND_STRIP, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.TOOLBAR, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.WORLD_CANVAS, controller.focusRegion());
    }

    @Test
    void controller_applyPrimaryActionPlacesSelectedBlockAtCurrentWorldCursorCell() {
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.selectBlock(2);
        controller.setWorldCursor(new EditorCursorState(65, 130));
        controller.applyPrimaryAction();

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 2)));
    }

    @Test
    void controller_performEyedropSelectsBlockUnderCurrentWorldCursor() {
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        level.setBlockInMap(0, 1, 2, 2);
        controller.setWorldCursor(new EditorCursorState(65, 130));

        controller.performEyedrop();

        assertEquals(2, controller.selection().selectedBlock());
        assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
    }

    @Test
    void controller_primaryActionIgnoresNonWorldCanvasFocus() {
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.cycleFocusRegion();
        controller.selectBlock(2);
        controller.setWorldCursor(new EditorCursorState(65, 130));
        controller.applyPrimaryAction();

        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 2)));
    }

    @Test
    void controller_applyPrimaryActionInBlockDepthDerivesBlockAndUndoRedoRestoresMapReference() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 12);
        level.setBlockInMap(0, 1, 1, 1);
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(4));
        level.setChunkInBlock(1, 1, 0, new ChunkDesc(5));
        level.setChunkInBlock(1, 0, 1, new ChunkDesc(6));
        level.setChunkInBlock(1, 1, 1, new ChunkDesc(7));
        level.setChunkInBlock(2, 0, 0, new ChunkDesc(9));
        level.setChunkInBlock(2, 1, 1, new ChunkDesc(10));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.setWorldCursor(new EditorCursorState(80, 96));
        controller.selectBlock(1);
        controller.descend();
        controller.selectChunk(11);
        controller.moveActiveSelection(1, 1);
        controller.applyPrimaryAction();

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(4, level.getBlock(2).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(11, level.getBlock(2).getChunkDesc(1, 1).getChunkIndex());

        controller.undo();
        assertEquals(1, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(9, level.getBlock(2).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(10, level.getBlock(2).getChunkDesc(1, 1).getChunkIndex());
        assertEquals(1, controller.selection().selectedBlock());
        assertEquals(7, controller.selection().selectedChunk());

        controller.redo();
        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(4, level.getBlock(2).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(11, level.getBlock(2).getChunkDesc(1, 1).getChunkIndex());
        assertEquals(2, controller.selection().selectedBlock());
        assertEquals(11, controller.selection().selectedChunk());
    }

    @Test
    void controller_blockEyedropAppliesRawChunkDescriptorToDerivedBlockCell() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 12);
        int flaggedChunkRaw = 0xFC07;
        level.setBlockInMap(0, 1, 1, 1);
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(4));
        level.setChunkInBlock(1, 1, 1, new ChunkDesc(flaggedChunkRaw));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.setWorldCursor(new EditorCursorState(80, 96));
        controller.selectBlock(1);
        controller.descend();
        controller.moveActiveSelection(1, 1);
        controller.performEyedrop();
        controller.moveActiveSelection(-1, -1);
        controller.applyPrimaryAction();

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(flaggedChunkRaw, level.getBlock(2).getChunkDesc(0, 0).get());
        assertEquals(7, controller.selection().selectedChunk());
    }

    @Test
    void controller_applyPrimaryActionInBlockDepthDoesNothingWithoutSelectedChunk() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 12);
        level.setBlockInMap(0, 1, 1, 1);
        level.setChunkInBlock(1, 1, 1, new ChunkDesc(7));
        level.setChunkInBlock(2, 1, 1, new ChunkDesc(10));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.setWorldCursor(new EditorCursorState(80, 96));
        controller.selectBlock(1);
        controller.descend();
        controller.moveActiveSelection(1, 1);
        controller.applyPrimaryAction();

        assertEquals(1, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(10, level.getBlock(2).getChunkDesc(1, 1).getChunkIndex());
    }

    @Test
    void controller_performEyedropInBlockDepthSelectsChunkFromActiveBlockCell() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 12);
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(4));
        level.setChunkInBlock(1, 1, 1, new ChunkDesc(7));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.selectBlock(1);
        controller.descend();
        controller.moveActiveSelection(1, 1);

        controller.performEyedrop();

        assertEquals(1, controller.selection().selectedBlock());
        assertEquals(7, controller.selection().selectedChunk());
        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());
        assertEquals(EditorFocusRegion.BLOCK_PANE, controller.focusRegion());
    }

    @Test
    void controller_applyPrimaryActionInBlockDepthNoOpsWhenSelectedBlockDiffersFromCursorBlock() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 12);
        level.setBlockInMap(0, 1, 1, 1);
        level.setChunkInBlock(1, 1, 1, new ChunkDesc(7));
        level.setChunkInBlock(2, 1, 1, new ChunkDesc(10));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.setWorldCursor(new EditorCursorState(80, 96));
        controller.selectBlock(0);
        controller.descend();
        controller.selectChunk(11);
        controller.moveActiveSelection(1, 1);
        controller.applyPrimaryAction();

        assertEquals(1, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(10, level.getBlock(2).getChunkDesc(1, 1).getChunkIndex());
        assertEquals(0, controller.selection().selectedBlock());
        assertEquals(11, controller.selection().selectedChunk());
    }

    @Test
    void controller_applyPrimaryActionInChunkDepthDerivesChunkAndUndoRedoRestoresBlockReference() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 5);
        level.setBlockInMap(0, 0, 0, 1);
        level.setChunkInBlock(0, 1, 0, new ChunkDesc(1));
        level.setChunkInBlock(0, 0, 1, new ChunkDesc(2));
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(3));
        level.setPatternDescInChunk(3, 0, 0, new PatternDesc(31));
        level.setPatternDescInChunk(3, 1, 0, new PatternDesc(32));
        level.setPatternDescInChunk(3, 0, 1, new PatternDesc(33));
        level.setPatternDescInChunk(3, 1, 1, new PatternDesc(34));
        level.setPatternDescInChunk(4, 0, 0, new PatternDesc(41));
        level.setPatternDescInChunk(4, 1, 1, new PatternDesc(44));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.selectBlock(1);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();
        controller.performEyedrop();
        controller.moveActiveSelection(1, 1);
        controller.applyPrimaryAction();

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 0, 0)));
        assertEquals(4, level.getBlock(2).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(3, level.getBlock(1).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(31, level.getChunk(4).getPatternDesc(0, 0).get());
        assertEquals(31, level.getChunk(4).getPatternDesc(1, 1).get());
        assertEquals(32, level.getChunk(4).getPatternDesc(1, 0).get());
        assertEquals(2, controller.selection().selectedBlock());
        assertEquals(4, controller.selection().selectedChunk());

        controller.undo();
        assertEquals(1, Byte.toUnsignedInt(level.getMap().getValue(0, 0, 0)));
        assertEquals(3, level.getBlock(1).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(41, level.getChunk(4).getPatternDesc(0, 0).get());
        assertEquals(44, level.getChunk(4).getPatternDesc(1, 1).get());
        assertEquals(1, controller.selection().selectedBlock());
        assertEquals(3, controller.selection().selectedChunk());

        controller.redo();
        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 0, 0)));
        assertEquals(4, level.getBlock(2).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(31, level.getChunk(4).getPatternDesc(0, 0).get());
        assertEquals(31, level.getChunk(4).getPatternDesc(1, 1).get());
        assertEquals(2, controller.selection().selectedBlock());
        assertEquals(4, controller.selection().selectedChunk());
    }

    @Test
    void controller_applyPrimaryActionInChunkDepthPreservesActiveChunkDescriptorHighBits() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 5);
        int sourceChunkRaw = 0xFC03;
        int derivedChunkRaw = 0xFC04;
        level.setBlockInMap(0, 0, 0, 1);
        level.setChunkInBlock(0, 1, 0, new ChunkDesc(1));
        level.setChunkInBlock(0, 0, 1, new ChunkDesc(2));
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(sourceChunkRaw));
        level.setPatternDescInChunk(3, 0, 0, new PatternDesc(31));
        level.setPatternDescInChunk(4, 0, 0, new PatternDesc(41));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.selectBlock(1);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();
        controller.performEyedrop();
        controller.applyPrimaryAction();

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 0, 0)));
        assertEquals(derivedChunkRaw, level.getBlock(2).getChunkDesc(0, 0).get());
        assertEquals(sourceChunkRaw, level.getBlock(1).getChunkDesc(0, 0).get());
    }

    @Test
    void controller_applyPrimaryActionInChunkDepthPreservesOtherMapCellsUsingSourceBlock() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 5);
        level.setBlockInMap(0, 0, 1, 1);
        level.setBlockInMap(0, 1, 1, 1);
        level.setChunkInBlock(0, 1, 0, new ChunkDesc(1));
        level.setChunkInBlock(0, 0, 1, new ChunkDesc(2));
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(3));
        level.setPatternDescInChunk(3, 0, 0, new PatternDesc(31));
        level.setPatternDescInChunk(3, 1, 1, new PatternDesc(34));
        level.setPatternDescInChunk(4, 0, 0, new PatternDesc(41));
        level.setPatternDescInChunk(4, 1, 1, new PatternDesc(44));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.setWorldCursor(new EditorCursorState(80, 96));
        controller.selectBlock(1);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();
        controller.performEyedrop();
        controller.moveActiveSelection(1, 1);
        controller.applyPrimaryAction();

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(1, Byte.toUnsignedInt(level.getMap().getValue(0, 0, 1)));
        assertEquals(3, level.getBlock(1).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(4, level.getBlock(2).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(31, level.getChunk(4).getPatternDesc(1, 1).get());
    }

    @Test
    void controller_applyPrimaryActionInChunkDepthNoOpsWhenSelectedBlockDiffersFromCursorBlock() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 5);
        level.setBlockInMap(0, 1, 1, 1);
        level.setChunkInBlock(0, 1, 0, new ChunkDesc(1));
        level.setChunkInBlock(0, 0, 1, new ChunkDesc(2));
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(3));
        level.setPatternDescInChunk(3, 0, 0, new PatternDesc(31));
        level.setPatternDescInChunk(4, 1, 1, new PatternDesc(44));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.setWorldCursor(new EditorCursorState(80, 96));
        controller.selectBlock(0);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();
        controller.performEyedrop();
        controller.moveActiveSelection(1, 1);
        controller.applyPrimaryAction();

        assertEquals(1, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(3, level.getBlock(1).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(44, level.getChunk(4).getPatternDesc(1, 1).get());
        assertEquals(0, controller.selection().selectedBlock());
        assertEquals(3, controller.selection().selectedChunk());
    }

    @Test
    void controller_applyPrimaryActionInChunkDepthNoOpsWhenSelectedChunkDiffersFromActiveBlockCell() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 3, 6);
        level.setBlockInMap(0, 1, 1, 1);
        level.setChunkInBlock(0, 1, 0, new ChunkDesc(1));
        level.setChunkInBlock(0, 0, 1, new ChunkDesc(2));
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(3));
        level.setPatternDescInChunk(4, 0, 0, new PatternDesc(41));
        level.setPatternDescInChunk(5, 1, 1, new PatternDesc(55));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.setWorldCursor(new EditorCursorState(80, 96));
        controller.selectBlock(1);
        controller.descend();
        controller.selectChunk(4);
        controller.descend();
        controller.performEyedrop();
        controller.moveActiveSelection(1, 1);
        controller.applyPrimaryAction();

        assertEquals(1, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(3, level.getBlock(1).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(55, level.getChunk(5).getPatternDesc(1, 1).get());
        assertEquals(1, controller.selection().selectedBlock());
        assertEquals(4, controller.selection().selectedChunk());
    }

    @Test
    void controller_noOpUndoRedoPreserveSelectionInBlockDepth() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 2, 5);
        level.setBlockInMap(0, 1, 1, 1);
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(3));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.setWorldCursor(new EditorCursorState(80, 96));
        controller.selectBlock(0);
        controller.descend();
        controller.selectChunk(4);

        controller.undo();
        assertEquals(0, controller.selection().selectedBlock());
        assertEquals(4, controller.selection().selectedChunk());

        controller.redo();
        assertEquals(0, controller.selection().selectedBlock());
        assertEquals(4, controller.selection().selectedChunk());
    }

    @Test
    void controller_applyPrimaryActionInChunkDepthDoesNothingWithoutSelectedPatternDescriptor() {
        MutableLevel level = createMutableLevelWithChunkCount(2, 2, 2, 2, 5);
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(3));
        level.setPatternDescInChunk(4, 1, 1, new PatternDesc(44));
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);

        controller.selectBlock(1);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();
        controller.moveActiveSelection(1, 1);
        controller.applyPrimaryAction();

        assertEquals(3, level.getBlock(1).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(44, level.getChunk(4).getPatternDesc(1, 1).get());
    }

    @Test
    void controller_focusRegionCyclesThroughConcreteBlockDepthRegions() {
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.selectBlock(7);
        controller.descend();

        assertEquals(EditorFocusRegion.BLOCK_PANE, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.CHUNK_PANE, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.COMMAND_STRIP, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.TOOLBAR, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.BLOCK_PANE, controller.focusRegion());
    }

    @Test
    void controller_focusRegionCyclesThroughConcreteChunkDepthRegions() {
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.selectBlock(7);
        controller.descend();
        controller.selectChunk(0);
        controller.descend();

        assertEquals(EditorFocusRegion.CHUNK_PANE, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.PATTERN_PANE, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.COMMAND_STRIP, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.TOOLBAR, controller.focusRegion());

        controller.cycleFocusRegion();
        assertEquals(EditorFocusRegion.CHUNK_PANE, controller.focusRegion());
    }

    @Test
    void inputHandler_mapsDescendAndAscendActionsOntoController() {
        LevelEditorController controller = new LevelEditorController();
        EditorInputHandler handler = new EditorInputHandler(controller);

        controller.selectBlock(7);
        handler.handleAction(EditorInputHandler.Action.DESCEND);

        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());

        handler.handleAction(EditorInputHandler.Action.ASCEND);

        assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
    }

    @Test
    void inputHandler_updateMapsEscapeToAscendOnController() {
        LevelEditorController controller = new LevelEditorController();
        EditorInputHandler handler = new EditorInputHandler(controller);
        InputHandler inputHandler = new InputHandler();

        controller.selectBlock(7);
        controller.descend();

        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());

        inputHandler.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        handler.update(inputHandler);

        assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
    }

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

    @Test
    void inputHandler_updateRoutesTabToCycleFocusRegionInEditorMode() {
        LevelEditorController controller = new LevelEditorController();
        EditorInputHandler handler = new EditorInputHandler(controller);
        InputHandler input = new InputHandler();

        input.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        handler.update(input);

        assertEquals(EditorFocusRegion.BLOCK_PANE, controller.focusRegion());
    }

    @Test
    void inputHandler_updateRoutesSpaceToPrimaryActionInEditorMode() {
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        LevelEditorController controller = new LevelEditorController();
        EditorInputHandler handler = new EditorInputHandler(controller);
        InputHandler input = new InputHandler();

        controller.attachLevel(level);
        controller.selectBlock(2);
        controller.setWorldCursor(new EditorCursorState(65, 130));

        input.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        handler.update(input);

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 2)));
    }

    @Test
    void inputHandler_updateRoutesEToEyedropInEditorMode() {
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        LevelEditorController controller = new LevelEditorController();
        EditorInputHandler handler = new EditorInputHandler(controller);
        InputHandler input = new InputHandler();

        controller.attachLevel(level);
        level.setBlockInMap(0, 1, 2, 1);
        controller.selectBlock(2);
        controller.setWorldCursor(new EditorCursorState(65, 130));

        input.handleKeyEvent(GLFW_KEY_E, GLFW_PRESS);
        handler.update(input);

        assertEquals(1, controller.selection().selectedBlock());
    }

    @Test
    void inputHandler_updateRoutesCtrlZAndCtrlYToUndoRedoInEditorMode() {
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        LevelEditorController controller = new LevelEditorController();
        EditorInputHandler handler = new EditorInputHandler(controller);
        InputHandler input = new InputHandler();

        controller.attachLevel(level);
        controller.selectBlock(2);
        controller.setWorldCursor(new EditorCursorState(65, 130));
        controller.applyPrimaryAction();
        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 2)));

        input.handleKeyEvent(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_Z, GLFW_PRESS);
        handler.update(input);
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 2)));

        input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_Y, GLFW_PRESS);
        handler.update(input);
        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 2)));
    }

    @Test
    void inputHandler_publicActionsMatchImplementedMvpSurface() {
        assertArrayEquals(
                new EditorInputHandler.Action[] {
                        EditorInputHandler.Action.DESCEND,
                        EditorInputHandler.Action.ASCEND,
                        EditorInputHandler.Action.CYCLE_FOCUS_REGION,
                        EditorInputHandler.Action.APPLY_PRIMARY_ACTION,
                        EditorInputHandler.Action.PERFORM_EYEDROP,
                        EditorInputHandler.Action.TOGGLE_LAYER,
                        EditorInputHandler.Action.SAVE,
                        EditorInputHandler.Action.UNDO,
                        EditorInputHandler.Action.REDO
                },
                EditorInputHandler.Action.values());
    }

    @Test
    void gameLoop_editorModeUpdatesEditorInputHandlerBeforeAdvancingInputState() {
        TestEnvironment.activeGameplayMode();
        try {
            InputHandler inputHandler = new InputHandler();
            GameLoop gameLoop = new GameLoop(inputHandler);
            LevelEditorController controller = new LevelEditorController();
            EditorInputHandler editorInputHandler = new EditorInputHandler(controller);

            gameLoop.setEditorInputHandler(editorInputHandler);
            gameLoop.setGameMode(GameMode.EDITOR);

            controller.selectBlock(7);
            inputHandler.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);

            gameLoop.step();

            assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());
            assertFalse(inputHandler.isKeyPressed(GLFW_KEY_ENTER));
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void gameLoop_editorModeUpdatesCursorMovementBeforeAdvancingInputState() {
        TestEnvironment.activeGameplayMode();
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
            SessionManager.clear();
        }
    }

    @Test
    void gameLoop_shiftTabDoesNotInvokePlaytestToggleHandlerOutsideEditorMode() {
        TestEnvironment.activeGameplayMode();
        try {
            InputHandler inputHandler = new InputHandler();
            GameLoop gameLoop = new GameLoop(inputHandler);
            int[] toggleCount = {0};

            gameLoop.setEditorPlaytestToggleHandler(() -> toggleCount[0]++);
            gameLoop.pause();

            inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
            inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);

            gameLoop.step();

            assertEquals(0, toggleCount[0]);
            assertFalse(inputHandler.isKeyPressed(GLFW_KEY_TAB));
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void gameLoop_shiftTabInEditorModeInvokesPlaytestToggleHandler() {
        TestEnvironment.activeGameplayMode();
        try {
            InputHandler inputHandler = new InputHandler();
            GameLoop gameLoop = new GameLoop(inputHandler);
            int[] toggleCount = {0};

            gameLoop.setEditorPlaytestToggleHandler(() -> toggleCount[0]++);
            gameLoop.setGameMode(GameMode.EDITOR);

            inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
            inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);

            gameLoop.step();

            assertEquals(1, toggleCount[0]);
            assertFalse(inputHandler.isKeyPressed(GLFW_KEY_TAB));
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void gameLoop_plainTabInEditorModeDoesNotInvokePlaytestToggleHandler() {
        TestEnvironment.activeGameplayMode();
        try {
            InputHandler inputHandler = new InputHandler();
            GameLoop gameLoop = new GameLoop(inputHandler);
            int[] toggleCount = {0};

            gameLoop.setEditorPlaytestToggleHandler(() -> toggleCount[0]++);
            gameLoop.setGameMode(GameMode.EDITOR);

            inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);

            gameLoop.step();

            assertEquals(0, toggleCount[0]);
            assertFalse(inputHandler.isKeyPressed(GLFW_KEY_TAB));
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void gameLoop_rightShiftTabInEditorModeInvokesPlaytestToggleHandler() {
        TestEnvironment.activeGameplayMode();
        try {
            InputHandler inputHandler = new InputHandler();
            GameLoop gameLoop = new GameLoop(inputHandler);
            int[] toggleCount = {0};

            gameLoop.setEditorPlaytestToggleHandler(() -> toggleCount[0]++);
            gameLoop.setGameMode(GameMode.EDITOR);

            inputHandler.handleKeyEvent(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS);
            inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);

            gameLoop.step();

            assertEquals(1, toggleCount[0]);
            assertFalse(inputHandler.isKeyPressed(GLFW_KEY_TAB));
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void gameLoop_f5InEditorModeInvokesFreshStartHandler() {
        TestEnvironment.activeGameplayMode();
        try {
            InputHandler inputHandler = new InputHandler();
            GameLoop gameLoop = new GameLoop(inputHandler);
            int[] freshStartCount = {0};

            gameLoop.setEditorFreshStartHandler(() -> freshStartCount[0]++);
            gameLoop.setGameMode(GameMode.EDITOR);

            inputHandler.handleKeyEvent(GLFW_KEY_F5, GLFW_PRESS);

            gameLoop.step();

            assertEquals(1, freshStartCount[0]);
            assertFalse(inputHandler.isKeyPressed(GLFW_KEY_F5));
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void gameLoop_f5OutsideEditorModeDoesNotInvokeFreshStartHandler() {
        TestEnvironment.activeGameplayMode();
        try {
            InputHandler inputHandler = new InputHandler();
            GameLoop gameLoop = new GameLoop(inputHandler);
            int[] freshStartCount = {0};

            gameLoop.setEditorFreshStartHandler(() -> freshStartCount[0]++);
            gameLoop.pause();
            inputHandler.handleKeyEvent(GLFW_KEY_F5, GLFW_PRESS);

            gameLoop.step();

            assertEquals(0, freshStartCount[0]);
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void gameLoop_editorModeBypassesPauseToggleHandling() {
        TestEnvironment.activeGameplayMode();
        try {
            InputHandler inputHandler = new InputHandler();
            GameLoop gameLoop = new GameLoop(inputHandler);
            int pauseKey = SonicConfigurationService.getInstance().getInt(SonicConfiguration.PAUSE_KEY);

            gameLoop.setGameMode(GameMode.EDITOR);
            inputHandler.handleKeyEvent(pauseKey, GLFW_PRESS);

            gameLoop.step();

            assertFalse(gameLoop.isUserPaused());
            assertFalse(inputHandler.isKeyPressed(pauseKey));
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void controller_descendsAndAscendsHierarchyWithBreadcrumbs() {
        LevelEditorController controller = new LevelEditorController();

        assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
        assertEquals("World", controller.breadcrumb());

        controller.selectBlock(12);
        controller.descend();

        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());
        assertEquals("World > Block 12", controller.breadcrumb());

        controller.selectChunk(3);
        controller.descend();

        assertEquals(EditorHierarchyDepth.CHUNK, controller.depth());
        assertEquals("World > Block 12 > Chunk 3", controller.breadcrumb());

        controller.ascend();

        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());
        assertEquals("World > Block 12", controller.breadcrumb());

        controller.ascend();

        assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
        assertEquals("World", controller.breadcrumb());
    }

    @Test
    void controller_descendRequiresSelectionAtCurrentDepth() {
        LevelEditorController controller = new LevelEditorController();

        controller.descend();

        assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
        assertEquals("World", controller.breadcrumb());

        assertThrows(IllegalStateException.class, () -> controller.selectChunk(3));

        assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
        assertEquals("World", controller.breadcrumb());

        controller.selectBlock(12);
        controller.descend();

        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());
        assertEquals("World > Block 12", controller.breadcrumb());

        controller.descend();

        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());
        assertEquals("World > Block 12", controller.breadcrumb());
    }

    @Test
    void controller_selectBlockAtChunkDepthNormalizesToBlockDepth() {
        LevelEditorController controller = new LevelEditorController();

        controller.selectBlock(12);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();

        assertEquals(EditorHierarchyDepth.CHUNK, controller.depth());
        assertEquals("World > Block 12 > Chunk 3", controller.breadcrumb());

        controller.selectBlock(8);

        assertEquals(EditorHierarchyDepth.BLOCK, controller.depth());
        assertEquals("World > Block 8", controller.breadcrumb());
    }

    @Test
    void controller_rejectsChunkSelectionWithoutSelectedBlock() {
        LevelEditorController controller = new LevelEditorController();

        assertThrows(IllegalStateException.class, () -> controller.selectChunk(3));
    }

    @Test
    void controller_rejectsNegativeSelectionIndices() {
        LevelEditorController controller = new LevelEditorController();

        assertThrows(IllegalArgumentException.class, () -> controller.selectBlock(-1));

        controller.selectBlock(12);

        assertThrows(IllegalArgumentException.class, () -> controller.selectChunk(-1));
    }

    @Test
    void selectedBlockPreview_returnsSelectedBlockFromAttachedLevel() {
        LevelEditorController controller = new LevelEditorController();
        MutableLevel level = createMutableLevel(4, 3, 2, 3);
        controller.attachLevel(level);

        controller.selectBlock(1);

        assertSame(level.getBlock(1), controller.selectedBlockPreview());
    }

    @Test
    void selectedBlockCellPreview_returnsSelectedChunkFromAttachedLevel() {
        LevelEditorController controller = new LevelEditorController();
        MutableLevel level = createMutableLevelWithChunkCount(4, 3, 2, 3, 3);
        level.setChunkInBlock(1, 0, 0, new com.openggf.level.ChunkDesc(2));
        controller.attachLevel(level);

        controller.selectBlock(1);

        assertSame(level.getChunk(2), controller.selectedBlockCellPreview());
    }

    @Test
    void selectedBlockChunkPreview_returnsChunkForSpecifiedBlockCell() {
        LevelEditorController controller = new LevelEditorController();
        MutableLevel level = createMutableLevelWithChunkCount(4, 3, 2, 3, 4);
        level.setChunkInBlock(1, 0, 0, new com.openggf.level.ChunkDesc(3));
        controller.attachLevel(level);

        controller.selectBlock(1);

        assertSame(level.getChunk(3), controller.selectedBlockChunkPreview(0, 0));
    }

    @Test
    void selectedChunkPreview_returnsChunkFromCurrentSelection() {
        LevelEditorController controller = new LevelEditorController();
        MutableLevel level = createMutableLevelWithChunkCount(4, 3, 2, 6, 6);
        controller.attachLevel(level);
        level.setChunkInBlock(1, 0, 0, new com.openggf.level.ChunkDesc(3));

        controller.selectBlock(1);
        controller.selectChunk(3);

        assertSame(level.getChunk(3), controller.selectedChunkPreview());
    }

    @Test
    void controller_movesWorldCursorInWorldDepth() {
        LevelEditorController controller = new LevelEditorController();

        controller.setWorldCursor(new EditorCursorState(320, 448));
        controller.moveWorldCursor(-3, 6);

        assertEquals(317, controller.worldCursor().x());
        assertEquals(454, controller.worldCursor().y());
    }

    @Test
    void moveWorldCursor_clampsToAttachedLevelBounds() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createMutableLevel(4, 3, 2, 2));
        controller.setWorldCursor(new EditorCursorState(0, 0));

        controller.moveWorldCursor(-64, -32);
        assertEquals(16, controller.worldCursor().x());
        assertEquals(32, controller.worldCursor().y());

        controller.moveWorldCursor(9999, 9999);
        assertEquals(271, controller.worldCursor().x());
        assertEquals(223, controller.worldCursor().y());
    }

    @Test
    void setWorldCursor_clampsToAttachedLevelBounds() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createMutableLevel(4, 3, 2, 2));

        controller.setWorldCursor(new EditorCursorState(-100, -100));
        assertEquals(16, controller.worldCursor().x());
        assertEquals(32, controller.worldCursor().y());

        controller.setWorldCursor(new EditorCursorState(9999, 9999));
        assertEquals(271, controller.worldCursor().x());
        assertEquals(223, controller.worldCursor().y());
    }

    @Test
    void moveActiveSelection_inWorldDepthUsesClampedCursorMovement() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createMutableLevel(4, 3, 2, 2));
        controller.setWorldCursor(new EditorCursorState(270, 222));

        controller.moveActiveSelection(3, 3);

        assertEquals(271, controller.worldCursor().x());
        assertEquals(223, controller.worldCursor().y());
    }

    @Test
    void controller_arrowNavigationInBlockDepthMovesChunkSelectionInsteadOfWorldCursor() {
        LevelEditorController controller = new LevelEditorController();

        controller.setWorldCursor(new EditorCursorState(320, 448));
        controller.selectBlock(12);
        controller.descend();
        controller.moveActiveSelection(1, 0);

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
        controller.moveActiveSelection(1, 1);

        assertEquals(new EditorCursorState(320, 448), controller.worldCursor());
        assertEquals(1, controller.selectedChunkCellX());
        assertEquals(1, controller.selectedChunkCellY());
    }

    @Test
    void controller_attachLevelResetsNavigationState() {
        LevelEditorController controller = new LevelEditorController();
        controller.setWorldCursor(new EditorCursorState(320, 448));
        controller.selectBlock(12);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();
        controller.moveActiveSelection(1, 1);

        MutableLevel level = MutableLevel.snapshot(new NavigationResetSourceLevel());

        controller.attachLevel(level);

        assertEquals(new EditorCursorState(0, 0), controller.worldCursor());
        assertEquals(EditorHierarchyDepth.WORLD, controller.depth());
        assertEquals(2, controller.blockGridSide());
        assertEquals(0, controller.selectedBlockCellX());
        assertEquals(0, controller.selectedBlockCellY());
        assertEquals(0, controller.selectedChunkCellX());
        assertEquals(0, controller.selectedChunkCellY());
        assertEquals("World", controller.breadcrumb());
    }

    @Test
    void controller_clampsBlockSelectionToControllerOwnedGridBounds() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(MutableLevel.snapshot(new NavigationResetSourceLevel()));
        controller.selectBlock(12);
        controller.descend();

        controller.moveActiveSelection(-1, -1);
        assertEquals(0, controller.selectedBlockCellX());
        assertEquals(0, controller.selectedBlockCellY());

        controller.moveActiveSelection(10, 10);
        assertEquals(1, controller.selectedBlockCellX());
        assertEquals(1, controller.selectedBlockCellY());
    }

    @Test
    void controller_clampsChunkSelectionToControllerOwnedGridBounds() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(MutableLevel.snapshot(new NavigationResetSourceLevel()));
        controller.selectBlock(12);
        controller.descend();
        controller.selectChunk(3);
        controller.descend();

        controller.moveActiveSelection(-1, -1);
        assertEquals(0, controller.selectedChunkCellX());
        assertEquals(0, controller.selectedChunkCellY());

        controller.moveActiveSelection(10, 10);
        assertEquals(1, controller.selectedChunkCellX());
        assertEquals(1, controller.selectedChunkCellY());
    }

    private static LevelEditorController createControllerWithLevel() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createMutableLevel(4, 3, 2, 3));
        return controller;
    }

    private static MutableLevel createMutableLevel(int mapWidth, int mapHeight, int blockGridSide, int blockCount) {
        TestLevel level = new TestLevel(mapWidth, mapHeight, blockGridSide, blockCount, 1);
        return MutableLevel.snapshot(level);
    }

    private static MutableLevel createMutableLevelWithChunkCount(int mapWidth,
                                                                 int mapHeight,
                                                                 int blockGridSide,
                                                                 int blockCount,
                                                                 int chunkCount) {
        TestLevel level = new TestLevel(mapWidth, mapHeight, blockGridSide, blockCount, chunkCount);
        return MutableLevel.snapshot(level);
    }

    private static final class TestLevel extends AbstractLevel {
        private TestLevel(int mapWidth, int mapHeight, int blockGridSide, int blockCount, int chunkCount) {
            super(0);
            patternCount = 1;
            patterns = new Pattern[] { new Pattern() };
            this.chunkCount = chunkCount;
            chunks = new Chunk[chunkCount];
            for (int i = 0; i < chunkCount; i++) {
                chunks[i] = new Chunk();
            }
            this.blockCount = blockCount;
            blocks = new Block[blockCount];
            for (int i = 0; i < blockCount; i++) {
                blocks[i] = new Block(blockGridSide);
            }
            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            map = new Map(1, mapWidth, mapHeight);
            palettes = new Palette[] {
                    new Palette(), new Palette(), new Palette(), new Palette()
            };
            objects = List.of();
            rings = List.of();
            minX = 16;
            maxX = 271;
            minY = 32;
            maxY = 223;
        }

        @Override
        public int getChunksPerBlockSide() {
            return blocks.length > 0 ? blocks[0].getGridSide() : 0;
        }

        @Override
        public int getBlockPixelSize() {
            return getChunksPerBlockSide() * 32;
        }

        @Override
        public int getPaletteCount() {
            return palettes.length;
        }

        @Override
        public Palette getPalette(int index) {
            return palettes[index];
        }

        @Override
        public int getPatternCount() {
            return patternCount;
        }

        @Override
        public Pattern getPattern(int index) {
            return patterns[index];
        }

        @Override
        public int getChunkCount() {
            return chunkCount;
        }

        @Override
        public Chunk getChunk(int index) {
            return chunks[index];
        }

        @Override
        public int getBlockCount() {
            return blockCount;
        }

        @Override
        public Block getBlock(int index) {
            return blocks[index];
        }

        @Override
        public SolidTile getSolidTile(int index) {
            return solidTiles[index];
        }

        @Override
        public Map getMap() {
            return map;
        }

        @Override
        public List<ObjectSpawn> getObjects() {
            return List.of();
        }

        @Override
        public List<RingSpawn> getRings() {
            return List.of();
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }

        @Override
        public int getMinX() {
            return minX;
        }

        @Override
        public int getMaxX() {
            return maxX;
        }

        @Override
        public int getMinY() {
            return minY;
        }

        @Override
        public int getMaxY() {
            return maxY;
        }

        @Override
        public int getZoneIndex() {
            return 0;
        }
    }

    private static final class NavigationResetSourceLevel extends AbstractLevel {
        private NavigationResetSourceLevel() {
            super(0);
            patternCount = 0;
            patterns = new Pattern[0];
            chunkCount = 0;
            chunks = new Chunk[0];
            blockCount = 0;
            blocks = new Block[0];
            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            map = new Map(1, 1, 1);
            palettes = new Palette[0];
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 64;
            minY = 0;
            maxY = 64;
        }

        @Override
        public int getChunksPerBlockSide() {
            return 2;
        }

        @Override
        public int getBlockPixelSize() {
            return 32;
        }

        @Override
        public int getPaletteCount() {
            return 0;
        }

        @Override
        public Palette getPalette(int index) {
            return null;
        }

        @Override
        public int getPatternCount() {
            return 0;
        }

        @Override
        public Pattern getPattern(int index) {
            return null;
        }

        @Override
        public int getChunkCount() {
            return 0;
        }

        @Override
        public Chunk getChunk(int index) {
            return null;
        }

        @Override
        public int getBlockCount() {
            return 0;
        }

        @Override
        public Block getBlock(int index) {
            return null;
        }

        @Override
        public SolidTile getSolidTile(int index) {
            return null;
        }

        @Override
        public Map getMap() {
            return map;
        }

        @Override
        public List<ObjectSpawn> getObjects() {
            return List.of();
        }

        @Override
        public List<RingSpawn> getRings() {
            return List.of();
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }

        @Override
        public int getMinX() {
            return 0;
        }

        @Override
        public int getMaxX() {
            return 64;
        }

        @Override
        public int getMinY() {
            return 0;
        }

        @Override
        public int getMaxY() {
            return 64;
        }

        @Override
        public int getZoneIndex() {
            return 0;
        }
    }
}
