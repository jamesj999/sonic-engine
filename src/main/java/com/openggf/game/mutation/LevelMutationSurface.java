package com.openggf.game.mutation;

import com.openggf.level.Level;
import com.openggf.level.MutableLevel;
import com.openggf.level.Pattern;

import java.util.Objects;

/**
 * Abstraction over writable level data used by the mutation pipeline.
 *
 * <p>The surface hides whether the target level is a {@link MutableLevel} with
 * dirty-region tracking or a direct level structure that requires explicit
 * redraw/reupload effects.
 */
public interface LevelMutationSurface {

    /** Adapts the supplied level to the appropriate mutation surface implementation. */
    static LevelMutationSurface forLevel(Level level) {
        Objects.requireNonNull(level, "level");
        if (level instanceof MutableLevel mutableLevel) {
            return new MutableLevelMutationSurface(mutableLevel);
        }
        return new DirectLevelMutationSurface(level);
    }

    /** Replaces one pattern entry and returns the effects needed to reflect the change. */
    MutationEffects setPattern(int index, Pattern pattern);

    /** Restores one chunk descriptor/state entry from a previously captured snapshot. */
    MutationEffects restoreChunkState(int chunkIndex, int[] state);

    /** Restores one block descriptor/state entry from a previously captured snapshot. */
    MutationEffects restoreBlockState(int blockIndex, int[] state);

    /** Replaces a map block reference on the requested layer. */
    MutationEffects setBlockInMap(int layer, int blockX, int blockY, int blockIndex);

    /**
     * Replaces a map block reference on the requested layer without signalling any redraw.
     *
     * <p>Use this when the caller manages its own redraw sequencing and the
     * pipeline's automatic redraw publication would break the desired ordering
     * (e.g. snapshot-then-clear effects where the clear must be invisible until
     * the next explicit flush).  All non-rendering side effects (dirty patterns,
     * object/ring resync) are still returned.
     *
     * <p>The default implementation delegates to {@link #setBlockInMap} and strips
     * redraw hints via {@link MutationEffects#withoutRedrawHints()}. Implementations
     * may override for efficiency.
     */
    default MutationEffects setBlockInMapWithoutRedraw(int layer, int blockX, int blockY, int blockIndex) {
        return setBlockInMap(layer, blockX, blockY, blockIndex).withoutRedrawHints();
    }

    /** Requests a foreground redraw without performing any direct data change. */
    default MutationEffects requestRedraw() {
        return MutationEffects.redraw();
    }

    /** Requests object spawn list resynchronization after layout changes. */
    default MutationEffects requestObjectResync() {
        return MutationEffects.objectResync();
    }

    /** Requests ring spawn list resynchronization after layout changes. */
    default MutationEffects requestRingResync() {
        return MutationEffects.ringResync();
    }
}
