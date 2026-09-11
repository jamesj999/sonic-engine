package com.openggf.game;

/**
 * Interface for title card display management.
 * Title cards appear when a level first loads, after player respawns,
 * and when returning from special stages.
 */
public interface TitleCardProvider {
    /**
     * Initializes the title card for a zone/act.
     *
     * @param zoneIndex Zone index (0-10)
     * @param actIndex  Act index (0-2)
     */
    void initialize(int zoneIndex, int actIndex);

    /**
     * Initializes the title card in in-level overlay mode.
     * Default implementation falls back to normal title card init.
     */
    default void initializeInLevel(int zoneIndex, int actIndex) {
        initialize(zoneIndex, actIndex);
    }

    /**
     * Initializes a title card after the game's level routine has loaded a
     * destination and entered its native title-card boundary. Games whose
     * level routine rewrites the title owner's wait value can override this
     * entry point; ordinary host-level initialization keeps its normal timing.
     */
    default void initializeFreshLevelTransition(int zoneIndex, int actIndex) {
        initialize(zoneIndex, actIndex);
    }

    /**
     * Defers a fresh level-gamestate install to the native in-level title-card
     * display boundary. Games without that handoff can ignore the request.
     */
    default void requestLevelGamestateResetAtInLevelDisplay() {
        // No-op for games without S3K's in-level act-title handoff.
    }

    default void requestLevelGamestateResetAtInLevelDisplay(int additionalDispatches) {
        requestLevelGamestateResetAtInLevelDisplay();
    }

    default void requestLevelGamestateResetAtInLevelDisplay(
            int additionalDispatches, int phaseOneDispatchOverlap) {
        requestLevelGamestateResetAtInLevelDisplay(additionalDispatches);
    }

    /**
     * Arms a fresh-level runtime-art producer for the title-card handoff.
     * Games whose level assembly owns a later hardware queue can retain the
     * level index here and publish that work when the title-card owner reaches
     * its native completion boundary.
     */
    default void requestFreshLevelRuntimeArtHandoff(int levelIndex) {
        // No-op for games without a post-title-card runtime-art handoff.
    }

    default void requestInLevelPlayerControlLock() {
        // No-op unless an in-level title card owns the native controller lock.
    }

    default boolean ownsInLevelPlayerControlLock() {
        return false;
    }

    default boolean shouldLockPlayerControlForInLevelOverlay() {
        return false;
    }

    /**
     * Returns whether a fresh level-transition boundary may release its
     * destination state. Games whose native title owner keeps the loaded
     * player slots held through the visible exit can defer this beyond the
     * ordinary control-release point.
     */
    default boolean shouldCompleteFreshLevelTransitionBoundary() {
        return shouldReleaseControl();
    }

    /** Releases an in-level lock after its ROM object owner takes over. */
    default void releaseInLevelPlayerControlLockOwnership() {
    }

    default void requestInLevelExitAdditionalDispatches(int dispatches) {
        // No-op unless the title card models SST child retirement dispatches.
    }

    default void requestInLevelExitAdditionalDispatches(
            int dispatches, int phaseOneDispatchOverlap) {
        requestInLevelExitAdditionalDispatches(dispatches);
    }

    /**
     * Whether an active in-level title owner still dispatches on replay rows
     * whose level gameplay counter is held. Normal level-title overlays do not.
     */
    default boolean advancesOnHeldLevelCounter() {
        return false;
    }

    /** Whether an in-level title owner still owns the native held-counter phase. */
    default boolean ownsHeldLevelCounter() {
        return false;
    }

    /** Whether that held-counter phase came from a retained results owner mutating into a title card. */
    default boolean ownsRetainedResultsHeldLevelCounter() {
        return false;
    }

    /** Whether retained fixed-object cadence follows the playable history ring. */
    default boolean projectsRetainedResultsSpriteCadence() {
        return false;
    }

    /**
     * Hands the title card off to its gameplay-phase object lifetime when the
     * locked presentation itself was omitted.
     *
     * <p>The native locked title-card loop exits as soon as the zone-name piece
     * has reached its target and the pattern load cue is empty, but the title
     * card <em>objects</em> survive that exit and keep running on ordinary
     * gameplay frames: they idle for {@code anim_frame_duration} frames, then
     * slide back out and load the standard-water plus per-zone animal art on
     * the frame they leave. Omitting the presentation must not skip that tail,
     * because it is where the native game reclaims the VRAM the card occupied.
     *
     * <p>docs/s2disasm/s2.asm:4914-4925 (Level_TtlCard exit condition),
     * docs/s2disasm/s2.asm:5066-5080 (routine $16 + $2D handed to the pieces
     * immediately before the main level loop),
     * docs/s2disasm/s2.asm:27605-27637 (Obj34_WaitAndGoAway →
     * Obj34_LoadStandardWaterAndAnimalArt).
     *
     * <p>Games whose omitted-presentation art boundary is already reached
     * eagerly leave this a no-op.
     *
     * @param zoneIndex Zone index the card would have shown (the art the tail
     *                  loads is per-zone)
     * @param actIndex  Act index the card would have shown
     */
    default void beginOmittedPresentationExitTail(int zoneIndex, int actIndex) {
        // No-op unless the game models a gameplay-phase title-card exit tail.
    }

