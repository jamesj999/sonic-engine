package com.openggf.level.scroll;

/**
 * Interface for zone-specific horizontal scroll routines.
 * Each implementation should match the corresponding SwScrl_* routine from the ROM.
 * 
 * Reference: s2.asm DeformBgLayer -> SwScrl_Index dispatch
 */
public interface ZoneScrollHandler {

    /**
     * Selects the Plane B tilemap residency model used by this handler.
     * Existing handlers retain the stateless window builder by default.
     */
    default BgTilemapUpdateMode getBgTilemapUpdateMode() {
        return BgTilemapUpdateMode.STATIC_WINDOW;
    }

    /**
     * Fill the horizontal scroll buffer for this zone.
     * 
     * This method should populate the 224-entry buffer with packed scroll values
     * where each entry contains (FG_scroll << 16) | (BG_scroll & 0xFFFF).
     * 
     * @param horizScrollBuf 224-entry buffer to fill with packed (FG,BG) scroll words
     * @param cameraX        Current foreground camera X position (pixels)
     * @param cameraY        Current foreground camera Y position (pixels)
     * @param frameCounter   Current frame number for animations
     * @param actId          Current act (0-based: 0 = Act 1, 1 = Act 2)
     */
    void update(int[] horizScrollBuf,
                int cameraX,
                int cameraY,
                int frameCounter,
                int actId);

    /**
     * Get the VScroll factor for Plane B (background).
     * This value will be written to VSRAM for vertical scrolling.
     * 
     * @return VScroll factor for background plane (16-bit signed)
     */
    short getVscrollFactorBG();

    /**
     * Optional per-scanline BG vertical scroll offsets.
     * <p>
     * When non-null, values are interpreted as signed pixel deltas that are
     * added on top of {@link #getVscrollFactorBG()} by the parallax shader.
     *
     * @return 224-entry per-line BG VScroll offset array, or null for flat VScroll
     */
    default short[] getPerLineVScrollBG() {
        return null;
    }

    /**
     * Optional per-column BG vertical scroll offsets (20 columns in H40 mode).
     * <p>
     * When non-null, values are interpreted as signed pixel deltas that are
     * added on top of {@link #getVscrollFactorBG()} by the parallax shader.
     *
     * @return 20-entry per-column BG VScroll offset array, or null for flat VScroll
     */
    default short[] getPerColumnVScrollBG() {
        return null;
    }

    /**
     * Optional per-column FG vertical scroll offsets (20 columns in H40 mode).
     * <p>
     * When non-null, values are absolute FG VScroll values (signed 16-bit, in
     * pixels) that override the flat {@link #getVscrollFactorFG()} value on a
     * per-column basis. Each entry maps to a 16-pixel-wide column of the Plane A
     * nametable, mirroring the hardware VDP per-column VSCROLL behavior.
     *
     * <p>ROM reference: Gumball_SetUpVScroll (s3.asm:76130) writes different
     * VScroll values to HScroll_table+$0, +$4, +$8, +$C, +$10 so that the
     * gumball machine body tiles drift with the machine object.
     *
     * @return 20-entry per-column FG VScroll array, or null for flat VScroll
     */
    default short[] getPerColumnVScrollFG() {
        return null;
    }

    /**
     * Get minimum BG scroll offset relative to FG for this frame.
     * Used by LevelManager to determine tile loading bounds.
     * 
     * @return Minimum (BG - FG) scroll offset
     */
    int getMinScrollOffset();

    /**
     * Get maximum BG scroll offset relative to FG for this frame.
     * Used by LevelManager to determine tile loading bounds.
     *
     * @return Maximum (BG - FG) scroll offset
     */
    int getMaxScrollOffset();

