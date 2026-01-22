package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.game.sonic2.OscillationManager;
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
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 19 - Moving platform from CPZ, OOZ and WFZ.
 * Implements all movement behaviors from the disassembly (Obj19).
 *
 * Subtype structure:
 * - Bits 0-3: Movement type (0-F)
 * - Bits 4-7: Size/frame index lookup (>>3 & 0x1E)
 *
 * Movement types:
 * - 0: Horizontal oscillation (amplitude $40)
 * - 1: Horizontal oscillation (amplitude $60)
 * - 2: Vertical oscillation (amplitude $80)
 * - 3: Trigger on standing (increments subtype)
 * - 4: Auto rise with oscillation
 * - 5: Stationary (no movement)
 * - 6,7: Continuous rise
 * - 8-B: Circular motion (variants)
 * - C-F: Circular motion reversed (variants)
 */
public class CPZPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {
    private static final Logger LOGGER = Logger.getLogger(CPZPlatformObjectInstance.class.getName());

    // Subtype properties: width_pixels, mapping_frame (from
    // Obj19_SubtypeProperties)
    private static final int[][] SUBTYPE_PROPERTIES = {
            { 0x20, 0 }, // Index 0: 32px width, frame 0
            { 0x18, 1 }, // Index 1: 24px width, frame 1
            { 0x40, 2 }, // Index 2: 64px width, frame 2
            { 0x20, 3 }, // Index 3: 32px width, frame 3
    };

    private static final int HALF_HEIGHT = 0x11; // d3 = $11 in Obj19_Main

    private int x;
    private int y;
    private int baseX; // objoff_30
    private int baseY; // objoff_32
    private int widthPixels;
    private int mappingFrame;
    private int moveType;
    private int yVel;
    private boolean xFlip;
    private ObjectSpawn dynamicSpawn;

    public CPZPlatformObjectInstance(ObjectSpawn spawn, String name) {
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
        // Apply movement based on subtype
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

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_PLATFORM);
        if (renderer == null || !renderer.isReady()) {
            appendDebug(commands);
            return;
        }

        boolean hFlip = xFlip;
        boolean vFlip = false;

