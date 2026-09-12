package com.openggf.tools.audio.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Task 8 remains blocked until the atomic authenticated bundle is published. */
class TestS1OverrideResumeAudioOracle {
    @org.junit.jupiter.api.io.TempDir
    Path parityRoot;

    @Test
    void authenticatedOverrideResumeReferenceIsUnavailable() {
        OverrideResumeReferenceBundle.ReferenceUnavailableException failure = assertThrows(
                OverrideResumeReferenceBundle.ReferenceUnavailableException.class,
                () -> OverrideResumeReferenceBundle.open(parityRoot));
        assertEquals("FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE",
                failure.code());
    }
}