    /**
     * Get the main BG camera X position (v_bgscreenposx equivalent).
     * Used by LevelManager to determine which region of a wide BG map
     * to render into the 512px VDP nametable tilemap.
     *
     * <p>Zones with BG maps wider than 512px that do NOT tile at 512px
     * (e.g., SBZ with 15360px BG) must override this to return the
     * current BG camera X so the tilemap contains the correct tiles.
     *
     * @return BG camera X in pixels, or Integer.MIN_VALUE if the BG
     *         tiles naturally at 512px (default, no offset needed)
     */
    default int getBgCameraX() {
        return Integer.MIN_VALUE;
    }

    /**
     * Get the required BG tilemap period width in pixels.
     * <p>
     * Zones with multiple BG scroll speeds (e.g., GHZ with mountains at 96/256,
     * hills at 128/256, and water interpolated to camera speed) have a parallax
     * spread wider than the VDP's 512px nametable. The FBO must be wide enough
     * to cover the entire visible BG range without a wrap seam.
     * <p>
     * On the real hardware, the nametable is a persistent ring buffer that
     * accumulates column updates from multiple BG scroll blocks, masking the
     * 512px limitation. The engine rebuilds the tilemap from scratch, so it
     * must be wide enough to cover all visible scanlines simultaneously.
     *
     * @return Required BG period width in pixels (default: 512)
     */
    default int getBgPeriodWidth() {
        return 512;
    }

    /**
     * Get the VScroll factor for Plane A (foreground).
     * Most zones return 0 (foreground uses camera Y directly), but zones
     * with screen shake (MCZ, HTZ earthquake mode) modify the FG vertical
     * scroll to include ripple offsets.
     *
     * @return VScroll factor for foreground plane (16-bit signed), or 0 for default
     */
    default short getVscrollFactorFG() {
        return 0;
    }

    /**
     * Get the horizontal screen shake offset for this frame.
     * Zones with screen shake effects (ARZ, HTZ, MCZ) produce per-frame
     * horizontal offsets derived from ripple data. Used by LevelManager
     * to offset FG tiles and sprites.
     *
     * @return Horizontal shake offset in pixels, or 0 if no shake
     */
    default int getShakeOffsetX() {
        return 0;
    }

    /**
     * Get the vertical screen shake offset for this frame.
     * Zones with screen shake effects (ARZ, HTZ, MCZ) produce per-frame
     * vertical offsets derived from ripple data. Used by LevelManager
     * to offset FG tiles and sprites.
     *
     * @return Vertical shake offset in pixels, or 0 if no shake
     */
    default int getShakeOffsetY() {
        return 0;
    }

    /**
     * Initialize zone-specific scroll state on level load.
     * Called when entering a zone to set up background camera positions,
     * reset animation counters, and prepare zone-specific scroll data.
     *
     * @param actId   current act (0-based: 0 = Act 1, 1 = Act 2)
     * @param cameraX current foreground camera X position (pixels)
     * @param cameraY current foreground camera Y position (pixels)
     */
    default void init(int actId, int cameraX, int cameraY) {
        // no-op by default
    }

    /**
     * Capture per-frame LOGICAL scroll state that is not derivable from the
     * frame counter or the restored camera, for the rewind snapshot.
     * <p>
     * Most handlers return {@code null}: their scroll output is either a pure
     * function of the camera/frame (recomputed after a rewind restore) or a
     * frame-counter-derived accumulator (see {@link FrameScrollAccumulator}).
     * Handlers that own genuinely stateful logic — notably
     * {@link CameraDrivenScrollHandler}s that drive the camera from an internal
     * level-event routine and accumulate a background position each frame — must
     * override this so a rewind restores that state instead of re-simulating it
     * forward from stale values.
     *
     * @return an opaque, value-equal snapshot object, or {@code null} if the
     *         handler has no such state
     */
    default Object captureRewindState() {
        return null;
    }

    /**
     * Restore state previously produced by {@link #captureRewindState()}.
     * A {@code null} argument (no state was captured) is a no-op.
     */
    default void restoreRewindState(Object state) {
        // no-op by default
    }
}
