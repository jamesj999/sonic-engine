package com.openggf.game.sonic2.scroll;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.ScrollHandlerProvider;
import com.openggf.game.sonic2.DynamicHtz;
import com.openggf.level.Level;
import com.openggf.level.scroll.ZoneScrollHandler;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Scroll handler provider for Sonic 2.
 * Owns all 11 zone scroll handlers, the shared BackgroundCamera,
 * ParallaxTables, and DynamicHtz art streamer.
 *
 * <p>This is the single source of scroll behavior for Sonic 2.
 * ParallaxManager delegates to this provider without any game-specific
 * knowledge.
 */
public class Sonic2ScrollHandlerProvider implements ScrollHandlerProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ScrollHandlerProvider.class.getName());

    private ParallaxTables tables;
    private boolean loaded = false;

    // Shared state
    private BackgroundCamera bgCamera;
    private DynamicHtz dynamicHtz;

    // Zone handlers
    private SwScrlArz arzHandler;
    private SwScrlCnz cnzHandler;
    private SwScrlCpz cpzHandler;
    private SwScrlDez dezHandler;
    private SwScrlEhz ehzHandler;
    private SwScrlHtz htzHandler;
    private SwScrlMcz mczHandler;
    private SwScrlMtz mtzHandler;
    private SwScrlOoz oozHandler;
    private SwScrlScz sczHandler;
    private SwScrlWfz wfzHandler;

    // Tracks which zone is currently initialized (to avoid redundant init)
    private int currentZone = -1;
    private int currentAct = -1;

    @Override
    public void load(Rom rom) throws IOException {
        if (loaded) {
            return;
        }
        try {
            tables = new ParallaxTables(rom);
            bgCamera = new BackgroundCamera();

            arzHandler = new SwScrlArz(tables);
            cnzHandler = new SwScrlCnz(tables);
            cpzHandler = new SwScrlCpz(tables);
            dezHandler = new SwScrlDez(tables, bgCamera);
            ehzHandler = new SwScrlEhz(tables);
            htzHandler = new SwScrlHtz(tables, bgCamera);
            mczHandler = new SwScrlMcz(tables);
            mtzHandler = new SwScrlMtz(bgCamera);
            oozHandler = new SwScrlOoz(tables);
            sczHandler = new SwScrlScz();
            wfzHandler = new SwScrlWfz(tables, bgCamera);

            dynamicHtz = new DynamicHtz();
            dynamicHtz.init();

            loaded = true;
            LOGGER.info("Sonic 2 scroll handlers loaded.");
        } catch (IOException e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "Failed to load parallax data: " + e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public ZoneScrollHandler getHandler(int zoneIndex) {
        if (!loaded) {
            return null;
        }

        return switch (zoneIndex) {
            case Sonic2ZoneConstants.ZONE_EHZ -> ehzHandler;
            case Sonic2ZoneConstants.ZONE_CPZ -> cpzHandler;
            case Sonic2ZoneConstants.ZONE_ARZ -> arzHandler;
            case Sonic2ZoneConstants.ZONE_CNZ -> cnzHandler;
            case Sonic2ZoneConstants.ZONE_HTZ -> htzHandler;
            case Sonic2ZoneConstants.ZONE_MCZ -> mczHandler;
            case Sonic2ZoneConstants.ZONE_OOZ -> oozHandler;
            case Sonic2ZoneConstants.ZONE_MTZ -> mtzHandler;
            case Sonic2ZoneConstants.ZONE_SCZ -> sczHandler;
            case Sonic2ZoneConstants.ZONE_WFZ -> wfzHandler;
            case Sonic2ZoneConstants.ZONE_DEZ -> dezHandler;
            default -> null;
        };
    }

    @Override
    public ZoneConstants getZoneConstants() {
        return Sonic2ZoneConstants.INSTANCE;
    }

    @Override
    public void initForZone(int zoneId, int actId, int cameraX, int cameraY) {
        if (zoneId == currentZone && actId == currentAct) {
            return;
        }
        currentZone = zoneId;
        currentAct = actId;

        // Initialize the shared background camera for all zones
        if (bgCamera != null) {
            bgCamera.init(zoneId, actId, cameraX, cameraY);
        }

        // Zone-specific handler initialization
        switch (zoneId) {
            case Sonic2ZoneConstants.ZONE_ARZ -> {
                if (arzHandler != null) {
                    arzHandler.init(actId, cameraX, cameraY);
                }
            }
            case Sonic2ZoneConstants.ZONE_CPZ -> {
                if (cpzHandler != null) {
                    cpzHandler.init(cameraX, cameraY);
                }
            }
            case Sonic2ZoneConstants.ZONE_HTZ -> {
                if (htzHandler != null) {
                    htzHandler.init();
                }
                if (dynamicHtz != null) {
                    dynamicHtz.reset();
                }
            }
            case Sonic2ZoneConstants.ZONE_OOZ -> {
                if (oozHandler != null) {
                    oozHandler.init(cameraX, cameraY);
                }
            }
            case Sonic2ZoneConstants.ZONE_SCZ -> {
                if (sczHandler != null) {
                    sczHandler.init();
                }
                // SCZ camera is driven by Tornado velocity, not player following.
                // Freeze camera to prevent normal updatePosition() from overriding.
                GameServices.camera().setFrozen(true);
            }
            default -> {
                // Other zones only need the bgCamera init (already done above)
            }
        }
    }

    @Override
    public void updateDynamicArt(Level level, int cameraX) {
        if (currentZone == Sonic2ZoneConstants.ZONE_HTZ
                && dynamicHtz != null && level != null && htzHandler != null) {
            dynamicHtz.update(level, cameraX, htzHandler);
        }
    }

    @Override
    public int getTornadoVelocityX() {
        return sczHandler != null ? sczHandler.getTornadoVelocityX() : 0;
    }

    @Override
    public int getTornadoVelocityY() {
        return sczHandler != null ? sczHandler.getTornadoVelocityY() : 0;
    }

    @Override
    public int getCameraBgXOffset() {
        return bgCamera != null ? bgCamera.getBgXPos() : 0;
    }

    /**
     * Returns the DEZ handler for use by the ending sequence.
     * The ending uses DEZ scroll with a custom BG vscroll value.
     *
     * @return the DEZ handler, or null if not loaded
     */
    public SwScrlDez getDezHandler() {
        return dezHandler;
    }

    /**
     * Returns the background camera for use by the ending sequence
     * and other S2-specific code that needs BG camera state.
     *
     * @return the background camera, or null if not loaded
     */
    public BackgroundCamera getBgCamera() {
        return bgCamera;
    }

    /**
     * Resets zone tracking state without destroying handler instances.
     * Used when re-entering a zone that needs fresh initialization.
     */
    @Override
    public void resetZoneState() {
        currentZone = -1;
        currentAct = -1;
        if (htzHandler != null) {
            htzHandler.init();
        }
        if (dynamicHtz != null) {
            dynamicHtz.reset();
        }
    }

    @Override
    public boolean updateForEnding(int[] horizScrollBuf, int zoneId, int actId,
                                    int frameCounter, short bgVscroll) {
        if (dezHandler == null) {
            return false;
        }
        // The ending supplies Camera_BG_Y_pos as a per-frame cutscene value.
        // Keep the gameplay-owned BackgroundCamera unchanged while exposing that
        // temporary ROM value to SwScrl_DEZ for this one update.
        int previousBgY = bgCamera != null ? bgCamera.getBgYPos() : 0;
        if (bgCamera != null) {
            bgCamera.setBgYPos(bgVscroll);
        }
        dezHandler.setVscrollFactorBG(bgVscroll);
        try {
            dezHandler.update(horizScrollBuf, 0, 0, frameCounter, actId);
        } finally {
            if (bgCamera != null) {
                bgCamera.setBgYPos(previousBgY);
            }
        }
        return true;
    }
}
