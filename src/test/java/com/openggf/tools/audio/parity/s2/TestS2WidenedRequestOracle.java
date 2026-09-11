package com.openggf.tools.audio.parity.s2;

import com.openggf.tests.SessionInvocationExtension;
import com.openggf.tools.audio.completerun.s2.S2NativeSoundResolver;
import com.openggf.tests.trace.runs.S2RequestProjectionBk2TestBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Widened request-aware measurement: every committed window cut from the
 * complete run that the run-chain harness can replay is compared against the
 * engine's own request projection, from one capture that spans them all. The
 * committed payloads are comparison-only; nothing here hydrates engine state,
 * and nothing here fixes an engine divergence.
 */
class TestS2WidenedRequestOracle {

    /** The widest movie row the committed run chain replays in one capture. */
    private static final int CAPTURE_FIRST_ROW = 10_150;
    private static final int CAPTURE_EXCLUSIVE_END = 12_400;

    @TempDir
    Path temporaryDirectory;

    /** How many of a published window's transfers were observed at one site. */
    private static int siteTransfers(S2PublishedRequestWindows.Published published,
            S2RequestAwareOracleRawStream.TransferSite site, Path directory)
            throws Exception {
        return (int) transfers(published, directory).stream()
                .filter(transfer -> transfer.site() == site)
                .count();
    }

    /**
     * The driver's music playlist range. {@code zPlaySound} treats an id below
     * {@code MusID__First} as no music, and {@code MusID__First} is {@code 81h}
     * with the last playlist entry at {@code 9Fh}
     * (docs/s2disasm/s2.constants.asm:832, s2.sounddriver.asm:1514).
     */
    private static final int MUSIC_FIRST_ID = 0x81;
    private static final int MUSIC_LAST_ID = 0x9f;

    /**
     * One site's transfers as row and request id, in order.
     *
     * <p>A playlist id is translated into the engine's own music id space.
     * The ROM stores the playlist id in {@code sndDriverInput}, where
     * {@code MusID_EHZ} is {@code 82h} (docs/s2disasm/s2.constants.asm:834),
     * while the engine's request pipeline carries its own music id, where
     * Emerald Hill is {@code 0x81}. {@link S2NativeSoundResolver} owns that
     * mapping, so it is read out of the resolver rather than assumed to be a
     * fixed offset. Command ids above the playlist are the same byte in both
     * spaces, which the speed-shoes {@code FBh} transfer confirms.
     */
    private static List<String> siteRequests(
            S2PublishedRequestWindows.Published published,
            S2RequestAwareOracleRawStream.TransferSite site, Path directory)
            throws Exception {
        List<String> result = new ArrayList<>();
        for (S2RequestAwareOracleRawStream.RequestTransfer transfer
                : transfers(published, directory)) {
            if (transfer.site() != site) {
                continue;
            }
            int id = transfer.requestByte();
            if (id >= MUSIC_FIRST_ID && id <= MUSIC_LAST_ID) {
                id = S2NativeSoundResolver.rev01().fromNativeId(id).engineApiId();
            }
            result.add(transfer.sourceRow() + ":" + id);
        }
        return List.copyOf(result);
    }

    /** Every transfer the published window recorded, in observation order. */
    private static List<S2RequestAwareOracleRawStream.RequestTransfer> transfers(
            S2PublishedRequestWindows.Published published, Path directory)
            throws Exception {
        Path expanded = published.expand(directory);
        S2RequestAwareOracleRawStream.Result reference =
                S2RequestAwareOracleRawStream.scanWindowSourceCandidateForTesting(
                        expanded, published.window());
        List<S2RequestAwareOracleRawStream.RequestTransfer> result = new ArrayList<>();
        for (S2RequestAwareOracleRawStream.Frame frame : reference.frames()) {
            result.addAll(frame.requestTransfers());
        }
        result.sort(Comparator.comparingLong(
                S2RequestAwareOracleRawStream.RequestTransfer::sourceGlobalOrdinal));
        return List.copyOf(result);
    }

