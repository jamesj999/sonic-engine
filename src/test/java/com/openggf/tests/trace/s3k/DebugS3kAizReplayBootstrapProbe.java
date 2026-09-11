package com.openggf.tests.trace.s3k;

import com.openggf.Engine;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.GameMode;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesAiz1Instance;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.objects.AizLrzRockObjectInstance;
import com.openggf.game.sonic3k.objects.FloatingPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.RockDebrisChild;
import com.openggf.game.sonic3k.objects.badniks.MonkeyDudeBadnikInstance;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@RequiresRom(SonicGame.SONIC_3K)
public class DebugS3kAizReplayBootstrapProbe {

    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun");
    private static final TraceData TRACE = loadTraceData();
    private static final String PRE_TRACE_OSC_OVERRIDE_PROPERTY = "s3k.aiz.preTraceOscOverride";
    private static final Field ORIGINAL_SPAWN_FIELD = resolveOriginalSpawnField();

    @Test
    void clearsIntroOverlayAndForcedInputAtReplayBootstrap() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            TitleCardProvider titleCardProvider = GameServices.module().getTitleCardProvider();
            ObjectManager objectManager = GameServices.level().getObjectManager();
            ObjectInstance ridingObject = objectManager != null
                    ? objectManager.getRidingObject(fixture.sprite())
                    : null;
            assertEquals(0, fixture.sprite().getForcedInputMask());
            assertFalse(fixture.sprite().isForceInputRight());
            assertFalse(fixture.sprite().hasSpeedShoes());
            assertFalse(fixture.sprite().isSuperSonic());
            assertEquals(0x0C, fixture.sprite().getRunAccel());
            assertFalse(fixture.sprite().isOnObject());
            assertNull(ridingObject);
            assertFalse(titleCardProvider != null && titleCardProvider.isOverlayActive());
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayStartsAtFrameZeroBeforeGameplayStart() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int levelEntryFrame = TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(trace);
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applySeedReplayStartStateForTraceReplay(trace, fixture);

