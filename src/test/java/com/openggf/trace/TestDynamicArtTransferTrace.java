package com.openggf.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDynamicArtTransferTrace {

    private static final String S1_SPECIAL_STAGE_HEADER =
            "frame,input,lag,x_pos,y_pos,vel_x,vel_y,inertia,status,ss_angle,"
                    + "ss_rotate,bg_anim,rings,emeralds\n";

    private static final String S2_SPECIAL_STAGE_HEADER =
            "frame,input,input_p2,lag,speed_factor,track_anim,track_anim_frame,"
                    + "track_drawing_index,track_orientation,track_duration_timer,"
                    + "current_segment,player_anim_frame_timer,rings_togo_bcd,"
                    + "check_rings_flag,tails_control_counter,swap_positions_flag,"
                    + "sonic_present,sonic_ss_x,sonic_ss_x_sub,sonic_ss_y,"
                    + "sonic_ss_y_sub,sonic_ss_z,sonic_angle,sonic_routine,"
                    + "sonic_routine_secondary,sonic_status,sonic_anim,"
                    + "sonic_anim_frame,sonic_rings_bcd,sonic_hurt_timer,"
                    + "sonic_slide_timer,sonic_flip_timer,tails_present,tails_ss_x,"
                    + "tails_ss_x_sub,tails_ss_y,tails_ss_y_sub,tails_ss_z,"
                    + "tails_angle,tails_routine,tails_routine_secondary,tails_status,"
                    + "tails_anim,tails_anim_frame,tails_rings_bcd,tails_hurt_timer,"
                    + "tails_slide_timer,tails_flip_timer\n";

    private static final String ROM_REQUEST = """
            {"rom_source_address":327680,"source_tile_index":18,
             "ram_source_address":-1,"vram_destination":61440,"byte_length":64}
            """.replaceAll("\\s+", "");

    private static final String RAM_REQUEST = """
            {"rom_source_address":-1,"source_tile_index":-1,
             "ram_source_address":51200,"vram_destination":61440,"byte_length":736}
            """.replaceAll("\\s+", "");

    @Test
    void parsesNativeEnvelopeAndReproducesDescriptorFingerprint()
            throws Exception {
        TraceEvent event = TraceEvent.parseJsonLine(envelope(7,
                submittedEdge(11, 42, 5, 9, 0, 7, false, ROM_REQUEST),
                "[42]"), new ObjectMapper());

        TraceEvent.DynamicArtTransferState state =
                assertInstanceOf(TraceEvent.DynamicArtTransferState.class, event);
        assertEquals(7, state.frame());
        assertEquals(11, state.edges().getFirst().edgeOrdinal());
        assertEquals(82794, state.edges().getFirst().romCallbackPc());
        assertEquals("sha256:1bb0a8d4cac355faf96c97dd4828479a8902c5367fca9aff98c7d857bbb21da7",
                DynamicArtTransfer.fingerprint(state.edges().getFirst().submissionDescriptor()));
        assertFalse(TraceEventFormatter.summariseFrameEvents(List.of(state))
                        .contains("82794"),
                "rom_callback_pc is validation evidence, not compared output");

        DynamicArtTransfer.SegmentEdge firstSite =
                DynamicArtTransfer.parseSegmentEdge(new ObjectMapper().readTree(
                        completedEdge(12, 42, 5, 10, 0, 8, false, RAM_REQUEST)));
        DynamicArtTransfer.SegmentEdge secondSite =
                DynamicArtTransfer.parseSegmentEdge(new ObjectMapper().readTree(
                        completedEdge(12, 42, 5, 10, 0, 8, false, RAM_REQUEST)
                                .replace("\"rom_callback_pc\":3408",
                                        "\"rom_callback_pc\":3684")));
        assertEquals(firstSite.comparisonView(), secondSite.comparisonView());
    }

    @Test
    void rejectsRequestSentinelRangeAndCallbackPcViolations() {
        assertThrows(IllegalArgumentException.class,
                () -> parseWithRequest(ROM_REQUEST.replace(
                        "\"ram_source_address\":-1", "\"ram_source_address\":123")));
        assertThrows(IllegalArgumentException.class,
                () -> parseWithRequest(ROM_REQUEST.replace(
                        "\"source_tile_index\":18", "\"source_tile_index\":-1")));
        assertThrows(IllegalArgumentException.class,
                () -> parseWithRequest(ROM_REQUEST.replace(
                        "\"vram_destination\":61440", "\"vram_destination\":65536")));
        assertThrows(IllegalArgumentException.class,
                () -> TraceEvent.parseJsonLine(envelope(7,
                        submittedEdge(11, 42, 5, 9, 0, 7, false, ROM_REQUEST)
                                .replace("\"rom_callback_pc\":82794",
                                        "\"rom_callback_pc\":16777216"),
                        "[42]"), new ObjectMapper()));

        TraceEvent.DynamicArtTransferState wrongProfile =
                (TraceEvent.DynamicArtTransferState) TraceEvent.parseJsonLine(
                        envelope(0, submittedEdge(
                                11, 42, 5, 0, 0, 0, false, ROM_REQUEST)
                                .replace("\"rom_callback_pc\":82794",
                                        "\"rom_callback_pc\":5290"),
                                "[42]"),
                        new ObjectMapper());
        assertThrows(IllegalArgumentException.class,
                () -> DynamicArtTransfer.validateSegment(
                        List.of(wrongProfile),
                        new StoredPhysicsFrameDomain(List.of(0)),
                        "s1", new DynamicArtTransfer.LifecycleIdentity()));
    }

    @Test
    void acceptsOnlyTheFourRetailS1DynamicArtSubmissionSites() {
        for (int callbackPc : List.of(0x0D20, 0x0E34, 0x0F24, 0x1030)) {
            TraceEvent.DynamicArtTransferState state =
                    (TraceEvent.DynamicArtTransferState) TraceEvent.parseJsonLine(
                            envelope(0, submittedEdge(
                                    0, 1, 8, 0, 0, 0, false, ROM_REQUEST)
                                    .replace("\"rom_callback_pc\":82794",
                                            "\"rom_callback_pc\":"
                                                    + callbackPc),
                                    "[1]"),
                            new ObjectMapper());
            assertDoesNotThrow(() -> DynamicArtTransfer.validateSegment(
                    List.of(state),
                    new StoredPhysicsFrameDomain(List.of(0)),
                    "s1", new DynamicArtTransfer.LifecycleIdentity()));
        }

        TraceEvent.DynamicArtTransferState staleSite =
                (TraceEvent.DynamicArtTransferState) TraceEvent.parseJsonLine(
                        envelope(0, submittedEdge(
                                0, 1, 8, 0, 0, 0, false, ROM_REQUEST),
                                "[1]"),
                        new ObjectMapper());
        assertThrows(IllegalArgumentException.class,
                () -> DynamicArtTransfer.validateSegment(
                        List.of(staleSite),
                        new StoredPhysicsFrameDomain(List.of(0)),
                        "s1", new DynamicArtTransfer.LifecycleIdentity()));
    }

    @Test
    void advertisedCapabilityFailsClosedOnUnknownEvent(@TempDir Path dir)
            throws IOException {
        writeMetadata(dir, 1);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(1));
        Files.writeString(dir.resolve("aux_state.jsonl"),
                "{\"frame\":0,\"event\":\"dynamic_art_transfer_states\","
                        + "\"edges\":[],\"outstanding_transfer_ids\":[]}\n");

        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));
    }

    @Test
    void advertisedCapabilityAcceptsKnownGenericNativeEvents(@TempDir Path dir)
            throws IOException {
        writeMetadata(dir, 1);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(1));
        StringBuilder aux = new StringBuilder();
        for (String event : List.of("state_snapshot", "cursor_state", "slot_dump",
                "s2_tornado_state", "cnz_slot_machine_state")) {
            aux.append("{\"frame\":0,\"event\":\"").append(event)
                    .append("\",\"native_value\":1}\n");
        }
        aux.append(envelope(0, "", "[]")).append('\n');
        Files.writeString(dir.resolve("aux_state.jsonl"), aux);

        TraceData trace = assertDoesNotThrow(() -> TraceData.load(dir));
        assertEquals(3, trace.getEventsForFrame(0).stream()
                .filter(TraceEvent.StateSnapshot.class::isInstance).count());
        assertEquals(1, trace.getEventsForFrame(0).stream()
                .filter(TraceEvent.S2TornadoState.class::isInstance).count());
        assertEquals(1, trace.getEventsForFrame(0).stream()
                .filter(TraceEvent.CnzSlotMachineState.class::isInstance).count());
    }

    @Test
    void validatesEveryStoredRowIncludingFirstLastAndLagRows(@TempDir Path dir)
            throws IOException {
        writeMetadata(dir, 3);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(3));
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, "", "[]") + "\n"
                        + envelope(1, "", "[]") + "\n"
                        + envelope(2, "", "[]") + "\n");

        TraceData trace = TraceData.load(dir);
        assertEquals(1, trace.dynamicArtTransferStatesForFrame(1).size());

        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, "", "[]") + "\n"
                        + envelope(2, "", "[]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));

        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, "", "[]") + "\n"
                        + envelope(1, "", "[]") + "\n"
                        + envelope(2, "", "[]") + "\n"
                        + envelope(3, "", "[]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));
    }

    @Test
    void validatesLifecycleCursorPairingLedgerAndTerminalPending(@TempDir Path dir)
            throws IOException {
        writeMetadata(dir, 2);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(2));
        String submitted = submittedEdge(0, 4, 3, 0, 0, 0, false, ROM_REQUEST)
                .replace("\"rom_callback_pc\":82794",
                        "\"rom_callback_pc\":3360");
        String completed = completedEdge(1, 4, 3, 1, 0, 1, false, RAM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, submitted, "[4]") + "\n"
                        + envelope(1, completed, "[]") + "\n");
        assertTrue(TraceData.load(dir).terminalDynamicArtLedger().isEmpty());

        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, submitted, "[4]") + "\n"
                        + envelope(1, "", "[4]") + "\n");
        assertEquals(List.of(4L), TraceData.load(dir).terminalDynamicArtTransferIds());

        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, completed, "[]") + "\n"
                        + envelope(1, "", "[]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));

        String duplicateOrdinalCompletion =
                completedEdge(0, 4, 3, 1, 0, 1, false, RAM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, submitted, "[4]") + "\n"
                        + envelope(1, duplicateOrdinalCompletion, "[]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));

        String duplicateTransfer = submittedEdge(
                1, 4, 4, 1, 0, 1, false, ROM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, submitted, "[4]") + "\n"
                        + envelope(1, duplicateTransfer, "[4]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));

        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, submitted, "[4]") + "\n"
                        + envelope(1, completed.replace("\"logical_frame\":1",
                                "\"logical_frame\":0"), "[]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));

        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, submitted, "[4]") + "\n"
                        + envelope(1, completed.replace("\"owner\":\"sonic\"",
                                "\"owner\":\"tails\""), "[]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));
    }

    @Test
    void rejectsNonContiguousCursorsDescendingOrdinalsAndReusedTransferIds(
            @TempDir Path dir) throws IOException {
        writeMetadata(dir, 3);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(3));
        String submitted = submittedEdge(2, 4, 3, 0, 0, 0, false, ROM_REQUEST);
        String completed = completedEdge(1, 4, 3, 1, 0, 1, false, RAM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, submitted, "[4]") + "\n"
                        + envelope(1, completed, "[]") + "\n"
                        + envelope(2, "", "[]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));

        String skippedIndex = submittedEdge(0, 4, 3, 0, 1, 0, false, ROM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, skippedIndex, "[4]") + "\n"
                        + envelope(1, "", "[4]") + "\n"
                        + envelope(2, "", "[4]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));

        String first = submittedEdge(0, 4, 3, 0, 0, 0, false, ROM_REQUEST);
        String done = completedEdge(1, 4, 3, 1, 0, 1, false, RAM_REQUEST);
        String reused = submittedEdge(2, 4, 4, 2, 0, 2, false, ROM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, first, "[4]") + "\n"
                        + envelope(1, done, "[]") + "\n"
                        + envelope(2, reused, "[4]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));
    }

    @Test
    void enforcesS1SubmissionAndCompletionSourceDomains(@TempDir Path dir)
            throws IOException {
        writeMetadata(dir, 2);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(2));
        String ramSubmission =
                submittedEdge(0, 4, 3, 0, 0, 0, false, RAM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, ramSubmission, "[4]") + "\n"
                        + envelope(1, "", "[4]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));

        String romSubmission =
                submittedEdge(0, 4, 3, 0, 0, 0, false, ROM_REQUEST);
        String fabricatedRomCompletion =
                completedEdge(1, 4, 3, 1, 0, 1, false, ROM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, romSubmission, "[4]") + "\n"
                        + envelope(1, fabricatedRomCompletion, "[]") + "\n");
        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));
    }

    @Test
    void rejectsGapCursorFieldsOnSegmentEdges() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String edge = submittedEdge(0, 4, 3, 0, 0, 0, false, ROM_REQUEST);

        assertThrows(IllegalArgumentException.class,
                () -> DynamicArtTransfer.parseSegmentEdge(mapper.readTree(
                        edge.replace("\"requests\":",
                                "\"movie_logical_frame\":0,\"requests\":"))));
        assertThrows(IllegalArgumentException.class,
                () -> DynamicArtTransfer.parseSegmentEdge(mapper.readTree(
                        edge.replace("\"requests\":",
                                "\"gap_edge_index\":0,\"requests\":"))));
    }

    @Test
    void runValidationSharesOrdinalAndTransferIdentityAcrossSegmentAndGap(
            @TempDir Path dir) throws IOException {
        writeMetadata(dir, "s2", 1);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(1));
        String submission = submittedEdge(
                2, 4, 3, 0, 0, 0, false, ROM_REQUEST)
                .replace("\"rom_callback_pc\":82794",
                        "\"rom_callback_pc\":5290");
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, submission, "[4]") + "\n");
        TraceData trace = TraceData.load(dir);

        DynamicArtTransfer.Request request =
                new DynamicArtTransfer.Request(327680, 18, -1, 61440, 64);
        DynamicArtTransfer.GapEdge completion = new DynamicArtTransfer.GapEdge(
                1, 4, "completed", "sonic", "segment", 3, 1, 0, 5292,
                List.of(request));
        DynamicArtTransfer.GapTransition transition =
                new DynamicArtTransfer.GapTransition(
                        completion,
                        DynamicArtTransfer.ledgerHash(
                                trace.terminalDynamicArtLedger()),
                        List.of());
        TraceRunManifest run = runManifest(
                List.of(transition), 1);

        assertThrows(IllegalStateException.class,
                () -> run.validateDynamicArtRun(List.of(trace)));
    }

    @Test
    void runValidationPermitsPendingLedgerAtMovieEndButRejectsReuse(
            @TempDir Path dir) throws IOException {
        writeMetadata(dir, "s2", 1);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(1));
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, "", "[]") + "\n");
        TraceData trace = TraceData.load(dir);

        DynamicArtTransfer.Request request =
                new DynamicArtTransfer.Request(327680, 18, -1, 61440, 64);
        DynamicArtTransfer.GapEdge submission = new DynamicArtTransfer.GapEdge(
                0, 4, "submitted", "sonic", "run_gap", 3, 1, 0, 5290,
                List.of(request));
        DynamicArtTransfer.Descriptor descriptor =
                submission.submissionDescriptor();
        DynamicArtTransfer.GapTransition pending =
                new DynamicArtTransfer.GapTransition(
                        submission, DynamicArtTransfer.ledgerHash(List.of()),
                        List.of(descriptor));
        assertDoesNotThrow(() -> runManifest(List.of(pending), 1)
                .validateDynamicArtRun(List.of(trace)));

        DynamicArtTransfer.GapEdge completion = new DynamicArtTransfer.GapEdge(
                1, 4, "completed", "sonic", "run_gap", 3, 2, 0, 5292,
                List.of(request));
        DynamicArtTransfer.GapEdge reused = new DynamicArtTransfer.GapEdge(
                2, 4, "submitted", "sonic", "run_gap", 4, 3, 0, 5290,
                List.of(request));
        DynamicArtTransfer.GapTransition done =
                new DynamicArtTransfer.GapTransition(
                        completion,
                        DynamicArtTransfer.ledgerHash(List.of(descriptor)),
                        List.of());
        DynamicArtTransfer.GapTransition reuse =
                new DynamicArtTransfer.GapTransition(
                        reused, DynamicArtTransfer.ledgerHash(List.of()),
                        List.of(reused.submissionDescriptor()));
        assertThrows(IllegalStateException.class,
                () -> runManifest(List.of(pending, done, reuse), 1)
                        .validateDynamicArtRun(List.of(trace)));
    }

    @Test
    void rejectsNonContiguousGapCursor() {
        DynamicArtTransfer.Request request =
                new DynamicArtTransfer.Request(327680, 18, -1, 61440, 64);
        DynamicArtTransfer.GapEdge submission = new DynamicArtTransfer.GapEdge(
                0, 4, "submitted", "sonic", "run_gap", 3, 1, 1, 5290,
                List.of(request));
        DynamicArtTransfer.GapTransition transition =
                new DynamicArtTransfer.GapTransition(
                        submission, DynamicArtTransfer.ledgerHash(List.of()),
                        List.of(submission.submissionDescriptor()));

        assertThrows(IllegalStateException.class,
                () -> DynamicArtTransfer.validateGaps(
                        List.of(transition), List.of(), "s2",
                        new DynamicArtTransfer.LifecycleIdentity()));
    }

    @Test
    void terminalForwardingIsConfinedToLastStoredRow(@TempDir Path dir)
            throws IOException {
        writeMetadata(dir, 2);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(2));
        String forwarded = submittedEdge(0, 4, 3, 0, 0, 0, true, ROM_REQUEST);
        Files.writeString(dir.resolve("aux_state.jsonl"),
                envelope(0, forwarded, "[4]") + "\n"
                        + envelope(1, "", "[4]") + "\n");

        assertThrows(IllegalArgumentException.class, () -> TraceData.load(dir));
    }

    @Test
    void scannerAcceptsPlainAndGzipAndRejectsNonContiguousFrameDomains(
            @TempDir Path dir) throws IOException {
        Path plain = dir.resolve("physics.csv");
        Files.writeString(plain, "frame,anything\n0,a\n1,b\n2,c\n");
        StoredPhysicsFrameDomain domain = StoredPhysicsFrameDomain.scan(plain);
        assertEquals(List.of(0, 1, 2), domain.frames());

        Path gzip = dir.resolve("physics.csv.gz");
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(gzip))) {
            output.write("frame,anything\n0,a\n1,b\n2,c\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(domain, StoredPhysicsFrameDomain.scan(gzip));

        Files.writeString(plain, "frame,anything\n"
                + "0000,a\n0001,b\n0002,c\n0003,d\n0004,e\n0005,f\n"
                + "0006,g\n0007,h\n0008,i\n0009,j\n000A,k\n");
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                StoredPhysicsFrameDomain.scan(plain).frames());

        Files.writeString(plain, "frame,anything\n0,a\n2,c\n");
        assertThrows(IllegalArgumentException.class,
                () -> StoredPhysicsFrameDomain.scan(plain));
        Files.writeString(plain, "frame,anything\n1,a\n");
        assertThrows(IllegalArgumentException.class,
                () -> StoredPhysicsFrameDomain.scan(plain));
    }

    @Test
    void actualSpecialStageLoadersValidateAdvertisedRowsForPlainAndGzip(
            @TempDir Path dir) throws IOException {
        StringBuilder s1Physics = new StringBuilder(S1_SPECIAL_STAGE_HEADER);
        StringBuilder s2Physics = new StringBuilder(S2_SPECIAL_STAGE_HEADER);
        StringBuilder aux = new StringBuilder();
        for (int frame = 0; frame <= 10; frame++) {
            s1Physics.append(s1SpecialStageRow(frame)).append('\n');
            s2Physics.append(s2SpecialStageRow(frame)).append('\n');
            aux.append(envelope(frame, "", "[]")).append('\n');
        }

        Path s1 = dir.resolve("s1");
        Files.createDirectories(s1);
        writeSpecialStageMetadata(s1, "s1", "s1_special_stage", 11);
        Files.writeString(s1.resolve("physics.csv"), s1Physics);
        Files.writeString(s1.resolve("aux_state.jsonl"), aux);
        Sonic1SpecialStageTraceData s1Trace =
                Sonic1SpecialStageTraceData.load(s1);
        assertEquals(11, s1Trace.frameCount());
        assertEquals(10, s1Trace.dynamicArtTransferStateForFrame(10).frame());
        Files.writeString(s1.resolve("aux_state.jsonl"), envelope(0, "", "[]") + "\n");
        assertThrows(IllegalArgumentException.class,
                () -> Sonic1SpecialStageTraceData.load(s1));

        Path s2 = dir.resolve("s2");
        Files.createDirectories(s2);
        writeSpecialStageMetadata(s2, "s2", "s2_special_stage", 11);
        writeGzip(s2.resolve("physics.csv.gz"), s2Physics.toString());
        writeGzip(s2.resolve("aux_state.jsonl.gz"), aux.toString());
        SpecialStageTraceData s2Trace = SpecialStageTraceData.load(s2);
        assertEquals(11, s2Trace.frameCount());
        assertEquals(10, s2Trace.dynamicArtTransferStateForFrame(10).frame());
        writeGzip(s2.resolve("aux_state.jsonl.gz"), envelope(0, "", "[]") + "\n");
        assertThrows(IllegalArgumentException.class,
                () -> SpecialStageTraceData.load(s2));
    }

    @Test
    void legacyMetadataMayOmitDynamicArtCapability(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("metadata.json"), """
                {"game":"s1","zone":"ghz","act":1,"bk2_frame_offset":0,
                 "trace_frame_count":1,"trace_schema":5,
                 "start_x":"0000","start_y":"0000","aux_schema_extras":[]}
                """);
        Files.writeString(dir.resolve("physics.csv"), physicsCsv(1));

        TraceData trace = TraceData.load(dir);
        assertFalse(trace.metadata().hasPerFrameDynamicArtTransferState());
    }

    private static void parseWithRequest(String request) {
        TraceEvent.parseJsonLine(envelope(7,
                submittedEdge(11, 42, 5, 9, 0, 7, false, request), "[42]"),
                new ObjectMapper());
    }

    private static String submittedEdge(long ordinal, long transferId,
            int mappingFrame, int logicalFrame, int logicalEdgeIndex,
            int publicationFrame, boolean terminalForwarded, String request) {
        return edge(ordinal, transferId, "submitted", "sonic", "segment",
                mappingFrame, logicalFrame, logicalEdgeIndex, publicationFrame,
                terminalForwarded, 82794, request);
    }

    private static String completedEdge(long ordinal, long transferId,
            int mappingFrame, int logicalFrame, int logicalEdgeIndex,
            int publicationFrame, boolean terminalForwarded, String request) {
        return edge(ordinal, transferId, "completed", "sonic", "segment",
                mappingFrame, logicalFrame, logicalEdgeIndex, publicationFrame,
                terminalForwarded, 3408, request);
    }

    private static String edge(long ordinal, long transferId, String phase,
            String owner, String origin, int mappingFrame, int logicalFrame,
            int logicalEdgeIndex, int publicationFrame,
            boolean terminalForwarded, int callbackPc, String request) {
        return "{\"edge_ordinal\":" + ordinal + ",\"transfer_id\":" + transferId
                + ",\"phase\":\"" + phase + "\",\"owner\":\"" + owner
                + "\",\"submission_origin\":\"" + origin
                + "\",\"mapping_frame\":" + mappingFrame
                + ",\"logical_frame\":" + logicalFrame
                + ",\"logical_edge_index\":" + logicalEdgeIndex
                + ",\"publication_frame\":" + publicationFrame
                + ",\"terminal_forwarded\":" + terminalForwarded
                + ",\"rom_callback_pc\":" + callbackPc
                + ",\"requests\":[" + request + "]}";
    }

    private static String envelope(int frame, String edges, String outstanding) {
        return "{\"frame\":" + frame
                + ",\"event\":\"dynamic_art_transfer_state\",\"edges\":["
                + edges + "],\"outstanding_transfer_ids\":" + outstanding + "}";
    }

    private static void writeMetadata(Path dir, int frameCount) throws IOException {
        writeMetadata(dir, "s1", frameCount);
    }

    private static void writeMetadata(
            Path dir, String game, int frameCount) throws IOException {
        Files.writeString(dir.resolve("metadata.json"), """
                {"game":"%s","zone":"ghz","act":1,"bk2_frame_offset":0,
                 "trace_frame_count":%d,"trace_schema":5,
                 "start_x":"0000","start_y":"0000",
                 "aux_schema_extras":["pre_level_intro_prefix",
                   "dynamic_art_transfer_state_per_frame"]}
                """.formatted(game, frameCount));
    }

    private static TraceRunManifest runManifest(
            List<DynamicArtTransfer.GapTransition> gaps, int frameCount) {
        return new TraceRunManifest(
                "s2", "synthetic", "synthetic.bk2", "checksum",
                List.of(new TraceRunManifest.Segment(
                        "segment", "level", "complete_run", 0, frameCount,
                        0, 1, null, null)),
                List.of(), gaps,
                TraceRunManifest.ExpectedMovieEndMode.UNSPECIFIED);
    }

    private static void writeSpecialStageMetadata(Path dir, String game,
            String profile, int frameCount) throws IOException {
        Files.writeString(dir.resolve("metadata.json"), """
                {"game":"%s","trace_profile":"%s","act":1,
                 "bk2_frame_offset":0,"trace_frame_count":%d,
                 "trace_schema":5,
                 "start_x":"0000","start_y":"0000",
                 "aux_schema_extras":["dynamic_art_transfer_state_per_frame"]}
                """.formatted(game, profile, frameCount));
    }

    private static String s1SpecialStageRow(int frame) {
        return frame
                + ",208,0,fffe8000,00478000,fe00,0123,0456,03,4000,ff80,6,17,1";
    }

    private static String s2SpecialStageRow(int frame) {
        return frame + ",8,0,0,2,3,4,5,6,7,8,9,a1a2a3,b,c,d,"
                + "1,10,11,12,13,14,15,16,17,18,19,1a,010203,1b,1c,1d,"
                + "0,20,21,22,23,24,25,26,27,28,29,2a,040506,2b,2c,2d";
    }

    private static void writeGzip(Path path, String contents) throws IOException {
        try (GZIPOutputStream output =
                new GZIPOutputStream(Files.newOutputStream(path))) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String physicsCsv(int frameCount) {
        String header = "frame,input,camera_x,camera_y,rings,gameplay_frame_counter,"
                + "vblank_counter,lag_counter,player_present,player_x,player_y,"
                + "player_x_speed,player_y_speed,player_g_speed,player_angle,"
                + "player_air,player_rolling,player_ground_mode,player_x_sub,"
                + "player_y_sub,player_routine,player_status_byte,player_stand_on_obj,"
                + "player_animation_id,player_mapping_frame,sidekick_present,"
                + "sidekick_x,sidekick_y,sidekick_x_speed,sidekick_y_speed,"
                + "sidekick_g_speed,sidekick_angle,sidekick_air,sidekick_rolling,"
                + "sidekick_ground_mode,sidekick_x_sub,sidekick_y_sub,sidekick_routine,"
                + "sidekick_status_byte,sidekick_stand_on_obj,sidekick_animation_id,"
                + "sidekick_mapping_frame\n";
        String rowTail = ",0000,0000,0000,0000,0000,0000,0000,1,0040,0420,0000,"
                + "0000,0000,00,0,0,0,0000,0000,02,00,00,00,00,0,0000,0000,"
                + "0000,0000,0000,00,0,0,0,0000,0000,00,00,00,00,00\n";
        StringBuilder csv = new StringBuilder(header);
        for (int frame = 0; frame < frameCount; frame++) {
            csv.append("%04x".formatted(frame)).append(rowTail);
        }
        return csv.toString();
    }
}
