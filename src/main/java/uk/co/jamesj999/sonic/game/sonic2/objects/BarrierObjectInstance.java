package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.SolidContact;
import uk.co.jamesj999.sonic.level.objects.SolidObjectListener;
import uk.co.jamesj999.sonic.level.objects.SolidObjectParams;
import uk.co.jamesj999.sonic.level.objects.SolidObjectProvider;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 2D - One way barrier from CPZ, HTZ, MTZ, ARZ, and DEZ.
 * <p>
 * A vertical barrier platform that rises when the player enters its detection zone
 * and returns to its base position when the player leaves.
 * <p>
 * Based on Sonic 2 disassembly (s2.asm lines 24128-24287).
 * <p>
 * Subtypes determine zone-specific art and width:
 * - 0: HTZ (8px width)
 * - 1: MTZ (12px width)
 * - 2: CPZ/DEZ (8px width)
 * - 3: ARZ (8px width)
 * <p>
 * Behavior:
 * - Detection zone: 64 pixels tall (Y +/- 32 from base)
 * - X boundaries: x-512 to x+24 (normal) or x-488 to x+488 (flipped)
 * - Movement rate: 8 pixels per frame
 * - Max rise: 64 pixels (0x40)
 * - Solid collision enabled while visible
 */
public class BarrierObjectInstance extends AbstractObjectInstance implements SolidObjectProvider, SolidObjectListener {
    private static final Logger LOGGER = Logger.getLogger(BarrierObjectInstance.class.getName());

    // Movement constants (from disassembly)
    private static final int RISE_SPEED = 8;           // addq.w #8,objoff_30(a0)
    private static final int MAX_RISE = 0x40;          // cmpi.w #$40,objoff_30(a0)
    private static final int DETECTION_Y_HALF = 0x20;  // subi.w #$20,d4 / addi.w #$20,d5

    // X detection boundaries (from disassembly)
    private static final int X_LEFT_OFFSET = 0x200;    // subi.w #$200,d2
    private static final int X_RIGHT_OFFSET = 0x18;    // addi.w #$18,d3
    private static final int X_FLIP_LEFT_ADD = 0x1E8;  // subi.w #-$1E8,d2 (which adds 0x1E8)
    private static final int X_FLIP_RIGHT_ADD = 0x1E8; // addi.w #$1E8,d3

    // Solid collision constants (from disassembly)
    private static final int SOLID_EXTRA_WIDTH = 0x0B; // addi.w #$B,d1
    private static final int SOLID_HALF_HEIGHT = 0x20; // move.w #$20,d2

    // Subtype configurations: {width_pixels}
    private static final int[] SUBTYPE_WIDTH = {
            8,   // Subtype 0: HTZ
            12,  // Subtype 1: MTZ
            8,   // Subtype 2: CPZ/DEZ
            8    // Subtype 3: ARZ
    };

    private int x;
    private int y;
    private int baseY;          // objoff_32 - original Y position
    private int riseOffset;     // objoff_30 - current rise amount (0 to MAX_RISE)
    private int leftBoundary;   // objoff_38 - left X boundary for detection
    private int rightBoundary;  // objoff_3A - right X boundary for detection
    private int widthPixels;
    private int mappingFrame;
    private boolean movingUp;   // routine_secondary - true when moving up
    private boolean xFlip;

    private ObjectSpawn dynamicSpawn;

    public BarrierObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        init();
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return dynamicSpawn != null ? dynamicSpawn : spawn;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Reset movement flag each frame
        movingUp = false;

        // Calculate detection boundaries based on flip state
        int detectLeft, detectRight;
        if (!xFlip) {
            // Normal: check from left boundary to current x (or right boundary if moving)
            detectLeft = leftBoundary;
            detectRight = movingUp ? rightBoundary : x;
        } else {
            // Flipped: check from current x (or left boundary if moving) to right boundary
            detectLeft = movingUp ? leftBoundary : x;
            detectRight = rightBoundary;
        }

        int detectTop = baseY - DETECTION_Y_HALF;
        int detectBottom = baseY + DETECTION_Y_HALF;

        // Check both main character and sidekick (from s2.asm lines 24222-24225):
        //   lea (MainCharacter).w,a1
        //   bsr.s Obj2D_CheckCharacter
        //   lea (Sidekick).w,a1
        //   bsr.s Obj2D_CheckCharacter
        checkCharacter(player, detectLeft, detectRight, detectTop, detectBottom);

