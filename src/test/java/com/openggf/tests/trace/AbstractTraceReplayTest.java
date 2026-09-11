package com.openggf.tests.trace;

import com.openggf.Engine;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2ZoneFeatureProvider;
import com.openggf.game.sonic2.objects.TornadoObjectInstance;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineManager;
import com.openggf.level.LevelManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.objects.Aiz2BossEndSequenceState;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSignpostInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseDebugHit;
import com.openggf.level.objects.TouchResponseDebugState;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.SessionInvocationExtension;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.s2.S2SkyChaseBadnikDiagnostics;
import com.openggf.tests.trace.s3k.S3kCheckpointProbe;
import com.openggf.tests.trace.s3k.S3kSidekickCylinderDiagnostics;
import com.openggf.trace.DivergenceGroup;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.EngineDiagnostics;
import com.openggf.trace.EngineSnapshot;
import com.openggf.trace.EngineSidekickCpuState;
import com.openggf.trace.EngineNearbyObject;
import com.openggf.trace.EngineNearbyObjectFormatter;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.LoadQueueComparisonProjection;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TouchResponseDebugHitFormatter;
import com.openggf.trace.TraceBinder;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceEventFormatter;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.TraceVerificationScope;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.TraceReplayEngineSnapshot;
import com.openggf.tests.trace.s3k.S3kRequiredCheckpointGuard;
import com.openggf.tests.trace.s3k.S3kReplayCheckpointDetector;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.openggf.tests.trace.TraceReplayDiagnostics.combineDiagnostics;
import static com.openggf.tests.trace.TraceReplayDiagnostics.hasTracePayload;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for trace replay tests. Subclasses provide game/zone/act/path;
 * this class handles level loading, BK2 playback, per-frame comparison, and report output.
 *
 * <p>Originally used JUnit 4 because the ROM fixture was exposed as a JUnit 4 rule.
 */
@ExtendWith(SessionInvocationExtension.class)
public abstract class AbstractTraceReplayTest {
    private static final Logger LOGGER = Logger.getLogger(AbstractTraceReplayTest.class.getName());
    private static final boolean QUIET_TRACE_LOGS =
            Boolean.parseBoolean(System.getProperty("trace.quietLogs", "true"));

    static {
        if (QUIET_TRACE_LOGS) {
            System.setProperty("slf4j.internal.verbosity", "ERROR");
            quietJavaUtilLogging();
        }
    }

    /**
     * Env-gated ({@code OGGF_SLOT_PROBE=1}) ROM-vs-engine SST occupancy diff.
     * Null in every ordinary run; comparison-only when armed.
     */
    private SlotOccupancyProbe slotOccupancyProbe;

    /** Which game ROM this test requires. */
    protected abstract SonicGame game();

    private static void quietJavaUtilLogging() {
        Logger root = Logger.getLogger("");
        if (root.getLevel() == null || root.getLevel().intValue() < Level.WARNING.intValue()) {
            root.setLevel(Level.WARNING);
        }
        for (Handler handler : root.getHandlers()) {
            if (handler.getLevel() == null || handler.getLevel().intValue() < Level.WARNING.intValue()) {
                handler.setLevel(Level.WARNING);
            }
        }
    }

    /** Zone index (0-based). */
    protected abstract int zone();

    /** Act index (0-based). */
    protected abstract int act();

    /** Path to the trace directory containing metadata.json, physics.csv(.gz), and optionally a .bk2. */
    protected abstract Path traceDirectory();

    /** Override to supply custom tolerances. */
    protected ToleranceConfig tolerances() {
        return ToleranceConfig.DEFAULT;
    }

    /** Override to force a specific pre-trace oscillation frame count. Return -1 to use metadata. */
    protected int overridePreTraceOscFrames() { return -1; }

    /** Override to change report output directory. */
    protected Path reportOutputDir() {
        return TestSessionOutputPaths.traceReports();
    }

    /** Override only for explicitly diagnostic trace fixtures that are not release gates. */
    protected boolean allowDiagnosticOnlyTraceReplay() {
        return false;
    }

    /** Override only when warning-only reports are deliberately diagnostic debt. */
    protected boolean allowDiagnosticOnlyWarnings() {
        return false;
    }

    /** Override when a trace needs object-slot aux events to define the true frontier. */
    protected boolean compareObjectNearEvents() {
        return false;
    }

    /** Independent trace gate selected with {@code -Dtrace.verification}. */
    protected TraceVerificationScope verificationScope() {
        return TraceVerificationScope.fromSystemProperty();
    }

    /** Override with a diagnostic predicate when only a subset of object-near events is relevant. */
    protected boolean shouldCompareObjectNearEvent(TraceEvent.ObjectNear near) {
        return true;
    }

    /** Test observer invoked after a real replay frame passes rewind closure validation. */
    protected void onRewindReferenceClosureValidated(
            int traceIndex, TraceFrame frame, TraceExecutionPhase phase) {
        // test observer hook
    }

    /**
     * Post-fixture-build hook for profile-specific entry setup (e.g. bonus
     * stage provider registration). Default: no-op.
     */
    protected void afterFixtureBuild(TraceData trace) {
    }

    /**
     * The sprite compared against the recorded trace for this frame. Default:
     * the fixture's primary sprite (unchanged behavior for every existing
     * trace replay). Override when the runtime under test can swap which
     * playable object the ROM camera/comparator actually tracks (see
     * {@code AbstractS3kBonusStageTraceReplayTest} for the slot-runtime
     * player-swap case).
     */
    protected AbstractPlayableSprite comparedSprite(HeadlessTestFixture fixture) {
        return fixture.sprite();
    }

    /**
     * Whether the live production lifecycle has reached this replay profile's
     * terminal boundary. Checked only after the current frame has been driven
     * and compared, so the boundary-owning gameplay row remains verified.
     */
    protected boolean replayTerminalReached() {
        return false;
    }

    /**
     * Optional semantic end for the hardware-timing schedule. Returning a raw
     * frame asks the S3K replay loop to drive and compare through that
     * represented frame, then close the timing prefix.
     */
    protected Integer semanticTimingPrefixLastRawFrame(TraceData trace) {
        return null;
    }

    /**
     * Whether the recorded run deliberately ends with production hardware
     * work still in flight. Such a trace is a semantic handoff prefix: every
     * completion edge represented by the stream must still be consumed, but
     * work whose ROM completion lies beyond the captured rows is detached with
     * the fixture rather than treated as a replay failure.
     */
    protected List<ExpectedPendingHardwareWork> expectedPendingHardwareTimingAtTraceEnd(
            TraceData trace) {
        return List.of();
    }

    protected record ExpectedPendingHardwareWork(
            HardwareWorkKind kind,
            long ordinal,
            int romSourceAddress,
            String submissionFingerprint) {
    }

    protected static ExpectedPendingHardwareWork expectedPendingHardwareWork(
            HardwareWorkKind kind,
            long ordinal,
            int romSourceAddress,
            String submissionFingerprint) {
        return new ExpectedPendingHardwareWork(
                kind, ordinal, romSourceAddress, submissionFingerprint);
    }

    private static void verifyExpectedPendingHardwareTiming(
            HeadlessTestFixture fixture,
            List<ExpectedPendingHardwareWork> expected) {
        var mode = fixture.gameplayMode();
        var jobsByHandle = mode.hardwareTiming().capture().jobs().stream()
                .collect(java.util.stream.Collectors.toMap(
                        job -> job.handle(), job -> job));
        List<ExpectedPendingHardwareWork> actual = mode
                .recordedCompletionAuthority()
                .pendingSubmissions().stream()
                .map(pending -> {
                    var job = jobsByHandle.get(pending.handle());
                    if (job == null) {
                        throw new AssertionError(
                                "pending hardware identity has no production job: "
                                        + pending.handle());
                    }
                    return new ExpectedPendingHardwareWork(
                            pending.handle().kind(),
                            pending.handle().ordinal(),
                            job.romSourceAddress(),
                            pending.handle().submissionFingerprint());
                })
                .toList();
        assertEquals(expected, actual,
                "terminal pending hardware work must match the declared semantic handoff");
    }

    static boolean shouldValidateRewindReferenceClosure(SonicGame game) {
        return game == SonicGame.SONIC_2 || game == SonicGame.SONIC_3K;
    }

