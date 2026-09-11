package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Workstream D Task 5 — coverage for CNZ1 miniboss routines 6/8/A per the
 * ROM dispatch table at sonic3k.asm:144874:
 * <ul>
 *   <li>routine 6 — duplicate Move slot, entered by
 *       {@code Obj_CNZMinibossCloseGo} (sonic3k.asm:144922), where
 *       {@code Obj_CNZMinibossChangeDir} (sonic3k.asm:144935) periodically
 *       flips {@code x_vel} via {@code neg.w x_vel(a0)}.</li>
 *   <li>routine 8 — {@code Obj_CNZMinibossOpening} (sonic3k.asm:144941),
 *       Animate_RawMultiDelay body whose animation-complete callback is
 *       {@code Obj_CNZMinibossOpenGo} (sonic3k.asm:144945).</li>
 *   <li>routine A — {@code Obj_CNZMinibossWaitHit} (sonic3k.asm:144954),
 *       gates on {@code btst #6,status(a0)}; on hit, {@code loc_6DB4E}
 *       (sonic3k.asm:144960) advances routine to C.</li>
 * </ul>
 *
 * <p>Note on routine numbers: the workstream plan references
 * &quot;routines 6/8/A&quot; but the authoritative ROM dispatch at
 * {@link com.openggf.game.sonic3k.constants.Sonic3kConstants} (lines
 * 1389..1407) shows 6 = Move-duplicate, 8 = Opening, A = WaitHit,
 * C = Closing. Tests track the ROM mapping to stay parity-faithful.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossOpeningPhase {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void enteringRoutine6FlipsSwingDirection() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(6);
        boss.forceXVelForTest((short) 0x0100);
        boss.update(0, fixture.sprite());
        // Obj_CNZMinibossChangeDir negates x_vel on entry to routine 6
        assertEquals((short) -0x0100, boss.getCurrentXVel(),
                "Routine 6 entry should have flipped x_vel");
    }

    @Test
    void waitHitRoutineStallsUntilOpenBitSet() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        // ROM dispatch: routine A == Obj_CNZMinibossWaitHit (sonic3k.asm:144954).
        boss.forceRoutineForTest(0xA);
        int routineBefore = boss.getCurrentRoutine();
        for (int i = 0; i < 30; i++) boss.update(i, fixture.sprite());
        assertEquals(routineBefore, boss.getCurrentRoutine(),
                "WaitHit must idle (btst #6,status — no hit yet)");
    }

    @Test
    void hitDuringOpeningAdvancesToClosing() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        // ROM dispatch: routine A == WaitHit; loc_6DB4E advances routine to C
        // when status bit 6 is set.
        boss.forceRoutineForTest(0xA);
        boss.simulateHitForTest();     // sets status bit 6 (ROM loc_6DB4E precondition)
        boss.update(0, fixture.sprite());
        assertEquals(0x0C, boss.getCurrentRoutine() & 0xFF,
                "Hit during WaitHit advances routine to C (Closing)");
    }
}
