package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.GameModule;
import com.openggf.game.ScrollHandlerProvider;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.ParallaxSnapshot;
import com.openggf.level.scroll.BgTilemapUpdateMode;
import com.openggf.level.scroll.CameraDrivenScrollHandler;
import com.openggf.level.scroll.ZoneScrollHandler;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Manages parallax scrolling effects.
 * Outputs scroll values compatible with the LevelManager rendering system.
 *
 * <p>All game-specific scroll logic is provided by the current
 * {@link ScrollHandlerProvider} obtained from the active {@link GameModule}.
 * ParallaxManager itself contains no game-specific imports or constants.
 */
public class ParallaxManager implements RewindSnapshottable<ParallaxSnapshot> {
    private static final Logger LOGGER = Logger.getLogger(ParallaxManager.class.getName());

    public static final int VISIBLE_LINES = 224;

    // Packed as (planeA << 16) | (planeB & 0xFFFF)
    private final int[] hScroll = new int[VISIBLE_LINES];
    // Optional per-line BG VScroll deltas (added on top of vscrollFactorBG).
    private final short[] vScrollPerLineBG = new short[VISIBLE_LINES];
    // Per-column VScroll buffer size: ceil(screenWidth / 16). 20 at native 320px.
    private final int BG_VSCROLL_COLUMN_COUNT;
    private final short[] vScrollPerColumnBG;
    private final int FG_VSCROLL_COLUMN_COUNT;
    private final short[] vScrollPerColumnFG;

    public ParallaxManager() {
        int screenWidth;
        try {
            screenWidth = GameServices.configuration().getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        } catch (Exception e) {
            screenWidth = 320;
        }
        BG_VSCROLL_COLUMN_COUNT = columnCount(screenWidth);
        FG_VSCROLL_COLUMN_COUNT = columnCount(screenWidth);
        vScrollPerColumnBG = new short[BG_VSCROLL_COLUMN_COUNT];
        vScrollPerColumnFG = new short[FG_VSCROLL_COLUMN_COUNT];
    }

    private static int columnCount(int width) {
        return (width + 15) / 16;
    }

    private boolean hasPerLineVScrollBG = false;
    private boolean hasPerColumnVScrollBG = false;
    private boolean hasPerColumnVScrollFG = false;

    private int minScroll = 0;
    private int maxScroll = 0;

    private short vscrollFactorFG;
    private short vscrollFactorBG;

    // Game-agnostic scroll handler provider (from current GameModule)
    private ScrollHandlerProvider scrollProvider;
    private boolean providerLoaded = false;

    private int currentZone = -1;
    private int currentAct = -1;

    // Screen shake offsets propagated from zone handlers
    private int currentShakeOffsetX = 0;
    private int currentShakeOffsetY = 0;

    // Cached BG camera X from the active scroll handler (Integer.MIN_VALUE = no offset)
    private int cachedBgCameraX = Integer.MIN_VALUE;
    private int cachedBgPeriodWidth = 512;
    private BgTilemapUpdateMode cachedBgTilemapUpdateMode = BgTilemapUpdateMode.STATIC_WINDOW;

    /**
     * Reset zone state to force reinitialization on next initZone call.
     * Useful for tests to ensure deterministic state.
     */
    public void resetZoneState() {
        currentZone = -1;
        currentAct = -1;
        if (scrollProvider != null) {
            scrollProvider.resetZoneState();
        }
    }

    /**
     * Resets all mutable state without destroying the singleton instance.
     * Clears scroll factors and provider so the next load starts fresh.
     */
    public void resetState() {
        resetZoneState();
        scrollProvider = null;
        providerLoaded = false;
        vscrollFactorFG = 0;
        vscrollFactorBG = 0;
        currentShakeOffsetX = 0;
        currentShakeOffsetY = 0;
        cachedBgCameraX = Integer.MIN_VALUE;
        cachedBgPeriodWidth = 512;
        cachedBgTilemapUpdateMode = BgTilemapUpdateMode.STATIC_WINDOW;
        minScroll = 0;
        maxScroll = 0;
        java.util.Arrays.fill(hScroll, 0);
        java.util.Arrays.fill(vScrollPerLineBG, (short) 0);
        java.util.Arrays.fill(vScrollPerColumnBG, (short) 0);
        java.util.Arrays.fill(vScrollPerColumnFG, (short) 0);
        hasPerLineVScrollBG = false;
        hasPerColumnVScrollBG = false;
        hasPerColumnVScrollFG = false;
    }

