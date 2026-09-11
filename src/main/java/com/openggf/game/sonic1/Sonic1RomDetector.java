package com.openggf.game.sonic1;

import com.openggf.data.Rom;
import com.openggf.game.AbstractHeaderNameRomDetector;
import com.openggf.game.GameModule;

import java.util.logging.Logger;

/**
 * ROM detector for Sonic the Hedgehog 1 (Mega Drive/Genesis).
 *
 * <p>Detection is based on the ROM header domestic/international name
 * containing "SONIC THE HEDGEHOG" but NOT "HEDGEHOG 2" or "HEDGEHOG 3".
 */
public class Sonic1RomDetector extends AbstractHeaderNameRomDetector {
    private static final Logger LOGGER = Logger.getLogger(Sonic1RomDetector.class.getName());

    private static final String SONIC_NAME = "SONIC THE HEDGEHOG";
    private static final String SONIC_2_SUFFIX = "HEDGEHOG 2";
    private static final String SONIC_3_SUFFIX = "HEDGEHOG 3";

    // Lower priority number = checked first (before Sonic 2's priority of 100)
    private static final int PRIORITY = 90;

    @Override
    public boolean canHandle(Rom rom) {
        return canHandleHeaderName(rom);
    }

    @Override
    protected boolean matchesNormalizedName(String normalizedName) {
        return normalizedName.contains(SONIC_NAME)
                && !normalizedName.contains(SONIC_2_SUFFIX)
                && !normalizedName.contains(SONIC_3_SUFFIX);
    }

    @Override
    protected Logger logger() {
        return LOGGER;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public GameModule createModule() {
        return new Sonic1GameModule();
    }

    @Override
    public String getGameName() {
        return "Sonic the Hedgehog";
    }
}
