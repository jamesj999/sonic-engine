package com.openggf.game;

import com.openggf.camera.Camera;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

/**
 * Stores checkpoint state for save/restore on player death.
 * <p>
 * Based on the Sonic 2 disassembly's Saved_* variables.
 * </p>
 */
public class CheckpointState implements RespawnState {
    private static final Logger LOGGER = Logger.getLogger(CheckpointState.class.getName());

    private int lastCheckpointIndex = -1;
    // Persistent star-post activation high-water: the highest star-post subtype
    // ever activated this life. Unlike lastCheckpointIndex (ROM Last_star_post_hit,
    // zeroed on a star-post BONUS entry), this survives that zeroing so a
    // returned-from-bonus star post is still recognised as already-used. Models the
    // ROM per-object respawn bit kept across the reload by Respawn_table_keep. See
    // RespawnState#getStarPostActivationMark.
    private int starPostActivationMark = -1;
    private int savedX;
    private int savedY;
    private int savedCameraX;
    private int savedCameraY;
    private boolean cameraLock;
    private boolean usedForSpecialStage; // Prevents stars from respawning after SS entry

    // Water state (ROM: v_lamp_wtrpos, v_lamp_wtrrout)
    private int savedWaterLevel;
    private int savedWaterRoutine;
    private boolean hasWaterState;
    private int savedCameraMaxY;
    private int savedDynamicResizeRoutine;
    private boolean hasS3kRuntimeState;
    private byte savedTopSolidBit = 0x0C;
    private byte savedLrbSolidBit = 0x0D;
    private boolean hasSolidBits;
    // ROM Saved_Timer / v_lamp_time / Saved_timer: the act timer banked by the
    // star post and reinstated by its load routine, so an act's elapsed time
    // survives a death respawn and a special-stage detour.
    //   S2  save  docs/s2disasm/s2.asm:44743
    //   S1  save  docs/s1disasm/_incObj/79 Lamppost.asm:158
    //   S3K save  docs/skdisasm/sonic3k.asm:61721 (and Saved2 at :61745)
    private long savedTimerFrames;
    private boolean hasSavedTimer;

    public record RewindState(
            int lastCheckpointIndex,
            int starPostActivationMark,
            int savedX,
            int savedY,
            int savedCameraX,
            int savedCameraY,
            boolean cameraLock,
            boolean usedForSpecialStage,
            int savedWaterLevel,
            int savedWaterRoutine,
            boolean hasWaterState,
            int savedCameraMaxY,
            int savedDynamicResizeRoutine,
            boolean hasS3kRuntimeState,
            byte savedTopSolidBit,
            byte savedLrbSolidBit,
            boolean hasSolidBits,
            long savedTimerFrames,
            boolean hasSavedTimer) {}

    /**
     * Clear checkpoint state (called on level start/change).
     */
    public void clear() {
        lastCheckpointIndex = -1;
        starPostActivationMark = -1;
        savedX = 0;
        savedY = 0;
        savedCameraX = 0;
        savedCameraY = 0;
        cameraLock = false;
        usedForSpecialStage = false;
        savedWaterLevel = 0;
        savedWaterRoutine = 0;
        hasWaterState = false;
        savedCameraMaxY = 0;
        savedDynamicResizeRoutine = 0;
        hasS3kRuntimeState = false;
        savedTopSolidBit = 0x0C;
        savedLrbSolidBit = 0x0D;
        hasSolidBits = false;
        savedTimerFrames = 0;
        hasSavedTimer = false;
    }

    /**
     * Save checkpoint state from raw values.
     * Game-agnostic — callers extract position/flags from their game-specific checkpoint object.
     */
    public void saveCheckpoint(int checkpointIndex, int x, int y, boolean cameraLockFlag) {
        this.lastCheckpointIndex = checkpointIndex;
        // Bump the persistent activation high-water (never lowered by a save).
        this.starPostActivationMark = Math.max(this.starPostActivationMark, checkpointIndex);
        this.savedX = x;
        this.savedY = y;
        this.cameraLock = cameraLockFlag;

        Camera camera = GameServices.camera();
        this.savedCameraX = camera.getX();
        this.savedCameraY = camera.getY();
        saveProviderRuntimeStateIfPresent(camera);
        savePlayerSolidBitsIfPresent();
        saveActTimerIfPresent();

        LOGGER.fine("Saved checkpoint " + lastCheckpointIndex + " at (" + savedX + ", " + savedY + ")");
    }

