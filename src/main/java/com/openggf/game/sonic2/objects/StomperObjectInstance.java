package com.openggf.game.sonic2.objects;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Stomper Object (Obj2A) - MCZ ceiling crusher.
 * <p>
 * A ceiling-mounted crusher that slowly rises then rapidly descends.
 * <p>
 * <b>Behavior (from s2.asm lines 24068-24126):</b>
 * <ul>
 *   <li>Rising phase: Timer increments 0→96 at 1/frame, stomper rises</li>
 *   <li>Crushing phase: Timer decrements 96→0 at 8/frame, stomper slams down</li>
 * </ul>
 * <p>
 * <b>Position calculation:</b> Y = baseY - timer
 * <ul>
 *   <li>Timer=0: Fully extended (down)</li>
 *   <li>Timer=96: Fully retracted (up)</li>
 * </ul>
 * <p>
 * <b>Collision (lines 24113-24120):</b>
 * <ul>
 *   <li>Half-width: 0x10 + 0x0B = 0x1B (27 pixels)</li>
 *   <li>Height: 0x40 (64 pixels)</li>
 *   <li>Y-radius: 0x41 (65 pixels)</li>
 *   <li>Solid from all sides (crushes player)</li>
 * </ul>
 * <p>
 * Uses MCZ level art tiles (palette 2).
 */
public class StomperObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, RewindRecreatable {

    private static final boolean DEBUG_VIEW_ENABLED = staticDebugViewEnabled();
    private static final DebugOverlayManager OVERLAY_MANAGER = staticDebugOverlay();

    // Timer constants (from disassembly)
    private static final int MAX_TIMER = 0x60;      // 96 frames - fully retracted
    private static final int CRUSH_SPEED = 8;       // 8 units/frame during crushing

    // Collision parameters (from disassembly lines 24113-24120)
    private static final int COLLISION_HALF_WIDTH = 0x1B;  // width_pixels(0x10) + 0x0B
    private static final int COLLISION_HEIGHT = 0x40;      // 64 pixels
    private static final int COLLISION_Y_RADIUS = 0x41;    // 65 pixels

    // Rendering
    private static final int PALETTE_INDEX = 2;

    // Sprite mapping (from mappings/sprite/obj2A.asm)
    // spritePiece format: x, y, width_tiles, height_tiles, tile_index, xflip, yflip, palette, priority
    private static final SpriteMappingFrame STOMPER_MAPPING = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(-0x10, -0x50, 3, 2, 0x1A, false, false, 0),  // Top cap
            new SpriteMappingPiece(-0x10, -0x40, 4, 4, 0x20, false, false, 0),  // Upper body
            new SpriteMappingPiece(-0x10, -0x20, 4, 4, 0x30, false, false, 0),  // Middle body
            new SpriteMappingPiece(-0x10, 0x00, 4, 4, 0x30, false, true, 0),    // Middle body (V-flip)
            new SpriteMappingPiece(-0x10, 0x20, 4, 4, 0x20, false, true, 0),    // Lower body (V-flip)
            new SpriteMappingPiece(-0x10, 0x40, 3, 2, 0x1A, false, true, 0)     // Bottom cap (V-flip)
    ));

    // State
    private int baseY;           // Original spawn Y position (objoff_32)
    private int currentY;              // Current Y position
    private int timer = 0;             // Movement timer (objoff_30)
    private boolean crushing = false;  // routine_secondary != 0

    public StomperObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseY = spawn.y();
        this.currentY = baseY;
        updateDynamicSpawn(spawn.x(), currentY);
    }

    @Override
    public StomperObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new StomperObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return currentY;
    }
    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Two-phase cycle from disassembly (lines 24096-24109)
        if (!crushing) {
            // Rising phase: timer increments 1/frame
            timer++;
            if (timer >= MAX_TIMER) {
                // Switch to crushing phase
                crushing = true;
            }
        } else {
            // Crushing phase: timer decrements 8/frame
            timer -= CRUSH_SPEED;
            if (timer <= 0) {
                // Reset to rising phase
                timer = 0;
                crushing = false;
            }
        }

        // Update Y position: Y = baseY - timer (lines 24110-24112)
        currentY = baseY - timer;

        updateDynamicSpawn(spawn.x(), currentY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        GraphicsManager graphicsManager = services().graphicsManager();
        List<SpriteMappingPiece> pieces = STOMPER_MAPPING.pieces();

        // Render using level art tiles
        // Draw in reverse order (Painter's Algorithm) - first piece in list appears on top
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    spawn.x(),
                    currentY,
                    0,  // Level patterns start at index 0
                    PALETTE_INDEX,
                    false,  // No H-flip
                    false,  // No V-flip
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }

    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    // --- SolidObjectProvider implementation ---

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly lines 24113-24120
        return SolidObjectParams.of(COLLISION_HALF_WIDTH, COLLISION_HEIGHT, COLLISION_Y_RADIUS);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Stomper is solid from all sides - crushes player
        return false;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Obj2A rewrites y_pos every frame while the live SST slot continues to
        // own p1/p2 pushing and standing bits. Dynamic-spawn coordinates are a
        // placement/rendering detail and must not change the solid latch owner.
        // docs/s2disasm/s2.asm:24200-24255
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj2A passes width_pixels+$B to standard SolidObject. Its unsigned
        // BHI rejection accepts the exact +$1B edge and reaches AtEdge.
        return true;
    }

    @Override
    public boolean preservesEdgeSubpixelMotion() {
        // SolidObject_AtEdge has d0=0, sets Status_Push, and bypasses
        // StopCharacter, leaving x_vel, inertia, and x_sub live.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = spawn.x();
        int y = currentY;

        // Collision box dimensions from getSolidParams
        int halfWidth = COLLISION_HALF_WIDTH;
        int halfHeight = COLLISION_HEIGHT;

        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        // Color: red when crushing (danger), cyan when rising (safe)
        float r = crushing ? 1.0f : 0.2f;
        float g = crushing ? 0.2f : 0.8f;
        float b = crushing ? 0.2f : 0.8f;

        ctx.drawLine(left, top, right, top, r, g, b);
        ctx.drawLine(right, top, right, bottom, r, g, b);
        ctx.drawLine(right, bottom, left, bottom, r, g, b);
        ctx.drawLine(left, bottom, left, top, r, g, b);

        // Cross at center
        int crossHalf = Math.min(halfWidth, halfHeight) / 4;
        ctx.drawLine(x - crossHalf, y, x + crossHalf, y, r, g, b);
        ctx.drawLine(x, y - crossHalf, x, y + crossHalf, r, g, b);
    }

}
