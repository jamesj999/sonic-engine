package com.openggf.level;

import com.openggf.game.BonusStageType;
import com.openggf.game.GameOverExit;
import com.openggf.level.objects.PersistentRespawnState;

/**
 * Holds all transition request/consume state that was previously scattered
 * across LevelManager fields.  LevelManager owns a single instance and
 * exposes it via {@code getTransitions()}.
 * <p>
 * This is a pure state holder — it never calls back into LevelManager or
 * any other singleton.
 */
public class LevelTransitionCoordinator {

    // ── Special stage ──────────────────────────────────────────────────
    private boolean specialStageRequestedFromCheckpoint;
    private boolean specialStageEntryRoutineArmed;
    private boolean specialStageEntryAdvancesLevel;
    private boolean specialStageReturnLevelReloadRequested;
    private boolean resultsReturnCardOwnedByCaller;
    private boolean levelRoutineReentry;

    // ── S3K big ring return (ROM: Saved2_* variables) ──────────
    private BigRingReturnState bigRingReturn;
    /** Engine snapshot of Object_respawn_table kept by Respawn_table_keep. */
    private PersistentRespawnState bigRingReturnRespawnState;

    // ── Bonus stage ───────────────────────────────────────────────────
    private BonusStageType bonusStageRequested;
    private int bonusStageReturnCheckpointIndex = -1;

    // ── Title card ─────────────────────────────────────────────────────
    private boolean titleCardRequested;
    private int titleCardZone = -1;
    private int titleCardAct = -1;
    private boolean inLevelTitleCardRequested;
    private int inLevelTitleCardZone = -1;
    private int inLevelTitleCardAct = -1;
    private boolean inLevelTitleCardLevelGamestateResetRequested;
    private int inLevelTitleCardResetAdditionalDispatches;
    private int inLevelTitleCardResetPhaseOneDispatchOverlap;
    private boolean inLevelTitleCardPlayerControlLockRequested;
    private int inLevelTitleCardExitAdditionalDispatches;
    private int inLevelTitleCardExitPhaseOneDispatchOverlap;

    // ── Transition request flags (for fade-coordinated transitions) ────
    private boolean respawnRequested;
    private boolean nextActRequested;
    private boolean nextZoneRequested;
    private boolean specificZoneActRequested;
    private int requestedZone = -1;
    private int requestedAct = -1;
    private int requestedMusicId = -1;

    // ── Seamless transitions ───────────────────────────────────────────
    private boolean seamlessTransitionRequested;
    private SeamlessLevelTransitionRequest pendingSeamlessTransitionRequest;

    // ── Credits ────────────────────────────────────────────────────────
    private boolean creditsRequested;
    private GameOverExit gameOverExitRequested;

    // ── HUD / music suppression ────────────────────────────────────────
    private boolean forceHudSuppressed;
    private boolean suppressNextMusicChange;

    // ── Level inactive flag ────────────────────────────────────────────
    private boolean levelInactiveForTransition;

    // ================================================================
    //  Special stage requests
    // ================================================================

    /**
     * Advances an end-of-act results card to {@code Got_NextLevel}, whose body
     * runs on the FOLLOWING frame and both advances the level and enters the
     * special stage.
     * <p>
     * ROM: {@code Got_Wait} ("_incObj/3A Got Through Card.asm":112-116) spends
     * the frame its post-tally delay expires doing nothing but
     * {@code addq.b #2,obRoutine}; {@code Got_NextLevel} (same file, 175-202)
     * executes one frame later, reading the next level out of
     * {@code LevelOrder} into {@code v_zone_act} and then, for a collected
     * giant ring, writing {@code v_gamemode = id_Special}. Doing either on the
     * expiry frame instead changes the level a frame early, which a whole-run
     * replay sees as the source segment losing ownership of its final
     * represented frame.
     */
    public void advanceToSpecialStageEntryRoutine() {
        this.specialStageEntryRoutineArmed = true;
    }

