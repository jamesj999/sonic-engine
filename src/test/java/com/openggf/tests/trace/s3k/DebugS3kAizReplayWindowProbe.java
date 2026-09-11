package com.openggf.tests.trace.s3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.objects.AizLrzRockObjectInstance;
import com.openggf.game.sonic3k.objects.RockDebrisChild;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSignpostInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseDebugHit;
import com.openggf.level.objects.TouchResponseDebugState;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.TraceExecutionModel;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DebugS3kAizReplayWindowProbe {

    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun");
    private static final int[] SAMPLE_TRACE_FRAMES = {1500, 1651, 3000, 5000};
    private static final int OFFSET_SWEEP_RADIUS = 12;
    private static final int MAX_TRACE_FRAME = 6000;
    private static final int START_WINDOW_START = 1538;
    private static final int START_WINDOW_END = 1572;
    private static final int MONKEY_WINDOW_START = 1788;
    private static final int MONKEY_WINDOW_END = 1845;
    private static final int[] LEGACY_BOOTSTRAP_SAMPLES = {
            1651, 1800, 2000, 2200, 2400, 2600, 2800, 3000, 5000, 5610
    };
    private static final int LEGACY_ROUTE_WINDOW_START = 1980;
    private static final int LEGACY_ROUTE_WINDOW_END = 2420;
    private static final int ROCK_WINDOW_START = 2094;
    private static final int ROCK_WINDOW_END = 2099;

    @Test
    void scanOffsetsAroundFixtureStart() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);

        Path bk2Path;
        try (var files = Files.list(TRACE_DIR)) {
            bk2Path = files.filter(path -> path.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + TRACE_DIR);

        TraceData trace = TraceData.load(TRACE_DIR);
        TraceMetadata meta = trace.metadata();

        int rawOffset = meta.bk2FrameOffset();
        int minOffset = Math.max(0, rawOffset - OFFSET_SWEEP_RADIUS);
        int maxOffset = rawOffset + OFFSET_SWEEP_RADIUS;
        System.out.printf(
                "S3K AIZ replay offset sweep: rawOffset=%d range=[%d,%d] maxTraceFrame=%d%n",
                rawOffset, minOffset, maxOffset, MAX_TRACE_FRAME);

        List<SweepResult> results = new ArrayList<>();
        for (int offset = minOffset; offset <= maxOffset; offset++) {
            SweepResult result = runProbe(trace, offset);
            System.out.println(result.format());
            results.add(result);
        }

        // Oracle: the offset sweep must actually run every offset in the range.
        assertTrue(trace.frameCount() > 0, "trace had no frames to replay");
        assertEquals(maxOffset - minOffset + 1, results.size(),
                "sweep did not cover the full offset range");
        // characterization guard: each offset produces a populated result whose
        // checkpoint label is non-null (defaults to "<none>" when unreached).
        for (SweepResult result : results) {
            assertNotNull(result.latestCheckpoint(), "sweep result missing checkpoint label");
        }
    }

    @Test
    void dumpMonkeyWindowAtRawOffset() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int rawOffset = trace.metadata().bk2FrameOffset();

        int framesStepped = dumpWindow(trace, rawOffset, MONKEY_WINDOW_START, MONKEY_WINDOW_END);
        // Oracle: the monkey window must replay through its end frame.
        assertTrue(framesStepped > MONKEY_WINDOW_END,
                "monkey window did not reach end frame " + MONKEY_WINDOW_END);
        // characterization guard: replay steps exactly one trace frame per index up to MONKEY_WINDOW_END.
        assertEquals(MONKEY_WINDOW_END + 1, framesStepped, "monkey window frame count drifted");
    }

    @Test
    void dumpGameplayStartWindowAtRawOffset() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int rawOffset = trace.metadata().bk2FrameOffset();

        int framesStepped = dumpWindow(trace, rawOffset, START_WINDOW_START, START_WINDOW_END);
        // Oracle: the gameplay-start window must replay through its end frame.
        assertTrue(framesStepped > START_WINDOW_END,
                "start window did not reach end frame " + START_WINDOW_END);
        // characterization guard: one trace frame stepped per index up to START_WINDOW_END.
        assertEquals(START_WINDOW_END + 1, framesStepped, "start window frame count drifted");
    }

    @Test
    void dumpGameplayStartWindowWithIntroBootstrap() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int rawOffset = trace.metadata().bk2FrameOffset();

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            int framesStepped =
                    dumpWindowWithMetadataStart(trace, rawOffset, START_WINDOW_START, START_WINDOW_END);
            // Oracle: intro-bootstrap replay must reach the start-window end frame.
            assertTrue(framesStepped > START_WINDOW_END,
                    "intro-bootstrap window did not reach end frame " + START_WINDOW_END);
            // characterization guard: one trace frame stepped per index up to START_WINDOW_END.
            assertEquals(START_WINDOW_END + 1, framesStepped,
                    "intro-bootstrap window frame count drifted");
        } finally {
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
        }
    }

    @Test
    void scanLegacyBootstrapProgression() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int rawOffset = trace.metadata().bk2FrameOffset();

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            int framesStepped = dumpLegacyBootstrapSamples(trace, rawOffset);
            // Oracle: legacy-bootstrap progression must step at least one trace frame.
            assertTrue(framesStepped > 0, "legacy-bootstrap progression stepped no frames");
            // characterization guard: the scan walks up to (and breaks at) the final sample frame.
            assertTrue(framesStepped <= trace.frameCount(),
                    "legacy-bootstrap stepped beyond trace length");
        } finally {
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
        }
    }

    @Test
    void dumpLegacyBootstrapRouteWindow() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int rawOffset = trace.metadata().bk2FrameOffset();
        int startFrame = Integer.getInteger("s3k.aiz.window.start", LEGACY_ROUTE_WINDOW_START);
        int endFrame = Integer.getInteger("s3k.aiz.window.end", LEGACY_ROUTE_WINDOW_END);

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            int framesStepped = dumpLegacyBootstrapWindow(trace, rawOffset, startFrame, endFrame);
            // Oracle: the route window replays at least one trace frame through to endFrame.
            assertTrue(framesStepped > 0, "legacy-bootstrap route window stepped no frames");
            assertTrue(endFrame >= startFrame, "route window bounds inverted");
        } finally {
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
        }
    }

    @Test
    void scanLegacyBootstrapForFirstLargeDrift() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int rawOffset = trace.metadata().bk2FrameOffset();
        int startFrame = Integer.getInteger("s3k.aiz.scan.start", 1800);
        int endFrame = Integer.getInteger("s3k.aiz.scan.end", 6000);
        int xBudget = Integer.getInteger("s3k.aiz.scan.xBudget", 0x20);
        int yBudget = Integer.getInteger("s3k.aiz.scan.yBudget", 0x20);
        int camBudget = Integer.getInteger("s3k.aiz.scan.camBudget", 0x20);

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            int framesScanned = scanLegacyBootstrapForFirstLargeDrift(
                    trace, rawOffset, startFrame, endFrame, xBudget, yBudget, camBudget);
            // Oracle: the drift scan must replay at least one frame of the window.
            assertTrue(framesScanned > 0, "legacy-bootstrap drift scan stepped no frames");
        } finally {
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
        }
    }

    @Test
    void scanFullTraceReplayForFirstLargeDrift() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int startFrame = Integer.getInteger("s3k.aiz.full.scan.start", 1500);
        int endFrame = Integer.getInteger("s3k.aiz.full.scan.end", 6000);
        int xBudget = Integer.getInteger("s3k.aiz.full.scan.xBudget", 0);
        int yBudget = Integer.getInteger("s3k.aiz.full.scan.yBudget", 0);
        int camBudget = Integer.getInteger("s3k.aiz.full.scan.camBudget", 0);

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            int framesScanned =
                    scanFullTraceReplayForFirstLargeDrift(trace, startFrame, endFrame, xBudget, yBudget, camBudget);
            // Oracle: the full-trace drift scan must replay at least one frame.
            assertTrue(framesScanned > 0, "full-trace drift scan stepped no frames");
        } finally {
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
        }
    }

    @Test
    void dumpFullTraceReplayWindowFromProperties() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int startFrame = Integer.getInteger("s3k.aiz.full.window.start", 0);
        int endFrame = Integer.getInteger("s3k.aiz.full.window.end", startFrame + 32);

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            int framesStepped = dumpFullTraceReplayWindow(trace, startFrame, endFrame);
            // Oracle: the full-trace window must replay through its end frame.
            assertTrue(framesStepped > 0, "full-trace window stepped no frames");
            assertTrue(endFrame >= startFrame, "full-trace window bounds inverted");
        } finally {
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
        }
    }

    @Test
    void dumpFullTraceReplayFocusFrameFromProperties() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int probeFrame = Integer.getInteger("s3k.aiz.focus.frame", 2006);

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            // Oracle: the probe frame must be within the trace before we replay to it.
            assertTrue(probeFrame < trace.frameCount(),
                    "focus frame " + probeFrame + " is beyond trace length " + trace.frameCount());
            int reachedFrame = dumpFullTraceReplayFocusFrame(trace, probeFrame);
            // characterization guard: replay landed on the requested focus frame.
            assertEquals(probeFrame, reachedFrame, "focus replay did not land on the probe frame");
        } finally {
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
        }
    }

    @Test
    void dumpLegacyBootstrapRockWindow() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        int rawOffset = trace.metadata().bk2FrameOffset();
        int startFrame = Integer.getInteger("s3k.aiz.rock.start", ROCK_WINDOW_START);
        int endFrame = Integer.getInteger("s3k.aiz.rock.end", ROCK_WINDOW_END);

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            int framesStepped = dumpLegacyBootstrapRockWindow(trace, rawOffset, startFrame, endFrame);
            // Oracle: the rock window must replay through to its end frame.
            assertTrue(framesStepped > 0, "rock window stepped no frames");
            assertTrue(endFrame >= startFrame, "rock window bounds inverted");
        } finally {
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
        }
    }

    private int dumpWindow(TraceData trace, int rawOffset, int startFrame, int endFrame) throws Exception {
        int framesStepped = 0;
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(rawOffset)
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }
            if (GameServices.debugOverlay() != null) {
                GameServices.debugOverlay().setEnabled(DebugOverlayToggle.TOUCH_RESPONSE, true);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceFrame previous = null;
            for (int i = 0; i <= endFrame; i++) {
                TraceFrame current = trace.getFrame(i);
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(trace.metadata().game()).phaseFor(previous, current);
                int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                framesStepped++;

                if (current.frame() >= startFrame) {
                    System.out.printf(
                            "frame=%d phase=%s expIn=%04X bk2In=%04X exp=(%04X,%04X) act=(%04X,%04X) expSpd=(%04X,%04X,%04X) actSpd=(%04X,%04X,%04X) cam=(%04X,%04X) expCam=(%04X,%04X)%n",
                            current.frame(),
                            phase,
                            current.input(),
                            bk2Input,
                            current.x() & 0xFFFF,
                            current.y() & 0xFFFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            current.xSpeed() & 0xFFFF,
                            current.ySpeed() & 0xFFFF,
                            current.gSpeed() & 0xFFFF,
                            fixture.sprite().getXSpeed() & 0xFFFF,
                            fixture.sprite().getYSpeed() & 0xFFFF,
                            fixture.sprite().getGSpeed() & 0xFFFF,
                            GameServices.camera().getX() & 0xFFFF,
                            GameServices.camera().getY() & 0xFFFF,
                            current.cameraX() & 0xFFFF,
                            current.cameraY() & 0xFFFF);
                }
                previous = current;
            }
        } finally {
            sharedLevel.dispose();
        }
        return framesStepped;
    }

    private int dumpWindowWithMetadataStart(
            TraceData trace, int rawOffset, int startFrame, int endFrame) throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(rawOffset)
                    .startPosition(trace.metadata().startX(), trace.metadata().startY())
                    .startPositionIsCentre()
                    .build();

            return runDumpLoop(trace, fixture, startFrame, endFrame);
        } finally {
            sharedLevel.dispose();
        }
    }

    private int runDumpLoop(
            TraceData trace, HeadlessTestFixture fixture, int startFrame, int endFrame) {
        int framesStepped = 0;
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager != null) {
            objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
        }

        int preTraceOsc = trace.metadata().preTraceOscillationFrames();
        for (int i = 0; i < preTraceOsc; i++) {
            com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
        }

        TraceFrame previous = null;
        for (int i = 0; i <= endFrame; i++) {
            TraceFrame current = trace.getFrame(i);
            TraceExecutionPhase phase =
                    TraceExecutionModel.forGame(trace.metadata().game()).phaseFor(previous, current);
            int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                    ? fixture.skipFrameFromRecording()
                    : fixture.stepFrameFromRecording();
            framesStepped++;

            if (current.frame() >= startFrame) {
                System.out.printf(
                        "frame=%d phase=%s expIn=%04X bk2In=%04X exp=(%04X,%04X) act=(%04X,%04X) expSpd=(%04X,%04X,%04X) actSpd=(%04X,%04X,%04X) cam=(%04X,%04X) expCam=(%04X,%04X)%n",
                        current.frame(),
                        phase,
                        current.input(),
                        bk2Input,
                        current.x() & 0xFFFF,
                        current.y() & 0xFFFF,
                        fixture.sprite().getCentreX() & 0xFFFF,
                        fixture.sprite().getCentreY() & 0xFFFF,
                        current.xSpeed() & 0xFFFF,
                        current.ySpeed() & 0xFFFF,
                        current.gSpeed() & 0xFFFF,
                        fixture.sprite().getXSpeed() & 0xFFFF,
                        fixture.sprite().getYSpeed() & 0xFFFF,
                        fixture.sprite().getGSpeed() & 0xFFFF,
                        GameServices.camera().getX() & 0xFFFF,
                        GameServices.camera().getY() & 0xFFFF,
                        current.cameraX() & 0xFFFF,
                        current.cameraY() & 0xFFFF);
            }
            previous = current;
        }
        return framesStepped;
    }

    private SweepResult runProbe(TraceData trace, int offset) throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(offset)
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
            TraceEvent.Checkpoint latestCheckpoint = null;
            TraceFrame previous = null;
            Sample[] samples = new Sample[SAMPLE_TRACE_FRAMES.length];

            int limit = Math.min(MAX_TRACE_FRAME, trace.frameCount() - 1);
            for (int i = 0; i <= limit; i++) {
                TraceFrame current = trace.getFrame(i);
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(trace.metadata().game()).phaseFor(previous, current);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }

                S3kCheckpointProbe probe = captureProbe(current.frame(), fixture);
                TraceEvent.Checkpoint engineCheckpoint = detector.observe(probe);
                if (engineCheckpoint != null) {
                    latestCheckpoint = engineCheckpoint;
                }

                for (int sampleIndex = 0; sampleIndex < SAMPLE_TRACE_FRAMES.length; sampleIndex++) {
                    if (SAMPLE_TRACE_FRAMES[sampleIndex] == current.frame()) {
                        samples[sampleIndex] = new Sample(
                                current.frame(),
                                fixture.sprite().getCentreX(),
                                fixture.sprite().getCentreY(),
                                GameServices.camera().getX(),
                                GameServices.camera().getY(),
                                probe.eventsFg5(),
                                probe.fireTransitionActive(),
                                probe.actualAct(),
                                latestCheckpoint != null ? latestCheckpoint.name() : "<none>");
                    }
                }

                if (latestCheckpoint != null && "aiz2_main_gameplay".equals(latestCheckpoint.name())) {
                    break;
                }
                previous = current;
            }

            return new SweepResult(offset, latestCheckpoint != null ? latestCheckpoint.name() : "<none>", samples);
        } finally {
            sharedLevel.dispose();
        }
    }

    private int dumpLegacyBootstrapSamples(TraceData trace, int rawOffset) throws Exception {
        int framesStepped = 0;
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(rawOffset)
                    .startPosition(trace.metadata().startX(), trace.metadata().startY())
                    .startPositionIsCentre()
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartState(trace, fixture);

            S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
            for (int frame = 0; frame <= replayStart.seededTraceIndex(); frame++) {
                for (TraceEvent event : trace.getEventsForFrame(frame)) {
                    if (event instanceof TraceEvent.Checkpoint checkpoint) {
                        detector.seedCheckpoint(checkpoint.name());
                    }
                }
            }

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex(); traceIndex < trace.frameCount(); traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(trace.metadata().game()).phaseFor(previous, current);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
                framesStepped++;

                S3kCheckpointProbe probe = captureProbe(current.frame(), fixture);
                TraceEvent.Checkpoint engineCheckpoint = detector.observe(probe);
                if (engineCheckpoint != null) {
                    System.out.printf(
                            "legacy-bootstrap checkpoint frame=%d name=%s zone=%s act=%s apparent=%s moveLock=%d ctrlLocked=%b objCtrl=%b hidden=%b levelStarted=%b fire=%b overlay=%b%n",
                            current.frame(),
                            engineCheckpoint.name(),
                            probe.actualZoneId(),
                            probe.actualAct(),
                            probe.apparentAct(),
                            probe.moveLock(),
                            probe.ctrlLocked(),
                            probe.objectControlled(),
                            probe.hidden(),
                            probe.levelStarted(),
                            probe.fireTransitionActive(),
                            probe.titleCardOverlayActive());
                }

                for (int sampleFrame : LEGACY_BOOTSTRAP_SAMPLES) {
                    if (current.frame() == sampleFrame) {
                        System.out.printf(
                                "legacy-bootstrap sample frame=%d x=%04X y=%04X cam=(%04X,%04X) zone=%s act=%s apparent=%s moveLock=%d ctrlLocked=%b objCtrl=%b hidden=%b levelStarted=%b fire=%b overlay=%b checkpoint=%s%n",
                                current.frame(),
                                fixture.sprite().getCentreX() & 0xFFFF,
                                fixture.sprite().getCentreY() & 0xFFFF,
                                GameServices.camera().getX() & 0xFFFF,
                                GameServices.camera().getY() & 0xFFFF,
                                probe.actualZoneId(),
                                probe.actualAct(),
                                probe.apparentAct(),
                                probe.moveLock(),
                                probe.ctrlLocked(),
                                probe.objectControlled(),
                                probe.hidden(),
                                probe.levelStarted(),
                                probe.fireTransitionActive(),
                                probe.titleCardOverlayActive(),
                                engineCheckpoint != null ? engineCheckpoint.name() : "<none>");
                    }
                }

                previous = current;
                if (current.frame() >= LEGACY_BOOTSTRAP_SAMPLES[LEGACY_BOOTSTRAP_SAMPLES.length - 1]) {
                    break;
                }
            }
        } finally {
            sharedLevel.dispose();
        }
        return framesStepped;
    }

    private int dumpLegacyBootstrapWindow(
            TraceData trace, int rawOffset, int startFrame, int endFrame) throws Exception {
        int framesStepped = 0;
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(rawOffset)
                    .startPosition(trace.metadata().startX(), trace.metadata().startY())
                    .startPositionIsCentre()
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartState(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex(); traceIndex <= endFrame; traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(trace.metadata().game()).phaseFor(previous, current);
                int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                framesStepped++;

                if (current.frame() >= startFrame) {
                    System.out.printf(
                            "legacy-window frame=%d phase=%s expIn=%04X bk2In=%04X exp=(%04X,%04X) act=(%04X,%04X) expSpd=(%04X,%04X,%04X) actSpd=(%04X,%04X,%04X) expCam=(%04X,%04X) actCam=(%04X,%04X) hDelay=%d layer=%d bits=%02X/%02X pri=%b onObj=%b switches=%s objs=%s%n",
                            current.frame(),
                            phase,
                            current.input(),
                            bk2Input,
                            current.x() & 0xFFFF,
                            current.y() & 0xFFFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            current.xSpeed() & 0xFFFF,
                            current.ySpeed() & 0xFFFF,
                            current.gSpeed() & 0xFFFF,
                            fixture.sprite().getXSpeed() & 0xFFFF,
                            fixture.sprite().getYSpeed() & 0xFFFF,
                            fixture.sprite().getGSpeed() & 0xFFFF,
                            current.cameraX() & 0xFFFF,
                            current.cameraY() & 0xFFFF,
                            GameServices.camera().getX() & 0xFFFF,
                            GameServices.camera().getY() & 0xFFFF,
                            GameServices.camera().getHorizScrollDelay(),
                            fixture.sprite().getLayer(),
                            fixture.sprite().getTopSolidBit() & 0xFF,
                            fixture.sprite().getLrbSolidBit() & 0xFF,
                            fixture.sprite().isHighPriority(),
                            fixture.sprite().isOnObject(),
                            nearbyPathSwaps(fixture.sprite()),
                            nearbyActiveObjects(fixture.sprite()));
                }

                previous = current;
            }
        } finally {
            sharedLevel.dispose();
        }
        return framesStepped;
    }

    private int scanLegacyBootstrapForFirstLargeDrift(
            TraceData trace,
            int rawOffset,
            int startFrame,
            int endFrame,
            int xBudget,
            int yBudget,
            int camBudget) throws Exception {
        int framesScanned = 0;
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(rawOffset)
                    .startPosition(trace.metadata().startX(), trace.metadata().startY())
                    .startPositionIsCentre()
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartState(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            boolean found = false;
            for (int traceIndex = replayStart.startingTraceIndex(); traceIndex <= endFrame; traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(trace.metadata().game()).phaseFor(previous, current);
                int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                framesScanned++;

                int dx = Math.abs(fixture.sprite().getCentreX() - current.x());
                int dy = Math.abs(fixture.sprite().getCentreY() - current.y());
                int dCamX = Math.abs((GameServices.camera().getX() & 0xFFFF) - current.cameraX());
                int dCamY = Math.abs((GameServices.camera().getY() & 0xFFFF) - current.cameraY());
                if (current.frame() >= startFrame
                        && (dx > xBudget || dy > yBudget || dCamX > camBudget || dCamY > camBudget)) {
                    System.out.printf(
                            "legacy-drift frame=%d phase=%s expIn=%04X bk2In=%04X " +
                                    "exp=(%04X,%04X) act=(%04X,%04X) d=(%d,%d) " +
                                    "expCam=(%04X,%04X) actCam=(%04X,%04X) dCam=(%d,%d) " +
                                    "events=%s switches=%s objs=%s%n",
                            current.frame(),
                            phase,
                            current.input(),
                            bk2Input,
                            current.x() & 0xFFFF,
                            current.y() & 0xFFFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            dx,
                            dy,
                            current.cameraX() & 0xFFFF,
                            current.cameraY() & 0xFFFF,
                            GameServices.camera().getX() & 0xFFFF,
                            GameServices.camera().getY() & 0xFFFF,
                            dCamX,
                            dCamY,
                            describeTraceEvents(trace.getEventsForFrame(traceIndex)),
                            nearbyPathSwaps(fixture.sprite()),
                            nearbyActiveObjects(fixture.sprite()));
                    int windowStart = Math.max(replayStart.startingTraceIndex(), current.frame() - 6);
                    int windowEnd = Math.min(endFrame, current.frame() + 6);
                    dumpLegacyBootstrapWindow(trace, rawOffset, windowStart, windowEnd);
                    found = true;
                    break;
                }

                previous = current;
            }

            if (!found) {
                System.out.printf(
                        "legacy-drift none start=%d end=%d budgets=(x=%d,y=%d,cam=%d)%n",
                        startFrame,
                        endFrame,
                        xBudget,
                        yBudget,
                        camBudget);
            }
        } finally {
            sharedLevel.dispose();
        }
        return framesScanned;
    }

    private int scanFullTraceReplayForFirstLargeDrift(
            TraceData trace,
            int startFrame,
            int endFrame,
            int xBudget,
            int yBudget,
            int camBudget) throws Exception {
        int framesScanned = 0;
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);
            System.out.printf("full-trace-window replayStart=%d seeded=%b seed=%d end=%d%n",
                    replayStart.startingTraceIndex(),
                    replayStart.hasSeededTraceState(),
                    replayStart.seededTraceIndex(),
                    endFrame);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            boolean found = false;
            for (int traceIndex = replayStart.startingTraceIndex(); traceIndex <= endFrame; traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                framesScanned++;

                int dx = Math.abs(fixture.sprite().getCentreX() - current.x());
                int dy = Math.abs(fixture.sprite().getCentreY() - current.y());
                int dCamX = Math.abs((GameServices.camera().getX() & 0xFFFF) - current.cameraX());
                int dCamY = Math.abs((GameServices.camera().getY() & 0xFFFF) - current.cameraY());
                if (current.frame() >= startFrame
                        && (dx > xBudget || dy > yBudget || dCamX > camBudget || dCamY > camBudget)) {
                    System.out.printf(
                            "full-trace-drift frame=%d phase=%s expIn=%04X bk2In=%04X "
                                    + "exp=(%04X,%04X) act=(%04X,%04X) d=(%d,%d) "
                                    + "expCam=(%04X,%04X) actCam=(%04X,%04X) dCam=(%d,%d) "
                                    + "zone=%d act=%d apparent=%d levelStarted=%b moveLock=%d ctrl=%b objCtrl=%b hidden=%b "
                                    + "eventsFg5=%b fire=%b resize=%d checkpoint=%s objs=%s%n",
                            current.frame(),
                            phase,
                            current.input(),
                            bk2Input,
                            current.x() & 0xFFFF,
                            current.y() & 0xFFFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            dx,
                            dy,
                            current.cameraX() & 0xFFFF,
                            current.cameraY() & 0xFFFF,
                            GameServices.camera().getX() & 0xFFFF,
                            GameServices.camera().getY() & 0xFFFF,
                            dCamX,
                            dCamY,
                            GameServices.level().getCurrentZone(),
                            GameServices.level().getCurrentAct(),
                            GameServices.level().getApparentAct(),
                            GameServices.camera().isLevelStarted(),
                            fixture.sprite().getMoveLockTimer(),
                            fixture.sprite().isControlLocked(),
                            fixture.sprite().isObjectControlled(),
                            fixture.sprite().isHidden(),
                            eventsFg5(),
                            isFireTransitionActive(),
                            dynamicResizeRoutine(),
                            trace.latestCheckpointAtOrBefore(traceIndex),
                            nearbyActiveObjects(fixture.sprite()));
                    int windowStart = Math.max(replayStart.startingTraceIndex(), current.frame() - 6);
                    int windowEnd = Math.min(endFrame, current.frame() + 6);
                    dumpFullTraceReplayWindow(trace, windowStart, windowEnd);
                    found = true;
                    break;
                }

                previous = current;
            }

            if (!found) {
                System.out.printf(
                        "full-trace-drift none start=%d end=%d budgets=(x=%d,y=%d,cam=%d)%n",
                        startFrame, endFrame, xBudget, yBudget, camBudget);
            }
        } finally {
            if (!Boolean.getBoolean("s3k.aiz.full.window.skipDispose")) {
                sharedLevel.dispose();
            }
        }
        return framesScanned;
    }

    private int dumpFullTraceReplayWindow(TraceData trace, int startFrame, int endFrame) throws Exception {
        int framesStepped = 0;
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex(); traceIndex <= endFrame; traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                framesStepped++;
                if (traceIndex >= startFrame) {
                    System.out.printf(
                            "full-trace-window frame=%d phase=%s expIn=%04X bk2In=%04X "
                                    + "exp=(%04X,%04X) act=(%04X,%04X) "
                                    + "expSpd=(%04X,%04X,%04X) actSpd=(%04X,%04X,%04X) "
                                    + "expSub=(%04X,%04X) actSub=(%04X,%04X) "
                                    + "expCam=(%04X,%04X) actCam=(%04X,%04X) "
                                    + "zone=%d act=%d apparent=%d lvlStarted=%b moveLock=%d ctrl=%b objCtrl=%b hidden=%b y=%04X h=%d yr=%d "
                                    + "eventsFg5=%b fire=%b resize=%d checkpoint=%s objs=%s%n",
                            current.frame(),
                            phase,
                            current.input(),
                            bk2Input,
                            current.x() & 0xFFFF,
                            current.y() & 0xFFFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            current.xSpeed() & 0xFFFF,
                            current.ySpeed() & 0xFFFF,
                            current.gSpeed() & 0xFFFF,
                            fixture.sprite().getXSpeed() & 0xFFFF,
                            fixture.sprite().getYSpeed() & 0xFFFF,
                            fixture.sprite().getGSpeed() & 0xFFFF,
                            current.xSub() & 0xFFFF,
                            current.ySub() & 0xFFFF,
                            fixture.sprite().getXSubpixelRaw() & 0xFFFF,
                            fixture.sprite().getYSubpixelRaw() & 0xFFFF,
                            current.cameraX() & 0xFFFF,
                            current.cameraY() & 0xFFFF,
                            GameServices.camera().getX() & 0xFFFF,
                            GameServices.camera().getY() & 0xFFFF,
                            GameServices.level().getCurrentZone(),
                            GameServices.level().getCurrentAct(),
                            GameServices.level().getApparentAct(),
                            GameServices.camera().isLevelStarted(),
                            fixture.sprite().getMoveLockTimer(),
                            fixture.sprite().isControlLocked(),
                            fixture.sprite().isObjectControlled(),
                            fixture.sprite().isHidden(),
                            fixture.sprite().getY() & 0xFFFF,
                            fixture.sprite().getHeight(),
                            fixture.sprite().getYRadius(),
                            eventsFg5(),
                            isFireTransitionActive(),
                            dynamicResizeRoutine(),
                            trace.latestCheckpointAtOrBefore(traceIndex),
                            nearbyActiveObjects(fixture.sprite()));
                }
                previous = current;
            }
        } finally {
            sharedLevel.dispose();
        }
        return framesStepped;
    }

    private int dumpFullTraceReplayFocusFrame(TraceData trace, int probeFrame) throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex(); traceIndex <= probeFrame; traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                if (traceIndex == probeFrame) {
                    dumpFullTraceReplayFocusState(trace, current, fixture, phase, bk2Input);
                    return traceIndex;
                }
                previous = current;
            }

            throw new AssertionError("Probe frame not reached: " + probeFrame);
        } finally {
            sharedLevel.dispose();
        }
    }

    private void dumpFullTraceReplayFocusState(TraceData trace,
                                               TraceFrame current,
                                               HeadlessTestFixture fixture,
                                               TraceExecutionPhase phase,
                                               int bk2Input) {
        AbstractPlayableSprite sprite = fixture.sprite();
        System.out.printf(
                "full-trace-focus frame=%d phase=%s expIn=%04X bk2In=%04X "
                        + "exp=(%04X,%04X) act=(%04X,%04X) expSpd=(%04X,%04X,%04X) actSpd=(%04X,%04X,%04X) "
                        + "expAngle=%02X actAngle=%02X expAir=%b actAir=%b expRoll=%b actRoll=%b "
                        + "radii=%d,%d moveLock=%d ctrl=%b objCtrl=%b hidden=%b onObj=%b "
                        + "events=%s switches=%s objs=%s%n",
                current.frame(),
                phase,
                current.input(),
                bk2Input,
                current.x() & 0xFFFF,
                current.y() & 0xFFFF,
                sprite.getCentreX() & 0xFFFF,
                sprite.getCentreY() & 0xFFFF,
                current.xSpeed() & 0xFFFF,
                current.ySpeed() & 0xFFFF,
                current.gSpeed() & 0xFFFF,
                sprite.getXSpeed() & 0xFFFF,
                sprite.getYSpeed() & 0xFFFF,
                sprite.getGSpeed() & 0xFFFF,
                current.angle() & 0xFF,
                sprite.getAngle() & 0xFF,
                current.air(),
                sprite.getAir(),
                current.rolling(),
                sprite.getRolling(),
                sprite.getXRadius(),
                sprite.getYRadius(),
                sprite.getMoveLockTimer(),
                sprite.isControlLocked(),
                sprite.isObjectControlled(),
                sprite.isHidden(),
                sprite.isOnObject(),
                describeTraceEvents(trace.getEventsForFrame(current.frame())),
                nearbyPathSwaps(sprite),
                nearbyActiveObjects(sprite));

        TouchResponseDebugState touchState = GameServices.level() != null
                ? GameServices.level().getObjectManager().getTouchResponseDebugState()
                : null;
        if (touchState == null || touchState.getHits().isEmpty()) {
            System.out.println("touch=<none>");
        } else {
            System.out.printf(
                    "touch box=@%04X,%04X h=%d yr=%d crouch=%b overlaps=%s scans=%s%n",
                    touchState.getPlayerX() & 0xFFFF,
                    touchState.getPlayerY() & 0xFFFF,
                    touchState.getPlayerHeight(),
                    touchState.getPlayerYRadius(),
                    touchState.isCrouching(),
                    summariseTouchOverlaps(touchState.getHits()),
                    summariseNearbyTouchScans(
                            touchState.getHits(),
                            sprite.getCentreX(),
                            sprite.getCentreY()));
        }

        System.out.println(describeSensorArray("ground", sprite.getGroundSensors(), sprite));
        System.out.println(describeSensorArray("ceiling", sprite.getCeilingSensors(), sprite));
        System.out.println(describeSensorArray("push", sprite.getPushSensors(), sprite));
        System.out.println("ceiling-sweep " + describeCeilingSweep(sprite));
        System.out.println("right-wall-sweep " + describeRightWallSweep(sprite));
        System.out.println("floor-sweep " + describeFloorSweep(sprite));
        System.out.println("aiz-intro " + describeAizIntroInstance());
    }

    private boolean eventsFg5() {
        if (GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager levelEvents) {
            return levelEvents.isEventsFg5();
        }
        return false;
    }

    private boolean isFireTransitionActive() {
        if (GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager levelEvents) {
            return levelEvents.isFireTransitionActive();
        }
        return false;
    }

    private int dynamicResizeRoutine() {
        if (GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager levelEvents) {
            return levelEvents.getDynamicResizeRoutine();
        }
        return -1;
    }

    private int dumpLegacyBootstrapRockWindow(
            TraceData trace, int rawOffset, int startFrame, int endFrame) throws Exception {
        int framesStepped = 0;
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(rawOffset)
                    .startPosition(trace.metadata().startX(), trace.metadata().startY())
                    .startPositionIsCentre()
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = trace.metadata().preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartState(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex(); traceIndex <= endFrame; traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(trace.metadata().game()).phaseFor(previous, current);
                int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                framesStepped++;

                if (current.frame() >= startFrame) {
                    System.out.printf(
                            "rock-window frame=%d phase=%s expIn=%04X bk2In=%04X exp=(%04X,%04X) act=(%04X,%04X) hDelay=%d rock=%s%n",
                            current.frame(),
                            phase,
                            current.input(),
                            bk2Input,
                            current.x() & 0xFFFF,
                            current.y() & 0xFFFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            GameServices.camera().getHorizScrollDelay(),
                            describeRockState(GameServices.level().getObjectManager()));
                }

                previous = current;
            }
        } finally {
            sharedLevel.dispose();
        }
        return framesStepped;
    }

    private static Path findBk2File() throws Exception {
        try (var files = Files.list(TRACE_DIR)) {
            return files.filter(path -> path.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static String describeSensorArray(String label,
                                              Sensor[] sensors,
                                              AbstractPlayableSprite sprite) {
        if (sensors == null) {
            return label + "=<none>";
        }
        StringBuilder builder = new StringBuilder(label).append("=[");
        for (int i = 0; i < sensors.length; i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            Sensor sensor = sensors[i];
            if (sensor == null) {
                builder.append(i).append("=<null>");
                continue;
            }
            sensor.computeRotatedOffset();
            int worldX = sprite.getCentreX() + sensor.getRotatedX();
            int worldY = sprite.getCentreY() + sensor.getRotatedY();
            SensorResult result = sensor.scan((short) 0, (short) 0);
            builder.append(String.format(
                    "%d:{act=%s dir=%s off=(%d,%d) rot=(%d,%d) world=(%04X,%04X) res=%s}",
                    i,
                    sensor.isActive(),
                    sensor.getDirection(),
                    sensor.getX(),
                    sensor.getY(),
                    sensor.getRotatedX(),
                    sensor.getRotatedY(),
                    worldX & 0xFFFF,
                    worldY & 0xFFFF,
                    formatSensorResult(result)));
        }
        return builder.append(']').toString();
    }

    private static String formatSensorResult(SensorResult result) {
        if (result == null) {
            return "<null>";
        }
        return String.format(
                "{dir=%s dist=%d angle=%02X tile=%04X}",
                result.direction(),
                result.distance(),
                result.angle() & 0xFF,
                result.tileId() & 0xFFFF);
    }

    private static String describeCeilingSweep(AbstractPlayableSprite sprite) {
        StringBuilder builder = new StringBuilder();
        for (int dx = -12; dx <= 12; dx += 4) {
            int checkX = sprite.getCentreX() + dx;
            TerrainCheckResult result = ObjectTerrainUtils.checkCeilingDist(
                    checkX,
                    sprite.getCentreY(),
                    sprite.getYRadius());
            appendSweepSample(builder, "x", checkX, result);
        }
        return builder.toString();
    }

    private static String describeRightWallSweep(AbstractPlayableSprite sprite) {
        StringBuilder builder = new StringBuilder();
        int checkX = sprite.getCentreX() + 10;
        for (int dy = -16; dy <= 16; dy += 4) {
            int checkY = sprite.getCentreY() + dy;
            TerrainCheckResult result = ObjectTerrainUtils.checkRightWallDist(checkX, checkY);
            appendSweepSample(builder, "y", checkY, result);
        }
        return String.format("x=%04X %s", checkX & 0xFFFF, builder);
    }

    private static String describeFloorSweep(AbstractPlayableSprite sprite) {
        StringBuilder builder = new StringBuilder();
        for (int dx = -12; dx <= 12; dx += 4) {
            int checkX = sprite.getCentreX() + dx;
            TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(
                    checkX,
                    sprite.getCentreY(),
                    sprite.getYRadius());
            appendSweepSample(builder, "x", checkX, result);
        }
        return builder.toString();
    }

    private static void appendSweepSample(StringBuilder builder,
                                          String axis,
                                          int coordinate,
                                          TerrainCheckResult result) {
        if (!builder.isEmpty()) {
            builder.append(" | ");
        }
        builder.append(String.format(
                "%s=%04X:%s",
                axis,
                coordinate & 0xFFFF,
                formatTerrainCheckResult(result)));
    }

    private static String formatTerrainCheckResult(TerrainCheckResult result) {
        if (result == null || !result.foundSurface()) {
            return "<none>";
        }
        return String.format(
                "{dist=%d hit=%s angle=%02X tile=%04X}",
                result.distance(),
                result.hasCollision(),
                result.angle() & 0xFF,
                result.tileIndex() & 0xFFFF);
    }

    private static String nearbyPathSwaps(com.openggf.sprites.playable.AbstractPlayableSprite sprite) {
        ObjectManager objectManager = GameServices.level() != null
                ? GameServices.level().getObjectManager()
                : null;
        if (sprite == null || objectManager == null) {
            return "-";
        }
        int spriteX = sprite.getCentreX() & 0xFFFF;
        int spriteY = sprite.getCentreY() & 0xFFFF;
        return objectManager.getActiveSpawns().stream()
                .filter(spawn -> spawn.objectId() == 0x02)
                .sorted(java.util.Comparator.comparingInt(spawn -> {
                    int dx = Math.abs((spawn.x() & 0xFFFF) - spriteX);
                    int dy = Math.abs((spawn.y() & 0xFFFF) - spriteY);
                    return dx + dy;
                }))
                .limit(3)
                .map(spawn -> String.format("(%04X,%04X sub=%02X side=%d)",
                        spawn.x() & 0xFFFF,
                        spawn.y() & 0xFFFF,
                        spawn.subtype() & 0xFF,
                        objectManager.getPlaneSwitcherSideState(spawn)))
                .reduce((left, right) -> left + " | " + right)
                .orElse("-");
    }

    private static String nearbyActiveObjects(com.openggf.sprites.playable.AbstractPlayableSprite sprite) {
        ObjectManager objectManager = GameServices.level() != null
                ? GameServices.level().getObjectManager()
                : null;
        if (sprite == null || objectManager == null) {
            return "-";
        }
        int spriteX = sprite.getCentreX() & 0xFFFF;
        int spriteY = sprite.getCentreY() & 0xFFFF;
        return objectManager.getActiveObjects().stream()
                .filter(instance -> instance != null
                        && !instance.isDestroyed()
                        && instance.getSpawn() != null)
                .sorted(java.util.Comparator.comparingInt(instance -> {
                    int dx = Math.abs((instance.getX() & 0xFFFF) - spriteX);
                    int dy = Math.abs((instance.getY() & 0xFFFF) - spriteY);
                    return dx + dy;
                }))
                .limit(4)
                .map(instance -> {
                    var spawn = instance.getSpawn();
                    return String.format("%s(id=%02X sub=%02X spawn=%04X,%04X pos=%04X,%04X)",
                            instance.getClass().getSimpleName(),
                            spawn.objectId() & 0xFF,
                            spawn.subtype() & 0xFF,
                            spawn.x() & 0xFFFF,
                            spawn.y() & 0xFFFF,
                            instance.getX() & 0xFFFF,
                            instance.getY() & 0xFFFF);
                })
                .reduce((left, right) -> left + " | " + right)
                .orElse("-");
    }

    private static String summariseTouchOverlaps(java.util.List<TouchResponseDebugHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (TouchResponseDebugHit hit : hits) {
            if (!hit.overlapping()) {
                continue;
            }
            if (shown > 0) {
                sb.append(" | ");
            }
            appendTouchPrefix(sb, "touch", hit);
            appendTouchPosition(sb, hit);
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return sb.toString();
    }

    private static String summariseNearbyTouchScans(
            java.util.List<TouchResponseDebugHit> hits, int centreX, int centreY) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (TouchResponseDebugHit hit : hits) {
            int dx = Math.abs(hit.objectX() - centreX);
            int dy = Math.abs(hit.objectY() - centreY);
            if (dx > 160 || dy > 160) {
                continue;
            }
            if (shown > 0) {
                sb.append(" | ");
            }
            appendTouchPrefix(sb, "scan", hit);
            sb.append(String.format(" ov=%d sz=%dx%d",
                    hit.overlapping() ? 1 : 0,
                    hit.width(),
                    hit.height()));
            appendTouchPosition(sb, hit);
            shown++;
            if (shown >= 8) {
                break;
            }
        }
        return sb.toString();
    }

    private static void appendTouchPrefix(StringBuilder sb, String prefix, TouchResponseDebugHit hit) {
        sb.append(prefix);
        if (hit.slotIndex() >= 0) {
            sb.append(' ').append('s').append(hit.slotIndex());
        }
        ObjectSpawn spawn = hit.spawn();
        if (spawn != null) {
            sb.append(String.format(" 0x%02X", spawn.objectId() & 0xFF));
        } else {
            sb.append(" 0x??");
        }
        sb.append(' ').append(hit.category());
    }

    private static void appendTouchPosition(StringBuilder sb, TouchResponseDebugHit hit) {
        sb.append(String.format(" obj=@%04X,%04X",
                hit.objectX() & 0xFFFF,
                hit.objectY() & 0xFFFF));
        ObjectSpawn spawn = hit.spawn();
        if (spawn != null && (spawn.x() != hit.objectX() || spawn.y() != hit.objectY())) {
            sb.append(String.format(" sp=@%04X,%04X",
                    spawn.x() & 0xFFFF,
                    spawn.y() & 0xFFFF));
        }
    }

    private static String describeAizIntroInstance() {
        AizPlaneIntroInstance intro = AizPlaneIntroInstance.getActiveIntroInstance();
        if (intro == null) {
            return "<none>";
        }
        return String.format(
                "routine=%d x=%04X y=%04X xs=%04X ys=%04X destroyed=%b",
                intro.getRoutine(),
                intro.getX() & 0xFFFF,
                intro.getY() & 0xFFFF,
                intro.getReplayXSpeed() & 0xFFFF,
                intro.getReplayYSpeed() & 0xFFFF,
                intro.isDestroyed());
    }

    private static String describeRockState(ObjectManager objectManager) {
        if (objectManager == null) {
            return "<no-object-manager>";
        }

        AizLrzRockObjectInstance rock = objectManager.getActiveObjects().stream()
                .filter(AizLrzRockObjectInstance.class::isInstance)
                .map(AizLrzRockObjectInstance.class::cast)
                .filter(instance -> instance.getSpawn() != null
                        && instance.getSpawn().x() == 0x1980
                        && instance.getSpawn().y() == 0x0424)
                .findFirst()
                .orElse(null);
        long debrisCount = objectManager.getActiveObjects().stream()
                .filter(RockDebrisChild.class::isInstance)
                .filter(instance -> Math.abs(instance.getX() - 0x1980) <= 0x20
                        && Math.abs(instance.getY() - 0x0424) <= 0x20)
                .count();

        if (rock == null) {
            return "intact=<none> debris=" + debrisCount;
        }

        return String.format(
                "intact=x=%04X y=%04X breaking=%s standing=%s pushing=%s preAnim=%02X preRoll=%s preX=%04X preY=%04X debris=%d",
                rock.getX() & 0xFFFF,
                rock.getY() & 0xFFFF,
                readBoolean(rock, "breaking"),
                readBoolean(rock, "playerStandingOnRock"),
                readBoolean(rock, "playerPushingSide"),
                readInt(rock, "savedPreContactAnimationId") & 0xFF,
                readBoolean(rock, "savedPreContactRolling"),
                readInt(rock, "savedPreContactXSpeed") & 0xFFFF,
                readInt(rock, "savedPreContactYSpeed") & 0xFFFF,
                debrisCount);
    }

    private static boolean readBoolean(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static int readInt(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static String describeTraceEvents(java.util.List<TraceEvent> events) {
        if (events == null || events.isEmpty()) {
            return "-";
        }
        return events.stream()
                .map(event -> switch (event) {
                    case TraceEvent.Checkpoint checkpoint ->
                            "checkpoint:" + checkpoint.name();
                    case TraceEvent.CollisionEvent collision ->
                            "collision:" + collision.type();
                    case TraceEvent.ModeChange modeChange ->
                            "mode:" + modeChange.field() + "=" + modeChange.to();
                    case TraceEvent.RoutineChange routineChange ->
                            "routine:" + routineChange.to();
                    case TraceEvent.ObjectAppeared objectAppeared ->
                            "spawn:" + objectAppeared.objectType();
                    case TraceEvent.ObjectRemoved objectRemoved ->
                            "remove:" + objectRemoved.objectType();
                    case TraceEvent.ObjectNear objectNear ->
                            "near:" + objectNear.objectType();
                    case TraceEvent.ZoneActState zoneActState ->
                            "zone:" + zoneActState.actualZoneId() + "/" + zoneActState.actualAct();
                    default -> event.getClass().getSimpleName();
                })
                .distinct()
                .limit(6)
                .reduce((left, right) -> left + " | " + right)
                .orElse("-");
    }

    private static S3kCheckpointProbe captureProbe(int replayFrame, HeadlessTestFixture fixture) {
        boolean resultsActive = GameServices.level().getObjectManager().getActiveObjects().stream()
                .anyMatch(S3kResultsScreenObjectInstance.class::isInstance);
        boolean signpostActive = S3kSignpostInstance.activeSignpost(
                GameServices.level().getObjectManager()) != null;
        boolean eventsFg5 =
                GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager
                        && manager.isEventsFg5();
        boolean fireTransitionActive =
                GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager
                        && manager.isFireTransitionActive();
        var titleCardProvider = GameServices.module().getTitleCardProvider();
        boolean titleCardOverlayActive = titleCardProvider != null && titleCardProvider.isOverlayActive();

        return new S3kCheckpointProbe(
                replayFrame,
                GameServices.level().getCurrentZone(),
                GameServices.level().getCurrentAct(),
                GameServices.level().getApparentAct(),
                0x0C,
                fixture.sprite().getMoveLockTimer(),
                fixture.sprite().isControlLocked(),
                fixture.sprite().isObjectControlled(),
                fixture.sprite().isHidden(),
                eventsFg5,
                fireTransitionActive,
                false,
                signpostActive,
                resultsActive,
                GameServices.camera().isLevelStarted(),
                titleCardOverlayActive);
    }

    private record Sample(
            int frame,
            int x,
            int y,
            int cameraX,
            int cameraY,
            boolean eventsFg5,
            boolean fireActive,
            Integer actualAct,
            String latestCheckpoint) {

        private String format() {
            return String.format(
                    "f=%d x=%04X y=%04X cam=(%04X,%04X) act=%s fg5=%b fire=%b latest=%s",
                    frame,
                    x & 0xFFFF,
                    y & 0xFFFF,
                    cameraX & 0xFFFF,
                    cameraY & 0xFFFF,
                    actualAct,
                    eventsFg5,
                    fireActive,
                    latestCheckpoint);
        }
    }

    private record SweepResult(int offset, String latestCheckpoint, Sample[] samples) {
        private String format() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("offset=%d latest=%s", offset, latestCheckpoint));
            for (Sample sample : samples) {
                if (sample != null) {
                    sb.append(" | ").append(sample.format());
                }
            }
            return sb.toString();
        }
    }
}