    /**
     * Sequences everything an omitted title-card presentation still owes.
     *
     * <p>Omitting the presentation does not delete the owner, so both halves of
     * its lifetime still run: {@link #beginOmittedPresentationExitTail} models
     * the tail, and {@link #beginOmittedFreshLevelOwner} models the entry art
     * {@code Obj_TitleCardInit} queues on the object's first dispatch
     * (docs/skdisasm/sonic3k.asm:62108-62164). The entry half runs only for a
     * load that owns the destination's fresh runtime art — a host-placed level
     * entry never reached the game's own {@code Level:} routine and so
     * installed no owner to queue it.
     *
     * @param ownsFreshLevelRuntimeArt whether this load owns the destination's
     *        fresh runtime art ({@code LevelLoadContext#isQueueFreshLevelRuntimeArt})
     */
    default void beginOmittedPresentation(
            int zoneIndex, int actIndex, boolean ownsFreshLevelRuntimeArt) {
        beginOmittedPresentationExitTail(zoneIndex, actIndex);
        if (ownsFreshLevelRuntimeArt) {
            beginOmittedFreshLevelOwner(zoneIndex, actIndex);
        }
    }

    /**
     * Whether an omitted fresh-level presentation still owns a live title-card
     * object whose per-iteration dispatch belongs to this row.
     *
     * <p>Omitting the presentation does not delete the owner. S3K installs
     * {@code Obj_TitleCard} in slot 5 and runs the locked loop
     * {@code loc_62CC} (docs/skdisasm/sonic3k.asm:7735-7748) until the object
     * clears {@code objoff_48}; every iteration of that loop is one V-int, so
     * the owner is dispatched once per recorded row whether or not anything is
     * drawn.
     */
    default boolean ownsOmittedFreshLevelPresentation() {
        return false;
    }

    /**
     * Installs the owner an omitted fresh-level presentation still has, and
     * runs the art work its first dispatch performs.
     *
     * <p>Called only for a load that owns the destination's fresh runtime art
     * ({@code LevelLoadContext#isQueueFreshLevelRuntimeArt}). A host-placed
     * level entry does not reach the game's own {@code Level:} routine and
     * installs no owner, so it queues nothing.
     */
    default void beginOmittedFreshLevelOwner(int zoneIndex, int actIndex) {
        // No-op unless the game models an omitted fresh-level title owner.
    }

    /**
     * Runs one dispatch of an omitted fresh-level owner, in the object-scan
     * position of its loop iteration. Renders nothing.
     */
    default void updateOmittedFreshLevelOwner() {
        // No-op unless the game models an omitted fresh-level title owner.
    }

    /** Publishes an armed fresh-level handoff when an omitted native owner reaches its exit. */
    default void completeOmittedPresentationFreshLevelRuntimeArtHandoff() {
        // No-op for games without a title-owned fresh-level hardware handoff.
    }

    /**
     * Publishes a fresh-level handoff when the destination title owner reaches
     * its native runtime-art boundary. The recording/live drivers use this
     * when a game keeps the loaded player slots held through that boundary;
     * games without such a split can leave it as a no-op.
     */
    default void completeFreshLevelRuntimeArtHandoff() {
        // No-op for games without a split fresh-level title boundary.
    }

    /**
     * Initializes the title card for a bonus stage entry.
     * S3K shows "BONUS STAGE" text; S1/S2 have no bonus stages so this is a no-op.
     */
    default void initializeBonus() {
        // No-op for games without bonus stages
    }

    /**
     * Updates the title card animation.
     * Call this once per frame while in TITLE_CARD mode.
     */
    void update();

    /**
     * Returns true if player control should be released.
     * control is released at the start of TEXT_WAIT phase,
     * allowing the player to move while text is still visible.
     *
     * @return true if control should be released
     */
    boolean shouldReleaseControl();

    /**
     * Returns true if the title card overlay should still be drawn.
     * The overlay remains visible during TEXT_WAIT and TEXT_EXIT phases,
     * even though player control has been released.
     *
     * @return true if overlay is active
     */
    boolean isOverlayActive();

