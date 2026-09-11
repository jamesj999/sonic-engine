package com.openggf.editor;

import com.openggf.game.session.EditorCursorState;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Map;
import com.openggf.level.MutableLevel;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestEditorBgLayer {

    @Test
    void toggleActiveLayerSwitchesBetweenForegroundAndBackground() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createMutableLevel());

        assertEquals(0, controller.activeLayer());

        controller.toggleActiveLayer();
        assertEquals(1, controller.activeLayer());

        controller.toggleActiveLayer();
        assertEquals(0, controller.activeLayer());
    }

    @Test
    void primaryPlacementWritesToActiveLayerOnly() {
        MutableLevel level = createMutableLevel();
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.selectBlock(2);
        controller.setWorldCursor(new EditorCursorState(32, 32));

        controller.toggleActiveLayer();
        controller.applyPrimaryAction();

        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertEquals(2, Byte.toUnsignedInt(level.getMap().getValue(1, 1, 1)));
    }

    @Test
    void eyedropReadsFromActiveLayer() {
        MutableLevel level = createMutableLevel();
        level.setBlockInMap(0, 1, 1, 1);
        level.setBlockInMap(1, 1, 1, 2);
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(level);
        controller.setWorldCursor(new EditorCursorState(32, 32));

        controller.performEyedrop();
        assertEquals(1, controller.selection().selectedBlock());

        controller.toggleActiveLayer();
        controller.performEyedrop();
        assertEquals(2, controller.selection().selectedBlock());
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
            map = new Map(2, 4, 4);
            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 128;
            minY = 0;
            maxY = 128;
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
