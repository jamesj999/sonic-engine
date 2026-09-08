package com.openggf.game.mutation;

import java.util.BitSet;

/**
 * Side effects produced by one layout mutation.
 *
 * <p>Mutation code returns data changes separately from the engine work needed
 * to make those changes visible, such as dirty pattern uploads, redraws, or
 * object/ring spawn resync.
 */
@com.openggf.game.ModApi
public record MutationEffects(
        BitSet dirtyPatterns,
        boolean dirtyRegionProcessingRequired,
        boolean foregroundRedrawRequired,
        boolean allTilemapsRedrawRequired,
        boolean patternLookupRefreshRequired,
        boolean objectResyncRequired,
        boolean ringResyncRequired) {

    public static final MutationEffects NONE =
            new MutationEffects(new BitSet(), false, false, false, false, false, false);

    public MutationEffects {
        dirtyPatterns = dirtyPatterns == null ? new BitSet() : (BitSet) dirtyPatterns.clone();
    }

    /** Returns effects requesting dirty-region processing only. */
    public static MutationEffects dirtyRegionProcessing() {
        return new MutationEffects(new BitSet(), true, false, false, false, false, false);
    }

    /** Returns effects requesting foreground redraw only. */
    public static MutationEffects foregroundRedraw() {
        return new MutationEffects(new BitSet(), false, true, false, false, false, false);
    }

    /** Returns effects requesting full tilemap redraw. */
    public static MutationEffects redrawAllTilemaps() {
        return new MutationEffects(new BitSet(), false, false, true, false, false, false);
    }

    /**
     * Returns effects requesting a pattern atlas lookup refresh only.
     *
     * <p>Use this for mutations that rewrite 8x8 pattern data in place (art
     * overlays, PLC uploads) without changing which pattern indices the
     * tilemap cells reference. The tilemap bytes stay valid, so a full
     * redraw would only repeat the same build.
     */
    public static MutationEffects patternLookupRefresh() {
        return new MutationEffects(new BitSet(), false, false, false, true, false, false);
    }

    /** Alias for {@link #foregroundRedraw()}. */
    public static MutationEffects redraw() {
        return foregroundRedraw();
    }

    /** Returns effects requesting object spawn resynchronization. */
    public static MutationEffects objectResync() {
        return new MutationEffects(new BitSet(), false, false, false, false, true, false);
    }

    /** Returns effects requesting ring spawn resynchronization. */
    public static MutationEffects ringResync() {
        return new MutationEffects(new BitSet(), false, false, false, false, false, true);
    }

    /** Returns effects marking one pattern index for reupload. */
    public static MutationEffects reuploadPattern(int patternIndex) {
        BitSet dirtyPatterns = new BitSet();
        dirtyPatterns.set(patternIndex);
        return new MutationEffects(dirtyPatterns, false, false, false, false, false, false);
    }

    /** Returns {@code true} when one or more pattern uploads are required. */
    public boolean hasDirtyPatterns() {
        return !dirtyPatterns.isEmpty();
    }

    /** Returns {@code true} when the mutation produced no visible side effects. */
    public boolean isEmpty() {
        return dirtyPatterns.isEmpty()
                && !dirtyRegionProcessingRequired
                && !foregroundRedrawRequired
                && !allTilemapsRedrawRequired
                && !patternLookupRefreshRequired
                && !objectResyncRequired
                && !ringResyncRequired;
    }

    /**
     * Returns a copy of these effects with all redraw and dirty-region hints stripped.
     *
     * <p>Non-rendering side effects (dirty pattern uploads, object resync, ring resync)
     * are preserved so that the caller can still react to structural data changes.
     */
    public MutationEffects withoutRedrawHints() {
        if (!dirtyRegionProcessingRequired && !foregroundRedrawRequired && !allTilemapsRedrawRequired) {
            return this;
        }
        return new MutationEffects(dirtyPatterns, false, false, false,
                patternLookupRefreshRequired, objectResyncRequired, ringResyncRequired);
    }
}
