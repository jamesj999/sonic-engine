package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.objects.bosses.Sonic2ARZBossInstance;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Aquatic Ruin Zone events.
 * ROM: LevEvents_ARZ (s2.asm:21717-21790)
 *
 * Act 1: No dynamic events
 * Act 2: Boss arena - camera lock and boss spawn
 */
public class Sonic2ARZEvents extends Sonic2ZoneEvents {
    private Sonic2ARZBossInstance arzBoss;

    public Sonic2ARZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        arzBoss = null;
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        if (act == 0) {
            // Act 1: No dynamic events
            return;
        }

        // Act 2: Boss arena setup
        // ROM: LevEvents_ARZ2 (s2.asm:21723-21790)
        switch (eventRoutine) {
            case 0 -> {
                // Routine 1 (s2.asm:21737-21749): Wait for camera X >= $2810
                if (camera().getX() >= 0x2810) {
                    // ROM: Set minX to current camera X (prevent backtracking)
                    camera().setMinX(camera().getX());
                    // ROM: Set maxY TARGET to $400 (boss arena height)
                    camera().setMaxYTarget((short) 0x400);
                    setSidekickBounds((int) camera().getX(), null, 0x400);
                    eventRoutine += 2;
                    // ROM: move.b #4,(Current_Boss_ID).w
                    gameState().setCurrentBossId(4);
                    // ROM: LoadPLC for ARZ boss art
                    requestSonic2Plc(Sonic2Constants.PLC_ARZ_BOSS);
                }
            }
            case 2 -> {
                // Routine 2 (s2.asm:21752-21767): Wait for camera X >= $2A40, spawn boss
                if (camera().getX() >= 0x2A40) {
                    // ROM: Lock camera X at $2A40 (both min and max)
                    camera().setMinX((short) 0x2A40);
                    camera().setMaxX((short) 0x2A40);
                    setSidekickBounds(0x2A40, 0x2A40, null);
                    eventRoutine += 2;
                    bossSpawnDelay = 0;
                    // ROM: Fade out music
                    audio().fadeOutMusic();
                    // ROM: Spawn ARZ boss (obj89) - this is where the boss is created!
                    spawnARZBoss();
                }
            }
            case 4 -> {
                // Routine 3 (s2.asm:21770-21783): Lock floor, wait for spawn delay, play boss music
                // ROM: Lock minY when camera Y >= $3F8
                if (camera().getY() >= 0x3F8) {
                    camera().setMinY((short) 0x3F8);
                }
                // ROM: Increment delay every frame, play boss music at $5A (90) frames
                bossSpawnDelay++;
                if (bossSpawnDelay >= 0x5A) {
                    eventRoutine += 2;
                    // Start boss music
                    audio().playMusic(Sonic2Music.BOSS.id);
                }
            }
            case 6 -> {
                // Routine 4 (s2.asm:21786-21790): Prevent backtracking during boss fight
                // ROM: move.w (Camera_X_pos).w,(Camera_Min_X_pos).w
                short cameraX = camera().getX();
                if (cameraX > camera().getMinX()) {
                    camera().setMinX(cameraX);
                }
                syncSidekickBoundsToCamera();
            }
            default -> {
                // No more routines
            }
        }
    }

    /**
     * Spawns the ARZ Act 2 boss.
     * ROM: Creates Object 0x89 at current location with subtype 0
     * Note: The boss object handles its own positioning in initMain().
     */
    private void spawnARZBoss() {
        // ROM spawns obj89 with no specific coordinates - the object positions itself
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x2AE0, 0x388, Sonic2ObjectIds.ARZ_BOSS, 0, 0, false, 0);
        arzBoss = new Sonic2ARZBossInstance(bossSpawn);
        spawnObject(arzBoss);
    }
}
