package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.events.S3kCnzEventWriteSupport;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Object 0x42 - CNZ Cannon ({@code Obj_CNZCannon}).
 *
 * <p>Verified ROM anchors:
 * <ul>
 *   <li>Object routine: {@code Obj_CNZCannon} in {@code sonic3k.asm}</li>
 *   <li>Mappings: {@code Map_CNZCannon} at the final lock-on offset published in
 *   {@link Sonic3kObjectArtKeys} and {@link com.openggf.game.sonic3k.constants.Sonic3kConstants}</li>
 *   <li>Dedicated art: {@code Cannon.bin} from the CNZ lock-on art block, plus the
 *   {@code DPLC_CNZCannon} table that remaps the animated chamber tiles</li>
 * </ul>
 *
 * <p>ROM behavior summary:
 * <ul>
 *   <li>The cannon is a top-solid traversal object until it captures the player.</li>
 *   <li>On capture it forces rolling, locks control, zeroes velocity, and keeps the
 *   player anchored to the cannon until launch.</li>
 *   <li>When the jump button is pressed it computes a launch vector from the current
 *   spin frame, releases control, and applies the ROM turn sound.</li>
 * </ul>
 *
 * <p>The engine keeps the same externally visible contract even though the original
 * object uses a couple of tiny internal sub-states to drive the lift/launch timing.
 * This version keeps the capture/launch handoff explicit so the behavior is easy to
 * test while still matching the ROM's forced rolling and release semantics.
 */
