package com.openggf.tools.audio.parity.s2;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integrity of the committed request-aware S2 candidate: exact gzipped and
 * expanded payload digests, and a strict parse to the pinned bounded window
 * with its ordered pre-consumption request transfers. The payload is
 * comparison-only reference data; nothing here hydrates engine state.
 */
// Minutes-long oracle sweep: excluded from the -Psmoke fast lane, still run by
// the default suite on pull requests, the nightly schedule and release validation.
@Tag("slow-suite")
class TestS2RequestWindowFixture {
    static final String FIXTURE_RESOURCE =
            "/audio/parity/s2/s2-request-window-w10150-10900.raw-v2.jsonl.gz";
    static final String PAYLOAD_GZ_SHA256 =
            "1d675c69d46f955eb0b69558b8b24efa3e323ffb278c10b6d936e8a1642f515f";
    static final String PAYLOAD_RAW_SHA256 =
            "aba57c7e3de464c26c0a9caa2bc0327638c6db58e6967bb39c91982795d1773e";
    /** Ordered request transfers the extractor observed inside the window. */
    static final int REQUEST_TRANSFERS = 27;

    @TempDir
    Path temporaryDirectory;

    @Test
    void committedPayloadDigestsArePinned() throws Exception {
        try (InputStream input = fixtureStream()) {
            assertEquals(PAYLOAD_GZ_SHA256, digest(input));
        }
        try (InputStream input = new GZIPInputStream(fixtureStream())) {
            assertEquals(PAYLOAD_RAW_SHA256, digest(input));
        }
    }

    @Test
    void committedPayloadParsesToThePinnedWindowAndTransfers() throws Exception {
        S2RequestAwareOracleRawStream.Result result =
                S2RequestAwareOracleRawStream.scanWindowSourceCandidateForTesting(
                        expandCommittedCandidate(temporaryDirectory));

        assertEquals(750, result.frames().size());
        assertEquals(10_150, result.baseline().row());
        assertEquals(10_149, result.baseline().sourcePrecedingRow());
        assertEquals(REQUEST_TRANSFERS, result.frames().stream()
                .mapToInt(frame -> frame.requestTransfers().size())
                .sum());
    }

    /**
     * Expands the committed gzipped payload into {@code directory} and returns
     * the plain bounded JSONL path the strict reader accepts.
     */
    static Path expandCommittedCandidate(Path directory) throws IOException {
        Path expanded = directory.resolve("s2-request-window.oracle-raw-v2.jsonl");
        if (Files.exists(expanded)) {
            return expanded;
        }
        try (InputStream input = new GZIPInputStream(fixtureStream());
             OutputStream output = Files.newOutputStream(expanded)) {
            input.transferTo(output);
        }
        return expanded;
    }

    private static String digest(InputStream input)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static InputStream fixtureStream() {
        InputStream stream = TestS2RequestWindowFixture.class
                .getResourceAsStream(FIXTURE_RESOURCE);
        assertNotNull(stream, "committed S2 request-window fixture is absent");
        return stream;
    }
}
