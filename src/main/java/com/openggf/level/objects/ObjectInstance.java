package com.openggf.level.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.level.CarriedTitlePublicationTiming;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

import java.util.List;

public interface ObjectInstance {
    ObjectSpawn getSpawn();

    default int getX() {
        return getSpawn().x();
    }

    default int getY() {
        return getSpawn().y();
    }

    /**
     * Returns the object's X coordinate for touch collision geometry.
     * Most objects use their normal position; objects whose hitbox follows a
     * facing-dependent visual anchor can override this without moving their
     * logical/render-stream position.
     */
    default int getCollisionX() {
        return getX();
    }

    /** Returns the object's Y coordinate for touch collision geometry. */
    default int getCollisionY() {
        return getY();
    }

    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * Returns the object's X position as it was before the current frame's update loop.
     * Used by touch response collision checks to match ROM ordering, where ReactToItem
     * runs before ExecuteObjects — so objects are at pre-update positions during collision.
     * <p>
     * Defaults to current position. {@link AbstractObjectInstance} snapshots before updates.
     */
    default int getPreUpdateX() {
        return getX();
    }

    /**
     * Returns the object's Y position as it was before the current frame's update loop.
     * @see #getPreUpdateX()
     */
    default int getPreUpdateY() {
        return getY();
    }

    /** Returns the frame-start X coordinate for touch collision geometry. */
    default int getPreUpdateCollisionX() {
        return getPreUpdateX();
    }

    /** Returns the frame-start Y coordinate for touch collision geometry. */
    default int getPreUpdateCollisionY() {
        return getPreUpdateY();
    }

    /**
     * Snapshots the current position as the pre-update position.
     * Called by ObjectManager before the object update loop each frame.
     */
    default void snapshotPreUpdatePosition() {
        // Default no-op; AbstractObjectInstance provides implementation.
    }

    /**
     * Refreshes the object state used by touch-response collision before the
     * player slot runs in inline-order modules. Unlike
     * {@link #snapshotPreUpdatePosition()}, this must not advance unrelated
     * first-frame bookkeeping such as solid-contact gating.
     */
    default void snapshotTouchResponseState() {
        snapshotPreUpdatePosition();
    }

    /**
     * Returns the collision flags as they were before the current frame's object update.
     * ROM parity: ReactToItem runs in Sonic's slot (slot 0) BEFORE other objects update,
     * so it sees enemies at their previous frame's collision type. If an object activates
     * its collision type during this frame's update (e.g., lava geyser becoming active),
     * ReactToItem wouldn't see it. Default returns -1 (no snapshot, use current flags).
     */
    default int getPreUpdateCollisionFlags() {
        return -1;
    }

    /**
     * Returns true if this object needs to be updated on the same frame it was spawned.
     * <p>
     * ROM parity: In the ROM's ExecuteObjects, when an object at slot N creates a child
     * at slot M > N, the child IS processed in the same pass. The engine queues children
     * into pendingDynamicAdditions during the loop, so they miss the current frame.
     * Objects that return true here get a follow-up update in the finally block.
     * <p>
     * Use sparingly — most objects work correctly with the default 1-frame delay.
     * Only enable for objects whose position accuracy on the first frame matters
     * (e.g., projectiles checked for collision on subsequent frames).
     */
    default boolean requiresSameFrameUpdate() {
        return false;
    }

    /**
     * Returns true if this object was spawned during the current frame's update loop
     * and should be excluded from touch/hurt collision checks this frame.
     * <p>
     * ROM parity: In the ROM's ExecuteObjects, Sonic (slot 0) runs ReactToItem BEFORE
     * objects at higher slots create children. So newly created children are never checked
     * for touch collision on their spawning frame. The engine processes objects before
     * player physics, so children that receive same-frame updates must be excluded from
     * touch checks to match this behavior.
     */
    default boolean isSkipTouchThisFrame() {
        return false;
    }

