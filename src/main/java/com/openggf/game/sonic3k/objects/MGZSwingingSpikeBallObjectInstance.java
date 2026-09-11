package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;

import java.util.List;

/**
 * Object 0x58 - MGZ Swinging Spike Ball.
 *
 * <p>ROM: Obj_MGZSwingingSpikeBall (sonic3k.asm:70563-70730).
 * The parent owns the harmful spike ball while a helper sprite renders the anchor and
 * chain links. This port keeps the parent-only collision model and renders the helper
 * geometry inline from the same object instance.
 */
public class MGZSwingingSpikeBallObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_SWINGING_SPIKE_BALL;

    private static final int COLLISION_FLAGS = 0x8F;
    private static final int PRIORITY_BUCKET = 5; // Closest to helper priority $280.

    private static final int FRAME_LINK = 0;
    private static final int FRAME_HORIZONTAL_ANCHOR = 1;
    private static final int FRAME_VERTICAL_ANCHOR = 2;
    private static final int FRAME_BALL = 3;

    private static final int LINK_COUNT = 4;
    private static final int ROM_CHILD_SLOT_COUNT = 1;
    private static final int LINK_SPACING = 0x10;
    private static final int HORIZONTAL_ANGLE_STEP = 2;
    private static final int VERTICAL_SPEED_INIT = 0x0100;
    private static final int VERTICAL_SPEED_DELTA = 0x0010;

    private int baseX;
    private int baseY;
    private boolean verticalMode;
    private boolean hFlip;
    private boolean vFlip;

    private final int[] linkX = new int[LINK_COUNT];
    private final int[] linkY = new int[LINK_COUNT];

    private int ballX;
    private int ballY;
    private int angleByte;
    private int verticalAngleWord;
    private int verticalSpeedWord;
    private int anchorFrame;
    private boolean ballInFront;
    private boolean linksInFrontOfAnchor;
    private boolean childSlotReserved;

    public MGZSwingingSpikeBallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZSwingingSpikeBall");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.verticalMode = (spawn.subtype() & 0xFF) != 0;
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.vFlip = (spawn.renderFlags() & 0x02) != 0;

        if (verticalMode) {
            verticalAngleWord = 0x8000;
            verticalSpeedWord = VERTICAL_SPEED_INIT;
        }
        updateGeometry();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        reserveRomChildSlot();
        int oldAngle = currentAngleByte();
        updateGeometry();

        if (verticalMode) {
            if (isNegativeAngle(oldAngle)) {
                verticalSpeedWord = (verticalSpeedWord + VERTICAL_SPEED_DELTA) & 0xFFFF;
            } else {
                verticalSpeedWord = (verticalSpeedWord - VERTICAL_SPEED_DELTA) & 0xFFFF;
            }
            verticalAngleWord = (verticalAngleWord + verticalSpeedWord) & 0xFFFF;
        } else {
            int delta = vFlip ? -HORIZONTAL_ANGLE_STEP : HORIZONTAL_ANGLE_STEP;
            angleByte = (angleByte + delta) & 0xFF;
        }

        int newAngle = currentAngleByte();
        if (crossedSoundBoundary(oldAngle, newAngle) && isOnScreen()) {
            try {
                services().playSfx(Sonic3kSfx.SPIKE_BALLS.id);
            } catch (Exception ignored) {
                // Audio should not affect gameplay determinism.
            }
        }
    }

    @Override
    public int getReservedChildSlotCount() {
        return ROM_CHILD_SLOT_COUNT;
    }

    private void reserveRomChildSlot() {
        if (childSlotReserved || getSlotIndex() < 0) {
            return;
        }
        childSlotReserved = true;
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }
        // The ROM allocates the loc_34244 visual helper immediately after the
        // parent. Rendering it inline is equivalent visually, but its SST must
        // remain occupied so later AllocateObject calls see native slot pressure.
        svc.objectManager().allocateChildSlotsAfter(spawn, ROM_CHILD_SLOT_COUNT, getSlotIndex());
    }

    private void updateGeometry() {
        int angle = currentAngleByte();
        // ROM: GetSineCosine -> swap d1 -> asr.l #4
        // This yields a signed 16.16 step of cos(angle) * 16, accumulated per link.
        // Using a per-link fixed-point accumulator preserves subpixel carry between
        // links, which makes the swing smoother and reaches farther at shallow angles.
        int stepFixed = TrigLookupTable.cosHex(angle) << 12;
        int distanceFixed = stepFixed;

        for (int i = 0; i < LINK_COUNT; i++) {
            int distance = distanceFixed >> 16;
            if (verticalMode) {
                linkX[i] = baseX;
                linkY[i] = baseY + distance;
            } else {
                linkX[i] = baseX + distance;
                linkY[i] = baseY;
            }
            distanceFixed += stepFixed;
        }

        // ROM adds one more step before writing the parent ball position, so the
        // spike ball sits one segment farther out than the last helper link.
        int ballDistance = (distanceFixed + stepFixed) >> 16;
        if (verticalMode) {
            ballX = baseX;
            ballY = baseY + ballDistance;
        } else {
            ballX = baseX + ballDistance;
            ballY = baseY;
        }

        ballInFront = isNegativeAngle(angle);
        linksInFrontOfAnchor = verticalMode || isMovingLeft(angle, ballDistance);
        anchorFrame = verticalMode ? FRAME_VERTICAL_ANCHOR : FRAME_HORIZONTAL_ANCHOR;
    }

    private boolean isMovingLeft(int angle, int currentBallDistance) {
        int delta = vFlip ? -HORIZONTAL_ANGLE_STEP : HORIZONTAL_ANGLE_STEP;
        for (int step = 1; step <= 4; step++) {
            int nextAngle = (angle + (delta * step)) & 0xFF;
            int nextBallDistance = calculateBallDistance(nextAngle);
            if (nextBallDistance != currentBallDistance) {
                return nextBallDistance < currentBallDistance;
            }
        }
        return false;
    }

    private int calculateBallDistance(int angle) {
        int stepFixed = TrigLookupTable.cosHex(angle) << 12;
        return (stepFixed * (LINK_COUNT + 2)) >> 16;
    }

    private int currentAngleByte() {
        return verticalMode ? ((verticalAngleWord >>> 8) & 0xFF) : (angleByte & 0xFF);
    }

    private static boolean isNegativeAngle(int angle) {
        return (angle & 0x80) != 0;
    }

    private static boolean crossedSoundBoundary(int oldAngle, int newAngle) {
        return (((oldAngle ^ newAngle) & 0x40) != 0) && ((newAngle & 0x40) != 0);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }

        if (linksInFrontOfAnchor) {
            drawAnchorAndLinks(renderer);
            renderer.drawFrameIndex(FRAME_BALL, ballX, ballY, hFlip, vFlip);
        } else {
            renderer.drawFrameIndex(FRAME_BALL, ballX, ballY, hFlip, vFlip);
            drawAnchorAndLinks(renderer);
        }
    }

    private void drawAnchorAndLinks(PatternSpriteRenderer renderer) {
        if (linksInFrontOfAnchor) {
            renderer.drawFrameIndex(anchorFrame, baseX, baseY, hFlip, vFlip);
            for (int i = 0; i < LINK_COUNT; i++) {
                renderer.drawFrameIndex(FRAME_LINK, linkX[i], linkY[i], hFlip, vFlip);
            }
            return;
        }
        for (int i = 0; i < LINK_COUNT; i++) {
            renderer.drawFrameIndex(FRAME_LINK, linkX[i], linkY[i], hFlip, vFlip);
        }
        renderer.drawFrameIndex(anchorFrame, baseX, baseY, hFlip, vFlip);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawCross(baseX, baseY, 4, 1.0f, 1.0f, 0.0f);
        int prevX = baseX;
        int prevY = baseY;
        for (int i = 0; i < LINK_COUNT; i++) {
            ctx.drawLine(prevX, prevY, linkX[i], linkY[i], 0.6f, 0.6f, 0.6f);
            prevX = linkX[i];
            prevY = linkY[i];
        }
        ctx.drawLine(prevX, prevY, ballX, ballY, 1.0f, 0.3f, 0.3f);
        ctx.drawRect(ballX, ballY, 0x10, 0x10, 1.0f, 0.3f, 0.3f);
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return getTouchResponseProfile(true);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                multiRegionSource,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                multiRegionSource
                        ? TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY
                        : TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        return new TouchRegion[]{new TouchRegion(ballX, ballY, COLLISION_FLAGS)};
    }

    @Override
    public int getX() {
        return ballX;
    }

    @Override
    public int getY() {
        return ballY;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ROM loc_341F0 reloads the fixed anchor from $30(a0) into d0 before
        // tail-calling loc_1B666.  Using the moving ball X here can delete a
        // right-facing ball as soon as its anchor enters the placement window.
        return baseX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }
}
