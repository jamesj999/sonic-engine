package com.openggf.tools.audio.parity.s2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.openggf.tools.audio.parity.OverrideResumeReferenceBundle;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Task 8 remains blocked until the atomic authenticated bundle is published. */
class TestS2OverrideResumeAudioOracle {
    private static final Path PARITY_ROOT = Path.of(
            "src/test/resources/audio/parity").toAbsolutePath();

    @Test
    void exactFirstServiceAndNextPcmMatch() {
        OverrideResumeReferenceBundle.ReferenceUnavailableException failure = assertThrows(
                OverrideResumeReferenceBundle.ReferenceUnavailableException.class,
                () -> OverrideResumeReferenceBundle.open(PARITY_ROOT));
        assertEquals("FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE",
                failure.code());
    }
}
