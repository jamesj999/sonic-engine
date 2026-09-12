package com.openggf.tests;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit, comparison-only fixtures outside the checkout and test classpath. */
public final class AudioReferenceFixtures {
    public static final String PROPERTY = "openggf.audio.fixtures";

    private AudioReferenceFixtures() { }

    public static Path require(String relative) {
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Audio reference coverage requires -D" + PROPERTY
                    + "=<external fixture root>; it is not part of the public synthetic suite");
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        String member = relative.startsWith("/") ? relative.substring(1) : relative;
        Path path = root.resolve(member).normalize();
        if (!path.startsWith(root) || !Files.exists(path)) {
            throw new IllegalStateException("Required audio reference fixture is absent or outside root: " + path);
        }
        return path;
    }

    public static InputStream open(String relative) {
        try {
            return Files.newInputStream(require(relative));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read required audio reference fixture: " + relative, failure);
        }
    }
}
