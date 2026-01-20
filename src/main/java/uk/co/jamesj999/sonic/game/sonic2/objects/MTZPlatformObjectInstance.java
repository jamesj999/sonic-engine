package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.OscillationManager;
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
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * MTZ Platform (Object 0x6B) - Moving platform from Metropolis Zone and Chemical Plant Zone.
 * <p>
 * In CPZ, this object renders as a SINGLE 32x32 block. The multi-block visual effect
 * seen in the game is achieved by placing MULTIPLE separate Object 0x6B instances
 * at the same location, each with different subtypes (movement types 8, 9, 10, 11)
 * which have different radii (0x10, 0x30, 0x50, 0x70). The oscillators for these
 * types are synchronized but at different amplitudes, creating coordinated but
 * differential movement.
 * <p>
 * In MTZ, this object uses different mappings with multi-block frames, but all
 * blocks still move as one unit (single x_pos, y_pos).
 * <p>
 * Movement types (from s2.asm lines 53860-54159):
 * <ul>
 *   <li>Type 0: Stationary</li>
 *   <li>Types 1-2: Horizontal oscillation (amplitudes 0x40, 0x80)</li>
 *   <li>Types 3-4: Vertical oscillation (amplitudes 0x40, 0x80)</li>
 *   <li>Types 5-6: Triggered falling</li>
 *   <li>Type 7: Bouncy platform</li>
 *   <li>Types 8-11: Circular/square motion with radii 0x10, 0x30, 0x50, 0x70</li>
 * </ul>
 */
