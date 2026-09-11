package com.openggf.tests.trace.s1;

import com.openggf.game.GameServices;
import com.openggf.game.sonic1.objects.Sonic1RingInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.TouchResponseDebugHit;
import com.openggf.level.objects.TouchResponseDebugState;
import com.openggf.level.LevelManager;
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

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_1)
class TestS1Mz1LostRingCollectionOrderRegression {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s1/mz1_fullrun");

    /**
     * Holds the fixture/trace bootstrapped through the production replay path.
     *
     * <p>These regression tests used to do a bespoke manual setup (build fixture,
     * {@code initVblaCounter}, hand-wind {@code OscillationManager}, step from
     * frame 0). That skipped the production replay bootstrap
     * ({@link TraceReplaySessionBootstrap#applyBootstrap}: pre-trace object
     * snapshots, replay-start selection, RNG seed reset, and frame-counter
     * alignment), so lost-ring scatter at the inspected frames depended on
     * ambient state left by parallel fork-mates — making the assertions
     * order-dependent (correct only when a fork-mate happened to leave the right
     * state). Routing through the same bootstrap the passing
     * {@code TestS1Mz1TraceReplay} uses makes the replay deterministic. This is
     * pre-trace bootstrap (a "load save state at the BK2 start"), not per-frame
     * trace-to-engine sync — the comparison-only invariant is preserved.
     */
    private record Mz1Replay(TraceData trace, HeadlessTestFixture fixture,
                             SharedLevel sharedLevel, int startIndex, boolean touchOverlay) {
        void dispose() {
            if (touchOverlay && GameServices.debugOverlay() != null) {
                GameServices.debugOverlay()
                        .setEnabled(com.openggf.debug.DebugOverlayToggle.TOUCH_RESPONSE, false);
            }
            sharedLevel.dispose();
        }
    }

