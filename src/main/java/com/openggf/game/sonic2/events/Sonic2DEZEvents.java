package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.objects.bosses.Sonic2MechaSonicInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectSpawn;

import java.util.logging.Logger;

/**
 * Death Egg Zone events.
 * ROM: LevEvents_DEZ (s2.asm:21661-21724)
 *
 * DEZ has two sequential boss fights in a single act:
 *   Routine 0: Camera X >= $140 -> spawn Silver Sonic (ObjAF) + load PLCID_FieryExplosion
 *   Routine 2: Silver Sonic fight in progress (empty - boss advances this on defeat)
 *   Routine 4: Push Camera_Min_X forward; Camera X >= $300 -> load PLCID_DezBoss
 *   Routine 6: Push Camera_Min_X forward; Camera X >= $680 locks camera for Death Egg Robot
 *   Routine 8: Death Egg Robot fight in progress (empty)
 *
 * Note: boss_id and music fade are handled by the boss object itself (ObjAF routine 2),
 * not by the event routines. The ROM sets boss_id=9 and fades music when Camera_X >= $224,
 * which is checked in ObjAF's WAIT_CAMERA routine.
 */
public class Sonic2DEZEvents extends Sonic2ZoneEvents {
    private Sonic2MechaSonicInstance silverSonic;

    public Sonic2DEZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        silverSonic = null;
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        // DEZ has only one act in the ROM (act index 0)
        switch (eventRoutine) {
            case 0 -> {
                // Routine 0 (s2.asm LevEvents_DEZ_Routine1):
                // ROM: cmp.w #$140,(Camera_X_pos).w / bhi.s
                if (camera().getX() >= 0x140) {
                    // Advance routine
                    eventRoutine += 2;
                    // Spawn Silver Sonic (ObjAF) at ($348, $A0) subtype $48
                    spawnSilverSonic();
                    // ROM: moveq #PLCID_FieryExplosion,d0 / jmpto JmpTo2_LoadPLC
                    requestSonic2Plc(Sonic2Constants.PLC_FIERY_EXPLOSION);
                }
            }
            case 2 -> {
                // Routine 2 (s2.asm LevEvents_DEZ_Routine2): rts
                // Empty - boss defeat handler advances Dynamic_Resize_Routine
            }
            case 4 -> {
                // Routine 4 (s2.asm LevEvents_DEZ_Routine3):
                // ROM: Push Camera_Min_X forward (prevent backtracking)
                camera().setMinX(camera().getX());
                // Wait for Camera_X >= $300
                if (camera().getX() >= 0x300) {
                    eventRoutine += 2;
                    // ROM: moveq #PLCID_DezBoss,d0 / jmpto JmpTo2_LoadPLC
                    requestSonic2Plc(Sonic2Constants.PLC_DEZ_BOSS);
                }
            }
            case 6 -> {
                // Routine 6 (s2.asm LevEvents_DEZ_Routine4):
                // ROM: Push Camera_Min_X forward
                camera().setMinX(camera().getX());
                // Wait for Camera_X >= $680
                if (camera().getX() >= 0x680) {
                    eventRoutine += 2;
                    // Lock camera for Death Egg Robot arena
                    // ROM: move.w d0,(Camera_Min_X_pos).w
                    camera().setMinX((short) 0x680);
                    // ROM: addi.w #$C0,d0 -> move.w d0,(Camera_Max_X_pos).w
                    camera().setMaxX((short) (0x680 + 0xC0));
                }
            }
            case 8 -> {
                // Routine 8 (s2.asm LevEvents_DEZ_Routine5): rts
                // Death Egg Robot fight in progress (empty)
            }
            default -> {
                // No more routines
            }
        }
    }

    /**
     * Spawns the Silver Sonic / Mecha Sonic boss.
     * ROM: Creates Object 0xAF at coordinates ($348, $A0) with subtype $48
     */
    private void spawnSilverSonic() {
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x348, 0xA0, Sonic2ObjectIds.MECHA_SONIC, 0x48, 0, false, 0);
        silverSonic = spawnObject(() -> new Sonic2MechaSonicInstance(bossSpawn));
    }

    /**
     * Get the DEZ event routine for external access (e.g., by the boss defeat handler).
     */
    public int getDEZEventRoutine() {
        return eventRoutine;
    }
}
