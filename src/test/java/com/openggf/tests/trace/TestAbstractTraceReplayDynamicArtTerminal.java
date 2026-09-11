package com.openggf.tests.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAbstractTraceReplayDynamicArtTerminal
        extends AbstractTraceReplayTest {
    private static final Path SOURCE = Path.of(
            "src/test/resources/traces/s2/ehz1_fullrun");
    private static final int FINAL_FRAME = 5851;

    @TempDir
    Path tempDir;
    private Path activeTrace;
    private Path activeReports;

    @AfterEach
    void clearFrontierProperties() {
        System.clearProperty("trace.frontierOnly");
        System.clearProperty("trace.contextRadius");
    }

    @Override
    public void replayMatchesTrace() {
        // The two tests below call the complete inherited replay explicitly
        // after preparing their advertised fixture and policy.
    }

    @Test
    void completedAdvertisedReplayReportsStaleTerminalSnapshot()
            throws Exception {
        prepareAdvertisedFixture();

        assertThrows(AssertionError.class, super::replayMatchesTrace);

        String report = Files.readString(reportPath());
        assertTrue(report.contains("\"field\" : \"dynamic_art.frame\""));
        assertTrue(report.contains("\"end_frame\" : " + FINAL_FRAME),
                "completed replay must report the terminal expectation");
    }

    @Test
    void actualEarlyFrontierStopSuppressesUnproducedTerminalExpectation()
            throws Exception {
        prepareAdvertisedFixture();
        System.setProperty("trace.frontierOnly", "true");
        System.setProperty("trace.contextRadius", "0");

        assertThrows(AssertionError.class, super::replayMatchesTrace);

        String report = Files.readString(reportPath());
        assertTrue(report.contains("\"field\" : \"dynamic_art.frame\""));
        assertFalse(report.contains("\"end_frame\" : " + FINAL_FRAME),
                "an actually-taken early frontier must not invent terminal data");
    }

    @Override
    protected void afterFixtureBuild(com.openggf.trace.TraceData trace) {
        var gameplay = SessionManager.getCurrentGameplayMode();
        gameplay.endDynamicArtComparisonSegment();
        gameplay.plcFrameLifecycle()
                .setComparisonSegmentsExternallyManaged(true);
    }

    private void prepareAdvertisedFixture() throws IOException {
        activeTrace = tempDir.resolve("trace");
        activeReports = tempDir.resolve("reports");
        Files.createDirectories(activeTrace);
        // The committed payload is gzipped -- TestTraceFixtureCompressionGuard
        // forbids an uncompressed physics.csv under src/test/resources/traces,
        // and the replay loader reads either form. Copy the form that exists.
        Files.copy(SOURCE.resolve("physics.csv.gz"),
                activeTrace.resolve("physics.csv.gz"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(SOURCE.resolve("s2-ehz1.bk2"),
                activeTrace.resolve("s2-ehz1.bk2"),
                StandardCopyOption.REPLACE_EXISTING);

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> metadata = mapper.readValue(
                SOURCE.resolve("metadata.json").toFile(),
                new TypeReference<>() {});
        List<String> extras = new ArrayList<>(
                (List<String>) metadata.get("aux_schema_extras"));
        if (!extras.contains("dynamic_art_transfer_state_per_frame")) {
            extras.add("dynamic_art_transfer_state_per_frame");
        }
        metadata.put("aux_schema_extras", extras);
        mapper.writeValue(activeTrace.resolve("metadata.json").toFile(),
                metadata);

        // The committed fixture already advertises
        // dynamic_art_transfer_state_per_frame and carries one real heartbeat
        // per stored row. This probe substitutes its own empty per-frame
        // states, so the recorded ones must be dropped rather than appended
        // to: TraceData.validateDynamicArtTransferStates requires exactly one
        // state per stored physics frame, and two at frame 0 is a malformed
        // stream, not a validator defect.
        try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(
                            SOURCE.resolve("aux_state.jsonl.gz"))),
                    java.nio.charset.StandardCharsets.UTF_8));
             var output = Files.newBufferedWriter(
                     activeTrace.resolve("aux_state.jsonl"),
                     java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.contains("\"event\": \"dynamic_art_transfer_state\"")
                        || line.contains("\"event\":\"dynamic_art_transfer_state\"")) {
                    continue;
                }
                output.write(line);
                output.write("\n");
            }
            for (int frame = 0; frame <= FINAL_FRAME; frame++) {
                output.write(("""
                        {"frame":%d,"event":"dynamic_art_transfer_state",\
"edges":[],"outstanding_transfer_ids":[]}
                        """.formatted(frame)));
            }
        }
    }

    private Path reportPath() {
        return activeReports.resolve("s2_ehz1_report.json");
    }

    @Override protected SonicGame game() { return SonicGame.SONIC_2; }
    @Override protected int zone() { return 0; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return activeTrace; }
    @Override protected Path reportOutputDir() { return activeReports; }
}
