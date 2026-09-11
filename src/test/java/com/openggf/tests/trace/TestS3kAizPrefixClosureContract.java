package com.openggf.tests.trace;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kAizPrefixClosureContract {
    private static final Path AIZ_TRACE =
            Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun");
    private static final Path AIZ_BK2 =
            AIZ_TRACE.resolve("s3-aiz1-2-sonictails.bk2");
    private static final Path MGZ_TRACE =
            Path.of("src/test/resources/traces/s3k/mgz");
    private static final Path MGZ_BK2 =
            MGZ_TRACE.resolve("s3k-mgz-sonic-tails.bk2");

    @Test
    void standaloneAizPrefixClosesWithoutDispatchingLoadedLevelEarly() throws Exception {
        TraceData trace = TraceData.load(AIZ_TRACE);
        HeadlessTestFixture fixture = buildCanonicalFixture(trace, AIZ_BK2, 0, 0, false);
        ObjectManager objects = GameServices.level().getObjectManager();
        boolean usePreviousInput =
                TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace);
        Observation preBootstrap = observe(fixture, objects, -1, usePreviousInput);

        TraceReplaySessionBootstrap.BootstrapResult boot =
                TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);
        TraceReplayBootstrap.ReplayStartState replayStart = boot.replayStart();
        TraceFrame previous = null;
        alignCounters(trace, replayStart, previous);

        assertEquals(0, replayStart.startingTraceIndex());
        assertTrue(usePreviousInput);
        AizPlaneIntroInstance intro = AizPlaneIntroInstance.getActiveIntroInstance();
        assertNotNull(intro, "canonical bootstrap must expose the live fixed AIZ plane-intro object");

        Observation postBootstrap = observe(fixture, objects, -1, usePreviousInput);
        assertEquals(0x0040, postBootstrap.playerCentreX(), postBootstrap.toString());
        assertFalse(postBootstrap.pendingSetup(), postBootstrap.toString());

        int prefixDispatchCount = postBootstrap.objectDispatchCount();
        for (int traceIndex = 0; traceIndex <= 289; traceIndex++) {
            TraceFrame current = trace.getFrame(traceIndex);
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previous, current);
            assertEquals(TraceExecutionPhase.VBLANK_ONLY, phase,
                    "unexpected prefix admission at trace row " + traceIndex);

            int remainingBefore = fixture.runner().getRecordingFramesRemaining();
            int input = drive(trace, fixture, traceIndex, current, phase, objects);
            Observation after = observe(fixture, objects, input, usePreviousInput);
            assertEquals(current.input(), input,
                    "prefix input alignment at trace row " + traceIndex + ": " + after);
            assertEquals(remainingBefore - 1, after.recordingFramesRemaining(),
                    "prefix row must consume exactly one BK2 row: " + after);
            assertEquals(prefixDispatchCount, after.objectDispatchCount(),
                    "loaded-level object closure ran during VBLANK_ONLY prefix row "
                            + traceIndex + ": " + after);
            assertEquals(postBootstrap.introScrollOffset(), after.introScrollOffset(),
                    "plane intro advanced during VBLANK_ONLY prefix row "
                            + traceIndex + ": " + after);
            assertEquals(postBootstrap.playerCentreX(), after.playerCentreX(),
                    "player moved during VBLANK_ONLY prefix row " + traceIndex + ": " + after);
            previous = current;
        }

        Observation postPrefix = observe(fixture, objects, -1, usePreviousInput);
        assertEquals(postBootstrap.introScrollOffset(), postPrefix.introScrollOffset(),
                postPrefix.toString());
        assertEquals(prefixDispatchCount, postPrefix.objectDispatchCount(), postPrefix.toString());

        Observation pre430 = postPrefix;
        for (int traceIndex = 290; traceIndex <= 719; traceIndex++) {
            TraceFrame current = trace.getFrame(traceIndex);
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previous, current);
            assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME, phase,
                    "ordinary AIZ intro row must be a full closure at " + traceIndex);
            int input = drive(trace, fixture, traceIndex, current, phase, objects);
            assertEquals(current.input(), input,
                    "ordinary input alignment at trace row " + traceIndex);
            previous = current;
        }

        Observation post430 = observe(fixture, objects, -1, usePreviousInput);
        TraceFrame row720 = trace.getFrame(720);
        TraceExecutionPhase phase720 =
                TraceReplayBootstrap.phaseForReplay(trace, previous, row720);
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME, phase720);
        int input720 = drive(trace, fixture, 720, row720, phase720, objects);
        assertEquals(row720.input(), input720, "input alignment at trace row 720");

        // A semantically complete trace prefix, not a whole run: close recorded
        // timing at the last driven raw frame so this prefix's own edges and
        // submissions are verified while later, undriven edges are left alone.
        fixture.closeHardwareTimingReplayPrefix(row720.frame());

        Observation post431 = observe(fixture, objects, -1, usePreviousInput);
        assertAll(
                () -> assertEquals((short) 0xE920, postBootstrap.introScrollOffset(),
                        "preBootstrap=" + preBootstrap + ", postBootstrap=" + postBootstrap),
                () -> assertEquals(pre430.objectDispatchCount() + 430,
                        post430.objectDispatchCount(),
                        "430 FULL rows must produce exactly 430 object closures: " + post430),
                () -> assertEquals(0, post430.introScrollOffset(), post430.toString()),
                () -> assertEquals(0x0040, post430.playerCentreX(), post430.toString()),
                () -> assertEquals(post430.objectDispatchCount() + 1,
                        post431.objectDispatchCount(), post431.toString()),
                () -> assertEquals(0, post431.introScrollOffset(), post431.toString()),
                () -> assertEquals(0x0050, post431.playerCentreX(), post431.toString()));
    }

    @Test
    void currentSchemaMgzUsesTheSameClosureDriverWithoutPrefixObjectLeakage() throws Exception {
        TraceData aiz = TraceData.load(AIZ_TRACE);
        TraceData mgz = TraceData.load(MGZ_TRACE);
        assertEquals(aiz.metadata().traceSchema(), mgz.metadata().traceSchema());
        assertEquals(aiz.metadata().traceSchema(), mgz.metadata().traceSchema());

        HeadlessTestFixture fixture = buildCanonicalFixture(mgz, MGZ_BK2, 2, 0, true);
        ObjectManager objects = GameServices.level().getObjectManager();
        TraceReplaySessionBootstrap.BootstrapResult boot =
                TraceReplaySessionBootstrap.applyBootstrap(mgz, fixture, -1);
        TraceReplayBootstrap.ReplayStartState replayStart = boot.replayStart();
        int traceIndex = replayStart.startingTraceIndex();
        TraceFrame previous = replayStart.hasSeededTraceState()
                ? mgz.getFrame(replayStart.seededTraceIndex())
                : traceIndex > 0 ? mgz.getFrame(traceIndex - 1) : null;
        alignCounters(mgz, replayStart, previous);

        int initialDispatchCount = objects.getFrameCounter();
        int fullClosures = 0;
        int rows = 64;
        int lastDrivenRawFrame = -1;
        for (int i = 0; i < rows; i++, traceIndex++) {
            TraceFrame current = mgz.getFrame(traceIndex);
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(mgz, previous, current);
            int remainingBefore = fixture.runner().getRecordingFramesRemaining();
            int input = drive(mgz, fixture, traceIndex, current, phase, objects);
            assertEquals(current.input(), input, "MGZ input alignment at " + traceIndex);
            assertEquals(remainingBefore - 1, fixture.runner().getRecordingFramesRemaining(),
                    "MGZ row must consume exactly one BK2 row at " + traceIndex);
            if (phase != TraceExecutionPhase.VBLANK_ONLY
                    && phase != TraceExecutionPhase.ADVANCE_ONLY
                    && phase != TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY) {
                fullClosures++;
            }
            lastDrivenRawFrame = current.frame();
            previous = current;
        }

        // Prefix, not a whole run -- see the AIZ closure above.
        fixture.closeHardwareTimingReplayPrefix(lastDrivenRawFrame);

        assertEquals(initialDispatchCount + fullClosures, objects.getFrameCounter(),
                "MGZ object dispatch count must equal admitted FULL closures");
        objects.validateRewindReferenceClosure();
    }

    private static HeadlessTestFixture buildCanonicalFixture(
            TraceData trace,
            Path bk2,
            int zone,
            int act,
            boolean metadataStart) throws Exception {
        TraceReplaySessionBootstrap.prepareConfiguration(trace, trace.metadata());
        HeadlessTestFixture.Builder builder = HeadlessTestFixture.builder()
                .withZoneAndAct(zone, act)
                .withRecording(bk2)
                .withRecordingStartFrame(
                        TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                // Same selection AbstractTraceReplayTest makes: when the trace
                // carries recorded hardware timing input, the gameplay context
                // must own recorded admission before the level load submits any
                // hardware work, because applyBootstrap installs the timing
                // replay port against that authority.
                .withHardwareReadinessAdmissionPolicy(
                        trace.hardwareTimingSchedule().hasRecordedInput()
                                ? HardwareReadinessAdmissionPolicy.RECORDED
                                : HardwareReadinessAdmissionPolicy.LIVE);
        if (metadataStart) {
            builder.startPosition(trace.metadata().startX(), trace.metadata().startY())
                    .startPositionIsCentre();
        }
        HeadlessTestFixture fixture = builder.build();
        TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
        return fixture;
    }

    private static void alignCounters(
            TraceData trace,
            TraceReplayBootstrap.ReplayStartState replayStart,
            TraceFrame previous) {
        int start = replayStart.startingTraceIndex();
        TraceReplaySessionBootstrap.alignFrameCountersForReplayStart(
                trace, replayStart, previous,
                start < trace.frameCount() ? trace.getFrame(start) : null);
    }

    private static int drive(
            TraceData trace,
            HeadlessTestFixture fixture,
            int traceIndex,
            TraceFrame current,
            TraceExecutionPhase phase,
            ObjectManager objects) {
        // Same per-row announcement AbstractTraceReplayTest makes before it
        // drives a row (AbstractTraceReplayTest.java:774). Without it the
        // installed timing replay port never latches a raw frame, so every
        // recorded completion edge these prefixes drive over stays unconsumed
        // and the run's close verification throws -- in whichever test class
        // happens to tear this lazily-destroyed session down.
        fixture.beginTraceRow(traceIndex, current.frame());
        int input = TraceReplayFrameClosureDriver.driveS3k(
                phase,
                TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace),
                fixture::stepFrameFromRecording,
                fixture::stepFrameFromRecordingUsingPreviousInput,
                fixture::skipFrameFromRecording,
                fixture::consumeRecordingFrameInputOnly,
                fixture::advancePlayableAnimationsOnly,
                fixture::suppressFirstSidekickAnimationOnce,
                objects::validateRewindReferenceClosure);
        // VBLANK_ONLY does not invoke the driver's post-object callback, but
        // its VBlank-owned mutations must preserve the same reference closure.
        objects.validateRewindReferenceClosure();
        return input;
    }

    private static Observation observe(
            HeadlessTestFixture fixture,
            ObjectManager objects,
            int input,
            boolean usePreviousInput) {
        objects.validateRewindReferenceClosure();
        return new Observation(
                AizPlaneIntroInstance.getIntroScrollOffset(),
                fixture.sprite().getCentreX() & 0xFFFF,
                objects.getFrameCounter(),
                GameServices.level().hasPendingInitialProcessSpritesPass(),
                fixture.runner().getRecordingFramesRemaining(),
                input,
                usePreviousInput,
                true);
    }

    private record Observation(
            int introScrollOffset,
            int playerCentreX,
            int objectDispatchCount,
            boolean pendingSetup,
            int recordingFramesRemaining,
            int input,
            boolean usePreviousInput,
            boolean rewindClosureValid) {
    }
}
