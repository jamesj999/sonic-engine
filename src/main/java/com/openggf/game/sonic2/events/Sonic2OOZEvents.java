package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.OilSurfaceManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.Sonic2OOZBossInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Oil Ocean Zone events.
 * ROM: LevEvents_OOZ (s2.asm:20938-21035)
 * Also handles oil surface (Obj07) and oil slides (OilSlides routine).
 */
public class Sonic2OOZEvents extends Sonic2ZoneEvents {
    private static final int APPROACH_TRIGGER_X = 0x2668;
    private static final int ARENA_LOCK_X = 0x2880;
    private static final int ARENA_MAX_X = 0x28C0;
    private static final int ARENA_OIL_Y = 0x02D8;
    private static final int ARENA_MAX_Y_TARGET = 0x01E0;
    private static final int ARENA_MIN_Y = 0x01D8;
    private static final int SPAWN_DELAY = 0x5A;
    private static final int CURRENT_BOSS_ID_OOZ = 8;

    private OilSurfaceManager oilManager;
    private Sonic2OOZBossInstance oozBoss;

    public Sonic2OOZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        oilManager = new OilSurfaceManager();
        oozBoss = null;
    }

    @Override
    public void updatePrePhysics(int act, int frameCounter) {
        if (oilManager == null) {
            return;
        }
        // ROM OilSlides runs in NonWaterEffects, BEFORE RunObjects executes the
        // player object (docs/s2disasm/s2.asm:5301,5537-5642). Running it here
        // (pre-physics) lets the sliding status bit gate the same frame's player
        // friction/move and makes the OilSlides_Chunks lookup read the previous
        // frame's player position, exactly as the ROM does. Running it post-
        // physics applied oil friction one frame early (OOZ1 trace f563).
        //
        // ROM processes both Player 1 and Player 2 every frame; mirror that for
        // the camera-focused character and every registered sidekick.
        ObjectPlayerQuery playerQuery = playerQueryFromRuntime();
        for (PlayableEntity participant :
                playerQuery.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (participant instanceof AbstractPlayableSprite playable) {
                oilManager.updateSlides(playable);
            }
        }
    }

    @Override
    public void updateReservedObjectSlots(int act, int frameCounter) {
        if (oilManager == null) {
            return;
        }
        // ROM Obj07_Main (oil surface) runs during object processing, after the
        // player object (docs/s2disasm/s2.asm:49659-49749). Its SST is the
        // RESERVED slot `Oil` (aliased with WaterSurface1), which sits between
        // the player objects and Dynamic_Object_RAM
        // (docs/s2disasm/s2.constants.asm:1131-1137) - so it executes before
        // every dynamic object and, crucially, before Tails_Tails (Obj05) in
        // LevelOnly_Object_RAM (docs/s2disasm/s2.constants.asm:1144-1152).
        // Obj05 reads `anim(a2)` at its own late execution point
        // (docs/s2disasm/s2.asm:41735), so the Walk animation this landing
        // writes via Sonic_ResetOnFloor_Part2 (docs/s2disasm/s2.asm:37780-37786)
        // must already be visible to it. Running the oil surface in the
        // post-camera level-event pass instead left Obj05 reading the stale Roll
        // anim on the landing frame and minted a directional DPLC transfer the
        // ROM never queues (OOZ1 trace f346, OOZ2 f10684).
        ObjectPlayerQuery playerQuery = playerQueryFromRuntime();
        for (PlayableEntity participant :
                playerQuery.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (participant instanceof AbstractPlayableSprite playable) {
                oilManager.updateSurface(playable);
            }
        }
        // Re-arm the per-frame slide cadence for the next frame's pre-physics pass.
        oilManager.endFrame();
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        updateBossEvents(act);
    }

    private void updateBossEvents(int act) {
        if (act == 0) {
            return;
        }

        switch (eventRoutine) {
            case 0 -> {
                if (camera().getX() >= APPROACH_TRIGGER_X) {
                    camera().setMinX(camera().getX());
                    // ROM LevEvents_OOZ2_Routine1 writes (Oil+y_pos).w = $2D8
                    // when the boss approach starts (docs/s2disasm/s2.asm:21343-21349).
                    oilManager.setOilSurfaceY(ARENA_OIL_Y);
                    camera().setMaxYTarget((short) ARENA_MAX_Y_TARGET);
                    setSidekickBounds((int) camera().getX(), null, ARENA_MAX_Y_TARGET);
                    eventRoutine += 2;
                }
            }
            case 2 -> {
                if (camera().getX() >= ARENA_LOCK_X) {
                    camera().setMinX((short) ARENA_LOCK_X);
                    camera().setMaxX((short) ARENA_MAX_X);
                    setSidekickBounds(ARENA_LOCK_X, ARENA_MAX_X, ARENA_MAX_Y_TARGET);
                    eventRoutine += 2;
                    bossSpawnDelay = 0;
                    audio().fadeOutMusic();
                    gameState().setCurrentBossId(CURRENT_BOSS_ID_OOZ);
                    requestSonic2Plc(Sonic2Constants.PLC_OOZ_BOSS);
                    loadOOZBossPalette();
                }
            }
            case 4 -> {
                if (camera().getY() >= ARENA_MIN_Y) {
                    camera().setMinY((short) ARENA_MIN_Y);
                    setSidekickBounds(ARENA_LOCK_X, ARENA_MAX_X, ARENA_MIN_Y, ARENA_MAX_Y_TARGET);
                }
                bossSpawnDelay++;
                if (bossSpawnDelay >= SPAWN_DELAY) {
                    spawnOOZBoss();
                    eventRoutine += 2;
                    audio().playMusic(Sonic2Music.BOSS.id);
                }
            }
            case 6 -> {
                if (oozBoss != null && oozBoss.hasRaisedBossDefeatedFlag()) {
                    camera().setMinX(camera().getX());
                    syncSidekickBoundsToCamera();
                }
            }
            default -> {
            }
        }
    }

    private void spawnOOZBoss() {
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x2940, 0x02D0, Sonic2ObjectIds.OOZ_BOSS, 0, 0, false, 0);
        oozBoss = new Sonic2OOZBossInstance(bossSpawn);
        spawnObject(oozBoss);
    }

    protected void loadOOZBossPalette() {
        loadBossPalette(1, Sonic2Constants.PAL_OOZ_BOSS_ADDR);
    }

    private ObjectPlayerQuery playerQueryFromRuntime() {
        AbstractPlayableSprite focusedPlayer = camera().getFocusedSprite();
        List<AbstractPlayableSprite> sidekicks = List.copyOf(spriteManager().getSidekicks());
        return new ObjectPlayerQuery(
                () -> focusedPlayer,
                () -> sidekicks);
    }
}
