package com.openggf.game;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.GameStateSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages session-persistent game state such as Score, Lives, and Special Stage progress.
 * <p>
 * Special Stage tracking mirrors ROM variables:
 * - Current_Special_Stage: cycles 0-6, wraps at 7→0, increments on each entry
 * - Emerald_count: number of emeralds collected (0-7)
 * - Got_Emeralds_array: which specific emeralds have been obtained
 */
public class GameStateManager implements RewindSnapshottable<GameStateSnapshot> {
    private static final Logger LOGGER = Logger.getLogger(GameStateManager.class.getName());
    private static final int DEFAULT_SPECIAL_STAGE_COUNT = 7;
    private static final int DEFAULT_CHAOS_EMERALD_COUNT = 7;

    private int score;
    private int lives;
    private int continues;

    private int currentSpecialStageIndex;
    private int emeraldCount;
    private int specialStageCount;
    private int chaosEmeraldCount;
    private boolean[] gotEmeralds;
    private boolean[] gotSuperEmeralds;
    /** S3K ROM: Emeralds_converted_flag. Durable across levels and rewind. */
    private boolean emeraldsConverted;

    /**
     * Current boss ID (ROM: Current_Boss_ID).
     * 0 = no boss active, non-zero = boss fight in progress.
     * Used by level boundary logic to remove the +64 right buffer during boss fights.
     */
    private int currentBossId;

    /*
     * LIFETIME IS PER-GAME, and the three games do not agree. A comment here once
     * said this field is "cleared on every boss defeat"; that is true of S1 only,
     * and stating it as universal is how seven S2 bosses came to clear it.
     *
     *  S1  -- CLEARED. The Egg Prison clears it on release
     *         (docs/s1disasm/_incObj/3E Prison Capsule.asm:97), and the LZ boss
     *         clears it too (_incObj/77 Boss - LZ Main.asm:288).
     *  S2  -- NEVER CLEARED. docs/s2disasm/s2.asm writes it only as
     *         `move.b #N,(Current_Boss_ID).w` from the boss-arena setup routines
     *         (ids 1-9) and otherwise only reads it with `tst.b`. There is no
     *         `clr.b` and no `move.b #0` anywhere in the file, so it resets only
     *         through the level-load RAM clear and persists to the end of the act.
     *  S3K -- CLEARED. `clr.b (Boss_flag).w` appears at 31 sites in
     *         docs/skdisasm/sonic3k.asm, one annotated "Unlock the screen";
     *         note S3K uses the separate boolean Boss_flag rather than an id.
     *
     * The asymmetry matters because Sonic_Boundary's right-hand test widens the
     * side boundary by $40 only while this is zero (docs/s2disasm/s2.asm:37243-37251).
     */

    /**
     * Boss defeated flag (S2 ROM: {@code Boss_defeated_flag} at $FFFFF7A7).
     * Dynamic level events use this separately from {@link #currentBossId}; S2
     * bosses set it when their escape sequence begins while {@code Current_Boss_ID}
     * remains nonzero for boundary/player logic -- see the lifetime note on
     * {@link #currentBossId}, which S2 never clears.
     */
    private boolean bossDefeatedFlag;

    /**
     * Screen-lock flag (ROM: {@code f_lockscreen} at $FFFFF7AA).
     *
     * <p>S1 {@code Sonic_LevelBound} gates the +64 right level-boundary
     * extension on this flag (s1disasm/_incObj/01 Sonic.asm:1047-1049). The ROM
     * sets it at boss spawn via the dynamic level events
     * ({@code move.b #1,(f_lockscreen).w}) and clears it ONLY at the Egg Prison
     * (s1disasm/_incObj/3E Prison Capsule.asm:97) or the LZ boss
     * (s1disasm/_incObj/77 Boss - LZ Main.asm:288). Unlike {@link #currentBossId}
     * (which is cleared on every boss defeat), it therefore stays set through and
     * after boss defeat in the Final Zone (no Egg Prison), keeping Sonic clamped
     * to the locked arena edge. Set automatically when a boss id is assigned via
     * {@link #setCurrentBossId(int)} (the ROM's per-boss
     * {@code move.b #1,(f_lockscreen).w}); never auto-cleared on defeat.
     */
    private boolean screenLocked;

