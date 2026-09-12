package com.openggf.tools.audio.parity.s2;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integrity of every committed request-aware S2 candidate: exact gzipped and
 * expanded payload digests, and a strict parse to that candidate's own pinned
 * window with its ordered pre-consumption request transfers. A payload that
 * drifts in any byte, or a window claimed for the wrong interval, fails here
 * before any comparison can silently change meaning.
 */
// Minutes-long oracle sweep: excluded from the -Psmoke fast lane, still run by
// the default suite on pull requests, the nightly schedule and release validation.
@Tag("slow-suite")
@org.junit.jupiter.api.Tag("audio-reference")
class TestS2PublishedRequestWindows {

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyCommittedWindowMatchesItsPinnedDigestsAndShape() throws Exception {
        for (S2PublishedRequestWindows.Published published
                : S2PublishedRequestWindows.ALL) {
            try (InputStream input = published.open()) {
                assertEquals(published.payloadGzSha256(), digest(input),
                        published.name() + " gzipped payload digest");
            }
            Path expanded = published.expand(
                    java.nio.file.Files.createDirectories(
                            temporaryDirectory.resolve(published.name())));
            try (InputStream input = java.nio.file.Files.newInputStream(expanded)) {
                assertEquals(published.payloadRawSha256(), digest(input),
                        published.name() + " expanded payload digest");
            }

            S2RequestAwareOracleRawStream.Result result =
                    S2RequestAwareOracleRawStream
                            .scanWindowSourceCandidateForTesting(
                                    expanded, published.window());

            assertEquals(published.window().exclusiveEnd()
                            - published.window().firstRow(),
                    result.frames().size(), published.name() + " frame count");
            assertEquals(published.window().firstRow(), result.baseline().row(),
                    published.name() + " baseline row");
            assertEquals(published.window().firstRow() - 1,
                    result.baseline().sourcePrecedingRow(),
                    published.name() + " baseline preceding row");
            assertEquals(published.requestTransfers(), result.frames().stream()
                            .mapToInt(frame -> frame.requestTransfers().size()).sum(),
                    published.name() + " request transfers");
        }
    }

    /**
     * A window is an identity, not a hint: reading a published payload under
     * another window's bounds must fail rather than quietly compare the wrong
     * rows.
     */
    @Test
    void aPayloadReadUnderAnotherWindowIsRejected() throws Exception {
        S2PublishedRequestWindows.Published published =
                S2PublishedRequestWindows.EHZ1_CONTINUATION;
        Path expanded = published.expand(temporaryDirectory);

        assertThrows(IllegalArgumentException.class,
                () -> S2RequestAwareOracleRawStream
                        .scanWindowSourceCandidateForTesting(expanded,
                                S2PublishedRequestWindows.CONTROL.window()));
        assertThrows(IllegalArgumentException.class,
                () -> S2RequestAwareOracleRawStream
                        .scanWindowSourceCandidateForTesting(expanded,
                                S2PublishedRequestWindows.CPZ_LOAD.window()));
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
}
