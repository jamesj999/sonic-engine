package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Floating points display object (Obj29).
 * <p>
 * From s2.asm Obj29_Init/Obj29_Main:
 * - Initial y_vel = -$300 (-768 subpixels = -3 pixels/frame upward)
 * - Each frame: position += velocity, then velocity += $18 (gravity)
 * - Deleted when y_vel >= 0 (about to fall back down)
 */
public class PointsObjectInstance extends uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance {
    // ROM constants from Obj29_Init and Obj29_Main
    private static final int INITIAL_Y_VEL = -0x300;  // -768 subpixels (upward)
    private static final int GRAVITY = 0x18;          // +24 subpixels per frame

    private final PatternSpriteRenderer renderer;
    private final LevelManager levelManager;
    private int currentX;
    private int ySubpixel;  // 8.8 fixed-point Y position (high byte = pixel)
    private int yVel;       // Y velocity in subpixels
    private int scoreFrame;

    public PointsObjectInstance(ObjectSpawn spawn, LevelManager levelManager, int points) {
        super(spawn, "Points");
        this.levelManager = levelManager;
        this.renderer = levelManager.getObjectRenderManager().getPointsRenderer();
        this.currentX = spawn.x();
        this.ySubpixel = spawn.y() << 8;  // Convert to 8.8 fixed-point
        this.yVel = INITIAL_Y_VEL;
        setScore(points);
    }

    public void setScore(int points) {
        switch (points) {
            case 10:
                this.scoreFrame = 4;
                break;
            case 100:
                this.scoreFrame = 0;
                break;
            case 200:
                this.scoreFrame = 1;
                break;
            case 500:
                this.scoreFrame = 2;
                break;
            case 1000:
                this.scoreFrame = 3;
                break;
            default:
                this.scoreFrame = 0; // Default 100
                break;
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // ROM: tst.w y_vel(a0) / bpl.w DeleteObject
        // Delete when velocity becomes non-negative (object would start falling)
        if (yVel >= 0) {
            setDestroyed(true);
            return;
        }

        // ROM: bsr.w ObjectMove - apply velocity to position
        ySubpixel += yVel;

        // ROM: addi.w #$18,y_vel(a0) - apply gravity (slow down upward motion)
        yVel += GRAVITY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed())
            return;
        // Convert 8.8 fixed-point to pixel position (high byte)
        int pixelY = ySubpixel >> 8;
        renderer.drawFrameIndex(scoreFrame, currentX, pixelY, false, false);
    }

    public int getX() {
        return currentX;
    }

    public int getY() {
        // Return pixel position (high byte of 8.8 fixed-point)
        return ySubpixel >> 8;
    }
}
