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
 * MTZ Platform (Object 0x6B) - Multi-purpose platform used in Metropolis Zone and Chemical Plant Zone.
 * <p>
 * Implements 12 distinct movement subtypes from the disassembly (s2.asm lines 53860-54159):
 * <ul>
 *   <li>Type 0: Stationary</li>
 *   <li>Type 1: Horizontal oscillation (amplitude 0x40)</li>
 *   <li>Type 2: Horizontal oscillation (amplitude 0x80)</li>
 *   <li>Type 3: Vertical oscillation (amplitude 0x40)</li>
 *   <li>Type 4: Vertical oscillation (amplitude 0x80)</li>
 *   <li>Type 5: Trigger on standing (transitions to type 6)</li>
 *   <li>Type 6: Falling platform</li>
 *   <li>Type 7: Bouncy platform</li>
 *   <li>Types 8-11: Circular motion with varying radii</li>
 * </ul>
 * <p>
 * Subtype structure:
 * <ul>
 *   <li>Bits 0-3: Movement type (0-11)</li>
 *   <li>Bits 2-4: Property index (size/collision selection)</li>
 * </ul>
 */
public class MTZPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(MTZPlatformObjectInstance.class.getName());

    // Subtype properties from Obj6B_Properties (line 53879-53881)
    // Format: {width_pixels, y_radius, mapping_frame}
    private static final int[][] SUBTYPE_PROPERTIES = {
            {32, 12, 1}, // Index 0: wide platform (32px half-width, 12px y_radius, frame 1)
            {16, 16, 0}, // Index 1: small block (16px half-width, 16px y_radius, frame 0)
    };

    // Circular motion radii for types 8-11
    private static final int[] CIRCULAR_RADII = {0x10, 0x30, 0x50, 0x70};

    // Position tracking
    private int x;
    private int y;
    private int baseX;      // objoff_34 - Original X position
    private int baseY;      // objoff_30 - Original Y position
    private int yFixed;     // 16.8 fixed-point Y for falling/bouncing (objoff_2C)
    private int yVel;       // Y velocity for falling/bouncing

    // Subtype configuration
    private int moveType;       // Movement type (0-11)
    private int widthPixels;    // Collision half-width
    private int yRadius;        // Collision half-height
    private int mappingFrame;   // Sprite frame index

    // State tracking
    private int flipState;      // objoff_2E - Circular motion quadrant (0-3)
    private int bounceAccel;    // objoff_38 - Bounce direction (+8 or -8)
    private boolean xFlip;      // X-flip from render flags

    // Zone handling
    private boolean isCpz;
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
    public void update(int frameCounter, AbstractPlayableSprite player) {
        applyMovement(player);
        refreshDynamicSpawn();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            appendDebug(commands);
            return;
        }

        if (isCpz) {
            // CPZ uses the stair block renderer (single piece)
            PatternSpriteRenderer renderer = renderManager.getCpzStairBlockRenderer();
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, x, y, xFlip, false);
                return;
            }
        }

        // Fall back to debug rendering for MTZ (multi-piece level art not yet integrated)
        appendDebug(commands);
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
        return true; // Platform only solid from above
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Platform state is driven via SolidObjectManager standing checks.
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    private void init() {
        // Zone detection
        LevelManager manager = LevelManager.getInstance();
        int zone = manager != null ? manager.getCurrentZone() : -1;
        isCpz = (zone == 2); // chemical_plant_zone

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
            moveType = 0; // Undefined types become stationary
        }

        // X-flip from render flags
        xFlip = (spawn.renderFlags() & 0x01) != 0;
        flipState = xFlip ? 1 : 0;

        // Special init for circular motion (types 8-11)
        if (moveType >= 8 && moveType <= 11) {
            int oscOffset = 0x2A + ((moveType - 8) * 4);
            int oscWord = OscillationManager.getWord(oscOffset);
            if (oscWord < 0) {
                flipState ^= 1; // Toggle x_flip
            }
        }

        yVel = 0;
        bounceAccel = 0;

        refreshDynamicSpawn();
    }

    /**
     * Applies movement based on movement type (subtype & 0x0F).
     * Ported from Obj6B_Move and Obj6B_MoveRoutines (s2.asm lines 53938-54080).
     */
    private void applyMovement(AbstractPlayableSprite player) {
        switch (moveType) {
            case 0 -> { /* Stationary - no movement */ }
            case 1 -> applyHorizontalOscillation(0x08, 0x40); // Oscillating_Data+8, amplitude $40
            case 2 -> applyHorizontalOscillation(0x1C, 0x80); // Oscillating_Data+$1C, amplitude $80
            case 3 -> applyVerticalOscillation(0x08, 0x40);   // Oscillating_Data+8, amplitude $40
            case 4 -> applyVerticalOscillation(0x1C, 0x80);   // Oscillating_Data+$1C, amplitude $80
            case 5 -> applyTriggerFall();
            case 6 -> applyFalling();
            case 7 -> applyBouncy();
            case 8, 9, 10, 11 -> applyCircularMotion(moveType - 8);
        }
    }

    /**
     * Movement types 1-2: Horizontal oscillation.
     * x_pos = objoff_34 - oscillation_value (adjusted for x-flip)
     */
    private void applyHorizontalOscillation(int oscOffset, int amplitude) {
        int oscValue = OscillationManager.getByte(oscOffset);

        if (xFlip) {
            // Negate and add amplitude
            oscValue = -oscValue + amplitude;
        }

        x = baseX - oscValue;
    }

    /**
     * Movement types 3-4: Vertical oscillation.
     * y_pos = objoff_30 - oscillation_value (adjusted for x-flip)
     */
    private void applyVerticalOscillation(int oscOffset, int amplitude) {
        int oscValue = OscillationManager.getByte(oscOffset);

        if (xFlip) {
            // Negate and add amplitude
            oscValue = -oscValue + amplitude;
        }

        y = baseY - oscValue;
    }

    /**
     * Movement type 5: Trigger on standing.
     * Visual wobble using oscillation, transitions to type 6 (falling) when player stands on it.
     * ROM: Obj6B_Move_Type05 (s2.asm lines 53986-53998)
     */
    private void applyTriggerFall() {
        // Visual wobble: y = baseY + (oscillation >> 1)
        int oscValue = OscillationManager.getByte(0);
        y = baseY + (oscValue >> 1);

        if (isStanding()) {
            moveType = 6; // Transition to falling
        }
    }

    /**
     * Movement type 6: Falling platform.
     * Applies gravity (+8 per frame) and resets when below camera + 224.
     * ROM: Obj6B_Move_Type06 (s2.asm lines 54000-54020)
     */
    private void applyFalling() {
        // 16.8 fixed-point position update
        yFixed += yVel;
        y = yFixed >> 8;
        yVel += 8; // Gravity

        // Reset when below camera + 224
        Camera camera = Camera.getInstance();
        int maxY = camera != null ? camera.getMaxY() + 224 : baseY + 500;

        if (y > maxY) {
            moveType = 5; // Reset to trigger mode
            yVel = 0;
            y = baseY;
            yFixed = baseY << 8;
        }
    }

    /**
     * Movement type 7: Bouncy platform.
     * Bounces at velocity 0x2A8, reverses direction at peak.
     * ROM: Obj6B_Move_Type07 (s2.asm lines 54022-54062)
     */
    private void applyBouncy() {
        if (bounceAccel == 0) {
            if (isStanding()) {
                bounceAccel = 8; // Start bouncing
            }
            return;
        }

        yFixed += yVel;
        y = (yFixed >> 8) & 0x7FF; // Mask to prevent overflow

        if (yVel == 0x2A8) {
            bounceAccel = -bounceAccel; // Reverse at peak
        }

        yVel += bounceAccel;

        if (yVel == 0) {
            moveType = 0; // Reset to stationary
            bounceAccel = 0;
            y = baseY;
            yFixed = baseY << 8;
        }
    }

    /**
     * Movement types 8-11: Circular motion.
     * Uses oscillation data to move in a circle with varying radii.
     * ROM: Obj6B_Move_Type08 through Type0B (s2.asm lines 54064-54156)
     *
     * @param typeIndex 0-3 corresponding to types 8-11
     */
    private void applyCircularMotion(int typeIndex) {
        int radius = CIRCULAR_RADII[typeIndex];
        int oscOffset = 0x28 + (typeIndex * 4);

        // Get oscillation value (high byte) and delta (word at +2)
        int d0 = OscillationManager.getByte(oscOffset);
        // Only type 8 (typeIndex == 0) applies lsr #1 to the oscillation value
        if (typeIndex == 0) {
            d0 = d0 >> 1;
        }
        int d3 = OscillationManager.getWord(oscOffset + 2);

        // Advance quadrant when oscillation crosses zero
        if (d3 == 0) {
            flipState = (flipState + 1) & 0x03;
        }

        // Calculate position based on quadrant (from s2.asm lines 54109-54152)
        // Quadrants 1 and 2 use (radius - 1) in their calculations
        switch (flipState & 0x03) {
            case 0 -> {
                // d0 = d0 - radius; x = baseX + d0; y = baseY - radius
                x = baseX - radius + d0;
                y = baseY - radius;
            }
            case 1 -> {
                // x = baseX + radius; y = baseY + (radius - 1) - d0
                x = baseX + radius;
                y = baseY + radius - d0 - 1;
            }
            case 2 -> {
                // x = baseX + (radius - 1) - d0; y = baseY + radius
                x = baseX + radius - d0 - 1;
                y = baseY + radius;
            }
            case 3 -> {
                // x = baseX - radius; y = baseY + d0 - radius
                x = baseX - radius;
                y = baseY - radius + d0;
            }
        }
    }

    private boolean isStanding() {
        LevelManager manager = LevelManager.getInstance();
        if (manager == null || manager.getSolidObjectManager() == null) {
            return false;
        }
        return manager.getSolidObjectManager().isRidingObject(this);
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
        int halfHeight = yRadius;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        // Platform outline
        appendLine(commands, left, top, right, top);
        appendLine(commands, right, top, right, bottom);
        appendLine(commands, right, bottom, left, bottom);
        appendLine(commands, left, bottom, left, top);

        // Cross to indicate platform center
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
