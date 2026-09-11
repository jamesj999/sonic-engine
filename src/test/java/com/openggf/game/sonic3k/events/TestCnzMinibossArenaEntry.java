package com.openggf.game.sonic3k.events;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.CnzMinibossInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossArenaEntry {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void cameraAtThresholdLocksArenaAndSetsBossFlag() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        GameServices.camera().setX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);

        Sonic3kCNZEvents cnz = getCnzEvents();
        // Dynamic level events run after Process_Sprites, but the arena write
        // belongs to the placed Obj_CNZMiniboss slot. Merely crossing the
        // threshold must not let the event adapter pre-empt that object.
        cnz.update(0, 0);
        assertFalse(cnz.isBossFlag(),
                "The zone event must wait for Obj_CNZMiniboss to own the arena write");

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x32C0, 0x020C, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        GameServices.level().getObjectManager().addDynamicObject(boss);
        boss.update(0, fixture.sprite());

        assertTrue(cnz.isBossFlag(),  "Boss_flag must be set");
        assertTrue(cnz.isWallGrabSuppressed(),
                "Disable_wall_grab bit 7 must be set (wall-grab suppression)");
        assertEquals(Sonic3kCNZEvents.BG_BOSS_START, cnz.getBackgroundRoutine(),
                "BG routine must be BG_BOSS_START after threshold crossing");
    }

    @Test
    void placedObjectGateIgnoresEventModeAndArenaMax() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        GameServices.camera().setX((short) (Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X + 1));

        Sonic3kCNZEvents cnz = getCnzEvents();
        cnz.update(0, 0);
        assertEquals(Sonic3kCNZEvents.BossBackgroundMode.ACT1_POST_BOSS,
                cnz.getBossBackgroundMode(),
                "Guard setup: the event adapter has independently selected post-boss mode");

        CnzMinibossInstance boss = addMiniboss();
        boss.update(0, fixture.sprite());

        assertTrue(cnz.isBossFlag(),
                "Obj_CNZMiniboss tests only unsigned Camera_X_pos >= $31E0");
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X,
                GameServices.camera().getMinX() & 0xFFFF);
    }

    @Test
    void arenaThresholdMatchesRom() {
        // The hard number: ROM sonic3k.asm:144824 reads `move.w #$31E0,d0`.
        // The scaffold previously held 0x3000; workstream D corrects it.
        assertEquals(0x31E0, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);
    }

    private static Sonic3kCNZEvents getCnzEvents() {
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        return events.getCnzEvents();
    }

    private static CnzMinibossInstance addMiniboss() {
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x32C0, 0x020C, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        GameServices.level().getObjectManager().addDynamicObject(boss);
        return boss;
    }
}
