package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractFallingFragment;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x04 - Collapsing Platform (Sonic 3 &amp; Knuckles).
 * <p>
 * A sloped platform that crumbles when Sonic stands on it for 7 frames.
 * Used in AIZ (Angel Island Zone) and ICZ (IceCap Zone).
 * When the timer expires, the platform splits into individual fragment objects
 * that fall with gravity in a staggered order based on a per-zone delay table.
 * <p>
 * Subtype encoding: subtype = initial mapping_frame (selects platform variant).
 * Fragment frame = mapping_frame + 2.
 * <p>
 * Uses sloped collision ({@code SolidObjectTopSloped2} in disassembly) with a
 * per-pixel height map. Uses level art (no dedicated compressed art).
 * <p>
 * ROM references: Obj_CollapsingPlatform (sonic3k.asm), loc_20594, loc_205CE.
 */
public class Sonic3kCollapsingPlatformObjectInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, RomObjectCodePointerProvider, SpawnRewindRecreatable {

    private static final Logger LOG = Logger.getLogger(Sonic3kCollapsingPlatformObjectInstance.class.getName());

    // Initial collapse delay: 7 frames (ROM: move.b #7,$38(a0))
    private static final int INITIAL_COLLAPSE_DELAY = 7;

    // Gravity constant from MoveSprite: addi.w #$38,y_vel(a0)
    private static final int GRAVITY = 0x38;

    // Priority: $280 = bucket 5 (ROM: move.w #$280,priority(a0))
    private static final int PRIORITY = 5;

    @Override
    public int romObjectCodePointerHighWord() {
        // Obj_CollapsingPlatform / loc_20594 are in ROM bank 0x0002
        // (sonic3k.asm:44784,44814).
        return 0x0002;
    }

    // ===== Zone-specific configuration =====

    /**
     * AIZ collapse delay table (byte_20CB6, 30 entries).
     * Used for both AIZ Act 1 and Act 2. Fragment count may be less than 30;
     * only entries up to the fragment piece count are used.
     */
    private static final int[] AIZ_COLLAPSE_DELAYS = {
            0x30, 0x2C, 0x28, 0x24, 0x20, 0x1C, 0x2E, 0x2A, 0x26, 0x22, 0x1E, 0x1A,
            0x2C, 0x28, 0x24, 0x20, 0x1C, 0x18, 0x2A, 0x26, 0x22, 0x1E, 0x1A, 0x16,
            0x28, 0x24, 0x20, 0x1C, 0x18, 0x14
    };

    /**
     * ICZ collapse delay table (byte_20CD4, 32 entries).
     */
    private static final int[] ICZ_COLLAPSE_DELAYS = {
            0x30, 0x2C, 0x28, 0x24, 0x20, 0x1C, 0x2E, 0x2A, 0x26, 0x22, 0x1E, 0x1A,
            0x2C, 0x28, 0x24, 0x20, 0x1C, 0x18, 0x2A, 0x26, 0x22, 0x1E, 0x1A, 0x16,
            0x28, 0x24, 0x20, 0x1C, 0x18, 0x14, 0x12, 0x10
    };

    /**
     * AIZ slope height map (byte_20E9E, 64 bytes).
     * Gentle slope from 0x1F (left) to 0x0E (right).
     */
    private static final byte[] AIZ_SLOPE_DATA = {
            0x1F, 0x1F, 0x1F, 0x1F, 0x1F, 0x1F, 0x1F, 0x1F,
            0x1F, 0x1F, 0x1F, 0x1F, 0x1F, 0x1F, 0x1F, 0x1F,
            0x1F, 0x1F, 0x1F, 0x1F, 0x1F, 0x1F, 0x1E, 0x1E,
            0x1D, 0x1D, 0x1C, 0x1C, 0x1B, 0x1B, 0x1A, 0x1A,
            0x19, 0x19, 0x18, 0x18, 0x17, 0x17, 0x16, 0x16,
            0x15, 0x15, 0x14, 0x14, 0x13, 0x13, 0x12, 0x12,
            0x11, 0x11, 0x10, 0x10, 0x0F, 0x0F, 0x0E, 0x0E,
            0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E
    };

