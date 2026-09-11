package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * ROM {@code Child1_MakeRoboShipFlame} for the MHZ end-boss escape craft.
 */
public final class MhzEndBossRobotnikShipFlameInstance extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int OBJECT_ID = 0;
    private static final int X_OFFSET = 0x1E;
    private static final int Y_OFFSET = 0;
    private static final int FLAME_FRAME = 6;
    private static final int PRIORITY_BUCKET = 5; // ObjDat3_RoboShipFlame priority $280
    private static final int STATE_FLAGS_OFFSET = 0x38;
    private static final int DELETE_WHEN_PARENT_FLAG = 0x10; // $38 bit 4
    private static final int RENDER_HALF_WIDTH = 0x08;
    private static final int RENDER_HALF_HEIGHT = 0x04;

    private MhzEndBossInstance parent;
    private boolean visibleThisFrame;
    private boolean flipX;

    private MhzEndBossRobotnikShipFlameInstance() {
        this(placeholderParentForFlameSpawn(new ObjectSpawn(0, 0, OBJECT_ID, 0, 0, false, 0)));
    }

    public MhzEndBossRobotnikShipFlameInstance(MhzEndBossInstance parent) {
        super(new ObjectSpawn(parent.getX() + X_OFFSET, parent.getY() + Y_OFFSET,
                OBJECT_ID, 0, 0, false, 0), "MHZEndBossRobotnikShipFlame");
        this.parent = parent;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MhzEndBossRobotnikShipFlameInstance(placeholderParentForFlameSpawn(ctx.spawn()));
    }

    private static MhzEndBossInstance placeholderParentForFlameSpawn(ObjectSpawn flameSpawn) {
        ObjectSpawn spawn = flameSpawn != null
                ? flameSpawn
                : new ObjectSpawn(0, 0, OBJECT_ID, 0, 0, false, 0);
        return new MhzEndBossInstance(new ObjectSpawn(
                spawn.x() - X_OFFSET - 0xC0,
                spawn.y() - Y_OFFSET,
                Sonic3kObjectIds.MHZ_END_BOSS,
                0,
                0,
                false,
                spawn.layoutIndex()));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if ((parent.getCustomFlag(STATE_FLAGS_OFFSET) & DELETE_WHEN_PARENT_FLAG) != 0 || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }
        flipX = (parent.getState().renderFlags & 1) != 0;
        int xOffset = flipX ? -X_OFFSET : X_OFFSET;
        updateDynamicSpawn(parent.getX() + xOffset, parent.getY() + Y_OFFSET);
        visibleThisFrame = (vIntRunCount & 1) == 0 && parent.getState().xVel != 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!visibleThisFrame || isDestroyed()) {
            return;
        }
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(FLAME_FRAME, getX(), getY(), flipX, false);
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }
}
