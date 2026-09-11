package com.openggf.game.sonic2.events;

import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.objects.bosses.Sonic2CPZBossInstance;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Chemical Plant Zone events.
 * ROM: LevEvents_CPZ (s2.asm:20504-20542)
 *
 * Act 1: No dynamic events
 * Act 2: Water (Mega Mack) rises when player reaches trigger X coordinate
 */
public class Sonic2CPZEvents extends Sonic2ZoneEvents {
    private boolean cpzWaterTriggered;
    private Sonic2CPZBossInstance cpzBoss;

    public Sonic2CPZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        cpzWaterTriggered = false;
        cpzBoss = null;
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        if (act != 1) {
            // Only Act 2 has water rise events
            return;
        }
        updateCPZWaterRise();
        updateCPZBossEvents();
    }

    public boolean isCpzWaterTriggered()       { return cpzWaterTriggered; }
    public void setCpzWaterTriggered(boolean v){ cpzWaterTriggered = v; }

    private void updateCPZWaterRise() {
        if (cpzWaterTriggered) {
            return;
        }
        final int ZONE_ID_CPZ_ROM = 0x0D;
        final int WATER_RISE_TRIGGER_X = 0x1E80;
        final int WATER_TARGET_Y = 0x510;
        var player = camera().getFocusedSprite();
        if (player != null && player.getCentreX() >= WATER_RISE_TRIGGER_X) {
            waterSystem().setWaterLevelTarget(
                    ZONE_ID_CPZ_ROM, 1, WATER_TARGET_Y);
            cpzWaterTriggered = true;
        }
    }

    private void updateCPZBossEvents() {
        switch (eventRoutine) {
            case 0 -> {
                if (camera().getX() >= 0x2680) {
                    camera().setMinX(camera().getX());
                    camera().setMaxYTarget((short) 0x450);
                    setSidekickBounds((int) camera().getX(), null, 0x450);
                    eventRoutine += 2;
                }
            }
            case 2 -> {
                if (camera().getX() >= 0x2A20) {
                    // ROM locks camera completely at X=0x2A20 for the entire fight
                    camera().setMinX((short) 0x2A20);
                    camera().setMaxX((short) 0x2A20);
                    setSidekickBounds(0x2A20, 0x2A20, null);
                    eventRoutine += 2;
                    bossSpawnDelay = 0;
                    audio().fadeOutMusic();
                    gameState().setCurrentBossId(1);
                    requestSonic2Plc(Sonic2Constants.PLC_CPZ_BOSS);
                }
            }
            case 4 -> {
                if (camera().getY() >= 0x448) {
                    camera().setMinY((short) 0x448);
                }
                bossSpawnDelay++;
                if (bossSpawnDelay >= 0x5A) {
                    spawnCPZBoss();
                    eventRoutine += 2;
                    audio().playMusic(Sonic2Music.BOSS.id);
                }
            }
            case 6 -> {
                // Prevent backtracking - minX can only increase, never decrease
                // ROM: move.w (Camera_X_pos).w,(Camera_Min_X_pos).w
                short cameraX = camera().getX();
                if (cameraX > camera().getMinX()) {
                    camera().setMinX(cameraX);
                }
                syncSidekickBoundsToCamera();
            }
            default -> {
            }
        }
    }

    private void spawnCPZBoss() {
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x2B80, 0x04B0, Sonic2ObjectIds.CPZ_BOSS, 0, 0, false, 0);
        cpzBoss = spawnObject(() -> new Sonic2CPZBossInstance(bossSpawn));
    }
}
