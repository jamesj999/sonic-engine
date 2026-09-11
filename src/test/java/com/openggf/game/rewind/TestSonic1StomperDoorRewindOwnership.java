package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.Sonic1StomperDoorSingletonRewindAdapter;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.objects.Sonic1StomperDoorObjectInstance;
import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.level.LevelManager;
import com.openggf.level.LevelTilemapManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.lang.reflect.Field;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Exercises the real ObjectManager clear/recreate path that exposed stale S1
 * SBZ3 singleton ownership during rewind.
 */
class TestSonic1StomperDoorRewindOwnership {
    private static final ObjectSpawn SBZ3_DOOR = new ObjectSpawn(
            0x1000, 0x180, Sonic1ObjectIds.SBZ_STOMPER_DOOR, 0, 0, false, 0, 7);

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        GraphicsManager.getInstance().initHeadless();
        Sonic1StomperDoorObjectInstance.resetSbz3Flag();
    }

    @AfterEach
    void tearDown() {
        Sonic1StomperDoorObjectInstance.resetSbz3Flag();
        GraphicsManager.getInstance().resetState();
        TestEnvironment.resetAll();
    }

    @Test
    void restoringAbsentSnapshotThenReloadingDoorDoesNotLeaveRecreatedDoorDestroyed() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        RewindRegistry rewindRegistry = new RewindRegistry();
        GameServices.module().rewindAdapters().forEach(rewindRegistry::register);
        rewindRegistry.register(objectManager.rewindSnapshottable());
        rewindRegistry.registerPostRestoreCallback(
                "s1-test-door-rebind",
                () -> Sonic1StomperDoorSingletonRewindAdapter.rebindAfterObjectRestore(objectManager));

        CompositeSnapshot absent = rewindRegistry.capture();
        objectManager.reset(0x1000);
        assertEquals(1, liveDoors(objectManager).size(), "precondition: door must load through ObjectManager");
        assertFalse(liveDoors(objectManager).getFirst().isDestroyed(), "precondition: first door must own slot");

        rewindRegistry.restore(absent);
        assertEquals(0, liveDoors(objectManager).size(), "absent snapshot must drop the active door");

        objectManager.reset(0x1000);
        assertEquals(1, liveDoors(objectManager).size(),
                "the door must be recreated after rewinding before its spawn point");
        assertFalse(liveDoors(objectManager).getFirst().isDestroyed(),
                "a recreated SBZ3 door must not inherit stale singleton ownership");
    }

    @Test
    void registeredOwnerRestoresContainingDoorForRecreateAndReusePaths() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        RewindRegistry rewindRegistry = productionRegistry(objectManager);

        objectManager.reset(0x1000);
        Sonic1StomperDoorObjectInstance source = liveDoors(objectManager).getFirst();
        CompositeSnapshot containing = rewindRegistry.capture();

        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        rewindRegistry.restore(containing);
        Sonic1StomperDoorObjectInstance recreated = liveDoors(objectManager).getFirst();
        assertFalse(recreated.isDestroyed(), "recreated containing snapshot door must own v_obj6B");
        assertTrue(Sonic1StomperDoorObjectInstance.hasSbz3Singleton(),
                "post-restore reconciliation must rebind v_obj6B to the restored door");

        objectManager.setRewindInPlaceRestoreEnabledForTest(true);
        CompositeSnapshot reusable = rewindRegistry.capture();
        rewindRegistry.restore(reusable);
        Sonic1StomperDoorObjectInstance reused = liveDoors(objectManager).getFirst();
        assertSame(recreated, reused, "matching rewind entries should use ObjectManager's in-place path");
        assertFalse(reused.isDestroyed(), "in-place restore must retain singleton ownership");
        assertTrue(Sonic1StomperDoorObjectInstance.hasSbz3Singleton(),
                "in-place restore must still rebind v_obj6B");
        assertTrue(source != reused, "the forced reconstruction must have replaced the source object");
    }

    @Test
    void productionLevelRegistrationRunsSbz3ReconcileAfterObjectManagerRestore() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        GameplayModeContext context = TestEnvironment.activeGameplayMode();
        LevelManager levelManager = mock(LevelManager.class);
        LevelTilemapManager tilemapManager = mock(LevelTilemapManager.class);
        @SuppressWarnings("unchecked")
        RewindSnapshottable<com.openggf.game.rewind.snapshot.LevelSnapshot> levelSnapshot =
                mock(RewindSnapshottable.class);
        @SuppressWarnings("unchecked")
        RewindSnapshottable<com.openggf.game.rewind.snapshot.LevelTilemapSnapshot> tilemapSnapshot =
                mock(RewindSnapshottable.class);
        when(levelSnapshot.key()).thenReturn("level");
        when(levelSnapshot.capture()).thenReturn(mock(com.openggf.game.rewind.snapshot.LevelSnapshot.class));
        when(tilemapSnapshot.key()).thenReturn("level-tilemap");
        when(tilemapSnapshot.capture()).thenReturn(mock(com.openggf.game.rewind.snapshot.LevelTilemapSnapshot.class));
        when(levelManager.getTilemapManager()).thenReturn(tilemapManager);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        when(levelManager.getGameModule()).thenReturn(GameServices.module());
        when(levelManager.levelRewindSnapshottable()).thenReturn(levelSnapshot);
        when(levelManager.levelTilemapRewindSnapshottable()).thenReturn(tilemapSnapshot);
        replaceContextLevelManager(context, levelManager);

        context.registerLevelAdapters(levelManager);
        assertTrue(registeredPostRestoreCallbacks(context.getRewindRegistry())
                        .contains("level-tilemap-event-reconcile"),
                "production level registration must install the post-restore reconciliation callback");

        objectManager.reset(0x1000);
        // Prime ObjectManager's class safety audit through its production
        // reconstruction path, then require the next restore to reuse in place.
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CompositeSnapshot containing = context.getRewindRegistry().capture();
        context.getRewindRegistry().restore(containing);
        objectManager.setRewindInPlaceRestoreEnabledForTest(true);
        Sonic1StomperDoorObjectInstance source = liveDoors(objectManager).getFirst();
        CompositeSnapshot reusable = context.getRewindRegistry().capture();
        context.getRewindRegistry().restore(reusable);

        assertEquals(1, liveDoors(objectManager).size(),
                "production registry restore must retain the containing door");
        assertSame(source, liveDoors(objectManager).getFirst(),
                "production registry restore must exercise ObjectManager's in-place reuse path");
        assertFalse(liveDoors(objectManager).getFirst().isDestroyed(),
                "production callback must leave the reused door live");
        assertTrue(Sonic1StomperDoorObjectInstance.hasSbz3Singleton(),
                "production callback must restore v_obj6B after ObjectManager phase two");

        Sonic1StomperDoorObjectInstance duplicate = new Sonic1StomperDoorObjectInstance(
                new ObjectSpawn(0x1100, 0x180, Sonic1ObjectIds.SBZ_STOMPER_DOOR, 0, 0, false, 0, 8),
                Sonic1Constants.ZONE_LZ);
        assertTrue(duplicate.isDestroyed(),
                "the production-rebound owner must still suppress an ordinary duplicate");
    }

    @Test
    void repeatedAbsentRestoresAndNormalOffscreenUnloadClearAndReclaimOwner() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        RewindRegistry rewindRegistry = productionRegistry(objectManager);
        CompositeSnapshot absent = rewindRegistry.capture();

        for (int iteration = 0; iteration < 3; iteration++) {
            objectManager.reset(0x1000);
            assertEquals(1, liveDoors(objectManager).size(), "door must reclaim owner on iteration " + iteration);
            assertTrue(Sonic1StomperDoorObjectInstance.hasSbz3Singleton());
            rewindRegistry.restore(absent);
            assertEquals(0, liveDoors(objectManager).size(), "absent restore must drop door on iteration " + iteration);
            assertFalse(Sonic1StomperDoorObjectInstance.hasSbz3Singleton(),
                    "absent restore must clear v_obj6B on iteration " + iteration);
        }

        objectManager.reset(0x1000);
        AbstractObjectInstance.updateCameraBounds(0x3000, 0, 0x3200, 0x200, 0);
        assertFalse(liveDoors(objectManager).getFirst().isPersistent(),
                "normal offscreen unload must still report the door as non-persistent");
        assertFalse(Sonic1StomperDoorObjectInstance.hasSbz3Singleton(),
                "normal offscreen unload must clear v_obj6B");
    }

    @Test
    void ordinaryDuplicateStillKeepsFirstLoadedSbz3Slot() {
        Sonic1StomperDoorObjectInstance.resetSbz3Flag();
        Sonic1StomperDoorObjectInstance first = new Sonic1StomperDoorObjectInstance(
                SBZ3_DOOR, Sonic1Constants.ZONE_LZ);
        Sonic1StomperDoorObjectInstance duplicate = new Sonic1StomperDoorObjectInstance(
                new ObjectSpawn(0x1100, 0x180, Sonic1ObjectIds.SBZ_STOMPER_DOOR, 0, 0, false, 0, 8),
                Sonic1Constants.ZONE_LZ);

        assertFalse(first.isDestroyed(), "first loaded SBZ3 slot must claim v_obj6B");
        assertTrue(duplicate.isDestroyed(), "ordinary duplicate must still be rejected");
    }

    private static RewindRegistry productionRegistry(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        GameServices.module().rewindAdapters().forEach(rewindRegistry::register);
        rewindRegistry.register(objectManager.rewindSnapshottable());
        rewindRegistry.registerPostRestoreCallback(
                "s1-test-door-rebind",
                () -> Sonic1StomperDoorSingletonRewindAdapter.rebindAfterObjectRestore(objectManager));
        return rewindRegistry;
    }

    private static void replaceContextLevelManager(
            GameplayModeContext context, LevelManager levelManager) throws Exception {
        Field field = GameplayModeContext.class.getDeclaredField("levelManager");
        field.setAccessible(true);
        field.set(context, levelManager);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> registeredPostRestoreCallbacks(
            RewindRegistry rewindRegistry) throws Exception {
        Field field = RewindRegistry.class.getDeclaredField("postRestoreCallbacks");
        field.setAccessible(true);
        return ((java.util.Map<String, Runnable>) field.get(rewindRegistry)).keySet();
    }

    private static List<Sonic1StomperDoorObjectInstance> liveDoors(ObjectManager objectManager) {
        return objectManager.activeObjectsOfType(Sonic1StomperDoorObjectInstance.class).stream()
                .filter(door -> !door.isDestroyed())
                .toList();
    }

    private record Harness(ObjectManager objectManager, ObjectServices services,
                           TestablePlayableSprite player) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
            TestCamera camera = new TestCamera(player);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(SBZ3_DOOR),
                    new FixedSbz3ObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager, services, player);
        }
    }

    private static final class FixedSbz3ObjectRegistry extends Sonic1ObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic1Constants.ZONE_LZ;
        }
    }

    private static final class TestCamera extends Camera {
        private final TestablePlayableSprite focusedSprite;
        private short x;

        private TestCamera(TestablePlayableSprite focusedSprite) {
            this.focusedSprite = focusedSprite;
        }

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) {}
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return x; }
        @Override public short getY() { return 0; }
        @Override public short getWidth() { return 320; }
        @Override public short getHeight() { return 224; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }
}
