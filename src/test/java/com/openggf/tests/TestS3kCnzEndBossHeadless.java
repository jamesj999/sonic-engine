package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.GameRng;
import com.openggf.game.session.SessionManager;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossExplosionControllerChild;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikFlameChild;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikHeadChild;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikShipChild;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzEndBossHeadless {
    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void cameraGateStartsNativeBossAndLoadsRomPalette() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        assertTrue(GameServices.level().consumePendingInitialProcessSpritesPass(),
                "consume the native load-time Process_Sprites pass before injecting a later boss object");
        fixture.camera().setX((short) 0x4660);
        fixture.camera().setY((short) 0x0240);
        fixture.camera().setMinX((short) 0x4660);

        CnzEndBossInstance boss = createBoss();
        GameServices.level().getObjectManager().addDynamicObject(boss);
        boss.update(0, fixture.sprite());
        assertEquals("CAMERA_LOCK", boss.getRoutineForTest());
        assertFalse(boss.isStartupCompleteForTest());
        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                TestEnvironment.objectServices().renderManager().getArtProvider();
        assertTrue(artProvider.isCnzEndBossArtPending(),
                "Obj_CNZEndBoss must issue Load_PLC($6E) only after the camera gate");
        assertFalse(boss.isNativeBodyRenderableForTest(),
                "loc_6E4B8 camera continuation runs before routine-0 sprite setup");
        S3kPaletteWriteSupport.resolvePendingWritesNow(
                GameServices.paletteOwnershipRegistry(),
                GameServices.level().getCurrentLevel(),
                GameServices.graphics());
        for (int color = 0; color < 16; color++) {
            assertEquals(S3kPaletteOwners.CNZ_END_BOSS,
                    GameServices.paletteOwnershipRegistry().ownerAt(PaletteSurface.NORMAL, 1, color),
                    "Pal_CNZEndBoss resolves through the frame palette pipeline before the camera wait completes");
        }
        // loc_85D06 follows live Camera_X_pos; move the live camera through
        // the $4760 target instead of relying on the retired boundary easing.
        fixture.camera().setX((short) 0x4760);
        boss.update(1, fixture.sprite());
        fixture.camera().setX((short) 0x4660);
        fixture.stepIdleFrames(130);

        assertTrue(boss.isStartupCompleteForTest());
        assertEquals("ENTRY", boss.getRoutineForTest());
        assertEquals(Sonic3kObjectIds.CNZ_END_BOSS, GameServices.gameState().getCurrentBossId());
        assertEquals(5, boss.getPriorityBucket());
        assertTrue(artProvider.isCnzEndBossArtComplete());
        assertEquals(1, activeNamed("CnzEndBossRobotnikShipChild"));
        assertEquals(1, activeNamed("CnzEndBossRobotnikHeadChild"));
        assertEquals(1, activeNamed("CnzEndBossMagnetChild"));
        assertEquals(4, activeNamed("CnzEndBossArmChild"),
                "routine 0 must create ship, magnet and four arms in native slot order");
    }

    @Test
    void rewindRestorePreservesRealShipHeadAndNativeBossGraph() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        fixture.camera().setX((short) 0x4660);
        fixture.camera().setY((short) 0x0240);
        fixture.camera().setMinX((short) 0x4660);
        CnzEndBossInstance boss = createBoss();
        GameServices.level().getObjectManager().addDynamicObject(boss);
        fixture.stepIdleFrames(1);
        fixture.camera().setX((short) 0x4760);
        boss.update(1, fixture.sprite());
        fixture.camera().setX((short) 0x4660);
        fixture.stepIdleFrames(130);
        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertEquals(4, activeNamed("CnzEndBossArmChild"),
                "precondition: routine 0 created all four arm slots");

        var snapshot = objectManager.rewindSnapshottable().capture();
        assertEquals(4, snapshot.dynamicObjects().stream()
                        .filter(entry -> entry.className().endsWith("CnzEndBossArmChild"))
                        .count(),
                () -> "captured graph=" + snapshot.dynamicObjects().stream()
                        .map(entry -> entry.className()).toList());
        objectManager.rewindSnapshottable().restore(snapshot);

        assertEquals(1, activeNamed("CnzEndBossRobotnikShipChild"));
        assertEquals(1, activeNamed("CnzEndBossRobotnikHeadChild"));
        assertEquals(1, activeNamed("CnzEndBossMagnetChild"));
        assertEquals(4, activeNamed("CnzEndBossArmChild"),
                () -> "rewind graph=" + objectManager.getActiveObjects().stream()
                        .filter(object -> !object.isDestroyed())
                        .map(object -> object.getClass().getSimpleName())
                        .toList());
    }

    @Test
    void subtypeFourExplosionControllerSpawnsImmediatelyFromSwappedRandomWord() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        long seed = 0x12345678L;
        GameServices.rng().setSeed(seed);
        GameRng expectedRng = new GameRng(GameRng.Flavour.S3K, seed);
        int random = expectedRng.nextRaw();

        var constructor = CnzEndBossExplosionControllerChild.class
                .getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        CnzEndBossExplosionControllerChild controller = constructor.newInstance(0x4740, 0x0240, 4);
        controller.setServices(TestEnvironment.objectServices());
        GameServices.level().getObjectManager().addDynamicObject(controller);

        controller.update(0, fixture.sprite());

        ObjectInstance explosion = GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(S3kBossExplosionChild.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertEquals(0x4740 + (random & 0x3F) - 0x20, explosion.getSpawn().x());
        assertEquals(0x0240 + ((random >>> 16) & 0x3F) - 0x20, explosion.getSpawn().y(),
                "RandomNumber's swapped/high word supplies Child6_CreateBossExplosion Y jitter");
    }

    @Test
    void escapingShipGraphIsPersistentAndDeletesOnSignedUnderflow() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        CnzEndBossInstance boss = createBoss();
        boss.setServices(TestEnvironment.objectServices());

        var shipConstructor = CnzEndBossRobotnikShipChild.class
                .getDeclaredConstructor(CnzEndBossInstance.class);
        shipConstructor.setAccessible(true);
        CnzEndBossRobotnikShipChild ship = shipConstructor.newInstance(boss);
        ship.setServices(TestEnvironment.objectServices());
        var headConstructor = CnzEndBossRobotnikHeadChild.class
                .getDeclaredConstructor(CnzEndBossRobotnikShipChild.class);
        headConstructor.setAccessible(true);
        CnzEndBossRobotnikHeadChild head = headConstructor.newInstance(ship);
        var flameConstructor = CnzEndBossRobotnikFlameChild.class
                .getDeclaredConstructor(CnzEndBossRobotnikShipChild.class);
        flameConstructor.setAccessible(true);
        CnzEndBossRobotnikFlameChild flame = flameConstructor.newInstance(ship);
        assertTrue(ship.isPersistent());
        assertTrue(head.isPersistent());
        assertTrue(flame.isPersistent(),
                "escape graph children must bypass generic offscreen culling");

        Class<?> routineClass = Class.forName(
                "com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikShipChild$Routine");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object escape = Enum.valueOf((Class<? extends Enum>) routineClass.asSubclass(Enum.class), "ESCAPE");
        var routineField = CnzEndBossRobotnikShipChild.class.getDeclaredField("routine");
        routineField.setAccessible(true);
        routineField.set(ship, escape);
        var timerField = CnzEndBossRobotnikShipChild.class.getDeclaredField("escapeTimer");
        timerField.setAccessible(true);
        timerField.setInt(ship, 0x100);

        for (int frame = 0; frame < 0x100; frame++) {
            ship.update(frame, fixture.sprite());
        }
        assertFalse(ship.isDestroyed(), "$100 reaches zero without taking the signed-underflow branch");
        ship.update(0x100, fixture.sprite());
        assertTrue(ship.isDestroyed(), "the 257th escape dispatch underflows and deletes the ship");
    }

    private static long activeNamed(String simpleName) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(object -> !object.isDestroyed())
                .filter(object -> object.getClass().getSimpleName().equals(simpleName))
                .count();
    }

    @Test
    void attackCycleReachesNativeMagnetDropPhase() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        fixture.camera().setX((short) 0x4660);
        fixture.camera().setY((short) 0x0240);
        fixture.camera().setMinX((short) 0x4660);
        fixture.sprite().setCentreX((short) 0x4740);

        CnzEndBossInstance boss = createBoss();
        GameServices.level().getObjectManager().addDynamicObject(boss);
        fixture.stepIdleFrames(1);
        fixture.camera().setX((short) 0x4760);
        boss.update(1, fixture.sprite());
        fixture.camera().setX((short) 0x4660);
        for (int frame = 0; frame < 1800 && !"MAGNET_DROP".equals(boss.getRoutineForTest()); frame++) {
            fixture.stepIdleFrames(1);
        }

        assertEquals("MAGNET_DROP", boss.getRoutineForTest(),
                "off_6E4E2 must reach loc_6E5B6's released-magnet wait");
        assertEquals(0, boss.getMappingFrameForTest(),
                "ObjDat_CNZEndBoss keeps the parent body on frame 0; attack animation belongs to children");
    }

    private static CnzEndBossInstance createBoss() {
        CnzEndBossInstance boss = new CnzEndBossInstance(new ObjectSpawn(
                0x4740, 0x0240, Sonic3kObjectIds.CNZ_END_BOSS, 0, 0, false, 0));
        boss.setServices(TestEnvironment.objectServices());
        return boss;
    }
}
