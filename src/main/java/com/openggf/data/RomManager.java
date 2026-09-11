package com.openggf.data;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
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
 * Rom rom = GameServices.rom().getRom();
 * byte[] data = rom.readBytes(offset, length);
 * </pre>
 */
public class RomManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(RomManager.class.getName());
    private static final String MISSING_ROM_PREFIX = "ROM file does not exist: ";

    private static RomManager instance;

    private Rom rom;
    private boolean initialized = false;
    private final Map<String, Rom> secondaryRoms = new HashMap<>();

    private RomManager() {
    }

    private SonicConfigurationService configService() {
        return GameServices.configuration();
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
     * Injects a pre-opened ROM instance. Useful for tests that open
     * specific ROMs directly rather than relying on config resolution.
     */
    public synchronized void setRom(Rom rom) {
        // Do NOT close the previous ROM here — its lifecycle is managed
        // by whoever created it (e.g., RomCache in tests). Closing it here
        // would invalidate cached ROM instances shared across tests.
        this.rom = rom;
        this.initialized = rom != null && rom.isOpen();
    }

    /**
     * Checks if the ROM is currently open and available.
     */
    public synchronized boolean isRomAvailable() {
        return initialized && rom != null && rom.isOpen();
    }

    /**
     * Opens a secondary ROM by game ID without changing the active game module.
     * Used for cross-game feature donation (e.g., loading S2 sprites while playing S1).
     *
     * @param gameId "s1", "s2", or "s3k"
     * @return The open ROM instance
     * @throws IOException If the ROM cannot be opened
     */
    public synchronized Rom getSecondaryRom(String gameId) throws IOException {
        Rom existing = secondaryRoms.get(gameId);
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        RomLocation location = resolveRomLocation(legacyRomGame(gameId));
        if (location == null) {
            throw new IOException("No ROM configured for game: " + gameId);
        }
        Rom secondaryRom = new Rom();
        if (!secondaryRom.open(location.resolvedPath().toString())) {
            throw new IOException("Failed to open secondary ROM: " + location.configuredValue());
        }
        LOGGER.info("Opened secondary ROM (" + gameId + "): " + secondaryRom.readDomesticName());
        secondaryRoms.put(gameId, secondaryRom);
        return secondaryRom;
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

        RomLocation location = resolveRomLocation(
                legacyRomGame(configService().getString(SonicConfiguration.DEFAULT_ROM)));
        if (location == null) {
            throw new IOException("ROM filename not configured (DEFAULT_ROM not set or per-game ROM key empty)");
        }

        if (!Files.exists(location.resolvedPath())) {
            rom = null;
            initialized = false;
            throw new IOException(MISSING_ROM_PREFIX + location.configuredValue());
        }

        LOGGER.info("Opening ROM: " + location.configuredValue());

        rom = new Rom();
        if (!rom.open(location.resolvedPath().toString())) {
            rom = null;
            initialized = false;
            throw new IOException("Failed to open ROM file: " + location.configuredValue());
        }

        initialized = true;
    }

    private RomLocation resolveRomLocation(RomGame game) {
        return RomLocationResolver.forCurrentWorkingDirectory(configService())
                .resolve(game)
                .orElse(null);
    }

    public static boolean isConfiguredRomMissing(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.startsWith(MISSING_ROM_PREFIX)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Resolves the ROM filename for a given game identifier.
     *
     * @param gameId "s1", "s2", or "s3k"
     * @return the configured ROM filename for that game
     */
    @Deprecated
    public static String resolveRomForGame(String gameId) {
        SonicConfigurationService configuration = GameServices.configuration();
        return configuredRomValue(legacyRomGame(gameId), configuration);
    }

    private static String configuredRomValue(RomGame game, SonicConfigurationService configuration) {
        return switch (game) {
            case S1 -> configuration.getString(SonicConfiguration.SONIC_1_ROM);
            case S2 -> configuration.getString(SonicConfiguration.SONIC_2_ROM);
            case S3K -> configuration.getString(SonicConfiguration.SONIC_3K_ROM);
        };
    }

    private static RomGame legacyRomGame(String gameId) {
        return switch (gameId != null ? gameId.toLowerCase() : "s2") {
            case "s1" -> RomGame.S1;
            case "s3k" -> RomGame.S3K;
            default -> RomGame.S2;
        };
    }

    /**
     * Closes the ROM and releases resources.
     * Should be called on engine shutdown.
     */
    @Override
    public synchronized void close() {
        if (rom != null) {
            LOGGER.fine("Closing ROM via RomManager");
            rom.close();
            rom = null;
        }
        for (Rom secondary : secondaryRoms.values()) {
            if (secondary != null) {
                secondary.close();
            }
        }
        secondaryRoms.clear();
        initialized = false;
    }

}