    /**
     * Request entry to special stage using the current game's access method.
     * Used by owners whose ROM counterpart writes the game mode in the same
     * object tick as the trigger (S2 {@code Obj79_Star}, S3K
     * {@code SSEntryFlash_GoSS}).
     */
    public void requestSpecialStageEntry() {
        this.specialStageRequestedFromCheckpoint = true;
    }

    /**
     * Consumes and clears the special stage request flag. A routine advance
     * armed by {@link #advanceToSpecialStageEntryRoutine()} becomes the
     * pending request here, so the entry is serviced by the next frame's
     * consume rather than this one.
     *
     * @return true if a special stage was requested since last check
     */
    public boolean consumeSpecialStageRequest() {
        boolean requested = specialStageRequestedFromCheckpoint;
        specialStageRequestedFromCheckpoint = false;
        if (!requested && specialStageEntryRoutineArmed) {
            specialStageEntryRoutineArmed = false;
            specialStageRequestedFromCheckpoint = true;
            specialStageEntryAdvancesLevel = true;
        }
        return requested;
    }

    /**
     * True exactly once, on the frame the armed {@code Got_NextLevel} body
     * runs, for the {@code v_zone_act} write that precedes its mode change.
     */
    public boolean consumeSpecialStageEntryLevelAdvance() {
        boolean advance = specialStageEntryAdvancesLevel;
        specialStageEntryAdvancesLevel = false;
        return advance;
    }

    /**
     * Non-consuming peek at a pending special-stage entry request. The LEVEL
     * tick's {@link #consumeSpecialStageRequest()} remains the only consumer;
     * this exists so trace-run replay can observe an organically raised
     * transition without swallowing it (spec 2026-07-18, addition #1).
     */
    public boolean isSpecialStageRequested() {
        return specialStageRequestedFromCheckpoint
                || specialStageEntryRoutineArmed;
    }

    /**
     * Consumes and clears the pending level-reload request for special-stage
     * return.
     *
     * @return true if the next act should be loaded before resuming gameplay
     */
    public boolean consumeSpecialStageReturnLevelReloadRequest() {
        boolean requested = specialStageReturnLevelReloadRequested;
        specialStageReturnLevelReloadRequested = false;
        return requested;
    }

    /**
     * Sets the special-stage return level reload flag.
     * Called by LevelManager when advancing to the next act after a special
     * stage return, and cleared at the start of seamless/level-load transitions.
     */
    public void setSpecialStageReturnLevelReloadRequested(boolean requested) {
        this.specialStageReturnLevelReloadRequested = requested;
    }

    /**
     * Signals that the next level load is a special-stage results return whose
     * mandatory title card the caller presents itself after the reload
     * (mirrors {@link #isBonusStageReturn()}). The load must request the card
     * — leaving its PLC queue live for the presented card's locked loop —
     * rather than model an omitted presentation, even headless.
     */
    public void setResultsReturnCardOwnedByCaller(boolean owned) {
        this.resultsReturnCardOwnedByCaller = owned;
    }

    /** Returns true when the caller presents the results-return title card itself. */
    public boolean isResultsReturnCardOwnedByCaller() {
        return resultsReturnCardOwnedByCaller;
    }

    /**
     * Signals that the next level load re-enters the game's {@code Level:}
     * routine from a level that was already running, rather than entering one
     * from a host/tooling boundary.
     *
     * <p>Sonic 1's end-of-act card only writes {@code f_restart}
     * (docs/s1disasm/_incObj/3A Got Through Card.asm:200-211); the main loop's
     * own {@code tst.w (f_restart).w} then falls out of {@code Level_MainLoop}
     * (docs/s1disasm/sonic.asm:3041-3055) straight back into {@code GM_Level}.
     * That re-entry runs the whole routine, so it reaches
     * {@code Level_TtlCardLoop} (sonic.asm:2814-2842) exactly as a first entry
     * does. The presentation is therefore part of the modelled restart and is
     * not omitted for a host that omits a direct entry's card.
     */
    public void setLevelRoutineReentry(boolean reentry) {
        this.levelRoutineReentry = reentry;
    }

