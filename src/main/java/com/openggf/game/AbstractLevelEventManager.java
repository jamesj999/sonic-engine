package com.openggf.game;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.LevelEventSnapshot;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Abstract base class for game-specific level event managers.
 * Provides the state machine mechanics and convenience methods shared
 * across S1, S2, and S3K level event systems.
 *
 * All three games use the same fundamental pattern: a per-zone state machine
 * driven by camera position triggers, with routine counters incremented by
 * a game-specific stride (2 for S1/S2, 4 for S3K).
 *
 * Subclasses implement zone-specific dispatch in {@link #onUpdate()} and
 * game-specific initialization in {@link #onInitLevel(int, int)}.
 */
public abstract class AbstractLevelEventManager
        implements LevelEventProvider, RewindSnapshottable<LevelEventSnapshot> {

    /**
     * Returns the current Camera singleton. Always call this accessor rather
     * than caching the reference, so it survives singleton replacement.
     */
    protected Camera camera() {
        return GameServices.camera();
    }

    /**
     * Returns the current LevelManager singleton. Always call this accessor rather
     * than caching the reference, so it survives state resets cleanly.
     */
    protected LevelManager levelManager() {
        return GameServices.level();
    }

    /**
     * Returns the current Camera, or {@code null} when no runtime camera exists
     * yet. Shared helper so event implementations never reach GameServices
     * directly (TestZoneEventRuntimeAccessGuard).
     */
    protected Camera cameraOrNull() {
        return GameServices.cameraOrNull();
    }

    /** Resolves a per-game service from the active module. */
    protected <T> T gameService(Class<T> type) {
        return GameServices.module().getGameService(type);
    }

    // Current zone and act
    protected int currentZone = -1;
    protected int currentAct = -1;

    // Dual event routine counters (ROM: Events_routine_fg, Events_routine_bg)
    // S1/S2 only use eventRoutineFg. S3K uses both.
    protected int eventRoutineFg;
    protected int eventRoutineBg;

    // General-purpose frame counter, incremented each update() call
    protected int frameCounter;

    // Countdown timer for timed sequences (boss spawn delays, cutscene waits, etc.)
    // Decremented each frame when > 0. Use startTimer()/isTimerExpired().
    protected int timerFrames;

    // Boss active flag (ROM: Boss_flag in S3K, bossActive in S2)
    // S3K uses this to gate FG events during boss fights.
    protected boolean bossActive;

    // Per-zone temporary data storage (ROM: Events_fg_0..5, Events_bg[24])
    // Sized by subclass via getEventDataFgSize()/getEventDataBgSize().
    protected short[] eventDataFg;
    protected byte[] eventDataBg;

    protected AbstractLevelEventManager() {
        int fgSize = getEventDataFgSize();
        int bgSize = getEventDataBgSize();
        this.eventDataFg = fgSize > 0 ? new short[fgSize] : null;
        this.eventDataBg = bgSize > 0 ? new byte[bgSize] : null;
    }

    // =========================================================================
    // LevelEventProvider implementation
    // =========================================================================

    @Override
    public void initLevel(int zone, int act) {
        this.currentZone = zone;
        this.currentAct = act;
        this.eventRoutineFg = 0;
        this.eventRoutineBg = 0;
        this.frameCounter = 0;
        this.timerFrames = 0;
        this.bossActive = false;
        if (eventDataFg != null) {
            java.util.Arrays.fill(eventDataFg, (short) 0);
        }
        if (eventDataBg != null) {
            java.util.Arrays.fill(eventDataBg, (byte) 0);
        }
        onInitLevel(zone, act);
    }

    @Override
    public void update() {
        frameCounter++;
        if (timerFrames > 0) {
            timerFrames--;
        }
        if (currentZone < 0) {
            return;
        }
        onUpdate();
    }

    /**
     * Hook invoked by the rewind registry after a keyframe restore so a game's
     * level-event handlers can reconcile any one-shot state that gates dynamic
     * objects against the actually-restored object set. Default is a no-op;
     * game subclasses override where a sequence can be left in an impossible
     * state after restore (e.g. S3K AIZ2 ship-loop/boss). Must be a live-state
     * reconciliation, never a zone/frame carve-out.
     */
    public void reconcileAfterRewindRestore() {
    }

    /**
     * Game-specific rewind adapters this level-event manager contributes;
     * {@link com.openggf.game.session.GameplayModeContext} registers them with
     * the level adapters. Default none.
     */
    public java.util.List<com.openggf.game.rewind.RewindSnapshottable<?>> extraRewindAdapters() {
        return java.util.List.of();
    }

    @Override
    public void updatePrePhysics() {
        // Runs before the player physics step; the frame counter is advanced by
        // update() later in the same frame, so it must not be touched here.
        if (currentZone < 0) {
            return;
        }
        onUpdatePrePhysics();
    }

    /**
     * Game-specific pre-physics zone dispatch, run before player physics.
     * Default is a no-op; only zones with a ROM pre-{@code RunObjects} routine
     * (e.g. S2 OOZ OilSlides) override this.
     */
    protected void onUpdatePrePhysics() {
        // Default no-op
    }

    // =========================================================================
    // Abstract methods - subclass contract
    // =========================================================================

    /** Returns 2 for S1/S2, 4 for S3K. */
    protected abstract int getRoutineStride();

    /** Number of short slots in eventDataFg. 0 for S1, ~6 for S2/S3K. */
    protected abstract int getEventDataFgSize();

    /** Number of byte slots in eventDataBg. 0 for S1/S2, 24 for S3K. */
    protected abstract int getEventDataBgSize();

    /** Game-specific initialization called after base state is reset. */
    protected abstract void onInitLevel(int zone, int act);

    /** Game-specific per-frame zone dispatch. */
    protected abstract void onUpdate();

    /**
     * Returns the current player character matching ROM's {@code (Player_mode).w}.
     * S1 always returns SONIC_AND_TAILS.
     * S2 resolves from config (MAIN_CHARACTER_CODE / SIDEKICK_CHARACTER_CODE).
     * S3K queries the actual player mode selection.
     */
    public abstract PlayerCharacter getPlayerCharacter();

    // =========================================================================
    // Routine management
    // =========================================================================

    /** Advance the foreground event routine by the game-specific stride. */
    protected void advanceFgRoutine() {
        eventRoutineFg += getRoutineStride();
    }

    /** Advance the background event routine by the game-specific stride. */
    protected void advanceBgRoutine() {
        eventRoutineBg += getRoutineStride();
    }

    /** Revert the foreground event routine by the game-specific stride. */
    protected void revertFgRoutine() {
        eventRoutineFg -= getRoutineStride();
    }

    /** Directly set the foreground event routine (for non-sequential jumps). */
    protected void setFgRoutine(int routine) {
        eventRoutineFg = routine;
    }

    /** Directly set the background event routine. */
    protected void setBgRoutine(int routine) {
        eventRoutineBg = routine;
    }

    /**
     * Restores the dynamic resize routine state after a bonus/special stage return.
     * Must be called AFTER initLevel() has reset counters to 0.
     */
    public void restoreEventRoutineState(int routineFg, int routineBg) {
        this.eventRoutineFg = routineFg;
        this.eventRoutineBg = routineBg;
    }

    // =========================================================================
    // Camera boundary convenience methods
    // =========================================================================

    /** Lock both X boundaries immediately. */
    protected void lockCameraX(int min, int max) {
        camera().setMinX((short) min);
        camera().setMaxX((short) max);
    }

    /** Lock both Y boundaries immediately. */
    protected void lockCameraY(int min, int max) {
        camera().setMinY((short) min);
        camera().setMaxY((short) max);
    }

    /** Set bottom boundary target (eased at +2px/frame by Camera). */
    protected void setBottomBoundaryTarget(int y) {
        camera().setMaxYTarget((short) y);
    }

    /** Set top boundary target (eased by Camera). */
    protected void setTopBoundaryTarget(int y) {
        camera().setMinYTarget((short) y);
    }

    /** Freeze camera (stops following player). Manual position still works. */
    protected void freezeCamera() {
        camera().setFrozen(true);
    }

    /** Unfreeze camera (resumes following player). */
    protected void unfreezeCamera() {
        camera().setFrozen(false);
    }

    /**
     * Prevent backtracking: set minX to current camera X.
     * Common post-boss or mid-level gate pattern.
     */
    protected void preventBacktracking() {
        camera().setMinX(camera().getX());
    }

    /**
     * Prevent advancing: set maxX to current camera X.
     * Used in S1 GHZ boss to stop player walking past boss arena.
     */
    protected void preventAdvancing() {
        camera().setMaxX(camera().getX());
    }

    // =========================================================================
    // Timing
    // =========================================================================

    /**
     * Start a countdown timer. Decremented automatically each frame in update().
     * Use {@link #isTimerExpired()} to check completion.
     *
     * @param frames number of frames to count down
     */
    protected void startTimer(int frames) {
        this.timerFrames = frames;
    }

    /**
     * Check if the countdown timer has expired.
     * Only meaningful after calling {@link #startTimer(int)}.
     */
    protected boolean isTimerExpired() {
        return timerFrames <= 0;
    }

    // =========================================================================
    // Player control
    // =========================================================================

    /**
     * Lock player input (ROM: Ctrl_1_locked = 1).
     * Suppresses all directional and jump input.
     */
    protected void lockPlayerInput() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player != null) {
            player.setControlLocked(true);
        }
    }

    /**
     * Unlock player input (ROM: Ctrl_1_locked = 0).
     */
    protected void unlockPlayerInput() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player != null) {
            player.setControlLocked(false);
        }
    }

    /**
     * Inject forced button input (ROM: writing to Ctrl_1_Logical).
     * The forced input mask is OR'd with (or replaces) normal input.
     *
     * @param mask button bitmask (use AbstractPlayableSprite.INPUT_* constants)
     */
    protected void setForcedInput(int mask) {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player != null) {
            player.setForcedInputMask(mask);
        }
    }

    /** Clear any forced input injection. */
    protected void clearForcedInput() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player != null) {
            player.clearForcedInputMask();
        }
    }

    /** Force player to walk right (end-of-act walkoff shorthand). */
    protected void forcePlayerRight() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player != null) {
            player.setForceInputRight(true);
        }
    }

    // =========================================================================
    // Audio
    // =========================================================================

    /** Fade out the currently playing music. */
    protected void fadeMusic() {
        GameServices.audio().fadeOutMusic();
    }

    /** Play a music track by ID. */
    protected void playMusic(int musicId) {
        GameServices.audio().playMusic(musicId);
    }

    /** Play a sound effect by ID. */
    protected void playSfx(int sfxId) {
        GameServices.audio().playSfx(sfxId);
    }

    // =========================================================================
    // Object spawning
    // =========================================================================

    /**
     * Spawn a dynamic object into the level.
     * Wraps {@code objectManager.addDynamicObject()}.
     */
    protected void spawnObject(ObjectInstance object) {
        LevelManager lm = levelManager();
        if (lm.getObjectManager() != null) {
            lm.getObjectManager().addDynamicObject(object);
        }
    }

    // =========================================================================
    // Zone transition
    // =========================================================================

    /**
     * Trigger a transition to a different zone/act.
     * Used by S1 SBZ3->FZ and S3K inter-act transitions (LRZ1->2, DEZ1->2).
     *
     * Requests a fade-coordinated level restart via LevelManager.
     * The checkpoint is cleared automatically by the transition.
     */
    protected void transitionToZone(int zone, int act) {
        levelManager().requestZoneAndAct(zone, act);
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Resets mutable state without destroying the singleton instance.
     * Calls {@link #initLevel(int, int)} with (-1, -1) to clear zone/act
     * and reset all event counters/data.
     */
    public void resetState() {
        initLevel(-1, -1);
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public int getCurrentZone() {
        return currentZone;
    }

    public int getCurrentAct() {
        return currentAct;
    }

    public int getEventRoutineFg() {
        return eventRoutineFg;
    }

    public int getEventRoutineBg() {
        return eventRoutineBg;
    }

    public boolean isBossActive() {
        return bossActive;
    }

    public void setBossActive(boolean bossActive) {
        this.bossActive = bossActive;
    }

    // =========================================================================
    // RewindSnapshottable<LevelEventSnapshot>
    // =========================================================================

    /**
     * Subclasses override to encode game-specific extra state into a byte array
     * (e.g. using {@link java.nio.ByteBuffer}).
     * Return {@code null} when there is no extra state to capture.
     */
    protected byte[] captureExtra() {
        return null;
    }

    /**
     * Subclasses override to restore game-specific state from the byte array
     * previously produced by {@link #captureExtra()}.
     * The default no-op is safe for subclasses without extra state.
     */
    protected void restoreExtra(byte[] extra) {
        // no-op in base class
    }

    @Override
    public String key() {
        return "level-event";
    }

    @Override
    public LevelEventSnapshot capture() {
        return new LevelEventSnapshot(
                currentZone,
                currentAct,
                eventRoutineFg,
                eventRoutineBg,
                frameCounter,
                timerFrames,
                bossActive,
                eventDataFg != null ? eventDataFg.clone() : null,
                eventDataBg != null ? eventDataBg.clone() : null,
                captureExtra());
    }

    @Override
    public void restore(LevelEventSnapshot snapshot) {
        this.currentZone    = snapshot.currentZone();
        this.currentAct     = snapshot.currentAct();
        this.eventRoutineFg = snapshot.eventRoutineFg();
        this.eventRoutineBg = snapshot.eventRoutineBg();
        this.frameCounter   = snapshot.frameCounter();
        this.timerFrames    = snapshot.timerFrames();
        this.bossActive     = snapshot.bossActive();
        short[] fg = snapshot.eventDataFg();
        if (this.eventDataFg != null && fg != null) {
            System.arraycopy(fg, 0, this.eventDataFg, 0,
                    Math.min(fg.length, this.eventDataFg.length));
        }
        byte[] bg = snapshot.eventDataBg();
        if (this.eventDataBg != null && bg != null) {
            System.arraycopy(bg, 0, this.eventDataBg, 0,
                    Math.min(bg.length, this.eventDataBg.length));
        }
        restoreExtra(snapshot.extra());
    }
}
