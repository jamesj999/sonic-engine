package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.TrigLookupTable;

import java.util.List;

/**
 * Object 0xB4 - ICZ Swinging Platform.
 *
 * <p>ROM: {@code Obj_ICZSwingingPlatform} (sonic3k.asm:188875-189425).
 * The visible platform hangs from a chain child 0x80 pixels above it. A lower
 * full-solid child starts the swing when the player rides it with enough
 * horizontal speed in the platform's facing direction; non-zero subtypes detach
 * from the chain and slide after the early-swing release threshold.
 */
public class IczSwingingPlatformObjectInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider, SolidObjectListener, RewindRecreatable {

    private enum Phase { IDLE, SWING_PENDING, SWINGING, FALLING, SLIDING, STOPPED }

    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS;

    // ObjDat_ICZSwingingPlatform: priority $80, width $20, height $10, mapping frame 7.
    private static final int PRIORITY_BUCKET = 1;
    private static final int FRAME_PLATFORM = 7;
    private static final int FRAME_CHAIN_LINK = 8;
    private static final int FRAME_SOLID_CAP = 0x27;
    private static final int PALETTE_PLATFORM = 1;
    private static final int PALETTE_CHAIN = 2;

    // ChildObjDat_8B164: lower solid at (0,+8), adjusted upper solid at (+$1C,-8).
    private static final int PIECE_LOWER_TRIGGER = 0;
    private static final int PIECE_UPPER = 1;
    private static final int LOWER_Y_OFFSET = 8;
    private static final int UPPER_X_OFFSET = 0x1C;
    private static final int UPPER_Y_OFFSET = -8;

    // sub_8B054: d1=$2B, d2=8, d3=8. sub_8B06A: d1=$F, d2=8, d3=8.
    private static final SolidObjectParams LOWER_PARAMS = new SolidObjectParams(0x2B, 8, 8);
    private static final SolidObjectParams UPPER_PARAMS = new SolidObjectParams(0x0F, 8, 8);

    // loc_8AF32 / sub_8AFA2: seven chain child sprites at 1/8 through 7/8 of the span.
    private static final int CHAIN_LINK_COUNT = 7;
    private static final int CHAIN_ANCHOR_Y_OFFSET = -0x80;

    // sub_8B0B0 trigger thresholds and clamps.
    private static final int TRIGGER_SPEED = 0x200;
    private static final int MAX_SWING_VELOCITY = 0x400;
    private static final int SWING_ACCEL = 0x10;

    // loc_8AD40 / loc_8ADCA thresholds, stored as angle accumulator high byte.
    private static final int SWING_LIMIT = 0x6E00;
    private static final int RELEASE_LIMIT = 0x4800;
    private static final int RELEASE_MIN_SPEED = 0x200;

    // loc_8AD9C.
    private static final int RELEASE_X_VELOCITY = 0x400;
    private static final int RELEASE_Y_VELOCITY = -0x600;
    private static final int FLOOR_BOUNCE_MIN_Y_VELOCITY = 0x100;
    private static final int SLIDE_ACCEL = 0x10;
    private static final int FALLING_Y_RADIUS = 0x10;

    private int spawnX;
    private int spawnY;
    private int anchorX;
    private int anchorY;
    private boolean xFlip;
    private boolean releaseOnSwing;

    private final int[] chainX = new int[CHAIN_LINK_COUNT];
    private final int[] chainY = new int[CHAIN_LINK_COUNT];

    private Phase phase = Phase.IDLE;
    private int x;
    private int y;
    private int angleAccumulator;
    private int swingVelocity;
    private int xVel;
    private int yVel;
    private int slideAcceleration;
    private int xSub;
    private int ySub;

