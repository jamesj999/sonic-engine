package com.openggf.game.sonic2.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.level.objects.SpringHelper;
import com.openggf.level.objects.BoxObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.level.objects.*;

import com.openggf.audio.GameSound;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

public class SpringObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider, RewindRecreatable {
    // Subtype constants (shifted >> 3 & 0xE) - matches ROM Obj41_Index
    private static final int TYPE_UP = 0;
    private static final int TYPE_HORIZONTAL = 2;
    private static final int TYPE_DOWN = 4;
    private static final int TYPE_DIAGONAL_UP = 6;
    private static final int TYPE_DIAGONAL_DOWN = 8;

    // Diagonal slope data
    private static final byte[] SLOPE_DIAG_UP = {
            0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10,
            0x10, 0x10, 0x10, 0x10, 0x0E, 0x0C, 0x0A, 0x08,
            0x06, 0x04, 0x02, 0x00, (byte) 0xFE, (byte) 0xFC, (byte) 0xFC, (byte) 0xFC,
            (byte) 0xFC, (byte) 0xFC, (byte) 0xFC, (byte) 0xFC
    };
    private static final byte[] SLOPE_DIAG_DOWN = {
            (byte) 0xF4, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF2, (byte) 0xF4, (byte) 0xF6, (byte) 0xF8,
            (byte) 0xFA, (byte) 0xFC, (byte) 0xFE, 0x00,
            0x02, 0x04, 0x04, 0x04,
            0x04, 0x04, 0x04, 0x04
    };

    private static final int ANIM_VERTICAL_IDLE = 0;
    private static final int ANIM_VERTICAL_TRIGGER = 1;
    private static final int ANIM_HORIZONTAL_IDLE = 2;
    private static final int ANIM_HORIZONTAL_TRIGGER = 3;
    private static final int ANIM_DIAGONAL_IDLE = 4;
    private static final int ANIM_DIAGONAL_TRIGGER = 5;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    private boolean redSpring;
    private ObjectAnimationState animationState;
    private int idleAnimId;
    private int triggeredAnimId;
    private int mappingFrame;
    private boolean initialized;
    // Frames remaining in which a horizontal spring's loc_18BC6 proximity launch
    // is suppressed because the spring is still playing its triggered animation
    // (ROM `cmpi.b #3,anim(a0)`, s2.asm:34076). Set on each launch to the trigger
    // animation's displayed-frame count; the ROM's $FD end marker only switches
    // anim away from 3 the frame AFTER the last displayed frame, so a freshly
    // triggered horizontal spring blocks re-fire for exactly that many frames.
    // Tracked locally rather than off ObjectAnimationState.getAnimId() because the
    // shared animation runner switches the animation index a frame early on $FD.
    private int horizontalTriggerLock;

