package com.openggf.game;

import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Interface for game-specific dynamic level events.
 *
 * Level events handle runtime changes to camera boundaries, boss arena setup,
 * earthquake effects, and other zone-specific behaviors that trigger based on
 * player/camera position during gameplay.
 *
 * Implementations are game-specific (e.g., Sonic 2's RunDynamicLevelEvents).
 */
public interface LevelEventProvider {

    /**
     * Initialize level event state for a new level.
     * Called when a level is loaded or restarted.
     *
     * @param zone The zone index
     * @param act The act index within the zone
     */
    void initLevel(int zone, int act);

    /**
     * Update level events for the current frame.
     * Called once per frame before camera boundary easing.
     *
     * Implementations should check camera/player position and trigger
     * appropriate boundary changes or other level events.
     */
    void update();

    /**
     * Pre-physics level-event update, run once per frame BEFORE the player
     * object physics step.
     * <p>
     * The ROM main level loop runs {@code WaterEffects} (which calls
     * {@code OilSlides} for OOZ and {@code WindTunnel} for WFZ) immediately
     * before {@code RunObjects} executes the player object
     * (docs/s2disasm/s2.asm:5094-5095,5299-5305). Routines in that pre-object
     * slot therefore evaluate against the player position from the end of the
     * previous frame and can set the {@code sliding} status bit before the
     * player's friction/move code reads it the same frame. Routines that belong
     * after object execution (e.g. the Obj07 oil-surface platform) must stay in
     * {@link #update()}.
     * <p>
     * Default is a no-op for games/zones without a pre-object level routine.
     */
    default void updatePrePhysics() {
        // Default no-op
    }

    /**
     * Advances hardware-owned work on an emulator VBlank-only row where the
     * level loop itself did not execute.
     *
     * <p>Most level-event state is CPU-owned and must remain frozen. The
     * default is therefore a no-op; providers may advance only explicitly
     * VBlank/DMA-owned queues such as a delayed plane redraw.
     */
    default void advanceVblankOnlyState() {
        // Default no-op
    }

    /**
     * Updates fixed in-level object RAM that executes before dynamic level
     * object slots.
     * <p>
     * S1 stores {@code v_sonicbubbles} at object slot 13, before the level
     * object area starts at slot 32
     * (docs/s1disasm/s1disasm/_Variables.asm:68; ExecuteObjects.asm:11-31).
     * Its {@code FindFreeObj} allocations must therefore happen before later
     * level objects execute in the same frame. The default is a no-op for games
     * whose fixed sidecars run elsewhere.
     */
    default void updateFixedInLevelObjectsBeforeDynamicObjects() {
        // Default no-op
    }

    /**
     * Updates event-owned object state after dynamic objects and before the
     * camera scroll step. This slot models ROM workers that execute in the
     * object loop but publish camera boundaries consumed by DeformBgLayer in
     * the same frame.
     */
    default void updateAfterObjectsBeforeCamera() {
        // Default no-op
    }

    /**
     * Updates fixed in-level object RAM that is outside the dynamic SST scan and
     * executes after dynamic level object slots.
     * <p>
     * S3K runs fixed objects such as {@code Breathing_bubbles} and
     * {@code Breathing_bubbles_P2} after dynamic object RAM and before
     * {@code ScreenEvents} (docs/skdisasm/sonic3k.constants.asm:311-312;
     * docs/skdisasm/sonic3k.asm:7893-7898,35965). The default is a no-op for
     * games without fixed object sidecars.
     */
    default void updateFixedInLevelObjects() {
        // Default no-op
    }

    /**
     * Returns true when the level-event provider owns the drowning bubble
     * cadence through ROM fixed object sidecars. Shared player water code uses
     * this to avoid running its generic bubble RNG cadence in parallel.
     */
    default boolean ownsFixedDrowningBubbleCadence() {
        return false;
    }

    /**
     * Returns true when the level-event provider owns the drowning bubble
     * cadence for this specific player slot. Multi-player or novelty sidekick
     * configurations may only have ROM fixed sidecars for a subset of sprites.
     */
    default boolean ownsFixedDrowningBubbleCadence(AbstractPlayableSprite player) {
        return ownsFixedDrowningBubbleCadence();
    }

    /**
     * Returns true when an event/object-order bridge should treat a sidekick
     * follow step as still inside a ROM-visible push/on-object context.
     */
    default boolean isSidekickObjectOrderFollowSteeringContext(
            AbstractPlayableSprite sidekick,
            AbstractPlayableSprite effectiveLeader) {
        return false;
    }

    /**
     * Returns true when an event/object-order bridge should use the alternate
     * ROM-visible follow target for the immediate +/-1 sidekick x_pos nudge.
     * This can be narrower than the steering bridge above.
     */
    default boolean isSidekickObjectOrderFollowNudgeContext(
            AbstractPlayableSprite sidekick,
            AbstractPlayableSprite effectiveLeader) {
        return isSidekickObjectOrderFollowSteeringContext(sidekick, effectiveLeader);
    }

    /**
     * Returns true when a ROM door/platform support context should suppress
     * stale push-grace handling during sidekick follow steering.
     */
    default boolean isSidekickDoorSupportGraceFollowSteeringContext(
            AbstractPlayableSprite sidekick,
            ObjectInstance ridingObject) {
        return false;
    }

    /**
     * Returns true when an event marker path reaches Tails_Catch_Up_Flying
     * with the stored engine counter one tick behind the ROM-visible sidekick
     * CPU slot. This is separate from normal follow/auto-jump cadence.
     */
    default boolean usesSidekickRomVisibleCatchUpMarkerFrameCounterBridge(AbstractPlayableSprite sidekick) {
        return false;
    }

    /**
     * Returns true when level-event ordering should put an uninitialized
     * sidekick into a dormant marker state instead of the normal spawn path.
     */
    default boolean shouldEnterSidekickDormantMarker(AbstractPlayableSprite sidekick) {
        return false;
    }

    /**
     * Publishes level-event-owned animation state when a playable clears
     * {@code Status_InAir} during its movement slot. The callback runs before
     * the same slot's animation dispatch; providers must ignore landings whose
     * animation they do not currently own.
     */
    default void onPlayableLandingAnimationWrite(AbstractPlayableSprite playable) {
        // Default no-op
    }

    /**
     * Publishes state derived from the camera boundary after the
     * DynamicLevelEvents easing tail has run. The resulting values are consumed
     * by the next frame's player slots, matching the ROM's global-state order.
     */
    default void updateAfterCameraBoundaryEasing() {
        // Default no-op
    }

    /**
     * Runs the ROM {@code Level_MainLoop} tail slot, immediately after
     * {@code SynchroAnimate} and before the next frame's sprite build
     * (docs/s1disasm/sonic.asm:3032).
     * <p>
     * S1 uses this slot for {@code SignpostArtLoad}
     * (docs/s1disasm/sonic.asm:3183-3204), which lazily queues the end-of-act
     * signpost PLC and locks the left camera boundary. Because the slot runs
     * <em>after</em> {@code RunPLC} in the same frame, anything queued here is
     * only serviced from the following frame.
     * <p>
     * S2's analogue is {@code CheckLoadSignpostArt}; it has additional gates
     * ({@code Level_Has_Signpost}, {@code Two_player_mode}) and is not wired up
     * here. S3K has no equivalent routine. Default is therefore a no-op.
     */
    default void updateAtLevelLoopTail() {
        // Default no-op
    }

    /**
     * Called when a player falls below the bottom boundary.
     * If this returns true, the pit death is intercepted (e.g. zone transition).
     * <p>
     * ROM reference: Sonic_LevelBound in s1disasm - SBZ2 intercepts bottom
     * boundary death to transition to SBZ3 (LZ act 3).
     *
     * @param player the player about to die
     * @return true if the death should be suppressed
     */
    default boolean interceptPitDeath(AbstractPlayableSprite player) {
        return false;
    }
}
