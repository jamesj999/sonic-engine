package com.openggf.game;

import com.openggf.architecture.CompositionRoot;
import com.openggf.data.Rom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Service that orchestrates ROM detection by querying registered detectors.
 * Detectors are checked in priority order (lower priority value = checked first).
 *
 * <p>Usage:
 * <pre>
 * RomDetectionService service = EngineServices.current().romDetection();
 * boolean detected = service.detectAndSetModule(rom);
 * GameModule module = SessionManager.requireCurrentGameModule();
 * </pre>
 */
@CompositionRoot
public class RomDetectionService {
    private static final Logger LOGGER = Logger.getLogger(RomDetectionService.class.getName());
    private static RomDetectionService instance;

    private final List<RomDetector> detectors = new ArrayList<>();

    private RomDetectionService() {
        this(BuiltInRomDetectors.all());
    }

    RomDetectionService(List<? extends RomDetector> initialDetectors) {
        initialDetectors.forEach(this::registerDetector);
    }

    public static synchronized RomDetectionService getInstance() {
        if (instance == null) {
            instance = new RomDetectionService();
        }
        return instance;
    }

    /**
     * Registers a custom ROM detector.
     *
     * @param detector the detector to register
     */
    public void registerDetector(RomDetector detector) {
        if (detector != null && !detectors.contains(detector)) {
            detectors.add(detector);
            // Keep sorted by priority
            detectors.sort(Comparator.comparingInt(RomDetector::getPriority));
            LOGGER.fine("Registered ROM detector: " + detector.getGameName() +
                    " (priority " + detector.getPriority() + ")");
        }
    }

    /**
     * Unregisters a ROM detector.
     *
     * @param detector the detector to remove
     */
    public void unregisterDetector(RomDetector detector) {
        detectors.remove(detector);
    }

    /**
     * Detects the game type from the ROM and creates an appropriate GameModule.
     *
     * @param rom the ROM to analyze
     * @return an Optional containing the GameModule if detected, empty otherwise
     */
    public Optional<GameModule> detectAndCreateModule(Rom rom) {
        if (rom == null || !rom.isOpen()) {
            LOGGER.warning("Cannot detect ROM: ROM is null or not open");
            return Optional.empty();
        }

        for (RomDetector detector : detectors) {
            try {
                if (detector.canHandle(rom)) {
                    LOGGER.info("ROM detected as: " + detector.getGameName());
                    return Optional.of(detector.createModule());
                }
            } catch (RuntimeException e) {
                LOGGER.warning("Error in detector " + detector.getGameName() + ": " + e.getMessage());
            }
        }

        LOGGER.warning("No detector matched the ROM");
        return Optional.empty();
    }

    /**
     * Detects the game type from the ROM and forwards the result to the
     * {@link GameModuleRegistry}, which owns bootstrap-module mutation and
     * fallback behavior.
     *
     * <p>This method does not own active gameplay module state. Once a
     * {@code WorldSession} exists, {@link GameModuleRegistry#getCurrent()}
     * resolves from session-owned state instead.
     *
     * @param rom the ROM to analyze
     * @return true if a module was detected, false if the bootstrap default was
     * reset to Sonic 2 fallback
     * @deprecated use {@link GameModuleRegistry#detectAndSetModule(Rom)} for
     * registry-owned bootstrap application
     */
    @Deprecated
    public boolean detectAndSetModule(Rom rom) {
        return GameModuleRegistry.applyDetectedModule(detectAndCreateModule(rom));
    }

    /**
     * Returns the list of registered detectors (read-only for inspection).
     *
     * @return list of registered detectors
     */
    public List<RomDetector> getRegisteredDetectors() {
        return List.copyOf(detectors);
    }
}
