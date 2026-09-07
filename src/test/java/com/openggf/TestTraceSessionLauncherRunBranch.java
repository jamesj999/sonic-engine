package com.openggf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.control.InputHandler;
import com.openggf.game.CheckpointState;
import com.openggf.game.GameId;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.LevelGamestate;
import com.openggf.game.profiles.trace.TracePlaybackProfile;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestTempFiles;
import com.openggf.testmode.TraceRunFailureStatus;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.runs.DestinationAdmissionReceipt;
import com.openggf.trace.replay.runs.RunBoundarySignal;
import com.openggf.trace.replay.runs.RunLevelLoadCause;
import com.openggf.trace.replay.runs.RunPlaybackObservation;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import com.openggf.trace.replay.runs.TraceRunFrameDriver;
import com.openggf.trace.replay.runs.TraceRunFrameDriver.Disposition;
import com.openggf.trace.replay.runs.TraceRunFrameDriver.Hooks;
import com.openggf.trace.replay.runs.TraceRunFrameDriver.Step;
import com.openggf.trace.replay.runs.TraceRunExternalDiagnostics;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunReferencePlanLoader;
import com.openggf.trace.replay.runs.TraceStructuralRowComparator;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRowDriver;
import com.openggf.trace.replay.runs.TraceRunVblankClock;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage for the visual multi-stage trace-run adapter. It proves
 * the production launcher translates shared coordinator actions identically
 * to the headless policy harness, and retains focused compatibility coverage
 * for {@link RunSegmentAdvancer}. Synthetic fixtures exercise level, bonus,
 * and special-stage segment plans without requiring a ROM.
 */
// Minutes-long oracle sweep: excluded from the -Psmoke fast lane, still run by
// the default suite on pull requests, the nightly schedule and release validation.
@Tag("slow-suite")
class TestTraceSessionLauncherRunBranch {

