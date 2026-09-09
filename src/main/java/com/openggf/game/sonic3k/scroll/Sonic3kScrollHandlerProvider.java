package com.openggf.game.sonic3k.scroll;

import com.openggf.data.Rom;
import com.openggf.game.ScrollHandlerProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.scroll.ZoneScrollHandler;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Scroll handler provider for Sonic 3 &amp; Knuckles.
 * Provides zone-specific scroll handlers for parallax effects.
 */
public class Sonic3kScrollHandlerProvider implements ScrollHandlerProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kScrollHandlerProvider.class.getName());

    private boolean loaded = false;

    private SwScrlAiz aizHandler;
    private SwScrlCnz cnzHandler;
    private SwScrlHcz hczHandler;
    private SwScrlIcz iczHandler;
    private SwScrlLbz lbzHandler;
    private SwScrlMhz mhzHandler;
    private SwScrlMgz mgzHandler;
    private SwScrlGumball gumballHandler;
    private SwScrlPachinko pachinkoHandler;
    private SwScrlSlots slotsHandler;
    private SwScrlS3kDefault defaultHandler;

    @Override
    public void load(Rom rom) throws IOException {
        if (loaded) {
            return;
        }
        aizHandler = new SwScrlAiz();
        cnzHandler = new SwScrlCnz();
        byte[] hczWaterlineData = null;
        try {
            hczWaterlineData = rom.readBytes(
                    Sonic3kConstants.HCZ_WATERLINE_SCROLL_DATA_ADDR,
                    Sonic3kConstants.HCZ_WATERLINE_SCROLL_DATA_SIZE);
        } catch (IOException e) {
            LOGGER.fine(() -> "HCZ waterline scroll data unavailable; using fallback HCZ handler: "
                    + e.getMessage());
        }
        hczHandler = new SwScrlHcz(hczWaterlineData);
        iczHandler = new SwScrlIcz();
        byte[] lbzWaterlineData = null;
        try {
            lbzWaterlineData = rom.readBytes(
                    Sonic3kConstants.LBZ_WATERLINE_SCROLL_DATA_ADDR,
                    Sonic3kConstants.LBZ_WATERLINE_SCROLL_DATA_SIZE);
        } catch (IOException e) {
            LOGGER.fine(() -> "LBZ waterline scroll data unavailable; using fallback LBZ waterline lookup: "
                    + e.getMessage());
        }
        byte[] lbzDeathEggWaveData = null;
        try {
            lbzDeathEggWaveData = rom.readBytes(Sonic3kConstants.LBZ_DEATH_EGG_WAVE_DATA_ADDR,
                    Sonic3kConstants.LBZ_DEATH_EGG_WAVE_DATA_SIZE);
        } catch (IOException e) {
            LOGGER.fine(() -> "LBZ Death Egg wave data unavailable: " + e.getMessage());
        }
        lbzHandler = new SwScrlLbz(lbzWaterlineData, lbzDeathEggWaveData);
        mhzHandler = new SwScrlMhz();
        mgzHandler = new SwScrlMgz();
        gumballHandler = new SwScrlGumball();
        pachinkoHandler = new SwScrlPachinko();
        slotsHandler = new SwScrlSlots();
        defaultHandler = new SwScrlS3kDefault();
        loaded = true;
        LOGGER.info("Sonic 3K scroll handlers loaded.");
    }

    @Override
    public ZoneScrollHandler getHandler(int zoneIndex) {
        if (!loaded) {
            return null;
        }

        return switch (zoneIndex) {
            case Sonic3kZoneConstants.ZONE_AIZ -> aizHandler;
            case Sonic3kZoneConstants.ZONE_CNZ -> cnzHandler;
            case Sonic3kZoneConstants.ZONE_HCZ -> hczHandler;
            case Sonic3kZoneConstants.ZONE_ICZ -> iczHandler;
            case Sonic3kZoneConstants.ZONE_LBZ -> lbzHandler;
            case Sonic3kZoneConstants.ZONE_MHZ -> mhzHandler;
            case Sonic3kZoneConstants.ZONE_MGZ -> mgzHandler;
            case Sonic3kZoneIds.ZONE_GUMBALL -> gumballHandler;
            case Sonic3kZoneIds.ZONE_GLOWING_SPHERE -> pachinkoHandler;
            case Sonic3kZoneIds.ZONE_SLOT_MACHINE -> slotsHandler;
            default -> defaultHandler;
        };
    }

    @Override
    public ZoneConstants getZoneConstants() {
        return Sonic3kZoneConstants.INSTANCE;
    }

    @Override
    public void initForZone(int zoneId, int actId, int cameraX, int cameraY) {
        ZoneScrollHandler handler = getHandler(zoneId);
        if (handler != null) {
            handler.init(actId, cameraX, cameraY);
        }
    }
}
