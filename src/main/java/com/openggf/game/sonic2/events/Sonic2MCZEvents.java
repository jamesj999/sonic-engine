package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.Sonic2MCZBossInstance;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Mystic Cave Zone events.
 * ROM: LevEvents_MCZ (s2.asm:20777-20851)
 *
 * Act 1: No dynamic events
 * Act 2: Boss arena - camera lock, art load, boss spawn
 */
public class Sonic2MCZEvents extends Sonic2ZoneEvents {
    private Sonic2MCZBossInstance mczBoss;

    public Sonic2MCZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        mczBoss = null;
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        if (act == 0) {
            // Act 1: No dynamic events (ROM: LevEvents_MCZ1 just returns)
            return;
        }

        // Act 2: Boss arena setup
        // ROM: LevEvents_MCZ2 (s2.asm:21384-21483)
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0 (s2.asm LevEvents_MCZ2_Routine1): Wait for camera X >= $2080
                if (camera().getX() >= 0x2080) {
                    // ROM: Set minX to current camera X (prevents backtracking)
                    camera().setMinX(camera().getX());
                    // ROM: Set maxY TARGET to $5D0
                    camera().setMaxYTarget((short) 0x5D0);
                    setSidekickBounds((int) camera().getX(), null, 0x5D0);
                    eventRoutine += 2;
                }
            }
            case 2 -> {
                // Routine 1 (s2.asm LevEvents_MCZ2_Routine2): Wait for camera X >= $20F0
                if (camera().getX() >= 0x20F0) {
                    // ROM: Lock camera X boundaries for boss arena
                    camera().setMinX((short) 0x20F0);
                    camera().setMaxX((short) 0x20F0);
                    setSidekickBounds(0x20F0, 0x20F0, null);
                    // Mark boss fight active
                    gameState().setCurrentBossId(Sonic2ObjectIds.MCZ_BOSS);
                    eventRoutine += 2;
                    bossSpawnDelay = 0;
                    // ROM: Fade out music
                    audio().fadeOutMusic();
                    requestSonic2Plc(Sonic2Constants.PLC_MCZ_BOSS);
                    // ROM: PalLoad_Now Pal_MCZ_B -> palette line 1 (s2.asm:21447-21448)
                    loadBossPalette(1, Sonic2Constants.PAL_MCZ_BOSS_ADDR);
                }
            }
            case 4 -> {
                // Routine 2 (s2.asm LevEvents_MCZ2_Routine3): Lock floor and spawn boss
                // ROM: Set minY when camera Y reaches $5C8
                if (camera().getY() >= 0x5C8) {
                    camera().setMinY((short) 0x5C8);
                }
                // ROM: Increment delay every frame, spawn boss at $5A (90) frames
                bossSpawnDelay++;
                if (bossSpawnDelay >= 0x5A) {
                    spawnMCZBoss();
                    eventRoutine += 2;
                    // Start boss music
                    audio().playMusic(Sonic2Music.BOSS.id);
                }
            }
            case 6 -> {
                // Routine 3 (s2.asm LevEvents_MCZ2_Routine4): Boss fight area
                // ROM: Play rumble SFX every $20 frames during screen shake
                if (mczBoss != null && mczBoss.isScreenShaking()) {
                    if ((frameCounter & 0x1F) == 0) {
                        audio().playSfx(Sonic2Sfx.RUMBLING_2.id);
                    }
                }
                // ROM: Update minX to camera X (prevent backtracking)
                camera().setMinX(camera().getX());
                syncSidekickBoundsToCamera();
            }
            default -> {
                // No more routines
            }
        }
    }

    /**
     * Spawns the MCZ Act 2 boss.
     * ROM: Creates Object 0x57 at coordinates (0x21A0, 0x0560)
     */
    private void spawnMCZBoss() {
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x21A0, 0x0560, Sonic2ObjectIds.MCZ_BOSS, 0, 0, false, 0);
        mczBoss = new Sonic2MCZBossInstance(bossSpawn);
        spawnObject(mczBoss);
    }
}
