package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x46 - MZ Bricks (Marble Zone solid blocks).
 * <p>
 * Solid 32x32 blocks found in Marble Zone. The subtype low 3 bits control behavior:
 * <ul>
 *   <li>Type 0: Static - no movement</li>
 *   <li>Type 1: Wobble using oscillator 5 (v_oscillate+$16); subtype bit 3 reverses direction</li>
 *   <li>Type 2: Proximity wobble - wobbles like type 1, but when Sonic is within $90 pixels,
 *       transitions to type 3 (falling)</li>
 *   <li>Type 3: Falling with gravity ($18/frame); snaps to floor on contact, then transitions to
 *       type 4 (landed on lava/unstable tile) or type 0 (static, on solid ground)</li>
 *   <li>Type 4: Post-landing wobble using oscillator 4 (v_oscillate+$12), with smaller
 *       amplitude (lsr #3)</li>
 * </ul>
 * <p>
 * Solid params: d1=$1B (halfWidth), d2=$10 (airHalfHeight), d3=$11 (groundHalfHeight).
 * <p>
 * Reference: docs/s1disasm/_incObj/46 MZ Bricks.asm
 */
public class Sonic1MzBrickObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.w #$1B,d1
    private static final int HALF_WIDTH = 0x1B;

    // From disassembly: move.w #$10,d2
    private static final int AIR_HALF_HEIGHT = 0x10;

    // From disassembly: move.w #$11,d3
    private static final int GROUND_HALF_HEIGHT = 0x11;

    // From disassembly: move.b #3,obPriority(a0)
    private static final int PRIORITY = 3;

    // From disassembly: move.b #$10,obActWid(a0)
    private static final int ACTIVE_WIDTH = 0x10;

    // Type 2 proximity check: cmpi.w #$90,d0
    private static final int PROXIMITY_TRIGGER_RANGE = 0x90;

    // Type 3 gravity: addi.w #$18,obVelY(a0)
    private static final int FALL_GRAVITY = 0x18;

    // Type 3 floor tile threshold (REV01): cmpi.w #$16A,d0
    // If floor tile index >= this, the brick stays as type 4 (wobbling on lava).
    // If below this, it becomes type 0 (static on solid ground).
    private static final int LAVA_TILE_THRESHOLD = 0x16A;

    // Oscillation indices (mapped from v_oscillate offsets to OscillationManager offsets).
    // v_oscillate+$16 = oscillator 5: getByte((0x16 - 0x02)) = getByte(0x14)
    private static final int OSC_WOBBLE = 0x14;
    // v_oscillate+$12 = oscillator 4: getByte((0x12 - 0x02)) = getByte(0x10)
    private static final int OSC_LANDED_WOBBLE = 0x10;

    // Dynamic position
    private int x;
    private int y;

    // Saved original Y (brick_origY = objoff_30)
    private int origY;

    // Current behavior type (low 3 bits of obSubtype)
    private int behaviorType;

    // Live subtype byte (obSubtype): updated dynamically by behavior transitions.
    // Wobble direction is read from bit 3 of this value each frame.
    private int subtype;

    // Y velocity for type 3 falling (obVelY, in subpixels where 256 = 1px)
    private int yVelocity;

    // 16.16 subpixel state for SpeedToPos Y position updates during falling.
    private final SubpixelMotion.State fallMotion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    public Sonic1MzBrickObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MzBrick");

        this.x = spawn.x();
        this.y = spawn.y();
        this.origY = spawn.y();

        this.subtype = spawn.subtype() & 0xFF;
        this.behaviorType = this.subtype & 0x07;

        this.yVelocity = 0;

        updateDynamicSpawn(x, y);
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
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // From disassembly: tst.b obRender(a0) / bpl.s .chkdel
        // Only process behavior when on-screen (render flag bit 7 set)
        if (!isOnScreen(128)) {
            return;
        }

        switch (behaviorType) {
            case 0 -> { /* Type 0: static - no movement */ }
            case 1 -> updateWobble();
            case 2 -> updateProximityTrigger(player);
            case 3 -> updateFalling();
            case 4 -> updateLandedWobble();
            default -> { /* Types 5-7 not used in MZ placements */ }
        }

        updateDynamicSpawn(x, y);
    }

    /**
     * Type 1 (Brick_Type01): Wobble using oscillator 5.
     * <p>
     * Reads oscillator byte, optionally negates and adds $10 if bit 3 set.
     * Subtracts from origY to get current Y.
     * <pre>
     * move.b (v_oscillate+$16).w,d0
     * btst   #3,obSubtype(a0)
     * beq.s  loc_E8A8
     * neg.w  d0
     * addi.w #$10,d0
     * loc_E8A8:
     * move.w brick_origY(a0),d1
     * sub.w  d0,d1
     * move.w d1,obY(a0)
     * </pre>
     */
    private void updateWobble() {
        int d0 = OscillationManager.getByte(OSC_WOBBLE);
        // btst #3,obSubtype(a0) / beq.s loc_E8A8
        if ((subtype & 0x08) != 0) {
            // neg.w d0 / addi.w #$10,d0
            d0 = (-d0 + 0x10) & 0xFFFF;
        }
        // move.w brick_origY(a0),d1 / sub.w d0,d1 / move.w d1,obY(a0)
        y = origY - d0;
    }

    /**
     * Type 2 (Brick_Type02): Proximity trigger.
     * <p>
     * Checks if Sonic is within $90 pixels horizontally. If so, transitions to type 3
     * (falling). Otherwise, wobbles like type 1.
     * <pre>
     * move.w (v_player+obX).w,d0
     * sub.w  obX(a0),d0
     * bcc.s  loc_E888
     * neg.w  d0
     * loc_E888:
     * cmpi.w #$90,d0
     * bhs.s  Brick_Type01  ; if not in range, just wobble
     * move.b #3,obSubtype(a0) ; in range -> start falling
     * </pre>
     */
    private void updateProximityTrigger(AbstractPlayableSprite player) {
        if (player != null) {
            int playerX = player.getCentreX();
            int distance = Math.abs(playerX - x);
            if (distance < PROXIMITY_TRIGGER_RANGE) {
                // move.b #3,obSubtype(a0) - overwrites ENTIRE subtype byte (clears bit 3)
                subtype = 3;
                behaviorType = 3;
                // Falls through to Brick_Type01 (wobble) on the trigger frame
            }
        }
        // Wobble like type 1 (both when in range and not in range this frame).
        // After trigger: subtype is now 3, bit 3 is cleared -> non-reversed wobble.
        updateWobble();
    }

    /**
     * Type 3 (Brick_Type03): Falling with gravity.
     * <p>
     * Applies SpeedToPos (velocity to position), then adds gravity.
     * Checks floor distance via ObjFloorDist. When floor is hit (d1 < 0):
     * - Snaps Y to floor
     * - Clears velocity
     * - Updates origY
     * - Checks floor tile index: if >= $16A (lava), stays type 4; else becomes type 0 (static)
     * <pre>
     * bsr.w   SpeedToPos
     * addi.w  #$18,obVelY(a0)
     * bsr.w   ObjFloorDist
     * tst.w   d1
     * bpl.w   locret_E8EE
     * add.w   d1,obY(a0)
     * clr.w   obVelY(a0)
     * move.w  obY(a0),brick_origY(a0)
     * move.b  #4,obSubtype(a0)
     * move.w  (a1),d0
     * andi.w  #$3FF,d0
     * cmpi.w  #$16A,d0
     * bcc.s   locret_E8EE
     * move.b  #0,obSubtype(a0)
     * </pre>
     */
    private void updateFalling() {
        // SpeedToPos: Y position update using 16.16 fixed point
        fallMotion.y = y;
        fallMotion.yVel = yVelocity;
        SubpixelMotion.speedToPosY(fallMotion);
        y = fallMotion.y;

        // addi.w #$18,obVelY(a0)
        yVelocity = (short) (yVelocity + FALL_GRAVITY);

        // ObjFloorDist: check floor from object bottom
        // Object height is $F (obHeight), so bottom = Y + $F
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, 0x0F);

        if (!result.foundSurface() || result.distance() >= 0) {
            // No collision yet (d1 >= 0), keep falling
            return;
        }

        // Floor hit: d1 < 0 (negative distance means collision)
        // add.w d1,obY(a0) - snap to floor
        y += result.distance();
        fallMotion.ySub = 0;

        // clr.w obVelY(a0)
        yVelocity = 0;

        // move.w obY(a0),brick_origY(a0)
        origY = y;

        // move.b #4,obSubtype(a0) -- default to type 4 (landed wobble)
        subtype = 4;
        behaviorType = 4;

        // Check floor tile to determine final post-landing behavior
        // move.w (a1),d0 / andi.w #$3FF,d0 / cmpi.w #$16A,d0 / bcc.s locret_E8EE
        int tileIndex = result.tileIndex() & 0x3FF;
        if (tileIndex < LAVA_TILE_THRESHOLD) {
            // move.b #0,obSubtype(a0) - landed on solid ground: become static
            subtype = 0;
            behaviorType = 0;
        }
        // If tileIndex >= threshold (lava/unstable): keep type 4 (landed wobble)
    }

    /**
     * Type 4 (Brick_Type04): Post-landing wobble.
     * <p>
     * Uses oscillator 4 (v_oscillate+$12) with small amplitude (lsr #3).
     * Subtracts from origY.
     * <pre>
     * move.b (v_oscillate+$12).w,d0
     * lsr.w  #3,d0
     * move.w brick_origY(a0),d1
     * sub.w  d0,d1
     * move.w d1,obY(a0)
     * </pre>
     */
    private void updateLandedWobble() {
        int d0 = OscillationManager.getByte(OSC_LANDED_WOBBLE);
        d0 = (d0 & 0xFFFF) >>> 3;
        y = origY - d0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MZ_BRICK);
        if (renderer == null) return;
        renderer.drawFrameIndex(0, x, y, false, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, AIR_HALF_HEIGHT, GROUND_HALF_HEIGHT);
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM uses obActWid ($10) for Solid_Landed / SolidObject_InsideTop,
        // not the collision halfWidth ($1B).
        return ACTIVE_WIDTH;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // Solid_ChkCollision rejects the right edge with BHI, so a player at
        // exactly x_pos + 2*d1 remains a side contact and retains Status_Push.
        return SolidRoutineProfile.fullSolid(false, true, false);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Standard solid collision handled by ObjectManager
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        return !isDestroyed() && isOnScreenX(128);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw solid collision bounds
        ctx.drawRect(x, y, HALF_WIDTH, AIR_HALF_HEIGHT, 0.0f, 1.0f, 0.5f);

        // Draw origin Y marker
        ctx.drawLine(x - 4, origY, x + 4, origY, 1.0f, 0.5f, 0.0f);

        // Label with type info
        String typeLabel = switch (behaviorType) {
            case 0 -> "MZBrick:STATIC";
            case 1 -> "MZBrick:WOBBLE";
            case 2 -> "MZBrick:PROX";
            case 3 -> String.format("MZBrick:FALL v=%d", yVelocity);
            case 4 -> "MZBrick:LANDED";
            default -> String.format("MZBrick:T%d", behaviorType);
        };
        ctx.drawWorldLabel(x, y, -2, typeLabel, DebugColor.CYAN);
    }
}