    public void load(Rom rom) {
        if (providerLoaded) {
            return;
        }

        // Get the game-specific scroll handler provider
        GameModule module = GameServices.module();

        if (module != null) {
            scrollProvider = module.getScrollHandlerProvider();
            if (scrollProvider != null) {
                try {
                    scrollProvider.load(rom);
                    providerLoaded = true;
                } catch (IOException e) {
                    LOGGER.warning("Failed to load game scroll provider: " + e.getMessage());
                    scrollProvider = null;
                }
            }
        }
    }

    /**
     * Compatibility overload for callers that supplied the obsolete game-level
     * background-Y hint. The active zone handler is authoritative.
     */
    @Deprecated
    public void update(int zoneId, int actId, Camera camera, int frameCounter, int ignoredBackgroundY) {
        update(zoneId, actId, camera, frameCounter);
    }

    /** Compatibility overload retaining the obsolete background-Y hint. */
    @Deprecated
    public void update(int zoneId, int actId, Camera camera, int frameCounter,
            int ignoredBackgroundY, Level level) {
        update(zoneId, actId, camera, frameCounter, level);
    }

    public void initZone(int zoneId, int actId, int cameraX, int cameraY) {
        if (zoneId != currentZone || actId != currentAct) {
            currentZone = zoneId;
            currentAct = actId;
            if (scrollProvider != null) {
                scrollProvider.initForZone(zoneId, actId, cameraX, cameraY);
            }
        }
    }

    public int[] getHScroll() {
        return hScroll;
    }

    public int getMinScroll() {
        return minScroll;
    }

    public int getMaxScroll() {
        return maxScroll;
    }

    /**
     * Get the raw hScroll buffer for shader-based rendering.
     * This returns the packed (FG << 16 | BG) format array.
     * The BackgroundRenderer extracts BG values during upload.
     */
    public int[] getHScrollForShader() {
        return hScroll;
    }

    public short getVscrollFactorFG() {
        return vscrollFactorFG;
    }

    public short getVscrollFactorBG() {
        return vscrollFactorBG;
    }

    /**
     * Returns the active zone scroll handler for the given zone, or null.
     * Used by event handlers that need to communicate directly with
     * their zone's scroll handler (e.g., HCZ2 wall-chase driving SwScrlHcz).
     */
    public ZoneScrollHandler getHandler(int zoneId) {
        return scrollProvider != null ? scrollProvider.getHandler(zoneId) : null;
    }

    /**
     * Optional per-line BG VScroll values for shader-based heat haze effects.
     *
     * @return 224-entry per-line VScroll array, or null when not active
     */
    public short[] getVScrollPerLineBGForShader() {
        return hasPerLineVScrollBG ? vScrollPerLineBG : null;
    }

    /**
     * Optional per-column BG VScroll values for shader-based column distortion effects.
     *
     * @return per-column VScroll array (ceil(screenWidth/16) entries), or null when not active
     */
    public short[] getVScrollPerColumnBGForShader() {
        return hasPerColumnVScrollBG ? vScrollPerColumnBG : null;
    }

    /**
     * Optional per-column FG VScroll values for shader-based column distortion effects.
     * Used by the S3K Gumball bonus stage to make machine body tiles drift with the
     * gumball machine object.
     *
     * @return per-column FG VScroll array (ceil(screenWidth/16) entries), or null when not active
     */
    public short[] getVScrollPerColumnFGForShader() {
        return hasPerColumnVScrollFG ? vScrollPerColumnFG : null;
    }

    /**
     * Get the BG camera X position from the active scroll handler.
     * Used by LevelManager to determine which region of a wide BG map
     * to render into the 512px VDP nametable tilemap.
     *
     * @return BG camera X in pixels, or Integer.MIN_VALUE if no offset needed
     */
    public int getBgCameraX() {
        return cachedBgCameraX;
    }

    /**
     * Get the required BG tilemap period width from the active scroll handler.
     *
     * @return Period width in pixels (default 512)
     */
    public int getBgPeriodWidth() {
        return cachedBgPeriodWidth;
    }

