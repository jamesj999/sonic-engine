package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sonic2SpecialStageStreamedObjectCadenceTest {

    @Test
    void streamedRingAndBombRunTheirRomInitFallthroughBeforeTheNextNormalPass() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        set(objectManager, "objectLocationData", new byte[] {
                0x05, 0x40,       // ring: distance 5, angle $40
                0x45, 0x60,       // bomb: distance 5, angle $60
                (byte) 0xFF
        });
        set(objectManager, "currentPosition", 0);

        Sonic2TrackAnimator trackAnimator = new Sonic2TrackAnimator(null);
        trackAnimator.initializeWithMockLayout();
        set(trackAnimator, "currentSegmentType", 4); // length 11: depth = 5*4 + 11*4 = $40

        set(manager, "objectManager", objectManager);
        set(manager, "trackAnimator", trackAnimator);
        set(manager, "perspectiveData", alwaysVisiblePerspective());
        set(manager, "playerBootstrapPhase", Sonic2SpecialStageManager.PlayerBootstrapPhase.INITIALIZED);
        set(manager, "drawingIndex", 4);
        set(manager, "lastDrawingIndex", 3);

        invoke(manager, "streamSpecialStageObjects");
        // SSObjectsManager allocates the entries, then the same SS_MainLoop
        // iteration reaches RunObjects (s2.asm:6679-6688). Exercise both halves
        // of that production iteration instead of asking the allocator to dispatch.
        invoke(manager, "executeActiveSpecialStageObjects");

        List<Sonic2SpecialStageObject> objects = objectManager.getActiveObjects();
        assertEquals(2, objects.size());
        assertEquals(0x003F3334L, depthFixed(objects.get(0)),
                "Obj60 init must fall through to the drawing-index-4 $CCCC depth step");
        assertEquals(0x003F3334L, depthFixed(objects.get(1)),
                "Obj61 shares the same init-fallthrough depth path");
        assertEquals(Sonic2SpecialStageObject.State.ACTIVE, objects.get(0).getState());
        assertEquals(0, objects.get(0).getAnimIndex());
        assertTrue(objects.get(0).isOnScreen(), "the init tick must also publish projection state");

        set(manager, "drawingIndex", 0);
        invoke(manager, "executeActiveSpecialStageObjects");

        assertEquals(0x003E6667L, depthFixed(objects.get(0)),
                "the following active pass must contribute exactly one normal $CCCD step");
        assertEquals(0x003E6667L, depthFixed(objects.get(1)));
    }

    @Test
    void streamedEmeraldRunsRoutineZeroOnItsAllocationAssociatedPass() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        set(objectManager, "objectLocationData", new byte[] {
                (byte) Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager.MARKER_EMERALD
        });
        set(objectManager, "currentPosition", 0);

        Sonic2TrackAnimator trackAnimator = new Sonic2TrackAnimator(null);
        trackAnimator.initializeWithMockLayout();
        set(manager, "objectManager", objectManager);
        set(manager, "trackAnimator", trackAnimator);
        set(manager, "playerBootstrapPhase", Sonic2SpecialStageManager.PlayerBootstrapPhase.INITIALIZED);
        set(manager, "drawingIndex", 4);
        set(manager, "lastDrawingIndex", 3);

        invoke(manager, "streamSpecialStageObjects");
        // Obj59 is allocated by SSObjectsManager before the same iteration's
        // RunObjects scan reaches its later SST slot (s2.asm:6679-6688, 6992-6999,
        // 72304-72325).
        invoke(manager, "executeActiveSpecialStageObjects");

        Sonic2SpecialStageEmerald emerald = objectManager.getActiveEmerald();
        assertTrue(emerald.restrictsControlsToStart(),
                "Obj59 routine zero sets SS_Pause_Only_flag on its allocation-associated pass");
        assertEquals(1, emerald.captureRewindSnapshot().emeraldPhaseTimer(),
                "allocation must contribute exactly one of Obj59's sixty initialization passes");

        set(manager, "drawingIndex", 0);
        invoke(manager, "executeActiveSpecialStageObjects");
        assertEquals(2, emerald.captureRewindSnapshot().emeraldPhaseTimer(),
                "the following active pass must advance the delay exactly once more");
    }

    @Test
    void emeraldFinalInitializationPassFallsThroughToApproachMotion() {
        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
        emerald.initialize(54, 0x40);

        for (int pass = 0; pass < 59; pass++) {
            emerald.update(0, false, 12, false);
        }
        assertEquals(Sonic2SpecialStageEmerald.EmeraldPhase.INITIALIZING, emerald.getPhase());
        assertEquals(54, emerald.getDepth());

        emerald.update(0, false, 12, true);

        assertEquals(Sonic2SpecialStageEmerald.EmeraldPhase.APPROACHING, emerald.getPhase());
        assertEquals(53, emerald.getDepth(),
                "Obj59 routine zero falls through to loc_36022 on the -$3C pass");
    }

    @Test
    void emeraldChecksAnimationBeforeMovementAndAwardsOnTheFollowingPass() throws Exception {
        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
        emerald.initialize(54, 0x40);
        set(emerald, "phase", Sonic2SpecialStageEmerald.EmeraldPhase.APPROACHING);
        set(emerald, "depthFixed", 3L << 16);
        emerald.updateScreenPosition(alwaysVisiblePerspective(), 0, false);

        emerald.update(0, false, 12, true);

        assertEquals(2, emerald.getDepth(), "movement should cross into animation 9");
        assertEquals(Sonic2SpecialStageEmerald.EmeraldPhase.APPROACHING, emerald.getPhase(),
                "loc_360F0 reads the pre-movement animation");

        emerald.updateScreenPosition(alwaysVisiblePerspective(), 0, false);
        emerald.update(0, false, 12, false);

        assertEquals(Sonic2SpecialStageEmerald.EmeraldPhase.COLLECTED, emerald.getPhase(),
                "the next pass must select routine 8 and award the emerald immediately");
        assertTrue(emerald.isEmeraldAwarded(),
                "the routine-change pass also owns the emerald-music award latch");
        assertEquals(0x63, emerald.captureRewindSnapshot().emeraldPhaseTimer());

        emerald.update(0, false, 12, false);

        assertEquals(0x62, emerald.captureRewindSnapshot().emeraldPhaseTimer(),
                "routine 8 starts decrementing on the pass after the award");
    }

    @Test
    void emeraldSuccessCountdownCompletesOnlyAfterZero() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
        emerald.initialize(54, 0x40);
        emerald.setManager(manager);
        set(emerald, "phase", Sonic2SpecialStageEmerald.EmeraldPhase.APPROACHING);
        set(emerald, "animIndex", 9);

        emerald.update(0, false, 12, false);
        assertEquals(0x63, emerald.captureRewindSnapshot().emeraldPhaseTimer());

        for (int pass = 0; pass < 0x63; pass++) {
            emerald.update(0, false, 12, false);
        }

        assertEquals(0, emerald.captureRewindSnapshot().emeraldPhaseTimer());
        assertEquals(Sonic2SpecialStageEmerald.EmeraldPhase.COLLECTED, emerald.getPhase());
        assertFalse(manager.isFinished(), "ROM bpl keeps routine 8 active at timer zero");
        assertFalse(emerald.shouldRemove());

        emerald.update(0, false, 12, false);

        assertEquals(-1, emerald.captureRewindSnapshot().emeraldPhaseTimer());
        assertTrue(manager.isFinished());
        assertTrue(emerald.shouldRemove());
    }

    @Test
    void emeraldFailureSeedsFourFAndCompletesOnlyAfterZero() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
        emerald.initialize(54, 0x40);
        emerald.setManager(manager);
        emerald.setRingRequirement(1);
        set(emerald, "phase", Sonic2SpecialStageEmerald.EmeraldPhase.APPROACHING);
        set(emerald, "animIndex", 6);

        emerald.update(0, false, 12, false);

        assertEquals(Sonic2SpecialStageEmerald.EmeraldPhase.FAILED, emerald.getPhase());
        assertEquals(0x4F, emerald.captureRewindSnapshot().emeraldPhaseTimer());

        for (int pass = 0; pass < 0x4F; pass++) {
            emerald.update(0, false, 12, false);
        }

        assertEquals(0, emerald.captureRewindSnapshot().emeraldPhaseTimer());
        assertFalse(manager.isFinished(), "ROM bpl keeps routine 6 active at timer zero");
        assertFalse(emerald.shouldRemove());

        emerald.update(0, false, 12, false);

        assertEquals(-1, emerald.captureRewindSnapshot().emeraldPhaseTimer());
        assertTrue(manager.isFinished());
        assertTrue(emerald.shouldRemove());
    }

    @Test
    void firstPlayerToCollectSharedRingConsumesCollisionForBothPlayers() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        Sonic2SpecialStageRing ring = new Sonic2SpecialStageRing();
        ring.initialize(4, 0x40);
        set(ring, "animIndex", 8);
        objectManager.getActiveObjects().add(ring);

        Sonic2SpecialStagePlayer sonic = initializedPlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        Sonic2SpecialStagePlayer tails = initializedPlayer(
                Sonic2SpecialStagePlayer.PlayerType.TAILS, false);
        sonic.setSwapPositionsOwner(manager);
        tails.setSwapPositionsOwner(manager);

        set(manager, "objectManager", objectManager);
        set(manager, "players", new ArrayList<>(List.of(sonic, tails)));

        invoke(manager, "checkObjectCollisions");

        assertEquals(1, objectManager.getRingsCollected(),
                "Obj61_TestCollision clears collision on the first successful player");
        assertEquals(Sonic2SpecialStageObject.State.COLLECTED, ring.getState());
    }

    @Test
    void bombTestsTailsFirstWhenSonicsUnsignedDepthIsNotLess() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        Sonic2SpecialStageBomb bomb = new Sonic2SpecialStageBomb();
        bomb.initialize(4, 0x40);
        set(bomb, "animIndex", 8);
        objectManager.getActiveObjects().add(bomb);

        Sonic2SpecialStagePlayer sonic = initializedPlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        Sonic2SpecialStagePlayer tails = initializedPlayer(
                Sonic2SpecialStagePlayer.PlayerType.TAILS, false);
        set(sonic, "ssZPos", 0x80);
        set(tails, "ssZPos", 0x80);
        sonic.setSwapPositionsOwner(manager);
        tails.setSwapPositionsOwner(manager);
        set(sonic, "collisionProperty", 0);
        set(tails, "collisionProperty", 0);

        set(manager, "objectManager", objectManager);
        set(manager, "players", new ArrayList<>(List.of(sonic, tails)));

        invoke(manager, "checkObjectCollisions");
        sonic.update(0, 0);
        tails.update(0, 0);

        assertFalse(sonic.isHurt(), "ROM must not test Sonic first when Sonic Z >= Tails Z");
        assertTrue(tails.isHurt(), "the first successful Tails collision must consume the bomb");
    }

    @Test
    void ringCollisionExcludesThePositiveAngleThresholdBoundary() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        Sonic2SpecialStageRing ring = new Sonic2SpecialStageRing();
        ring.initialize(4, 0x38);
        set(ring, "animIndex", 8);
        objectManager.getActiveObjects().add(ring);

        Sonic2SpecialStagePlayer sonic = initializedPlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        set(sonic, "angle", 0x42);

        set(manager, "objectManager", objectManager);
        set(manager, "players", new ArrayList<>(List.of(sonic)));

        invoke(manager, "checkObjectCollisions");

        assertEquals(0, objectManager.getRingsCollected(),
                "Obj61_TestCollision rejects playerAngle == objectAngle + d6");
        assertEquals(Sonic2SpecialStageObject.State.ACTIVE, ring.getState());
    }

    @Test
    void ringCollisionIncludesTheNegativeThresholdBoundaryAcrossWrap() throws Exception {
        assertCollisionState(new Sonic2SpecialStageRing(), 0x04, 0xFA,
                Sonic2SpecialStageObject.State.COLLECTED);
    }

    @Test
    void ringCollisionExcludesThePositiveThresholdBoundaryAcrossWrap() throws Exception {
        assertCollisionState(new Sonic2SpecialStageRing(), 0xFC, 0x06,
                Sonic2SpecialStageObject.State.ACTIVE);
    }

    @Test
    void bombCollisionExcludesThePositiveEightUnitBoundary() throws Exception {
        assertCollisionState(new Sonic2SpecialStageBomb(), 0x38, 0x40,
                Sonic2SpecialStageObject.State.ACTIVE);
    }

    @Test
    void bombCollisionIncludesTheNegativeEightUnitBoundaryAcrossWrap() throws Exception {
        assertCollisionState(new Sonic2SpecialStageBomb(), 0x04, 0xFC,
                Sonic2SpecialStageObject.State.EXPLODING);
    }

    @Test
    void bombCollisionExcludesTheNegativeNineUnitOutsideBoundary() throws Exception {
        assertCollisionState(new Sonic2SpecialStageBomb(), 0x40, 0x37,
                Sonic2SpecialStageObject.State.ACTIVE);
    }

    private static void assertCollisionState(
            Sonic2SpecialStageObject object,
            int objectAngle,
            int playerAngle,
            Sonic2SpecialStageObject.State expectedState) throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        object.initialize(4, objectAngle);
        set(object, "animIndex", 8);
        objectManager.getActiveObjects().add(object);

        Sonic2SpecialStagePlayer sonic = initializedPlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        set(sonic, "angle", playerAngle);

        set(manager, "objectManager", objectManager);
        set(manager, "players", new ArrayList<>(List.of(sonic)));

        invoke(manager, "checkObjectCollisions");

        assertEquals(expectedState, object.getState());
    }

    private static Sonic2SpecialStagePlayer initializedPlayer(
            Sonic2SpecialStagePlayer.PlayerType type,
            boolean main) {
        Sonic2SpecialStagePlayer player = new Sonic2SpecialStagePlayer(
                type, main, new Sonic2SpecialStageManager());
        player.initializeScalarStateFromRomObjectRoutine();
        return player;
    }

    private static Sonic2PerspectiveData alwaysVisiblePerspective() {
        return new Sonic2PerspectiveData() {
            @Override
            public PerspectiveEntry getEntry(int trackFrame, int depth) {
                return new PerspectiveEntry(0x7F, 0x58, 8, 8, 0, 0);
            }
        };
    }

    private static long depthFixed(Sonic2SpecialStageObject object) {
        return object.captureBaseRewindSnapshot().depthFixed();
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
