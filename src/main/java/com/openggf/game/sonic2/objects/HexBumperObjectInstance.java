package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.audio.GameSound;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Hexagonal Bumper Object (ObjD7).
 * <p>
 * A hexagonal bumper that bounces the player in one of 4 cardinal directions
 * based on approach angle. Unlike the round bumper (Obj44), this uses quantized
 * directional bounce physics.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 59329-59510
 * <ul>
 *   <li>ObjD7_Init: line 59341</li>
 *   <li>ObjD7_BouncePlayerOff: line 59383</li>
 * </ul>
 *
 * <h3>ROM Constants</h3>
 * <table border="1">
 *   <tr><th>Property</th><th>Value</th><th>ROM Reference</th></tr>
 *   <tr><td>Object ID</td><td>0xD7</td><td>ObjPtr_Bumper</td></tr>
 *   <tr><td>Bounce Velocity</td><td>$800 (2048)</td><td>line 59446</td></tr>
 *   <tr><td>Collision Flags</td><td>$CA</td><td>line 59349</td></tr>
 *   <tr><td>Collision Box</td><td>16x8 pixels</td><td>Touch_Sizes[0x0A]</td></tr>
 *   <tr><td>width_pixels</td><td>$10 (16 px)</td><td>line 59347</td></tr>
 *   <tr><td>Sound</td><td>SndID_Bumper (0xB4)</td><td>line 59428</td></tr>
 * </table>
 *
 * <h3>Art Data</h3>
 * <ul>
 *   <li>Art: ArtNem_CNZHexBumper (art/nemesis/Hexagonal bumper from CNZ.nem)</li>
 *   <li>Mappings: ObjD7_MapUnc_2C626 (mappings/sprite/objD7.asm)</li>
 *   <li>Frame 0: 4 pieces, 48x32 px (idle)</li>
 *   <li>Frame 1: 4 pieces, 48x28 px (vertical squeeze)</li>
 *   <li>Frame 2: 4 pieces, 48x32 px (horizontal squeeze)</li>
 * </ul>
 *
 * <h3>Subtypes</h3>
 * <ul>
 *   <li>$00: Stationary bumper</li>
 *   <li>$01: Moving bumper (oscillates +/- $60 pixels horizontally)</li>
 * </ul>
 *
 * <h3>Physics (4-Direction Quantized Bounce)</h3>
 * <pre>
 * angle = CalcAngle(bumper_x - player_x, bumper_y - player_y)
 * angle = (angle + $20) &amp; $C0  ; quantize to 4 directions
 *
 * Direction mapping:
 *   $00: Left  - x_vel = -$800, y_vel = 0
 *   $40: Down  - x_vel = +/-$200 (position-based), y_vel = -$800
 *   $80: Right - x_vel = $800, y_vel = 0
 *   $C0: Up    - x_vel = +/-$200 (position-based), y_vel = $800
 * </pre>
 *
 * @see BumperObjectInstance Round bumper with radial physics
 * @see BonusBlockObjectInstance Drop target with hit tracking
 */
