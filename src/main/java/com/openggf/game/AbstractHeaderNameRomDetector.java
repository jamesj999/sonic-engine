package com.openggf.game;

import com.openggf.data.Rom;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Base detector for games identified by a normalized ROM header name.
 */
public abstract class AbstractHeaderNameRomDetector implements RomDetector {

    /**
     * Runs the shared ROM-header detection algorithm.
     *
     * @param rom the ROM to inspect
     * @return whether the normalized domestic or international name matches
     */
    protected final boolean canHandleHeaderName(Rom rom) {
        if (rom == null || !rom.isOpen()) {
            return false;
        }

        try {
            String domesticName = rom.readDomesticName();
            if (matchesName(domesticName)) {
                logger().fine(getGameName() + " detected via domestic name: " + domesticName);
                return true;
            }

            String internationalName = rom.readInternationalName();
            if (matchesName(internationalName)) {
                logger().fine(getGameName() + " detected via international name: " + internationalName);
                return true;
            }

            logger().fine("ROM names did not match " + getGameName() + ": domestic='"
                    + domesticName + "', international='" + internationalName + "'");
            return false;
        } catch (IOException e) {
            logger().warning("Error reading ROM header: " + e.getMessage());
            return false;
        }
    }

    private boolean matchesName(String name) {
        return name != null && matchesNormalizedName(normalizeWhitespace(name));
    }

    /**
     * Matches a ROM header name after whitespace has been normalized and its
     * characters converted to uppercase.
     *
     * @param normalizedName normalized ROM header name
     * @return whether this detector recognizes the name
     */
    protected abstract boolean matchesNormalizedName(String normalizedName);

    /**
     * Returns the logger associated with the concrete detector.
     *
     * @return the detector logger
     */
    protected abstract Logger logger();

    /**
     * Collapses whitespace and converts a ROM header name to uppercase.
     *
     * @param input header name to normalize
     * @return normalized header name
     */
    protected final String normalizeWhitespace(String input) {
        return input.toUpperCase().replaceAll("\\s+", " ").trim();
    }
}
