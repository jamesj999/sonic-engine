package com.openggf.editor.commands;

import com.openggf.editor.EditorCommand;
import com.openggf.level.ChunkDesc;
import com.openggf.level.MutableLevel;
import com.openggf.level.PatternDesc;

import java.util.Arrays;
import java.util.Objects;

public final class DeriveChunkFromPatternsCommand implements EditorCommand {
    private static final int CHUNK_INDEX_MASK = 0x03FF;

    private final MutableLevel level;
    private final int blockIndex;
    private final int blockX;
    private final int blockY;
    private final int sourceChunkIndex;
    private final int derivedChunkIndex;
    private final int[] blockBeforeState;
    private final int[] sourceChunkState;
    private final int[] derivedChunkBeforeState;
    private final int replacementPatternRaw;
    private final int replacementChunkRaw;
    private final int replaceX;
    private final int replaceY;

    public DeriveChunkFromPatternsCommand(MutableLevel level, int blockIndex, int blockX, int blockY,
                                          int sourceChunkIndex, int derivedChunkIndex,
                                          int[] derivedChunkBeforeState, PatternDesc replacementPattern,
                                          int replaceX, int replaceY) {
        this.level = Objects.requireNonNull(level, "level");
        Objects.requireNonNull(replacementPattern, "replacementPattern");
        validateIndices(level, sourceChunkIndex, derivedChunkIndex);
        this.blockIndex = blockIndex;
        this.blockX = blockX;
        this.blockY = blockY;
        this.sourceChunkIndex = sourceChunkIndex;
        this.derivedChunkIndex = derivedChunkIndex;
        this.blockBeforeState = level.getBlock(blockIndex).saveState();
        this.sourceChunkState = level.getChunk(sourceChunkIndex).saveState();
        this.derivedChunkBeforeState = Arrays.copyOf(derivedChunkBeforeState, derivedChunkBeforeState.length);
        this.replacementPatternRaw = replacementPattern.get();
        this.replacementChunkRaw = replaceChunkIndex(
                level.getBlock(blockIndex).getChunkDesc(blockX, blockY).get(),
                derivedChunkIndex);
        this.replaceX = replaceX;
        this.replaceY = replaceY;
    }

    private static void validateIndices(MutableLevel level, int sourceChunkIndex, int derivedChunkIndex) {
        if (sourceChunkIndex < 0 || sourceChunkIndex >= level.getChunkCount()) {
            throw new IllegalArgumentException("sourceChunkIndex out of range: " + sourceChunkIndex);
        }
        if (derivedChunkIndex < 0 || derivedChunkIndex >= level.getChunkCount()) {
            throw new IllegalArgumentException("derivedChunkIndex out of range: " + derivedChunkIndex);
        }
        if (sourceChunkIndex == derivedChunkIndex) {
            throw new IllegalArgumentException("derivedChunkIndex must point at a fresh slot");
        }
        if (level.isChunkReferencedInBlocks(derivedChunkIndex)) {
            throw new IllegalArgumentException("derivedChunkIndex already referenced by a block: " + derivedChunkIndex);
        }
    }

    private static int replaceChunkIndex(int descriptorRaw, int chunkIndex) {
        return (descriptorRaw & ~CHUNK_INDEX_MASK) | (chunkIndex & CHUNK_INDEX_MASK);
    }

    @Override
    public void apply() {
        level.restoreChunkState(derivedChunkIndex, sourceChunkState);
        level.setPatternDescInChunk(derivedChunkIndex, replaceX, replaceY, new PatternDesc(replacementPatternRaw));
        level.setChunkInBlock(blockIndex, blockX, blockY, new ChunkDesc(replacementChunkRaw));
    }

    @Override
    public void undo() {
        level.restoreChunkState(derivedChunkIndex, derivedChunkBeforeState);
        level.restoreBlockState(blockIndex, blockBeforeState);
    }
}
