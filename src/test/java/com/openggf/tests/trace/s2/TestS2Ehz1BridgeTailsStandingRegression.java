package com.openggf.tests.trace.s2;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.objects.BridgeObjectInstance;
import com.openggf.game.sonic2.objects.ResultsScreenObjectInstance;
import com.openggf.game.sonic2.objects.SignpostObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.physics.Direction;
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
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiresRom(SonicGame.SONIC_2)
class TestS2Ehz1BridgeTailsStandingRegression {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int TAILS_OBJ18_LEFT_EDGE_BALANCE_FRAME = 385;
    private static final int TAILS_OBJ18_BALANCE_WIDTH_FRAME = 395;
    private static final int PROBE_FRAME = 875;
    private static final int DESPAWN_FRAME = 1131;
    private static final int TAILS_BRIDGE_LANDING_FRAME = 2911;
    private static final int SONIC_BRIDGE_JUMP_PREP_FRAME = 4564;
    private static final int SIGNPOST_RESULTS_EDGE_FRAME = 5040;
    private static final int SIGNPOST_RESULTS_FIRST_GROUNDED_DIVERGENCE_FRAME = 5105;

    @Test
    void tailsFacesLeftAtObj18LeftEdge() throws Exception {
        BridgeProbe probe = replayToFrame(TAILS_OBJ18_LEFT_EDGE_BALANCE_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            AbstractPlayableSprite tails = probe.tails();
            assertNotNull(expected.sidekick(), "Trace frame does not contain recorded Tails state");
            assertNotNull(tails, "Tails should be present at the Obj18 left-edge probe frame");
            assertEquals(0x11, expected.sidekick().standOnObj(),
                    "Probe frame changed; expected Tails to be standing on Obj18 slot 0x11");
            assertEquals(1, expected.sidekick().statusByte() & 0x01,
                    "Probe frame changed; recorded Tails should be facing left");
            assertEquals(expected.sidekick().statusByte() & 0x01,
                    tails.getDirection() == Direction.LEFT ? 1 : 0,
                    () -> "Tails should face toward Obj18's left edge: " + probe.describeActual());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void tailsUsesObj18WidthPixelsForObjectEdgeBalance() throws Exception {
        BridgeProbe probe = replayToFrame(TAILS_OBJ18_BALANCE_WIDTH_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            AbstractPlayableSprite tails = probe.tails();
            assertNotNull(expected.sidekick(), "Trace frame does not contain recorded Tails state");
            assertNotNull(tails, "Tails should be present at the Obj18 balance-width probe frame");
            assertEquals(0x11, expected.sidekick().standOnObj(),
                    "Probe frame changed; expected Tails to be standing on Obj18 slot 0x11");
            assertEquals(expected.sidekick().statusByte() & 0x01,
                    tails.getDirection() == Direction.LEFT ? 1 : 0,
                    () -> "Tails facing bit diverged on Obj18 balance edge check: " + probe.describeActual());
            assertEquals(expected.sidekick().statusByte() & 0x08,
                    tails.isOnObject() ? 0x08 : 0,
                    () -> "Tails on-object bit diverged on Obj18 balance edge check: " + probe.describeActual());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void tailsMatchesRecordedPlatformCarryStateAtProbeFrame() throws Exception {
        BridgeProbe probe = replayToFrame(PROBE_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            assertNotNull(expected.sidekick(), "Trace frame does not contain recorded Tails state");
            assertEquals(0x11, expected.sidekick().standOnObj(),
                    "Probe frame changed; expected recorded interact slot 0x11");
            assertEquals(0, expected.sidekick().statusByte() & 0x08,
                    "Probe frame changed; expected latched interact slot without active on-object status");
            assertEquals(-1, probe.actualStandOnSlot(),
                    () -> "Tails should not have an active riding object once the recorded on-object bit is clear: "
                            + probe.describeActual());
            assertEquals(expected.sidekick().x(), probe.tails().getCentreX(),
                    () -> "Tails platform carry X drifted at probe frame: " + probe.describeActual());
            assertEquals(expected.sidekick().y(), probe.tails().getCentreY(),
                    () -> "Tails platform carry Y drifted at probe frame: " + probe.describeActual());
            assertEquals(expected.sidekick().xSpeed(), probe.tails().getXSpeed(),
                    () -> "Tails platform carry X speed drifted: " + probe.describeActual());
            assertEquals(expected.sidekick().gSpeed(), probe.tails().getGSpeed(),
                    () -> "Tails platform carry ground speed drifted: " + probe.describeActual());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void tailsDespawnsAtRecordedOffscreenBridgeTransition() throws Exception {
        BridgeProbe probe = replayToFrame(DESPAWN_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            assertNotNull(expected.sidekick(), "Trace frame does not contain recorded Tails state");
            assertEquals(expected.sidekick().x(), probe.tails().getCentreX(),
                    () -> "Tails X diverged at off-screen despawn transition: " + probe.describeActual());
            assertEquals(expected.sidekick().y(), probe.tails().getCentreY(),
                    () -> "Tails Y diverged at off-screen despawn transition: " + probe.describeActual());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void tailsUsesRecordedFlatBridgeSurfaceOnInitialLanding() throws Exception {
        BridgeProbe probe = replayToFrame(TAILS_BRIDGE_LANDING_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            AbstractPlayableSprite tails = probe.tails();
            assertNotNull(expected.sidekick(), "Trace frame does not contain recorded Tails state");
            assertNotNull(tails, "Tails should be present at the bridge landing frame");
            assertEquals(expected.sidekick().x(), tails.getCentreX(),
                    () -> "Tails X drifted on initial bridge landing: " + probe.describeActual());
            assertEquals(expected.sidekick().y(), tails.getCentreY(),
                    () -> "Tails Y drifted on initial bridge landing: " + probe.describeActual());
            assertEquals(expected.sidekick().xSpeed(), tails.getXSpeed(),
                    () -> "Tails X speed drifted on initial bridge landing: " + probe.describeActual());
            assertEquals(false, tails.getAir(),
                    () -> "Tails should be grounded after initial bridge landing: " + probe.describeActual());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void sonicMatchesRecordedBridgeSurfaceBeforeJumpOff() throws Exception {
        BridgeProbe probe = replayToFrame(SONIC_BRIDGE_JUMP_PREP_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            AbstractPlayableSprite sonic = probe.sonic();
            assertNotNull(sonic, "Sonic should be present at the bridge jump-off probe frame");
            assertEquals(expected.x(), sonic.getCentreX(),
                    () -> "Sonic X drifted on the bridge before jump-off: " + probe.describeActual());
            assertEquals(expected.y(), sonic.getCentreY(),
                    () -> "Sonic Y drifted on the bridge before jump-off: " + probe.describeActual());
            assertEquals(expected.xSpeed(), sonic.getXSpeed(),
                    () -> "Sonic X speed drifted on the bridge before jump-off: " + probe.describeActual());
            assertEquals(expected.ySpeed(), sonic.getYSpeed(),
                    () -> "Sonic Y speed drifted on the bridge before jump-off: " + probe.describeActual());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void sonicDoesNotRetainWalkoffControlAfterResultsSpawnEdgeCase() throws Exception {
        BridgeProbe probe = replayToFrame(SIGNPOST_RESULTS_EDGE_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            AbstractPlayableSprite sonic = probe.sonic();
            assertNotNull(sonic, "Sonic should be present at the signpost results-edge probe frame");
            assertEquals(expected.x(), sonic.getCentreX(),
                    () -> "Sonic X drifted after end-of-act results spawn: " + probe.describeActual());
            assertEquals(expected.y(), sonic.getCentreY(),
                    () -> "Sonic Y drifted after end-of-act results spawn: " + probe.describeActual());
            assertEquals(expected.xSpeed(), sonic.getXSpeed(),
                    () -> "Sonic X speed drifted after end-of-act results spawn: " + probe.describeActual());
            assertEquals(expected.gSpeed(), sonic.getGSpeed(),
                    () -> "Sonic ground speed drifted after end-of-act results spawn: " + probe.describeActual());
            assertEquals(false, sonic.isControlLocked(),
                    () -> "Signpost walk-off control should not persist after results spawn: "
                            + probe.describeActual());
            assertEquals(false, sonic.isForceInputRight(),
                    () -> "Signpost forced-right input should be cleared after results spawn: "
                            + probe.describeActual());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void sonicMatchesRecordedEndOfActLandingDecelerationAtFirstBadFrame() throws Exception {
        BridgeProbe probe = replayToFrame(SIGNPOST_RESULTS_FIRST_GROUNDED_DIVERGENCE_FRAME);
        try {
            TraceFrame expected = probe.expectedFrame();
            AbstractPlayableSprite sonic = probe.sonic();
            assertNotNull(sonic, "Sonic should be present at the end-of-act landing divergence frame");
            assertEquals(expected.x(), sonic.getCentreX(),
                    () -> "Sonic X drifted at the first grounded end-of-act divergence frame: "
                            + probe.describeActual());
            assertEquals(expected.y(), sonic.getCentreY(),
                    () -> "Sonic Y drifted at the first grounded end-of-act divergence frame: "
                            + probe.describeActual());
            assertEquals(expected.xSpeed(), sonic.getXSpeed(),
                    () -> "Sonic X speed drifted at the first grounded end-of-act divergence frame: "
                            + probe.describeActual());
            assertEquals(expected.gSpeed(), sonic.getGSpeed(),
                    () -> "Sonic ground speed drifted at the first grounded end-of-act divergence frame: "
                            + probe.describeActual());
        } finally {
            probe.dispose();
        }
    }

    private static BridgeProbe replayToFrame(int targetFrame) throws Exception {
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

            return new BridgeProbe(sharedLevel, trace.getFrame(targetFrame));
        } catch (Throwable t) {
            sharedLevel.dispose();
            throw t;
        }
    }

    private record BridgeProbe(SharedLevel sharedLevel, TraceFrame expectedFrame) {
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

        int actualMainStandOnSlot() {
            AbstractPlayableSprite sonic = sonic();
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (sonic == null || objectManager == null) {
                return -1;
            }
            ObjectInstance riding = objectManager.getRidingObject(sonic);
            if (riding instanceof AbstractObjectInstance aoi) {
                return aoi.getSlotIndex();
            }
            return -1;
        }

        int actualStandOnSlot() {
            AbstractPlayableSprite tails = tails();
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (tails == null || objectManager == null) {
                return -1;
            }
            ObjectInstance riding = objectManager.getRidingObject(tails);
            if (riding instanceof AbstractObjectInstance aoi) {
                return aoi.getSlotIndex();
            }
            return -1;
        }

        String describeActual() {
            AbstractPlayableSprite sonic = sonic();
            AbstractPlayableSprite tails = tails();
            if (sonic == null || tails == null) {
                return String.format("sonic=%s tails=%s",
                        sonic == null ? "<missing>" : "<present>",
                        tails == null ? "<missing>" : "<present>");
            }
            return String.format(
                    "sonic=(x=0x%04X y=0x%04X xs=%d ys=%d gs=%d air=%s on=%d lock=%s forceR=%s) "
                            + "tails=(x=0x%04X y=0x%04X xs=%d ys=%d gs=%d air=%s on=%d) "
                            + "goal=%s results=%s bridges=%s",
                    sonic.getCentreX() & 0xFFFF,
                    sonic.getCentreY() & 0xFFFF,
                    (int) sonic.getXSpeed(),
                    (int) sonic.getYSpeed(),
                    (int) sonic.getGSpeed(),
                    sonic.getAir(),
                    actualMainStandOnSlot(),
                    sonic.isControlLocked(),
                    sonic.isForceInputRight(),
                    tails.getCentreX() & 0xFFFF,
                    tails.getCentreY() & 0xFFFF,
                    (int) tails.getXSpeed(),
                    (int) tails.getYSpeed(),
                    (int) tails.getGSpeed(),
                    tails.getAir(),
                    actualStandOnSlot(),
                    goalDescription(),
                    resultsDescription(),
                    bridgeDescriptions());
        }

        private String goalDescription() {
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager == null) {
                return "<no object manager>";
            }
            return objectManager.getActiveObjects().stream()
                    .filter(SignpostObjectInstance.class::isInstance)
                    .map(SignpostObjectInstance.class::cast)
                    .map(this::describeGoalPlate)
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("<none>");
        }

        private String describeGoalPlate(SignpostObjectInstance signpost) {
            int slot = signpost instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
            return String.format("slot=%d@0x%04X,0x%04X state=%s spawned=%s walkFrame=%s",
                    slot,
                    signpost.getCenterX() & 0xFFFF,
                    signpost.getCenterY() & 0xFFFF,
                    reflectField(signpost, "routineState"),
                    reflectField(signpost, "resultsSpawned"),
                    reflectField(signpost, "walkOffEnteredVIntRunCount"));
        }

        private String resultsDescription() {
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager == null) {
                return "<no object manager>";
            }
            return objectManager.getActiveObjects().stream()
                    .filter(ResultsScreenObjectInstance.class::isInstance)
                    .map(instance -> instance instanceof AbstractObjectInstance aoi
                            ? String.format("slot=%d", aoi.getSlotIndex())
                            : "<untracked>")
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("<none>");
        }

        private Object reflectField(Object target, String fieldName) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException e) {
                return "<" + fieldName + " unavailable>";
            }
        }

        private String bridgeDescriptions() {
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager == null) {
                return "<no object manager>";
            }
            return objectManager.getActiveObjects().stream()
                    .filter(instance -> instance instanceof BridgeObjectInstance)
                    .map(instance -> {
                        int slot = instance instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
                        return String.format("slot=%d@0x%04X,0x%04X",
                                slot,
                                instance.getX() & 0xFFFF,
                                instance.getY() & 0xFFFF);
                    })
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("<none>");
        }

        void dispose() {
            sharedLevel.dispose();
        }
    }
}