    /**
     * Clears the same-frame-spawn touch-skip flag at the start of a later frame.
     * <p>
     * ROM parity: a hazard spawned at slot M during frame N's ExecuteObjects also
     * registers itself to {@code Collision_response_list} and runs {@code Draw_Sprite}
     * (sets obRender bit 7) that same frame, so by frame N+1 — when the player slot
     * reads the list — it is fully touch-eligible (1-frame latency, NOT 2). Modules
     * that consume the previous frame's collision-response list
     * ({@code touchResponseUsesPreviousCollisionResponseList}) skip the full
     * frame-start touch snapshot (to preserve that list), so they must still clear
     * this spawn-skip flag here, or a same-frame-spawned hazard would be skipped for
     * an extra frame.
     */
    default void clearSpawnTouchSkip() {
    }

    /**
     * Returns true when this object's just-finished routine published itself to
     * S3K's {@code Collision_response_list}. Most touch-capable objects tail-call
     * {@code Sprite_CheckDeleteTouch*}; routines that return before that tail
     * should override this for previous-list touch timing.
     */
    default boolean publishesTouchResponseListEntryThisFrame() {
        return true;
    }

    /**
     * Returns true if this object should skip SolidObject checks this frame.
     * ROM parity: on the first frame of an object's existence, obRender bit 7
     * is not yet set (DisplaySprite hasn't run), so the object's update skips
     * the SolidObject call. See "tst.b obRender(a0) / bpl.s" pattern in many
     * S1 objects (e.g., 46 MZ Bricks, 26 Monitor).
     */
    default boolean isSkipSolidContactThisFrame() {
        return false;
    }

    /**
     * Returns true when this object is currently within the camera viewport (ROM
     * render_flags bit 7 equivalent, set by Render_Sprites).  Solid contact
     * resolution is gated on this in ROM SolidObject_cont (s2.asm:35140-35145
     * SolidObject_OnScreenTest, sonic3k.asm:41390-41392 loc_1DF88,
     * s1disasm/_incObj/sub SolidObject.asm:124-126 Solid_ChkEnter / line 86-87
     * SolidObject2F).  Off-screen objects skip the side / top / bottom path so
     * the player keeps their velocity even when the camera has scrolled past.
     * Defaults to {@code true} so test stubs and pre-existing implementations
     * stay opt-in; {@link AbstractObjectInstance} provides the camera-bounds
     * check for production objects.
     */
    default boolean isWithinSolidContactBounds() {
        return true;
    }

    /**
     * Refreshes any retained render_flags state after the frame's camera step,
     * matching the ROM BuildSprites/Render_Sprites pass. Most objects query
     * camera bounds directly and do not need retained state.
     */
    default void refreshPostCameraRenderState() {
        // Default no-op.
    }

    void update(int vIntRunCount, PlayableEntity player);

    void appendRenderCommands(List<GLCommand> commands);

    boolean isHighPriority();

    /**
     * Palette lines whose high-priority foreground pixels occlude this sprite.
     * Bit 0 represents palette line 0 through bit 3 for palette line 3.
     */
    default int getTileOcclusionPaletteMask() {
        return isHighPriority() ? 0 : 0xF;
    }

    default int getPriorityBucket() {
        return RenderPriority.MIN;
    }

    boolean isDestroyed();

    /**
     * ROM parity: true when this destroy was triggered by an off-screen check
     * (Sprite_OnScreen_Test family in sonic3k.asm). ROM clears bit 7 of the
     * respawn-table entry ({@code bclr #7,(a2)} at loc_1B5A0 / sonic3k.asm:37275)
     * so the placement system can re-spawn the object when the camera returns.
     * Implementors that mark themselves destroyed via off-screen self-delete
     * must override and return {@code true}; the placement layer routes those
     * spawns to {@code removeFromActiveForUnload} (no permanent latch) instead
     * of {@code removeFromActive} (latched, models player-kill explosions
     * where ROM never clears the respawn bit).
     */
    default boolean isDestroyedRespawnable() {
        return false;
    }

