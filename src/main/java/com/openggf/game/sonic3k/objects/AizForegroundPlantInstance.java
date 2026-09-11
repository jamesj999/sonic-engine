package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
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
 * Object 0x35 - AIZ Foreground Plant.
 * <p>
 * A purely decorative foreground plant in Angel Island Zone. Renders static
 * plant sprites at different parallax scroll rates to create depth layering
 * in front of gameplay.
 * <p>
 * Subtype format: [RRRR VVVV]
 * <ul>
 *   <li>Bits 0-3: Visual variant (0=with flowers, 1=without flowers)</li>
 *   <li>Bits 4-7: Scroll rate index (0-6)</li>
 * </ul>
 * <p>
 * ROM reference: sonic3k.asm lines 60430-60582
 */
public class AizForegroundPlantInstance extends AbstractObjectInstance implements RewindRecreatable {

    private int origX;
    private int origY;
    private int mappingFrame;
    private int scrollRate;
    private PlaceholderObjectInstance placeholder;

    public AizForegroundPlantInstance(ObjectSpawn spawn) {
        super(spawn, "AIZForegroundPlant");
        this.origX = spawn.x();
        this.origY = spawn.y();
        this.mappingFrame = Math.min(spawn.subtype() & 0x0F, 1);
        this.scrollRate = Math.min((spawn.subtype() >> 4) & 0x07, 6);
    }

    @Override
    public AizForegroundPlantInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AizForegroundPlantInstance(ctx.spawn());
    }

    @Override
    public int getPriorityBucket() {
        // ROM priority field = dc.w 0
        return 0;
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_AIZMisc1, 2, 1) — priority bit = 1
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null) {
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_FOREGROUND_PLANT);
            if (renderer != null && renderer.isReady()) {
                Camera camera = services().camera();
                int cameraX = camera.getX();
                int cameraY = camera.getY();

                // World-space position; shader subtracts camera to get screen coords
                int drawX = origX;
                int drawY = origY;

                if (scrollRate > 0) {
                    // Delta from screen center (160=half width, 112=half height)
                    int deltaX = origX - 160 - cameraX;
                    int deltaY = origY - 112 - cameraY;
                    drawX += xParallaxOffset(deltaX);
                    drawY += yParallaxOffset(deltaY);
                }

                renderer.drawFrameIndex(mappingFrame, drawX, drawY, false, false);
                return;
            }
        }

        // Fallback to placeholder
        if (placeholder == null) {
            placeholder = new PlaceholderObjectInstance(spawn, name);
        }
        placeholder.appendRenderCommands(commands);
    }

    // ROM: loc_2C270 through loc_2C37A — per-rate arithmetic shift on delta from screen center
    private int xParallaxOffset(int delta) {
        return switch (scrollRate) {
            case 1 -> delta >> 4;   // asr #4 (1/16x)
            case 2 -> delta >> 3;   // asr #3 (1/8x)
            case 3 -> delta >> 2;   // asr #2 (1/4x)
            case 4 -> delta >> 1;   // asr #1 (1/2x)
            case 5 -> delta;        // 1x
            case 6 -> delta << 1;   // lsl #1 (2x)
            default -> 0;
        };
    }

    // Rate 6 Y uses 1x (same as rate 5), NOT 2x
    private int yParallaxOffset(int delta) {
        return switch (scrollRate) {
            case 1 -> delta >> 4;
            case 2 -> delta >> 3;
            case 3 -> delta >> 2;
            case 4 -> delta >> 1;
            case 5, 6 -> delta;    // Rate 6 Y = 1x
            default -> 0;
        };
    }
}
