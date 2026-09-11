package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.AnimalObjectInstance;
import com.openggf.level.objects.DestructionEffects;
import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj $A3 - Clamer.
 *
 * <p>ROM reference: {@code Obj_Clamer} and child {@code loc_8908C} in
 * {@code docs/skdisasm/sonic3k.asm}. The visible parent owns a hidden spring
 * child at {@code y_pos - 8}; touching that child launches the player and sets
 * the parent close flag.
 */
public final class ClamerObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, TouchResponseAttackable, RewindRecreatable {

    /*
     * ROM Clamer spring child collision_flags = $D7 ($C0 | $17), set once at
     * spawn by word_89136 (sonic3k.asm:185986+) and never modified. Size index
     * $17 = 8x8 collision rect. The high $C0 bits would normally decode to
     * BOSS in engine, but $17 is one of the Touch_Special property indices
     * (sonic3k.asm:21165-21194), so usesS3kTouchSpecialPropertyResponse() = true
     * routes the rect through SPECIAL with a cprop-style latch. This object
     * exposes the rect with the ROM-correct $D7 flags every frame the spring
     * is "in the response list" -- never widening to $12 (engine-only hack).
     */
    private static final int PARENT_COLLISION_FLAGS = 0x0A;
    private static final int SPRING_COLLISION_FLAGS = 0x40 | 0x17;
    private static final int SPRING_OFFSET_Y = -8;
    // Obj_WaitOffscreen installs Map_Offscreen and width/height $20 until the
    // render pass sets render_flags bit 7 (docs/skdisasm/sonic3k.asm:
    // 180266-180298). Obj_Clamer enters through it before loc_88FDC
    // initializes attributes and spawns the spring child (185857-185877).
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;
    private static final int LAUNCH_SPEED = 0x800;
    private static final int CLOSE_FRAMES = 10;

    /** ROM loc_88FEC: cmpi.w #$60, d2; bhs loc_8900C. */
    private static final int AUTO_CLOSE_DX_THRESHOLD = 0x60;
    private static final int[] AUTO_CLOSE_ANIM_FRAMES = {0, 5, 6, 7, 8, 7, 6, 5, 0};
    private static final int[] AUTO_CLOSE_ANIM_DELAYS = {2, 2, 2, 0x2F, 5, 0x1F, 2, 2, 0x1F};
    private static final int AUTO_CLOSE_PROJECTILE_FRAME = 8;
    private static final int PROJECTILE_COLLISION_FLAGS = 0x80 | 0x18;
    private static final int PROJECTILE_SHIELD_REACTION_BOUNCE = 1 << 3;
    private static final int PROJECTILE_DEFLECT_SPEED = 0x800;
    private static final ObjectPlayerParticipationPolicy AUTO_CLOSE_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;
    private static final ObjectPlayerParticipationPolicy CPROP_TARGET_PARTICIPATION =
            ObjectPlayerParticipationPolicy.NATIVE_P1_P2;

