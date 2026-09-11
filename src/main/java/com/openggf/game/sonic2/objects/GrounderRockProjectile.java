package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.objects.badniks.GrounderBadnikInstance;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Rock projectile spawned by Grounder (Obj8D) in Aquatic Ruin Zone.
 * <p>
 * Behavior from disassembly (s2.asm Obj90):
 * <ul>
 *   <li>Starts hidden (waits for parent's activation flag)</li>
 *   <li>When activated, applies velocity from direction table and falls with gravity</li>
 *   <li>Self-destructs when off-screen</li>
 *   <li>collision_flags = 0 (no collision - purely cosmetic debris)</li>
 * </ul>
 * <p>
 * Rock velocities from Obj90_Directions table:
 * <pre>
 * Index 0: X=-1, Y=-4 (256 subpixels each)
 * Index 2: X=+4, Y=-3
 * Index 4: X=+2, Y=0
 * Index 6: X=-3, Y=-1
 * Index 8: X=-3, Y=-3
 * </pre>
 */
public class GrounderRockProjectile extends AbstractObjectInstance implements GrounderZeroIndexChildRewindRecreatable {

    private static final int GRAVITY = 0x38; // 0.21875 pixels/frame (from ObjectMoveAndFall)
    private static final int APPROX_RENDER_Y_MARGIN = 32; // BuildSprites_ApproxYCheck assumed radius

    // Rock velocity table from Obj90_Directions (X, Y in 8.8 fixed point)
    private static final int[][] ROCK_VELOCITIES = {
            {-0x100, -0x400}, // Index 0: X=-1, Y=-4
            {0x400, -0x300},  // Index 2: X=+4, Y=-3
            {0x200, 0},       // Index 4: X=+2, Y=0
            {-0x300, -0x100}, // Index 6: X=-3, Y=-1
            {-0x300, -0x300}  // Index 8: X=-3, Y=-3
    };

    // Rock frames from Obj90_Frames (6 entries in ROM, only 5 used)
    private static final int[] ROCK_FRAMES = {0, 2, 0, 1, 0, 0};

    private int currentX;
    private int currentY;
    private int xVelocity;
    private int yVelocity;
    private int xSubpixel;
    private int ySubpixel;
    private int mappingFrame;
    private boolean activated;
    private boolean initialized;
    private boolean romRenderOnScreen;
    private final GrounderBadnikInstance parent;
    private int rockIndex;

    /**
     * Creates a rock projectile at the specified position.
     *
     * @param x         Initial X position
     * @param y         Initial Y position
     * @param rockIndex Index into velocity/frame tables (0-4)
     * @param parent    Parent Grounder instance (for activation flag)
     */
    public GrounderRockProjectile(int x, int y, int rockIndex, GrounderBadnikInstance parent) {
        super(createRockSpawn(x, y), "GrounderRock");
        this.currentX = x;
        this.currentY = y;
        this.rockIndex = Math.min(rockIndex, ROCK_VELOCITIES.length - 1);
        this.parent = parent;
        this.activated = false;
        this.initialized = false;
        this.romRenderOnScreen = true;
        this.xSubpixel = 0;
        this.ySubpixel = 0;

        // Get velocity and frame from tables
        int[] vel = ROCK_VELOCITIES[this.rockIndex];
        this.xVelocity = vel[0];
        this.yVelocity = vel[1];
        this.mappingFrame = ROCK_FRAMES[this.rockIndex];
    }

    private GrounderRockProjectile() {
        this(0, 0, 0, null);
    }

    private static ObjectSpawn createRockSpawn(int x, int y) {
        return new ObjectSpawn(
                x, y,
                0x90, // GROUNDER_ROCKS
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
            } else {
                return;
            }
        }

        // Obj90_Move tests the previous BuildSprites render_flags.on_screen bit
        // before ObjectMoveAndFall; a cleared render bit deletes the rock without
        // a final movement step (s2.asm:73490-73494).
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
        // after movement, matching the ROM jump at the end of Obj90_Move.
    }

    @Override
    public void refreshPostCameraRenderState() {
        romRenderOnScreen = isWithinRenderSpriteBounds(getOnScreenHalfWidth(), getOnScreenHalfHeight());
    }

    @Override
    public int getOnScreenHalfWidth() {
        return 8;
    }

    @Override
    public int getOnScreenHalfHeight() {
        // Obj90_SubObjData2 does not set render_flags.explicit_height, so S2
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
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Don't render until activated
        if (!activated) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.GROUNDER_ROCK);
        if (renderer == null) return;

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfSize = 8;
        int left = currentX - halfSize;
        int right = currentX + halfSize;
        int top = currentY - halfSize;
        int bottom = currentY + halfSize;

        ctx.drawLine(left, top, right, top, 0.6f, 0.4f, 0.2f);
        ctx.drawLine(right, top, right, bottom, 0.6f, 0.4f, 0.2f);
        ctx.drawLine(right, bottom, left, bottom, 0.6f, 0.4f, 0.2f);
        ctx.drawLine(left, bottom, left, top, 0.6f, 0.4f, 0.2f);
    }

}