    private static final Path S1_EMERALD_RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s1", "runs",
            "s1-sonic-complete-withemeralds");
    private List<TraceRunReplayWalker.SegmentPlan> segments;
    private Path runDir;
    private Path specialStageRunDir;
    private Path canonicalEmeraldRunDir;

    @BeforeEach
    void loadFixture() throws Exception {
        Path root = TestTempFiles.createTempDirectory("trace-run-launcher-v5");
        runDir = TraceV5RunFixture.writeS3kBonusRun(root.resolve("s3k"));
        specialStageRunDir = TraceV5RunFixture.writeS2SpecialStageRun(root.resolve("s2"));
        canonicalEmeraldRunDir = canonicalizeInstalledRun(
                S1_EMERALD_RUN_DIR, root.resolve("s1"));
        TraceRunManifest run = TraceRunManifest.load(runDir.resolve("run_manifest.json"));
        segments = TraceRunReferencePlanLoader.load(run, runDir);
    }

    @AfterEach
    void clearSession() {
        Engine.clearGlobalInstance();
        GameServices.playbackDebug().endSession();
        TraceRunFailureStatus.clear();
        SessionManager.clear();
    }

    @Test
    void failedRunLaunchDoesNotLeakRecordedPolicyIntoNextGameplayContext() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.armNextGameplayAdmissionPolicy(
                HardwareReadinessAdmissionPolicy.RECORDED);

        TraceSessionLauncher.restoreFailedLaunch(null, false);
        var next = SessionManager.openGameplaySession(new Sonic2GameModule());

        assertEquals(HardwareReadinessAdmissionPolicy.LIVE,
                next.hardwareTiming().admissionPolicy());
    }

    @Test
    void levelLaunchPresentsTitleCardBeforeInstallingReplayState()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        List<String> events = new ArrayList<>();
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);

        session.beginTitleCardPresentation(
                new TraceSessionLauncher.TitleCardPresentation() {
                    @Override
                    public void prepareLevel() {
                        events.add("prepare-level-and-team");
                    }

                    @Override
                    public void enterTitleCard() {
                        events.add("enter-title-card");
                    }
                });

        assertEquals(List.of(
                "prepare-level-and-team", "enter-title-card"), events);
        assertSame(session, TraceSessionLauncher.active());
        assertTrue(session.isPresentingTitleCard());
        assertNull(getField(session, "fixture"));
        assertNull(getField(session, "comparator"));
        assertNull(getField(session, "runDynamicArtSegments"));
        assertFalse(GameServices.playbackDebug().isSessionPlaying(),
                "title-card presentation must not start playback");

        session.requestEarlyExit();
        assertNull(TraceSessionLauncher.active(),
                "Escape ownership must work before a comparator exists");
    }

    @Test
    void visualReplayActivationCannotReloadLevelOrRestartMusic() throws Exception {
        String launcher = Files.readString(Path.of("src", "main", "java",
                "com", "openggf", "TraceSessionLauncher.java"));
        String driver = Files.readString(Path.of("src", "main", "java",
                "com", "openggf", "trace", "replay", "TraceReplayDriver.java"));
        int preparedStart = driver.indexOf("public void startPreparedLevel()");
        int sharedStart = driver.indexOf("private void startPlayback(", preparedStart);
        assertTrue(preparedStart >= 0 && sharedStart > preparedStart);
        String preparedPath = driver.substring(preparedStart, sharedStart);
        assertTrue(launcher.contains("driver.startPreparedLevel();"));
        assertFalse(launcher.contains("reopenCurrentGameplayForVisualTrace"));
        int installStart = launcher.indexOf("private void installRunComparator(");
        int installEnd = launcher.indexOf(
                "private void adoptRunDestinationProductionIterationOwner(",
                installStart);
        String destinationInstall = launcher.substring(installStart, installEnd);
        assertFalse(destinationInstall.contains("startSession("),
                "destination admission must retain the one run timeline");
        int acceptedStart = launcher.indexOf(
                "boolean scheduleAcceptedRunLevelDestinationIfNeeded(");
        int acceptedEnd = launcher.indexOf(
                "public static boolean activateScheduledPlaybackForLoadedLevel(",
                acceptedStart);
        assertFalse(launcher.substring(acceptedStart, acceptedEnd)
                        .contains("scheduleSessionAtNextLevelLoad("),
                "an accepted load must not schedule a destination seek");
        assertFalse(preparedPath.contains("loadZoneAndAct("));
        assertFalse(preparedPath.contains("resetLevelSubsystemsForReplay("));
        assertFalse(preparedPath.contains("registerActiveTeam("));
        assertFalse(preparedPath.contains("playMusic("));
    }

    @Test
    void realVisualLauncherAndHeadlessPolicyEmitSameCoordinatorTranscript() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        new Engine(EngineServices.current());

        TraceRunManifest.Segment level = segments.get(0).segment();
        TraceRunManifest.Segment bonus = new TraceRunManifest.Segment(
                "seg01_gumball", "bonus_stage", "s3k_bonus_stage",
                1900, 2, 19, 1, null, "gumball");
        TraceRunManifest.Transition transition =
                new TraceRunManifest.Transition(
                        0, 1, "starpost_bonus", 1750,
                        2, null, null, null, null, null, null, null);
        TraceRunManifest run = new TraceRunManifest(
                "s3k", "visual-headless-parity", "synthetic.bk2",
                "checksum", List.of(level, bonus),
                List.of(transition));
        List<TraceRunReplayWalker.SegmentPlan> twoSegments = List.of(
                new TraceRunReplayWalker.SegmentPlan(
                        level, segments.get(0).trace(), null, transition),
                new TraceRunReplayWalker.SegmentPlan(
                        bonus, segments.get(1).trace(), transition, null));
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-run.bk2"), "logkey", Map.of(),
                java.util.stream.IntStream.range(0, 1902)
                        .mapToObj(TestTraceSessionLauncherRunBranch::frame)
                        .toList(), 3);

        TraceSessionLauncher visual =
                TestRunPayloads.session(null, movie, twoSegments, null);
        TraceRunPlaybackCoordinator visualCoordinator =
                new TraceRunPlaybackCoordinator(
                        run, TracePlaybackProfile.DISABLED, movie.getFrameCount());
        setField(visual, "runCoordinator", visualCoordinator);
        setField(visual, "runBoundaryProbe", new TraceRunReplayWalker.BoundaryProbe(
                new TraceRunReplayWalker.EngineHooks() {
                    @Override
                    public int currentBk2Frame() {
                        return 1750;
                    }

                    @Override
                    public com.openggf.game.BonusStageType peekBonusRequest() {
                        return com.openggf.game.BonusStageType.GUMBALL;
                    }

                    @Override
                    public boolean isSpecialStageRequested() {
                        return false;
                    }

                    @Override
                    public GameMode currentMode() {
                        return GameMode.BONUS_STAGE;
                    }
                }));
        GameServices.playbackDebug().startSession(movie, 500);

        List<TraceRunPlaybackCoordinator.Action> headless =
                driveCanonicalPolicy(new TraceRunPlaybackCoordinator(
                        run, TracePlaybackProfile.DISABLED, movie.getFrameCount()));
        driveCanonicalVisual(visual, visualCoordinator);

        assertEquals(headless, visual.runCoordinatorTranscript());
        assertEquals(List.of(
                        "AdmitDestination", "CloseSegment", "EnterTransitionGap",
                        "AdmitDestination", "CloseSegment", "CompleteRun"),
                headless.stream()
                        .map(action -> action.getClass().getSimpleName())
                        .toList());
    }

    @Test
    void loadInsideSourceIterationKeepsSourceOwnerAndDestinationRowZero()
            throws Exception {
        RunPlaybackObservation sourceOwner = new RunPlaybackObservation(
                GameMode.LEVEL, 129, 8,
                new RunPlaybackObservation.LevelIdentity(10, 0, 0, 0),
                false, null, null, true, false, 0, false, 4, 5);
        RunPlaybackObservation postStepDestination = new RunPlaybackObservation(
                GameMode.LEVEL, 130, 9,
                new RunPlaybackObservation.LevelIdentity(11, 1, 1, 0),
                false, null, null, false, true, 1, false, 6, 7);
        Method method = TraceSessionLauncher.class.getDeclaredMethod(
                "withProductionOwner",
                RunPlaybackObservation.class, RunPlaybackObservation.class);
        method.setAccessible(true);

        RunPlaybackObservation published = (RunPlaybackObservation) method.invoke(
                null, postStepDestination, sourceOwner);

        assertEquals(sourceOwner.level(), published.level());
        assertEquals(GameMode.LEVEL, published.mode());
        assertTrue(published.currentSegmentExhausted());
        assertEquals(130, published.sharedBk2Cursor());
        assertEquals(9, published.admittedStepOrdinal());
        assertEquals(0, published.destinationRowsConsumed(),
                "a remembered load inside source production has not consumed "
                        + "destination row zero");
    }

    @Test
    void captureObservationReportsPendingInitialTitleCard() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);
        GameServices.level().requestTitleCard(0, 1);

        RunPlaybackObservation observation = captureObservation(
                session, GameMode.LEVEL);

        assertTrue(observation.initialTitleCardPending());
    }

    @Test
    void productionOwnerPinKeepsDestinationTitleCardBarrier()
            throws Exception {
        RunPlaybackObservation sourceOwner = new RunPlaybackObservation(
                GameMode.LEVEL, 129, 8,
                new RunPlaybackObservation.LevelIdentity(10, 0, 0, 0),
                false, null, null, true, false, 0, false, 4, 5);
        RunPlaybackObservation postStepDestination = new RunPlaybackObservation(
                GameMode.LEVEL, 130, 9,
                new RunPlaybackObservation.LevelIdentity(11, 0, 0, 1),
                true, null, null, false, true, 0, false, 6, 7);
        Method method = TraceSessionLauncher.class.getDeclaredMethod(
                "withProductionOwner",
                RunPlaybackObservation.class, RunPlaybackObservation.class);
        method.setAccessible(true);

        RunPlaybackObservation published = (RunPlaybackObservation) method.invoke(
                null, postStepDestination, sourceOwner);

        assertEquals(sourceOwner.level(), published.level());
        assertEquals(GameMode.LEVEL, published.mode());
        assertTrue(published.initialTitleCardPending(),
                "source identity pinning must preserve the live destination barrier");
    }

    @Test
    void pendingInitialTitleCardKeepsVisualDestinationOwnersClosed()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        new Engine(EngineServices.current());

        TraceRunManifest.Segment source = segments.getFirst().segment();
        TraceRunManifest.Segment destination = new TraceRunManifest.Segment(
                "seg01_next_act", "level", "complete_run",
                600, source.traceFrameCount(), 0, 2, null, null);
        TraceRunManifest.Transition transition = new TraceRunManifest.Transition(
                0, 1, "level_advance", 502,
                null, null, null, null, null, null, null, null);
        TraceRunManifest run = new TraceRunManifest(
                "s2", "visual-title-card-barrier", "synthetic.bk2",
                "checksum", List.of(source, destination),
                List.of(transition));
        List<TraceRunReplayWalker.SegmentPlan> twoLevels = List.of(
                new TraceRunReplayWalker.SegmentPlan(
                        source, segments.getFirst().trace(), null, transition),
                new TraceRunReplayWalker.SegmentPlan(
                        destination, segments.getFirst().trace(), transition, null));
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-run.bk2"), "logkey", Map.of(),
                List.of(frame(500), frame(600)), 3);
        TraceSessionLauncher session =
                TestRunPayloads.session(null, movie, twoLevels, null);
        RecordingTimingFixture fixture = new RecordingTimingFixture(context);
        TraceRunPlaybackCoordinator coordinator =
                new TraceRunPlaybackCoordinator(
                        run, TracePlaybackProfile.DISABLED, movie.getFrameCount());
        TraceRunReplayWalker.BoundaryProbe sourceBoundaryProbe =
                new TraceRunReplayWalker.BoundaryProbe(
                        new TraceRunReplayWalker.EngineHooks() {
                            @Override
                            public int currentBk2Frame() {
                                return GameServices.playbackDebug().getCursorFrame();
                            }

                            @Override
                            public com.openggf.game.BonusStageType peekBonusRequest() {
                                return com.openggf.game.BonusStageType.NONE;
                            }

                            @Override
                            public boolean isSpecialStageRequested() {
                                return false;
                            }

                            @Override
                            public GameMode currentMode() {
                                return GameMode.LEVEL;
                            }
                        });
        setField(session, "fixture", fixture);
        setField(session, "runCoordinator", coordinator);
        setField(session, "runBoundaryProbe", sourceBoundaryProbe);
        setField(session, "runHardwareTiming",
                new TraceRunReplayWalker.HardwareTimingCoordinator(
                        fixture,
                        TestRunPayloads.hardwareTimingSegments(twoLevels)));
        session.installRunDynamicArtSegments(context);
        GameServices.playbackDebug().setFrameObserver(sourceBoundaryProbe);

        RunPlaybackObservation sourceActive = new RunPlaybackObservation(
                GameMode.LEVEL, 500, 0,
                new RunPlaybackObservation.LevelIdentity(10, 0, 0, 0),
                false, null, null, false, false, 0, false, 0, 0);
        appendCoordinatorTranscript(session,
                coordinator.activateInitialLevel(sourceActive));
        LiveTraceComparator sourceComparator = new LiveTraceComparator(
                twoLevels.getFirst().trace(), ToleranceConfig.DEFAULT, 0,
                () -> null, null);
        setField(session, "comparator", sourceComparator);
        sourceBoundaryProbe.setDelegate(sourceComparator);
        GameServices.playbackDebug().startSession(movie, 500);
        int sourceCursor = GameServices.playbackDebug().getCursorFrame();

        GameServices.level().requestTitleCard(0, 2);
        boolean titleCardPending = captureObservation(session, GameMode.LEVEL)
                .initialTitleCardPending();
        RunPlaybackObservation destinationPending = new RunPlaybackObservation(
                GameMode.LEVEL, sourceCursor, 1,
                new RunPlaybackObservation.LevelIdentity(11, 0, 0, 1),
                titleCardPending, null, null, false, false, 0, false, 0, 0);
        RunBoundarySignal.LevelLoaded loaded = new RunBoundarySignal.LevelLoaded(
                sourceCursor, RunLevelLoadCause.LEVEL_ADVANCE,
                destinationPending.level());
        assertTrue(coordinator.beforeLoadedLevelActivation(
                loaded, destinationPending).isEmpty());
        RunPlaybackObservation sourceExhausted = new RunPlaybackObservation(
                GameMode.LEVEL, sourceCursor, 2, sourceActive.level(),
                titleCardPending, null, null, false, true, 0, false, 0, 0);
        applyCoordinatorActions(session,
                coordinator.afterProduction(sourceExhausted));
        applyCoordinatorActions(session,
                coordinator.beforeAdmission(destinationPending));

        assertNull(getField(GameServices.playbackDebug(), "frameObserver"));
        assertNull(getField(sourceBoundaryProbe, "delegate"));
        assertNull(getField(session, "comparator"));
        assertTrue(fixture.handoffs.isEmpty());
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        assertEquals(sourceCursor,
                GameServices.playbackDebug().getCursorFrame());
        assertEquals(List.of(0), session.runCoordinatorTranscript().stream()
                .filter(TraceRunPlaybackCoordinator.AdmitDestination.class::isInstance)
                .map(TraceRunPlaybackCoordinator.AdmitDestination.class::cast)
                .map(action -> action.receipt().segmentIndex())
                .toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void destinationAdmissionInsideProductionTransfersPublisherAtComparatorCursor(
            int rowsConsumed)
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        new Engine(EngineServices.current());

        TraceData sourceTrace = dynamicArtTrace(2);
        TraceData destinationTrace = dynamicArtTrace(3);
        TraceRunManifest.Segment source = new TraceRunManifest.Segment(
                "seg00_source", "level", "complete_run",
                0, 2, 0, 1, null, null);
        TraceRunManifest.Segment destination = new TraceRunManifest.Segment(
                "seg01_destination", "level", "complete_run",
                2, 3, 0, 2, null, null);
        TraceRunManifest.Transition transition = new TraceRunManifest.Transition(
                0, 1, "level_advance", 1,
                null, null, null, null, null, null, null, null);
        List<TraceRunReplayWalker.SegmentPlan> twoLevels = List.of(
                new TraceRunReplayWalker.SegmentPlan(
                        source, sourceTrace, null, transition),
                new TraceRunReplayWalker.SegmentPlan(
                        destination, destinationTrace, transition, null));
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-run.bk2"), "logkey", Map.of(),
                List.of(frame(0), frame(1), frame(2), frame(3), frame(4)), 3);
        TraceSessionLauncher session =
                TestRunPayloads.session(null, movie, twoLevels, null);
        TraceRunReplayWalker.BoundaryProbe boundaryProbe =
                new TraceRunReplayWalker.BoundaryProbe(
                        new TraceRunReplayWalker.EngineHooks() {
                            @Override
                            public int currentBk2Frame() {
                                return GameServices.playbackDebug().getCursorFrame();
                            }

                            @Override
                            public com.openggf.game.BonusStageType peekBonusRequest() {
                                return com.openggf.game.BonusStageType.NONE;
                            }

                            @Override
                            public boolean isSpecialStageRequested() {
                                return false;
                            }

                            @Override
                            public GameMode currentMode() {
                                return GameMode.LEVEL;
                            }
                        });
        setField(session, "runBoundaryProbe", boundaryProbe);
        session.installRunDynamicArtSegments(context);

        LiveTraceComparator sourceComparator = new LiveTraceComparator(
                sourceTrace, ToleranceConfig.DEFAULT, 0, () -> null);
        setField(session, "comparator", sourceComparator);
        boundaryProbe.setDelegate(sourceComparator);
        session.beforeProductionIteration();
        sourceComparator.afterFrameAdvanced(frame(0), false);
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        session.afterProductionIteration();
        assertEquals(1, sourceComparator.cursor());

        TraceRunReplayWalker.DynamicArtSegmentController segmentsController =
                (TraceRunReplayWalker.DynamicArtSegmentController)
                        getField(session, "runDynamicArtSegments");
        segmentsController.enterGap();
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        closeRunSegment(session, 0);

        session.beforeProductionIteration();
        GameServices.playbackDebug().startSession(
                movie, 2 + rowsConsumed);
        applyRunDestinationAdmission(session, new DestinationAdmissionReceipt(
                1, DestinationAdmissionReceipt.InputClock.SHARED,
                2 + rowsConsumed, rowsConsumed,
                new DestinationAdmissionReceipt.LevelIdentity(0, 0, 1),
                2, 1, 1));
        LiveTraceComparator destinationComparator =
                (LiveTraceComparator) getField(session, "comparator");
        assertNotSame(sourceComparator, destinationComparator);
        long destinationGeneration = context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration();

        destinationComparator.afterFrameAdvanced(frame(2 + rowsConsumed), false);
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        session.afterProductionIteration();

        assertEquals(destinationGeneration, context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration());
        assertEquals(0, destinationComparator.errorCount());
        assertEquals(rowsConsumed + 1, destinationComparator.cursor());
        assertEquals(1, sourceComparator.cursor(),
                "the closed source comparator must not consume destination row zero");
        assertDoesNotThrow(() -> destinationComparator.afterFrameAdvanced(
                frame(3 + rowsConsumed), false),
                "destination row at the admitted cursor must drain in its own production wrapper");
    }

    @Test
    void destinationAdmissionAppliesLegacyLevelClockBudgetsToLoadedObjectManager()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        TraceRunManifest.Segment ghz1 = levelSegment(
                "ghz1", 788, 5_598, 1);
        TraceRunManifest.Segment ghz2 = levelSegment(
                "ghz2", 6_622, 4_028, 2);
        TraceRunManifest.Segment ghz3 = levelSegment(
                "ghz3", 10_885, 9_678, 3);
        TraceSessionLauncher session = launcherForSegments(
                List.of(ghz1, ghz2, ghz3));
        TraceRunVblankClock clock = new TraceRunVblankClock(
                TracePlaybackProfile.SONIC_1);
        setField(session, "runVblankClock", clock);
        ObjectManager destinationObjects = new ObjectManager(
                List.of(), null, 0, null, null);

        clock.captureLevelSourceTail(0, ghz1, 6_386, 0x17B7);
        session.applyRunDestinationVblankAdmission(
                levelReceipt(1, 6_622), destinationObjects);
        assertEquals(0x17B7 + 230, destinationObjects.getVblaCounter());

        clock.captureLevelSourceTail(1, ghz2, 10_650, 0x2850);
        session.applyRunDestinationVblankAdmission(
                levelReceipt(2, 10_885), destinationObjects);
        assertEquals(0x2850 + 229, destinationObjects.getVblaCounter());
    }

    @Test
    void destinationAdmissionAppliesUncomparedInteriorReturnClockToLoadedObjectManager()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        TraceRunManifest.Segment source = levelSegment(
                "ghz1", 100, 10, 1);
        TraceRunManifest.Segment special = new TraceRunManifest.Segment(
                "ss", "special_stage", "s1_special_stage", 111, 20,
                null, null, 0, null);
        TraceRunManifest.Segment returned = levelSegment(
                "ghz2", 140, 10, 2);
        TraceRunManifest.Transition entry = new TraceRunManifest.Transition(
                0, 1, "giant_ring", 110,
                null, null, null, null, null, null, null, null);
        TraceRunManifest.Transition exit = new TraceRunManifest.Transition(
                1, 2, "stage_exit", 139,
                null, null, null, null, null, null, null, null);
        List<TraceRunReplayWalker.SegmentPlan> plans = List.of(
                new TraceRunReplayWalker.SegmentPlan(
                        source, dynamicArtTrace(1), null, entry),
                new TraceRunReplayWalker.SegmentPlan(
                        special, dynamicArtTrace(1), entry, exit),
                new TraceRunReplayWalker.SegmentPlan(
                        returned, dynamicArtTrace(1), exit, null));
        TraceSessionLauncher session = TestRunPayloads.session(
                null, null, plans, null);
        TraceRunVblankClock clock = new TraceRunVblankClock(
                TracePlaybackProfile.SONIC_1);
        setField(session, "runVblankClock", clock);
        ObjectManager destinationObjects = new ObjectManager(
                List.of(), null, 0, null, null);

        clock.captureLevelSourceTail(0, source, 110, 0x500);
        session.applyRunDestinationVblankAdmission(
                levelReceipt(2, 140), destinationObjects);

        assertEquals(0x500 + 31, destinationObjects.getVblaCounter());
    }

    @Test
    void specialStageReturnCarriesTitleCardFallthroughRowIntoDestinationAdmission()
            throws Exception {
        TraceRunManifest.Segment source = levelSegment("ghz1", 0, 1, 1);
        TraceRunManifest.Segment special = new TraceRunManifest.Segment(
                "ss", "special_stage", "s1_special_stage", 1, 1,
                0, 0, 0, null);
        TraceRunManifest.Segment destination = levelSegment("ghz2", 3, 1, 2);
        TraceRunManifest.Transition entry = new TraceRunManifest.Transition(
                0, 1, "giant_ring", 1,
                null, null, null, null, null, null, null, null);
        TraceRunManifest.Transition exit = new TraceRunManifest.Transition(
                1, 2, "stage_exit", 3,
                null, null, null, null, null, null, null, null);
        TraceRunManifest run = new TraceRunManifest(
                "s1", "synthetic-ss-return", "movie.bk2", "rom",
                List.of(source, special, destination), List.of(entry, exit));
        List<TraceRunReplayWalker.SegmentPlan> plans = List.of(
                new TraceRunReplayWalker.SegmentPlan(
                        source, dynamicArtTrace(1), null, entry),
                new TraceRunReplayWalker.SegmentPlan(
                        special, dynamicArtTrace(1), entry, exit),
                new TraceRunReplayWalker.SegmentPlan(
                        destination, dynamicArtTrace(1), exit, null));
        TraceRunPlaybackCoordinator coordinator =
                new TraceRunPlaybackCoordinator(
                        run, TracePlaybackProfile.SONIC_1, 5);
        coordinator.activateInitialLevel(new RunPlaybackObservation(
                GameMode.LEVEL, 0, 0,
                new RunPlaybackObservation.LevelIdentity(1, 0, 0, 0),
                false, null, null, false, false, 0, false, 0, 0));
        coordinator.observeBoundary(new RunBoundarySignal.SpecialStageRequest(1, 0));
        coordinator.afterProduction(new RunPlaybackObservation(
                GameMode.LEVEL, 1, 1,
                new RunPlaybackObservation.LevelIdentity(1, 0, 0, 0),
                false, null, null, false, true, 0, false, 0, 0));
        coordinator.beforeAdmission(new RunPlaybackObservation(
                GameMode.SPECIAL_STAGE, 1, 2, null,
                false, null, 0, false, false, 0, false, 1, 1));
        coordinator.afterProduction(new RunPlaybackObservation(
                GameMode.SPECIAL_STAGE, 1, 3, null,
                false, null, 0, false, true, 0, false, 1, 1));
        assertEquals(TraceRunPlaybackCoordinator.Phase.TRANSITION_GAP,
                coordinator.phase());

        int destinationOffset = destination.bk2FrameOffset();
        List<Bk2FrameInput> rows = new ArrayList<>(destinationOffset + 3);
        for (int index = 0; index < destinationOffset + 3; index++) {
            rows.add(frame(index));
        }
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-destination-handoff.bk2"), "logkey",
                Map.of(), rows, rows.size());
        TraceSessionLauncher session = TestRunPayloads.session(
                null, movie, plans, null);
        setField(session, "runCoordinator", coordinator);
        GameServices.playbackDebug().startSession(movie, destinationOffset + 1);

        Method method = TraceSessionLauncher.class.getDeclaredMethod(
                "destinationRowsConsumedForAdmission");
        method.setAccessible(true);
        assertEquals(1, method.invoke(session),
                "the title-card fall-through must consume destination row zero");

        GameServices.playbackDebug().seekSessionFrame(destinationOffset, true);
        assertEquals(0, method.invoke(session),
                "a release seam before destination production consumes no rows");

        GameServices.playbackDebug().seekSessionFrame(destinationOffset + 2, true);
        assertEquals(2, method.invoke(session),
                "the adapter must expose an overrun instead of rebasing it");
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.beforeAdmission(new RunPlaybackObservation(
                        GameMode.LEVEL, destinationOffset + 2, 4,
                        new RunPlaybackObservation.LevelIdentity(1, 0, 1, 1),
                        false, null, null, false, false, 2, false, 0, 0)),
                "a second destination row before admission must remain a hard failure");
    }

    @Test
    void admissionLatchesComparedLevelBonusLevelRowsAcrossStructuralHandoffs() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-run.bk2"),
                "logkey",
                Map.of(),
                List.of(frame(500), frame(1900), frame(2900)),
                3);
        TraceSessionLauncher session =
                TestRunPayloads.session(null, movie, segments, null);
        RecordingTimingFixture fixture = new RecordingTimingFixture();
        RunSegmentAdvancer advancer =
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments));
        var first = HardwareTimingSchedule.empty();
        var bonus = HardwareTimingSchedule.empty();
        var returnedLevel = HardwareTimingSchedule.empty();
        var coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                500, List.of(100), first),
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                1900, List.of(200), bonus),
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                2900, List.of(300), returnedLevel)));
        setField(session, "fixture", fixture);
        setField(session, "runAdvancer", advancer);
        setField(session, "runHardwareTiming", coordinator);

        GameServices.playbackDebug().startSession(movie, 0);
        try {
            session.prepareHardwareTimingForAdmission(GameMode.LEVEL);

            session.runAdvanceTickIfActive(GameMode.TITLE_CARD, 501);
            session.runAdvanceTickIfActive(GameMode.BONUS_STAGE, 1900);
            GameServices.playbackDebug().seekSessionFrame(1, true);
            session.prepareHardwareTimingForAdmission(GameMode.BONUS_STAGE);

            session.runAdvanceTickIfActive(GameMode.TITLE_CARD, 1901);
            session.runAdvanceTickIfActive(GameMode.LEVEL, 2900);
            GameServices.playbackDebug().seekSessionFrame(2, true);
            session.prepareHardwareTimingForAdmission(GameMode.LEVEL);
        } finally {
            GameServices.playbackDebug().endSession();
        }

        assertEquals(List.of(100, 200, 300), fixture.rawFrames);
        assertEquals(List.of(bonus, returnedLevel), fixture.handoffs);
        assertEquals(0, fixture.gaps);
    }

    @Test
    void productionDynamicArtWindowSpansRepresentedSegmentsAndNativeGaps() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);
        setField(session, "runAdvancer",
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments)));
        session.installRunDynamicArtSegments(context);

        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentOpen());

        session.runAdvanceTickIfActive(GameMode.TITLE_CARD, 501);
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        context.dynamicArtLifecycle().observePlayerDplc(
                GameId.S2, "tails-tails", 13,
                new com.openggf.level.render.SpriteDplcFrame(List.of(
                        new TileLoadRequest(110, 12))));

        session.runAdvanceTickIfActive(GameMode.BONUS_STAGE, 1900);
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        assertEquals(List.of(0L), context.dynamicArtDiagnostics()
                .latestSnapshot().outstandingTransferIds());
        for (int rowIndex = 0; rowIndex < 126; rowIndex++) {
            context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
                row.claim(PlcLifecyclePhase.PALETTE_FADE);
                row.prepareAfterLoop(PlcLifecyclePhase.PALETTE_FADE);
                return null;
            });
            assertEquals(List.of(0L), context.dynamicArtDiagnostics()
                    .latestSnapshot().outstandingTransferIds());
        }
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });
        assertTrue(context.dynamicArtDiagnostics().latestSnapshot()
                .outstandingTransferIds().isEmpty());
        session.runAdvanceTickIfActive(GameMode.TITLE_CARD, 1901);
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        session.runAdvanceTickIfActive(GameMode.LEVEL, 2900);
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentOpen());

        session.runAdvanceTickIfActive(GameMode.LEVEL, 2902);

        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        assertEquals(List.of("submitted"),
                context.dynamicArtLifecycle().gapEdges().stream()
                        .map(edge -> edge.phase()).toList());
    }

    @Test
    void singleTraceSegmentOwnershipRejectsAnAlreadyExternalWindowWithoutRebasingIt() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        context.dynamicArtLifecycle().openComparisonSegment();
        context.plcFrameLifecycle()
                .setComparisonSegmentsExternallyManaged(true);
        long generation = context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration();
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);

        assertThrows(IllegalStateException.class,
                () -> session.installDynamicArtSegments(context));

        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        assertEquals(generation, context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration());
        context.dynamicArtLifecycle().closeComparisonSegment();
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen(),
                "rejecting a second owner must preserve the first external owner");
        assertEquals(generation, context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration());
    }

    @Test
    void visualOwnershipReplacesCompletedAutomaticWindowAndPreservesPendingS2Transfer() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        final long[] transferId = {-1};
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            transferId[0] = context.dynamicArtLifecycle().observeRamDplc(
                    GameId.S2, "sonic", 1,
                    List.of(new TileLoadRequest(0, 1)),
                    0x1000, 0xF000).transferId();
            return null;
        });
        DynamicArtDiagnosticsSnapshot automatic =
                context.dynamicArtDiagnostics().latestSnapshot();
        assertTrue(automatic.published());
        assertEquals(List.of(transferId[0]),
                automatic.outstandingTransferIds());
        long automaticGeneration = automatic.segmentGeneration();
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);

        session.installDynamicArtSegments(context);

        DynamicArtDiagnosticsSnapshot traceOrigin =
                context.dynamicArtDiagnostics().latestSnapshot();
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentReserved());
        assertFalse(traceOrigin.published());
        assertEquals(automaticGeneration + 1,
                traceOrigin.segmentGeneration());
        assertEquals(List.of(transferId[0]),
                traceOrigin.outstandingTransferIds());

        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        DynamicArtDiagnosticsSnapshot retired =
                context.dynamicArtDiagnostics().latestSnapshot();
        assertEquals(List.of(), retired.outstandingTransferIds());
        assertEquals(List.of(), retired.edges(),
                "pre-segment completion must not enter comparison row zero");
        assertEquals(List.of("completed"),
                context.dynamicArtLifecycle().gapEdges().stream()
                        .map(edge -> edge.phase()).toList());
        assertEquals(automaticGeneration + 1,
                retired.segmentGeneration());

        TraceRunReplayWalker.DynamicArtSegmentController controller =
                (TraceRunReplayWalker.DynamicArtSegmentController)
                        getField(session, "runDynamicArtSegments");
        controller.enterGap();
        long closedGeneration = context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration();
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen(),
                "external ownership must suppress automatic windows in run gaps");
        assertEquals(closedGeneration, context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration());
    }

    @Test
    void failedFreshTraceWindowOpenRestoresAutomaticOwnership() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        var pending = context.dynamicArtLifecycle().observeRomDplc(
                "sonic", 7, List.of(new TileLoadRequest(0, 1)),
                0x2000, 0xF000);
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);

        session.installDynamicArtSegments(context);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> context.plcFrameLifecycle().runLogicalIteration(
                        () -> { }, row -> {
                            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
                            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
                            return null;
                        }));

        assertTrue(failure.getMessage().contains("pending production work"));
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        assertEquals(List.of(pending.transferId()),
                context.dynamicArtDiagnostics().latestSnapshot()
                        .outstandingTransferIds());

        try {
            Method abort = TraceSessionLauncher.class.getDeclaredMethod(
                    "abortRunDynamicArtSegments");
            abort.setAccessible(true);
            abort.invoke(session);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
        context.dynamicArtLifecycle().completeApplied(pending);
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentOpen(),
                "normal lifecycle ownership must resume after launch rollback");
        assertEquals(List.of(), context.dynamicArtDiagnostics().latestSnapshot()
                .outstandingTransferIds());
    }

    @Test
    void singleTraceSegmentOwnershipPublishesComparisonRowZeroAtomically() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        TraceData source = segments.getFirst().trace();
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 2),
                List.of(source.getFrame(0), source.getFrame(1)),
                Map.of(
                        0, List.of(new TraceEvent.DynamicArtTransferState(
                                0, List.of(), List.of())),
                        1, List.of(new TraceEvent.DynamicArtTransferState(
                                1, List.of(), List.of()))));
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null, null, ignored -> { });
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);
        setField(session, "comparator", comparator);
        session.installDynamicArtSegments(context);
        long reservedGeneration = context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration();
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentReserved());
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        context.dynamicArtLifecycle().observePlayerDplc(
                GameId.S1, "sonic", 8,
                new com.openggf.level.render.SpriteDplcFrame(List.of(
                        new TileLoadRequest(0, 12))));

        session.beforeProductionIteration();
        comparator.afterFrameAdvanced(frame(0), false);
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        session.afterProductionIteration();

        DynamicArtDiagnosticsSnapshot published =
                context.dynamicArtDiagnostics().latestSnapshot();
        assertTrue(published.published());
        assertEquals(0, published.frame());
        assertEquals(reservedGeneration, published.segmentGeneration());
        assertEquals(List.of(), published.edges());
        assertEquals(List.of("submitted", "completed"),
                context.dynamicArtLifecycle().gapEdges().stream()
                        .map(edge -> edge.phase()).toList());
    }

    @Test
    void abortClosesStoredDynamicArtOwnerWhenNoGameplayContextIsCurrent()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()));
        context.dynamicArtLifecycle().beginRun();
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);
        session.installDynamicArtSegments(context);
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());

        Method abort = TraceSessionLauncher.class.getDeclaredMethod(
                "abortIncompleteSession", Throwable.class, String.class,
                GameLoop.class);
        abort.setAccessible(true);
        abort.invoke(session, null, "test abort", null);

        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentOpen(),
                "abort must restore automatic lifecycle ownership");
        context.destroy();
    }

    @Test
    void abortResetsStoredDynamicArtOwnerWithUnpublishedProductionEdge()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()));
        context.dynamicArtLifecycle().beginRun();
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);
        session.installDynamicArtSegments(context);
        context.dynamicArtLifecycle().observeRamDplc(
                GameId.S2, "sonic", 1, List.of(new TileLoadRequest(0, 1)),
                0x1000, 0xF000);

        Method abort = TraceSessionLauncher.class.getDeclaredMethod(
                "abortIncompleteSession", Throwable.class, String.class,
                GameLoop.class);
        abort.setAccessible(true);
        Object cleanupFailure = abort.invoke(
                session, null, "test buffered-edge abort", null);

        assertNull(cleanupFailure,
                "a production-owned abort reset must recover graceful-close rejection");
        assertFalse(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentOpen(),
                "automatic lifecycle ownership must resume after abort reset");
        context.destroy();
    }

    @Test
    void abortDoesNotMaskUnrelatedDynamicArtCloseFailure() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()));
        context.dynamicArtLifecycle().beginRun();
        TraceRunReplayWalker.DynamicArtSegmentController controller =
                new TraceRunReplayWalker.DynamicArtSegmentController(
                        new TraceRunReplayWalker.DynamicArtSegmentWindow() {
                            @Override
                            public void open() {
                            }

                            @Override
                            public void close() {
                                throw new IllegalStateException(
                                        "unrelated close invariant");
                            }
                        });
        controller.beginSegment();
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);
        setField(session, "runDynamicArtSegments", controller);
        setField(session, "dynamicArtSegmentGameplayMode", context);

        Method abort = TraceSessionLauncher.class.getDeclaredMethod(
                "abortIncompleteSession", Throwable.class, String.class,
                GameLoop.class);
        abort.setAccessible(true);
        Throwable cleanupFailure = (Throwable) abort.invoke(
                session, null, "test unrelated close failure", null);

        assertNotNull(cleanupFailure);
        assertEquals("unrelated close invariant", cleanupFailure.getMessage());
        context.destroy();
    }

    @Test
    void teardownRequestedInsideIterationWaitsForPostFinishDrain()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, segments, null);
        session.installRunDynamicArtSegments(context);
        session.beforeProductionIteration();

        var teardown = TraceSessionLauncher.class
                .getDeclaredMethod("teardown");
        teardown.setAccessible(true);
        teardown.invoke(session);

        assertTrue((boolean) getField(session, "teardownPending"));
        assertNotNull(getField(session, "runDynamicArtSegments"),
                "teardown must not close production comparison in the body");

        session.afterProductionIteration();

        assertFalse((boolean) getField(session, "teardownPending"));
        assertNull(getField(session, "runDynamicArtSegments"));
    }

    @Test
    void visualRunSpecialStageComparesAdvertisedProductionRowsAfterPublication() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        List<TraceRunReplayWalker.SegmentPlan> advertised =
                withAdvertisedSpecialStageTrace();
        assertTrue(advertised.get(1).trace().metadata()
                .hasPerFrameDynamicArtTransferState());
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-run.bk2"), "logkey", Map.of(),
                java.util.stream.IntStream.range(0, 1_200)
                        .mapToObj(TestTraceSessionLauncherRunBranch::frame)
                        .toList(),
                3);
        TraceSessionLauncher session =
                TestRunPayloads.session(null, movie, advertised, null);
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator reportSink = new LiveTraceComparator(
                advertised.get(1).trace(), ToleranceConfig.DEFAULT, 0,
                () -> null, null, observed::add);
        RecordingTimingFixture fixture = new RecordingTimingFixture(context);
        setField(session, "fixture", fixture);
        setField(session, "comparator", reportSink);
        setField(session, "runAdvancer",
                new RunSegmentAdvancer(TestRunPayloads.descriptors(advertised)));
        setField(session, "runHardwareTiming",
                new TraceRunReplayWalker.HardwareTimingCoordinator(
                        fixture,
                        TestRunPayloads.hardwareTimingSegments(advertised)));
        TraceRunExternalDiagnostics diagnostics =
                new TraceRunExternalDiagnostics(null);
        setField(session, "runExternalDiagnostics", diagnostics);
        session.installRunDynamicArtSegments(context);
        GameServices.playbackDebug().startSession(movie, 500);
        session.runAdvanceTickIfActive(GameMode.TITLE_CARD, 501);

        session.prepareHardwareTimingForAdmission(GameMode.SPECIAL_STAGE);
        TraceRunReplayWalker.DynamicArtSegmentComparison comparison =
                (TraceRunReplayWalker.DynamicArtSegmentComparison) getField(
                        session, "runSpecialDynamicArtComparison");
        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> {
        }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            session.runAdvanceTickIfActive(GameMode.SPECIAL_STAGE, 800);
            return null;
        });
        session.afterProductionIteration();

        session.prepareHardwareTimingForAdmission(GameMode.SPECIAL_STAGE);
        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> {
        }, row -> {
            row.claim(PlcLifecyclePhase.LAG);
            context.dynamicArtLifecycle().observeRamDplc(
                    "ss-sonic", 3, List.of(new TileLoadRequest(1, 1)),
                    0xFF0000, 0x5CA0);
            row.prepareAfterLoop(PlcLifecyclePhase.LAG);
            session.runAdvanceTickIfActive(GameMode.SPECIAL_STAGE, 801);
            assertEquals(1, comparison.comparisons().size(),
                    "terminal comparison must wait for coordinator finish");
            return null;
        });
        session.afterProductionIteration();

        assertEquals(2, comparison.comparisons().size());
        assertTrue(comparison.comparisons().stream()
                .noneMatch(FrameComparison::hasDivergence));
        assertEquals("true", comparison.comparisons().getLast().fields()
                .get("dynamic_art.edge[0].terminal_forwarded").actual());
        assertEquals(0, diagnostics.errorCount());
        assertTrue(observed.isEmpty(),
                "the detached source comparator must not retain destination rows");
    }

    @Test
    void visualRunStartingInSpecialStageBindsAlreadyOpenGeneration() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        TraceRunReplayWalker.SegmentPlan special =
                withAdvertisedSpecialStageTrace().get(1);
        List<TraceRunReplayWalker.SegmentPlan> ssFirst = List.of(special);
        TraceSessionLauncher session =
                TestRunPayloads.session(null, null, ssFirst, null);
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator reportSink = new LiveTraceComparator(
                special.trace(), ToleranceConfig.DEFAULT, 0,
                () -> null, null, observed::add);
        RecordingTimingFixture fixture = new RecordingTimingFixture(context);
        setField(session, "fixture", fixture);
        setField(session, "comparator", reportSink);
        setField(session, "runAdvancer",
                new RunSegmentAdvancer(TestRunPayloads.descriptors(ssFirst)));
        setField(session, "runHardwareTiming",
                new TraceRunReplayWalker.HardwareTimingCoordinator(
                        fixture,
                        TestRunPayloads.hardwareTimingSegments(ssFirst)));
        session.installRunDynamicArtSegments(context);

        long alreadyOpenGeneration = context.dynamicArtDiagnostics()
                .latestSnapshot().segmentGeneration();
        session.prepareHardwareTimingForAdmission(GameMode.SPECIAL_STAGE);
        assertEquals(alreadyOpenGeneration,
                getField(session,
                        "runSpecialDynamicArtTargetGeneration"));
        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> {
        }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });
        session.afterProductionIteration();

        assertEquals(1, observed.size());
        assertFalse(observed.getFirst().hasDivergence());
        assertEquals(alreadyOpenGeneration,
                context.dynamicArtDiagnostics().latestSnapshot()
                        .segmentGeneration());
    }

    @Test
    void visualRunSpecialStageRowZeroRebindsBeforeMismatchPublication() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        List<TraceRunReplayWalker.SegmentPlan> advertised =
                withAdvertisedSpecialStageTrace();
        Engine engine = new Engine(EngineServices.current());
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-run.bk2"),
                "logkey",
                Map.of(),
                java.util.stream.IntStream.range(0, 1_200)
                        .mapToObj(TestTraceSessionLauncherRunBranch::frame)
                        .toList(),
                3);
        List<FrameComparison> oldObserved = new ArrayList<>();
        AtomicBoolean oldFirstError = new AtomicBoolean();
        LiveTraceComparator levelComparator = new LiveTraceComparator(
                advertised.getFirst().trace(), ToleranceConfig.DEFAULT, 0,
                () -> null, () -> oldFirstError.set(true), oldObserved::add);
        TraceSessionLauncher session =
                TestRunPayloads.session(null, movie, advertised, null);
        RecordingTimingFixture fixture = new RecordingTimingFixture(context);
        setField(session, "fixture", fixture);
        setField(session, "comparator", levelComparator);
        setField(session, "runAdvancer",
                new RunSegmentAdvancer(TestRunPayloads.descriptors(advertised)));
        setField(session, "runHardwareTiming",
                new TraceRunReplayWalker.HardwareTimingCoordinator(
                        fixture,
                        TestRunPayloads.hardwareTimingSegments(advertised)));
        TraceRunExternalDiagnostics diagnostics =
                new TraceRunExternalDiagnostics(
                        engine.getGameLoop()::toggleUserPause);
        setField(session, "runExternalDiagnostics", diagnostics);
        session.installRunDynamicArtSegments(context);
        GameServices.playbackDebug().setFrameObserver(levelComparator);
        GameServices.playbackDebug().startSession(movie, 500);
        context.plcFrameLifecycle().runLogicalIteration(() -> {
        }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertEquals(0,
                context.dynamicArtDiagnostics().latestSnapshot().frame(),
                "regression must cross a prior level publication epoch");
        assertEquals(0, levelComparator.errorCount());
        assertTrue(levelComparator.recentMismatches().isEmpty());
        session.runAdvanceTickIfActive(GameMode.TITLE_CARD, 501);
        assertNull(getField(session, "comparator"),
                "the closed source lease must release its comparator in the gap");

        session.prepareHardwareTimingForAdmission(GameMode.SPECIAL_STAGE);
        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> {
        }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            context.dynamicArtLifecycle().observeRamDplc(
                    "ss-sonic", 2, List.of(new TileLoadRequest(0, 1)),
                    0xFF0000, 0x5CA0);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            session.runAdvanceTickIfActive(GameMode.SPECIAL_STAGE, 800);
            assertNull(getField(session, "comparator"),
                    "the closed source lease must release its comparator in the gap");
            return null;
        });
        GameServices.playbackDebug().seekSessionFrame(800, true);
        session.afterProductionIteration();

        LiveTraceComparator specialStageComparator =
                (LiveTraceComparator) getField(session, "comparator");
        assertNotSame(levelComparator, specialStageComparator);
        assertEquals(3, diagnostics.errorCount());
        assertEquals(
                List.of(
                        "dynamic_art.edge[0].present",
                        "dynamic_art.outstanding_transfer_ids",
                        "dynamic_art.edges"),
                diagnostics.recentMismatches().stream()
                        .map(mismatch -> mismatch.field()).toList());
        assertTrue(diagnostics.recentMismatches().stream()
                .allMatch(mismatch -> mismatch.frame() == 0));
        assertSame(specialStageComparator,
                getField(GameServices.playbackDebug(), "frameObserver"));
        assertTrue(engine.getGameLoop().isPaused());
        assertEquals(0, levelComparator.errorCount());
        assertTrue(levelComparator.recentMismatches().isEmpty());
        assertFalse(oldFirstError.get());
        assertTrue(oldObserved.isEmpty());
    }

    @Test
    void visualRunSpecialStageRejectsOmittedAdvertisedRow() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        List<TraceRunReplayWalker.SegmentPlan> advertised =
                withAdvertisedSpecialStageTrace();
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-run.bk2"), "logkey", Map.of(),
                java.util.stream.IntStream.range(0, 1_200)
                        .mapToObj(TestTraceSessionLauncherRunBranch::frame)
                        .toList(),
                3);
        TraceSessionLauncher session =
                TestRunPayloads.session(null, movie, advertised, null);
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator reportSink = new LiveTraceComparator(
                advertised.getFirst().trace(), ToleranceConfig.DEFAULT, 0,
                () -> null, null, observed::add);
        RecordingTimingFixture fixture = new RecordingTimingFixture(context);
        setField(session, "fixture", fixture);
        setField(session, "comparator", reportSink);
        setField(session, "runAdvancer",
                new RunSegmentAdvancer(TestRunPayloads.descriptors(advertised)));
        setField(session, "runHardwareTiming",
                new TraceRunReplayWalker.HardwareTimingCoordinator(
                        fixture,
                        TestRunPayloads.hardwareTimingSegments(advertised)));
        session.installRunDynamicArtSegments(context);
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        var oldWork = context.dynamicArtLifecycle().observeRamDplc(
                "ss-sonic", 7, List.of(new TileLoadRequest(0, 1)),
                0xFF0000, 0x5CA0);
        context.dynamicArtLifecycle().completeApplied(oldWork);
        GameServices.playbackDebug().startSession(movie, 500);
        session.runAdvanceTickIfActive(GameMode.TITLE_CARD, 501);
        DynamicArtDiagnosticsSnapshot oldTerminal =
                context.dynamicArtDiagnostics().latestSnapshot();
        assertTrue(oldTerminal.published());
        assertEquals(0, oldTerminal.frame());
        session.prepareHardwareTimingForAdmission(GameMode.SPECIAL_STAGE);
        DynamicArtDiagnosticsSnapshot newlyOpened =
                context.dynamicArtDiagnostics().latestSnapshot();
        assertFalse(newlyOpened.published());
        assertEquals(-1, newlyOpened.frame());
        assertEquals(oldTerminal.deliverySerial(),
                newlyOpened.deliverySerial());
        assertTrue(newlyOpened.segmentGeneration()
                > oldTerminal.segmentGeneration());
        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> {
        }, row -> {
            session.runAdvanceTickIfActive(GameMode.SPECIAL_STAGE, 800);
            return null;
        });

        assertTrue(observed.isEmpty());
        assertThrows(IllegalStateException.class,
                session::afterProductionIteration);
    }

    @Test
    void specialStagePublicationPrecedesPendingSegmentVerification() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        List<TraceRunReplayWalker.SegmentPlan> plans;
        try {
            TraceRunManifest run = TraceRunManifest.load(
                    specialStageRunDir.resolve("run_manifest.json"));
            plans = List.of(TraceRunReferencePlanLoader.load(
                    run, specialStageRunDir).get(1));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        TraceData trace = specialStagePublicationTrace();
        TraceSessionLauncher session = TestRunPayloads.session(
                null, null, plans, null);
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator sink = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0,
                () -> null, null, observed::add);
        setField(session, "fixture", new RecordingTimingFixture(context));
        setField(session, "comparator", sink);
        session.installRunDynamicArtSegments(context);
        TraceRunSpecialStageRowDriver rowDriver =
                new TraceRunSpecialStageRowDriver(
                        plans.getFirst().specialStageRows(), trace);
        rowDriver.admitCurrentRow(context.dynamicArtDiagnostics().latestSnapshot());
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });
        rowDriver.publishAdmittedRow(context.dynamicArtDiagnostics().latestSnapshot());
        rowDriver.admitCurrentRow(context.dynamicArtDiagnostics().latestSnapshot());
        setField(session, "runSpecialRowDriver", rowDriver);
        setField(session, "runSpecialVerificationPending", true);

        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });

        assertDoesNotThrow(session::afterProductionIteration,
                "the admitted row must publish before pending verification closes it");
        assertTrue(rowDriver.isComplete());
        assertEquals(1, observed.size());
        assertFalse(observed.getFirst().hasDivergence());
    }

    @Test
    void coordinatorAdmissionOwnsSpecialInputTimingPublicationAndClosure()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        TraceRunManifest run = TraceRunManifest.load(
                specialStageRunDir.resolve("run_manifest.json"));
        List<TraceRunReplayWalker.SegmentPlan> plans =
                withAdvertisedSpecialStageTrace();
        List<Bk2FrameInput> movieFrames = new ArrayList<>();
        for (int frameIndex = 0; frameIndex < 1200; frameIndex++) {
            int inputMask = frameIndex == 800
                    ? AbstractPlayableSprite.INPUT_RIGHT : 0;
            movieFrames.add(new Bk2FrameInput(
                    frameIndex, inputMask, 0, false, ""));
        }
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-run.bk2"), "logkey", Map.of(),
                movieFrames, 3);
        TraceSessionLauncher session = TestRunPayloads.session(
                null, movie, plans, null);
        RecordingTimingFixture fixture = new RecordingTimingFixture(context);
        TraceRunPlaybackCoordinator coordinator =
                new TraceRunPlaybackCoordinator(
                        run, TracePlaybackProfile.DISABLED,
                        movie.getFrameCount());
        TraceRunReplayWalker.BoundaryProbe probe =
                new TraceRunReplayWalker.BoundaryProbe(
                        new TraceRunReplayWalker.EngineHooks() {
                            @Override
                            public int currentBk2Frame() {
                                return 750;
                            }

                            @Override
                            public com.openggf.game.BonusStageType peekBonusRequest() {
                                return com.openggf.game.BonusStageType.NONE;
                            }

                            @Override
                            public boolean isSpecialStageRequested() {
                                return true;
                            }

                            @Override
                            public GameMode currentMode() {
                                return GameMode.SPECIAL_STAGE;
                            }
                        });
        setField(session, "fixture", fixture);
        setField(session, "runCoordinator", coordinator);
        setField(session, "runBoundaryProbe", probe);
        setField(session, "comparator", new LiveTraceComparator(
                plans.getFirst().trace(), ToleranceConfig.DEFAULT, 0,
                () -> null, null));
        setField(session, "runExternalDiagnostics",
                new TraceRunExternalDiagnostics(null));
        setField(session, "runHardwareTiming",
                new TraceRunReplayWalker.HardwareTimingCoordinator(
                        fixture,
                        TestRunPayloads.hardwareTimingSegments(plans)));
        session.installRunDynamicArtSegments(context);
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            row.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });

        RunPlaybackObservation source = new RunPlaybackObservation(
                GameMode.LEVEL, 500, 0,
                new RunPlaybackObservation.LevelIdentity(7, 0, 0, 0),
                false, null, null, false, false, 0, false, 0, 0);
        appendCoordinatorTranscript(session,
                coordinator.activateInitialLevel(source));
        coordinator.observeBoundary(
                new RunBoundarySignal.SpecialStageRequest(750, 0));
        RunPlaybackObservation sourceComplete = new RunPlaybackObservation(
                GameMode.LEVEL, 502, 1, source.level(),
                false, null, null, false, true, 0, false, 0, 0);
        applyCoordinatorActions(session,
                coordinator.afterProduction(sourceComplete));
        RunPlaybackObservation special = new RunPlaybackObservation(
                GameMode.SPECIAL_STAGE, 800, 2,
                null, false, null, 0,
                false, false, 0, false, 1, 1);
        applyCoordinatorActions(session,
                coordinator.beforeAdmission(special));

        TraceRunSpecialStageRowDriver driver =
                (TraceRunSpecialStageRowDriver) getField(
                        session, "runSpecialRowDriver");
        assertNotNull(driver);
        assertEquals(0, driver.cursor());
        assertTrue(session.runCoordinatorTranscript().stream()
                .filter(TraceRunPlaybackCoordinator.AdmitDestination.class::isInstance)
                .map(TraceRunPlaybackCoordinator.AdmitDestination.class::cast)
                .anyMatch(action -> action.receipt().segmentIndex() == 1
                        && action.receipt().inputClock()
                        == DestinationAdmissionReceipt.InputClock.SPECIAL_LOCAL));

        InputHandler input = new InputHandler();
        session.prepareHardwareTimingForAdmission(GameMode.SPECIAL_STAGE);
        session.applySpecialStageTraceInputIfActive(input);
        input.refreshLogicalSnapshot();
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT,
                input.logical().player1().heldMask());
        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });
        session.afterProductionIteration();
        session.advanceSpecialStageTraceCursorIfActive(input);

        assertFalse(input.hasLogicalOverride());
        assertEquals(1, driver.cursor());
        assertEquals(1, driver.comparisons().size());
        assertEquals(1, fixture.rawFrames.size());

        session.prepareHardwareTimingForAdmission(GameMode.SPECIAL_STAGE);
        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });
        session.afterProductionIteration();
        assertTrue(driver.isComplete());
        assertEquals(2, driver.comparisons().size());

        RunPlaybackObservation specialComplete = new RunPlaybackObservation(
                GameMode.SPECIAL_STAGE, 802, 3,
                null, false, null, 0,
                false, true, 0, false, 1, 1);
        applyCoordinatorActions(session,
                coordinator.afterProduction(specialComplete));

        assertNull(getField(session, "runSpecialRowDriver"));
        assertEquals(1, session.runCoordinatorTranscript().stream()
                .filter(TraceRunPlaybackCoordinator.CloseSegment.class::isInstance)
                .map(TraceRunPlaybackCoordinator.CloseSegment.class::cast)
                .filter(action -> action.segmentIndex() == 1)
                .count());

        GameServices.playbackDebug().startSession(movie, 500);
        assertEquals(802, session.currentRunBoundaryBk2Frame(),
                "the closed special-stage source must retain its local physical clock");
    }

    @Test
    void specialStageReturnTranslatesForwardedBoundaryAndGatesLevelRebind()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        TraceRunManifest run = TraceRunManifest.load(
                specialStageRunDir.resolve("run_manifest.json"));
        List<TraceRunReplayWalker.SegmentPlan> plans =
                withAdvertisedSpecialStageTrace();
        List<Bk2FrameInput> movieFrames = new ArrayList<>();
        for (int frameIndex = 0; frameIndex < 1_205; frameIndex++) {
            movieFrames.add(frame(frameIndex));
        }
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-ss-return.bk2"), "logkey", Map.of(),
                movieFrames, movieFrames.size());
        TraceRunPlaybackCoordinator coordinator =
                new TraceRunPlaybackCoordinator(
                        run, TracePlaybackProfile.DISABLED,
                        movie.getFrameCount());
        coordinator.activateInitialLevel(new RunPlaybackObservation(
                GameMode.LEVEL, 500, 0,
                new RunPlaybackObservation.LevelIdentity(7, 0, 0, 0),
                false, null, null, false, false, 0, false, 0, 0));
        coordinator.observeBoundary(
                new RunBoundarySignal.SpecialStageRequest(750, 0));
        coordinator.afterProduction(new RunPlaybackObservation(
                GameMode.LEVEL, 502, 1,
                new RunPlaybackObservation.LevelIdentity(7, 0, 0, 0),
                false, null, null, false, true, 0, false, 0, 0));
        coordinator.beforeAdmission(new RunPlaybackObservation(
                GameMode.SPECIAL_STAGE, 800, 2, null,
                false, null, 0, false, false, 0, false, 1, 1));
        coordinator.afterProduction(new RunPlaybackObservation(
                GameMode.SPECIAL_STAGE, 800, 3, null,
                false, null, 0, false, true, 0, false, 1, 1));

        TraceRunReplayWalker.BoundaryProbe probe =
                new TraceRunReplayWalker.BoundaryProbe(
                        new TraceRunReplayWalker.EngineHooks() {
                            @Override
                            public int currentBk2Frame() {
                                return 499;
                            }

                            @Override
                            public com.openggf.game.BonusStageType peekBonusRequest() {
                                return com.openggf.game.BonusStageType.NONE;
                            }

                            @Override
                            public boolean isSpecialStageRequested() {
                                return false;
                            }

                            @Override
                            public GameMode currentMode() {
                                return GameMode.LEVEL;
                            }
                        });
        probe.arm(plans.get(1).exitBoundary());
        setObjectField(probe, "latchedObservation",
                new TraceRunReplayWalker.BoundaryObservation(true, 499));
        TraceSessionLauncher session = TestRunPayloads.session(
                null, movie, plans, null);
        setField(session, "runCoordinator", coordinator);
        setField(session, "runBoundaryProbe", probe);
        setField(session, "runSpecialLocalRow", 2);
        setField(session, "activeSession", session);
        GameServices.playbackDebug().startSession(movie, 499);

        TraceSessionLauncher.observeRunStageExitIfActive();
        session.runAdvanceTickIfActive(GameMode.LEVEL, 499);

        RunPlaybackObservation.LevelIdentity destinationIdentity =
                new RunPlaybackObservation.LevelIdentity(8, 0, 0, 0);
        RunBoundarySignal.LevelLoaded accepted =
                new RunBoundarySignal.LevelLoaded(
                        802, RunLevelLoadCause.INTERIOR_RETURN,
                        destinationIdentity);
        RunPlaybackObservation pendingTitleCard = new RunPlaybackObservation(
                GameMode.TITLE_CARD, 499, 4, destinationIdentity,
                true, null, null, false, false, 0, false, 2, 2);
        List<TraceRunPlaybackCoordinator.Action> actions =
                coordinator.beforeLoadedLevelActivation(
                        accepted, pendingTitleCard);
        assertTrue(actions.isEmpty());
        assertTrue(coordinator.remembersLevelLoad(accepted),
                "the forwarded stage exit must use the special-local clock");

        RunBoundarySignal.LevelLoaded rejected =
                new RunBoundarySignal.LevelLoaded(
                        802, RunLevelLoadCause.INTERIOR_RETURN,
                        new RunPlaybackObservation.LevelIdentity(9, 0, 0, 0));
        assertFalse(session.scheduleAcceptedRunLevelDestinationIfNeeded(
                rejected, actions));
        assertFalse(GameServices.playbackDebug().hasScheduledLevelLoadSession());

        assertTrue(session.scheduleAcceptedRunLevelDestinationIfNeeded(
                accepted, actions));
        assertFalse(GameServices.playbackDebug().hasScheduledLevelLoadSession(),
                "the accepted load retains the continuous timeline");
        assertEquals(499, GameServices.playbackDebug().getCursorFrame());
    }

    @Test
    void visualNextActReturnPublishesCommonBoundaryWithoutInteriorRingTally()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        new Engine(EngineServices.current());
        TraceRunManifest run = TraceRunManifest.load(
                canonicalEmeraldRunDir.resolve("run_manifest.json"));
        List<TraceRunReplayWalker.SegmentPlan> plans =
                TraceRunReferencePlanLoader.load(run, canonicalEmeraldRunDir);
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator destination = new LiveTraceComparator(
                plans.get(2).trace(), ToleranceConfig.DEFAULT, 0,
                () -> null, null, observed::add);
        TraceSessionLauncher session = TestRunPayloads.session(
                null, null, plans, null);
        setField(session, "comparator", destination);
        GameServices.level().resetLevelGamestate(new LevelGamestate());
        setObjectField(GameServices.level(), "currentAct", 1);
        setObjectField(getField(GameServices.level(), "checkpointCoordinator"),
                "checkpointState", new CheckpointState());

        compareRunReturnBoundary(session, 2);

        assertEquals(1, observed.size());
        FrameComparison boundary = observed.getFirst();
        assertTrue(boundary.fields().containsKey(
                "run_boundary.next_act.manifest_advance"));
        assertTrue(boundary.fields().containsKey(
                "run_boundary.emeralds.recorded_progression"));
        assertFalse(boundary.fields().containsKey("run_boundary.rings"));
        assertTrue(destination.recentMismatches().stream()
                .noneMatch(mismatch -> "run_boundary.rings".equals(
                        mismatch.field())));
    }

    @Test
    void specialStageAdmissionRecapturesExhaustionBeforeDestinationClosure()
            throws Exception {
        TraceRunFailureStatus.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        new Engine(EngineServices.current());
        GameLoop loop = Engine.currentGameLoop();
        loop.setGameMode(GameMode.SPECIAL_STAGE);
        setObjectField(loop, "activeSpecialStageProvider",
                GameServices.module().getSpecialStageProvider());

        TraceRunManifest run = TraceRunManifest.load(
                canonicalEmeraldRunDir.resolve("run_manifest.json"));
        List<TraceRunReplayWalker.SegmentPlan> plans =
                TraceRunReferencePlanLoader.load(run, canonicalEmeraldRunDir);
        Bk2Movie movie = new Bk2MovieLoader().load(
                canonicalEmeraldRunDir.resolve(run.sourceBk2()));
        TraceSessionLauncher session = TestRunPayloads.session(
                null, movie, plans, null);
        RecordingTimingFixture fixture = new RecordingTimingFixture(context);
        TraceRunPlaybackCoordinator coordinator =
                new TraceRunPlaybackCoordinator(
                        run, TracePlaybackProfile.SONIC_1,
                        movie.getFrameCount());
        TraceRunReplayWalker.BoundaryProbe probe =
                new TraceRunReplayWalker.BoundaryProbe(
                        new TraceRunReplayWalker.EngineHooks() {
                            @Override
                            public int currentBk2Frame() {
                                return 4976;
                            }

                            @Override
                            public com.openggf.game.BonusStageType
                                    peekBonusRequest() {
                                return com.openggf.game.BonusStageType.NONE;
                            }

                            @Override
                            public boolean isSpecialStageRequested() {
                                return true;
                            }

                            @Override
                            public GameMode currentMode() {
                                return GameMode.SPECIAL_STAGE;
                            }
                        });
        LiveTraceComparator sourceComparator = new LiveTraceComparator(
                plans.getFirst().trace(), ToleranceConfig.DEFAULT, 0,
                () -> null, null);
        setObjectField(sourceComparator, "complete", true);
        setField(session, "fixture", fixture);
        setField(session, "runCoordinator", coordinator);
        setField(session, "runBoundaryProbe", probe);
        setField(session, "comparator", sourceComparator);
        setField(session, "runExternalDiagnostics",
                new TraceRunExternalDiagnostics(null));
        setField(session, "runHardwareTiming",
                new TraceRunReplayWalker.HardwareTimingCoordinator(
                        fixture,
                        TestRunPayloads.hardwareTimingSegments(plans)));
        session.installRunDynamicArtSegments(context);

        RunPlaybackObservation source = new RunPlaybackObservation(
                GameMode.LEVEL, 4975, 0,
                new RunPlaybackObservation.LevelIdentity(1, 0, 0, 0),
                false, null, null, false, false, 0, false, 0, 0);
        appendCoordinatorTranscript(session,
                coordinator.activateInitialLevel(source));
        coordinator.observeBoundary(
                new RunBoundarySignal.SpecialStageRequest(4976, 0));
        RunPlaybackObservation sourceComplete = new RunPlaybackObservation(
                GameMode.LEVEL, 4976, 1, source.level(),
                false, null, null, false, true, 0, false, 0, 0);
        applyCoordinatorActions(session,
                coordinator.afterProduction(sourceComplete));
        GameServices.playbackDebug().startSession(movie, 4976);

        session.runAdvanceTickIfActive(GameMode.SPECIAL_STAGE, 4976);

        TraceRunSpecialStageRowDriver driver =
                (TraceRunSpecialStageRowDriver) getField(
                        session, "runSpecialRowDriver");
        assertNotNull(driver);
        assertEquals(TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                coordinator.phase());
        assertEquals(1, coordinator.currentSegmentIndex());
        assertEquals(0, driver.cursor());
        assertTrue(TraceRunFailureStatus.current().isEmpty());
        assertFalse(session.runCoordinatorTranscript().stream()
                .anyMatch(TraceRunPlaybackCoordinator.FailRun.class::isInstance));
        assertFalse(session.runCoordinatorTranscript().stream()
                .filter(TraceRunPlaybackCoordinator.CloseSegment.class::isInstance)
                .map(TraceRunPlaybackCoordinator.CloseSegment.class::cast)
                .anyMatch(action -> action.segmentIndex() == 1));

        session.prepareHardwareTimingForAdmission(GameMode.SPECIAL_STAGE);
        session.beforeProductionIteration();
        context.plcFrameLifecycle().runLogicalIteration(() -> { }, row -> {
            row.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            row.prepareAfterLoop(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });
        session.afterProductionIteration();

        assertEquals(1, driver.cursor());
        assertEquals(1, driver.comparisons().size());
        assertFalse(driver.comparisons().getFirst().hasDivergence());
    }

    @Test
    void specialStageReturnBridgeReportsThroughRunDiagnosticsAfterLeaseDetach()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        Engine engine = new Engine(EngineServices.current());
        engine.getGameLoop().setInputHandler(new InputHandler());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();

        TraceRunManifest.Segment source = new TraceRunManifest.Segment(
                "source", "level", "complete_run", 0, 1,
                0, 1, null, null);
        TraceRunManifest.Segment special = new TraceRunManifest.Segment(
                "special", "special_stage", "s1_special_stage", 1, 1,
                0, 0, 0, null);
        TraceRunManifest.Segment bridge = new TraceRunManifest.Segment(
                "bridge", "level", "complete_run", 2, 2,
                0, 2, null, null);
        TraceRunManifest.Transition entry = new TraceRunManifest.Transition(
                0, 1, "giant_ring", 1,
                null, null, null, null, null, null, null, null);
        TraceRunManifest.Transition exit = new TraceRunManifest.Transition(
                1, 2, "stage_exit", 2,
                null, null, null, null, null, null, null, null);
        TraceData oneRow = TraceFixtures.trace(
                TraceFixtures.metadata("s1", 0, 1),
                List.of(TraceFrame.executionTestFrame(0, 1, 1, 0)));
        TraceData bridgeRows = TraceFixtures.trace(
                TraceFixtures.metadata("s1", 0, 2),
                List.of(
                        TraceFrame.executionTestFrame(0, 10, 20, 0),
                        TraceFrame.executionTestFrame(1, 11, 20, 0)));
        TraceRunManifest canonical = TraceRunManifest.load(
                canonicalEmeraldRunDir.resolve("run_manifest.json"));
        var specialRows = TraceRunReferencePlanLoader.load(
                canonical, canonicalEmeraldRunDir).get(1).specialStageRows();
        List<TraceRunReplayWalker.SegmentPlan> plans = List.of(
                new TraceRunReplayWalker.SegmentPlan(
                        source, oneRow, null, entry),
                new TraceRunReplayWalker.SegmentPlan(
                        special, oneRow, entry, exit,
                        specialRows,
                        TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL),
                new TraceRunReplayWalker.SegmentPlan(
                        bridge, bridgeRows, exit, null));
        assertEquals(TraceRunReplayWalker.SegmentExecutionPolicy
                        .LEVEL_PRESENTATION_BRIDGE,
                plans.get(2).executionPolicy());

        Bk2Movie movie = new Bk2Movie(
                Path.of("special-return-bridge.bk2"), "logkey", Map.of(),
                List.of(
                        frame(0), frame(1),
                        new Bk2FrameInput(2, 1, 0, false, ""), frame(3)),
                4);
        AtomicInteger payloadCloseCount = new AtomicInteger();
        TraceSessionLauncher session = TestRunPayloads.session(
                null, movie, plans, null, payloadCloseCount);
        TraceRunExternalDiagnostics diagnostics =
                new TraceRunExternalDiagnostics(null);
        setField(session, "runExternalDiagnostics", diagnostics);

        closeRunSegment(session, 0);
        openRunPayload(session, 1);
        closeRunSegment(session, 1);
        assertNull(getField(session, "comparator"),
                "the closed special-stage source must not remain a sink");
        setField(session, "runBoundaryProbe", inertBoundaryProbe());
        GameServices.playbackDebug().startSession(movie, 2);
        applyRunDestinationAdmission(session, new DestinationAdmissionReceipt(
                2, DestinationAdmissionReceipt.InputClock.SHARED, 2, 0,
                new DestinationAdmissionReceipt.LevelPresentationIdentity(
                        0, 0, 2),
                -1, 2, 2,
                TraceRunReplayWalker.SegmentExecutionPolicy
                        .LEVEL_PRESENTATION_BRIDGE));

        TraceRunPlaybackCoordinator coordinator =
                org.mockito.Mockito.mock(TraceRunPlaybackCoordinator.class);
        org.mockito.Mockito.when(coordinator.phase()).thenReturn(
                TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT);
        org.mockito.Mockito.when(coordinator.currentSegmentIndex()).thenReturn(2);
        TraceRunFrameDriver frameDriver = new TraceRunFrameDriver();
        setField(session, "runCoordinator", coordinator);
        setField(session, "runFrameDriver", frameDriver);
        context.installTraceRunFrameDriver(frameDriver);
        setField(session, "activeSession", session);

        assertDoesNotThrow(() -> driveRunPhysicalRow(
                session, () -> { },
                GameServices.playbackDebug()::onLevelFrameAdvanced));
        assertEquals(1, diagnostics.errorCount(),
                "the bridge mismatch must retain whole-run diagnostic ownership");
        assertEquals("input_alignment",
                diagnostics.recentMismatches().getFirst().field());
        assertNull(getField(session, "comparator"),
                "bridge comparison must not resurrect a closed segment alias");
        assertEquals(2, payloadCloseCount.get(),
                "the admitted bridge lease must remain open");
    }

    @Test
    void presentationBridgeTerminalReportsThroughRunDiagnosticsAndClosesLease()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        new Engine(EngineServices.current());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();

        TraceRunManifest.Segment source = new TraceRunManifest.Segment(
                "source", "level", "complete_run", 0, 1,
                0, 1, null, null);
        TraceRunManifest.Segment special = new TraceRunManifest.Segment(
                "special", "special_stage", "s1_special_stage", 1, 1,
                0, 0, 0, null);
        TraceRunManifest.Segment bridge = new TraceRunManifest.Segment(
                "bridge", "level", "complete_run", 2, 1,
                0, 2, null, null);
        TraceRunManifest.Transition entry = new TraceRunManifest.Transition(
                0, 1, "giant_ring", 1,
                null, null, null, null, null, null, null, null);
        TraceRunManifest.Transition exit = new TraceRunManifest.Transition(
                1, 2, "stage_exit", 2,
                null, null, null, null, null, null, null, null);
        TraceData oneRow = TraceFixtures.trace(
                TraceFixtures.metadata("s1", 0, 1),
                List.of(TraceFrame.executionTestFrame(0, 1, 1, 0)));
        TraceRunManifest canonical = TraceRunManifest.load(
                canonicalEmeraldRunDir.resolve("run_manifest.json"));
        var specialRows = TraceRunReferencePlanLoader.load(
                canonical, canonicalEmeraldRunDir).get(1).specialStageRows();
        List<TraceRunReplayWalker.SegmentPlan> plans = List.of(
                new TraceRunReplayWalker.SegmentPlan(
                        source, oneRow, null, entry),
                new TraceRunReplayWalker.SegmentPlan(
                        special, oneRow, entry, exit,
                        specialRows,
                        TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL),
                new TraceRunReplayWalker.SegmentPlan(
                        bridge, dynamicArtTrace(1), exit, null, null,
                        TraceRunReplayWalker.SegmentExecutionPolicy
                                .LEVEL_PRESENTATION_BRIDGE));
        assertEquals(TraceRunReplayWalker.SegmentExecutionPolicy
                        .LEVEL_PRESENTATION_BRIDGE,
                plans.get(2).executionPolicy());

        Bk2Movie movie = new Bk2Movie(
                Path.of("terminal-special-return-bridge.bk2"), "logkey",
                Map.of(), List.of(frame(0), frame(1), frame(2)), 3);
        AtomicInteger payloadCloseCount = new AtomicInteger();
        TraceSessionLauncher session = TestRunPayloads.session(
                null, movie, plans, null, payloadCloseCount);
        TraceRunExternalDiagnostics diagnostics =
                new TraceRunExternalDiagnostics(null);
        setField(session, "runExternalDiagnostics", diagnostics);

        closeRunSegment(session, 0);
        openRunPayload(session, 1);
        closeRunSegment(session, 1);
        assertNull(getField(session, "comparator"),
                "the closed source and special-stage leases must not remain sinks");
        setField(session, "runBoundaryProbe", inertBoundaryProbe());
        GameServices.playbackDebug().startSession(movie, 2);
        applyRunDestinationAdmission(session, new DestinationAdmissionReceipt(
                2, DestinationAdmissionReceipt.InputClock.SHARED, 2, 0,
                new DestinationAdmissionReceipt.LevelPresentationIdentity(
                        0, 0, 2),
                -1, 2, 2,
                TraceRunReplayWalker.SegmentExecutionPolicy
                        .LEVEL_PRESENTATION_BRIDGE));

        var lifecycle = context.dynamicArtLifecycle();
        TraceRunReplayWalker.DynamicArtSegmentController dynamicArt =
                new TraceRunReplayWalker.DynamicArtSegmentController(
                        new TraceRunReplayWalker.DynamicArtSegmentWindow() {
                            @Override
                            public void open() {
                                lifecycle.openComparisonSegment();
                            }

                            @Override
                            public void close() {
                                lifecycle.closeComparisonSegment();
                            }
                        });
        dynamicArt.beginSegment();
        setField(session, "runDynamicArtSegments", dynamicArt);
        setField(session, "dynamicArtSegmentGameplayMode", context);

        TraceStructuralRowComparator structural =
                (TraceStructuralRowComparator) getField(
                        session, "runStructuralComparator");
        DynamicArtDiagnosticsSnapshot before =
                GameServices.captureDynamicArtDiagnostics();
        DynamicArtDiagnosticsSnapshot published = lifecycle.publishRow(0, false);
        structural.prepareRow(new Bk2FrameInput(
                2, AbstractPlayableSprite.INPUT_LEFT, 0, false,
                "terminal bridge mismatch"));
        assertNull(structural.completePostProduction(before, published),
                "the terminal comparison must remain deferred until lease close");
        closeRunSegment(session, 2);

        assertEquals(1, diagnostics.errorCount(),
                "terminal divergence must reach the run-scoped owner");
        assertEquals("input_alignment",
                diagnostics.recentMismatches().getFirst().field());
        assertNull(getField(session, "comparator"),
                "terminal publication must not restore a stale segment sink");
        assertNull(getField(session, "runStructuralComparator"));
        assertEquals(3, payloadCloseCount.get(),
                "closing the bridge must close each real payload lease once");
    }

    @Test
    void specialStageEntryRetainsDestinationPhysicalRowForAdmission()
            throws Exception {
        TraceRunManifest run = TraceRunManifest.load(
                canonicalEmeraldRunDir.resolve("run_manifest.json"));
        List<TraceRunReplayWalker.SegmentPlan> plans =
                TraceRunReferencePlanLoader.load(run, canonicalEmeraldRunDir);
        Bk2Movie movie = new Bk2MovieLoader().load(
                canonicalEmeraldRunDir.resolve(run.sourceBk2()));
        TraceRunPlaybackCoordinator coordinator =
                TraceRunPlaybackCoordinator.fromDescriptors(
                        run, TracePlaybackProfile.SONIC_1,
                        movie.getFrameCount(), TestRunPayloads.descriptors(plans));
        TraceSessionLauncher session = TestRunPayloads.session(
                null, movie, plans, null);
        TraceRunFrameDriver frameDriver = new TraceRunFrameDriver();
        setField(session, "runCoordinator", coordinator);
        setField(session, "runFrameDriver", frameDriver);
        setField(session, "activeSession", session);

        RunPlaybackObservation source = new RunPlaybackObservation(
                GameMode.LEVEL, 4975, 0,
                new RunPlaybackObservation.LevelIdentity(1, 0, 0, 0),
                false, null, null, false, false, 0, false, 0, 0);
        coordinator.activateInitialLevel(source);
        coordinator.observeBoundary(
                new RunBoundarySignal.SpecialStageRequest(4976, 0));
        coordinator.afterProduction(new RunPlaybackObservation(
                GameMode.LEVEL, 4976, 1, source.level(),
                false, null, null, false, true, 0, false, 0, 0));
        GameServices.playbackDebug().startSession(movie, 4976);

        frameDriver.execute(
                new Step(Disposition.SHARED_GAP, 4976, false),
                new Hooks<>() {
                    @Override
                    public void preparePhysicalRow(Step step) {
                    }

                    @Override
                    public void prepareHardwareTiming(Step step) {
                    }

                    @Override
                    public Object captureBefore(Step step) {
                        return null;
                    }

                    @Override
                    public void runProductionLifecycle(Step step) {
                        TraceSessionLauncher
                                .deferRunPhysicalRowForSpecialStageEntry(
                                        GameMode.LEVEL, GameMode.SPECIAL_STAGE);
                    }

                    @Override
                    public void advancePhysicalRow(Step step) {
                    }

                    @Override
                    public Object captureAfter(Step step) {
                        return null;
                    }

                    @Override
                    public void compare(Step step, Object before, Object after) {
                    }

                    @Override
                    public void afterStep(Step step) {
                    }
                });

        assertTrue((boolean) getField(
                session, "runPhysicalRowAdvanceDeferred"));
    }

    private List<TraceRunReplayWalker.SegmentPlan>
            withAdvertisedSpecialStageTrace() {
        List<TraceRunReplayWalker.SegmentPlan> specialSegments;
        try {
            TraceRunManifest run = TraceRunManifest.load(
                    specialStageRunDir.resolve("run_manifest.json"));
            specialSegments = TraceRunReferencePlanLoader.load(
                    run, specialStageRunDir);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
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
        var middle = specialSegments.get(1);
        return List.of(
                specialSegments.get(0),
                new TraceRunReplayWalker.SegmentPlan(
                        middle.segment(), trace,
                        middle.entryBoundary(), middle.exitBoundary(),
                        middle.specialStageRows()),
                specialSegments.get(2));
    }

    private static TraceData specialStagePublicationTrace() {
        return TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 2),
                List.of(),
                Map.of(
                        0, List.of(new TraceEvent.DynamicArtTransferState(
                                0,
                                List.of(new DynamicArtTransfer.SegmentEdge(
                                        0, 0, "submitted", "ss-sonic",
                                        "segment", 3, 1, 0, 1, true,
                                        0x33ADA,
                                        List.of(new DynamicArtTransfer.Request(
                                                -1, -1, 0xFF0020,
                                                0x5CA0, 0x20)))),
                                List.of(0L))),
                        1, List.of(new TraceEvent.DynamicArtTransferState(
                                1, List.of(), List.of()))));
    }

    private static Path canonicalizeInstalledRun(Path source, Path target)
            throws Exception {
        Files.createDirectories(target);
        ObjectMapper mapper = new ObjectMapper();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                String filename = path.getFileName().toString();
                if (filename.equals("metadata.json")
                        || filename.equals("run_manifest.json")) {
                    ObjectNode json = (ObjectNode) mapper.readTree(
                            Files.readString(path));
                    for (String removed : List.of(
                            "run_" + "schema", "lua_" + "script_version",
                            "csv_" + "version", "ss_" + "csv_" + "version",
                            "hardware_" + "timing_schema")) {
                        json.remove(removed);
                    }
                    json.put("trace_schema", 5);
                    if (json.has("aux_schema_extras")) {
                        var extras = json.withArray("aux_schema_extras");
                        for (int index = 0; index < extras.size(); index++) {
                            if ("dynamic_art_transfer_state_per_frame_v1"
                                    .equals(extras.get(index).asText())) {
                                extras.set(index,
                                        mapper.getNodeFactory().textNode(
                                                "dynamic_art_transfer_state_per_frame"));
                            }
                        }
                    }
                    if (filename.equals("run_manifest.json")
                            && !json.has("dynamic_art_gap_transitions")) {
                        json.putArray("dynamic_art_gap_transitions");
                    }
                    Files.writeString(destination,
                            mapper.writeValueAsString(json));
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }

    @Test
    void staysComparingWhileModeMatchesSegmentZero() {
        RunSegmentAdvancer advancer =
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments));
        assertNull(advancer.onFrame(GameMode.LEVEL, 500));
        assertNull(advancer.onFrame(GameMode.LEVEL, 600));
        assertEquals(0, advancer.currentSegmentIndex());
    }

    @Test
    void entersTransitionWhenModeLeavesSegmentZero() {
        RunSegmentAdvancer advancer =
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments));
        advancer.onFrame(GameMode.LEVEL, 1750);
        assertNull(advancer.onFrame(GameMode.TITLE_CARD, 1751));
        assertEquals(0, advancer.currentSegmentIndex());
    }

    @Test
    void emitsAdvanceActionWhenBonusStageReached() {
        RunSegmentAdvancer advancer =
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments));
        advancer.onFrame(GameMode.LEVEL, 1750);
        advancer.onFrame(GameMode.TITLE_CARD, 1751);
        RunSegmentAdvancer.Event event = advancer.onFrame(GameMode.BONUS_STAGE, 1900);
        assertTrue(event instanceof RunSegmentAdvancer.AdvanceAction);
        RunSegmentAdvancer.AdvanceAction action = (RunSegmentAdvancer.AdvanceAction) event;
        assertEquals(1900, action.reseekOffset());
        assertEquals(1, action.nextSegmentIndex());
        assertEquals(1, advancer.currentSegmentIndex());
    }

    @Test
    void modeFlickerDuringTransitionEmitsNothing() {
        RunSegmentAdvancer advancer =
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments));
        advancer.onFrame(GameMode.LEVEL, 1750);
        advancer.onFrame(GameMode.TITLE_CARD, 1751);
        // TITLE_CARD -> TITLE_CARD flicker mid-transition: not the next
        // segment's expected mode (BONUS_STAGE), so nothing is emitted and
        // the advancer stays mid-transition on segment 0.
        assertNull(advancer.onFrame(GameMode.TITLE_CARD, 1752));
        assertEquals(0, advancer.currentSegmentIndex());
    }

    @Test
    void wrongModeDuringTransitionKeepsWaiting() {
        RunSegmentAdvancer advancer =
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments));
        advancer.onFrame(GameMode.LEVEL, 1750);
        advancer.onFrame(GameMode.TITLE_CARD, 1751);
        // SPECIAL_STAGE is not segment 1's expected mode (BONUS_STAGE):
        // never throws, just keeps waiting.
        assertNull(advancer.onFrame(GameMode.SPECIAL_STAGE, 1755));
        assertEquals(0, advancer.currentSegmentIndex());
    }

    @Test
    void fullChainReachesEndOfRun() {
        RunSegmentAdvancer advancer =
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments));
        assertNull(advancer.onFrame(GameMode.LEVEL, 1000));

        advancer.onFrame(GameMode.LEVEL, 1750);
        advancer.onFrame(GameMode.TITLE_CARD, 1751);
        RunSegmentAdvancer.Event toBonus = advancer.onFrame(GameMode.BONUS_STAGE, 1900);
        assertEquals(new RunSegmentAdvancer.AdvanceAction(1900, 1), toBonus);
        assertEquals(1, advancer.currentSegmentIndex());

        assertNull(advancer.onFrame(GameMode.BONUS_STAGE, 2000));
        advancer.onFrame(GameMode.TITLE_CARD, 2800);
        RunSegmentAdvancer.Event toLevel = advancer.onFrame(GameMode.LEVEL, 2900);
        assertEquals(new RunSegmentAdvancer.AdvanceAction(2900, 2), toLevel);
        assertEquals(2, advancer.currentSegmentIndex());

        // Segment 2 (offset 2900, 2 trace frames): still comparing before
        // the last frame is exhausted.
        assertNull(advancer.onFrame(GameMode.LEVEL, 2901));
        RunSegmentAdvancer.Event end = advancer.onFrame(GameMode.LEVEL, 2902);
        assertSame(RunSegmentAdvancer.EndOfRun.INSTANCE, end);
    }

    @Test
    void staysDoneAfterEndOfRun() {
        RunSegmentAdvancer advancer =
                new RunSegmentAdvancer(TestRunPayloads.descriptors(segments));
        advancer.onFrame(GameMode.LEVEL, 1750);
        advancer.onFrame(GameMode.TITLE_CARD, 1751);
        advancer.onFrame(GameMode.BONUS_STAGE, 1900);
        advancer.onFrame(GameMode.TITLE_CARD, 2800);
        advancer.onFrame(GameMode.LEVEL, 2900);
        advancer.onFrame(GameMode.LEVEL, 2902);
        assertNull(advancer.onFrame(GameMode.LEVEL, 3000));
    }

    private static Bk2FrameInput frame(int index) {
        return new Bk2FrameInput(index, 0, 0, false, "");
    }

    private static TraceData dynamicArtTrace(int frameCount) {
        List<TraceFrame> frames = new ArrayList<>();
        Map<Integer, List<TraceEvent>> events = new LinkedHashMap<>();
        for (int frame = 0; frame < frameCount; frame++) {
            frames.add(TraceFrame.executionTestFrame(
                    frame, frame, frame, 0));
            events.put(frame, List.of(new TraceEvent.DynamicArtTransferState(
                    frame, List.of(), List.of())));
        }
        return TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, frameCount),
                frames, events);
    }

    private static TraceSessionLauncher launcherForSegments(
            List<TraceRunManifest.Segment> manifestSegments) {
        List<TraceRunReplayWalker.SegmentPlan> plans = manifestSegments.stream()
                .map(segment -> new TraceRunReplayWalker.SegmentPlan(
                        segment, dynamicArtTrace(1), null, null))
                .toList();
        return TestRunPayloads.session(null, null, plans, null);
    }

    private static TraceRunManifest.Segment levelSegment(
            String directory, int offset, int frames, int act) {
        return new TraceRunManifest.Segment(
                directory, "level", "complete_run", offset, frames,
                0, act, null, null);
    }

    private static DestinationAdmissionReceipt levelReceipt(
            int segmentIndex, int absoluteBk2Row) {
        return new DestinationAdmissionReceipt(
                segmentIndex, DestinationAdmissionReceipt.InputClock.SHARED,
                absoluteBk2Row, 0,
                new DestinationAdmissionReceipt.LevelIdentity(0, 0, segmentIndex),
                segmentIndex, segmentIndex, segmentIndex);
    }

    private static void applyRunDestinationAdmission(
            TraceSessionLauncher launcher,
            DestinationAdmissionReceipt receipt) {
        try {
            Method method = TraceSessionLauncher.class.getDeclaredMethod(
                    "applyRunDestinationAdmission",
                    DestinationAdmissionReceipt.class);
            method.setAccessible(true);
            method.invoke(launcher, receipt);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void closeRunSegment(
            TraceSessionLauncher launcher, int segmentIndex) {
        try {
            Method method = TraceSessionLauncher.class.getDeclaredMethod(
                    "closeRunSegment", int.class);
            method.setAccessible(true);
            method.invoke(launcher, segmentIndex);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void openRunPayload(
            TraceSessionLauncher launcher, int segmentIndex) {
        try {
            Method method = TraceSessionLauncher.class.getDeclaredMethod(
                    "openRunPayload", int.class);
            method.setAccessible(true);
            method.invoke(launcher, segmentIndex);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void driveRunPhysicalRow(
            TraceSessionLauncher launcher,
            Runnable productionIteration,
            Runnable advanceRunPhysicalRow) {
        try {
            Method method = TraceSessionLauncher.class.getDeclaredMethod(
                    "driveRunPhysicalRow", Runnable.class, Runnable.class);
            method.setAccessible(true);
            method.invoke(launcher, productionIteration, advanceRunPhysicalRow);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static TraceRunReplayWalker.BoundaryProbe inertBoundaryProbe() {
        return new TraceRunReplayWalker.BoundaryProbe(
                new TraceRunReplayWalker.EngineHooks() {
                    @Override
                    public int currentBk2Frame() {
                        return GameServices.playbackDebug().getCursorFrame();
                    }

                    @Override
                    public com.openggf.game.BonusStageType peekBonusRequest() {
                        return com.openggf.game.BonusStageType.NONE;
                    }

                    @Override
                    public boolean isSpecialStageRequested() {
                        return false;
                    }

                    @Override
                    public GameMode currentMode() {
                        return GameMode.TITLE_CARD;
                    }
                });
    }

    private static List<TraceRunPlaybackCoordinator.Action> driveCanonicalPolicy(
            TraceRunPlaybackCoordinator coordinator) {
        List<TraceRunPlaybackCoordinator.Action> transcript = new ArrayList<>();
        transcript.addAll(coordinator.activateInitialLevel(
                levelObservation(false)));
        coordinator.observeBoundary(new RunBoundarySignal.BonusRequest(
                1750, com.openggf.game.BonusStageType.GUMBALL));
        transcript.addAll(coordinator.afterProduction(levelObservation(true)));
        transcript.addAll(coordinator.beforeAdmission(bonusObservation(false)));
        transcript.addAll(coordinator.afterProduction(bonusObservation(true)));
        return List.copyOf(transcript);
    }

    private static void driveCanonicalVisual(
            TraceSessionLauncher launcher,
            TraceRunPlaybackCoordinator coordinator) {
        applyCoordinatorActions(launcher,
                coordinator.activateInitialLevel(levelObservation(false)));
        coordinator.observeBoundary(new RunBoundarySignal.BonusRequest(
                1750, com.openggf.game.BonusStageType.GUMBALL));
        assertNotNull(getField(coordinator, "observedBoundary"));
        applyCoordinatorActions(launcher,
                coordinator.afterProduction(levelObservation(true)));
        assertEquals(TraceRunPlaybackCoordinator.Phase.TRANSITION_GAP,
                coordinator.phase());
        assertEquals(0, coordinator.currentSegmentIndex());
        assertNotNull(getField(coordinator, "observedBoundary"));
        List<TraceRunPlaybackCoordinator.Action> admission =
                coordinator.beforeAdmission(bonusObservation(false));
        assertFalse(admission.isEmpty(),
                "visual coordinator must admit the observed bonus destination");
        GameServices.playbackDebug().seekSessionFrame(1900, true);
        applyCoordinatorActions(launcher, admission);
        applyCoordinatorActions(launcher,
                coordinator.afterProduction(bonusObservation(true)));
    }

    private static RunPlaybackObservation levelObservation(boolean exhausted) {
        return new RunPlaybackObservation(
                GameMode.LEVEL, 500, exhausted ? 2 : 0,
                new RunPlaybackObservation.LevelIdentity(1, 0, 0, 0),
                false, null, null, false, exhausted, 0, false, 10, 20);
    }

    private static RunPlaybackObservation bonusObservation(boolean exhausted) {
        return new RunPlaybackObservation(
                GameMode.BONUS_STAGE, 1900, exhausted ? 4 : 3,
                null, false,
                new RunPlaybackObservation.BonusIdentity(
                        19, 0, com.openggf.game.BonusStageType.GUMBALL),
                null, false, exhausted, 0, false, 30, 40);
    }

    private static RunPlaybackObservation captureObservation(
            TraceSessionLauncher launcher, GameMode mode) {
        try {
            Method method = TraceSessionLauncher.class.getDeclaredMethod(
                    "captureRunObservation", GameMode.class, int.class,
                    boolean.class);
            method.setAccessible(true);
            return (RunPlaybackObservation) method.invoke(
                    launcher, mode, 0, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void compareRunReturnBoundary(
            TraceSessionLauncher launcher, int destinationIndex) {
        try {
            Method method = TraceSessionLauncher.class.getDeclaredMethod(
                    "compareRunReturnBoundaryIfPresent", int.class);
            method.setAccessible(true);
            method.invoke(launcher, destinationIndex);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendCoordinatorTranscript(
            TraceSessionLauncher launcher,
            List<TraceRunPlaybackCoordinator.Action> actions) {
        ((List<TraceRunPlaybackCoordinator.Action>) getField(
                launcher, "runCoordinatorTranscript")).addAll(actions);
    }

    @SuppressWarnings("unchecked")
    private static void applyCoordinatorActions(
            TraceSessionLauncher launcher,
            List<TraceRunPlaybackCoordinator.Action> actions) {
        try {
            Method method = TraceSessionLauncher.class.getDeclaredMethod(
                    "applyRunCoordinatorActions", List.class);
            method.setAccessible(true);
            method.invoke(launcher, actions);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(
            TraceSessionLauncher session, String fieldName, Object value) {
        try {
            Field field = launcherField(fieldName);
            field.setAccessible(true);
            field.set(session, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Field launcherField(String fieldName)
            throws NoSuchFieldException {
        return switch (fieldName) {
            case "activeSession" -> TraceSessionLauncher.class
                    .getDeclaredField("activeSession");
            case "comparator" -> TraceSessionLauncher.class
                    .getDeclaredField("comparator");
            case "dynamicArtSegmentGameplayMode" -> TraceSessionLauncher.class
                    .getDeclaredField("dynamicArtSegmentGameplayMode");
            case "fixture" -> TraceSessionLauncher.class
                    .getDeclaredField("fixture");
            case "runAdvancer" -> TraceSessionLauncher.class
                    .getDeclaredField("runAdvancer");
            case "runBoundaryProbe" -> TraceSessionLauncher.class
                    .getDeclaredField("runBoundaryProbe");
            case "runCoordinator" -> TraceSessionLauncher.class
                    .getDeclaredField("runCoordinator");
            case "runDynamicArtSegments" -> TraceSessionLauncher.class
                    .getDeclaredField("runDynamicArtSegments");
            case "runExternalDiagnostics" -> TraceSessionLauncher.class
                    .getDeclaredField("runExternalDiagnostics");
            case "runFrameDriver" -> TraceSessionLauncher.class
                    .getDeclaredField("runFrameDriver");
            case "runHardwareTiming" -> TraceSessionLauncher.class
                    .getDeclaredField("runHardwareTiming");
            case "runSpecialLocalRow" -> TraceSessionLauncher.class
                    .getDeclaredField("runSpecialLocalRow");
            case "runSpecialRowDriver" -> TraceSessionLauncher.class
                    .getDeclaredField("runSpecialRowDriver");
            case "runSpecialVerificationPending" -> TraceSessionLauncher.class
                    .getDeclaredField("runSpecialVerificationPending");
            case "runVblankClock" -> TraceSessionLauncher.class
                    .getDeclaredField("runVblankClock");
            default -> throw new IllegalArgumentException(
                    "unsupported launcher field " + fieldName);
        };
    }

    private static void setObjectField(
            Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object getField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingTimingFixture
            implements TraceReplayFixture {
        private final List<Integer> rawFrames = new ArrayList<>();
        private final List<HardwareTimingSchedule> handoffs = new ArrayList<>();
        private int gaps;
        private final GameplayModeContext gameplayMode;

        private RecordingTimingFixture() {
            this(null);
        }

        private RecordingTimingFixture(GameplayModeContext gameplayMode) {
            this.gameplayMode = gameplayMode;
        }

        @Override
        public void beginTraceRow(int traceIndex, int rawFrame) {
            rawFrames.add(rawFrame);
        }

        @Override
        public void enterHardwareTimingGap() {
            gaps++;
        }

        @Override
        public void handoffHardwareTimingReplay(
                HardwareTimingSchedule nextSchedule) {
            handoffs.add(nextSchedule);
        }

        @Override
        public AbstractPlayableSprite sprite() {
            return null;
        }

        @Override
        public GameplayModeContext gameplayMode() {
            return gameplayMode;
        }

        @Override
        public void installHardwareTimingReplay(
                HardwareTimingReplayPort replayPort) {
        }

        @Override
        public void verifyHardwareTimingSegmentEdges() {
        }

        @Override
        public void closeHardwareTimingReplayRun() {
        }

        @Override
        public void abortHardwareTimingReplayRun() {
        }

        @Override
        public int stepFrameFromRecording() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int skipFrameFromRecording() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advancePlayableAnimationsOnly() {
        }

        @Override
        public void advancePlayableFixedSlotsOnly() {
        }

        @Override
        public void suppressFirstSidekickAnimationOnce() {
        }

        @Override
        public int consumeRecordingFrameInputOnly() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advanceRecordingCursor(int frameCount) {
            throw new UnsupportedOperationException();
        }
    }
}

/** Test-only adapter for package-level launcher tests with in-memory plans. */
final class TestRunPayloads {
    private TestRunPayloads() {
    }

    static TraceSessionLauncher session(
            com.openggf.trace.catalog.TraceEntry entry,
            Bk2Movie movie,
            List<TraceRunReplayWalker.SegmentPlan> plans,
            com.openggf.trace.replay.TraceReplaySessionBootstrap.ConfigSnapshot snapshot) {
        return session(entry, movie, plans, snapshot, (Runnable) null);
    }

    static TraceSessionLauncher session(
            com.openggf.trace.catalog.TraceEntry entry,
            Bk2Movie movie,
            List<TraceRunReplayWalker.SegmentPlan> plans,
            com.openggf.trace.replay.TraceReplaySessionBootstrap.ConfigSnapshot snapshot,
            java.util.concurrent.atomic.AtomicBoolean payloadClosed) {
        return session(entry, movie, plans, snapshot,
                () -> payloadClosed.set(true));
    }

    static TraceSessionLauncher session(
            com.openggf.trace.catalog.TraceEntry entry,
            Bk2Movie movie,
            List<TraceRunReplayWalker.SegmentPlan> plans,
            com.openggf.trace.replay.TraceReplaySessionBootstrap.ConfigSnapshot snapshot,
            java.util.concurrent.atomic.AtomicInteger payloadCloseCount) {
        return session(entry, movie, plans, snapshot,
                payloadCloseCount::incrementAndGet);
    }

    private static TraceSessionLauncher session(
            com.openggf.trace.catalog.TraceEntry entry,
            Bk2Movie movie,
            List<TraceRunReplayWalker.SegmentPlan> plans,
            com.openggf.trace.replay.TraceReplaySessionBootstrap.ConfigSnapshot snapshot,
            Runnable payloadClosed) {
        List<TraceRunSegmentDescriptor> descriptors = descriptors(plans);
        ActiveSegmentPayload initial = plans.isEmpty()
                ? null : payload(descriptors.getFirst(), plans.getFirst());
        TraceSessionLauncher session = new TraceSessionLauncher(
                entry, movie, descriptors, initial, snapshot);
        session.activeSegmentFactory = new TraceSessionLauncher.ActiveSegmentFactory() {
            @Override
            public ActiveSegmentPayload open(
                    TraceRunSegmentDescriptor descriptor, int segmentIndex) {
                return payload(descriptor, plans.get(segmentIndex));
            }

            @Override
            public void close(ActiveSegmentPayload payload) {
                payload.close();
                if (payloadClosed != null) {
                    payloadClosed.run();
                }
            }
        };
        return session;
    }

    static List<TraceRunSegmentDescriptor> descriptors(
            List<TraceRunReplayWalker.SegmentPlan> plans) {
        return plans.stream().map(TestRunPayloads::descriptor).toList();
    }

    static List<TraceRunReplayWalker.HardwareTimingSegment> hardwareTimingSegments(
            List<TraceRunReplayWalker.SegmentPlan> plans) {
        return plans.stream().map(plan -> {
            int parsedRows = plan.trace().frameCount();
            int representedRows = parsedRows > 0
                    ? parsedRows : plan.segment().traceFrameCount();
            List<Integer> rawFrames = new ArrayList<>(representedRows);
            for (int row = 0; row < representedRows; row++) {
                rawFrames.add(parsedRows > 0
                        ? plan.trace().getFrame(row).frame() : row);
            }
            return new TraceRunReplayWalker.HardwareTimingSegment(
                    plan.segment().bk2FrameOffset(), rawFrames,
                    plan.trace().hardwareTimingSchedule());
        }).toList();
    }

    private static TraceRunSegmentDescriptor descriptor(
            TraceRunReplayWalker.SegmentPlan plan) {
        int rowCount = plan.segment().traceFrameCount();
        List<Integer> rawFrames = new ArrayList<>(rowCount);
        BitSet lagged = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++) {
            if (row < plan.trace().frameCount()) {
                rawFrames.add(plan.trace().getFrame(row).frame());
                var lagState = plan.trace().lagStateForFrame(row);
                if (lagState != null && lagState.lagged()) {
                    lagged.set(row);
                }
            } else {
                rawFrames.add(row);
            }
        }
        boolean special = "special_stage".equals(plan.segment().kind());
        return new TraceRunSegmentDescriptor(
                plan.segment(), Path.of(plan.segment().dir()),
                plan.trace().metadata(), rowCount,
                special ? null : plan.trace().getFrame(0), rawFrames, lagged,
                plan.trace().hardwareTimingSchedule(),
                plan.trace().terminalDynamicArtLedger(), plan.entryBoundary(),
                plan.exitBoundary(), special ? 0 : Math.min(rowCount,
                        TraceRunReplayWalker.levelLoopRowCount(plan.trace())),
                plan.executionPolicy());
    }

    private static ActiveSegmentPayload payload(
            TraceRunSegmentDescriptor descriptor,
            TraceRunReplayWalker.SegmentPlan plan) {
        try {
            var constructor = ActiveSegmentPayload.class.getDeclaredConstructor(
                    TraceRunSegmentDescriptor.class, TraceData.class,
                    com.openggf.trace.replay.runs.TraceRunSpecialStageRows.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    descriptor, plan.trace(), plan.specialStageRows());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