    /**
     * Returns true if this object should remain active even when its spawn position
     * is outside the camera window. Used by objects like spin tubes that need to
     * continue controlling the player after they've moved far from the object's origin.
     */
    default boolean isPersistent() {
        return false;
    }

    /**
     * Called on a persistent dynamic object that was carried across a seamless
     * act-transition reload, after the world has been shifted by the transition
     * offset. Implementations should shift their world position by
     * {@code (offsetX, offsetY)} so they stay aligned with the offset
     * player/camera/level.
     *
     * <p>ROM: {@code Offset_ObjectsDuringTransition} applies the same delta to
     * every surviving object's {@code x_pos}/{@code y_pos} that it applies to the
     * players and camera (e.g. CNZ1BGE_DoTransition shifts the end signpost by
     * (-$3000, +$200) so it follows the level into Act 2). Objects that compute
     * their position from non-offset state (or are pinned to the camera) can
     * leave this as a no-op.
     */
    default void onCarriedAcrossSeamlessTransition(int offsetX, int offsetY) {
    }

    /**
     * Carries both the world delta and semantic title timing. The default
     * preserves the established offset-only hook for existing objects.
     */
    default void onCarriedAcrossSeamlessTransition(
            int offsetX,
            int offsetY,
            CarriedTitlePublicationTiming titleTiming) {
        onCarriedAcrossSeamlessTransition(offsetX, offsetY);
    }

    /**
     * Returns true when this object participates in ROM level-repeat coordinate
     * shifts such as MHZ's forced-scroll loop helper. The ROM gates these shifts
     * on {@code render_flags} bit 2, which normal level-space objects set after
     * initialization.
     */
    default boolean participatesInLevelRepeatOffset() {
        return false;
    }

    /**
     * Applies a ROM-style repeat-loop offset to the object's native
     * {@code x_pos}/{@code y_pos}. Called only when
     * {@link #participatesInLevelRepeatOffset()} returns true.
     */
    default void applyLevelRepeatOffset(int offsetX, int offsetY) {
    }

    /**
     * Returns the X coordinate used by ROM-style {@code out_of_range} checks.
     * <p>
     * Most objects use their current X position, but some S1 objects store a
     * separate anchor/origin in objoff_30/32/3A and feed that to the macro.
     * The counter-based unload path must use the same reference X or grouped
     * child objects can despawn before the parent anchor leaves range.
     */
    default int getOutOfRangeReferenceX() {
        return getX();
    }

    /**
     * Returns true when this object needs to replace the shared ROM-style
     * out_of_range X check with an object-specific delete predicate.
     * <p>
     * Most objects should leave this false and provide, at most, a custom
     * {@link #getOutOfRangeReferenceX()}. Use a full override only for ROM
     * routines that do not call the standard macro, such as objects that test
     * both ends of a movement range before deleting.
     */
    default boolean usesCustomOutOfRangeCheck() {
        return false;
    }

    /**
     * Returns true when the ROM routine for this object performs its
     * {@code out_of_range} / {@code RememberState} unload check at the END of
     * its routine, AFTER moving (e.g. S1 badniks run {@code SpeedToPos} then
     * {@code bra.w RememberState}, which tests the object's CURRENT post-move
     * {@code x_pos} — docs/s1disasm/_incObj/sub RememberState.asm:9).
     * <p>
     * When true, the S1 counter-based exec loop
     * ({@code ObjectManager.updateCounterBasedExecThenLoad}) skips the
     * pre-execute out_of_range check and instead re-checks out_of_range AFTER
     * {@code executeObjectWithSolidContext} has run the routine and applied the
     * frame's movement — matching ROM's post-move unload timing and mirroring
     * the non-counter {@code runExecLoop} (S2/S3K MarkObjGone at routine end).
     * <p>
     * Leave false for objects whose ROM {@code out_of_range} is at routine
     * START (static scenery / fixed-anchor objects that test before any move);
     * for those the pre-execute check already matches ROM. Static objects that
     * do not move see the same position either way, so this flag only changes
     * behaviour for objects that actually move within their routine.
     */
    default boolean checksOutOfRangeAfterRoutine() {
        return false;
    }