    /**
     * Returns true if the title card animation is fully complete.
     *
     * @return true if complete
     */
    boolean isComplete();

    /**
     * Renders the title card.
     * Call this from Engine.draw() when in TITLE_CARD mode.
     */
    void draw();

    /**
     * Returns true if player movement physics should run during the
     * title card's locked phase.
     *
     * <p>S1 ROM: title card is a blocking routine; player physics does NOT
     * run, so Sonic stays at his spawn position until the title card ends.
     * This is important for SBZ3 where Sonic spawns at Y=0 and must fall
     * after the title card.
     *
     * <p>S2 ROM: player physics runs during the title card so Sonic can
     * settle onto the Tornado in SCZ, and onto ground in other zones.
     *
     * @return true to run player physics during lock, false to skip
     */
    default boolean shouldRunPlayerPhysics() {
        return true;
    }

    /**
     * Returns whether the engine's already-loaded level objects should execute
     * during the locked title-card phase.
     *
     * <p>This is distinct from whether the native game calls its generic object
     * dispatcher while the card is visible. Sonic 1's
     * {@code Level_TtlCardLoop} does call {@code ExecuteObjects}, but object RAM
     * still contains the title-card objects at that point. {@code ObjPosLoad}
     * populates the level objects only after the wait loop, immediately before
     * the one pre-gameplay {@code ExecuteObjects} pass. The engine renders title
     * cards through this provider instead of putting them in level object RAM,
     * so advancing {@code ObjectManager} during that wait would incorrectly age
     * the level objects.
     */
    default boolean shouldRunLevelObjectsDuringLockedPhase() {
        return true;
    }

    /**
     * Returns whether a locked title-card frame advances the production VBlank
     * clock without dispatching the engine's already-loaded level objects.
     */
    default boolean shouldAdvanceVblankClockDuringLockedPhase() {
        return false;
    }

    /**
     * Whether the level-only fixed object slots (Tails' tails, spindash dust,
     * shields, bubbles, invincibility stars) execute while the title card is up.
     *
     * <p>Sonic 2's {@code RunObjects} chooses its slot count from the game mode:
     * it runs the first {@code $80} slots ({@code Object_RAM..Object_RAM_End})
     * unless {@code Game_Mode} equals {@code GameModeID_Level} exactly, in which
     * case it extends to {@code LevelOnly_Object_RAM_End}
     * (docs/s2disasm/s2.asm:29805-29824). {@code Level} sets
     * {@code GameModeFlag_TitleCard} on entry (s2.asm:4758) and only
     * {@code Level_StartGame} clears it, immediately before {@code Level_MainLoop}
     * (s2.asm:5087). Every pre-main-loop {@code RunObjects} call — the one at
     * s2.asm:5006 and each title-card wait-loop iteration at s2.asm:5060-5066 —
     * therefore runs with the flag set and never reaches
     * {@code LevelOnly_Object_RAM}, which begins at {@code Tails_Tails}
     * (docs/s2disasm/s2.constants.asm:1145-1176). Obj05's first execution is
     * consequently the first {@code Level_MainLoop} pass,
     * {@code Level_frame_counter == 1}.
     *
     * <p>Sonic 3 &amp; Knuckles has no such gate: {@code Process_Sprites} always
     * walks the whole {@code Object_RAM}, including {@code Level_object_RAM}
     * (docs/skdisasm/sonic3k.asm:35963-35976, sonic3k.constants.asm:309).
     * Sonic 1 has no level-only fixed slot family at all.
     */
    default boolean shouldRunLevelOnlyFixedSlotsDuringLockedPhase() {
        return true;
    }

    /**
     * Number of object-only passes to run immediately before the title card
     * releases into the first normal level frame.
     *
     * <p>Sonic 1 performs {@code ObjPosLoad} and one {@code ExecuteObjects}
     * pass after the title-card wait and before {@code Level_MainLoop}. The
     * engine's title-card renderer does not occupy native object RAM, so this
     * explicit handoff pass reproduces that otherwise-missing lifecycle step.
     */
    default int levelObjectPreludePassesAtRelease() {
        return 0;
    }

    /**
     * Whether each release prelude pass also dispatches the playable slots.
     * Sonic 1's native {@code ExecuteObjects} includes Sonic in slot 0 before
     * the first {@code Level_MainLoop}; engines that split players from level
     * objects must opt that half of the dispatch back in explicitly.
     */
    default boolean shouldRunPlayerPreludeAtRelease() {
        return false;
    }

    /**
     * Resets the manager state.
     */
    void reset();

    /**
     * Gets the current zone index.
     * @return zone index
     */
    int getCurrentZone();

    /**
     * Gets the current act index.
     * @return act index
     */
    int getCurrentAct();
}
