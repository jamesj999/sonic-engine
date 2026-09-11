package com.openggf.game.mutation;

import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Pattern;

import java.util.Arrays;
import java.util.Objects;

/**
 * {@link LevelMutationSurface} implementation for immutable-style {@link Level} instances.
 *
 * <p>This adapter writes directly into the live level structures and returns explicit
 * {@link MutationEffects} describing which redraws or pattern uploads the caller must perform
 * because the target level is not tracking dirty regions for us.
 */
public final class DirectLevelMutationSurface implements LevelMutationSurface {

    private final Level level;

    /** Creates a direct-write mutation surface for the supplied level snapshot. */
    public DirectLevelMutationSurface(Level level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public MutationEffects setPattern(int index, Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        level.ensurePatternCapacity(index + 1);
        Pattern target = level.getPattern(index);
        target.copyFrom(pattern);
        return MutationEffects.reuploadPattern(index);
    }

    @Override
    public MutationEffects restoreChunkState(int chunkIndex, int[] state) {
        int[] stateCopy = Arrays.copyOf(Objects.requireNonNull(state, "state"), state.length);
        if (level instanceof AbstractLevel abstractLevel) {
            Chunk replacement = new Chunk();
            replacement.restoreState(stateCopy);
            Chunk[] chunks = abstractLevel.chunksReference().clone();
            chunks[chunkIndex] = replacement;
            abstractLevel.replaceChunks(chunks);
            return MutationEffects.redrawAllTilemaps();
        }
        Chunk chunk = level.getChunk(chunkIndex);
        chunk.restoreState(stateCopy);
        return MutationEffects.redrawAllTilemaps();
    }

    @Override
    public MutationEffects restoreBlockState(int blockIndex, int[] state) {
        int[] stateCopy = Arrays.copyOf(Objects.requireNonNull(state, "state"), state.length);
        if (level instanceof AbstractLevel abstractLevel) {
            Block source = level.getBlock(blockIndex);
            Block replacement = new Block(source.getGridSide());
            replacement.restoreState(stateCopy);
            Block[] blocks = abstractLevel.blocksReference().clone();
            blocks[blockIndex] = replacement;
            abstractLevel.replaceBlocks(blocks);
            return MutationEffects.redrawAllTilemaps();
        }
        Block block = level.getBlock(blockIndex);
        block.restoreState(stateCopy);
        return MutationEffects.redrawAllTilemaps();
    }

    @Override
    public MutationEffects setBlockInMap(int layer, int blockX, int blockY, int blockIndex) {
        Map map = Objects.requireNonNull(level.getMap(), "level.map");
        // Ensure the map's data array is writable for CoW snapshot protection.
        if (level instanceof AbstractLevel) {
            map.cowEnsureWritable(((AbstractLevel) level).currentEpoch());
        }
        map.setValue(layer, blockX, blockY, (byte) blockIndex);
        return layer == 0 ? MutationEffects.foregroundRedraw() : MutationEffects.redrawAllTilemaps();
    }

    @Override
    public MutationEffects setBlockInMapWithoutRedraw(int layer, int blockX, int blockY, int blockIndex) {
        Map map = Objects.requireNonNull(level.getMap(), "level.map");
        // Ensure the map's data array is writable for CoW snapshot protection.
        if (level instanceof AbstractLevel) {
            map.cowEnsureWritable(((AbstractLevel) level).currentEpoch());
        }
        map.setValue(layer, blockX, blockY, (byte) blockIndex);
        return MutationEffects.NONE;
    }
}
