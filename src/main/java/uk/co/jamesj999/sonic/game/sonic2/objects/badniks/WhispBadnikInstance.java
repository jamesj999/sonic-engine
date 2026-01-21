package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.Random;

/**
 * Whisp (0x8C) - Blowfly Badnik from Aquatic Ruin Zone.
 * A flying enemy that chases the player in cycles.
 *
 * Behavior from disassembly (obj8C.asm, lines 72660-72772):
 * 1. INIT: Load graphics, set 4 attack cycles
 * 2. WAIT_ONSCREEN: Wait until visible on screen, then decrement attacks and start chase
 * 3. CHASE: Accelerate toward player for 96 frames, initial Y velocity -0x100
 * 4. PAUSE: Random 0-31 frame pause, then decrement attacks and chase again
 * 5. FLY_AWAY: When attacks exhausted, escape at max speed up-left
 *
 * Movement physics:
 * - Acceleration: ±0x10 (16 subpixels) per frame toward player
 * - Max velocity: ±0x200 (512 subpixels = 2 pixels/frame)
 * - 4 attack cycles before escape
 * - Escape velocity: x=-0x200, y=-0x200 (flies up-left at 45°)
 * - Initial chase Y velocity: -0x100 (upward momentum)
 */
public class WhispBadnikInstance extends AbstractBadnikInstance {

    private enum State {
        INIT,              // Routine 0: Initialize
        WAIT_ONSCREEN,     // Routine 2: Wait until visible
        CHASE,             // Routine 4: Chase player
        PAUSE,             // Routine 6: Pause between attacks
        FLY_AWAY           // Routine 8: Escape
    }

    // Collision size from Touch_Sizes table index 0x0B = 8x8 pixels
    // (disassembly line 72762: subObjData ...$B)
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // Timing constants from disassembly
    private static final int CHASE_DURATION = 96;         // 96 frames chase duration (line 72712)
    private static final int MAX_ATTACKS = 4;             // 4 attack cycles before escape (line 72678)
    private static final int PAUSE_MASK = 0x1F;           // Random pause 0-31 frames (line 72746)

    // Movement constants (fixed-point 8.8 format)
    private static final int ACCELERATION = 0x10;         // Per frame acceleration (line 72738-72739)
    private static final int MAX_VELOCITY = 0x200;        // 2 pixels/frame max speed (line 72728-72729)
    private static final int ESCAPE_VELOCITY_X = -0x200;  // Escape velocity X (line 72704)
    private static final int ESCAPE_VELOCITY_Y = -0x200;  // Escape velocity Y (line 72705)
    private static final int INITIAL_CHASE_Y_VEL = -0x100; // Initial upward velocity when starting chase (line 72711)

    private static final Random random = new Random();

    private State state;
    private int timer;
    private int attacksRemaining;

    // Fixed-point position (8.8 format for subpixel accuracy)
    private int xPosFixed;
    private int yPosFixed;

    // Fixed-point velocities (8.8 format)
    private int xVelFixed;
    private int yVelFixed;

    public WhispBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "Whisp");
        this.state = State.INIT;
        this.timer = 0;
        this.attacksRemaining = MAX_ATTACKS;
        this.xVelFixed = 0;
        this.yVelFixed = 0;

        // Initialize fixed-point positions (shift by 8 for subpixel precision)
        this.xPosFixed = currentX << 8;
        this.yPosFixed = currentY << 8;

        // Initial facing based on render_flags
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        switch (state) {
            case INIT -> updateInit();
            case WAIT_ONSCREEN -> updateWaitOnscreen();
            case CHASE -> updateChase(player);
            case PAUSE -> updatePause();
            case FLY_AWAY -> updateFlyAway();
        }

        // Update integer position from fixed-point
        currentX = xPosFixed >> 8;
        currentY = yPosFixed >> 8;
    }

    /**
     * INIT state: Transition immediately to WAIT_ONSCREEN.
     */
    private void updateInit() {
        state = State.WAIT_ONSCREEN;
    }

    /**
     * WAIT_ONSCREEN state: Wait until the whisp is visible on screen.
     * When visible, decrement attacks and start chase (matching loc_36970 flow).
     */
    private void updateWaitOnscreen() {
        Camera camera = Camera.getInstance();
        int screenX = currentX - camera.getX();
        int screenY = currentY - camera.getY();

        // Check if on-screen (with some margin)
        if (screenX >= -32 && screenX < 352 && screenY >= -32 && screenY < 256) {
            // Transition to attack check (loc_36970 in disassembly)
            startNextAttackOrEscape();
        }
    }

    /**
     * Common routine for starting next attack or escaping (loc_36970).
     * Decrements attack counter BEFORE starting chase, not after.
     */
    private void startNextAttackOrEscape() {
        attacksRemaining--;
        if (attacksRemaining < 0) {
            // All attacks exhausted - fly away (routine 8)
            state = State.FLY_AWAY;
            xVelFixed = ESCAPE_VELOCITY_X;
            yVelFixed = ESCAPE_VELOCITY_Y;
        } else {
            // Start chase with initial upward velocity (routine 4)
            state = State.CHASE;
            timer = CHASE_DURATION;
            yVelFixed = INITIAL_CHASE_Y_VEL;  // -0x100 upward (line 72711)
        }
    }

    /**
     * PAUSE state: Random 0-31 frame pause between attacks.
     * When timer expires, go to attack check (loc_36970).
     */
    private void updatePause() {
        timer--;
        if (timer <= 0) {
            // Timer expired - check attacks and start next chase
            startNextAttackOrEscape();
        }
    }

    /**
     * CHASE state: Accelerate toward player for 96 frames.
     * When timer expires, transition to PAUSE with random duration.
     */
    private void updateChase(AbstractPlayableSprite player) {
        if (player != null) {
            // Calculate direction to player
            int playerX = player.getCentreX();
            int playerY = player.getCentreY();

            // Accelerate toward player on X axis
            if (playerX < currentX) {
                xVelFixed -= ACCELERATION;
                if (xVelFixed < -MAX_VELOCITY) {
                    xVelFixed = -MAX_VELOCITY;
                }
                facingLeft = true;
            } else {
                xVelFixed += ACCELERATION;
                if (xVelFixed > MAX_VELOCITY) {
                    xVelFixed = MAX_VELOCITY;
                }
                facingLeft = false;
            }

            // Accelerate toward player on Y axis
            if (playerY < currentY) {
                yVelFixed -= ACCELERATION;
                if (yVelFixed < -MAX_VELOCITY) {
                    yVelFixed = -MAX_VELOCITY;
                }
            } else {
                yVelFixed += ACCELERATION;
                if (yVelFixed > MAX_VELOCITY) {
                    yVelFixed = MAX_VELOCITY;
                }
            }
        }

        // Apply velocity to position
        xPosFixed += xVelFixed;
        yPosFixed += yVelFixed;

        // Decrement chase timer
        timer--;
        if (timer <= 0) {
            // Chase finished - transition to pause with random duration (lines 72744-72747)
            state = State.PAUSE;
            timer = random.nextInt(PAUSE_MASK + 1);  // Random 0-31 frames
        }
    }

    /**
     * FLY_AWAY state: Escape at max speed up-left.
     */
    private void updateFlyAway() {
        // Apply constant escape velocity
        xPosFixed += xVelFixed;
        yPosFixed += yVelFixed;
        facingLeft = true;
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        // Fast wing flapping - toggle between frames every tick
        animFrame = frameCounter & 1;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.WHISP);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Render current animation frame
        // Art faces right by default; flip when facing left
        boolean hFlip = facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
