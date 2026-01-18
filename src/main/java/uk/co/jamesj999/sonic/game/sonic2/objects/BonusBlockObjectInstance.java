package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Bonus Block / Drop Target Object (ObjD8).
 * <p>
 * A UFO saucer-shaped bumper that can be hit 3 times before disappearing.
 * Each hit awards increasing points and changes the block's appearance.
 * Used in Casino Night Zone bonus areas.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 59520-59739
 * <ul>
 *   <li>ObjD8_Init: line 59533</li>
 *   <li>ObjD8_Hit: line 59606</li>
 *   <li>ObjD8_BouncePlayer: line 59640</li>
 * </ul>
 *
 * <h3>ROM Constants</h3>
 * <table border="1">
 *   <tr><th>Property</th><th>Value</th><th>ROM Reference</th></tr>
 *   <tr><td>Object ID</td><td>0xD8</td><td>ObjPtr_BonusBlock</td></tr>
 *   <tr><td>Bounce Velocity</td><td>$700 (1792)</td><td>line 59653</td></tr>
 *   <tr><td>Collision Flags</td><td>$D7</td><td>line 59541</td></tr>
 *   <tr><td>Collision Box</td><td>12x24 pixels</td><td>Touch_Sizes[0x17]</td></tr>
 *   <tr><td>width_pixels</td><td>$10 (16 px)</td><td>line 59539</td></tr>
 *   <tr><td>Sound</td><td>SndID_BonusBumper (0xD8)</td><td>line 59667</td></tr>
 *   <tr><td>Max Hits</td><td>3</td><td>line 59681</td></tr>
 * </table>
 *
 * <h3>Art Data</h3>
 * <ul>
 *   <li>Art: ArtNem_CNZMiniBumper (art/nemesis/Drop target from CNZ.nem)</li>
 *   <li>Mappings: ObjD8_MapUnc_2C8C4 (mappings/sprite/objD8.asm)</li>
 *   <li>Frames 0,3: 32x16 px (horizontal orientation)</li>
 *   <li>Frames 1,4: 24x32 px (vertical orientation)</li>
 *   <li>Frames 2,5: 16x32 px (vertical narrow)</li>
 * </ul>
 *
 * <h3>Subtype Format</h3>
 * <pre>
 * Bits 7-6: Initial orientation (rotated into bits 0-1 for anim index)
 *   anim = (subtype >> 6) &amp; 3
 *
 * Bits 5-0: Index into CNZ_saucer_data array for hit tracking
 * </pre>
 *
 * <h3>Hit Tracking</h3>
 * <ul>
 *   <li>Uses CNZ_saucer_data array (64 bytes max)</li>
 *   <li>Each instance tracks hits independently via subtype index</li>
 *   <li>Points awarded: 1st hit = 10pts, 2nd hit = 10pts, 3rd hit = 500pts</li>
 *   <li>Block deletes after 3rd hit (no explosion)</li>
 *   <li>Palette changes with each hit</li>
 * </ul>
 *
 * <h3>Physics</h3>
 * Uses surface-based directional bounce similar to Obj44, with $700 velocity.
 * The bounce direction depends on which surface the player contacts.
 *
 * @see BumperObjectInstance Round bumper with radial physics
 * @see HexBumperObjectInstance Hex bumper with 4-direction quantized physics
 */
public class BonusBlockObjectInstance extends AbstractObjectInstance {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /**
     * Bounce velocity magnitude = $700 (1792 in 8.8 fixed point).
     * <p>
     * ROM Reference: s2.asm line 59653
     */
    private static final int BOUNCE_VELOCITY = 0x700;

    /**
     * Collision box width = 12 pixels.
     * <p>
     * ROM Reference: Touch_Sizes[0x17] at s2.asm line 84574
     */
    private static final int COLLISION_WIDTH = 12;

    /**
     * Collision box height = 24 pixels.
     * <p>
     * ROM Reference: Touch_Sizes[0x17] at s2.asm line 84574
     */
    private static final int COLLISION_HEIGHT = 24;

    /**
     * Maximum hits before block is destroyed.
     * <p>
     * ROM Reference: s2.asm line 59681
     */
    private static final int MAX_HITS = 3;

    // ========================================================================
    // Points Constants
    // ========================================================================

    /**
     * Points awarded per hit (index = hit count - 1).
     * <p>
     * ROM Reference: s2.asm lines 59671, 59684
     * Hits 1-2 use d0=1 (10 pts), hit 3 uses d0=50 (500 pts).
     */
    private static final int[] POINTS_PER_HIT = {10, 10, 500};

    // ========================================================================
    // Animation Constants
    // ========================================================================

    /**
     * Animation frame offset for hit animation.
     * Frames 0-2 are idle states, frames 3-5 are corresponding hit states.
     */
    private static final int HIT_FRAME_OFFSET = 3;

    /** Duration of hit animation in frames */
    private static final int ANIM_DURATION = 8;

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Current hit count (0-3, deleted at 3) */
    private int hitCount = 0;

    /** Base animation frame based on subtype orientation */
    private int baseAnimFrame;

    /** Current animation frame */
    private int animFrame;

    /** Animation timer */
    private int animTimer = 0;

    /** Whether this block has been destroyed */
    private boolean destroyed = false;

    /** Hit cooldown to prevent multiple hits per frame */
    private int hitCooldown = 0;

