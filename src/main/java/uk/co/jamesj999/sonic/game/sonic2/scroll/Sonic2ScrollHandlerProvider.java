package uk.co.jamesj999.sonic.game.sonic2.scroll;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.ScrollHandlerProvider;
import uk.co.jamesj999.sonic.level.scroll.ParallaxTables;
import uk.co.jamesj999.sonic.level.scroll.SwScrlArz;
import uk.co.jamesj999.sonic.level.scroll.SwScrlCpz;
import uk.co.jamesj999.sonic.level.scroll.SwScrlEhz;
import uk.co.jamesj999.sonic.level.scroll.SwScrlMcz;
import uk.co.jamesj999.sonic.level.scroll.ZoneScrollHandler;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scroll handler provider for Sonic 2.
 * Provides zone-specific scroll handlers for parallax effects.
 */
public class Sonic2ScrollHandlerProvider implements ScrollHandlerProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ScrollHandlerProvider.class.getName());

    private ParallaxTables tables;
    private boolean loaded = false;

    private SwScrlArz arzHandler;
    private SwScrlEhz ehzHandler;
    private SwScrlCpz cpzHandler;
    private SwScrlMcz mczHandler;

    @Override
    public void load(Rom rom) throws IOException {
        if (loaded) {
            return;
        }
        try {
            tables = new ParallaxTables(rom);
            arzHandler = new SwScrlArz(tables);
            ehzHandler = new SwScrlEhz(tables);
            cpzHandler = new SwScrlCpz(tables);
            mczHandler = new SwScrlMcz(tables);
            loaded = true;
            LOGGER.info("Sonic 2 scroll handlers loaded.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load parallax data: " + e.getMessage(), e);
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
            case Sonic2ZoneConstants.ZONE_MCZ -> mczHandler;
            // Other zones use inline scroll routines in ParallaxManager for now
            default -> null;
        };
    }

    @Override
    public ZoneConstants getZoneConstants() {
        return Sonic2ZoneConstants.INSTANCE;
    }

    /**
     * Gets the parallax tables for direct access by ParallaxManager.
     * Used for zones that haven't been converted to ZoneScrollHandler yet.
     *
     * @return the parallax tables, or null if not loaded
     */
    public ParallaxTables getTables() {
        return tables;
    }

    /**
     * Gets the MCZ handler for screen shake support.
     *
     * @return the MCZ handler, or null if not loaded
     */
    public SwScrlMcz getMczHandler() {
        return mczHandler;
    }
}
