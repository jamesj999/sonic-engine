package com.openggf.tests.trace;

import java.nio.file.Path;

/** Resolves read-only replay fixtures from an optional complete scratch fleet. */
public final class TraceFixtureRoot {
    private static final String OVERRIDE_PROPERTY = "openggf.trace.fixtureRoot";
    private static final Path INSTALLED_ROOT = Path.of("src/test/resources/traces");

    private TraceFixtureRoot() {
    }

    public static Path resolve(Path installedPath) {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override == null || override.isBlank()) {
            return installedPath;
        }
        Path normalized = installedPath.normalize();
        if (!normalized.startsWith(INSTALLED_ROOT)) {
            throw new IllegalArgumentException(
                    "candidate fixture override can resolve only installed trace paths: " + installedPath);
        }
        return Path.of(override).resolve(INSTALLED_ROOT.relativize(normalized)).normalize();
    }
}
