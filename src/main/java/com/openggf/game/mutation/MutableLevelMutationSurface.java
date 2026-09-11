package com.openggf.game.mutation;

import com.openggf.level.MutableLevel;
import com.openggf.level.Pattern;

import java.util.Objects;

/**
 * {@link LevelMutationSurface} implementation backed by {@link MutableLevel}.
 *
 * <p>{@code MutableLevel} already records dirty regions for block/chunk/pattern changes, so this
 * adapter mainly translates mutation calls into the common {@link MutationEffects} contract used
 * by {@link ZoneLayoutMutationPipeline}.
 */
public final class MutableLevelMutationSurface implements LevelMutationSurface {

    private final MutableLevel level;

    /** Creates a mutation surface that forwards writes into the mutable level editor model. */
    public MutableLevelMutationSurface(MutableLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public MutationEffects setPattern(int index, Pattern pattern) {
        level.setPattern(index, Objects.requireNonNull(pattern, "pattern"));
        return MutationEffects.dirtyRegionProcessing();
    }

    @Override
    public MutationEffects restoreChunkState(int chunkIndex, int[] state) {
        level.restoreChunkStateForRuntimeMutation(chunkIndex, Objects.requireNonNull(state, "state"));
        return MutationEffects.dirtyRegionProcessing();
    }

    @Override
    public MutationEffects restoreBlockState(int blockIndex, int[] state) {
        level.restoreBlockStateForRuntimeMutation(blockIndex, Objects.requireNonNull(state, "state"));
        return MutationEffects.dirtyRegionProcessing();
    }

    @Override
    public MutationEffects setBlockInMap(int layer, int blockX, int blockY, int blockIndex) {
        level.setBlockInMapForRuntimeMutation(layer, blockX, blockY, blockIndex);
        return MutationEffects.dirtyRegionProcessing();
    }

    @Override
    public MutationEffects setBlockInMapWithoutRedraw(int layer, int blockX, int blockY, int blockIndex) {
        level.setBlockInMapForRuntimeMutationWithoutRedraw(layer, blockX, blockY, blockIndex);
        return MutationEffects.NONE;
    }
}
