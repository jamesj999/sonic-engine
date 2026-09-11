package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Emerald Hill Zone events.
 * ROM: LevEvents_EHZ (s2.asm:20357-20503)
 *
 * Act 1: No dynamic events
 * Act 2: Boss arena boundary changes when reaching end of level
 */
public class Sonic2EHZEvents extends Sonic2ZoneEvents {
    private Sonic2EHZBossInstance ehzBoss;

    public Sonic2EHZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        ehzBoss = null;
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        if (act == 0) {
            // Act 1: No dynamic events (ROM: LevEvents_EHZ1 just returns)
            return;
        }

        // Act 2: Boss arena setup
        // ROM: LevEvents_EHZ2 (s2.asm:20363-20503)
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0 (s2.asm:20377-20388): Wait for camera X >= $2780
                if (camera().getX() >= 0x2780) {
                    // ROM: Set minX to current camera X (immediate, prevents backtracking)
                    camera().setMinX(camera().getX());
                    // ROM: Set maxY TARGET to $390 (will ease down to boss arena height)
                    camera().setMaxYTarget((short) 0x390);
                    setSidekickBounds((int) camera().getX(), null, 0x390);
                    eventRoutine += 2;
                }
            }
            case 2 -> {
                // Routine 1 (s2.asm:20396-20411): Wait for camera X >= $28F0
                if (camera().getX() >= 0x28F0) {
                    // ROM: Lock X boundaries immediately for boss arena (not eased)
                    camera().setMinX((short) 0x28F0);
                    camera().setMaxX((short) 0x2940);
                    setSidekickBounds(0x28F0, 0x2940, null);
                    // Mark boss fight active when camera locks (enables tight boundary)
                    gameState().setCurrentBossId(Sonic2ObjectIds.EHZ_BOSS);
                    eventRoutine += 2;
                    bossSpawnDelay = 0;
                    // ROM: Start music fade-out (s2.asm:20404)
                    // Fade runs during the 90-frame spawn delay
                    audio().fadeOutMusic();
                    requestSonic2Plc(Sonic2Constants.PLC_EHZ_BOSS);
                }
            }
            case 4 -> {
                // Routine 2 (s2.asm:20414-20435): Lock floor and spawn boss
                // ROM: Set minY when camera Y reaches $388 (immediate, not eased)
                if (camera().getY() >= 0x388) {
                    camera().setMinY((short) 0x388);
                }
                // ROM: Increment delay every frame, spawn boss at $5A (90) frames
                bossSpawnDelay++;
                if (bossSpawnDelay >= 0x5A) {
                    spawnEHZBoss();
                    eventRoutine += 2;
                    // Start boss music
                    audio().playMusic(Sonic2Music.BOSS.id);
                }
            }
            case 6 -> {
                // ROM: LevEvents_EHZ2_Routine4 (s2.asm:20468-20477). This is the
                // TERMINAL routine of LevEvents_EHZ2_Index -- it never advances
                // Dynamic_Resize_Routine, so it keeps running every frame for the
                // rest of the act. While Boss_defeated_flag is clear it does
                // nothing at all (tst.b / beq.s + / rts). Once the flag is set it
                // re-copies, on EVERY subsequent frame:
                //     move.w (Camera_Max_X_pos).w,(Tails_Max_X_pos).w
                //     move.w (Camera_X_pos).w,(Tails_Min_X_pos).w
                // (the third write in that block, Camera_X_pos -> Camera_Min_X_pos,
                // is the camera-side release already owned by the boss defeat
                // sequence.)
                // This matters because the boss's own flee routine walks
                // Camera_Max_X_pos up by 2 each frame (s2.asm:63596-63599), and
                // Tails_LevelBound clamps against Tails_Max_X_pos + screen_width-24
                // (+$40 while Current_Boss_ID is clear) -- s2.asm:40265-40276 --
                // whereas Sonic_LevelBound reads Camera_Max_X_pos directly
                // (s2.asm:37243-37250). Leaving Tails_Max_X_pos frozen at the arena
                // value therefore pins the sidekick behind a boundary Sonic never
                // sees. The pre-defeat per-frame sync this replaced has no ROM
                // counterpart; Routine2 (s2.asm:20433-20434) already wrote the same
                // arena values once, so dropping it changes no value during the fight.
                if (ehzBoss != null && ehzBoss.isDefeated()) {
                    setSidekickBounds((int) camera().getX(), (int) camera().getMaxX(), null);
                }
            }
            default -> {
                // No more routines
            }
        }
    }

    /**
     * Spawns the EHZ Act 2 boss.
     * ROM: Creates Object 0x56 at coordinates (0x29D0, 0x0426) with subtype 0x81
     */
    private void spawnEHZBoss() {
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x29D0, 0x0426, Sonic2ObjectIds.EHZ_BOSS, 0x81, 0, false, 0);
        ehzBoss = spawnObject(() -> new Sonic2EHZBossInstance(bossSpawn));
    }
}