    /**
     * Screen shake flag (ROM: Screen_Shaking_Flag at $FFFFF72C).
     * When active, scroll handlers should apply shake offsets from ripple data.
     * Used by boss fights and events like pillar rising in ARZ.
     */
    private boolean screenShakeActive;

    /**
     * Background collision flag (ROM: Background_collision_flag at $FFFFF7C7).
     * When set, terrain collision routines (FindFloor, FindWall) perform a dual-path
     * scan: first against FG collision data, then against BG collision data. The
     * result with the greater distance (more lenient) is used. This allows the
     * player to collide with background-layer terrain during specific sequences
     * (e.g., HCZ2 wall chase, SSZ moving platforms).
     */
    private boolean backgroundCollisionFlag;

    /**
     * Giant Ring collected flag (S1 ROM: f_bigring at $FFFFF7AA).
     * Set when a Giant Ring flash triggers; prevents hidden bonuses from activating.
     * Reset on level load.
     */
    private boolean bigRingCollected;

    /**
     * WFZ/SCZ fire toggle (ROM: WFZ_SCZ_Fire_Toggle at $FFFFF72E).
     * Controls which palette cycling data is used in Wing Fortress Zone:
     * false (0) = fire palette (CyclingPal_WFZFire), timer 1
     * true (1)  = conveyor belt palette (CyclingPal_WFZBelt), timer 5
     * Toggled by Obj8B (WFZPalSwitcher) when player crosses trigger lines.
     */
    private boolean wfzFireToggle;

    /**
     * Item bonus chain counter (ROM: v_itembonus at $FFFFFEB2).
     * Tracks consecutive block/wall smashes in a level. Incremented by 2 per smash.
     * Used by Object 0x3C (Smashable Wall) and Object 0x51 (Smashable Green Block)
     * to award escalating points. Reset on level load.
     */
    private int itemBonus;

    /**
     * Reverse gravity flag (ROM: Reverse_gravity_flag at $FFFFF768).
     * When active, gravity is inverted for players and Y-dependent objects
     * must flip their behavior (e.g., up springs become down springs).
     * Used in S3K DEZ gravity-flip sections.
     * Currently always false - will be activated by level events when implemented.
     */
    private boolean reverseGravityActive;

    /**
     * S3K Special Stage Entry Ring collected bitfield (ROM: Collected_special_ring_array).
     * 32-bit bitfield where each ring's subtype (0-31) is a bit index.
     * Reset per level load.
     */
    private int collectedSpecialRings;

    /**
     * End-of-level signpost active flag (ROM: Level_end_flag at $FFFFFFD1).
     * Set when the end-of-act signpost begins its sequence; gates player
     * control lock and score tally trigger.
     */
    private boolean endOfLevelActive;

    /**
     * End-of-level completed flag (ROM: End_of_level_flag).
     * Set when the signpost spin/land sequence finishes and the act
     * transition should begin.
     */
    private boolean endOfLevelFlag;

    /**
     * In-game pause flag (ROM: Game_paused at $FFFFF63A for S3K, $FFFFFE5C for
     * S1, $FFFFFF7E for S2). Distinct from the loop/timing-level window-focus and
     * keyboard-toggle pauses in {@link com.openggf.GameLoop}: when this flag is
     * set the whole level update (objects, physics, camera, scroll) is skipped for
     * the frame while the V-int / frame counter still advances, exactly matching
     * the ROM {@code Pause_Loop} which only runs the V-int routine while paused.
     * <p>
     * ROM pause routines (universal across all three games — the trigger and
     * unpause are a Start-press edge in every case; per-game divergences are
     * debug-only cheats gated by Slow_motion_flag and are inert in normal play):
     * <ul>
     *   <li>S1 {@code PauseGame} / {@code Pause_Loop} —
     *       {@code docs/s1disasm/_inc/PauseGame.asm:5-54}</li>
     *   <li>S2 {@code PauseGame} / {@code Pause_Loop} —
     *       {@code docs/s2disasm/s2.asm:1585-1633}</li>
     *   <li>S3K {@code Pause_Game} / {@code Pause_Loop} —
     *       {@code docs/skdisasm/s3.asm:1690-1761}</li>
     * </ul>
     */
    private boolean gamePaused;

