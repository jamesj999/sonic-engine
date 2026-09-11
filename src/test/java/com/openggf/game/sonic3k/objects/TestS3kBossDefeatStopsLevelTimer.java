package com.openggf.game.sonic3k.objects;

import com.openggf.game.LevelState;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every S3K boss whose hit counter reaches zero enters its defeat branch through
 * {@code BossDefeated_StopTimer} (sonic3k.asm:180890), which clears
 * {@code Update_HUD_timer}. That flag is the first thing
 * {@code SonicKnux_SuperHyper} tests (sonic3k.asm:23609-23611): with it clear the
 * routine branches straight to {@code .revertToNormal}, so beating a boss or
 * miniboss as Super/Hyper drops the player back to the normal form regardless of
 * how many rings are left.
 *
 * <p>The engine models {@code Update_HUD_timer} as {@link LevelState}'s paused
 * flag, which {@code SuperStateController.updateSuper()} polls. Bosses that
 * never paused it left Super Sonic active for the rest of the act.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kBossDefeatStopsLevelTimer {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void cnzMinibossDefeatStopsTheLevelTimer() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        LevelState levelState = services.levelGamestate();
        assertFalse(levelState.isTimerPaused(), "Timer must be running before the boss is beaten");

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0200, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(0x06);

        for (int i = 0; i < 3; i++) {
            boss.simulateHitForTest();
            assertFalse(levelState.isTimerPaused(),
                    "Non-fatal hits must not stop the timer (ROM: only the $45 == 0 branch does)");
        }
        boss.simulateHitForTest();

        assertTrue(levelState.isTimerPaused(),
                "CNZMiniboss_BossDefeated jumps to BossDefeated_StopTimer (sonic3k.asm:145527)");
    }

    @Test
    void iczMinibossDefeatStopsTheLevelTimer() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 0)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        LevelState levelState = services.levelGamestate();
        assertFalse(levelState.isTimerPaused(), "Timer must be running before the boss is beaten");

        IczMinibossInstance boss = new IczMinibossInstance(
                new ObjectSpawn(0x2000, 0x0300, Sonic3kObjectIds.ICZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        while (boss.getState().hitCount > 0) {
            boss.simulateHitForTest();
        }

        assertTrue(levelState.isTimerPaused(),
                "loc_71926 jumps to BossDefeated_StopTimer (sonic3k.asm:150472)");
    }
}
