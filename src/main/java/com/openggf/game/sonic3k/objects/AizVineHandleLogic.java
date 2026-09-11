package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.level.objects.ObjectServices;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

/**
 * Shared handle/ride logic for AIZ ride-vine objects (Obj06/Obj0C).
 * Mirrors sub_220C2 and related frame/offset tables from sonic3k.asm.
 */
final class AizVineHandleLogic {
    private static final int RELEASE_DELAY = 0x3C;
    private static final int PLAYER_HANG_Y_OFFSET = 0x14;
    private static final int GRAB_HALF_WIDTH = 0x10;
    private static final int GRAB_HEIGHT = 0x18;

    // byte_22248 / byte_22A4C
    private static final int[] MODE0_PLAYER_FRAMES = {
            0x91, 0x91, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90,
            0x92, 0x92, 0x92, 0x92, 0x92, 0x92, 0x91, 0x91
    };

    // byte_222D4
    private static final int[] MODE1_PLAYER_FRAMES = {
            0x78, 0x78, 0x7F, 0x7F, 0x7E, 0x7E, 0x7D, 0x7D,
            0x7C, 0x7C, 0x7B, 0x7B, 0x7A, 0x7A, 0x79, 0x79
    };

    // byte_222E4 (x, y pairs)
    private static final int[] MODE1_PLAYER_OFFSETS = {
            0, 0x18,
            -0x12, 0x13,
            -0x18, 0,
            -0x12, -0x13,
            0, -0x18,
            0x12, -0x13,
            0x18, 0,
            0x12, 0x13
    };

    // NB: State/PlayerState are plain scalar holders captured for rewind by
    // RewindCodecs.PlainStateHolderCodec (their simpleName ends in "State" and
    // every field has a scalar codec, including the two booleans below), so the
    // owning object's final `handle` field rides keyframes automatically -- no
    // RewindStateful/annotation needed. TestAizRideVineRewind pins this.
    static final class PlayerState {
        int grabFlag;
        int releaseDelay;
        boolean pendingJumpRelease;
        int pendingReleaseAngle;
        // ROM Status_Roll at the moment of grab. The held player is object-
        // controlled, so movement (which would clear Roll) never runs during the
        // hold -- Status_Roll stays at its grab value until Player_TouchFloor
        // clears it on the first grounding. Captured at grab so the Walk latch is
        // decided by the ROM-equivalent held Roll state, not the engine's live
        // roll flag (which its own landing collision may clear early).
        boolean rollingAtGrab;
        // Latches the anim byte to Walk. ROM: CheckGrab writes anim=$14 once and
        // the hanging hold never rewrites it (sonic3k.asm:46742, 46607-46636), so
        // anim stays $14 while airborne. On grounding, Player_TouchFloor (dispatch
        // at sonic3k.asm:24335; character_id branch to Knux_TouchFloor at :24339)
        // runs the Knuckles landing body (Knux_TouchFloor label :32829), which has
        // two independent anim-reset gates: (1) the roll gate -- btst #Status_Roll /
        // beq skips clearing Roll+anim, so anim=0 only when rolling (:32833-32836,
        // matching the Sonic body Player_TouchFloor :24344-24347); (2) the >=0x20
        // gate -- cmpi.b #$20,anim / blo / move.b #0,anim resets anim only for the
        // glide family (:32865-32867; the glide-landing lane owns that path). A held
        // vine player is HANG2=$14 (or Walk=0), so ONLY the roll gate can fire; a
        // not-rolling grab hits NEITHER and keeps $14. The swing branch also writes
        // anim=0 unconditionally (HoldPlayerSwinging :46656). Once set, the hold
        // never rewrites anim, so Walk persists for the rest of the grab.
        boolean walkLatched;
    }

    static final class State {
        int mode;
        int x;
        int y;
        int prevX;
        int prevY;
        final PlayerState p1 = new PlayerState();
        final PlayerState p2 = new PlayerState();
    }

    private AizVineHandleLogic() {
    }

