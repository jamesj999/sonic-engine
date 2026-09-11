package com.openggf.configuration;

import java.util.Set;

/**
 * Metadata for a single {@link SonicConfiguration} key: where it lives on disk,
 * its value kind, its human description, and whether it is persisted at all.
 *
 * <p>A {@code persisted == false} key is DERIVED: it is computed at runtime and
 * never read from or written to disk, so it has no {@code section}/{@code leaf}.
 */
public record ConfigKeyMeta(
        String section,
        String leaf,
        ConfigType type,
        String description,
        Set<String> allowedValues,
        boolean persisted) {

    public static ConfigKeyMeta of(String section, String leaf, ConfigType type, String description) {
        return new ConfigKeyMeta(section, leaf, type, description, Set.of(), true);
    }

    public static ConfigKeyMeta ofEnum(String section, String leaf, String description, Set<String> allowedValues) {
        return new ConfigKeyMeta(section, leaf, ConfigType.ENUM, description, Set.copyOf(allowedValues), true);
    }

    public static ConfigKeyMeta derived(ConfigType type, String description) {
        return new ConfigKeyMeta(null, null, type, description, Set.of(), false);
    }

    /** Full dotted path used as the flatten reverse-lookup key, e.g. {@code "input.player1.a"}. */
    public String path() {
        if (section == null || leaf == null) {
            throw new IllegalStateException("derived key has no on-disk path");
        }
        return section + "." + leaf;
    }
}