    /**
     * ROM {@code move.l (Timer).w,(Saved_Timer).w} at the star post's save
     * routine -- S2 docs/s2disasm/s2.asm:44743,
     * S1 docs/s1disasm/_incObj/79 Lamppost.asm:158,
     * S3K docs/skdisasm/sonic3k.asm:61721. All three games bank the running act
     * timer alongside position, rings and camera.
     */
    private void saveActTimerIfPresent() {
        LevelState levelState = GameServices.levelOrNull() != null
                ? GameServices.levelOrNull().getLevelGamestate()
                : null;
        if (levelState == null) {
            savedTimerFrames = 0;
            hasSavedTimer = false;
            return;
        }
        savedTimerFrames = levelState.getTimerFrames();
        hasSavedTimer = true;
    }

    /**
     * ROM checkpoint-load timer reinstatement. All three games run the same
     * three instructions:
     *
     * <pre>
     *   move.l (Saved_Timer).w,(Timer).w
     *   move.b #59,(Timer_frame).w
     *   subq.b #1,(Timer_second).w
     * </pre>
     *
     * S2 {@code Obj79_LoadData} docs/s2disasm/s2.asm:44783-44785,
     * S1 docs/s1disasm/_incObj/79 Lamppost.asm:193-195,
     * S3K docs/skdisasm/sonic3k.asm:61776-61778 (and the Saved2 big-ring path
     * at :61803-61805).
     *
     * <p>{@code Timer} is minute/second/frame with {@code Timer_frame} counting
     * UP and rolling at 60 in {@code HudUpdate} (docs/s2disasm/s2.asm:87775-87786),
     * so elapsed frames are {@code minute*3600 + second*60 + frame}. Forcing
     * frame to 59 and rolling the second back one therefore discards the stored
     * partial second and re-ticks it on the very next HUD update: elapsed
     * becomes {@code minute*3600 + second*60 - 1}. (When the stored second is 0
     * the ROM's byte {@code subq} wraps it to 255 and the next HUD tick carries
     * it straight back to 0, which is the same observable minute/second as the
     * clamped expression below.)
     */
    @Override
    public void clearSavedActTimer() {
        savedTimerFrames = 0;
    }

    private void restoreActTimerIfSaved() {
        if (!hasSavedTimer) {
            return;
        }
        com.openggf.level.LevelManager level = GameServices.levelOrNull();
        LevelState levelState = level != null ? level.getLevelGamestate() : null;
        if (levelState == null) {
            return;
        }
        long minutes = savedTimerFrames / 3600;
        long seconds = (savedTimerFrames / 60) % 60;
        long restored = minutes * 3600 + seconds * 60 - 1;
        levelState.setTimerFrames(Math.max(0, restored));
    }

    private void savePlayerSolidBitsIfPresent() {
        Camera camera = GameServices.cameraOrNull();
        if (camera == null || !(camera.getFocusedSprite() instanceof AbstractPlayableSprite playable)) {
            savedTopSolidBit = 0x0C;
            savedLrbSolidBit = 0x0D;
            hasSolidBits = false;
            return;
        }
        savedTopSolidBit = playable.getTopSolidBit();
        savedLrbSolidBit = playable.getLrbSolidBit();
        hasSolidBits = true;
    }

    private void saveProviderRuntimeStateIfPresent(Camera camera) {
        LevelEventProvider eventProvider = GameServices.module().getLevelEventProvider();
        if (camera == null || !(eventProvider instanceof CheckpointRuntimeStateProvider provider)) {
            savedCameraMaxY = 0;
            savedDynamicResizeRoutine = 0;
            hasS3kRuntimeState = false;
            return;
        }
        savedCameraMaxY = camera.getMaxY();
        savedDynamicResizeRoutine = provider.checkpointDynamicResizeRoutine();
        hasS3kRuntimeState = true;
    }

