package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.Sonic2MTZBossInstance;
import com.openggf.game.GameServices;
import com.openggf.level.objects.ObjectSpawn;

import java.util.logging.Logger;

/**
 * Metropolis Zone events.
 * ROM: LevEvents_MTZ / LevEvents_MTZ3 (s2.asm:20471-20556)
 *
 * Acts 1 & 2 (act 0, 1): No dynamic events (ROM: LevEvents_MTZ just rts).
 * Act 3 (act 2): 5 routines via Dynamic_Resize_Routine dispatch:
 *   Routine 0: Bottom boundary adjustment at X >= $2530
 *   Routine 2: Pre-boss minX lock + maxY target at X >= $2980
 *   Routine 4: Boss arena camera lock at X >= $2A80, fade music, set boss ID, load PLC
 *   Routine 6: Lock minY at Y >= $400, boss spawn delay, play boss music
 *   Routine 8: Prevent backtracking (minX follows camera)
 */
public class Sonic2MTZEvents extends Sonic2ZoneEvents {
    private static final Logger LOGGER = Logger.getLogger(Sonic2MTZEvents.class.getName());

    /** ROM: move.b #7,(Current_Boss_ID).w */
    private static final int MTZ_BOSS_ID = 7;

    public Sonic2MTZEvents() {
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        if (act < 2) {
            // Acts 1 & 2 (act 0, 1): No dynamic events
            // ROM: LevEvents_MTZ just rts
            return;
        }

        // Act 3 (act 2): Boss arena setup
        // ROM: LevEvents_MTZ3 (s2.asm:20488-20556)
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0 (s2.asm:20494-20505): Wait for camera X >= $2530
                // ROM: Adjusts bottom boundary before boss area
                if (camera().getX() >= 0x2530) {
                    // ROM: move.w #$500,(Camera_Max_Y_pos).w
                    camera().setMaxY((short) 0x500);
                    // ROM: move.w #$450,(Camera_Max_Y_pos_target).w
                    camera().setMaxYTarget((short) 0x450);
                    setSidekickBounds(null, null, 0x450);
                    eventRoutine += 2;
                }
            }
            case 2 -> {
                // Routine 2 (s2.asm:20508-20519): Wait for camera X >= $2980
                // ROM: Lock minX to prevent backtracking, lower max Y target
                if (camera().getX() >= 0x2980) {
                    // ROM: move.w (Camera_X_pos).w,(Camera_Min_X_pos).w
                    camera().setMinX(camera().getX());
                    // ROM: move.w #$400,(Camera_Max_Y_pos_target).w
                    camera().setMaxYTarget((short) 0x400);
                    setSidekickBounds((int) camera().getX(), null, 0x400);
                    eventRoutine += 2;
                }
            }
            case 4 -> {
                // Routine 4 (s2.asm:20522-20540): Wait for camera X >= $2A80
                // ROM: Lock camera horizontally at $2AB0 for boss arena
                if (camera().getX() >= 0x2A80) {
                    // ROM: move.w #$2AB0,(Camera_Min_X_pos).w
                    //      move.w #$2AB0,(Camera_Max_X_pos).w
                    camera().setMinX((short) 0x2AB0);
                    camera().setMaxX((short) 0x2AB0);
                    setSidekickBounds(0x2AB0, 0x2AB0, null);
                    eventRoutine += 2;
                    // ROM: move.w #$E2,d0 / jsr (PlayMusic).l  (MusID_FadeOut)
                    audio().fadeOutMusic();
                    // ROM: clr.b (Boss_spawn_delay).w
                    bossSpawnDelay = 0;
                    // ROM: move.b #7,(Current_Boss_ID).w
                    gameState().setCurrentBossId(MTZ_BOSS_ID);
                    // ROM: moveq #PLCID_MtzBoss,d0 / jsr (LoadPLC).l
                    requestSonic2Plc(Sonic2Constants.PLC_MTZ_BOSS);
                }
            }
            case 6 -> {
                // Routine 6 (s2.asm:20543-20551): Lock floor, count spawn delay, spawn boss
                // ROM: cmpi.w #$400,(Camera_Y_pos).w / blo.s +
                //      move.w #$400,(Camera_Min_Y_pos).w
                if (camera().getY() >= 0x400) {
                    // ROM LevEvents_MTZ3_Routine4 (s2.asm:20537-20540):
                    //   cmpi.w #$400,(Camera_Y_pos).w / blo.s +
                    //   move.w #$400,(Camera_Min_Y_pos).w
                    //   move.w #$400,(Tails_Min_Y_pos).w
                    camera().setMinY((short) 0x400);
                    setSidekickBounds(null, null, 0x400, null);
                }
                // ROM: addq.b #1,(Boss_spawn_delay).w
                //      cmpi.b #$5A,(Boss_spawn_delay).w / blo.s ++
                bossSpawnDelay++;
                if (bossSpawnDelay >= 0x5A) {
                    // ROM: AllocateObject + load obj54 (MTZ boss)
                    spawnMTZBoss();
                    eventRoutine += 2;
                    // ROM: move.w #$8C,d0 / jsr (PlayMusic).l  (MusID_Boss)
                    audio().playMusic(Sonic2Music.BOSS.id);
                }
            }
            case 8 -> {
                // ROM LevEvents_MTZ3_Routine5 (s2.asm:20542-20544):
                //   move.w (Camera_X_pos).w,(Camera_Min_X_pos).w
                //   rts
                // A single, UNCONDITIONAL write of Camera_Min_X_pos = Camera_X_pos.
                // There is no guard (min-X may follow the camera back) and no Tails
                // bound write in this routine. During the boss the camera is pinned at
                // $2AB0 (locked in Routine2), so in real play this is effectively a no-op.
                camera().setMinX(camera().getX());
            }
            default -> {
                // No more routines
            }
        }
    }

    /**
     * Spawn MTZ boss (Obj54) at ROM coordinates ($2B50, $380).
     * ROM: AllocateObject -> ObjID_MTZBoss at init position
     */
    private void spawnMTZBoss() {
        var objectManager = levelManager() != null ? levelManager().getObjectManager() : null;
        if (objectManager == null) {
            return;
        }
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x2B50, 0x380, Sonic2ObjectIds.MTZ_BOSS, 0, 0, false, 0);
        // ROM Obj54 is already the current SST object before Obj54_Init allocates
        // the laser shooter and Obj53 orb parent (docs/s2disasm/s2.asm:67224-67240).
        objectManager.createDynamicObjectWithReservedSlot(
                () -> new Sonic2MTZBossInstance(bossSpawn));
    }
}
