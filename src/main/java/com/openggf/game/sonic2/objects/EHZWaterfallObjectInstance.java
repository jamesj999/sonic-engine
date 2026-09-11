package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 49 - Waterfall from EHZ.
 * <p>
 * Displays different frames based on proximity to the player.
 * Based on Obj49_ChkDel logic.
 */
public class EHZWaterfallObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(EHZWaterfallObjectInstance.class.getName());

    private int mappingFrame;

    public EHZWaterfallObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        // Default to subtype frame (ROM always adds subtype to mapping_frame)
        this.mappingFrame = spawn.subtype() & 0xFF;
    }

    @Override
    public EHZWaterfallObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new EHZWaterfallObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        // Logic from Obj49_ChkDel:
        // d1 = x_pos
        // d2 = d1 + $40
        // d1 = d1 - $40
        // Check if player X is within [d1, d2)
        // If not, frame = subtype
        // If so, frame = 1 + subtype

        int px = player.getCentreX();
        int ox = spawn.x();
        int dx = px - ox;
        int baseFrame = spawn.subtype() & 0xFF;

        int minX = ox - 64; // Distance from object origin
        int maxX = ox + 64;

        if (px >= minX && px < maxX) {
            // In range: show active frame (1 + subtype)
            int newFrame = baseFrame + 1;
            if (newFrame != mappingFrame) {
                LOGGER.fine("Waterfall activated: frame " + newFrame + " (dx=" + dx + ")");
                mappingFrame = newFrame;
            }
        } else {
            // Out of range: subtype frame
            if (mappingFrame != baseFrame) {
                LOGGER.fine("Waterfall deactivated: frame " + baseFrame + " (dx=" + dx + ")");
                mappingFrame = baseFrame;
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WATERFALL);
        if (renderer == null) return;

        // Render at spawn position
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }
}