    /**
     * Restore state after player death.
     * ROM behavior: restores position, camera, clears rings.
     */
    public void restoreToPlayer(AbstractPlayableSprite player, Camera camera) {
        if (!isActive()) {
            return;
        }

        // ROM checkpoint x_pos/y_pos are playable centre coordinates.
        player.setCentreX((short) savedX);
        player.setCentreY((short) savedY);

        // Clear player state for fresh start
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setAir(false);
        player.setRolling(false);

        // Clear rings (ROM behavior)
        player.setRingCount(0);

        restoreActTimerIfSaved();

        if (camera != null) {
            camera.setFocusedSprite(player);
            if (hasS3kRuntimeState) {
                // Camera_Max_Y_pos / _target ARE restored by the checkpoint-load
                // routine and survive: LevelSizeLoad writes the level-size Y
                // bounds BEFORE calling it (s2.asm:14704-14707 vs :44790-44791).
                camera.setMaxY((short) savedCameraMaxY);
                camera.setMaxYTarget((short) savedCameraMaxY);
            }

            // Apply camera min X lock if subtype bit 7 was set.
            // s2.asm:44807-44810 (tst.b Last_star_pole_hit / bpl over it).
            if (cameraLock) {
                int minX = savedX - 0xA0;
                camera.setMinX((short) Math.max(0, minX));
            }

            // The camera is NOT restored from Saved_Camera_X/Y_pos. All three
            // ROMs write those back in their checkpoint-load routine and then
            // immediately overwrite Camera_X_pos / Camera_Y_pos from the
            // RESTORED PLAYER POSITION, in the shared tail of the level-size
            // init that the checkpoint branch falls into:
            //
            //   S2  Obj79_LoadData writes Camera_X/Y_pos (s2.asm:44793-44794),
            //       returns into LevelSizeLoad, which loads d1/d0 from
            //       MainCharacter x_pos/y_pos and runs the same
            //       "subi.w #$A0,d1 / clamp / move.w d1,(Camera_X_pos)" and
            //       "subi.w #$60,d0 / clamp / move.w d0,(Camera_Y_pos)" tail as
            //       a fresh start (s2.asm:14775-14814).
            //   S1  Lamp_LoadInfo, then LevSz_InitCameraPositions'
            //       "subi.w #320/2,d1" / "subi.w #$60,d0" clamped tail
            //       (_inc/LevelSizeLoad & BgScrollSpeed.asm:79-146).
            //   S3K the same "subi.w #$A0,d1 / subi.w #$60,d0" clamped tail
            //       (sonic3k.asm:38244-38266).
            //
            // Camera.updatePosition(true) IS that formula (see its comment), and
            // the level re-init on this path has already applied it from the
            // restored position. Writing the saved values here overrode it: on
            // the S2 special-stage return the engine's saved camera was
            // x-$90 (a left-scroll rest position banked at an earlier pass of
            // the star post) where the ROM's recompute gives x-$A0, leaving the
            // returned level's camera_x 16px right of the recording from its
            // first frame.
            camera.updatePosition(true);
        }

        LOGGER.info("Restored from checkpoint " + lastCheckpointIndex);
    }

    /**
     * Re-seats the banked act timer across a level reload, alongside the water,
     * runtime and solid-bit state carried on {@link LevelLoadContext}. The ROM
     * keeps {@code Saved_Timer} in main RAM across the reload rather than
     * re-deriving it, so the engine's per-load {@code CheckpointState} has to
     * carry it the same way.
     */
    public void saveActTimer(long timerFrames) {
        this.savedTimerFrames = timerFrames;
        this.hasSavedTimer = true;
    }

    /** ROM {@code Saved_Timer} banked at the star post; see {@link #saveActTimerIfPresent()}. */
    public long getSavedTimerFrames() {
        return savedTimerFrames;
    }

    /** True once a star post has banked an act timer this life. */
    public boolean hasSavedTimer() {
        return hasSavedTimer;
    }

    public boolean isActive() {
        return lastCheckpointIndex >= 0;
    }

    public int getLastCheckpointIndex() {
        return lastCheckpointIndex;
    }

    @Override
    public int getStarPostActivationMark() {
        return starPostActivationMark;
    }

    @Override
    public void restoreStarPostActivationMark(int mark) {
        this.starPostActivationMark = mark;
    }

    public int getSavedX() {
        return savedX;
    }

    public int getSavedY() {
        return savedY;
    }

    public int getSavedCameraX() {
        return savedCameraX;
    }

    public int getSavedCameraY() {
        return savedCameraY;
    }

