package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.WaterSystem;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ARZ Bubble (Object 0x24 routine 4) - Rising breathable bubble.
 * Spawned by BubbleGeneratorObjectInstance.
 * <p>
 * Based on Obj24_Rising from s2.asm (lines 44868-44987).
 * <p>
 * Physics:
 * - Rises upward at -0x88 velocity (8.8 fixed point = ~0.53 pixels/frame)
 * - Horizontal sine-wave wobble using 128-byte lookup table
 * - Pops when reaching water surface
 * <p>
 * Player Interaction:
 * - Large bubbles (mapping_frame >= 6) can be breathed by player
 * - When touched, restores player's air and plays inhaling sound
 */
public class BubbleObjectInstance extends AbstractObjectInstance {

    // Rise velocity in 8.8 fixed point (-0x88 = ~-0.53 pixels/frame upward)
    private static final int RISE_VELOCITY = -0x88;

    // Wobble data table (128 bytes, signed) - matches ROM Obj24_WobbleData
    // This creates a smooth horizontal oscillation as the bubble rises
    private static final int[] WOBBLE_DATA = {
        0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2,
        2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2,
        2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0,
        0, -1, -1, -1, -1, -1, -2, -2, -2, -2, -2, -3, -3, -3, -3, -3,
        -3, -3, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4,
        -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -3,
        -3, -3, -3, -3, -3, -3, -2, -2, -2, -2, -2, -1, -1, -1, -1, -1
    };

    // Touch collision half-sizes for breathable bubbles (ROM Touch_Sizes entry)
    private static final int COLLISION_HALF_WIDTH = 16;
    private static final int COLLISION_HALF_HEIGHT = 16;

    // Animation frame for large breathable bubble (frame >= 6 can be breathed)
    private static final int BREATHABLE_FRAME_THRESHOLD = 6;

    // Position as 16.16 fixed point
    private int posX16;
    private int posY16;

    // Base X position for wobble calculation
    private int baseX;

    // Wobble angle (0-255, wraps around) - ROM uses only low byte
    private int wobbleAngle;

    // Current display position
    private int displayX;
    private int displayY;

    // Bubble size/type (determines frame and breathability)
    private int bubbleSize;

    // Mapping frame for rendering
    private int mappingFrame;

    // Animation timer for frame changes
    private int animTimer;

    // Whether this bubble has been breathed (collected)
    private boolean breathed;

    /**
     * Creates a rising bubble at the specified position.
     *
     * @param x           X position (world coordinates)
     * @param y           Y position (world coordinates)
     * @param bubbleSize  Size: 0=tiny, 1=small, 2=medium, 3-5=large (breathable)
     * @param wobbleAngle Initial wobble angle (0-255)
     */
    public BubbleObjectInstance(int x, int y, int bubbleSize, int wobbleAngle) {
        super(createDummySpawn(x, y), "Bubble");

        // Store position as 16.16 fixed point
        this.posX16 = x << 16;
        this.posY16 = y << 16;
        this.baseX = x;

        this.wobbleAngle = wobbleAngle & 0xFF;
        this.bubbleSize = bubbleSize;
        this.breathed = false;

        // Determine initial mapping frame based on size
        // ROM: Obj24_ChooseBubble selects frame 0-5 based on random
        // Frames 0-1: tiny bubbles, 2-3: small, 4-5: medium
        // Large breathable bubbles use frames 6+ but we start at lower frames
        this.mappingFrame = Math.min(bubbleSize, 5);
        this.animTimer = 0;

        // Initial display position
        this.displayX = x;
        this.displayY = y;
    }