    @Test
    @ExtendWith(SessionInvocationExtension.class)
    void everyReplayableWindowComparesAgainstTheEnginesOwnRequests()
            throws Exception {
        String romProperty = System.getProperty("sonic2.rom.path");
        String bk2Property = System.getProperty("s2.request.bk2.path");
        assumeTrue(romProperty != null && bk2Property != null,
                "explicit ROM and BK2 paths are required");

        S2RequestProjectionBk2TestBridge.Capture capture =
                S2RequestProjectionBk2TestBridge.capture(
                        Path.of(romProperty), Path.of(bk2Property),
                        CAPTURE_FIRST_ROW, CAPTURE_EXCLUSIVE_END);

        for (S2PublishedRequestWindows.Published published
                : S2PublishedRequestWindows.COMPLETE_RUN_WINDOWS) {
            if (published.window().exclusiveEnd() > CAPTURE_EXCLUSIVE_END) {
                continue;
            }
            S2RequestAwareCandidateComparator.Report report =
                    S2RequestAwareCandidateComparator.compareWindow(
                            published.expand(temporaryDirectory),
                            published.window(), capture.projector(),
                            capture.requestRows());
            System.out.println("MEASUREMENT_ONLY " + published.name() + " "
                    + report.describe());
            assertEquals(S2RequestAwareCandidateComparator.Kind.MATCH,
                    report.kind(), published.name() + ": " + report.describe());
            // The projector models the ROM's three-entry sound queue
            // (Sonic2SoundRequestPipeline.QueueSlot), so it can only ever see
            // the SFX site. Pin its count against the SFX-site transfers
            // rather than the payload total.
            assertEquals(siteTransfers(published, S2RequestAwareOracleRawStream
                            .TransferSite.SFX, temporaryDirectory),
                    report.comparedTransfers(),
                    published.name() + ": " + report.describe());

            // The music site is compared at the engine's own model of the
            // ROM's second request store: submitMusic, which the pipeline
            // holds as the MUSIC0/MUSIC1 mailbox. Both sides carry the native
            // request byte, so no id translation takes part.
            List<String> expectedMusic = siteRequests(published,
                    S2RequestAwareOracleRawStream.TransferSite.MUSIC,
                    temporaryDirectory);
            List<String> actualMusic = new ArrayList<>();
            for (int index = 0; index < capture.musicSubmissionRows().size(); index++) {
                int row = capture.musicSubmissionRows().get(index);
                if (row < published.window().firstRow()
                        || row >= published.window().exclusiveEnd()) {
                    continue;
                }
                actualMusic.add(row + ":" + capture.projector().musicSubmissions()
                        .get(index).nativeRequestId());
            }
            System.out.println("MEASUREMENT_ONLY " + published.name()
                    + " music mailbox: reference=" + expectedMusic.size()
                    + " engine=" + actualMusic.size());
            assertEquals(expectedMusic, actualMusic, published.name()
                    + ": the engine must submit every music-site request the ROM"
                    + " stored, on the same rows and with the same byte");

            // The two sites together account for the whole published window.
            assertEquals(published.requestTransfers(),
                    report.comparedTransfers() + expectedMusic.size(),
                    published.name() + ": published transfer count");
        }
    }

    /**
     * A deliberately corrupted candidate must break the comparison. Without
     * this, a window that never actually compared would read exactly like a
     * window that agreed.
     */
    @Test
    void aCorruptedCandidateBreaksTheComparison() throws Exception {
        S2PublishedRequestWindows.Published published =
                S2PublishedRequestWindows.EHZ1_CONTINUATION;
        Path expanded = published.expand(temporaryDirectory);
        byte[] payload = java.nio.file.Files.readAllBytes(expanded);
        int index = new String(payload, java.nio.charset.StandardCharsets.UTF_8)
                .indexOf("\"first_row\":10900");
        assertNotEquals(-1, index, "the window bound must appear in the payload");
        Path corrupted = temporaryDirectory.resolve("corrupted.jsonl");
        java.nio.file.Files.writeString(corrupted,
                new String(payload, java.nio.charset.StandardCharsets.UTF_8)
                        .replaceFirst("\"first_row\":10900", "\"first_row\":10901"));

        S2RequestAwareCandidateComparator.Report report =
                S2RequestAwareCandidateComparator.compareWindow(corrupted,
                        published.window(),
                        new com.openggf.tools.audio.completerun.s2
                                .S2ProductionRequestProjector(),
                        java.util.List.of());

        assertEquals(S2RequestAwareCandidateComparator.Kind.INVALID,
                report.kind(), report.describe());
    }
}
