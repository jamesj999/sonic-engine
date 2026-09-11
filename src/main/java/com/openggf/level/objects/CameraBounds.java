package com.openggf.level.objects;

/**
 * Pre-computed camera visibility bounds, updated once per frame.
 * ROM equivalent: Objects check against Camera_X_pos/Camera_Y_pos in MarkObjGone.
 * By caching these values, we avoid repeated camera service lookups
 * and field reads when checking visibility for many objects.
 *
 * Mutable to avoid per-frame allocation - use update() to change values.
 */
public final class CameraBounds {
    private int left;
    private int top;
    private int right;
    private int bottom;

    // Vertical wrap range for modular Y checks (0 = no wrapping).
    // When > 0, Y visibility uses modular arithmetic to emulate VDP coordinate wrapping.
    private int verticalWrapRange = 0;

    public CameraBounds(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * Updates all bounds in place, avoiding allocation.
     */
    public void update(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * Sets the vertical wrap range for modular Y checks.
     * @param range Wrap range in pixels (e.g. 2048 for LZ3/SBZ2), or 0 to disable.
     */
    public void setVerticalWrapRange(int range) {
        this.verticalWrapRange = range;
    }

    public int left() { return left; }
    public int top() { return top; }
    public int right() { return right; }
    public int bottom() { return bottom; }

    /**
     * Checks if a point is within these bounds.
     * When vertical wrapping is active, Y is checked using modular arithmetic.
     */
    public boolean contains(int x, int y) {
        if (x < left || x > right) return false;
        return containsY(y, 0);
    }

    /**
     * Checks if an X coordinate is within horizontal bounds.
     * Matches ROM's MarkObjGone which only checks X distance for the on_screen flag.
     */
    public boolean containsX(int x) {
        return x >= left && x <= right;
    }

    /**
     * Checks if an X coordinate is within horizontal bounds, expanded by a margin.
     */
    public boolean containsX(int x, int margin) {
        return x >= left - margin && x <= right + margin;
    }

    /**
     * Checks if a point is within these bounds with a margin.
     * When vertical wrapping is active, Y is checked using modular arithmetic.
     */
    public boolean contains(int x, int y, int margin) {
        if (x < left - margin || x > right + margin) return false;
        return containsY(y, margin);
    }

    /**
     * Checks if a point is within these bounds with independent horizontal and
     * vertical margins. ROM sprite visibility uses width_pixels and height_pixels
     * separately when setting render_flags bit 7.
     */
    public boolean contains(int x, int y, int xMargin, int yMargin) {
        if (x < left - xMargin || x > right + xMargin) return false;
        return containsY(y, yMargin);
    }

    /**
     * Checks the bounds used by the ROM sprite renderer when setting
     * {@code render_flags} bit 7.
     * <p>
     * S1 {@code BuildSprites}, S2 {@code BuildSprites}, and S3K
     * {@code Render_Sprites} reject the right/bottom edges with
     * {@code bge}/{@code bhs} after subtracting/adding object half-extents
     * (S1 BuildSprites.asm:44-60; S2 s2.asm:30372-30395; S3K
     * sonic3k.asm:36347-36365), so those upper edges are exclusive.
     * SolidObjectFull's on-screen gate reads that bit on the next object update;
     * using an inclusive upper bound keeps exact edge objects solid one frame
     * longer than the ROM.
     */
    public boolean containsRenderSpriteBounds(int x, int y, int xMargin, int yMargin) {
        if (x < left - xMargin || x >= right + xMargin) return false;
        return containsRenderSpriteY(y, yMargin);
    }

    /**
     * Checks if a Y coordinate is within the vertical bounds, optionally with margin.
     * When vertical wrapping is active, uses modular arithmetic: computes the shortest
     * distance in the wrapped space and checks if it falls within the screen height.
     */
    private boolean containsY(int y, int margin) {
        if (verticalWrapRange > 0) {
            int adjustedTop = top - margin;
            int height = (bottom - top) + 2 * margin;
            int diff = y - adjustedTop;
            diff = ((diff % verticalWrapRange) + verticalWrapRange) % verticalWrapRange;
            return diff <= height;
        }
        return y >= top - margin && y <= bottom + margin;
    }

    private boolean containsRenderSpriteY(int y, int margin) {
        if (verticalWrapRange > 0) {
            int adjustedTop = top - margin;
            int height = (bottom - top) + 2 * margin;
            int diff = y - adjustedTop;
            diff = ((diff % verticalWrapRange) + verticalWrapRange) % verticalWrapRange;
            return diff < height;
        }
        return y >= top - margin && y < bottom + margin;
    }
}