    static void positionFromParent(State state, int parentX, int parentY, int parentAngle) {
        int oldX = state.x;
        int oldY = state.y;
        int angle = (angleByte(parentAngle) + 4) & 0xF8;
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);

        int xOffset = (-sin + 8) >> 4;
        int yOffset = (cos + 8) >> 4;

        state.x = parentX + xOffset;
        state.y = parentY + yOffset;

        if (state.x != oldX) {
            state.prevX = oldX;
        }
        if (state.y != oldY) {
            state.prevY = oldY;
        }
    }

    static boolean anyGrabbed(State state) {
        return state.p1.grabFlag != 0 || state.p2.grabFlag != 0;
    }

    static boolean shouldRender(State state) {
        return !anyGrabbed(state) || state.mode == 0;
    }

    static void markGrabbedAsFastEject(State state) {
        if (state.p1.grabFlag != 0) {
            state.p1.grabFlag = (byte) 0x81;
        }
        if (state.p2.grabFlag != 0) {
            state.p2.grabFlag = (byte) 0x81;
        }
    }

    static void updatePlayers(State state,
            ObjectServices services,
            AbstractPlayableSprite player1,
            AbstractPlayableSprite player2,
            int parentAngle) {
        updatePlayer(state, state.p1, services, player1, parentAngle);
        updatePlayer(state, state.p2, services, player2, parentAngle);
    }

    private static void updatePlayer(State handle,
            PlayerState playerState,
            ObjectServices services,
            AbstractPlayableSprite player,
            int parentAngle) {
        if (playerState.grabFlag != 0) {
            updateGrabbedPlayer(handle, playerState, player, parentAngle);
            return;
        }

        if (playerState.releaseDelay > 0) {
            playerState.releaseDelay--;
            if (playerState.releaseDelay > 0) {
                return;
            }
        }

        if (player == null || !canGrab(handle, player)) {
            return;
        }

        // sub_220C2 capture path (loc_22302)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setCentreXPreserveSubpixel((short) handle.x);
        player.setCentreYPreserveSubpixel((short) (handle.y + PLAYER_HANG_Y_OFFSET));
        player.setAnimationId(Sonic3kAnimationIds.HANG2);
        player.setForcedAnimationId(Sonic3kAnimationIds.HANG2);
        player.setObjectMappingFrameControl(true);
        // ROM AIZRideVineHandle_CheckGrab: move.b #0,spin_dash_flag(a1)
        // (sonic3k.asm:46743). That is a byte write, so it clears the WHOLE
        // field, and the engine splits that one ROM byte across three flags:
        //   bit 0 ($01) -> pinball mode
        //   bit 7 ($80) -> pinball speed lock
        //   the spindash-charge sense used by Tails_Spindash
        // Clearing only the charge left bit 7 latched from an earlier
        // Obj_AutoSpin capture, and Tails_RollSpeed's own entry test
        //   tst.b spin_dash_flag(a0) / bmi.w loc_14DF0   (sonic3k.asm:28180-28181)
        // then skipped input, friction and deceleration for the rest of the
        // level -- so a landed, rolling sidekick kept its ground_vel forever
        // and only slope gravity could change it.
        player.setPinballMode(false);
        player.setPinballSpeedLock(false);
        player.setSpindash(false);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        // ROM grab path (sonic3k.asm:46739-46743 loc_22302) writes only:
        //   move.b #3, object_control(a1)
        //   andi.b #$FD, render_flags(a1)
        //   move.b #1, (a2)
        // ROM writes object_control = 3 (bits 0+1, NOT bit 7). The dispatcher's
        // bit-7 gates therefore stay clear: Sonic_Control's input mirror keeps
        // running, Tails_CPU_Control still sees the leader as "free" (sonic3k.asm:
        // 26481-26482 bmi.w branch in Tails_Catch_Up_Flying only fires when bit 7
        // is set), and the per-frame TouchResponse pass is not suppressed.
        // Engine analog: keep objectControlAllowsCpu=true so the SidekickCpuController
        // NORMAL/CATCH_UP_FLIGHT bit-7 gates evaluate the same way ROM's bmi.w does.
        // No Ctrl_1_locked write. ROM Sonic_Control still runs the input
        // mirror (sonic3k.asm:21970 loc_10BF0 → move.w (Ctrl_1).w,
        // (Ctrl_1_logical).w), so Sonic_RecordPos at sonic3k.asm:22132
        // captures the live BK2 input into Stat_table. Tails CPU then sees
        // that input 16 frames later via getInputHistory(16).
        //
        // Setting controlLocked here suppressed the engine's logical input
        // (SpriteManager.publishInputState gates effective inputs on
        // !controlLocked, line 388-392), which zeroed inputHistory and
        // caused Tails to lose all air-acceleration cues during AIZ
        // GiantRideVine grab sequences. AIZ trace F2878 reproduces this:
        // Sonic was grabbed at F285x while pressing right; ROM-side Tails
        // saw the right input via Stat_table 16 frames later and applied
        // 0x18/frame air-acceleration; engine-side Tails saw zero input
        // and accumulated -0x14 instead of the expected +0x03 at F2878.
        //
        // CPU Tails is different: loc_13E0A/loc_13E34 in Tails_CPU_Control
        // (sonic3k.asm:26717-26724, 26735-26742) suppress the +/-1 follow
        // nudge when object_control bit 0 is set. Obj_AIZGiantRideVine writes
        // object_control=3 in sub_220C2, so mirror bit 0 for CPU sidekicks
        // without applying the same input-history lock to Player_1.
        if (player.isCpuControlled()) {
            player.setControlLocked(true);
        }
        player.setRenderFlips(player.getDirection() == Direction.LEFT, false);
        playerState.grabFlag = 1;
        playerState.walkLatched = false;
        playerState.rollingAtGrab = player.getRolling();
        if (services != null) {
            services.playSfx(Sonic3kSfx.GRAB.id);
        }
    }

    private static boolean canGrab(State handle, AbstractPlayableSprite player) {
        int dx = player.getCentreX() - handle.x;
        if (dx < -GRAB_HALF_WIDTH || dx >= GRAB_HALF_WIDTH) {
            return false;
        }
        int dy = player.getCentreY() - handle.y;
        if (dy < 0 || dy >= GRAB_HEIGHT) {
            return false;
        }
        if (player.isObjectControlled() || player.isControlLocked()) {
            return false;
        }
        if (player.isHurt() || player.getDead() || player.isDebugMode()) {
            return false;
        }
        return true;
    }

    private static void updateGrabbedPlayer(State handle,
            PlayerState playerState,
            AbstractPlayableSprite player,
            int parentAngle) {
        // loc_2217E: forced eject state ($81)
        if (playerState.grabFlag < 0) {
            if (player != null) {
                player.setXSpeed((short) 0x300);
                player.setYSpeed((short) 0x200);
                player.setAir(true);
                clearPlayerControl(player);
            }
            playerState.grabFlag = 0;
            playerState.releaseDelay = RELEASE_DELAY;
            return;
        }

        // AIZRideVineHandle_ProcessPlayer tests the held player's render_flags
        // BEFORE the routine check and the button read:
        //   tst.b  render_flags(a1)
        //   bpl.w  AIZRideVineHandle_ReleasePlayer
        //   cmpi.b #4,routine(a1)
        //   bhs.w  AIZRideVineHandle_ReleasePlayer
        // (sonic3k.asm:46490-46494). Bit 7 is the on-screen flag written by the
        // preceding display pass, so a held player that scrolls out of the
        // render box is dropped on the handle's NEXT pass. The target is the
        // plain AIZRideVineHandle_ReleasePlayer (sonic3k.asm:46548-46552),
        // which only clears object_control and the grab byte and arms the $3C
        // regrab cooldown -- unlike AIZRideVineHandle_ForcedRelease above it
        // writes no velocity, no Status_InAir and no animation, so the player
        // simply resumes normal physics from a standstill.
        //
        // isHurt()/getDead() stand in for the ROM's routine >= 4 test (routine
        // 4 = Hurt, 6 = Dead). The sibling grab object LbzRideGrappleInstance
        // already models the same pair for sub_266B0.
        boolean renderFlagOffScreen =
                player != null
                        && player.hasRenderFlagOnScreenState()
                        && !player.isRenderFlagOnScreen();
        if (player == null || renderFlagOffScreen
                || player.isHurt() || player.getDead() || player.isDebugMode()) {
            if (player != null) {
                clearPlayerControl(player);
            }
            playerState.grabFlag = 0;
            playerState.releaseDelay = RELEASE_DELAY;
            return;
        }

        // ROM sub_220C2 reads Ctrl_1_logical/Ctrl_2_logical and masks only
        // A/B/C press-edge bits before entering loc_22136.
        if (player.isJumpJustPressed()) {
            // The vine object runs after Sonic in the ROM SST order. Queue the
            // release so the velocity is visible at frame end, but position
            // integration does not happen until the next player step.
            playerState.pendingJumpRelease = true;
            playerState.pendingReleaseAngle = parentAngle;
            return;
        }

        // loc_221EC / loc_22258 hold-position paths.
        if (handle.mode == 0) {
            setPlayerHeldMode0(handle, playerState, player, parentAngle);
        } else {
            setPlayerHeldMode1(handle, playerState, player, parentAngle);
        }
        player.setRenderFlips(player.getDirection() == Direction.LEFT, false);
    }

    static void updatePostPlayer(State state,
            AbstractPlayableSprite player1,
            AbstractPlayableSprite player2) {
        updatePostPlayer(state, state.p1, player1);
        updatePostPlayer(state, state.p2, player2);
    }

    private static void updatePostPlayer(State handle, PlayerState playerState, AbstractPlayableSprite player) {
        if (!playerState.pendingJumpRelease) {
            return;
        }
        playerState.pendingJumpRelease = false;
        if (player == null || playerState.grabFlag == 0) {
            return;
        }

        clearPlayerControlImmediate(player);
        playerState.grabFlag = 0;
        playerState.releaseDelay = RELEASE_DELAY;
        launchPlayer(handle, player, playerState.pendingReleaseAngle);
    }

    private static void launchPlayer(State handle, AbstractPlayableSprite player, int parentAngle) {
        if (handle.mode == 1) {
            int angle = angleByte(parentAngle);
            int sin = TrigLookupTable.sinHex(angle);
            int cos = TrigLookupTable.cosHex(angle);
            player.setXSpeed((short) (cos << 3));
            player.setYSpeed((short) (sin << 3));
        } else {
            player.setXSpeed((short) ((handle.x - handle.prevX) << 7));
            player.setYSpeed((short) ((handle.y - handle.prevY) << 7));
            if (player.isLeftPressed()) {
                player.setXSpeed((short) -0x200);
            }
            if (player.isRightPressed()) {
                player.setXSpeed((short) 0x200);
            }
            player.setYSpeed((short) (player.getYSpeed() - 0x380));
        }

        player.setAir(true);
        player.setJumping(true);
        player.applyRollingRadii(false);
        int centreX = player.getCentreX();
        int centreY = player.getCentreY();
        player.setRolling(true);
        player.setCentreXPreserveSubpixel((short) centreX);
        player.setCentreYPreserveSubpixel((short) centreY);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
    }

    private static void setPlayerHeldMode0(State handle, PlayerState playerState,
            AbstractPlayableSprite player, int parentAngle) {
        player.setCentreXPreserveSubpixel((short) handle.x);
        player.setCentreYPreserveSubpixel((short) (handle.y + PLAYER_HANG_Y_OFFSET));

        int angle = angleByte(parentAngle);
        if (player.getDirection() == Direction.LEFT) {
            angle = (-angle) & 0xFF;
        }
        // ROM anim-byte ownership during the hanging hold: CheckGrab writes
        // anim=$14 once (sonic3k.asm:46742) and the hold NEVER rewrites it
        // (AIZRideVineHandle_HoldPlayer only sets mapping_frame, 46607-46636).
        // So anim stays $14 (HANG2) while the held player is airborne. On the
        // first grounding, Player_TouchFloor (dispatch :24335, character_id branch
        // to Knux_TouchFloor at :24339) runs the Knuckles landing body (label
        // :32829): it writes anim=0 + clears Status_Roll ONLY when Status_Roll is
        // set (roll gate btst #Status_Roll / beq, :32833-32836; matching the Sonic
        // body :24344-24347), and the >=0x20 glide gate (:32865-32867) can't fire
        // for HANG2 ($14) -- so a not-rolling grounding leaves anim at $14. Only a
        // rolling grab latches Walk. Status_Roll during the hold equals its grab
        // value (object_control skips movement), so gate on the captured
        // rollingAtGrab. Because the hold never rewrites anim, Walk then latches
        // for the rest of the grab even if the vine lifts the player airborne
        // again. (Open note: HOW TouchFloor runs on a held player while
        // object_control=3 nominally skips Knux_Modes is unpinned; the write +
        // effect set are cited and match the recorded f1947 transition.)
        if (!playerState.walkLatched && !player.getAir() && playerState.rollingAtGrab) {
            player.setRolling(false);
            playerState.walkLatched = true;
        }
        player.setForcedAnimationId(playerState.walkLatched
                ? Sonic3kAnimationIds.WALK
                : Sonic3kAnimationIds.HANG2);
        int index = ((angle + 8) & 0xFF) >> 4;
        player.setMappingFrame(MODE0_PLAYER_FRAMES[index]);
    }

    private static void setPlayerHeldMode1(State handle, PlayerState playerState,
            AbstractPlayableSprite player, int parentAngle) {
        int angle = angleByte(parentAngle);
        if (player.getDirection() == Direction.LEFT) {
            angle = (-angle) & 0xFF;
        }
        int index = (((angle + 0x10) & 0xFF) >> 5) & 0x7;
        int frameIndex = index << 1;

        int frame = MODE1_PLAYER_FRAMES[frameIndex];
        int offsetX = MODE1_PLAYER_OFFSETS[frameIndex];
        int offsetY = MODE1_PLAYER_OFFSETS[frameIndex + 1];
        if (player.getDirection() == Direction.LEFT) {
            offsetX = -offsetX;
        }

        // ROM AIZRideVineHandle_HoldPlayerSwinging writes anim=0 unconditionally
        // (sonic3k.asm:46656). Latch Walk so a later swing->hang transition (mode
        // 1->0 without a grounding) keeps anim=0, matching the ROM (the hanging
        // hold never rewrites anim back to $14).
        playerState.walkLatched = true;
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        player.setForcedAnimationId(Sonic3kAnimationIds.WALK);
        player.setMappingFrame(frame);
        player.setCentreXPreserveSubpixel((short) (handle.x + offsetX));
        player.setCentreYPreserveSubpixel((short) (handle.y + offsetY));
    }

    static void clearPlayerControl(AbstractPlayableSprite player) {
        player.setObjectMappingFrameControl(false);
        player.setForcedAnimationId(-1);
        player.setControlLocked(false);
        player.deferObjectControlRelease();
        // Suppress the stale jump press to prevent immediate ability activation
        // (insta-shield / glide). While object-controlled, PlayableSpriteMovement
        // doesn't run, so its jumpPrevious field is stale. Without suppression the
        // edge detector sees a false "new press" on the release frame, which
        // satisfies the double-jump condition and fires the ability instantly.
        player.suppressNextJumpPress();
    }

    private static void clearPlayerControlImmediate(AbstractPlayableSprite player) {
        player.setObjectMappingFrameControl(false);
        player.setForcedAnimationId(-1);
        player.setControlLocked(false);
        ObjectControlState.none().applyTo(player);
        player.suppressNextJumpPress();
    }

    private static int angleByte(int angleWord) {
        return (angleWord >> 8) & 0xFF;
    }
}
