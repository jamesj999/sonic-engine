package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.objects.badniks.GrounderBadnikInstance;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Wall piece spawned by Grounder (Obj8D) in Aquatic Ruin Zone.
 * <p>
 * Behavior from disassembly (s2.asm Obj8F):
 * <ul>
 *   <li>BEFORE activation: Visible wall hiding the Grounder (uses LEVEL ART tiles)</li>
 *   <li>Waits for parent's activation flag (objoff_2B)</li>
 *   <li>AFTER activation: Scatters with velocity from direction table and falls with gravity</li>
 *   <li>Self-destructs when off-screen</li>
 * </ul>
 * <p>
 * Wall sprite mapping (word_36D9A) - 32x16 pixels using LEVEL tiles:
 * <pre>
 * spritePiece -$10, -8, 2, 2, $93, 0, 0, 2, 0  ; Left 2x2 tiles
 * spritePiece    0, -8, 2, 2, $97, 0, 0, 2, 0  ; Right 2x2 tiles
 * </pre>
 * <p>
 * Wall spawn offsets from byte_36CBC (relative to Grounder position):
 * <pre>
 * Index 0: X=0, Y=-20   (top center)
 * Index 2: X=+16, Y=-4  (right middle)
 * Index 4: X=0, Y=+12   (bottom center)
 * Index 6: X=-16, Y=-4  (left middle)
 * </pre>
 * <p>
 * Wall velocities from Obj8F_Directions:
 * <pre>
 * Index 0: X=+1, Y=-2
 * Index 2: X=+1, Y=-1
 * Index 4: X=-1, Y=-2
 * Index 6: X=-1, Y=-1
 * </pre>
 */
public class GrounderWallInstance extends AbstractObjectInstance implements GrounderZeroIndexChildRewindRecreatable {

    private static final int GRAVITY = 0x38; // 0.21875 pixels/frame (from ObjectMoveAndFall)
    private static final int PALETTE_INDEX = 1;  // Level art palette (matches FallingPillar)
    private static final int APPROX_RENDER_Y_MARGIN = 32; // BuildSprites_ApproxYCheck assumed radius

    // Wall mapping from word_36D9A - 32x16 using level tiles
    private static final List<SpriteMappingPiece> WALL_PIECES = List.of(
            new SpriteMappingPiece(-16, -8, 2, 2, 0x93, false, false, 1),  // Left 2x2
            new SpriteMappingPiece(0, -8, 2, 2, 0x97, false, false, 1)     // Right 2x2
    );

    // Wall velocity table from Obj8F_Directions (X, Y in 8.8 fixed point)
    private static final int[][] WALL_VELOCITIES = {
            {0x100, -0x200},  // Index 0: X=+1, Y=-2
            {0x100, -0x100},  // Index 2: X=+1, Y=-1
            {-0x100, -0x200}, // Index 4: X=-1, Y=-2
            {-0x100, -0x100}  // Index 6: X=-1, Y=-1
    };

    // Wall spawn offsets from byte_36CBC
    public static final int[][] WALL_OFFSETS = {
            {0, -20},   // Index 0
            {16, -4},   // Index 2
            {0, 12},    // Index 4
            {-16, -4}   // Index 6
    };

    private int currentX;
    private int currentY;
    private int xVelocity;
    private int yVelocity;
    private int xSubpixel;
    private int ySubpixel;
    private boolean activated;
    private boolean initialized;
    private boolean romRenderOnScreen;
    private final GrounderBadnikInstance parent;

    /**
     * Creates a wall piece at the specified position.
     *
     * @param x         Initial X position
     * @param y         Initial Y position
     * @param wallIndex Index into velocity table (0-3)
     * @param parent    Parent Grounder instance (for activation flag)
     */
    public GrounderWallInstance(int x, int y, int wallIndex, GrounderBadnikInstance parent) {
        super(createWallSpawn(x, y), "GrounderWall");
        this.currentX = x;
        this.currentY = y;
        this.parent = parent;
        this.activated = false;
        this.initialized = false;
        this.romRenderOnScreen = true;
        this.xSubpixel = 0;
        this.ySubpixel = 0;

        // Get velocity from table
        int idx = Math.min(wallIndex, WALL_VELOCITIES.length - 1);
        int[] vel = WALL_VELOCITIES[idx];
        this.xVelocity = vel[0];
        this.yVelocity = vel[1];
    }

    private GrounderWallInstance() {
        this(0, 0, 0, null);
    }

    private static ObjectSpawn createWallSpawn(int x, int y) {
        return new ObjectSpawn(
                x, y,
                0x8F, // GROUNDER_WALL
                0,
                0,
                false,
                0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            initialized = true;
            return;
        }

        // Wait for parent's activation flag
        if (!activated) {
            if (parent == null || parent.isActivated()) {
                activated = true;
                // ROM Obj8F loc_36BA6 only advances routine 2->4 and latches
                // velocities when objoff_2B is set; Obj8F_Move runs next frame.
                // docs/s2disasm/s2.asm:73424-73437
                return;
            } else {
                return;
            }
        }

        // Obj8F_Move shares Obj90_Move's render-flag delete gate: it tests the
        // previous BuildSprites on-screen bit before ObjectMoveAndFall
        // (s2.asm:73490-73494).
        if (!romRenderOnScreen) {
            setDestroyed(true);
            return;
        }

        // Update X position with fixed-point math
        xSubpixel += xVelocity;
        currentX += (xSubpixel >> 8);
        xSubpixel &= 0xFF;

        // ObjectMoveAndFall moves with the old y_vel, then applies gravity.
        // docs/s2disasm/s2.asm:30163-30177
        ySubpixel += yVelocity;
        currentY += (ySubpixel >> 8);
        ySubpixel &= 0xFF;
        yVelocity += GRAVITY;

        // The shared ObjectManager MarkObjGone tail handles X-range cleanup
        // after movement, matching the ROM jump at the end of Obj8F_Move.
    }

    @Override
    public void refreshPostCameraRenderState() {
        romRenderOnScreen = isWithinRenderSpriteBounds(getOnScreenHalfWidth(), getOnScreenHalfHeight());
    }

    @Override
    public int getOnScreenHalfHeight() {
        // Obj8F_SubObjData does not set render_flags.explicit_height, so S2
        // BuildSprites uses its approximate +/-32px Y band before setting
        // render_flags.on_screen (docs/s2disasm/s2.asm:30569-30588).
        return APPROX_RENDER_Y_MARGIN;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        GraphicsManager graphicsManager = services().graphicsManager();

        for (int i = WALL_PIECES.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = WALL_PIECES.get(i);
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    currentX,
                    currentY,
                    0,  // basePatternIndex (tiles are absolute in mapping)
                    PALETTE_INDEX,
                    false,  // hFlip
                    false,  // vFlip
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
}