        // Check sidekick (Tails AI) if present
        AbstractPlayableSprite sidekick = SpriteManager.getInstance().getSidekick();
        if (sidekick != null) {
            checkCharacter(sidekick, detectLeft, detectRight, detectTop, detectBottom);
        }

        // Update position based on movement state
        if (movingUp) {
            // Move up if not at max height
            if (riseOffset < MAX_RISE) {
                riseOffset += RISE_SPEED;
                if (riseOffset > MAX_RISE) {
                    riseOffset = MAX_RISE;
                }
            }
        } else {
            // Move down if not at base position
            if (riseOffset > 0) {
                riseOffset -= RISE_SPEED;
                if (riseOffset < 0) {
                    riseOffset = 0;
                }
            }
        }

        // Update Y position: y_pos = base_y - rise_offset
        y = baseY - riseOffset;

        refreshDynamicSpawn();
    }

    /**
     * Checks if a character is within the detection zone.
     * If so, sets the barrier to move up.
     * <p>
     * From Obj2D_CheckCharacter (s2.asm lines 24259-24277)
     */
    private void checkCharacter(AbstractPlayableSprite character, int left, int right, int top, int bottom) {
        if (character == null) {
            return;
        }

        int charX = character.getX();
        int charY = character.getY();

        // Check X boundaries (character must be >= left and < right)
        if (charX < left || charX >= right) {
            return;
        }

        // Check Y boundaries (character must be >= top and < bottom)
        if (charY < top || charY >= bottom) {
            return;
        }

        // Check if character is not dead/disabled (obj_control bit 7 clear)
        // In our engine, we check if the player is controllable/alive
        if (character.getDead()) {
            return;
        }

        // Character is in zone - set barrier to move up
        movingUp = true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.BARRIER);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        // mappingFrame is the subtype (0=HTZ, 1=MTZ, 2=CPZ/DEZ, 3=ARZ)
        renderer.drawFrameIndex(mappingFrame, x, y, xFlip, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: d1 = width_pixels + $B, d2 = $20, d3 = $21
        int halfWidth = widthPixels + SOLID_EXTRA_WIDTH;
        int airHalfHeight = SOLID_HALF_HEIGHT;
        int groundHalfHeight = SOLID_HALF_HEIGHT + 1;
        return new SolidObjectParams(halfWidth, airHalfHeight, groundHalfHeight);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Barrier is a full solid object, not top-only
        return false;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Solid collision is handled by ObjectManager
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    private void init() {
        x = spawn.x();
        baseY = spawn.y();
        y = baseY;
        riseOffset = 0;
        movingUp = false;

        // Get subtype (mapping frame)
        mappingFrame = spawn.subtype() & 0x03;
        if (mappingFrame < 0 || mappingFrame >= SUBTYPE_WIDTH.length) {
            mappingFrame = 0;
        }
        widthPixels = SUBTYPE_WIDTH[mappingFrame];

        // Check X-flip status
        xFlip = (spawn.renderFlags() & 0x01) != 0;

        // Calculate X detection boundaries
        // Normal: left = x - $200, right = x + $18
        // Flipped: left = x - $200 + $1E8 = x - $18, right = x + $18 + $1E8 = x + $200
        if (!xFlip) {
            leftBoundary = x - X_LEFT_OFFSET;
            rightBoundary = x + X_RIGHT_OFFSET;
        } else {
            // When flipped, the disassembly does:
            // subi.w #-$1E8,d2  ; This actually adds $1E8 (subtracting a negative)
            // addi.w #$1E8,d3
            // So: left = x - $200 + $1E8, right = x + $18 + $1E8
            leftBoundary = x - X_LEFT_OFFSET + X_FLIP_LEFT_ADD;
            rightBoundary = x + X_RIGHT_OFFSET + X_FLIP_RIGHT_ADD;
        }

        refreshDynamicSpawn();
    }

    private void refreshDynamicSpawn() {
        if (dynamicSpawn == null || dynamicSpawn.x() != x || dynamicSpawn.y() != y) {
            dynamicSpawn = new ObjectSpawn(
                    x,
                    y,
                    spawn.objectId(),
                    spawn.subtype(),
                    spawn.renderFlags(),
                    spawn.respawnTracked(),
                    spawn.rawYWord());
        }
    }
}
