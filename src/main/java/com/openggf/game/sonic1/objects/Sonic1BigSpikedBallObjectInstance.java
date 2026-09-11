package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.OscillationManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x58 — Giant Spiked Balls (SYZ).
 * <p>
 * Large spiked ball found exclusively in Spring Yard Zone.
 * Hurts the player on contact (obColType = $86 = HURT + size 6).
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>High nibble (bits 4-7): Speed value, multiplied by 8 for circular motion</li>
 *   <li>Low nibble (bits 0-2): Movement type (0-3)</li>
 * </ul>
 * <p>
 * <b>Movement types:</b>
 * <ul>
 *   <li>Type 0: Static — no movement at all</li>
 *   <li>Type 1: Horizontal oscillation — moves left/right using v_oscillate+$E, amplitude $60</li>
 *   <li>Type 2: Vertical oscillation — moves up/down using v_oscillate+$E, amplitude $80 (flipped)</li>
 *   <li>Type 3: Circular motion — orbits around origin using CalcSine, radius $50</li>
 * </ul>
 * <p>
 * <b>Initial angle (type 3):</b> Derived from obStatus bits 0-1:
 * {@code ror.b #2,d0 / andi.b #$C0,d0} → maps bits 0-1 to quadrant starts ($00/$40/$80/$C0).
 * <p>
 * <b>Disassembly reference:</b> docs/s1disasm/_incObj/58 Big Spiked Ball.asm
 */
public class Sonic1BigSpikedBallObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // From disassembly: move.b #$86,obColType(a0) — HURT ($80) + size 6
    private static final int COLLISION_TYPE = 0x86;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // From disassembly: move.b #$18,obActWid(a0)
    private static final int HALF_WIDTH = 0x18;

    // v_oscillate+$E → getByte(0x0C) (oscillator 3)
    private static final int OSC_OFFSET = 0x0C;

    // Type 1 horizontal oscillation amplitude: move.w #$60,d1
    private static final int TYPE1_AMPLITUDE = 0x60;

    // Type 2 vertical oscillation offset: addi.w #$80,d0 (when flipped)
    private static final int TYPE2_FLIP_OFFSET = 0x80;

    // Type 3 circular motion radius: move.b #$50,bball_radius(a0)
    private static final int CIRCLE_RADIUS = 0x50;

    // Stored original position (bball_origX = objoff_3A, bball_origY = objoff_38)
    private int origX;
    private int origY;

    // Current position
    private int x;
    private int y;

    // Movement configuration from subtype
    private int moveType;       // Low nibble bits 0-2: movement type (0-3)
    private int speed;          // bball_speed = high nibble << 3
    private boolean flipped;    // obStatus bit 0 (from spawn renderFlags)

    // Type 3 circular motion angle (bball_radius already fixed at CIRCLE_RADIUS)
    private int angle;                // obAngle: accumulating angle for circular motion

    public Sonic1BigSpikedBallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "BigSpikedBall");

        this.origX = spawn.x();
        this.origY = spawn.y();
        this.x = origX;
        this.y = origY;

        int subtype = spawn.subtype() & 0xFF;

        // move.b obSubtype(a0),d1 / andi.b #$F0,d1 / ext.w d1 / asl.w #3,d1
        // High nibble: speed factor. ext.w sign-extends, asl.w #3 multiplies by 8.
        // Since d1 = (subtype & 0xF0), ext.w produces a signed word.
        // e.g., subtype $30 → d1=$30 → ext.w=$0030 → asl.w#3=$0180
        // e.g., subtype $F0 → d1=$F0 → ext.w=$FFF0 → asl.w#3=$FF80
        int d1 = (byte)(subtype & 0xF0);  // sign-extend the high nibble byte
        this.speed = (short)(d1 << 3);     // asl.w #3 (16-bit shift)

        // Low nibble bits 0-2: movement type
        // move.b obSubtype(a0),d0 / andi.w #7,d0
        this.moveType = subtype & 0x07;

        // obStatus bit 0 from spawn renderFlags
        this.flipped = (spawn.renderFlags() & 0x01) != 0;

        // Initial angle: move.b obStatus(a0),d0 / ror.b #2,d0 / andi.b #$C0,d0
        // obStatus bits 0-1 map to quadrant: 0→$00, 1→$40, 2→$80, 3→$C0
        int status = spawn.renderFlags() & 0xFF;
        int d0 = (status & 0xFF);
        // ror.b #2: rotate right 2 bits within byte
        d0 = ((d0 >>> 2) | (d0 << 6)) & 0xFF;
        // move.b d0,obAngle(a0) — writes to high byte of word (68000 big-endian)
        this.angle = (d0 & 0xC0) << 8;

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
        if (isDestroyed()) {
            return;
        }

        switch (moveType) {
            case 0 -> {} // type00: rts (static, no movement)
            case 1 -> updateType01();
            case 2 -> updateType02();
            case 3 -> updateType03();
            default -> {} // types 4-7: not defined in disasm jump table, but index has only 4 entries
        }

        updateDynamicSpawn(x, y);
    }

    /**
     * Type 01: Horizontal oscillation using v_oscillate+$E.
     * <pre>
     * .type01:
     *   move.w  #$60,d1
     *   moveq   #0,d0
     *   move.b  (v_oscillate+$E).w,d0
     *   btst    #0,obStatus(a0)
     *   beq.s   .noflip1
     *   neg.w   d0
     *   add.w   d1,d0
     * .noflip1:
     *   move.w  bball_origX(a0),d1
     *   sub.w   d0,d1
     *   move.w  d1,obX(a0)
     * </pre>
     */
    private void updateType01() {
        int d0 = OscillationManager.getByte(OSC_OFFSET) & 0xFF;
        if (flipped) {
            // neg.w d0 / add.w d1,d0
            d0 = ((-d0) + TYPE1_AMPLITUDE) & 0xFFFF;
        }
        // sub.w d0,d1 → x = origX - d0
        x = origX - (short) d0;
    }

    /**
     * Type 02: Vertical oscillation using v_oscillate+$E.
     * <pre>
     * .type02:
     *   move.w  #$60,d1
     *   moveq   #0,d0
     *   move.b  (v_oscillate+$E).w,d0
     *   btst    #0,obStatus(a0)
     *   beq.s   .noflip2
     *   neg.w   d0
     *   addi.w  #$80,d0
     * .noflip2:
     *   move.w  bball_origY(a0),d1
     *   sub.w   d0,d1
     *   move.w  d1,obY(a0)
     * </pre>
     */
    private void updateType02() {
        int d0 = OscillationManager.getByte(OSC_OFFSET) & 0xFF;
        if (flipped) {
            // neg.w d0 / addi.w #$80,d0
            d0 = ((-d0) + TYPE2_FLIP_OFFSET) & 0xFFFF;
        }
        // sub.w d0,d1 → y = origY - d0
        y = origY - (short) d0;
    }

    /**
     * Type 03: Circular motion using CalcSine.
     * <pre>
     * .type03:
     *   move.w  bball_speed(a0),d0
     *   add.w   d0,obAngle(a0)
     *   move.b  obAngle(a0),d0
     *   jsr     (CalcSine).l
     *   move.w  bball_origY(a0),d2
     *   move.w  bball_origX(a0),d3
     *   moveq   #0,d4
     *   move.b  bball_radius(a0),d4
     *   move.l  d4,d5
     *   muls.w  d0,d4       ; sin * radius
     *   asr.l   #8,d4
     *   muls.w  d1,d5       ; cos * radius
     *   asr.l   #8,d5
     *   add.w   d2,d4       ; Y = origY + (sin * radius) >> 8
     *   add.w   d3,d5       ; X = origX + (cos * radius) >> 8
     *   move.w  d4,obY(a0)
     *   move.w  d5,obX(a0)
     * </pre>
     */
    private void updateType03() {
        // add.w d0,obAngle(a0) — 16-bit angle accumulation
        angle = (angle + speed) & 0xFFFF;

        // move.b obAngle(a0),d0 — reads high byte of word (68000 big-endian)
        int angleByte = (angle >> 8) & 0xFF;

        // CalcSine: d0 = sine, d1 = cosine
        int sin = TrigLookupTable.sinHex(angleByte);
        int cos = TrigLookupTable.cosHex(angleByte);

        // muls.w d0,d4 / asr.l #8,d4 → yOffset = (sin * radius) >> 8
        int yOffset = (sin * CIRCLE_RADIUS) >> 8;
        // muls.w d1,d5 / asr.l #8,d5 → xOffset = (cos * radius) >> 8
        int xOffset = (cos * CIRCLE_RADIUS) >> 8;

        // add.w d2,d4 / add.w d3,d5
        y = origY + yOffset;
        x = origX + xOffset;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SYZ_BIG_SPIKED_BALL);
        if (renderer == null) return;

        // Render spiked ball (frame 0 from Map_BBall)
        renderer.drawFrameIndex(0, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ---- TouchResponseProvider (hurts the player on contact) ----

    @Override
    public int getCollisionFlags() {
        return COLLISION_TYPE;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // out_of_range.w DeleteObject,bball_origX(a0) — checks origX not current X
        return !isDestroyed() && isOrigXOnScreen();
    }

    private boolean isOrigXOnScreen() {
        return isInRangeAt(origX);
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw original position (yellow cross)
        ctx.drawLine(origX - 4, origY, origX + 4, origY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(origX, origY - 4, origX, origY + 4, 1.0f, 1.0f, 0.0f);

        // Draw collision bounds (red = harmful)
        int left = x - HALF_WIDTH;
        int right = x + HALF_WIDTH;
        int top = y - HALF_WIDTH;
        int bottom = y + HALF_WIDTH;
        ctx.drawLine(left, top, right, top, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(right, top, right, bottom, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(right, bottom, left, bottom, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(left, bottom, left, top, 1.0f, 0.0f, 0.0f);

        // Draw ball center (red cross)
        ctx.drawLine(x - 4, y, x + 4, y, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 1.0f, 0.0f, 0.0f);

        // For type 3, draw the circular orbit path
        if (moveType == 3) {
            ctx.drawLine(origX - CIRCLE_RADIUS, origY, origX + CIRCLE_RADIUS, origY,
                    0.5f, 0.5f, 0.0f);
            ctx.drawLine(origX, origY - CIRCLE_RADIUS, origX, origY + CIRCLE_RADIUS,
                    0.5f, 0.5f, 0.0f);
        }
    }


}
