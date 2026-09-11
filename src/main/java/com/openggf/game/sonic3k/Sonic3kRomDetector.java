package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.game.AbstractHeaderNameRomDetector;
import com.openggf.game.GameModule;

import java.util.logging.Logger;

/**
 * ROM detector for Sonic 3, Sonic &amp; Knuckles, and Sonic 3 &amp; Knuckles
 * (Mega Drive/Genesis).
 *
 * <p>Detection is based on the ROM header domestic/international name
 * containing "SONIC THE HEDGEHOG 3", "SONIC & KNUCKLES", or
 * "SONIC3 & KNUCKLES".
 */
public class Sonic3kRomDetector extends AbstractHeaderNameRomDetector {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kRomDetector.class.getName());

    // Checked before S1 (90) and S2 (100)
    private static final int PRIORITY = 80;

    @Override
    public boolean canHandle(Rom rom) {
        return canHandleHeaderName(rom);
    }

    @Override
    protected boolean matchesNormalizedName(String normalizedName) {
        return normalizedName.contains("SONIC THE HEDGEHOG 3")
                || normalizedName.contains("SONIC & KNUCKLES")
                || normalizedName.contains("SONIC3 & KNUCKLES")
                || normalizedName.contains("SONIC AND KNUCKLES");
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
        return new Sonic3kGameModule();
    }

    @Override
    public String getGameName() {
        return "Sonic 3 & Knuckles";
    }
}
