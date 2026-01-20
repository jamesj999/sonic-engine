package uk.co.jamesj999.sonic.game.sonic2.objects;

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
 * Sideways Platform (Object 0x7A) - Horizontal moving platform from CPZ and MCZ.
 * <p>
 * This platform moves horizontally back and forth between two boundary points.
 * Some subtypes create linked pairs of platforms that toggle direction when they touch.
 * <p>
 * Based on Obj7A from the Sonic 2 disassembly.
 * <p>
 * Subtype structure (Obj7A_Properties):
 * - Subtype 0x00: Single platform, 1 platform total
 * - Subtype 0x06: Two linked platforms (40px spacing)
 * - Subtype 0x0C: Two linked platforms (80px spacing), starts moving left
 * - Subtype 0x12: Single platform, offset right
 */
public class SidewaysPformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(SidewaysPformObjectInstance.class.getName());

    // Platform collision dimensions (from disassembly)
    private static final int HALF_WIDTH = 0x18;  // 24 pixels
    private static final int HALF_HEIGHT = 8;

    // Subtype properties from Obj7A_Properties
    // Format: {totalChildren-1, originXOffset, parentXOffset, childXOffset}
    // Subtypes map: 0x00 -> index 0, 0x06 -> index 1, 0x0C -> index 2, 0x12 -> index 3
    private static final int[][] SUBTYPE_PROPERTIES = {
            {0, 0x68, -0x68, 0},    // subtype 0x00: Single platform, centered
            {1, 0xA8, -0xB0, 0x40}, // subtype 0x06: Two linked platforms
            {1, 0xE8, -0x80, 0x80}, // subtype 0x0C: Two linked platforms (wide)
            {0, 0x68, 0x67, 0}      // subtype 0x12: Single platform, offset right
    };

    // Position tracking
    private int x;
    private int y;
    private int minX;        // objoff_32 - Left boundary
    private int maxX;        // objoff_34 - Right boundary
    private int direction;   // objoff_36 - 0=moving right, 1=moving left

    // For linked platforms
    private SidewaysPformObjectInstance linkedPlatform;
    private boolean isChild;

    private ObjectSpawn dynamicSpawn;

    /**
     * Creates a new SidewaysPform instance.
     *
     * @param spawn the spawn data
     * @param name  the object name
     */
    public SidewaysPformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.isChild = false;
        init();
    }

    /**
     * Creates a child platform linked to a parent.
     *
     * @param spawn  the spawn data
     * @param name   the object name
     * @param parent the parent platform to link with
     */
    private SidewaysPformObjectInstance(ObjectSpawn spawn, String name, SidewaysPformObjectInstance parent) {
        super(spawn, name);
        this.isChild = true;
        this.linkedPlatform = parent;
        parent.linkedPlatform = this;
        initChild(parent);
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
        return new SolidObjectParams(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;  // PlatformObject - only solid from top
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Platform state is driven via SolidObjectManager standing checks.
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        applyMovement();
        refreshDynamicSpawn();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        PatternSpriteRenderer renderer = null;

        if (renderManager != null) {
            renderer = renderManager.getSidewaysPformRenderer();
        }

        if (renderer != null && renderer.isReady()) {
            // Frame 0 is the 48x16 platform
            renderer.drawFrameIndex(0, x, y, false, false);
        } else {
            appendDebug(commands);
        }
    }

    private void init() {
        // Get subtype properties index
        int propsIndex = getPropertiesIndex(spawn.subtype());
        int[] props = SUBTYPE_PROPERTIES[propsIndex];

        int totalChildren = props[0];      // Number of additional platforms (0 or 1)
        int originXOffset = props[1];      // Movement range (half of total range)
        int parentXOffset = props[2];      // Offset for parent starting position

        // Calculate movement boundaries (relative to spawn position)
        // Assembly: minX = spawn_x - originXOffset, maxX = spawn_x + originXOffset
        minX = spawn.x() - originXOffset;
        maxX = spawn.x() + originXOffset;

        // Initial position: spawn_x + parentXOffset
        x = spawn.x() + parentXOffset;
        y = spawn.y();

        // Direction: subtype 0x0C starts moving left (direction=1), others start moving right
        direction = (spawn.subtype() == 0x0C) ? 1 : 0;

        refreshDynamicSpawn();

        // Create child platform if needed
        if (totalChildren > 0 && !isChild) {
            createChildPlatform(props[3]);  // childXOffset
        }
    }

    private void initChild(SidewaysPformObjectInstance parent) {
        // Get subtype properties index (same as parent)
        int propsIndex = getPropertiesIndex(spawn.subtype());
        int[] props = SUBTYPE_PROPERTIES[propsIndex];

        int childXOffset = props[3];

        // Child shares same movement boundaries as parent
        minX = parent.minX;
        maxX = parent.maxX;

        // Child starts offset from parent
        x = parent.x + childXOffset;
        y = parent.y;

        // Child always starts with default direction (0 = moving right)
        // Only the parent of subtype 0x0C gets direction=1; child is fresh allocation
        direction = 0;

        refreshDynamicSpawn();
    }

    private void createChildPlatform(int childXOffset) {
        // Create spawn data for child platform
        ObjectSpawn childSpawn = new ObjectSpawn(
                spawn.x() + childXOffset,
                spawn.y(),
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                false,  // Child is not respawn tracked
                spawn.rawYWord()
        );

        // Create child platform and register it
        SidewaysPformObjectInstance child = new SidewaysPformObjectInstance(
                childSpawn, name + "_child", this);

        // Add child to object manager
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager != null && levelManager.getObjectManager() != null) {
            levelManager.getObjectManager().addDynamicObject(child);
        }
    }

    /**
     * Applies horizontal movement at 1 pixel/frame.
     * Direction toggles at boundaries.
     * Linked platforms toggle when they touch (their collision boxes overlap).
     */
    private void applyMovement() {
        if (direction == 0) {
            // Moving right
            x++;
            if (x >= maxX) {
                x = maxX;
                direction = 1;
            }
        } else {
            // Moving left
            x--;
            if (x <= minX) {
                x = minX;
                direction = 0;
            }
        }

        // Check for linked platform collision (direction toggle)
        if (linkedPlatform != null && !isChild) {
            // Platforms touch when (this.x + 0x18) meets (linked.x - 0x18)
            // i.e., their collision boxes overlap
            int thisRight = x + HALF_WIDTH;
            int linkedLeft = linkedPlatform.x - HALF_WIDTH;

            if (thisRight >= linkedLeft && direction == 0 && linkedPlatform.direction == 1) {
                // Platforms are touching while moving towards each other
                direction = 1;
                linkedPlatform.direction = 0;
            }
        }
    }

    /**
     * Gets the properties table index for a subtype.
     */
    private int getPropertiesIndex(int subtype) {
        // Subtypes map to indices: 0x00->0, 0x06->1, 0x0C->2, 0x12->3
        return switch (subtype) {
            case 0x00 -> 0;
            case 0x06 -> 1;
            case 0x0C -> 2;
            case 0x12 -> 3;
            default -> 0;  // Default to first entry
        };
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
        int left = x - HALF_WIDTH;
        int right = x + HALF_WIDTH;
        int top = y - HALF_HEIGHT;
        int bottom = y + HALF_HEIGHT;

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
                0.4f, 0.8f, 0.6f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.4f, 0.8f, 0.6f, x2, y2, 0, 0));
    }
}
