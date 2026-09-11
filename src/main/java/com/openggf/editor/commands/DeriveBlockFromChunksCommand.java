package com.openggf.editor.commands;

import com.openggf.editor.EditorCommand;
import com.openggf.level.ChunkDesc;
import com.openggf.level.MutableLevel;

import java.util.Arrays;
import java.util.Objects;

public final class DeriveBlockFromChunksCommand implements EditorCommand {
    private final MutableLevel level;
    private final int mapLayer;
    private final int mapX;
    private final int mapY;
    private final int sourceBlockIndex;
    private final int derivedBlockIndex;
    private final int[] sourceBlockState;
    private final int[] derivedBlockBeforeState;
    private final int replacementChunkRaw;
    private final int replaceX;
    private final int replaceY;

    public DeriveBlockFromChunksCommand(MutableLevel level, int mapLayer, int mapX, int mapY,
                                        int sourceBlockIndex, int derivedBlockIndex,
                                        int[] derivedBlockBeforeState, ChunkDesc replacementChunk,
                                        int replaceX, int replaceY) {
        this.level = Objects.requireNonNull(level, "level");
        Objects.requireNonNull(replacementChunk, "replacementChunk");
        validateIndices(level, sourceBlockIndex, derivedBlockIndex);
        this.mapLayer = mapLayer;
        this.mapX = mapX;
        this.mapY = mapY;
        this.sourceBlockIndex = sourceBlockIndex;
        this.derivedBlockIndex = derivedBlockIndex;
        this.sourceBlockState = level.getBlock(sourceBlockIndex).saveState();
        this.derivedBlockBeforeState = Arrays.copyOf(derivedBlockBeforeState, derivedBlockBeforeState.length);
        this.replacementChunkRaw = replacementChunk.get();
        this.replaceX = replaceX;
        this.replaceY = replaceY;
    }

    private static void validateIndices(MutableLevel level, int sourceBlockIndex, int derivedBlockIndex) {
        if (sourceBlockIndex < 0 || sourceBlockIndex >= level.getBlockCount()) {
            throw new IllegalArgumentException("sourceBlockIndex out of range: " + sourceBlockIndex);
        }
        if (derivedBlockIndex < 0 || derivedBlockIndex >= level.getBlockCount()) {
            throw new IllegalArgumentException("derivedBlockIndex out of range: " + derivedBlockIndex);
        }
        if (sourceBlockIndex == derivedBlockIndex) {
            throw new IllegalArgumentException("derivedBlockIndex must point at a fresh slot");
        }
        if (level.isBlockReferencedInMap(derivedBlockIndex)) {
            throw new IllegalArgumentException("derivedBlockIndex already referenced in map: " + derivedBlockIndex);
        }
    }

    @Override
    public void apply() {
        level.restoreBlockState(derivedBlockIndex, sourceBlockState);
        level.setChunkInBlock(derivedBlockIndex, replaceX, replaceY, new ChunkDesc(replacementChunkRaw));
        level.setBlockInMap(mapLayer, mapX, mapY, derivedBlockIndex);
    }

    @Override
    public void undo() {
        level.restoreBlockState(derivedBlockIndex, derivedBlockBeforeState);
        level.setBlockInMap(mapLayer, mapX, mapY, sourceBlockIndex);
    }
}