    /**
     * Object-specific out-of-range delete predicate. Called only when
     * {@link #usesCustomOutOfRangeCheck()} returns true.
     *
     * @param cameraX current camera X position
     * @return true when the object should delete itself as off-screen
     */
    default boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    /**
     * Returns true when the S1 counter-based out-of-range unload should clear
     * bit 7 of the object's respawn-table entry.
     * <p>
     * Most S1 remember-state objects route through {@code RememberState}, which
     * clears the bit before deleting. A few objects use a direct
     * {@code out_of_range.w DeleteObject,...} tail instead; those keep bit 7 set
     * and must not respawn when the cursor sees the same placement entry again.
     */
    default boolean clearsRespawnStateOnCounterBasedOutOfRange() {
        return true;
    }

    /**
     * Returns true if this object should stay in the active spawn set even after being
     * marked as remembered. Used by objects like monitors and capsules that need to
     * complete their destruction/animation sequence before being removed.
     * <p>
     * Objects that return true will:
     * - Be marked as remembered (won't respawn after death/restart)
     * - Stay in the active set to complete their logic
     * - Self-destruct by calling setDestroyed(true) when done
     * <p>
     * Default is false - most objects are removed from active immediately when remembered.
     */
    default boolean shouldStayActiveWhenRemembered() {
        return false;
    }

    /**
     * Returns the number of additional SST slots this object reserves for child
     * sub-objects. These slots are allocated via FindFreeObj (not FindNextFreeObj)
     * at spawn time, matching the ROM's object initialization.
     * <p>
     * ROM example: S1 ChainedStomper objects allocate 1 parent slot + N child slots
     * for multi-segment layout entries.
     *
     * @return number of extra slots to allocate (default 0)
     */
    default int getReservedChildSlotCount() {
        return 0;
    }

    /**
     * Returns true if ObjectManager should pre-allocate child slots for this object
     * BEFORE {@code syncActiveSpawnsLoad} runs (i.e. before ObjPosLoad).
     * <p>
     * ROM parity: in S1, ring objects (obj25) run Ring_Main during ExecuteObjects
     * BEFORE ObjPosLoad. Their child allocations via FindFreeObj therefore get lower
     * slot numbers than objects loaded by ObjPosLoad in the same frame. Returning
     * {@code true} here causes {@link #getReservedChildSlotCount()} slots to be
     * reserved before ObjPosLoad, giving children the correct lower slot numbers.
     * <p>
     * Objects that self-allocate child slots from within their own {@code update()}
     * (e.g. ChainedStomper via {@code allocateChildSlotsAfter}) should return
     * {@code false} to avoid double-allocation.
     *
     * @return {@code false} by default (self-allocating objects keep current behavior)
     */
    default boolean needsPreAllocatedChildSlots() {
        return false;
    }

    /**
     * Returns true when the object's ROM touch-list helper runs after its
     * routine has already moved the object's touch coordinates. Most inline
     * player-slot touch checks use the pre-update snapshot; this opt-in models
     * routines whose Collision_response_list entry represents current x/y.
     */
    default boolean usesCurrentTouchResponseState() {
        return false;
    }

    /**
     * Called when this object is being unloaded from the active object list.
     * Override to perform cleanup when the object goes off-screen or is removed.
     * Default implementation does nothing.
     */
    default void onUnload() {
        // Default no-op
    }

    /**
     * Append debug rendering commands for this object instance.
     * Called during the geometry phase when the OBJECT_DEBUG overlay is enabled.
     * Override to draw hitboxes, velocity vectors, AI state, etc.
     */
    default void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Default no-op
    }

    /**
     * Optional compact state details for trace-replay divergence reports.
     * Keep this to ROM-relevant fields that explain object/player mismatches.
     */
    default String traceDebugDetails() {
        return "";
    }
}
