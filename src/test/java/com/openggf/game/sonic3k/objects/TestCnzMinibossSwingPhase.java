package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossSwingPhase {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void initSetsDescentVelocityAndWait() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        boss.update(0, fixture.sprite());

        // Routine 0 -> 2 (advance to Lower after Init). Init runs once, sets y_vel and wait.
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_INIT_Y_VEL,
                boss.getCurrentYVel(), "Init must write y_vel = 0x80");
        assertTrue(boss.getCurrentRoutine() >= 2,
                "Init must advance state.routine past 0");
    }

    @Test
    void lowerAdvancesPositionUntilWaitExpires() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        int initialY = boss.getCentreY();
        for (int i = 0; i < Sonic3kConstants.CNZ_MINIBOSS_INIT_WAIT + 5; i++) {
            boss.update(i, fixture.sprite());
        }
        assertTrue(boss.getCentreY() > initialY,
                "Lower must move the boss downward (y_vel 0x80 positive)");
    }

    @Test
    void moveAssignsSwingVelocityAfterGo3() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        // Fast-forward through Init+Lower+Go2+Move waits so Obj_CNZMinibossGo3
        // fires. Per ROM (sonic3k.asm:144918..144922), Go3 writes x_vel=0x100
        // and falls through to Obj_CNZMinibossCloseGo which advances routine
        // from 4 (Move) to 6 (Move-duplicate slot); the T5 port wires this
        // fallthrough so the assertion below reads routine 6.
        int totalWait = Sonic3kConstants.CNZ_MINIBOSS_INIT_WAIT
                + Sonic3kConstants.CNZ_MINIBOSS_GO2_WAIT + 10;
        for (int i = 0; i < totalWait; i++) {
            boss.update(i, fixture.sprite());
        }
        assertEquals(6, boss.getCurrentRoutine() & 0xFF,
                "Boss must be in routine 6 (Move-duplicate) after Go3 falls "
                        + "through to CloseGo (ROM sonic3k.asm:144922)");
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_SWING_X_VEL,
                Math.abs(boss.getCurrentXVel()),
                "Go3 sets x_vel magnitude = 0x100 (sign depends on swing direction)");
    }

    @Test
    void twoClosingCyclesPreserveMoveTurnCadenceAndTopParent() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        boss.update(0, fixture.sprite());
        CnzMinibossTopInstance top = services.objectManager().getActiveObjects().stream()
                .filter(CnzMinibossTopInstance.class::isInstance)
                .map(CnzMinibossTopInstance.class::cast)
                .findFirst().orElseThrow();

        int frame = 1;
        int initialWait = Sonic3kConstants.CNZ_MINIBOSS_INIT_WAIT
                + Sonic3kConstants.CNZ_MINIBOSS_GO2_WAIT + 12;
        while (frame < initialWait) {
            boss.update(frame++, fixture.sprite());
        }
        assertEquals(6, boss.getCurrentRoutine() & 0xFF);

        for (int cycle = 0; cycle < 2; cycle++) {
            boss.forceOpenForTest();
            boss.simulateHitForTest();
            boss.update(frame++, fixture.sprite());
            assertEquals(0x0C, boss.getCurrentRoutine() & 0xFF,
                    "top-hit handoff must enter Closing through production update");
            for (int i = 0; i < 27; i++) {
                boss.update(frame++, fixture.sprite());
            }
            assertEquals(6, boss.getCurrentRoutine() & 0xFF,
                    "Closing's deferred $F4 callback must return to duplicate Move routine");
            assertEquals(Sonic3kConstants.CNZ_MINIBOSS_SWING_X_VEL,
                    Math.abs(boss.getCurrentXVel()));
            assertTrue(top.retainsParentForTest(boss),
                    "top must retain the production parent across repeated Closing cycles");

            short beforeTurn = boss.getCurrentXVel();
            boss.forceRoutineForTest(6);
            boss.update(frame++, fixture.sprite());
            assertEquals(-beforeTurn, boss.getCurrentXVel(),
                    "Move callback must negate x_vel through production update");
            short afterTurn = boss.getCurrentXVel();
            for (int i = 0; i <= Sonic3kConstants.CNZ_MINIBOSS_CHANGEDIR_WAIT; i++) {
                boss.update(frame++, fixture.sprite());
            }
            assertEquals(-afterTurn, boss.getCurrentXVel(),
                    "ChangeDir must re-arm the exact ROM $13F turn cadence");
        }
    }
}
