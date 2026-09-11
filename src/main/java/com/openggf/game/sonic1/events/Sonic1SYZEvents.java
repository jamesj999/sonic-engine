package com.openggf.game.sonic1.events;

import com.openggf.game.GameServices;
import com.openggf.game.sonic1.objects.bosses.Sonic1BossBlockInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Spring Yard Zone dynamic level events.
 * ROM: DLE_SYZ (DynamicLevelEvents.asm)
 *
 * Act 1: No events.
 * Act 2: Bottom boundary adjusts based on camera X and player Y.
 * Act 3: Boss arena with 3-routine state machine.
 */
class Sonic1SYZEvents extends Sonic1ZoneEvents {
    // Boss constants
    private static final int BOSS_SYZ_X = 0x2C00;
    private static final int BOSS_SYZ_Y = 0x4CC;

    Sonic1SYZEvents() {
    }

    @Override
    void update(int act) {
        retryPendingPlc();
        switch (act) {
            case 0 -> { /* DLE_SYZ1: rts */ }
            case 1 -> updateAct2();
            case 2 -> updateAct3();
        }
    }

    /**
     * DLE_SYZ2: Three-zone boundary with player Y check.
     * Bottom boundary defaults to 0x520, drops to 0x420 when camera X >= 0x25A0,
     * but reverts to 0x520 if player Y >= 0x4D0.
     */
    private void updateAct2() {
        int camX = camera().getX() & 0xFFFF;

        // v_limitbtm1 = 0x520
        camera().setMaxYTarget((short) 0x520);
        if (camX < 0x25A0) {
            return; // locret_71A2
        }

        // v_limitbtm1 = 0x420
        camera().setMaxYTarget((short) 0x420);

        // Check player Y position directly via the focused sprite
        int playerY = camera().getFocusedSprite().getCentreY() & 0xFFFF;
        if (playerY < 0x4D0) {
            return; // locret_71A2
        }

        // v_limitbtm1 = 0x520 (revert)
        camera().setMaxYTarget((short) 0x520);
        // locret_71A2
    }

    /**
     * DLE_SYZ3: State machine for Act 3 boss sequence.
     * Dispatches to main/boss/end based on eventRoutine.
     */
    private void updateAct3() {
        switch (eventRoutine) {
            case 0 -> updateAct3Main();     // DLE_SYZ3main
            case 2 -> updateAct3Boss();     // DLE_SYZ3boss
            case 4 -> updateAct3End();      // DLE_SYZ3end
        }
    }

    /**
     * DLE_SYZ3main: Pre-boss trigger.
     * When camera X reaches boss_syz_x - 0x140 (0x2AC0),
     * spawn boss arena blocks and advance to boss phase.
     * ROM: move.w #id_BossBlock,(v_lvlobjspace+$40).w
     */
    private void updateAct3Main() {
        int camX = camera().getX() & 0xFFFF;

        if (camX < (BOSS_SYZ_X - 0x140)) {
            return; // locret_71CE
        }

        // ROM: Spawn boss block object which self-replicates into 10 blocks
        Sonic1BossBlockInstance.spawnAllBlocks();

        eventRoutine += 2; // advance to DLE_SYZ3boss
    }

    /**
     * DLE_SYZ3boss: Boss encounter phase.
     * When camera X reaches boss_syz_x, locks the camera, spawns the boss,
     * and plays boss music.
     * ROM: DLE_SYZ3boss
     */
    private void updateAct3Boss() {
        int camX = camera().getX() & 0xFFFF;

        if (camX < BOSS_SYZ_X) {
            return; // locret_7200
        }

        // v_limitbtm1 = boss_syz_y
        camera().setMaxYTarget((short) BOSS_SYZ_Y);

        // ROM: Spawn boss object
        LevelManager lm = levelManager();
        if (lm != null && lm.getObjectManager() != null) {
            // Create boss at spawn position (boss_syz_x + $1B0, boss_syz_y + $E)
            ObjectSpawn bossSpawn = new ObjectSpawn(
                    BOSS_SYZ_X + 0x1B0, BOSS_SYZ_Y + 0x0E,
                    0x75, 0, 0, false, 0);
            spawnObject(() -> new Sonic1SYZBossInstance(bossSpawn));
        }

        // ROM: QueueSound1 bgm_Boss
        audio().playMusic(Sonic1Music.BOSS.id);
        requestSonic1Plc(17);

        // ROM: f_lockscreen = 1 — gates the 64px right boundary extension in Sonic_LevelBound. Does NOT modify v_limitleft2 or v_limitright2; camera scrolls within natural level boundaries.
        gameState().setCurrentBossId(0x75);
        eventRoutine += 2; // advance to DLE_SYZ3end
    }

    /**
     * DLE_SYZ3end: Post-boss lock.
     * Locks left boundary to current camera X position.
     */
    private void updateAct3End() {
        // v_limitleft2 = v_screenposx
        camera().setMinX(camera().getX());
    }
}
