package com.openggf.tests.trace.s2;

import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@RequiresRom(SonicGame.SONIC_2)
class TestS2Ehz1MonitorBreakRegression {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int PRE_ACCEL_FRAME = 2921;
    private static final int PRE_BREAK_FRAME = 2953;
    private static final int BREAK_FRAME = 2954;
    private static final int POST_BREAK_FRAME = BREAK_FRAME + 1;
    private static final int MONITOR_ID = 0x26;
    private static final int EXPLOSION_ID = 0x27;
    private static final int MONITOR_X = 0x1344;
    private static final int MONITOR_Y = 0x0375;

    @Test
    void monitorEdgeContactPreservesRecordedAccelerationBeforeBreakWindow() throws Exception {
        MonitorProbe probe = replayToFrame(PRE_ACCEL_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            assertNotNull(probe.findObject(MONITOR_ID, MONITOR_X, MONITOR_Y),
                    () -> "Monitor missing before recorded edge-contact window: " + probe.describeObjects());
            assertNull(probe.findObject(EXPLOSION_ID, MONITOR_X, MONITOR_Y),
                    () -> "Explosion spawned before monitor edge-contact window resolved: "
                            + probe.describeState());
            assertEquals(expected.xSpeed(), probe.sonic().getXSpeed(),
                    () -> "Monitor edge contact zeroed Sonic X speed early: " + probe.describeState());
            assertEquals(expected.gSpeed(), probe.sonic().getGSpeed(),
                    () -> "Monitor edge contact zeroed Sonic ground speed early: " + probe.describeState());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void monitorDoesNotBreakBeforeRecordedFrame() throws Exception {
        MonitorProbe probe = replayToFrame(PRE_BREAK_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            assertNotNull(probe.findObject(MONITOR_ID, MONITOR_X, MONITOR_Y),
                    () -> "Monitor vanished before recorded break: " + probe.describeObjects());
            assertNull(probe.findObject(EXPLOSION_ID, MONITOR_X, MONITOR_Y),
                    () -> "Explosion spawned one frame early: " + probe.describeState());
            assertEquals(expected.xSpeed(), probe.sonic().getXSpeed(),
                    () -> "Sonic X speed drifted before recorded monitor break: "
                            + probe.describeState());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void monitorBreaksOnRecordedFrame() throws Exception {
        MonitorProbe probe = replayToFrame(BREAK_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            assertNotNull(probe.findObject(MONITOR_ID, MONITOR_X, MONITOR_Y),
                    () -> "Broken monitor shell missing on recorded break frame: "
                            + probe.describeObjects());
            assertNotNull(probe.findObject(EXPLOSION_ID, MONITOR_X, MONITOR_Y),
                    () -> "Explosion missing on recorded break frame: " + probe.describeObjects());
            assertEquals(expected.air(), probe.sonic().getAir(),
                    () -> "Sonic air state drifted on recorded monitor break frame: "
                            + probe.describeState());
            assertEquals(expected.x(), probe.sonic().getCentreX(),
                    () -> "Sonic X drifted on recorded monitor break frame: "
                            + probe.describeState());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void monitorBreakPreservesRecordedTailsReleaseCadence() throws Exception {
        MonitorProbe breakProbe = replayToFrame(BREAK_FRAME);
        try {
            TraceFrame expected = breakProbe.expectedFrame();
            assertNotNull(expected.sidekick(), "Trace frame does not contain recorded Tails state");
            assertNotNull(breakProbe.tails(), () -> "Tails missing on recorded break frame: " + breakProbe.describeState());
            assertEquals(expected.sidekick().air(), breakProbe.tails().getAir(),
                    () -> "Tails air state drifted on recorded monitor break frame: "
                            + breakProbe.describeState());
            assertEquals(expected.sidekick().ySpeed(), breakProbe.tails().getYSpeed(),
                    () -> "Tails Y speed advanced too early on recorded monitor break frame: "
                            + breakProbe.describeState());
        } finally {
            breakProbe.dispose();
        }

        MonitorProbe postBreakProbe = replayToFrame(POST_BREAK_FRAME);
        try {
            TraceFrame expected = postBreakProbe.expectedFrame();
            assertNotNull(expected.sidekick(), "Trace frame does not contain recorded Tails state");
            assertNotNull(postBreakProbe.tails(),
                    () -> "Tails missing on post-break cadence frame: " + postBreakProbe.describeState());
            assertEquals(expected.sidekick().air(), postBreakProbe.tails().getAir(),
                    () -> "Tails air state drifted on post-break cadence frame: "
                            + postBreakProbe.describeState());
            assertEquals(expected.sidekick().ySpeed(), postBreakProbe.tails().getYSpeed(),
                    () -> "Tails Y speed drifted on post-break cadence frame: "
                            + postBreakProbe.describeState());
        } finally {
            postBreakProbe.dispose();
        }
    }

    private static MonitorProbe replayToFrame(int targetFrame) throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);

        Path bk2Path;
        try (var files = Files.list(TRACE_DIR)) {
            bk2Path = files
                    .filter(path -> path.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + TRACE_DIR);

        TraceData trace = TraceData.load(TRACE_DIR);
        TraceMetadata meta = trace.metadata();
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_2, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1).replayStart();

            for (int i = replayStart.startingTraceIndex(); i <= targetFrame; i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase phase =
                        TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
            }

            return new MonitorProbe(sharedLevel, trace.getFrame(targetFrame));
        } catch (Throwable t) {
            sharedLevel.dispose();
            throw t;
        }
    }

    private record MonitorProbe(SharedLevel sharedLevel, TraceFrame expectedFrame) {
        AbstractPlayableSprite sonic() {
            SpriteManager spriteManager = GameServices.sprites();
            if (spriteManager == null) {
                return null;
            }
            return spriteManager.getSprite("sonic") instanceof AbstractPlayableSprite playable
                    ? playable
                    : null;
        }

        AbstractPlayableSprite tails() {
            SpriteManager spriteManager = GameServices.sprites();
            if (spriteManager == null || spriteManager.getSidekicks().isEmpty()) {
                return null;
            }
            return spriteManager.getSidekicks().getFirst();
        }

        ObservedObject findObject(int objectId, int x, int y) {
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager == null) {
                return null;
            }
            return objectManager.getActiveObjects().stream()
                    .filter(instance -> !instance.isDestroyed())
                    .filter(instance -> instance.getSpawn() != null)
                    .filter(instance -> instance.getSpawn().objectId() == objectId)
                    .map(TestS2Ehz1MonitorBreakRegression::toObservedObject)
                    .filter(object -> object.x() == x && object.y() == y)
                    .findFirst()
                    .orElse(null);
        }

        String describeState() {
            AbstractPlayableSprite sonic = sonic();
            AbstractPlayableSprite tails = tails();
            if (sonic == null) {
                return "sonic=<missing> tails="
                        + (tails == null ? "<missing>" : describePlayable(tails))
                        + " objects=" + describeObjects();
            }
            return String.format(
                    "sonic=%s tails=%s objects=%s",
                    describePlayable(sonic),
                    tails == null ? "<missing>" : describePlayable(tails),
                    describeObjects());
        }

        private String describePlayable(AbstractPlayableSprite playable) {
            return String.format(
                    "(x=0x%04X y=0x%04X xs=0x%04X ys=0x%04X gs=0x%04X air=%s roll=%s anim=0x%02X)",
                    playable.getCentreX() & 0xFFFF,
                    playable.getCentreY() & 0xFFFF,
                    playable.getXSpeed() & 0xFFFF,
                    playable.getYSpeed() & 0xFFFF,
                    playable.getGSpeed() & 0xFFFF,
                    playable.getAir(),
                    playable.getRolling(),
                    playable.getAnimationId() & 0xFF);
        }

        String describeObjects() {
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager == null) {
                return "<no object manager>";
            }
            return objectManager.getActiveObjects().stream()
                    .filter(instance -> !instance.isDestroyed())
                    .filter(instance -> instance.getSpawn() != null)
                    .filter(instance -> {
                        int objectId = instance.getSpawn().objectId();
                        return objectId == MONITOR_ID || objectId == EXPLOSION_ID;
                    })
                    .map(TestS2Ehz1MonitorBreakRegression::toObservedObject)
                    .sorted(Comparator.comparingInt(ObservedObject::slot))
                    .map(ObservedObject::toString)
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("<none>");
        }

        void dispose() {
            sharedLevel.dispose();
        }
    }

    private static ObservedObject toObservedObject(ObjectInstance instance) {
        int slot = instance instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
        int objectId = instance.getSpawn() != null ? instance.getSpawn().objectId() : -1;
        return new ObservedObject(
                slot,
                objectId,
                instance.getX() & 0xFFFF,
                instance.getY() & 0xFFFF);
    }

    private record ObservedObject(int slot, int objectId, int x, int y) {
        @Override
        public String toString() {
            return String.format("slot=%d id=0x%02X x=0x%04X y=0x%04X", slot, objectId, x, y);
        }
    }
}
