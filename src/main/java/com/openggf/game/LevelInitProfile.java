package com.openggf.game;

import java.util.List;

/**
 * Declares the ordered initialization and teardown sequences for a game.
 * <p>
 * Each Mega Drive Sonic game has a {@code Level:} routine in its disassembly
 * that defines the exact initialization sequence for entering a level. Each
 * game module provides its own profile that faithfully transcribes these
 * ROM sequences:
 * <ul>
 *   <li><b>S1:</b> {@code sonic.asm:2956} — 44 steps, phases A-L</li>
 *   <li><b>S2:</b> {@code s2.asm:4753} — 57 steps, phases A-J</li>
 *   <li><b>S3K:</b> {@code sonic3k.asm:7505} — 65 steps, phases A-Q</li>
 * </ul>
 * The engine executes steps in declared order — no topological sorting,
 * no dependency resolution. The disassembly IS the dependency graph.
 */
public interface LevelInitProfile {
    /**
     * Initial object setup requested after a genuine successful fresh load.
     * Games without a proven native setup dispatch retain no extra work.
     */
    default InitialProcessSpritesLifecycle initialProcessSpritesLifecycle() {
        return InitialProcessSpritesLifecycle.NONE;
    }

    /**
     * Offset used to place already-registered sidekicks relative to the main
     * player. Defaults to the S1/S2-style InitPlayers offset; S3K overrides
     * with SpawnLevelMainSprites_SpawnPlayers parity.
     */
    default SidekickSpawnOffset sidekickSpawnOffset() {
        return SidekickSpawnOffset.S2_STYLE;
    }

    /**
     * Number of native main-player object dispatches performed after the
     * freshly cleared player slot is created, but before gameplay frame
     * counting begins.
     *
     * <p>This is distinct from title-card player physics. Sonic 1 runs one
     * {@code ExecuteObjects} pass after {@code ObjPosLoad}, then clears
     * {@code v_frame_counter} and enters {@code Level_MainLoop}. Other games
     * keep their existing startup sequencing unless their profile proves an
     * equivalent native pass.
     */
    default int freshMainPlayablePreludeFrames() {
        return 0;
    }

    /**
     * Whether a fresh level start must preserve the player's cleared grounded
     * status until the first ordinary playable dispatch.
     *
     * <p>S3K creates and initializes the player slot before the first compared
     * level frame. That frame therefore enters routine 2 grounded and lets
     * {@code Player_AnglePos} publish any terrain walk-off transition itself.
     * S1/S2 retain their established pre-frame ground-snap sequencing.
     */
    default boolean preserveFreshGroundedStatusUntilFirstDispatch() {
        return false;
    }

    /**
     * Advances the ROM-owned PLC lifecycle between the locked initial
     * presentation and ordinary gameplay.
     *
     * <p>The active game profile derives the sequence from production level
     * state and ROM data. Replay rows, fixture identity, and recorded readiness
     * are not inputs to this boundary.
     */
    default void completeInitialPresentationPlcs() {
        // Games without an S1/S2-style initial PLC presentation have no work.
    }

    /**
     * Number of omitted presentation iterations that ran the ROM's object loop
     * with the player objects already created, and so advanced player
     * animation and the persistent last-loaded-DPLC byte before gameplay.
     *
     * <p>Zero for a game whose omitted presentation carries no player object
     * loop.
     */
    default int skippedPresentationPlayableFrames() {
        return 0;
    }

    /**
     * How many of {@link #skippedPresentationPlayableFrames()} run before the
     * omitted presentation's first {@code WaitForVint}.
     *
     * <p>A game whose omitted presentation is a plain {@code WaitForVint} /
     * object-loop wait returns zero; a game that dispatches its object loop
     * once outside that wait reports that leading pass here so the replay does
     * not service a V-blank the ROM never reached.
     */
    default int skippedPresentationPlayableFramesBeforeFirstVBlank() {
        return 0;
    }

    /**
     * Frames the game's {@code Level:} routine spends fading out the previous
     * screen before it queues the returning level's initial PLCs.
     *
     * <p>Each such frame is a real {@code WaitForVBlank} row that runs
     * {@code RunPLC} against the just-cleared queue, so the level art's drain
     * starts this many rows after the game-mode handoff rather than at it.
     *
     * <p>Zero for a game whose return-load fade has not been verified against
     * its disassembly; the shared results-exit path then keeps its established
     * immediate-load sequencing.
     */
    default int preLevelFadeOutFrames() {
        return 0;
    }

    /**
     * Begins the ROM's level-entry routine before any counted pre-load fade.
     * A direct load invokes the same hook from its ordered profile as a
     * fallback; the level manager makes both paths one logical boundary.
     */
    default void beginLevelEntry() {
        // Games without work before their level-entry fade have no action.
    }

    /**
     * Services game-owned work for one physical vertical interrupt during
     * level entry or gameplay. The canonical caller is {@code LevelFrameStep};
     * profiles must not attach this work to object dispatch.
     */
    default void serviceLevelLoadVBlank() {
        // Games without deferred level-entry work have no action.
    }

    /** Cancels prepared work when a level-entry boundary is abandoned. */
    default void cancelPendingLevelLoadWork() {
        // Games without deferred level-entry work have no action.
    }

    /** Whether this profile owns a not-yet-published level-music request. */
    default boolean isLevelMusicPublicationPending() {
        return false;
    }

    /**
     * Counted {@code WaitForVBlank} rows the game's {@code Level:} routine
     * spends between its last un-timed load step and the first iteration of
     * the level main loop.
     *
     * <p>The routine's own object prelude stages the player's tiles and raises
     * the "graphics changed" flag; the very next V-int — the first row of this
     * tail — performs the transfer. The tail is frame-counted in the listing,
     * so the transfer's row is the level's first main-loop row minus this
     * count regardless of how long the un-timed load steps before it took.
     *
     * <p>Zero for a game whose tail has not been transcribed from its
     * disassembly; its staged transfer then belongs to the presentation
     * boundary that prepared it.
     */
    default int preLevelMainLoopDelayFrames() {
        return 0;
    }

    /**
     * Ordered steps for entering a level (title card through control unlock).
     * <p>
     * Maps to the game's {@code Level:} routine: S1 has 44 steps (phases A-L),
     * S2 has 57 steps (phases A-J), S3K has 65 steps (phases A-Q).
     * Each game currently provides 8 coarse-grained step groups that map to
     * ROM phase ranges. The context carries shared state between steps.
     */
    List<InitStep> levelLoadSteps(LevelLoadContext ctx);

    /**
     * Ordered steps for tearing down all singletons before the next level load.
     * <p>
     * These are the <em>inverse</em> of the ROM's init phases — each step
     * undoes the state set up by a corresponding phase in the {@code Level:}
     * routine. Executed by {@link com.openggf.tests.TestEnvironment#resetAll()}.
     */
    List<InitStep> levelTeardownSteps();

    /**
     * Subset of teardown safe for per-test reset (preserves level data).
     * <p>
     * Clears transient gameplay state (event routines, sprites, camera,
     * collision, fade, game-state counters, timers, water) while preserving
     * loaded level geometry, art, and audio cache. Executed by
     * {@link com.openggf.tests.TestEnvironment#resetPerTest()}.
     */
    List<InitStep> perTestResetSteps();

    /** Static field fixups applied after full teardown (e.g. GroundSensor wiring).
     *  Only used by levelTeardownSteps() path, not per-test reset. */
    List<StaticFixup> postTeardownFixups();
}
