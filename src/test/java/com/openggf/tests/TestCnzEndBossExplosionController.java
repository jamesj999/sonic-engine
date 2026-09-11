package com.openggf.tests;

import com.openggf.game.GameRng;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossExplosionControllerChild;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossRobotnikShipChild;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzEndBossExplosionController {
    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void subtypeFourFollowsRestoredShipUntilParentDeletion() throws Exception {
        HeadlessTestFixture fixture = fixtureAtBoss();
        CnzEndBossRobotnikShipChild ship = activeObject(CnzEndBossRobotnikShipChild.class);
        CnzEndBossExplosionControllerChild controller = controller(ship);
        GameServices.level().getObjectManager().addDynamicObject(controller);
        ObjectManager objectManager = GameServices.level().getObjectManager();
        var snapshot = objectManager.rewindSnapshottable().capture();
        objectManager.rewindSnapshottable().restore(snapshot);

        CnzEndBossRobotnikShipChild restoredShip = activeObject(CnzEndBossRobotnikShipChild.class);
        CnzEndBossExplosionControllerChild restoredController =
                activeObject(CnzEndBossExplosionControllerChild.class);
        setInt(restoredShip, "centreX", 0x47A0);
        setInt(restoredShip, "centreY", 0x01E0);
        restoredController.update(0, fixture.sprite());
        assertEquals(0x47A0, restoredController.getX());
        assertEquals(0x01E0, restoredController.getY());
        assertFalse(restoredController.isDestroyed(),
                "subtype 4's signed-negative $80 timer remains constant");

        restoredShip.setDestroyed(true);
        restoredController.update(1, fixture.sprite());
        assertTrue(restoredController.isDestroyed(),
                "Obj_WaitForParent deletes with the escaping ship");
    }

    @Test
    void subtypeFourSpawnsImmediatelyFromTheShipsLivePosition() throws Exception {
        HeadlessTestFixture fixture = fixtureAtBoss();
        CnzEndBossRobotnikShipChild ship = activeObject(CnzEndBossRobotnikShipChild.class);
        setInt(ship, "centreX", 0x47A0);
        setInt(ship, "centreY", 0x01E0);
        long seed = 0x12345678L;
        GameServices.rng().setSeed(seed);
        GameRng expectedRng = new GameRng(GameRng.Flavour.S3K, seed);
        int random = expectedRng.nextRaw();
        CnzEndBossExplosionControllerChild controller = controller(ship);
        GameServices.level().getObjectManager().addDynamicObject(controller);
        controller.update(0, fixture.sprite());

        S3kBossExplosionChild explosion = activeObject(S3kBossExplosionChild.class);
        assertEquals(0x47A0 + (random & 0x3F) - 0x20, explosion.getSpawn().x());
        assertEquals(0x01E0 + ((random >>> 16) & 0x3F) - 0x20, explosion.getSpawn().y());
    }

    private static HeadlessTestFixture fixtureAtBoss() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        fixture.camera().setX((short) 0x4760);
        fixture.camera().setY((short) 0x0240);
        fixture.camera().setMinX((short) 0x4760);
        fixture.stepIdleFrames(132);
        return fixture;
    }

    private static CnzEndBossExplosionControllerChild controller(
            CnzEndBossRobotnikShipChild ship) throws Exception {
        var constructor = CnzEndBossExplosionControllerChild.class
                .getDeclaredConstructor(CnzEndBossRobotnikShipChild.class, int.class);
        constructor.setAccessible(true);
        CnzEndBossExplosionControllerChild controller = constructor.newInstance(ship, 4);
        controller.setServices(TestEnvironment.objectServices());
        return controller;
    }

    private static <T> T activeObject(Class<T> type) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(object -> !object.isDestroyed())
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void setInt(Object target, String fieldName, int value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