public class HexBumperObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, RewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /**
     * Bounce velocity magnitude = $800 (2048 in 8.8 fixed point).
     * <p>
     * ROM Reference: s2.asm line 59446
     */
    private static final int BOUNCE_VELOCITY = 0x800;

    /**
     * Secondary X velocity for up/down bounces = $200 (512).
     * <p>
     * ROM Reference: s2.asm lines 59453, 59489
     */
    private static final int SECONDARY_X_VELOCITY = 0x200;

    /**
     * Collision box half-width = 16 pixels.
     * <p>
     * ROM Reference: Touch_Sizes[0x0A] at s2.asm line 84574
     * Touch_Sizes values are half-width, half-height = $10, 8 = 32x16 total box.
     */
    private static final int COLLISION_HALF_WIDTH = 16;

    /**
     * Collision box half-height = 8 pixels.
     * <p>
     * ROM Reference: Touch_Sizes[0x0A] at s2.asm line 84574
     */
    private static final int COLLISION_HALF_HEIGHT = 8;

    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            true,
            false,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    // ========================================================================
    // Subtype Constants
    // ========================================================================

    /** Subtype $00: Stationary bumper */
    private static final int SUBTYPE_STATIONARY = 0x00;

    /** Subtype $01: Moving bumper (oscillates horizontally) */
    private static final int SUBTYPE_MOVING = 0x01;

    /** Movement range for moving subtype = $60 (96 pixels each direction) */
    private static final int MOVEMENT_RANGE = 0x60;

    // ========================================================================
    // Animation Constants
    // ========================================================================

    /** Frame 0: Idle state */
    private static final int FRAME_IDLE = 0;

    /** Frame 1: Vertical squeeze (used for up/down bounce) */
    private static final int FRAME_VERTICAL_SQUEEZE = 1;

    /** Frame 2: Horizontal squeeze (used for left/right bounce) */
    private static final int FRAME_HORIZONTAL_SQUEEZE = 2;

    /** Duration of hit animation in frames */
    private static final int ANIM_DURATION = 8;

    // ========================================================================
    // Direction Constants (after quantization)
    // ========================================================================

    /** Quantized direction: Left */
    private static final int DIR_LEFT = 0x00;

    /** Quantized direction: Down */
    private static final int DIR_DOWN = 0x40;

    /** Quantized direction: Right */
    private static final int DIR_RIGHT = 0x80;

    /** Quantized direction: Up */
    private static final int DIR_UP = 0xC0;

    // ========================================================================
    // Instance State
    // ========================================================================

    private int animFrame = FRAME_IDLE;
    private int animTimer = 0;
    private int collisionProperty = 0;

    // Moving subtype state
    private int baseX;
    private int currentX;
    private int minX;
    private int maxX;
    private int movementDirection = 1; // 1 = right, -1 = left

    public HexBumperObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        initMovement();
    }

    @Override
    public HexBumperObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HexBumperObjectInstance(ctx.spawn(), getName());
    }

    private void initMovement() {
        baseX = spawn.x();
        currentX = baseX;
        minX = baseX - MOVEMENT_RANGE;
        maxX = baseX + MOVEMENT_RANGE;
        // ROM: x_flip status determines initial direction
        boolean xFlip = (spawn.renderFlags() & 0x1) != 0;
        movementDirection = xFlip ? -1 : 1;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        processPendingBounce(playerEntity);

        // Update movement for moving subtype
        if ((spawn.subtype() & 0xFF) == SUBTYPE_MOVING) {
            updateMovement();
        }

        // Update animation timer
        if (animTimer > 0) {
            animTimer--;
            if (animTimer == 0) {
                animFrame = FRAME_IDLE;
            }
        }
    }

    private void processPendingBounce(PlayableEntity playerEntity) {
        int pending = collisionProperty;
        if (pending == 0) {
            return;
        }

        if ((pending & 0x01) != 0 && playerEntity instanceof AbstractPlayableSprite player) {
            applyBounce(player);
        }
        if ((pending & 0x02) != 0) {
            if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick) {
                applyBounce(sidekick);
            }
        }
        collisionProperty = 0;
    }

    private void updateMovement() {
        // ROM Reference: s2.asm lines 59432-59456
        // Moves 1 pixel per frame, reverses direction at bounds
        currentX += movementDirection;
        if (currentX <= minX) {
            currentX = minX;
            movementDirection = 1;
        } else if (currentX >= maxX) {
            currentX = maxX;
            movementDirection = -1;
        }
    }

    /**
     * Check collision using rectangular hitbox.
     * <p>
     * ROM uses collision_flags $CA which maps to Touch_Sizes[0x0A].
     * Touch_Sizes values are half-width, half-height = $10, 8 = 32x16 total box.
     * <p>
     * Note: Player getX()/getY() returns top-left corner, so we use getCentreX()/getCentreY()
     * to compare with the bumper's center position.
     */
    private boolean checkCollision(AbstractPlayableSprite player) {
        int currentX = getCurrentX();
        int dx = Math.abs(player.getCentreX() - currentX);
        int dy = Math.abs(player.getCentreY() - spawn.y());

        int playerHalfWidth = 8; // Approximate
        int playerHalfHeight = player.getYRadius();

        return dx < (COLLISION_HALF_WIDTH + playerHalfWidth) &&
               dy < (COLLISION_HALF_HEIGHT + playerHalfHeight);
    }

    private int getCurrentX() {
        // For moving subtype, return tracked current position
        // For stationary, return spawn position (currentX == spawn.x())
        return currentX;
    }

    @Override
    public int getX() {
        return getCurrentX();
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return (spawn.subtype() & 0xFF) == SUBTYPE_MOVING;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        if ((spawn.subtype() & 0xFF) != SUBTYPE_MOVING) {
            return isRomOutOfRange(getCurrentX(), cameraX);
        }
        // Moving ObjD7 does not tail-call MarkObjGone. ROM tests both movement
        // bounds, objoff_30/objoff_32, and deletes only when both are outside
        // the camera window (docs/s2disasm/s2.asm:59489-59510).
        return isRomOutOfRange(minX, cameraX) && isRomOutOfRange(maxX, cameraX);
    }

    private boolean isRomOutOfRange(int objectX, int cameraX) {
        int objRounded = objectX & 0xFF80;
        int screenRounded = (cameraX - 128) & 0xFF80;
        int distance = (objRounded - screenRounded) & 0xFFFF;
        // 0x280 (640) = 128 + 320 + 192 at native; width-driven for widescreen
        // (viewportWidth() is 320 at NATIVE_4_3). See KNOWN_DISCREPANCIES entry #14.
        return distance > (128 + viewportWidth() + 192);
    }

    @Override
    public int getCollisionFlags() {
        // ROM collision_flags is $CA (Touch_Sizes[$0A]), but the engine's
        // high-bit category dispatch would treat $C0 as automatic boss bounce.
        // Route it as listener-only SPECIAL while preserving the size index.
        return 0x40 | 0x0A;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        return true;
    }

    @Override
    public boolean requiresRenderFlagForTouch() {
        // S2 TouchResponse checks collision_flags directly, with no
        // render_flags.on_screen gate before Touch_Special (s2.asm:84537-84551).
        // ObjD7 relies on that path: Touch_Special increments
        // collision_property, then ObjD7_Main consumes P1/P2 bits
        // (s2.asm:59387-59399, 85022-85098).
        return false;
    }

    @Override
    public boolean enablesPostSpecialTouchAirborneSideVelocityPreservation() {
        return true;
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null || player.isHurt() || player.getDead()) {
            return;
        }
        // ROM TouchResponse only sets collision_property bits; ObjD7_Main later
        // consumes bit 0 for Sonic and bit 1 for Tails before applying the
        // bounce (s2.asm:59365-59382). Applying the bounce immediately during
        // the player slot makes Tails CPU sample Sonic's post-bounce x_vel too
        // early on CNZ ObjD7 frames.
        if (player.isCpuControlled()) {
            collisionProperty |= 0x02;
        } else {
            collisionProperty |= 0x01;
        }
    }

    /**
     * Apply 4-direction quantized bounce to player.
     * <p>
     * ROM Reference: ObjD7_BouncePlayerOff at s2.asm line 59383
     * <p>
     * Physics:
     * <ol>
     *   <li>Calculate angle from bumper to player</li>
     *   <li>Add $20 and mask with $C0 to quantize to 4 directions</li>
     *   <li>Apply fixed velocity based on direction</li>
     * </ol>
     */
    private void applyBounce(AbstractPlayableSprite player) {
        int currentX = getCurrentX();

        // Calculate direction from bumper to player center
        int dx = currentX - player.getCentreX();
        int dy = spawn.y() - player.getCentreY();

        // Calculate angle (0-255 range, 0 = right, 64 = down, 128 = left, 192 = up)
        double radians = StrictMath.atan2(dy, dx);
        int angle = (int) ((radians / (2 * StrictMath.PI)) * 256) & 0xFF;

        // Quantize to 4 directions: add $20, mask with $C0
        int quantized = (angle + 0x20) & 0xC0;

        int xVel = player.getXSpeed();
        int yVel = player.getYSpeed();

        switch (quantized) {
            case DIR_LEFT:
                xVel = -BOUNCE_VELOCITY;
                animFrame = FRAME_HORIZONTAL_SQUEEZE;
                break;

            case DIR_DOWN:
                // ROM: subi.w #$200,x_vel; addi.w #$400,x_vel when the player
                // is right of the bumper (d1 = obj.x - player.x is negative;
                // s2.asm:59422-59427).
                xVel -= SECONDARY_X_VELOCITY;
                if (player.getCentreX() > currentX) {
                    xVel += SECONDARY_X_VELOCITY * 2;
                }
                yVel = -BOUNCE_VELOCITY;
                animFrame = FRAME_VERTICAL_SQUEEZE;
                break;

            case DIR_RIGHT:
                xVel = BOUNCE_VELOCITY;
                animFrame = FRAME_HORIZONTAL_SQUEEZE;
                break;

            case DIR_UP:
                // Same relative x_vel adjustment as ObjD7_BounceDown, then
                // y_vel = +$800 (s2.asm:59442-59453).
                xVel -= SECONDARY_X_VELOCITY;
                if (player.getCentreX() > currentX) {
                    xVel += SECONDARY_X_VELOCITY * 2;
                }
                yVel = BOUNCE_VELOCITY;
                animFrame = FRAME_VERTICAL_SQUEEZE;
                break;
        }

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);

        // Set player state
        // ObjD7_BounceEnd sets in-air, clears rolljumping/pushing, and clears
        // jumping after the velocity write (s2.asm:59440-59453).
        player.setAir(true);
        player.setRollingJump(false);
        player.setJumping(false);
        player.setPushing(false);

        // Trigger animation
        animTimer = ANIM_DURATION;

        // Play sound
        services().playSfx(GameSound.BUMPER);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.HEX_BUMPER);
        if (renderer != null) {
            boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
            boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
            renderer.drawFrameIndex(animFrame, getCurrentX(), spawn.y(), hFlip, vFlip);
        }
    }
}
