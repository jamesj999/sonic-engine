package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * S3K S3KL object $10 - Launch Base tube elevator.
 *
 * <p>ROM reference: {@code Obj_LBZTubeElevator} and
 * {@code LBZTubeElevator_Action} (sonic3k.asm:57796-58298). The elevator
 * reuses {@code AutoTunnel_GetPath}; path data comes from
 * {@link AutomaticTunnelObjectInstance#PATHS}.
 */
public final class LbzTubeElevatorInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_LBZTubeElevator} is installed from the S3K object pointer table at
     * {@code $00029C9E} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:57801).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }

    private static final int WIDTH_PIXELS = 0x18;
    private static final int HEIGHT_PIXELS = 0x30;
    private static final int SOLID_SIDE_PADDING = 0x0B;
    private static final int PATH_SPEED = 0x1000;
    private static final int MAX_SPIN_SPEED = 0x180;
    private static final int MIN_SPIN_SPEED = 8;
    private static final int PLAYER_X_BIAS = 3;
    private static final int PLAYER_X_RANGE = 0x10;
    private static final int PLAYER_Y_BIAS = 0x20;
    private static final int PLAYER_Y_RANGE = 0x40;
    private static final int PLAYER_Y_OFFSET = 0x18;
    private static final int END_SPIN_FRAME_TIMER = 0x0F;
    private static final int PARENT_PRIORITY_BUCKET = 1; // ROM priority $80
    private static final int OVERLAY_PRIORITY_BUCKET = 5; // ROM child priority $280
    private static final int CLOSED_SUPPRESSED_X = 0x7FF0;

    private static final int STATE_WAIT_PLAYER = 0;
    private static final int STATE_START_SPIN = 2;
    private static final int STATE_MOVE_PATH = 4;
    private static final int STATE_SLOW_SPIN = 6;
    private static final int STATE_WAIT_EXIT = 8;
    private static final int STATE_END_SPIN = 10;
    private static final int STATE_CLOSED = 12;

    private static final int[] PLAYER_FRAMES = {
            0x55, 0x59, 0x5A, 0x5B, 0x5A, 0x59,
            0x55, 0x56, 0x57, 0x58, 0x57, 0x56
    };
    private static final boolean[] PLAYER_H_FLIP = {
            false, true, true, false, false, false,
            true, true, true, false, false, false
    };
    private static final int[] CHILD_X_OFFSETS = {0, -8, -8, 0, 8, 8};

    private static final SolidObjectParams FULL_SOLID =
            SolidObjectParams.of(WIDTH_PIXELS + SOLID_SIDE_PADDING, HEIGHT_PIXELS, HEIGHT_PIXELS + 1);
    private static final SolidObjectParams OPEN_SOLID =
            // SolidObjectFull_Offset uses d3 as a downward collision anchor
            // offset and d2 as the radius on both sides. This equivalent
            // geometry also preserves the established-rider placement
            // y_pos(object)+$20-$08-y_radius.
            SolidObjectParams.of(WIDTH_PIXELS + SOLID_SIDE_PADDING, 8, 8, 0, 0x20);

    private final PlayerTubeState p1 = new PlayerTubeState();
    private final PlayerTubeState p2 = new PlayerTubeState();
    private boolean closedOnly;

    private int x;
    private int y;
    private long fixedX;
    private long fixedY;
    private int xVel;
    private int yVel;
    private int baseY;
    private int state;
    private int bobAngle;
    private int pathTimer;
    private int pathRemaining;
    private int pathIndex;
    private int[] path;
    private boolean reversePath;
    private int spinSpeed;
    private int mappingAccumulator;
    private int angleAccumulator;
    private int mappingFrame;
    private int angle;
    private int endSpinTimer;
    @RewindTransient(reason = "structural overlay child relinked during generic graph recreate")
    private transient OverlayChild overlayChild;

    public LbzTubeElevatorInstance(ObjectSpawn spawn) {
        super(spawn, "LBZTubeElevator");
        this.x = spawn.x();
        this.y = spawn.y();
        this.fixedX = (long) x << 16;
        this.fixedY = (long) y << 16;
        this.baseY = spawn.y();
        this.closedOnly = (spawn.subtype() & 0x40) != 0;
        this.state = closedOnly ? STATE_CLOSED : STATE_WAIT_PLAYER;
        this.mappingFrame = closedOnly ? 0 : 2;
        this.angle = closedOnly ? 0 : 2;
        this.mappingAccumulator = mappingFrame << 8;
        this.angleAccumulator = angle << 8;
        updateDynamicSpawn(x, y);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureOverlayChild();
        if (!closedOnly) {
            updateAction(playerEntity);
            processPlayers(playerEntity);
        } else {
            suppressClosedDestinationIfEnteringFromActiveElevator(playerEntity);
            updateClosedBob();
        }
        updateDynamicSpawn(x, y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_TUBE_ELEVATOR);
        if (renderer == null) {
            return;
        }
        int shellFrame = Math.floorMod(mappingFrame, 6);
        renderer.drawFrameIndex(shellFrame, x, y, false, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return state == STATE_WAIT_PLAYER || state == STATE_WAIT_EXIT ? OPEN_SOLID : FULL_SOLID;
    }

    @Override
    public boolean isTopSolidOnly() {
        // ROM uses SolidObjectFull_Offset while open and SolidObjectFull while
        // spinning/closed. The offset changes d2/d3, not the routine family.
        return false;
    }

    @Override
    public boolean preservesObjectManagedRideWhileNotSolidFor(PlayableEntity player) {
        return player instanceof AbstractPlayableSprite sprite
                && sprite.isObjectControlled()
                && sprite.getLatchedSolidObjectInstance() == this;
    }

    @Override
    public Integer getObjectManagedRideCentreY(PlayableEntity player, int objectY, SolidObjectParams params) {
        return objectY + PLAYER_Y_OFFSET - player.getYRadius();
    }

    @Override
    public int getPriorityBucket() {
        return PARENT_PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS + SOLID_SIDE_PADDING;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HEIGHT_PIXELS;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (!contact.standing() || !(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        PlayerTubeState tubeState = player == nativeP2OrNull() ? p2 : p1;
        if (tubeState.phase == 0 && canCapture(player)) {
            // ROM slot order is Action (including SolidObjectFull_Offset), then
            // CheckPlayer. Consume a landing published by the shared solid pass
            // in this same object slot so the capture snap is not one frame late.
            capturePlayer(player, tubeState);
        }
    }

    private void ensureOverlayChild() {
        if (closedOnly || overlayChild != null) {
            return;
        }
        overlayChild = spawnChild(() -> new OverlayChild(this));
    }

    void rewindAttachOverlayChild(OverlayChild child) {
        overlayChild = child;
    }

    private void updateAction(PlayableEntity playerEntity) {
        switch (state) {
            case STATE_WAIT_PLAYER -> updateWaitPlayer();
            case STATE_START_SPIN -> updateStartSpin();
            case STATE_MOVE_PATH -> updateMovePath();
            case STATE_SLOW_SPIN -> updateSlowSpin();
            case STATE_WAIT_EXIT -> updateWaitExit(playerEntity);
            case STATE_END_SPIN -> updateEndSpin();
            case STATE_CLOSED -> updateClosedBob();
            default -> state = STATE_CLOSED;
        }
    }

    private void updateWaitPlayer() {
        if (p1.phase == 2 || p2.phase == 2) {
            state = STATE_START_SPIN;
            playRollingSfx();
            updateStartSpin();
            return;
        }
        applyBobOpen();
    }

    private void updateStartSpin() {
        spinShell();
        if (spinSpeed < MAX_SPIN_SPEED) {
            spinSpeed += 2;
        } else {
            state = STATE_MOVE_PATH;
            spinSpeed = MAX_SPIN_SPEED;
            bobAngle = 0;
            setupPath();
            requestFastVerticalScroll();
        }
        applyStartSpinBob();
    }

    private void updateMovePath() {
        spinShell();
        if (path == null) {
            state = STATE_SLOW_SPIN;
            return;
        }
        pathTimer--;
        if (pathTimer > 0) {
            fixedX += (long) signWord(xVel) << 8;
            fixedY += (long) signWord(yVel) << 8;
            x = (int) (fixedX >> 16);
            y = (int) (fixedY >> 16);
            requestFastVerticalScroll();
            return;
        }

        writeXWordPreserveSubpixel(path[pathIndex]);
        writeYWordPreserveSubpixel(path[pathIndex + 1]);
        pathIndex += reversePath ? -2 : 2;
        pathRemaining -= 4;
        if (pathRemaining <= 0 || pathIndex < 0 || pathIndex + 1 >= path.length) {
            state = STATE_SLOW_SPIN;
            xVel = 0;
            yVel = 0;
            baseY = y;
            bobAngle = 0;
            return;
        }
        calculateVelocity(path[pathIndex], path[pathIndex + 1]);
        requestFastVerticalScroll();
    }

    private void updateSlowSpin() {
        spinShell();
        if (spinSpeed == MIN_SPIN_SPEED && mappingFrame == 2) {
            state = STATE_WAIT_EXIT;
            updateWaitExit(null);
            return;
        }
        if (spinSpeed != MIN_SPIN_SPEED) {
            spinSpeed -= 4;
            if (spinSpeed < MIN_SPIN_SPEED) {
                spinSpeed = MIN_SPIN_SPEED;
            }
        }
        updateWaitExit(null);
    }

    private void updateWaitExit(PlayableEntity playerEntity) {
        applyBobOpen();
        if (p1.phase != 2 && p2.phase != 2 && !hasReleasedPlayerStillStanding(playerEntity)) {
            state = STATE_END_SPIN;
            endSpinTimer = 0;
        }
    }

    private void updateEndSpin() {
        endSpinTimer--;
        if (endSpinTimer >= 0) {
            updateClosedBob();
            return;
        }
        endSpinTimer = END_SPIN_FRAME_TIMER;
        mappingFrame++;
        if (mappingFrame >= 6) {
            mappingFrame = 0;
            state = STATE_CLOSED;
        }
        mappingAccumulator = mappingFrame << 8;
        angle = (angle + 1) % 6;
        angleAccumulator = angle << 8;
        updateClosedBob();
    }

    private void updateClosedBob() {
        applyBobFull();
    }

    private void suppressClosedDestinationIfEnteringFromActiveElevator(PlayableEntity player) {
        if (!isPlayerEnteringFromActiveElevator(player)) {
            return;
        }
        x = CLOSED_SUPPRESSED_X;
        fixedX = (long) x << 16;
    }

    private boolean isPlayerEnteringFromActiveElevator(PlayableEntity player) {
        if (player == null || !player.isOnObject() || !player.isObjectControlled()) {
            return false;
        }
        try {
            ObjectManager objectManager = services().objectManager();
            if (objectManager == null) {
                return false;
            }
            ObjectInstance ridingObject = objectManager.getRidingObject(player);
            if (ridingObject instanceof LbzTubeElevatorInstance elevator && !elevator.closedOnly) {
                return true;
            }
        } catch (IllegalStateException ignored) {
            // Direct object tests can run without an object manager.
        }
        return player instanceof AbstractPlayableSprite sprite
                && sprite.getLatchedSolidObjectInstance() instanceof LbzTubeElevatorInstance elevator
                && !elevator.closedOnly;
    }

    private void processPlayers(PlayableEntity playerEntity) {
        AbstractPlayableSprite player1 = playerEntity instanceof AbstractPlayableSprite sprite ? sprite : null;
        AbstractPlayableSprite player2 = nativeP2OrNull();
        processPlayer(player1, p1, false);
        processPlayer(player2, p2, p1.phase != 0);
    }

    private AbstractPlayableSprite nativeP2OrNull() {
        try {
            PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
            return nativeP2 instanceof AbstractPlayableSprite sprite ? sprite : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void processPlayer(AbstractPlayableSprite player, PlayerTubeState tubeState, boolean otherPlayerInside) {
        if (player == null) {
            return;
        }
        if (tubeState.phase == 0) {
            if (canCapture(player) || (otherPlayerInside && isStandingOnThisElevator(player))) {
                capturePlayer(player, tubeState);
            }
            return;
        }
        if (tubeState.phase == 2) {
            positionCapturedPlayer(player);
            refreshCapturedPlayerLatch(player);
            if (state == STATE_WAIT_EXIT) {
                player.setDirection(Direction.LEFT);
                ObjectControlState.none().applyTo(player);
                player.setObjectMappingFrameControl(false);
                player.setLatchedSolidObjectId(0);
                tubeState.phase = 4;
                // loc_2A1EC follows the WaitExit release branch: native still
                // publishes this slot's final tube mapping/DPLC after clearing
                // object_control, but ordinary animation resumes next frame.
                applyCapturedPlayerFrame(player, false);
                return;
            }
            applyCapturedPlayerFrame(player, true);
        }
    }

    private boolean canCapture(AbstractPlayableSprite player) {
        if (player.isDebugMode() || player.isObjectControlled() || player.getAir()) {
            return false;
        }
        int dx = player.getCentreX() - x + PLAYER_X_BIAS;
        if (dx < 0 || dx >= PLAYER_X_RANGE) {
            return false;
        }
        int dy = player.getCentreY() - y + PLAYER_Y_BIAS;
        return dy >= 0 && dy < PLAYER_Y_RANGE;
    }

    private boolean isStandingOnThisElevator(AbstractPlayableSprite player) {
        if (!player.isOnObject()) {
            return false;
        }
        try {
            ObjectManager objectManager = services().objectManager();
            if (objectManager != null) {
                return objectManager.getRidingObject(player) == this;
            }
        } catch (IllegalStateException ignored) {
            // Direct object tests can use the native solid-object latch.
        }
        return player.getLatchedSolidObjectInstance() == this;
    }

    private void capturePlayer(AbstractPlayableSprite player, PlayerTubeState tubeState) {
        tubeState.phase = 2;
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setAnimationId(0);
        // Capture runs after this frame's player slot. Native object_control
        // bit 1 suppresses the next Animate dispatch immediately, although the
        // elevator publishes its first mapping only on its next object pass.
        player.setObjectMappingFrameControl(true);
        player.setJumping(false);
        player.setGSpeed((short) 0);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        // ROM clears the elevator's OWN p1 bit and only p1, even when the
        // captured character is Player_2 (docs/skdisasm/sonic3k.asm:58249).
        services().objectManager().solidContacts().releaseObjectPushLatch(
                services().playerQuery().mainPlayerOrNull(), this);
        player.setPushing(false);
        player.setAir(false);
        positionCapturedPlayer(player);
        refreshCapturedPlayerLatch(player);
    }

    private void positionCapturedPlayer(AbstractPlayableSprite player) {
        NativePositionOps.writeXPosPreserveSubpixel(player, x);
        NativePositionOps.writeYPosPreserveSubpixel(player, y + PLAYER_Y_OFFSET - player.getYRadius());
    }

    private void refreshCapturedPlayerLatch(AbstractPlayableSprite player) {
        // ROM's closed destination checks Status_OnObj + interact while object_control is nonzero.
        player.setOnObject(true);
        player.setLatchedSolidObject(spawn.objectId(), this);
    }

    private void applyCapturedPlayerFrame(AbstractPlayableSprite player, boolean retainMappingControl) {
        int index = Math.floorMod(angle, PLAYER_FRAMES.length);
        player.setMappingFrame(PLAYER_FRAMES[index]);
        player.setObjectMappingFrameControl(retainMappingControl);
        player.setRenderFlips(PLAYER_H_FLIP[index], player.getRenderVFlip());
    }

    private boolean hasReleasedPlayerStillStanding(PlayableEntity playerEntity) {
        return isReleasedPlayerStanding(playerEntity, p1) || isReleasedPlayerStanding(nativeP2OrNull(), p2);
    }

    private boolean isReleasedPlayerStanding(PlayableEntity player, PlayerTubeState tubeState) {
        if (tubeState.phase != 4 || player == null) {
            return false;
        }
        try {
            ObjectManager objectManager = services().objectManager();
            if (objectManager != null) {
                return objectManager.getRidingObject(player) == this;
            }
        } catch (IllegalStateException ignored) {
            // Direct object tests can run without an object manager.
        }
        return player.isOnObject();
    }

    private void applyBobOpen() {
        applyBob();
    }

    private void applyBobFull() {
        applyBob();
    }

    private void applyStartSpinBob() {
        // ROM loc_29EE2 samples 1(a4) before incrementing it. The waiting,
        // exit, and closed routines increment first and therefore use applyBob().
        int sampleAngle = bobAngle;
        bobAngle = (bobAngle + 2) & 0xFF;
        int offset = TrigLookupTable.sinHex(sampleAngle) >> 6;
        writeYWordPreserveSubpixel(baseY - offset);
    }

    private void applyBob() {
        bobAngle = (bobAngle + 2) & 0xFF;
        int adjusted = bobAngle;
        if (adjusted >= 0xB0 && adjusted < 0xD0) {
            adjusted = (adjusted + 0x20) & 0xFF;
        }
        // ROM writes the adjusted value back to 1(a4), skipping the entire
        // $B0-$CF phase band rather than reapplying the correction each frame.
        bobAngle = adjusted;
        int offset = TrigLookupTable.sinHex(adjusted);
        if (offset == 0x100) {
            offset--;
        }
        offset >>= 6;
        writeYWordPreserveSubpixel(baseY - offset);
    }

    private void spinShell() {
        mappingAccumulator = positiveMod(mappingAccumulator - spinSpeed, 0x600);
        angleAccumulator = positiveMod(angleAccumulator - spinSpeed, 0xC00);
        mappingFrame = (mappingAccumulator >> 8) & 0xFF;
        angle = (angleAccumulator >> 8) & 0xFF;
    }

    private void setupPath() {
        int pathId = spawn.subtype() & 0x1F;
        if (pathId >= AutomaticTunnelObjectInstance.PATHS.length) {
            state = STATE_SLOW_SPIN;
            return;
        }
        path = AutomaticTunnelObjectInstance.PATHS[pathId];
        reversePath = (spawn.subtype() & 0x80) != 0;
        int waypointCount = path.length / 2;
        pathRemaining = (waypointCount - 1) * 4;
        if (reversePath) {
            pathIndex = path.length - 2;
            writeXWordPreserveSubpixel(path[pathIndex]);
            writeYWordPreserveSubpixel(path[pathIndex + 1]);
            pathIndex -= 2;
        } else {
            writeXWordPreserveSubpixel(path[0]);
            writeYWordPreserveSubpixel(path[1]);
            pathIndex = 2;
        }
        if (pathIndex >= 0 && pathIndex + 1 < path.length) {
            calculateVelocity(path[pathIndex], path[pathIndex + 1]);
        }
    }

    private void writeXWordPreserveSubpixel(int value) {
        x = value & 0xFFFF;
        fixedX = ((long) x << 16) | (fixedX & 0xFFFFL);
    }

    private void writeYWordPreserveSubpixel(int value) {
        y = value & 0xFFFF;
        fixedY = ((long) y << 16) | (fixedY & 0xFFFFL);
    }

    private void calculateVelocity(int targetX, int targetY) {
        int dx = targetX - x;
        int dy = targetY - y;
        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);

        int duration;
        if (absDy >= absDx) {
            yVel = dy >= 0 ? PATH_SPEED : -PATH_SPEED;
            duration = dy == 0 ? 0 : (int) (((long) dy << 16) / yVel);
            xVel = duration == 0 ? 0 : (int) (((long) dx << 16) / duration);
        } else {
            xVel = dx >= 0 ? PATH_SPEED : -PATH_SPEED;
            duration = dx == 0 ? 0 : (int) (((long) dx << 16) / xVel);
            yVel = duration == 0 ? 0 : (int) (((long) dy << 16) / duration);
        }
        pathTimer = (Math.abs(duration) >> 8) & 0xFF;
    }

    private void requestFastVerticalScroll() {
        try {
            Camera camera = services().camera();
            if (camera != null) {
                camera.requestFastVerticalScroll();
            }
        } catch (IllegalStateException ignored) {
            // Direct object tests can run without a camera service.
        }
    }

    private void playRollingSfx() {
        try {
            services().playSfx(GameSound.ROLLING);
        } catch (Exception ignored) {
            // Audio failure must not affect object logic.
        }
    }

    private static int positiveMod(int value, int modulus) {
        int result = value % modulus;
        return result < 0 ? result + modulus : result;
    }

    private static int signWord(int value) {
        value &= 0xFFFF;
        return value >= 0x8000 ? value - 0x10000 : value;
    }

    private int overlayX() {
        int frame = Math.floorMod(mappingFrame, CHILD_X_OFFSETS.length);
        return x + CHILD_X_OFFSETS[frame];
    }

    private static final class PlayerTubeState {
        int phase;
    }

    private static final class OverlayChild extends AbstractObjectInstance implements RewindRecreatable {
        private LbzTubeElevatorInstance parent;

        private OverlayChild(ObjectSpawn spawn) {
            super(spawn, "LBZTubeElevatorOverlay");
            this.parent = null;
        }

        private OverlayChild(LbzTubeElevatorInstance parent) {
            super(parent.getSpawn(), "LBZTubeElevatorOverlay");
            this.parent = parent;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            LbzTubeElevatorInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                    ctx, LbzTubeElevatorInstance.class);
            if (liveParent == null) {
                return null;
            }
            OverlayChild restored = new OverlayChild(liveParent);
            liveParent.rewindAttachOverlayChild(restored);
            return restored;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed() || parent.closedOnly) {
                setDestroyed(true);
            }
        }

        @Override
        public int getX() {
            return parent.overlayX();
        }

        @Override
        public int getY() {
            return parent.y;
        }

        @Override
        public int getPriorityBucket() {
            return OVERLAY_PRIORITY_BUCKET;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_TUBE_ELEVATOR);
            if (renderer != null) {
                renderer.drawFrameIndex(6, getX(), getY(), false, false);
            }
        }
    }
}
