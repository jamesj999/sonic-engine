package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Speed Booster (Object 0x1B).
 * Yellow arrow pads that boost Sonic's horizontal velocity when he runs over them.
 * Based on obj1B.asm from the Sonic 2 disassembly.
 */
public class SpeedBoosterObjectInstance extends AbstractObjectInstance {

    // Boost speed values from ROM (Obj1B_BoosterSpeeds)
    private static final int FAST_SPEED = 0x1000;
    private static final int SLOW_SPEED = 0x0A00;

    // Collision box half-size (32x32 total = ±16 from center)
    private static final int COLLISION_HALF_SIZE = 16;

    // Move lock duration (frames)
    private static final int MOVE_LOCK_FRAMES = 0x0F;

    // The boost speed for this instance (determined by subtype)
    private final int boostSpeed;

    // Current mapping frame for rendering (toggles for blinking)
    private int mappingFrame = 0;

    public SpeedBoosterObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        // ROM: bit 1 of subtype selects speed (0=fast/$1000, 2=slow/$A00)
        this.boostSpeed = (spawn.subtype() & 0x02) == 0 ? FAST_SPEED : SLOW_SPEED;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Animation: Toggle between frame 0 (visible) and frame 2 (empty)
        // ROM: move.b (Level_frame_counter+1).w,d0 / andi.b #2,d0 / move.b d0,mapping_frame(a0)
        // This masks bit 1 directly, producing 0 or 2
        mappingFrame = frameCounter & 2;

        // Check collision with player
        if (player != null) {
            checkPlayerCollision(player);
        }
    }

    /**
     * Checks if player is within the 32x32 collision box and applies boost if grounded.
     */
    private void checkPlayerCollision(AbstractPlayableSprite player) {
        // ROM: btst #status.player.in_air,status(a1) / bne.s skip
        if (player.getAir()) {
            return; // Only activate when player is grounded
        }

        int objX = spawn.x();
        int objY = spawn.y();
        // ROM uses x_pos/y_pos which are CENTER positions
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // ROM collision check: x_pos ±16, y_pos ±16
        int leftBound = objX - COLLISION_HALF_SIZE;
        int rightBound = objX + COLLISION_HALF_SIZE;
        int topBound = objY - COLLISION_HALF_SIZE;
        int bottomBound = objY + COLLISION_HALF_SIZE;

        if (playerX < leftBound || playerX >= rightBound) {
            return;
        }
        if (playerY < topBound || playerY >= bottomBound) {
            return;
        }

        // Player is within collision box - apply boost
        applyBoost(player);
    }

    /**
     * Applies the speed boost to the player.
     * Based on Obj1B_GiveBoost from s2.asm.
     */
    private void applyBoost(AbstractPlayableSprite player) {
        // ROM: Get player's X velocity and check direction
        int playerXVel = player.getXSpeed();
        boolean objectFlipped = isFlippedHorizontal();

        // ROM: Make velocity absolute for comparison
        int absVel = objectFlipped ? -playerXVel : playerXVel;

        // ROM: cmpi.w #$1000,d0 / bge.s Obj1B_GiveBoost_Done
        // Only boost if player's speed in boost direction is < 0x1000
        if (absVel >= 0x1000) {
            // Player already going fast enough, just play sound
            playBoostSound();
            return;
        }

        // ROM: Set X velocity to boost speed
        int newXVel = boostSpeed;

        // ROM: Set player direction and negate velocity if flipped
        if (objectFlipped) {
            // Flipped = boost left (negative velocity)
            player.setDirection(Direction.LEFT);
            newXVel = -newXVel;
        } else {
            // Not flipped = boost right (positive velocity)
            player.setDirection(Direction.RIGHT);
        }

        player.setXSpeed((short) newXVel);

        // ROM: move.w #$F,move_lock(a1)
        // Engine uses setSpringing() for control locking behavior
        player.setSpringing(MOVE_LOCK_FRAMES);

        // ROM: move.w x_vel(a1),inertia(a1)
        player.setGSpeed((short) newXVel);

        // Clear pushing status (ROM clears status bits)
        // This is handled implicitly by the velocity change

        playBoostSound();
    }

    private void playBoostSound() {
        // ROM: move.w #SndID_Spring,d0 / jmp (PlaySound).l
        try {
            AudioManager audioManager = AudioManager.getInstance();
            if (audioManager != null) {
                audioManager.playSfx(GameSound.SPRING);
            }
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getSpeedBoosterRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = (spawn.renderFlags() & 0x02) != 0;

        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1);
    }
}
