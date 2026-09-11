package com.openggf.editor;

import com.openggf.editor.commands.DeriveBlockFromChunksCommand;
import com.openggf.editor.commands.DeriveChunkFromPatternsCommand;
import com.openggf.editor.commands.PlaceBlockCommand;
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
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestEditorCommands {

    @Test
    void placeBlockCommand_mutatesMapAndUndoRestoresPreviousValue() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        int before = level.getMap().getValue(0, 1, 1) & 0xFF;
        PlaceBlockCommand command = new PlaceBlockCommand(level, 0, 1, 1, before, 2);

        command.apply();
        assertEquals(2, level.getMap().getValue(0, 1, 1) & 0xFF);

        command.undo();
        assertEquals(before, level.getMap().getValue(0, 1, 1) & 0xFF);
    }

    @Test
    void placeBlockCommand_keepsReverseLookupLiveForLaterBlockDirtying() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        int before = level.getMap().getValue(0, 1, 1) & 0xFF;
        PlaceBlockCommand command = new PlaceBlockCommand(level, 0, 1, 1, before, 2);

        command.apply();
        level.consumeDirtyMapCells();

        level.setChunkInBlock(2, 0, 0, new ChunkDesc(1));

        BitSet dirtyMapCells = level.consumeDirtyMapCells();
        assertEquals(2, level.getMap().getValue(0, 1, 1) & 0xFF);
        assertEquals(true, dirtyMapCells.get(3));
    }

    @Test
    void levelEditorController_placeBlockUndoRedoMutatesAttachedLevel() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        LevelEditorController controller = new LevelEditorController();

        controller.attachLevel(level);

        int before = level.getMap().getValue(0, 1, 1) & 0xFF;

        controller.placeBlock(0, 1, 1, 2);
        assertEquals(2, level.getMap().getValue(0, 1, 1) & 0xFF);

        controller.undo();
        assertEquals(before, level.getMap().getValue(0, 1, 1) & 0xFF);

        controller.redo();
        assertEquals(2, level.getMap().getValue(0, 1, 1) & 0xFF);
    }

    @Test
    void levelEditorController_attachLevelClearsHistoryForPreviousMutableLevel() {
        MutableLevel firstLevel = MutableLevel.snapshot(new SyntheticLevel());
        MutableLevel secondLevel = MutableLevel.snapshot(new SyntheticLevel());
        LevelEditorController controller = new LevelEditorController();

        controller.attachLevel(firstLevel);
        int firstBefore = firstLevel.getMap().getValue(0, 1, 1) & 0xFF;
        controller.placeBlock(0, 1, 1, 2);

        controller.attachLevel(secondLevel);
        controller.undo();

        assertEquals(2, firstLevel.getMap().getValue(0, 1, 1) & 0xFF);
        assertEquals(firstBefore, secondLevel.getMap().getValue(0, 1, 1) & 0xFF);
    }

    @Test
    void levelEditorController_placeBlockRequiresAttachedLevel() {
        LevelEditorController controller = new LevelEditorController();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.placeBlock(0, 1, 1, 2));

        assertEquals("No MutableLevel is attached to the editor controller", error.getMessage());
    }

    @Test
    void deriveBlockFromChunksCommand_clonesSourceBlockAndUndoRestoresDerivedBlockStateAndMapCell() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        int mapLayer = 0;
        int mapX = 1;
        int mapY = 0;
        int sourceBlockIndex = level.getMap().getValue(mapLayer, mapX, mapY) & 0xFF;
        int derivedBlockIndex = 2;
        int[] derivedBlockBeforeState = level.getBlock(derivedBlockIndex).saveState();
        int baselineChunkAt00 = level.getBlock(derivedBlockIndex).getChunkDesc(0, 0).get();
        int baselineChunkAt11 = level.getBlock(derivedBlockIndex).getChunkDesc(1, 1).get();
        ChunkDesc replacementChunk = new ChunkDesc(9);
        DeriveBlockFromChunksCommand command = new DeriveBlockFromChunksCommand(
                level,
                mapLayer,
                mapX,
                mapY,
                sourceBlockIndex,
                derivedBlockIndex,
                derivedBlockBeforeState,
                replacementChunk,
                1,
                1
        );

        command.apply();
        assertEquals(derivedBlockIndex, level.getMap().getValue(mapLayer, mapX, mapY) & 0xFF);
        assertEquals(9, level.getBlock(derivedBlockIndex).getChunkDesc(1, 1).getChunkIndex());
        assertEquals(1, level.getBlock(derivedBlockIndex).getChunkDesc(0, 0).getChunkIndex());

        command.undo();
        assertEquals(sourceBlockIndex, level.getMap().getValue(mapLayer, mapX, mapY) & 0xFF);
        assertEquals(baselineChunkAt00, level.getBlock(derivedBlockIndex).getChunkDesc(0, 0).get());
        assertEquals(baselineChunkAt11, level.getBlock(derivedBlockIndex).getChunkDesc(1, 1).get());
    }

    @Test
    void deriveBlockFromChunksCommand_undoRefreshesChunkReverseLookupForRestoredBlockState() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        DeriveBlockFromChunksCommand command = new DeriveBlockFromChunksCommand(
                level,
                0,
                1,
                0,
                level.getMap().getValue(0, 1, 0) & 0xFF,
                2,
                level.getBlock(2).saveState(),
                new ChunkDesc(4),
                0,
                0
        );

        command.apply();
        command.undo();
        level.consumeDirtyBlocks();
        level.consumeDirtyMapCells();

        level.setPatternDescInChunk(4, 0, 0, new PatternDesc(444));

        BitSet dirtyBlocks = level.consumeDirtyBlocks();
        assertFalse(dirtyBlocks.get(2));
    }

    @Test
    void deriveBlockFromChunksCommand_rejectsDerivedBlockSlotAlreadyUsedInMap() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        int sourceBlockIndex = level.getMap().getValue(0, 1, 0) & 0xFF;

        assertThrows(IllegalArgumentException.class, () -> new DeriveBlockFromChunksCommand(
                level,
                0,
                1,
                0,
                sourceBlockIndex,
                0,
                level.getBlock(0).saveState(),
                new ChunkDesc(9),
                1,
                1
        ));
    }

    @Test
    void deriveChunkFromPatternsCommand_clonesSourceChunkAndUndoRestoresDerivedChunkStateAndBlockReference() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        int blockIndex = 1;
        int blockX = 0;
        int blockY = 1;
        int sourceChunkIndex = level.getBlock(blockIndex).getChunkDesc(blockX, blockY).getChunkIndex();
        int derivedChunkIndex = 4;
        int[] derivedChunkBeforeState = level.getChunk(derivedChunkIndex).saveState();
        int baselinePatternAt00 = level.getChunk(derivedChunkIndex).getPatternDesc(0, 0).get();
        int baselinePatternAt10 = level.getChunk(derivedChunkIndex).getPatternDesc(1, 0).get();
        PatternDesc replacementPattern = new PatternDesc(77);
        DeriveChunkFromPatternsCommand command = new DeriveChunkFromPatternsCommand(
                level,
                blockIndex,
                blockX,
                blockY,
                sourceChunkIndex,
                derivedChunkIndex,
                derivedChunkBeforeState,
                replacementPattern,
                1,
                0
        );

        command.apply();
        assertEquals(derivedChunkIndex, level.getBlock(blockIndex).getChunkDesc(blockX, blockY).getChunkIndex());
        assertEquals(77, level.getChunk(derivedChunkIndex).getPatternDesc(1, 0).get());
        assertEquals(30, level.getChunk(derivedChunkIndex).getPatternDesc(0, 0).get());
        level.consumeDirtyChunks();

        command.undo();
        BitSet dirtyChunks = level.consumeDirtyChunks();
        assertEquals(sourceChunkIndex, level.getBlock(blockIndex).getChunkDesc(blockX, blockY).getChunkIndex());
        assertEquals(baselinePatternAt00, level.getChunk(derivedChunkIndex).getPatternDesc(0, 0).get());
        assertEquals(baselinePatternAt10, level.getChunk(derivedChunkIndex).getPatternDesc(1, 0).get());
        assertEquals(true, dirtyChunks.get(derivedChunkIndex));
    }

    @Test
    void deriveChunkFromPatternsCommand_preservesBlockCellDescriptorHighBitsWhenReplacingChunkIndex() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        int blockIndex = 1;
        int blockX = 0;
        int blockY = 1;
        int sourceChunkRaw = 0xFC03;
        int derivedChunkRaw = 0xFC04;
        level.setChunkInBlock(blockIndex, blockX, blockY, new ChunkDesc(sourceChunkRaw));
        DeriveChunkFromPatternsCommand command = new DeriveChunkFromPatternsCommand(
                level,
                blockIndex,
                blockX,
                blockY,
                3,
                4,
                level.getChunk(4).saveState(),
                new PatternDesc(77),
                1,
                0
        );

        command.apply();
        assertEquals(derivedChunkRaw, level.getBlock(blockIndex).getChunkDesc(blockX, blockY).get());

        command.undo();
        assertEquals(sourceChunkRaw, level.getBlock(blockIndex).getChunkDesc(blockX, blockY).get());
    }

    @Test
    void deriveChunkFromPatternsCommand_keepsReverseLookupLiveForLaterChunkDirtying() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());
        DeriveChunkFromPatternsCommand command = new DeriveChunkFromPatternsCommand(
                level,
                1,
                0,
                1,
                level.getBlock(1).getChunkDesc(0, 1).getChunkIndex(),
                4,
                level.getChunk(4).saveState(),
                new PatternDesc(77),
                1,
                0
        );

        command.apply();
        level.consumeDirtyBlocks();
        level.consumeDirtyMapCells();

        level.setPatternDescInChunk(4, 0, 0, new PatternDesc(123));

        BitSet dirtyBlocks = level.consumeDirtyBlocks();
        BitSet dirtyMapCells = level.consumeDirtyMapCells();
        assertEquals(true, dirtyBlocks.get(1));
        assertEquals(true, dirtyMapCells.get(1));
    }

    @Test
    void deriveChunkFromPatternsCommand_rejectsDerivedChunkSlotAlreadyReferencedByBlocks() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());

        assertThrows(IllegalArgumentException.class, () -> new DeriveChunkFromPatternsCommand(
                level,
                1,
                0,
                1,
                level.getBlock(1).getChunkDesc(0, 1).getChunkIndex(),
                3,
                level.getChunk(3).saveState(),
                new PatternDesc(77),
                1,
                0
        ));
    }

    private static final class SyntheticLevel extends AbstractLevel {
        private SyntheticLevel() {
            super(0);
            patternCount = 4;
            patterns = new Pattern[patternCount];
            for (int i = 0; i < patternCount; i++) {
                patterns[i] = new Pattern();
                patterns[i].setPixel(0, 0, (byte) (i + 1));
            }

            chunkCount = 5;
            chunks = new Chunk[chunkCount];
            for (int i = 0; i < chunkCount; i++) {
                chunks[i] = new Chunk();
                int[] state = new int[Chunk.PATTERNS_PER_CHUNK + 2];
                state[0] = i * 10;
                state[1] = i * 10 + 1;
                state[2] = i * 10 + 2;
                state[3] = i * 10 + 3;
                chunks[i].restoreState(state);
            }

            blockCount = 3;
            blocks = new Block[blockCount];
            for (int i = 0; i < blockCount; i++) {
                blocks[i] = new Block(2);
                blocks[i].setChunkDesc(0, 0, new ChunkDesc(i));
                blocks[i].setChunkDesc(1, 0, new ChunkDesc((i + 1) % 4));
                blocks[i].setChunkDesc(0, 1, new ChunkDesc((i + 2) % 4));
                blocks[i].setChunkDesc(1, 1, new ChunkDesc((i + 3) % 4));
            }

            solidTileCount = 1;
            solidTiles = new SolidTile[] {
                    new SolidTile(0, new byte[SolidTile.TILE_SIZE_IN_ROM], new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0)
            };

            map = new Map(1, 2, 2);
            map.setValue(0, 0, 0, (byte) 0);
            map.setValue(0, 1, 0, (byte) 1);
            map.setValue(0, 0, 1, (byte) 1);
            map.setValue(0, 1, 1, (byte) 0);

            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }

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
    }
}


