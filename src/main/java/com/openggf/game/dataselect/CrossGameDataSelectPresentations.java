package com.openggf.game.dataselect;

import com.openggf.game.DataSelectProvider;

import java.util.Objects;
import java.util.function.Function;

/**
 * Centralizes cross-game data-select presentation donation.
 *
 * <p>The shared layer never names a concrete game-specific delegate. A donor
 * game registers its {@link DataSelectProvider} factory via
 * {@link #registerDonor(String, Function)} at module-construction time; recipient
 * games request the donated presentation by donor key via
 * {@link #donated(String, DataSelectHostProfile)}.
 */
public final class CrossGameDataSelectPresentations {
    /** Conventional donor key for the S3K data-select renderer. */
    public static final String DONOR_S3K = "s3k";

    private static final java.util.concurrent.ConcurrentMap<String,
            Function<DataSelectSessionController, ? extends DataSelectProvider>> DONOR_FACTORIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Donor key → class names whose initialization registers a factory under that key.
     * Loaded reflectively so the shared layer does not compile against game-specific types.
     */
    private static final java.util.Map<String, String[]> DONOR_BOOTSTRAP_CLASSES = java.util.Map.of(
            DONOR_S3K, new String[] {"com.openggf.game.sonic3k.Sonic3kGameModule"});

    private CrossGameDataSelectPresentations() {
    }

    private static void ensureDonorBootstrap(String donorKey) {
        if (DONOR_FACTORIES.containsKey(donorKey)) {
            return;
        }
        String[] bootstrapClasses = DONOR_BOOTSTRAP_CLASSES.get(donorKey);
        if (bootstrapClasses == null) {
            return;
        }
        for (String className : bootstrapClasses) {
            try {
                Class.forName(className);
            } catch (ClassNotFoundException ignored) {
                // donor module not on the classpath; donated() will surface the missing-donor error
            }
        }
    }

    /**
     * Registers a donor's {@link DataSelectProvider} factory under the given key.
     * Idempotent — a donor module may safely re-register the same factory.
     */
    public static void registerDonor(String donorKey,
                                     Function<DataSelectSessionController, ? extends DataSelectProvider> factory) {
        Objects.requireNonNull(donorKey, "donorKey");
        Objects.requireNonNull(factory, "factory");
        DONOR_FACTORIES.put(donorKey, factory);
    }

    /**
     * Builds a donated {@link DataSelectPresentationProvider} backed by the donor
     * registered under {@code donorKey}. The recipient supplies its own
     * {@link DataSelectHostProfile} (save ownership, zone labels, team configs)
     * while the donor supplies the renderer.
     *
     * @throws IllegalStateException if no donor is registered under {@code donorKey}.
     */
    public static DataSelectPresentationProvider donated(String donorKey,
                                                          DataSelectHostProfile hostProfile) {
        Objects.requireNonNull(donorKey, "donorKey");
        ensureDonorBootstrap(donorKey);
        Function<DataSelectSessionController, ? extends DataSelectProvider> factory =
                DONOR_FACTORIES.get(donorKey);
        if (factory == null) {
            throw new IllegalStateException(
                    "No data-select donor registered under key '" + donorKey + "'");
        }
        return new DataSelectPresentationProvider(factory,
                new DataSelectSessionController(hostProfile));
    }

    /** Test/diagnostic helper: returns true if a donor factory is registered. */
    public static boolean hasDonor(String donorKey) {
        return donorKey != null && DONOR_FACTORIES.containsKey(donorKey);
    }
}
