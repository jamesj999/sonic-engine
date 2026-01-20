package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.Random;

/**
 * ARZ Leaves Generator (Object 0x2C).
 * Invisible trigger that spawns 4 falling leaf particles when the player
 * passes through at sufficient speed.
 * <p>
 * Based on Obj2C from s2.asm (lines 51533-51747).
 * <p>
 * Behavior:
 * - Invisible collision trigger (no visual rendering)
 * - Subtypes 0/1/2 select different collision box sizes
 * - Only triggers when player speed >= 0x200 in X or Y axis
 * - Spawns 4 LeafParticleObjectInstance children at player position
 * - 16-frame cooldown prevents rapid re-triggering
 */
public class LeavesGeneratorObjectInstance extends AbstractObjectInstance {

    // Minimum speed required to trigger leaves (0x200 in either axis)
    private static final int MIN_TRIGGER_SPEED = 0x200;

    // Cooldown frames between triggers (ROM: 16 frames via $F mask)
    private static final int COOLDOWN_FRAMES = 16;

    // Collision box half-sizes based on subtype -> collision_flags mapping
    // ROM uses Touch_Sizes table indexed by collision_flags byte
    // 0xD6 -> ~24x32, 0xD4 -> ~16x16, 0xD5 -> ~20x20
    private static final int[][] COLLISION_SIZES = {
            {24, 32},  // Subtype 0 -> 0xD6
            {16, 16},  // Subtype 1 -> 0xD4
            {20, 20}   // Subtype 2 -> 0xD5
    };

    // Velocity table for leaf particles (8.8 fixed point values)
    // ROM: Obj2C_Speeds at line 26286
    private static final int[][] LEAF_VELOCITIES = {
            {-0x80, -0x80},  // Leaf 0: top-left
            {0xC0, -0x40},   // Leaf 1: top-right
            {-0xC0, 0x40},   // Leaf 2: bottom-left
            {0x80, 0x80}     // Leaf 3: bottom-right
    };

    private final int collisionHalfWidth;
    private final int collisionHalfHeight;
    private final Random random = new Random();

    // Cooldown timer to prevent rapid re-triggering
    private int cooldownTimer = 0;

    public LeavesGeneratorObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Select collision size based on subtype (clamped to valid range)
        int subtype = spawn.subtype() & 0x03;
        if (subtype >= COLLISION_SIZES.length) {
            subtype = 0;
        }
        this.collisionHalfWidth = COLLISION_SIZES[subtype][0];
        this.collisionHalfHeight = COLLISION_SIZES[subtype][1];
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Decrement cooldown timer
        if (cooldownTimer > 0) {
            cooldownTimer--;
        }

        if (player == null) {
            return;
        }

        // Check if player is within collision box
        if (!isPlayerInCollisionBox(player)) {
            return;
        }

        // Check if player has sufficient speed
        if (!hasMinimumSpeed(player)) {
            return;
        }

        // Check cooldown
        if (cooldownTimer > 0) {
            return;
        }

        // Trigger leaves!
        spawnLeaves(player);
        cooldownTimer = COOLDOWN_FRAMES;
    }

    /**
     * Checks if the player is within this trigger's collision box.
     */
    private boolean isPlayerInCollisionBox(AbstractPlayableSprite player) {
        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        int dx = Math.abs(playerX - objX);
        int dy = Math.abs(playerY - objY);

        return dx < collisionHalfWidth && dy < collisionHalfHeight;
    }

    /**
     * Checks if player has the minimum speed to trigger leaves.
     * ROM: mvabs.w x_vel(a2),d0 / cmpi.w #$200,d0 (and same for y_vel)
     */
    private boolean hasMinimumSpeed(AbstractPlayableSprite player) {
        int absXSpeed = Math.abs(player.getXSpeed());
        int absYSpeed = Math.abs(player.getYSpeed());

        return absXSpeed >= MIN_TRIGGER_SPEED || absYSpeed >= MIN_TRIGGER_SPEED;
    }

    /**
     * Spawns 4 leaf particles at the player's position.
     * ROM: Obj2C_CreateLeaves at line 51629
     */
    private void spawnLeaves(AbstractPlayableSprite player) {
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }

        boolean playerFacingLeft = player.getDirection() == uk.co.jamesj999.sonic.physics.Direction.LEFT;

        for (int i = 0; i < 4; i++) {
            // Random ±8 pixel offset from player position
            // ROM: andi.w #$F,d0 / subq.w #8,d0
            int offsetX = (random.nextInt(16)) - 8;
            int offsetY = (random.nextInt(16)) - 8;

            int leafX = player.getCentreX() + offsetX;
            int leafY = player.getCentreY() + offsetY;

            // Get velocity for this leaf
            int xVel = LEAF_VELOCITIES[i][0];
            int yVel = LEAF_VELOCITIES[i][1];

            // Negate X velocity if player facing left
            // ROM: btst #status.player.x_flip,status(a2) / neg.w x_vel(a1)
            if (playerFacingLeft) {
                xVel = -xVel;
            }

            // Random initial frame (0 or 1)
            // ROM: andi.b #1,d0 / move.b d0,mapping_frame(a1)
            int initialFrame = random.nextInt(2);

            // Random initial oscillation angle
            int initialAngle = random.nextInt(256);

            LeafParticleObjectInstance leaf = new LeafParticleObjectInstance(
                    leafX, leafY, xVel, yVel, initialFrame, initialAngle);

            levelManager.getObjectManager().addDynamicObject(leaf);
        }

        // Play leaves sound
        playLeavesSound();
    }

    private void playLeavesSound() {
        try {
            AudioManager audioManager = AudioManager.getInstance();
            if (audioManager != null) {
                audioManager.playSfx(Sonic2AudioConstants.SFX_LEAVES);
            }
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible object - no rendering
    }

    @Override
    public int getPriorityBucket() {
        return 4; // ROM: move.b #4,priority(a0)
    }
}