    public GameStateManager() {
        configureSpecialStageProgress(DEFAULT_SPECIAL_STAGE_COUNT, DEFAULT_CHAOS_EMERALD_COUNT);
        resetSession();
    }

    /**
     * Resets the game session state to defaults (Score: 0, Lives: 3, no emeralds).
     */
    public void resetSession() {
        this.score = 0;
        this.lives = 3;
        this.continues = 0;

        this.currentSpecialStageIndex = 0;
        this.emeraldCount = 0;
        for (int i = 0; i < gotEmeralds.length; i++) {
            gotEmeralds[i] = false;
        }
        if (gotSuperEmeralds != null) {
            for (int i = 0; i < gotSuperEmeralds.length; i++) {
                gotSuperEmeralds[i] = false;
            }
        }
        this.emeraldsConverted = false;

        this.currentBossId = 0;
        this.bossDefeatedFlag = false;
        this.screenLocked = false;
        this.screenShakeActive = false;
        this.backgroundCollisionFlag = false;
        this.bigRingCollected = false;
        this.wfzFireToggle = false;
        this.itemBonus = 0;
        this.reverseGravityActive = false;
        this.collectedSpecialRings = 0;
        this.endOfLevelActive = false;
        this.endOfLevelFlag = false;
        this.gamePaused = false;
    }

    /**
     * Resets all mutable state for test teardown.
     * Delegates to {@link #resetSession()} for the actual reset logic.
     * This method exists for naming consistency with other singletons
     * (Camera, CollisionSystem, TimerManager, etc.).
     */
    public void resetState() {
        resetSession();
    }

    /**
     * Resets per-level state flags between act transitions.
     * Session-persistent state (score, lives, emeralds) is NOT reset.
     */
    public void resetForLevel() {
        endOfLevelActive = false;
        endOfLevelFlag = false;
        gamePaused = false;
        // ROM clears f_lockscreen at level load; the per-act boss DLE re-sets it
        // when the boss spawns. Resetting here prevents a prior act's screen lock
        // from leaking into the next act's free-scroll approach.
        screenLocked = false;
        bossDefeatedFlag = false;
        // Current_Boss_ID is cleared by the same level-load RAM wipe as the two
        // flags above: S2 Level_ClrRam runs `clearRAM Misc_Variables,Misc_Variables_End`
        // (docs/s2disasm/s2.asm:4810) and Current_Boss_ID (s2.constants.asm:1597) lies
        // inside Misc_Variables (:1484) .. Misc_Variables_End (:1629), alongside
        // Boss_defeated_flag (:1595). S1 Level_ClrRam wipes v_misc_variables the same
        // way (docs/s1disasm/sonic.asm:2741), so this is a universal correction, not a
        // per-game rule. S2 never writes the byte back to zero anywhere else -- see the
        // lifetime note on currentBossId -- so without this the previous act's boss id
        // survives into the next act and Sonic_LevelBound's `tst.b (Current_Boss_ID).w`
        // (s2.asm:37245-37250) keeps withholding the +$40 right-boundary extension.
        currentBossId = 0;
        // f_bigring is a level variable, cleared by the same level-load RAM wipe:
        // S1 Level_ClrRam runs `clearRAM v_levelvariables` (docs/s1disasm/sonic.asm:2742)
        // and f_bigring (_Variables.asm:285) lies inside v_levelvariables (:179) ..
        // v_levelvariables_end (:301), the block commented "variables that are reset
        // between levels". Nothing else in the ROM ever writes the byte back to zero --
        // Obj7C's `move.b #1,(f_bigring).w` ("_incObj/4B, 7C Giant Ring and Flash.asm":123)
        // is its only other write -- so without this the flag survives the special stage
        // it triggered and every later act end reads it as still set. Got_ChkSS
        // ("_incObj/3A Got Through Card.asm":199-201) then writes v_gamemode = id_Special
        // on an act the player finished with fewer than ss_giantring_rings rings.
        bigRingCollected = false;
    }

