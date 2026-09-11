package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SpringBounceHelper;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Springs - Object ID 0x41.
 * <p>
 * Subtype encoding (from docs/s1disasm/_incObj/41 Springs.asm):
 * <ul>
 *   <li>Bit 1: Yellow spring (0=red/-$1000, 2=yellow/-$A00)</li>
 *   <li>Bit 4: Left/Right spring</li>
 *   <li>Bit 5: Downward spring</li>
 *   <li>Neither bit 4 nor 5: Upward spring (default)</li>
 * </ul>
 * <p>
 * No diagonal springs in S1 (unlike S2).
 * No collision layer switching (S1 UNIFIED collision model).
 * No flip/twirl subtype bit (S2-only feature).
 */
public class Sonic1SpringObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    private static final int TYPE_UP = 0;
    private static final int TYPE_HORIZONTAL = 1;

    // Spring_Main: move.b #32/2,obActWid(a0) (41 Springs.asm:45), kept by the
    // upright and downward springs.
    private static final int DEFAULT_ACT_WIDTH = 0x10;

    // Spring_Main sideways branch: move.b #16/2,obActWid(a0) (41 Springs.asm:56).
    private static final int SIDEWAYS_ACT_WIDTH = 0x08;
    private static final int TYPE_DOWN = 2;

    // Spring_Powers: dc.w -$1000, -$A00
    // Strength constants shared via SpringBounceHelper.STRENGTH_RED / STRENGTH_YELLOW

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // From disassembly: move.w #$F,objoff_3E(a1) — horizontal control lock
    // Shared via SpringBounceHelper.CONTROL_LOCK_FRAMES

    // Animation IDs (registered in Sonic1ObjectArtProvider)
    private static final int ANIM_IDLE = 0;
    private static final int ANIM_TRIGGERED = 1;

    // S1 Obj41 calls SolidObject only in active routines. After a trigger,
    // animation/reset routines run without SolidObject before returning active:
    // docs/s1disasm/s1disasm/_incObj/41 Springs.asm:77-110,115-167,172-218
    // docs/s1disasm/s1disasm/_anim/Springs.asm:8-15
    private static final int POST_TRIGGER_INACTIVE_FRAMES = 11;

    private int springType;
    private boolean yellow;
    private int strength;
    private ObjectAnimationState animationState;
    private int mappingFrame;
    private int postTriggerInactiveFrames;
    private boolean contactEnabledThisFrame = true;

    public Sonic1SpringObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Spring");

        int subtype = spawn.subtype();

        // Determine spring type from subtype bits
        if ((subtype & 0x10) != 0) {
            this.springType = TYPE_HORIZONTAL;
        } else if ((subtype & 0x20) != 0) {
            this.springType = TYPE_DOWN;
        } else {
            this.springType = TYPE_UP;
        }

        // Bit 1: yellow flag. andi.w #$F,d0 then index into Spring_Powers
        this.yellow = (subtype & 0x02) != 0;
        this.strength = SpringBounceHelper.strength(!yellow);

        // Initial mapping frame: 0 = idle for both vertical and horizontal sheets
        this.mappingFrame = 0;
    }

    private void ensureInitialized() {
        if (animationState != null) {
            return;
        }
        ObjectRenderManager renderManager = services().renderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getSpringAnimations() : null,
                ANIM_IDLE,
                0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        contactEnabledThisFrame = postTriggerInactiveFrames == 0;
        if (postTriggerInactiveFrames > 0) {
            postTriggerInactiveFrames--;
        }
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }
        if (!contactEnabledThisFrame) {
            return;
        }

        switch (springType) {
            case TYPE_UP -> {
                // Spring_Up: triggers when Sonic is standing on top (obSolid set)
                if (!contact.standing()) {
                    return;
                }
                applyUpSpring(player);
            }
            case TYPE_HORIZONTAL -> {
                // Spring_LR: triggers when Sonic pushes against side (obStatus bit 5)
                if (!contact.pushing()) {
                    return;
                }
                applyHorizontalSpring(player);
            }
            case TYPE_DOWN -> {
                // Spring_Dwn: triggers on bottom contact (d4 < 0), NOT when standing on top
                if (contact.standing() || !contact.touchBottom()) {
                    return;
                }
                applyDownSpring(player);
            }
        }
    }

    /**
     * ROM: Spring_BounceUp
     * - addq.w #8,obY(a1) — push Sonic down 8px (away from spring face)
     * - move.w spring_pow(a0),obVelY(a1) — set Y velocity (negative = up)
     * - bset #1,obStatus(a1) — set airborne
     * - bclr #3,obStatus(a1) — clear standing on object
     * - move.b #id_Spring,obAnim(a1) — set Sonic animation to Spring (0x10)
     * - move.b #2,obRoutine(a1) — force Sonic's OWN routine to Sonic_Control (2)
     * Note: ROM does NOT touch obInertia (g_speed) — it preserves the value
     * set by Solid_ResetFloor (g_speed = x_speed at landing time).
     */
    private void applyUpSpring(AbstractPlayableSprite player) {
        // ROM: addq.w #8,obY(a1) — push Sonic down 8px from landing position.
        // After Sonic_ResetOnFloor raised him by 5px (yRadius change from ball→standing),
        // the net effect is +3px from the collision-resolved landing position.
        player.setY((short) (player.getY() + 8));
        player.setYSpeed((short) strength);
        player.setAir(true);
        // ROM Spring_BounceUp (s1disasm/_incObj/41 Springs.asm:88-89): bset Status_InAir,
        // bclr Status_OnObj. Solid_ResetFloor just landed Sonic on the spring (set
        // OnObj=1); the trigger immediately clears it as Sonic launches off.
        player.setOnObject(false);
        // ROM does NOT zero g_speed — it stays at x_speed from Solid_ResetFloor
        player.setSpringing(SpringBounceHelper.CONTROL_LOCK_FRAMES);

        // Up spring sets Sonic's animation to Spring (id_Spring = 0x10)
        player.setAnimationId(Sonic1AnimationIds.SPRING);

        // ROM: move.b #2,obRoutine(a1) (s1disasm/_incObj/41 Springs.asm:96) forces
        // Sonic's object routine to Sonic_Control (2) unconditionally, even if he
        // was in routine 4 (hurt/knockback — see AbstractPlayableSprite.hurt).
        // A hurt Sonic bounced by an up spring must regain control (D-pad/jump)
        // the same frame the spring fires, not wait for a normal grounded
        // Sonic_HurtStop landing.
        player.setHurt(false);

        triggerSpring();
    }

    /**
     * ROM: Spring_BounceDwn
     * - subq.w #8,obY(a1) — push Sonic up 8px (away from spring face)
     * - move.w spring_pow(a0),obVelY(a1) then neg.w — positive = downward
     * - bset #1,obStatus(a1) — set airborne
     * - bclr #3,obStatus(a1) — clear standing on object
     * - move.b #2,obRoutine(a1) — force Sonic's OWN routine to Sonic_Control (2)
     * - Does NOT set Sonic's animation (unlike up spring)
     * - Does NOT touch obInertia (g_speed)
     */
    private void applyDownSpring(AbstractPlayableSprite player) {
        // ROM: subq.w #8,obY(a1) — push player up (away from spring face)
        player.setY((short) (player.getY() - 8));

        // ROM negates strength for down springs: positive = downward
        player.setYSpeed((short) -strength);
        player.setAir(true);
        // ROM Spring_BounceDwn (s1disasm/_incObj/41 Springs.asm:183-184) mirrors Spring_BounceUp:
        // bset Status_InAir / bclr Status_OnObj after the trigger.
        player.setOnObject(false);
        // ROM does NOT zero g_speed
        player.setSpringing(SpringBounceHelper.CONTROL_LOCK_FRAMES);
        // ROM: move.b #2,obRoutine(a1) (s1disasm/_incObj/41 Springs.asm:203) — same
        // unconditional routine override as Spring_BounceUp; see applyUpSpring().
        player.setHurt(false);

        // Down spring does NOT change Sonic's animation
        triggerSpring();
    }

    /**
     * ROM: Spring_BounceLR
     * - move.w spring_pow(a0),obVelX(a1) — starts negative (leftward)
     * - addq.w #8,obX(a1) — push right 8px
     * - btst #0,obStatus(a0) — check H-flip
     * - bne.s Spring_Flipped — if flipped, keep negative vel + right offset
     * - subi.w #$10,obX(a1) — net: push left 8px
     * - neg.w obVelX(a1) — now positive (rightward)
     * Spring_Flipped:
     * - move.w #$F,objoff_3E(a1) — 15 frame control lock
     * - move.w obVelX(a1),obInertia(a1) — set ground speed
     * - bchg #0,obStatus(a1) — toggle facing direction
     * - btst #2,obStatus(a1) / bne skip — if rolling, skip animation change
     * - move.b #id_Walk,obAnim(a1) — set Walk animation
     */
    private void applyHorizontalSpring(AbstractPlayableSprite player) {
        int xVel = strength; // starts negative (leftward)
        boolean flipped = isFlippedHorizontal();

        // Always add 8 first
        int newX = player.getX() + 8;

        if (!flipped) {
            // Unflipped spring: subtract 16 (net -8), negate velocity to rightward
            newX -= 16;
            xVel = -xVel;
        }
        // Flipped spring: keep +8, keep negative velocity (leftward)

        player.setX((short) newX);
        player.setXSpeed((short) xVel);

        // ROM: move.w obVelX(a1),obInertia(a1) — horizontal springs set ground speed
        // Horizontal springs do NOT set airborne
        player.setGSpeed((short) xVel);

        // ROM Spring_BounceLR toggles Sonic's existing facing bit with
        // `bchg #0,obStatus(a1)`; it does not derive facing from launch
        // velocity. This matters when the spring reverses a player who was
        // already facing along the launch direction: status and slope-frame
        // selection intentionally remain opposite to the new velocity.
        // (docs/s1disasm/_incObj/41 Springs.asm:146-149)
        player.setDirection(player.getDirection() == Direction.LEFT
                ? Direction.RIGHT : Direction.LEFT);

        // ROM: move.w #$F,objoff_3E(a1) — 15 frame control lock (Spring_BounceLR,
        // docs/s1disasm/_incObj/41 Springs.asm:145). objoff_3E is the player's
        // locktime field — the same RAM word S2 writes as move_lock from the
        // horizontal spring (docs/s2disasm/s2.asm:34031, loc_18B1C). The ROM only
        // decrements locktime on grounded frames via Sonic_SlopeRepel
        // (docs/s1disasm/_incObj/01 Sonic.asm:1383,1410); it is FROZEN while
        // airborne. The engine models locktime as moveLockTimer, which is
        // likewise only decremented in doSlopeRepel() on grounded modes. The
        // springing flag alone is decremented unconditionally every frame in
        // tickStatus(), so when the LR spring launches Sonic airborne (he flies
        // off a ledge) the bespoke spring lock expires several frames early and
        // the engine starts applying D-pad deceleration before the ROM does
        // (S1 SLZ2 trace f1714 / spring at f06A2: 6 airborne frames must freeze
        // the lock). Drive the control lock through moveLockTimer so the
        // grounded-only decrement matches ROM. Keep springing for the carry /
        // air-spring animation marker consumed elsewhere.
        player.setMoveLockTimer(SpringBounceHelper.CONTROL_LOCK_FRAMES);
        player.setSpringing(SpringBounceHelper.CONTROL_LOCK_FRAMES);

        // ROM: btst #2,obStatus(a1) / bne.s loc_DC56 — skip Walk anim if rolling
        if (!player.getRolling()) {
            player.setAnimationId(Sonic1AnimationIds.WALK);
        }

        // ROM: bclr #5,obStatus(a0) / bclr #5,obStatus(a1) — clear pushing flags
        // (docs/s1disasm/_incObj/41 Springs.asm:154-156). The first bclr is the
        // SPRING's own pushed flag; clearing it is what stops the next frame's
        // Solid_NoCollision `btst #5,obStatus(a0)` (sub SolidObject.asm:243-263)
        // from passing.
        services().objectManager().solidContacts().releaseObjectPushLatch(player, this);
        player.setPushing(false);

        triggerSpring();
    }

    private void triggerSpring() {
        postTriggerInactiveFrames = POST_TRIGGER_INACTIVE_FRAMES;
        contactEnabledThisFrame = false;
        if (animationState != null) {
            animationState.setAnimId(ANIM_TRIGGERED);
        }

        try {
            services().playSfx(Sonic1Sfx.SPRING.id);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        return contactEnabledThisFrame;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (springType == TYPE_HORIZONTAL) {
            // Spring_LR: d1=$13 (19), d2=$E (14), d3=$F (15)
            return SolidObjectParams.of(19, 14, 15);
        }
        // Spring_Up / Spring_Dwn: d1=$1B (27), d2=8, d3=$10 (16)
        return SolidObjectParams.of(27, 8, 16);
    }

    /**
     * Obj41's ROM {@code obActWid}, which the sideways variant narrows.
     *
     * <p>{@code Spring_Main} writes {@code move.b #32/2,obActWid(a0)} = 16 for
     * every spring and then, on the {@code btst #4,d0} sideways branch that also
     * selects the {@code Spring_LR} routine, overwrites it with
     * {@code move.b #16/2,obActWid(a0)} = 8
     * (docs/s1disasm/_incObj/41 Springs.asm:45,49-56). The downward branch does
     * not touch it, so only the left/right spring differs from the shared
     * default. The discriminator is the same subtype bit the class already reads
     * to pick {@link #TYPE_HORIZONTAL}.
     *
     * <p>Supplied here rather than at {@link #getBalanceWidthPixels()} because
     * both ROM consumers want the byte: {@code BuildSprites}' horizontal cull
     * (docs/s1disasm/_inc/BuildSprites.asm:49-58) and {@code Sonic_Balance}
     * (docs/s1disasm/_incObj/01 Sonic.asm:423). Springs are full-solid, so the
     * balance accessor inherits this one. {@code Spring_LR}'s separately
     * authored {@code d1 = #16/2+sonic_solid_width} = {@code $13} at
     * {@code :117} is unchanged.
     *
     * <p>Without the override the inherited 16 balanced only beyond 12px from
     * centre on a top surface reaching 19px, where the ROM balances beyond 4px
     * -- almost all of it.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return springType == TYPE_HORIZONTAL ? SIDEWAYS_ACT_WIDTH : DEFAULT_ACT_WIDTH;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // ROM Spring routines call SolidObject, whose x-range check
        // (Solid_ChkCollision, docs/s1disasm/_incObj/sub SolidObject.asm:160-166)
        // rejects only when `d0 > 2*halfWidth` (`cmp.w d3,d0; bhi.w
        // Solid_NoCollision`), so the RIGHT edge (d0 == 2*halfWidth, i.e. Sonic's
        // solid edge exactly flush against the object's right face) STILL collides.
        // With the default exclusive right edge, a Sonic falling flush against the
        // right side of an LR spring (S1 SYZ1 f502: spring @0218 right solid edge =
        // 0218+19 = 022B, Sonic centre 022B) was rejected as out-of-range, so the
        // spring's side contact never fired and Spring_LR could not set the pushing
        // bit / bounce — Sonic fell to the terrain instead of launching at 0x1000.
        // inclusiveRightEdge=true matches the ROM bhi boundary (same as Girder/
        // Junction/PushBlock/InvisibleBarrier full-solid objects).
        return SolidRoutineProfile.fullSolid(usesStickyContactBuffer(), true, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        String artKey = resolveArtKey();
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = isFlippedHorizontal();
        // Down springs have V-flip (from disassembly: bset #1,obStatus in Spring_Main)
        boolean vFlip = (springType == TYPE_DOWN);

        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    private String resolveArtKey() {
        // S2 convention: default keys = red, "_RED" keys = yellow (inverted naming)
        if (springType == TYPE_HORIZONTAL) {
            return yellow ? ObjectArtKeys.SPRING_HORIZONTAL_RED : ObjectArtKeys.SPRING_HORIZONTAL;
        }
        return yellow ? ObjectArtKeys.SPRING_VERTICAL_RED : ObjectArtKeys.SPRING_VERTICAL;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }
}
