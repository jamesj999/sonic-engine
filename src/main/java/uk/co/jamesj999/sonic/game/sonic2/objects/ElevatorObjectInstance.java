package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Elevator Object (ObjD5).
 * <p>
 * A platform that moves vertically when the player stands on it, returning to
 * its original position when the player leaves.
 * <p>
 * <b>Movement Model:</b>
 * The elevator always accelerates toward the spawn position (objoff_32 in ROM).
 * When velocity reaches zero (after overshooting), it snaps to spawn and then
 * applies a position offset to reach the destination. This creates smooth
 * deceleration as the platform approaches the spawn point.
 * <p>
 * <b>Behavior (State Machine):</b>
 * <ul>
 *   <li>State 0: Wait for contact - platform waits for player to stand on it</li>
 *   <li>State 2: Move toward spawn - accelerates toward spawn Y, ends at opposite extreme</li>
 *   <li>State 4: Player departs - waits for player to leave (no collision in this state)</li>
 *   <li>State 6: Return - accelerates toward spawn Y, ends at initial position (no collision)</li>
 * </ul>
 * <p>
 * <b>Subtype Encoding:</b>
 * <ul>
 *   <li>subtype * 4 = Y offset from spawn position</li>
 *   <li>Bit 3 of render flags (x_flip) inverts direction:
 *       Clear (normal) = starts ABOVE spawn, ends BELOW spawn, returns ABOVE;
 *       Set (inverted) = starts BELOW spawn, ends ABOVE spawn, returns BELOW</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm ObjD5 (CNZ Elevator), lines 58376-58498
 */