    /** Returns the active handler's Plane B residency model. */
    public BgTilemapUpdateMode getBgTilemapUpdateMode() {
        return cachedBgTilemapUpdateMode;
    }

    /**
     * Get the current horizontal shake offset for this frame.
     * This is propagated from the active zone scroll handler when screen shake is active.
     * Used by LevelManager to set camera shake offsets for FG tiles and sprites.
     *
     * @return Horizontal shake offset in pixels, or 0 if no shake
     */
    public int getShakeOffsetX() {
        return currentShakeOffsetX;
    }

    /**
     * Get the current vertical shake offset for this frame.
     * This is propagated from the active zone scroll handler when screen shake is active.
     * Used by LevelManager to set camera shake offsets for FG tiles and sprites.
     *
     * @return Vertical shake offset in pixels, or 0 if no shake
     */
    public int getShakeOffsetY() {
        return currentShakeOffsetY;
    }

    /**
     * Get the current Tornado X velocity (pixels per frame) for SCZ objects.
     * ROM: loc_36776 adds Tornado_Velocity_X to object x_pos each frame.
     */
    public int getTornadoVelocityX() {
        return scrollProvider != null ? scrollProvider.getTornadoVelocityX() : 0;
    }

    /**
     * Get the current Tornado Y velocity (pixels per frame) for SCZ objects.
     * ROM: loc_36776 adds Tornado_Velocity_Y to object y_pos each frame.
     */
    public int getTornadoVelocityY() {
        return scrollProvider != null ? scrollProvider.getTornadoVelocityY() : 0;
    }

    /**
     * Get the current background camera X offset used by WFZ scripted objects.
     * ROM equivalent: Camera_BG_X_offset.
     */
    public int getCameraBgXOffset() {
        return scrollProvider != null ? scrollProvider.getCameraBgXOffset() : 0;
    }

    /**
     * Set the screen shake flag for MCZ.
     * @deprecated Use GameServices.gameState().setScreenShakeActive() directly.
     *             Screen shake is now a global state that all zones check.
     */
    @Deprecated
    public void setScreenShakeFlag(boolean screenShakeFlag) {
        GameServices.gameState().setScreenShakeActive(screenShakeFlag);
    }

    public void update(int zoneId, int actId, Camera cam, int frameCounter) {
        // Clear scroll buffer to ensure deterministic state
        // (some zone handlers intentionally leave lines unwritten)
        java.util.Arrays.fill(hScroll, 0);

        minScroll = Integer.MAX_VALUE;
        maxScroll = Integer.MIN_VALUE;

        // Reset shake offsets at start of frame
        currentShakeOffsetX = 0;
        currentShakeOffsetY = 0;
        java.util.Arrays.fill(vScrollPerLineBG, (short) 0);
        java.util.Arrays.fill(vScrollPerColumnBG, (short) 0);
        java.util.Arrays.fill(vScrollPerColumnFG, (short) 0);
        hasPerLineVScrollBG = false;
        hasPerColumnVScrollBG = false;
        hasPerColumnVScrollFG = false;

        int cameraX = cam.getX();
        int cameraY = cam.getY();
        vscrollFactorFG = (short) cameraY;

        // Unified provider-based dispatch for all games
        if (scrollProvider != null) {
            initZone(zoneId, actId, cameraX, cameraY);

            ZoneScrollHandler handler = scrollProvider.getHandler(zoneId);
            if (handler != null) {
                handler.update(hScroll, cameraX, cameraY, frameCounter, actId);
                minScroll = handler.getMinScrollOffset();
                maxScroll = handler.getMaxScrollOffset();
                vscrollFactorBG = handler.getVscrollFactorBG();
                currentShakeOffsetX = handler.getShakeOffsetX();
                currentShakeOffsetY = handler.getShakeOffsetY();
                cachedBgCameraX = handler.getBgCameraX();
                cachedBgPeriodWidth = handler.getBgPeriodWidth();
                cachedBgTilemapUpdateMode = handler.getBgTilemapUpdateMode();
                capturePerLineVScroll(handler);
                capturePerColumnVScroll(handler);
                capturePerColumnVScrollFG(handler);

                // FG vscroll: handlers that apply screen shake (MCZ, HTZ earthquake)
                // return a non-zero value including the ripple offset.
                // Handlers that don't override FG vscroll return 0 (the default).
                // SCZ modifies camera position directly during update(), so we
                // re-read cam.getY() for the default case.
                short handlerFgVscroll = handler.getVscrollFactorFG();
                if (handlerFgVscroll != 0) {
                    vscrollFactorFG = handlerFgVscroll;
                } else {
                    vscrollFactorFG = (short) cam.getY();
                }
            } else {
                cachedBgCameraX = Integer.MIN_VALUE;
                cachedBgPeriodWidth = 512;
                cachedBgTilemapUpdateMode = BgTilemapUpdateMode.STATIC_WINDOW;
                fillMinimal(cam);
            }
        } else {
            cachedBgTilemapUpdateMode = BgTilemapUpdateMode.STATIC_WINDOW;
            fillMinimal(cam);
        }
    }