    public SpringObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 8, 1.0f, 0.85f, 0.1f, false);
        // ROM: bit 1 of subtype selects strength (0=red/-$1000, 2=yellow/-$A00)
        this.redSpring = (spawn.subtype() & 0x02) == 0;
        this.idleAnimId = resolveIdleAnimId();
        this.triggeredAnimId = resolveTriggeredAnimId();
        this.mappingFrame = resolveIdleMappingFrame();
    }

    @Override
    public SpringObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SpringObjectInstance(ctx.spawn(), getName());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        ObjectRenderManager renderManager = services().renderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getSpringAnimations() : null,
                idleAnimId,
                mappingFrame);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive spring activation from update().
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    private void applyCheckpointContact(AbstractPlayableSprite player, PlayerSolidContactResult contact) {
        if (player == null || contact == null || contact.kind() == ContactKind.NONE) {
            return;
        }

        // ROM behavior: No check for "springing" state before triggering.
        // The ROM only checks pushing/standing flags and side position.
        // Natural collision resolution (pushing player away) prevents
        // infinite re-triggering on the same spring. The move_lock/springing
        // state only locks player INPUT, not object interactions.

        int type = getType();

        if (type == TYPE_DIAGONAL_UP) {
            // ROM: Obj41_DiagonallyUp only calls loc_18DB4 after
            // SlopedSolid_SingleCharacter sets the standing bit.
            if (!contact.standingNow()) {
                return;
            }
            if (!hasReachedDiagonalLaunchThreshold(player)) {
                return;
            }
            applyDiagonalSpring(player, true);
            return;
        }

        if (type == TYPE_DIAGONAL_DOWN) {
            // ROM: Obj41_DiagonallyDown only launches on the d4 == -2 bottom-contact path.
            if (contact.kind() != ContactKind.BOTTOM) {
                return;
            }
            if (!hasReachedDiagonalLaunchThreshold(player)) {
                return;
            }
            applyDiagonalSpring(player, false);
            return;
        }

        if (type == TYPE_HORIZONTAL) {
            // ROM: Obj41_Horizontal push path (loc_18AA8/loc_18AD8) — fires when the
            // player has the pushing bit set on the spring's launch side
            // (s2.asm:33976-33987). The second, contact-independent proximity path
            // (loc_18BC6) is handled in update() so it runs even with no solid
            // contact this frame.
            if (!contact.pushingNow()) {
                return;
            }
            applyHorizontalSpring(player);
            return;
        }

        if (type == TYPE_DOWN) {
            if (contact.kind() != ContactKind.BOTTOM) {
                return;
            }
            applyDownSpring(player);
            return;
        }

        // Default: Up spring
        if (!contact.standingNow()) {
            return;
        }
        applyUpSpring(player);
    }

    /**
     * ROM: Obj41_Up (loc_189CA)
     * - addq.w #8,y_pos(a1)
     * - move.w objoff_30(a0),y_vel(a1) [negative = up]
     * - bset #status.player.in_air
     */
    /**
     * ROM: Obj41_Up (loc_189CA)
     * - addq.w #8,y_pos(a1) -> In ROM, Y increases downward, so this pushes player
     * down
     * - In our engine, Y increases upward, so we SUBTRACT to push down (away from
     * spring face)
     */
    private void applyUpSpring(AbstractPlayableSprite player) {
        // ROM: addq.w #8,y_pos(a1) — push player down 8px (away from spring face)
        // before launching. y_pos is center coordinate.
        player.setCentreYPreserveSubpixel((short) (player.getCentreY() + 8));

        // ROM: y_vel = negative value (negative = up in Y-down coordinate system)
        player.setYSpeed((short) getStrength()); // Negative = up

        player.setAir(true);
        // ROM loc_189CA (s2.asm:33732-33733):
        //   bset #status.player.in_air,status(a1)
        //   bclr #status.player.on_object,status(a1)
        // SolidObject_Always_SingleCharacter just landed the player on the spring
        // (set OnObj=1); the trigger sub immediately clears it as the player launches
        // off. Without this clear, OnObj remains true into subsequent frames where
        // ROM has it cleared, biasing leader-OnObj reads in CPU follow steering.
        player.setOnObject(false);
        // ROM loc_189CA (s2.asm:33735): move.b #2,routine(a1)
        // Unconditionally forces the player back to Obj01_Control routine. When the
        // player was in the Hurt routine (routine=4), this clears the hurt state so
        // subsequent airborne frames use Obj01_MdAir's +$38 gravity and the
        // Sonic_UpVelCap (-$FC0) cap rather than the hurt routine's +$30 gravity
        // (no cap). MCZ2 trace F925: Sonic was hurt mid-air, hit an up-spring; the
        // engine left hurt=true and produced y_speed=-$FD0 instead of ROM's -$F88.
        player.setHurt(false);
        player.setSpringing(SpringBounceHelper.CONTROL_LOCK_FRAMES);
        trigger(player);
    }

    /**
     * ROM: Obj41_Down - same as Up but flipped
     * - subq.w #8,y_pos(a1)
     * - move.w objoff_30(a0),y_vel(a1) then neg.w
     */
    private void applyDownSpring(AbstractPlayableSprite player) {
        // ROM: subq.w #8,y_pos (pushes player up in Y-down coordinate system)
        // Java engine also has Y-down, so we SUBTRACT to push up (away from spring)
        player.setY((short) (player.getY() - 8));

        // ROM negates the strength for down springs (positive = down in Y-down system)
        player.setYSpeed((short) -getStrength()); // Negated = positive = down

        player.setAir(true);
        // ROM Obj41_Down trigger mirrors Obj41_Up (s2.asm:33732-33733): bclr Status_OnObj.
        player.setOnObject(false);
        // ROM loc_18CC6 (s2.asm:34023): move.b #2,routine(a1) — clears Hurt routine.
        player.setHurt(false);
        player.setSpringing(SpringBounceHelper.CONTROL_LOCK_FRAMES);
        trigger(player);
    }

    /**
     * ROM: Obj41_Horizontal (loc_18AEE)
     * - move.w objoff_30(a0),x_vel(a1) [starts negative]
     * - addq.w #8,x_pos(a1)
     * - bset player facing right
     * - btst spring.x_flip
     * - bne skip_adjustment (if flipped, keep +8 and negative velocity)
     * - bclr player facing (now left)
     * - subi.w #$10,x_pos(a1) [net: -8]
     * - neg.w x_vel(a1) [now positive = right]
     */
    private void applyHorizontalSpring(AbstractPlayableSprite player) {
        int strength = getStrength(); // starts negative
        boolean flipped = isFlippedHorizontal();

        // ROM uses x_pos which is center coordinate.
        // Always add 8 first (addq.w #8,x_pos)
        int newCentreX = player.getCentreX() + 8;
        Direction dir = Direction.RIGHT;

        if (!flipped) {
            // Unflipped spring: subtract 16 (net -8), negate velocity
            // ROM: subi.w #$10,x_pos(a1)
            newCentreX -= 16;
            strength = -strength; // now positive (right)
        } else {
            // Flipped spring: keep +8, keep negative velocity (left)
            dir = Direction.LEFT;
        }

        // ROM adjusts x_pos with word-sized add/sub instructions, which preserve x_sub.
        player.setCentreXPreserveSubpixel((short) newCentreX);
        player.setXSpeed((short) strength);
        player.setDirection(dir);

        // ROM: Horizontal springs do NOT set in_air!
        // They set inertia (gSpeed) = x_vel and keep player grounded
        // Line 33810: move.w x_vel(a1),inertia(a1)
        player.setGSpeed((short) strength);

        // ROM Line 33818: bpl.s -> move.w #0,y_vel(a1) (if subtype bit 7 set, clear Y
        // velocity)
        if ((spawn.subtype() & 0x80) != 0) {
            player.setYSpeed((short) 0);
        }

        // ROM: move.w #$F,move_lock(a1) — 15 frames of input lock
        // Horizontal springs use move_lock (not springing state) to prevent
        // player from braking immediately after being launched
        player.setMoveLockTimer(SpringBounceHelper.CONTROL_LOCK_FRAMES);

        trigger(player);
    }

    /**
     * ROM: Diagonal springs apply both X and Y velocity.
     * ROM: s2.asm:34052-34058 — Position offsets before launch:
     *   addq.w #6,y_pos(a1)
     *   addq.w #6,x_pos(a1)
     *   btst #0,status(a0)  ; if spring faces right (not x-flipped)
     *   beq.s +              ; skip if flipped
     *   subi.w #$C,x_pos(a1) ; subtract 12 (net -6)
     */
    private void applyDiagonalSpring(AbstractPlayableSprite player, boolean up) {
        int strength = getStrength(); // negative base
        boolean flipped = isFlippedHorizontal();

        // ROM position offsets before launching
        player.setCentreYPreserveSubpixel((short) (player.getCentreY() + 6));
        int newCentreX = player.getCentreX() + 6;
        if (!flipped) {
            // Unflipped (faces right): subtract 12 from X (net -6)
            newCentreX -= 12;
        }
        player.setCentreXPreserveSubpixel((short) newCentreX);

        int xStrength = flipped ? strength : -strength;
        int yStrength = up ? strength : -strength;

        player.setXSpeed((short) xStrength);
        player.setYSpeed((short) yStrength);
        player.setDirection(xStrength < 0 ? Direction.LEFT : Direction.RIGHT);
        player.setAir(true);
        // ROM diagonal spring trigger mirrors Obj41_Up (s2.asm:33732-33733):
        // bset Status_InAir then bclr Status_OnObj.
        player.setOnObject(false);
        // ROM loc_18DD8 (s2.asm:34090) / loc_18EE6 (s2.asm:34173): move.b #2,routine(a1)
        // — clears Hurt routine. Matches Up/Down springs.
        player.setHurt(false);
        player.setSpringing(SpringBounceHelper.CONTROL_LOCK_FRAMES);

        trigger(player);
    }

    /**
     * ROM: loc_18DB4 gates diagonal spring launch on the player's centre X.
     * Unflipped springs launch once {@code springX - 4 < playerX}; flipped springs
     * launch while {@code playerX <= springX + 4}.
     */
    private boolean hasReachedDiagonalLaunchThreshold(AbstractPlayableSprite player) {
        int springX = spawn.x() & 0xFFFF;
        int playerX = player.getCentreX() & 0xFFFF;
        if (isFlippedHorizontal()) {
            return Integer.compareUnsigned((springX + 4) & 0xFFFF, playerX) >= 0;
        }
        return Integer.compareUnsigned((springX - 4) & 0xFFFF, playerX) < 0;
    }

    /**
     * ROM: loc_18BC6 (s2.asm:34075-34138) — the proximity launch path for
     * horizontal springs, run unconditionally each frame at loc_18AE0
     * (s2.asm:34008) for both characters.
     *
     * <p>The {@code cmpi.b #3,anim(a0)} triggered-animation guard
     * (s2.asm:34076-34077) is evaluated ONCE at the top of loc_18BC6, before
     * either character is examined — see {@link #update}. Both characters are
     * then tested against that single snapshot, so launching the main character
     * (which sets {@code anim=3}) does NOT block the sidekick launching in the
     * same frame (HTZ1 f5531, where Sonic and Tails both fire).
     *
     * <p>For each character the spring fires when:
     * <ul>
     *   <li>The player is on the ground ({@code btst in_air / bne skip},
     *       s2.asm:34092-34093).</li>
     *   <li>The player's {@code inertia}, negated when the spring is x-flipped,
     *       is {@code >= 0} — i.e. the player is moving in the spring's launch
     *       direction ({@code move.w inertia(a1),d4 / tst.w d4 / bmi skip},
     *       s2.asm:34094-34101).</li>
     *   <li>The player's centre x/y lies inside the box
     *       {@code [x_pos, x_pos+$28]} x {@code [y_pos-$18, y_pos+$18]} for an
     *       unflipped spring, or {@code [x_pos-$28, x_pos]} for a flipped spring
     *       (s2.asm:34078-34111). The x compares use {@code blo}/{@code bhs}
     *       (unsigned), with the upper edge exclusive.</li>
     * </ul>
     */
    private boolean shouldProximityLaunchHorizontal(AbstractPlayableSprite player) {
        // ROM: btst in_air,status(a1) / bne skip — grounded players only.
        if (player.getAir()) {
            return false;
        }

        boolean flipped = isFlippedHorizontal();

        // ROM: inertia, negated for flipped springs; bmi skip when < 0.
        int inertia = player.getGSpeed();
        if (flipped) {
            inertia = -inertia;
        }
        if (inertia < 0) {
            return false;
        }

        int springX = spawn.x() & 0xFFFF;
        int boxLeft;
        int boxRight;
        if (flipped) {
            // ROM loc_18BE8 path: d1 = x_pos, d0 = x_pos - $28.
            boxLeft = (springX - 0x28) & 0xFFFF;
            boxRight = springX;
        } else {
            boxLeft = springX;
            boxRight = (springX + 0x28) & 0xFFFF;
        }

        int playerX = player.getCentreX() & 0xFFFF;
        // ROM: cmp.w d0,d4 / blo skip ; cmp.w d1,d4 / bhs skip (unsigned, right edge exclusive).
        if (Integer.compareUnsigned(playerX, boxLeft) < 0
                || Integer.compareUnsigned(playerX, boxRight) >= 0) {
            return false;
        }

        int springY = spawn.y() & 0xFFFF;
        int boxTop = (springY - 0x18) & 0xFFFF;
        int boxBottom = (springY + 0x18) & 0xFFFF;
        int playerY = player.getCentreY() & 0xFFFF;
        // ROM: cmp.w d2,d4 / blo skip ; cmp.w d3,d4 / bhs skip.
        if (Integer.compareUnsigned(playerY, boxTop) < 0
                || Integer.compareUnsigned(playerY, boxBottom) >= 0) {
            return false;
        }

        return true;
    }

    private void trigger(AbstractPlayableSprite player) {
        animationState.setAnimId(triggeredAnimId);

        if (getType() == TYPE_HORIZONTAL) {
            // ROM: launching sets anim(a0)=3 (loc_18AEE, s2.asm:34014). loc_18BC6's
            // `cmpi.b #3,anim(a0)` guard then blocks re-fire until AnimateSprite
            // reads the $FD marker and reverts the index. With the ROM byte_19000
            // trigger script running at speed 0 (one displayed frame per game
            // frame), anim stays 3 through the launch frame's render plus the next
            // (frameCount-1) renders, and $FD executes the frame after the last
            // displayed frame — AFTER that frame's launch check. So a launch at
            // frame F next allows a re-fire at F+frameCount+1 (HTZ1: launch f5511,
            // re-fire f5521, frameCount=9). The local lock is decremented once per
            // frame at the end of update(), and the proximity guard is captured at
            // the start, so `frameCount + 1` reproduces that window exactly.
            // (s2.asm:34076, Ani_obj41 byte_19000 at s2.asm:34443-34456.)
            int frames = animationState == null ? 0 : animationState.frameCount(triggeredAnimId);
            if (frames > 0) {
                horizontalTriggerLock = Math.max(horizontalTriggerLock, frames + 1);
            }
        }

        int subtype = spawn.subtype();
        int type = getType();

        // ROM: Animation handling varies by spring type
        // Up/Diagonal-Up: Set Spring animation, then override to Walk if bit 0 set
        // Down/Diagonal-Down: Only set Walk if bit 0 set (no default animation change)
        // Horizontal: Set Walk (unless rolling), then add flip params if bit 0 set

        if (type == TYPE_HORIZONTAL) {
            // ROM: loc_18B11-18B13 - Horizontal springs use Walk animation (unless rolling)
            if (!player.getRolling()) {
                player.setAnimationId(Sonic2AnimationIds.WALK);
            }
            // ROM: loc_18BAA clears pushing flags after a horizontal spring
            // triggers -- and it clears THREE bits, not one
            // (docs/s2disasm/s2.asm:34074-34076):
            //   bclr #p1_pushing_bit,status(a0)
            //   bclr #p2_pushing_bit,status(a0)
            //   bclr #status.player.pushing,status(a1)
            // The two object-side bits matter on the following frame: with the
            // spring's own pushing bit clear, the next pass's
            // SolidObject_TestClearPush `btst d4,status(a0)`
            // (docs/s2disasm/s2.asm:35462-35466) fails and the Walk/Run restart
            // write is skipped. Clearing only the player flag leaves the engine
            // restarting the launched character's run animation one frame after
            // every horizontal-spring launch.
            services().objectManager().solidContacts()
                    .releaseObjectPushLatchForAllPlayers(this);
            player.setPushing(false);
        } else if (type == TYPE_UP || type == TYPE_DIAGONAL_UP) {
            // ROM: loc_189CA/loc_18E10 - Up springs set Spring animation first
            player.setAnimationId(Sonic2AnimationIds.SPRING);
        }
        // Down springs (TYPE_DOWN, TYPE_DIAGONAL_DOWN): No default animation change

        // ROM: If bit 0 set, override to Walk animation with flip/twirl effect
        if ((subtype & 0x01) != 0) {
            player.setAnimationId(Sonic2AnimationIds.WALK);
            player.setFlipAngle(1);

            if (type == TYPE_UP || type == TYPE_DOWN) {
                // ROM: Up/Down springs use flip_speed=4, flips=0 (or 1 if bit 1 NOT set)
                player.setFlipSpeed(4);
                player.setFlipsRemaining((subtype & 0x02) != 0 ? 0 : 1);
            } else {
                // ROM: Horizontal/Diagonal springs use flip_speed=8, flips=1 (or 3 if bit 1 NOT set)
                player.setFlipSpeed(8);
                player.setFlipsRemaining((subtype & 0x02) != 0 ? 1 : 3);
            }

            // ROM: move.w #1,inertia(a1) - Set inertia for twirl
            short inertia = 1;

            // ROM: Negate flip_angle and inertia if player facing left
            if (player.getDirection() == Direction.LEFT) {
                player.setFlipAngle(-player.getFlipAngle());
                inertia = -1;  // ROM: neg.w inertia(a1)
            }

            // Only set inertia for non-horizontal springs (horizontal already sets gSpeed = x_vel)
            if (type != TYPE_HORIZONTAL) {
                player.setGSpeed(inertia);
            }
        }

        // ROM: loc_18A3E-18A66 - Set collision layer based on subtype bits 2-3
        SpringHelper.applyCollisionLayerBits(player, subtype);

        try {
            services().playSfx(GameSound.SPRING);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    /**
     * ROM: getStrength returns NEGATIVE values
     * Obj41_Strengths: dc.w -$1000, -$A00
     * Bit 1 of subtype: 0=red(-$1000), 2=yellow(-$A00)
     */
    private int getStrength() {
        return SpringBounceHelper.strength(redSpring);
    }

    private int getType() {
        // ROM: lsr.w #3,d0 then andi.w #$E,d0
        return (spawn.subtype() >> 3) & 0xE;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    /**
     * ROM behavior: Springs are always solid.
     * The onSolidContact guard (checking player.getSpringing()) prevents
     * re-triggering while allowing the spring to remain solid for collision.
     * This is critical for spring loops where the player must collide with
     * the second spring after hitting the first.
     *
     * Previous implementation made springs non-solid during springing state,
     * which caused players to pass through springs and hit terrain.
     */
    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return true;
    }

    /**
     * ROM divergence: every S2 spring variant uses
     * {@code SolidObject_Always_SingleCharacter}
     * (s2.asm:33709/33718/33784/33802) which jumps directly to
     * {@code SolidObject_cont} (s2.asm:35147) without traversing the
     * {@code SolidObject_OnScreenTest} on-screen gate at s2.asm:35140-35145.
     * Off-screen springs therefore still resolve push and side contact in
     * the ROM. Mirrors the S3K {@code SolidObjectFull2_1P} behaviour
     * (sonic3k.asm:41065-41067). The S2 CollisionRules currently keep
     * {@code solidObjectOffscreenGate=false}; this override is defensive so
     * the bypass continues to hold if S2 enables the gate in the future.
     */
    @Override
    public boolean bypassesOffscreenSolidGate() {
        return true;
    }

    /**
     * ROM collision params vary by type:
     * Up/Down: D1=$1B (27), D2=8, D3=$10 (16)
     * Horizontal: D1=$13 (19), D2=$E (14), D3=$F (15)
     * Diagonal: D1=$1B (27), D2=$10 (16) - taller to catch player running off terrain
     */
    @Override
    public SolidObjectParams getSolidParams() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            // ROM: d2=$E (14), d3=$F (15) — air half-height and ground half-height
            return SolidObjectParams.of(19, 14, 15);
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            // ROM: Diagonal springs use d2=$10 (halfHeight=16), taller collision box
            // This is critical for catching the player when running off terrain edges
            return SolidObjectParams.of(27, 16, 16);
        }
        // Up, Down springs use standard vertical params
        // ROM: d2=8, d3=$10 (16) — air half-height=8, ground half-height=16
        return SolidObjectParams.of(27, 8, 16);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM: SolidObject_cont X gate rejects with bhi (s2.asm:35147-35150),
        // so relX == halfWidth*2 is a valid side contact — match with inclusive edge.
        return true;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fullSolid(
                usesStickyContactBuffer(),
                usesInclusiveRightEdge(),
                bypassesOffscreenSolidGate());
    }

    @Override
    public byte[] getSlopeData() {
        int type = getType();
        if (type == TYPE_DIAGONAL_UP) {
            return SLOPE_DIAG_UP;
        }
        if (type == TYPE_DIAGONAL_DOWN) {
            return SLOPE_DIAG_DOWN;
        }
        return null;
    }

    @Override
    public boolean isSlopeFlipped() {
        return isFlippedHorizontal();
    }

    @Override
    public boolean usesGroundedStandingCatchWindow() {
        int type = getType();
        return type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN;
    }

    @Override
    public boolean addsSlopeCatchRangeToVerticalOverlap() {
        int type = getType();
        // S2 SlopedSolid_cont keeps the diagonal spring's d2 catch range in the
        // vertical overlap value after sampling the slope surface:
        // move.b y_radius,d3 / add.w d3,d2 / ... / add.w d2,d3.
        return type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        boolean loadFrame = !initialized;
        ensureInitialized();
        if (loadFrame) {
            // ROM Obj41_Init (s2.asm:33824-33888): routine 0 sets mappings, art
            // tile, width, priority and the subtype-specific routine index, then
            // returns through Obj41_Init_Common's `rts` (s2.asm:33886). It does
            // NOT fall through to the action routine, so the frame on which a
            // spring is loaded runs no SolidObject pass, no push launch, no
            // loc_18BC6 proximity test and no AnimateSprite call — the action
            // routine first executes on the following frame.
            //
            // This matters wherever a spring is loaded already overlapping a
            // character: without it the engine resolves the side contact and
            // fires one frame ahead of the ROM.
            return;
        }
        animationState.update();
        mappingFrame = animationState.getMappingFrame();

        SolidCheckpointBatch batch = services().solidExecution().resolveSolidNowAll();
        boolean horizontal = getType() == TYPE_HORIZONTAL;
        List<PlayableEntity> participants = playerParticipants(playerEntity);

        // ROM Obj41_Horizontal order (s2.asm:33967-34010): the SolidObject pass +
        // push-launch (loc_18AA8/loc_18AD8) runs for BOTH characters FIRST, then
        // loc_18BC6 (the proximity launch) runs once afterward. So all push
        // contacts resolve before any proximity check.
        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite player) {
                applyCheckpointContact(player, batch.perPlayer().get(participant));
            }
        }

        // ROM loc_18BC6 (s2.asm:34076-34077): the `cmpi.b #3,anim(a0)` triggered-
        // animation guard is read ONCE, AFTER the push passes above, before either
        // character is tested. Capturing the lock here (post-push) means a push
        // launch this frame suppresses the proximity path (they are mutually
        // exclusive, CNZ2 f205), while a proximity launch of the main character
        // does NOT suppress the sidekick's proximity launch in the same frame
        // (HTZ1 f5531, both fire under the single guard snapshot).
        boolean proximityArmed = horizontal && horizontalTriggerLock == 0;
        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite player) {
                // ROM: Obj41_Horizontal runs loc_18BC6 unconditionally at loc_18AE0
                // (s2.asm:34008), independent of whether a solid contact was
                // registered. A player who lands on flush ground next to a
                // horizontal spring and rolls/runs across it (HTZ1 f5511) is
                // launched here even with no pushing contact.
                if (proximityArmed && shouldProximityLaunchHorizontal(player)) {
                    applyHorizontalSpring(player);
                }
            }
        }

        // ROM AnimateSprite reads the $FD end marker (reverting the triggered
        // animation away from index 3) the frame AFTER the last displayed trigger
        // frame, so the re-fire lock counts down once per frame after the launch.
        if (horizontalTriggerLock > 0) {
            horizontalTriggerLock--;
        }
    }

    private List<PlayableEntity> playerParticipants(PlayableEntity updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            return withUpdatePlayer;
        }
        return participants;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        ObjectRenderManager.SpringVariant variant = resolveVariant();
        // NOTE: Renderer naming is inverted - "RedRenderer" variants are yellow,
        // default are red
        // So we pass !redSpring to get correct visual color
        PatternSpriteRenderer renderer = renderManager.getSpringRenderer(variant, !redSpring);
        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = getType() == TYPE_DOWN || (spawn.renderFlags() & 0x2) != 0;
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    private ObjectRenderManager.SpringVariant resolveVariant() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            return ObjectRenderManager.SpringVariant.HORIZONTAL;
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            return ObjectRenderManager.SpringVariant.DIAGONAL;
        }
        return ObjectRenderManager.SpringVariant.VERTICAL;
    }

    private int resolveIdleAnimId() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            return ANIM_HORIZONTAL_IDLE;
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            return ANIM_DIAGONAL_IDLE;
        }
        return ANIM_VERTICAL_IDLE;
    }

    private int resolveTriggeredAnimId() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            return ANIM_HORIZONTAL_TRIGGER;
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            return ANIM_DIAGONAL_TRIGGER;
        }
        return ANIM_VERTICAL_TRIGGER;
    }

    private int resolveIdleMappingFrame() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            return 3;
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            return 7;
        }
        return 0;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
