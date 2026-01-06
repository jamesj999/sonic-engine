package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Bridge Stake (scenery) object (0x1C subtype 2) - Static decorative posts at
 * bridge ends.
 * Uses the same art as the bridge (EHZ bridge.nem) but renders frame 1 (the
 * stake).
 * 
 * From disassembly (objEHZ.ini):
 * - Uses obj11_b.asm mappings with frame 1
 * - Uses palette 2
 * - No collision/physics (pure scenery)
 */
public class BridgeStakeObjectInstance extends AbstractObjectInstance {

    public BridgeStakeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getBridgeRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Render frame 1 (the stake) at the spawn position
        renderer.drawFrameIndex(1, spawn.x(), spawn.y(), false, false);
    }
}
