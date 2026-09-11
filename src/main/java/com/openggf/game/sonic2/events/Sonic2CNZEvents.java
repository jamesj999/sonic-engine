package com.openggf.game.sonic2.events;

import com.openggf.game.GameServices;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.Sonic2CNZBossInstance;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.objects.ObjectSpawn;

import java.util.logging.Logger;

/**
 * Casino Night Zone events.
 * ROM: LevEvents_CNZ (s2.asm:21479-21570)
 *
 * Act 1: No dynamic events (just SlotMachine call)
 * Act 2: Boss arena - camera lock and boss spawn
 *
 * CNZ2 boss arena triggers:
 * - Routine 0: Camera X >= $27C0 -> lock minX, set maxY target
 * - Routine 2: Camera X >= $2890 -> lock arena, load palette, fade music
 * - Routine 4: Lock minY, spawn boss after delay, play boss music
 * - Routine 6: Prevent backtracking during fight
 */
public class Sonic2CNZEvents extends Sonic2ZoneEvents {
    private static final Logger LOGGER = Logger.getLogger(Sonic2CNZEvents.class.getName());
    private Sonic2CNZBossInstance cnzBoss;
    // CNZ Act 2 boss arena wall positions (for removal after defeat)
    // Layout offset calculation: offset / layoutWidth = y, offset % layoutWidth = x
    // ROM uses 256-wide layout, but we store the calculated x,y for flexibility
    // Note: Names reflect actual X positions (lower X = left, higher X = right)
    // - $C50 (x=80) is the LEFT wall (lower pixel position: 80 * 128 = 0x2800)
    // - $C54 (x=84) is the RIGHT wall (higher pixel position: 84 * 128 = 0x2A00)
    private int cnzLeftWallX = -1;
    private int cnzLeftWallY = -1;
    private int cnzRightWallX = -1;
    private int cnzRightWallY = -1;

    public Sonic2CNZEvents() {
    }

    @Override
    public void init(int act) {
        super.init(act);
        cnzBoss = null;
        cnzLeftWallX = -1;
        cnzLeftWallY = -1;
        cnzRightWallX = -1;
        cnzRightWallY = -1;
    }

    @Override
    public void update(int act, int frameCounter) {
        retryPendingPlc();
        if (act == 0) {
            // Act 1: No dynamic events (ROM: LevEvents_CNZ just calls SlotMachine and returns)
            return;
        }

        // Act 2: Boss arena setup
        // ROM: LevEvents_CNZ2 (s2.asm:21486-21570)
        switch (eventRoutine) {
            case 0 -> {
                // Routine 1 (s2.asm:21500-21517): Wait for camera X >= $27C0
                if (camera().getX() >= 0x27C0) {
                    // ROM: Set minX to current camera X (prevent backtracking)
                    camera().setMinX(camera().getX());
                    // ROM: Set maxY TARGET to $62E (initial arena height - allows access to floor)
                    // This gets tightened to $5D0 later in routine 4 once fight starts
                    camera().setMaxYTarget((short) 0x62E);
                    setSidekickBounds((int) camera().getX(), null, 0x62E);
                    eventRoutine += 2;
                }
            }
            case 2 -> {
                // Routine 2 (s2.asm:21520-21538): Wait for camera X >= $2890, lock arena
                if (camera().getX() >= 0x2890) {
                    // ROM: Lock camera X boundaries for boss arena
                    camera().setMinX((short) 0x2860);
                    camera().setMaxX((short) 0x28E0);
                    setSidekickBounds(0x2860, 0x28E0, null);
                    eventRoutine += 2;
                    bossSpawnDelay = 0;
                    // ROM: Fade out music
                    audio().fadeOutMusic();
                    // ROM: Set Current_Boss_ID to 6 (CNZ boss ID in BossCollision_Index)
                    gameState().setCurrentBossId(6);
                    requestSonic2Plc(Sonic2Constants.PLC_CNZ_BOSS);

                    // ROM: Load CNZ boss palette (Pal_CNZ_B to palette line 1)
                    // This palette contains the electricity effect colors
                    loadBossPalette(1, Sonic2Constants.PAL_CNZ_BOSS_ADDR);

                    // Place boss arena walls by modifying level layout (ROM-accurate)
                    // ROM writes block $F9 (solid wall) to layout offsets $C54 and $C50
                    placeCNZArenaWalls();
                }
            }
            case 4 -> {
                // Routine 4 (s2.asm:21541-21558): Lock floor and spawn boss
                // ROM: Set minY when camera Y reaches $4E0
                if (camera().getY() >= 0x4E0) {
                    camera().setMinY((short) 0x4E0);
                }
                // ROM: Increment delay every frame, spawn boss at $5A (90) frames
                bossSpawnDelay++;
                if (bossSpawnDelay >= 0x5A) {
                    spawnCNZBoss();
                    eventRoutine += 2;
                    // Start boss music
                    audio().playMusic(Sonic2Music.BOSS.id);
                }
            }
            case 6 -> {
                // Routine 6 (s2.asm:21561-21570): Prevent backtracking during boss fight
                // ROM: When camera X >= $2A00, adjust camera bounds
                if (camera().getX() >= 0x2A00) {
                    camera().setMaxYTarget((short) 0x5D0);
                    // ROM: move.w (Camera_X_pos).w,(Camera_Min_X_pos).w
                    short cameraX = camera().getX();
                    if (cameraX > camera().getMinX()) {
                        camera().setMinX(cameraX);
                    }
                    syncSidekickBoundsToCamera();
                }
            }
            default -> {
                // No more routines
            }
        }
    }