public class ElevatorObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    // State constants (from disassembly routine offsets)
    private static final int STATE_WAIT_FOR_CONTACT = 0;
    private static final int STATE_MOVE_UP = 2;
    private static final int STATE_PLAYER_DEPARTS = 4;
    private static final int STATE_RETURN = 6;

    // Movement velocity: ROM uses moveq #8,d1 then add.w d1,y_vel(a0)
    // ObjectMove shifts velocity left 8 bits when adding to 16.16 position.
    // Our 16.8 fixed-point needs the raw value (8), not scaled.
    private static final int VELOCITY_INCREMENT = 8;

    // Platform collision dimensions
    // d1 = 0x10 (half-width = 16 pixels)
    // d3 = 9 (platform height for collision)
    private static final int HALF_WIDTH = 16;
    private static final int PLATFORM_HEIGHT = 9;

    // Position tracking
    private int x;              // Current X (constant, same as spawn)
    private int y;              // Current Y position (integer part)
    private int ySub;           // 16.8 fixed-point Y position
    private int yVel;           // Y velocity in 16.8 fixed-point

    // Movement parameters
    private int baseY;          // Original spawn Y position (objoff_32 in ROM)
    private int yOffset;        // Movement range: subtype * 4

    // State
    private int state;
    private boolean inverted;   // True if bit 3 of render flags is set (invert direction)

    // Contact tracking
    private int lastContactFrame = -2;

    // Dynamic spawn for moving position
    private ObjectSpawn dynamicSpawn;

    public ElevatorObjectInstance(ObjectSpawn spawn, String name) {
        // Use platform half-width for debug box, cyan color
        super(spawn, name, HALF_WIDTH, PLATFORM_HEIGHT, 0.2f, 0.8f, 0.8f, false);
        init();
    }

    private void init() {
        x = spawn.x();
        baseY = spawn.y();

        // Inverted flag from x_flip bit of render flags (bit 0 after extraction from yWord bit 13)
        // ROM uses status.npc.x_flip (bit 3 of status), but we extract x_flip to bit 0 of renderFlags
        inverted = (spawn.renderFlags() & 0x01) != 0;

        // Y offset = subtype * 4 (stored for use in state transitions)
        yOffset = (spawn.subtype() & 0xFF) * 4;

        // ROM Init (s2.asm lines 58396-58405):
        // Normal (x_flip=0): y_pos = spawn - offset (starts ABOVE spawn)
        // Inverted (x_flip=1): y_pos = spawn + offset (starts BELOW spawn)
        if (inverted) {
            y = baseY + yOffset;
        } else {
            y = baseY - yOffset;
        }

        // Initialize 16.8 fixed-point position (ROM uses 0x8000 = 0.5 fractional)
        ySub = (y << 8) | 0x80;
        yVel = 0;
        state = STATE_WAIT_FOR_CONTACT;

        refreshDynamicSpawn();
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
        // From disassembly: d1 = 0x10 (half-width), d3 = 9 (platform height)
        return new SolidObjectParams(HALF_WIDTH, PLATFORM_HEIGHT, PLATFORM_HEIGHT + 1);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Elevators are platform objects - only solid from the top
        return true;
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        // ROM (s2.asm lines 58412-58417): Platform collision only runs in states 0, 2, 4.
        // State 6 (return) has NO collision - player cannot stand on returning elevator.
        if (state == STATE_RETURN) {
            return false;
        }
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
        // Apply velocity FIRST (matches ROM ObjectMove timing)
        applyVelocity();

        boolean standing = (frameCounter - lastContactFrame) <= 1;

        switch (state) {
            case STATE_WAIT_FOR_CONTACT -> updateWaitForContact(standing);
            case STATE_MOVE_UP -> updateMoveUpAcceleration();
            case STATE_PLAYER_DEPARTS -> updatePlayerDeparts(standing);
            case STATE_RETURN -> updateReturnAcceleration();
        }

        refreshDynamicSpawn();
    }

    /**
     * Apply velocity to position (matches ROM ObjectMove timing).
     * Called BEFORE state handlers add acceleration.
     */
    private void applyVelocity() {
        ySub += yVel;
        y = ySub >> 8;
    }

    /**
     * State 0: Wait for player to stand on the platform.
     * When contact detected, transition to State 2 (move up) and play sound.
     */
    private void updateWaitForContact(boolean standing) {
        if (standing) {
            state = STATE_MOVE_UP;
            playElevatorSound();
        }
    }

    /**
     * State 2: Accelerate toward spawn position (baseY).
     * <p>
     * ROM behavior (s2.asm lines 58438-58457):
     * - Always accelerates toward objoff_32 (spawn Y), not a fixed target
     * - When velocity reaches 0, snaps to spawn then applies end position offset
     * - End position is OPPOSITE of initial position:
     *   - x_flip=0: End at spawn + offset (BELOW spawn)
     *   - x_flip=1: End at spawn - offset (ABOVE spawn)
     */
    private void updateMoveUpAcceleration() {
        // Accelerate toward spawn (baseY)
        if (y < baseY) {
            yVel += VELOCITY_INCREMENT;  // Below spawn, accelerate down
        } else if (y > baseY) {
            yVel -= VELOCITY_INCREMENT;  // Above spawn, accelerate up
        }

        // ROM: bne.s + - transition only when velocity == 0
        if (yVel == 0) {
            // Snap to spawn
            y = baseY;
            ySub = baseY << 8;

            // Apply State 2 end position (opposite of initial position)
            // ROM (s2.asm lines 58447-58457):
            // x_flip=0: y_pos = spawn + offset (BELOW spawn)
            // x_flip=1: y_pos = spawn - offset (ABOVE spawn)
            if (inverted) {
                y -= yOffset;  // x_flip=1: end ABOVE spawn
            } else {
                y += yOffset;  // x_flip=0: end BELOW spawn
            }
            ySub = y << 8;

            state = STATE_PLAYER_DEPARTS;
        }
    }

    /**
     * State 4: Wait for player to leave the platform.
     * When player leaves, transition to State 6 (return) and play sound.
     */
    private void updatePlayerDeparts(boolean standing) {
        if (!standing) {
            state = STATE_RETURN;
            playElevatorSound();
        }
    }

    /**
     * State 6: Return to spawn position (baseY), then back to initial position.
     * <p>
     * ROM behavior (s2.asm lines 58472-58491):
     * - Always accelerates toward objoff_32 (spawn Y)
     * - When velocity reaches 0, snaps to spawn then applies end position offset
     * - End position is SAME as initial position (back to start):
     *   - x_flip=0: End at spawn - offset (ABOVE spawn)
     *   - x_flip=1: End at spawn + offset (BELOW spawn)
     */
    private void updateReturnAcceleration() {
        // Accelerate toward spawn (baseY)
        if (y < baseY) {
            yVel += VELOCITY_INCREMENT;  // Below spawn, accelerate down
        } else if (y > baseY) {
            yVel -= VELOCITY_INCREMENT;  // Above spawn, accelerate up
        }

        // Transition only when velocity == 0
        if (yVel == 0) {
            // Snap to spawn
            y = baseY;
            ySub = baseY << 8;

            // Apply State 6 end position (same as initial position)
            // ROM (s2.asm lines 58481-58491):
            // x_flip=0: y_pos = spawn - offset (ABOVE spawn) - back to start
            // x_flip=1: y_pos = spawn + offset (BELOW spawn) - back to start
            if (inverted) {
                y += yOffset;  // x_flip=1: end BELOW spawn (initial position)
            } else {
                y -= yOffset;  // x_flip=0: end ABOVE spawn (initial position)
            }
            ySub = y << 8;

            state = STATE_WAIT_FOR_CONTACT;
        }
    }

    private void playElevatorSound() {
        try {
            AudioManager audioManager = AudioManager.getInstance();
            if (audioManager != null) {
                audioManager.playSfx(GameSound.CNZ_ELEVATOR);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    private void refreshDynamicSpawn() {
        if (dynamicSpawn == null || dynamicSpawn.y() != y) {
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

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_ELEVATOR);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        // Frame 0 only, no flipping
        renderer.drawFrameIndex(0, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
