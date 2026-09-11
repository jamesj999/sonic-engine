package com.openggf.game.sonic3k.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnDefaultArgsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Lightweight gravity-affected rock debris fragment.
 * Spawned by {@link AizLrzRockObjectInstance} when a rock breaks.
 * <p>
 * Each fragment receives a scattered velocity and renders a specific mapping
 * frame from the parent rock's sprite sheet. Falls with gravity until offscreen,
 * then deletes itself.
 * <p>
 * ROM: BreakObjectToPieces (sonic3k.asm:45772) creates fragments with
 * velocities from word_2A8B0. Gravity = 0x18 subpixels/frame (same as
 * cork floor fragments).
 */
public class RockDebrisChild extends GravityDebrisChild implements SpawnDefaultArgsRewindRecreatable {

    private static final int GRAVITY = 0x18;

    // Non-final: not derivable from the carried ObjectSpawn (only x/y are
    // carried; velocities/subtype are 0). Rewind recreate passes placeholders
    // (0, null) and the GenericFieldCapturer reapplies these captured values
    // after recreateDynamicObject. Mirrors the AizRockFragmentChild sibling.
    private int mappingFrame;
    private String artKey;

    RockDebrisChild() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), 0, 0, 0, null);
    }

    public RockDebrisChild(ObjectSpawn spawn, int xVel, int yVel,
                           int mappingFrame, String artKey) {
        super(spawn, "RockDebris", xVel, yVel, GRAVITY);
        this.mappingFrame = mappingFrame;
        this.artKey = artKey;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(mappingFrame, motionState.x, motionState.y, false, false);
        }
    }
}
