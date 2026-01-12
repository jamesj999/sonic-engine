package uk.co.jamesj999.sonic.data;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Singleton manager for ROM access across the engine.
 *
 * Provides centralized ROM lifecycle management:
 * - Opens the ROM once on first access
 * - Provides thread-safe access to ROM data
 * - Closes the ROM on engine shutdown
 *
 * Usage:
 * <pre>
 * Rom rom = RomManager.getInstance().getRom();
 * byte[] data = rom.readBytes(offset, length);
 * </pre>
 */
public class RomManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(RomManager.class.getName());

    private static RomManager instance;

    private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
    private Rom rom;
    private boolean initialized = false;

    private RomManager() {
    }

    /**
     * Gets the singleton instance of RomManager.
     */
    public static synchronized RomManager getInstance() {
        if (instance == null) {
            instance = new RomManager();
        }
        return instance;
    }

    /**
     * Gets the ROM instance, opening it if necessary.
     *
     * @return The open ROM instance
     * @throws IOException If the ROM cannot be opened
     */
    public synchronized Rom getRom() throws IOException {
        if (!initialized || rom == null || !rom.isOpen()) {
            openRom();
        }
        return rom;
    }

    /**
     * Checks if the ROM is currently open and available.
     */
    public synchronized boolean isRomAvailable() {
        return initialized && rom != null && rom.isOpen();
    }

    /**
     * Opens the ROM file using the configured filename.
     *
     * @throws IOException If the ROM cannot be opened
     */
    private void openRom() throws IOException {
        // Close existing ROM if any
        if (rom != null) {
            rom.close();
        }

        String romFilename = configService.getString(SonicConfiguration.ROM_FILENAME);
        if (romFilename == null || romFilename.isEmpty()) {
            throw new IOException("ROM filename not configured (ROM_FILENAME is null or empty)");
        }

        LOGGER.info("Opening ROM: " + romFilename);

        rom = new Rom();
        if (!rom.open(romFilename)) {
            rom = null;
            throw new IOException("Failed to open ROM file: " + romFilename);
        }

        initialized = true;
        LOGGER.info("ROM opened successfully: " + rom.readDomesticName());
    }

    /**
     * Closes the ROM and releases resources.
     * Should be called on engine shutdown.
     */
    @Override
    public synchronized void close() {
        if (rom != null) {
            LOGGER.info("Closing ROM via RomManager");
            rom.close();
            rom = null;
        }
        initialized = false;
    }

    /**
     * Resets the singleton instance (primarily for testing).
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }
}
