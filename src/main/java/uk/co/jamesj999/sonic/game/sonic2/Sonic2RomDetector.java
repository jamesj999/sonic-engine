package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.GameModule;
import uk.co.jamesj999.sonic.game.RomDetector;

import java.io.IOException;
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
public class Sonic2RomDetector implements RomDetector {
    private static final Logger LOGGER = Logger.getLogger(Sonic2RomDetector.class.getName());

    // Expected strings in ROM header
    private static final String SONIC_2_NAME = "SONIC THE HEDGEHOG 2";

    // Priority: Sonic 2 is the default/most common, give it standard priority
    private static final int PRIORITY = 100;

    @Override
    public boolean canHandle(Rom rom) {
        if (rom == null || !rom.isOpen()) {
            return false;
        }

        try {
            // Check domestic name (normalize whitespace for comparison)
            String domesticName = rom.readDomesticName();
            if (domesticName != null && normalizeWhitespace(domesticName).contains(SONIC_2_NAME)) {
                LOGGER.fine("Sonic 2 detected via domestic name: " + domesticName);
                return true;
            }

            // Check international name (normalize whitespace for comparison)
            String intlName = rom.readInternationalName();
            if (intlName != null && normalizeWhitespace(intlName).contains(SONIC_2_NAME)) {
                LOGGER.fine("Sonic 2 detected via international name: " + intlName);
                return true;
            }

            LOGGER.fine("ROM names did not match Sonic 2: domestic='" + domesticName +
                    "', international='" + intlName + "'");
            return false;
        } catch (IOException e) {
            LOGGER.warning("Error reading ROM header: " + e.getMessage());
            return false;
        }
    }

    /**
     * Normalizes whitespace in a string by collapsing multiple spaces into one
     * and converting to uppercase for comparison.
     */
    private String normalizeWhitespace(String input) {
        return input.toUpperCase().replaceAll("\\s+", " ").trim();
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
