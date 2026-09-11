package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzEndBossShip {

    @Test
    void subtypeNineShipAndRealHeadUseRomFrames() throws Exception {
        ObjectServices services = new StubObjectServices();
        CnzEndBossInstance boss = boss();
        boss.setServices(services);
        CnzEndBossRobotnikShipChild ship = new CnzEndBossRobotnikShipChild(boss);
        ship.setServices(services);

        ship.update(0, null);

        assertEquals(9, ship.frameForTest(),
                "Child1_MakeRoboShip4 subtype 9 initializes mapping_frame to 9");
        assertEquals(boss.getCentreX(), ship.getCentreX());
        assertEquals(boss.getCentreY() - 8, ship.getCentreY());

        CnzEndBossRobotnikHeadChild head = new CnzEndBossRobotnikHeadChild(ship);
        head.setServices(services);
        setInt(boss, "hitInvulnerabilityTimer", 0x20);
        head.update(1, null);
        assertEquals(2, head.frameForTest(),
                "Obj_RobotnikHead exposes the hurt frame while parent boss status bit 6 is set");
    }

    @Test
    void shipRunsFrameARiseFlameEscapeAndClearsBossFlagAfterOneHundredFrames() throws Exception {
        CnzEndBossInstance boss = boss();
        ObjectServices services = new StubObjectServices();
        boss.setServices(services);
        CnzEndBossRobotnikShipChild ship = new CnzEndBossRobotnikShipChild(boss);
        ship.setServices(services);
        ship.update(0, null);
        setBossRoutine(boss, CnzEndBossInstance.Routine.DEFEATED);
        ship.update(1, null);
        assertTrue(ship.explosionControllerSpawnedForTest(),
                "parent status bit 7 must create the subtype-4 boss explosion controller once");

        setBoolean(boss, "defeatHandoffComplete", true);
        ship.update(2, null);
        assertEquals(0xA, ship.frameForTest(), "parent bit 4 selects damaged ship frame $A");
        setInt(ship, "centreY", 0x40);
        for (int frame = 3; frame < 400 && !ship.escapeStartedForTest(); frame++) {
            ship.update(frame, null);
        }
        assertTrue(ship.escapeStartedForTest());
        assertTrue(ship.flameSpawnedForTest(), "loc_67F1E creates Child1_MakeRoboShipFlame");
        for (int frame = 0; frame < 0x100; frame++) {
            ship.update(500 + frame, null);
        }
        assertFalse(ship.isDestroyed(), "$100 reaches zero without taking the signed-underflow branch");
        ship.update(500 + 0x100, null);
        assertTrue(ship.isDestroyed(), "Obj_RobotnikShipEscape deletes on the 257th dispatch");
        assertTrue(ship.bossFlagClearedForTest(), "loc_67F4C clears Boss_flag before deleting the ship");
    }

    @Test
    void bossBodyFlickersButShipHeadRetainsHurtExposure() throws Exception {
        CnzEndBossInstance boss = boss();
        setInt(boss, "hitInvulnerabilityTimer", 0x20);
        setBossRoutine(boss, CnzEndBossInstance.Routine.ENTRY);
        setInt(boss, "routineTimer", 0x7F);
        boss.setServices(new StubObjectServices());

        boss.update(0, null);
        assertTrue(boss.isBodyVisibleForTest());
        boss.update(1, null);
        assertFalse(boss.isBodyVisibleForTest(),
                "status bit 6 must suppress the body sprite on alternating invulnerability frames");
    }

    @Test
    void robotnikHeadRawAnimationAdvancesBehindHurtFrameOverlay() throws Exception {
        ObjectServices services = new StubObjectServices();
        CnzEndBossInstance boss = boss();
        boss.setServices(services);
        CnzEndBossRobotnikShipChild ship = new CnzEndBossRobotnikShipChild(boss);
        ship.setServices(services);
        CnzEndBossRobotnikHeadChild head = new CnzEndBossRobotnikHeadChild(ship);
        head.setServices(services);
        setInt(head, "animationTimer", 0);
        setInt(boss, "hitInvulnerabilityTimer", 0x20);

        head.update(0, null);
        assertEquals(2, head.frameForTest(),
                "Obj_RobotnikHead overlays hurt frame 2 after Animate_Raw advances");

        setInt(boss, "hitInvulnerabilityTimer", 0);
        head.update(1, null);
        assertEquals(1, head.frameForTest(),
                "the raw 0/1 phase advanced during hurt and resumes without freezing");
    }

    private static CnzEndBossInstance boss() {
        return new CnzEndBossInstance(new com.openggf.level.objects.ObjectSpawn(
                0x4740, 0x0240, 0xA7, 0, 0, false, 0));
    }

    private static void setBossRoutine(CnzEndBossInstance boss, CnzEndBossInstance.Routine routine)
            throws Exception {
        field(boss, "routine").set(boss, routine);
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception {
        field(target, name).setBoolean(target, value);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        field(target, name).setInt(target, value);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