    /**
     * Save water state at checkpoint time.
     * ROM: Lamp_StoreInfo saves v_waterpos2 and v_wtr_routine.
     */
    public void saveWaterState(int waterLevel, int waterRoutine) {
        this.savedWaterLevel = waterLevel;
        this.savedWaterRoutine = waterRoutine;
        this.hasWaterState = true;
    }

    public boolean hasWaterState() {
        return hasWaterState;
    }

    public int getSavedWaterLevel() {
        return savedWaterLevel;
    }

    public int getSavedWaterRoutine() {
        return savedWaterRoutine;
    }

    public boolean isUsedForSpecialStage() {
        return usedForSpecialStage;
    }

    public boolean hasCameraLock() {
        return cameraLock;
    }

    public boolean hasS3kRuntimeState() {
        return hasS3kRuntimeState;
    }

    public int getSavedCameraMaxY() {
        return savedCameraMaxY;
    }

    public int getSavedDynamicResizeRoutine() {
        return savedDynamicResizeRoutine;
    }

    public boolean hasSolidBits() {
        return hasSolidBits;
    }

    public byte getSavedTopSolidBit() {
        return savedTopSolidBit;
    }

    public byte getSavedLrbSolidBit() {
        return savedLrbSolidBit;
    }

    public void markUsedForSpecialStage() {
        this.usedForSpecialStage = true;
        LOGGER.fine("Checkpoint " + lastCheckpointIndex + " marked as used for special stage entry");
    }

    /**
     * Restore checkpoint state from previously saved values.
     * Called after loadLevel() clears the state but we still need checkpoint for
     * respawn.
     */
    public void restoreFromSaved(int x, int y, int cameraX, int cameraY, int checkpointIndex) {
        this.lastCheckpointIndex = checkpointIndex;
        this.savedX = x;
        this.savedY = y;
        this.savedCameraX = cameraX;
        this.savedCameraY = cameraY;
        LOGGER.fine("Restored checkpoint " + checkpointIndex + " state at (" + x + ", " + y + ")");
    }

    public void saveS3kRuntimeState(int cameraMaxY, int dynamicResizeRoutine) {
        this.savedCameraMaxY = cameraMaxY;
        this.savedDynamicResizeRoutine = dynamicResizeRoutine;
        this.hasS3kRuntimeState = true;
    }

    public void saveSolidBits(byte topSolidBit, byte lrbSolidBit) {
        this.savedTopSolidBit = topSolidBit;
        this.savedLrbSolidBit = lrbSolidBit;
        this.hasSolidBits = true;
    }

    public RewindState captureRewindState() {
        return new RewindState(
                lastCheckpointIndex,
                starPostActivationMark,
                savedX,
                savedY,
                savedCameraX,
                savedCameraY,
                cameraLock,
                usedForSpecialStage,
                savedWaterLevel,
                savedWaterRoutine,
                hasWaterState,
                savedCameraMaxY,
                savedDynamicResizeRoutine,
                hasS3kRuntimeState,
                savedTopSolidBit,
                savedLrbSolidBit,
                hasSolidBits,
                savedTimerFrames,
                hasSavedTimer);
    }

    public void restoreRewindState(RewindState state) {
        if (state == null) {
            clear();
            return;
        }
        this.lastCheckpointIndex = state.lastCheckpointIndex();
        this.starPostActivationMark = state.starPostActivationMark();
        this.savedX = state.savedX();
        this.savedY = state.savedY();
        this.savedCameraX = state.savedCameraX();
        this.savedCameraY = state.savedCameraY();
        this.cameraLock = state.cameraLock();
        this.usedForSpecialStage = state.usedForSpecialStage();
        this.savedWaterLevel = state.savedWaterLevel();
        this.savedWaterRoutine = state.savedWaterRoutine();
        this.hasWaterState = state.hasWaterState();
        this.savedCameraMaxY = state.savedCameraMaxY();
        this.savedDynamicResizeRoutine = state.savedDynamicResizeRoutine();
        this.hasS3kRuntimeState = state.hasS3kRuntimeState();
        this.savedTopSolidBit = state.savedTopSolidBit();
        this.savedLrbSolidBit = state.savedLrbSolidBit();
        this.hasSolidBits = state.hasSolidBits();
        this.savedTimerFrames = state.savedTimerFrames();
        this.hasSavedTimer = state.hasSavedTimer();
    }
}
