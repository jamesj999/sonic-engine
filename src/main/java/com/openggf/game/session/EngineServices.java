package com.openggf.game.session;

import java.util.Objects;

/**
 * Process-wide engine service root.
 */
public final class EngineServices {
    private static EngineContext current;

    private EngineServices() {
    }

    public static synchronized void configure(EngineContext services) {
        current = Objects.requireNonNull(services, "services");
    }

    public static synchronized EngineContext current() {
        if (current == null) {
            current = EngineContext.fromLegacySingletonsForBootstrap();
        }
        return current;
    }
}