        renderer.drawFrameIndex(mappingFrame, x, y, hFlip, vFlip);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return new SolidObjectParams(widthPixels, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Platform state is driven via ObjectManager standing checks.
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    private void init() {
        // Extract size index from subtype bits 4-7: (subtype >> 3) & 0x1E
        int sizeIndex = (spawn.subtype() >> 3) & 0x1E;
        sizeIndex /= 2; // Convert to table index
        if (sizeIndex < 0) {
            sizeIndex = 0;
        }
        if (sizeIndex >= SUBTYPE_PROPERTIES.length) {
            sizeIndex = SUBTYPE_PROPERTIES.length - 1;
        }

        widthPixels = SUBTYPE_PROPERTIES[sizeIndex][0];
        mappingFrame = SUBTYPE_PROPERTIES[sizeIndex][1];

        // Movement type from bits 0-3
        moveType = spawn.subtype() & 0x0F;

        // Store base positions for oscillation reference
        baseX = spawn.x();
        baseY = spawn.y();
        x = baseX;
        y = baseY;

        // X-flip from status/render flags
        xFlip = (spawn.renderFlags() & 0x1) != 0;

        // Special initialization for subtypes 3 and 7
        // From disassembly: if subtype is 3 or 7 and x-flipped, subtract $C0 from y_pos
        if ((moveType == 3 && xFlip) || moveType == 7) {
            y -= 0xC0;
            baseY -= 0xC0;
        }

        yVel = 0;

        refreshDynamicSpawn();
    }

    /**
     * Applies movement based on movement type (subtype & 0x0F).
     * Ported from Obj19_Move and Obj19_MoveRoutine1-8.
     */
    private void applyMovement(AbstractPlayableSprite player) {
        switch (moveType) {
            case 0 -> applyHorizontalOscillation(0x08, 0x40); // Oscillating_Data+8, amplitude $40
            case 1 -> applyHorizontalOscillation(0x0C, 0x60); // Oscillating_Data+$C, amplitude $60
            case 2 -> applyVerticalOscillation(0x1C, 0x80); // Oscillating_Data+$1C, amplitude $80
            case 3 -> applyTriggerOnStanding();
            case 4 -> applyAutoRise(true);
            case 5 -> {
                /* Stationary - no movement */ }
            case 6, 7 -> applyAutoRise(false);
            case 8, 9, 0xA, 0xB -> applyCircularMotion(moveType, false);
            case 0xC, 0xD, 0xE, 0xF -> applyCircularMotion(moveType, true);
        }
    }

    /**
     * Movement routine 1 & 2: Horizontal oscillation.
     * x_pos = objoff_30 - oscillation_value (adjusted for x-flip)
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
     * Movement routine 3: Vertical oscillation.
     * y_pos = objoff_32 - oscillation_value (adjusted for x-flip)
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
     * Movement routine 4: Trigger on standing.
     * When player stands on platform, increment subtype (move to next routine).
     */
    private void applyTriggerOnStanding() {
        if (isStanding()) {
            moveType = (moveType + 1) & 0x0F;
        }
    }

    /**
     * Movement routine 5 & 6: Auto rise with or without subtype increment.
     * Moves toward y - $60, accelerates at 8 per frame.
     */
    private void applyAutoRise(boolean incrementSubtype) {
        // ObjectMove equivalent: apply velocity
        y += yVel >> 8;

        // Calculate target (objoff_32 - $60)
        int targetY = baseY - 0x60;

        // Determine acceleration direction
        int accel = 8;
        if (y < targetY) {
            accel = -8;
        }

        yVel += accel;

        // For routine 5: increment subtype when velocity becomes 0
        if (incrementSubtype && yVel == 0) {
            moveType = (moveType + 1) & 0x0F;
        }
    }

    /**
     * Movement routine 7 & 8: Circular motion.
     * Uses Oscillating_Data+$38 and +$3C for X and Y components.
     *
     * Bit flags in moveType:
     * - Bit 1: Swap X/Y and negate X
     * - Bit 2: Negate both X and Y
     *
     * Routine 8 (reversed) also negates X before adding to position.
     */
    private void applyCircularMotion(int subtype, boolean reversed) {
        // Get circular motion components
        int d1 = OscillationManager.getByte(0x38) - 0x40; // Sign-extend
        int d2 = OscillationManager.getByte(0x3C) - 0x40;

        // Sign-extend from byte to signed value
        d1 = (byte) d1;
        d2 = (byte) d2;

        // Apply bit 2: negate both
        if ((subtype & 0x04) != 0) {
            d1 = -d1;
            d2 = -d2;
        }

        // Apply bit 1: swap and negate X
        if ((subtype & 0x02) != 0) {
            d1 = -d1;
            int temp = d1;
            d1 = d2;
            d2 = temp;
        }

        // Routine 8 (reversed): additional negation of d1
        if (reversed) {
            d1 = -d1;
        }

        x = baseX + d1;
        y = baseY + d2;
    }

    private boolean isStanding() {
        LevelManager manager = LevelManager.getInstance();
        if (manager == null || manager.getObjectManager() == null) {
            return false;
        }
        return manager.getObjectManager().isRidingObject(this);
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
        int halfWidth = widthPixels;
        int halfHeight = HALF_HEIGHT;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        appendLine(commands, left, top, right, top);
        appendLine(commands, right, top, right, bottom);
        appendLine(commands, right, bottom, left, bottom);
        appendLine(commands, left, bottom, left, top);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.8f, 0.5f, 0.2f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.8f, 0.5f, 0.2f, x2, y2, 0, 0));
    }
}
