package com.openggf.game.sonic1.events;

import com.openggf.game.sonic1.objects.bosses.Sonic1MZBossInstance;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Marble Zone dynamic level events.
 * ROM: DLE_MZ (DynamicLevelEvents.asm)
 *
 * Act 1: Complex 4-routine state machine adjusting top and bottom boundaries.
 * Act 2: Simple bottom boundary change.
 * Act 3: Boss arena with 2-routine state machine.
 */
class Sonic1MZEvents extends Sonic1ZoneEvents {
    private static final int BOSS_MZ_X = 0x1800;
    private static final int BOSS_MZ_Y = 0x210;

    Sonic1MZEvents() {
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
     * DLE_MZ1: 4-routine state machine (off_6FB2).
     * Dispatches to routines 0, 2, 4, 6 based on eventRoutine.
     */
    private void updateAct1() {
        switch (eventRoutine) {
            case 0 -> updateAct1Routine0(); // loc_6FBA
            case 2 -> updateAct1Routine2(); // loc_6FEA
            case 4 -> updateAct1Routine4(); // loc_702E
            case 6 -> updateAct1Routine6(); // loc_7050
        }
    }

    /**
     * loc_6FBA: Initial routine - progressive bottom boundary changes
     * as camera moves right. Advances to routine 2 when camera Y >= 0x340.
     */
    private void updateAct1Routine0() {
        int camX = camera().getX() & 0xFFFF;
        int camY = camera().getY() & 0xFFFF;

        // v_limitbtm1 = 0x1D0
        camera().setMaxYTarget((short) 0x1D0);
        if (camX < 0x700) {
            return; // locret_6FE8
        }

        // v_limitbtm1 = 0x220
        camera().setMaxYTarget((short) 0x220);
        if (camX < 0xD00) {
            return; // locret_6FE8
        }

        // v_limitbtm1 = 0x340
        camera().setMaxYTarget((short) 0x340);
        if (camY < 0x340) {
            return; // locret_6FE8
        }

        // Camera Y >= 0x340, advance to routine 2
        eventRoutine += 2;
    }

    /**
     * loc_6FEA: Second routine - can revert to routine 0 if camera goes
     * back above Y 0x340. When in lower area, manages both top and bottom
     * boundaries based on X position. Advances to routine 4 when Y >= 0x370.
     */
    private void updateAct1Routine2() {
        int camX = camera().getX() & 0xFFFF;
        int camY = camera().getY() & 0xFFFF;

        // Check if camera went back up above 0x340
        if (camY < 0x340) {
            // subq.b #2,(v_dle_routine) - revert to routine 0
            eventRoutine -= 2;
            return;
        }

        // loc_6FF8: Camera Y >= 0x340
        // v_limittop2 = 0 (immediate)
        camera().setMinY((short) 0);

        if (camX >= 0xE00) {
            return; // locret_702C
        }

        // v_limittop2 = 0x340 (immediate)
        camera().setMinY((short) 0x340);
        // v_limitbtm1 = 0x340
        camera().setMaxYTarget((short) 0x340);

        if (camX >= 0xA90) {
            return; // locret_702C
        }

        // v_limitbtm1 = 0x500
        camera().setMaxYTarget((short) 0x500);

        if (camY < 0x370) {
            return; // locret_702C
        }

        // Camera Y >= 0x370, advance to routine 4
        eventRoutine += 2;
    }

    /**
     * loc_702E: Third routine - can revert to routine 2 if camera goes
     * back above Y 0x370. Advances to routine 6 when Y >= 0x500 and X >= 0xB80.
     */
    private void updateAct1Routine4() {
        int camX = camera().getX() & 0xFFFF;
        int camY = camera().getY() & 0xFFFF;

        // Check if camera went back up above 0x370
        if (camY < 0x370) {
            // subq.b #2,(v_dle_routine) - revert to routine 2
            eventRoutine -= 2;
            return;
        }

        // loc_703C: Camera Y >= 0x370
        if (camY < 0x500) {
            return; // locret_704E
        }

        // REV01: also require X >= 0xB80
        if (camX < 0xB80) {
            return; // locret_704E
        }

        // v_limittop2 = 0x500 (immediate)
        camera().setMinY((short) 0x500);
        // Advance to routine 6
        eventRoutine += 2;
    }

    /**
     * loc_7050: Fourth routine (REV01) - complex top boundary management.
     * When X < 0xB80, eases top boundary down toward 0x340 (2 pixels/frame).
     * When X >= 0xB80, snaps top to 0x500 if camera Y is there, then
     * opens top boundary at X >= 0xE70 and adjusts bottom for end of act.
     */
    private void updateAct1Routine6() {
        int camX = camera().getX() & 0xFFFF;
        int camY = camera().getY() & 0xFFFF;
        int currentMinY = camera().getMinY() & 0xFFFF;

        if (camX < 0xB80) {
            // locj_76B8 not taken: ease top boundary toward 0x340
            if (currentMinY == 0x340) {
                return; // locret_7072 (already at target)
            }
            // subq.w #2,(v_limittop2) - ease top boundary down by 2 per frame
            camera().setMinY((short) (camera().getMinY() - 2));
            return;
        }

        // locj_76B8: Camera X >= 0xB80
        if (currentMinY != 0x500) {
            // Top boundary not yet at 0x500
            if (camY < 0x500) {
                return; // locret_7072
            }
            // Snap top to 0x500
            camera().setMinY((short) 0x500);
        }

        // locj_76CE: Top boundary is at 0x500
        if (camX < 0xE70) {
            return; // locret_7072
        }

        // v_limittop2 = 0 (open top boundary)
        camera().setMinY((short) 0);
        // v_limitbtm1 = 0x500
        camera().setMaxYTarget((short) 0x500);

        if (camX < 0x1430) {
            return; // locret_7072
        }

        // v_limitbtm1 = 0x210 (end of act area)
        camera().setMaxYTarget((short) 0x210);
    }

    /**
     * DLE_MZ2: Simple two-zone boundary.
     * Bottom boundary is 0x520 until camera X >= 0x1700, then 0x200.
     */
    private void updateAct2() {
        int camX = camera().getX() & 0xFFFF;

        // v_limitbtm1 = 0x520
        camera().setMaxYTarget((short) 0x520);
        if (camX < 0x1700) {
            return; // locret_7088
        }

        // v_limitbtm1 = 0x200
        camera().setMaxYTarget((short) 0x200);
    }

    /**
     * DLE_MZ3: Boss sequence state machine with 2 routines.
     * Dispatches to routines 0, 2 based on eventRoutine.
     */
    private void updateAct3() {
        switch (eventRoutine) {
            case 0 -> updateAct3Boss();   // DLE_MZ3boss
            case 2 -> updateAct3End();    // DLE_MZ3end
        }
    }

    /**
     * DLE_MZ3boss (routine 0): Pre-boss boundary logic.
     * Progressive bottom boundary changes as camera approaches boss area.
     * Locks camera and advances to routine 2 at boss_mz_x - 0x10.
     */
    private void updateAct3Boss() {
        int camX = camera().getX() & 0xFFFF;

        // v_limitbtm1 = 0x720
        camera().setMaxYTarget((short) 0x720);
        if (camX < BOSS_MZ_X - 0x2A0) { // 0x1560
            return; // locret_70E8
        }

        // v_limitbtm1 = boss_mz_y (0x210)
        camera().setMaxYTarget((short) BOSS_MZ_Y);
        // ROM threshold: boss_mz_x-$10 (0x17F0).
        if (camX < BOSS_MZ_X - 0x10) { // 0x17F0
            return; // locret_70E8
        }

        // ROM: Spawn boss object at boss_mz_x + $1F0, boss_mz_y + $1C
        LevelManager lm = levelManager();
        if (lm != null && lm.getObjectManager() != null) {
            ObjectSpawn bossSpawn = new ObjectSpawn(
                    BOSS_MZ_X + 0x1F0, BOSS_MZ_Y + 0x1C,
                    Sonic1ObjectIds.MZ_BOSS, 0, 0, false, 0);
            spawnObject(() -> new Sonic1MZBossInstance(bossSpawn));
        }

        // ROM: bgm_Boss — play boss music
        audio().playMusic(Sonic1Music.BOSS.id);
        requestSonic1Plc(17);

        // f_lockscreen = 1.
        // ROM: f_lockscreen limits Sonic's movement range (01 Sonic.asm:824-834) but
        // does NOT freeze the camera(). MoveScreenHoriz still runs, clamped to v_limitright2
        // ($1800 from LevelSizeArray). DLE_MZ3end ratchets v_limitleft2 forward each
        // frame. Camera settles at v_limitright2 naturally with no visible snap.
        // The Java equivalent is isBossFightActive() gating doLevelBoundary's RIGHT_EXTRA.
        gameState().setCurrentBossId(Sonic1ObjectIds.MZ_BOSS);
        eventRoutine += 2; // advance to DLE_MZ3end
    }

    /**
     * DLE_MZ3end (routine 2): Post-boss lock.
     * Locks left boundary to current camera X position.
     */
    private void updateAct3End() {
        // v_limitleft2 = v_screenposx
        camera().setMinX(camera().getX());
    }
}
