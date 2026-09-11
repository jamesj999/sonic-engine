package com.openggf.tests.trace.runs;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.tests.trace.TraceV5RunFixture;
import com.openggf.trace.replay.runs.RunBoundarySignal;
import com.openggf.trace.replay.runs.RunLevelLoadCause;
import com.openggf.trace.replay.runs.RunPlaybackObservation;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.BoundaryEntryMode;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.BoundaryPairing;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.ReturnAssertionMode;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.SegmentExecutionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceRunReplayWalkerControlFlow {

    // A high cap so the frozen-cursor guard never trips in tests that exercise
    // the window-edge / peek / persistent-mode paths instead.
    private static final int NO_CAP = 100_000;

    @Test
    void dynamicArtSegmentsTranslateStructuralSegmentAndGapBoundaries() {
        RecordingSegmentWindow window = new RecordingSegmentWindow();
        var controller =
                new TraceRunReplayWalker.DynamicArtSegmentController(window);

        controller.beginSegment();
        assertEquals(List.of("open"), window.transitions);

        controller.enterGap();
        assertEquals(List.of("open", "close"), window.transitions);

        controller.beginSegment();
        controller.endSegment();
        assertEquals(List.of("open", "close", "open", "close"),
                window.transitions);

        controller.beginSegment();
        controller.close();
        assertEquals(List.of("open", "close", "open", "close", "open", "close"),
                window.transitions,
                "terminal controller close must balance the final segment");
    }

    @Test
    void dynamicArtSegmentBodyClosesAfterExceptionalExit() {
        RecordingSegmentWindow window = new RecordingSegmentWindow();
        var controller =
                new TraceRunReplayWalker.DynamicArtSegmentController(window);
        RuntimeException failure = new RuntimeException("segment failed");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> controller.runSegment(() -> {
                    assertEquals(List.of("open"), window.transitions);
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(List.of("open", "close"), window.transitions,
                "exceptional segment exit must still close the comparison window");
    }

    @Test
    void advertisedRunSpecialStageRowsUseProductionCoordinatorSnapshots() {
        DynamicArtLifecycleService lifecycle =
                new DynamicArtLifecycleService();
        lifecycle.beginRun();
        lifecycle.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        (PlcLifecycleService) null, lifecycle);
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 2),
                List.of(),
                Map.of(
                        0, List.of(new TraceEvent.DynamicArtTransferState(
                                0, List.of(), List.of())),
                        1, List.of(new TraceEvent.DynamicArtTransferState(
                                1,
                                List.of(new DynamicArtTransfer.SegmentEdge(
                                        0, 0, "submitted", "ss-sonic",
                                        "segment", 3, 1, 0, 1, true,
                                        0x33ADA,
                                        List.of(new DynamicArtTransfer.Request(
                                                -1, -1, 0xFF0020,
                                                0x5CA0, 0x20)))),
                                List.of(0L)))));
        var comparisons =
                new TraceRunReplayWalker.DynamicArtSegmentComparison(
                        trace, 2);

        coordinator.runLogicalIteration(() -> {
        }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });
        comparisons.compareRow(0, lifecycle.latestSnapshot());

        coordinator.runLogicalIteration(() -> {
        }, row -> {
            row.claim(PlcLifecyclePhase.LAG);
            lifecycle.observeRamDplc(
                    "ss-sonic", 3, List.of(new TileLoadRequest(1, 1)),
                    0xFF0000, 0x5CA0);
            row.prepareAfterLoop(PlcLifecyclePhase.LAG);
            return null;
        });
        lifecycle.closeComparisonSegment();
        comparisons.compareRow(1, lifecycle.latestSnapshot());
        comparisons.verifyComplete();

        assertEquals(2, comparisons.comparisons().size());
        assertTrue(comparisons.comparisons().stream()
                .noneMatch(FrameComparison::hasDivergence));
        assertEquals("true", comparisons.comparisons().get(1).fields()
                .get("dynamic_art.edge[0].terminal_forwarded").actual());
    }

    @Test
    void advertisedRunSpecialStageCannotSilentlyOmitARow() {
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 2),
                List.of(),
                Map.of(
                        0, List.of(new TraceEvent.DynamicArtTransferState(
                                0, List.of(), List.of())),
                        1, List.of(new TraceEvent.DynamicArtTransferState(
                                1, List.of(), List.of()))));
        var comparisons =
                new TraceRunReplayWalker.DynamicArtSegmentComparison(
                        trace, 2);
        comparisons.compareRow(
                0, new com.openggf.game.resources.DynamicArtDiagnosticsSnapshot(
                        0, List.of(), List.of()));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, comparisons::verifyComplete);

        assertTrue(error.getMessage().contains("expected 2 rows"),
                error.getMessage());
    }

    @Test
    void plansGeneratedV5SegmentsWithExplicitTransitionPairing(@TempDir Path root)
            throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        TraceRunManifest run = TraceRunManifest.load(runDir.resolve("run_manifest.json"));
        var descriptors = TraceRunReplayWalker.planDescriptors(run, runDir);
        assertEquals(3, descriptors.size());
        assertNull(descriptors.get(0).entryBoundary());
        assertEquals("starpost_bonus", descriptors.get(0).exitBoundary().entryKind());
        assertEquals("starpost_bonus", descriptors.get(1).entryBoundary().entryKind());
        assertEquals("stage_exit", descriptors.get(1).exitBoundary().entryKind());
        assertEquals("stage_exit", descriptors.get(2).entryBoundary().entryKind());
        assertEquals(SegmentExecutionPolicy.GAMEPLAY,
                descriptors.get(2).executionPolicy(),
                "a generated S3K stage-exit destination with an advancing "
                        + "gameplay clock remains ordinary gameplay");
        assertNull(descriptors.get(2).exitBoundary());
    }

    @Test
    void metadataOnlySpecialStagePlanRejectsNonContiguousStoredRows(
            @TempDir Path runDir) throws Exception {
        Path segment = runDir.resolve("ss");
        Files.createDirectories(segment);
        Files.writeString(segment.resolve("metadata.json"), """
                {"game":"s2","zone":"special_stage","act":1,
                 "bk2_frame_offset":0,"trace_frame_count":2,
                 "trace_schema":5,"trace_profile":"s2_special_stage",
                 "start_x":"0000","start_y":"0000"}
                """);
        Files.writeString(segment.resolve("physics.csv"),
                "frame,anything\n0,a\n2,c\n");
        Files.writeString(runDir.resolve("run_manifest.json"), """
                {"trace_schema":5,"game":"s2","run_id":"gap",
                 "source_bk2":"gap.bk2","rom_checksum":"x",
                 "segments":[{"dir":"ss","kind":"special_stage",
                   "trace_profile":"s2_special_stage","bk2_frame_offset":0,
                   "trace_frame_count":2,"special_stage_index":1}],
                 "transitions":[],"dynamic_art_gap_transitions":[]}
                """);

        TraceRunManifest manifest = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));
        IOException error = assertThrows(
                IOException.class,
                () -> TraceRunReplayWalker.planDescriptors(manifest, runDir));
        assertTrue(error.getMessage().contains("contiguous"), error.getMessage());
    }

    /**
     * Manifest-driven iteration with NO hardcoded count: the committed 25-segment
     * S3K run pairs transitions to segments by explicit from/to indices, leaving
     * null on the plain level->level boundaries that carry no transition record
     * (AIZ->HCZ seg 8->9, HCZ->MGZ seg 18->19). Pure -- no ROM, no trace load.
     */
    @Test
    void pairBoundariesHandlesGeneratedV5GapsAndTransitions() {
        List<TraceRunManifest.Segment> segments = List.of(
                segmentAt("aiz", "level", 0),
                segmentAt("gumball", "bonus_stage", 100),
                segmentAt("aiz_return", "level", 200),
                segmentAt("ss", "special_stage", 300));
        TraceRunManifest run = new TraceRunManifest(
                "s3k", "generated", "movie.bk2", "checksum", segments,
                List.of(new TraceRunManifest.Transition(0, 1, "starpost_bonus", 50,
                                null, null, null, null, null, null, null, null),
                        new TraceRunManifest.Transition(2, 3, "giant_ring", 250,
                                null, null, null, null, null, null, null, null)));

        BoundaryPairing pairing = TraceRunReplayWalker.pairBoundaries(run);
        assertEquals(4, pairing.entryBoundaries().length);
        assertEquals(4, pairing.exitBoundaries().length);

        // Segment 0 is the run start: no entry boundary; exits via starpost_bonus.
        assertNull(pairing.entryBoundaries()[0]);
        assertEquals("starpost_bonus", pairing.exitBoundaries()[0].entryKind());

        // Interior 1 (gumball) has the explicit starpost bonus entry.
        assertEquals("starpost_bonus", pairing.entryBoundaries()[1].entryKind());

        // Plain level->level gaps carry NO transition record -> null both sides.
        assertNull(pairing.exitBoundaries()[1], "gumball->AIZ is a plain boundary");
        assertNull(pairing.entryBoundaries()[2], "AIZ has no entry transition record");

        // A giant_ring special-stage entry is paired by explicit index.
        assertEquals("giant_ring", pairing.exitBoundaries()[2].entryKind());
        assertEquals("giant_ring", pairing.entryBoundaries()[3].entryKind());

        // Last segment ends the run: no exit boundary.
        assertNull(pairing.exitBoundaries()[3]);
    }

    @Test
    void boundaryEntryModeMapsEveryEntryKind() {
        assertEquals(BoundaryEntryMode.BONUS_REQUEST,
            TraceRunReplayWalker.boundaryEntryMode("starpost_bonus"));
        assertEquals(BoundaryEntryMode.SPECIAL_STAGE_REQUEST,
            TraceRunReplayWalker.boundaryEntryMode("giant_ring"));
        // S2 starpost_special maps to the special-stage request signal (new shape).
        assertEquals(BoundaryEntryMode.SPECIAL_STAGE_REQUEST,
            TraceRunReplayWalker.boundaryEntryMode("starpost_special"));
        assertEquals(BoundaryEntryMode.LEVEL_MODE,
            TraceRunReplayWalker.boundaryEntryMode("stage_exit"));
        assertEquals(BoundaryEntryMode.LEVEL_LOAD,
            TraceRunReplayWalker.boundaryEntryMode("level_advance"));
        assertEquals(BoundaryEntryMode.LEVEL_LOAD,
            TraceRunReplayWalker.boundaryEntryMode("death_restart"));
        assertThrows(IllegalArgumentException.class,
            () -> TraceRunReplayWalker.boundaryEntryMode("nonsense"));
    }

    @Test
    void returnAssertionModeIsDataDriven() {
        assertEquals(ReturnAssertionMode.POSITIONAL_RESTORE,
            TraceRunReplayWalker.returnAssertionMode(entryTransition("starpost_special", 3568)));
        assertEquals(ReturnAssertionMode.CHECKPOINT_RESTORE,
            TraceRunReplayWalker.returnAssertionMode(entryTransition("starpost_bonus", 10104)));
        // giant_ring split on saved_x_pos presence: S1 (none) -> NEXT_ACT;
        // S3K SS (present) -> RINGS_EMERALDS_ONLY. Manifest data, not a game name.
        assertEquals(ReturnAssertionMode.NEXT_ACT,
            TraceRunReplayWalker.returnAssertionMode(entryTransition("giant_ring", null)));
        assertEquals(ReturnAssertionMode.RINGS_EMERALDS_ONLY,
            TraceRunReplayWalker.returnAssertionMode(entryTransition("giant_ring", 2528)));
        // A stage_exit is not an interior-entry transition.
        assertThrows(IllegalArgumentException.class,
            () -> TraceRunReplayWalker.returnAssertionMode(entryTransition("stage_exit", null)));
    }

    @Test
    void expectedModeAndUncomparedInteriorAreKindDriven() {
        assertEquals(GameMode.LEVEL, TraceRunReplayWalker.expectedMode(segment("level")));
        assertEquals(GameMode.BONUS_STAGE, TraceRunReplayWalker.expectedMode(segment("bonus_stage")));
        assertEquals(GameMode.SPECIAL_STAGE, TraceRunReplayWalker.expectedMode(segment("special_stage")));

        // SS-interior policy v1: only special_stage is advance-uncompared.
        assertTrue(TraceRunReplayWalker.isUncomparedInterior(segment("special_stage")));
        assertFalse(TraceRunReplayWalker.isUncomparedInterior(segment("bonus_stage")));
        assertFalse(TraceRunReplayWalker.isUncomparedInterior(segment("level")));
    }

    @Test
    void segmentExecutionPolicyRecognizesStageExitPresentationBridgeByRowShape() {
        TraceRunManifest.Segment destination = segmentAt("return", "level", 1200);
        TraceRunManifest.Transition stageExit = boundaryOfKind("stage_exit", 1100);
        TraceData bridge = executionTrace(100, 100, 100);

        assertEquals(SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE,
                TraceRunReplayWalker.segmentExecutionPolicy(
                        destination, stageExit, bridge));
        assertEquals(com.openggf.trace.TraceExecutionPhase.FULL_LEVEL_FRAME,
                com.openggf.trace.replay.TraceReplayRowPolicy.resolve(
                        bridge, 0, destination.bk2FrameOffset()).phase(),
                "row zero is synthetic FULL only because it has no predecessor");
        assertEquals(com.openggf.trace.TraceExecutionPhase.VBLANK_ONLY,
                com.openggf.trace.replay.TraceReplayRowPolicy.resolve(
                        bridge, 1, destination.bk2FrameOffset() + 1).phase());
    }

    @Test
    void segmentExecutionPolicyKeepsGameplayStageExitDestinationsAndSpecialLocalsDistinct() {
        TraceRunManifest.Transition stageExit = boundaryOfKind("stage_exit", 1100);

        assertEquals(SegmentExecutionPolicy.GAMEPLAY,
                TraceRunReplayWalker.segmentExecutionPolicy(
                        segmentAt("return", "level", 1200), stageExit,
                        executionTrace(100, 101, 102)));
        assertEquals(SegmentExecutionPolicy.GAMEPLAY,
                TraceRunReplayWalker.segmentExecutionPolicy(
                        segmentAt("ordinary", "level", 1200), null,
                        executionTrace(100, 100, 100)));
        assertEquals(SegmentExecutionPolicy.SPECIAL_LOCAL,
                TraceRunReplayWalker.segmentExecutionPolicy(
                        segmentAt("ss", "special_stage", 800), null,
                        TraceFixtures.trace(
                                TraceFixtures.metadata("s1", 0, 1), List.of())));
    }

    @Test
    void activeLevelSegmentConvertsManifestActAndRejectsOtherPhases() {
        var level = segmentAt("mz1", "level", 27467);
        assertTrue(TraceRunReplayWalker.isActiveLevelSegment(level, 0, 0));
        assertFalse(TraceRunReplayWalker.isActiveLevelSegment(level, 0, 1));
        assertFalse(TraceRunReplayWalker.isActiveLevelSegment(
                segmentAt("ss", "special_stage", 0), 0, 0));
    }

    @Test
    void newActiveLevelRequiresLifecycleChangeEvenForSameZoneAndAct() {
        var level = segmentAt("mz1_restart", "level", 31086);
        Object beforeDeath = new Object();
        assertFalse(TraceRunReplayWalker.isNewActiveLevelSegment(
                level, 0, 0, beforeDeath, beforeDeath));
        assertTrue(TraceRunReplayWalker.isNewActiveLevelSegment(
                level, 0, 0, beforeDeath, new Object()));
    }

    @Test
    void allLagSameLevelContinuationRebindsWithoutAnotherModeCycle() {
        var first = segmentAt("ghz2", "level", 8705);
        var continuation = segmentAt("ghz2_2", "level", 9741);
        assertTrue(TraceRunReplayWalker.isLagOnlySameLevelContinuation(
                first, continuation, 800, 799));
        assertFalse(TraceRunReplayWalker.isLagOnlySameLevelContinuation(
                first, continuation, 800, 798));
        var otherAct = new TraceRunManifest.Segment(
                "ghz3", "level", "profile", 18719, 10, 0, 2, null, null);
        assertFalse(TraceRunReplayWalker.isLagOnlySameLevelContinuation(
                first, otherAct, 800, 799));
    }

    @Test
    void interSegmentStepCapIsMaxGapPlusWindow() {
        TraceRunManifest run = new TraceRunManifest(
            "s3k", "syn", "syn.bk2", "cs",
            List.of(segmentAt("a", "level", 0),
                    segmentAt("b", "bonus_stage", 100),
                    segmentAt("c", "level", 1000)),
            List.of());
        // gaps: 100, 900 -> max 900; + BOUNDARY_WINDOW_FRAMES (600) = 1500.
        assertEquals(900 + TraceRunReplayWalker.BOUNDARY_WINDOW_FRAMES,
            TraceRunReplayWalker.interSegmentStepCap(run));
    }

    @Test
    void boundaryWindowSemantics() {
        assertTrue(TraceRunReplayWalker.withinBoundaryWindow(1500, 1750));
        assertTrue(TraceRunReplayWalker.withinBoundaryWindow(1750, 1750));
        assertTrue(TraceRunReplayWalker.withinBoundaryWindow(
            1750 + TraceRunReplayWalker.LATE_BOUNDARY_GRACE_FRAMES, 1750));
        assertFalse(TraceRunReplayWalker.withinBoundaryWindow(
            1750 + TraceRunReplayWalker.LATE_BOUNDARY_GRACE_FRAMES + 1, 1750));
        assertFalse(TraceRunReplayWalker.withinBoundaryWindow(
            1750 - TraceRunReplayWalker.BOUNDARY_WINDOW_FRAMES - 1, 1750)); // before the window
    }

    @Test
    void awaitBoundaryObservesTransientPeekDuringCallbackOnly() {
        var hooks = new StubHooks();               // simulates the real engine's transient peek:
        hooks.bonusRequestDuringFrame = 1700;      // peek non-null ONLY inside frame 1700's
                                                   // observer callback; null before AND after
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        hooks.installedProbe = probe;              // stub's step() invokes probe.afterFrameAdvanced
        var boundary = boundaryOfKind("starpost_bonus", 1750);
        var obs = TraceRunReplayWalker.awaitBoundary(probe, boundary, NO_CAP, hooks::step);
        assertTrue(obs.observed());
        assertEquals(1700, obs.observedBk2Frame());
        // Post-step polling would have missed it — assert the stub really is transient:
        assertNull(hooks.peekBonusRequest());
    }

    @Test
    void awaitBoundaryObservesSpecialStageRequestRaisedAfterFrameCallback() {
        var hooks = new StubHooks();
        hooks.specialRequestAfterCallbackFrame = 1700;
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        hooks.installedProbe = probe;

        var obs = TraceRunReplayWalker.awaitBoundary(
            probe, boundaryOfKind("giant_ring", 1750), NO_CAP, hooks::step);

        assertTrue(obs.observed());
        assertEquals(1700, obs.observedBk2Frame());
        assertFalse(hooks.isSpecialStageRequested(),
            "the durable event marker must work after the live request was consumed");
    }

    @Test
    void normalSpecialStagePeekKeepsPostAdvanceClockAnchor() {
        var hooks = new StubHooks();
        hooks.frame = 1699;
        hooks.specialRequestDuringFrame = 1700;
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        probe.arm(boundaryOfKind("giant_ring", 1750));

        probe.onSpecialStageRequestRaised();
        hooks.frame = 1700;
        hooks.inCallback = true;
        try {
            probe.afterFrameAdvanced(null, false);
        } finally {
            hooks.inCallback = false;
        }

        assertTrue(probe.latched());
        assertEquals(1700, probe.observation().observedBk2Frame(),
            "the ordinary frame callback must supersede the pre-advance fallback marker");
    }

    @Test
    void awaitBoundaryFailsClosedWhenWindowExhausted() {
        var hooks = new StubHooks();               // peek never fires
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        hooks.installedProbe = probe;
        var boundary = boundaryOfKind("starpost_bonus", 1750);
        var obs = TraceRunReplayWalker.awaitBoundary(probe, boundary, NO_CAP, hooks::step);
        assertFalse(obs.observed());
    }

    @Test
    void stageExitObservedByPersistentModePoll() {
        var hooks = new StubHooks();
        hooks.modeBecomesLevelAtFrame = 2850;      // persistent condition; NO observer callback
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        hooks.installedProbe = probe;
        var obs = TraceRunReplayWalker.awaitBoundary(
            probe, boundaryOfKind("stage_exit", 2800 + 600), NO_CAP, hooks::step);
        assertTrue(obs.observed());
        assertEquals(2850, obs.observedBk2Frame());  // not vacuous at window start
    }

    @Test
    void probeLatchesSemanticLevelAdvanceAndDeathRestartSignals() {
        var hooks = new StubHooks();
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        RunPlaybackObservation.LevelIdentity destination =
                new RunPlaybackObservation.LevelIdentity(2, 1, 1, 0);

        probe.arm(boundaryOfKind("level_advance", 500));
        probe.observeSignal(new RunBoundarySignal.LevelLoaded(
                500, RunLevelLoadCause.LEVEL_ADVANCE, destination));
        assertTrue(probe.latched());
        assertEquals(500, probe.observation().observedBk2Frame());

        probe.arm(boundaryOfKind("death_restart", 700));
        probe.observeSignal(new RunBoundarySignal.LevelLoaded(
                700, RunLevelLoadCause.DEATH_RESTART, destination));
        assertTrue(probe.latched());
        assertEquals(700, probe.observation().observedBk2Frame());
    }

    @Test
    void probeRejectsWrongOrLateSemanticLoadSignal() {
        var hooks = new StubHooks();
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        RunPlaybackObservation.LevelIdentity destination =
                new RunPlaybackObservation.LevelIdentity(2, 1, 1, 0);
        probe.arm(boundaryOfKind("death_restart", 700));

        probe.observeSignal(new RunBoundarySignal.LevelLoaded(
                700, RunLevelLoadCause.LEVEL_ADVANCE, destination));
        assertFalse(probe.latched());
        probe.observeSignal(new RunBoundarySignal.LevelLoaded(
                700 + TraceRunReplayWalker.LATE_BOUNDARY_GRACE_FRAMES + 1,
                RunLevelLoadCause.DEATH_RESTART, destination));
        assertFalse(probe.latched());
    }

    /**
     * Step-cap firing: a FROZEN cursor (never advances) with a mode that never
     * settles must throw a diagnostic {@code BoundaryStepCapExceededException}
     * instead of hanging. The peek never fires and the cursor never passes the
     * edge, so ONLY the step cap can end the loop.
     */
    @Test
    void awaitBoundaryThrowsWhenStepCapExceededOnFrozenCursor() {
        var hooks = new StubHooks();
        hooks.freezeCursor = true;                 // step() never advances the frame
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        hooks.installedProbe = probe;
        var boundary = boundaryOfKind("starpost_bonus", 1750);
        var thrown = assertThrows(
            TraceRunReplayWalker.BoundaryStepCapExceededException.class,
            () -> TraceRunReplayWalker.awaitBoundary(probe, boundary, 50, hooks::step));
        assertTrue(thrown.getMessage().contains("step cap 50"),
            "diagnostic names the cap: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("starpost_bonus"),
            "diagnostic names the entry_kind: " + thrown.getMessage());
    }

    @Test
    void probeDelegatesSkipGateToAttachedComparator() {
        var hooks = new StubHooks();
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        Bk2FrameInput detachedRow = new Bk2FrameInput(0, 0, 0, false, "detached");
        assertFalse(probe.shouldSkipGameplayTick(detachedRow),
            "detached probe must not skip gameplay ticks");
        probe.afterFrameAdvanced(detachedRow, false);
        probe.setDelegate(new AlwaysSkipDelegate());   // tiny inline stub delegate
        assertTrue(probe.shouldSkipGameplayTick(
                        new Bk2FrameInput(1, 0, 0, false, "attached")),
            "attached delegate's lag gating must flow through the probe");
    }

    @Test
    void probeForwardsPurePreparationAndAppliedOffsetOnlyWhileAttached() {
        var hooks = new StubHooks();
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        var delegate = new PreparedOffsetDelegate();
        Bk2FrameInput frame = new Bk2FrameInput(
                23, 0, 0, false, "row");
        probe.setDelegate(delegate);

        probe.prepareFrame(frame);
        assertEquals(-1, probe.appliedInputOffset(frame));
        assertEquals(1, delegate.prepareCalls);
        assertEquals(1, delegate.offsetCalls);

        probe.afterFrameAdvanced(frame, false);
        probe.setDelegate(null);
        Bk2FrameInput gapFrame = new Bk2FrameInput(
                24, 0, 0, false, "gap");
        probe.prepareFrame(gapFrame);
        assertEquals(0, probe.appliedInputOffset(gapFrame),
                "a structural run gap must retain ordinary current-row input");
        assertEquals(1, delegate.prepareCalls);
        assertEquals(1, delegate.offsetCalls);
    }

    @Test
    void probePinsPreparedRowToItsOriginalDelegateAcrossHandoff() {
        var hooks = new StubHooks();
        var probe = new TraceRunReplayWalker.BoundaryProbe(hooks);
        var source = new RecordingDelegate(false);
        var destination = new RecordingDelegate(true);
        Bk2FrameInput sourceRow = new Bk2FrameInput(10, 0, 0, false, "source");
        Bk2FrameInput destinationRow = new Bk2FrameInput(20, 0, 0, false, "destination");

        probe.setDelegate(source);
        probe.prepareFrame(sourceRow);
        probe.setDelegate(destination);
        assertFalse(probe.shouldSkipGameplayTick(sourceRow),
                "the prepared source row must retain source lag policy");
        probe.afterFrameAdvanced(sourceRow, false);
        assertEquals(List.of(10), source.publishedFrames);
        assertTrue(destination.publishedFrames.isEmpty(),
                "a mid-row handoff must not redirect source publication");

        probe.prepareFrame(destinationRow);
        assertTrue(probe.shouldSkipGameplayTick(destinationRow));
        probe.afterFrameAdvanced(destinationRow, true);
        assertEquals(List.of(20), destination.publishedFrames);
    }

    @Test
    void remainingSegmentFramesSubtractsAlreadyConsumedFallthroughRows() {
        assertEquals(799, TraceRunReplayWalker.remainingSegmentFrames(800, 1));
        assertEquals(800, TraceRunReplayWalker.remainingSegmentFrames(800, 0));
        assertEquals(0, TraceRunReplayWalker.remainingSegmentFrames(800, 800));
    }

    @Test
    void unspecifiedMovieEndModeSkipsTerminalTailReplayAndAssertion() {
        var plan = TraceRunReplayWalker.planTerminalMovieTail(
                TraceRunManifest.ExpectedMovieEndMode.UNSPECIFIED, 120, 100);

        assertFalse(plan.shouldReplay());
        assertFalse(plan.shouldAssertExpectedMode());
    }

    @Test
    void declaredMovieEndModesReplayRemainingRowsAndAssertTheirMode() {
        var level = TraceRunReplayWalker.planTerminalMovieTail(
                TraceRunManifest.ExpectedMovieEndMode.LEVEL, 8, 11);
        var titleScreen = TraceRunReplayWalker.planTerminalMovieTail(
                TraceRunManifest.ExpectedMovieEndMode.TITLE_SCREEN, 8, 11);

        assertTrue(level.shouldReplay());
        assertTrue(level.shouldAssertExpectedMode());
        assertEquals(3, level.rowsToReplay());
        assertEquals(GameMode.LEVEL, level.expectedMode());
        assertTrue(titleScreen.shouldReplay());
        assertTrue(titleScreen.shouldAssertExpectedMode());
        assertEquals(3, titleScreen.rowsToReplay());
        assertEquals(GameMode.TITLE_SCREEN, titleScreen.expectedMode());
    }

    @Test
    void declaredMovieEndModeRejectsTailPastMovieWithDiagnostic() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> TraceRunReplayWalker.planTerminalMovieTail(
                        TraceRunManifest.ExpectedMovieEndMode.LEVEL, 12, 11));

        assertTrue(thrown.getMessage().contains("tail start 12"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("movie frame count 11"), thrown.getMessage());
    }

    @Test
    void declaredMovieEndModeAtMovieBoundaryStillAssertsWithoutReplayingRows() {
        var plan = TraceRunReplayWalker.planTerminalMovieTail(
                TraceRunManifest.ExpectedMovieEndMode.TITLE_SCREEN, 11, 11);

        assertFalse(plan.shouldReplay());
        assertTrue(plan.shouldAssertExpectedMode());
        assertEquals(0, plan.rowsToReplay());
        assertEquals(GameMode.TITLE_SCREEN, plan.expectedMode());
    }

    @Test
    void interLevelVblankBudgetUsesMovieGapAndProfiledNonAdvancingRows() {
        var ghz2 = new TraceRunManifest.Segment(
                "ghz2", "level", "profile", 8705, 800, 0, 1, null, null);
        var continuation = new TraceRunManifest.Segment(
                "ghz2_2", "level", "profile", 9741, 7440, 0, 1, null, null);
        assertEquals(230, TraceRunReplayWalker.interLevelVblankBudget(
                ghz2, continuation, 0, 6));

        var ghz3Tail = new TraceRunManifest.Segment(
                "ghz3_2", "level", "profile", 18719, 8520, 0, 2, null, null);
        var mz1 = new TraceRunManifest.Segment(
                "mz1", "level", "profile", 27467, 3391, 2, 0, null, null);
        assertEquals(223, TraceRunReplayWalker.interLevelVblankBudget(
                ghz3Tail, mz1, 1, 6));

        var mz2Death = new TraceRunManifest.Segment(
                "mz2", "level", "profile", 42308, 542, 2, 1, null, null);
        var mz2Restart = new TraceRunManifest.Segment(
                "mz2_2", "level", "profile", 43078, 3728, 2, 1, null, null);
        assertEquals(222, TraceRunReplayWalker.interLevelVblankBudget(
                mz2Death, mz2Restart, 0, 6));
    }

    @Test
    void interLevelVblankBudgetRejectsOverlappingMovieRanges() {
        var current = new TraceRunManifest.Segment(
                "a", "level", "profile", 100, 50, 0, 0, null, null);
        var next = new TraceRunManifest.Segment(
                "b", "level", "profile", 140, 50, 0, 1, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> TraceRunReplayWalker.interLevelVblankBudget(current, next, 0, 0));
    }

    @Test
    void uncomparedInteriorReturnVblankBudgetCountsEveryMovieRowSinceLevelTail() {
        var mz1Tail = new TraceRunManifest.Segment(
                "mz1_2", "level", "profile", 31086, 8684, 2, 0, null, null);
        var mz2 = new TraceRunManifest.Segment(
                "mz2", "level", "profile", 42308, 542, 2, 1, null, null);

        assertEquals(2539, TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                mz1Tail, mz2, 1, 0));
        // The profile-owned masked-row term is subtracted, as it is on the
        // inter-level budget: Sonic 2's level+special-stage entry composition.
        assertEquals(2528, TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                mz1Tail, mz2, 1, 11));
    }

    @Test
    void uncomparedInteriorReturnVblankBudgetRunsToTheLastConsumedDestinationRow() {
        var mz1Tail = new TraceRunManifest.Segment(
                "mz1_2", "level", "profile", 31086, 8684, 2, 0, null, null);
        var mz2 = new TraceRunManifest.Segment(
                "mz2", "level", "profile", 42308, 542, 2, 1, null, null);

        // One consumed row is the shape every committed uncompared return has,
        // and is what the budget previously hardcoded -- so this value is the
        // pre-existing one and the term changes nothing for those runs.
        assertEquals(2539, TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                mz1Tail, mz2, 1, 0));
        // Each further consumed destination row carries its own tick, exactly as
        // nextFramesConsumed does on interLevelVblankBudget.
        assertEquals(2540, TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                mz1Tail, mz2, 2, 0));
        assertEquals(2542, TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                mz1Tail, mz2, 4, 0));
        // A return that consumed no destination row anchors on the row BEFORE
        // the destination's frame 0 -- the value entering the first row its
        // comparator will compare. That is the same value, from the same
        // expression, that the inter-level budget gives at a consumed count of
        // zero, which is what every level_advance admission passes.
        assertEquals(2538, TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                mz1Tail, mz2, 0, 0));
        assertEquals(2538, TraceRunReplayWalker.interLevelVblankBudget(
                mz1Tail, mz2, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                        mz1Tail, mz2, -1, 0));
    }

    @Test
    void sourceTailVblankIsProjectedFromObservedMovieCursor() {
        var source = new TraceRunManifest.Segment(
                "mz1_2", "level", "profile", 31086, 8684, 2, 0, null, null);

        assertEquals(0x99F9, TraceRunReplayWalker.sourceTailVblankAtBoundary(
                source, 39773, 0x99FC));
        assertEquals(0x99F9, TraceRunReplayWalker.sourceTailVblankAtBoundary(
                source, 39769, 0x99F8));
    }


    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static TraceRunManifest.Transition boundaryOfKind(String entryKind, int modeChangeBk2Frame) {
        return new TraceRunManifest.Transition(
            0, 0, entryKind, modeChangeBk2Frame,
            null, null, null, null, null, null, null, null);
    }

    private static TraceRunManifest.Transition entryTransition(String entryKind, Integer savedXPos) {
        return new TraceRunManifest.Transition(
            0, 1, entryKind, 0,
            null, savedXPos, savedXPos == null ? null : 100, null, null, null, null, null);
    }

    private static TraceRunManifest.Segment segment(String kind) {
        return segmentAt("dir", kind, 0);
    }

    private static TraceRunManifest.Segment segmentAt(String dir, String kind, int bk2FrameOffset) {
        return new TraceRunManifest.Segment(
            dir, kind, "profile", bk2FrameOffset, 10, 0, 1, null, null);
    }

    private static TraceData executionTrace(int... gameplayCounters) {
        List<TraceFrame> frames = new java.util.ArrayList<>();
        for (int index = 0; index < gameplayCounters.length; index++) {
            frames.add(TraceFrame.executionTestFrame(
                    index, 0x300 + index, gameplayCounters[index], 0));
        }
        return TraceFixtures.trace(
                TraceFixtures.metadata("s1", 0, 1), frames);
    }

    /**
     * Models the real coordinator's TRANSIENT peek semantics: the bonus stage
     * request is visible ONLY during the observer callback of the frame that
     * raises it, plus the persistent LEVEL mode-poll used for stage_exit
     * boundaries, plus an optional FROZEN cursor (step() never advances) for the
     * step-cap firing test.
     */
    private static final class StubHooks implements TraceRunReplayWalker.EngineHooks {
        int frame;
        int bonusRequestDuringFrame = -1;
        int specialRequestDuringFrame = -1;
        int specialRequestAfterCallbackFrame = -1;
        int modeBecomesLevelAtFrame = -1;
        boolean freezeCursor;
        boolean inCallback;
        TraceRunReplayWalker.BoundaryProbe installedProbe;

        void step() {
            if (!freezeCursor) {
                frame++;
            }
            inCallback = true;
            try {
                installedProbe.afterFrameAdvanced(null, false);
            } finally {
                inCallback = false;
            }
            if (frame == specialRequestAfterCallbackFrame) {
                installedProbe.onSpecialStageRequestRaised();
            }
        }

        @Override
        public int currentBk2Frame() {
            return frame;
        }

        @Override
        public BonusStageType peekBonusRequest() {
            return inCallback && frame == bonusRequestDuringFrame ? BonusStageType.GUMBALL : null;
        }

        @Override
        public boolean isSpecialStageRequested() {
            return inCallback && frame == specialRequestDuringFrame;
        }

        @Override
        public GameMode currentMode() {
            if (modeBecomesLevelAtFrame >= 0 && frame < modeBecomesLevelAtFrame) {
                return GameMode.BONUS_STAGE;
            }
            return GameMode.LEVEL;
        }
    }

    private static final class AlwaysSkipDelegate implements PlaybackDebugManager.PlaybackFrameObserver {
        @Override
        public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
            return true;
        }

        @Override
        public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
            // Unused by this test.
        }
    }

    private static final class PreparedOffsetDelegate
            implements PlaybackDebugManager.PlaybackFrameObserver {
        private int prepareCalls;
        private int offsetCalls;

        @Override
        public void prepareFrame(Bk2FrameInput frame) {
            prepareCalls++;
        }

        @Override
        public int appliedInputOffset(Bk2FrameInput frame) {
            offsetCalls++;
            return -1;
        }

        @Override
        public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
            return false;
        }

        @Override
        public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
        }
    }

    private static final class RecordingDelegate
            implements PlaybackDebugManager.PlaybackFrameObserver {
        private final boolean skip;
        private final List<Integer> publishedFrames = new java.util.ArrayList<>();

        private RecordingDelegate(boolean skip) {
            this.skip = skip;
        }

        @Override
        public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
            return skip;
        }

        @Override
        public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
            publishedFrames.add(frame.frameIndex());
        }
    }

    private static final class RecordingSegmentWindow
            implements TraceRunReplayWalker.DynamicArtSegmentWindow {
        private final List<String> transitions = new java.util.ArrayList<>();

        @Override
        public void open() {
            transitions.add("open");
        }

        @Override
        public void close() {
            transitions.add("close");
        }
    }
}
