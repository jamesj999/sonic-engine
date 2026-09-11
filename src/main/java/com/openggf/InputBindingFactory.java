package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputBindings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Supplier;

public final class InputBindingFactory {
    private InputBindingFactory() {
    }

    public static Supplier<InputBindings> supplier(SonicConfigurationService config) {
        Objects.requireNonNull(config, "config");
        return () -> fromConfig(config);
    }

    public static Supplier<InputBindings> standaloneSupplier() {
        SonicConfigurationService config = createSyntheticConfig();
        return supplier(config);
    }

    public static InputBindings fromConfig(SonicConfigurationService config) {
        Objects.requireNonNull(config, "config");
        return new InputBindings(
                config.getInt(SonicConfiguration.UP),
                config.getInt(SonicConfiguration.DOWN),
                config.getInt(SonicConfiguration.LEFT),
                config.getInt(SonicConfiguration.RIGHT),
                config.getInt(SonicConfiguration.P1_A),
                config.getInt(SonicConfiguration.P1_B),
                config.getInt(SonicConfiguration.P1_C),
                config.getInt(SonicConfiguration.START),
                config.getInt(SonicConfiguration.P2_UP),
                config.getInt(SonicConfiguration.P2_DOWN),
                config.getInt(SonicConfiguration.P2_LEFT),
                config.getInt(SonicConfiguration.P2_RIGHT),
                config.getInt(SonicConfiguration.P2_A),
                config.getInt(SonicConfiguration.P2_B),
                config.getInt(SonicConfiguration.P2_C),
                config.getInt(SonicConfiguration.P2_START),
                config.getBoolean(SonicConfiguration.CONTROLLER_ENABLED),
                config.getDouble(SonicConfiguration.CONTROLLER_DEADZONE),
                config.getString(SonicConfiguration.CONTROLLER_PLAYER1),
                config.getString(SonicConfiguration.CONTROLLER_PLAYER2),
                config.getInt(SonicConfiguration.DEBUG_MODE_KEY),
                config.getInt(SonicConfiguration.LIVE_REWIND_KEY),
                config.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    private static SonicConfigurationService createSyntheticConfig() {
        return SonicConfigurationService.createStandalone(SyntheticConfigDirectory.PATH);
    }

    /**
     * One synthetic configuration directory per JVM, deleted on shutdown.
     *
     * <p>This used to be a fresh {@code Files.createTempDirectory} per call,
     * and nothing ever removed them. Every no-argument {@code InputHandler}
     * takes this path -- there are hundreds of such construction sites across
     * the engine and its tests -- so a full suite run left one directory per
     * call behind in {@code java.io.tmpdir}. On a machine where {@code /tmp} is
     * a RAM-backed tmpfs that accumulated into hundreds of thousands of
     * directories and filled the filesystem, which then broke unrelated
     * features that legitimately need temporary space.
     *
     * <p>Sharing one directory is safe because the service reads its
     * configuration and this factory never exposes the service, so nothing can
     * save into the directory: every caller still gets a freshly constructed
     * service reading the same absent-config defaults it read before.
     *
     * <p>Held in a holder class so initialization is lazy and thread-safe: a
     * process that never builds a standalone input handler creates nothing.
     */
    private static final class SyntheticConfigDirectory {
        private static final Path PATH = create();

        private static Path create() {
            try {
                Path directory = Files.createTempDirectory("openggf-input-handler");
                Runtime.getRuntime().addShutdownHook(new Thread(
                        () -> deleteRecursively(directory),
                        "openggf-input-handler-cleanup"));
                return directory;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to create standalone input configuration", e);
            }
        }

        private static void deleteRecursively(Path directory) {
            try (var entries = Files.walk(directory)) {
                entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort on shutdown.
                    }
                });
            } catch (IOException ignored) {
                // Best effort on shutdown.
            }
        }
    }
}
