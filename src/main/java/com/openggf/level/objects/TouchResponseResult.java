package com.openggf.level.objects;

public record TouchResponseResult(
        int sizeIndex,
        int widthRadius,
        int heightRadius,
        TouchCategory category,
        int shieldReactionFlags,
        int regionX) {

    /** Construct with no region X override (single-region or non-positional touch). */
    public TouchResponseResult(int sizeIndex, int widthRadius, int heightRadius, TouchCategory category) {
        this(sizeIndex, widthRadius, heightRadius, category, 0, Integer.MIN_VALUE);
    }

    /** Construct with shield reaction flags but no region X override. */
    public TouchResponseResult(int sizeIndex, int widthRadius, int heightRadius,
            TouchCategory category, int shieldReactionFlags) {
        this(sizeIndex, widthRadius, heightRadius, category, shieldReactionFlags, Integer.MIN_VALUE);
    }

    /** Returns true if this result carries a specific region X for hurt-direction computation. */
    public boolean hasRegionX() {
        return regionX != Integer.MIN_VALUE;
    }
}