    /**
     * Advances scroll routines that drive foreground camera movement as gameplay
     * state. Render-time parallax updates must only consume the resulting state.
     */
    public boolean advanceCameraDrivenScroll(int zoneId, int actId, Camera cam, int frameCounter) {
        if (!providerLoaded) {
            try {
                Rom rom = GameServices.rom().getRom();
                if (rom != null) {
                    load(rom);
                }
            } catch (IOException e) {
                if (RomManager.isConfiguredRomMissing(e)) {
                    LOGGER.fine(() -> "Skipped lazy scroll-provider load: " + e.getMessage());
                } else {
                    LOGGER.warning("Failed to lazy-load scroll provider: " + e.getMessage());
                }
            }
        }
        if (scrollProvider == null || cam == null) {
            return false;
        }
        initZone(zoneId, actId, cam.getX(), cam.getY());
        ZoneScrollHandler handler = scrollProvider.getHandler(zoneId);
        if (handler instanceof CameraDrivenScrollHandler cameraDriven) {
            return cameraDriven.advanceCameraForFrame(cam, actId);
        }
        return false;
    }

    /**
     * Update parallax scrolling with dynamic art streaming support.
     * This overload handles zone-specific dynamic art updates (HTZ mountains/clouds).
     *
     * @param zoneId Zone identifier
     * @param actId Act identifier
     * @param cam Camera instance
     * @param frameCounter Current frame counter
     * @param level Level instance for dynamic art updates (may be null)
     */
    public void update(int zoneId, int actId, Camera cam, int frameCounter, Level level) {
        // Perform standard parallax update
        update(zoneId, actId, cam, frameCounter);

        // Update zone-specific dynamic art via the provider
        if (scrollProvider != null && level != null) {
            scrollProvider.updateDynamicArt(level, cam.getX());
        }
    }

    /**
     * Update parallax for the ending cutscene.
     * <p>
     * Uses camera (0,0) and a fixed BG vscroll value from the ending provider.
     * Delegates to the scroll provider's ending handler for the
     * zone-specific scroll behavior during the ending sequence.
     *
     * @param zoneId     zone ID for the ending zone
     * @param actId      act ID
     * @param frameCounter current frame counter
     * @param bgVscroll  ending BG vertical scroll (ROM: Camera_BG_Y_pos)
     */
    public void updateForEnding(int zoneId, int actId, int frameCounter, int bgVscroll) {
        java.util.Arrays.fill(hScroll, 0);
        minScroll = Integer.MAX_VALUE;
        maxScroll = Integer.MIN_VALUE;

        // Camera is (0,0) during ending
        vscrollFactorFG = 0;
        vscrollFactorBG = (short) bgVscroll;
        cachedBgTilemapUpdateMode = BgTilemapUpdateMode.STATIC_WINDOW;

        // Initialize zone if needed
        initZone(zoneId, actId, 0, 0);

        // Delegate to the provider for game-specific ending scroll
        if (scrollProvider != null) {
            if (scrollProvider.updateForEnding(hScroll, zoneId, actId, frameCounter, vscrollFactorBG)) {
                // Provider handled the ending - extract results from the handler
                ZoneScrollHandler handler = scrollProvider.getHandler(zoneId);
                if (handler != null) {
                    minScroll = handler.getMinScrollOffset();
                    maxScroll = handler.getMaxScrollOffset();
                    vscrollFactorBG = handler.getVscrollFactorBG();
                    cachedBgCameraX = handler.getBgCameraX();
                    cachedBgPeriodWidth = handler.getBgPeriodWidth();
                    cachedBgTilemapUpdateMode = handler.getBgTilemapUpdateMode();
                }
            }
        }
    }

