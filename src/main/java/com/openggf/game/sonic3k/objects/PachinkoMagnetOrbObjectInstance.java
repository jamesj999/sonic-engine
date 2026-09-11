package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Object 0xEC - Pachinko magnet orb.
 *
 * <p>ROM reference: {@code Obj_PachinkoMagnetOrb}. The orb tracks capture/orbit state
 * per player slot rather than sharing one global state across the object.
 */
public class PachinkoMagnetOrbObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final int CATCH_RANGE = 0x38;
    private static final int RELEASE_COOLDOWN = 30;
    private static final int ORBIT_RADIUS_SCALE = 0x3800;
    private static final int RELEASE_MAGNITUDE = 0x0C;
    private static final int HOVER_SFX_PERIOD = 16;
    private static final int[] RELEASE_TABLE = {
            0x20, 0x20, 0x20, 0x30, 0x40, 0x50, 0x60, 0x60,
            0x60, 0xA0, 0xA0, 0xB0, 0xC0, 0xD0, 0xE0, 0xE0
    };

    private final IdentityHashMap<AbstractPlayableSprite, PlayerState> playerStates =
            new IdentityHashMap<>();

    public PachinkoMagnetOrbObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PachinkoMagnetOrb");
    }

    @Override
    public PachinkoMagnetOrbObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new PachinkoMagnetOrbObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite updatePlayer = playerEntity instanceof AbstractPlayableSprite player ? player : null;
        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            participants = withUpdatePlayer;
        }

        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite playable) {
                updatePlayer(playable, vIntRunCount);
            }
        }
    }

    private void updatePlayer(AbstractPlayableSprite player, int vIntRunCount) {
        PlayerState state = playerStates.computeIfAbsent(player, ignored -> new PlayerState());
        if (state.captured) {
            updateCapturedPlayer(player, state, vIntRunCount);
            return;
        }

        if (state.cooldownFrames > 0) {
            state.cooldownFrames--;
            if (state.cooldownFrames == 0) {
                restoreReleasedPlayerPriority(player);
            } else {
                return;
            }
        }

        if (player.isDebugMode() || player.getDead() || player.isHurt()) {
            return;
        }

        int dx = player.getCentreX() - spawn.x();
        int dy = player.getCentreY() - spawn.y();
        if (dx < -CATCH_RANGE || dx >= CATCH_RANGE || dy < -CATCH_RANGE || dy >= CATCH_RANGE) {
            return;
        }
        if (player.isObjectControlled() || player.isControlLocked()) {
            return;
        }

        capturePlayer(player, state);
    }

    public void forceReleasePlayer(AbstractPlayableSprite player, int frameCounter) {
        PlayerState state = playerStates.get(player);
        if (state == null || !state.captured) {
            return;
        }
        releasePlayer(player, state, frameCounter, false);
    }

    private void updateCapturedPlayer(AbstractPlayableSprite player, PlayerState state,
                                      int vIntRunCount) {
        if (player.isDebugMode() || player.getDead() || player.isHurt()) {
            releasePlayer(player, state, vIntRunCount, false);
            return;
        }
        // ROM sub_4A428 (sonic3k.asm:96955-96967) runs the captured branch with no
        // on-screen, camera-distance or render-flag test of any kind: once `(a2)` is
        // non-zero, the only exits are Debug_placement_mode, `routine(a1) >= 4`,
        // `object_control` bit 7, and an A/B/C press in that player's own
        // Ctrl_N_logical pressed byte (`andi.b #button_A_mask|button_B_mask|
        // button_C_mask,d1 / bne.w loc_4A4B4`). Player 2 goes through exactly the
        // same subroutine from loc_4A408 (sonic3k.asm:96943-96949), so there is no
        // CPU-sidekick-specific release at all. An extra "release a CPU sidekick
        // that has left the screen" gate ejects Tails as soon as the camera follows
        // Sonic away from the orb, and the loc_4A4F6 tail it then runs sets
        // Status_Roll, anim 2 and the ball radii -- observed as tails_status_byte
        // 0x07 where the ROM holds 0x03, with a 1px tails_y from the shrunken box.
        if (player.isJumpJustPressed()) {
            releasePlayer(player, state, vIntRunCount, true);
            return;
        }

        if (player.isLeftPressed()) {
            state.angleB = (state.angleB - 1) & 0xFF;
        }
        if (player.isRightPressed()) {
            state.angleB = (state.angleB + 1) & 0xFF;
        }

        int previousX = player.getCentreX();
        int previousY = player.getCentreY();
        placePlayerOnOrbit(player, state);
        // ROM sub_4A428 loc_4A464 (sonic3k.asm:96974-96989): saves the OLD x_pos/y_pos,
        // recomputes the new orbit position via sub_4A5E0, then computes
        // `sub.w x_pos(a1),d1` (old-new) followed by `asl.w #8,d1` and `neg.w d1`,
        // yielding x_vel = -(old-new)<<8 = (new-old)<<8 -- i.e. plain per-frame
        // displacement, NOT displacement negated. The extra unary minus here inverted
        // the sign of both axes, so a player orbiting downward reported y_speed going
        // the wrong direction (observed: engine y_speed=-0x0100 vs ROM +0x0100 while
        // captured by a Pachinko energy-trap-adjacent magnet orb).
        player.setXSpeed((short) ((player.getCentreX() - previousX) << 8));
        player.setYSpeed((short) ((player.getCentreY() - previousY) << 8));
        player.setGSpeed((short) 0x0800);
        player.setAir(true);

        if ((vIntRunCount & (HOVER_SFX_PERIOD - 1)) == 0) {
            playSfx(Sonic3kSfx.HOVERPAD);
        }
    }

    private void capturePlayer(AbstractPlayableSprite player, PlayerState state) {
        int angle = TrigLookupTable.calcAngle((short) (player.getCentreX() - spawn.x()),
                (short) (player.getCentreY() - spawn.y()));
        if ((angle & 0x80) != 0) {
            state.angleA = -0x80;
            angle = (angle + 0x80) & 0xFF;
        } else {
            state.angleA = 0;
        }
        state.angleB = (angle - 0x40) & 0xFF;

        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0x0800);
        // ROM sub_4A428 loc_4A5AA (sonic3k.asm:97086-97097) writes ground_vel,
        // render_flags, anim and `move.b #1,object_control(a1)` -- it never
        // touches Ctrl_1_locked/Ctrl_2_locked. Setting the engine's control lock
        // here latched logicalInputState (Obj01_Control's Ctrl_1_locked
        // short-circuit, sonic3k.asm:21968-21971), so the frozen word was what
        // Sonic_RecordPos (sonic3k.asm:22132) stored into Stat_table. ROM copies
        // Ctrl_1 -> Ctrl_1_logical BEFORE the `btst #0,object_control(a0)` test at
        // sonic3k.asm:21973, so a captured player still records live pad state and
        // the sidekick's $44-back Stat_table read (loc_13DA6/loc_13DD0,
        // sonic3k.asm:26682-26700) sees the release press on the correct frame.
        // ROM sub_4A428 loc_4A5AA (sonic3k.asm:97091): `move.b #1,object_control(a1)`
        // sets ONLY bit 0 (movement-suppress) of object_control, not bit 7. The
        // Sonic_Control dispatcher's own TouchResponse gate (sonic3k.asm:22019-22022:
        // `move.b object_control(a0),d0 / andi.b #$A0,d0 / bne.s locret_10C8E / jsr
        // (TouchResponse).l`) only skips TouchResponse (which runs BOTH
        // Test_Ring_Collisions/GiveRing and the general Touch_Loop) when bits 5 or 7
        // ($A0) are set -- bit 0 alone (this orb's capture flag) leaves TouchResponse
        // running every frame. The bit-7 "full control" state used here previously
        // wrongly blocked ring pickups for the whole capture duration -- observed as
        // a permanent -1 ring desync starting at the first ring touched while
        // orbiting the magnet orb (Pachinko trace frame 1252).
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setAir(true);
        player.setOnObject(false);
        player.setJumping(false);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);

        state.captured = true;
        placePlayerOnOrbit(player, state);
    }

    private void placePlayerOnOrbit(AbstractPlayableSprite player, PlayerState state) {
        int radius = (TrigLookupTable.cosHex(state.angleA & 0xFF) * ORBIT_RADIUS_SCALE) >> 16;
        // ROM sub_4A5E0 (sonic3k.asm:97103-97119): computes the X term as
        // `moveq #0,d2 / ... / sub.l d5,d2 / asr.l #8,d2` -- i.e. it NEGATES the
        // full-precision product (radius*sin(angleB)) first and THEN applies the
        // arithmetic shift, giving asr(-P, 8). The Y term has no negation:
        // `asr.l #8,d3` on the plain product, then a plain add.
        // asr(-P, 8) is NOT equal to -(asr(P, 8)) whenever P has a nonzero
        // low byte: 68000 asr.l floors toward negative infinity, so negating
        // before vs after the shift differ by exactly 1 (0x0100 in 8.8 velocity
        // units) whenever radius*sin(angleB) isn't a multiple of 256. The previous
        // `spawn.x() - ((radius*sin)>>8)` shifted first and negated second via the
        // subtraction, which is the mathematically "nicer" but ROM-unfaithful
        // rounding -- it is what produced the 1px X/x_speed drift observed while
        // captured by a Pachinko magnet orb (trace frame 55 onward).
        int xProduct = radius * TrigLookupTable.sinHex(state.angleB & 0xFF);
        int xOffset = (-xProduct) >> 8;
        int yOffset = (radius * TrigLookupTable.cosHex(state.angleB & 0xFF)) >> 8;

        setPlayerCenterPosition(player, spawn.x() + xOffset, spawn.y() + yOffset);

        if (state.angleA < 0) {
            setRenderPriority(player, 5, false);
        } else {
            setRenderPriority(player, RenderPriority.PLAYER_DEFAULT, true);
        }

        state.angleA = (byte) ((state.angleA + 4) & 0xFF);
    }

    private void releasePlayer(AbstractPlayableSprite player, PlayerState state,
                               int frameCounter, boolean launched) {
        if (launched) {
            int combinedAngle = buildReleaseAngle(state.angleA);
            int launchRadius = TrigLookupTable.sinHex(combinedAngle) * RELEASE_MAGNITUDE;
            int xVelocity = (TrigLookupTable.sinHex(state.angleB & 0xFF) * launchRadius) >> 8;
            int yVelocity = -((TrigLookupTable.cosHex(state.angleB & 0xFF) * launchRadius) >> 8);
            player.setXSpeed((short) xVelocity);
            player.setYSpeed((short) yVelocity);
        }

        // ROM loc_4A4F0/loc_4A4F6 (sonic3k.asm:97024-97042) clears object_control
        // bits 0-1 only; there is no Ctrl_1_locked write to undo here either.
        player.releaseFromObjectControl(frameCounter);
        player.setAir(true);
        player.setOnObject(false);
        if (!player.getRolling()) {
            // ROM loc_4A4F6 (sonic3k.asm:97029-97042) asserts y_radius=$E, x_radius=7
            // and the Status_Roll bit as direct writes with NO y_pos modification --
            // unlike Sonic_Roll's feet-planted `addq.w #5,y_pos`, the magnet-orb
            // release keeps the player's centre (y_pos) fixed while the collision box
            // shrinks to ball size. The engine tracks position as top-left, so
            // setRolling() shrinking the box height moves getCentreY() unless the
            // centre is restored; without this the reported y_pos landed 5px too low
            // at the release frame (Pachinko trace frame 94: y 0x0EBF vs ROM 0x0EBA).
            short centreYBeforeRoll = player.getCentreY();
            player.setRolling(true);
            NativePositionOps.writeYPosPreserveSubpixel(player, centreYBeforeRoll);
        }
        player.setRollingJump(false);
        player.setJumping(false);
        player.setPinballMode(true);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setFlipAngle(0);
        player.setDoubleJumpFlag(0);
        restoreReleasedPlayerPriority(player);

        state.captured = false;
        state.cooldownFrames = RELEASE_COOLDOWN;
    }

    private int buildReleaseAngle(int angleA) {
        int angle = angleA & 0xFF;
        int highNibble = RELEASE_TABLE[(angle >>> 4) & 0x0F];
        return ((angle & 0x0F) | highNibble) & 0xFF;
    }

    private void setPlayerCenterPosition(AbstractPlayableSprite player, int centerX, int centerY) {
        player.setX((short) (centerX - (player.getWidth() / 2)));
        player.setY((short) (centerY - (player.getHeight() / 2)));
    }

    private void restoreReleasedPlayerPriority(AbstractPlayableSprite player) {
        setRenderPriority(player, RenderPriority.PLAYER_DEFAULT, true);
    }

    private void setRenderPriority(AbstractPlayableSprite player, int bucket, boolean highPriority) {
        int clampedBucket = RenderPriority.clamp(bucket);
        boolean changed = player.getPriorityBucket() != clampedBucket
                || player.isHighPriority() != highPriority;
        player.setPriorityBucket(clampedBucket);
        player.setHighPriority(highPriority);
        if (changed) {
            SpriteManager spriteManager = services().spriteManager();
            if (spriteManager != null) {
                spriteManager.invalidateRenderBuckets();
            }
        }
    }

    private void playSfx(Sonic3kSfx sfx) {
        try {
            services().playSfx(sfx.id);
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
        return false;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.PACHINKO_F_ITEM);
        if (renderer == null) {
            return;
        }
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(0, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    private static final class PlayerState {
        private boolean captured;
        private int cooldownFrames;
        private int angleA;
        private int angleB;
    }
}
