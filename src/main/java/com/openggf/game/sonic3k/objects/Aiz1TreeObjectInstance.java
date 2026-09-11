package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x09 - AIZ Act 1 Tree.
 * <p>
 * A static decorative terrain filler in Angel Island Zone Act 1.
 * Uses level primary art tiles 0x39-0x3C (art_tile base 1 + mapping tile 0x38),
 * which are solid terrain-fill patterns with no transparent pixels.
 * <p>
 * ROM: make_art_tile($001, 2, 0) — base tile 1, palette 2, priority 0 (low).
 * <p>
 * On the VDP this low-priority sprite renders above low-priority Plane A tiles,
 * drawing the same terrain fill with palette line 2. It visually matches the
 * underlying FG, making it invisible — but critically it masks any lower-priority
 * sprites (e.g. AIZMinibossSmall at bucket 7) through VDP sprite table ordering.
 * It also forces palette line 2 over FG tiles that may use palette line 1,
 * preventing boss-palette colour bleed into the tree canopy.
 */
public class Aiz1TreeObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    public Aiz1TreeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZ1Tree");
    }

    @Override
    public Aiz1TreeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Aiz1TreeObjectInstance(ctx.spawn());
    }

    @Override
    public int getPriorityBucket() {
        // ROM priority 0x180 / 0x80 = 3
        return 3;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ1_TREE);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(0, spawn.x(), spawn.y(), false, false);
        }
    }
}