    // ========== Helper Methods ==========

    private static int packScrollWords(short fg, short bg) {
        return ((fg & 0xFFFF) << 16) | (bg & 0xFFFF);
    }

    private void fillMinimal(Camera cam) {
        short fgScroll = (short) -cam.getX();
        int offset = cam.getX() >> 1;
        short bgScroll = (short) (fgScroll + offset);
        int packed = packScrollWords(fgScroll, bgScroll);

        int offsetShort = (short) offset;
        minScroll = offsetShort;
        maxScroll = offsetShort;

        for (int line = 0; line < VISIBLE_LINES; line++) {
            hScroll[line] = packed;
        }
    }

    private void capturePerLineVScroll(ZoneScrollHandler handler) {
        short[] perLine = handler.getPerLineVScrollBG();
        if (perLine == null || perLine.length == 0) {
            hasPerLineVScrollBG = false;
            return;
        }
        int count = Math.min(VISIBLE_LINES, perLine.length);
        System.arraycopy(perLine, 0, vScrollPerLineBG, 0, count);
        if (count < VISIBLE_LINES) {
            java.util.Arrays.fill(vScrollPerLineBG, count, VISIBLE_LINES, (short) 0);
        }
        hasPerLineVScrollBG = true;
    }

    private void capturePerColumnVScroll(ZoneScrollHandler handler) {
        short[] perColumn = handler.getPerColumnVScrollBG();
        if (perColumn == null || perColumn.length == 0) {
            hasPerColumnVScrollBG = false;
            return;
        }
        int count = Math.min(BG_VSCROLL_COLUMN_COUNT, perColumn.length);
        System.arraycopy(perColumn, 0, vScrollPerColumnBG, 0, count);
        if (count < BG_VSCROLL_COLUMN_COUNT) {
            java.util.Arrays.fill(vScrollPerColumnBG, count, BG_VSCROLL_COLUMN_COUNT, (short) 0);
        }
        hasPerColumnVScrollBG = true;
    }

    private void capturePerColumnVScrollFG(ZoneScrollHandler handler) {
        short[] perColumn = handler.getPerColumnVScrollFG();
        if (perColumn == null || perColumn.length == 0) {
            hasPerColumnVScrollFG = false;
            return;
        }
        int count = Math.min(FG_VSCROLL_COLUMN_COUNT, perColumn.length);
        System.arraycopy(perColumn, 0, vScrollPerColumnFG, 0, count);
        if (count < FG_VSCROLL_COLUMN_COUNT) {
            java.util.Arrays.fill(vScrollPerColumnFG, count, FG_VSCROLL_COLUMN_COUNT, (short) 0);
        }
        hasPerColumnVScrollFG = true;
    }

    // ── RewindSnapshottable ───────────────────────────────────────────────

    @Override
    public String key() {
        return "parallax";
    }

    @Override
    public ParallaxSnapshot capture() {
        // Dense/scalar parallax is derived from the restored camera/frame/zone
        // and recomputed after the registry restore. The only state that must be
        // carried is the active handler's genuinely-stateful logical scroll
        // state (e.g. SCZ's camera-driven BG accumulator + level-event routine),
        // which is not recomputable from the camera/frame.
        return new ParallaxSnapshot(captureActiveHandlerRewindState());
    }

    @Override
    public void restore(ParallaxSnapshot s) {
        // Dense/scalar parallax is recomputed by GameplayModeContext after the
        // registry restore; only the active handler's logical state is restored
        // here so re-simulation continues from the correct values.
        ZoneScrollHandler handler = activeHandler();
        if (handler != null) {
            handler.restoreRewindState(s.handlerRewindState());
        }
    }

    private Object captureActiveHandlerRewindState() {
        ZoneScrollHandler handler = activeHandler();
        return handler != null ? handler.captureRewindState() : null;
    }

    private ZoneScrollHandler activeHandler() {
        return scrollProvider != null ? scrollProvider.getHandler(currentZone) : null;
    }
}
