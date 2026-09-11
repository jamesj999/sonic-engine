package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x0A - AIZ Act 1 Zipline Peg.
 * <p>
 * A static decorative zipline peg in Angel Island Zone Act 1.
 * Uses level patterns (art_tile base 0x324, palette 2).
 * ROM priority: 0x380 → bucket 7.
 */
public class Aiz1ZiplinePegObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private PlaceholderObjectInstance placeholder;

    public Aiz1ZiplinePegObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZ1ZiplinePeg");
    }

    @Override
    public Aiz1ZiplinePegObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Aiz1ZiplinePegObjectInstance(ctx.spawn());
    }

    @Override
    public int getPriorityBucket() {
        // ROM priority 0x380 / 0x80 = 7
        return 7;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null) {
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ1_ZIPLINE_PEG);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(0, spawn.x(), spawn.y(), false, false);
                return;
            }
        }

        // Fallback to placeholder
        if (placeholder == null) {
            placeholder = new PlaceholderObjectInstance(spawn, name);
        }
        placeholder.appendRenderCommands(commands);
    }
}
