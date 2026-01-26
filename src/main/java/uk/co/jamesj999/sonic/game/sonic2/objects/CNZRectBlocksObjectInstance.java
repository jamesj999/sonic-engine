package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Rect Blocks / Flashing Blocks (Object 0xD2).
 * <p>
 * "Caterpillar" platforms that appear and disappear in CNZ.
 * The blocks animate around a rectangular path and periodically become invisible,
 * causing any player standing on them to fall.
 * <p>
 * <b>Behavior:</b>
 * <ul>
 *   <li>16 animation frames showing blocks moving around a rectangular path</li>
 *   <li>Each animation frame lasts 16 ticks</li>
 *   <li>After completing all frames, object becomes invisible for (subtype * 16) ticks</li>
 *   <li>When going invisible, players standing on the object are set to air state</li>
 *   <li>Each frame has different X/Y offset and collision dimensions</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 58068-58201 (ObjD2)
 */
public class CNZRectBlocksObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    /**
     * Animation frame data from byte_2B654.
     * Each entry: {xOffset, yOffset, width, height}
     * Values are signed bytes for offset, unsigned for width/height.
     */
    private static final int[][] FRAME_DATA = {
            {-0x28,  0x18, 0x08, 0x08}, // Frame 0
            {-0x28,  0x10, 0x08, 0x10}, // Frame 1
            {-0x28,  0x08, 0x08, 0x18}, // Frame 2
            {-0x28,  0x00, 0x08, 0x20}, // Frame 3
            {-0x20,  0x00, 0x10, 0x20}, // Frame 4
            {-0x18, -0x08, 0x18, 0x18}, // Frame 5
            {-0x10, -0x10, 0x20, 0x10}, // Frame 6
            {-0x08, -0x18, 0x28, 0x08}, // Frame 7
            { 0x08, -0x18, 0x28, 0x08}, // Frame 8
            { 0x10, -0x10, 0x20, 0x10}, // Frame 9
            { 0x18, -0x08, 0x18, 0x18}, // Frame 10
            { 0x20,  0x00, 0x10, 0x20}, // Frame 11
            { 0x28,  0x00, 0x08, 0x20}, // Frame 12
            { 0x28,  0x08, 0x08, 0x18}, // Frame 13
            { 0x28,  0x10, 0x08, 0x10}, // Frame 14
            { 0x28,  0x18, 0x08, 0x08}, // Frame 15
    };

    private static final int FRAMES_PER_TICK = 16; // objoff_3A reset value + 1

    // Base position (stored during init, from spawn location)
    private final int baseX;
    private final int baseY;

    // Animation state
    private int mappingFrame = 0;
    private int animTimer = FRAMES_PER_TICK - 1; // Counts down from 15 to 0
    private int invisibleTimer; // Counts down, object invisible when > 0

    // Current computed position
    private int currentX;
    private int currentY;

    // Track if player 1 was standing on us last frame (for kick-off logic)
    private boolean player1Standing = false;

    public CNZRectBlocksObjectInstance(ObjectSpawn spawn, String name) {
        // Use arbitrary base dimensions - actual dimensions come from frame data
        super(spawn, name, 8, 8, 0.8f, 0.2f, 0.8f, false);
        this.baseX = spawn.x();
        this.baseY = spawn.y();

        // Initialize invisible timer from subtype: subtype * 16
        int subtype = spawn.subtype() & 0xFF;
        this.invisibleTimer = subtype << 4;

        // Compute initial position
        updatePosition();
    }

    /**
     * Updates the current X/Y position based on animation frame and flip flags.
     * Based on loc_2B60C in disassembly.
     */
    private void updatePosition() {
        int[] frameData = FRAME_DATA[mappingFrame];
        int xOffset = frameData[0];
        int yOffset = frameData[1];

        // Apply X flip if set (bit 0 of status/render flags)
        if (isFlippedHorizontal()) {
            xOffset = -xOffset;
        }

        currentX = baseX + xOffset;
        currentY = baseY + yOffset;
    }

    /**
     * Returns true if the object is currently invisible (during cooldown period).
     */
    public boolean isInvisible() {
        return invisibleTimer > 0;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Phase 1: If invisible, count down and skip everything else
        if (invisibleTimer > 0) {
            invisibleTimer--;
            return;
        }

        // Phase 2: Animation timer countdown
        animTimer--;
        if (animTimer < 0) {
            // Reset timer and advance frame
            animTimer = FRAMES_PER_TICK - 1;
            mappingFrame = (mappingFrame + 1) & 0x0F;

            // If we wrapped back to frame 0, become invisible
            if (mappingFrame == 0) {
                // Set invisible timer from subtype
                int subtype = spawn.subtype() & 0xFF;
                invisibleTimer = subtype << 4;

                // Kick off any standing player (handled in onSolidContact)
                // The player1Standing flag will trigger the kick-off
                if (player1Standing && player != null) {
                    kickPlayerOff(player);
                }

                // Reset position to base for next visible cycle
                updatePosition();
                return;
            }
        }

        // Update position for current frame
        updatePosition();
    }

    /**
     * Kicks the player off the platform when it disappears.
     * Based on loc_2B5B8 - loc_2B5E0 in disassembly.
     */
    private void kickPlayerOff(AbstractPlayableSprite player) {
        // Clear standing-on-object status and set in-air status
        // ROM: bclr #status.player.on_object / bset #status.player.in_air
        player.setAir(true);
        player1Standing = false;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }

        // Track standing state for kick-off logic
        player1Standing = contact.standing();
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        // Not solid when invisible
        return !isInvisible();
    }

    @Override
    public SolidObjectParams getSolidParams() {
        int[] frameData = FRAME_DATA[mappingFrame];
        int width = frameData[2];
        int height = frameData[3];

        // Match disassembly: d1 = width + 11, d2 = height, d3 = height + 1
        int d1 = width + 0x0B;
        int d2 = height;
        int d3 = height + 1;

        // Calculate offset from spawn position to current position
        int offsetX = currentX - baseX;
        int offsetY = currentY - baseY;

        return new SolidObjectParams(d1, d2, d3, offsetX, offsetY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Don't render when invisible
        if (isInvisible()) {
            return;
        }

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_RECT_BLOCKS);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }

        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        // Render at current computed position (not base spawn position)
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    protected int getHalfWidth() {
        int[] frameData = FRAME_DATA[mappingFrame];
        return frameData[2];
    }

    @Override
    protected int getHalfHeight() {
        int[] frameData = FRAME_DATA[mappingFrame];
        return frameData[3];
    }

    /**
     * Override to use current computed position instead of base spawn position.
     */
    public int getCurrentX() {
        return currentX;
    }

    /**
     * Override to use current computed position instead of base spawn position.
     */
    public int getCurrentY() {
        return currentY;
    }
}
