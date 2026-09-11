package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.CanonicalAnimation;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x67 -- Running Disc spot (SBZ).
 * <p>
 * A small visual spot (blob) that orbits around a fixed center point. The disc
 * itself is part of the level tilemap; this object only renders the moving spot
 * on the disc surface. When Sonic is near the center and on the ground, the object
 * forces a minimum/maximum running speed and sets the {@code stick_to_convex} flag.
 * <p>
 * <b>Subtype encoding (byte):</b>
 * <ul>
 *   <li>Low nibble (bits 0-3): 0 = large disc (spot_distance=$18, radius=$48),
 *       non-zero = small disc (spot_distance=$10, radius=$38)</li>
 *   <li>High nibble (bits 4-7): Angular speed magnitude. Sign-extended and
 *       left-shifted 3 bits to form 16-bit angular velocity added each frame.</li>
 * </ul>
 * <p>
 * <b>Initial angle:</b> Derived from placement render flags (obStatus bits 0-1):
 * <pre>
 *   ror.b #2,d0 / andi.b #$C0,d0
 * </pre>
 * Status bit 0 (hflip) -> angle bit 6 (0x40 = 90 degrees),
 * Status bit 1 (vflip) -> angle bit 7 (0x80 = 180 degrees).
 * <p>
 * <b>Disassembly reference:</b> docs/s1disasm/_incObj/67 Running Disc.asm
 */
