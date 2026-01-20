package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Springboard / Lever Spring (Object 0x40).
 * A red diving-board style springboard found in CPZ, ARZ, and MCZ.
 * <p>
 * When the player stands on the "high" side (the raised end), the platform
 * compresses and launches them upward with position-dependent velocity.
 * Standing closer to the high end gives a stronger launch.
 * <p>
 * Based on Sonic 2 disassembly s2.asm lines 51757-51971.
 * <p>
 * Launch sequence:
 * 1. Player stands on high side -> animation switches to 1 (compressed)
 * 2. Animation 1 shows frame 1, then cycles to frame 0
 * 3. When anim==1 AND frame==0, player is launched
 * 4. Animation auto-switches back to 0 (idle)
 */
public class SpringboardObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider {

    // Animation IDs (from Ani_obj40)
    private static final int ANIM_IDLE = 0;       // byte_265EC: delay=0xF, frames=[0], LOOP
    private static final int ANIM_COMPRESSED = 1; // byte_265EF: delay=3, frames=[1,0], SWITCH to 0

    /**
     * Diagonal slope data (idle state - frame 0).
     * From s2.asm Obj40_SlopeData_DiagUp (40 bytes).
     */
    private static final byte[] SLOPE_DIAG_UP = {
            0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x09,
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x14, 0x15, 0x15, 0x16,
            0x17, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18,
            0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18
    };

    /**
     * Straight slope data (compressed state - frame 1).
     * From s2.asm Obj40_SlopeData_Straight (40 bytes).
     */
    private static final byte[] SLOPE_STRAIGHT = {
            0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x09,
            0x0A, 0x0B, 0x0C, 0x0C, 0x0C, 0x0C, 0x0D, 0x0D,
            0x0D, 0x0D, 0x0D, 0x0D, 0x0E, 0x0E, 0x0F, 0x0F,
            0x10, 0x10, 0x10, 0x10, 0x0F, 0x0F, 0x0E, 0x0E,
            0x0D, 0x0D, 0x0D, 0x0D, 0x0D, 0x0D, 0x0D, 0x0D
    };