    /**
     * Called by Sonic2CNZBossInstance when the CNZ boss is defeated.
     * ROM timing: Called during transition from defeat exploding to defeat bounce phase.
     */
    public void onBossDefeated() {
        removeCNZArenaWalls();
    }

    // ---- Rewind accessors ----
    public int getCnzLeftWallX()        { return cnzLeftWallX; }
    public void setCnzLeftWallX(int v)  { cnzLeftWallX = v; }
    public int getCnzLeftWallY()        { return cnzLeftWallY; }
    public void setCnzLeftWallY(int v)  { cnzLeftWallY = v; }
    public int getCnzRightWallX()       { return cnzRightWallX; }
    public void setCnzRightWallX(int v) { cnzRightWallX = v; }
    public int getCnzRightWallY()       { return cnzRightWallY; }
    public void setCnzRightWallY(int v) { cnzRightWallY = v; }
    // ---- end rewind accessors ----

    public boolean isBossArenaActive() {
        return eventRoutine >= 4;
    }

    public boolean isBossSpawnPending() {
        return eventRoutine >= 4 && cnzBoss == null;
    }

    public boolean isBossSpawned() {
        return cnzBoss != null;
    }

    public boolean isLeftArenaWallPlaced() {
        return cnzLeftWallX >= 0 && cnzLeftWallY >= 0;
    }

    public boolean isRightArenaWallPlaced() {
        return cnzRightWallX >= 0 && cnzRightWallY >= 0;
    }

    /**
     * Spawns the CNZ Act 2 boss.
     * ROM: Creates Object 0x51 via JmpTo_AllocateObject in LevEvents_CNZ2_Routine3
     * The boss object handles its own initial positioning.
     */
    private void spawnCNZBoss() {
        // ROM spawns obj51 with no specific coordinates - the object positions itself
        // CNZ boss initial position from Sonic2CNZBossInstance: (0x2A46, 0x654)
        ObjectSpawn bossSpawn = new ObjectSpawn(
                0x2A46, 0x654, Sonic2ObjectIds.CNZ_BOSS, 0, 0, false, 0);
        cnzBoss = new Sonic2CNZBossInstance(bossSpawn, this);
        spawnObject(cnzBoss);
    }

    /**
     * Places CNZ Act 2 boss arena walls by modifying the level layout.
     * ROM: LevEvents_CNZ2 modifies Level_Layout directly with block $F9 (solid wall).
     * <p>
     * From docs/s2disasm/s2.asm (LevEvents_CNZ2):
     * - Routine 1 (line 21509): move.b #$F9,(Level_Layout+$C54).w  ; left wall
     * - Routine 2 (line 21523): move.b #$F9,(Level_Layout+$C50).w  ; right wall
     * <p>
     * Layout offset calculation (256 tiles wide):
     * - Offset $C54 (3156): x = 3156 % 256 = 84, y = 3156 / 256 = 12
     * - Offset $C50 (3152): x = 3152 % 256 = 80, y = 3152 / 256 = 12
     */
    private void placeCNZArenaWalls() {
        Level level = levelManager().getCurrentLevel();
        if (level == null) {
            return;
        }
        Map map = level.getMap();
        if (map == null) {
            return;
        }

        // ROM layout offsets for CNZ boss arena walls
        // Block $F9 = solid wall block
        final int WALL_BLOCK = 0xF9;
        final int LAYOUT_WIDTH = 256;  // ROM layout width

        // Left wall: offset $C50 = 3152 (x=80, lower X position)
        final int LEFT_OFFSET = 0xC50;
        cnzLeftWallX = LEFT_OFFSET % LAYOUT_WIDTH;   // 80
        cnzLeftWallY = LEFT_OFFSET / LAYOUT_WIDTH;   // 12

        // Right wall: offset $C54 = 3156 (x=84, higher X position)
        final int RIGHT_OFFSET = 0xC54;
        cnzRightWallX = RIGHT_OFFSET % LAYOUT_WIDTH; // 84
        cnzRightWallY = RIGHT_OFFSET / LAYOUT_WIDTH; // 12

        mutationPipeline().queue(context -> {
            try {
                context.surface().setBlockInMap(0, cnzLeftWallX, cnzLeftWallY, WALL_BLOCK);
                return context.surface().setBlockInMap(0, cnzRightWallX, cnzRightWallY, WALL_BLOCK);
            } catch (IllegalArgumentException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "CNZ wall placement failed", e);
                return MutationEffects.NONE;
            }
        });
    }

    /**
     * Removes CNZ Act 2 boss arena wall after defeat.
     * ROM: After boss defeat, removes the RIGHT wall (offset $C54) with block $DD (empty).
     * <p>
     * From docs/s2disasm/s2.asm (line 66296):
     * - move.b #$DD,(Level_Layout+$C54).w
     * <p>
     * This allows Sonic to exit the arena to the right after defeating the boss.
     */
    private void removeCNZArenaWalls() {
        Level level = levelManager().getCurrentLevel();
        if (level == null || cnzRightWallX < 0) {
            return;
        }
        Map map = level.getMap();
        if (map == null) {
            return;
        }

        // ROM: Removes right wall (offset $C54, x=84) with block $DD after defeat
        final int EMPTY_BLOCK = 0xDD;

        mutationPipeline().queue(context -> {
            try {
                return context.surface().setBlockInMap(0, cnzRightWallX, cnzRightWallY, EMPTY_BLOCK);
            } catch (IllegalArgumentException e) {
                return MutationEffects.NONE;
            }
        });
    }
}
