package com.openggf.tests.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.trace.*;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;

import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceReplayStartPositionPolicy {

    @TempDir
    Path tempDir;

    private static final List<String> UNRETAINED_PER_FRAME_CAPABILITIES = List.of(
            "load_queue_state_per_frame",
            "dynamic_art_transfer_state_per_frame");

    private int policyTraceCopyIndex;

    @Test
    void s3kEndToEndTraceUsesLiveIntroSpawnInsteadOfRecordedFrameZeroPosition() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));
        TraceMetadata metadata = trace.metadata();

        AbstractTraceReplayTest subject = new AbstractTraceReplayTest() {
            @Override
            protected SonicGame game() {
                return SonicGame.SONIC_3K;
            }

            @Override
            protected int zone() {
                return 0;
            }

            @Override
            protected int act() {
                return 0;
            }

            @Override
            protected Path traceDirectory() {
                return Path.of("unused");
            }
        };

        Method method = AbstractTraceReplayTest.class.getDeclaredMethod(
                "shouldApplyMetadataStartPosition",
                TraceData.class,
                TraceMetadata.class);
        method.setAccessible(true);

        boolean shouldApply = (boolean) method.invoke(subject, trace, metadata);

        assertFalse(
                shouldApply,
                "The pre-level-prefix S3K AIZ full-run trace starts before LEVEL mode, so replay must "
                        + "keep the engine's live intro spawn instead of applying frame-zero "
                        + "start_x/start_y from stale Player_1 RAM.");
    }

    @Test
    void s3kEndToEndTraceStartsAtFrameZeroWithoutSkippingIntro() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));

        assertFalse(trace.metadata().hasPreLevelIntroPrefix(),
                "The regenerated fixture deliberately carries no legacy prefix capability.");
        assertTrue(TraceReplayBootstrap.hasRecordedPreLevelPrefix(trace),
                "The recorded non-LEVEL -> LEVEL zone-state transition must identify the prefix.");
        assertTrue(TraceReplayBootstrap.releaseBlockersForTraceReplay(trace).isEmpty(),
                "The regenerated AIZ full-run fixture must not be release-blocked by legacy heuristics.");
        assertFalse(TraceReplayBootstrap.shouldSeedFrameZeroForTraceReplay(trace));
        assertEquals(0, TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(trace));
        // Strict comparison starts on the first real AIZ level frame, where
        // the ROM has switched to Game_Mode 0x0C and spawned Obj_AIZPlaneIntro.
        assertEquals(289, TraceReplayBootstrap.strictStartTraceIndexForTraceReplay(trace),
                "AIZ frame-0 Player_1 RAM is still the title banner object; strict "
                        + "gameplay comparison starts when the ROM reaches Game_Mode 0x0C.");
        assertEquals(trace.metadata().bk2FrameOffset(),
                TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace),
                "Pre-level-prefix replay should trust the recorder's BK2 frame offset.");
        assertEquals(0,
                TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(trace, -1),
                "The AIZ prefix is simulated from frame 0, so no separate oscillator seed is required.");
        assertEquals(0,
                TraceReplayBootstrap.initialOscillationSuppressionFramesForTraceReplay(trace),
                "The AIZ intro prefix now drives the first LevelLoop tick natively, so "
                        + "Obj_FloatingPlatform must see the live OscillateNumDo phase without "
                        + "an extra replay-local suppression.");
        assertEquals(0,
                TraceReplayBootstrap.s3kCompleteRunAnimatedTilePreludeFramesForTraceReplay(trace),
                "Pre-level-prefix full-run traces simulate the intro natively and must not "
                        + "receive complete-run segment animation prelude.");
    }

    @Test
    void s3kEndToEndTracePreLevelPrefixAdvancesMovieWithoutTickingLevel() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceReplayBootstrap.phaseForReplay(trace, null, trace.getFrame(0)),
                "Pre-level prefix rows advance the movie without ticking the loaded level.");
    }

    @Test
    void vblankOnlyRowsAdvanceMovieButDoNotCompareGameplayState() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceReplayBootstrap.phaseForReplay(trace, trace.getFrame(287), trace.getFrame(288)),
                "Rows before the first LEVEL-mode frame stay VBlank-only.");
    }

    @Test
    void aizLevelTransitionStartsNativePrefixExecutionAfterItsBoundaryRow() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));
        TraceFrame beforeTransition = trace.getFrame(288);
        TraceFrame transition = trace.getFrame(289);
        TraceFrame firstDrivenPrefixRow = trace.getFrame(290);

        assertTrue(transition.lagCounter() > beforeTransition.lagCounter(),
                "The LEVEL transition row also carries direct lag-counter evidence.");
        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceReplayBootstrap.phaseForReplay(trace, beforeTransition, transition),
                "The zone_act_state transition into live LEVEL mode is still the setup boundary.");
        assertEquals(transition.gameplayFrameCounter() + 1,
                firstDrivenPrefixRow.gameplayFrameCounter(),
                "the regenerated fixture exposes the first native LevelLoop counter increment");
        assertEquals(transition.vblankCounter() + 1, firstDrivenPrefixRow.vblankCounter());
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(trace, transition, firstDrivenPrefixRow),
                "The first row after the LEVEL boundary must run native gameplay, which the "
                        + "regenerated fixture shows directly as the first counter increment.");
    }

    @Test
    void s3kEndingHistoryAdvanceOnACompletedIterationStaysAFullLevelFrame() throws Exception {
        TraceData trace = TraceData.load(Path.of("src/test/resources/traces/s3k/lbz_completerun"));
        TraceFrame previous = trace.getFrame(22067);
        TraceFrame current = trace.getFrame(22068);
        TraceEvent.CpuState before = trace.cpuStateForFrame(previous.frame(), "tails");
        TraceEvent.CpuState after = trace.cpuStateForFrame(current.frame(), "tails");

        assertEquals(6, after.cpuRoutine(),
                "The ending-pose CPU routine intentionally emits no tails_cpu_normal_step hook.");
        assertFalse(trace.getEventsForFrame(current.frame()).stream()
                .anyMatch(TraceEvent.TailsCpuNormalStep.class::isInstance));
        assertEquals(4, (after.posTableIndex() - before.posTableIndex()) & 0xFF,
                "Sonic_RecordPos still advances one history entry after the playable slots run.");

        // The history witness above is necessary but never sufficient to
        // downgrade a row: LevelLoop bumps Level_frame_counter in the
        // instruction after Wait_VSync returns (skdisasm/sonic3k.asm:7884-7889),
        // so a row pair that advances Level_frame_counter by one AND
        // V_int_run_count by one is, by construction, one completed main-loop
        // iteration that consumed exactly its own V-blank. This pair does
        // (gameplay_frame_counter $5627 -> $5628, vblank_counter $D4B9 ->
        // $D4BA), so it is a full level frame however the ending-pose CPU
        // routine animates inside it.
        //
        // This assertion previously expected VBLANK_ONLY /
        // PLAYABLE_ANIMATION_ONLY. That was written on 2026-07-23 against a
        // defective lbz_completerun capture whose gameplay_frame_counter column
        // was pinned at 0000 for every row and whose vblank_counter carried
        // only a stale high byte ($0E00 -> $0F00); the fixture regeneration in
        // 8a6313bb3 (2026-08-01) restored the real counters and with them the
        // ROM-correct classification. PLAYABLE_ANIMATION_ONLY stays gated on a
        // genuinely V-blank-only row -- see TestTraceReplayRowPolicy and
        // TestLiveTraceComparatorObserver for its own coverage.
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s3k").phaseFor(previous, current));
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current));
    }

    @Test
    void s3kMovingSidekickDuckToWalkCanHoldOnlyItsAnimateDispatch() throws Exception {
        TraceData trace = TraceData.load(Path.of("src/test/resources/traces/s3k/cnz_completerun"));
        TraceFrame previous = trace.getFrame(30485);
        TraceFrame current = trace.getFrame(30486);
        TraceEvent.CpuState before = trace.cpuStateForFrame(previous.frame(), "tails");
        TraceEvent.CpuState after = trace.cpuStateForFrame(current.frame(), "tails");

        assertFalse(trace.getEventsForFrame(current.frame()).stream()
                .anyMatch(TraceEvent.TailsCpuNormalStep.class::isInstance),
                "The native hooks-off fixture must use the structural history witness.");
        assertEquals(4, (after.posTableIndex() - before.posTableIndex()) & 0xFF,
                "Sonic_RecordPos advances after Tails movement and before Animate_Tails.");
        assertFalse(current.sidekick().physicsStateEquals(previous.sidekick()));
        assertEquals(previous.sidekick().animationId(), current.sidekick().animationId());
        assertEquals(previous.sidekick().mappingFrame(), current.sidekick().mappingFrame());
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current));
    }

    @Test
    void regeneratedPreLevelPrefixContainsNoSyntheticInputOnlyLatchRow() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));

        assertEquals(-1, firstInputOnlyStateRow(trace),
                "the regenerated fixture exposes direct execution counters and no longer "
                        + "contains the legacy input-only latch shape");
    }

    @Test
    void regeneratedPreLevelPrefixContainsNoPinnedStateAdvancingInputLatchRow() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));

        assertEquals(-1, firstStateAdvancingInputLatchRow(trace),
                "the regenerated fixture has no state-advancing input edge with all execution "
                        + "counters pinned");
        assertTrue(TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace),
                "pre-level-prefix replay still preserves its recording-input epoch contract");
    }

    @Test
    void unchangedStateInputEdgeAfterGameplayStartUsesNormalExecutionPhase() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));
        TraceFrame previous = trace.getFrame(1822);
        TraceFrame current = trace.getFrame(1823);

        assertFalse(previous.input() == current.input());
        assertTrue(current.stateEquals(previous));
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current),
                "After gameplay_start, an unchanged player row with a native counter increment "
                        + "still follows normal execution.");
    }

    @Test
    void traceReplayBootstrapNeverReportsTraceFrameAsActualPrimaryState() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/trace/TraceReplayBootstrap.java"));

        assertFalse(source.contains("fromTraceFrame"),
                "Trace rows are comparison input only; replay bootstrap must not expose a helper "
                        + "that can turn a TraceFrame into the actual engine primary state.");
        assertFalse(source.contains("trace-vblank"),
                "Legacy pre-level rows may be skipped for comparison, but must not be reported "
                        + "as actual player state.");
    }

    @Test
    void frameZeroSidekickAndObjectBootstrapCoverageIsDocumentedAsPartial() throws Exception {
        // The frame-0 projection moved out of the test base into production
        // code (AbstractTraceReplayTest.captureEngineSnapshot now delegates to
        // TraceReplayEngineSnapshot.capture), so the coverage-debt note lives
        // with the code that owns the gap. The empty per-slot SST map is the
        // structural half of the assertion, not just prose.
        String snapshot = Files.readString(Path.of(
                "src/main/java/com/openggf/trace/replay/TraceReplayEngineSnapshot.java"));
        String releaseIssues = Files.readString(Path.of("docs/architecture/audits/release-architecture-review-issues.md"));

        assertTrue(snapshot.contains("Sidekick CPU state is now")
                        && snapshot.contains("Per-slot SST snapshots")
                        && snapshot.contains("Map.of()"),
                "The frame-zero engine projection must keep captured sidekick CPU state and the "
                        + "empty per-slot SST view visible.");
        assertTrue(releaseIssues.contains("not a full sidekick/SST parity proof"),
                "Release notes must not claim warning-only bootstrap gaps prove strict parity.");
    }

    @Test
    void s3kGameplayTraceDrivesFrameZeroWithoutReplayCompensationPreludes() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/cnz"));

        assertFalse(trace.preTraceObjectSnapshots().isEmpty(),
                "CNZ records object snapshots for randomised balloon bob phases.");
        assertFalse(TraceReplayBootstrap.hasRecordedPreLevelPrefix(trace));
        assertEquals(0, TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(trace));
        assertFalse(TraceReplayBootstrap.shouldSeedFrameZeroForTraceReplay(trace));
        assertEquals(0,
                TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace),
                "The ordinary S3K playable lifecycle owns the first native dispatch.");
        assertEquals(0,
                TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace),
                "CNZ frame 0 must run the normal level object pass, not a replay-only warmup.");
        assertEquals(TraceReplayBootstrap.ReplayStartState.DEFAULT,
                TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, null),
                "CNZ must drive and compare frame 0 normally.");
        assertEquals(trace.metadata().bk2FrameOffset(),
                TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace),
                "Frame 0 consumes the first recorded BK2 input normally.");
        assertEquals(0,
                TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(trace, -1),
                "The normal frame-0 LevelLoop owns OscillateNumDo.");
        assertEquals(0,
                TraceReplayBootstrap.initialOscillationSuppressionFramesForTraceReplay(trace),
                "Replay must not compensate for the production lifecycle with oscillator suppression.");
    }

    @Test
    void s3kMgzGameplayTraceDrivesFrameZeroWhenSonicAlreadyMoved() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/mgz"));

        assertFalse(TraceReplayBootstrap.hasRecordedPreLevelPrefix(trace));
        assertEquals(0x18, trace.getFrame(0).xSpeed(),
                "MGZ frame 0 is after the first input-driven Obj_Sonic update: "
                        + "Sonic_Move accelerates right and MoveSprite_TestGravity applies gravity "
                        + "(docs/skdisasm/sonic3k.asm:7888-7894, 21967-21985, 22350-22361, "
                        + "22428-22443, 22858-22876, 36068-36077).");
        assertEquals(0,
                TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace),
                "MGZ frame 0 is driven natively because Sonic has already moved, so the sidekick "
                        + "seed-frame prelude does not apply.");
        assertEquals(TraceReplayBootstrap.ReplayStartState.DEFAULT,
                TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, null),
                "MGZ frame 0 is not a sidekick-only seed row. Sonic has already moved, so the "
                        + "first BK2 input must still be stepped and compared natively.");
        assertEquals(0,
                TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(trace, -1),
                "Driving frame 0 natively also runs the first OscillateNumDo pass in the normal "
                        + "LevelLoop order (docs/skdisasm/sonic3k.asm:7888-7909).");
    }

    @Test
    void s3kCompleteRunSegmentsDoNotSeedFrameZeroTraceState() throws Exception {
        for (String route : java.util.List.of("aiz_completerun", "hcz_completerun",
                "mgz_completerun", "cnz_completerun", "icz_completerun",
                "lbz_completerun", "mhz_completerun")) {
            TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k", route));

            assertFalse(TraceReplayBootstrap.hasRecordedPreLevelPrefix(trace),
                    route + " begins in live LEVEL mode and must not acquire an intro prefix.");
            assertTrue(TraceReplayBootstrap.isS3kCompleteRunSegment(trace),
                    route + " must be recognized as a complete-run segment.");
            assertTrue(TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace),
                    route + " still uses the metadata start centre as bounded frame-zero bootstrap debt.");
            assertFalse(TraceReplayBootstrap.shouldGroundSnapMetadataStartForTraceReplay(trace),
                    route + " metadata is an in-level handoff centre, not a fresh spawn needing snap.");
            assertFalse(TraceReplayBootstrap.shouldSeedFrameZeroForTraceReplay(trace),
                    route + " must not copy recorded frame-zero player state into the engine.");
            assertFalse(TraceReplayBootstrap.shouldSeedReplayStartStateForTraceReplay(trace, 0),
                    route + " must not copy recorded replay-start state into the engine.");
            assertEquals(TraceReplayBootstrap.ReplayStartState.DEFAULT,
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, null),
                    route + " uses default native replay state; phase policy handles handoff rows.");
            assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace),
                    route + " complete-run segments must not receive the sidekick seed-row prelude.");
            assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace),
                    route + " represented complete-run state must not schedule replay-owned object setup.");
            assertEquals(0,
                    TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(trace, -1),
                    route + " must not schedule oscillator prelude from frame-zero outcome shape.");
            assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                    TraceReplayBootstrap.phaseForReplay(trace, null, trace.getFrame(0)),
                    route + " frame 0 follows the normal direct counter model, independent of "
                            + "recorded position, speed, or next-row state shape.");
            boolean hasHandoffCounterTick =
                    TraceReplayBootstrap.isS3kCompleteRunHandoffCounterTickRow(trace);
            assertEquals(1 + (hasHandoffCounterTick ? 1 : 0),
                    TraceReplayBootstrap.s3kCompleteRunAnimatedTilePreludeFramesForTraceReplay(trace),
                    route + " advances only native S3K Animate_Tiles calls skipped before the first driven motion row.");
        }
    }

    @Test
    void s3kCompleteRunAirborneStartsDriveFrameZeroWhenVelocityAlreadyIntegrated() throws Exception {
        TraceData hcz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/hcz_completerun"));
        TraceData mgz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/mgz_completerun"));

        assertEquals(0x0038, hcz.getFrame(0).ySpeed() & 0xFFFF,
                "HCZ frame 0 already includes the first S3K gravity step.");
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(hcz, null, hcz.getFrame(0)),
                "HCZ frame 0 must be driven and compared, otherwise replay is one gravity tick late.");
        assertEquals(0x0038, mgz.getFrame(0).ySpeed() & 0xFFFF,
                "MGZ frame 0 already includes the first S3K gravity step.");
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(mgz, null, mgz.getFrame(0)),
                "MGZ frame 0 must be driven and compared, otherwise replay is one gravity tick late.");
        assertFalse(TraceReplayBootstrap.isS3kCompleteRunHandoffCounterTickRow(hcz),
                "HCZ already includes native velocity on frame 0, so no handoff-before-motion counter tick applies.");
        assertFalse(TraceReplayBootstrap.isS3kCompleteRunHandoffCounterTickRow(mgz),
                "MGZ already includes native velocity on frame 0, so no handoff-before-motion counter tick applies.");
    }

    @Test
    void s3kCompleteRunFormerHandoffShapesAreNotPhaseClassifiedFromOutcomes() throws Exception {
        TraceData lbz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/lbz_completerun"));
        TraceData cnz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/cnz_completerun"));
        TraceData mhz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/mhz_completerun"));

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(lbz, null, lbz.getFrame(0)),
                "LBZ starts with repeated visible player rows, but the launch object countdown must tick.");
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(lbz, lbz.getFrame(0), lbz.getFrame(1)),
                "Repeated LBZ rows still run hidden startup object state.");
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(cnz, cnz.getFrame(0), cnz.getFrame(1)),
                "CNZ row 1 advances state from the former handoff-shaped row and should tick exactly once.");
        assertFalse(cnz.getFrame(0).stateEquals(cnz.getFrame(1)));
        assertEquals(0, cnz.getFrame(0).xSpeed());
        assertEquals(0, cnz.getFrame(0).ySpeed());
        assertEquals(0, cnz.getFrame(0).gSpeed());
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(cnz, null, cnz.getFrame(0)),
                "CNZ's zero-velocity/state-change shape must not override the direct frame-zero phase.");
        assertTrue(TraceReplayBootstrap.isS3kCompleteRunHandoffCounterTickRow(cnz),
                "The separate legacy handoff debt remains visible without controlling phase classification.");
        assertFalse(mhz.getFrame(0).stateEquals(mhz.getFrame(1)));
        assertEquals(0, mhz.getFrame(0).xSpeed());
        assertEquals(0, mhz.getFrame(0).ySpeed());
        assertEquals(0, mhz.getFrame(0).gSpeed());
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(mhz, null, mhz.getFrame(0)),
                "MHZ's zero-velocity/state-change shape must not override the direct frame-zero phase.");
        assertTrue(TraceReplayBootstrap.isS3kCompleteRunHandoffCounterTickRow(mhz),
                "The separate legacy handoff debt remains visible without controlling phase classification.");
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(mhz, mhz.getFrame(0), mhz.getFrame(1)),
                "MHZ row 1 advances state from the former handoff-shaped row and should tick exactly once.");
    }

    @Test
    void s3kCompleteRunFormerVisibleHoldShapeDoesNotAcquirePrefixOrSeedPrelude() throws Exception {
        TraceData icz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/icz_completerun"));
        TraceData lbz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/lbz_completerun"));

        assertFalse(TraceReplayBootstrap.hasRecordedPreLevelPrefix(icz),
                "A visible-hold start shape is not a recorded non-LEVEL -> LEVEL transition.");
        assertEquals(0x0800, icz.getFrame(0).xSpeed() & 0xFFFF,
                "ICZ frame 0 carries native launch velocity.");
        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(icz));
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(icz, null, icz.getFrame(0)),
                "The regenerated no-hook fixture exposes frame 0 as a normal native level row.");
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(icz, icz.getFrame(28), icz.getFrame(29)),
                "Later ICZ rows remain counter-driven full frames.");

        assertEquals(0, lbz.getFrame(0).ySpeed(),
                "LBZ's repeated rows are a zero-velocity hidden launch countdown, not a visible velocity hold.");
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(lbz, null, lbz.getFrame(0)),
                "LBZ still ticks the hidden ground-launch countdown during repeated visible rows.");
    }

    @Test
    void s3kCompleteRunNormalFrameZeroDoesNotSeedCounterFromCpuCursor() throws Exception {
        TraceData icz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/icz_completerun"));
        TraceData lbz = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/lbz_completerun"));

        TraceReplayBootstrap.ReplayStartState iczStart =
                TraceReplayBootstrap.applyReplayStartStateForTraceReplay(icz, null);
        assertEquals(-1,
                TraceReplaySessionBootstrap.s3kCompleteRunFrameCounterSeedForReplayStart(
                        icz, iczStart),
                "The regenerated ICZ fixture drives frame 0 normally and needs no skipped-row counter seed.");

        TraceReplayBootstrap.ReplayStartState lbzStart =
                TraceReplayBootstrap.applyReplayStartStateForTraceReplay(lbz, null);
        assertEquals(-1,
                TraceReplaySessionBootstrap.s3kCompleteRunFrameCounterSeedForReplayStart(
                        lbz, lbzStart),
                "Complete-run traces without skipped visible-hold rows should keep the normal counter path.");
    }

    @Test
    void s3kCompleteRunBootstrapDebtStaysNarrowAndDocumented() throws Exception {
        String replayBootstrap = Files.readString(Path.of(
                "src/main/java/com/openggf/trace/TraceReplayBootstrap.java"));
        String sessionBootstrap = Files.readString(Path.of(
                "src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java"));
        String discrepancies = Files.readString(Path.of("docs/status/known-discrepancies.md"));
        String releaseIssues = Files.readString(Path.of("docs/architecture/audits/release-architecture-review-issues.md"));

        assertFalse(sessionBootstrap.contains("seedS3kCompleteRunStartState"),
                "The retired S3K complete-run trace-state seed helper must not return.");
        assertTrue(replayBootstrap.matches(
                        "(?s).*public static boolean shouldSeedFrameZeroForTraceReplay\\(TraceData trace\\) \\{\\s*return false;\\s*\\}.*"),
                "TraceReplayBootstrap should keep frame-zero trace-row state seeding disabled.");
        assertTrue(replayBootstrap.matches(
                        "(?s).*public static boolean shouldSeedReplayStartStateForTraceReplay\\(TraceData trace,\\s*int requestedSeedTraceIndex\\) \\{\\s*return false;\\s*\\}.*"),
                "TraceReplayBootstrap should keep replay-start trace-row state seeding disabled.");
        assertTrue(discrepancies.contains("S3K Complete-Run Segment Start-Position Bootstrap Debt"),
                "The remaining metadata start-position bootstrap debt must stay documented.");
        assertTrue(releaseIssues.contains("| REL-034 | resolved | Trace policy |"),
                "REL-034 should remain resolved only while the seed path is removed or narrowed.");
    }

    @Test
    void s3kGameplayTraceStillDoesNotSeedFrameZeroWhenObjectSnapshotsExist() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/cnz"));

        assertFalse(trace.preTraceObjectSnapshots().isEmpty());
        assertFalse(TraceReplayBootstrap.shouldSeedReplayStartStateForTraceReplay(trace, 0),
                "Pre-trace object snapshots and primary frame-0 rows are comparison data only.");
    }

    @Test
    void s2SczAndWfzLevelSelectUseNativeTornadoRideStart() throws Exception {
        TraceData scz = loadPolicyTrace(Path.of("src/test/resources/traces/s2/scz"));
        TraceData wfz = loadPolicyTrace(Path.of("src/test/resources/traces/s2/wfz"));

        assertTrue(TraceReplayBootstrap.isS2TornadoRideStartMetadataCandidate(scz),
                "SCZ metadata is eligible for the ObjB2-authorized Tornado ride-start prelude.");
        assertTrue(TraceReplayBootstrap.isS2TornadoRideStartMetadataCandidate(wfz),
                "WFZ metadata is eligible for the ObjB2-authorized Tornado ride-start prelude.");
        assertFalse(TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(scz),
                "S2 Tornado release replay must not copy metadata start_x/start_y into the player.");
        assertFalse(TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(wfz),
                "S2 Tornado release replay must not copy metadata start_x/start_y into the player.");
    }

    @Test
    void s2SlotMachinePreludeUsesRecordedFeatureCapability() throws Exception {
        TraceData slotMachineTrace = loadPolicyTrace(Path.of("src/test/resources/traces/s2/cnz"));
        TraceData otherCapabilityTrace = loadPolicyTrace(Path.of("src/test/resources/traces/s2/scz"));

        assertTrue(slotMachineTrace.metadata().hasPerFrameSlotMachineState(),
                "Replay policy should consume generic slot-machine recorder capability metadata.");
        assertEquals(1,
                TraceReplayBootstrap.zoneFeatureTitleCardPreludeFramesForTraceReplay(slotMachineTrace),
                "Bootstrap initializes SlotMachine_Routine1; recorded row 0 owns the following "
                        + "Routine2 slot-1 draw (s2.asm:59320-59355).");
        assertEquals(10,
                TraceReplayBootstrap.zoneFeatureTitleCardPreludeStartVblankOffsetForTraceReplay(slotMachineTrace));
        assertEquals(1,
                TraceReplayBootstrap.zoneFeatureTitleCardPreludeFramesForTraceReplay(otherCapabilityTrace),
                "The generic recorder capability, not the route name, owns S2 feature prelude scheduling.");
        assertEquals(0,
                TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(slotMachineTrace),
                "Metadata-only replay policy must not apply Tornado object preludes to non-Tornado routes.");
    }

    @Test
    void ordinaryS2TraceDoesNotUseTornadoRideStart() throws Exception {
        TraceData trace = loadPolicyTrace(Path.of("src/test/resources/traces/s2/ehz1_fullrun"));

        assertFalse(TraceReplayBootstrap.isS2TornadoRideStartMetadataCandidate(trace));
    }

    @Test
    void bonusAndSingleCharacterStartsDoNotAcquireS3kPrefixOrSeedPrelude() throws Exception {
        TraceData bonus = loadPolicyTrace(Path.of("src/test/resources/traces/s3k/bonus_gumball"));

        assertEquals(List.of("knuckles"), bonus.metadata().recordedCharacters());
        assertFalse(TraceReplayBootstrap.hasRecordedPreLevelPrefix(bonus));
        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(bonus));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(bonus));
        assertEquals(0, TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(bonus, -1));
    }

    @Test
    void contradictoryLegacyMetadataCannotChangeStructuralAizPolicy() throws Exception {
        TraceData aiz = metadataVariant(
                Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"),
                "aiz-legacy-conflict",
                false,
                true,
                73,
                290);

        assertFalse(aiz.metadata().hasPreLevelIntroPrefix());
        assertTrue(aiz.metadata().hasSidekickSeedFramePrelude());
        assertEquals(73, aiz.metadata().preTraceOscillationFrames());
        assertTrue(TraceReplayBootstrap.hasRecordedPreLevelPrefix(aiz));
        assertEquals(0, TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(aiz));
        assertEquals(0, TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(aiz, -1));
    }

    @Test
    void contradictoryLegacyMetadataCannotChangeStructuralCnzPolicy() throws Exception {
        TraceData cnz = metadataVariant(
                Path.of("src/test/resources/traces/s3k/cnz"),
                "cnz-legacy-conflict",
                true,
                true,
                91,
                1);

        assertTrue(cnz.metadata().hasPreLevelIntroPrefix());
        assertTrue(cnz.metadata().hasSidekickSeedFramePrelude());
        assertEquals(91, cnz.metadata().preTraceOscillationFrames());
        assertFalse(TraceReplayBootstrap.hasRecordedPreLevelPrefix(cnz));
        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(cnz));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(cnz));
        assertEquals(0, TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(cnz, -1));
    }

    private TraceData metadataVariant(Path source,
                                      String directoryName,
                                      boolean preLevelPrefix,
                                      boolean sidekickSeedPrelude,
                                      int preTraceOscillationFrames,
                                      int lastFrame) throws Exception {
        Path target = tempDir.resolve(directoryName);
        Files.createDirectories(target);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode metadata = (ObjectNode) mapper.readTree(source.resolve("metadata.json").toFile());
        ArrayNode extras = metadata.withArray("aux_schema_extras");
        for (int i = extras.size() - 1; i >= 0; i--) {
            String value = extras.get(i).asText();
            if ("pre_level_intro_prefix".equals(value)
                    || "sidekick_seed_frame_prelude".equals(value)) {
                extras.remove(i);
            }
        }
        if (preLevelPrefix) {
            extras.add("pre_level_intro_prefix");
        }
        if (sidekickSeedPrelude) {
            extras.add("sidekick_seed_frame_prelude");
        }
        metadata.put("pre_trace_osc_frames", preTraceOscillationFrames);
        metadata.put("trace_frame_count", lastFrame + 1);
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("metadata.json").toFile(), metadata);
        copyPhysicsPrefix(source, target, lastFrame);
        copyAuxPrefix(source, target, mapper, lastFrame);
        return TraceData.load(target);
    }

    /**
     * Loads the checked-in physics rows and only the aux event families used by
     * replay scheduling policy. The regenerated no-hook fixtures carry more
     * than 100 MiB of per-object diagnostics each; retaining those unrelated
     * comparison events would make this policy-only suite depend on heap size.
     */
    private TraceData loadPolicyTrace(Path source) throws Exception {
        Path target = tempDir.resolve("policy-"
                + policyTraceCopyIndex++ + "-" + source.getFileName());
        Files.createDirectories(target);
        writePolicyMetadata(source, target);
        copyTraceFile(source, target, "physics.csv");
        try (BufferedReader reader = traceReader(source, "aux_state.jsonl");
             BufferedWriter writer = Files.newBufferedWriter(
                     target.resolve("aux_state.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isReplayPolicyEvent(line)) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
        return TraceData.load(target);
    }

    /**
     * Copies {@code metadata.json} minus the per-frame aux capabilities whose
     * event families {@link #isReplayPolicyEvent} deliberately drops.
     * {@link TraceData#load} enforces one typed heartbeat per stored physics
     * row for every advertised per-frame family
     * ({@code validateAdvertisedLoadQueueStates} /
     * {@code validateAdvertisedDynamicArtTransferStates}), so a derived
     * fixture that keeps the capability while discarding its events
     * contradicts itself and fails to load at frame 0. The curated event set
     * is what these policy assertions are written against, so the capability
     * list follows the payload rather than the payload following the list.
     */
    private static void writePolicyMetadata(Path source, Path target) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode metadata = (ObjectNode) mapper.readTree(source.resolve("metadata.json").toFile());
        ArrayNode extras = metadata.withArray("aux_schema_extras");
        for (int i = extras.size() - 1; i >= 0; i--) {
            String value = extras.get(i).asText();
            if (UNRETAINED_PER_FRAME_CAPABILITIES.contains(value)) {
                extras.remove(i);
            }
        }
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(target.resolve("metadata.json").toFile(), metadata);
    }

    private static boolean isReplayPolicyEvent(String line) {
        return line.contains("\"event\":\"zone_act_state\"")
                || line.contains("\"event\":\"checkpoint\"")
                || line.contains("\"event\":\"tails_cpu_normal_step\"")
                || line.contains("\"event\":\"cpu_state\"")
                || line.contains("\"event\":\"object_state_snapshot\"");
    }

    private static void copyTraceFile(Path source, Path target, String baseName) throws Exception {
        // Preserve compressed fixture bytes when available so copied fixtures retain
        // their canonical compressed representation.
        Path compressed = source.resolve(baseName + ".gz");
        Path input = Files.exists(compressed) ? compressed : source.resolve(baseName);
        Files.copy(input, target.resolve(input.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyPhysicsPrefix(Path source, Path target, int lastFrame) throws Exception {
        try (BufferedReader reader = traceReader(source, "physics.csv");
             BufferedWriter writer = Files.newBufferedWriter(
                     target.resolve("physics.csv"), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            writer.write(header);
            writer.newLine();
            for (int frame = 0; frame <= lastFrame; frame++) {
                writer.write(reader.readLine());
                writer.newLine();
            }
        }
    }

    private static void copyAuxPrefix(Path source,
                                      Path target,
                                      ObjectMapper mapper,
                                      int lastFrame) throws Exception {
        try (BufferedReader reader = traceReader(source, "aux_state.jsonl");
             BufferedWriter writer = Files.newBufferedWriter(
                     target.resolve("aux_state.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (mapper.readTree(line).path("frame").asInt() <= lastFrame) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    private static BufferedReader traceReader(Path directory, String baseName) throws Exception {
        Path compressed = directory.resolve(baseName + ".gz");
        InputStream input = Files.newInputStream(
                Files.exists(compressed) ? compressed : directory.resolve(baseName));
        if (Files.exists(compressed)) {
            input = new GZIPInputStream(input);
        }
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static int firstInputOnlyStateRow(TraceData trace) {
        for (int i = 1; i + 1 < trace.frameCount(); i++) {
            for (TraceEvent event : trace.getEventsForFrame(i)) {
                if (event instanceof TraceEvent.Checkpoint checkpoint
                        && "gameplay_start".equals(checkpoint.name())) {
                    return -1;
                }
            }
            TraceFrame previous = trace.getFrame(i - 1);
            TraceFrame current = trace.getFrame(i);
            if (current.input() != previous.input()
                    && current.stateEquals(previous)
                    && current.gameplayFrameCounter() == previous.gameplayFrameCounter()
                    && current.vblankCounter() == previous.vblankCounter()
                    && current.lagCounter() == previous.lagCounter()) {
                return i;
            }
        }
        return -1;
    }

    private static int firstStateAdvancingInputLatchRow(TraceData trace) {
        boolean afterGameplayStart = false;
        for (int i = 1; i + 1 < trace.frameCount(); i++) {
            if (!afterGameplayStart) {
                for (TraceEvent event : trace.getEventsForFrame(i)) {
                    if (event instanceof TraceEvent.Checkpoint checkpoint
                            && "gameplay_start".equals(checkpoint.name())) {
                        afterGameplayStart = true;
                    }
                }
                continue;
            }
            TraceFrame previous = trace.getFrame(i - 1);
            TraceFrame current = trace.getFrame(i);
            if (current.input() != previous.input()
                    && !current.stateEquals(previous)
                    && current.gameplayFrameCounter() == previous.gameplayFrameCounter()
                    && current.vblankCounter() == previous.vblankCounter()
                    && current.lagCounter() == previous.lagCounter()) {
                return i;
            }
        }
        return -1;
    }
}