    private Mz1Replay bootstrapMz1(boolean touchOverlay) throws Exception {
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

        // Production replay bootstrap (mirrors AbstractTraceReplayTest): set
        // pre-load config, then load + build the fixture with the canonical
        // recording start frame, then run the shared bootstrap.
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_1, 1, 0);
        boolean ok = false;
        try {
            HeadlessTestFixture.Builder builder = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace));
            if (TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace)) {
                builder.startPosition(meta.startX(), meta.startY()).startPositionIsCentre();
            }
            HeadlessTestFixture fixture = builder.build();
            TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
            if (touchOverlay && GameServices.debugOverlay() != null) {
                GameServices.debugOverlay()
                        .setEnabled(com.openggf.debug.DebugOverlayToggle.TOUCH_RESPONSE, true);
            }
            TraceReplaySessionBootstrap.BootstrapResult boot =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);
            int startIndex = Math.max(0, boot.replayStart().startingTraceIndex());
            ok = true;
            return new Mz1Replay(trace, fixture, sharedLevel, startIndex, touchOverlay);
        } finally {
            if (!ok) {
                sharedLevel.dispose();
            }
        }
    }

    /** Steps the replay from the bootstrap start through {@code frame}, applying ROM-phase lag handling. */
    private static void stepThrough(Mz1Replay r, int upToFrameInclusive) {
        for (int i = r.startIndex(); i < r.trace().frameCount(); i++) {
            TraceFrame expected = r.trace().getFrame(i);
            TraceFrame previous = i > 0 ? r.trace().getFrame(i - 1) : null;
            TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(r.trace(), previous, expected);
            if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                r.fixture().skipFrameFromRecording();
            } else {
                r.fixture().stepFrameFromRecording();
            }
            if (expected.frame() == upToFrameInclusive) {
                return;
            }
        }
    }

    @Test
    void spilledRingDoesNotCollectBeforeTouchPhaseSeesItsPreviousPosition() throws Exception {
        Mz1Replay r = bootstrapMz1(false);
        try {
            stepThrough(r, 758);
            assertEquals(6, r.fixture().sprite().getRingCount(),
                    "Lost-ring collection should run before spill physics advances into overlap");
        } finally {
            r.dispose();
        }
    }

    @Test
    void spilledRingDoesNotCollectWhenFrame759TouchOrderHasNotReachedItsSlot() throws Exception {
        Mz1Replay r = bootstrapMz1(true);
        try {
            stepThrough(r, 759);
            assertEquals(6, r.fixture().sprite().getRingCount(),
                    () -> "Lost-ring recollection should still be blocked at frame 759"
                            + " player=" + formatPlayerState(r.fixture())
                            + " touchHits=" + describeTouchHits(GameServices.level().getObjectManager()));
        } finally {
            r.dispose();
        }
    }

    @Test
    void lostRingDoesNotCollectDuringTouchPhaseBeforePlatformSlopeCarryRuns() throws Exception {
        Mz1Replay r = bootstrapMz1(false);
        try {
            for (int i = r.startIndex(); i < r.trace().frameCount(); i++) {
                TraceFrame expected = r.trace().getFrame(i);
                TraceFrame previous = i > 0 ? r.trace().getFrame(i - 1) : null;
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(r.trace(), previous, expected);

                if (expected.frame() < 762) {
                    if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                        r.fixture().skipFrameFromRecording();
                    } else {
                        r.fixture().stepFrameFromRecording();
                    }
                    continue;
                }

                assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME, phase,
                        "Frame 762 regression assumes a normal gameplay frame");
                assertEquals(6, r.fixture().sprite().getRingCount(),
                        "Fixture assumption changed before frame 762");

                int beforeX = r.fixture().sprite().getCentreX();
                int beforeY = r.fixture().sprite().getCentreY();
                boolean beforeOnObject = r.fixture().sprite().isOnObject();
                stepTouchPhaseOnly(r.fixture(), expected.input());
                int afterX = r.fixture().sprite().getCentreX();
                int afterY = r.fixture().sprite().getCentreY();
                boolean afterOnObject = r.fixture().sprite().isOnObject();
                var ridingObject = GameServices.level().getObjectManager() != null
                        ? GameServices.level().getObjectManager().getRidingObject(r.fixture().sprite())
                        : null;
                String ridingSummary = ridingObject == null
                        ? "<none>"
                        : ridingObject.getClass().getSimpleName()
                                + String.format("@0x%04X,0x%04X", ridingObject.getX(), ridingObject.getY());

                assertEquals(6, r.fixture().sprite().getRingCount(),
                        "Touch phase should not recollect the spilled ring before the platform's current-frame slope carry"
                                + String.format(" before=(0x%04X,0x%04X onObj=%s) after=(0x%04X,0x%04X onObj=%s) riding=%s",
                                beforeX, beforeY, beforeOnObject, afterX, afterY, afterOnObject, ridingSummary));
                return;
            }
        } finally {
            r.dispose();
        }
    }

    @Test
    void stageRingDoesNotCollectUntilReactToItemHeightMinusThreeActuallyOverlaps() throws Exception {
        Mz1Replay r = bootstrapMz1(false);
        try {
            stepThrough(r, 2098);
            assertEquals(0, r.fixture().sprite().getRingCount(),
                    () -> "Placed ring should not collect until the next frame's"
                            + " ReactToItem height-minus-three overlap"
                            + " player=" + formatPlayerState(r.fixture()));
        } finally {
            r.dispose();
        }
    }

    @Test
    void stageTouchPassCollectsOnlyOneOverlappingRingPerFrameInSlotOrder() throws Exception {
        Mz1Replay r = bootstrapMz1(false);
        try {
            stepThrough(r, 2808);
            assertEquals(8, r.fixture().sprite().getRingCount(),
                    () -> "S1 ReactToItem should stop after the first overlapping ring"
                            + " player=" + formatPlayerState(r.fixture()));
        } finally {
            r.dispose();
        }
    }

    @Test
    void stageTouchPassTargetsRomFirstRingAtFrame2808() throws Exception {
        Mz1Replay r = bootstrapMz1(true);
        try {
            stepThrough(r, 2808);
            assertEquals(8, r.fixture().sprite().getRingCount(),
                    "Frame 2808 should still add exactly one ring");
            assertEquals("slot=34 flags=0x47 obj=0x0D2C,0x03F0",
                    firstOverlappingHit(GameServices.level().getObjectManager()),
                    () -> "S1 ReactToItem should collect the lower-slot 0x0D2C ring first"
                            + " player=" + formatPlayerState(r.fixture())
                            + " allHits=" + describeAllTouchHits(GameServices.level().getObjectManager())
                            + " ringStates=" + describeStageRingStates(GameServices.level().getObjectManager(),
                            0x0D2C, 0x0D14));
        } finally {
            r.dispose();
        }
    }

    private static void stepTouchPhaseOnly(HeadlessTestFixture fixture, int inputMask) {
        boolean up = (inputMask & com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_UP) != 0;
        boolean down = (inputMask & com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_DOWN) != 0;
        boolean left = (inputMask & com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_LEFT) != 0;
        boolean right = (inputMask & com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_RIGHT) != 0;
        boolean jump = (inputMask & com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_JUMP) != 0;

        var sprite = fixture.sprite();
        LevelManager levelManager = GameServices.level();
        int nextFrame = fixture.frameCount() + 1;

        sprite.setJumpInputPressed(jump);
        sprite.setDirectionalInputPressed(up, down, left, right);

        levelManager.updateZoneFeaturesPrePhysics();
        levelManager.prepareTouchResponseSnapshots();

        com.openggf.sprites.managers.SpriteManager.tickPlayablePhysics(sprite,
                up, down, left, right, jump,
                false, false, false, levelManager, nextFrame);
    }

    private static String formatPlayerState(HeadlessTestFixture fixture) {
        var sprite = fixture.sprite();
        return String.format("@0x%04X,0x%04X rings=%d onObj=%s frame=%d",
                sprite.getCentreX(),
                sprite.getCentreY(),
                sprite.getRingCount(),
                sprite.isOnObject(),
                fixture.frameCount());
    }

    private static String describeTouchHits(ObjectManager objectManager) {
        if (objectManager == null) {
            return "<no object manager>";
        }
        TouchResponseDebugState state = objectManager.getTouchResponseDebugState();
        if (state == null || state.getHits().isEmpty()) {
            return "<none>";
        }
        return state.getHits().stream()
                .filter(TouchResponseDebugHit::overlapping)
                .map(hit -> String.format("slot=%d flags=0x%02X obj=0x%04X,0x%04X",
                        hit.slotIndex(),
                        hit.flags() & 0xFF,
                        hit.objectX() & 0xFFFF,
                        hit.objectY() & 0xFFFF))
                .collect(Collectors.joining(" | "));
    }

    private static String firstOverlappingHit(ObjectManager objectManager) {
        if (objectManager == null) {
            return "<no object manager>";
        }
        TouchResponseDebugState state = objectManager.getTouchResponseDebugState();
        if (state == null) {
            return "<no touch debug state>";
        }
        return state.getHits().stream()
                .filter(TouchResponseDebugHit::overlapping)
                .findFirst()
                .map(hit -> String.format("slot=%d flags=0x%02X obj=0x%04X,0x%04X",
                        hit.slotIndex(),
                        hit.flags() & 0xFF,
                        hit.objectX() & 0xFFFF,
                        hit.objectY() & 0xFFFF))
                .orElse("<none>");
    }

    private static String describeAllTouchHits(ObjectManager objectManager) {
        if (objectManager == null) {
            return "<no object manager>";
        }
        TouchResponseDebugState state = objectManager.getTouchResponseDebugState();
        if (state == null) {
            return "<no touch debug state>";
        }
        if (state.getHits().isEmpty()) {
            return "<none>";
        }
        return state.getHits().stream()
                .map(hit -> String.format("slot=%d overlap=%s flags=0x%02X obj=0x%04X,0x%04X",
                        hit.slotIndex(),
                        hit.overlapping(),
                        hit.flags() & 0xFF,
                        hit.objectX() & 0xFFFF,
                        hit.objectY() & 0xFFFF))
                .collect(Collectors.joining(" | "));
    }

    private static String describeStageRingStates(ObjectManager objectManager, int... xs) {
        if (objectManager == null) {
            return "<no object manager>";
        }
        StringBuilder builder = new StringBuilder();
        for (int x : xs) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(String.format("0x%04X=%s", x & 0xFFFF, describeRingState(objectManager, x)));
        }
        return builder.toString();
    }

    private static String describeRingState(ObjectManager objectManager, int targetX) {
        for (var instance : objectManager.getActiveObjects()) {
            if (!(instance instanceof Sonic1RingInstance ring)) {
                continue;
            }
            if (ring.getX() != targetX) {
                continue;
            }
            String state = "<unknown>";
            try {
                Field field = Sonic1RingInstance.class.getDeclaredField("state");
                field.setAccessible(true);
                state = String.valueOf(field.get(ring));
            } catch (ReflectiveOperationException ignored) {
                state = "<reflect-failed>";
            }
            int slot = ring instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
            return String.format("%s@slot=%d", state, slot);
        }
        return "<missing>";
    }
}