    /**
     * Position-to-boost lookup table (72 bytes).
     * From s2.asm byte_26550.
     * Maps relative X position to a boost value (0-4).
     */
    private static final byte[] VELOCITY_BOOST_TABLE = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2,
            3, 3, 3, 3, 3, 3, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0
    };

    // Collision dimensions from ROM (Obj40_Main)
    // d1 = $27 (39) half-width, d2 = 8 height
    private static final int COLLISION_HALF_WIDTH = 0x27;
    private static final int COLLISION_HEIGHT = 8;

    // Position threshold for launch trigger (0x10 pixels from center)
    // ROM: loc_2641E checks player.x vs springboard.x ± 0x10
    private static final int POSITION_THRESHOLD = 0x10;

    private final ObjectAnimationState animationState;
    private int mappingFrame;

    public SpringboardObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, COLLISION_HALF_WIDTH, COLLISION_HEIGHT, 1.0f, 0.85f, 0.1f, false);

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getAnimations(Sonic2ObjectArtKeys.ANIM_SPRINGBOARD) : null,
                ANIM_IDLE,
                0);
        this.mappingFrame = 0;
    }

    /**
     * Called when player has solid contact with the springboard.
     * ROM: loc_2641E is called when p1_standing_bit is set in object's status.
     */
    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null || !contact.standing()) {
            return;
        }

        // Don't process if player is already springing or in air
        // This prevents re-triggering immediately after launch
        if (player.getSpringing() || player.getAir()) {
            return;
        }

        // ROM: loc_2641E - Check if player is on the "high" side
        if (!isPlayerOnHighSide(player)) {
            return;
        }

        // ROM: loc_26446 - Check animation state
        int currentAnim = animationState.getAnimId();

        if (currentAnim != ANIM_COMPRESSED) {
            // ROM: move.w #(1<<8)|(0<<0),anim(a0) - set anim to 1, frame to 0
            // This starts the compress animation
            animationState.setAnimId(ANIM_COMPRESSED);
            return;
        }

        // ROM: loc_26456 - anim is 1, check if mapping_frame is 0
        if (mappingFrame == 0) {
            // ROM: loc_2645E - Launch the player!
            applyLaunch(player);
        }
        // If mapping_frame != 0, do nothing (wait for animation to cycle)
    }

    /**
     * Checks if the player is on the "high" side of the springboard.
     * ROM: loc_2641E (unflipped) and loc_26436 (flipped)
     */
    private boolean isPlayerOnHighSide(AbstractPlayableSprite player) {
        int playerX = player.getX();
        int springboardX = spawn.x();

        if (isFlippedHorizontal()) {
            // ROM: loc_26436 - d0 = springboard.x + 0x10, bhs if d0 >= player.x
            // Flipped: high side is on the left, player.x <= springboard.x + 0x10
            return playerX <= springboardX + POSITION_THRESHOLD;
        } else {
            // ROM: loc_2641E - d0 = springboard.x - 0x10, blo if d0 < player.x
            // Unflipped: high side is on the right, player.x > springboard.x - 0x10
            return playerX > springboardX - POSITION_THRESHOLD;
        }
    }

    /**
     * Applies launch velocity and sets player state.
     * ROM: loc_2645E through loc_26546
     */
    private void applyLaunch(AbstractPlayableSprite player) {
        boolean flipped = isFlippedHorizontal();

        // ROM: loc_2645E - Calculate relative X position for boost lookup
        // d0 = player.x - (springboard.x - 0x1C) = player.x - springboard.x + 0x1C
        int dx = player.getX() - spawn.x() + 0x1C;

        if (flipped) {
            // ROM bug preserved: uses 0x27 instead of 0x38 (2*0x1C)
            dx = ~dx + 0x27;
        }

        // ROM: loc_2647A - Clamp negative values to 0
        if (dx < 0) {
            dx = 0;
        }

        // Clamp to table bounds (72 entries)
        if (dx >= VELOCITY_BOOST_TABLE.length) {
            dx = VELOCITY_BOOST_TABLE.length - 1;
        }

        // ROM: loc_26480 - Look up boost value (0-4)
        int boost = VELOCITY_BOOST_TABLE[dx] & 0xFF;

        // ROM: move.w #-$400,y_vel(a1) then sub.b d0,y_vel(a1)
        // sub.b from high byte = y_vel -= boost << 8
        int yVelocity = -0x400 - (boost << 8);
        player.setYSpeed((short) yVelocity);

        // ROM: bset #status.player.x_flip,status(a1) (set facing right)
        // Then if springboard NOT flipped: bclr (clear = face left), neg.b d0
        if (flipped) {
            player.setDirection(Direction.LEFT);
        } else {
            player.setDirection(Direction.RIGHT);
            boost = -boost; // ROM: neg.b d0
        }

        // ROM: loc_264AA - Apply X velocity boost if |x_vel| >= 0x400
        int xVel = player.getXSpeed();
        if (Math.abs(xVel) >= 0x400) {
            // ROM: sub.b d0,x_vel(a1) - subtract from high byte = x_vel -= boost << 8
            player.setXSpeed((short) (xVel - (boost << 8)));
        }

        // ROM: loc_264BC - Set player to airborne state
        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setSpringing(15); // ROM: AniIDSonAni_Spring

        // ROM: Clear on_object status (player no longer standing on springboard)
        // This is handled by the physics engine when we set air=true

        // ROM: loc_26546 - Play spring sound
        try {
            if (AudioManager.getInstance() != null) {
                AudioManager.getInstance().playSfx(GameSound.SPRING);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: d1=$27 (half-width), d2=8 (height)
        return new SolidObjectParams(COLLISION_HALF_WIDTH, COLLISION_HEIGHT, COLLISION_HEIGHT);
    }

    @Override
    public byte[] getSlopeData() {
        // ROM: Obj40_Main selects slope data based on mapping_frame
        // frame 0 = diagonal (idle), frame 1 = straight (compressed)
        return mappingFrame == 0 ? SLOPE_DIAG_UP : SLOPE_STRAIGHT;
    }

    @Override
    public boolean isSlopeFlipped() {
        return isFlippedHorizontal();
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // ROM: Obj40_Main calls AnimateSprite before collision check
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.SPRINGBOARD);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }

        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
