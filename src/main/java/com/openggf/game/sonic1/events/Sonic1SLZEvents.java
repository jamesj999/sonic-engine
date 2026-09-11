package com.openggf.game.sonic1.events;

import com.openggf.game.sonic1.objects.bosses.Sonic1SLZBossInstance;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Star Light Zone dynamic level events.
 * ROM: DLE_SLZ (DynamicLevelEvents.asm)
 *
 * Acts 1-2: No events.
 * Act 3: Boss arena with 3-routine state machine.
 *
 * The event handler spawns the boss object (0x7A) via FindFreeObj,
 * manages camera boundaries and boss music. The boss object handles all
 * combat behavior, seesaw scanning, and defeat sequence internally.
 */
class Sonic1SLZEvents extends Sonic1ZoneEvents {
    // Boss constants
    private static final int BOSS_SLZ_X = 0x2000;
    private static final int BOSS_SLZ_Y = 0x210;

    Sonic1SLZEvents() {
    }

    @Override
    void update(int act) {
        retryPendingPlc();
        switch (act) {
            case 0, 1 -> { /* DLE_SLZ12: rts */ }
            case 2 -> updateAct3();
        }
    }

    /**
     * DLE_SLZ3: State machine for Act 3 boss sequence.
     * Dispatches to main/boss/end based on eventRoutine.
     */
    private void updateAct3() {
        switch (eventRoutine) {
            case 0 -> updateAct3Main();     // DLE_SLZ3main
            case 2 -> updateAct3Boss();     // DLE_SLZ3boss
            case 4 -> updateAct3End();      // DLE_SLZ3end
        }
    }

    /**
     * DLE_SLZ3main: Pre-boss boundary trigger.
     * When camera X reaches boss_slz_x - 0x190 (0x1E70),
     * sets bottom boundary to boss_slz_y and advances to boss phase.
     */
    private void updateAct3Main() {
        int camX = camera().getX() & 0xFFFF;

        if (camX < (BOSS_SLZ_X - 0x190)) {
            return; // locret_7130
        }

        // v_limitbtm1 = boss_slz_y
        camera().setMaxYTarget((short) BOSS_SLZ_Y);
        eventRoutine += 2; // advance to DLE_SLZ3boss
    }

    /**
     * DLE_SLZ3boss: Boss encounter phase.
     * When camera X reaches boss_slz_x, spawns the boss, locks the camera
     * and plays boss music.
     * ROM: DLE_SLZ3boss — FindFreeObj, set id_BossStarLight, QueueSound1 bgm_Boss,
     * f_lockscreen = 1, AddPLC plcid_Boss.
     */
    private void updateAct3Boss() {
        int camX = camera().getX() & 0xFFFF;

        if (camX < BOSS_SLZ_X) {
            return; // locret_715C
        }

        // ROM: FindFreeObj + move.b #id_BossStarLight,obID(a1)
        // BossStarLight_Main sets X = boss_slz_x+$188, Y = boss_slz_y+$18
        int bossSpawnX = BOSS_SLZ_X + 0x188; // $2188
        int bossSpawnY = BOSS_SLZ_Y + 0x18;  // $228
        LevelManager lm = levelManager();
        ObjectSpawn bossSpawn = new ObjectSpawn(
                bossSpawnX, bossSpawnY,
                Sonic1ObjectIds.SLZ_BOSS, 0, 0, false, 0);
        Sonic1SLZBossInstance boss = new Sonic1SLZBossInstance(bossSpawn);
        if (lm.getObjectManager() != null) {
            lm.getObjectManager().addDynamicObject(boss);
        }

        // ROM: QueueSound1 bgm_Boss — play boss music
        audio().playMusic(Sonic1Music.BOSS.id);
        requestSonic1Plc(17);

        // ROM: f_lockscreen = 1 — gates the 64px right boundary extension in Sonic_LevelBound. Does NOT modify v_limitleft2 or v_limitright2; camera scrolls within natural level boundaries.
        gameState().setCurrentBossId(Sonic1ObjectIds.SLZ_BOSS);
        eventRoutine += 2; // advance to DLE_SLZ3end
    }

    /**
     * DLE_SLZ3end: Post-boss lock.
     * Locks left boundary to current camera X position.
     */
    private void updateAct3End() {
        // v_limitleft2 = v_screenposx
        camera().setMinX(camera().getX());
    }
}
