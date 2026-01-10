package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 49 - Waterfall from EHZ.
 * <p>
 * Displays different frames based on proximity to the player.
 * Based on Obj49_ChkDel logic.
 */
public class EHZWaterfallObjectInstance extends AbstractObjectInstance {
    private static final Logger LOGGER = Logger.getLogger(EHZWaterfallObjectInstance.class.getName());

    private int mappingFrame;

    public EHZWaterfallObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        // Default to subtype frame (ROM always adds subtype to mapping_frame)
        this.mappingFrame = spawn.subtype() & 0xFF;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
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
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getWaterfallRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Render at spawn position
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }
}
