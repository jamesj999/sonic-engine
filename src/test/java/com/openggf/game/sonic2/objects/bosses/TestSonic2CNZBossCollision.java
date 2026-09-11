package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.boss.BossStateContext;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSonic2CNZBossCollision {

    @Test
    void obj51BodyUsesEnemyCategoryBossHitByte() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x299C, 0x0657);

        assertEquals(0x0F, boss.getCollisionFlags(),
                "Obj51_Init writes collision_flags=$0F; category is enemy/boss-hit, not generic BOSS");
    }

    @Test
    void obj51LowerElectricityReportsHurtRegionBeforeBody() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x299C, 0x0657);
        setField(boss, "bossCollisionRoutine", 1);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();

        assertNotNull(regions, "BossCollision_CNZ should expose the electric hurt check as a touch region");
        assertEquals(2, regions.length);
        assertEquals(0x299C, regions[0].x());
        assertEquals(0x067F, regions[0].y(), "BossCollision_CNZ lower mode checks y_pos+$28");
        assertEquals(0x8A, regions[0].collisionFlags(), "electric collision jumps to Touch_ChkHurt");
        assertEquals(0x299C, regions[1].x());
        assertEquals(0x0657, regions[1].y());
        assertEquals(0x0F, regions[1].collisionFlags(), "body region keeps Obj51 collision_flags=$0F");
    }

    @Test
    void splitBallCloneUsesAllocateObjectAfterCurrentSemantics() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x299C, 0x0657);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x299C, 0x0684, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        ObjectManager objectManager = mock(ObjectManager.class);
        ball.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        Method explodeAndSplit = CNZBossElectricBall.class.getDeclaredMethod("explodeAndSplit");
        explodeAndSplit.setAccessible(true);
        explodeAndSplit.invoke(ball);

        ArgumentCaptor<CNZBossElectricBall> cloneCaptor = ArgumentCaptor.forClass(CNZBossElectricBall.class);
        verify(objectManager).addDynamicObjectAfterCurrent(cloneCaptor.capture());
        verify(objectManager, never()).addDynamicObject(any(CNZBossElectricBall.class));
    }

    @Test
    void attachBallPreservesAllocationTimeParentXDuringPostTriggerCountdown() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x2909, 0x0657);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x2909, 0x0657, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        boss.getState().x = 0x290B;

        Method updateBallAttach = CNZBossElectricBall.class.getDeclaredMethod("updateBallAttach", int.class);
        updateBallAttach.setAccessible(true);
        updateBallAttach.invoke(ball, 100);

        assertEquals(0x2909, ball.getX(),
                "loc_31BA8 leaves Boss_X_pos stationary, so every loc_31F96 copy retains the allocated x_pos");
    }

    @Test
    void rightwardTriggerPublishesFreshBossXForBodyTouch() throws Exception {
        Sonic2CNZBossInstance boss = initializedCnzBoss();
        BossStateContext state = boss.getState();
        state.x = 0x28D9;
        state.xFixed = 0x28D98000;
        state.xVel = 0x180;
        setField(boss, "bossXPos", 0x28D9);
        setField(boss, "dirToggle", 2);

        invokePatrolBallTrigger(boss, 0x28D9);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();
        assertNotNull(regions);
        assertEquals(0x28DB, regions[0].x(),
                "post-trigger body touch keeps the trigger-boundary x_pos snapshot");
    }

    @Test
    void leftwardTriggerKeepsRomCopiedXInsteadOfAdvancedEngineAccumulator() throws Exception {
        Sonic2CNZBossInstance boss = initializedCnzBoss();
        BossStateContext state = boss.getState();
        state.x = 0x2917;
        state.xFixed = 0x29170000;
        state.xVel = -0x180;
        setField(boss, "bossXPos", 0x2917);
        setField(boss, "dirToggle", 0);

        invokePatrolBallTrigger(boss, 0x2918);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();
        assertNotNull(regions);
        assertEquals(0x2917, regions[0].x(),
                "leftward body touch keeps the ROM-copied trigger-boundary x_pos");
    }

    @Test
    void attachedBallSeesParentCountdownAfterRomParentSlot() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x2909, 0x0657);
        boss.getState().routine = 2;
        setField(boss, "bossCountdown", 1);
        setField(boss, "lastVIntRunCount", 100);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x2909, 0x0657, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);

        invokeBallAttach(ball, 101);

        assertEquals(1, getField(ball, "routineState"),
                "a child reached before its parent must observe the ROM parent-slot decrement to zero");
    }

    @Test
    void attachedBallFallTransitionRequiresExactZeroCountdown() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x2909, 0x0657);
        boss.getState().routine = 2;
        setField(boss, "bossCountdown", -1);
        setField(boss, "lastVIntRunCount", 101);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x2909, 0x0657, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);

        invokeBallAttach(ball, 101);

        assertEquals(0, getField(ball, "routineState"),
                "ROM tst.w/bne keeps every nonzero countdown, including negative values, attached");
    }

    @Test
    void fallingBallUsesOrdinaryFrameStartSingleTouchRegion() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x2909, 0x0657);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x2909, 0x0657, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        setField(ball, "routineState", 1);
        setField(ball, "x", 0x28F3);
        setField(ball, "y", 0x06DF);
        setField(ball, "xVel", 0x100);
        setField(ball, "yVel", 0x200);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x28F4, 0x06E1, 8))
                    .thenReturn(new TerrainCheckResult(1, (byte) 0, 0));
            Method updateBallFall = CNZBossElectricBall.class.getDeclaredMethod("updateBallFall");
            updateBallFall.setAccessible(true);
            updateBallFall.invoke(ball);
        }

        assertEquals(0x28F4, ball.getX());
        assertEquals(0x06E1, ball.getY());
        assertNull(ball.getMultiTouchRegions(),
                "BALL_FALL remains on the ordinary frame-start single-region touch path");
    }

    @Test
    void splitHalvesUseOrdinaryFrameStartSingleTouchRegions() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x2909, 0x0657);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x2909, 0x0657, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        setField(ball, "routineState", 2);
        setField(ball, "x", 0x28EE);
        setField(ball, "y", 0x06E6);
        setField(ball, "xVel", -0x100);
        setField(ball, "yVel", 0x02E8);

        assertNull(ball.getMultiTouchRegions(),
                "the original half is checked at its ordinary frame-start SST coordinate");

        setField(ball, "xVel", 0x100);

        assertNull(ball.getMultiTouchRegions(),
                "the AllocateObjectAfterCurrent clone uses the same ordinary frame-start path");
    }

    @Test
    void positiveSplitHalfRetainsCoordinateBeforeImmediateAllocatedSlotStep() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x2909, 0x0657);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x2909, 0x0657, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        setField(ball, "routineState", 2);
        setField(ball, "x", 0x28F3);
        setField(ball, "y", 0x06DF);
        setField(ball, "xVel", 0x100);
        setField(ball, "yVel", 0x200);

        Method updateBallSplit = CNZBossElectricBall.class.getDeclaredMethod("updateBallSplit");
        updateBallSplit.setAccessible(true);
        updateBallSplit.invoke(ball);

        assertEquals(0x28F4, ball.getX());
        assertEquals(0x06E1, ball.getY());
        assertEquals(0x28F3, ball.getPreUpdateX());
        assertEquals(0x06DF, ball.getPreUpdateY());
        assertNull(ball.getMultiTouchRegions(),
                "the positive half retains ordinary single-region touch actor/stop policies");
    }

    @Test
    void ballPhysicsRebuildsSubpixelFromIntegerPositionEachFrame() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x299C, 0x0657);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x299C, 0x0684, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        setField(ball, "x", 0x28DD);
        setField(ball, "y", 0x06F0);
        setField(ball, "yFixed", 0x06F0F000);
        setField(ball, "yVel", -0x290);

        Method applyBallPhysics = CNZBossElectricBall.class.getDeclaredMethod("applyBallPhysics");
        applyBallPhysics.setAccessible(true);
        applyBallPhysics.invoke(ball);

        assertEquals(0x06ED, getField(ball, "y"),
                "Obj51 loc_31FF8 rebuilds d3 from y_pos, so stale low-word subpixel is discarded");
        assertEquals(0x06ED7000, getField(ball, "yFixed"));
    }

    @Test
    void ballFloorSplitRejectsZeroAndAcceptsNegativeDistance() throws Exception {
        Sonic2CNZBossInstance boss = newCnzBossAt(0x28DB, 0x0656);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x28DB, 0x06F6, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        setField(ball, "routineState", 1);
        setField(ball, "x", 0x28DB);
        setField(ball, "y", 0x06F6);
        setField(ball, "xVel", 0);
        setField(ball, "yVel", 0);
        ObjectManager objectManager = mock(ObjectManager.class);
        ball.setServices(new StubObjectServices() {
            @Override public ObjectManager objectManager() { return objectManager; }
        });
        Method updateBallFall = CNZBossElectricBall.class.getDeclaredMethod("updateBallFall");
        updateBallFall.setAccessible(true);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x28DB, 0x06F6, 8))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0),
                            new TerrainCheckResult(-1, (byte) 0, 0));

            updateBallFall.invoke(ball);
            assertEquals(1, getField(ball, "routineState"),
                    "tst.w d1 / bpl keeps exact floor contact in the falling routine");
            verify(objectManager, never()).addDynamicObjectAfterCurrent(any(CNZBossElectricBall.class));

            updateBallFall.invoke(ball);
            assertEquals(2, getField(ball, "routineState"),
                    "negative ObjCheckFloorDist penetration reaches loc_32030");
            assertEquals(0x06F5, ball.getY());
            verify(objectManager, times(1)).addDynamicObjectAfterCurrent(any(CNZBossElectricBall.class));
        }
    }

    @Test
    void positiveSplitHalfTouchConsumesFrameStartSnapshot() throws Exception {
        TouchResponseTable table = mock(TouchResponseTable.class);
        when(table.getWidthRadius(0x18)).thenReturn(4);
        when(table.getHeightRadius(0x18)).thenReturn(4);
        ObjectManager objectManager = newS2ObjectManager(table);

        Sonic2CNZBossInstance boss = newCnzBossAt(0x28DB, 0x0656);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x28DB, 0x06F6, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        setField(ball, "routineState", 2);
        setField(ball, "x", 0x28F3);
        setField(ball, "y", 0x06DF);
        setField(ball, "xVel", 0x100);
        setField(ball, "yVel", 0x200);
        objectManager.addDynamicObject(ball);

        AbstractPlayableSprite tails = mock(AbstractPlayableSprite.class);
        when(tails.isCpuControlled()).thenReturn(true);
        when(tails.getCentreX()).thenReturn((short) 0x28F1);
        when(tails.getCentreY()).thenReturn((short) 0x06F0);
        when(tails.getYRadius()).thenReturn((short) 15);
        when(tails.getCrouching()).thenReturn(false);
        when(tails.getDead()).thenReturn(false);
        when(tails.getInvulnerable()).thenReturn(false);
        when(tails.getInvulnerableFrames()).thenReturn(0);

        // Player-slot Touch_Boss runs from the frame-start snapshot even if a
        // later Obj51 phase has already advanced the live object in this test.
        objectManager.snapshotTouchResponseState(false);
        ball.update(0, tails);
        assertEquals(0x06E1, ball.getY());
        objectManager.runTouchResponsesForPlayer(tails, 9199, true);
        verify(tails, never()).applyHurt(org.mockito.ArgumentMatchers.anyInt());

        ball.update(1, tails);
        objectManager.snapshotTouchResponseState(false);
        objectManager.runTouchResponsesForPlayer(tails, 9200, true);
        verify(tails, times(1)).applyHurt(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void allocateAfterCurrentSplitCloneExecutesExactlyOnceOnBirth() throws Exception {
        ObjectManager objectManager = newS2ObjectManager(null);
        Sonic2CNZBossInstance boss = newCnzBossAt(0x28DB, 0x0656);
        CNZBossElectricBall clone = new CNZBossElectricBall(
                new ObjectSpawn(0x28DB, 0x06F6, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        setField(clone, "routineState", 2);
        setField(clone, "x", 0x28DB);
        setField(clone, "y", 0x06F6);
        setField(clone, "xVel", 0x100);
        setField(clone, "yVel", -0x300);
        setBooleanField(clone, "exploding", true);
        SplitCloneSpawner spawner = new SplitCloneSpawner(objectManager, clone);
        objectManager.addDynamicObject(spawner);

        objectManager.update(0x2880, null, List.of(), 9175, false, true, true);

        assertEquals(0x28DC, clone.getX(),
                "AllocateObjectAfterCurrent clone must run once in its birth pass");
        assertEquals(0x06F3, clone.getY(),
                "the one birth-pass loc_32080 step applies -$300 exactly once");

        objectManager.update(0x2880, null, List.of(), 9176, false, true, true);
        assertEquals(0x28DD, clone.getX());
        assertEquals(0x06F0, clone.getY());
    }

    private static ObjectManager newS2ObjectManager(TouchResponseTable table) {
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0x2880);
        when(camera.getY()).thenReturn((short) 0x04E0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        DebugOverlayManager debugOverlay = mock(DebugOverlayManager.class);
        ObjectRegistry registry = new ObjectRegistry() {
            @Override public com.openggf.level.objects.ObjectInstance create(ObjectSpawn spawn) { return null; }
            @Override public void reportCoverage(List<ObjectSpawn> spawns) { }
            @Override public String getPrimaryName(int objectId) { return "Obj51"; }
            @Override public ObjectSlotLayout objectSlotLayout() { return ObjectSlotLayout.SONIC_2; }
        };
        TestObjectServices services = new TestObjectServices()
                .withCamera(camera)
                .withDebugOverlay(debugOverlay);
        return new ObjectManager(List.of(), registry, 0, null, table,
                null, camera, services);
    }

    private static final class SplitCloneSpawner extends AbstractObjectInstance {
        private final ObjectManager objectManager;
        private final CNZBossElectricBall clone;
        private boolean spawned;

        private SplitCloneSpawner(ObjectManager objectManager, CNZBossElectricBall clone) {
            super(new ObjectSpawn(0x28DB, 0x06F6, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0),
                    "Obj51 split spawner");
            this.objectManager = objectManager;
            this.clone = clone;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (!spawned) {
                spawned = true;
                objectManager.addDynamicObjectAfterCurrent(clone);
            }
        }

        @Override public int getX() { return spawn.x(); }
        @Override public int getY() { return spawn.y(); }
        @Override public void appendRenderCommands(List<GLCommand> commands) { }
    }

    private static Sonic2CNZBossInstance newCnzBossAt(int x, int y) throws Exception {
        Sonic2CNZBossInstance boss = new Sonic2CNZBossInstance(
                new ObjectSpawn(x, y, Sonic2ObjectIds.CNZ_BOSS, 0, 0, false, 0));
        BossStateContext state = boss.getState();
        state.x = x;
        state.y = y;
        state.xFixed = x << 16;
        state.yFixed = y << 16;
        setField(boss, "touchCollisionX", x);
        setField(boss, "touchCollisionY", y);
        return boss;
    }

    private static Sonic2CNZBossInstance initializedCnzBoss() throws Exception {
        Sonic2CNZBossInstance boss = new Sonic2CNZBossInstance(
                new ObjectSpawn(0x2A46, 0x0654, Sonic2ObjectIds.CNZ_BOSS, 0, 0, false, 0));
        boss.setServices(new StubObjectServices());
        return boss;
    }

    private static void invokePatrolBallTrigger(Sonic2CNZBossInstance boss, int playerX) throws Exception {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) playerX);
        when(player.getCentreY()).thenReturn((short) 0x06B0);
        Method updatePatrol = Sonic2CNZBossInstance.class.getDeclaredMethod(
                "updatePatrol", AbstractPlayableSprite.class);
        updatePatrol.setAccessible(true);
        updatePatrol.invoke(boss, player);
    }

    private static void invokeBallAttach(CNZBossElectricBall ball, int frameCounter) throws Exception {
        Method updateBallAttach = CNZBossElectricBall.class.getDeclaredMethod("updateBallAttach", int.class);
        updateBallAttach.setAccessible(true);
        updateBallAttach.invoke(ball, frameCounter);
    }

    private static void setField(Sonic2CNZBossInstance boss, String name, int value) throws Exception {
        Field field = Sonic2CNZBossInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(boss, value);
    }

    private static void setField(CNZBossElectricBall ball, String name, int value) throws Exception {
        Field field = CNZBossElectricBall.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(ball, value);
    }

    private static int getField(CNZBossElectricBall ball, String name) throws Exception {
        Field field = CNZBossElectricBall.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(ball);
    }

    private static void setBooleanField(CNZBossElectricBall ball, String name, boolean value) throws Exception {
        Field field = CNZBossElectricBall.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(ball, value);
    }
}