    /** Returns true while the pending load re-enters a running level's {@code Level:} routine. */
    public boolean isLevelRoutineReentry() {
        return levelRoutineReentry;
    }

    // ================================================================
    //  Big ring return position
    // ================================================================

    /**
     * Saves the big ring return state (ROM: Save_Level_Data2 -> Saved2_*).
     */
    public void saveBigRingReturn(BigRingReturnState state) {
        saveBigRingReturn(state, null);
    }

    /**
     * Saves the big-ring return state together with the persistent object table
     * that S3K keeps across the special-stage reload.
     */
    public void saveBigRingReturn(BigRingReturnState state,
                                  PersistentRespawnState respawnState) {
        this.bigRingReturn = state;
        this.bigRingReturnRespawnState = respawnState;
    }

    /**
     * Saves the big-ring return state, folding the ring manager's
     * {@code Ring_status_table} into the same snapshot as the object table.
     * One ROM byte preserves both, so they travel together.
     *
     * @see #bigRingReturnRingStatusTable()
     */
    public void saveBigRingReturn(BigRingReturnState state,
                                  PersistentRespawnState respawnState,
                                  com.openggf.level.rings.RingManager ringManager) {
        saveBigRingReturn(state, respawnState == null || ringManager == null
                ? respawnState
                : respawnState.withRingStatusBits(ringManager.captureRingStatusTable()));
    }

    /** Returns true if a big ring return state is saved. */
    public boolean hasBigRingReturn() {
        return bigRingReturn != null;
    }

    /** Returns the saved big ring return state, or null if none. */
    public BigRingReturnState getBigRingReturn() {
        return bigRingReturn;
    }

    /** Returns the Object_respawn_table snapshot captured at big-ring entry. */
    public PersistentRespawnState getBigRingReturnRespawnState() {
        return bigRingReturnRespawnState;
    }

    /**
     * Returns the {@code Ring_status_table} snapshot to re-establish on the
     * next level load, or {@code null} when the load must start with a clean
     * table.
     *
     * <p>ROM {@code sub_EB1A} -- the rings-manager init reached from
     * {@code loc_E8BE} (docs/skdisasm/sonic3k.asm:18232-18238) -- wipes
     * {@code Ring_status_table} only while {@code Respawn_table_keep} is clear:
     * {@code tst.b (Respawn_table_keep).w} then {@code bne.s loc_EB30} skips
     * the entire {@code $400}-byte clear (:18561-18570). That is the same byte
     * which preserves {@code Object_respawn_table}, so the ring status rides in
     * the same snapshot rather than forming a second notion of "keep".
     *
     * <p>The predicate is the presence of that snapshot, and deliberately not
     * {@link #isLastStarPostHitSet()}: the star-post flag gates the ROM's saved
     * <em>position</em> restore ({@code Load_Starpost_Settings}, :61763-61836)
     * and has no bearing on the table wipe. {@code loc_618AC} clears
     * {@code Last_star_post_hit} while still setting {@code Respawn_table_keep}
     * (:128411-128421), so the two must not share a predicate. Nor is it keyed
     * on which entry path ran: {@code loc_61892} sets the flag for the
     * giant-ring entry (:128407-128412) and :128421 for the Super-Emerald
     * entry.
     */
    public long[] bigRingReturnRingStatusTable() {
        if (bigRingReturnRespawnState == null) {
            return null;
        }
        long[] bits = bigRingReturnRespawnState.ringStatusBits();
        return bits.length == 0 ? null : bits;
    }

    /** Clears the big ring return state. */
    public void clearBigRingReturn() {
        this.bigRingReturn = null;
        this.bigRingReturnRespawnState = null;
    }

