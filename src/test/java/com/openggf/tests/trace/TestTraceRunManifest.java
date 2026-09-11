package com.openggf.tests.trace;

import com.openggf.trace.TraceRunManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceRunManifest {

    private static final String EMPTY_LEDGER_HASH =
            "sha256:42f87419ea3765ece5e0a63ffa9f9ebe5e60d91c115090adf9133c0bd0aca3c9";
    private static final String GAP_DESCRIPTOR_FINGERPRINT =
            "sha256:ff64bf72b7cc56fbd3c31656213363d447e149e3f4cc6c3f35f4f6a6a294cd63";

    private static final String VALID_MANIFEST = """
        {
          "trace_schema": 5,
          "game": "s3k",
          "run_id": "s3k-aiz-gumball-roundtrip",
          "source_bk2": "s3k-aiz-gumball.bk2",
          "rom_checksum": "C5B1C655C19F462ADE0AC4E17A844D10",
          "recorder": "native-bizhawk-headless",
          "recorder_version": "3.0",
          "segments": [
            {"dir": "seg00_aiz", "kind": "level", "trace_profile": "complete_run",
             "bk2_frame_offset": 500, "trace_frame_count": 1200, "zone_id": 0, "act": 1},
            {"dir": "seg01_gumball", "kind": "bonus_stage", "trace_profile": "s3k_bonus_stage",
             "bk2_frame_offset": 1900, "trace_frame_count": 800, "zone_id": 19,
             "bonus_stage_type": "gumball"},
            {"dir": "seg02_aiz", "kind": "level", "trace_profile": "complete_run",
             "bk2_frame_offset": 2900, "trace_frame_count": 600, "zone_id": 0, "act": 1}
          ],
          "transitions": [
            {"from_segment": 0, "to_segment": 1, "entry_kind": "starpost_bonus",
             "mode_change_bk2_frame": 1750, "special_bonus_entry_flag": 2,
             "saved_x_pos": 4660, "saved_y_pos": 1024, "last_star_post_hit": 1,
             "rings_before": 25, "emeralds_before": 0},
            {"from_segment": 1, "to_segment": 2, "entry_kind": "stage_exit",
             "mode_change_bk2_frame": 2800, "rings_after": 40, "emeralds_after": 0}
          ],
          "dynamic_art_gap_transitions": []
        }
        """;

    private Path writeRun(Path dir, String manifestJson, String... segmentDirs)
            throws IOException {
        for (String seg : segmentDirs) {
            Path segDir = dir.resolve(seg);
            Files.createDirectories(segDir);
            Files.writeString(segDir.resolve("metadata.json"), "{}");
        }
        Path manifest = dir.resolve("run_manifest.json");
        Files.writeString(manifest, manifestJson);
        return manifest;
    }

    @Test
    void loadsAndValidatesWellFormedManifest(@TempDir Path dir) throws IOException {
        Path manifest = writeRun(dir, VALID_MANIFEST, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        run.validate(dir);
        assertEquals(3, run.segments().size());
        assertEquals("bonus_stage", run.segments().get(1).kind());
        assertEquals("gumball", run.segments().get(1).bonusStageType());
        assertEquals(2, run.transitions().size());
        assertEquals("starpost_bonus", run.transitions().get(0).entryKind());
        assertEquals(2, run.transitions().get(0).specialBonusEntryFlag());
    }

    @Test
    void defaultsMissingExpectedMovieEndModeToUnspecified(@TempDir Path dir) throws IOException {
        TraceRunManifest run = TraceRunManifest.load(
                writeRun(dir, VALID_MANIFEST, "seg00_aiz", "seg01_gumball", "seg02_aiz"));

        assertEquals(TraceRunManifest.ExpectedMovieEndMode.UNSPECIFIED,
                run.expectedMovieEndMode());
    }

    @Test
    void parsesDeclaredExpectedMovieEndModes(@TempDir Path dir) throws IOException {
        String level = VALID_MANIFEST.replace("\"segments\": [",
                "\"expected_movie_end_mode\": \"level\",\n  \"segments\": [");
        String titleScreen = VALID_MANIFEST.replace("\"segments\": [",
                "\"expected_movie_end_mode\": \"title_screen\",\n  \"segments\": [");

        assertEquals(TraceRunManifest.ExpectedMovieEndMode.LEVEL,
                TraceRunManifest.load(writeRun(
                        dir.resolve("level"), level,
                        "seg00_aiz", "seg01_gumball", "seg02_aiz")).expectedMovieEndMode());
        assertEquals(TraceRunManifest.ExpectedMovieEndMode.TITLE_SCREEN,
                TraceRunManifest.load(writeRun(
                        dir.resolve("title-screen"), titleScreen,
                        "seg00_aiz", "seg01_gumball", "seg02_aiz")).expectedMovieEndMode());
    }

    @Test
    void rejectsUnknownAndNonStringExpectedMovieEndModes(@TempDir Path dir) {
        String unknown = VALID_MANIFEST.replace("\"segments\": [",
                "\"expected_movie_end_mode\": \"credits\",\n  \"segments\": [");
        String nonString = VALID_MANIFEST.replace("\"segments\": [",
                "\"expected_movie_end_mode\": 12,\n  \"segments\": [");

        assertThrows(IOException.class, () -> TraceRunManifest.load(writeRun(
                dir.resolve("unknown"), unknown,
                "seg00_aiz", "seg01_gumball", "seg02_aiz")));
        assertThrows(IOException.class, () -> TraceRunManifest.load(writeRun(
                dir.resolve("non-string"), nonString,
                "seg00_aiz", "seg01_gumball", "seg02_aiz")));
    }

    @Test
    void rejectsUnknownSegmentKind(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"kind\": \"bonus_stage\"", "\"kind\": \"casino\"");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("casino"), ex.getMessage());
    }

    @Test
    void rejectsNonMonotonicBk2Offsets(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"bk2_frame_offset\": 2900", "\"bk2_frame_offset\": 100");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("bk2_frame_offset"), ex.getMessage());
    }

    @Test
    void rejectsDuplicateSegmentDirectories(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"dir\": \"seg02_aiz\"", "\"dir\": \"seg00_aiz\"");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("duplicate segment directory"), ex.getMessage());
        assertTrue(ex.getMessage().contains("seg00_aiz"), ex.getMessage());
    }

    @Test
    void rejectsMissingSegmentDir(@TempDir Path dir) throws IOException {
        Path manifest = writeRun(dir, VALID_MANIFEST, "seg00_aiz", "seg02_aiz"); // seg01 missing
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("seg01_gumball"), ex.getMessage());
    }

    @Test
    void rejectsBonusSegmentWithoutType(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"bonus_stage_type\": \"gumball\"", "\"notes\": \"x\"");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("bonus_stage_type"), ex.getMessage());
    }

    @Test
    void rejectsTransitionWithBadIndices(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"from_segment\": 1, \"to_segment\": 2",
                                            "\"from_segment\": 1, \"to_segment\": 5");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("to_segment"), ex.getMessage());
    }

    @Test
    void requiresCurrentTraceSchemaAndExplicitGapArray(@TempDir Path dir) {
        for (String invalid : java.util.List.of(
                VALID_MANIFEST.replace("\"trace_schema\": 5,\n", ""),
                VALID_MANIFEST.replace("\"dynamic_art_gap_transitions\": []", ""),
                VALID_MANIFEST.replace("\"dynamic_art_gap_transitions\": []",
                        "\"dynamic_art_gap_transitions\": {}"))) {
            assertThrows(IOException.class, () -> TraceRunManifest.load(writeRun(
                    dir.resolve("invalid-" + invalid.hashCode()), invalid,
                    "seg00_aiz", "seg01_gumball", "seg02_aiz")));
        }
    }

    @Test
    void rejectsDuplicateSchemaFieldsAndTrailingJson(@TempDir Path dir) {
        String duplicateSchema = VALID_MANIFEST.replace(
                "\"trace_schema\": 5,",
                "\"trace_schema\": 5,\n  \"trace_schema\": 5,");
        String trailingJson = VALID_MANIFEST + "\n{}";

        assertThrows(IOException.class, () -> TraceRunManifest.load(writeRun(
                dir.resolve("duplicate"), duplicateSchema,
                "seg00_aiz", "seg01_gumball", "seg02_aiz")));
        assertThrows(IOException.class, () -> TraceRunManifest.load(writeRun(
                dir.resolve("trailing"), trailingJson,
                "seg00_aiz", "seg01_gumball", "seg02_aiz")));
    }

    @Test
    void validatesCompleteOrderedGapLifecycleAndHashes(@TempDir Path dir)
            throws IOException {
        String descriptor = descriptorJson();
        String submission = gapTransitionJson(
                gapEdgeJson(3, 9, "submitted", "run_gap", 18, 0, 5290),
                EMPTY_LEDGER_HASH, "[" + descriptor + "]");
        String pendingHash = com.openggf.trace.DynamicArtTransfer.ledgerHash(
                java.util.List.of(com.openggf.trace.DynamicArtTransfer.parseDescriptor(
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(descriptor))));
        String completion = gapTransitionJson(
                gapEdgeJson(4, 9, "completed", "run_gap", 19, 0, 5292),
                pendingHash, "[]");
        String schemaTwo = VALID_MANIFEST
                .replace("\"game\": \"s3k\"", "\"game\": \"s2\"")
                .replace("\"dynamic_art_gap_transitions\": []",
                        "\"dynamic_art_gap_transitions\": ["
                        + submission + "," + completion + "]");

        TraceRunManifest manifest = TraceRunManifest.load(writeRun(
                dir, schemaTwo, "seg00_aiz", "seg01_gumball", "seg02_aiz"));
        manifest.validate(dir);
        manifest.validateDynamicArtGaps(java.util.List.of(), true);

        assertEquals(GAP_DESCRIPTOR_FINGERPRINT,
                manifest.dynamicArtGapTransitions().getFirst()
                        .afterLedgerDescriptors().getFirst().fingerprint());
    }

    @Test
    void rejectsBadGapOrderHashDescriptorAndNonemptyPostGap(@TempDir Path dir)
            throws IOException {
        String descriptor = descriptorJson();
        String submission = gapTransitionJson(
                gapEdgeJson(3, 9, "submitted", "run_gap", 18, 0, 5290),
                EMPTY_LEDGER_HASH, "[" + descriptor + "]");
        String schemaTwo = VALID_MANIFEST
                .replace("\"game\": \"s3k\"", "\"game\": \"s2\"")
                .replace("\"dynamic_art_gap_transitions\": []",
                        "\"dynamic_art_gap_transitions\": ["
                        + submission + "]");
        TraceRunManifest pending = TraceRunManifest.load(writeRun(
                dir.resolve("pending"), schemaTwo,
                "seg00_aiz", "seg01_gumball", "seg02_aiz"));
        assertThrows(IllegalStateException.class,
                () -> pending.validateDynamicArtGaps(java.util.List.of(), true));

        String badHash = schemaTwo.replace(EMPTY_LEDGER_HASH,
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        TraceRunManifest hash = TraceRunManifest.load(writeRun(
                dir.resolve("hash"), badHash,
                "seg00_aiz", "seg01_gumball", "seg02_aiz"));
        assertThrows(IllegalStateException.class,
                () -> hash.validateDynamicArtGaps(java.util.List.of(), false));

        String badDescriptor = schemaTwo.replace(GAP_DESCRIPTOR_FINGERPRINT,
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        assertThrows(IOException.class, () -> TraceRunManifest.load(writeRun(
                dir.resolve("descriptor"), badDescriptor,
                "seg00_aiz", "seg01_gumball", "seg02_aiz")));

        String duplicated = schemaTwo.replace("[" + submission + "]",
                "[" + submission + "," + submission + "]");
        TraceRunManifest order = TraceRunManifest.load(writeRun(
                dir.resolve("order"), duplicated,
                "seg00_aiz", "seg01_gumball", "seg02_aiz"));
        assertThrows(IllegalStateException.class,
                () -> order.validateDynamicArtGaps(java.util.List.of(), false));
    }

    @Test
    void rejectsOverlappingSegmentRangesAndDuplicateTransitionAdjacency(
            @TempDir Path dir) throws IOException {
        String overlap = VALID_MANIFEST.replace(
                "\"bk2_frame_offset\": 1900", "\"bk2_frame_offset\": 1600");
        TraceRunManifest overlapping = TraceRunManifest.load(writeRun(
                dir.resolve("overlap"), overlap,
                "seg00_aiz", "seg01_gumball", "seg02_aiz"));
        assertThrows(IllegalStateException.class,
                () -> overlapping.validate(dir.resolve("overlap")));

        String duplicate = VALID_MANIFEST.replace(
                "{\"from_segment\": 1, \"to_segment\": 2",
                "{\"from_segment\": 0, \"to_segment\": 1");
        TraceRunManifest duplicateAdjacency = TraceRunManifest.load(writeRun(
                dir.resolve("duplicate"), duplicate,
                "seg00_aiz", "seg01_gumball", "seg02_aiz"));
        assertThrows(IllegalStateException.class,
                () -> duplicateAdjacency.validate(dir.resolve("duplicate")));
    }

    private static String descriptorJson() {
        return "{\"transfer_id\":9,\"owner\":\"tails\",\"mapping_frame\":4,"
                + "\"submission_origin\":\"run_gap\",\"requests\":[{"
                + "\"rom_source_address\":410400,\"source_tile_index\":1,"
                + "\"ram_source_address\":-1,\"vram_destination\":62464,"
                + "\"byte_length\":32}],\"fingerprint\":\""
                + GAP_DESCRIPTOR_FINGERPRINT + "\"}";
    }

    private static String gapEdgeJson(long ordinal, long transferId, String phase,
            String origin, int movieFrame, int edgeIndex, int callbackPc) {
        return "{\"edge_ordinal\":" + ordinal + ",\"transfer_id\":" + transferId
                + ",\"phase\":\"" + phase + "\",\"owner\":\"tails\","
                + "\"submission_origin\":\"" + origin + "\",\"mapping_frame\":4,"
                + "\"movie_logical_frame\":" + movieFrame + ",\"gap_edge_index\":"
                + edgeIndex + ",\"rom_callback_pc\":" + callbackPc
                + ",\"requests\":[{\"rom_source_address\":410400,"
                + "\"source_tile_index\":1,\"ram_source_address\":-1,"
                + "\"vram_destination\":62464,\"byte_length\":32}]}";
    }

    private static String gapTransitionJson(
            String edge, String beforeHash, String afterDescriptors) {
        return "{\"dynamic_art_gap_edge\":" + edge
                + ",\"before_ledger_hash\":\"" + beforeHash
                + "\",\"after_ledger_descriptors\":" + afterDescriptors + "}";
    }
}
