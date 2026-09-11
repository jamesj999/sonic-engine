package com.openggf.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPlcTimingEvidenceTool {

    @TempDir
    Path temporaryDirectory;

    @Test
    void committedVariedHistoryEvidenceIsApprovedAndAnalyzerClean() throws Exception {
        Path vectors = Path.of("docs/architecture/research/trace/assets/s1-s2-plc-evidence-vectors.json.gz");
        try (var input = new GZIPInputStream(Files.newInputStream(vectors))) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(input);
            assertEquals("NATIVE_MODEL_APPROVED", root.path("disposition").asText());
            assertEquals(7, root.path("captures").size());
            for (JsonNode capture : root.path("captures")) {
                assertFalse(capture.path("route").asText().isBlank());
                var evidence = mapper.treeToValue(
                        capture.path("evidence"), PlcTimingEvidenceTool.Evidence.class);
                assertTrue(PlcTimingEvidenceTool.analyze(evidence).matches(),
                        () -> capture.path("route").asText());
                assertFalse(PlcTimingEvidenceTool.analyze(
                        evidence.withHandlerBudgets(Map.of())).matches(),
                        () -> capture.path("route").asText() + " ignored handler-budget mutation");
            }
        }
    }

    @Test
    void evidenceBudgetsSerializeInStableNumericOrder() {
        var evidence = new PlcTimingEvidenceTool.Evidence(
                "s1", Map.of(0x18, 9, 0x04, 9, 0x10, 3),
                List.of(), List.of());

        assertEquals(List.of(0x04, 0x10, 0x18),
                List.copyOf(evidence.handlerBudgets().keySet()));
    }

    /** Catches a CLI that accepts unordered raw hook records or trusts RAM deltas. */
    @Test
    void cliDerivesACompactVectorFromExecuteHookRecords() throws Exception {
        Path rom = temporaryDirectory.resolve("test.gen");
        byte[] bytes = new byte[0x1DD94];
        bytes[0x40] = 0;
        bytes[0x41] = 10;
        bytes[0x1DD88] = 0;
        bytes[0x1DD89] = 4;
        bytes[0x1DD8A] = 0;
        bytes[0x1DD8B] = 0;
        bytes[0x1DD8C] = 0;
        bytes[0x1DD8D] = 0;
        bytes[0x1DD8E] = 0;
        bytes[0x1DD8F] = 0x40;
        Files.write(rom, bytes);
        Path probe = temporaryDirectory.resolve("probe.jsonl");
        Files.writeString(probe, """
                {"raw_frame":10,"within_frame_order":1,"event":"plc_frame_state","game_mode":12,"interrupt_handler":12,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_submission","operation":"append","plc_id":1,"game_mode":12,"interrupt_handler":12,"lag":false,"hblank_deferred":false,"queue_source":64}
                {"raw_frame":10,"within_frame_order":3,"event":"plc_prepare_end","game_mode":12,"interrupt_handler":12,"lag":false,"hblank_deferred":false,"queue_source":64,"patterns_left_before":0,"patterns_left_after":10}
                {"raw_frame":11,"within_frame_order":1,"event":"plc_frame_state","game_mode":12,"interrupt_handler":0,"lag":true,"hblank_deferred":false}
                {"raw_frame":11,"within_frame_order":2,"event":"plc_vint_state","game_mode":12,"interrupt_handler":12,"lag":false,"hblank_deferred":false}
                {"raw_frame":11,"within_frame_order":3,"event":"plc_service","game_mode":12,"interrupt_handler":0,"lag":true,"hblank_deferred":false,"queue_source":64,"patterns_left_before":10,"patterns_left_after":1}
                {"raw_frame":11,"within_frame_order":4,"event":"plc_consumer_observation","consumer_id":"ready_gate","queue_empty":false,"game_mode":12,"interrupt_handler":0,"lag":true,"hblank_deferred":false,"queue_source":64,"patterns_left_after":1}
                {"raw_frame":12,"within_frame_order":1,"event":"plc_frame_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":true}
                {"raw_frame":12,"within_frame_order":2,"event":"plc_vint_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":false}
                {"raw_frame":12,"within_frame_order":3,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":true}
                {"raw_frame":12,"within_frame_order":4,"event":"plc_service","game_mode":12,"interrupt_handler":0,"lag":true,"hblank_deferred":false,"queue_source":64,"patterns_left_before":1,"patterns_left_after":0}
                {"raw_frame":12,"within_frame_order":5,"event":"plc_pop","game_mode":12,"interrupt_handler":0,"lag":true,"hblank_deferred":false,"queue_source":64,"patterns_left_after":0,"queue_slots_before":1,"queue_slots_after":0}
                {"raw_frame":12,"within_frame_order":6,"event":"plc_empty","game_mode":12,"interrupt_handler":0,"lag":true,"hblank_deferred":false,"queue_source":0,"patterns_left_after":0,"queue_slots_after":0}
                {"raw_frame":12,"within_frame_order":7,"event":"plc_consumer_observation","consumer_id":"ready_gate","queue_empty":true,"game_mode":12,"interrupt_handler":0,"lag":true,"hblank_deferred":false,"queue_source":0,"patterns_left_after":0}
                """);
        Path output = temporaryDirectory.resolve("vector.json");

        assertTrue(PlcTimingEvidenceTool.run(new String[] {
                "--game", "s1", "--rom", rom.toString(), "--probe", probe.toString(), "--out", output.toString()}));
        String vector = Files.readString(output);
        assertTrue(vector.contains("\"matches\" : true"));
        assertTrue(vector.contains("\"HBLANK_SERVICE\""));
        JsonNode predictedEdges = new ObjectMapper().readTree(vector).path("analysis").path("predictedEdges");
        assertEquals(1, predictedEdgeCount(predictedEdges, "SERVICE"));
        assertEquals(1, predictedEdgeCount(predictedEdges, "HBLANK_SERVICE"));
    }

    /** Catches a structural validator that lets HBlank events exist without one VInt segment. */
    @Test
    void cliRejectsOrphanAndDuplicateHblankStates() throws Exception {
        assertCliRejectsRawJsonl("""
                {"raw_frame":10,"within_frame_order":1,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":true}
                """);
        assertCliRejectsRawJsonl("""
                {"raw_frame":10,"within_frame_order":1,"event":"plc_vint_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":true}
                {"raw_frame":10,"within_frame_order":3,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":true}
                """);
    }

    /** Catches an HBlank transition accepted from a VInt that cannot defer its service. */
    @Test
    void cliRejectsHblankAfterLagOrNonDeferCapableVint() throws Exception {
        assertCliRejectsRawJsonl("""
                {"raw_frame":10,"within_frame_order":1,"event":"plc_vint_state","game_mode":12,"interrupt_handler":8,"lag":true,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":true}
                """);
        assertCliRejectsRawJsonl("""
                {"raw_frame":10,"within_frame_order":1,"event":"plc_vint_state","game_mode":12,"interrupt_handler":12,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":12,"lag":false,"hblank_deferred":true}
                """);
    }

    /** Catches an HBlank record whose handler, lag, or deferred state disagrees with its VInt. */
    @Test
    void cliRejectsHblankStateContradictingItsVint() throws Exception {
        assertCliRejectsRawJsonl("""
                {"raw_frame":10,"within_frame_order":1,"event":"plc_vint_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":16,"lag":false,"hblank_deferred":true}
                """);
        assertCliRejectsRawJsonl("""
                {"raw_frame":10,"within_frame_order":1,"event":"plc_vint_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":8,"lag":true,"hblank_deferred":true}
                """);
        assertCliRejectsRawJsonl("""
                {"raw_frame":10,"within_frame_order":1,"event":"plc_vint_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":false}
                """);
    }

    /** Catches a later VInt replacing the unresolved segment to which HBlank must belong. */
    @Test
    void cliRejectsHblankAssociationAfterASecondVint() throws Exception {
        assertCliRejectsRawJsonl("""
                {"raw_frame":10,"within_frame_order":1,"event":"plc_vint_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_vint_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":3,"event":"plc_hblank_state","game_mode":12,"interrupt_handler":8,"lag":false,"hblank_deferred":true}
                {"raw_frame":10,"within_frame_order":4,"event":"plc_consumer_observation","consumer_id":"ready_gate","queue_empty":true,"queue_source":0,"patterns_left_after":0}
                """);
    }

    @Test
    void cliRejectsOracleOnlyRecordsWithoutAnIndependentFrameSnapshot() throws Exception {
        Path rom = temporaryDirectory.resolve("empty.gen");
        Files.write(rom, new byte[0x1DD90]);
        Path probe = temporaryDirectory.resolve("oracle-only.jsonl");
        Files.writeString(probe, """
                {"raw_frame":10,"within_frame_order":1,"event":"plc_service","game_mode":12,"interrupt_handler":12,"lag":false,"hblank_deferred":false,"queue_source":64,"patterns_left_after":1}
                """);

        assertThrows(IllegalArgumentException.class, () -> PlcTimingEvidenceTool.run(new String[] {
                "--game", "s1", "--rom", rom.toString(), "--probe", probe.toString(),
                "--out", temporaryDirectory.resolve("should-not-exist.json").toString()}));
    }

    @Test
    void cliRejectsAServiceHookThatDidNotCaptureItsPostServiceState() throws Exception {
        Path rom = temporaryDirectory.resolve("empty.gen");
        Files.write(rom, new byte[0x1DD90]);
        Path probe = temporaryDirectory.resolve("unsafe-service.jsonl");
        Files.writeString(probe, """
                {"raw_frame":10,"within_frame_order":1,"event":"plc_frame_state","game_mode":12,"interrupt_handler":12,"lag":false,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_service","queue_source":64,"patterns_left_after":1}
                {"raw_frame":10,"within_frame_order":3,"event":"plc_consumer_observation","consumer_id":"ready_gate","queue_empty":false}
                """);

        assertThrows(IllegalArgumentException.class, () -> PlcTimingEvidenceTool.run(new String[] {
                "--game", "s1", "--rom", rom.toString(), "--probe", probe.toString(),
                "--out", temporaryDirectory.resolve("should-not-exist.json").toString()}));
    }

    /**
     * Catches a predictor that ignores a structural input and simply trusts a
     * diagnostic progress edge.
     */
    @Test
    void structuralMutationsRejectTheObservedOracle() {
        var evidence = fixture();
        assertTrue(PlcTimingEvidenceTool.analyze(evidence).matches());

        assertRejected(evidence.withRows(replace(evidence.rows(), 1,
                evidence.rows().get(1).withInterruptHandler(0x08))));
        assertRejected(evidence.withRows(replace(evidence.rows(), 1,
                evidence.rows().get(1).withLag(true))));
        assertRejected(evidence.withRows(replace(evidence.rows(), 1,
                evidence.rows().get(1).withHblankDeferred(true))));
        assertRejected(evidence.withRows(replace(evidence.rows(), 0,
                evidence.rows().get(0).withRunPlcCalled(false))));
        assertRejected(evidence.withRows(replace(evidence.rows(), 2,
                evidence.rows().get(2).withConsumerPolls(List.of(
                        new PlcTimingEvidenceTool.ConsumerPoll("ready_gate", 2),
                        new PlcTimingEvidenceTool.ConsumerPoll("ready_gate", 1))))));
        assertRejected(evidence.withHandlerBudgets(Map.of(0x0C, 3)));
    }

    @Test
    void replacementDiscardsTheOldQueueWithoutClearingItsOwnReplacement() {
        var evidence = new PlcTimingEvidenceTool.Evidence(
                "s1", Map.of(),
                List.of(new PlcTimingEvidenceTool.StructuralRow(
                        10, 12, 0, false, false,
                        List.of(
                                new PlcTimingEvidenceTool.Submission(0x20, 3),
                                new PlcTimingEvidenceTool.Submission(0x40, 10,
                                        PlcTimingEvidenceTool.SubmissionOperation.REPLACE)),
                        true, List.of(new PlcTimingEvidenceTool.ConsumerPoll("ready_gate", 1)))),
                List.of(
                        new PlcTimingEvidenceTool.ObservedEdge(10,
                                PlcTimingEvidenceTool.EdgeKind.PREPARE, 0x40, 10),
                        new PlcTimingEvidenceTool.ObservedEdge(10,
                                PlcTimingEvidenceTool.EdgeKind.CONSUMER_BUSY, 0x40, 10)));

        assertTrue(PlcTimingEvidenceTool.analyze(evidence).matches());
    }

    @Test
    void consumerSeesQueuedHeadBeforeRunPlcPreparesItsDecoder() {
        var evidence = new PlcTimingEvidenceTool.Evidence(
                "s1", Map.of(0x0C, 1),
                List.of(
                        new PlcTimingEvidenceTool.StructuralRow(
                                10, 12, 0, true, false,
                                List.of(
                                        new PlcTimingEvidenceTool.Submission(0x40, 1),
                                        new PlcTimingEvidenceTool.Submission(0x80, 6)),
                                true, List.of()),
                        new PlcTimingEvidenceTool.StructuralRow(
                                11, 12, 0x0C, false, false, List.of(), false,
                                List.of(new PlcTimingEvidenceTool.ConsumerPoll("ready_gate", 1))),
                        new PlcTimingEvidenceTool.StructuralRow(
                                12, 12, 0x0C, false, false, List.of(), true, List.of())),
                List.of(
                        new PlcTimingEvidenceTool.ObservedEdge(10,
                                PlcTimingEvidenceTool.EdgeKind.PREPARE, 0x40, 1),
                        new PlcTimingEvidenceTool.ObservedEdge(11,
                                PlcTimingEvidenceTool.EdgeKind.SERVICE, 0x40, 0),
                        new PlcTimingEvidenceTool.ObservedEdge(11,
                                PlcTimingEvidenceTool.EdgeKind.POP, 0x40, 0),
                        new PlcTimingEvidenceTool.ObservedEdge(11,
                                PlcTimingEvidenceTool.EdgeKind.CONSUMER_BUSY, 0x80, 0),
                        new PlcTimingEvidenceTool.ObservedEdge(12,
                                PlcTimingEvidenceTool.EdgeKind.PREPARE, 0x80, 6)));

        assertTrue(PlcTimingEvidenceTool.analyze(evidence).matches());
    }

    @Test
    void atomicReplacementRecordCarriesTheCompletedIdlePostState() throws Exception {
        Path rom = temporaryDirectory.resolve("replace.gen");
        byte[] bytes = new byte[0x1DD94];
        bytes[0x40] = 0; bytes[0x41] = 3;
        bytes[0x1DD88] = 0; bytes[0x1DD89] = 4;
        bytes[0x1DD8E] = 0; bytes[0x1DD8F] = 0x40;
        Files.write(rom, bytes);
        Path probe = temporaryDirectory.resolve("replace.jsonl");
        Files.writeString(probe, """
                {"raw_frame":10,"within_frame_order":1,"event":"plc_frame_state","game_mode":12,"interrupt_handler":0,"lag":true,"hblank_deferred":false}
                {"raw_frame":10,"within_frame_order":2,"event":"plc_submission","operation":"replace","plc_id":1,"patterns_left_before":0,"patterns_left_after":0,"queue_slots_after":1}
                {"raw_frame":10,"within_frame_order":3,"event":"plc_prepare_end","queue_source":64,"patterns_left_before":0,"patterns_left_after":3}
                {"raw_frame":10,"within_frame_order":4,"event":"plc_consumer_observation","consumer_id":"ready_gate","queue_empty":false,"queue_source":64,"patterns_left_after":3}
                """);
        assertTrue(PlcTimingEvidenceTool.run(new String[] {"--game", "s1", "--rom", rom.toString(),
                "--probe", probe.toString(), "--out", temporaryDirectory.resolve("replace-vector.json").toString()}));
    }

    private void assertCliRejectsRawJsonl(String records) throws Exception {
        Path rom = temporaryDirectory.resolve("empty.gen");
        Files.write(rom, new byte[0x1DD90]);
        Path probe = temporaryDirectory.resolve("invalid-probe.jsonl");
        Files.writeString(probe, records);

        assertThrows(IllegalArgumentException.class, () -> PlcTimingEvidenceTool.run(new String[] {
                "--game", "s1", "--rom", rom.toString(), "--probe", probe.toString(),
                "--out", temporaryDirectory.resolve("should-not-exist.json").toString()}));
    }

    private static long predictedEdgeCount(JsonNode predictedEdges, String kind) {
        long count = 0;
        for (JsonNode edge : predictedEdges) {
            if (kind.equals(edge.path("kind").asText())) {
                count++;
            }
        }
        return count;
    }

    private static void assertRejected(PlcTimingEvidenceTool.Evidence evidence) {
        assertFalse(PlcTimingEvidenceTool.analyze(evidence).matches());
    }

    private static List<PlcTimingEvidenceTool.StructuralRow> replace(
            List<PlcTimingEvidenceTool.StructuralRow> rows,
            int index,
            PlcTimingEvidenceTool.StructuralRow replacement) {
        var copy = new java.util.ArrayList<>(rows);
        copy.set(index, replacement);
        return List.copyOf(copy);
    }

    private static PlcTimingEvidenceTool.Evidence fixture() {
        return new PlcTimingEvidenceTool.Evidence(
                "s1",
                Map.of(0x08, 3, 0x0C, 9),
                List.of(
                        new PlcTimingEvidenceTool.StructuralRow(
                                10, 12, 0x0C, false, false,
                                List.of(new PlcTimingEvidenceTool.Submission(0x40, 10)),
                                true, List.of()),
                        new PlcTimingEvidenceTool.StructuralRow(
                                11, 12, 0x0C, false, false, List.of(), false,
                                List.of(new PlcTimingEvidenceTool.ConsumerPoll("ready_gate", 1))),
                        new PlcTimingEvidenceTool.StructuralRow(
                                12, 12, 0x0C, false, false, List.of(), false,
                                List.of(new PlcTimingEvidenceTool.ConsumerPoll("ready_gate", 1)))),
                List.of(
                        new PlcTimingEvidenceTool.ObservedEdge(10,
                                PlcTimingEvidenceTool.EdgeKind.PREPARE, 0x40, 10),
                        new PlcTimingEvidenceTool.ObservedEdge(11,
                                PlcTimingEvidenceTool.EdgeKind.SERVICE, 0x40, 1),
                        new PlcTimingEvidenceTool.ObservedEdge(11,
                                PlcTimingEvidenceTool.EdgeKind.CONSUMER_BUSY, 0x40, 1),
                        new PlcTimingEvidenceTool.ObservedEdge(12,
                                PlcTimingEvidenceTool.EdgeKind.SERVICE, 0x40, 0),
                        new PlcTimingEvidenceTool.ObservedEdge(12,
                                PlcTimingEvidenceTool.EdgeKind.POP, 0x40, 0),
                        new PlcTimingEvidenceTool.ObservedEdge(12,
                                PlcTimingEvidenceTool.EdgeKind.EMPTY, 0, 0),
                        new PlcTimingEvidenceTool.ObservedEdge(12,
                                PlcTimingEvidenceTool.EdgeKind.CONSUMER_EMPTY, 0, 0)));
    }
}