public class MTZPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(MTZPlatformObjectInstance.class.getName());

    // Subtype properties from Obj6B_Properties (line 53879-53881)
    // Format: {width_pixels, y_radius, mapping_frame}
    private static final int[][] SUBTYPE_PROPERTIES = {
            {32, 12, 1}, // Index 0: width=32, y_radius=12, frame 1
            {16, 16, 0}, // Index 1: width=16, y_radius=16, frame 0
    };

    // Circular motion radii for types 8-11
    private static final int[] CIRCULAR_RADII = {0x10, 0x30, 0x50, 0x70};

    // Position tracking
    private int x;
    private int y;
    private int baseX;      // objoff_34 - Original X position
    private int baseY;      // objoff_30 - Original Y position
    private int yFixed;     // 16.8 fixed-point Y for falling/bouncing
    private int yVel;       // Y velocity for falling/bouncing

    // Subtype configuration
    private int moveType;
    private int widthPixels;
    private int yRadius;
    private int mappingFrame;

    // State tracking
    private int flipState;      // objoff_2E - Circular motion quadrant (0-3)
    private int bounceAccel;
    private boolean xFlip;

    // Contact tracking
    private int lastContactFrame = -2;

    private ObjectSpawn dynamicSpawn;

    public MTZPlatformObjectInstance(ObjectSpawn spawn, String name) {
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
    public SolidObjectParams getSolidParams() {
        // From disassembly line 53930-53937:
        // d1 = width_pixels + 11 (half-width for collision)
        int halfWidth = widthPixels + 0x0B;
        return new SolidObjectParams(halfWidth, yRadius, yRadius + 1);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Obj6B uses regular SolidObject (jsrto JmpTo14_SolidObject), not PlatformObject,
        // so it's fully solid from all sides, not just the top.
        return false;
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (contact.standing() || contact.touchTop()) {
            lastContactFrame = frameCounter;
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        applyMovement(frameCounter);
        refreshDynamicSpawn();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        PatternSpriteRenderer renderer = null;

        if (renderManager != null) {
            renderer = renderManager.getCpzStairBlockRenderer();
        }

        if (renderer != null && renderer.isReady()) {
            // In CPZ, the original mappings (obj6B.asm) only have 1 frame with 1 piece.
            // Multiple instances are placed at the same location with different subtypes
            // to create the multi-block effect. So we always render a SINGLE block (frame 2).
            renderer.drawFrameIndex(2, x, y, xFlip, false);
        } else {
            appendDebug(commands);
        }
    }

    private void init() {
        // Property extraction: (subtype >> 2) & 0x1C gives byte offset
        int propsOffset = (spawn.subtype() >> 2) & 0x1C;
        int propsIndex = Math.min(propsOffset / 4, SUBTYPE_PROPERTIES.length - 1);
        if (propsIndex < 0) {
            propsIndex = 0;
        }

        widthPixels = SUBTYPE_PROPERTIES[propsIndex][0];
        yRadius = SUBTYPE_PROPERTIES[propsIndex][1];
        mappingFrame = SUBTYPE_PROPERTIES[propsIndex][2];

        // Store base positions
        baseX = spawn.x();
        baseY = spawn.y();
        x = baseX;
        y = baseY;
        yFixed = baseY << 8;

        // Movement type from bits 0-3 (clamped to 0-11)
        moveType = spawn.subtype() & 0x0F;
        if (moveType > 11) {
            moveType = 0;
        }

        // flipState (objoff_2E) is initialized from status byte which has both xFlip and yFlip
        // This creates a 2-bit quadrant value (0-3) for circular motion
        // Bit 0 = xFlip, Bit 1 = yFlip
        xFlip = (spawn.renderFlags() & 0x01) != 0;
        flipState = spawn.renderFlags() & 0x03;  // Both bits: 0-3 range

        // Special init for circular motion (types 8-11):
        // If oscillator delta is negative, toggle bit 0 of flipState
        if (moveType >= 8 && moveType <= 11) {
            int oscOffset = 0x2A + ((moveType - 8) * 4);
            int oscWord = OscillationManager.getWord(oscOffset);
            if (oscWord < 0) {
                flipState ^= 1;  // Toggle bit 0 (bchg #0,objoff_2E)
            }
        }

        yVel = 0;
        bounceAccel = 0;

        refreshDynamicSpawn();
    }

    /**
     * Applies movement based on movement type.
     */
    private void applyMovement(int frameCounter) {
        boolean standing = (frameCounter - lastContactFrame) <= 1;

        switch (moveType) {
            case 0 -> { /* Stationary - no movement */ }
            case 1 -> applyHorizontalOscillation(0x08, 0x40);
            case 2 -> applyHorizontalOscillation(0x1C, 0x80);
            case 3 -> applyVerticalOscillation(0x08, 0x40);
            case 4 -> applyVerticalOscillation(0x1C, 0x80);
            case 5 -> applyTriggerFall(standing);
            case 6 -> applyFalling();
            case 7 -> applyBouncy(standing);
            case 8, 9, 10, 11 -> applyCircularMotion(moveType - 8);
        }
    }

    /**
     * Movement types 1-2: Horizontal oscillation.
     */
    private void applyHorizontalOscillation(int oscOffset, int amplitude) {
        int oscValue = OscillationManager.getByte(oscOffset) & 0xFF;

        if (xFlip) {
            oscValue = -oscValue + amplitude;
        }

        x = baseX - oscValue;
        y = baseY;
    }

    /**
     * Movement types 3-4: Vertical oscillation.
     */
    private void applyVerticalOscillation(int oscOffset, int amplitude) {
        int oscValue = OscillationManager.getByte(oscOffset) & 0xFF;

        if (xFlip) {
            oscValue = -oscValue + amplitude;
        }

        x = baseX;
        y = baseY - oscValue;
    }

    /**
     * Movement type 5: Trigger on standing.
     */
    private void applyTriggerFall(boolean standing) {
        int oscValue = OscillationManager.getByte(0) & 0xFF;
        y = baseY + (oscValue >> 1);
        x = baseX;

        if (standing) {
            moveType = 6;
            yFixed = y << 8;
        }
    }

    /**
     * Movement type 6: Falling platform.
     */
    private void applyFalling() {
        yFixed += yVel;
        y = yFixed >> 8;
        yVel += 8;
        x = baseX;

        Camera camera = Camera.getInstance();
        int maxY = camera != null ? camera.getMaxY() + 224 : baseY + 500;

        if (y > maxY) {
            moveType = 5;
            yVel = 0;
            y = baseY;
            yFixed = baseY << 8;
        }
    }

    /**
     * Movement type 7: Bouncy platform.
     */
    private void applyBouncy(boolean standing) {
        x = baseX;

        if (bounceAccel == 0) {
            if (standing) {
                bounceAccel = 8;
            }
            return;
        }

        yFixed += yVel;
        y = (yFixed >> 8) & 0x7FF;

        if (yVel == 0x2A8) {
            bounceAccel = -bounceAccel;
        }

        yVel += bounceAccel;

        if (yVel == 0) {
            moveType = 0;
            bounceAccel = 0;
            y = baseY;
            yFixed = baseY << 8;
        }
    }

    /**
     * Movement types 8-11: Circular/square motion.
     * Each type uses a different oscillator with different radius but synchronized timing.
     * Multiple Object 0x6B instances at the same location with types 8, 9, 10, 11
     * create the visual effect of blocks moving at different rates.
     */
    private void applyCircularMotion(int typeIndex) {
        int radius = CIRCULAR_RADII[typeIndex];
        int oscOffset = 0x28 + (typeIndex * 4);

        int d0 = OscillationManager.getByte(oscOffset) & 0xFF;
        if (typeIndex == 0) {
            d0 = d0 >> 1;
        }
        int d3 = OscillationManager.getWord(oscOffset + 2);

        // Advance quadrant when delta crosses zero
        if (d3 == 0) {
            flipState = (flipState + 1) & 0x03;
        }

        // Position calculation based on current quadrant
        switch (flipState & 0x03) {
            case 0 -> {
                x = baseX - radius + d0;
                y = baseY - radius;
            }
            case 1 -> {
                x = baseX + radius;
                y = baseY + radius - d0 - 1;
            }
            case 2 -> {
                x = baseX + radius - d0 - 1;
                y = baseY + radius;
            }
            case 3 -> {
                x = baseX - radius;
                y = baseY - radius + d0;
            }
        }
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

    private void appendDebug(List<GLCommand> commands) {
        int halfWidth = widthPixels + 0x0B;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - yRadius;
        int bottom = y + yRadius + 1;

        appendLine(commands, left, top, right, top);
        appendLine(commands, right, top, right, bottom);
        appendLine(commands, right, bottom, left, bottom);
        appendLine(commands, left, bottom, left, top);

        // Draw center cross
        appendLine(commands, x - 4, y, x + 4, y);
        appendLine(commands, x, y - 4, x, y + 4);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.8f, 0.2f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.8f, 0.2f, x2, y2, 0, 0));
    }
}