    /**
     * ICZ slope height map (byte_20EDE, 48 bytes).
     * Mostly flat at 0x30 with slight drop on right edge.
     */
    private static final byte[] ICZ_SLOPE_DATA = {
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x2F, 0x2F, 0x2F, 0x2F,
            0x2F, 0x2F, 0x2F, 0x2F, 0x2F, 0x2F, 0x2F, 0x2F,
            0x2E, 0x2E, 0x2E, 0x2E, 0x2E, 0x2E, 0x2E, 0x2E,
            0x2D, 0x2D, 0x2D, 0x2D, 0x2D, 0x2D, 0x2D, 0x2D,
            0x2D, 0x2D, 0x2D, 0x2D, 0x2D, 0x2C, 0x2B, 0x2A
    };

    /** Per-zone configuration record. */
    private record ZoneConfig(
            String artKey,
            int halfWidth,
            int halfHeight,
            int[] collapseDelays,
            byte[] slopeData,
            int fragmentFrameOffset
    ) {}

    private static final ZoneConfig AIZ1_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_AIZ1,
            0x3C, 0x20, AIZ_COLLAPSE_DELAYS, AIZ_SLOPE_DATA, 2);

    private static final ZoneConfig AIZ2_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_AIZ2,
            0x3C, 0x20, AIZ_COLLAPSE_DELAYS, AIZ_SLOPE_DATA, 2);

    private static final ZoneConfig ICZ_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_ICZ,
            0x30, 0x30, ICZ_COLLAPSE_DELAYS, ICZ_SLOPE_DATA, 2);

    // ===== Instance state =====

    private final ZoneConfig config;
    private int mappingFrame;  // subtype selects intact variant
    private boolean hFlip;

    private int x;
    private int y;

    // State machine: 0=normal, 1=collapsing(timer), 2=solid-stay(invisible but solid),
    //                3=falling(gravity, offscreen destroy)
    // ROM flow: loc_20594 (states 0-1) -> loc_205DE (state 2) -> loc_20620 (state 3)
    private int state;
    private int collapseTimer;
    // ROM $3A: set by loc_205A6 from a FRESH read of this object's own standing
    // bits at dispatch entry, never from the contact callback. Once set it
    // latches, exactly as ROM never clears $3A.
    private boolean triggered;
    private boolean fragmented;

    // Post-fragment solid-stay timer: ROM reuses $38 with first delay table entry.
    // The parent remains solid and invisible for this many frames after fragments spawn,
    // then releases the player. (ROM: loc_205DE countdown -> sub_205FC release)
    private int solidStayTimer;
    private boolean releasePending;
    /**
     * ROM {@code loc_205DE} (sonic3k.asm:44850-44859) runs {@code sub_205B6} --
     * one {@code SolidObjectTopSloped2} pass covering BOTH players -- before it
     * decrements {@code $38}, rewrites the action pointer and calls
     * {@code sub_205FC} for Player 1 and then Player 2. The engine splits that
     * single pass into one per-player solid callback, so Player 1's release can
     * promote {@link #state} to 3 before Player 2's callback has run. Without
     * this flag the second player silently loses the solid pass the ROM had
     * already given them on that dispatch.
     */
    private boolean releaseDispatchActive;
    private boolean releaseSolidPassExposed;

    /** This dispatch is the native CreateFragments dispatch, which skips {@code sub_205B6}. */
    private boolean transitionFrameSlopeSkip;

    // Post-fragment parent fall state
    private int velY;
    private int yFrac;

    // ROM Sprite_OnScreen_Test (sonic3k.asm:37262) reads Camera_X_pos_coarse_back,
    // which Load_Sprites (sonic3k.asm:37545 loc_1B7F2) sets at the START of each
    // frame's level loop -- BEFORE Process_Sprites (sonic3k.asm:7893 -> 7894).
    // Camera_X_pos_coarse_back therefore reflects {@code Camera_X_pos} at the
    // start of frame N, which equals end-of-frame-N-1 (camera moves during
    // DeformBgLayer at sonic3k.asm:7897, AFTER Process_Sprites). In the engine
    // {@link com.openggf.LevelFrameStep} runs object execution (step 4) BEFORE
    // the camera tracking step (step 5: {@code camera.updatePosition()}), so
    // {@code services().camera().getX()} read at the start of this object's
    // {@code update()} already corresponds to the same start-of-frame value
    // ROM saw at its Load_Sprites. No additional caching is required: the
    // engine's frame-step ordering already mirrors ROM's by-construction.
    //
    // (Round 13 attempted to mirror ROM by caching the previous frame's value,
    // under the mistaken premise that Load_Sprites runs AFTER Process_Sprites.
    // The disassembly (sonic3k.asm:7893 jsr Load_Sprites; 7894 jsr
    // Process_Sprites; 7897 jsr DeformBgLayer) shows the order is in fact
    // Load_Sprites -> Process_Sprites -> DeformBgLayer, so the round-13 cache
    // pulled cam_X from too far in the past and let the platform's destruction
    // lag ROM by one frame, which in turn delayed the AIZ trace F6255 sidekick
    // freed-slot despawn by one frame.)

    public Sonic3kCollapsingPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CollapsingPlatform");
        this.x = spawn.x();
        this.y = spawn.y();
        this.mappingFrame = spawn.subtype() & 0xFF;
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.collapseTimer = INITIAL_COLLAPSE_DELAY;
        this.config = resolveConfig();
    }

    // ===== SlopedSolidProvider =====

    @Override
    public byte[] getSlopeData() {
        return config.slopeData;
    }

    @Override
    public boolean isSlopeFlipped() {
        return hFlip;
    }

    @Override
    public int getSlopeBaseline() {
        return 0; // ROM uses absolute slope values
    }

    // ===== SolidObjectProvider =====

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(config.halfWidth, 0, 0);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        return true;
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        // S3K SolidObjCheckSloped2 only rejects negative object_control values.
        // AIZ's sequence can hold a positive object-control state while this
        // platform still supports the player.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        // Solid during normal, collapsing, AND solid-stay states.
        // ROM: loc_205DE still calls SolidObjectTopSloped2 after fragments spawn.
        boolean alreadyRiding = playerEntity != null
                && services().objectManager().isRidingObject(playerEntity, this);
        return solidForTransitionState(alreadyRiding);
    }

    boolean solidForTransitionState(boolean alreadyRiding) {
        if (state >= 3) {
            // ROM loc_205DE's sub_205B6 solid pass precedes both sub_205FC
            // release calls, so every player still standing on this dispatch
            // gets the pass regardless of which one the engine released first.
            if (releaseDispatchActive) {
                return true;
            }
            // sub_205FC runs for Player 1 and Player 2 in one object dispatch.
            // Player 1 may promote the engine state before the separate P2
            // solid callback, so retain solidity only for an already-recorded
            // rider until the next object update consumes releasePending.
            return releasePending && alreadyRiding;
        }
        // ObjPlatformCollapse_CreateFragments jumps to Play_SFX instead of
        // calling sub_205B6/SolidObjectTopSloped2 on the transition dispatch
        // (sonic3k.asm:45399). Existing riders retain their standing bits across
        // that skipped pass, but a second player cannot establish a fresh
        // contact until loc_205DE resumes the solid helper on the following
        // dispatch.
        return !transitionFrameSlopeSkip || alreadyRiding;
    }

    /**
     * One-frame suppression of the slope sample / y_pos write for the
     * state-1 -> state-2 transition frame. ROM
     * {@code ObjPlatformCollapse_CreateFragments} (sonic3k.asm:45399-45442)
     * does not fall through to {@code sub_205B6}, so the slope sample is
     * skipped while the player remains attached. Player y_pos therefore
     * holds the previous frame's value -- which is the trace observation at
     * AIZ F6920 ({@code y=0x0342} continuing F6919's value, while the
     * post-physics x_pos {@code 0x0E8B} would have sampled
     * {@code AIZ_SLOPE_DATA[0x2B]=0x14} and produced {@code 0x0341}).
     */
    @Override
    public boolean suppressSlopeSampleThisFrame(PlayableEntity player) {
        return transitionFrameSlopeSkip;
    }

    /**
     * The collapse-transition frame's airborne-rider unseat must be suppressed
     * even when the generic unseat in {@code ObjectSolidContactController} is
     * evaluated before this platform's {@code update()} promotes the pending
     * transition into {@link #transitionFrameSlopeSkip}.
     * <p>
     * Reporting both the pending and promoted views keeps the unseat suppressed
     * for that single frame regardless of object execution order, so the rider keeps
     * {@code Status_OnObj} on the jump/collapse frame (aiz1 trace f3317 status
     * 0x0E) and is unseated next frame (f3318 0x06). The slope-sample
     * suppression above intentionally applies only after promotion, when the
     * engine frame corresponds to the native transition dispatch.
     */
    @Override
    public boolean defersAirborneRiderUnseatThisFrame(PlayableEntity player) {
        // Also true before this frame's update() has run, for the dispatch that
        // is about to fragment: loc_20594's `tst.b $38 / beq.w
        // ObjPlatformCollapse_CreateFragments` (sonic3k.asm:44822-44823) takes
        // the branch when the countdown has already reached zero, so
        // state 1 with an exhausted timer IS the CreateFragments dispatch.
        return transitionFrameSlopeSkip || (state == 1 && collapseTimer <= 0);
    }

    @Override
    public boolean sampleSlopeOnRideExit(PlayableEntity player) {
        // ROM loc_205DE runs sub_205B6 (SolidObjectTopSloped2) before
        // sub_205FC clears Status_OnObj and sets Status_InAir on the rider
        // (sonic3k.asm:44850-44864). The engine's split object/solid phases
        // can reach the ride-exit branch after state has advanced to 3, so the
        // final sloped y_pos write is still required before clearing support.
        return state == 3;
    }

    /**
     * Opt out of {@code ObjectManager.unloadCounterBasedOutOfRange()} so this
     * platform's lifecycle is governed exclusively by ROM's
     * {@code Sprite_OnScreen_Test} analog inside {@link #update}. The two
     * checks share the same 0x280 threshold but differ by one frame:
     * {@code unloadCounterBasedOutOfRange} compares against the CURRENT
     * frame's camera_x, while ROM's S3K {@code Sprite_OnScreen_Test} reads
     * {@code Camera_X_pos_coarse_back} which {@code Load_Sprites} updates
     * AFTER {@code Process_Sprites} (sonic3k.asm:37545 loc_1B7F2). Letting
     * the engine destroy the platform with the eager current-frame value
     * collapses it one frame too early relative to ROM, which was the
     * blocker preventing the F6255 freed-slot despawn analog from firing.
     */
    @Override
    public boolean isPersistent() {
        return true;
    }

    // ===== SolidObjectListener =====

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (releasePending && player != null && contact.standing()) {
            // ROM loc_205DE resolves SolidObjectTopSloped2 before sub_205FC
            // releases the player. The engine's separate solid pass is the
            // matching point to clear ride state after the no-movement frame.
            state = 3;
            releaseSolidPassExposed = false;
            releaseDispatchActive = true;
            player.setAir(true);
            player.setOnObject(false);
            publishReleaseAnimationState(player);
            services().objectManager().clearRidingObject(player);
            return;
        }
        // ROM does NOT set $3A from the contact. loc_205A6 re-reads
        // `status(a0) & standing_mask` at every dispatch ENTRY, which is
        // modelled in update() -- see readStandingTrigger().
    }

    void publishReleaseAnimationState(AbstractPlayableSprite player) {
        // sub_205FC clears Status_Push and writes only prev_anim=Run. The
        // current Walk byte remains untouched, forcing Animate to restart it
        // on the following player tick (sonic3k.asm:44860-44864).
        player.setPushing(false);
        player.getAnimationManager().publishPreviousAnimationId(Sonic3kAnimationIds.RUN.id());
    }

    /**
     * ROM {@code loc_205A6}: {@code move.b status(a0),d0 / andi.b #standing_mask,d0}
     * (sonic3k.asm:44826-44828). {@code standing_mask = p1_standing|p2_standing}
     * (sonic3k.constants.asm:147), so either character's standing bit arms the
     * collapse. Read fresh at dispatch entry, never cached.
     */
    private boolean readStandingTrigger() {
        for (PlayableEntity player : services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (player != null
                    && services().objectManager().hasObjectStandingBit(player, this)) {
                return true;
            }
        }
        return false;
    }

    // ===== Update =====

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        transitionFrameSlopeSkip = false;
        // A new dispatch: the previous dispatch's sub_205B6 pass is over.
        releaseDispatchActive = false;
        switch (state) {
            case 0 -> {
                // ROM loc_20594 (sonic3k.asm:44819-44824): the $38 countdown is
                // reached only when $3A is ALREADY set, so the dispatch that
                // sets $3A does not decrement.
                if (triggered) {
                    // $3A was set on an earlier dispatch, so this one runs the
                    // countdown itself -- ROM does not spend a dispatch merely
                    // "entering" the collapsing state.
                    state = 1;
                    if (collapseTimer <= 0) {
                        performCollapse();
                    } else {
                        collapseTimer--;
                    }
                }
                // ROM loc_205A6 (sonic3k.asm:44826-44830) then re-reads the
                // object's own standing bits and sets $3A from them. This is a
                // fresh read every dispatch, not a latch taken at contact time:
                // the bits it reads were written by the PREVIOUS dispatch's
                // sub_205B6, and any other solid object that has re-seated the
                // same character since will have cleared them via
                // RideObject_SetRide's `bclr d6,status(a3)` (:42027-42031).
                // Two overlapping platforms therefore starve each other's
                // trigger: the lower slot clears the higher slot's bit every
                // frame before the higher slot's routine body reads it.
                else if (readStandingTrigger()) {
                    triggered = true;
                }
                // ROM loc_20594 falls through to sub_205B6 -> Sprite_OnScreen_Test
                // every frame in pre-collapse and standing-trigger states. Mirror
                // that off-screen delete here so the platform vanishes at the
                // ROM-correct frame when the camera scrolls past it (sonic3k.asm:
                // 44814 loc_20594, 44830 sub_205B6, 37262 Sprite_OnScreen_Test).
                if (!spriteOnScreenTestPasses()) {
                    setDestroyedByOffscreen();
                }
            }
            case 1 -> {
                // Collapsing: countdown timer.
                if (collapseTimer <= 0) {
                    performCollapse();
                } else {
                    collapseTimer--;
                }
                // Same per-frame off-screen delete as state 0 -- ROM does not
                // skip Sprite_OnScreen_Test while $38 counts down (sonic3k.asm:
                // 44830 sub_205B6 -> 37262 Sprite_OnScreen_Test).
                if (state != 2 && !spriteOnScreenTestPasses()) {
                    setDestroyedByOffscreen();
                }
            }
            case 2 -> {
                // Solid-stay: parent is invisible but still solid (ROM: loc_205DE).
                // Player continues standing on the invisible platform while fragments
                // fall away beneath them. ROM calls sub_205B6 before decrementing
                // $38, so the normal solid pass must still see this object as solid.
                if (releasePending) {
                    if (releaseSolidPassExposed) {
                        // ROM loc_205DE (sonic3k.asm:44850-44854) performs
                        // sub_205B6 before rewriting the action pointer and
                        // clearing any rider in sub_205FC. Expose one engine
                        // post-update solid pass first; if no contact consumed
                        // it, promote here on the next update so abandoned
                        // platforms cannot remain invisible solids forever.
                        state = 3;
                        releasePending = false;
                        releaseSolidPassExposed = false;
                    } else {
                        releaseSolidPassExposed = true;
                    }
                    break;
                }
                solidStayTimer--;
                if (solidStayTimer <= 0) {
                    releasePending = true;
                    releaseSolidPassExposed = false;
                }
                // ROM loc_205DE entry-point begins with `bsr.w sub_205B6` which
                // re-runs the SolidObjectTopSloped2 + Sprite_OnScreen_Test pair
                // each frame (sonic3k.asm:44851). Off-screen delete remains
                // active during solid-stay countdown so the platform exits
                // cleanly when the camera scrolls past during fragment fall.
                if (!spriteOnScreenTestPasses()) {
                    setDestroyedByOffscreen();
                }
            }
            case 3 -> {
                releasePending = false;
                releaseSolidPassExposed = false;
                advanceFallingParent();
            }
        }
    }

    private void advanceFallingParent() {
        // Post-fragment: parent falls with gravity, destroy when offscreen.
        // When the release is finalized at the start of this update, run the
        // first loc_20620 step immediately so keeping releasePending live for
        // both player callbacks does not delay the parent by one frame.
        velY += GRAVITY;
        int y32 = (y << 16) | (yFrac & 0xFFFF);
        y32 += ((int) (short) velY) << 8;
        y = y32 >> 16;
        yFrac = y32 & 0xFFFF;

        if (!isOnScreen(128)) {
            // ROM ObjPlatformCollapse_CreateFragments clears the respawn table
            // bit before the falling parent reaches loc_20620
            // (sonic3k.asm:45435-45438).
            setDestroyedByOffscreen();
        }
    }

    /**
     * ROM Sprite_OnScreen_Test (sonic3k.asm:37262):
     * <pre>
     *   move.w  x_pos(a0),d0
     *   andi.w  #$FF80,d0
     *   sub.w   (Camera_X_pos_coarse_back).w,d0
     *   cmpi.w  #$280,d0
     *   bhi.w   loc_1B5A0    ; off-screen -> Delete_Current_Sprite
     * </pre>
     * {@code Camera_X_pos_coarse_back} = {@code (Camera_X_pos - $80) & $FF80},
     * recomputed by {@code Load_Sprites} (sonic3k.asm:37472-37478) at the
     * START of the level loop -- BEFORE {@code Process_Sprites} runs the
     * platform's solid pass (sonic3k.asm:7893 jsr Load_Sprites; 7894 jsr
     * Process_Sprites; 7897 jsr DeformBgLayer). So during ROM
     * {@code Process_Sprites} of frame N, {@code Camera_X_pos_coarse_back}
     * reflects {@code Camera_X_pos} at the start of frame N (i.e. the same
     * cam_X the camera held at the end of frame N-1, since
     * {@code DeformBgLayer} -- the per-frame camera-tracker -- runs only
     * AFTER {@code Process_Sprites}).
     *
     * <p>The engine's {@link com.openggf.LevelFrameStep} mirrors that order
     * exactly: object execution (where this method runs) is step 4, while
     * {@code camera.updatePosition()} is step 5. So
     * {@code services().camera().getX()} read inside this method is already
     * the start-of-frame cam_X, equal to the end-of-frame-N-1 cam_X, equal to
     * ROM's {@code Camera_X_pos_coarse_back} input.
     *
     * <p>The arithmetic is unsigned 16-bit: a platform that has scrolled
     * BEHIND the camera underflows the subtraction into the high $FFxx range,
     * which exceeds $280 and triggers the {@code bhi} delete branch.
     *
     * @return true if the platform passes the test (stays alive); false if
     *         the camera has scrolled far enough that ROM would
     *         {@code Delete_Current_Sprite} this slot
     */
    private boolean spriteOnScreenTestPasses() {
        int currentCameraX = services().camera().getX() & 0xFFFF;
        int cameraXPosCoarseBack = (currentCameraX - 0x80) & 0xFF80;
        int objectXCoarse = x & 0xFF80;
        // ROM uses 16-bit unsigned wrap; emulate with a 16-bit AND.
        int diff = (objectXCoarse - cameraXPosCoarseBack) & 0xFFFF;
        return diff <= 0x280;
    }

    /**
     * Fragments the platform: spawns individual fragment children and plays SFX.
     * ROM: ObjPlatformCollapse_CreateFragments
     */
    private void performCollapse() {
        if (fragmented) {
            return;
        }
        fragmented = true;

        // Enter solid-stay state: parent remains invisible but solid while fragments fall.
        // ROM: ObjPlatformCollapse_SmashObject writes collapseDelays[0] into the parent's $38,
        // and loc_205DE counts it down while still calling SolidObjectTopSloped2.
        state = 2;
        releasePending = false;
        releaseSolidPassExposed = false;
        // ROM ObjPlatformCollapse_CreateFragments (sonic3k.asm:45399) does NOT
        // fall through to sub_205B6 -- it jmps to Play_SFX. So the slope
        // sample / y_pos write is skipped on the ROM transition frame.
        //
        // performCollapse runs from update(), which precedes the engine's solid
        // pass, so marking the skip here suppresses this dispatch's slope
        // sample -- the same dispatch ROM skips. It is cleared at the top of the
        // next update().
        transitionFrameSlopeSkip = true;
        // ObjPlatformCollapse_CreateFragments reuses the parent's own slot as
        // the first fragment (movea.l a0,a1), so the parent takes the first
        // delay-table byte: `move.b (a4)+,$38(a1)` (sonic3k.asm:45434) with a4
        // = $30(a0), the byte_20CB6 delay table. loc_205DE (:44855-44858) then
        // runs sub_205B6 and decrements $38, releasing when it reaches zero, so
        // release lands collapseDelays[0] dispatches after fragmentation.
        solidStayTimer = config.collapseDelays[0];

        // Play collapse SFX
        if (isOnScreen()) {
            try {
                services().playSfx(Sonic3kSfx.COLLAPSE.id);
            } catch (Exception e) {
                // Prevent audio failure from breaking game logic
            }
        }

        // Get the fragment mapping frame
        int fragmentFrameIndex = mappingFrame + config.fragmentFrameOffset;

        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }
        ObjectSpriteSheet sheet = renderManager.getSheet(config.artKey);
        if (sheet == null || fragmentFrameIndex >= sheet.getFrameCount()) {
            return;
        }

        SpriteMappingFrame fragmentFrame = sheet.getFrame(fragmentFrameIndex);
        int pieceCount = fragmentFrame.pieces().size();
        int maxFragments = Math.min(pieceCount, config.collapseDelays.length);

        // Spawn fragment children for each piece, starting from index 1.
        // ROM: ObjPlatformCollapse_SmashObject's first loop iteration writes to the
        // parent (a1=a0), consuming piece 0 and delay[0] for the parent's solid-stay.
        // Subsequent iterations allocate new fragment objects starting from piece 1.
        for (int i = 1; i < maxFragments; i++) {
            int delay = config.collapseDelays[i];
            CollapsingPlatformFragment fragment = new CollapsingPlatformFragment(
                    x, y, fragmentFrameIndex, i, delay, config.artKey, hFlip,
                    config.halfWidth, config.halfHeight);
            spawnDynamicObject(fragment);
        }

        // ROM uses Delete_Current_Sprite (not Remember_Sprite), so the platform
        // respawns when the player scrolls away and returns. The Placement system
        // handles this: state 3's setDestroyed cleans up the falling parent, and
        // the destroyedInWindow latch clears once the spawn leaves the window.

        // Do NOT release the player here - they continue riding the invisible solid
        // platform until solidStayTimer expires (handled in state 2 update).
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(config.artKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (fragmented && state == 2) {
            // ROM: static_mappings mode — parent renders only piece 0 from the
            // fragment frame while providing invisible collision during solid-stay.
            int fragmentFrameIndex = mappingFrame + config.fragmentFrameOffset;
            renderer.drawFramePieceByIndex(fragmentFrameIndex, 0, x, y, hFlip, false);
        } else if (!fragmented) {
            renderer.drawFrameIndex(mappingFrame, x, y, hFlip, false);
        }
        // state 3 (falling): parent is off-screen heading down, no need to render
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ===== Helpers =====

    // Uses inherited getRenderManager() from AbstractObjectInstance

    private ZoneConfig resolveConfig() {
        try {
            int zone = services().romZoneId();
            int act = services().currentAct();
            if (zone == Sonic3kZoneIds.ZONE_AIZ) {
                return act == 0 ? AIZ1_CONFIG : AIZ2_CONFIG;
            }
            if (zone == Sonic3kZoneIds.ZONE_ICZ) {
                return ICZ_CONFIG;
            }
            LOG.warning("CollapsingPlatform: unknown zone 0x" + Integer.toHexString(zone) + ", defaulting to AIZ1 config");
        } catch (Exception e) {
            LOG.fine("Could not resolve zone config: " + e.getMessage());
        }
        return AIZ1_CONFIG; // fallback
    }

    // ===================================================================
    // Fragment inner class
    // ===================================================================

    /**
     * Fragment object spawned when the collapsing platform breaks apart.
     * Each fragment renders a single piece from the fragment mapping frame and
     * falls with gravity after its individual delay expires.
     * <p>
     * ROM: loc_205CE (fragment routine in Obj_CollapsingPlatform).
     */
    public static class CollapsingPlatformFragment extends AbstractFallingFragment implements RewindRecreatable {

        private int fragmentFrameIndex;
        private int pieceIndex;
        private String artKey;
        private boolean hFlip;
        private int renderHalfWidth;
        private int renderHalfHeight;
        private boolean romRenderFlag = true;

        public CollapsingPlatformFragment(int parentX, int parentY,
                                          int fragmentFrameIndex, int pieceIndex,
                                          int delay, String artKey, boolean hFlip) {
            this(parentX, parentY, fragmentFrameIndex, pieceIndex, delay, artKey,
                    hFlip, AIZ1_CONFIG.halfWidth, AIZ1_CONFIG.halfHeight);
        }

        private CollapsingPlatformFragment(int parentX, int parentY,
                                           int fragmentFrameIndex, int pieceIndex,
                                           int delay, String artKey, boolean hFlip,
                                           int renderHalfWidth, int renderHalfHeight) {
            super(new ObjectSpawn(parentX, parentY, Sonic3kObjectIds.COLLAPSING_PLATFORM,
                    0, hFlip ? 1 : 0, false, 0), "PlatformFragment", delay, PRIORITY);
            this.fragmentFrameIndex = fragmentFrameIndex;
            this.pieceIndex = pieceIndex;
            this.artKey = artKey;
            this.hFlip = hFlip;
            this.renderHalfWidth = renderHalfWidth;
            this.renderHalfHeight = renderHalfHeight;
        }

        private CollapsingPlatformFragment() {
            this(0, 0, 0, 0, 0, Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_AIZ1, false);
        }

        @Override
        public CollapsingPlatformFragment recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn capturedSpawn = ctx.spawn();
            int x = capturedSpawn != null ? capturedSpawn.x() : 0;
            int y = capturedSpawn != null ? capturedSpawn.y() : 0;
            boolean capturedHFlip = capturedSpawn != null && (capturedSpawn.renderFlags() & 1) != 0;
            return new CollapsingPlatformFragment(
                    x,
                    y,
                    0,
                    0,
                    0,
                    Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_AIZ1,
                    capturedHFlip);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(artKey);
            if (renderer == null) {
                return;
            }

            renderer.drawFramePieceByIndex(fragmentFrameIndex, pieceIndex, getX(), getY(), hFlip, false);
        }

        @Override
        public int getOnScreenHalfWidth() {
            return renderHalfWidth;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return renderHalfHeight;
        }

        @Override
        protected boolean shouldDeleteBeforeFall() {
            // loc_20620 consumes bit 7 from the preceding Draw_Sprite pass
            // before calling MoveSprite. A fragment whose prior render box was
            // off-screen deletes without receiving another gravity step.
            return !romRenderFlag;
        }

        @Override
        protected boolean shouldDeleteAfterFall() {
            return false;
        }

        @Override
        public void refreshPostCameraRenderState() {
            // Draw_Sprite queues the static fragment during Process_Sprites;
            // Render_Sprites publishes bit 7 only after DeformBgLayer has
            // updated the copied camera used for the actual bounds test.
            romRenderFlag = isWithinRenderSpriteBounds(renderHalfWidth, renderHalfHeight);
        }
    }
}