public final class CnzCannonInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_CNZCannon} is installed from the S3K object pointer table at
     * {@code $000318A4} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:66875).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }


    /** Synthetic subtype used by Obj_CNZEndBoss for its watched cannon slot. */
    public static final int END_SEQUENCE_SUBTYPE = 0x80;

    private static final int PRIORITY = 0x280;
    private static final int FRAME_CHAMBER_IDLE = 4;
    private static final int FRAME_SPIN_MIN = 0;
    private static final int FRAME_SPIN_MAX = 8;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PULLING_PLAYER = 1;
    private static final int STATE_READY_TO_LAUNCH = 2;
    private static final int STATE_COOLDOWN = 3;
    private static final int CAPTURE_PULL_GRAVITY = 0x38;
    private static final int PLAYER_CAPTURE_PRIORITY = RenderPriority.MAX;
    private static final int PLAYER_RESTORE_PRIORITY_FRAMES = 8;
    private static final int LAUNCH_PUFF_TIMER = 0x0F;

    // ROM capture writes y_radius=$0E and x_radius=7.
    private static final int ROLL_X_RADIUS = 7;
    private static final int ROLL_Y_RADIUS = 14;

    // ROM sub_3192C: move.w #$10,d1 / move.w #$29,d3 / jmp SolidObjectTop.
    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(0x10, 0x29, 0x29);

    private int state = STATE_IDLE;
    private int stateTimer;
    private int spinAngle;
    private int launchPuffAngle;
    private int chamberFrame = FRAME_CHAMBER_IDLE;
    private boolean spinArmed;
    private AbstractPlayableSprite capturedPlayer;
    private AbstractPlayableSprite releasedPlayer;
    private int releasedPlayerPriorityTimer;

    public CnzCannonInstance(ObjectSpawn spawn) {
        super(spawn, "CNZCannon");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-contained: rebuilds from the captured spawn. Scalar fields are reapplied
     * by the standard scalar-restore pass after recreate; player back-references are not
     * wired here (they were not captured by the deleted explicit restore path either).
     * Replaces the former explicit dynamic restore path (Phase-2 codec-deletion batch 2).
     */

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                ? sprite
                : null;

        updateReleasedPlayerPriority();

        switch (state) {
            case STATE_IDLE -> updateIdle(vIntRunCount, player);
            case STATE_PULLING_PLAYER -> updatePullingPlayer(player);
            case STATE_READY_TO_LAUNCH -> updateReadyToLaunch(vIntRunCount, player);
            case STATE_COOLDOWN -> updateCooldown(vIntRunCount);
            default -> {
                state = STATE_IDLE;
                chamberFrame = FRAME_CHAMBER_IDLE;
                capturedPlayer = null;
            }
        }
    }

    private void updateReleasedPlayerPriority() {
        if (releasedPlayer == null) {
            return;
        }
        if (releasedPlayerPriorityTimer > 0) {
            releasedPlayerPriorityTimer--;
        }
        if (releasedPlayerPriorityTimer <= 0) {
            releasedPlayer.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);
            releasedPlayer = null;
        }
    }

    private void updateIdle(int vIntRunCount, AbstractPlayableSprite player) {
        chamberFrame = FRAME_CHAMBER_IDLE;
        if (player == null || player.isObjectControlled()) {
            return;
        }

        // The ROM's idle routine only runs SolidObjectTop here. Capture happens
        // from the standing bit set by that solid check, mirrored by onSolidContact.
    }

    private void updatePullingPlayer(AbstractPlayableSprite player) {
        AbstractPlayableSprite activePlayer = capturedPlayer != null ? capturedPlayer : player;
        if (activePlayer == null || !activePlayer.isObjectControlled()) {
            state = STATE_IDLE;
            chamberFrame = FRAME_CHAMBER_IDLE;
            capturedPlayer = null;
            return;
        }

        activePlayer.setCentreXPreserveSubpixel((short) spawn.x());
        activePlayer.move((short) 0, activePlayer.getYSpeed());
        activePlayer.setYSpeed((short) (activePlayer.getYSpeed() + CAPTURE_PULL_GRAVITY));
        if ((activePlayer.getCentreY() & 0xFFFF) > spawn.y()) {
            activePlayer.setCentreYPreserveSubpixel((short) spawn.y());
            activePlayer.setAnimationId(0x1C);
            state = STATE_READY_TO_LAUNCH;
        }
        activePlayer.setOnObject(false);
        activePlayer.setAir(true);
    }

    private void updateReadyToLaunch(int vIntRunCount, AbstractPlayableSprite player) {
        // ROM sub_3192C runs before sub_319F4. On the first ready dispatch,
        // $34(a0) is still zero while sub_3192C checks it, then sub_319F4 sees
        // player state word $0200/$0202 and arms $34 for the following frame.
        // Keep that read-before-write ordering so ordinary cannons retain their
        // idle chamber frame for the first ready dispatch.
        if (spinArmed) {
            advanceSpin(vIntRunCount);
        } else {
            spinArmed = true;
        }

        AbstractPlayableSprite activePlayer = capturedPlayer != null ? capturedPlayer : player;
        if (activePlayer == null || !activePlayer.isObjectControlled()) {
            state = STATE_IDLE;
            chamberFrame = FRAME_CHAMBER_IDLE;
            capturedPlayer = null;
            return;
        }

        activePlayer.setCentreXPreserveSubpixel((short) spawn.x());
        activePlayer.setCentreYPreserveSubpixel((short) spawn.y());
        activePlayer.setOnObject(false);
        activePlayer.setAir(true);

        boolean endSequence = (spawn.subtype() & END_SEQUENCE_SUBTYPE) != 0;
        boolean jumpPressed = endSequence
                ? (activePlayer.getForcedInputMask() & AbstractPlayableSprite.INPUT_JUMP) != 0
                : activePlayer.isJumpPressed();
        if (jumpPressed) {
            launchPlayer(activePlayer, vIntRunCount);
        }
    }

    private void updateCooldown(int vIntRunCount) {
        advanceSpin(-1);
        if (stateTimer >= 0 && (vIntRunCount & 0x03) == 0) {
            spawnLaunchPuff();
        }
        stateTimer--;
        if (stateTimer < 0) {
            state = STATE_IDLE;
            capturedPlayer = null;
            chamberFrame = FRAME_CHAMBER_IDLE;
        }
    }

    private void spawnLaunchPuff() {
        int puffAngle = launchPuffAngle;
        spawnChild(() -> new CannonLaunchPuff(spawn.x(), spawn.y() - 0x18, puffAngle));
    }

    private void advanceSpin(int frameCounter) {
        int displayAngle = spinAngle;
        spinAngle = (spinAngle + 2) & 0xFF;
        // sub_3192C derives the chamber frame from the old angle, then stores angle + 2
        // before the jump-button path can launch the player.
        int frame = (TrigLookupTable.sinHex(displayAngle) + 0x120) >> 6;
        chamberFrame = Math.max(FRAME_SPIN_MIN, Math.min(FRAME_SPIN_MAX, frame));

        if (frameCounter >= 0 && (frameCounter & 0x1F) == 0) {
            try {
                services().playSfx(Sonic3kSfx.CANNON_TURN.id);
            } catch (Exception ignored) {
                // Headless tests may not wire audio.
            }
        }
    }

    private void capturePlayer(AbstractPlayableSprite player) {
        capturedPlayer = player;
        short captureY = player.getCentreY();
        spinAngle = 0;
        spinArmed = false;
        chamberFrame = FRAME_CHAMBER_IDLE;

        // ROM: move.w #$81,object_control(a1) / bset #Status_Roll,status(a1)
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setRolling(true);
        player.applyCustomRadii(ROLL_X_RADIUS, ROLL_Y_RADIUS);
        player.setAnimationId(2);

        // ROM: x_pos is snapped to the cannon with a word write, while y_pos
        // is left untouched until the pull-down sub-state reaches the cannon.
        player.setCentreXPreserveSubpixel((short) spawn.x());
        player.setCentreYPreserveSubpixel(captureY);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setAir(true);
        player.setOnObject(false);
        player.setJumping(false);
        player.setPinballMode(false);
        player.setPriorityBucket(PLAYER_CAPTURE_PRIORITY);
        player.setHighPriority(false);
        releasedPlayer = null;
        releasedPlayerPriorityTimer = 0;
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }
        if ((spawn.subtype() & END_SEQUENCE_SUBTYPE) != 0 && services().camera() != null) {
            // The native end cannon occupies slot 4 while Obj_CNZEndBoss is in
            // slot 17, so its $30=1 capture write is visible to loc_6E7B6 later
            // in the same SST pass. Publish the watched-slot consequence here;
            // the boss owner still owns the timer and control-lock transition.
            services().camera().setMaxYTarget((short) 0x0200);
            S3kCnzEventWriteSupport.requestSidekickBoundsPublishAfterCameraEasing(
                    services());
        }
    }

    private void launchPlayer(AbstractPlayableSprite player, int frameCounter) {
        int launchAngle = ((chamberFrame & 0x0F) << 4) + 0x80;
        short xSpeed = (short) (TrigLookupTable.cosHex(launchAngle) << 4);
        short ySpeed = (short) (TrigLookupTable.sinHex(launchAngle) << 4);
        launchPuffAngle = launchAngle;

        // ROM launch writes the player's x_pos to the cannon and y_pos to the
        // cannon centre minus $18 before releasing control.
        player.setRolling(true);
        player.applyCustomRadii(ROLL_X_RADIUS, ROLL_Y_RADIUS);
        player.setCentreXPreserveSubpixel((short) spawn.x());
        player.setCentreYPreserveSubpixel((short) (spawn.y() - 0x18));
        player.setXSpeed(xSpeed);
        player.setYSpeed(ySpeed);
        player.setGSpeed(xSpeed);
        player.setAir(true);
        player.releaseFromObjectControl(frameCounter);
        player.setOnObject(false);
        player.setJumping(false);
        player.setPriorityBucket(PLAYER_CAPTURE_PRIORITY);
        player.setHighPriority(false);

        capturedPlayer = null;
        releasedPlayer = player;
        releasedPlayerPriorityTimer = PLAYER_RESTORE_PRIORITY_FRAMES;
        state = STATE_COOLDOWN;
        stateTimer = LAUNCH_PUFF_TIMER;
        chamberFrame = FRAME_CHAMBER_IDLE;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (state != STATE_IDLE || !contact.standing()
                || !(playerEntity instanceof AbstractPlayableSprite player)
                || player.isCpuControlled()
                || player.isObjectControlled() || player.isJumping()) {
            return;
        }

        capturePlayer(player);
        state = STATE_PULLING_PLAYER;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return state == STATE_IDLE;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.topSolid(true);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding(PlayableEntity player) {
        // Obj_CNZCannon calls the shared SolidObjectTop entry. Its vertical
        // gate accepts only signed overlap d0 in [-$10,-1]: d0 == 0 survives
        // the first `bhi`, then the unsigned `blo` against -$10 rejects it
        // (sonic3k.asm:41982-42015). Waiting for one pixel of overlap keeps
        // the standing-bit capture in sub_319F4 on the native object pass.
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_CANNON);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x02) != 0;
        renderer.drawFrameIndex(chamberFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public String traceDebugDetails() {
        return "state=" + state + " timer=" + stateTimer
                + " angle=" + String.format("%02X", spinAngle & 0xFF)
                + " armed=" + spinArmed;
    }

    int getRenderFrameForTest() {
        return chamberFrame;
    }

    /**
     * Returns whether the cannon owns a captured player in either pull-down or
     * launch-ready state.
     *
     * <p>This is the engine contract for the ROM's per-player cannon state byte
     * becoming {@code 1}: callers may arm the post-boss sequence immediately
     * when pull-down starts, without waiting for the player to reach the chamber.
     */
    public boolean hasCapturedPlayerForEndSequence() {
        return capturedPlayer != null
                && (state == STATE_PULLING_PLAYER || state == STATE_READY_TO_LAUNCH);
    }

    /** Returns the cannon's raw unsigned spin-angle byte used by the ROM launch gate. */
    public int getSpinAngle() {
        return spinAngle & 0xFF;
    }

    public boolean isEndSequenceLaunchReady() {
        return state == STATE_READY_TO_LAUNCH && capturedPlayer != null;
    }

    public void triggerEndSequenceLaunch(int frameCounter) {
        if (isEndSequenceLaunchReady()) {
            launchPlayer(capturedPlayer, frameCounter);
        }
    }

    /**
     * Package-private test seam for launch timing.
     *
     * <p>Drives the cannon into the launch-ready state without simulating
     * the full pull-down phase. When {@code frames <= 0}, advances state
     * directly to {@link #STATE_READY_TO_LAUNCH} so callers can press jump
     * and observe the launch on the very next frame. When {@code frames > 0}
     * sets {@code stateTimer} for any pending cooldown countdown.
     *
     * <p>Reflectively invoked by {@code TestS3kCnzVisualCapture} and
     * {@code TestS3kCnzDirectedTraversalHeadless}.
     */
    void setLaunchDelayFramesForTest(int frames) {
        if (frames <= 0 && state == STATE_PULLING_PLAYER && capturedPlayer != null) {
            // ROM loc_31A4C snaps player to cannon.y when pull-down ends.
            capturedPlayer.setCentreYPreserveSubpixel((short) spawn.y());
            capturedPlayer.setAnimationId(0x1C);
            state = STATE_READY_TO_LAUNCH;
            stateTimer = 0;
            return;
        }
        stateTimer = Math.max(0, frames);
    }

    private static final class CannonLaunchPuff extends AbstractObjectInstance implements SpawnRewindRecreatable {
        private static final int PRIORITY = RenderPriority.MAX;
        private static final int FIRST_FRAME = 1;
        private static final int DELETE_FRAME = 5;
        private static final int ANIM_DELAY = 3;

        private int x;
        private int y;
        private int xSubpixel;
        private int ySubpixel;
        private int xVelocity;
        private int yVelocity;
        private int mappingFrame = FIRST_FRAME;
        private int animFrameTimer = ANIM_DELAY;

        private CannonLaunchPuff(int x, int y, int angle) {
            this(new ObjectSpawn(x, y, 0, angle, 0, false, y));
        }

        private CannonLaunchPuff(ObjectSpawn spawn) {
            super(spawn, "CNZCannonLaunchPuff");
            this.x = spawn.x();
            this.y = spawn.y();
            this.xVelocity = TrigLookupTable.cosHex(spawn.subtype()) << 3;
            this.yVelocity = TrigLookupTable.sinHex(spawn.subtype()) << 3;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            xSubpixel += xVelocity;
            ySubpixel += yVelocity;
            x += xSubpixel >> 8;
            y += ySubpixel >> 8;
            xSubpixel &= 0xFF;
            ySubpixel &= 0xFF;
            updateDynamicSpawn(x, y);

            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }
            animFrameTimer = ANIM_DELAY;
            mappingFrame++;
            if (mappingFrame == DELETE_FRAME) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || mappingFrame >= DELETE_FRAME) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.EXPLOSION);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, x, y, false, false);
            }
        }

        @Override
        public int getPriorityBucket() {
            return PRIORITY;
        }
    }
}
