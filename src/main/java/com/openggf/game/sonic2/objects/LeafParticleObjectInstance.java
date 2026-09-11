package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ARZ Leaf Particle (Object 0x2C routine 4).
 * Falling leaf spawned by LeavesGeneratorObjectInstance.
 * <p>
 * Based on Obj2C_Leaf from s2.asm (lines 51695-51737).
 * <p>
 * Physics:
 * - Position stored as 16.16 fixed point
 * - Velocity applied each frame with gravity
 * - Sine/cosine oscillation for wobbling motion
 * - Random direction changes for natural movement
 * <p>
 * Animation:
 * - 4 frames (0-3), toggles bit 1 every 11 frames
 * - Deletes when off-screen
 */
public class LeafParticleObjectInstance extends AbstractObjectInstance
        implements SpawnCoordinateZeroScalarArgsRewindRecreatable {

    // Animation: toggle bit 1 every 11 frames
    private static final int ANIM_FRAME_DURATION = 11;

    // Oscillation speed (angle increment per frame)
    // ROM: move.b #4,objoff_38(a1)
    private static final int INITIAL_OSCILLATION_SPEED = 4;

    // Position as 16.16 fixed point (high word = pixel position)
    private int posX16;  // objoff_30 in ROM
    private int posY16;  // objoff_34 in ROM

    // Velocity (8.8 fixed point)
    private int xVel;
    private int yVel;

    // Oscillation angle (0-255)
    private int angle;

    // Oscillation speed (can be negated for direction change)
    private int oscillationSpeed;

    // Current display position (after oscillation)
    private int displayX;
    private int displayY;

    // Animation state
    private int mappingFrame;
    private int animTimer;

    // Frame counter for random direction changes
    private int frameCount;

    private LeafParticleObjectInstance() {
        this(0, 0, 0, 0, 0, 0);
    }

    public LeafParticleObjectInstance(int x, int y, int xVel, int yVel, int initialFrame, int initialAngle) {
        super(createDummySpawn(x, y), "Leaf");

        // Initialize position as 16.16 fixed point
        // ROM stores pixel position in high word
        this.posX16 = x << 16;
        this.posY16 = y << 16;

        this.xVel = xVel;
        this.yVel = yVel;

        this.angle = initialAngle & 0xFF;
        this.oscillationSpeed = INITIAL_OSCILLATION_SPEED;

        this.mappingFrame = initialFrame & 1;  // 0 or 1
        this.animTimer = ANIM_FRAME_DURATION;

        this.displayX = x;
        this.displayY = y;
    }

    /**
     * Creates a dummy ObjectSpawn for the particle.
     * Particles are dynamically spawned, not loaded from level data.
     */
    private static ObjectSpawn createDummySpawn(int x, int y) {
        // ObjectSpawn(x, y, objectId, subtype, renderFlags, respawnTracked, rawYWord)
        return new ObjectSpawn(x, y, 0x2C, 0, 0, false, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        frameCount++;

        // Update oscillation angle
        // ROM: move.b objoff_38(a0),d0 / add.b d0,angle(a0)
        angle = (angle + oscillationSpeed) & 0xFF;

        // Direction reversal check.
        // ROM: Obj2C_Leaf adds (Vint_runcount+3) to objoff_38 and gates on
        // $1F, then uses ExecuteObjects' d7 loop register, not RNG, to decide
        // whether to negate objoff_38 (docs/s2disasm/s2.asm:52202-52208).
        int check = (oscillationSpeed + vIntRunCount) & 0x1F;
        if (check == 0) {
            int slot = getSlotIndex();
            int romD7 = slot >= 0 && slot <= 127 ? 127 - slot : vIntRunCount;
            if ((romD7 & 1) != 0) {
                oscillationSpeed = -oscillationSpeed;
            }
        }

        // Update position with velocity (16.16 fixed point math)
        // ROM: move.w x_vel(a0),d0 / ext.l d0 / asl.l #8,d0 / add.l d0,d2
        posX16 += xVel << 8;
        posY16 += yVel << 8;

        // Apply gravity using fractional part of Y position
        // ROM: andi.w #3,d3 / addq.w #4,d3 / add.w d3,y_vel(a0)
        // d3 contains the low word of objoff_34 (Y position's fractional part)
        int gravity = (posY16 & 3) + 4;
        yVel += gravity;

        // Calculate display position with oscillation
        // ROM: move.b angle(a0),d0 / jsr CalcSine
        //      asr.w #6,d0 / add.w objoff_30(a0),d0 / move.w d0,x_pos(a0)
        //      asr.w #6,d1 / add.w objoff_34(a0),d1 / move.w d1,y_pos(a0)
        int sinValue = TrigLookupTable.sinHex(angle);
        int cosValue = TrigLookupTable.cosHex(angle);

        // Get pixel position from 16.16 fixed point (high word)
        int baseX = posX16 >> 16;
        int baseY = posY16 >> 16;

        // Add oscillation (shifted right by 6)
        displayX = baseX + (sinValue >> 6);
        displayY = baseY + (cosValue >> 6);

        // Update animation
        // ROM: subq.b #1,anim_frame_duration(a0) / bpl.s + /
        //      move.b #$B,anim_frame_duration(a0) / bchg #1,mapping_frame(a0)
        animTimer--;
        if (animTimer < 0) {
            animTimer = ANIM_FRAME_DURATION;
            mappingFrame ^= 2;  // Toggle bit 1: 0↔2 or 1↔3
        }

        // Check if off-screen - delete if so
        // ROM: btst #render_flags.on_screen,render_flags(a0) / beq DeleteObject
        if (!isWithinRenderSpriteBounds(getOnScreenHalfWidth(), getOnScreenHalfHeight())) {
            setDestroyed(true);
        }
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
    public int getOnScreenHalfWidth() {
        // ROM: Obj2C_CreateLeaves sets width_pixels(a1) to 8 before Obj2C_Leaf
        // observes render_flags.on_screen (docs/s2disasm/s2.asm:52166,52235).
        return 8;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.LEAVES);
        if (renderer == null) return;

        // Render the current frame
        renderer.drawFrameIndex(mappingFrame, displayX, displayY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.b #1,priority(a1)
        return RenderPriority.clamp(1);
    }
}