            assertEquals(0, levelEntryFrame);
            assertEquals(0, replayStart.startingTraceIndex());
            assertEquals(-1, replayStart.seededTraceIndex());
            assertFalse(GameServices.camera().isLevelStarted(),
                    "AIZ should still be before gameplay_start at trace frame 0.");
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayDoesNotWarmPastTheVisibleIntroPrefix() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int strictStartFrame = TraceReplayBootstrap.strictStartTraceIndexForTraceReplay(trace);
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            assertTrue(strictStartFrame > 0,
                    "Strict comparison still begins at the first recorded LEVEL frame.");
            assertEquals(0, replayStart.startingTraceIndex());
            assertEquals(-1, replayStart.seededTraceIndex());
            assertFalse(GameServices.camera().isLevelStarted(),
                    "AIZ should still be before gameplay_start at trace frame 0.");
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayKeepsHiddenPlayerFrozenThroughEarlyIntroWindow() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int probeFrame = 469;
        TraceFrame expected = trace.getFrame(probeFrame);
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);

            assertFrameMatches(expected, fixture, lastInput);
            assertEquals(expected.cameraX(), GameServices.camera().getX() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
            assertEquals(expected.cameraY(), GameServices.camera().getY() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
            assertTrue(fixture.sprite().isObjectControlled(),
                    "Sonic should still be intro-controlled at trace frame " + probeFrame);
            assertTrue(fixture.sprite().isHidden(),
                    "Sonic should still be hidden at trace frame " + probeFrame);
            assertFalse(GameServices.camera().isLevelStarted(),
                    "AIZ should still be in the intro at trace frame " + probeFrame);
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayBootstrapLeavesNativeAizIntroObjectUnconsumed() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int levelEntryFrame = TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(trace);
        TraceFrame entryFrame = trace.getFrame(levelEntryFrame);
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applySeedReplayStartStateForTraceReplay(trace, fixture);

            // The frame-0 policy never seeds engine state from trace rows: the
            // replay cursor starts at trace frame 0 with nothing consumed.
            assertEquals(0, levelEntryFrame);
            assertEquals(0, replayStart.startingTraceIndex());
            assertEquals(-1, replayStart.seededTraceIndex());

            AizPlaneIntroInstance intro = AizPlaneIntroInstance.getActiveIntroInstance();
            assertNotNull(intro, "AIZ intro object should exist natively after the fresh level load.");
            assertEquals(2, intro.getRoutine(),
                    "Level load reproduces ROM's setup Process_Sprites pass "
                            + "(sonic3k.asm:7848-7859): Obj_intPlane has completed its "
                            + "routine-0 init and sits in the routine-2 wait before the "
                            + "first driven trace row.");
            assertEquals(entryFrame.vblankCounter(), GameServices.level().getObjectManager().getVblaCounter(),
                    "Bootstrap should leave ObjectManager on the recorded frame-0 vblank counter.");
            assertTrue(fixture.sprite().isControlLocked(),
                    "Obj_intPlane routine-0 init locks player control natively during level load, "
                            + "not from trace hydration.");
            assertTrue(fixture.sprite().isObjectControlled(),
                    "Obj_intPlane routine-0 init takes object control of the hidden intro player.");
            assertTrue(fixture.sprite().isHidden(),
                    "Obj_intPlane routine-0 init hides the player until the cutscene reveal.");
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayDrivesTraceFramesWithPreviousBk2Input() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);

        // Frame-0 replay starts the BK2 cursor at the trace's own offset
        // (recordingStartFrame = bk2FrameOffset + max(0, seed - 1) with seed 0).
        // The ROM's one-frame input poll latency relative to trace rows is
        // realized by driving each executed row with the previous BK2 input
        // row (stepFrameFromRecordingUsingPreviousInput), not by shifting the
        // recording start frame back by one.
        assertEquals(0, TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(trace));
        assertEquals(
                trace.metadata().bk2FrameOffset(),
                TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace));
        assertTrue(TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace),
                "Pre-level-prefix traces drive executed rows with the previous BK2 input row.");
    }

    @Test
    void liveBootstrapStartsAtTraceFrameZeroWithoutConsumingIntroFrames() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);

        TraceReplayBootstrap.ReplayStartState replayStart =
                com.openggf.trace.replay.TraceReplaySessionBootstrap
                        .applyLiveBootstrap(trace, null, -1)
                        .replayStart();

        assertEquals(0, replayStart.startingTraceIndex());
        assertEquals(-1, replayStart.seededTraceIndex());
    }

    @Test
    void preLevelPrefixIsVblankOnlyUntilFirstLevelFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int strictStartFrame = TraceReplayBootstrap.strictStartTraceIndexForTraceReplay(trace);

        assertEquals(289, strictStartFrame);
        TraceFrame previous = null;
        for (int frame = 0; frame <= strictStartFrame; frame++) {
            TraceFrame current = trace.getFrame(frame);
            assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                    TraceReplayBootstrap.phaseForReplay(trace, previous, current),
                    "pre-level AIZ prefix should not tick the loaded level at trace frame " + frame);
            previous = current;
        }
        // The first Level-mode row (Game_Mode $8C -> $0C) is the boundary
        // between ROM's synchronous setup block and its first LevelLoop
        // iteration; phaseForReplay routes that boundary row VBLANK_ONLY so
        // the engine's first physics tick lands on the following trace row.
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(trace, previous, trace.getFrame(strictStartFrame + 1)));
    }

    @Test
    void matchesFirstGroundAccelerationAfterGameplayStartAnchor() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int gameplayStartFrame = findCheckpointFrame(trace, "gameplay_start");
        int preAccelerationFrame = 0x0606;
        int firstAccelerationFrame = 0x0607;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, preAccelerationFrame);

            TraceFrame preAccelerationExpected = trace.getFrame(preAccelerationFrame);
            assertFrameMatches(preAccelerationExpected, fixture, lastInput);

            lastInput = stepReplayFrame(trace, fixture, preAccelerationFrame);

            TraceFrame expected = trace.getFrame(firstAccelerationFrame);
            assertFrameMatches(expected, fixture, lastInput);
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayAlignsFirstRightInputToRecordedFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int firstInputFrame = 0x0594;
        int firstEffectFrame = 0x0595;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, firstInputFrame);
            TraceFrame firstInputExpected = trace.getFrame(firstInputFrame);
            assertEquals(0, trace.getFrame(firstInputFrame - 1).input());
            assertEquals(AbstractPlayableSprite.INPUT_RIGHT, firstInputExpected.input());
            // Previous-input driving: stepFrameFromRecordingUsingPreviousInput
            // drives physics with the prior BK2 row but returns the driven
            // row's own input as a validation mask, so the returned mask must
            // track the trace's recorded input column exactly. The first
            // recorded-input row is still driven with the prior idle input,
            // which is why its recorded x_speed is 0 and the first motion
            // shows on the following row (0x000C in the trace).
            assertEquals(firstInputExpected.input(), lastInput,
                    "Validation input mask should align with the recorded input column.");
            assertFrameMatches(firstInputExpected, fixture, lastInput);

            lastInput = stepReplayFrame(trace, fixture, firstInputFrame);

            TraceFrame firstEffectExpected = trace.getFrame(firstEffectFrame);
            assertEquals(AbstractPlayableSprite.INPUT_RIGHT, lastInput & AbstractPlayableSprite.INPUT_RIGHT);
            assertFrameMatches(firstEffectExpected, fixture, lastInput);
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void detectsGameplayStartAtRecordedTraceFrameDuringFullReplayWindow() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int gameplayStartFrame = findCheckpointFrame(trace, "gameplay_start");
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int detectedFrame = replayUntilCheckpoint(trace, replayStart, fixture, "gameplay_start");

            // The frame-0 policy plays the AIZ intro natively instead of
            // seeding at the recorded anchor. The headless intro hands control
            // back two trace rows before the recorder-visible unlock row (the
            // recorded gameplay_start checkpoint; aux_state shows ROM
            // ctrl1_locked flipping 255->0 on that same row). The recorded
            // rows between the two are fully idle (input 0, player static), so
            // the skew has no parity impact before the first input row at
            // 0x0594. Pin the skew so intro-length regressions still trip.
            assertEquals(gameplayStartFrame - 2, detectedFrame,
                    "Full replay loop should detect gameplay_start two rows before the recorded "
                            + "checkpoint frame (native intro handoff skew).");
            TitleCardProvider titleCardProvider = GameServices.module().getTitleCardProvider();
            assertTrue(titleCardProvider != null && titleCardProvider.isOverlayActive(),
                    "AIZ gameplay_start should still have the in-level title card overlay active.");
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void gameplayStartProbeMatchesDetectorPredicatesAtRecordedFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int gameplayStartFrame = findCheckpointFrame(trace, "gameplay_start");
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, gameplayStartFrame);
            TraceFrame expected = trace.getFrame(gameplayStartFrame);
            assertFrameMatches(expected, fixture, lastInput);

            S3kCheckpointProbe probe = captureProbe(gameplayStartFrame, fixture);
            assertTrue(probe.levelStarted(), "Recorded gameplay_start should have levelStarted=true.");
            assertEquals(Integer.valueOf(0x0C), probe.gameMode(),
                    "Recorded gameplay_start should resolve to trace game_mode 0x0C.");
            assertEquals(0, probe.moveLock(), "Recorded gameplay_start should have no move lock.");
            assertFalse(probe.ctrlLocked(), "Recorded gameplay_start should not be control-locked.");
            assertFalse(probe.objectControlled(), "Recorded gameplay_start should not be object-controlled.");
            assertFalse(probe.hidden(), "Recorded gameplay_start should not be hidden.");
            assertTrue(probe.titleCardOverlayActive(),
                    "AIZ gameplay_start should still have the in-level title card overlay active.");

            S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
            detector.seedCheckpoint("intro_begin");
            TraceEvent.Checkpoint checkpoint = detector.observe(probe);
            assertNotNull(checkpoint);
            assertEquals("gameplay_start", checkpoint.name());
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void inLevelTitleCardActivatesOnRecordedGameplayStartFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int gameplayStartFrame = findCheckpointFrame(trace, "gameplay_start");
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            // The native intro hands off two trace rows before the recorded
            // gameplay_start checkpoint row (see
            // detectsGameplayStartAtRecordedTraceFrameDuringFullReplayWindow),
            // so the in-level title card activates on gameplayStartFrame - 2.
            int nativeHandoffFrame = gameplayStartFrame - 2;
            advanceReplayToTraceFrame(trace, fixture, replayStart, nativeHandoffFrame - 1);
            TitleCardProvider titleCardProvider = GameServices.module().getTitleCardProvider();
            assertFalse(titleCardProvider != null && titleCardProvider.isOverlayActive(),
                    "AIZ in-level title card should still be inactive on the frame before the native handoff.");

            stepReplayFrame(trace, fixture, nativeHandoffFrame - 1);
            assertTrue(titleCardProvider != null && titleCardProvider.isOverlayActive(),
                    "AIZ in-level title card should activate on the native intro handoff frame.");
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void cutsceneKnucklesDespawnsOnRecordedGameplayStartFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int gameplayStartFrame = findCheckpointFrame(trace, "gameplay_start");
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            // The native intro hands off two trace rows before the recorded
            // gameplay_start checkpoint row (see
            // detectsGameplayStartAtRecordedTraceFrameDuringFullReplayWindow),
            // so cutscene Knuckles despawns on gameplayStartFrame - 2.
            int nativeHandoffFrame = gameplayStartFrame - 2;
            advanceReplayToTraceFrame(trace, fixture, replayStart, nativeHandoffFrame - 1);
            assertNotNull(findActiveObject(CutsceneKnucklesAiz1Instance.class),
                    "Cutscene Knuckles should still exist on the frame before the native handoff.");

            stepReplayFrame(trace, fixture, nativeHandoffFrame - 1);
            CutsceneKnucklesAiz1Instance knux = findActiveObject(CutsceneKnucklesAiz1Instance.class);
            assertNull(knux, () -> String.format(
                    "Cutscene Knuckles should despawn on the native intro handoff frame. "
                            + "actual pos=(%04X,%04X) camX=%04X relX=%d routine=%d",
                    knux.getX() & 0xFFFF,
                    knux.getY() & 0xFFFF,
                    GameServices.camera().getX() & 0xFFFF,
                    knux.getX() - GameServices.camera().getX(),
                    knux.getRoutine()));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void matchesDeferredHurtFrameAfterAizIntroExplosionRelease() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int explosionReleaseFrame = 1161;
        int deferredHurtFrame = 1162;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, explosionReleaseFrame);
            TraceFrame releaseExpected = trace.getFrame(explosionReleaseFrame);
            assertFrameMatches(releaseExpected, fixture, lastInput);
            assertEquals(releaseExpected.cameraX(), GameServices.camera().getX() & 0xFFFF,
                    describeSpriteState(fixture, releaseExpected, lastInput));
            assertFalse(fixture.sprite().isObjectControlled(),
                    "Explosion release frame should already hand Sonic back from object control.");

            lastInput = stepReplayFrame(trace, fixture, explosionReleaseFrame);

            TraceFrame hurtExpected = trace.getFrame(deferredHurtFrame);
            assertFrameMatches(hurtExpected, fixture, lastInput);
            assertEquals(hurtExpected.cameraX(), GameServices.camera().getX() & 0xFFFF,
                    describeSpriteState(fixture, hurtExpected, lastInput));
            assertEquals(hurtExpected.cameraY(), GameServices.camera().getY() & 0xFFFF,
                    describeSpriteState(fixture, hurtExpected, lastInput));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayMatchesShortlyAfterFireTransitionCheckpoint() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int probeFrame = 1800;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);
            TraceFrame expected = trace.getFrame(probeFrame);

            assertFrameMatches(expected, fixture, lastInput);
            assertEquals(expected.cameraX(), GameServices.camera().getX() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
            assertEquals(expected.cameraY(), GameServices.camera().getY() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayMatchesShortlyBeforeAiz2ReloadResume() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        // Rebased from 5000 after the AIZ trace re-recording moved the BK2
        // start forward by 114 frames (offset 397→511, frame count 20912→20798).
        int probeFrame = 4886;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);
            TraceFrame expected = trace.getFrame(probeFrame);

            assertFrameMatches(expected, fixture, lastInput);
            assertEquals(expected.cameraX(), GameServices.camera().getX() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
            assertEquals(expected.cameraY(), GameServices.camera().getY() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void keepsMonkeyDudeBodyAtDiagnosticHeightBeforeRecordedStomp() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        // Rebased from 1833 after the AIZ trace re-recording moved the BK2
        // start forward by 114 frames (offset 397→511, frame count 20912→20798).
        int probeFrame = 1719;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);
            TraceFrame expected = trace.getFrame(probeFrame);

            assertFrameMatches(expected, fixture, lastInput);

            ObjectManager objectManager = GameServices.level().getObjectManager();
            MonkeyDudeBadnikInstance monkey = objectManager.getActiveObjects().stream()
                    .filter(MonkeyDudeBadnikInstance.class::isInstance)
                    .map(MonkeyDudeBadnikInstance.class::cast)
                    .filter(instance -> Math.abs(instance.getX() - fixture.sprite().getCentreX()) <= 0x10)
                    .min((a, b) -> Integer.compare(
                            Math.abs(a.getX() - fixture.sprite().getCentreX()),
                            Math.abs(b.getX() - fixture.sprite().getCentreX())))
                    .orElse(null);

            assertNotNull(monkey, describeSpriteState(fixture, expected, lastInput));
            assertEquals(0x1838, monkey.getX(), describeSpriteState(fixture, expected, lastInput));
            assertEquals(0x0410, monkey.getY(), describeSpriteState(fixture, expected, lastInput));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void keepsRollingHitboxAtFirstPostSpringAirGSpeedResetFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int probeFrame = 2006;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);
            TraceFrame expected = trace.getFrame(probeFrame);

            assertTrue(fixture.sprite().getAir(), describeSpriteState(fixture, expected, lastInput));
            assertTrue(fixture.sprite().getRolling(), describeSpriteState(fixture, expected, lastInput));
            assertEquals(7, fixture.sprite().getXRadius(), describeSpriteState(fixture, expected, lastInput));
            assertEquals(14, fixture.sprite().getYRadius(), describeSpriteState(fixture, expected, lastInput));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayMatchesAtFirstPostSpringAirGSpeedResetFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int probeFrame = 2006;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);
            TraceFrame expected = trace.getFrame(probeFrame);
            String state = describeSpriteState(fixture, expected, lastInput);

            assertFrameMatches(expected, fixture, lastInput);
            assertEquals(expected.ySpeed(), fixture.sprite().getYSpeed(), state);
            assertEquals(expected.angle() & 0xFF, fixture.sprite().getAngle() & 0xFF, state);
            assertEquals(expected.air(), fixture.sprite().getAir(), state);
            assertEquals(expected.rolling(), fixture.sprite().getRolling(), state);
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void breaksRecordedAizRollRockIntoDebrisAtPostFireContactFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        // Rebased from 2097 after the AIZ trace re-recording moved the BK2
        // start forward by 114 frames (offset 397→511, frame count 20912→20798).
        int probeFrame = 1983;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);
            TraceFrame expected = trace.getFrame(probeFrame);

            ObjectManager objectManager = GameServices.level().getObjectManager();
            AizLrzRockObjectInstance intactRock = objectManager.getActiveObjects().stream()
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

            assertFrameMatches(expected, fixture, lastInput);
            assertNull(intactRock, describeSpriteState(fixture, expected, lastInput));
            assertTrue(debrisCount >= 4, describeSpriteState(fixture, expected, lastInput)
                    + ", debrisCount=" + debrisCount);
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void keepsTracedAizFloatingPlatformAtRecordedWorldPositionBeforeFalseLandingWindow() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        // Rebased from 2269 after the AIZ trace re-recording moved the BK2
        // start forward by 114 frames (offset 397→511, frame count 20912→20798).
        int probeFrame = 2155;
        TraceEvent.ObjectNear tracedPlatform = trace.getEventsForFrame(probeFrame).stream()
                .filter(TraceEvent.ObjectNear.class::isInstance)
                .map(TraceEvent.ObjectNear.class::cast)
                .filter(event -> event.slot() == 8)
                .filter(event -> "0x000255F4".equalsIgnoreCase(event.objectType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No traced floating platform event at frame " + probeFrame));
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);

            FloatingPlatformObjectInstance platform = GameServices.level().getObjectManager()
                    .getActiveObjects().stream()
                    .filter(FloatingPlatformObjectInstance.class::isInstance)
                    .map(FloatingPlatformObjectInstance.class::cast)
                    .filter(instance -> instance.getX() == tracedPlatform.x())
                    .findFirst()
                    .orElse(null);

            assertNotNull(platform, describeSpriteState(fixture, trace.getFrame(probeFrame), lastInput));
            assertEquals(tracedPlatform.x(), (short) platform.getX(),
                    describeSpriteState(fixture, trace.getFrame(probeFrame), lastInput));
            assertEquals(tracedPlatform.y(), (short) platform.getY(),
                    describeSpriteState(fixture, trace.getFrame(probeFrame), lastInput)
                            + ", tracedPlatformY=" + String.format("%04X", tracedPlatform.y() & 0xFFFF)
                            + ", actualPlatformY=" + String.format("%04X", platform.getY() & 0xFFFF));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayKeepsAizFloatingPlatformAtRecordedWorldPositionBeforeFalseLandingWindow() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        // Rebased from 2224 after the AIZ trace re-recording moved the BK2
        // start forward by 114 frames (offset 397→511, frame count 20912→20798).
        int probeFrame = 2110;
        TraceEvent.ObjectNear tracedPlatform = trace.getEventsForFrame(probeFrame).stream()
                .filter(TraceEvent.ObjectNear.class::isInstance)
                .map(TraceEvent.ObjectNear.class::cast)
                .filter(event -> event.slot() == 8)
                .filter(event -> "0x000255F4".equalsIgnoreCase(event.objectType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No traced floating platform event at frame " + probeFrame));
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);

            FloatingPlatformObjectInstance platform = GameServices.level().getObjectManager()
                    .getActiveObjects().stream()
                    .filter(FloatingPlatformObjectInstance.class::isInstance)
                    .map(FloatingPlatformObjectInstance.class::cast)
                    .filter(instance -> instance.getSpawn() != null)
                    .filter(instance -> instance.getSpawn().x() == tracedPlatform.x())
                    .findFirst()
                    .orElse(null);

            String state = describeSpriteState(fixture, trace.getFrame(probeFrame), lastInput)
                    + ", tracedPlatformY=" + String.format("%04X", tracedPlatform.y() & 0xFFFF)
                    + ", osc08=" + String.format("%02X", com.openggf.game.OscillationManager.getByte(0x08) & 0xFF)
                    + ", lvlFrame=" + GameServices.level().getFrameCounter()
                    + ", objFrame=" + GameServices.level().getObjectManager().getFrameCounter()
                    + ", floatingPlatforms=" + describeFloatingPlatformsInWindow(0x1800, 0x1900);
            assertNotNull(platform, state);
            assertEquals(tracedPlatform.x(), (short) platform.getX(), state);
            assertEquals(tracedPlatform.y(), (short) platform.getY(),
                    state
                            + ", actualPlatformY=" + String.format("%04X", platform.getY() & 0xFFFF)
                            + ", actualOrigSpawn="
                            + describeSpawn(originalSpawnOf(platform)));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayKeepsAizFloatingPlatformAtRecordedCarryTimingFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int probeFrame = 2164;
        TraceEvent.ObjectNear tracedPlatform = trace.getEventsForFrame(probeFrame).stream()
                .filter(TraceEvent.ObjectNear.class::isInstance)
                .map(TraceEvent.ObjectNear.class::cast)
                .filter(event -> event.slot() == 8)
                .filter(event -> "0x000255F4".equalsIgnoreCase(event.objectType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No traced floating platform event at frame " + probeFrame));
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);

            FloatingPlatformObjectInstance platform = GameServices.level().getObjectManager()
                    .getActiveObjects().stream()
                    .filter(FloatingPlatformObjectInstance.class::isInstance)
                    .map(FloatingPlatformObjectInstance.class::cast)
                    .filter(instance -> instance.getSpawn() != null)
                    .filter(instance -> instance.getSpawn().x() == tracedPlatform.x())
                    .findFirst()
                    .orElse(null);

            String state = describeSpriteState(fixture, trace.getFrame(probeFrame), lastInput)
                    + ", tracedPlatformY=" + String.format("%04X", tracedPlatform.y() & 0xFFFF)
                    + ", floatingPlatforms=" + describeFloatingPlatformsInWindow(0x1800, 0x1900);
            assertNotNull(platform, state);
            assertEquals(tracedPlatform.x(), (short) platform.getX(), state);
            assertEquals(tracedPlatform.y(), (short) platform.getY(),
                    state
                            + ", actualPlatformY=" + String.format("%04X", platform.getY() & 0xFFFF)
                            + ", actualOrigSpawn="
                            + describeSpawn(originalSpawnOf(platform)));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayMatchesFirstFloatingPlatformFalseLandingWindow() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int probeFrame = 2273;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);
            TraceFrame expected = trace.getFrame(probeFrame);

            assertFrameMatches(expected, fixture, lastInput);
            assertEquals(expected.cameraX(), GameServices.camera().getX() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
            assertEquals(expected.cameraY(), GameServices.camera().getY() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    @Test
    void fullTraceReplayMatchesFloatingPlatformLandingFrame() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int probeFrame = 2278;
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = buildTraceReplayFixture(trace, sharedLevel);
            TraceReplayBootstrap.applyPreTraceState(trace, fixture);
            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);

            int lastInput = advanceReplayToTraceFrame(trace, fixture, replayStart, probeFrame);
            TraceFrame expected = trace.getFrame(probeFrame);

            assertFrameMatches(expected, fixture, lastInput);
            assertEquals(expected.cameraX(), GameServices.camera().getX() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
            assertEquals(expected.cameraY(), GameServices.camera().getY() & 0xFFFF,
                    describeSpriteState(fixture, expected, lastInput));
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
        }
    }

    private static int advanceReplayToTraceFrame(TraceData trace,
                                                 HeadlessTestFixture fixture,
                                                 TraceReplayBootstrap.ReplayStartState replayStart,
                                                 int targetTraceFrame) {
        int lastInput = 0;
        for (int traceIndex = replayStart.startingTraceIndex(); traceIndex <= targetTraceFrame; traceIndex++) {
            lastInput = stepReplayFrame(trace, fixture, traceIndex - 1);
        }
        return lastInput;
    }

    private static int stepReplayFrame(TraceData trace,
                                       HeadlessTestFixture fixture,
                                       int previousTraceFrame) {
        TraceFrame previous = previousTraceFrame >= 0
                ? trace.getFrame(previousTraceFrame)
                : null;
        TraceFrame current = trace.getFrame(previousTraceFrame + 1);
        TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
        return stepReplayFrame(trace, fixture, phase);
    }

    /**
     * Mirrors the canonical {@code TestS3kAizTraceReplay} step: pre-level-prefix
     * traces drive each executed frame with the previous BK2 input row
     * ({@link TraceReplayBootstrap#shouldUsePreviousRecordingInputForTraceReplay}),
     * matching the ROM's one-frame input poll latency relative to trace rows.
     */
    private static int stepReplayFrame(TraceData trace,
                                       HeadlessTestFixture fixture,
                                       TraceExecutionPhase phase) {
        if (phase == TraceExecutionPhase.VBLANK_ONLY) {
            return fixture.skipFrameFromRecording();
        }
        if (TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace)) {
            return fixture.stepFrameFromRecordingUsingPreviousInput();
        }
        return fixture.stepFrameFromRecording();
    }

    private static int replayUntilCheckpoint(TraceData trace,
                                             TraceReplayBootstrap.ReplayStartState replayStart,
                                             HeadlessTestFixture fixture,
                                             String checkpointName) {
        int driveTraceIndex = replayStart.startingTraceIndex();
        TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : driveTraceIndex > 0 ? trace.getFrame(driveTraceIndex - 1) : null;
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

        if (replayStart.hasSeededTraceState()) {
            for (int frame = 0; frame <= replayStart.seededTraceIndex(); frame++) {
                for (TraceEvent event : trace.getEventsForFrame(frame)) {
                    if (event instanceof TraceEvent.Checkpoint checkpoint) {
                        detector.seedCheckpoint(checkpoint.name());
                    }
                }
            }
        }

        while (driveTraceIndex < trace.frameCount()) {
            TraceFrame driveFrame = trace.getFrame(driveTraceIndex);
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previousDriveFrame, driveFrame);
            stepReplayFrame(trace, fixture, phase);

            TraceEvent.Checkpoint engineCheckpoint =
                    detector.observe(captureProbe(driveFrame.frame(), fixture));

            if (engineCheckpoint != null && checkpointName.equals(engineCheckpoint.name())) {
                return driveFrame.frame();
            }

            driveTraceIndex++;
            previousDriveFrame = driveFrame;
        }

        fail("Checkpoint never detected: " + checkpointName);
        return -1;
    }

    private static S3kCheckpointProbe captureProbe(int replayFrame, HeadlessTestFixture fixture) {
        boolean resultsActive = GameServices.level().getObjectManager().getActiveObjects().stream()
                .anyMatch(com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance.class::isInstance);
        boolean signpostActive =
                com.openggf.game.sonic3k.objects.S3kSignpostInstance.activeSignpost(
                        GameServices.level().getObjectManager()) != null;
        boolean eventsFg5 =
                GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager
                        && manager.isEventsFg5();
        boolean fireTransitionActive =
                GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager
                        && manager.isFireTransitionActive();
        boolean hczTransitionActive =
                com.openggf.game.sonic3k.objects.Aiz2BossEndSequenceState
                        .isCutsceneOverrideObjectsActive() && !resultsActive;
        TitleCardProvider titleCardProvider = GameServices.module().getTitleCardProvider();
        boolean titleCardOverlayActive =
                titleCardProvider != null && titleCardProvider.isOverlayActive();

        return new S3kCheckpointProbe(
                replayFrame,
                GameServices.level().getCurrentZone(),
                GameServices.level().getCurrentAct(),
                GameServices.level().getApparentAct(),
                resolveS3kTraceGameMode(titleCardOverlayActive),
                fixture.sprite().getMoveLockTimer(),
                fixture.sprite().isControlLocked(),
                fixture.sprite().isObjectControlled(),
                fixture.sprite().isHidden(),
                eventsFg5,
                fireTransitionActive,
                hczTransitionActive,
                signpostActive,
                resultsActive,
                GameServices.camera().isLevelStarted(),
                titleCardOverlayActive);
    }

    private static Integer resolveS3kTraceGameMode(boolean titleCardOverlayActive) {
        boolean levelStarted = GameServices.camera() != null && GameServices.camera().isLevelStarted();
        // Camera state is per-test rebuilt and authoritative for "level mode active".
        // Trust it before consulting Engine.getInstance() — that singleton can leak
        // a stale GameMode (e.g. MASTER_TITLE_SCREEN) from a prior test that
        // constructed an Engine, which would silently misreport probe.gameMode().
        if (levelStarted) {
            return 0x0C;
        }
        Engine engine = Engine.getInstance();
        GameMode currentMode = engine != null ? engine.getCurrentGameMode() : null;
        if (currentMode == null) {
            return 0x04;
        }
        return switch (currentMode) {
            case LEVEL, TITLE_CARD -> 0x04;
            case SPECIAL_STAGE -> 0x10;
            case SPECIAL_STAGE_RESULTS, CONTINUE_SCREEN -> 0x14;
            case TITLE_SCREEN, MASTER_TITLE_SCREEN -> 0x00;
            case LEVEL_SELECT -> 0x08;
            case DATA_SELECT -> 0x18;
            case CREDITS_TEXT, CREDITS_DEMO, TRY_AGAIN_END, ENDING_CUTSCENE, EDITOR, BONUS_STAGE, LEGAL_DISCLAIMER -> null;
        };
    }

    private static void assertFrameMatches(TraceFrame expected,
                                           HeadlessTestFixture fixture,
                                           int actualInput) {
        String state = describeSpriteState(fixture, expected, actualInput);
        var primary = TraceReplayBootstrap.capturePrimaryReplayStateForComparison(
                TRACE, expected, fixture.sprite());
        assertEquals(expected.x(), primary.x(), state);
        assertEquals(expected.y(), primary.y(), state);
        assertEquals(expected.xSpeed(), primary.xSpeed(), state);
        assertEquals(expected.gSpeed(), primary.gSpeed(), state);
        assertEquals(expected.xSub(), primary.xSub(), state);
        assertEquals(expected.ySub(), primary.ySub(), state);
    }

    private static String describeSpriteState(HeadlessTestFixture fixture,
                                             TraceFrame expected,
                                             int actualInput) {
        var primary = TraceReplayBootstrap.capturePrimaryReplayStateForComparison(
                TRACE, expected, fixture.sprite());
        ObjectManager objectManager = GameServices.level().getObjectManager();
        ObjectInstance ridingObject = objectManager != null
                ? objectManager.getRidingObject(fixture.sprite())
                : null;
        return "state[traceFrame=" + expected.frame()
                + ", expectedInput=" + String.format("%04X", expected.input())
                + ", actualInput=" + String.format("%04X", actualInput)
                + ", xSpeed=" + String.format("%04X", fixture.sprite().getXSpeed() & 0xFFFF)
                + ", ySpeed=" + String.format("%04X", fixture.sprite().getYSpeed() & 0xFFFF)
                + ", gSpeed=" + String.format("%04X", fixture.sprite().getGSpeed() & 0xFFFF)
                + ", xSub=" + String.format("%04X", fixture.sprite().getXSubpixelRaw())
                + ", ySub=" + String.format("%04X", fixture.sprite().getYSubpixelRaw())
                + ", air=" + fixture.sprite().getAir()
                + ", rolling=" + fixture.sprite().getRolling()
                + ", rollingJump=" + fixture.sprite().getRollingJump()
                + ", jumping=" + fixture.sprite().isJumping()
                + ", sliding=" + fixture.sprite().isSliding()
                + ", springing=" + fixture.sprite().getSpringing()
                + ", ctrl=" + fixture.sprite().isControlLocked()
                + ", objCtrl=" + fixture.sprite().isObjectControlled()
                + ", forcedMask=" + fixture.sprite().getForcedInputMask()
                + ", moveLock=" + fixture.sprite().getMoveLockTimer()
                + ", onObj=" + fixture.sprite().isOnObject()
                + ", size=" + fixture.sprite().getWidth() + "x" + fixture.sprite().getHeight()
                + ", radii=" + fixture.sprite().getXRadius() + "x" + fixture.sprite().getYRadius()
                + ", riding=" + (ridingObject != null
                ? ridingObject.getClass().getSimpleName()
                : "<none>")
                + ", switches=" + nearbyPathSwaps(fixture.sprite())
                + ", objs=" + nearbyActiveObjects(fixture.sprite())
                + ", gMode=" + fixture.sprite().getGroundMode()
                + ", angle=" + (fixture.sprite().getAngle() & 0xFF)
                + ", dbl=" + fixture.sprite().getDoubleJumpFlag()
                + ", pinball=" + fixture.sprite().getPinballMode()
                + ", cmpSource=" + primary.source()
                + ", cmpX=" + String.format("%04X", primary.x() & 0xFFFF)
                + ", cmpY=" + String.format("%04X", primary.y() & 0xFFFF)
                + "]";
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
                .sorted(Comparator.comparingInt(spawn -> {
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
                .sorted(Comparator.comparingInt(instance -> {
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

    private static HeadlessTestFixture buildTraceReplayFixture(TraceData trace, SharedLevel sharedLevel)
            throws Exception {
        HeadlessTestFixture.Builder builder = HeadlessTestFixture.builder()
                .withRecording(TRACE_DIR.resolve("s3-aiz1-2-sonictails.bk2"))
                .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace));
        if (TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace)) {
            builder.withZoneAndAct(sharedLevel.zone(), sharedLevel.act());
        } else {
            builder.withSharedLevel(sharedLevel);
        }
        if (TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace)) {
            builder.startPosition(trace.metadata().startX(), trace.metadata().startY())
                    .startPositionIsCentre();
        }
        HeadlessTestFixture fixture = builder.build();

        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager != null) {
            objectManager.initVblaCounter(TraceReplayBootstrap.initialVblankCounterForTraceReplay(trace) - 1);
        }

        int overridePreTraceOsc = Integer.getInteger(
                PRE_TRACE_OSC_OVERRIDE_PROPERTY,
                Integer.MIN_VALUE);
        int preTraceOsc = overridePreTraceOsc != Integer.MIN_VALUE
                ? overridePreTraceOsc
                : trace.metadata().preTraceOscillationFrames();
        for (int i = 0; i < preTraceOsc; i++) {
            com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
        }
        com.openggf.game.OscillationManager.suppressNextFrames(
                TraceReplayBootstrap.initialOscillationSuppressionFramesForTraceReplay(trace));
        return fixture;
    }

    private static int findCheckpointFrame(TraceData trace, String checkpointName) {
        for (int frame = 0; frame < trace.frameCount(); frame++) {
            for (TraceEvent event : trace.getEventsForFrame(frame)) {
                if (event instanceof TraceEvent.Checkpoint checkpoint
                        && checkpointName.equals(checkpoint.name())) {
                    return frame;
                }
            }
        }
        fail("Checkpoint not present in trace: " + checkpointName);
        return -1;
    }

    private static <T> T findActiveObject(Class<T> type) {
        ObjectManager objectManager = GameServices.level() != null
                ? GameServices.level().getObjectManager()
                : null;
        if (objectManager == null) {
            return null;
        }
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(instance -> instance instanceof ObjectInstance object && !object.isDestroyed())
                .findFirst()
                .orElse(null);
    }

    private static String describeFloatingPlatformsInWindow(int minX, int maxX) {
        ObjectManager objectManager = GameServices.level() != null
                ? GameServices.level().getObjectManager()
                : null;
        if (objectManager == null) {
            return "<no-object-manager>";
        }
        return objectManager.getActiveObjects().stream()
                .filter(FloatingPlatformObjectInstance.class::isInstance)
                .map(FloatingPlatformObjectInstance.class::cast)
                .filter(instance -> {
                    int x = instance.getX() & 0xFFFF;
                    return x >= minX && x <= maxX;
                })
                .sorted(Comparator
                        .comparingInt((FloatingPlatformObjectInstance instance) -> instance.getX() & 0xFFFF)
                        .thenComparingInt(instance -> instance.getY() & 0xFFFF))
                .map(instance -> String.format(
                        "orig=%s dyn=%s pos=(%04X,%04X)",
                        describeSpawn(originalSpawnOf(instance)),
                        describeSpawn(instance.getSpawn()),
                        instance.getX() & 0xFFFF,
                        instance.getY() & 0xFFFF))
                .reduce((left, right) -> left + " | " + right)
                .orElse("<none>");
    }

    private static ObjectSpawn originalSpawnOf(FloatingPlatformObjectInstance platform) {
        try {
            return (ObjectSpawn) ORIGINAL_SPAWN_FIELD.get(platform);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to read original object spawn", e);
        }
    }

    private static String describeSpawn(ObjectSpawn spawn) {
        if (spawn == null) {
            return "<null>";
        }
        return String.format(
                "(%04X,%04X id=%02X sub=%02X idx=%d)",
                spawn.x() & 0xFFFF,
                spawn.y() & 0xFFFF,
                spawn.objectId() & 0xFF,
                spawn.subtype() & 0xFF,
                spawn.layoutIndex());
    }

    private static Field resolveOriginalSpawnField() {
        try {
            Field field = ObjectInstance.class
                    .getClassLoader()
                    .loadClass("com.openggf.level.objects.AbstractObjectInstance")
                    .getDeclaredField("spawn");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static TraceData loadTraceData() {
        try {
            return TraceData.load(TRACE_DIR);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
