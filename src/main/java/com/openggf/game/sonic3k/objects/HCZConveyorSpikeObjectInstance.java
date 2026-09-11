package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x3F - HCZ Conveyor Spike (Hydrocity Zone).
 *
 * <p>A spike-ball hazard attached to the path data owned by {@link HCZConveyorBeltObjectInstance}.
 * The subtype low nibble selects one of the shared HCZ conveyor bounds. The object rides the top
 * run, wraps around the right-hand pulley, traverses the bottom run, then wraps around the left
 * pulley back to the top. The ROM implements the curve sections with a packed signed-word table;
 * this class mirrors that table walk directly.</p>
 *
 * <p>ROM references: {@code Obj_HCZConveryorSpike} (sonic3k.asm:66631-66714),
 * {@code word_31124} shared conveyor bounds, {@code word_31664} curve table,
 * {@code Map_HCZConveyorSpike}, {@code ArtTile_HCZSpikeBall}.</p>
 */
public class HCZConveyorSpikeObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable, TouchResponseProvider {

    private static final String ART_KEY = Sonic3kObjectArtKeys.HCZ_CONVEYOR_SPIKE;

    private static final int PRIORITY = 5; // ROM: priority = $280
    private static final int WIDTH_PIXELS = 0x0C;
    private static final int HEIGHT_PIXELS = 0x0C;
    private static final int COLLISION_FLAGS = 0x8B;
    private static final int SPEED = 2;
    private static final int TRACK_Y_OFFSET = 0x18;
    private static final int CAMERA_MARGIN = 0x280;
    private static final int RIGHT_ARC_START_ANGLE = 0x40;
    private static final int ARC_MASK = 0x7E;
    private static final int ARC_Y_TABLE_OFFSET = 0x20;

    // Shared HCZ conveyor bounds table (word_31124, also used by Obj_HCZConveyorBelt).
    private static final int[][] BELT_BOUNDS = {
            {0x0B28, 0x0CD8},
            {0x0BA8, 0x0CD8},
            {0x0BA8, 0x0CD8},
            {0x0EA8, 0x1058},
            {0x11A8, 0x12D8},
            {0x1928, 0x19D8},
            {0x21A8, 0x2358},
            {0x21A8, 0x2358},
            {0x22A8, 0x2458},
            {0x23A8, 0x2558},
            {0x2528, 0x26D8},
            {0x26A8, 0x27D8},
            {0x26A8, 0x2958},
            {0x2728, 0x28D8},
            {0x3328, 0x3458},
            {0x3328, 0x33D8},
    };

    // Curve table word_31664. The ROM indexes this with byte offsets (0,2,4...),
    // and reads Y from the same table at +$20 bytes.
    private static final int[] CURVE_TABLE = {
            0x00, 0x02, 0x04, 0x06, 0x09, 0x0B, 0x0D, 0x0F,
            0x10, 0x12, 0x13, 0x15, 0x16, 0x16, 0x17, 0x17,
            0x18, 0x17, 0x17, 0x16, 0x16, 0x15, 0x13, 0x12,
            0x10, 0x0F, 0x0D, 0x0B, 0x09, 0x06, 0x04, 0x02,
            0x00, -0x03, -0x05, -0x07, -0x0A, -0x0C, -0x0E, -0x10,
            -0x11, -0x13, -0x14, -0x16, -0x17, -0x17, -0x18, -0x18,
            -0x18, -0x18, -0x18, -0x17, -0x17, -0x16, -0x14, -0x13,
            -0x11, -0x10, -0x0E, -0x0C, -0x0A, -0x07, -0x05, -0x03,
            0x00, 0x02, 0x04, 0x06, 0x09, 0x0B, 0x0D, 0x0F,
            0x10, 0x12, 0x13, 0x15, 0x16, 0x16, 0x17, 0x17,
    };

    private enum State {
        MOVE_RIGHT,
        CURVE_RIGHT,
        MOVE_LEFT,
        CURVE_LEFT
    }

    private int centerY;
    private int leftBound;
    private int rightBound;

    private State state;
    private int angle;
    private int centerX;
    private int currentX;
    private int currentY;

    public HCZConveyorSpikeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZConveyorSpike");
        int[] bounds = BELT_BOUNDS[spawn.subtype() & 0x0F];
        this.leftBound = bounds[0];
        this.rightBound = bounds[1];
        this.centerX = spawn.x();
        this.centerY = spawn.y();
        this.currentX = spawn.x();

        boolean lowerTrack = (spawn.renderFlags() & 0x01) != 0;
        if (lowerTrack) {
            this.currentY = centerY + TRACK_Y_OFFSET;
            this.state = State.MOVE_LEFT;
            this.angle = 0;
        } else {
            this.currentY = centerY - TRACK_Y_OFFSET;
            this.state = State.MOVE_RIGHT;
            this.angle = RIGHT_ARC_START_ANGLE;
        }
    }

    @Override
    public HCZConveyorSpikeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZConveyorSpikeObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        switch (state) {
            case MOVE_RIGHT -> updateMoveRight();
            case CURVE_RIGHT -> updateCurveRight();
            case MOVE_LEFT -> updateMoveLeft();
            case CURVE_LEFT -> updateCurveLeft();
        }

        updateDynamicSpawn(currentX, currentY);

        int cameraX = services().camera().getX() & 0xFF80;
        int leftCheck = (leftBound & 0xFF80) - CAMERA_MARGIN;
        int rightCheck = rightBound & 0xFF80;
        if (cameraX < leftCheck || cameraX > rightCheck) {
            setDestroyed(true);
        }
    }

    private void updateMoveRight() {
        currentX += SPEED;
        if (currentX == rightBound) {
            state = State.CURVE_RIGHT;
            centerX = currentX;
        }
    }

    private void updateCurveRight() {
        angle = (angle - 2) & ARC_MASK;
        if (angle == 0) {
            state = State.MOVE_LEFT;
        }
        currentX = centerX + curveWord(angle);
        currentY = centerY + curveWord(angle + ARC_Y_TABLE_OFFSET);
    }

    private void updateMoveLeft() {
        currentX -= SPEED;
        if (currentX == leftBound) {
            state = State.CURVE_LEFT;
            centerX = currentX;
        }
    }

    private void updateCurveLeft() {
        angle = (angle - 2) & ARC_MASK;
        if (angle == RIGHT_ARC_START_ANGLE) {
            state = State.MOVE_RIGHT;
        }
        currentX = centerX + curveWord(angle);
        currentY = centerY + curveWord(angle + ARC_Y_TABLE_OFFSET);
    }

    private static int curveWord(int byteOffset) {
        return CURVE_TABLE[(byteOffset & 0xFF) >> 1];
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(0, currentX, currentY, false, false);
        }
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
    public int getCollisionFlags() {
        return isDestroyed() ? 0 : COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) return;
        ctx.drawRect(currentX, currentY, WIDTH_PIXELS, HEIGHT_PIXELS,
                0.8f, 0.8f, 0.2f);
    }
}
