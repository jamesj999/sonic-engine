package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;

import java.util.List;

/**
 * One of the camera-boundary workers created by {@code Change_Act2Sizes}.
 *
 * <p>The ROM replaces the AIZ miniboss with {@code Obj_EndSignControl}, then
 * allocates independent {@code Obj_IncLevEndXGradual} and
 * {@code Obj_IncLevEndYGradual} objects before deleting the former miniboss
 * slot (sonic3k.asm:180415-180419,180575-180609,178154-178169,178210-178225).
 * Keeping these workers separate is significant: ordinary level objects may
 * reuse the released boss slot while the boundary changes continue.
 */
final class AizAct2CameraResizeController extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    static final int MAX_X = 0;
    static final int MAX_Y = 1;

    // Non-final so generic rewind capture can restore the constructor variant.
    private int boundary;
    private int accumulator;

    AizAct2CameraResizeController(int boundary) {
        super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "AIZAct2CameraResize");
        this.boundary = boundary;
        // The ROM's $30 accumulator starts at zero. CreateChild1_Normal
        // allocates each worker through AllocateObjectAfterCurrent, which only
        // ever hands back an SST slot *after* the creating object
        // (sonic3k.asm:37917-37932,176924-176936), so Process_Sprites reaches
        // the worker in the same pass that created it. The creation frame is
        // therefore the worker's dispatch 1, whose fixed-point carry
        // ($4000 for X, $8000 for Y) still yields a zero integer step. No
        // creation-pass carry is skipped and none is pre-charged here.
    }

    AizAct2CameraResizeController(ObjectSpawn spawn) {
        this(MAX_X);
    }

    @Override
    public int getX() {
        return 0;
    }

    @Override
    public int getY() {
        return 0;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (boundary == MAX_X) {
            updateMaxX();
        } else {
            updateMaxY();
        }
    }

    private void updateMaxX() {
        var camera = services().camera();
        var level = services().currentLevel();
        int storedMax = level != null ? level.getMaxX() : (camera.getMaxX() & 0xFFFF);

        accumulator += 0x4000;
        int delta = accumulator >>> 16;
        int currentMax = camera.getMaxX() & 0xFFFF;
        if (currentMax > storedMax) {
            // A later camera owner has already widened farther than the stale
            // gradual worker's target; ROM deletes the worker at its bound.
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        int nextMax = currentMax + delta;
        if (nextMax >= storedMax) {
            camera.setMaxX((short) storedMax);
            ObjectLifetimeOps.expireDynamic(this);
        } else {
            camera.setMaxX((short) nextMax);
        }
    }

    private void updateMaxY() {
        var camera = services().camera();
        var level = services().currentLevel();
        int storedMax = level != null ? level.getMaxY() : (camera.getMaxYTarget() & 0xFFFF);

        accumulator += 0x8000;
        int delta = accumulator >>> 16;
        int nextMax = (camera.getMaxY() & 0xFFFF) + delta;
        if (nextMax > storedMax) {
            camera.setMaxY((short) storedMax);
            ObjectLifetimeOps.expireDynamic(this);
        } else {
            camera.setMaxY((short) nextMax);
            camera.setMaxYTarget((short) storedMax);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible ROM control object.
    }
}