    public IczSwingingPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZSwingingPlatform");
        this.spawnX = spawn.x();
        this.spawnY = spawn.y();
        this.anchorX = spawn.x();
        this.anchorY = spawn.y() + CHAIN_ANCHOR_Y_OFFSET;
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.releaseOnSwing = (spawn.subtype() & 0xFF) != 0;
        this.x = spawn.x();
        this.y = spawn.y();
        updateChainPositions();
    }

    @Override
    public IczSwingingPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new IczSwingingPlatformObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        switch (phase) {
            case SWING_PENDING -> phase = Phase.SWINGING;
            case SWINGING -> updateSwing();
            case FALLING -> updateFalling();
            case SLIDING -> updateSliding();
            case IDLE, STOPPED -> {
            }
        }
        updateChainPositions();
    }

    private void updateSwing() {
        if (xFlip) {
            swingVelocity = sign16(swingVelocity + SWING_ACCEL);
            angleAccumulator = sign16(angleAccumulator + swingVelocity);
            if (releaseOnSwing && angleAccumulator <= -RELEASE_LIMIT
                    && Math.abs(swingVelocity) >= RELEASE_MIN_SPEED) {
                releaseFromChain();
                return;
            }
            if (angleAccumulator <= -SWING_LIMIT) {
                return;
            }
            if (angleAccumulator < 0 || swingVelocity < 0) {
                moveCircular();
                return;
            }
            resetSwing();
            return;
        }

        swingVelocity = sign16(swingVelocity - SWING_ACCEL);
        angleAccumulator = sign16(angleAccumulator + swingVelocity);
        if (releaseOnSwing && angleAccumulator >= RELEASE_LIMIT
                && swingVelocity >= RELEASE_MIN_SPEED) {
            releaseFromChain();
            return;
        }
        if (angleAccumulator >= SWING_LIMIT) {
            return;
        }
        if (angleAccumulator > 0 || swingVelocity > 0) {
            moveCircular();
            return;
        }
        resetSwing();
    }

    private void moveCircular() {
        int angle = (angleAccumulator >> 8) & 0xFF;
        int xFixed = (anchorX << 16) + (TrigLookupTable.sinHex(angle) << 15);
        int yFixed = (anchorY << 16) + (TrigLookupTable.cosHex(angle) << 15);
        x = xFixed >> 16;
        y = yFixed >> 16;
        xSub = xFixed & 0xFFFF;
        ySub = yFixed & 0xFFFF;
    }

    private void resetSwing() {
        phase = Phase.IDLE;
        angleAccumulator = 0;
        swingVelocity = 0;
        x = spawnX;
        y = spawnY;
        xSub = 0;
        ySub = 0;
    }

    private void releaseFromChain() {
        phase = Phase.FALLING;
        xVel = xFlip ? -RELEASE_X_VELOCITY : RELEASE_X_VELOCITY;
        yVel = RELEASE_Y_VELOCITY;
    }

    private void updateFalling() {
        SubpixelMotion.State motion = new SubpixelMotion.State(x, y, xSub, ySub, xVel, yVel);
        SubpixelMotion.objectFallXY(motion, SubpixelMotion.S3K_GRAVITY);
        x = motion.x;
        y = motion.y;
        xSub = motion.xSub;
        ySub = motion.ySub;
        xVel = sign16(motion.xVel);
        yVel = sign16(motion.yVel);

        if (yVel < 0) {
            return;
        }

        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, FALLING_Y_RADIUS);
        if (floor.distance() >= 0) {
            return;
        }

        y += floor.distance();
        if (yVel >= FLOOR_BOUNCE_MIN_Y_VELOCITY) {
            yVel = sign16(-(yVel >> 2));
            return;
        }

        phase = Phase.SLIDING;
        slideAcceleration = xVel < 0 ? SLIDE_ACCEL : -SLIDE_ACCEL;
        yVel = 0;
    }

    private void updateSliding() {
        int nextVel = sign16(xVel + slideAcceleration);
        if (((xVel ^ nextVel) & 0x8000) != 0 || nextVel == 0) {
            xVel = 0;
            phase = Phase.STOPPED;
            return;
        }

        xVel = nextVel;
        SubpixelMotion.State motion = new SubpixelMotion.State(x, y, xSub, ySub, xVel, yVel);
        SubpixelMotion.speedToPos(motion);
        x = motion.x;
        y = motion.y;
        xSub = motion.xSub;
        ySub = motion.ySub;
    }

    private void updateChainPositions() {
        int dx = x - anchorX;
        int dy = y - anchorY;
        for (int i = 0; i < CHAIN_LINK_COUNT; i++) {
            int numerator = i + 1;
            chainX[i] = anchorX + ((dx * numerator) / 8);
            chainY[i] = anchorY + ((dy * numerator) / 8);
        }
    }

    @Override
    public int getPieceCount() {
        return 2;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        if (pieceIndex == PIECE_UPPER) {
            return x + (xFlip ? -UPPER_X_OFFSET : UPPER_X_OFFSET);
        }
        return x;
    }

    @Override
    public int getPieceY(int pieceIndex) {
        if (pieceIndex == PIECE_UPPER) {
            return y + UPPER_Y_OFFSET;
        }
        return y + LOWER_Y_OFFSET;
    }

    @Override
    public SolidObjectParams getPieceParams(int pieceIndex) {
        return pieceIndex == PIECE_UPPER ? UPPER_PARAMS : LOWER_PARAMS;
    }

    @Override
    public int getPieceLandingHalfWidth(int pieceIndex) {
        // SolidObjectFull's loc_1E154 re-reads each child SST's width_pixels:
        // word_8B158 publishes $20 for the lower trigger, while word_8B15E
        // publishes $30 for the adjusted upper solid. These are independent of
        // the broad d1 values ($2B/$0F) passed into the initial overlap check.
        if (pieceIndex == PIECE_UPPER) {
            return 0x30;
        }
        return phase == Phase.IDLE ? 0x20 : LOWER_PARAMS.halfWidth();
    }

    @Override
    public boolean usesPieceSpecificLandingHalfWidths() {
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // S3K SolidObjectFull rejects the broad X overlap with `bhi`, so a
        // player exactly +d1 from either child remains inside its side box.
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return LOWER_PARAMS;
    }

    @Override
    public boolean usesPieceScopedStandingBits() {
        return true;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // Each solid child is a real SolidObjectFull SST slot. If its own
        // standing bit is still set when a rider jumps, loc_1DC98 clears that
        // bit and returns; the same slot cannot immediately re-enter the fresh
        // top-contact branch and apply loc_1E154's upward-player lift.
        return true;
    }

    @Override
    public boolean sidekickCpuStalePushGraceKeepsFollowSteeringWhileRiding(PlayableEntity player) {
        // The lower child remains P2's live SolidObjectFull support after its
        // transient side-push bit clears. TailsCPU therefore observes the
        // cleared native Status_Push and still executes FollowLeft/Right.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !isDestroyed() && phase != Phase.STOPPED;
    }

    @Override
    public void onPieceContact(int pieceIndex, PlayableEntity player, SolidContact contact, int frameCounter) {
        onPieceContact(pieceIndex, player, contact, frameCounter, false);
    }

    @Override
    public void onPieceContact(int pieceIndex, PlayableEntity player, SolidContact contact, int frameCounter,
            boolean standingBitWasSetAtEntry) {
        if (pieceIndex != PIECE_LOWER_TRIGGER || player == null || !contact.standing() || phase != Phase.IDLE) {
            return;
        }

        int speed = player.getXSpeed();
        if (speed > -TRIGGER_SPEED && speed < TRIGGER_SPEED) {
            return;
        }

        boolean movingLeft = speed < 0;
        if (movingLeft == !xFlip) {
            return;
        }

        int triggerSpeed = standingBitWasSetAtEntry ? sign16(speed) >> 1 : speed;
        int swing = clamp(sign16(triggerSpeed) >> 1, -MAX_SWING_VELOCITY, MAX_SWING_VELOCITY);
        swingVelocity = swing;
        // ROM child routine sub_8B0B0 sets parent flag/velocity and returns;
        // parent loc_8AD20 switches to the swing routine on its next update,
        // but the first circular movement does not run until the frame after.
        phase = Phase.SWING_PENDING;

        int adjustedPlayerSpeed = sign16(swing << 1);
        player.setXSpeed((short) adjustedPlayerSpeed);
        player.setGSpeed((short) adjustedPlayerSpeed);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Multi-piece callbacks carry the ROM child-solid distinction.
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }

        renderer.drawFrameIndex(FRAME_CHAIN_LINK, anchorX, anchorY, xFlip, false, PALETTE_CHAIN);
        for (int i = 0; i < CHAIN_LINK_COUNT; i++) {
            renderer.drawFrameIndex(FRAME_CHAIN_LINK, chainX[i], chainY[i], xFlip, false, PALETTE_CHAIN);
        }
        renderer.drawFrameIndex(FRAME_PLATFORM, x, y, xFlip, false, PALETTE_PLATFORM);
        renderer.drawFrameIndex(FRAME_SOLID_CAP, getPieceX(PIECE_LOWER_TRIGGER), getPieceY(PIECE_LOWER_TRIGGER),
                xFlip, false, PALETTE_PLATFORM);
        renderer.drawFrameIndex(FRAME_SOLID_CAP, getPieceX(PIECE_UPPER), getPieceY(PIECE_UPPER),
                xFlip, false, PALETTE_PLATFORM);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawLine(anchorX, anchorY, x, y, 0.6f, 0.6f, 0.6f);
        ctx.drawCross(anchorX, anchorY, 4, 1.0f, 1.0f, 0.0f);
        ctx.drawRect(getPieceX(PIECE_LOWER_TRIGGER), getPieceY(PIECE_LOWER_TRIGGER), LOWER_PARAMS.halfWidth(),
                LOWER_PARAMS.groundHalfHeight(), 0.2f, 1.0f, 0.2f);
        ctx.drawRect(getPieceX(PIECE_UPPER), getPieceY(PIECE_UPPER), UPPER_PARAMS.halfWidth(),
                UPPER_PARAMS.groundHalfHeight(), 0.2f, 0.7f, 1.0f);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    public int getXSubpixelForTesting() {
        return xSub;
    }

    public int getYSubpixelForTesting() {
        return ySub;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    private static int sign16(int value) {
        return (short) value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
