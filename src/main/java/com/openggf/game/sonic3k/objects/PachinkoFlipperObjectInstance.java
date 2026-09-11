package com.openggf.game.sonic3k.objects;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Object 0xE7 - Pachinko flipper.
 *
 * <p>ROM reference: {@code Obj_PachinkoFlipper}. This is a sloped top-solid flipper
 * that locks the player into rolling while standing on it, accelerates them along
 * the surface, and launches them when jump is pressed.
 */
public class PachinkoFlipperObjectInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, SpawnRewindRecreatable {

    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x20, 0x1C, 0x1D);

    // ROM: byte_49E5A
    private static final byte[] SLOPE_DATA = {
            -4, -4, -4, -4, -4, -4, -4, -4,
            -4, -5, -6, -7, -8, -9, -10, -11,
            -12, -13, -14, -15, -16, -17, -18, -19,
            -20, -21, -22, -23, -24, -25, -26, -27
    };

    // ROM idle anim frame: byte_49E7E = delay 0x1F, frame 4.
    private static final int IDLE_FRAME = 4;

    // ROM trigger anim: byte_49E81 = 3,2,1,0,1,2,3 with 1-frame delay.
    private static final int[] TRIGGER_SEQUENCE = {3, 2, 1, 0, 1, 2, 3};

    private static final int SURFACE_ACCEL = 0x18;
    private static final int SURFACE_ACCEL_FLIPPED = -0x19; // ROM uses NOT.W on 0x18, yielding -25.
    // ROM Obj_PachinkoFlipper keeps a SEPARATE per-player standing counter byte and
    // calls sub_49CFE once per character with its own pointer: `lea $36(a0),a3 /
    // lea (Player_1).w,a1 ... bsr.s sub_49CFE` then `lea $37(a0),a3 /
    // lea (Player_2).w,a1 ... bsr.s sub_49CFE` (sonic3k.asm:96389-96397), and
    // sub_49D72's release pass repeats the same $36/$37 split
    // (sonic3k.asm:96403-96410). Collapsing both into one reference made a
    // two-character ride mutually exclusive: with Sonic and Tails both standing on
    // the same flipper, each character's contact saw the OTHER character in the
    // single slot, re-entered the newly-locked branch and returned before
    // loc_49D54's acceleration ever ran, so neither ground_vel advanced again.
    private AbstractPlayableSprite lockedPlayer;
    private AbstractPlayableSprite lockedSidekick;
    private boolean contactThisFrame;
    private boolean sidekickContactThisFrame;
    private int triggerFrame = -1;

    public PachinkoFlipperObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PachinkoFlipper");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-contained: rebuilds from the captured spawn. Scalar fields are reapplied
     * by the standard scalar-restore pass after recreate; the locked-player back-reference
     * is not wired here (it was not captured by the deleted explicit restore path either).
     * Replaces the former explicit dynamic restore path (Phase-2 codec-deletion batch 2).
     */

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public byte[] getSlopeData() {
        return SLOPE_DATA;
    }

    @Override
    public boolean isSlopeFlipped() {
        return isFlippedHorizontal();
    }

    /**
     * {@inheritDoc}
     *
     * <p>ROM {@code SolidObjCheckSloped2}/{@code SolidObjSloped2} (sonic3k.asm:42076-42097,
     * 41732-41830), reached via {@code SolidObjectTopSloped2} (sonic3k.asm:41831-41872) as
     * called from {@code Obj_PachinkoFlipper}'s {@code loc_49C8A} (sonic3k.asm:96384
     * {@code jsr (SolidObjectTopSloped2).l}), samples the slope table byte and subtracts it
     * from {@code y_pos(a0)} directly: {@code move.w y_pos(a0),d0 / sub.w d3,d0} with no
     * baseline term. The engine's default {@link SlopedSolidProvider#getSlopeBaseline()}
     * subtracts {@code slopeData[0]} (here {@code -4}) as a relative baseline, which is only
     * correct for objects whose ROM routine itself normalizes against the table's first
     * entry. For the flipper this introduced a spurious 4px offset that raised its effective
     * collision surface, causing a false-positive landing ~1 frame before the ROM player
     * actually reached the surface (trace f427: expected air=1/status=0x07 continuing to
     * fall past the flipper, engine snapped to a landing with y_speed=0, status=0x0C).
     * Returning 0 here makes the engine use the raw (absolute) slope sample, matching
     * {@code SolidObjCheckSloped2}'s literal subtraction.
     */
    @Override
    public int getSlopeBaseline() {
        return 0;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        if (!contact.standing()) {
            return;
        }

        boolean sidekick = isSidekick(player);
        if (sidekick) {
            sidekickContactThisFrame = true;
        } else {
            contactThisFrame = true;
        }

        if (player.isDebugMode()) {
            releaseLockedPlayer(player);
            return;
        }

        boolean newlyLocked = lockedFor(player) != player;
        lockPlayer(player);
        if (newlyLocked) {
            // ROM sub_49CFE (sonic3k.asm:96416-96434): on a NEW lock (a3 byte was
            // 0), the routine locks control, sets roll radii/Status_Roll, and
            // conditionally lifts y_pos, then returns via locret_49D3A WITHOUT
            // ever touching ground_vel(a1). Acceleration (loc_49D54,
            // sonic3k.asm:96449-96457) is only reachable through the ALREADY-locked
            // branch (loc_49D3C, taken when (a3)!=0). Applying acceleration on the
            // same frame as the initial lock double-counted the landing's
            // ground_vel=x_vel carry-over (trace f430: expected g_speed=-0x18,
            // engine produced 0x0000 by adding +0x18 on top of the -0x18 already
            // set by the landing).
            //
            // The same newly-locked branch writes anim(a1) as a WORD:
            // `move.w #2<<8,anim(a1)` (sonic3k.asm:96422). anim/prev_anim are the
            // adjacent bytes $1C/$1D, so this stores anim=2 (Roll) AND prev_anim=0.
            // Because prev_anim now mismatches anim, the next Animate_Knuckles pass
            // (loc_17D34-17D40, sonic3k.asm:33034-33037) resets anim_frame=0 and
            // anim_frame_timer=0 -- the ball's roll spin restarts from the first
            // AniKnuckles02 frame the frame after the catch. Without this the
            // engine's roll animation kept advancing from its airborne index and
            // stayed one rotation-cycle step (two script entries) ahead of the ROM
            // for the whole flipper ride (trace f435+: expected mapping_frame 0x96,
            // engine produced 0x97, cascading through the entire spin cycle).
            // forceAnimationRestart() invalidates the tracked prev_anim (lastAnimationId)
            // so the next update restarts the script, matching the one-frame-delayed
            // ROM reset (the flipper object runs after the player's own Animate pass).
            // The word write's HIGH byte (anim=2/Roll) is NOT delayed -- it lands this
            // same frame, immediately visible to any same-frame reader, even though the
            // mapping/frame recompute driven by prev_anim waits for next frame's Animate
            // pass. This landing runs through the shared applyObjectLandingState ->
            // clearRollingOnLanding path first (same checkpoint, earlier in this same
            // frame's resolution), which -- since the player is still curled from the
            // prior airborne hop -- clears rolling and publishes anim=Walk exactly as
            // Sonic_ResetOnFloor does. sub_49CFE's word write must republish anim=Roll
            // afterward so the two ROM writes net out to Roll, matching the disasm
            // ordering (Sonic_ResetOnFloor -> object loop -> flipper's own write).
            // forceAnimationRestart() alone never touches animationId (pachinko1 trace
            // f2084: expected player_animation_id=0x0002, engine produced 0x0000 while
            // player_mapping_frame matched at 0x0035 both sides -- proving only the id
            // write, not the mapping recompute, was missing).
            int rollAnimationId = player.resolveAnimationId(CanonicalAnimation.ROLL);
            if (rollAnimationId >= 0) {
                player.setAnimationId(rollAnimationId);
            }
            player.forceAnimationRestart();
            return;
        }
        if (player.isJumpJustPressed()) {
            launchPlayer(player);
        } else {
            driveLockedPlayer(player);
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (lockedPlayer != null && (!contactThisFrame || lockedPlayer.getAir() || lockedPlayer.isDebugMode())) {
            releaseLockedPlayer(lockedPlayer);
        }
        if (lockedSidekick != null
                && (!sidekickContactThisFrame || lockedSidekick.getAir() || lockedSidekick.isDebugMode())) {
            releaseLockedPlayer(lockedSidekick);
        }
        contactThisFrame = false;
        sidekickContactThisFrame = false;

        if (triggerFrame >= 0) {
            triggerFrame++;
            if (triggerFrame >= TRIGGER_SEQUENCE.length) {
                triggerFrame = -1;
            }
        }
    }

    private void lockPlayer(AbstractPlayableSprite player) {
        setLockedFor(player, player);
        player.setControlLocked(true);
        player.setPinballMode(true);
        // ROM sub_49CFE's newly-locked branch (a3 byte == 0, sonic3k.asm:96422-96434)
        // sets object_control(a1) bit 0. From the next frame on, the player's own
        // control routine sees that bit and skips Sonic_Modes ENTIRELY
        // (sonic3k.asm:21973 btst #0,object_control(a0) / beq.s loc_10C0C): the
        // locked player never runs RollRepel/RollSpeed, its ground-wall check
        // (loc_11350), or MoveSprite for itself. Its ground_vel/x_vel/y_vel and
        // position are owned SOLELY by the flipper's loc_49DE4 (sonic3k.asm:96509-96534),
        // which applies acceleration, projects ground_vel onto the player's angle,
        // moves, and does its OWN wall check.
        //
        // The engine mirrors that by asserting the ROM object_control bit-0
        // movement gate (MOVEMENT_SUPPRESSED_ONLY: skips the player's per-frame
        // movement at PlayableSpriteMovement:475 while leaving TouchResponse and
        // SolidObject riding checks active so the flipper keeps its contact) and
        // having {@link #driveLockedPlayer} own the loc_49DE4 move. Previously the
        // engine kept running the player's roll update with only a friction bypass
        // (pinballSpeedLock), which projected x_vel from the PRE-accel ground_vel and
        // then ran the loc_11350 wall check. That one-frame projection lag made the
        // wall sensor predict 1px into a flipper-side wall, wrongly zeroing g_speed,
        // mangling x_vel to +0xE8, and setting Status_Push -- trace f431 expected
        // g_speed=0x0000 / x_speed=0x0000 / status=0x0D, engine produced
        // g_speed=0x0018 / x_speed=0x00E8 / status=0x2D.
        ObjectControlState.setMovementSuppressionPreservingOwnership(player, true);
        // pinballSpeedLock is now inert while suppressed (the player's RollSpeed
        // never runs) but is retained so a hurt/dead frame that bypasses the
        // movement gate still avoids the roll-stop pinball snap.
        player.setPinballSpeedLock(true);
        if (!player.getRolling()) {
            player.setRolling(true);
            player.applyRollingRadii(false);
            player.setY((short) (player.getY() + player.getRollHeightAdjustment()));
        }
    }

    /**
     * ROM already-locked drive: {@code loc_49D54} acceleration followed by
     * {@code loc_49DE4} (sonic3k.asm:96449-96534). Because the locked player skips
     * Sonic_Modes (object_control bit 0, asserted in {@link #lockPlayer}), the
     * flipper owns the player's per-frame ground_vel/x_vel/y_vel and position here,
     * exactly as {@code loc_49DE4} does after {@code movea.l a1,a0}.
     */
    private void driveLockedPlayer(AbstractPlayableSprite player) {
        // loc_49D54 (sonic3k.asm:96449-96457): moveq #$18,d1 / btst #0,status(a0)
        // [flipper facing] / beq.s loc_49D60 / not.w d1 / loc_49D60:
        // add.w d1,ground_vel(a1). NOT.W $18 = $FFE7 = -25 for a flipped flipper.
        // It never writes status(a1)/direction on the player.
        int accel = isFlippedHorizontal() ? SURFACE_ACCEL_FLIPPED : SURFACE_ACCEL;
        int gSpeed = (short) (player.getGSpeed() + accel);
        player.setGSpeed((short) gSpeed);

        // loc_49DE4 friction (sonic3k.asm:96511-96524): ground_vel is nudged toward
        // zero by d5 = the raw controller-input word, clamping at 0 (bcc guard).
        // d5 == 0 whenever no button is held, so for the idle locked ride this is a
        // no-op. The engine's collapsed A/B/C input cannot reconstruct the exact ROM
        // Ctrl_logical word, and this pachinko ride holds no direction while locked
        // (input == 0 across trace frames 431-453), so ground_vel friction is left as
        // that verified no-op rather than modelled from lossy input bits.

        // loc_49E28-49E56 (CheckLeftWallDist/CheckRightWallDist) is reached via
        // projectGroundVelAndMove -> applyBoardWallPush below: it pushes the player out
        // of a board wall and zeros ground_vel WITHOUT setting Status_Push and WITHOUT
        // touching x_vel, once a later flipper ride's flat span ran the player into the
        // pachinko board's left wall (pachinko1 trace f2085).
        projectGroundVelAndMove(player);
    }

    /**
     * ROM loc_49E0A (sonic3k.asm:96532-96541): project {@code ground_vel(a1)} onto the
     * player's own {@code angle(a1)} via {@code GetSineCosine}/{@code muls.w}/{@code asr.l #8},
     * write the result into {@code y_vel}/{@code x_vel}, then {@code jsr MoveSprite_TestGravity2}
     * moves {@code x_pos}/{@code y_pos} by that velocity. Shared by the idle-ride drive path
     * ({@link #driveLockedPlayer}) and the launch-trigger fall-through
     * ({@link #launchPlayer}, reached via {@code loc_49D68}'s {@code bra.w loc_49DE4}
     * sonic3k.asm:96460-96462).
     */
    private void projectGroundVelAndMove(AbstractPlayableSprite player) {
        int gSpeed = player.getGSpeed();
        int angle = player.getAngle() & 0xFF;
        short xVel = (short) ((gSpeed * TrigLookupTable.cosHex(angle)) >> 8);
        short yVel = (short) ((gSpeed * TrigLookupTable.sinHex(angle)) >> 8);
        player.setXSpeed(xVel);
        player.setYSpeed(yVel);
        player.move(xVel, yVel);
        applyBoardWallPush(player);
    }

    /**
     * ROM loc_49E28-loc_49E56 (sonic3k.asm:96542-96557), reached immediately after the
     * ground_vel move above (both from the idle-ride accel branch via
     * {@code loc_49D54->loc_49D60->bra.w loc_49DE4} and the launch-trigger branch via
     * {@code loc_49D68->bra.w loc_49DE4}): {@code jsr (CheckLeftWallDist).l / tst.w d1 /
     * bpl.s loc_49E42 / sub.w d1,x_pos(a0) / move.w #0,ground_vel(a0)}, then the mirrored
     * {@code jsr (CheckRightWallDist).l / tst.w d1 / bpl.s loc_49E56 / add.w d1,x_pos(a0) /
     * move.w #0,ground_vel(a0)}. {@code CheckLeftWallDist}/{@code CheckRightWallDist}
     * (sonic3k.asm:20505, 20188) probe from the player's own x_pos +/-0xA (the
     * non-Competition_mode path taken here) at y_pos with no y_radius offset, distinct
     * from the {@code Obj}-prefixed object variants. Only x_pos is patched by the
     * returned (negative-when-overlapping) distance and ground_vel(a1) is zeroed --
     * x_vel/y_vel and Status_Push are left untouched, so this is a raw position/ground_vel
     * correction on top of the velocity already written above, not a full landing
     * response.
     *
     * <p>Previously unported (see prior revision's comment on this method) on the
     * assumption the player launches before reaching any board wall. A later ride
     * proved that wrong: the flipper's flat span runs into the pachinko board's left
     * wall, and the un-pushed post-move position combined with the un-zeroed ground_vel
     * diverged both fields from the very next frame (pachinko1 trace f2085: expected
     * x=0x007A/g_speed=0x0000, engine produced x=0x0075 -- the naive post-move position
     * -- with g_speed left at the just-accelerated -0x0477 instead of zeroed by the wall
     * push).
     */
    private void applyBoardWallPush(AbstractPlayableSprite player) {
        TerrainCheckResult left =
                ObjectTerrainUtils.checkLeftWallDist(player.getCentreX() - 0xA, player.getCentreY());
        if (left != null && left.distance() < 0) {
            player.setX((short) (player.getX() - left.distance()));
            player.setGSpeed((short) 0);
        }
        TerrainCheckResult right =
                ObjectTerrainUtils.checkRightWallDist(player.getCentreX() + 0xA, player.getCentreY());
        if (right != null && right.distance() < 0) {
            player.setX((short) (player.getX() + right.distance()));
            player.setGSpeed((short) 0);
        }
    }

    /**
     * ROM loc_49DE4's ground_vel/d5 nudge-toward-zero step (sonic3k.lst:96509-96524),
     * shared by both the accelerate-and-drive path ({@link #driveLockedPlayer}, where
     * d5 is always 0 for this idle-hands ride) and the launch-trigger path
     * ({@link #launchPlayer}, where d5 is the action-button mask that fired the
     * trigger). See {@code loc_49DF8}/{@code loc_49E06}: subtract/add d5 toward
     * zero, clamping there (the {@code bcc} guard) rather than overshooting past it.
     */
    private void nudgeGSpeedTowardZero(AbstractPlayableSprite player, int d5) {
        if (d5 == 0) {
            return;
        }
        int gSpeed = player.getGSpeed();
        if (gSpeed > 0) {
            gSpeed = Math.max(0, gSpeed - d5);
        } else if (gSpeed < 0) {
            gSpeed = Math.min(0, gSpeed + d5);
        }
        player.setGSpeed((short) gSpeed);
    }

    private void applyLaunchTriggerFrameGroundVelDecel(AbstractPlayableSprite player) {
        nudgeGSpeedTowardZero(player, AbstractPlayableSprite.INPUT_JUMP);
    }

    private void launchPlayer(AbstractPlayableSprite player) {
        // ROM loc_49D68 (sonic3k.lst:96460-96462, "held button" branch of
        // sub_49CFE) is taken on the SAME per-player call that clears the
        // per-player standing counter and fires the trigger, but it still falls
        // through to loc_49DE4 (sonic3k.lst:96509-96524) before the top-level
        // routine consumes the $38(a0) trigger flag and calls sub_49D72 (this
        // method). loc_49DE4 nudges ground_vel(a1) toward zero by d5 -- the
        // Ctrl_x_logical action-button mask that was just tested nonzero --
        // clamping at 0; it never overwrites x_vel/y_vel, so this ground_vel
        // change is NOT superseded by the launch trig write below. The engine
        // collapses A/B/C into one INPUT_JUMP bit (0x10, matching ROM's
        // button_B_mask), the best available stand-in for d5 given the engine's
        // unified jump input. Omitting this left g_speed 0x10 high at launch
        // (pachinko1 trace f454: expected g_speed=0x0200, engine produced
        // 0x0210 once the launch no longer zeroed g_speed outright).
        applyLaunchTriggerFrameGroundVelDecel(player);

        // loc_49D68's bra.w loc_49DE4 (sonic3k.asm:96462) does not stop at the
        // ground_vel nudge above -- it falls all the way through loc_49E0A
        // (sonic3k.asm:96532-96541), which projects the just-nudged ground_vel
        // onto the player's angle into x_vel/y_vel and runs MoveSprite_TestGravity2,
        // moving x_pos/y_pos BEFORE the top-level Obj_PachinkoFlipper routine
        // consumes $38(a0) and calls sub_49D72 (sonic3k.asm:96469-96504). sub_49D72
        // then measures `x_pos(a1)-x_pos(a0)` (sonic3k.asm:96472-96473) using that
        // ALREADY-MOVED position, not the pre-trigger position. Skipping this move
        // left the distance measurement 2px short (ground_vel=0x200 at angle=0
        // moves the player +2px this same frame), which fed the wrong
        // launchDistance into the sine/cosine launch-velocity formula and diverged
        // x_speed/y_speed for the whole subsequent flight (pachinko1 trace f454:
        // expected x=0x0093/x_speed=0x0427/y_speed=-0x0DB4, engine produced
        // x=0x0091/x_speed=0x0415/y_speed=-0x0D77).
        projectGroundVelAndMove(player);

        int launchDistance = player.getCentreX() - spawn.x();
        if (isFlippedHorizontal()) {
            launchDistance = -launchDistance;
        }

        launchDistance += 0x20;
        int velocityMagnitude = -((launchDistance << 5) + 0x800);
        int angle = ((launchDistance >> 2) + 0x40) & 0xFF;

        int sinValue = TrigLookupTable.sinHex(angle);
        int cosValue = TrigLookupTable.cosHex(angle);
        int yVelocity = (sinValue * velocityMagnitude) >> 8;
        int xVelocity = (cosValue * velocityMagnitude) >> 8;
        if (isFlippedHorizontal()) {
            xVelocity = -xVelocity;
        }

        player.setYSpeed((short) yVelocity);
        player.setXSpeed((short) xVelocity);
        player.setAir(true);
        player.setOnObject(false);
        player.setPushing(false);
        // ROM sub_49D72 (sonic3k.lst:96469-96504, 49D72-49DE4) writes x_vel/y_vel,
        // Status_InAir/Status_OnObj, routine=2, and object_control=0, but never
        // touches ground_vel(a1). g_speed/ground_vel is left at whatever value
        // the last loc_49D54 accel step produced -- it simply isn't consumed
        // again until the player next lands and the ordinary ground-landing code
        // re-derives it from x_vel. Zeroing it here diverged g_speed from the
        // trace at the launch frame (pachinko1 f454: expected g_speed=0x0200,
        // engine produced 0x0000) and cascaded into x/y position and status_byte
        // on every frame after.
        player.setControlLocked(false);
        player.setPinballMode(false);
        player.setPinballSpeedLock(false);
        // ROM sub_49D72 clears object_control(a1) (sonic3k.asm:96505); drop the
        // engine movement gate so the launched player runs its own air physics.
        ObjectControlState.setMovementSuppressionPreservingOwnership(player, false);
        // sub_49D72's bset/bclr pair (sonic3k.asm:96498-96499) only touches
        // Status_InAir/Status_OnObj -- it never writes Status_Facing (bit 0) or
        // calls anything that would flip the player's facing direction, so the
        // launch keeps whatever facing the player had while locked to the flipper
        // even when the launch velocity points the opposite way. Deriving facing
        // from xVelocity's sign here was an unmodelled write: the flipper ride in
        // pachinko1 locks the player facing left (status bit 0 set, e.g. frames
        // 445-453 status=0x0D) while this launch's xVelocity is rightward
        // (x_speed=0x0427), so setting facing from the sign flipped the bit off
        // (pachinko1 trace f454: expected status_byte=0x0007, engine produced
        // 0x0006 once x/x_speed/y_speed already matched).

        var objectManager = services().objectManager();
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }

        setLockedFor(player, null);
        triggerFrame = 0;
        playFlipperSfx();
    }

    /**
     * ROM {@code loc_49D48} (sonic3k.asm:96440-96446) clears {@code object_control(a1)}
     * and the character's OWN counter byte {@code (a3)} -- $36 for Player_1, $37 for
     * Player_2 (sonic3k.asm:96389-96397) -- so one character leaving the flipper never
     * releases the other.
     */
    private void releaseLockedPlayer(AbstractPlayableSprite player) {
        if (player == null || lockedFor(player) == null) {
            return;
        }
        AbstractPlayableSprite lockedPlayer = lockedFor(player);
        lockedPlayer.setControlLocked(false);
        lockedPlayer.setPinballMode(false);
        lockedPlayer.setPinballSpeedLock(false);
        // ROM loc_49D48 (sonic3k.asm:96444) clears object_control(a1) when the
        // player stops standing on the flipper; drop the engine movement gate too.
        ObjectControlState.setMovementSuppressionPreservingOwnership(lockedPlayer, false);
        setLockedFor(lockedPlayer, null);
    }

    /** ROM's Player_2 arm of the {@code $36}/{@code $37} split (sonic3k.asm:96393-96397). */
    private boolean isSidekick(AbstractPlayableSprite player) {
        return player.isCpuControlled();
    }

    private AbstractPlayableSprite lockedFor(AbstractPlayableSprite player) {
        return isSidekick(player) ? lockedSidekick : lockedPlayer;
    }

    private void setLockedFor(AbstractPlayableSprite player, AbstractPlayableSprite value) {
        if (isSidekick(player)) {
            lockedSidekick = value;
        } else {
            lockedPlayer = value;
        }
    }

    private void playFlipperSfx() {
        try {
            services().playSfx(Sonic3kSfx.FLIPPER.id);
        } catch (Exception e) {
            // Keep gameplay logic independent from audio state.
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.PACHINKO_FLIPPER);
        if (renderer == null) {
            return;
        }
        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        int frame = triggerFrame >= 0 ? TRIGGER_SEQUENCE[triggerFrame] : IDLE_FRAME;
        renderer.drawFrameIndex(frame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }
}