public class Sonic1RunningDiscObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // From disassembly: move.b #4,obRender(a0) — render flags (screen-relative coords)
    // From disassembly: move.b #4,obPriority(a0)
    private static final int DISPLAY_PRIORITY = 4;

    // From disassembly: move.b #8,obActWid(a0)
    private static final int ACTIVE_WIDTH = 8;

    // From disassembly: Disc_MoveSonic speed thresholds
    // cmpi.w #$400,d0 / cmpi.w #-$400,d0
    private static final int MIN_SPEED = 0x400;
    // cmpi.w #$F00,d0 / cmpi.w #-$F00,d0
    private static final int MAX_SPEED = 0xF00;

    // Large disc defaults (subtype low nibble == 0)
    // move.b #$18,disc_spot_distance(a0) / move.b #$48,disc_radius(a0)
    private static final int LARGE_SPOT_DISTANCE = 0x18;
    private static final int LARGE_RADIUS = 0x48;

    // Small disc defaults (subtype low nibble != 0)
    // move.b #$10,disc_spot_distance(a0) / move.b #$38,disc_radius(a0)
    private static final int SMALL_SPOT_DISTANCE = 0x10;
    private static final int SMALL_RADIUS = 0x38;

    // disc_origX = objoff_32, disc_origY = objoff_30
    private int origX;
    private int origY;

    // disc_spot_distance = objoff_34: radius for spot orbit (in 8.8 fixed: shifted left 8)
    private int spotDistance;

    // disc_radius = objoff_38: detection radius for attaching Sonic
    private int detectionRadius;

    // objoff_36: angular speed (16-bit, added to angle word each frame)
    private int angularSpeed;

    // 16-bit angle accumulator (high byte used for CalcSine index)
    // Initialized from obAngle which is set from obStatus bits 0-1
    private int angle;

    // Current rendered position of the spot
    private int x;
    private int y;

    // disc_sonic_attached = objoff_3A: whether Sonic is currently attached
    private boolean sonicAttached;

    public Sonic1RunningDiscObjectInstance(ObjectSpawn spawn) {
        super(spawn, "RunningDisc");

        this.origX = spawn.x();
        this.origY = spawn.y();

        int subtype = spawn.subtype() & 0xFF;

        // Subtype low nibble: disc size
        // andi.b #$F,d1 / beq.s .typeis0
        int lowNibble = subtype & 0x0F;
        if (lowNibble == 0) {
            this.spotDistance = LARGE_SPOT_DISTANCE;
            this.detectionRadius = LARGE_RADIUS;
        } else {
            this.spotDistance = SMALL_SPOT_DISTANCE;
            this.detectionRadius = SMALL_RADIUS;
        }

        // Subtype high nibble -> angular speed:
        // andi.b #$F0,d1 / ext.w d1 / asl.w #3,d1 / move.w d1,objoff_36(a0)
        byte highNibbleByte = (byte) (subtype & 0xF0);
        int speed = highNibbleByte; // sign-extended byte to int (ext.w emulation)
        speed = (short) (speed << 3); // asl.w #3 (keep as 16-bit)
        this.angularSpeed = speed;

        // Initial angle from obStatus bits 0-1:
        // move.b obStatus(a0),d0 / ror.b #2,d0 / andi.b #$C0,d0 / move.b d0,obAngle(a0)
        int renderFlags = spawn.renderFlags() & 0x03;
        int initialAngle = rorByte(renderFlags, 2) & 0xC0;
        // obAngle is the high byte of the 16-bit angle word
        this.angle = initialAngle << 8;

        // Calculate initial spot position
        updateSpotPosition();
        updateDynamicSpawn(x, y);
    }

    /**
     * Emulates 68000 ror.b (rotate right byte) operation.
     */
    private static int rorByte(int value, int count) {
        value &= 0xFF;
        count &= 7;
        return ((value >>> count) | (value << (8 - count))) & 0xFF;
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
        if (isDestroyed()) {
            return;
        }

        // Disc_Action: bsr.w Disc_MoveSonic / bsr.w Disc_MoveSpot / bra.w Disc_ChkDel
        updateSonicAttachment(player);
        updateSpotPosition();
        updateDynamicSpawn(x, y);
    }

    /**
     * Checks whether Sonic is within the disc's detection radius and manages
     * the attachment state (speed clamping and stick_to_convex flag).
     * <p>
     * From disassembly: Disc_MoveSonic
     * <pre>
     *   moveq  #0,d2
     *   move.b disc_radius(a0),d2     ; detection radius
     *   move.w d2,d3
     *   add.w  d3,d3                  ; d3 = diameter
     *   ; Check X distance
     *   move.w obX(a1),d0
     *   sub.w  disc_origX(a0),d0
     *   add.w  d2,d0                  ; offset so 0 is at left edge
     *   cmp.w  d3,d0
     *   bhs.s  .detach                ; unsigned compare: outside range
     *   ; Check Y distance
     *   move.w obY(a1),d1
     *   sub.w  disc_origY(a0),d1
     *   add.w  d2,d1
     *   cmp.w  d3,d1
     *   bhs.s  .detach
     *   ; If Sonic is airborne (status bit 1), don't attach
     *   btst   #1,obStatus(a1)
     *   beq.s  .attach
     *   clr.b  disc_sonic_attached(a0)
     *   rts
     * </pre>
     */
    private void updateSonicAttachment(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        int radius = detectionRadius;
        int diameter = radius * 2;

        // Use centre coordinates for range check (player positions are centre coords)
        int dx = player.getCentreX() - origX + radius;
        int dy = player.getCentreY() - origY + radius;

        // Unsigned comparison: if dx < 0 or dx >= diameter, outside range
        if ((dx & 0xFFFF) >= diameter || (dy & 0xFFFF) >= diameter) {
            // .detach: if was attached, clear stick_to_convex
            if (sonicAttached) {
                // clr.b stick_to_convex(a1)
                player.setStickToConvex(false);
                sonicAttached = false;
            }
            return;
        }

        // btst #1,obStatus(a1) — check if Sonic is airborne
        if (player.getAir()) {
            // Airborne: clear attachment but don't clear stick_to_convex
            sonicAttached = false;
            return;
        }

        // .attach:
        if (!sonicAttached) {
            // First frame of attachment
            sonicAttached = true;

            // btst #2,obStatus(a1) — check rolling status
            if (!player.getRolling()) {
                // clr.b obAnim(a1) — publish Walk immediately. This happens
                // after the player's animation pass, so merely relying on the
                // next ordinary walking update preserves the landing frame.
                int walkAnimationId = player.resolveAnimationId(CanonicalAnimation.WALK);
                if (walkAnimationId >= 0) {
                    player.setAnimationId(walkAnimationId);
                }
            }

            // bclr #5,obStatus(a1) — clear "pushing" status
            player.setPushing(false);

            // move.b #id_Run,obPrevAni(a1) — restart Sonic's animation
            player.publishRunAsPreviousAnimation();

            // move.b #1,stick_to_convex(a1) — set stick to convex flag
            player.setStickToConvex(true);
        }

        // loc_155E2: Clamp Sonic's inertia (ground speed) to min/max range
        clampSonicSpeed(player);
    }

    /**
     * Clamps Sonic's ground speed to enforce movement direction and bounds
     * matching the disc's rotation direction.
     * <p>
     * From disassembly:
     * <pre>
     *   move.w obInertia(a1),d0
     *   tst.w  objoff_36(a0)
     *   bpl.s  loc_15608          ; positive speed -> force rightward
     *
     *   ; Negative speed -> force leftward
     *   cmpi.w #-$400,d0
     *   ble.s  loc_155FA          ; already fast enough left
     *   move.w #-$400,obInertia(a1)
     *   rts
     * loc_155FA:
     *   cmpi.w #-$F00,d0
     *   bge.s  locret_15606       ; not too fast
     *   move.w #-$F00,obInertia(a1)
     *   rts
     *
     * loc_15608: ; Positive speed -> force rightward
     *   cmpi.w #$400,d0
     *   bge.s  loc_15616          ; already fast enough right
     *   move.w #$400,obInertia(a1)
     *   rts
     * loc_15616:
     *   cmpi.w #$F00,d0
     *   ble.s  locret_15622       ; not too fast
     *   move.w #$F00,obInertia(a1)
     *   rts
     * </pre>
     */
    private void clampSonicSpeed(AbstractPlayableSprite player) {
        int inertia = player.getGSpeed();

        if (angularSpeed < 0) {
            // Force leftward movement
            if (inertia > -MIN_SPEED) {
                player.setGSpeed((short)-MIN_SPEED);
            } else if (inertia < -MAX_SPEED) {
                player.setGSpeed((short)-MAX_SPEED);
            }
        } else {
            // Force rightward movement
            if (inertia < MIN_SPEED) {
                player.setGSpeed((short)MIN_SPEED);
            } else if (inertia > MAX_SPEED) {
                player.setGSpeed((short)MAX_SPEED);
            }
        }
    }

    /**
     * Updates the spot's rendered position by rotating around the disc center.
     * <p>
     * From disassembly: Disc_MoveSpot
     * <pre>
     *   move.w objoff_36(a0),d0
     *   add.w  d0,obAngle(a0)       ; 16-bit angle accumulation
     *   move.b obAngle(a0),d0       ; read high byte as CalcSine input
     *   jsr    (CalcSine).l         ; d0=sin, d1=cos
     *   move.w disc_origY(a0),d2
     *   move.w disc_origX(a0),d3
     *   moveq  #0,d4
     *   move.b disc_spot_distance(a0),d4
     *   lsl.w  #8,d4                ; d4 = distance * 256
     *   move.l d4,d5
     *   muls.w d0,d4                ; d4 = sin * (distance << 8)
     *   swap   d4                   ; d4 = (sin * distance << 8) >> 16 = sin * distance / 256
     *   muls.w d1,d5                ; d5 = cos * (distance << 8)
     *   swap   d5                   ; d5 = cos * distance / 256
     *   add.w  d2,d4                ; Y = origY + sin * distance / 256
     *   add.w  d3,d5                ; X = origX + cos * distance / 256
     *   move.w d4,obY(a0)
     *   move.w d5,obX(a0)
     * </pre>
     */
    private void updateSpotPosition() {
        // add.w objoff_36(a0),obAngle(a0) — accumulate angular speed
        angle = (angle + angularSpeed) & 0xFFFF;

        // move.b obAngle(a0),d0 — read high byte of angle word
        int angleByte = (angle >> 8) & 0xFF;

        // CalcSine: d0 = sin, d1 = cos
        int sin = TrigLookupTable.sinHex(angleByte);
        int cos = TrigLookupTable.cosHex(angleByte);

        // lsl.w #8,d4 — distance * 256
        int distScaled = spotDistance << 8;

        // muls.w d0,d4 / swap d4 — (sin * distScaled) >> 16
        int yOff = (int) (((long) sin * distScaled) >> 16);
        // muls.w d1,d5 / swap d5 — (cos * distScaled) >> 16
        int xOff = (int) (((long) cos * distScaled) >> 16);

        y = origY + yOff;
        x = origX + xOff;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SBZ_RUNNING_DISC);
        if (renderer == null) return;

        // Render the spot at current position (single frame: frame 0)
        renderer.drawFrameIndex(0, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(DISPLAY_PRIORITY);
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // Disc_ChkDel: out_of_range.s .delete,disc_origX(a0)
        // Uses stored original X (not current X) for range check
        return !isDestroyed() && isInRangeAt(origX);
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw origin anchor point (yellow cross)
        ctx.drawLine(origX - 4, origY, origX + 4, origY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(origX, origY - 4, origX, origY + 4, 1.0f, 1.0f, 0.0f);

        // Draw detection radius (magenta box)
        int left = origX - detectionRadius;
        int right = origX + detectionRadius;
        int top = origY - detectionRadius;
        int bottom = origY + detectionRadius;
        ctx.drawLine(left, top, right, top, 1.0f, 0.0f, 1.0f);
        ctx.drawLine(right, top, right, bottom, 1.0f, 0.0f, 1.0f);
        ctx.drawLine(right, bottom, left, bottom, 1.0f, 0.0f, 1.0f);
        ctx.drawLine(left, bottom, left, top, 1.0f, 0.0f, 1.0f);

        // Draw line from origin to current spot position (cyan)
        ctx.drawLine(origX, origY, x, y, 0.0f, 1.0f, 1.0f);

        // Draw spot center (red cross)
        ctx.drawLine(x - 4, y, x + 4, y, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 1.0f, 0.0f, 0.0f);
    }


}
