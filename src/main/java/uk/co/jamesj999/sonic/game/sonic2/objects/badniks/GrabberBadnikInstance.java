package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AnimationIds;

import java.util.List;

/**
 * Grabber (0xA7) - Spider badnik from CPZ.
 * Patrols horizontally on ceiling, detects player below, dives to grab,
 * carries player back up, releases on button mashing or timeout.
 *
 * Based on ObjA7 from Sonic 2 disassembly.
 */
public class GrabberBadnikInstance extends AbstractBadnikInstance {
    private static final int COLLISION_SIZE_INDEX = 0x0B; // From disassembly

    // Movement constants from disassembly
    private static final int PATROL_VELOCITY = 0x40;        // x_vel = $40 in subpixels
    private static final int PATROL_TIMER_INIT = 0xFF;      // 255 frames before turning
    private static final int DIVE_VELOCITY = 0x200;         // y_vel = $200 during dive
    private static final int DIVE_DELAY_INIT = 0x10;        // 16 frames delay before dive
    private static final int DIVE_TIMER_INIT = 0x40;        // 64 frames max dive time
    private static final int INPUT_CHECK_INTERVAL = 0x20;   // 32 frames between input checks
    private static final int BLINK_COUNT_INIT = 0x10;       // 16 blinks before timeout/destruction
    private static final int ESCAPE_BUTTON_COUNT = 4;       // Direction toggles to escape

    // Detection ranges
    private static final int DETECT_RANGE_X = 0x80;         // 128 pixels horizontal
    private static final int DETECT_RANGE_Y = 0x80;         // Player must be below within 128 pixels

    private enum State {
        PATROL,         // State 0: Hunting for player
        DELAY,          // State 2: Wind-up before dive
        DIVING,         // State 4: Diving toward player
        CARRYING,       // State 6: Carrying grabbed player
        RELEASING,      // State 8: Releasing player
        DEATH           // State A: Exploding
    }

    private State state;
    private int patrolTimer;
    private int diveTimer;
    private int delayTimer;
    private int inputCheckTimer;        // 32-frame interval for checking escape input (objoff_37)
    private int blinkCounter;           // Frames until next blink (objoff_2A)
    private int blinkCount;             // Blinks remaining before timeout (objoff_2B)
    private int directionToggleCount;   // Button presses in current check window (objoff_38)
    private boolean inputDetectedThisCycle; // Has input been detected this cycle (objoff_31)
    private int lastDirectionBits;          // Last direction pressed as bitmask (objoff_36)
    private boolean paletteFlipped;         // Palette bit toggle for blink effect (not visibility)
    private int anchorY;            // Y position of anchor point (where Grabber starts)
    private AbstractPlayableSprite grabbedPlayer;

    // Sub-object positions
    private int legsFrame;          // Frame index for legs (3 or 4)
    private int stringFrame;        // Frame index for string (0-8 based on distance)

    // Subpixel positions for accurate movement (16.8 fixed point)
    private int xSubpixel;          // X subpixel position (0-255)
    private int ySubpixel;          // Y subpixel position (0-255)

