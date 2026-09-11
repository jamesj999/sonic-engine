package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Object 0x68 - HCZ Spinning Column (Sonic 3 & Knuckles).
 *
 * <p>ROM reference: Obj_HCZSpinningColumn (sonic3k.asm:68108-68179).
 */
public class HCZSpinningColumnObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable, SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_HCZSpinningColumn} is installed from the S3K object pointer table at
     * {@code $00032656} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:68113).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }


    private static final String ART_KEY = Sonic3kObjectArtKeys.HCZ_SPINNING_COLUMN;
    private static final int PRIORITY = 5; // ROM: move.w #$280,priority(a0)
    private static final int HALF_WIDTH = 0x10;
    private static final int HALF_HEIGHT = 0x20;
    private static final int ANIM_FRAME_COUNT = 3;
    private static final int ANIM_FRAME_RELOAD = 7;
    private static final int VERTICAL_OSCILLATION_OFFSET = 0x1C; // ROM: Oscillating_table+$1E
    private static final int CAPTURE_SWING_STEP = 2;
    private static final int RELEASE_Y_SPEED = -0x680;
    private static final int PLAYER_PRIORITY = 1; // ROM: move.w #$100,priority(a1)
    private static final int[] PLAYER_TWIST_FRAMES = {
            0x55, 0x59, 0x5A, 0x5B, 0x5A, 0x59, 0x55, 0x56, 0x57, 0x58, 0x57, 0x56
    };
    private static final boolean[] PLAYER_TWIST_FLIPS = {
            false, true, true, false, false, false, true, true, true, false, false, false
    };

    private static final int MOTION_STATIONARY = 0x00;
    private static final int MOTION_HORIZONTAL = 0x02;
    private static final int MOTION_VERTICAL = 0x04;

    private static final class RiderState {
        private AbstractPlayableSprite player;
        private boolean standingLastFrame;
        private boolean active;
        private int swingAngle;
        private int horizontalDistance;
    }

    private int baseX;
    private int baseY;
    private boolean xFlipped;
    private final RiderState[] riders = {new RiderState(), new RiderState()};

    private int motionMode;
    private int motionOffset;
    private int motionStep;
    private int currentX;
    private int currentY;
    private int mappingFrame;
    private int animFrameTimer;

    public HCZSpinningColumnObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZSpinningColumn");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;

        int subtype = spawn.subtype() & 0xFF;
        this.motionMode = (subtype << 1) & 0x06;
        this.motionOffset = subtype & 0xF0;
        this.motionStep = (motionOffset == 0xE0) ? -1 : 1;
        this.currentX = baseX;
        this.currentY = baseY;
        this.mappingFrame = 0;
        this.animFrameTimer = 0;
    }

    @Override
    public HCZSpinningColumnObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZSpinningColumnObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        switch (motionMode) {
            case MOTION_STATIONARY -> {
                currentX = baseX;
                currentY = baseY;
            }
            case MOTION_HORIZONTAL -> updateHorizontalMotion();
            case MOTION_VERTICAL -> updateVerticalMotion();
            default -> {
                currentX = baseX;
                currentY = baseY;
            }
        }
        for (RiderState rider : riders) {
            updateRider(rider, vIntRunCount);
            rider.standingLastFrame = false;
        }
        updateAnimation();
    }

    private void updateRider(RiderState rider, int vIntRunCount) {
        AbstractPlayableSprite player = rider.player;
        if (player == null) {
            return;
        }

        if (rider.active) {
            if (player.getDead() || player.isHurt() || !rider.standingLastFrame) {
                releaseRider(rider, vIntRunCount, false);
                return;
            }
            holdRider(rider, vIntRunCount);
            return;
        }

        if (!rider.standingLastFrame || player.isObjectControlled() || player.getDead() || player.isHurt()) {
            return;
        }
        captureRider(rider);
    }

    private void captureRider(RiderState rider) {
        AbstractPlayableSprite player = rider.player;
        int deltaX = player.getCentreX() - currentX;
        rider.swingAngle = deltaX < 0 ? 0x80 : 0x00;
        rider.horizontalDistance = Math.min(0xFF, Math.abs(deltaX));
        rider.active = true;

        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        // ROM writes object_control=3: CPU input generation remains active,
        // while the ordinary player movement slot is suppressed until release.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        // Obj68 does not write Ctrl_1_locked/Ctrl_2_locked. Keeping the logical
        // pad publisher live lets Sonic_RecordPos store held-button changes for
        // CPU Tails even while object_control suppresses movement.
        player.setControlLocked(false);
        player.setObjectMappingFrameControl(true);
        player.restoreDefaultRadii();
        player.setRolling(false);
        player.setAir(false);
        player.setPushing(false);
        player.setRollingJump(false);
        player.setJumping(false);
        player.setPriorityBucket(PLAYER_PRIORITY);
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        player.setForcedAnimationId(-1);
        player.setAnimationFrameIndex(0);
        player.setAnimationTick(0);
        applyTwistAnimation(player, rider.swingAngle);
    }

    private void holdRider(RiderState rider, int vIntRunCount) {
        AbstractPlayableSprite player = rider.player;
        if (rider.horizontalDistance > 0) {
            rider.horizontalDistance--;
        }
        // ROM keeps the shrinking integer radius in byte 2(a2), but reads it
        // together with byte 3(a2), a sine-derived fractional component, as one
        // 8.8 word before the cosine multiply (sub_32784 loc_327FC-3283E).
        // Dropping that low byte rounds a negative left-side offset one pixel
        // toward zero during the column capture.
        int sineFraction = ((TrigLookupTable.sinHex(rider.swingAngle) + 0x100) >> 2) & 0xFF;
        int fixedRadius = (rider.horizontalDistance << 8) | sineFraction;
        int xOffset = (TrigLookupTable.cosHex(rider.swingAngle) * fixedRadius) >> 16;
        rider.swingAngle = (rider.swingAngle + CAPTURE_SWING_STEP) & 0xFF;

        // ROM move.w writes x_pos only; the low subpixel word remains intact.
        NativePositionOps.writeXPosPreserveSubpixel(player, currentX + xOffset);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        // d5 is the Ctrl_*_logical word; andi.b addresses its low byte, which
        // contains newly pressed A/B/C bits rather than the held byte
        // (sonic3k.asm:68136-68148,68264-68276).
        if (player.isJumpJustPressed()) {
            releaseRider(rider, vIntRunCount, true);
            return;
        }
        // The jump branch returns through loc_325F2 before loc_3260A can
        // publish the incremented twist frame. A boundary-frame release must
        // therefore retain the mapping selected on the preceding hold tick.
        applyTwistAnimation(player, rider.swingAngle);
    }

    private void releaseRider(RiderState rider, int vIntRunCount, boolean jumpedOff) {
        AbstractPlayableSprite player = rider.player;
        if (player == null) {
            rider.active = false;
            return;
        }

        rider.active = false;
        player.setControlLocked(false);
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.setForcedAnimationId(-1);
        player.setOnObject(false);
        player.setPushing(false);
        player.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);

        if (jumpedOff) {
            player.setAir(true);
            player.setJumping(true);
            int releaseY = player.getCentreY();
            player.applyRollingRadii(false);
            player.setRolling(true);
            // setRolling changes the engine sprite height around its top-left
            // anchor; ROM writes the radii/status bytes in place and never
            // adjusts y_pos, so restore the native centre after that box change.
            NativePositionOps.writeYPosPreserveSubpixel(player, releaseY);
            player.setAnimationId(Sonic3kAnimationIds.ROLL);
            // sub_32784 copies y_vel(a0), not the column's direct oscillation
            // delta. Obj_HCZSpinningColumn never writes its y_vel field, so the
            // native launch is the literal -$680 even while its y_pos is moving.
            player.setYSpeed((short) RELEASE_Y_SPEED);
            player.setXSpeed((short) 0);
            player.setGSpeed((short) 0);
            publishReleasedLogicalInput(player);
            // Prevent the same held button from re-triggering the normal jump path,
            // which would add the generic jump sound on the release frame.
            player.suppressNextJumpPress();
        }
    }

    private void publishReleasedLogicalInput(AbstractPlayableSprite player) {
        int inputMask = 0;
        if (player.isUpPressed()) inputMask |= AbstractPlayableSprite.INPUT_UP;
        if (player.isDownPressed()) inputMask |= AbstractPlayableSprite.INPUT_DOWN;
        if (player.isLeftPressed()) inputMask |= AbstractPlayableSprite.INPUT_LEFT;
        if (player.isRightPressed()) inputMask |= AbstractPlayableSprite.INPUT_RIGHT;
        if (player.isJumpPressed()) inputMask |= AbstractPlayableSprite.INPUT_JUMP;
        // The engine's object-control latch skipped the earlier logical-pad
        // publication, but ROM Ctrl_1_logical is global and Sonic_RecordPos has
        // already stored this frame's word before the column slot executes.
        // Repair that same current history entry when the column consumes the
        // live jump press and releases control, so CPU Tails sees the press at
        // the native delayed Stat_table index.
        player.writeLogicalInputAndCurrentFollowerHistory(inputMask, player.isJumpJustPressed());
    }

    private void applyTwistAnimation(AbstractPlayableSprite player, int swingAngle) {
        int frameIndex = ((swingAngle + 0x0B) & 0xFF) / 0x16;
        if (frameIndex < 0 || frameIndex >= PLAYER_TWIST_FRAMES.length) {
            frameIndex = 0;
        }
        player.setMappingFrame(PLAYER_TWIST_FRAMES[frameIndex]);
        // ROM directly writes render_flags (andi.b #$FC / or.b flip) without
        // touching Status_Facing (sub_32610, sonic3k.asm:68077-68091). The twist
        // frame may therefore flip visually while the player's logical facing
        // remains unchanged for the post-column movement path.
        boolean flipLeft = PLAYER_TWIST_FLIPS[frameIndex];
        player.setRenderFlips(flipLeft, false);
    }

    private void updateHorizontalMotion() {
        int nextOffset = motionOffset + motionStep;
        if (motionStep > 0) {
            if (nextOffset == 0xE0) {
                motionStep = -1;
            }
        } else if (nextOffset == 0x00) {
            motionStep = 1;
        }

        motionOffset = nextOffset;
        int xOffset = motionOffset - 0x70;
        if (xFlipped) {
            xOffset = -xOffset;
        }
        currentX = baseX + xOffset;
        currentY = baseY;
    }

    private void updateVerticalMotion() {
        int oscillation = OscillationManager.getByte(VERTICAL_OSCILLATION_OFFSET) & 0xFF;
        int yOffset = xFlipped ? (0x80 - oscillation) : oscillation;
        currentX = baseX;
        currentY = baseY + yOffset;
    }

    private void updateAnimation() {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }

        animFrameTimer = ANIM_FRAME_RELOAD;
        mappingFrame--;
        if (mappingFrame < 0) {
            mappingFrame = ANIM_FRAME_COUNT - 1;
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH + 0x0B, HALF_HEIGHT, HALF_HEIGHT + 1);
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        // The native routine writes object_control=3 before calling
        // SolidObjectFull, so the captured rider remains on its normal
        // continued-ride contact path while movement is suppressed.
        return true;
    }

    @Override
    public boolean rejectsBit7ObjectControlSideContact(PlayableEntity player) {
        // SolidObject_cont returns on a signed object_control byte before side
        // separation. Positive Obj68 capture value $03 still participates,
        // while Tails' $81 catch-up-flight marker cannot be displaced.
        return true;
    }

    @Override
    public boolean rejectsBit7ObjectControlNewSolidContact(PlayableEntity player) {
        // The same signed test precedes new side/top classification
        // (sonic3k.asm:41438-41440).
        return true;
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        // loc_326B6 moves the column before loading its updated x_pos into d4
        // for SolidObjectFull. MvSonicOnPtfm then subtracts that same current
        // x_pos, producing a zero horizontal carry delta
        // (sonic3k.asm:68132-68157,41016-41042,41642-41679).
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj68 calls SolidObjectFull. SolidObject_cont rejects its initial X
        // window with bhi, so relX == d1*2 remains a valid zero-distance side
        // contact that sets Status_Push (sonic3k.asm:41394-41403,
        // 68148-68157).
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (!contact.standing() || !(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        RiderState rider = findOrAllocateRider(player);
        if (rider != null) {
            rider.standingLastFrame = true;
        }
    }

    private RiderState findOrAllocateRider(AbstractPlayableSprite player) {
        for (RiderState rider : riders) {
            if (rider.player == player) {
                return rider;
            }
        }
        for (RiderState rider : riders) {
            if (rider.player == null || (!rider.active && !rider.standingLastFrame)) {
                rider.player = player;
                return rider;
            }
        }
        return null;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawRect(currentX, currentY, HALF_WIDTH, HALF_HEIGHT, 0.2f, 0.8f, 1.0f);
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
        return RenderPriority.clamp(PRIORITY);
    }
}
