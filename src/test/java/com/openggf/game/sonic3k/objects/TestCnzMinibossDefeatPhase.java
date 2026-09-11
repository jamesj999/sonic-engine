package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6 defeat-phase tests for CNZ1 miniboss.
 *
 * <p>Plan-vs-ROM divergence note: the plan text for T6 describes
 * "routine C = Lower2" and "routine E = End", but the ROM dispatch
 * table at {@code CNZMiniboss_Index} (sonic3k.asm:144874-144882)
 * places {@code Obj_CNZMinibossClosing} at slot C (144968) and
 * {@code Obj_CNZMinibossLower2} at slot E (144972). {@code
 * Obj_CNZMinibossEnd} (144984) is not in the dispatch table at all;
 * it is installed via {@code $34(a0)} during the defeat chain
 * (CNZMiniboss_BossDefeated -> Wait_FadeToLevelMusic -> Obj_CNZMinibossEnd
 * -> Obj_CNZMinibossEndGo). The tests below follow the ROM.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossDefeatPhase {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void lower2AdvancesOnePixelPerFrameForCounter() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0200, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(0x0E);  // Lower2 is slot E (ROM: 144972), not C
        // $43(a0) = 4 (decremented per frame); restore $42(a0) = ROUTINE_MOVE_DUP
        // (0x06) on counter expiry. Migrated off the deprecated single-arg shim.
        boss.armLower2CounterForTest(4, 0x06);
        int startY = boss.getCentreY();

        for (int i = 0; i < 6; i++) boss.update(i, fixture.sprite());
        assertTrue(boss.getCentreY() >= startY + 4,
                "Lower2 must add #1 to y_pos each frame until $43 expires");
    }

    @Test
    void topPieceHitsReduceFourHitCounterAndFourthHitTriggersDefeat() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0200, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        // Capture intermediate counter values to prove monotonic decrement.
        // A buggy impl that slammed hitCount to 0 on the first hit would pass
        // the endpoint-only assertion, so sample at hits 1 and 3 explicitly.
        int counterAfter1 = -1;
        int counterAfter3 = -1;
        for (int i = 0; i < 4; i++) {
            boss.simulateHitForTest();
            int remaining = boss.getRemainingHits();
            if (i == 0) counterAfter1 = remaining;
            if (i == 2) counterAfter3 = remaining;
        }
        assertEquals(3, counterAfter1, "After 1 top-to-coil hit, hitCount should be 3");
        assertEquals(1, counterAfter3, "After 3 top-to-coil hits, hitCount should be 1");
        assertEquals(0, boss.getRemainingHits(),
                "After 4 top-to-coil hits, hitCount must be 0");
        assertTrue(boss.isDefeated(),
                "After the 4th top-to-coil hit the boss must be in defeat state");
    }

    @Test
    void defeatClearsBossFlagAndReleasesWallGrab() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        // Arm the arena-entry flags so the defeat path has something to clear.
        Sonic3kCNZEvents cnz = getCnzEvents();
        cnz.setBossFlag(true);
        cnz.setWallGrabSuppressed(true);

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0200, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        // Defeat replaces the current object routine but deliberately preserves
        // the live ROM $2E timer. Enter a valid live Move state with $2E=0;
        // attacking the never-initialized placement would leave $2E=-1, a state
        // the real collision path cannot reach and whose callback cannot expire.
        boss.forceRoutineForTest(0x06);

        for (int i = 0; i < 4; i++) {
            boss.simulateHitForTest();
        }
        // The inherited zero timer underflows on this update and dispatches
        // Obj_CNZMinibossEndGo through the replacement $34 callback.
        boss.update(0, fixture.sprite());

        assertFalse(cnz.isBossFlag(),
                "Obj_CNZMinibossEndGo must clr.b Boss_flag (sonic3k.asm:144998)");
        assertFalse(cnz.isWallGrabSuppressed(),
                "Post-defeat wall-grab suppression must be released (ROM sonic3k.asm:144998 mirror)");
    }

    private static Sonic3kCNZEvents getCnzEvents() {
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        return events.getCnzEvents();
    }
}
