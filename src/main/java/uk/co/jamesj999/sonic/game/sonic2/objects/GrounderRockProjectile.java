package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.GrounderBadnikInstance;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.TouchResponseProvider;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Rock projectile spawned by Grounder (Obj8D) in Aquatic Ruin Zone.
 * <p>
 * Behavior from disassembly (s2.asm Obj90):
 * <ul>
 *   <li>Starts hidden (waits for parent's activation flag)</li>
 *   <li>When activated, applies velocity from direction table and falls with gravity</li>
 *   <li>Self-destructs when off-screen</li>
 *   <li>collision_flags = 0x84 (HURT + size index 4)</li>
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
public class GrounderRockProjectile extends AbstractObjectInstance
        implements TouchResponseProvider {

    // Collision from subObjData: collision_flags = $84 (HURT + size 4)
    private static final int COLLISION_FLAGS = 0x84;
    private static final int GRAVITY = 0x38; // 0.21875 pixels/frame (from ObjectMoveAndFall)

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
    private final GrounderBadnikInstance parent;
    private final int rockIndex;

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
        this.xSubpixel = 0;
        this.ySubpixel = 0;

        // Get velocity and frame from tables
        int[] vel = ROCK_VELOCITIES[this.rockIndex];
        this.xVelocity = vel[0];
        this.yVelocity = vel[1];
        this.mappingFrame = ROCK_FRAMES[this.rockIndex];
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
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Wait for parent's activation flag
        if (!activated) {
            if (parent == null || parent.isActivated()) {
                activated = true;
            } else {
                return;
            }
        }

        // Apply gravity to Y velocity
        yVelocity += GRAVITY;

        // Update X position with fixed-point math
        xSubpixel += xVelocity;
        currentX += (xSubpixel >> 8);
        xSubpixel &= 0xFF;

        // Update Y position with fixed-point math
        ySubpixel += yVelocity;
        currentY += (ySubpixel >> 8);
        ySubpixel &= 0xFF;

        // Off-screen cleanup
        if (!isOnScreen(64)) {
            setDestroyed(true);
        }
    }

    @Override
    public int getCollisionFlags() {
        // Only check collision when activated
        if (!activated) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(
                currentX,
                currentY,
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                spawn.respawnTracked(),
                spawn.rawYWord());
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

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.GROUNDER_ROCK);
        if (renderer == null || !renderer.isReady()) {
            appendDebug(commands);
            return;
        }

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    private void appendDebug(List<GLCommand> commands) {
        int halfSize = 8;
        int left = currentX - halfSize;
        int right = currentX + halfSize;
        int top = currentY - halfSize;
        int bottom = currentY + halfSize;

        appendLine(commands, left, top, right, top);
        appendLine(commands, right, top, right, bottom);
        appendLine(commands, right, bottom, left, bottom);
        appendLine(commands, left, bottom, left, top);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.4f, 0.2f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.4f, 0.2f, x2, y2, 0, 0));
    }
}