    private static final int HIT_COOLDOWN_FRAMES = 4;

    public BonusBlockObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        initOrientation();
    }

    /**
     * Initialize orientation from subtype bits 7-6.
     * <p>
     * ROM Reference: s2.asm lines 59543-59548
     * <pre>
     * move.b  subtype(a0),d0
     * rol.b   #2,d0           ; rotate bits 7-6 into bits 1-0
     * andi.b  #3,d0
     * move.b  d0,anim(a0)
     * </pre>
     */
    private void initOrientation() {
        int subtype = spawn.subtype() & 0xFF;
        // Rotate bits 7-6 into bits 1-0
        baseAnimFrame = ((subtype >> 6) & 0x03);
        animFrame = baseAnimFrame;
    }

    /**
     * Get the index into CNZ_saucer_data for hit tracking.
     * <p>
     * ROM Reference: Uses bits 5-0 of subtype
     */
    private int getSaucerDataIndex() {
        return spawn.subtype() & 0x3F;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (destroyed) {
            return;
        }

        // Update hit cooldown
        if (hitCooldown > 0) {
            hitCooldown--;
        }

        // Update animation timer
        if (animTimer > 0) {
            animTimer--;
            if (animTimer == 0) {
                animFrame = baseAnimFrame;
            }
        }

        // Check collision with player
        if (player != null && !player.isHurt() && !player.getDead() && hitCooldown == 0) {
            if (checkCollision(player)) {
                handleHit(player);
            }
        }
    }

    /**
     * Check collision using player center position.
     * <p>
     * Note: Player getX()/getY() returns top-left corner, so we use getCentreX()/getCentreY()
     * to compare with the bonus block's center position (spawn.x(), spawn.y()).
     */
    private boolean checkCollision(AbstractPlayableSprite player) {
        int dx = Math.abs(player.getCentreX() - spawn.x());
        int dy = Math.abs(player.getCentreY() - spawn.y());

        int playerHalfWidth = 8; // Approximate
        int playerHalfHeight = player.getYRadius();

        return dx < (COLLISION_WIDTH / 2 + playerHalfWidth) &&
               dy < (COLLISION_HEIGHT / 2 + playerHalfHeight);
    }

    /**
     * Handle a hit on this bonus block.
     * <p>
     * ROM Reference: ObjD8_Hit at s2.asm line 59606
     * <p>
     * Behavior:
     * - Sound plays on every hit (line 59667)
     * - Color changes via palette line decrement (line 59672)
     * - After 3 hits, block deletes via animation (no explosion)
     */
    private void handleHit(AbstractPlayableSprite player) {
        hitCount++;
        hitCooldown = HIT_COOLDOWN_FRAMES;

        // Apply bounce
        applyBounce(player);

        // Play sound on every hit (ROM: line 59667)
        AudioManager.getInstance().playSfx(GameSound.BONUS_BUMPER);

        // Award points and spawn points display (ROM: lines 59687-59694)
        if (hitCount <= POINTS_PER_HIT.length) {
            int points = POINTS_PER_HIT[hitCount - 1];
            GameStateManager.getInstance().addScore(points);

            // Spawn floating points display
            LevelManager levelManager = LevelManager.getInstance();
            PointsObjectInstance pointsObj = new PointsObjectInstance(
                    new ObjectSpawn(spawn.x(), spawn.y(), 0x29, 0, 0, false, 0),
                    levelManager, points);
            levelManager.getObjectManager().addDynamicObject(pointsObj);
        }

        // Check for destruction (ROM: sets routine 4, deletes via animation)
        if (hitCount >= MAX_HITS) {
            destroyed = true;
            return;
        }

        // Trigger hit animation (color changes via palette in ROM)
        animFrame = baseAnimFrame + HIT_FRAME_OFFSET;
        animTimer = ANIM_DURATION;
    }

    /**
     * Apply bounce to player.
     * <p>
     * ROM Reference: ObjD8_BouncePlayer at s2.asm line 59640
     * <p>
     * Similar to Obj44 radial bounce but with $700 velocity.
     */
    private void applyBounce(AbstractPlayableSprite player) {
        // Calculate direction from block to player center
        int dx = spawn.x() - player.getCentreX();
        int dy = spawn.y() - player.getCentreY();

        // Calculate angle
        double angle = Math.atan2(dy, dx);

        // If player is exactly at center, push them up
        if (dx == 0 && dy == 0) {
            angle = Math.PI / 2;
        }

        // Apply velocity
        int xVel = (int) (-Math.cos(angle) * BOUNCE_VELOCITY);
        int yVel = (int) (-Math.sin(angle) * BOUNCE_VELOCITY);

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);

        // Set player state
        player.setAir(true);
        player.setPushing(false);
        player.setGSpeed((short) 0);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        ObjectRenderManager rm = LevelManager.getInstance().getObjectRenderManager();
        if (rm == null) {
            return;
        }

        PatternSpriteRenderer renderer = rm.getBonusBlockRenderer();
        if (renderer != null && renderer.isReady()) {
            boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
            boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
            renderer.drawFrameIndex(animFrame, spawn.x(), spawn.y(), hFlip, vFlip);
        }
    }

    /**
     * Check if this block has been destroyed.
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Get current hit count.
     */
    public int getHitCount() {
        return hitCount;
    }
}