    public int getScore() {
        return score;
    }

    public void addScore(int amount) {
        if (amount > 0) {
            this.score += amount;
        }
    }

    public int getLives() {
        return lives;
    }

    public void addLife() {
        this.lives++;
    }

    public int getContinues() {
        return continues;
    }

    /**
     * Accepted Continue: S1 Cont_GotoLevel, S2 ContinueScreen and S3K loc_5C48A.
     * Emerald inventory and route progression survive; this is not a new game.
     */
    public boolean consumeContinue() {
        if (continues <= 0) return false;
        continues--;
        lives = 3;
        score = 0;
        return true;
    }

    public void addContinue() {
        this.continues++;
    }

    public void restoreSaveProgress(int lives, int continues, List<Integer> chaosEmeralds, List<Integer> superEmeralds) {
        restoreSaveProgress(lives, continues, chaosEmeralds, superEmeralds, null);
    }

    public void restoreSaveProgress(int lives, int continues, List<Integer> chaosEmeralds,
                                    List<Integer> superEmeralds, Boolean emeraldsConverted) {
        this.lives = Math.max(0, lives);
        this.continues = Math.max(0, continues);
        this.emeraldCount = 0;
        for (int i = 0; i < gotEmeralds.length; i++) {
            gotEmeralds[i] = false;
        }
        if (gotSuperEmeralds != null) {
            for (int i = 0; i < gotSuperEmeralds.length; i++) {
                gotSuperEmeralds[i] = false;
            }
        }
        if (chaosEmeralds != null) {
            for (Integer emeraldIndex : chaosEmeralds) {
                if (emeraldIndex != null && emeraldIndex >= 0 && emeraldIndex < gotEmeralds.length
                        && !gotEmeralds[emeraldIndex]) {
                    gotEmeralds[emeraldIndex] = true;
                    emeraldCount++;
                }
            }
        }
        if (gotSuperEmeralds != null && superEmeralds != null) {
            for (Integer emeraldIndex : superEmeralds) {
                if (emeraldIndex != null && emeraldIndex >= 0 && emeraldIndex < gotSuperEmeralds.length
                        && gotEmeralds[emeraldIndex]) {
                    gotSuperEmeralds[emeraldIndex] = true;
                }
            }
        }
        this.emeraldsConverted = emeraldsConverted != null
                ? emeraldsConverted
                : superEmeralds != null && !superEmeralds.isEmpty();
    }

    public void loseLife() {
        if (this.lives > 0) {
            this.lives--;
        }
    }

    /**
     * Gets the current special stage index (0-6).
     */
    public int getCurrentSpecialStageIndex() {
        return currentSpecialStageIndex;
    }

    /**
     * ROM behavior: get the current stage index and advance to next.
     * Stage index wraps from 6 back to 0.
     * @return The stage index to use for this entry (before increment)
     */
    public int consumeCurrentSpecialStageIndexAndAdvance() {
        if (specialStageCount <= 0) {
            return 0;
        }
        int index = currentSpecialStageIndex;
        currentSpecialStageIndex = (currentSpecialStageIndex + 1) % specialStageCount;
        return index;
    }

    /**
     * Scans from the current special stage index until an uncollected stage
     * for the requested emerald set is found, then advances the cursor to the
     * following slot. Used by games whose ROM stage-selection policy skips
     * already-collected emerald stages.
     */
    public int consumeCurrentSpecialStageIndexAndAdvanceSkippingCollected(boolean superEmeraldMode) {
        if (specialStageCount <= 0) {
            return 0;
        }
        int startIndex = Math.floorMod(currentSpecialStageIndex, specialStageCount);
        int selectedIndex = startIndex;
        for (int offset = 0; offset < specialStageCount; offset++) {
            int candidateIndex = (startIndex + offset) % specialStageCount;
            if (isSpecialStageUncollected(candidateIndex, superEmeraldMode)) {
                selectedIndex = candidateIndex;
                break;
            }
        }
        currentSpecialStageIndex = (selectedIndex + 1) % specialStageCount;
        return selectedIndex;
    }

