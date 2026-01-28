package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
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

/**
 * EggPrison button component - separate object with full solid collision.
 *
 * In the ROM (s2.asm loc_3F354), the button is a child object with routine 4
 * that provides full SolidObject collision (width=$1B=27px, height=8px).
 * This matches that ROM structure by making the button a separate object
 * with its own collision system.
 *
 * Position: 40 pixels above parent capsule Y
 * Collision: Width=54px (±27), Height=16px (±8)
 * Behavior: Depresses 8 pixels when player lands on it, triggers parent
 */
public class EggPrisonButtonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    // ROM constants from s2.asm
    private static final int BUTTON_HALF_WIDTH = 0x1B;  // 27 pixels
    private static final int BUTTON_HALF_HEIGHT = 8;     // 8 pixels
    private static final int BUTTON_OFFSET_Y = -40;      // 40px above parent body
    private static final int BUTTON_DEPRESS_DISTANCE = 8; // How far button moves when pressed

    // Button state
    private final int baseY;           // Original Y position (40px above parent)
    private int currentY;              // Current Y position (depresses when triggered)
    private boolean triggered;         // Has button been pressed?
    private EggPrisonObjectInstance parent; // Parent capsule to notify

    /**
     * Create button instance attached to parent capsule.
     *
     * @param spawn Spawn data (uses parent X, Y position)
     * @param parent Parent capsule to notify when triggered
     */
    public EggPrisonButtonObjectInstance(ObjectSpawn spawn, EggPrisonObjectInstance parent) {
        super(spawn, "EggPrison Button");
        this.parent = parent;

        // Position button 40 pixels above parent body
        this.baseY = spawn.y() + BUTTON_OFFSET_Y;
        this.currentY = baseY;
        this.triggered = false;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Button is purely reactive - no autonomous updates
        // Collision system handles player landing detection via SolidObjectProvider
    }

    // ========================================================================================
    // SolidObjectProvider Implementation
    // ========================================================================================

    @Override
    public SolidObjectParams getSolidParams() {
        return new SolidObjectParams(
            BUTTON_HALF_WIDTH,          // halfWidth = 27px
            BUTTON_HALF_HEIGHT,         // airHalfHeight = 8px
            BUTTON_HALF_HEIGHT,         // groundHalfHeight = 8px
            0,                          // offsetX
            currentY - spawn.y()        // offsetY (button moves down 8px when triggered)
        );
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite sprite) {
        return true; // Solid for all players
    }

    // ========================================================================================
    // SolidObjectListener Implementation
    // ========================================================================================

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (!triggered && contact.standing() && player.getYSpeed() >= 0) {
            // Button triggered - depress and notify parent
            triggered = true;
            currentY = baseY + BUTTON_DEPRESS_DISTANCE; // Move down 8 pixels

            if (parent != null) {
                parent.onButtonTriggered();
            }
        }
    }

    // ========================================================================================
    // Rendering
    // ========================================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getEggPrisonRenderer()
                : null;

        if (renderer == null || !renderer.isReady()) {
            renderPlaceholder(commands);
            return;
        }

        // Button uses frame 4 from capsule mappings
        int frameIndex = 4; // FRAME_BUTTON from EggPrisonObjectInstance

        // Render button sprite at current Y position
        renderer.drawFrameIndex(frameIndex, spawn.x(), currentY, false, false);
    }

    private void renderPlaceholder(List<GLCommand> commands) {
        int x = spawn.x();
        int y = currentY;
        int hw = BUTTON_HALF_WIDTH;
        int hh = BUTTON_HALF_HEIGHT;

        int left = x - hw;
        int right = x + hw;
        int top = y - hh;
        int bottom = y + hh;

        appendLine(commands, left, top, right, top, 0.9f, 0.2f, 0.2f);
        appendLine(commands, right, top, right, bottom, 0.9f, 0.2f, 0.2f);
        appendLine(commands, right, bottom, left, bottom, 0.9f, 0.2f, 0.2f);
        appendLine(commands, left, bottom, left, top, 0.9f, 0.2f, 0.2f);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5); // Priority 5 per ROM
    }

    // ========================================================================================
    // Lifecycle
    // ========================================================================================

    /**
     * Detach from parent and destroy button (called when parent is destroyed).
     */
    public void destroyButton() {
        this.parent = null;
        setDestroyed(true);
    }

    @Override
    public String toString() {
        return String.format("EggPrisonButton[x=%d, y=%d, triggered=%b]",
            spawn.x(), currentY, triggered);
    }
}
