package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

public interface SolidObjectProvider {
    SolidObjectParams getSolidParams();

    default SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fromProvider(this);
    }

    default SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.AUTO_AFTER_UPDATE;
    }

    default boolean isSolidFor(PlayableEntity player) {
        return true;
    }

    default boolean isTopSolidOnly() {
        return false;
    }

    /**
     * Number of position-history frames to use for this object's new
     * {@code SolidObjectTop} geometry check.
     * <p>
     * Most objects use the player's current engine position. Object-specific
     * providers may opt into a different sampled phase when porting an inline
     * ROM top-solid helper. The call site must cite the concrete disassembly
     * routine; for S3K {@code SolidObjectTop}'s new-landing geometry reads
     * {@code x_pos/y_pos/y_radius} at sonic3k.asm:41982-42015.
     */
    default int getTopSolidPlayerPositionHistoryFrames(PlayableEntity player) {
        return 0;
    }

    /**
     * Number of position-history frames to use for this object's new
     * {@code SolidObjectFull} geometry check.
     * <p>
     * This is the full-solid counterpart to
     * {@link #getTopSolidPlayerPositionHistoryFrames(PlayableEntity)}. It is
     * intended for ROM object slots that observe a particular player before
     * that player's movement slot has executed, while the engine's shared
     * player pass has already advanced the live position.
     */
    default int getFullSolidPlayerPositionHistoryFrames(PlayableEntity player) {
        return 0;
    }

    /**
     * Whether this top-solid object rejects the exact surface boundary before landing.
     * <p>
     * Most shared top-solid callers keep the established engine/profile behavior.
     * Some helper variants reject {@code d0 == 0}; S3K's shared
     * {@code SolidObjectTop_1P} accepts it and only rejects positive separation
     * or overlap below {@code -$10}.
     */
    default boolean rejectsZeroDistanceTopSolidLanding() {
        return false;
    }

    default boolean rejectsZeroDistanceTopSolidLanding(PlayableEntity player) {
        return rejectsZeroDistanceTopSolidLanding();
    }

    /**
     * Whether this object accepts a new top-solid landing at the exact surface
     * boundary even when the game's shared top-solid profile normally rejects it.
     * <p>
     * The shared {@code topSolidLandingAllowsZeroDist} game flag models
     * {@code PlatformObject_ChkYRange}, whose new-landing fall-through window is
     * gated per game (S2 rejects the exact boundary; S1/S3K accept it).  However,
     * top-solid objects that land players through {@code SolidObject_Landed}
     * (reached via {@code SolidObject_TopBottom}) rather than
     * {@code PlatformObject_ChkYRange} accept the exact surface boundary on every
     * game: {@code SolidObject_TopBottom} does {@code cmpi.w #$10,d3 / blo
     * SolidObject_Landed} (docs/s2disasm/s2.asm:35488-35494), and {@code blo}
     * (unsigned lower-than $10) covers {@code d3 == 0}.  The engine identifies the
     * {@code SolidObject_Landed} routine by {@code !usesPlatformObjectLandingSnap()}
     * (the same predicate that selects the {@code playerY - distY + 3} snap), so a
     * SolidObject-routine top-solid object accepts the {@code distY == 0} boundary
     * regardless of the PlatformObject-oriented game flag.  This is required for
     * S2's Obj82 swinging platform (Obj82_Main calls JmpTo23_SolidObject,
     * docs/s2disasm/s2.asm:57159), where a fast-falling rider can reach the pillar
     * top with exactly {@code d3 == 0} and must still land that frame.
     */
    default boolean allowsZeroDistanceTopSolidLanding(PlayableEntity player) {
        // SolidObject_Landed (s2.asm:35488-35494 blo covers d3==0) accepts the
        // exact boundary; PlatformObject_ChkYRange (game flag) governs the rest.
        return isTopSolidOnly() && !usesPlatformObjectLandingSnap();
    }

    /**
     * Whether a new airborne {@code SolidObjectTop} landing should be gated by
     * the player's previous-frame position before applying the current contact.
     * <p>
     * Most solids run in the engine's shared contact phase and use the current
     * player position. A few object-local ROM helpers execute before the player's
     * movement for the frame; these can otherwise accept the top surface one
     * frame too early after the engine has already applied movement.
     */
    default boolean gatesNewTopSolidLandingWithPreviousPosition() {
        return false;
    }

    /**
     * Called when a top-solid first-landing check reaches the exact surface
     * boundary and this provider rejected that boundary.
     */
    default void onRejectedZeroDistanceTopSolidLanding(PlayableEntity player) {
        // Default no-op
    }

    /**
     * Whether this solid can keep a grounded player attached during the
     * pre-movement terrain attachment check used by S2/S3K inline solid
     * resolution.
     * <p>
     * Normal solids rely on the previous frame's standing snapshot. ROM helper
     * objects spawned immediately before their first {@code SolidObjectTop}
     * call do not have a previous snapshot yet, but can still support the
     * player in the same frame.
     */
    default boolean providesPreMovementGroundAttachmentSupport() {
        return false;
    }

    /**
     * Whether this solid should still be evaluated while the player is in an
     * object-controlled state. Most scripted object-control states suppress
     * generic solid contacts; a few ROM routines still call SolidObject and only
     * reject specific signed object_control values.
     */
    default boolean allowsObjectControlledSolidContacts() {
        return false;
    }

    /**
     * Whether this object's side-separation path should reject players whose
     * object-control state has bit 7 set.
     * <p>
     * Keep this object-local: some captured/riding states still need normal
     * solid support while object-controlled, and only concrete ROM routines
     * that prove a signed {@code object_control} side-contact reject should opt
     * in here.
     */
    default boolean rejectsBit7ObjectControlSideContact(PlayableEntity player) {
        return false;
    }

    /**
     * Whether this object's new-contact path rejects players whose
     * object-control state has bit 7 set, before side/top classification and
     * before the routine writes {@code y_pos}.
     * <p>
     * Defaults to {@code true}, because every shared solid tail in all three
     * ROMs performs the same signed {@code object_control} test:
     * <ul>
     *   <li>S1 {@code Solid_Collision}
     *       ({@code _incObj/sub SolidObject.asm:183-184}) and
     *       {@code MoveWithPlatform}
     *       ({@code _incObj/sub MvSonicOnPtfm.asm:26-27}) —
     *       {@code tst.b (f_playerctrl).w; bmi}.</li>
     *   <li>S2 {@code SolidObject_ChkBounds} ({@code s2.asm:35376-35377}) and
     *       the platform-landing writer {@code loc_19BA2}
     *       ({@code s2.asm:35651-35652}) —
     *       {@code tst.b obj_control(a1); bmi}.</li>
     *   <li>S3K {@code SolidObject_cont}'s {@code loc_1DFFE}
     *       ({@code sonic3k.asm:41443-41444}) and the sloped/platform landing
     *       tail {@code loc_1E45A} ({@code sonic3k.asm:42012-42013}, with its
     *       reverse-gravity twin at {@code :42060-42061}) —
     *       {@code tst.b object_control(a1); bmi}. The sloped tail's test sits
     *       immediately before its {@code move.w d2,y_pos(a1)}, so a bit-7
     *       rider is never repositioned by the object.</li>
     * </ul>
     * These are sign tests ({@code tst.b}/{@code bmi}), not "object control is
     * nonzero" tests, so positive states such as CNZ's {@code $42} wire cage
     * still pass. Existing standing-bit/riding branches are consumed before
     * these tails and are unaffected.
     * <p>
     * An object may override to {@code false} only where its own ROM routine
     * demonstrably bypasses the shared tail; cite the routine when doing so.
     */
    default boolean rejectsBit7ObjectControlNewSolidContact(PlayableEntity player) {
        return true;
    }

    /**
     * Whether this object keeps the player attached while object-local code,
     * rather than the generic solid routine, owns player positioning.
     * <p>
     * Default is false: when an object is no longer solid for the current
     * player, the generic platform path treats that as a ride exit. Bespoke ROM
     * objects can opt in when their routine keeps the standing/on-object state
     * set while suppressing normal solid contacts for an object-control capture.
     * Example: S2 CNZ Obj85 launcher springs set {@code obj_control=$81} and
     * continue writing {@code x_pos/y_pos} in loc_2ADFE / loc_2AFFE without
     * clearing {@code status.player.on_object}; they only clear it on off-screen
     * release or launch (docs/s2disasm/s2.asm:57520-57545, 57684-57711).
     */
    default boolean preservesObjectManagedRideWhileNotSolidFor(PlayableEntity player) {
        return false;
    }

    /**
     * Whether this object's own stale standing bit should consume an airborne
     * player by clearing support and returning no contact before new-contact
     * resolution.
     * <p>
     * This is the {@code SolidObjectFull_1P} entry contract: when a0.d6 is set
     * and {@code Status_InAir} is set, the helper branches to the stale-rider
     * return instead of reaching {@code SolidObject_cont}. Keep this opt-in so
     * custom objects whose standing bits gate object-local capture paths, such
     * as CNZ cylinders, are not forced through plain full-solid semantics.
     */
    default boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        return false;
    }

    /**
     * Whether an airborne rider must retain this object's riding record until
     * this same object's inline solid checkpoint consumes its standing bit.
     *
     * <p>Use this for native routines whose post-{@code SolidObjectFull}
     * behavior depends on the per-object {@code a0.d6} result. An earlier
     * object's solid checkpoint must not consume that state on its behalf.
     */
    default boolean airborneRiderUnseatRequiresOwnCheckpoint(PlayableEntity player) {
        return false;
    }

    /**
     * Whether a stale riding record that would be consumed by this object's
     * airborne standing-bit branch is also ineligible for pre-movement
     * ground-recovery support.
     * <p>
     * Most objects that opt into {@link #airborneStaleStandingBitReturnsNoContact}
     * still use the engine riding record as the best available live-support
     * signal before their own solid pass runs. Obj69-style callers can opt in
     * when the ROM object tail reaches standard {@code SolidObject} late enough
     * that the stale airborne branch must be visible to player movement first.
     */
    default boolean suppressesGroundingRecoveryFromAirborneStaleRide(PlayableEntity player) {
        return false;
    }

    /**
     * Whether a continued-ride exit clears this object's standing bit immediately.
     * <p>
     * Default remains false for existing folded/custom solid profiles whose
     * standing latch is consumed by later object-local code. Concrete ROM
     * {@code PlatformObject} users can opt in when their own status byte drives
     * same-frame behavior such as platform sag.
     */
    default boolean clearsStandingBitOnContinuedRideExit(PlayableEntity player) {
        return false;
    }

    /**
     * Whether a fresh standing contact established by this object should keep
     * {@code Status_OnObj} if object-local code makes the rider airborne later
     * in the same frame.
     * <p>
     * Most solids must return false so their normal airborne stale-rider branch
     * still clears support. Use this only for ROM routines that run their solid
     * helper first, then perform a same-frame status write such as a supported
     * hurt launch.
     */
    default boolean keepsOnObjWhenAirborneAfterSameFrameStandingContact(PlayableEntity player) {
        return false;
    }

    /**
     * Optional centre-Y write for objects that preserve a ride while object-local
     * code owns player positioning. Return {@code null} to leave Y unchanged.
     */
    default Integer getObjectManagedRideCentreY(PlayableEntity player, int objectY, SolidObjectParams params) {
        return null;
    }

    /**
     * Object-local correction applied when the shared solid resolver snaps a
     * player onto this object's top surface.
     * <p>
     * Use this for ROM helper quirks that belong to a concrete object routine
     * instead of broadening the shared top-solid behavior across games.
     */
    default int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        return 0;
    }

    /**
     * Object-local correction applied while the shared rider path re-seats a
     * player on this object's top surface. This is separate from fresh landing
     * placement because ROM objects may execute those paths at different SST
     * phases.
     */
    default int getContinuedRideSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        return 0;
    }

    /**
     * Whether a player landing on this object's top surface keeps its rolling
     * state instead of running the generic floor roll-clear.
     * <p>
     * The shared object-landing path mirrors {@code Sonic_ResetOnFloor} /
     * {@code Solid_ResetFloor}, which clears the rolling flag (and applies the
     * full roll-exit y_radius restoration) on landing. A few object routines
     * deliberately keep the player curled: they call only
     * {@code SolidObject_Always_SingleCharacter}/{@code RideObject_SetRide} for
     * the snap and never invoke {@code Sonic_ResetOnFloor}, so the rolling flag
     * and ball radii survive untouched. Returning {@code true} skips the generic
     * roll-clear for those objects so the landing y_pos is left exactly where the
     * snap placed it.
     */
    default boolean landingPreservesRolling(PlayableEntity player) {
        return false;
    }

    /**
     * Whether this object's landing routine enters the full S2
     * {@code Sonic_ResetOnFloor} entry and therefore publishes Walk even when
     * the player was not rolling. Keep this separate from roll clearing:
     * object-local RideObject/PlatformObject paths can establish a ride without
     * owning the raw animation byte.
     */
    default boolean nonRollingLandingPublishesWalk(PlayableEntity player) {
        return false;
    }

    /**
     * Whether this solid uses S3K's {@code SolidObjectFull} Player 2 visibility
     * gate. That helper processes Player 1, then skips Player 2 when Player 2's
     * {@code render_flags} bit 7 is clear (sonic3k.asm:41003-41008).
     */
    default boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        return false;
    }

    /**
     * Whether this object uses monitor-style solidity (SPG: "Item Monitor").
     * Monitor solidity differs from normal solid objects:
     * - No +4 added during vertical overlap check
     * - Landing only if player Y relative to top < 16 AND within object width + 4px margin
     * - Never pushes player downward, only to sides
     */
    default boolean hasMonitorSolidity() {
        return false;
    }

    /**
     * Vertical offset used by monitor-style solid overlap checks.
     * <p>
     * Defaults to zero to preserve existing monitor behavior. S3K monitors opt
     * into the generic {@code SolidObject_cont} offset because their monitor
     * gate branches directly there.
     */
    default int getMonitorSolidObjectVerticalOffset() {
        return 0;
    }

    /**
     * Whether this object should use the generic sticky contact buffer while being ridden.
     * <p>
     * The buffer reduces edge jitter for moving platforms, but some hazards should not
     * preserve contact through this tolerance.
     */
    default boolean usesStickyContactBuffer() {
        return true;
    }

    /**
     * Whether side-contact at exact edge overlap (distX == 0) should preserve
     * player subpixel motion instead of immediately zeroing horizontal speed.
     * <p>
     * Most static solids should return false to keep the player stable against
     * walls and avoid 1px edge jitter. Push-driven objects that depend on ROM
     * edge cadence (for example Sonic 1 push blocks) can return true.
     */
    default boolean preservesEdgeSubpixelMotion() {
        return false;
    }

    /**
     * Whether an exact zero-distance side contact keeps the native X subpixel,
     * horizontal speed, and ground speed unchanged.
     * <p>
     * This is narrower than {@link #preservesEdgeSubpixelMotion()}: it affects
     * only {@code distX == 0} and leaves ordinary nonzero side correction on the
     * shared pixel-snapping path. Use it for standard {@code SolidObject}
     * callers whose inclusive edge reaches {@code SolidObject_AtEdge} without
     * entering {@code SolidObject_StopCharacter}.
     */
    default boolean preservesZeroDistanceSideContactMotion() {
        return false;
    }

    /**
     * Whether this object preserves a same-frame SPECIAL TouchResponse velocity
     * handoff through the following airborne post-movement side contact.
     * <p>
     * Default is false because shared Sonic solid helpers zero horizontal speed
     * on moving side contact across games. Object-local ROM ordering exceptions
     * must opt in with a citation and are additionally gated on an actual
     * SPECIAL touch callback in the current frame.
     */
    default boolean preservesPostSpecialTouchAirborneSideVelocity() {
        return false;
    }

    /**
     * Whether a moving side contact should preserve the player's horizontal
     * velocity while still applying the side position correction and push bits.
     * <p>
     * Most shared {@code SolidObject} callers keep the ROM stop-on-side-contact
     * behavior. Object-local ordering exceptions can opt in when the engine's
     * inline post-physics checkpoint observes a side contact after the ROM frame
     * would have already let the player slot overwrite the stopped velocity.
     */
    default boolean preservesMovingSideContactVelocity(PlayableEntity player) {
        return false;
    }

    /**
     * Whether a zero horizontal velocity on the object's left side still routes
     * through {@code SolidObject_StopCharacter}.
     * <p>
     * Most existing solids keep the established engine-side sign convention.
     * Objects that have trace coverage for the S2 {@code SolidObject_InsideLeft}
     * boundary can opt in with a concrete disassembly citation.
     */
    default boolean zeroXSpeedStopsOnLeftSideContact() {
        return false;
    }

    /**
     * Whether a side classification should return no contact before applying
     * side correction or speed zeroing.
     * <p>
     * This is for ROM objects whose own per-slot standing bit can route a
     * player through a stale-rider branch before {@code SolidObject_cont}.
     * Use only with an object-level disassembly citation; ordinary full solids
     * should keep the shared side-contact behavior.
     */
    default boolean sideContactReturnsNoContact(PlayableEntity player) {
        return false;
    }

    /**
     * Whether a grounded lower-half edge escape from the squash path should
     * set the player/object push bits even when the player is not moving into
     * the object.
     * <p>
     * Most callers keep the established engine behavior. Concrete ports of
     * S2/S3K {@code SolidObjectFull} can opt in when their ROM path proves the
     * edge escape branches back into the same side-contact helper that sets
     * push for any grounded side separation.
     */
    default boolean groundedSquashEdgeSideContactSetsPush() {
        return false;
    }

    /**
     * Whether this object's standing/pushing latch key should be the live
     * object instance rather than {@link ObjectInstance#getSpawn()}.
     * <p>
     * ROM {@code SolidObjectFull} stores standing and pushing bits in the
     * object's SST {@code status(a0)} byte. Objects that rebuild a dynamic
     * engine spawn as they move still need those bits to survive from the
     * contact frame into the next no-contact clear path for the same SST slot.
     */
    default boolean usesInstanceSolidStateLatchKey() {
        return false;
    }

    /**
     * Whether this object's native pushing bit remains authoritative while its
     * state machine deliberately skips solid checkpoints.
     * <p>
     * Use only when the ROM stores the bit in the live SST status byte and has
     * intervening states that return without calling the solid helper. The next
     * checkpoint then owns the delayed no-contact release even though the
     * player's live push bit and one-frame checkpoint history have expired.
     */
    default boolean preservesNativePushLatchAcrossSkippedSolidCheckpoints() {
        return false;
    }

    /**
     * Whether CPU sidekick follow steering should treat this still-ridden object
     * as preserving the previous frame's push bit until the ROM CPU slot reads
     * it.
     * <p>
     * This is intentionally object-local. Most objects should not bridge a
     * cleared {@code Status_Push}: their current helper phase already matches
     * what the CPU sees. Plain SolidObject/SolidObjectFull callers that keep
     * standing/pushing bits in the live object status byte can opt in when trace
     * evidence shows the engine has reconciled support before TailsCPU_Normal
     * reads {@code Status_Push} (S2 loc_24836, docs/s2disasm/s2.asm:39291-39294).
     */
    default boolean preservesSidekickCpuPushGraceWhileRiding(PlayableEntity player) {
        return false;
    }

    /**
     * Whether CPU sidekick follow steering should treat this latched object as
     * preserving a previous push bit after the live ride record has cleared.
     * <p>
     * This is narrower than {@link #preservesSidekickCpuPushGraceWhileRiding(PlayableEntity)}:
     * it only applies when the player is no longer marked on-object locally, but
     * ROM-visible object state can still feed the {@code Status_Push} branch at
     * the sidekick CPU slot. Keep it object-local and do not use it to infer
     * push from route/frame-specific trace data.
     */
    default boolean preservesSidekickCpuPushGraceAfterRideClears(PlayableEntity player) {
        return false;
    }

    /**
     * Whether a current {@code Status_Push} bypass while the CPU sidekick is
     * still riding this object should consume the object-order input history
     * slot rather than the ordinary already-loaded follow slot.
     * <p>
     * Most objects must return false: ROM {@code TailsCPU_Normal} loads the
     * delayed input and delayed status from the same history entry before
     * testing {@code Status_Push}. Objects whose own solid/drop-floor routine
     * runs after the sidekick CPU slot can opt in when trace evidence shows the
     * ROM-visible push bit belongs to that later object-order window.
     */
    default boolean usesSidekickCpuCurrentPushObjectOrderInputDelay(PlayableEntity player) {
        return false;
    }

    /**
     * Whether {@code TailsCPU_Normal}'s delayed leader {@code Status_Push} test
     * should use the object-order status sample while this CPU sidekick rides
     * the object.
     * <p>
     * This is narrower than {@link #usesSidekickCpuCurrentPushObjectOrderInputDelay(PlayableEntity)}:
     * it affects the d4-style push-bypass decision, not the delayed Ctrl_1 word
     * consumed after the branch has already been chosen.
     */
    default boolean usesSidekickCpuPushBypassObjectOrderStatusDelay(PlayableEntity player) {
        return false;
    }

    /**
     * Whether a CPU sidekick riding this object should treat the delayed leader
     * status sample as still carrying {@code Status_Push} when deciding the
     * {@code TailsCPU_Normal} push-bypass branch.
     * <p>
     * This is for folded engine objects that represent multiple ROM SST solid
     * slots. ROM stores each slot's standing/pushing bits independently, and the
     * delayed {@code Sonic_Stat_Record_Buf} sample can still include a child-slot
     * push bit even when the folded engine history only retained the parent slot.
     */
    default boolean preservesSidekickDelayedLeaderPushWhileRiding(PlayableEntity sidekick) {
        return false;
    }

    /**
     * Whether a CPU sidekick's persistent {@code interact(a0)} slot should
     * preserve a just-cleared {@code Status_Push} bit for the ROM CPU read even
     * after the engine has already released the live ride state.
     * <p>
     * This is narrower than {@link #preservesSidekickCpuPushGraceWhileRiding}:
     * it applies only when the player is no longer riding the object but the ROM
     * interact slot still dereferences this object's SST status byte before the
     * object's later solid/drop-floor routine refreshes it.
     */
    default boolean preservesSidekickCpuPushGraceFromInteractSlot(PlayableEntity player) {
        return false;
    }

    /**
     * Whether an interact-slot object keeps current {@code Status_Push} visible
     * to {@code TailsCPU_Normal} even after the local push-grace counter has
     * decayed and the sidekick is not stationary relative to the leader.
     * <p>
     * This is narrower than {@link #preservesSidekickCpuPushGraceFromInteractSlot(PlayableEntity)}.
     * Use only for object-local ROM evidence where the sidekick's persistent
     * {@code interact(a0)} slot still dereferences this object's live
     * {@code SolidObject} status byte at the CPU read.
     */
    default boolean preservesMovingSidekickCpuPushAtZeroGraceFromInteractSlot(PlayableEntity player) {
        return false;
    }

    /**
     * Whether an approved interact-slot push bridge must also publish
     * {@code Status_Push} to the sidekick's later movement/animation pass.
     * <p>
     * Most interact-slot bridges only reproduce the earlier CPU-control read.
     * Use this narrower hook when ROM object ordering leaves the same live SST
     * push bit visible after {@code TailsCPU_Normal} as well.
     */
    default boolean publishesSidekickCpuPushFromInteractSlot(PlayableEntity player) {
        return false;
    }

    /**
     * Whether a CPU sidekick's persistent {@code interact(a0)} slot should keep
     * the delayed leader status sample in the push-visible branch after the live
     * push-grace window has expired.
     * <p>
     * This affects {@code TailsCPU_Normal}'s delayed d4 {@code Status_Push}
     * fall-through decision, not the current sidekick push-bypass branch.
     */
    default boolean preservesSidekickDelayedLeaderPushFromInteractSlot(PlayableEntity player) {
        return false;
    }

    /**
     * Minimum remaining push-grace frames for the released-interact CPU bridge.
     */
    default int sidekickCpuPushGraceMinimumFramesFromInteractSlot(PlayableEntity player) {
        return Integer.MAX_VALUE;
    }

    /**
     * Maximum remaining push-grace frames for the released-interact CPU bridge.
     */
    default int sidekickCpuPushGraceMaximumFramesFromInteractSlot(PlayableEntity player) {
        return Integer.MIN_VALUE;
    }

    /**
     * Whether this object's continued-riding path should keep the player's
     * {@code Status_Push} bit set even when the current frame does not produce
     * a fresh side-contact classification.
     * <p>
     * Ordinary solids should return false: their push bit is owned by current
     * side contact and the normal movement/animation clear paths. Multi-piece
     * ROM objects with adjacent-slot geometry can opt in when a rider remains
     * pressed into a neighbouring step face during the continued ride path.
     */
    default boolean preservesRidingPushStatus(PlayableEntity player) {
        return false;
    }

    /**
     * Minimum remaining push-grace frames for the CPU sidekick riding bridge.
     * <p>
     * The default keeps the conservative shared threshold. Objects with ROM
     * evidence that their {@code SolidObject} status byte stays visible longer
     * to Tails' CPU slot may lower this value locally.
     */
    default int sidekickCpuPushGraceMinimumFramesWhileRiding(PlayableEntity player) {
        return Integer.MAX_VALUE;
    }

    /**
     * Maximum remaining push-grace frames for the CPU sidekick riding bridge.
     * <p>
     * Some object-local ROM visibility windows are late-tail handoffs rather
     * than the whole grace span. The default leaves existing providers
     * unconstrained.
     */
    default int sidekickCpuPushGraceMaximumFramesWhileRiding(PlayableEntity player) {
        return Integer.MAX_VALUE;
    }

    /**
     * Whether an engine-stale push-grace window must leave ordinary sidekick
     * follow steering active while this object remains the live support.
     * Native CPU code sees the current cleared {@code Status_Push} bit in this
     * case and still executes its +/-1 follow-position nudge.
     */
    default boolean sidekickCpuStalePushGraceKeepsFollowSteeringWhileRiding(PlayableEntity player) {
        return false;
    }

    /**
     * Whether the right edge of the full solid X window is inclusive.
     * <p>
     * Inclusivity is a property of the ROM routine family, not the object.
     * Every full-solid X gate rejects with {@code bhi}, making
     * {@code relX == width * 2} a valid zero-distance side contact: S1
     * {@code SolidObject} ({@code _incObj/sub SolidObject.asm:122-123,167-168}),
     * S2 {@code SolidObject_cont} ({@code s2.asm:35344+}) and
     * {@code SlopedSolid_cont} ({@code s2.asm:35263-35271}), S3K
     * {@code SolidObject_cont} ({@code sonic3k.asm:41393-41399}). Top-solid
     * platform gates instead reject with {@code bhs}/{@code blo} — exclusive:
     * S1 {@code _incObj/sub PlatformObject & SlopeObject.asm:34-35}, S2
     * {@code PlatformObject_cont} ({@code s2.asm:35960}), S3K
     * {@code SolidObjectTop_1P} ({@code sonic3k.asm:41808}) and
     * {@code loc_1E42E} ({@code sonic3k.asm:41995-41996}).
     */
    default boolean usesInclusiveRightEdge() {
        return !isTopSolidOnly();
    }

    /**
     * Half-width of the standable top surface used by landing checks — the
     * engine's {@code width_pixels(a0)} equivalent, consumed directly by the
     * landing X gate.
     * <p>
     * Like {@link #usesInclusiveRightEdge()}, the default is a property of the
     * ROM routine family, not the object. The full-solid landing clamp
     * re-reads the object's own width byte rather than the caller's collision
     * {@code d1}: S1 {@code Solid_Landed} re-reads {@code obActWid}
     * ({@code _incObj/sub SolidObject.asm:318-336}), S2
     * {@code SolidObject_Landed} re-reads {@code width_pixels(a0)}
     * ({@code s2.asm:35588+}), S3K {@code Solid_Landed} / {@code loc_1E154}
     * re-reads {@code width_pixels(a0)} ({@code sonic3k.asm:41611-41621}).
     * Because nearly every full-solid caller passes
     * {@code d1 = width_pixels + $B}, the default reconstructs the width byte
     * as {@code collisionHalfWidth - $B}. Top-solid platform routines instead
     * land on the caller's {@code d1} directly — S1 {@code PlatformObject}
     * passes {@code obActWid} unpadded, S2 {@code PlatformObject_cont}
     * ({@code s2.asm:35960}) and S3K {@code SolidObjectTop_1P}
     * ({@code sonic3k.asm:41798-41825}) use {@code d1} as-is — so the
     * top-solid family default is the collision half-width itself.
     * <p>
     * Override with the disassembly-backed {@code width_pixels} when the
     * caller's {@code d1} is not {@code width_pixels + $B} (e.g. the MHZ1
     * cutscene button passes {@code d1 = $1B} while its ObjDat width is
     * {@code $80}); the returned value is used verbatim, narrower or wider.
     */
    default int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        return isTopSolidOnly() ? collisionHalfWidth : Math.max(0, collisionHalfWidth - 0x0B);
    }

    /**
     * Whether the collision half-width already matches the ROM's standable top
     * width for new landings.
     * <p>
     * Most solid helpers pass {@code d1 = obActWid + $B} into the generic solid
     * routines, so the landing check must narrow back down to {@code obActWid}.
     * Callers whose {@code d1} already equals the object's own width byte need
     * no narrowing: top-solid platform helpers (S1 {@code PlatformObject}, S2
     * {@code PlatformObject_cont}, S3K {@code SolidObjectTop_1P}) — now covered
     * by {@link #getTopLandingHalfWidth}'s family default — and full-solid
     * callers that skip the {@code +$B} idiom (e.g. S2 Obj70's
     * {@code d1 = width_pixels = $10}, s2.asm:55111, 35588-35620). This flag
     * takes precedence over {@link #getTopLandingHalfWidth} in the landing
     * gate; existing top-solid setters are redundant with the family default
     * and retained as documentation.
     */
    default boolean usesCollisionHalfWidthForTopLanding() {
        return false;
    }

    /**
     * Whether this top-only platform's new-contact geometry should use
     * {@link SolidObjectParams#groundHalfHeight()} as the top surface height.
     * <p>
     * S2 {@code PlatformObject} callers pass the platform surface height in
     * {@code d3}; the {@code d2} register is not part of the new-landing
     * {@code PlatformObject_cont -> PlatformObject_ChkYRange} path.
     */
    default boolean usesGroundHalfHeightForTopSolidContact() {
        return false;
    }

    /**
     * Whether a new airborne top-solid landing should apply the absolute
     * {@code PlatformObject_ChkYRange} snap formula
     * {@code anchorY - groundHalfHeight - yRadius - 1} after
     * {@code resolveContactInternal} places the player.
     * <p>
     * S2 {@code PlatformObject}-based objects use this formula (true).
     * S2 {@code SolidObject} (JmpTo22_SolidObject / {@code SolidObject_Landed})-based
     * objects do NOT — {@code resolveContactInternal} already produces the
     * correct position ({@code playerY - distY + 3}), so the override must not
     * overwrite it.  Default is {@code true} to preserve the established
     * PlatformObject-style landing snap for the majority of top-solid objects.
     */
    default boolean usesPlatformObjectLandingSnap() {
        return true;
    }

    /**
     * Whether a newly established ride should remember this frame's pre-update
     * object X as the baseline for the next continued-riding carry.
     * <p>
     * S2 {@code PlatformObject} callers pass the object's saved pre-move
     * {@code x_pos} in {@code d4} to the solid helper, so the first continued
     * riding frame carries by {@code current_x - d4}.
     */
    default boolean seedsNewRideCarryFromPreUpdateX() {
        return false;
    }

    /**
     * Whether new solid-contact geometry should use the object's pre-update
     * position instead of its current post-update position.
     * <p>
     * Most inline solid providers run their ROM-equivalent solid helper after
     * object motion, so the current position is correct. Object-local routines
     * that call {@code SolidObject*} before moving their body can opt in here
     * without changing the shared contact model for unrelated solids.
     */
    default boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        return false;
    }

    /**
     * Whether this object should project a grounded player's pending flat-ground
     * X movement before running new side-contact geometry.
     * <p>
     * Most objects keep the engine's normal pre-movement solid pass. Concrete
     * S2 objects whose ROM routine runs after the player slot may opt in when
     * trace evidence shows the object must see the post-player-move X position.
     */
    default boolean projectsPreMovementGroundXForSolidContact(PlayableEntity player) {
        return false;
    }

    /**
     * Whether this object should sample one pending airborne Y step for a
     * pre-movement {@code SolidObject_cont} top-landing check.
     * <p>
     * Use only for object routines that the ROM executes after the player slot
     * has already applied airborne movement, while the engine's object pass still
     * sees the pre-move centre. The resolver limits this hook to falling,
     * airborne players that cross into the normal {@code SolidObject_Landed}
     * top band; side and bottom contacts are unchanged.
     */
    default boolean projectsPreMovementAirYForSolidContact(PlayableEntity player) {
        return false;
    }

    /**
     * Whether continued-riding flat-top re-seat should use the object's
     * pre-update Y for this frame.
     * <p>
     * Most objects carry riders against their current post-update Y. Concrete
     * object ports can opt in when the ROM evidence shows the rider's Y is held
     * on the pre-move surface for a movement transition, without changing the
     * object's actual current position or side-contact cleanup.
     */
    default boolean usesPreUpdateYForContinuedRide(PlayableEntity player) {
        return false;
    }

    /**
     * Whether continued horizontal rider carry should use the object's
     * pre-update X as this frame's platform position.
     */
    default boolean usesPreUpdateXForContinuedRide(PlayableEntity player) {
        return false;
    }

    /**
     * Number of newly-pressed horizontal-input frames to ignore while this
     * object is the player's current riding solid.
     * <p>
     * Default is zero. Object-specific overrides are for ROM helper timing
     * quirks where the BK2 input row is aligned to V-int, but the player
     * movement routine does not consume the new logical horizontal value until
     * a later gameplay step. Keep this object-local; do not broaden stale input
     * suppression into shared movement unless all callers of the helper have
     * been checked.
     */
    default int staleHorizontalLogicalInputFramesWhileRiding(PlayableEntity player, int rideFrames) {
        return 0;
    }

    default int staleHorizontalLogicalInputFramesWhileRiding(
            PlayableEntity player, int rideFrames, boolean left, boolean right) {
        return right && !left ? staleHorizontalLogicalInputFramesWhileRiding(player, rideFrames) : 0;
    }

    /**
     * Whether the stale-horizontal-input edge tracker should remain attached to
     * this riding object while the player has nonzero ground speed.
     * <p>
     * Most providers keep the historic reset-while-moving behavior. Concrete
     * ROM routines may opt in when a mid-ride inertia reset should not make a
     * still-held horizontal input look newly pressed.
     */
    default boolean preservesStaleHorizontalInputEdgeWhileMoving(PlayableEntity player) {
        return false;
    }

    /**
     * Whether this object returns no contact when the player is already riding
     * another object.
     * <p>
     * Most shared solid helpers may still side-push or replace support while
     * Status_OnObj is set. Concrete object routines can opt in when their ROM
     * helper explicitly checks the player's on-object bit before resolving a
     * new contact and returns early for non-riding instances.
     */
    default boolean skipsNewContactWhilePlayerAlreadyOnObject(PlayableEntity player) {
        return false;
    }

    /**
     * Whether full-solid lower-half overlap should use the player's current
     * y-radius rather than the standing y-radius.
     * <p>
     * Most callers keep the current game profile behaviour. Object-specific
     * disassembly ports can opt in when a concrete helper path is known to
     * build both halves from the live {@code y_radius(a1)} value.
     */
    default boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        return false;
    }

    /**
     * Called when the player is pushing against this object.
     * ROM: bset #p1_pushing_bit,status(a0) (s2.asm:35220-35226).
     * Objects that need to react to being pushed (e.g., spring walls) can override.
     */
    default void setPlayerPushing(PlayableEntity player, boolean pushing) {
        // Default no-op
    }

    /**
     * Whether this object's continued-riding path should carry the rider by
     * this frame's horizontal platform delta.
     * <p>
     * ROM divergence: the S2 {@code SolidObject} caller passes the platform's
     * pre-{@code MvSonicOnPtfm} carry reference x in {@code d4} (s2.asm:35418).
     * That reference is read from {@code objoff_2E}, which the platform's main
     * routine saves at the start of each frame and which the per-subtype
     * movement routine may or may not refresh after updating {@code x_pos}.
     * If the subtype routine refreshes {@code objoff_2E} to the new x_pos
     * (e.g. S2 Obj65 button-triggered subtypes 1/2/6/7 via {@code loc_26D50}),
     * {@code MvSonicOnPtfm} sees a zero delta and the rider is not carried.
     * If the subtype leaves {@code objoff_2E} untouched (e.g. S2 Obj65
     * conveyor subtype 5 {@code loc_26E4A}), the carry reference stays at the
     * pre-move x and the rider follows the platform by the full delta.
     * <p>
     * Default {@code true} preserves existing behaviour. Override with the
     * platform's current movement-routine semantics when porting a ROM
     * object that exposes both refreshing and non-refreshing movement modes.
     */
    default boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        return true;
    }

    /**
     * Whether this object should run a DropOnFloor terrain check after repositioning
     * the player each frame. When enabled, if terrain is detected at or above the
     * player's feet, the player detaches from this object and enters the air state.
     * <p>
     * ROM: DropOnFloor (s2.asm:35810) — called by objects that can push the player
     * into solid terrain (e.g., vertically-moving platforms like HTZ rising lava).
     */
    default boolean dropOnFloor() {
        return false;
    }

    /**
     * Whether losing ride contact through the inline carrying path should force the
     * player airborne.
     * <p>
     * Generic platform helpers in the original games typically clear
     * {@code status.player.on_object} and set {@code status.player.in_air} when the
     * player walks off the ride bounds. Some bespoke solids, such as the EHZ/HPZ log
     * bridge helper ({@code PlatformObject11_cont}), clear only the on-object flag and
     * allow immediate terrain handoff without an airborne frame.
     */
    default boolean forceAirOnRideExit() {
        return true;
    }

    /**
     * Whether this object's continued-riding routine still applies its platform
     * carry after {@code ExitPlatform} has cleared the player's on-object flag
     * because the player jumped.
     * <p>
     * Most platform helpers stop as soon as the rider is airborne. Sonic 1 Obj52
     * is a narrow exception: {@code MBlock_StandOn} calls {@code ExitPlatform},
     * then moves the block, then unconditionally calls {@code MvSonicOnPtfm2}.
     * See {@code docs/s1disasm/_incObj/52 Moving Blocks.asm:65-83} and
     * {@code docs/s1disasm/_incObj/15 Swinging Platforms.asm:177-194}.
     */
    default boolean carriesAirborneRiderAfterExitPlatform() {
        return false;
    }

    /**
     * Whether the inline continued-riding slope sample should be suppressed for
     * exactly this frame while the player remains attached to the object.
     * <p>
     * Default: {@code false} (slope sample writes y_pos every frame, matching
     * ROM {@code SolidObjSloped2} sonic3k.asm:41727-41752 / {@code MvSonicOnSlope}
     * s2disasm:35429 invoked by {@code sub_205B6} sonic3k.asm:44830).
     * <p>
     * ROM divergence covered by this hook: S3K {@code Obj_CollapsingPlatform}
     * state-1 routine {@code loc_20594} (sonic3k.asm:44814-44824) decrements its
     * collapse timer {@code $38} and, when the timer is already zero at frame
     * start, branches to {@code ObjPlatformCollapse_CreateFragments}
     * (sonic3k.asm:45394-45442). That branch rewrites {@code (a0)} to
     * {@code loc_205DE} and {@code jmp}s to {@code Play_SFX} <em>without</em>
     * falling through to {@code sub_205B6} (sonic3k.asm:44830) -- so the slope
     * sample / y_pos write is skipped on the state-1 to state-2 transition
     * frame. Sonic remains attached because {@code Status_OnObj} and
     * {@code p1_standing_bit} are not cleared, but his y_pos is held at the
     * value written by the previous frame's {@code SolidObjSloped2}.
     * <p>
     * Engine architecture has the platform's {@code update()} (state machine)
     * and the {@code SolidContacts} continued-riding pass as separate steps,
     * so the post-update solid pass would still run a slope sample on the
     * transition frame. Returning {@code true} here for that exact frame keeps
     * the player riding (no air transition, no x carry change) while skipping
     * the y_pos write, mirroring ROM.
     */
    default boolean suppressSlopeSampleThisFrame(PlayableEntity player) {
        return false;
    }

    /**
     * Whether this object's ROM solid routine does not run at all this frame,
     * so its airborne-rider unseat (the {@code SolidObjectTopSloped2}/
     * {@code SolidObjectFull} air-unseat that clears {@code Status_OnObj}) must
     * be deferred by one frame.
     * <p>
     * Distinct from {@link #suppressSlopeSampleThisFrame}: that controls only
     * the y_pos slope write inside the object's OWN continued-riding pass,
     * which the engine evaluates AFTER the object's {@code update()} has run.
     * The generic airborne-rider unseat in {@code ObjectSolidContactController}
     * can fire DURING an earlier-slot object's solid pass -- before this
     * object's {@code update()} has promoted its transition-skip flag for the
     * frame -- so the unseat suppression needs a predicate that is already
     * correct at the START of the frame, independent of object exec order.
     * <p>
     * Default mirrors {@link #suppressSlopeSampleThisFrame}; objects whose
     * transition-skip flag is promoted inside {@code update()} (e.g. S3K
     * {@code Obj_CollapsingPlatform}) override this to also report the pending
     * (not-yet-promoted) state. ROM: {@code ObjPlatformCollapse_CreateFragments}
     * jmps to {@code Play_SFX} without running {@code sub_205B6}
     * (sonic3k.asm:44818, 45394), so the platform performs no air-unseat on the
     * collapse-transition frame; the rider keeps {@code Status_OnObj} that frame
     * and is unseated the next frame when {@code loc_205DE} re-runs
     * {@code sub_205B6}.
     */
    default boolean defersAirborneRiderUnseatThisFrame(PlayableEntity player) {
        return suppressSlopeSampleThisFrame(player);
    }

    /**
     * Whether continued-riding exit should still apply one final sloped
     * surface sample before clearing {@code Status_OnObj}.
     * <p>
     * ROM divergence: S3K {@code Obj_CollapsingPlatform} release frame
     * {@code loc_205DE} calls {@code sub_205B6} before {@code sub_205FC}
     * clears the standing bit and sets {@code Status_InAir}
     * (sonic3k.asm:44850-44864). If the engine's ride exit path clears the
     * rider first, the player misses that final slope-position write.
     */
    default boolean sampleSlopeOnRideExit(PlayableEntity player) {
        return false;
    }

    /**
     * Whether the {@code SolidObject_cont} on-screen gate (engine flag
     * {@link com.openggf.game.rules.CollisionRules#solidObjectOffscreenGate()})
     * should be bypassed for this object's new-contact resolution path.
     * <p>
     * ROM divergence: the on-screen gate at {@code loc_1DF88}
     * (sonic3k.asm:41390) lives <em>only</em> in the {@code SolidObjectFull_1P}
     * helper (sonic3k.asm:41016-41018). Objects that route through the
     * sibling helper {@code SolidObjectFull2_1P} (sonic3k.asm:41065-41067)
     * fall through directly to {@code SolidObject_cont} and never test
     * {@code render_flags} bit 7. Notably <strong>all spring variants</strong>
     * call {@code SolidObjectFull2_1P} (sonic3k.asm:47664/47673/47692/47701/
     * 47779/47798/47829/47848/48036/48045/48064/48074), so an off-screen
     * spring still resolves push and side contact in the ROM. The S2 spring
     * helpers use the equivalent {@code SolidObject_Always_SingleCharacter}
     * (s2.asm:33709/33718/33784/33802) which also bypasses the on-screen gate.
     * <p>
     * Default: {@code false} (gate applies, matching the existing
     * {@link com.openggf.game.rules.CollisionRules#solidObjectOffscreenGate()}
     * default behaviour). Spring instances and other objects that route through
     * the {@code Full2} helpers must override to {@code true}.
     */
    default boolean bypassesOffscreenSolidGate() {
        return false;
    }
}
