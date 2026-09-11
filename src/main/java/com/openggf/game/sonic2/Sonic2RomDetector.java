package com.openggf.game.sonic2;

import com.openggf.data.Rom;
import com.openggf.game.AbstractHeaderNameRomDetector;
import com.openggf.game.GameModule;

import java.util.logging.Logger;

/**
 * ROM detector for Sonic the Hedgehog 2 (Mega Drive/Genesis).
 *
 * <p>Detection is based on:
 * <ul>
 *   <li>ROM header domestic/international name containing "SONIC THE HEDGEHOG 2"</li>
 *   <li>ROM header format validation</li>
 * </ul>
 */
public class Sonic2RomDetector extends AbstractHeaderNameRomDetector {
    private static final Logger LOGGER = Logger.getLogger(Sonic2RomDetector.class.getName());

    // Expected strings in ROM header
    private static final String SONIC_2_NAME = "SONIC THE HEDGEHOG 2";

    // Priority: Sonic 2 is the default/most common, give it standard priority
    private static final int PRIORITY = 100;

    @Override
    public boolean canHandle(Rom rom) {
        return canHandleHeaderName(rom);
    }

    @Override
    protected boolean matchesNormalizedName(String normalizedName) {
        return normalizedName.contains(SONIC_2_NAME);
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
        return new Sonic2GameModule();
    }

    @Override
    public String getGameName() {
        return "Sonic the Hedgehog 2";
    }
}