    /**
     * Models the ROM's {@code Last_star_post_hit} gate on the saved-position
     * restore. A level load restores a saved position only while that flag is
     * non-zero: {@code loc_1BE46} (skdisasm/sonic3k.asm:38148-38151) tests it
     * and, when it is zero, falls through to {@code loc_1BE5E}
     * (sonic3k.asm:38157-38168) which reads {@code Sonic_Start_Locations}
     * instead. Only when it is non-zero does {@code Load_Starpost_Settings}
     * (sonic3k.asm:61763-61836) restore {@code Saved_} or, for
     * {@code Special_bonus_entry_flag}, {@code Saved2_}.
     * <p>
     * {@code Save_Level_Data2} (sonic3k.asm:61735) leaves the flag alone, so it
     * stays set here; {@code loc_618AC} (sonic3k.asm:128411-128417) writes
     * {@code move.b #0,(Last_star_post_hit).w} at sonic3k.asm:128414 when it
     * requests the Super Emerald arena restart, and the special-stage clear
     * re-sets bit 7 with {@code ori.b #$80,(Last_star_post_hit).w}
     * (sonic3k.asm:12119-12120 and 12673-12674) for the return leg.
     */
    private boolean lastStarPostHitSet = true;

    /** ROM {@code move.b #0,(Last_star_post_hit).w} (sonic3k.asm:128414). */
    public void clearLastStarPostHit() {
        this.lastStarPostHitSet = false;
    }

    /** ROM {@code ori.b #$80,(Last_star_post_hit).w} (sonic3k.asm:12673-12674). */
    public void setLastStarPostHit() {
        this.lastStarPostHitSet = true;
    }

    /** @see #clearLastStarPostHit() */
    public boolean isLastStarPostHitSet() {
        return lastStarPostHitSet;
    }

    // ================================================================
    //  Bonus stage requests
    // ================================================================

    /**
     * Request entry to a bonus stage from a star post bonus star.
     * Called by Sonic3kStarPostBonusStarChild on player touch.
     */
    public void requestBonusStageEntry(BonusStageType type) {
        this.bonusStageRequested = type;
    }

    /**
     * Consumes and clears the bonus stage request.
     * @return the requested bonus stage type, or null if none requested
     */
    public BonusStageType consumeBonusStageRequest() {
        BonusStageType requested = bonusStageRequested;
        bonusStageRequested = null;
        return requested;
    }

    /**
     * Non-consuming peek at a pending bonus-stage entry request; null when
     * none is pending. Mirrors {@link #isRespawnRequested()}.
     */
    public BonusStageType peekBonusStageRequest() {
        return bonusStageRequested;
    }

    /**
     * Signals that the next level load is a bonus stage return.
     * Set before {@code loadZoneAndAct()} so that {@code onInitLevel()} can
     * detect the return and skip intros. The checkpoint index is restored
     * to {@code CheckpointState} after the load completes.
     *
     * @param checkpointIndex the Last_star_post_hit value saved before bonus entry
     */
    public void setBonusStageReturnCheckpointIndex(int checkpointIndex) {
        this.bonusStageReturnCheckpointIndex = checkpointIndex;
    }

    /** Returns true if this level load is a bonus stage return. */
    public boolean isBonusStageReturn() {
        return bonusStageReturnCheckpointIndex >= 0;
    }

    /** Returns the checkpoint index for bonus stage return, or -1 if not returning. */
    public int getBonusStageReturnCheckpointIndex() {
        return bonusStageReturnCheckpointIndex;
    }

    /** Clears the bonus stage return signal. */
    public void clearBonusStageReturn() {
        this.bonusStageReturnCheckpointIndex = -1;
    }

    // ================================================================
    //  Title card requests
    // ================================================================

    /**
     * Requests a title card to be shown for the current zone/act.
     * Called when a new level is loaded.
     *
     * @param zone Zone index (0-10)
     * @param act  Act index (0-2)
     */
    public void requestTitleCard(int zone, int act) {
        this.titleCardRequested = true;
        this.titleCardZone = zone;
        this.titleCardAct = act;
    }