    private static ObjectSpawn createDummySpawn(int x, int y) {
        return new ObjectSpawn(x, y, 0x24, 0, 0, false, 0);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (breathed) {
            setDestroyed(true);
            return;
        }

        // Update wobble angle (ROM: addq.b #1,objoff_32(a0))
        wobbleAngle = (wobbleAngle + 1) & 0xFF;

        // Apply rise velocity to Y position (16.16 fixed point)
        // ROM: move.w y_vel(a0),d0 / ext.l d0 / asl.l #8,d0 / add.l d0,y_pos(a0)
        posY16 += RISE_VELOCITY << 8;

        // Calculate display Y from 16.16 fixed point
        displayY = posY16 >> 16;

        // Apply wobble to X position
        // ROM: move.b objoff_32(a0),d0 / lea Obj24_WobbleData,a1 / move.b (a1,d0.w),d0
        //      ext.w d0 / add.w objoff_30(a0),d0 / move.w d0,x_pos(a0)
        int wobbleIndex = wobbleAngle & 0x7F; // 128-entry table
        int wobbleOffset = WOBBLE_DATA[wobbleIndex];
        displayX = baseX + wobbleOffset;

        // Check if reached water surface (bubble pops)
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager != null && levelManager.getCurrentLevel() != null) {
            WaterSystem waterSystem = WaterSystem.getInstance();
            int zoneId = levelManager.getCurrentLevel().getZoneIndex();
            int actId = levelManager.getCurrentAct();

            if (waterSystem.hasWater(zoneId, actId)) {
                int waterY = waterSystem.getWaterLevelY(zoneId, actId);
                if (displayY <= waterY) {
                    // Bubble reached surface - pop it
                    setDestroyed(true);
                    return;
                }
            }
        }

        // Check for player collision if this is a breathable bubble
        if (player != null && isBreathable()) {
            checkPlayerCollision(player);
        }

        // Update animation (bubble grows slightly over time for large bubbles)
        animTimer++;
        if (animTimer >= 8 && mappingFrame < 5 && bubbleSize >= 3) {
            // Large bubbles grow to breathable size
            mappingFrame++;
            animTimer = 0;
        }
    }

    /**
     * Returns true if this bubble is large enough to be breathed.
     */
    private boolean isBreathable() {
        // ROM: cmpi.b #6,mapping_frame(a0) / blo.s Obj24_RisingNoCollision
        return mappingFrame >= BREATHABLE_FRAME_THRESHOLD || bubbleSize >= 3;
    }

    /**
     * Checks for collision with the player and handles air restoration.
     * ROM uses an asymmetric collision box: ±16 horizontal, downward-only from bubble Y.
     * <p>
     * ROM collision logic (lines 44952-44965):
     * X: (bubble_x - 16) <= player_x <= (bubble_x + 16)
     * Y: bubble_y < player_y < (bubble_y + 16)
     * <p>
     * The player's center must be BELOW the bubble (bubble above player),
     * but within 16 pixels. This is a downward-only collision box.
     */
    private void checkPlayerCollision(AbstractPlayableSprite player) {
        // Only interact if player is underwater
        if (!player.isInWater()) {
            return;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // ROM asymmetric box collision:
        // X: ±16 from bubble center (move.w x_pos(a0),d1 / subi.w #$10,d1 ... addi.w #$20,d1)
        // Y: downward-only - player must be BELOW bubble but within 16px
        //    ROM: cmp.w y_pos(a0),d1 / blo.s (bubble_y < player_y required)
        //    ROM: addi.w #$10,d1 / cmp.w d1,d0 (player_y < bubble_y + 16)
        int bubbleLeft = displayX - COLLISION_HALF_WIDTH;
        int bubbleRight = displayX + COLLISION_HALF_WIDTH;

        if (playerX >= bubbleLeft && playerX <= bubbleRight &&
            playerY > displayY && playerY < displayY + COLLISION_HALF_HEIGHT) {

            // Player touched the bubble - restore air
            player.replenishAir();

            // ROM also clears player velocity and locks movement for 35 frames
            // (lines 44966-44998), but this requires additional player state access
            // which we defer to replenishAir() for minimal invasiveness

            // Play inhaling sound
            try {
                AudioManager.getInstance().playSfx(Sonic2AudioConstants.SFX_INHALING_BUBBLE);
            } catch (Exception e) {
                // Don't let audio failure break game logic
            }

            // Mark bubble as breathed (will be destroyed next frame)
            breathed = true;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || breathed) {
            return;
        }

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.BUBBLES);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Clamp frame to valid range (0-5 for visual bubbles)
        int frameToRender = Math.min(mappingFrame, 5);

        renderer.drawFrameIndex(frameToRender, displayX, displayY, false, false);
    }

    @Override
    public int getX() {
        return displayX;
    }

    @Override
    public int getY() {
        return displayY;
    }

    @Override
    public int getPriorityBucket() {
        return 1; // ROM: move.b #1,priority(a1)
    }

    /**
     * Returns true if this bubble was breathed by the player.
     */
    public boolean wasBreathed() {
        return breathed;
    }
}
