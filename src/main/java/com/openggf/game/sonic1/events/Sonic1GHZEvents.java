package com.openggf.game.sonic1.events;

import com.openggf.game.sonic1.objects.bosses.Sonic1GHZBossInstance;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Green Hill Zone dynamic level events.
 * ROM: DLE_GHZ (DynamicLevelEvents.asm)
 *
 * Adjusts camera bottom boundary based on player/camera position.
 * Act 3 uses a state machine (eventRoutine) for boss sequence.
 */
class Sonic1GHZEvents extends Sonic1ZoneEvents {
    // Boss constants
    private static final int BOSS_GHZ_X = 0x2960;
    private static final int BOSS_GHZ_Y = 0x300;

    Sonic1GHZEvents() {
    }

    @Override
    void update(int act) {
        retryPendingPlc();
        switch (act) {
            case 0 -> updateAct1();
            case 1 -> updateAct2();
            case 2 -> updateAct3();
        }
    }

    /**
     * DLE_GHZ1: Simple two-zone boundary.
     * Bottom boundary is 0x300 until camera X >= 0x1780, then 0x400.
     */
    private void updateAct1() {
        camera().setMaxYTarget((short) 0x300);
        int camX = camera().getX() & 0xFFFF;
        if (camX < 0x1780) {
            return; // locret_6E08
        }
        camera().setMaxYTarget((short) 0x400);
    }

    /**
     * DLE_GHZ2: Four-zone boundary progression.
     * Boundaries change at X thresholds: 0xED0, 0x1600, 0x1D60.
     */
    private void updateAct2() {
        camera().setMaxYTarget((short) 0x300);
        int camX = camera().getX() & 0xFFFF;
        if (camX < 0xED0) {
            return; // locret_6E3A
        }
        camera().setMaxYTarget((short) 0x200);
        if (camX < 0x1600) {
            return; // locret_6E3A
        }
        camera().setMaxYTarget((short) 0x400);
        if (camX < 0x1D60) {
            return; // locret_6E3A
        }
        camera().setMaxYTarget((short) 0x300);
    }

    /**
     * DLE_GHZ3: State machine for Act 3 boss sequence.
     * Dispatches to main/boss/end based on eventRoutine.
     * ROM: off_6E4A jump table.
     */
    private void updateAct3() {
        switch (eventRoutine) {
            case 0 -> updateAct3Main();      // DLE_GHZ3main
            case 2 -> updateAct3Boss();      // DLE_GHZ3boss
            case 4 -> updateAct3End();       // DLE_GHZ3end
        }
    }

    /**
     * DLE_GHZ3main: Pre-boss boundary logic with Y-position check.
     * Progresses through multiple X thresholds, with a Y check at 0x960
     * that can short-circuit to the boss phase.
     */
    private void updateAct3Main() {
        int camX = camera().getX() & 0xFFFF;
        int camY = camera().getY() & 0xFFFF;

        camera().setMaxYTarget((short) 0x300);
        if (camX < 0x380) {
            return; // locret_6E96
        }

        camera().setMaxYTarget((short) 0x310);
        if (camX < 0x960) {
            return; // locret_6E96
        }

        // At X >= 0x960, check camera Y
        if (camY < 0x280) {
            // loc_6E98: transition to boss phase
            transitionToBoss();
            return;
        }

        camera().setMaxYTarget((short) 0x400);
        if (camX < 0x1380) {
            // Immediate set (not eased) - sets both v_limitbtm1 and v_limitbtm2
            camera().setMaxY((short) 0x4C0);
        }

        // loc_6E8E
        if (camX >= 0x1700) {
            // loc_6E98: transition to boss phase
            transitionToBoss();
            return;
        }
        // locret_6E96
    }

    /**
     * loc_6E98: Transition to boss phase.
     * Sets boss Y boundary and advances event routine.
     */
    private void transitionToBoss() {
        camera().setMaxYTarget((short) BOSS_GHZ_Y); // 0x300
        eventRoutine += 2; // advance to DLE_GHZ3boss
    }

    /**
     * DLE_GHZ3boss: Boss encounter phase.
     * Can revert to main if player goes back left of 0x960.
     * Locks camera and spawns boss when X >= 0x2960.
     */
    private void updateAct3Boss() {
        int camX = camera().getX() & 0xFFFF;

        if (camX < 0x960) {
            // Player went back left, revert to main phase
            eventRoutine -= 2; // back to DLE_GHZ3main
        }

        // loc_6EB0
        if (camX < BOSS_GHZ_X) {
            return; // locret_6EE8
        }

        // ROM: DynamicLevelEvents.asm — spawn boss at boss_ghz_x+$100, boss_ghz_y-$80
        // and play boss music (bgm_Boss)
        int bossSpawnX = BOSS_GHZ_X + 0x100; // $2A60
        int bossSpawnY = BOSS_GHZ_Y - 0x80;  // $280
        ObjectSpawn bossSpawn = new ObjectSpawn(
                bossSpawnX, bossSpawnY,
                Sonic1ObjectIds.GHZ_BOSS, 0, 0, false, 0);
        LevelManager lm = levelManager();
        Sonic1GHZBossInstance boss = new Sonic1GHZBossInstance(bossSpawn);
        if (lm.getObjectManager() != null) {
            lm.getObjectManager().addDynamicObject(boss);
        }
        audio().playMusic(Sonic1Music.BOSS.id);
        requestSonic1Plc(17);

        // ROM: f_lockscreen = 1 — gates the 64px right boundary extension in Sonic_LevelBound. Does NOT modify v_limitleft2 or v_limitright2; camera continues scrolling within natural level boundaries. setBossId() is the Java equivalent.
        gameState().setCurrentBossId(Sonic1ObjectIds.GHZ_BOSS);
        eventRoutine += 2; // advance to DLE_GHZ3end
    }

    /**
     * DLE_GHZ3end: Post-boss lock.
     * Locks left boundary to current camera X position.
     */
    private void updateAct3End() {
        // v_limitleft2 = v_screenposx
        camera().setMinX(camera().getX());
    }
}
