package com.openggf.tools.audio.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Task 8 remains blocked until the atomic authenticated bundle is published. */
class TestS1OverrideResumeAudioOracle {
    private static final Path PARITY_ROOT = Path.of(
            "src/test/resources/audio/parity").toAbsolutePath();

    @Test
    void authenticatedOverrideResumeReferenceIsUnavailable() {
        OverrideResumeReferenceBundle.ReferenceUnavailableException failure = assertThrows(
                OverrideResumeReferenceBundle.ReferenceUnavailableException.class,
                () -> OverrideResumeReferenceBundle.open(PARITY_ROOT));
        assertEquals("FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE",
                failure.code());
    }
}
