package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x5D - Fan (SLZ).
 * <p>
 * An animated fan that blows Sonic away from it in Star Light Zone.
 * The fan cycles through 3 animation frames and applies a horizontal push
 * to Sonic when he is within range. Subtypes 0x02/0x03 are always on;
 * subtypes 0x00/0x01 cycle on/off with a timer (2 seconds on, 3 seconds off).
 * <p>
 * <b>Subtypes:</b>
 * <ul>
 *   <li>Bit 0 of subtype: 0 = fan blows air from right to left (default),
 *       1 = fan blows air from left to right (reverse direction). This bit
 *       also selects the mapping frame offset (0 or 2).</li>
 *   <li>Bit 1 of subtype: 0 = cyclic on/off mode, 1 = always on.</li>
 * </ul>
 * <p>
 * <b>obStatus bit 0</b> (from spawn renderFlags bit 0): facing direction.
 * 0 = fan faces left (blows left), 1 = fan faces right (blows right).
 * <p>
 * <b>Wind physics:</b> The push strength depends on distance from the fan.
 * Close to the fan the push is stronger (doubled). The push is then scaled
 * (asr.w #4) and applied directly to Sonic's X position each frame.
 * The effective range is $A0 pixels horizontally and $70 pixels vertically
 * (centred $60 px above the fan's Y position).
 * <p>
 * Reference: docs/s1disasm/_incObj/5D Fan.asm
 */
public class Sonic1FanObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // From disassembly: move.b #$10,obActWid(a0)
    private static final int ACTIVE_WIDTH = 0x10;

    // From disassembly: move.b #0,obTimeFrame(a0) (animation tick delay)
    private static final int FRAME_DELAY = 0;

    // Animation cycles through 3 unique frames (0, 1, 2) then wraps
    // From disassembly: cmpi.b #3,obAniFrame(a0)
    private static final int ANIM_FRAME_COUNT = 3;

    // Timer values for cyclic on/off mode
    // From disassembly: move.w #120,fan_time(a0) — 2 seconds at 60fps (on duration)
    private static final int ON_DURATION = 120;
    // From disassembly: move.w #180,fan_time(a0) — 3 seconds at 60fps (off duration)
    private static final int OFF_DURATION = 180;

    // Wind range constants from disassembly
    // From: addi.w #$50,d0 / cmpi.w #$F0,d0 — effective X range is $A0 pixels
    private static final int WIND_X_CHECK_OFFSET = 0x50;
    private static final int WIND_X_CHECK_RANGE = 0xF0;

    // From: addi.w #$60,d1 / cmpi.w #$70,d1 — Y range check
    private static final int WIND_Y_CHECK_OFFSET = 0x60;
    private static final int WIND_Y_CHECK_RANGE = 0x70;

    // From: subi.w #$50,d0 — close range threshold
    private static final int CLOSE_RANGE_THRESHOLD = 0x50;

    // From: addi.w #$60,d0 — base push strength before scaling
    private static final int BASE_PUSH_STRENGTH = 0x60;

    // Subtype fields
    private boolean reverseDirection;  // bit 0: reverse wind direction
    private boolean alwaysOn;          // bit 1: always blowing

    // Facing direction from obStatus bit 0 (spawn renderFlags bit 0)
    private boolean facingRight;

    // Cyclic on/off state
    // fan_time = objoff_30: countdown timer
    private int fanTimer;
    // fan_switch = objoff_32: 0 = on, non-zero = off
    private boolean fanOff;

    // Animation state
    private int animFrameTimer;
    private int animFrameIndex;

    public Sonic1FanObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Fan");

        int subtype = spawn.subtype();
        // btst #0,obSubtype(a0) — bit 0 reverses wind direction and frame offset
        this.reverseDirection = (subtype & 0x01) != 0;
        // btst #1,obSubtype(a0) — bit 1 means always on
        this.alwaysOn = (subtype & 0x02) != 0;

        // btst #0,obStatus(a0) — facing direction from spawn render flags
        this.facingRight = (spawn.renderFlags() & 1) != 0;

        // Initial state: fan starts on, timer starts at 0
        this.fanTimer = 0;
        this.fanOff = false;

        // Animation starts at frame 0 with initial tick delay
        this.animFrameTimer = FRAME_DELAY;
        this.animFrameIndex = 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Fan_Delay: handle cyclic on/off (unless always-on subtype)
        if (!alwaysOn) {
            // subq.w #1,fan_time(a0) — subtract 1 from time delay
            fanTimer--;
            if (fanTimer < 0) {
                // Timer expired: toggle fan state
                if (fanOff) {
                    // Was off, now turning on
                    // move.w #120,fan_time(a0) — set delay to 2 seconds
                    fanTimer = ON_DURATION;
                    fanOff = false;
                } else {
                    // Was on, now turning off
                    // move.w #180,fan_time(a0) — set delay to 3 seconds
                    fanTimer = OFF_DURATION;
                    fanOff = true;
                }
            }
        }

        // .blow: tst.b fan_switch(a0) — is fan switched on?
        // bne.w .chkdel — if not, skip wind AND animation logic
        if (fanOff) {
            return;
        }

        // Fan is ON: apply wind if player is present
        if (player != null) {
            applyWind(player);
        }

        // .animate: only runs when fan is ON
        // subq.b #1,obTimeFrame(a0) / bpl.s .chkdel
        animFrameTimer--;
        if (animFrameTimer < 0) {
            // move.b #0,obTimeFrame(a0)
            animFrameTimer = FRAME_DELAY;
            // addq.b #1,obAniFrame(a0)
            animFrameIndex++;
            // cmpi.b #3,obAniFrame(a0) / blo.s .noreset
            if (animFrameIndex >= ANIM_FRAME_COUNT) {
                // move.b #0,obAniFrame(a0)
                animFrameIndex = 0;
            }
        }
    }

    /**
     * Applies wind push to the player sprite.
     * Translates the disassembly's .blow through .movesonic logic.
     * <p>
     * The wind check works as follows:
     * 1. Calculate signed X distance: player.x - fan.x
     * 2. If fan faces left (obStatus bit 0 = 0), negate distance
     * 3. Add $50 to distance, check if < $F0 (effective range: -$50 to +$A0 from fan)
     * 4. Check Y: player must be within $70 pixels of (fan.y - $60)
     * 5. Calculate push strength based on distance (close = stronger)
     * 6. Scale down by >>4 and apply to player X position
     */
    private void applyWind(AbstractPlayableSprite player) {
        int playerX = player.getCentreX();
        int fanX = getX();

        // move.w obX(a1),d0 / sub.w obX(a0),d0
        int dx = playerX - fanX;

        // btst #0,obStatus(a0) — is fan facing right?
        // bne.s .chksonic — if yes, branch (don't negate)
        // neg.w d0
        if (!facingRight) {
            dx = -dx;
        }

        // .chksonic: addi.w #$50,d0
        int adjustedDx = dx + WIND_X_CHECK_OFFSET;

        // cmpi.w #$F0,d0 — is Sonic more than $A0 pixels from the fan?
        // bhs.s .animate — if yes, branch (unsigned comparison)
        if (adjustedDx < 0 || adjustedDx >= WIND_X_CHECK_RANGE) {
            return; // Out of horizontal range
        }

        // move.w obY(a1),d1 / addi.w #$60,d1 / sub.w obY(a0),d1
        // ROM reads obY(a1) at the fan's slot in ExecuteObjects. Objects whose
        // ROM slot is HIGHER than the fan (Obj5D) have not re-positioned Sonic
        // yet — notably the SLZ staircase he rides, whose child pieces are
        // allocated above the fan's slot and re-seat his Y each frame. The
        // engine folds the staircase into a lower slot than the fan, so the live
        // centre Y already reflects this frame's ride-seat lift, putting Sonic in
        // the fan's vertical range one frame early (SLZ2 f2552: engine pushes +2
        // a frame before ROM). Read the player's centre Y as captured at the
        // start of the object exec pass (post-physics, pre-any-object re-seat) so
        // the fan sees the same pre-staircase-seat Y ROM's fan slot observes.
        var objectManager = services().objectManager();
        int playerY = objectManager != null
                ? objectManager.getPlayerCentreYAtExecStart(player)
                : player.getCentreY();
        int fanY = getY();
        int dy = playerY + WIND_Y_CHECK_OFFSET - fanY;

        // bcs.s .animate — branch if result is negative (Sonic is too low)
        if (dy < 0) {
            return;
        }
        // cmpi.w #$70,d1 / bhs.s .animate — branch if Sonic is too high
        if (dy >= WIND_Y_CHECK_RANGE) {
            return;
        }

        // subi.w #$50,d0 — is Sonic more than $50 pixels from the fan?
        int distFromFan = adjustedDx - CLOSE_RANGE_THRESHOLD;

        // bcc.s .faraway — if yes (unsigned: no borrow), branch
        if (distFromFan < 0) {
            // Close range: not.w d0 / add.w d0,d0
            // not.w inverts all bits (~d0), then doubled
            distFromFan = (~distFromFan) * 2;
        }

        // .faraway: addi.w #$60,d0
        int pushStrength = distFromFan + BASE_PUSH_STRENGTH;

        // btst #0,obStatus(a0) — is fan facing right?
        // bne.s .right — if yes, branch
        // neg.w d0
        if (!facingRight) {
            pushStrength = -pushStrength;
        }

        // .right: neg.b d0 — negate low byte only
        // This is a byte-level negate: only the low 8 bits are negated
        pushStrength = negateLowByte(pushStrength);

        // asr.w #4,d0 — arithmetic shift right by 4
        pushStrength = pushStrength >> 4;

        // btst #0,obSubtype(a0) — reverse direction subtype?
        // beq.s .movesonic
        // neg.w d0
        if (reverseDirection) {
            pushStrength = -pushStrength;
        }

        // .movesonic: add.w d0,obX(a1) — push Sonic's x_pos word only
        // (docs/s1disasm/_incObj/5D Fan.asm:75), preserving x_sub.
        player.shiftX(pushStrength);
    }

    /**
     * Emulates 68000 {@code neg.b d0} on a 16-bit signed value.
     * Only the low byte is negated; the high byte is preserved.
     * <p>
     * 68000 neg.b: result_low_byte = 0 - (value & 0xFF), sign extended to word
     * when used with word operations, but here the disassembly does:
     * neg.b d0 then asr.w #4,d0 - the neg.b only modifies the low byte
     * of the data register while preserving the high byte.
     */
    private static int negateLowByte(int value) {
        int highByte = value & 0xFF00;
        int lowByte = (-value) & 0xFF;
        return (short) (highByte | lowByte);
    }

    /**
     * Returns the mapping frame index for the current animation state.
     * From disassembly:
     * <pre>
     * moveq #0,d0
     * btst  #0,obSubtype(a0)
     * beq.s .noflip
     * moveq #2,d0
     * .noflip:
     * add.b obAniFrame(a0),d0
     * move.b d0,obFrame(a0)
     * </pre>
     * So frame = (subtype bit 0 ? 2 : 0) + animFrameIndex
     */
    private int getMappingFrame() {
        int base = reverseDirection ? 2 : 0;
        return base + animFrameIndex;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SLZ_FAN);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // ori.b #4,obRender(a0) — render flag bit 2 = use object coordinates
        // Fan uses obStatus bit 0 for h-flip direction
        renderer.drawFrameIndex(frame, getX(), getY(), facingRight, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawCross(spawn.x(), spawn.y(), 4, 0.5f, 0.8f, 1.0f);

        String state = fanOff ? "OFF" : "ON";
        String dir = facingRight ? "R" : "L";
        String rev = reverseDirection ? " rev" : "";
        ctx.drawWorldLabel(spawn.x(), spawn.y(), -1,
                String.format("Fan %s%s %s f=%d t=%d sub=%02X",
                        dir, rev, state, animFrameIndex, fanTimer, spawn.subtype()),
                DebugColor.CYAN);

        // Draw wind range rectangle (uses drawRect with centre + half-extents)
        int rangeCentreX;
        if (facingRight) {
            rangeCentreX = getX() + (WIND_X_CHECK_RANGE - WIND_X_CHECK_OFFSET * 2) / 2;
        } else {
            rangeCentreX = getX() - (WIND_X_CHECK_RANGE - WIND_X_CHECK_OFFSET * 2) / 2;
        }
        int rangeCentreY = getY() - WIND_Y_CHECK_OFFSET + WIND_Y_CHECK_RANGE / 2;
        int halfW = WIND_X_CHECK_RANGE / 2;
        int halfH = WIND_Y_CHECK_RANGE / 2;
        ctx.drawRect(rangeCentreX, rangeCentreY, halfW, halfH, 0.3f, 0.6f, 1.0f);
    }
}