    public GrabberBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "Grabber");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.anchorY = spawn.y();

        // Initial direction based on x_flip flag
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
        this.xVelocity = facingLeft ? -PATROL_VELOCITY : PATROL_VELOCITY;

        this.state = State.PATROL;
        this.patrolTimer = PATROL_TIMER_INIT;
        this.diveTimer = 0;
        this.delayTimer = 0;
        this.inputCheckTimer = 0;
        this.blinkCounter = 0;
        this.blinkCount = 0;
        this.directionToggleCount = 0;
        this.inputDetectedThisCycle = false;
        this.lastDirectionBits = 0;
        this.paletteFlipped = false;
        this.grabbedPlayer = null;
        this.legsFrame = 3;
        this.stringFrame = 0;
        this.xSubpixel = 0;
        this.ySubpixel = 0;
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        switch (state) {
            case PATROL -> updatePatrol(player);
            case DELAY -> updateDelay();
            case DIVING -> updateDiving(player);
            case CARRYING -> updateCarrying();
            case RELEASING -> updateReleasing();
            case DEATH -> updateDeath();
        }

        // Update string frame based on distance from anchor
        updateStringFrame();
    }

    private void updatePatrol(AbstractPlayableSprite player) {
        // Check if player is in range
        if (player != null && checkPlayerInRange(player)) {
            // Start attack sequence
            state = State.DELAY;
            delayTimer = DIVE_DELAY_INIT;
            xVelocity = 0; // Stop horizontal movement
            return;
        }

        // Continue patrolling
        patrolTimer--;
        if (patrolTimer < 0) {
            // Turn around
            patrolTimer = PATROL_TIMER_INIT;
            xVelocity = -xVelocity;
            facingLeft = !facingLeft;
        }

        // Move horizontally using 16.8 fixed point math (like ObjectMove in disassembly)
        // Position is stored as (pixel << 8) | subpixel, velocity is added directly
        int xPos32 = (currentX << 8) | (xSubpixel & 0xFF);
        xPos32 += xVelocity;
        currentX = xPos32 >> 8;
        xSubpixel = xPos32 & 0xFF;
    }

    private boolean checkPlayerInRange(AbstractPlayableSprite player) {
        // d2 = object X - player X (positive when player is to the LEFT)
        // d3 = object Y - player Y (positive when player is ABOVE, negative when BELOW)
        int dx = currentX - player.getCentreX();
        int dy = currentY - player.getCentreY();

        // Horizontal check: addi.w #$40,d2 / cmpi.w #$80,d2 / bhs.s (fail)
        // Range: -$40 to +$3F (-64 to +63 pixels)
        int adjustedDx = dx + 0x40;
        if (adjustedDx < 0 || adjustedDx >= 0x80) {
            return false;
        }

        // Vertical check: cmpi.w #-$80,d3 / bhi.s (attack)
        // Since Grabber is on ceiling and d3 = grabber_y - player_y:
        //   - Negative d3 = player is BELOW grabber (attack if in range)
        //   - Range: d3 must be in (-$7F to 0), meaning player 1-127 pixels below
        return dy > -DETECT_RANGE_Y && dy <= 0;
    }

    private void updateDelay() {
        delayTimer--;
        if (delayTimer < 0) {
            // Start diving
            state = State.DIVING;
            diveTimer = DIVE_TIMER_INIT;
            yVelocity = DIVE_VELOCITY;
            legsFrame = 4; // Switch to larger legs frame during dive
        }
    }

    private void updateDiving(AbstractPlayableSprite player) {
        // Check for grab collision
        if (player != null && checkGrabCollision(player)) {
            grabPlayer(player);
            return;
        }

        diveTimer--;
        if (diveTimer <= 0) {
            // Abort dive, return to patrol
            returnToPatrol();
            return;
        }

        // At midpoint, reverse direction
        if (diveTimer == DIVE_TIMER_INIT / 2) {
            yVelocity = -yVelocity;
        }

        // Move vertically using 16.8 fixed point math
        int yPos32 = (currentY << 8) | (ySubpixel & 0xFF);
        yPos32 += yVelocity;
        currentY = yPos32 >> 8;
        ySubpixel = yPos32 & 0xFF;
    }

    private boolean checkGrabCollision(AbstractPlayableSprite player) {
        // Check if legs are overlapping player
        // Legs are offset below the main body
        int legsY = currentY + 16;
        int dx = Math.abs(currentX - player.getCentreX());
        int dy = Math.abs(legsY - player.getCentreY());

        // Collision box approximately 24x16 pixels
        // Can't grab if player is invulnerable or invincible
        return dx < 24 && dy < 16 && !player.getInvulnerable();
    }

    private void grabPlayer(AbstractPlayableSprite player) {
        state = State.CARRYING;
        grabbedPlayer = player;

        // Initialize timers per disassembly (ObjA7_GrabCharacter, lines 76240-76245)
        inputCheckTimer = INPUT_CHECK_INTERVAL;  // objoff_37 = $20 (check every 32 frames)
        blinkCount = BLINK_COUNT_INIT;           // objoff_2B = $10 (16 blinks before timeout)
        blinkCounter = blinkCount;               // objoff_2A = objoff_2B (start with full interval)
        directionToggleCount = 0;                // objoff_38 = 0 (button press counter)
        inputDetectedThisCycle = false;          // objoff_31 = 0
        lastDirectionBits = 0;                   // objoff_36 = 0

        // Lock player movement (obj_control = $81)
        player.setObjectControlled(true);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setAnimationId(Sonic2AnimationIds.FLOAT);  // Per disassembly line 76221

        // Reverse dive direction to go back up
        if (yVelocity > 0) {
            yVelocity = -yVelocity;
            // Recalculate remaining time
            int remaining = DIVE_TIMER_INIT - diveTimer;
            diveTimer = remaining + 1;
        }

        animFrame = 1; // Closed claws frame
        // Note: Per disassembly, no sound effect plays on grab
    }

    private void updateCarrying() {
        if (grabbedPlayer == null) {
            returnToPatrol();
            return;
        }

        // === Input checking (loc_390BC in disassembly) ===
        // Track directional input changes using bitmask like the disassembly
        // Bit 2 = left ($04), Bit 3 = right ($08), mask = $0C
        int currentDirectionBits = 0;
        if (grabbedPlayer.isLeftPressed()) currentDirectionBits |= 0x04;
        if (grabbedPlayer.isRightPressed()) currentDirectionBits |= 0x08;
        currentDirectionBits &= 0x0C;  // Mask to direction bits only

        if (currentDirectionBits != 0) {
            if (!inputDetectedThisCycle) {
                // First direction press in this cycle - set baseline
                inputDetectedThisCycle = true;
                lastDirectionBits = currentDirectionBits;
            } else if (currentDirectionBits != lastDirectionBits) {
                // Direction changed - count the toggle
                directionToggleCount++;
                lastDirectionBits = currentDirectionBits;
            }
        }

        // Every 32 frames, check if player has escaped (objoff_37 countdown)
        inputCheckTimer--;
        if (inputCheckTimer <= 0) {
            if (directionToggleCount >= ESCAPE_BUTTON_COUNT) {
                // Player escaped! Grabber survives and returns to patrol
                releasePlayer(true);
                return;
            }
            // Reset for next check window
            inputCheckTimer = INPUT_CHECK_INTERVAL;
            directionToggleCount = 0;
            inputDetectedThisCycle = false;
        }

        // === Blink mechanism (ObjA7_CheckExplode) ===
        // Decrement blink counter each frame
        blinkCounter--;
        if (blinkCounter <= 0) {
            // Reload counter and decrement blink count
            blinkCounter = blinkCount;
            blinkCount--;
            paletteFlipped = !paletteFlipped;  // Toggle palette bit (bchg #palette_bit_0)

            if (blinkCount <= 0) {
                // Timeout! Grabber explodes and HURTS the player
                hurtAndReleasePlayer();
                // Trigger destruction (Grabber transforms to explosion)
                triggerDestruction();
                return;
            }
        }

        // === Movement back to anchor ===
        if (currentY > anchorY) {
            int yPos32 = (currentY << 8) | (ySubpixel & 0xFF);
            yPos32 += yVelocity;
            currentY = yPos32 >> 8;
            ySubpixel = yPos32 & 0xFF;
        }

        // Update grabbed player position (center below grabber's claws)
        grabbedPlayer.setX((short) (currentX - grabbedPlayer.getWidth() / 2));
        grabbedPlayer.setY((short) (currentY + 24 - grabbedPlayer.getHeight() / 2));
    }

    /**
     * Called when blink sequence completes - player takes damage.
     * Per disassembly ObjA7_Poof: player is released and hurt by the explosion.
     */
    private void hurtAndReleasePlayer() {
        if (grabbedPlayer != null) {
            grabbedPlayer.setObjectControlled(false);
            grabbedPlayer.setAir(true);

            // Hurt the player - this is the punishment for not escaping
            boolean hadRings = grabbedPlayer.getRingCount() > 0;
            if (hadRings && !grabbedPlayer.hasShield()) {
                LevelManager.getInstance().spawnLostRings(grabbedPlayer, 0);
            }
            grabbedPlayer.applyHurtOrDeath(currentX, true, hadRings);

            grabbedPlayer = null;
        }
    }

    /**
     * Called when the blink sequence completes - Grabber transforms to explosion.
     */
    private void triggerDestruction() {
        state = State.DEATH;
        destroyed = true;
        // Grabber transforms to explosion object (no animal spawned)
    }

    private void releasePlayer(boolean escaped) {
        if (grabbedPlayer != null) {
            grabbedPlayer.setObjectControlled(false);
            grabbedPlayer.setAir(true);
            // Per disassembly: player just becomes airborne and falls naturally
            // No velocity change on escape
            grabbedPlayer = null;
        }

        state = State.RELEASING;
        animFrame = 0; // Open claws
        paletteFlipped = false;  // Reset palette
    }

    private void updateReleasing() {
        // Brief pause before returning to patrol
        returnToPatrol();
    }

    private void returnToPatrol() {
        state = State.PATROL;
        patrolTimer = PATROL_TIMER_INIT;
        xVelocity = facingLeft ? -PATROL_VELOCITY : PATROL_VELOCITY;
        yVelocity = 0;
        animFrame = 0;
        legsFrame = 3;
        paletteFlipped = false;  // Reset palette

        // Return to anchor position
        currentY = anchorY;
        ySubpixel = 0;
    }

    private void updateDeath() {
        // Release any grabbed player
        if (grabbedPlayer != null) {
            releasePlayer(true);
        }
        // Death is handled by AbstractBadnikInstance
    }

    private void updateStringFrame() {
        // Per disassembly: string object Y = anchorY - 8
        // Frame = (grabberY - stringY) / 16 = (currentY - anchorY + 8) / 16
        int stringY = anchorY - 8;
        int distance = currentY - stringY;
        if (distance < 0) {
            stringFrame = 0;
        } else {
            // Each frame represents 16 pixels of string
            stringFrame = Math.min(distance >> 4, 8);
        }
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        // Animation is simple: frame 0 = open claws, frame 1 = closed claws
        // Already handled in state transitions
        if (state == State.CARRYING) {
            animFrame = 1;
        } else {
            // Animate between frames 0 and 1 when patrolling
            if (state == State.PATROL) {
                animFrame = ((frameCounter / 8) & 1);
            }
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public void onPlayerAttack(AbstractPlayableSprite player, uk.co.jamesj999.sonic.level.objects.TouchResponseResult result) {
        if (destroyed) {
            return;
        }

        // Grabber is ceiling-mounted - can only be destroyed from above
        // Player must be above the Grabber's center and moving downward (or at least not upward)
        int playerCentreY = player.getCentreY();
        int grabberCentreY = currentY;

        // Only allow attack if player is above the grabber
        if (playerCentreY >= grabberCentreY) {
            // Player is at same level or below - ignore the attack
            // This prevents destruction from jumping up into it from below
            return;
        }

        // Player is above - allow the attack
        // Release any grabbed player before destruction
        if (grabbedPlayer != null) {
            releasePlayer(true);
        }
        super.onPlayerAttack(player, result);
    }

    @Override
    public int getPriorityBucket() {
        // Per disassembly: priority = 4 (renders in front of player at priority 2)
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        // Palette blink: toggle between palette 1 (normal) and palette 0 (flipped)
        // This matches disassembly: bchg #palette_bit_0,art_tile(a0)
        int paletteOverride = paletteFlipped ? 0 : -1;  // -1 means use default (palette 1)

        // Draw string - per disassembly, string object is at anchorY - 8 and always renders
        // String frames extend downward from that position to connect to the grabber body
        PatternSpriteRenderer stringRenderer = renderManager.getRenderer(Sonic2ObjectArtKeys.GRABBER_STRING);
        if (stringRenderer != null && stringRenderer.isReady()) {
            stringRenderer.drawFrameIndex(stringFrame, currentX, anchorY - 8, false, false, paletteOverride);
        }

        // Draw anchor box (frame 2)
        PatternSpriteRenderer grabberRenderer = renderManager.getRenderer(Sonic2ObjectArtKeys.GRABBER);
        if (grabberRenderer == null || !grabberRenderer.isReady()) {
            return;
        }
        grabberRenderer.drawFrameIndex(2, currentX, anchorY - 12, false, false, paletteOverride);

        // Draw main body
        grabberRenderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false, paletteOverride);

        // Draw legs (frame 3 or 4)
        grabberRenderer.drawFrameIndex(legsFrame, currentX, currentY + 16, !facingLeft, false, paletteOverride);
    }
}