    /**
     * Requests an in-level (transparent) title card overlay.
     */
    public void requestInLevelTitleCard(int zone, int act) {
        requestInLevelTitleCard(zone, act, false);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay) {
        requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay, 0);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches) {
        requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay,
                resetAdditionalDispatches, false);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches, boolean lockPlayerControl) {
        requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay,
                resetAdditionalDispatches, lockPlayerControl, 0);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches, boolean lockPlayerControl,
                                        int exitAdditionalDispatches) {
        requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay,
                resetAdditionalDispatches, 0, lockPlayerControl, exitAdditionalDispatches);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches,
                                        int resetPhaseOneDispatchOverlap,
                                        boolean lockPlayerControl,
                                        int exitAdditionalDispatches) {
        requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay,
                resetAdditionalDispatches, resetPhaseOneDispatchOverlap,
                lockPlayerControl, exitAdditionalDispatches, 0);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches,
                                        int resetPhaseOneDispatchOverlap,
                                        boolean lockPlayerControl,
                                        int exitAdditionalDispatches,
                                        int exitPhaseOneDispatchOverlap) {
        this.inLevelTitleCardRequested = true;
        this.inLevelTitleCardZone = zone;
        this.inLevelTitleCardAct = act;
        this.inLevelTitleCardLevelGamestateResetRequested = resetLevelGamestateAtDisplay;
        this.inLevelTitleCardResetAdditionalDispatches = Math.max(0, resetAdditionalDispatches);
        this.inLevelTitleCardResetPhaseOneDispatchOverlap =
                Math.max(0, resetPhaseOneDispatchOverlap);
        this.inLevelTitleCardPlayerControlLockRequested = lockPlayerControl;
        this.inLevelTitleCardExitAdditionalDispatches = Math.max(0, exitAdditionalDispatches);
        this.inLevelTitleCardExitPhaseOneDispatchOverlap =
                Math.max(0, exitPhaseOneDispatchOverlap);
    }

    /**
     * Checks if a title card has been requested.
     *
     * @return true if a title card was requested since last check
     */
    public boolean isTitleCardRequested() {
        return titleCardRequested;
    }

    /**
     * Consumes and clears the title card request flag.
     *
     * @return true if a title card was requested since last check
     */
    public boolean consumeTitleCardRequest() {
        boolean requested = titleCardRequested;
        titleCardRequested = false;
        return requested;
    }

    /**
     * Consumes and clears the in-level title card request flag.
     */
    public boolean consumeInLevelTitleCardRequest() {
        boolean requested = inLevelTitleCardRequested;
        inLevelTitleCardRequested = false;
        return requested;
    }

    public boolean consumeInLevelTitleCardLevelGamestateResetRequest() {
        boolean requested = inLevelTitleCardLevelGamestateResetRequested;
        inLevelTitleCardLevelGamestateResetRequested = false;
        return requested;
    }

    public boolean hasPendingInLevelTitleCardHeldCounterDispatch() {
        return inLevelTitleCardRequested && inLevelTitleCardLevelGamestateResetRequested;
    }

    public int consumeInLevelTitleCardResetAdditionalDispatches() {
        int dispatches = inLevelTitleCardResetAdditionalDispatches;
        inLevelTitleCardResetAdditionalDispatches = 0;
        return dispatches;
    }

    public int consumeInLevelTitleCardResetPhaseOneDispatchOverlap() {
        int dispatches = inLevelTitleCardResetPhaseOneDispatchOverlap;
        inLevelTitleCardResetPhaseOneDispatchOverlap = 0;
        return dispatches;
    }

    public boolean consumeInLevelTitleCardPlayerControlLockRequest() {
        boolean requested = inLevelTitleCardPlayerControlLockRequested;
        inLevelTitleCardPlayerControlLockRequested = false;
        return requested;
    }

    public int consumeInLevelTitleCardExitAdditionalDispatches() {
        int dispatches = inLevelTitleCardExitAdditionalDispatches;
        inLevelTitleCardExitAdditionalDispatches = 0;
        return dispatches;
    }

    public int consumeInLevelTitleCardExitPhaseOneDispatchOverlap() {
        int dispatches = inLevelTitleCardExitPhaseOneDispatchOverlap;
        inLevelTitleCardExitPhaseOneDispatchOverlap = 0;
        return dispatches;
    }

    /**
     * Gets the zone index for the requested title card.
     *
     * @return zone index, or -1 if none requested
     */
    public int getTitleCardZone() {
        return titleCardZone;
    }

    /**
     * Gets the act index for the requested title card.
     *
     * @return act index, or -1 if none requested
     */
    public int getTitleCardAct() {
        return titleCardAct;
    }

    public int getInLevelTitleCardZone() {
        return inLevelTitleCardZone;
    }

    public int getInLevelTitleCardAct() {
        return inLevelTitleCardAct;
    }

    // ================================================================
    //  Transition requests (fade-coordinated)
    // ================================================================

    /**
     * True once something has asked the running level to stop being the level.
     *
     * <p>This is the engine's form of the ROM writes that end a level main
     * loop: {@code f_restart} (Sonic 1's end-of-act card and its death restart,
     * docs/s1disasm/_incObj/3A Got Through Card.asm:200-211,
     * _incObj/01 Sonic.asm:2069-2073) and a {@code v_gamemode} change written
     * from inside {@code ExecuteObjects} (the giant ring's Special Stage entry,
     * 3A Got Through Card.asm:196-201). All three games' level loops test them
     * on the instruction after their object pass and do not iterate again —
     * S1 {@code ExecuteObjects} then {@code tst.w (f_restart).w}
     * (docs/s1disasm/sonic.asm:3009-3018, 3041-3055), S2 {@code RunObjects}
     * then {@code tst.w (Level_Inactive_flag).w}
     * (docs/s2disasm/s2.asm:5095-5097), S3K {@code Process_Sprites} then
     * {@code tst.w (Restart_level_flag).w}
     * (docs/skdisasm/sonic3k.asm:7894-7896) — so a pending request means the
     * main loop has already ended even though the engine has not yet run the
     * consumer that acts on it.
     */
    public boolean hasPendingLevelExit() {
        return titleCardRequested
                || respawnRequested
                || nextActRequested
                || nextZoneRequested
                || specificZoneActRequested
                || creditsRequested
                || gameOverExitRequested != null
                || specialStageRequestedFromCheckpoint
                || bonusStageRequested != null;
    }

    /**
     * Request a respawn (death). GameLoop will handle the fade transition.
     */
    public void requestRespawn() {
        this.respawnRequested = true;
    }

    /**
     * Check and consume respawn request.
     *
     * @return true if respawn was requested
     */
    public boolean consumeRespawnRequest() {
        boolean requested = respawnRequested;
        respawnRequested = false;
        return requested;
    }

    public boolean isRespawnRequested() {
        return respawnRequested;
    }

    public void restoreRespawnRequested(boolean respawnRequested) {
        this.respawnRequested = respawnRequested;
    }

    /**
     * Request transition to next act. GameLoop will handle the fade transition.
     */
    public void requestNextAct() {
        this.nextActRequested = true;
    }

    /**
     * Check and consume next act request.
     *
     * @return true if next act was requested
     */
    public boolean consumeNextActRequest() {
        boolean requested = nextActRequested;
        nextActRequested = false;
        return requested;
    }

    /**
     * Request transition to next zone. GameLoop will handle the fade transition.
     */
    public void requestNextZone() {
        this.nextZoneRequested = true;
    }

    /**
     * Check and consume next zone request.
     *
     * @return true if next zone was requested
     */
    public boolean consumeNextZoneRequest() {
        boolean requested = nextZoneRequested;
        nextZoneRequested = false;
        return requested;
    }

    /**
     * Request transition to a specific zone and act. GameLoop will handle the fade transition.
     *
     * @param zone the zone index (0-based)
     * @param act the act index (0-based)
     */
    public void requestZoneAndAct(int zone, int act) {
        requestZoneAndAct(zone, act, false);
    }

    /**
     * Request transition to a specific zone and act with optional level deactivation
     * during the pending fade.
     *
     * @param zone                the zone index (0-based)
     * @param act                 the act index (0-based)
     * @param deactivateLevelNow  true to freeze level updates until the transition completes
     */
    public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {
        requestZoneAndAct(zone, act, deactivateLevelNow, -1);
    }

    /**
     * Request a zone/act transition with a track that must be started only
     * after the destination level has finished loading.
     *
     * <p>This is for cutscenes whose source track is still fading while they
     * hand control to a new zone.  Deferring the destination track prevents a
     * source-side fade command from silencing it.
     */
    public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow, int musicId) {
        this.requestedZone = zone;
        this.requestedAct = act;
        this.requestedMusicId = musicId;
        this.specificZoneActRequested = true;
        this.levelInactiveForTransition = deactivateLevelNow;
    }

    /**
     * Check and consume specific zone/act request.
     *
     * @return true if a specific zone/act was requested
     */
    public boolean consumeZoneActRequest() {
        boolean requested = specificZoneActRequested;
        specificZoneActRequested = false;
        return requested;
    }

    /**
     * Get the requested zone index. Only valid after consumeZoneActRequest() returns true.
     *
     * @return the requested zone index
     */
    public int getRequestedZone() {
        return requestedZone;
    }

    /**
     * Get the requested act index. Only valid after consumeZoneActRequest() returns true.
     *
     * @return the requested act index
     */
    public int getRequestedAct() {
        return requestedAct;
    }

    /**
     * @return a post-load music ID for the consumed zone/act request, or -1
     * when the destination should use its ordinary level-load music.
     */
    public int getRequestedMusicId() {
        return requestedMusicId;
    }

    // ================================================================
    //  Seamless transitions
    // ================================================================

    /**
     * Request an in-place seamless transition. GameLoop will execute it directly
     * without fade.
     */
    public void requestSeamlessTransition(SeamlessLevelTransitionRequest request) {
        if (request == null) {
            return;
        }
        this.pendingSeamlessTransitionRequest = request;
        this.seamlessTransitionRequested = true;
        this.levelInactiveForTransition = request.deactivateLevelNow();
    }

    /**
     * Consumes the pending seamless transition request.
     */
    public SeamlessLevelTransitionRequest consumeSeamlessTransitionRequest() {
        if (!seamlessTransitionRequested) {
            return null;
        }
        seamlessTransitionRequested = false;
        SeamlessLevelTransitionRequest request = pendingSeamlessTransitionRequest;
        pendingSeamlessTransitionRequest = null;
        return request;
    }

    // ================================================================
    //  Credits
    // ================================================================

    /**
     * Request transition to ending credits sequence.
     * Called by Sonic1EndingSTHObjectInstance after the STH logo timer expires.
     */
    public void requestCreditsTransition() {
        this.creditsRequested = true;
    }

    /**
     * Check and consume credits transition request.
     *
     * @return true if credits were requested
     */
    public boolean consumeCreditsRequest() {
        boolean requested = creditsRequested;
        creditsRequested = false;
        return requested;
    }

    // ================================================================
    //  Game over
    // ================================================================

    /**
     * The GAME OVER card's {@code move.b #id_Continue,(v_gamemode).w} /
     * {@code move.b #id_Sega,(v_gamemode).w}: ends the level main loop and
     * names the mode that follows. Idempotent because the card keeps writing
     * the mode on every frame after it has been dismissed.
     */
    public void requestGameOverExit(GameOverExit exit) {
        if (exit == null) {
            throw new IllegalArgumentException("exit must not be null");
        }
        this.gameOverExitRequested = exit;
    }

    /** @return the requested exit, or {@code null}; clears the request. */
    public GameOverExit consumeGameOverExitRequest() {
        GameOverExit exit = gameOverExitRequested;
        gameOverExitRequested = null;
        return exit;
    }

    public GameOverExit getGameOverExitRequested() {
        return gameOverExitRequested;
    }

    // ================================================================
    //  HUD / music suppression
    // ================================================================

    /**
     * Force-suppress HUD rendering. Used during credits demo playback
     * where the HUD should not appear regardless of zone settings.
     */
    public void setForceHudSuppressed(boolean suppressed) {
        this.forceHudSuppressed = suppressed;
    }

    /** Returns true if HUD rendering is force-suppressed. */
    public boolean isForceHudSuppressed() {
        return forceHudSuppressed;
    }

    /**
     * Suppresses the zone music that normally plays on the next loadLevel() call.
     * Resets after one use. Used by credits sequence to prevent zone music from
     * overriding the credits music.
     */
    public void setSuppressNextMusicChange(boolean suppress) {
        this.suppressNextMusicChange = suppress;
    }

    /** Returns true if the next music change should be suppressed. */
    public boolean isSuppressNextMusicChange() {
        return suppressNextMusicChange;
    }

    /**
     * Reads and clears the suppress-next-music flag.
     * <p>
     * The flag is strictly single-use: a caller that sets it but never reaches
     * the audio init step (preview-capture loads, a load that fails early, an
     * in-place act transition) must not leave it latched for a later level load,
     * which would silence that level's music until the next load or a respawn.
     *
     * @return true if the caller should skip the level music change
     */
    public boolean consumeSuppressNextMusicChange() {
        boolean suppress = suppressNextMusicChange;
        suppressNextMusicChange = false;
        return suppress;
    }

    // ================================================================
    //  Level inactive flag
    // ================================================================

    /**
     * Returns true while the current level should be treated as inactive for a
     * pending zone/act transition.
     */
    public boolean isLevelInactiveForTransition() {
        return levelInactiveForTransition;
    }

    /**
     * Sets the level-inactive-for-transition flag.
     */
    public void setLevelInactiveForTransition(boolean inactive) {
        this.levelInactiveForTransition = inactive;
    }

    // ================================================================
    //  Bulk reset
    // ================================================================

    /**
     * Clears all transition-related state.
     * Called from {@code LevelManager.resetState()}.
     */
    public void resetState() {
        specialStageRequestedFromCheckpoint = false;
        specialStageEntryRoutineArmed = false;
        specialStageEntryAdvancesLevel = false;
        specialStageReturnLevelReloadRequested = false;
        resultsReturnCardOwnedByCaller = false;
        levelRoutineReentry = false;
        bigRingReturn = null;
        bigRingReturnRespawnState = null;
        lastStarPostHitSet = true;
        bonusStageRequested = null;
        bonusStageReturnCheckpointIndex = -1;
        titleCardRequested = false;
        titleCardZone = -1;
        titleCardAct = -1;
        inLevelTitleCardRequested = false;
        inLevelTitleCardZone = -1;
        inLevelTitleCardAct = -1;
        inLevelTitleCardLevelGamestateResetRequested = false;
        inLevelTitleCardResetAdditionalDispatches = 0;
        inLevelTitleCardResetPhaseOneDispatchOverlap = 0;
        inLevelTitleCardPlayerControlLockRequested = false;
        inLevelTitleCardExitAdditionalDispatches = 0;
        inLevelTitleCardExitPhaseOneDispatchOverlap = 0;
        respawnRequested = false;
        nextActRequested = false;
        nextZoneRequested = false;
        specificZoneActRequested = false;
        seamlessTransitionRequested = false;
        creditsRequested = false;
        forceHudSuppressed = false;
        suppressNextMusicChange = false;
        levelInactiveForTransition = false;
        requestedZone = -1;
        requestedAct = -1;
        requestedMusicId = -1;
        pendingSeamlessTransitionRequest = null;
    }
}
