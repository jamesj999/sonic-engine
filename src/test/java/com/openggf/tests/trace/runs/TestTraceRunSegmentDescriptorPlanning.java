package com.openggf.tests.trace.runs;

import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunSegmentDescriptorPlanning {

    @Test
    void descriptorPlanMatchesIndependentlyLoadedSummaries(@TempDir Path root)
            throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        Path firstMetadata = runDir.resolve("seg00_aiz/metadata.json");
        Files.writeString(firstMetadata, Files.readString(firstMetadata).replace(
                "\"dynamic_art_transfer_state_per_frame\"",
                "\"dynamic_art_transfer_state_per_frame\",\"lag_state_per_frame\""));
        Path firstAux = runDir.resolve("seg00_aiz/aux_state.jsonl");
        Files.writeString(firstAux, Files.readString(firstAux)
                + "{\"frame\":0,\"event\":\"lag_state\",\"lagged\":false,\"lagcount\":0}\n"
                + "{\"frame\":1,\"event\":\"lag_state\",\"lagged\":true,\"lagcount\":1}\n");
        Files.writeString(runDir.resolve("seg01_gumball/hardware_timing.jsonl"), """
                {"event":"hardware_work_completed","raw_frame":1,"boundary":"pre_main_loop","kind":"kos_decompression_queue","ordinal":0,"submission_fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));

        var descriptors = TraceRunReplayWalker.planDescriptors(run, runDir);

        assertEquals(3, descriptors.size());
        for (int index = 0; index < descriptors.size(); index++) {
            TraceRunManifest.Segment segment = run.segments().get(index);
            TraceData expected = TraceData.load(
                    runDir.resolve(segment.dir()),
                    segment.dynamicArtInitialLedgerDescriptors());
            var descriptor = descriptors.get(index);
            assertEquals(segment, descriptor.segment());
            assertEquals(runDir.resolve(segment.dir()),
                    descriptor.segmentDirectory());
            assertEquals(expected.metadata(), descriptor.metadata());
            assertEquals(segment.traceProfile(),
                    descriptor.metadata().traceProfile());
            assertEquals(2, descriptor.rowCount());
            assertEquals(List.of(0, 1), descriptor.rawFrames());
            assertEquals(expected.getFrame(0), descriptor.openingFrame());
            assertEquals(expected.terminalDynamicArtLedger(),
                    descriptor.terminalDynamicArtLedger());
            assertEquals(expected.hardwareTimingSchedule().hasRecordedInput(),
                    descriptor.hardwareTimingSchedule().hasRecordedInput());
            assertEquals(expected.hardwareTimingSchedule().edges(),
                    descriptor.hardwareTimingSchedule().edges());
            var pairing = TraceRunReplayWalker.pairBoundaries(run);
            assertEquals(pairing.entryBoundaries()[index],
                    descriptor.entryBoundary());
            assertEquals(pairing.exitBoundaries()[index],
                    descriptor.exitBoundary());
            assertEquals(TraceRunReplayWalker.segmentExecutionPolicy(
                            segment, pairing.entryBoundaries()[index], expected),
                    descriptor.executionPolicy());
            assertEquals(TraceRunReplayWalker.levelLoopRowCount(expected),
                    descriptor.levelLoopRowCount());
        }
        assertFalse(descriptors.getFirst().laggedRows().get(0));
        assertTrue(descriptors.getFirst().laggedRows().get(1));
        assertTrue(descriptors.get(1).hardwareTimingSchedule().hasRecordedInput());
        assertEquals(1,
                descriptors.get(1).hardwareTimingSchedule().edges().size());
    }

    @Test
    void descriptorPlanContainsNoEagerPayloadOwner(@TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));

        var descriptors = TraceRunReplayWalker.planDescriptors(run, runDir);

        assertEquals(run.segments().size(), descriptors.size());
        assertTrue(Arrays.stream(TraceRunSegmentDescriptor.class.getRecordComponents())
                .noneMatch(component -> component.getType() == TraceData.class));
        assertTrue(descriptors.stream().noneMatch(descriptor ->
                Arrays.stream(descriptor.getClass().getDeclaredFields())
                        .anyMatch(field -> TraceData.class.isAssignableFrom(
                                field.getType()))));
    }

    @Test
    void descriptorDefensivelyCopiesCompactMutableState(@TempDir Path root)
            throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));
        TraceRunSegmentDescriptor planned =
                TraceRunReplayWalker.planDescriptors(run, runDir).getFirst();
        List<Integer> rawFrames = new ArrayList<>(planned.rawFrames());
        BitSet laggedRows = new BitSet();
        laggedRows.set(1);
        Path segmentDirectory = Path.of(planned.segmentDirectory().toString());

        TraceRunSegmentDescriptor copied = new TraceRunSegmentDescriptor(
                planned.segment(), segmentDirectory, planned.metadata(),
                planned.rowCount(), planned.openingFrame(), rawFrames,
                laggedRows, planned.hardwareTimingSchedule(),
                planned.terminalDynamicArtLedger(), planned.entryBoundary(),
                planned.exitBoundary(), planned.levelLoopRowCount(),
                planned.executionPolicy());
        rawFrames.clear();
        laggedRows.clear();

        assertEquals(List.of(0, 1), copied.rawFrames());
        assertTrue(copied.laggedRows().get(1));
        BitSet accessorResult = copied.laggedRows();
        accessorResult.clear(1);
        assertTrue(copied.laggedRows().get(1));
        assertThrows(UnsupportedOperationException.class,
                () -> copied.rawFrames().add(2));
        assertThrows(UnsupportedOperationException.class,
                () -> copied.terminalDynamicArtLedger().add(null));
        assertNotSame(segmentDirectory, copied.segmentDirectory());
    }

    @Test
    void descriptorGraphDefensivelyCopiesNestedMetadataAndBoundaryLists(
            @TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));
        TraceRunSegmentDescriptor planned =
                TraceRunReplayWalker.planDescriptors(run, runDir).getFirst();
        List<String> auxSchemaExtras = new ArrayList<>(List.of("aux-one"));
        List<String> characters = new ArrayList<>(List.of("sonic", "tails"));
        List<String> sidekicks = new ArrayList<>(List.of("tails"));
        TraceMetadata metadata = copyMetadataWithLists(
                planned.metadata(), auxSchemaExtras, characters, sidekicks);
        List<Integer> gapAdmissionRuns = new ArrayList<>(List.of(3, 1));
        TraceRunManifest.Transition basis = planned.exitBoundary();
        TraceRunManifest.Transition exitBoundary = new TraceRunManifest.Transition(
                basis.fromSegment(), basis.toSegment(), basis.entryKind(),
                basis.modeChangeBk2Frame(), basis.specialBonusEntryFlag(),
                basis.savedXPos(), basis.savedYPos(), basis.lastStarPostHit(),
                basis.ringsBefore(), basis.ringsAfter(), basis.emeraldsBefore(),
                basis.emeraldsAfter(), gapAdmissionRuns);

        TraceRunSegmentDescriptor copied = new TraceRunSegmentDescriptor(
                planned.segment(), planned.segmentDirectory(), metadata,
                planned.rowCount(), planned.openingFrame(), planned.rawFrames(),
                planned.laggedRows(), planned.hardwareTimingSchedule(),
                planned.terminalDynamicArtLedger(), planned.entryBoundary(),
                exitBoundary, planned.levelLoopRowCount(),
                planned.executionPolicy());
        auxSchemaExtras.add("mutated");
        characters.clear();
        sidekicks.add("knuckles");
        gapAdmissionRuns.add(7);

        assertEquals(List.of("aux-one"), copied.metadata().auxSchemaExtras());
        assertEquals(List.of("sonic", "tails"), copied.metadata().characters());
        assertEquals(List.of("tails"), copied.metadata().sidekicks());
        assertEquals(List.of(3, 1),
                copied.exitBoundary().gapAdmissionRuns());
        assertThrows(UnsupportedOperationException.class,
                () -> copied.metadata().auxSchemaExtras().add("mutated"));
        assertThrows(UnsupportedOperationException.class,
                () -> copied.metadata().characters().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> copied.metadata().sidekicks().add("knuckles"));
        assertThrows(UnsupportedOperationException.class,
                () -> copied.exitBoundary().gapAdmissionRuns().add(7));
    }

    @Test
    void descriptorRejectsInconsistentRowShape(@TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));
        TraceRunSegmentDescriptor descriptor =
                TraceRunReplayWalker.planDescriptors(run, runDir).getFirst();

        assertThrows(IllegalArgumentException.class,
                () -> copyWithRowShape(descriptor, -1, List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> copyWithRowShape(descriptor, 2, List.of(0),
                        descriptor.openingFrame()));
        assertThrows(IllegalArgumentException.class,
                () -> copyWithRowShape(descriptor, 2, List.of(0, 1), null));
    }

    @Test
    void descriptorRejectsLevelLoopRowsOutsideItsRowRangeForEveryPolicy() {
        List<TraceRunSegmentDescriptor> descriptors = List.of(
                syntheticDescriptor("level", 2, 2,
                        TraceRunReplayWalker.SegmentExecutionPolicy.GAMEPLAY),
                syntheticDescriptor("level", 2, 1,
                        TraceRunReplayWalker.SegmentExecutionPolicy
                                .LEVEL_PRESENTATION_BRIDGE),
                syntheticDescriptor("special_stage", 2, 0,
                        TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL));

        for (TraceRunSegmentDescriptor descriptor : descriptors) {
            assertThrows(IllegalArgumentException.class,
                    () -> copyWithLevelLoopRows(descriptor, -1),
                    descriptor.executionPolicy().toString());
            assertThrows(IllegalArgumentException.class,
                    () -> copyWithLevelLoopRows(descriptor,
                            descriptor.rowCount() + 1),
                    descriptor.executionPolicy().toString());
        }
    }

    @Test
    void specialStageDescriptorUsesMetadataOnlyOpeningAndCompactRowMappings(
            @TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS2SpecialStageRun(root);
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));

        TraceRunSegmentDescriptor special =
                TraceRunReplayWalker.planDescriptors(run, runDir).get(1);

        assertEquals("s2_special_stage", special.metadata().traceProfile());
        assertEquals(2, special.rowCount());
        assertEquals(List.of(0, 1), special.rawFrames());
        assertTrue(special.laggedRows().isEmpty());
        assertNull(special.openingFrame());
        assertEquals(TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL,
                special.executionPolicy());
        assertEquals(0, special.levelLoopRowCount());
        TraceRunManifest.Segment ordinary = run.segments().getFirst();
        TraceFrame ordinaryOpening = TraceData.load(
                runDir.resolve(ordinary.dir()),
                ordinary.dynamicArtInitialLedgerDescriptors()).getFrame(0);
        assertThrows(IllegalArgumentException.class,
                () -> copyWithRowShape(special, 2, List.of(0, 1),
                        ordinaryOpening));
    }

    @Test
    void descriptorPlannerWrapsMalformedSegmentWithIndexAndProfile(
            @TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        Files.delete(runDir.resolve("seg01_gumball/physics.csv"));
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));

        IOException error = assertThrows(IOException.class,
                () -> TraceRunReplayWalker.planDescriptors(run, runDir));

        assertTrue(error.getMessage().startsWith(
                "Segment 1 parser failed for profile 's3k_bonus_stage':"),
                error.getMessage());
    }

    @Test
    void descriptorPlannerRejectsManifestRowCountMismatchAtNamedSegment(
            @TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        replaceManifest(runDir,
                "\"trace_profile\":\"s3k_bonus_stage\",\"bk2_frame_offset\":1900,\"trace_frame_count\":2",
                "\"trace_profile\":\"s3k_bonus_stage\",\"bk2_frame_offset\":1900,\"trace_frame_count\":3");
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TraceRunReplayWalker.planDescriptors(run, runDir));

        assertEquals("Segment 1 row count mismatch: manifest=3, parsed=2",
                error.getMessage());
    }

    @Test
    void descriptorPlannerRejectsManifestProfileMismatchAtNamedSegment(
            @TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        replaceManifest(runDir,
                "\"trace_profile\":\"s3k_bonus_stage\",\"bk2_frame_offset\":1900",
                "\"trace_profile\":\"complete_run\",\"bk2_frame_offset\":1900");
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TraceRunReplayWalker.planDescriptors(run, runDir));

        assertEquals("Segment 1 profile mismatch: manifest='complete_run', "
                        + "metadata='s3k_bonus_stage'",
                error.getMessage());
    }

    @Test
    void descriptorPlannerWrapsNonContiguousSpecialStageRowsWithProfile(
            @TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS2SpecialStageRun(root);
        Path physics = runDir.resolve("ss/physics.csv");
        List<String> rows = new ArrayList<>(Files.readAllLines(physics));
        rows.set(2, rows.get(2).replaceFirst("^1", "2"));
        Files.write(physics, rows);
        TraceRunManifest run = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));

        IOException error = assertThrows(IOException.class,
                () -> TraceRunReplayWalker.planDescriptors(run, runDir));

        assertTrue(error.getMessage().startsWith(
                "Segment 1 parser failed for profile 's2_special_stage':"),
                error.getMessage());
        assertTrue(error.getMessage().contains("contiguous"), error.getMessage());
    }

    private static TraceRunSegmentDescriptor copyWithRowShape(
            TraceRunSegmentDescriptor descriptor,
            int rowCount,
            List<Integer> rawFrames,
            TraceFrame openingFrame) {
        return new TraceRunSegmentDescriptor(
                descriptor.segment(), descriptor.segmentDirectory(),
                descriptor.metadata(), rowCount, openingFrame, rawFrames,
                descriptor.laggedRows(), descriptor.hardwareTimingSchedule(),
                descriptor.terminalDynamicArtLedger(), descriptor.entryBoundary(),
                descriptor.exitBoundary(), descriptor.levelLoopRowCount(),
                descriptor.executionPolicy());
    }

    private static TraceRunSegmentDescriptor copyWithLevelLoopRows(
            TraceRunSegmentDescriptor descriptor, int levelLoopRowCount) {
        return new TraceRunSegmentDescriptor(
                descriptor.segment(), descriptor.segmentDirectory(),
                descriptor.metadata(), descriptor.rowCount(),
                descriptor.openingFrame(), descriptor.rawFrames(),
                descriptor.laggedRows(), descriptor.hardwareTimingSchedule(),
                descriptor.terminalDynamicArtLedger(), descriptor.entryBoundary(),
                descriptor.exitBoundary(), levelLoopRowCount,
                descriptor.executionPolicy());
    }

    private static TraceRunSegmentDescriptor syntheticDescriptor(
            String kind,
            int rowCount,
            int levelLoopRowCount,
            TraceRunReplayWalker.SegmentExecutionPolicy executionPolicy) {
        TraceRunManifest.Segment segment = new TraceRunManifest.Segment(
                executionPolicy.name().toLowerCase(), kind, "synthetic", 0,
                rowCount, 0, 1, null, null);
        List<Integer> rawFrames = new ArrayList<>(rowCount);
        for (int row = 0; row < rowCount; row++) {
            rawFrames.add(row);
        }
        return new TraceRunSegmentDescriptor(
                segment, Path.of(segment.dir()), TraceFixtures.metadata("s1", 0, 1),
                rowCount, "special_stage".equals(kind) ? null
                        : TraceFrame.executionTestFrame(0, 0x300, 0, 0),
                rawFrames, new BitSet(),
                com.openggf.trace.timing.HardwareTimingSchedule.empty(), List.of(),
                null, null, levelLoopRowCount, executionPolicy);
    }

    private static TraceMetadata copyMetadataWithLists(
            TraceMetadata base,
            List<String> auxSchemaExtras,
            List<String> characters,
            List<String> sidekicks) {
        return new TraceMetadata(
                base.game(), base.zone(), base.zoneId(), base.act(),
                base.bk2FrameOffset(), base.ringFloorCheckCounterPhase(),
                base.traceFrameCount(), base.startXHex(), base.startYHex(),
                base.recordingDate(), base.recorder(), base.recorderVersion(),
                base.traceSchema(), base.traceProfile(), base.bizhawkVersion(),
                base.genesisCore(), auxSchemaExtras, base.romZoneId(),
                base.route(), base.sourceBk2(), base.romChecksum(), base.notes(),
                characters, base.mainCharacter(), sidekicks,
                base.preTraceOscFrames(), base.rngSeedHex(), base.traceType(),
                base.inputSource(), base.creditsDemoIndex(),
                base.creditsDemoSlug(), base.specialStageIndex(), base.runId(),
                base.segmentIndex(), base.bonusStageType(), base.freshLoad(),
                base.vIntRunCount());
    }

    private static void replaceManifest(
            Path runDir, String target, String replacement) throws IOException {
        Path manifest = runDir.resolve("run_manifest.json");
        String json = Files.readString(manifest);
        assertTrue(json.contains(target), "fixture manifest did not contain target");
        Files.writeString(manifest, json.replace(target, replacement));
    }
}
