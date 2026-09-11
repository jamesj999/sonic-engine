package com.openggf.editor;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Map;
import com.openggf.level.MutableLevel;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestEditorMouseStrokeUndo {
    private GraphicsManager graphics;
    private Camera camera;

    @BeforeEach
    void setUp() {
        graphics = GraphicsManager.getInstance();
        graphics.setViewport(0, 0, 320, 224);
        camera = new Camera(SonicConfigurationService.getInstance());
        camera.setX((short) 0);
        camera.setY((short) 0);
    }

    @Test
    void leftClickCommitsOneUndoableStroke() {
        MutableLevel level = createMutableLevel();
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.selectBlock(2);
        EditorInputHandler handler = new EditorInputHandler(controller, () -> camera, () -> graphics);
        InputHandler input = new InputHandler();

        input.handleMouseMove(40, 40);
        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        handler.update(input);
        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        handler.update(input);

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        controller.undo();
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
    }

    @Test
    void leftDragAcrossCellsCommitsOneHistoryEntry() {
        MutableLevel level = createMutableLevel();
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.selectBlock(2);
        EditorInputHandler handler = new EditorInputHandler(controller, () -> camera, () -> graphics);
        InputHandler input = new InputHandler();

        input.handleMouseMove(40, 40);
        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        handler.update(input);
        input.handleMouseMove(72, 40);
        handler.update(input);
        input.handleMouseMove(104, 40);
        handler.update(input);
        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        handler.update(input);

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 2, 1)));
        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 3, 1)));

        controller.undo();

        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 2, 1)));
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 3, 1)));
        assertFalse(controller.hasUndoHistory());
    }

    @Test
    void finishActiveStrokeCommitsDragBeforeEditorExit() {
        MutableLevel level = createMutableLevel();
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.selectBlock(2);
        EditorInputHandler handler = new EditorInputHandler(controller, () -> camera, () -> graphics);
        InputHandler input = new InputHandler();

        input.handleMouseMove(40, 40);
        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        handler.update(input);

        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));

        handler.finishActiveStroke();

        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        handler.update(input);
        controller.undo();
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertFalse(controller.hasUndoHistory());
    }

    @Test
    void rightClickEyedropsHoveredCell() {
        MutableLevel level = createMutableLevel();
        level.setBlockInMap(0, 2, 1, 2);
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.selectBlock(1);
        EditorInputHandler handler = new EditorInputHandler(controller, () -> camera, () -> graphics);
        InputHandler input = new InputHandler();

        input.handleMouseMove(72, 40);
        input.handleMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);
        handler.update(input);

        assertEquals(2, controller.selection().selectedBlock());
    }

    @Test
    void mouseOutsideViewportDoesNotCommitAction() {
        graphics.setViewport(20, 20, 320, 224);
        MutableLevel level = createMutableLevel();
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.selectBlock(2);
        EditorInputHandler handler = new EditorInputHandler(controller, () -> camera, () -> graphics);
        InputHandler input = new InputHandler();

        input.handleMouseMove(10, 40);
        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        handler.update(input);
        input.handleMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        handler.update(input);

        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 0, 0)));
        assertFalse(controller.hasUndoHistory());
    }

    private static MutableLevel createMutableLevel() {
        return MutableLevel.snapshot(new SyntheticLevel());
    }

    private static final class SyntheticLevel extends AbstractLevel {
        private SyntheticLevel() {
            super(0);
            patternCount = 1;
            patterns = new Pattern[] { new Pattern() };
            chunkCount = 1;
            chunks = new Chunk[] { new Chunk() };
            blockCount = 3;
            blocks = new Block[] { new Block(1), new Block(1), new Block(1) };
            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            map = new Map(1, 8, 8);
            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 256;
            minY = 0;
            maxY = 256;
        }

        @Override
        public int getChunksPerBlockSide() {
            return 1;
        }

        @Override
        public int getBlockPixelSize() {
            return 32;
        }
    }
}
