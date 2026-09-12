package com.openggf.configuration;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Internal persistence boundary for engine-owned, already validated preference edits. */
public final class ConfigurationPersistence {
    private ConfigurationPersistence() { }

    /** Publish the candidate file before exposing any changes to live configuration readers. */
    public static void apply(SonicConfigurationService config, Map<SonicConfiguration, Object> changes)
            throws IOException {
        Objects.requireNonNull(config, "config");
        Map<SonicConfiguration, Object> candidate = Map.copyOf(changes);
        for (SonicConfiguration key : candidate.keySet()) {
            if (!ConfigCatalog.meta(key).persisted()) throw new IllegalArgumentException("Derived setting: " + key);
        }
        config.applySettings(candidate);
    }
}