    /** Routine values from Clamer_Index (sonic3k.asm:185866-185874). */
    private static final int ROUTINE_IDLE = 0x02;
    private static final int ROUTINE_SNAP_SHUT = 0x04;
    private static final int ROUTINE_AUTO_CLOSE = 0x06;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
            new com.openggf.game.profiles.touchresponse.TouchResponseProfile(
                    com.openggf.game.profiles.touchresponse.TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY,
                    true,
                    true,
                    true,
                    com.openggf.game.profiles.touchresponse.TouchShieldDeflectCapability.NONE,
                    0,
                    com.openggf.game.profiles.touchresponse.TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                    com.openggf.game.profiles.touchresponse.TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                    com.openggf.game.profiles.touchresponse.TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY));
    private static final DestructionConfig S3K_DESTRUCTION_CONFIG = new DestructionConfig(
            Sonic3kSfx.BREAK.id,
            (spawn, services) -> AnimalObjectInstance.deferredArtVariant(spawn, services, null),
            false,
            (spawn, svc, pts) -> new Sonic3kPointsObjectInstance(spawn, svc, pts),
            null,
            false
    );

    /**
     * Spring-child routine state, mirroring the ROM (a0) pointer cycle at
     * sonic3k.asm:185953-185973. LIVE = loc_890AA (in list, can fire).
     * COOLDOWN_DRAIN = loc_890C8 cooldown frame (NOT added to list).
     * COOLDOWN_DONE = engine-only intermediate; (a0)=loc_890AA but slot was
     * NOT in last frame list, so Sonic touch walk skips this slot and any
     * latched collision_property survives across the frame boundary.
     */
    private enum SpringRoutine { LIVE, COOLDOWN_DRAIN, COOLDOWN_DONE }

    private int currentX;
    private int currentY;
    private boolean facingRight;

    private int routine = ROUTINE_IDLE;
    private int mappingFrame;
    private SpringRoutine springRoutine = SpringRoutine.LIVE;
    /**
     * Mirrors ROM {@code collision_property(a0)} byte for the spring child
     * (sonic3k.asm:21162-21194 + 179904-179924). Each in-range overlap during
     * {@code Touch_Special} adds:
     * <ul>
     *   <li>+1 when the toucher is Player_1 (Sonic / primary character)
     *   <li>+2 when the toucher is Player_2 (sidekick / Tails CPU)
     * </ul>
     * Then {@code Check_PlayerCollision} masks {@code & 3} and indexes into
     * {@code word_85890} = [P1, P1, P2, P2]. So both players touching in the
     * same frame (cprop=3) launches Player_2.
     */
    private int springCprop;
    private boolean springInListLastFrame = true;
    /**
     * True when {@link #onTouchResponse} fired the spring during this engine
     * frame touch phase. Recorded so the subsequent
     * {@link #advanceSpringRoutine} call still simulates ROM
     * {@code loc_890C4: jmp Child_DrawTouch_Sprite} (sonic3k.asm:185961-185962)
     * which adds the slot to the response list AFTER the fire branch sets
     * {@code (a0) = loc_890C8}.
     */
    private boolean firedDuringThisFramesTouch;
    private int lastObservedFrameCounter;
    private int closeTimer;
    private int autoCloseAnimIndex;
    private int autoCloseAnimTimer;
    private boolean springFiredFlag;
    private boolean destroyed;
    private ClamerSpringChild springChildSlot;
    private boolean waitingForOnscreen = true;
    private boolean initialized;

    public ClamerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Clamer");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.facingRight = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public ClamerObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new ClamerObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (destroyed) {
            return;
        }
        if (waitingForOnscreen) {
            if (!isOnScreen(WAIT_OFFSCREEN_MARGIN)) {
                return;
            }
            // loc_85B02 restores the saved Clamer entry point and returns;
            // loc_88FDC/CreateChild1_Normal execute on the next object pass.
            waitingForOnscreen = false;
            return;
        }
        if (!initialized) {
            initialized = true;
            ensureSpringChildSlot();
            return;
        }
        ensureSpringChildSlot();
        lastObservedFrameCounter = vIntRunCount;

        // ROM Clamer_Index dispatch (sonic3k.asm:185860).
        switch (routine) {
            case ROUTINE_IDLE -> updateIdle(playerEntity);
            case ROUTINE_SNAP_SHUT -> updateSnapShut();
            case ROUTINE_AUTO_CLOSE -> updateAutoClose();
            default -> {
                // No-op for routine 0 (init runs once; we treat construction as init).
            }
        }

        // Spring-child update mirrors ROM (a0)=loc_890AA / loc_890C8 / loc_890D0.
        // ROM slot order runs Sonic (slot 0) BEFORE the spring child (slot 5),
        // so this update sees collision_property already latched by the
        // current frame Touch_Special walk. The engine mirrors that order by
        // running player touch responses inside the per-player physics tick
        // (LevelFrameStep step 2: physics) BEFORE this object update
        // (LevelFrameStep step 3: objects).
        advanceSpringRoutine(playerEntity);
    }

    /**
     * Advances the ROM (a0) cycle and the Add_SpriteToCollisionResponseList
     * timing (sonic3k.asm:185953-185973). Only loc_890AA reaches loc_890C4
     * which jumps to Child_DrawTouch_Sprite (sonic3k.asm:178048-178053) --
     * the only path that calls Add_SpriteToCollisionResponseList. The
     * cooldown frame and its tail-call into loc_890D0 both return via plain
     * rts, leaving the slot absent from the list for one frame. This is the
     * exact mechanism that delays Sonic next overlap test by one frame,
     * allowing the collision_property latch set during the cooldown frame to
     * fire on the frame after the cooldown.
     */
    private void advanceSpringRoutine(PlayableEntity playerEntity) {
        boolean addToList;
        switch (springRoutine) {
            case LIVE -> {
                if (firedDuringThisFramesTouch) {
                    // onTouchResponse already fired the spring this frame
                    // (engine-side touch-phase handling). ROM loc_890AA still
                    // jumps through loc_890C4 to Child_DrawTouch_Sprite after
                    // the fire branch, so the slot remains in this frame list.
                    // Transition to COOLDOWN_DRAIN to mirror the ROM
                    // (a0)=loc_890C8 store.
                    springRoutine = SpringRoutine.COOLDOWN_DRAIN;
                } else if (springCprop != 0) {
                    // ROM loc_890AA (sonic3k.asm:185953-185962): bsr.w
                    // Check_PlayerCollision; beq.s loc_890C4 (no fire branch).
                    // Non-zero collision_property -> Check_PlayerCollision
                    // (sonic3k.asm:179904-179924) masks bits 0-1, indexes
                    // word_85890 = [P1, P1, P2, P2] to pick the launch target,
                    // then clears the byte. Reached when a latch survived
                    // through cooldown into the post-cooldown LIVE state
                    // without a touch-phase fire.
                    AbstractPlayableSprite target = resolveCpropTarget(playerEntity);
                    springCprop = 0;
                    if (target != null) {
                        fireSpring(target);
                    }
                    springRoutine = SpringRoutine.COOLDOWN_DRAIN;
                }
                // ROM loc_890C4: jmp Child_DrawTouch_Sprite is reached on
                // both fire and no-fire branches.
                addToList = true;
            }
            case COOLDOWN_DRAIN -> {
                // ROM loc_890C8 (sonic3k.asm:185965-185968): subq.w #1, $2E(a0);
                // bmi.s loc_890D0; rts. Both paths skip Child_DrawTouch_Sprite,
                // so the slot is NOT added to the list this frame.
                springRoutine = SpringRoutine.COOLDOWN_DONE;
                addToList = false;
            }
            case COOLDOWN_DONE -> {
                // Engine-only intermediate state. Sonic touch walk this frame
                // did NOT see the spring slot (springInListLastFrame was
                // false going into touch), so collision_property survives.
                // If the latch is set from the prior cooldown frame touch
                // walk, fire now -- this is the F=621 fire in the trace.
                if (springCprop != 0) {
                    AbstractPlayableSprite target = resolveCpropTarget(playerEntity);
                    springCprop = 0;
                    if (target != null) {
                        fireSpring(target);
                    }
                    springRoutine = SpringRoutine.COOLDOWN_DRAIN;
                } else {
                    springRoutine = SpringRoutine.LIVE;
                }
                addToList = true;
            }
            default -> addToList = false;
        }
        springInListLastFrame = addToList && !destroyed;
        firedDuringThisFramesTouch = false;
    }

    private void ensureSpringChildSlot() {
        if (springChildSlot != null) {
            return;
        }
        // ROM loc_88FDC spawns ChildObjDat_89148 with CreateChild1_Normal
        // (sonic3k.asm:185875-185879, 185998-186000). The engine keeps the
        // spring response on the parent for multi-region cprop timing, but
        // the child still has to consume a real SST slot for placement order.
        springChildSlot = spawnChild(() -> new ClamerSpringChild(this));
    }

    /**
     * ROM Check_PlayerCollision (sonic3k.asm:179904-179924):
     * <pre>
     *     move.b  collision_property(a0),d0
     *     beq.s   locret_8588E
     *     clr.b   collision_property(a0)
     *     andi.w  #3,d0
     *     add.w   d0,d0
     *     lea     word_85890(pc),a1   ; [P1, P1, P2, P2]
     *     movea.w (a1,d0.w),a1
     * </pre>
     * cprop bit pattern (& 3):
     * <ul>
     *   <li>1 -> P1 (Sonic touched alone)
     *   <li>2 -> P2 (Tails touched alone)
     *   <li>3 -> P2 (both touched same frame; sidekick wins)
     *   <li>0 -> caller already returned no-fire
     * </ul>
     * The engine resolves "P2" via {@link ObjectPlayerQuery};
     * "P1" is the {@code playerEntity} passed into {@code update()}.
     */
    private AbstractPlayableSprite resolveCpropTarget(PlayableEntity primary) {
        int sel = springCprop & 3;
        if (sel == 1) {
            return (primary instanceof AbstractPlayableSprite p) ? p : null;
        }
        if (sel == 2 || sel == 3) {
            ObjectPlayerQuery query = playerQuery(primary);
            for (PlayableEntity candidate : query.playersFor(CPROP_TARGET_PARTICIPATION)) {
                if (candidate != primary && candidate instanceof AbstractPlayableSprite p) {
                    return p;
                }
            }
            // No sidekick available: fall back to the primary so the launch
            // still happens (mirrors solo-mode where ROM never reaches d0=2).
            return (primary instanceof AbstractPlayableSprite p) ? p : null;
        }
        return null;
    }

    private void fireSpring(AbstractPlayableSprite player) {
        // ROM loc_890AA fire branch (sonic3k.asm:185955-185959):
        //     move.l #loc_890C8, (a0)
        //     bsr.w  sub_890D8           (apply launch velocities)
        //     movea.w parent3(a0), a1
        //     bset   #0, $38(a1)         (signal parent snap-shut)
        applySpringLaunch(player);
        springFiredFlag = true;
        if (routine == ROUTINE_AUTO_CLOSE) {
            // ROM loc_89064 (sonic3k.asm:185930-185942) does not test $38 bit 0;
            // the auto-close animation and ChildObjDat_89150 projectile spawn
            // continue even if the spring child fires during routine 6.
            return;
        }
        startSnapShut();
    }

    private void startSnapShut() {
        springFiredFlag = false;
        closeTimer = CLOSE_FRAMES;
        routine = ROUTINE_SNAP_SHUT;
    }

    /**
     * ROM loc_88FEC (sonic3k.asm:185880-185902): idle / auto-close gate.
     * <pre>
     * loc_88FEC:
     *     btst    #0, $38(a0)             ; spring-fired flag
     *     bne.s   loc_89014               ; -> routine 4 (snap shut)
     *     jsr     Find_SonicTails(pc)     ; a1=closer player, d0=0/2 side, d2=abs(dx)
     *     cmpi.w  #$60, d2
     *     bhs.s   loc_8900C               ; abs(dx) >= $60: just animate idle
     *     btst    #0, render_flags(a0)
     *     beq.s   loc_89008
     *     subq.w  #2, d0                  ; flip side check when facing right
     * loc_89008:
     *     tst.w   d0
     *     beq.s   loc_89036               ; -> routine 6 (auto-close)
     * </pre>
     */
    private void updateIdle(PlayableEntity primary) {
        if (springFiredFlag) {
            startSnapShut();
            return;
        }
        if (closeTimer > 0) {
            closeTimer--;
            mappingFrame = closeTimer == 0 ? 0 : Math.min(4, CLOSE_FRAMES - closeTimer);
            return;
        }

        // Find_SonicTails (sonic3k.asm:178243-178277): returns closer of Sonic/Tails
        // by abs(dx). d0 = 0 if closer is left of object, 2 if right.
        ClosestPlayer closest = findClosestPlayer(primary);
        if (closest == null) {
            return;
        }
        int dxAbs = Math.abs(closest.dx);
        if (dxAbs >= AUTO_CLOSE_DX_THRESHOLD) {
            return; // loc_8900C: just animate idle.
        }
        // ROM Find_SonicTails computes d2 = x_pos(a0) - x_pos(a1). bpl skips
        // the negate; on player-right (a0.x - a1.x < 0) the negate runs and d0
        // is incremented by 2. Engine's closest.dx is player - clamer, so:
        //     player on LEFT  (dx <= 0): d0 = 0
        //     player on RIGHT (dx >  0): d0 = 2
        int d0 = closest.dx > 0 ? 2 : 0;
        if (facingRight) {
            d0 -= 2; // subq.w #2, d0
        }
        if (d0 == 0) {
            // loc_89036: transition to routine 0x06 (auto-close).
            routine = ROUTINE_AUTO_CLOSE;
            mappingFrame = 0;
            // Animate_RawNoSSTMultiDelay increments anim_frame by 2 before
            // reading byte_89185 (sonic3k.asm:177561-177574). loc_89036 clears
            // anim_frame, so the first routine-6 animation tick leaves frame 0
            // as the already-visible mapping and loads the second table pair.
            autoCloseAnimIndex = 1;
            autoCloseAnimTimer = 0;
            // ROM loc_890AA runs independently of the parent's routine, so we
            // do not touch the spring child cooldown here.
        }
    }

    /**
     * ROM loc_8904E (sonic3k.asm:185919-185921): snap-shut animation after
     * spring-child fires. We collapse this onto our local close timer and
     * then return to routine 0x02 via loc_89056.
     */
    private void updateSnapShut() {
        if (closeTimer > 0) {
            closeTimer--;
            mappingFrame = closeTimer == 0 ? 0 : Math.min(4, CLOSE_FRAMES - closeTimer);
            return;
        }
        routine = ROUTINE_IDLE;
        mappingFrame = 0;
    }

    /**
     * ROM loc_89064 (sonic3k.asm:185930-185940): auto-close uses
     * Animate_RawNoSSTMultiDelay over byte_89185, and when a frame change sets
     * mapping_frame = 8 it spawns ChildObjDat_89150 through
     * CreateChild5_ComplexAdjusted. The child is a real SST slot
     * ({@code loc_86D4A -> loc_86D5E}) and is therefore part of CNZ slot
     * pressure; it is not just a rendered effect.
     */
    private void updateAutoClose() {
        // Animate_RawNoSSTMultiDelay decrements the unsigned byte timer first;
        // bpl returns while it remains non-negative. When it underflows, the
        // next anim_frame-selected frame/delay pair is loaded
        // (sonic3k.asm:177561-177574, 185930-185940; byte_89185 at
        // 186039-186049).
        autoCloseAnimTimer = (autoCloseAnimTimer - 1) & 0xFF;
        if (autoCloseAnimTimer < 0x80) {
            return;
        }

        if (autoCloseAnimIndex >= AUTO_CLOSE_ANIM_FRAMES.length) {
            routine = ROUTINE_IDLE;
            mappingFrame = 0;
            autoCloseAnimIndex = 0;
            autoCloseAnimTimer = 0;
            return;
        }

        int newFrame = AUTO_CLOSE_ANIM_FRAMES[autoCloseAnimIndex];
        mappingFrame = newFrame;
        autoCloseAnimTimer = AUTO_CLOSE_ANIM_DELAYS[autoCloseAnimIndex];
        autoCloseAnimIndex++;

        if (newFrame == AUTO_CLOSE_PROJECTILE_FRAME
                && isWithinRenderSpriteBounds(getOnScreenHalfWidth(), getOnScreenHalfHeight())) {
            // loc_89064 tests the retained render_flags sign bit, which is set
            // only when the full $14x$10 Clamer render box overlaps the screen.
            // An X-only gate incorrectly fires projectiles from vertically
            // off-screen Clamers (sonic3k.asm:185930-185942,186052-186058).
            spawnAutoCloseProjectile();
        }
    }

    @Override
    public int getOnScreenHalfWidth() {
        return 0x14; // ObjSlot_Clamer width_pixels.
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 0x10; // ObjSlot_Clamer height_pixels.
    }

    private void spawnAutoCloseProjectile() {
        // ChildObjDat_89150 (sonic3k.asm:186020-186027):
        // loc_86D4A child, ObjDat3_8913C, MoveSprite2, offset -$10,+2,
        // x_vel -$200. CreateChild5_ComplexAdjusted flips X offset/velocity
        // when parent render_flags bit 0 is set (sonic3k.asm:177062-177108).
        int xOffset = -0x10;
        int xVelocity = -0x200;
        if (facingRight) {
            xOffset = -xOffset;
            xVelocity = -xVelocity;
        }
        int childX = currentX + xOffset;
        int childY = currentY + 2;
        int childXVel = xVelocity;
        spawnChild(() -> new ClamerAutoCloseProjectile(
                buildSpawnAt(childX, childY),
                childXVel));
    }

    @Override
    public int getCollisionFlags() {
        if (destroyed || waitingForOnscreen) {
            return 0;
        }
        // ROM loc_89014 (sonic3k.asm:185899-185906) clears collision_flags
        // during the snap-shut animation (routine 0x04). loc_89036 (auto-close)
        // does NOT clear collision_flags, so the parent collision box stays
        // alive while the auto-close anim plays.
        if (routine == ROUTINE_SNAP_SHUT) {
            return 0;
        }
        return closeTimer > 0 ? 0 : PARENT_COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        return true;
    }

    @Override
    public boolean usesS3kTouchSpecialPropertyResponse() {
        // ROM Touch_ChkValue (sonic3k.asm:20773-20778) routes objects with
        // collision_flags high bits = $C0 to Touch_Special. The spring child
        // uses cflags = $D7 (size $17), which is one of the Touch_Special
        // property indices (sonic3k.asm:21165-21194). Without this hook the
        // engine decoder maps $C0 to BOSS, blocking SPECIAL dispatch.
        return true;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        if (destroyed || waitingForOnscreen) {
            return new TouchRegion[0];
        }

        TouchRegion parent = new TouchRegion(currentX, currentY, getCollisionFlags());
        // The spring rect is exposed only when the spring slot was in the
        // ROM Collision_response_list at the end of the previous frame.
        // ROM Process_Sprites (sonic3k.asm:35965+) runs slot 3
        // (Reserved_object_3 / Obj_ResetCollisionResponseList,
        // sonic3k.asm:8467) which clears the list before slots 4+ repopulate
        // it, but slot 0 (Sonic) reads the list before the clear. So Sonic
        // touch walk at frame F sees slot 5 last-frame list state -- only
        // present when loc_890AA ran during F-1.
        if (!springInListLastFrame) {
            return new TouchRegion[] { parent };
        }

        TouchRegion spring = new TouchRegion(currentX, currentY + SPRING_OFFSET_Y, SPRING_COLLISION_FLAGS);
        return new TouchRegion[] { parent, spring };
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        lastObservedFrameCounter = frameCounter;
        if (result.category() != TouchCategory.SPECIAL
                || !(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }

        // ROM Touch_Special (sonic3k.asm:21162-21194) increments
        // collision_property(a1) for size index $17 on every overlap. The
        // increment is +1 for Player_1 (main character) and +2 for Player_2
        // (sidekick), so the byte encodes which character(s) touched this
        // frame. Check_PlayerCollision inside loc_890AA later masks bits 0-1
        // and indexes word_85890 = [P1, P1, P2, P2] to pick the launch target.
        int incr = player.isCpuControlled() ? 2 : 1;
        if (springRoutine == SpringRoutine.LIVE && !firedDuringThisFramesTouch) {
            // Engine ordering: this touch response runs inside the player
            // physics tick (LevelFrameStep step 2), one phase BEFORE the
            // spring update (step 3). To match the same-frame fire seen in
            // ROM at loc_890AA, fire here directly. The state transition to
            // COOLDOWN_DRAIN happens during this frame advanceSpringRoutine
            // call, which also sets springInListLastFrame=true to mirror the
            // ROM loc_890C4 -> Child_DrawTouch_Sprite tail call after the
            // fire branch.
            //
            // ROM Check_PlayerCollision clears the cprop byte before applying
            // the launch (sonic3k.asm:179907), so we mirror that: a same-frame
            // immediate fire CONSUMES the byte rather than latching it. A
            // subsequent same-frame Touch_Special hit (e.g. the OTHER player
            // also touching) will then re-set the byte for the NEXT
            // post-cooldown frame fire. The actual launch is applied to the
            // toucher (matching ROM word_85890[d0] resolution when only one
            // character has touched so far this frame).
            springCprop = 0;
            fireSpring(player);
            firedDuringThisFramesTouch = true;
            return;
        }
        // Cooldown frames (COOLDOWN_DRAIN / COOLDOWN_DONE), or a same-frame
        // touch that came in after the immediate fire, latch the cprop byte
        // without firing. The fire happens during the next non-cooldown
        // update phase, mirroring the ROM Touch_Special -> next-frame
        // loc_890AA -> Check_PlayerCollision sequence.
        springCprop = (springCprop + incr) & 0xFF;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        if (destroyed) {
            return;
        }
        destroyed = true;
        destroySpringChildSlot();
        int mySlot = ObjectLifetimeOps.detachSlotForTransfer(this);
        setDestroyed(true);
        DestructionEffects.destroyBadnik(
                currentX, currentY, spawn, mySlot, playerEntity, services(), S3K_DESTRUCTION_CONFIG);
    }

    @Override
    public void onUnload() {
        destroySpringChildSlot();
    }

    private void destroySpringChildSlot() {
        if (springChildSlot != null) {
            springChildSlot.setDestroyed(true);
            springChildSlot = null;
        }
    }

    private void applySpringLaunch(AbstractPlayableSprite player) {
        int xSpeed = facingRight ? -LAUNCH_SPEED : LAUNCH_SPEED;
        Direction direction = facingRight ? Direction.LEFT : Direction.RIGHT;

        player.setXSpeed((short) xSpeed);
        player.setGSpeed((short) xSpeed);
        player.setYSpeed((short) -LAUNCH_SPEED);
        player.setDirection(direction);
        player.setAir(true);
        player.setCentreYPreserveSubpixel((short) (player.getCentreY() + 6));
        player.setAnimationId(Sonic3kAnimationIds.SPRING);
        player.setJumping(false);

        try {
            services().playSfx(GameSound.SPRING);
        } catch (Exception ignored) {
            // Audio is not required for deterministic headless trace replay.
        }
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(getX(), getY());
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return 5;
    }

    @Override
    public boolean isHighPriority() {
        // ObjSlot_Clamer uses make_art_tile(ArtTile_Clamer,1,1).
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed || waitingForOnscreen) {
            return;
        }
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_CLAMER);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, facingRight, false);
    }

    /**
     * Engine equivalent of ROM Find_SonicTails (sonic3k.asm:178243-178277):
     * picks the closer of Sonic/Tails by abs(dx). Returns closer player and
     * its signed dx (positive when player is right of object).
     */
    private ClosestPlayer findClosestPlayer(PlayableEntity primary) {
        ClosestPlayer best = null;
        ObjectPlayerQuery query = playerQuery(primary);
        for (PlayableEntity entity : query.playersFor(AUTO_CLOSE_PARTICIPATION)) {
            if (!(entity instanceof AbstractPlayableSprite sprite) || sprite.getDead()) {
                continue;
            }
            int dx = sprite.getCentreX() - currentX;
            if (best == null || Math.abs(dx) < Math.abs(best.dx)) {
                best = new ClosestPlayer(sprite, dx);
            }
        }
        return best;
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity primary) {
        ObjectServices svc = tryServices();
        if (svc == null) {
            return new ObjectPlayerQuery(() -> primary, List::of);
        }
        ObjectPlayerQuery query = svc.playerQuery();
        return new ObjectPlayerQuery(() -> primary, query::sidekicks);
    }

    private record ClosestPlayer(AbstractPlayableSprite player, int dx) {
    }

    private static final class ClamerSpringChild extends AbstractObjectInstance implements RewindRecreatable {
        private final ClamerObjectInstance parent;

        private ClamerSpringChild(ClamerObjectInstance parent) {
            super(parent.buildSpawnAt(parent.currentX, parent.currentY + SPRING_OFFSET_Y), "ClamerSpringChild");
            this.parent = parent;
        }

        @Override
        public ClamerSpringChild recreateForRewind(RewindRecreateContext ctx) {
            ClamerObjectInstance restoredParent =
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, ClamerObjectInstance.class);
            return restoredParent == null ? null : new ClamerSpringChild(restoredParent);
        }

        @Override
        public int getX() {
            return parent.currentX;
        }

        @Override
        public int getY() {
            return parent.currentY + SPRING_OFFSET_Y;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(getX(), getY());
        }

        @Override
        public boolean isDestroyed() {
            return super.isDestroyed() || parent.destroyed || parent.isDestroyed();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Slot-only child: the parent renders and exposes the spring region.
        }
    }

    private static final class ClamerAutoCloseProjectile extends AbstractObjectInstance
            implements TouchResponseProvider, SpawnTrailingZeroIntsRewindRecreatable {
        private final SubpixelMotion.State motion;
        private boolean collisionEnabled = true;
        private boolean deleteNextFrame;

        private ClamerAutoCloseProjectile(ObjectSpawn spawn, int xVelocity) {
            super(spawn, "ClamerAutoCloseProjectile");
            // loc_86D4A sets ObjDat3_8913C and loc_86D5E runs MoveSprite2
            // before Sprite_CheckDeleteTouchXY (sonic3k.asm:182257-182265).
            this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, xVelocity, 0);
        }

        @Override
        public boolean isHighPriority() {
            // ObjDat3_8913C uses make_art_tile(ArtTile_Clamer+$70,1,1).
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (deleteNextFrame) {
                setDestroyed(true);
                return;
            }
            SubpixelMotion.moveSprite2(motion);
            if (!spriteCheckDeleteTouchXYKeepsAlive()) {
                // Sprite_CheckDeleteTouchXY branches through Go_Delete_Sprite,
                // which installs Delete_Current_Sprite and leaves the SST slot
                // occupied until the next ExecuteObjects pass (sonic3k.asm:
                // 179027-179039, 179131-179134, 36108-36122).
                collisionEnabled = false;
                deleteNextFrame = true;
            }
        }

        private boolean spriteCheckDeleteTouchXYKeepsAlive() {
            ObjectServices svc = tryServices();
            int cameraX = svc != null && svc.camera() != null ? svc.camera().getX() : 0;
            int cameraY = svc != null && svc.camera() != null ? svc.camera().getY() : 0;

            // Sprite_CheckDeleteTouchXY:
            //   (x_pos & $FF80) - Camera_X_pos_coarse_back <= $280 unsigned
            //   y_pos - Camera_Y_pos + $80 < $200 unsigned
            // Camera_X_pos_coarse_back is refreshed by Load_Sprites as
            // (Camera_X_pos - $80) & $FF80 before Process_Sprites
            // (sonic3k.asm:37545-37553, 179027-179039).
            int xAligned = getX() & 0xFF80;
            int coarseBack = (cameraX - 0x80) & 0xFF80;
            int xDistance = (xAligned - coarseBack) & 0xFFFF;
            if (xDistance > 0x280) {
                return false;
            }
            int yDistance = (getY() - cameraY + 0x80) & 0xFFFF;
            return yDistance < 0x200;
        }

        @Override
        public int getX() {
            return motion.x;
        }

        @Override
        public int getY() {
            return motion.y;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(getX(), getY());
        }

        @Override
        public int getCollisionFlags() {
            return collisionEnabled && !deleteNextFrame ? PROJECTILE_COLLISION_FLAGS : 0;
        }

        @Override
        public boolean usesCurrentTouchResponseState() {
            // loc_86D5E runs MoveSprite2 and then Sprite_CheckDeleteTouchXY,
            // whose DrawTouch tail publishes the SST pointer. The following
            // player pass dereferences that live x_pos; a copied snapshot is
            // one projectile step ahead of the ROM-visible contact phase.
            return true;
        }

        @Override
        public boolean isPersistent() {
            // Dynamic child lifetime is owned by loc_86D5E's
            // Sprite_CheckDeleteTouchXY tail, not by ObjectManager's generic
            // post-update out_of_range pass. Go_Delete_Sprite keeps the SST
            // slot occupied as a Delete_Current_Sprite marker until the next
            // ExecuteObjects pass (sonic3k.asm:182263-182266,179027-179039,
            // 179131-179134,36108-36122).
            return true;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public int getShieldReactionFlags() {
            // loc_86D4A: bset #3,shield_reaction(a0).
            return PROJECTILE_SHIELD_REACTION_BOUNCE;
        }

        @Override
        public boolean onShieldDeflect(PlayableEntity playerEntity) {
            if (!(playerEntity instanceof AbstractPlayableSprite player)) {
                return false;
            }
            int dx = player.getCentreX() - getX();
            int dy = player.getCentreY() - getY();
            int angle = TrigLookupTable.calcAngle(saturateToShort(dx), saturateToShort(dy));
            motion.xVel = -((TrigLookupTable.cosHex(angle) * PROJECTILE_DEFLECT_SPEED) >> 8);
            motion.yVel = -((TrigLookupTable.sinHex(angle) * PROJECTILE_DEFLECT_SPEED) >> 8);
            collisionEnabled = false;
            return true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectServices svc = tryServices();
            if (svc == null || svc.renderManager() == null) {
                return;
            }
            PatternSpriteRenderer renderer = svc.renderManager().getRenderer(Sonic3kObjectArtKeys.CNZ_CLAMER_SHOT);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(0, getX(), getY(), false, false);
            }
        }
    }

    private static short saturateToShort(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    // Test hooks (package-private) ------------------------------------------

    /** Test-only: current ROM-style routine value. */
    int testRoutine() {
        return routine;
    }

    /** Test-only: directly run the auto-close evaluator with a primary player. */
    void testStepIdle(PlayableEntity primary) {
        updateIdle(primary);
    }

    /** Test-only: release Obj_WaitOffscreen, then run the production init dispatch. */
    void testRunProductionInitializationAfterOffscreenWait() {
        waitingForOnscreen = false;
        update(0, null);
    }
}
