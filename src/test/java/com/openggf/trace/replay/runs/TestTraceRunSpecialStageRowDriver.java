package com.openggf.trace.replay.runs;

import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceRunManifest;
import com.openggf.tests.TestTempFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunSpecialStageRowDriver {

    @Test
    void commitsAdvertisedRowOnlyAfterAtomicPublication(@TempDir Path dir)
            throws Exception {
        Fixture fixture = fixture(dir, 2);
        TraceRunSpecialStageRowDriver driver = new TraceRunSpecialStageRowDriver(
                fixture.rows(), fixture.trace());
        DynamicArtDiagnosticsSnapshot before =
                DynamicArtDiagnosticsSnapshot.unpublished(7, 3);

        var admitted = driver.admitCurrentRow(before);

        assertEquals(0, admitted.row());
        assertEquals(0, driver.cursor());
        assertTrue(driver.hasPendingRow());
        assertThrows(IllegalStateException.class,
                () -> driver.admitCurrentRow(before));

        var result = driver.publishAdmittedRow(published(0, 8, 3));

        assertTrue(result.isPresent());
        assertFalse(result.orElseThrow().hasDivergence());
        assertEquals(1, driver.cursor());
        assertFalse(driver.hasPendingRow());
    }

    @Test
    void rejectsMissingOrWrongPublicationEvidence(@TempDir Path dir)
            throws Exception {
        Fixture fixture = fixture(dir, 1);
        TraceRunSpecialStageRowDriver driver = new TraceRunSpecialStageRowDriver(
                fixture.rows(), fixture.trace());
        driver.admitCurrentRow(DynamicArtDiagnosticsSnapshot.unpublished(7, 3));

        assertThrows(IllegalStateException.class,
                () -> driver.publishAdmittedRow(
                        DynamicArtDiagnosticsSnapshot.unpublished(8, 3)));
        assertThrows(IllegalStateException.class,
                () -> driver.publishAdmittedRow(published(0, 7, 3)));
        assertThrows(IllegalStateException.class,
                () -> driver.publishAdmittedRow(published(0, 8, 4)));
        assertThrows(IllegalStateException.class,
                () -> driver.publishAdmittedRow(published(1, 8, 3)));
        assertEquals(0, driver.cursor());
        assertTrue(driver.hasPendingRow());
    }

    @Test
    void rejectsEarlyCloseAndSpecialLocalRowSkip(@TempDir Path dir)
            throws Exception {
        Fixture fixture = fixture(dir, 1);
        TraceRunSpecialStageRowDriver driver = new TraceRunSpecialStageRowDriver(
                fixture.rows(), fixture.trace());

        assertThrows(IllegalStateException.class, driver::verifyComplete);
        assertThrows(IllegalArgumentException.class,
                () -> TraceRunSpecialStageRowDriver.requireFreshAdmission(1));

        driver.admitCurrentRow(DynamicArtDiagnosticsSnapshot.unpublished(2, 9));
        driver.publishAdmittedRow(published(0, 3, 9));
        driver.verifyComplete();
        assertTrue(driver.isComplete());
        assertThrows(IllegalStateException.class,
                () -> driver.admitCurrentRow(
                        DynamicArtDiagnosticsSnapshot.unpublished(3, 9)));
    }

    @Test
    void commitsAll3728AdvertisedRowsFromEmeraldSpecialStage() throws Exception {
        Path runDir = TestTempFiles.createTempDirectory("trace-v5-emerald-run");
        fixture(runDir.resolve("ss"), 3728);
        Files.writeString(runDir.resolve("run_manifest.json"), """
                {
                  "trace_schema": 5,
                  "game": "s1",
                  "run_id": "synthetic-emerald",
                  "source_bk2": "synthetic.bk2",
                  "rom_checksum": "AFE05EEE",
                  "segments": [
                    {"dir":"ss","kind":"special_stage",
                     "trace_profile":"s1_special_stage",
                     "bk2_frame_offset":0,"trace_frame_count":3728,
                     "zone_id":0,"act":0,"special_stage_index":0}
                  ],
                  "transitions": [],
                  "dynamic_art_gap_transitions": []
                }
                """);
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));
        TraceRunManifest.Segment segment = run.segments().getFirst();
        TraceRunSpecialStageRows rows = TraceRunSpecialStageRows.load(
                segment.traceProfile(), runDir.resolve(segment.dir()),
                segment.dynamicArtInitialLedgerDescriptors());
        TraceData trace = TraceData.loadMetadataOnly(
                runDir.resolve(segment.dir()),
                com.openggf.trace.StoredPhysicsFrameDomain.FrameEncoding.DECIMAL,
                segment.dynamicArtInitialLedgerDescriptors());
        TraceRunSpecialStageRowDriver driver = new TraceRunSpecialStageRowDriver(
                rows, trace);
        long generation = 23;
        long serial = 0;

        for (int row = 0; row < rows.rowCount(); row++) {
            driver.admitCurrentRow(
                    DynamicArtDiagnosticsSnapshot.unpublished(serial, generation));
            serial++;
            driver.publishAdmittedRow(published(
                    trace.dynamicArtTransferStateForFrame(row),
                    serial, generation));
        }

        driver.verifyComplete();
        assertEquals(3728, rows.rowCount());
        assertEquals(3728, driver.cursor());
        assertEquals(3728, driver.comparisons().size());
        assertTrue(driver.comparisons().stream()
                .noneMatch(com.openggf.trace.FrameComparison::hasDivergence));
    }

    private static DynamicArtDiagnosticsSnapshot published(
            int row, long serial, long generation) {
        return new DynamicArtDiagnosticsSnapshot(
                row, List.of(), List.of(), serial, generation, true);
    }

    private static DynamicArtDiagnosticsSnapshot published(
            TraceEvent.DynamicArtTransferState expected,
            long serial,
            long generation) {
        List<DynamicArtDiagnosticsSnapshot.Edge> edges = expected.edges().stream()
                .map(edge -> new DynamicArtDiagnosticsSnapshot.Edge(
                        edge.edgeOrdinal(), edge.transferId(), edge.phase(),
                        edge.owner(), edge.mappingFrame(), edge.logicalFrame(),
                        edge.logicalEdgeIndex(), edge.publicationFrame(),
                        edge.terminalForwarded(), edge.requests().stream()
                                .map(request -> new DynamicArtDiagnosticsSnapshot.Request(
                                        request.romSourceAddress(),
                                        request.sourceTileIndex(),
                                        request.ramSourceAddress(),
                                        request.vramDestination(),
                                        request.byteLength()))
                                .toList()))
                .toList();
        return new DynamicArtDiagnosticsSnapshot(
                expected.frame(), edges, expected.outstandingTransferIds(),
                serial, generation, true);
    }

    private static Fixture fixture(Path dir, int rowCount) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("metadata.json"), String.format("""
                {
                  "game": "s1",
                  "act": 0,
                  "bk2_frame_offset": 0,
                  "trace_frame_count": %d,
                  "trace_schema": 5,
                  "trace_profile": "s1_special_stage",
                  "start_x": "0000",
                  "start_y": "0000",
                  "aux_schema_extras": ["dynamic_art_transfer_state_per_frame"]
                }
                """, rowCount));
        StringBuilder physics = new StringBuilder(
                "frame,input,lag,x_pos,y_pos,vel_x,vel_y,inertia,status,ss_angle,ss_rotate,bg_anim,rings,emeralds\n");
        StringBuilder aux = new StringBuilder();
        for (int row = 0; row < rowCount; row++) {
            physics.append(row)
                    .append(",00,0,00000000,00000000,0000,0000,0000,00,00,00,00,00,00\n");
            aux.append(String.format(
                    "{\"frame\":%d,\"event\":\"dynamic_art_transfer_state\",\"edges\":[],\"outstanding_transfer_ids\":[]}\n",
                    row));
        }
        Files.writeString(dir.resolve("physics.csv"), physics);
        Files.writeString(dir.resolve("aux_state.jsonl"), aux);
        List<TraceFrame> frames = new java.util.ArrayList<>();
        Map<Integer, List<TraceEvent>> events = new LinkedHashMap<>();
        for (int row = 0; row < rowCount; row++) {
            frames.add(TraceFrame.executionTestFrame(row, row, row, 0));
            events.put(row, List.of(new TraceEvent.DynamicArtTransferState(
                    row, List.of(), List.of())));
        }
        TraceData comparisonTrace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s1", 0, 0, rowCount),
                frames, events);
        return new Fixture(
                TraceRunSpecialStageRows.load("s1_special_stage", dir),
                comparisonTrace);
    }

    private record Fixture(TraceRunSpecialStageRows rows, TraceData trace) {
    }
}