    private boolean isSpecialStageUncollected(int index, boolean superEmeraldMode) {
        if (superEmeraldMode) {
            return !hasSuperEmerald(index);
        }
        return !hasEmerald(index);
    }

    /**
     * Gets the total number of emeralds collected (0-7).
     */
    public int getEmeraldCount() {
        return emeraldCount;
    }

    /**
     * Checks if a specific emerald has been collected.
     * @param index Emerald index (0-6)
     */
    public boolean hasEmerald(int index) {
        return index >= 0 && index < gotEmeralds.length && gotEmeralds[index];
    }

    public List<Integer> getCollectedChaosEmeraldIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < gotEmeralds.length; i++) {
            if (gotEmeralds[i]) {
                indices.add(i);
            }
        }
        return List.copyOf(indices);
    }

    public List<Integer> getCollectedSuperEmeraldIndices() {
        List<Integer> indices = new ArrayList<>();
        if (gotSuperEmeralds == null) {
            return List.of();
        }
        for (int i = 0; i < gotSuperEmeralds.length; i++) {
            if (gotSuperEmeralds[i]) {
                indices.add(i);
            }
        }
        return List.copyOf(indices);
    }

    /**
     * Marks an emerald as collected.
     * @param index Emerald index (0-6)
     */
    public synchronized void markEmeraldCollected(int index) {
        if (index < 0 || index >= gotEmeralds.length) {
            LOGGER.warning("Attempted to mark emerald " + index +
                " but valid range is 0-" + (gotEmeralds.length - 1));
            return;
        }
        if (!gotEmeralds[index]) {
            gotEmeralds[index] = true;
            emeraldCount++;
        }
    }

    public boolean hasSuperEmerald(int index) {
        return gotSuperEmeralds != null
                && index >= 0
                && index < gotSuperEmeralds.length
                && gotSuperEmeralds[index];
    }

    public synchronized void markSuperEmeraldCollected(int index) {
        if (gotSuperEmeralds == null || index < 0 || index >= gotSuperEmeralds.length) {
            LOGGER.warning("Attempted to mark super emerald " + index +
                    " but valid range is 0-" + ((gotSuperEmeralds == null ? 0 : gotSuperEmeralds.length) - 1));
            return;
        }
        gotSuperEmeralds[index] = true;
        emeraldsConverted = true;
    }

    public boolean hasAllSuperEmeralds() {
        if (gotSuperEmeralds == null || gotSuperEmeralds.length == 0) {
            return false;
        }
        for (boolean gotSuperEmerald : gotSuperEmeralds) {
            if (!gotSuperEmerald) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmeraldsConverted() {
        return emeraldsConverted;
    }

    public void setEmeraldsConverted(boolean emeraldsConverted) {
        this.emeraldsConverted = emeraldsConverted;
    }

    /**
     * Checks if all 7 emeralds have been collected.
     */
    public boolean hasAllEmeralds() {
        return emeraldCount >= chaosEmeraldCount;
    }

    /**
     * Configures special stage cycle count and emerald target count for the current game.
     *
     * @param stageCount number of special stages in the rotation (minimum 1)
     * @param emeraldTarget number of chaos emeralds in this game (minimum 1)
     */
    public synchronized void configureSpecialStageProgress(int stageCount, int emeraldTarget) {
        int safeStageCount = Math.max(1, stageCount);
        int safeEmeraldTarget = Math.max(1, emeraldTarget);

        this.specialStageCount = safeStageCount;
        this.chaosEmeraldCount = safeEmeraldTarget;
        this.gotEmeralds = new boolean[safeEmeraldTarget];
        this.gotSuperEmeralds = new boolean[safeEmeraldTarget];
        this.currentSpecialStageIndex = 0;
        this.emeraldCount = 0;
    }

    public int getSpecialStageCount() {
        return specialStageCount;
    }

    public int getChaosEmeraldCount() {
        return chaosEmeraldCount;
    }

    /**
     * Checks if a Special Stage entry ring has been collected.
     * ROM: btst d0,(Collected_special_ring_array).w
     */
    public boolean isSpecialRingCollected(int bitIndex) {
        return (collectedSpecialRings & (1 << (bitIndex & 0x1F))) != 0;
    }

    /**
     * Marks a Special Stage entry ring as collected.
     * ROM: bset d0,(Collected_special_ring_array).w
     */
    public void markSpecialRingCollected(int bitIndex) {
        collectedSpecialRings |= (1 << (bitIndex & 0x1F));
    }

    /**
     * Gets the current boss ID.
     * ROM: Current_Boss_ID - 0 means no boss active.
     */
    public int getCurrentBossId() {
        return currentBossId;
    }

    /**
     * Sets the current boss ID.
     * ROM: Current_Boss_ID - set to non-zero when entering a boss fight,
     * 0 when the boss is defeated. When non-zero, the +64 right boundary
     * buffer is removed to keep the player within the boss arena.
     */
    public void setCurrentBossId(int bossId) {
        this.currentBossId = bossId;
        if (bossId != 0) {
            bossDefeatedFlag = false;
            // ROM: each boss's dynamic-level-event spawn does
            // move.b #1,(f_lockscreen).w alongside loading the boss object.
            // Setting the boss id is the engine's spawn point, so latch the
            // persistent screen lock here. It is NOT cleared when bossId is set
            // back to 0 on defeat — only the Egg Prison / LZ boss clear it.
            this.screenLocked = true;
        }
    }

    /**
     * Checks if a boss fight is currently active.
     */
    public boolean isBossFightActive() {
        return currentBossId != 0;
    }

    /**
     * Returns the ROM boss defeated flag used by dynamic level events.
     */
    public boolean isBossDefeatedFlag() {
        return bossDefeatedFlag;
    }

    /**
     * Sets the ROM boss defeated flag.
     */
    public void setBossDefeatedFlag(boolean bossDefeatedFlag) {
        this.bossDefeatedFlag = bossDefeatedFlag;
    }

    /**
     * Returns the ROM {@code f_lockscreen} state. See {@link #screenLocked}.
     */
    public boolean isScreenLocked() {
        return screenLocked;
    }

    /**
     * Sets the ROM {@code f_lockscreen} state. Call with {@code false} at the
     * ROM clear sites (Egg Prison Obj3E, LZ boss) where the ROM does
     * {@code clr.b (f_lockscreen).w}.
     */
    public void setScreenLocked(boolean locked) {
        this.screenLocked = locked;
    }

    /**
     * Gets the screen shake active state.
     * ROM: tst.b (Screen_Shaking_Flag).w
     *
     * @return true if screen shake is active
     */
    public boolean isScreenShakeActive() {
        return screenShakeActive;
    }

    /**
     * Sets the screen shake active state.
     * ROM: move.b #1,(Screen_Shaking_Flag).w to enable
     * ROM: move.b #0,(Screen_Shaking_Flag).w to disable
     *
     * When active, scroll handlers should use ripple data from ParallaxTables
     * to apply shake offsets to both horizontal and vertical scroll values.
     *
     * @param active true to enable screen shake, false to disable
     */
    public void setScreenShakeActive(boolean active) {
        this.screenShakeActive = active;
    }

    /**
     * Gets the background collision flag.
     * ROM: tst.b (Background_collision_flag).w
     *
     * @return true if background collision is enabled
     */
    public boolean isBackgroundCollisionFlag() {
        return backgroundCollisionFlag;
    }

    /**
     * Sets the background collision flag.
     * ROM: st (Background_collision_flag).w / clr.b (Background_collision_flag).w
     *
     * @param flag true to enable dual-path BG collision, false to disable
     */
    public void setBackgroundCollisionFlag(boolean flag) {
        this.backgroundCollisionFlag = flag;
    }

    /**
     * Checks if a Giant Ring has been collected in this level.
     * S1 ROM: tst.b (f_bigring).w
     */
    public boolean isBigRingCollected() {
        return bigRingCollected;
    }

    /**
     * Sets the Giant Ring collected flag.
     * S1 ROM: move.b #1,(f_bigring).w — set by Ring Flash at trigger frame.
     */
    public void setBigRingCollected(boolean collected) {
        this.bigRingCollected = collected;
    }

    /**
     * Gets the WFZ/SCZ fire toggle state.
     * ROM: tst.b (WFZ_SCZ_Fire_Toggle).w
     *
     * @return true if toggled to conveyor belt palette, false for fire palette
     */
    public boolean isWfzFireToggle() {
        return wfzFireToggle;
    }

    /**
     * Sets the WFZ/SCZ fire toggle state.
     * ROM: move.b #1,(WFZ_SCZ_Fire_Toggle).w or move.b #0,(WFZ_SCZ_Fire_Toggle).w
     * Toggled by Obj8B (WFZPalSwitcher) when player crosses trigger boundary.
     *
     * @param toggle true for conveyor belt palette, false for fire palette
     */
    public void setWfzFireToggle(boolean toggle) {
        this.wfzFireToggle = toggle;
    }

    /**
     * Gets the current item bonus chain counter.
     * ROM: v_itembonus - tracks consecutive block/wall smashes.
     * Used as a word-sized index into score tables (increments by 2).
     */
    public int getItemBonus() {
        return itemBonus;
    }

    /**
     * Sets the item bonus chain counter.
     * ROM: move.w d2,(v_itembonus).w
     */
    public void setItemBonus(int value) {
        this.itemBonus = value;
    }

    /**
     * Resets the item bonus counter to zero.
     * Called on level load to reset the chain.
     */
    public void resetItemBonus() {
        this.itemBonus = 0;
    }

    /**
     * Gets the reverse gravity flag.
     * ROM: tst.b (Reverse_gravity_flag).w at $FFFFF768
     *
     * @return true if reverse gravity is active
     */
    public boolean isReverseGravityActive() {
        return reverseGravityActive;
    }

    /**
     * Sets the reverse gravity flag.
     * ROM: move.b #1,(Reverse_gravity_flag).w to enable
     *
     * @param active true to enable reverse gravity, false to disable
     */
    public void setReverseGravityActive(boolean active) {
        this.reverseGravityActive = active;
    }

    /**
     * Gets the end-of-level active flag.
     * ROM: tst.b (Level_end_flag).w
     *
     * @return true if the end-of-level signpost sequence is active
     */
    public boolean isEndOfLevelActive() { return endOfLevelActive; }

    /**
     * Sets the end-of-level active flag.
     * ROM: move.b #1,(Level_end_flag).w
     */
    public void setEndOfLevelActive(boolean active) { this.endOfLevelActive = active; }

    /**
     * Gets the end-of-level completed flag.
     * ROM: tst.b (End_of_level_flag).w
     *
     * @return true if the act transition should begin
     */
    public boolean isEndOfLevelFlag() { return endOfLevelFlag; }

    /**
     * Sets the end-of-level completed flag.
     * ROM: move.b #1,(End_of_level_flag).w
     */
    public void setEndOfLevelFlag(boolean flag) { this.endOfLevelFlag = flag; }

    /**
     * Whether the game is currently in-game paused (ROM {@code Game_paused != 0}).
     *
     * @return true if the level update should be skipped this frame
     */
    public boolean isGamePaused() { return gamePaused; }

    /**
     * Sets the in-game pause flag directly. Prefer {@link #applyPauseToggle(boolean)}
     * from the frame loop so the Start-edge toggle semantics are modelled in one
     * place; this setter exists for tests and bootstrap.
     */
    public void setGamePaused(boolean paused) { this.gamePaused = paused; }

    /**
     * Applies one frame of ROM {@code Pause_Game} toggle logic and reports whether
     * the level update should run this frame.
     * <p>
     * Mirrors the universal Start-edge toggle (S1 {@code PauseGame}
     * {@code docs/s1disasm/_inc/PauseGame.asm:5-54}; S2 {@code PauseGame}
     * {@code docs/s2disasm/s2.asm:1585-1633}; S3K {@code Pause_Game}
     * {@code docs/skdisasm/s3.asm:1690-1761}):
     * <ul>
     *   <li>{@code tst.b (v_lives).w; beq.s .unpauseGame}: with no lives left
     *       the ROM clears the pause flag and returns without looking at Start,
     *       so the GAME OVER card can never be paused and a pause held at the
     *       moment the last life goes releases on the next frame
     *       (docs/s1disasm/_inc/PauseGame.asm:8-9 and :47-52,
     *       docs/s2disasm/s2.asm:1573-1574, docs/skdisasm/s3.asm:1692-1693).</li>
     *   <li>{@code Pause_Game} sits at the very top of {@code LevelLoop}
     *       ({@code docs/skdisasm/sonic3k.asm:7884-7894}). On the press frame it
     *       enters {@code Pause_Loop}, which runs only the V-int until Start is
     *       pressed again, so the rest of the level update never runs while
     *       paused. On the unpause-press frame it clears {@code Game_paused} and
     *       {@code rts}, and the remainder of {@code LevelLoop} (objects, physics,
     *       camera, scroll) <em>does</em> run that frame.</li>
     * </ul>
     * Therefore: the level update is skipped this frame iff the game is paused
     * <em>after</em> applying this frame's toggle.
     *
     * @param startEdgePressed true on the single frame Start transitions from
     *                         released to pressed (ROM {@code Ctrl_x_pressed} &
     *                         Start). Held Start across multiple frames must
     *                         report false except on the leading edge.
     * @return true if the level update should be skipped this frame (game is
     *         paused), false if it should run normally.
     */
    public boolean applyPauseToggle(boolean startEdgePressed) {
        if (lives == 0) {
            gamePaused = false;
            return false;
        }
        if (startEdgePressed) {
            gamePaused = !gamePaused;
        }
        return gamePaused;
    }

    /**
     * The title screen's level start: S1 {@code PlayLevel} writes three lives,
     * clears the score and the continue count (docs/s1disasm/sonic.asm:2273-2283);
     * S2 {@code TitleScreen} does the same for both players before entering
     * {@code GameModeID_Level} (docs/s2disasm/s2.asm:4513-4525). Without it a
     * game that follows a game over would open with the zero lives the card
     * left behind. S3K takes its counts from the data-select slot instead.
     */
    public void startNewGameFromTitle() {
        this.lives = 3;
        this.continues = 0;
        this.score = 0;
    }

    @Override
    public String key() {
        return "gamestate";
    }

    @Override
    public GameStateSnapshot capture() {
        return new GameStateSnapshot(
                score, lives, continues, currentSpecialStageIndex, emeraldCount,
                gotEmeralds, gotSuperEmeralds, emeraldsConverted, currentBossId, bossDefeatedFlag,
                screenShakeActive, backgroundCollisionFlag, bigRingCollected,
                wfzFireToggle, itemBonus, reverseGravityActive,
                collectedSpecialRings, endOfLevelActive, endOfLevelFlag, screenLocked);
    }

    @Override
    public void restore(GameStateSnapshot snapshot) {
        this.score = snapshot.score();
        this.lives = snapshot.lives();
        this.continues = snapshot.continues();
        this.currentSpecialStageIndex = snapshot.currentSpecialStageIndex();
        this.emeraldCount = snapshot.emeraldCount();
        this.gotEmeralds = snapshot.gotEmeralds().clone();
        this.gotSuperEmeralds = snapshot.gotSuperEmeralds().clone();
        this.emeraldsConverted = snapshot.emeraldsConverted();
        this.currentBossId = snapshot.currentBossId();
        this.bossDefeatedFlag = snapshot.bossDefeatedFlag();
        this.screenShakeActive = snapshot.screenShakeActive();
        this.backgroundCollisionFlag = snapshot.backgroundCollisionFlag();
        this.bigRingCollected = snapshot.bigRingCollected();
        this.wfzFireToggle = snapshot.wfzFireToggle();
        this.itemBonus = snapshot.itemBonus();
        this.reverseGravityActive = snapshot.reverseGravityActive();
        this.collectedSpecialRings = snapshot.collectedSpecialRings();
        this.endOfLevelActive = snapshot.endOfLevelActive();
        this.endOfLevelFlag = snapshot.endOfLevelFlag();
        this.screenLocked = snapshot.screenLocked();
    }
}