    /**
     * Drives one replay frame for a focused scenario test through the same
     * {@link TraceReplayFrameClosureDriver} the whole-trace comparison loop
     * uses.
     *
     * <p>Scenario tests replay a prefix of a trace to reach one interesting
     * frame and then assert on engine state there. They must reach that frame
     * along exactly the path {@link #replayMatchesTrace()} takes, otherwise
     * they are asserting against a differently-driven engine. Hand-rolled
     * per-test steppers that only special-cased {@code VBLANK_ONLY} silently
     * promoted {@code ADVANCE_ONLY} rows -- ROM frames where the recorder saw
     * a controller edge while {@code Level_frame_counter}, {@code
     * V_int_run_count} and the lag counter all stood still -- into full level
     * ticks, advancing the object/VBlank clock on frames the ROM never ran.
     *
     * @return the BK2 input consumed for this frame
     */
    protected final int driveScenarioReplayFrame(
            TraceData trace, HeadlessTestFixture fixture, TraceExecutionPhase phase) {
        return TraceReplayFrameClosureDriver.driveS3k(
                phase,
                TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace),
                fixture::stepFrameFromRecording,
                fixture::stepFrameFromRecordingUsingPreviousInput,
                fixture::skipFrameFromRecording,
                fixture::consumeRecordingFrameInputOnly,
                fixture::advancePlayableAnimationsOnly,
                fixture::suppressFirstSidekickAnimationOnce,
                () -> {
                });
    }

    @Test
    public void replayMatchesTrace() throws Exception {
        // 0. Skip if trace directory or required files are missing
        Path traceDir = TraceFixtureRoot.resolve(traceDirectory());
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);
        Assumptions.assumeTrue(Files.exists(traceDir.resolve("metadata.json")), "metadata.json not found in " + traceDir);
        Assumptions.assumeTrue(hasTracePayload(traceDir, "physics.csv"), "physics.csv(.gz) not found in " + traceDir);

        // 1. Load trace data (metadata is needed to resolve a shared, deduplicated BK2)
        TraceData trace = TraceData.load(traceDir);
        TraceMetadata meta = trace.metadata();
        TraceVerificationScope verificationScope = verificationScope();
        if (verificationScope == TraceVerificationScope.ANIMATION
                && !meta.hasPerFrameCharacterAnimation()) {
            fail("Animation-only verification requires CSV v7 character animation fields: "
                    + traceDir);
        }
        List<String> releaseBlockers = TraceReplayBootstrap.releaseBlockersForTraceReplay(trace);
        if (!releaseBlockers.isEmpty() && !allowDiagnosticOnlyTraceReplay()) {
            fail(String.join(System.lineSeparator(), releaseBlockers));
        }

        // 2. Resolve BK2: prefer a shared movie referenced by metadata.source_bk2 (stored once
        //    under <game>/_movies/), else use the retained per-directory movie placement.
        //    This is a v5 movie-location fallback, not legacy trace-schema support.
        Path bk2Path = resolveBk2File(traceDir, meta);
        Assumptions.assumeTrue(bk2Path != null,
                "No BK2 found for " + traceDir + " (no _movies/<source_bk2> and no .bk2 in dir)");
        boolean requiresFreshLevelLoad =
                TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace)
                        || trace.hardwareTimingSchedule().hasRecordedInput();

        // 3. Validate test configuration matches metadata
        validateMetadata(meta);
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);

        // 4. Load level and create fixture
        SharedLevel sharedLevel = requiresFreshLevelLoad
                ? null
                : SharedLevel.load(game(), zone(), act());
        // Hoisted so the finally block can always regenerate the report, even when
        // the run short-circuits via fail() (e.g. input-alignment) before the
        // normal report-write at step 7. Previously such failures left a STALE
        // *_report.json from an earlier run, silently masking the real result.
        TraceBinder binder = null;
        HeadlessTestFixture fixture = null;
        boolean hardwareTimingReplayClosed = false;
        Throwable replayFailure = null;
        // Comparison-only, env-gated (OGGF_SLOT_PROBE=1) SST occupancy diff. Off in
        // every normal run, and it never touches engine or binder state.
        slotOccupancyProbe = SlotOccupancyProbe.createIfEnabled(trace, game() + "_" + zone() + act());
        try {
            HeadlessTestFixture.Builder fixtureBuilder = HeadlessTestFixture.builder()
                .withRecording(bk2Path)
                .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                .withHardwareReadinessAdmissionPolicy(
                        trace.hardwareTimingSchedule().hasRecordedInput()
                                ? HardwareReadinessAdmissionPolicy.RECORDED
                                : HardwareReadinessAdmissionPolicy.LIVE);
            if (sharedLevel != null) {
                fixtureBuilder.withSharedLevel(sharedLevel);
            } else {
                fixtureBuilder.withZoneAndAct(zone(), act());
            }
            if (shouldApplyMetadataStartPosition(trace, meta)) {
                fixtureBuilder
                        .startPosition(meta.startX(), meta.startY())
                        .startPositionIsCentre();
            }
            // The lifecycle claim is about ROM state, not fixture mechanics: it
            // asks whether the ROM has just run SpawnLevelMainSprites and no
            // LevelLoop iteration has been dispatched yet. A replay that begins
            // at trace row 0 is exactly that -- row 0 is LevelLoop iteration 1,
            // so the player still carries its spawn-determined Status_InAir
            // (set only by the explicit per-zone bsets at sonic3k.asm:8132-8177;
            // MHZ1 $700 and CNZ1 $300 fall through to loc_68D8 at 8178-8197 and
            // spawn grounded). Whether the fixture also ground-snaps the
            // metadata start is a separate question, and gating on it let the
            // fixture's synthetic pre-frame terrain probe consume the floor
            // check that ROM performs during row 0 itself.
            if (TraceReplayBootstrap.shouldGroundSnapMetadataStartForTraceReplay(trace)
                    || TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(trace) == 0) {
                fixtureBuilder.withFreshLevelStartLifecycle();
            }
            fixture = fixtureBuilder.build();
            // ROM/production ordering: GameLoop.doEnterBonusStage loads the
            // bonus zone through the normal level path (LevelManager
            // .loadZoneAndAct -> LevelManager.initCameraForLevel, which resets
            // ObjectManager's dynamic-object set for S3K's two-axis cursor
            // placement -- LevelManager.java:2564-2577) and only THEN calls
            // ensureBonusStageBootstrapObjectPresent (GameLoop.java:2314,
            // inside prepareBonusStageForTitleCard, itself called after
            // loadZoneAndAct at GameLoop.java:2229/2245). Running
            // afterFixtureBuild's bonus-entry bootstrap object injection
            // (TraceReplaySessionBootstrap.applyBonusStageEntry ->
            // Obj_PachinkoEnergyTrap) BEFORE applyStartPositionAndGroundSnap
            // (which calls the same initCameraForLevel reset) silently wiped
            // the freshly-injected trap every frame, so it never executed and
            // Sonic's escape-through-the-top exit trigger never fired --
            // producing a same-frame-only camera_x divergence at the exact
            // frame ROM's Restart_level_flag check skips DeformBgLayer
            // (sonic3k.asm:7895-7896) after Process_Sprites. Apply the ground
            // snap/camera reset first so any bonus-entry object injection
            // survives it, matching production's load-then-inject order.
            TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
            afterFixtureBuild(trace);

            if (GameServices.debugOverlay() != null) {
                GameServices.debugOverlay().setEnabled(DebugOverlayToggle.TOUCH_RESPONSE, true);
            }

            // 4. Shared replay bootstrap: timing prelude, read-only snapshot
            //    reporting, and replay cursor selection.
            TraceReplaySessionBootstrap.BootstrapResult boot =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture,
                            overridePreTraceOscFrames());
            // This driver owns a comparison report, so it takes reporting
            // responsibility for close-time leftover hardware submissions
            // instead of letting them abort ahead of the divergence that
            // caused them. Drivers that do not opt in still fail hard.
            fixture.reportPendingRecordedHardwareSubmissionsAtClose();
            TraceReplayBootstrap.SnapshotReport snapshotReport = boot.snapshotReport();
            TraceReplayBootstrap.ReplayStartState replayStart = boot.replayStart();
            ObjectManager om = GameServices.level().getObjectManager();
            List<TraceEvent.ObjectStateSnapshot> preTraceSnapshots =
                    trace.preTraceObjectSnapshots();
            if (TraceReplayConsole.shouldPrintBootstrap()
                    && !preTraceSnapshots.isEmpty()
                    && om != null) {
                System.out.printf(
                        "Reported %d/%d pre-trace object snapshots (%d warnings)%n",
                        snapshotReport.matched(), snapshotReport.attempted(),
                        snapshotReport.warnings().size());
                for (String warning : snapshotReport.warnings()) {
                    System.out.println("  WARN: " + warning);
                }
            }

            // 5. Run frame-by-frame comparison
            binder = new TraceBinder(tolerances());

            // Frame-0 bootstrap comparison. Active only for traces recorded
            // against the post-universal-title-card engine (TraceMetadata
            // .hasNativePreludeBootstrap() derived from explicit metadata);
            // a no-op return for legacy traces. Surfaces a BootstrapDivergence
            // category in the final DivergenceReport for any mismatch between
            // engine state at frame 0 and the recorded ROM frame-0 snapshot
            // (player history ring, Tails CPU state, per-slot SST values).
            EngineSnapshot frameZero = captureEngineSnapshot(fixture);
            binder.compareBootstrapFrame0(trace, frameZero);

            FrontierReplayStopper frontierStopper = FrontierReplayStopper.fromSystemProperties();
            if ("s3k".equals(meta.game())) {
                hardwareTimingReplayClosed = replayS3kTrace(
                        trace, meta, fixture, binder, replayStart, frontierStopper);
            } else {
                int startTraceIndex = replayStart.startingTraceIndex();
                for (int i = startTraceIndex; i < trace.frameCount(); i++) {
                    TraceFrame expected = trace.getFrame(i);
                    fixture.beginTraceRow(i, expected.frame());

                    // Drive replay from recorded ROM counters instead of inferring
                    // lag from unchanged physics state.
                    int bk2Input;
                    TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                    TraceExecutionPhase phase =
                        TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
                    TraceReplayBootstrap.markVblankStarvedIterationForReplay(previous, expected);
                    int validationTraceIndex = i;
                    bk2Input = TraceReplayFrameClosureDriver.driveGeneral(
                            phase,
                            fixture::stepFrameFromRecording,
                            fixture::skipFrameFromRecording,
                            () -> {
                                if (shouldValidateRewindReferenceClosure(game())) {
                                    TraceReplayFrameClosureDriver.validateCurrentObjectManager(
                                            () -> {
                                                var level = GameServices.levelOrNull();
                                                return level != null
                                                        ? new TraceReplayFrameClosureDriver.CurrentLevelContext(
                                                                level.getObjectManager(),
                                                                level.getCurrentZone(),
                                                                level.getCurrentAct())
                                                        : null;
                                            },
                                            meta.game(), meta.zone(), meta.act(),
                                            validationTraceIndex, expected, phase);
                                    onRewindReferenceClosureValidated(
                                            validationTraceIndex, expected, phase);
                                }
                            });

                    if (!binder.validateInput(expected, bk2Input)) {
                        fail(String.format(
                            "Input alignment error at trace frame %d: " +
                            "BK2 input=0x%04X, trace input=0x%04X. " +
                            "Check bk2_frame_offset in metadata.json.",
                            i, bk2Input, expected.input()));
                    }
                    if (slotOccupancyProbe != null && GameServices.level() != null) {
                        slotOccupancyProbe.observe(
                                i, GameServices.level().getObjectManager());
                    }
                    if (!TraceReplayBootstrap.shouldCompareGameplayStateForReplay(phase)) {
                        compareDynamicArtIfAdvertised(
                                trace, binder, expected.frame());
                        if (observeFrontierAndShouldStop(
                                frontierStopper, binder, expected.frame())) {
                            break;
                        }
                        continue;
                    }
                    TraceFrame comparisonExpected =
                            TraceReplayBootstrap.frameForGameplayComparison(
                                    trace, i, previous, expected, phase);

                    // ROM stores centre coordinates at $D008/$D00C. With startPositionIsCentre(),
                    // the sprite's xPixel/yPixel are set to the correct top-left position,
                    // so getCentreX()/getCentreY() now return the actual ROM centre values.
                    var sprite = comparedSprite(fixture);

                    // Capture engine-side diagnostic state for context window
                    EngineDiagnostics engineDiag = captureEngineDiagnostics(sprite, comparisonExpected);
                    TraceCharacterState actualSidekick = captureFirstSidekickState();
                    String secondaryCharacterLabel = meta.recordedSidekicks().isEmpty()
                            ? "sidekick"
                            : meta.recordedSidekicks().getFirst();
                    String romDiag = combineDiagnostics(
                            comparisonExpected.hasExtendedData() ? comparisonExpected.formatDiagnostics() : "",
                            formatCharacterDiagnostics(secondaryCharacterLabel, expected.sidekick()));
                    romDiag = combineDiagnostics(
                            romDiag,
                            TraceEventFormatter.summariseFrameEvents(trace.getEventsForFrame(i)));
                    String engineDiagText = combineDiagnostics(
                            engineDiag.format(),
                            formatCharacterDiagnostics(secondaryCharacterLabel, actualSidekick));
                    TraceEvent.CpuState expectedSidekickCpu =
                            trace.cpuStateForFrame(comparisonExpected.frame(), secondaryCharacterLabel);
                    TraceEvent.TailsCpuNormalStep expectedSidekickNormalStep =
                            trace.tailsCpuNormalStepForFrame(comparisonExpected.frame(), secondaryCharacterLabel);
                    EngineSidekickCpuState actualSidekickCpu = captureFirstSidekickCpuState();
                    binder.compareFrame(comparisonExpected,
                        sprite.getCentreX(), sprite.getCentreY(),
                        sprite.getXSpeed(), sprite.getYSpeed(), sprite.getGSpeed(),
                        sprite.getAngle(),
                        sprite.getAir(), sprite.getRolling(),
                        sprite.getGroundMode().ordinal(), romDiag,
                        EngineDiagnostics.formattedWithCameraAnimationAndRings(
                                engineDiag.cameraX(), engineDiag.cameraY(),
                                engineDiag.animationId(), engineDiag.mappingFrame(),
                                engineDiag.rings(), engineDiagText)
                                .withLives(engineDiag.lives()),
                        secondaryCharacterLabel, actualSidekick,
                        expectedSidekickCpu, actualSidekickCpu, expectedSidekickNormalStep);
                    compareLoadQueuesIfAdvertised(
                            trace, binder, comparisonExpected.frame());
                    compareCnzSlotMachineIfRecorded(
                            trace, binder, comparisonExpected.frame());
                    compareS2TornadoIfRecorded(
                            trace, binder, comparisonExpected.frame());
                    compareDynamicArtIfAdvertised(
                            trace, binder, expected.frame());
                    recordUnmatchedHardwareCompletions(
                            fixture, binder, expected.frame());
                    if (compareObjectNearEvents()) {
                        binder.compareObjectNear(
                                comparisonExpected.frame(),
                                objectNearEventsForPrimary(trace, comparisonExpected.frame()),
                                captureEngineNearbyObjects(sprite));
                    }
                    if (observeFrontierAndShouldStop(
                            frontierStopper, binder, comparisonExpected.frame())) {
                        break;
                    }

                }
            }

            finishDynamicArtComparison(
                    trace, binder, fixture,
                    !frontierStopper.stoppedEarly());
            if (!hardwareTimingReplayClosed) {
                List<ExpectedPendingHardwareWork> expectedPending =
                        expectedPendingHardwareTimingAtTraceEnd(trace);
                if (!expectedPending.isEmpty()) {
                    fixture.verifyHardwareTimingSegmentEdges();
                    verifyExpectedPendingHardwareTiming(fixture, expectedPending);
                    fixture.abortHardwareTimingReplayRun();
                } else {
                    fixture.closeHardwareTimingReplayRun();
                }
                hardwareTimingReplayClosed = true;
            }
            recordPendingHardwareSubmissions(fixture, binder);

            // 6. Build report
            DivergenceReport report = buildDivergenceReport(binder, meta, trace);
            // Asserted here rather than inside writeReport: the finally block below
            // rewrites the report best-effort and only swallows RuntimeException, so
            // an AssertionError thrown from there would replace the real divergence
            // failure instead of adding to it.
            TraceReportWriter.assertGroupAccountingHolds(report);

            // 7. Write report if there are any divergences
            if (report.hasErrors() || report.hasWarnings()) {
                writeReport(report, meta);
            }

            // 8. Log summary only when explicitly requested. Failing assertions
            // still carry the compact frontier summary.
            TraceReplayConsole.printSummary(report, verificationScope);

            // 9. Assert no errors
            if (report.hasErrors(verificationScope) && TraceReplayConsole.shouldPrintContext()) {
                System.err.println("\n=== Context window around first error ===");
                System.err.println(report.getContextWindow(
                        report.firstErrorFrame(verificationScope), TraceReplayConsole.contextRadius()));
            }
            assertReportHasNoReleaseBlockingDivergences(report);
        } catch (Exception | Error failure) {
            replayFailure = failure;
            throw failure;
        } finally {
            // Always (re)write the report from the latest binder state so a stale
            // *_report.json from a prior run can never mask the current result.
            // Equivalent publication is idempotent. A real publication failure
            // is attached to the primary replay failure, or fails a run that
            // otherwise had no primary failure.
            Throwable reportFailure = null;
            if (binder != null) {
                try {
                    // Unconditional: a run that aborts mid-replay with a clean
                    // comparison so far (e.g. a hardware-timing admission throw)
                    // has zero divergences, and a divergence-gated write left no
                    // report at all -- indistinguishable from a run that never
                    // started. The report's total_frames is the only record of
                    // how far the replay actually reached.
                    writeReport(buildDivergenceReport(binder, meta, trace), meta);
                } catch (Exception | Error failure) {
                    if (replayFailure != null) {
                        replayFailure.addSuppressed(failure);
                    } else {
                        reportFailure = failure;
                    }
                }
            }
            if (slotOccupancyProbe != null) {
                slotOccupancyProbe.close();
                slotOccupancyProbe = null;
            }
            if (fixture != null && !hardwareTimingReplayClosed) {
                fixture.abortHardwareTimingReplayRun();
            }
            if (sharedLevel != null) {
                sharedLevel.dispose();
            } else {
                TestEnvironment.resetAll();
            }
            if (reportFailure != null) {
                if (reportFailure instanceof Exception exception) {
                    throw exception;
                }
                if (reportFailure instanceof Error error) {
                    throw error;
                }
                throw new AssertionError("unexpected report publication failure", reportFailure);
            }
        }
    }

    protected void assertReportHasNoReleaseBlockingDivergences(DivergenceReport report) {
        TraceReplayDiagnostics.assertNoReleaseBlockingDivergences(
                report, verificationScope(), allowDiagnosticOnlyWarnings());
    }

    /**
     * Capture a read-only snapshot of engine state at frame 0 for the
     * bootstrap comparator. The comparator only activates for v5 traces that
     * advertise the {@code native_prelude_bootstrap} semantic capability (see
     * {@link TraceMetadata#hasNativePreludeBootstrap()}); for traces without it
     * this snapshot is built but discarded. Captures whatever is readily
     * available from the fixture; fields without an accessible source are
     * left null/empty (the comparator emits WARNING entries for those).
     */
    private EngineSnapshot captureEngineSnapshot(HeadlessTestFixture fixture) {
        return TraceReplayEngineSnapshot.capture(
                fixture != null ? comparedSprite(fixture) : null);
    }

    /**
     * Build the divergence report for this trace. S3K passes the trace so the
     * binder can normalize split-row diagnostics; other games use the no-arg
     * builder. Extracted so the step-6 and finally/regen call sites cannot drift.
     */
    private DivergenceReport buildDivergenceReport(TraceBinder binder, TraceMetadata meta, TraceData trace) {
        return "s3k".equals(meta.game())
                ? binder.buildReport(trace)
                : binder.buildReport();
    }

    private void validateMetadata(TraceMetadata meta) {
        String expectedGameId = switch (game()) {
            case SONIC_1 -> "s1";
            case SONIC_2 -> "s2";
            case SONIC_3K -> "s3k";
        };
        assertEquals(expectedGameId, meta.game(), "Metadata game mismatch (test says " + game()
            + " but metadata says " + meta.game() + ")");
    }

    private boolean shouldApplyMetadataStartPosition(TraceData trace, TraceMetadata meta) {
        // Power-on traces can begin before the level is actually live. In those
        // cases start_x/start_y reflect whatever was left in Player_1 RAM at the
        // recorder start, not the first replayable in-level position.
        return TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace);
    }

    private boolean replayS3kTrace(TraceData trace, TraceMetadata meta,
                                HeadlessTestFixture fixture, TraceBinder binder,
                                TraceReplayBootstrap.ReplayStartState replayStart,
                                FrontierReplayStopper frontierStopper) {
        int driveTraceIndex = replayStart.startingTraceIndex();
        TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : driveTraceIndex > 0 ? trace.getFrame(driveTraceIndex - 1) : null;
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
        S3kRequiredCheckpointGuard checkpointGuard = new S3kRequiredCheckpointGuard();
        Integer semanticTimingPrefixLastRawFrame = semanticTimingPrefixLastRawFrame(trace);

        if (replayStart.hasSeededTraceState()) {
            TraceFrame seededFrame = trace.getFrame(replayStart.seededTraceIndex());
            TraceReplayBootstrap.ReplayPrimaryState seededPrimary =
                    TraceReplayBootstrap.capturePrimaryReplayStateForComparison(
                            trace, seededFrame, comparedSprite(fixture));
            EngineDiagnostics engineDiag = captureEngineDiagnostics(comparedSprite(fixture), seededFrame);
            String romDiag = combineDiagnostics(
                    seededFrame.hasExtendedData() ? seededFrame.formatDiagnostics() : "",
                    TraceEventFormatter.summariseFrameEvents(
                            trace.getEventsForFrame(replayStart.seededTraceIndex())));
            TraceCharacterState seededSidekick = captureFirstSidekickState();
            String secondaryCharacterLabel = meta.recordedSidekicks().isEmpty()
                    ? "sidekick"
                    : meta.recordedSidekicks().getFirst();

            TraceEvent.CpuState expectedSidekickCpu =
                    trace.cpuStateForFrame(seededFrame.frame(), secondaryCharacterLabel);
            TraceEvent.TailsCpuNormalStep expectedSidekickNormalStep =
                    trace.tailsCpuNormalStepForFrame(seededFrame.frame(), secondaryCharacterLabel);
            EngineSidekickCpuState actualSidekickCpu = captureFirstSidekickCpuState();
            binder.compareFrame(seededFrame,
                    seededPrimary.x(), seededPrimary.y(),
                    seededPrimary.xSpeed(), seededPrimary.ySpeed(), seededPrimary.gSpeed(),
                    seededPrimary.angle(),
                    seededPrimary.air(), seededPrimary.rolling(),
                    seededPrimary.groundMode(), romDiag,
                    engineDiag,
                    secondaryCharacterLabel,
                    seededSidekick,
                    expectedSidekickCpu,
                    actualSidekickCpu,
                    expectedSidekickNormalStep);
            compareDynamicArtIfAdvertised(
                    trace, binder, seededFrame.frame());
            recordUnmatchedHardwareCompletions(
                    fixture, binder, seededFrame.frame());
            observeFrontierAndShouldStop(frontierStopper, binder, seededFrame.frame());

            for (int frame = 0; frame <= replayStart.seededTraceIndex(); frame++) {
                for (TraceEvent event : trace.getEventsForFrame(frame)) {
                    if (event instanceof TraceEvent.Checkpoint traceCheckpoint) {
                        detector.seedCheckpoint(traceCheckpoint.name());
                    }
                }
            }
        } else if (driveTraceIndex > 0) {
            // Seed only the diagnostic checkpoint detector for trace prefix
            // frames that the replay policy intentionally starts after.
            for (int frame = 0; frame < driveTraceIndex; frame++) {
                for (TraceEvent event : trace.getEventsForFrame(frame)) {
                    if (event instanceof TraceEvent.Checkpoint traceCheckpoint) {
                        detector.seedCheckpoint(traceCheckpoint.name());
                    }
                }
            }
        } else {
            // Seed the diagnostic detector for frame-0 checkpoints without
            // changing the comparison cursor.
            for (TraceEvent event : trace.getEventsForFrame(0)) {
                if (event instanceof TraceEvent.Checkpoint traceCheckpoint) {
                    detector.seedCheckpoint(traceCheckpoint.name());
                }
            }
        }

        // Align SpriteManager.frameCounter with ROM Level_frame_counter so Tails-CPU
        // AI gates that read (Level_frame_counter & MASK) fire on the same trace
        // frames as the ROM (sonic3k.asm:26761 loc_13E7C dx-256-frame check,
        // sonic3k.asm:26775 loc_13E9C 64-frame jump-cadence check, etc.).
        //
        // The trace records each frame's gfc. The first iteration steps fc by one,
        // so for AI on iter K=K_start to see fc == T_K_start.gfc (== ROM's
        // Level_frame_counter at T_K_start logic), we pre-set fc =
        // T_(K_start-1).gfc here. The trace's gfc increments monotonically per
        // recorded gameplay frame and stays put on lag/VBLANK_ONLY frames, while
        // the engine's fc++/skip behaviour matches that exact pattern, so this
        // single pre-set keeps the counters synced for the entire replay.
        //
        // Concrete examples:
        //   * CNZ:  T_0.gfc=1   (gameplay starts immediately) → fc 0→1, then iter K=1
        //                                 step fc 1→2 = ROM.gfc(T_1)=2 ✓
        //   * AIZ:  T_289.gfc=0 (still inside intro)         → fc 0→0  (no change),
        //                                 then iter K=290 step fc 0→1 = ROM.gfc(T_290)=1 ✓
        TraceReplaySessionBootstrap.alignFrameCountersForReplayStart(
                trace,
                replayStart,
                previousDriveFrame,
                driveTraceIndex < trace.frameCount() ? trace.getFrame(driveTraceIndex) : null);
        while (driveTraceIndex < trace.frameCount()) {
            TraceFrame driveFrame = trace.getFrame(driveTraceIndex);
            fixture.beginTraceRow(driveTraceIndex, driveFrame.frame());
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previousDriveFrame, driveFrame);
            TraceReplayBootstrap.markVblankStarvedIterationForReplay(
                    previousDriveFrame, driveFrame);
            TraceReplayBootstrap.markIterationHeldIntoNextRowForReplay(
                    driveFrame,
                    driveTraceIndex + 1 < trace.frameCount()
                            ? trace.getFrame(driveTraceIndex + 1) : null);
            int validationTraceIndex = driveTraceIndex;
            int bk2Input = TraceReplayFrameClosureDriver.driveS3k(
                    phase,
                    TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace),
                    fixture::stepFrameFromRecording,
                    fixture::stepFrameFromRecordingUsingPreviousInput,
                    fixture::skipFrameFromRecording,
                    fixture::consumeRecordingFrameInputOnly,
                    fixture::advancePlayableAnimationsOnly,
                    fixture::suppressFirstSidekickAnimationOnce,
                    () -> {
                        if (shouldValidateRewindReferenceClosure(game())) {
                            TraceReplayFrameClosureDriver.validateCurrentObjectManager(
                                    () -> {
                                        var level = GameServices.levelOrNull();
                                        return level != null
                                                ? new TraceReplayFrameClosureDriver.CurrentLevelContext(
                                                        level.getObjectManager(),
                                                        level.getCurrentZone(),
                                                        level.getCurrentAct())
                                                : null;
                                    },
                                    meta.game(), meta.zone(), meta.act(),
                                    validationTraceIndex, driveFrame, phase);
                            onRewindReferenceClosureValidated(
                                    validationTraceIndex, driveFrame, phase);
                        }
                    });

            if (phase == TraceExecutionPhase.VBLANK_ONLY
                    && driveTraceIndex == replayStart.startingTraceIndex()
                    && TraceReplayBootstrap.isS3kCompleteRunHandoffCounterTickRow(trace)) {
                // The handoff row is skipped for gameplay comparison, but ROM ran a
                // full LevelLoop on it: Level_frame_counter increments before
                // Process_Sprites and Animate_Tiles runs after it
                // (sonic3k.asm:7889-7906). Mirror both native post-row effects so
                // the next driven row observes the same ROM-visible counters, with
                // no trace-gated compensation inside gameplay object code.
                TraceReplaySessionBootstrap.applyS3kCompleteRunHandoffNativePostRowEffects(trace);
            }

            if (!binder.validateInput(driveFrame, bk2Input)) {
                fail(String.format(
                    "Input alignment error at trace frame %d: " +
                    "BK2 input=0x%04X, trace input=0x%04X. " +
                    "Check bk2_frame_offset in metadata.json.",
                    driveTraceIndex, bk2Input, driveFrame.input()));
            }

            S3kCheckpointProbe probe = captureS3kProbe(driveFrame.frame(), comparedSprite(fixture));
            TraceEvent.Checkpoint engineCheckpoint = detector.observe(probe);
            // Unconditional: a dropped recorded completion belongs to the row
            // that produced it whether or not that row compares gameplay.
            recordUnmatchedHardwareCompletions(fixture, binder, driveFrame.frame());

            if (slotOccupancyProbe != null && GameServices.level() != null) {
                slotOccupancyProbe.observe(
                        driveTraceIndex, GameServices.level().getObjectManager());
            }

            if (TraceReplayBootstrap.shouldCompareGameplayStateForReplay(phase)) {
                TraceFrame comparisonExpected =
                        TraceReplayBootstrap.s3kFrameForGameplayComparison(
                                trace, driveTraceIndex, previousDriveFrame, driveFrame, phase);
                TraceReplayBootstrap.ReplayPrimaryState actualPrimary =
                        TraceReplayBootstrap.capturePrimaryReplayStateForComparison(
                                trace, comparisonExpected, comparedSprite(fixture));
                EngineDiagnostics engineDiag =
                        captureEngineDiagnostics(comparedSprite(fixture), comparisonExpected);
                String romDiag = combineDiagnostics(
                        comparisonExpected.hasExtendedData()
                                ? comparisonExpected.formatDiagnostics() : "",
                        TraceEventFormatter.summariseFrameEvents(trace.getEventsForFrame(driveTraceIndex)));
                TraceCharacterState actualSidekick = captureFirstSidekickState();
                String secondaryCharacterLabel = meta.recordedSidekicks().isEmpty()
                        ? "sidekick"
                        : meta.recordedSidekicks().getFirst();
                TraceEvent.CpuState expectedSidekickCpu =
                        trace.cpuStateForFrame(driveFrame.frame(), secondaryCharacterLabel);
                TraceEvent.TailsCpuNormalStep expectedSidekickNormalStep =
                        trace.tailsCpuNormalStepForFrame(driveFrame.frame(), secondaryCharacterLabel);
                EngineSidekickCpuState actualSidekickCpu = captureFirstSidekickCpuState();
                binder.compareFrame(comparisonExpected,
                        actualPrimary.x(), actualPrimary.y(),
                        actualPrimary.xSpeed(), actualPrimary.ySpeed(), actualPrimary.gSpeed(),
                        actualPrimary.angle(),
                        actualPrimary.air(), actualPrimary.rolling(),
                        actualPrimary.groundMode(), romDiag,
                        engineDiag,
                        secondaryCharacterLabel,
                        actualSidekick,
                        expectedSidekickCpu,
                        actualSidekickCpu,
                        expectedSidekickNormalStep);
                compareLoadQueuesIfAdvertised(trace, binder, driveFrame.frame());
                compareDynamicArtIfAdvertised(
                        trace, binder, driveFrame.frame());
                if (observeFrontierAndShouldStop(frontierStopper, binder, driveFrame.frame())) {
                    return false;
                }
            } else {
                compareDynamicArtIfAdvertised(
                        trace, binder, driveFrame.frame());
                if (observeFrontierAndShouldStop(
                        frontierStopper, binder, driveFrame.frame())) {
                    return false;
                }
            }

            TraceEvent.Checkpoint traceCheckpoint = trace.latestCheckpointAtOrBefore(driveTraceIndex);
            if (traceCheckpoint != null && traceCheckpoint.frame() == driveTraceIndex) {
                checkpointGuard.validateStrictEntry(
                        driveTraceIndex,
                        traceCheckpoint,
                        engineCheckpoint,
                        detector.requiredCheckpointNamesReached());
            }

            if (semanticTimingPrefixLastRawFrame != null) {
                if (validateSemanticTimingPrefix(
                        driveFrame.frame(), semanticTimingPrefixLastRawFrame)) {
                    fixture.closeHardwareTimingReplayPrefix(semanticTimingPrefixLastRawFrame);
                    return true;
                }
            } else if (replayTerminalReached()) {
                return false;
            }

            driveTraceIndex++;
            previousDriveFrame = driveFrame;
        }
        return false;
    }

    protected boolean validateSemanticTimingPrefix(
            int currentRawFrame, int lastPrefixRawFrame) {
        if (currentRawFrame < lastPrefixRawFrame) {
            return false;
        }
        if (currentRawFrame > lastPrefixRawFrame) {
            throw new IllegalStateException(
                    "trace replay advanced beyond semantic timing prefix boundary: "
                            + "current_raw_frame=" + currentRawFrame
                            + ", last_prefix_raw_frame=" + lastPrefixRawFrame);
        }
        return true;
    }

    /**
     * Records recorded hardware-completion edges the engine never submitted as
     * an error on the row that produced them. The edge itself released
     * nothing; only its severity and ordering change, so an earlier physics
     * divergence stays the first reported error.
     */
    /**
     * Records production submissions the recorded stream never completed as an
     * error on the closing row. Nothing was admitted or released; only the
     * severity and ordering of the complaint change, so an earlier physics
     * divergence stays the first reported error.
     */
    private static void recordPendingHardwareSubmissions(
            HeadlessTestFixture fixture, TraceBinder binder) {
        if (fixture == null) {
            return;
        }
        binder.comparePendingRecordedHardwareSubmissions(
                fixture.drainPendingRecordedHardwareSubmissions());
    }

    private static void recordUnmatchedHardwareCompletions(
            HeadlessTestFixture fixture, TraceBinder binder, int frame) {
        if (fixture == null) {
            return;
        }
        binder.compareRecordedHardwareCompletions(
                frame, fixture.drainUnmatchedRecordedHardwareCompletions());
    }

    /**
     * Compares the ROM's recorded {@code SlotMachineVariables} against the
     * engine's whenever the fixture carries the event and this session has a
     * CNZ slot manager. A no-op on every other fixture and game.
     *
     * <p>The recording has always carried this block on every row of both CNZ
     * captures and nothing read it. It is the instrument that makes a clock or
     * call-ordering error at the slot site show up as a field mismatch on the
     * frame it happens, instead of surfacing thousands of rows later as a ring
     * or speed divergence: see the 2026-08-21 tick-ownership entries in
     * docs/status/trace-frontier-log.md, where exactly that cost three rounds.
     */
    /**
     * Compares ObjB2's recorded SST against the engine's whenever the fixture
     * carries the event. Identity is by content, not by the recorded slot index:
     * the comparison runs only when exactly one tornado instance is active, so
     * no engine-slot-to-ROM-slot mapping is invented.
     */
    private static void compareS2TornadoIfRecorded(
            TraceData trace, TraceBinder binder, int frame) {
        TraceEvent.S2TornadoState expected = trace.s2TornadoStateForFrame(frame);
        if (expected == null) {
            return;
        }
        LevelManager levelManager = GameServices.levelOrNull();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }
        TornadoObjectInstance found = null;
        for (ObjectInstance instance : levelManager.getObjectManager().getActiveObjects()) {
            if (instance instanceof TornadoObjectInstance tornado) {
                if (found != null) {
                    return; // ambiguous; do not guess which one the recorder meant
                }
                found = tornado;
            }
        }
        if (found == null) {
            return;
        }
        binder.compareS2Tornado(frame, expected, found.snapshot());
    }

    private static void compareCnzSlotMachineIfRecorded(
            TraceData trace, TraceBinder binder, int frame) {
        TraceEvent.CnzSlotMachineState expected =
                trace.cnzSlotMachineStateForFrame(frame);
        if (expected == null) {
            return;
        }
        LevelManager levelManager = GameServices.levelOrNull();
        if (levelManager == null
                || !(levelManager.getZoneFeatureProvider()
                        instanceof Sonic2ZoneFeatureProvider provider)) {
            return;
        }
        CNZSlotMachineManager manager = provider.getSlotMachineManager();
        if (manager == null) {
            return;
        }
        binder.compareCnzSlotMachine(frame, expected, manager.snapshot());
    }

    private static void compareLoadQueuesIfAdvertised(
            TraceData trace, TraceBinder binder, int frame) {
        if (trace.metadata().hasPerFrameLoadQueueState()) {
            LoadQueueComparisonProjection projection =
                    LoadQueueComparisonProjection.project(
                            trace,
                            frame,
                            trace.loadQueueStatesForComparisonFrame(frame),
                            GameServices.captureQueueDiagnostics(),
                            GameServices.hardwareTiming().capture());
            binder.compareLoadQueues(
                    frame, projection.expected(), projection.actual());
        }
    }

    private static void compareDynamicArtIfAdvertised(
            TraceData trace, TraceBinder binder, int frame) {
        if (trace.metadata().hasPerFrameDynamicArtTransferState()) {
            List<TraceEvent.DynamicArtTransferState> states =
                    trace.dynamicArtTransferStates();
            if (!states.isEmpty() && frame == states.getLast().frame()) {
                return;
            }
            binder.compareDynamicArt(
                    trace.dynamicArtTransferStateForFrame(frame),
                    GameServices.captureDynamicArtDiagnostics());
        }
    }

    private static void finishDynamicArtComparison(
            TraceData trace,
            TraceBinder binder,
            com.openggf.trace.replay.TraceReplayFixture fixture,
            boolean replayCompleted) {
        // An edge belongs to the frame it is PUBLISHED on. The main-loop
        // iteration that follows the last recorded row publishes no row, so
        // whether its work belongs to this recording at all depends on what
        // came after the recording, and the recorder is the authority on that.
        //
        // A standalone trace is the tail of its recording: the capture loop
        // advances the emulator one frame past the last row and breaks
        // ("tools/tracechaser/bizhawk-headless/src/Recording/S2TraceCaptureRunner.cs":383-390),
        // leaving that iteration's callbacks buffered with no further
        // PublishRow, so PublishTerminal attaches them to the last row
        // (:520-529 FlushDynamicArtSegment -> S2DynamicArtObserver.cs:268
        // PublishTerminal, terminalForwarded=true).
        //
        // A run segment is a slice with a run gap after it. The run capture
        // loop marks an advance boundary at the top of every frame
        // ("S2RunCaptureRunner.cs":219, :441-451 PrepareDynamicArtCursor ->
        // S2DynamicArtObserver.cs:206 MarkAdvanceBoundary), and closes the
        // segment with PublishBoundaryTerminal (S2RunCaptureRunner.cs:887),
        // which forwards only the prefix buffered BEFORE the closing frame
        // began and reclassifies everything that frame produced into the
        // following gap (S2DynamicArtObserver.cs:868-899
        // ReclassifyBoundaryCallbacksAsGap). Its published outstanding set is
        // the ledger as of the boundary mark, not the current ledger.
        //
        // The corpus agrees: across all committed dynamic-art fixtures exactly
        // 15 recorded edges carry terminal_forwarded=true, all of them on the
        // final row of a standalone S2 trace, none in any run segment.
        if (replayCompleted && trace.metadata().runId() == null) {
            fixture.runTerminalDynamicArtIteration();
        }
        fixture.closeDynamicArtComparisonSegment();
        if (!replayCompleted
                || !trace.metadata()
                        .hasPerFrameDynamicArtTransferState()) {
            return;
        }
        TraceEvent.DynamicArtTransferState expected =
                trace.dynamicArtTransferStates().getLast();
        var actual = GameServices.captureDynamicArtDiagnostics();
        binder.compareDynamicArt(expected, actual);
    }

    private boolean observeFrontierAndShouldStop(
            FrontierReplayStopper frontierStopper,
            TraceBinder binder,
            int frame) {
        FrameComparison comparison = binder.comparisonForFrame(frame);
        frontierStopper.observe(comparison);
        return frontierStopper.shouldStopAfterFrame(frame);
    }

    private S3kCheckpointProbe captureS3kProbe(int replayFrame, AbstractPlayableSprite sprite) {
        var level = GameServices.levelOrNull();
        ObjectManager objectManager = level != null ? level.getObjectManager() : null;
        boolean resultsActive = objectManager != null
                && objectManager.getActiveObjects().stream()
                .anyMatch(S3kResultsScreenObjectInstance.class::isInstance);
        boolean signpostActive = objectManager != null
                && S3kSignpostInstance.activeSignpost(objectManager) != null;
        boolean eventsFg5 =
                GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager
                        && manager.isEventsFg5();
        boolean fireTransitionActive =
                GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager
                        && manager.isFireTransitionActive();
        boolean hczTransitionActive =
                Aiz2BossEndSequenceState.isCutsceneOverrideObjectsActive() && !resultsActive;
        var titleCardProvider = GameServices.module().getTitleCardProvider();
        boolean titleCardOverlayActive = titleCardProvider != null && titleCardProvider.isOverlayActive();
        Integer traceGameMode = resolveS3kTraceGameMode(titleCardOverlayActive);

        return new S3kCheckpointProbe(
                replayFrame,
                GameServices.level().getCurrentZone(),
                GameServices.level().getCurrentAct(),
                GameServices.level().getApparentAct(),
                traceGameMode,
                sprite.getMoveLockTimer(),
                sprite.isControlLocked(),
                sprite.isObjectControlled(),
                sprite.isHidden(),
                eventsFg5,
                fireTransitionActive,
                hczTransitionActive,
                signpostActive,
                resultsActive,
                GameServices.camera().isLevelStarted(),
                titleCardOverlayActive);
    }

    private Integer resolveS3kTraceGameMode(boolean titleCardOverlayActive) {
        Engine engine = Engine.getInstance();
        GameMode currentMode = engine != null ? engine.getCurrentGameMode() : null;
        boolean levelStarted = GameServices.camera() != null && GameServices.camera().isLevelStarted();
        if (currentMode == null) {
            return levelStarted ? 0x0C : 0x04;
        }
        return switch (currentMode) {
            case LEVEL, TITLE_CARD -> levelStarted ? 0x0C : 0x04;
            case SPECIAL_STAGE -> 0x10;
            case SPECIAL_STAGE_RESULTS, CONTINUE_SCREEN -> 0x14;
            case TITLE_SCREEN, MASTER_TITLE_SCREEN -> 0x00;
            case LEVEL_SELECT -> 0x08;
            case DATA_SELECT -> 0x18;
            case CREDITS_TEXT, CREDITS_DEMO, TRY_AGAIN_END, ENDING_CUTSCENE, EDITOR, BONUS_STAGE, LEGAL_DISCLAIMER -> null;
        };
    }

    /**
     * Resolve the BK2 movie for a trace. Prefers a shared, deduplicated movie named by
     * {@code metadata.source_bk2} and stored once under {@code <game>/_movies/} (sibling to the
     * per-act trace dirs) — so a complete-run movie used by 18 acts is committed once, not 18×.
     * Falls back to the retained per-directory {@code .bk2} movie placement
     * for v5 fixtures; this is not legacy trace-schema support.
     */
    private Path resolveBk2File(Path traceDir, TraceMetadata meta) throws IOException {
        if (meta != null && meta.sourceBk2() != null && !meta.sourceBk2().isBlank()) {
            Path shared = traceDir.getParent().resolve("_movies").resolve(meta.sourceBk2());
            if (Files.exists(shared)) {
                return shared;
            }
            // Run-segment placement: a segment directory of a multi-segment run
            // sits directly under the run root, and the run's single movie is
            // committed once at that root (sibling to the segment dirs and to
            // run_manifest.json) rather than in a game-level _movies/ pool.
            Path runRoot = traceDir.getParent().resolve(meta.sourceBk2());
            if (Files.exists(runRoot)) {
                return runRoot;
            }
        }
        return findBk2File(traceDir);
    }

    protected Path findBk2File(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files
                .filter(p -> p.toString().endsWith(".bk2"))
                .findFirst()
                .orElse(null);
        }
    }

    private static String formatCharacterDiagnostics(String label, TraceCharacterState state) {
        if (state == null) {
            return "";
        }
        return state.formatDiagnostics(label);
    }

    private TraceCharacterState captureFirstSidekickState() {
        SpriteManager spriteManager = GameServices.sprites();
        if (spriteManager == null || spriteManager.getSidekicks().isEmpty()) {
            return null;
        }
        return captureCharacterState(spriteManager.getSidekicks().getFirst());
    }

    private EngineSidekickCpuState captureFirstSidekickCpuState() {
        SpriteManager spriteManager = GameServices.sprites();
        if (spriteManager == null || spriteManager.getSidekicks().isEmpty()) {
            return null;
        }
        SidekickCpuController controller = spriteManager.getSidekicks().getFirst().getCpuController();
        if (controller == null) {
            return null;
        }
        return new EngineSidekickCpuState(
                controller.getDiagnosticControlCounter(),
                controller.getDiagnosticRespawnCounter(),
                controller.getDiagnosticInteractId(),
                controller.getDiagnosticRomCpuRoutine(),
                controller.targetX(),
                controller.targetY(),
                controller.getDiagnosticGeneratedHeldInput(),
                controller.getDiagnosticGeneratedPressedInput(),
                controller.getDiagnosticNormalStepHeldInput(),
                controller.getDiagnosticNormalStepPressedInput(),
                controller.getDiagnosticFollowHistorySlot(),
                controller.getDiagnosticJumpingFlag());
    }

    private TraceCharacterState captureCharacterState(AbstractPlayableSprite sprite) {
        return TraceCharacterState.fromSprite(sprite);
    }

    /**
     * Capture engine-side diagnostic state for the context window.
     * Some fields are compared by TraceBinder when the ROM trace carries the
     * matching state; the rest appear alongside ROM diagnostics for
     * cross-referencing.
     */
    private EngineDiagnostics captureEngineDiagnostics(AbstractPlayableSprite sprite, TraceFrame expected) {
        // Routine: S1 uses 0=init, 2=control, 4=hurt, 6=death
        int routine = TraceCharacterState.routineFromSprite(sprite);

        // Riding object: which SST slot is the player standing on?
        int standOnSlot = -1;
        int standOnType = -1;
        ObjectManager om = GameServices.level() != null
                ? GameServices.level().getObjectManager() : null;
        if (om != null) {
            ObjectInstance ridingObj = om.getRidingObject(sprite);
            if (ridingObj instanceof AbstractObjectInstance aoi && aoi.getSlotIndex() >= 0) {
                standOnSlot = aoi.getSlotIndex();
                standOnType = aoi.getSpawn() != null ? aoi.getSpawn().objectId() : -1;
            }
        }

        // Ring count
        int rings = sprite.getRingCount();

        // Status byte (replicate ROM's status encoding)
        int statusByte = TraceCharacterState.statusByteFromSprite(sprite);

        // Camera X/Y for ROM-trace cross-reference and camera_x/camera_y
        // comparison in TraceBinder.
        int camX = GameServices.camera() != null ? GameServices.camera().getX() & 0xFFFF : -1;
        int camY = GameServices.camera() != null ? GameServices.camera().getY() & 0xFFFF : -1;

        // Placement cursor state for ROMâ†”engine comparison
        int cursorIdx = -1, leftCursorIdx = -1, fwdCtr = -1, bwdCtr = -1;
        if (om != null) {
            int[] cursor = om.getPlacementCursorState();
            if (cursor != null) {
                cursorIdx = cursor[0];
                leftCursorIdx = cursor[1];
                fwdCtr = cursor[2];
                bwdCtr = cursor[3];
            }
        }

        // Subpixels for cross-referencing with ROM trace sub=(xsub,ysub)
        int xSub = sprite.getXSubpixelRaw();
        int ySub = sprite.getYSubpixelRaw();

        // Tri-state engine truth for "is this player riding any solid object?"
        // and the latched standing snapshot. These diverge from statusByte
        // bit 0x08 (live on-object flag) on platform release / walk-off /
        // mid-frame standing transitions, and are critical for diagnosing
        // late-game frontiers like WFZ f7065 where ROM transitions to
        // airborne one frame before engine drops the ride.
        int ridingObject = -1;
        int standingSnapshot = -1;
        if (om != null) {
            ridingObject = om.isRidingObject(sprite) ? 1 : 0;
            standingSnapshot = om.latestStandingSnapshot(sprite) ? 1 : 0;
        }

        String solidEvent = "";
        solidEvent = combineDiagnostics(solidEvent, String.format(
                "eng-player anim=%02X frame=%02X tick=%02X vel=(%04X,%04X) g=%04X dir=%s bal=%d rj=%s jump=%s shoes=%s accel=%04X max=%04X lock=%s moveLock=%d objCtrl=%s objSup=%s in=(%s,%s,%s,%s) look=%04X bias=%04X",
                sprite.getAnimationId() & 0xFF,
                sprite.getMappingFrame() & 0xFF,
                sprite.getAnimationTick() & 0xFF,
                sprite.getXSpeed() & 0xFFFF,
                sprite.getYSpeed() & 0xFFFF,
                sprite.getGSpeed() & 0xFFFF,
                sprite.getDirection(),
                sprite.getBalanceState(),
                sprite.getRollingJump(),
                sprite.isJumping(),
                sprite.hasSpeedShoes(),
                sprite.getRunAccel() & 0xFFFF,
                sprite.getMax() & 0xFFFF,
                sprite.isControlLocked(),
                sprite.getMoveLockTimer(),
                sprite.isObjectControlled(),
                sprite.isObjectControlSuppressesMovement(),
                sprite.isDownPressed(),
                sprite.isLeftPressed(),
                sprite.isRightPressed(),
                sprite.isJumpPressed(),
                sprite.getLookDelayCounter() & 0xFFFF,
                GameServices.camera() != null ? GameServices.camera().getYPosBias() & 0xFFFF : -1));
        solidEvent = combineDiagnostics(solidEvent, summariseGroundProbeDiagnostics("eng-ground", sprite));
        if (om != null) {
            TouchResponseDebugState touchState = om.getTouchResponseDebugState();
            if (touchState != null) {
                solidEvent = combineDiagnostics(solidEvent, String.format(
                        "touchBox @%04X,%04X h=%d yr=%d crouch=%d",
                        touchState.getPlayerX() & 0xFFFF,
                        touchState.getPlayerY() & 0xFFFF,
                        touchState.getPlayerHeight(),
                        touchState.getPlayerYRadius(),
                        touchState.isCrouching() ? 1 : 0));
            }
            if (touchState != null && !touchState.getHits().isEmpty()) {
                solidEvent = combineDiagnostics(solidEvent,
                        TouchResponseDebugHitFormatter.summariseOverlaps(touchState.getHits()));
                solidEvent = combineDiagnostics(solidEvent,
                        TouchResponseDebugHitFormatter.summariseNearbyScans(
                                touchState.getHits(),
                                sprite.getCentreX(),
                                sprite.getCentreY()));
            }

            List<EngineNearbyObject> nearbyObjects = captureEngineNearbyObjects(sprite);
            solidEvent = combineDiagnostics(solidEvent,
                    EngineNearbyObjectFormatter.summarise(nearbyObjects));
            solidEvent = combineDiagnostics(solidEvent,
                    summariseExpectedOnObjectSlot(om, expected, sprite));
            solidEvent = combineDiagnostics(solidEvent,
                    S2SkyChaseBadnikDiagnostics.summarise(game(), zone(), om));
            solidEvent = combineDiagnostics(solidEvent, summariseSidekickStateDiagnostics(om));
            solidEvent = combineDiagnostics(solidEvent, summariseSidekickNearbyObjects(om));
            solidEvent = combineDiagnostics(solidEvent, summariseSidekickCpuDiagnostics());
            solidEvent = combineDiagnostics(solidEvent, S3kSidekickCylinderDiagnostics.summarise(om));
            solidEvent = combineDiagnostics(solidEvent, additionalEngineObjectDiagnostics(om));
        }

        // Life count for the recorded life_count column. GameServices.gameStateOrNull()
        // is null outside a gameplay session, in which case -1 makes TraceBinder
        // raise lives_present rather than dropping the comparison.
        int lives = GameServices.gameStateOrNull() != null
                ? GameServices.gameStateOrNull().getLives()
                : EngineDiagnostics.LIVES_ABSENT;

        return new EngineDiagnostics(routine, standOnSlot, standOnType, rings, statusByte,
                camX, camY, cursorIdx, leftCursorIdx, fwdCtr, bwdCtr, solidEvent, xSub, ySub,
                ridingObject, standingSnapshot, sprite.getAnimationId(), sprite.getMappingFrame(),
                lives);
    }

    private List<TraceEvent.ObjectNear> objectNearEventsForPrimary(TraceData trace, int frame) {
        List<TraceEvent.ObjectNear> events = new ArrayList<>();
        for (TraceEvent event : trace.getEventsForFrame(frame)) {
            if (event instanceof TraceEvent.ObjectNear near
                    && (near.character() == null || near.character().isBlank()
                    || "sonic".equalsIgnoreCase(near.character()))
                    && shouldCompareObjectNearEvent(near)) {
                events.add(near);
            }
        }
        return events;
    }

    private List<EngineNearbyObject> captureEngineNearbyObjects(AbstractPlayableSprite sprite) {
        ObjectManager om = GameServices.level() != null
                ? GameServices.level().getObjectManager() : null;
        return TraceReplayDiagnostics.buildNearbyObjects(om, sprite, 160, false);
    }

    private String summariseExpectedOnObjectSlot(ObjectManager om, TraceFrame expected, AbstractPlayableSprite sprite) {
        if (om == null || expected == null || expected.standOnObj() <= 0) {
            return "";
        }
        int expectedSlot = expected.standOnObj();
        for (ObjectInstance instance : om.getActiveObjects()) {
            if (!(instance instanceof AbstractObjectInstance aoi) || aoi.getSlotIndex() != expectedSlot) {
                continue;
            }
            ObjectSpawn spawn = aoi.getSpawn();
            int objectId = spawn != null ? spawn.objectId() : -1;
            int dx = aoi.getX() - sprite.getCentreX();
            int dy = aoi.getY() - sprite.getCentreY();
            String details = aoi.traceDebugDetails();
            return String.format("eng-expected-onObj s%02X 0x%02X %s @%04X,%04X d=(%d,%d)%s",
                    expectedSlot,
                    objectId & 0xFF,
                    aoi.getName(),
                    aoi.getX() & 0xFFFF,
                    aoi.getY() & 0xFFFF,
                    dx,
                    dy,
                    details == null || details.isBlank() ? "" : " " + details);
        }
        return String.format("eng-expected-onObj s%02X missing", expectedSlot);
    }

    private String summariseSidekickStateDiagnostics(ObjectManager om) {
        SpriteManager spriteManager = GameServices.sprites();
        if (spriteManager == null || spriteManager.getSidekicks().isEmpty()) {
            return "eng-tails-state none sidekick=inactive";
        }
        AbstractPlayableSprite sidekick = spriteManager.getSidekicks().getFirst();
        int standOnSlot = -1;
        int standOnType = -1;
        ObjectInstance ridingObj = om.getRidingObject(sidekick);
        if (ridingObj instanceof AbstractObjectInstance aoi && aoi.getSlotIndex() >= 0) {
            standOnSlot = aoi.getSlotIndex();
            standOnType = aoi.getSpawn() != null ? aoi.getSpawn().objectId() : -1;
        }
        var cam = sidekick.currentCamera();
        int camMinY = cam != null ? cam.getMinY() & 0xFFFF : -1;
        int camMaxY = cam != null ? cam.getMaxY() & 0xFFFF : -1;
        int camMaxYTarget = cam != null ? cam.getMaxYTarget() & 0xFFFF : -1;
        int cpuMaxY = sidekick.getCpuController() != null
                ? sidekick.getCpuController().getMaxYBound(camMaxY) & 0xFFFF
                : -1;
        int interactSlot = sidekick.getInteractSlotIndex();
        String interactOccupant = summariseSlotOccupant(om, interactSlot);
        String renderFlagDiag = summariseSidekickRenderFlagDiagnostics(sidekick);
        String groundProbeDiag = summariseGroundProbeDiagnostics("ground", sidekick);
        return String.format(
                "eng-tails-state pos=(%04X,%04X) sub=(%04X,%04X) vel=(%04X,%04X) g=%04X dir=%s onObj=%s ride=s%d type=%02X interact=s%d[%s] st=%02X rtn=%02X dead=%s pin=%s lock=%s prs=%s boundsY=%04X/%04X/%04X cpuMax=%04X wrap=%s %s %s",
                sidekick.getCentreX() & 0xFFFF,
                sidekick.getCentreY() & 0xFFFF,
                sidekick.getXSubpixelRaw() & 0xFFFF,
                sidekick.getYSubpixelRaw() & 0xFFFF,
                sidekick.getXSpeed() & 0xFFFF,
                sidekick.getYSpeed() & 0xFFFF,
                sidekick.getGSpeed() & 0xFFFF,
                sidekick.getDirection(),
                sidekick.isOnObject(),
                standOnSlot,
                standOnType & 0xFF,
                interactSlot,
                interactOccupant,
                TraceCharacterState.statusByteFromSprite(sidekick),
                TraceCharacterState.routineFromSprite(sidekick),
                sidekick.getDead(),
                sidekick.getPinballMode(),
                sidekick.getPinballSpeedLock(),
                sidekick.shouldPreserveRollingOnNextRollStop(),
                camMinY,
                camMaxY,
                camMaxYTarget,
                cpuMaxY,
                cam != null && cam.isVerticalWrapEnabled(),
                renderFlagDiag,
                groundProbeDiag);
    }

    private String summariseGroundProbeDiagnostics(String label, AbstractPlayableSprite sprite) {
        Sensor[] sensors = sprite.getGroundSensors();
        if (sensors == null || sensors.length < 2) {
            return label + " none";
        }
        return String.format(
                "%s gm=%s angle=%02X L=%s R=%s",
                label,
                sprite.getGroundMode(),
                sprite.getAngle() & 0xFF,
                formatGroundProbe(sensors[0]),
                formatGroundProbe(sensors[1]));
    }

    private String formatGroundProbe(Sensor sensor) {
        if (sensor == null) {
            return "missing";
        }
        SensorResult result = sensor.getCurrentResult();
        if (result == null) {
            return String.format("off=(%d,%d) inactive=%s result=none",
                    sensor.getX(),
                    sensor.getY(),
                    !sensor.isActive());
        }
        return String.format(
                "off=(%d,%d) dist=%d ang=%02X tile=%04X dir=%s",
                sensor.getX(),
                sensor.getY(),
                (int) result.distance(),
                result.angle() & 0xFF,
                result.tileId() & 0xFFFF,
                result.direction());
    }

    private String summariseSidekickRenderFlagDiagnostics(AbstractPlayableSprite sidekick) {
        var cam = sidekick.currentCamera();
        if (cam == null) {
            return "rf=cam-none";
        }
        int rawCamX = cam.getX();
        int rawCamY = cam.getY();
        int renderCamX = cam.getXWithShake();
        int renderCamY = cam.getYWithShake();
        int relX = sidekick.getRenderCentreX() - renderCamX;
        int relY = sidekick.getRenderCentreY() - renderCamY;
        return String.format(
                "rf=%s/%s inv=%02X hurt=%s hidden=%s refresh=%s rc=(%04X,%04X) wh=%02X/%02X camRaw=(%04X,%04X) camCopy=(%04X,%04X) shake=(%d,%d) rel=(%d,%d) calc=%s",
                sidekick.isRenderFlagOnScreen(),
                sidekick.hasRenderFlagOnScreenState(),
                sidekick.getInvulnerableFrames() & 0xFF,
                sidekick.isHurt(),
                sidekick.isHidden(),
                sidekick.shouldRefreshRenderFlagThisFrame(),
                sidekick.getRenderCentreX() & 0xFFFF,
                sidekick.getRenderCentreY() & 0xFFFF,
                sidekick.getRenderFlagWidthPixels() & 0xFF,
                sidekick.getRenderFlagWidthPixels() & 0xFF,
                rawCamX & 0xFFFF,
                rawCamY & 0xFFFF,
                renderCamX & 0xFFFF,
                renderCamY & 0xFFFF,
                cam.getShakeOffsetX(),
                cam.getShakeOffsetY(),
                relX,
                relY,
                cam.isVisibleForRenderFlag(sidekick));
    }

    private String summariseSlotOccupant(ObjectManager om, int slot) {
        if (om == null || slot < 0) {
            return "empty";
        }
        for (ObjectInstance instance : om.getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance aoi
                    && aoi.getSlotIndex() == slot
                    && !instance.isDestroyed()
                    && instance.getSpawn() != null) {
                return String.format("%02X@%04X,%04X/%s",
                        instance.getSpawn().objectId() & 0xFF,
                        instance.getX() & 0xFFFF,
                        instance.getY() & 0xFFFF,
                        instance.getClass().getSimpleName());
            }
        }
        return "empty";
    }

    private String summariseSidekickNearbyObjects(ObjectManager om) {
        SpriteManager spriteManager = GameServices.sprites();
        if (spriteManager == null || spriteManager.getSidekicks().isEmpty()) {
            return "eng-tails-near none sidekick=inactive";
        }
        AbstractPlayableSprite sidekick = spriteManager.getSidekicks().getFirst();
        List<EngineNearbyObject> nearbyObjects =
                TraceReplayDiagnostics.buildNearbyObjects(om, sidekick, 192, false);
        String summary = EngineNearbyObjectFormatter.summarise(nearbyObjects);
        return summary.isEmpty() ? "eng-tails-near none" : "eng-tails-near " + summary;
    }

    protected String additionalEngineObjectDiagnostics(ObjectManager om) {
        return "";
    }

    private String summariseSidekickCpuDiagnostics() {
        SpriteManager spriteManager = GameServices.sprites();
        if (spriteManager == null || spriteManager.getSidekicks().isEmpty()) {
            return "eng-tails-cpu none sidekick=inactive";
        }
        AbstractPlayableSprite sidekick = spriteManager.getSidekicks().getFirst();
        if (sidekick.getCpuController() == null) {
            return "eng-tails-cpu none controller=missing";
        }
        return sidekick.getCpuController().formatLatestNormalStepDiagnostics();
    }

    private void writeReport(DivergenceReport report, TraceMetadata meta) throws IOException {
        String prefix = meta.game() + "_" + meta.zone() + meta.act();
        TraceVerificationScope scope = verificationScope();
        String scopeSuffix = scope == TraceVerificationScope.ALL
                ? ""
                : "_" + scope.name().toLowerCase();
        TraceReportWriter.writeReport(reportOutputDir(), report, "trace",
                SessionInvocationExtension.SessionInvocation.current(),
                "single", prefix + scopeSuffix, scope,
                TraceReplayConsole.contextRadius());
    }

}
