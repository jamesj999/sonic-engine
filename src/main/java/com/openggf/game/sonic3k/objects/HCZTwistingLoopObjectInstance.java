package com.openggf.game.sonic3k.objects;

import com.openggf.game.GroundMode;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * HCZ Twisting Loop — invisible controller that captures the player and
 * spirals them through S-curve water tubes.
 *
 * <p>ROM: Obj_HCZTwistingLoop (sonic3k.asm lines 76425-76744).
 *
 * <p>The object is invisible (no art/mappings/rendering). It detects when a
 * player enters its trigger zone, forces them into a rolling state, then
 * moves them along a sine-wave path defined by phase sequence tables.
 *
 * <p>Subtype encoding: bits 0-6 = index into the loop definition table (0-7),
 * bit 7 = reverse entry flag (player must be moving left).
 *
 * <p>Two instances at the HCZ1 boss arena (X=$3418/$3440, Y=$07F4, subtype $38)
 * carry Sonic downward into the Act 2 starting area after the miniboss.
 */
public class HCZTwistingLoopObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(HCZTwistingLoopObjectInstance.class.getName());

    // =========================================================================
    // Loop definition table — ROM: word_3903C (sonic3k.asm line 76398)
    // Each entry: centerX, topY, pointer to phase sequence table
    // =========================================================================

    private record LoopDef(int centerX, int topY, int seqOffset) {}

    // Phase sequence tables — ROM: byte_39006, byte_3900E, byte_3901C, byte_39028
    // Values are phase IDs (0=exit, 2/4/6/8/A/C/E = active phases).
    // Player's vertical progress / $60 indexes into the table.
    //
    // CRITICAL: In the ROM these tables are contiguous in memory. The ROM does
    // no bounds checking on the table index — when progress temporarily exceeds
    // the table, the lookup reads into the NEXT table's data (getting valid
    // phase IDs, not the terminator). We replicate this by storing all tables
    // in one contiguous array and using offsets, matching ROM memory layout.
    // ROM layout (with `even` padding): SEQ_0(8) + SEQ_1(14) + SEQ_2(12) + SEQ_3(20) = 54 bytes
    private static final int[] SEQ_DATA = {
            // byte_39006 (offset 0): SEQ_0
            2, 4, 4, 4, 4, 4, 0xC, 0,
            // byte_3900E (offset 8): SEQ_1 — 13 bytes + 1 pad byte from `even`
            2, 4, 6, 6, 6, 6, 8, 8, 8, 8, 0xA, 0xA, 0, 0,
            // byte_3901C (offset 22): SEQ_2 — 12 bytes, already even
            2, 4, 6, 6, 6, 6, 8, 8, 8, 8, 0xA, 0,
            // byte_39028 (offset 34): SEQ_3 — 20 bytes, already even
            2, 4, 6, 6, 6, 6, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 0xE, 0,
    };
    private static final int SEQ_0_OFF = 0;
    private static final int SEQ_1_OFF = 8;
    private static final int SEQ_2_OFF = 22;
    private static final int SEQ_3_OFF = 34;

    private static final LoopDef[] LOOP_DEFS = {
            new LoopDef(0x0840, 0x0120, SEQ_0_OFF),  // index 0
            new LoopDef(0x1540, 0x0620, SEQ_1_OFF),  // index 1
            new LoopDef(0x1740, 0x03A0, SEQ_1_OFF),  // index 2
            new LoopDef(0x1CC0, 0x0620, SEQ_0_OFF),  // index 3
            new LoopDef(0x1FC0, 0x02A0, SEQ_2_OFF),  // index 4
            new LoopDef(0x24C0, 0x0220, SEQ_3_OFF),  // index 5
            new LoopDef(0x26C0, 0x0120, SEQ_0_OFF),  // index 6
            new LoopDef(0x3040, 0x0620, SEQ_1_OFF),  // index 7
    };

    // =========================================================================
    // Player capture constants — ROM: loc_39158
    // =========================================================================

    /** ROM: move.b #1,object_control(a1) — object controls player, player CAN jump out */
    private static final int OBJECT_CONTROL_ALLOW_JUMP = 1;

    /** ROM: move.b #2,anim(a1) — rolling animation */
    private static final int ANIM_ROLLING = 2;

    /** ROM: move.b #$E,y_radius(a1) — rolling collision radii */
    private static final int ROLL_Y_RADIUS = 0x0E;
    private static final int ROLL_X_RADIUS = 0x07;

    /** ROM: move.b #$28,angle(a1) — initial angle on capture (~56 degrees) */
    private static final byte CAPTURE_ANGLE = 0x28;

    // =========================================================================
    // Physics constants — ROM: sub_39208
    // =========================================================================

    /** ROM: muls.w #$50,d0 — slope gravity strength */
    private static final int SLOPE_GRAVITY = 0x50;

    /** ROM: cmpi.w #$1800,ground_vel(a1) — speed cap (rightward only) */
    private static final int SPEED_CAP = 0x1800;

    // =========================================================================
    // Phase positioning constants
    // =========================================================================

    /** ROM: muls.w #-$2800,d0 — sine amplitude for X oscillation */
    private static final int SINE_AMPLITUDE = -0x2800;

    /** ROM: mulu.w #$DD,d0 — sine frequency scalar for phase 2 */
    private static final int FREQ_DD = 0xDD;

    /** ROM: mulu.w #$AA,d0 / muls.w #$AA,d0 — frequency/slope scalar */
    private static final int FREQ_AA = 0xAA;

    /** ROM: subi.w #$16,d0 — phase 2 exit threshold (22 pixels) */
    private static final int PHASE2_EXIT_THRESHOLD = 0x16;

    /** ROM: #$60 — pixels per sequence table entry */
    private static final int PROGRESS_PER_ENTRY = 0x60;

    // =========================================================================
    // Per-player state
    // =========================================================================

    /**
     * Tracks one player's state within the twisting loop.
     * ROM: 6-byte block at objoff_34 (P1) / objoff_3A (P2).
     */
    private static class PlayerState {
        /** Current phase ID: 0=detection, 2/4/6/8/A/C/E = active */
        int phase;
        /**
         * 16.16 fixed-point vertical progress from topY.
         * ROM layout: upper word = pixels, lower word = subpixels.
         * Written as word (move.w d1,2(a4)), advanced as long (add.l d0,2(a4)).
         */
        int progressFixed;

        int getProgressPixels() {
            return progressFixed >> 16;
        }
    }

    // =========================================================================
    // Instance state
    // =========================================================================

    private int subtype;
    private boolean reverseEntry;  // bit 7 of subtype
    private boolean objectFlippedX;  // ROM: status bit 0 from layout render_flags
    private final LoopDef loopDef;
    private final PlayerState p1State = new PlayerState();
    private final PlayerState p2State = new PlayerState();

    public HCZTwistingLoopObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZTwistingLoop");
        this.subtype = spawn.subtype();
        this.reverseEntry = (subtype & 0x80) != 0;
        this.objectFlippedX = (spawn.renderFlags() & 1) != 0;
        int tableIndex = subtype & 0x7F;
        if (tableIndex >= LOOP_DEFS.length) {
            LOG.warning("HCZTwistingLoop: subtype 0x" + Integer.toHexString(subtype)
                    + " index " + tableIndex + " out of range, clamping to 0");
            tableIndex = 0;
        }
        this.loopDef = LOOP_DEFS[tableIndex];
        LOG.info("HCZTwistingLoop spawned at (" + spawn.x() + "," + spawn.y()
                + ") subtype=0x" + Integer.toHexString(subtype)
                + " tableIndex=" + tableIndex
                + " loopCenter=(" + loopDef.centerX + "," + loopDef.topY + ")"
                + " reverseEntry=" + reverseEntry + " flipped=" + objectFlippedX);
    }

    @Override
    public HCZTwistingLoopObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZTwistingLoopObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) return;

        // Process Player 1
        PlayableEntity queriedMainPlayer = services().playerQuery().mainPlayerOrNull();
        AbstractPlayableSprite player1 = queriedMainPlayer instanceof AbstractPlayableSprite sprite ? sprite : null;
        if (player1 == null && playerEntity instanceof AbstractPlayableSprite sprite) {
            player1 = sprite;
        }
        if (player1 != null) {
            processPlayer(player1, p1State);
        }

        // Process Player 2 (sidekick)
        PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
        if (nativeP2 instanceof AbstractPlayableSprite sidekick && sidekick != player1) {
            processPlayer(sidekick, p2State);
        }

        // ROM: loc_3909C — if both players are in phase 0 (not captured),
        // call Delete_Sprite_If_Not_In_Range. ObjectManager's ordinary
        // off-screen path now clears the placement-loaded state and permits a
        // later respawn, so only an actively captured player needs to make the
        // controller persistent.
    }

    // =========================================================================
    // Per-player processing — ROM: sub_390C2
    // =========================================================================

    private void processPlayer(AbstractPlayableSprite player, PlayerState state) {
        // Dispatch to current phase handler
        switch (state.phase) {
            case 0x00 -> phaseDetect(player, state);
            case 0x02 -> phaseAscendingSpiral(player, state);
            case 0x04 -> phaseAngledDescent(player, state);
            case 0x06 -> phaseLinearOffset(player, state, 0xC0);
            case 0x08 -> phaseSineCurveOffset(player, state);
            case 0x0A -> phaseLinearOffset(player, state, 0x240);
            case 0x0C -> phaseLinearOffset(player, state, 0x240);
            case 0x0E -> phaseLinearOffset(player, state, 0x540);
        }

        // ROM: sub_390C2 — after phase dispatch, if phase != 0, apply physics
        // and update phase from sequence table
        if (state.phase != 0) {
            applySlopeGravity(player);
            updatePhaseFromProgress(player, state);
        }
    }

    /**
     * Advances the phase based on vertical progress through the sequence table.
     * ROM: sub_390C2 lines after phase dispatch.
     */
    private void updatePhaseFromProgress(AbstractPlayableSprite player, PlayerState state) {
        int progressPixels = state.getProgressPixels();
        int tableIndex = progressPixels / PROGRESS_PER_ENTRY;

        // ROM: divu.w #$60,d0 produces an unsigned result. Negative progress
        // would give a huge index in the ROM; clamp negative to 0 for safety.
        if (tableIndex < 0) tableIndex = 0;

        // ROM: move.b (a2,d0.w),(a4) — NO bounds checking. When the index
        // exceeds the table, it reads into adjacent table data in ROM memory.
        // We replicate this by indexing into the contiguous SEQ_DATA array.
        int dataIndex = loopDef.seqOffset + tableIndex;
        if (dataIndex >= SEQ_DATA.length) dataIndex = SEQ_DATA.length - 1;

        int newPhase = SEQ_DATA[dataIndex];
        state.phase = newPhase;

        // Phase 0 = exit the loop
        if (newPhase == 0) {
            releasePlayer(player);
        }
    }

    // =========================================================================
    // Phase 0: Detection — ROM: loc_39102
    // =========================================================================

    private void phaseDetect(AbstractPlayableSprite player, PlayerState state) {
        if (player.isObjectControlled()) return;

        if (reverseEntry) {
            detectReverseEntry(player, state);
        } else {
            detectNormalEntry(player, state);
        }
    }

    /**
     * Normal entry detection — ROM: loc_39102 (bit 7 clear).
     * X window: 16px centered on object. Y window: 48px below object.
     * Object status bit 0 (x-flip) determines required direction.
     */
    private void detectNormalEntry(AbstractPlayableSprite player, PlayerState state) {
        int dx = player.getCentreX() - getX();
        int dy = player.getCentreY() - getY();

        // ROM: addi.w #8,d0; cmpi.w #$10,d0 — unsigned check for 0..16 range
        if (dx + 8 < 0 || dx + 8 >= 0x10) return;

        // ROM: cmpi.w #$30,d1 — unsigned check for 0..48 range
        if (dy < 0 || dy >= 0x30) return;

        // ROM: btst #0,status(a0) — check object X-flip from layout render_flags
        short gSpeed = player.getGSpeed();

        if (objectFlippedX) {
            // Player must be moving LEFT
            if (gSpeed >= 0) return;
            if (player.getXSpeed() >= 0) return;
            // ROM: neg.w ground_vel(a1)
            player.setGSpeed((short) -gSpeed);
        } else {
            // Player must be moving RIGHT
            if (gSpeed < 0) return;
        }

        capturePlayer(player, state);
    }

    /**
     * Reverse entry detection — ROM: loc_39198 (bit 7 set).
     * Wider X window (32px), tighter Y window (16px centered on object Y).
     * Always requires leftward movement.
     */
    private void detectReverseEntry(AbstractPlayableSprite player, PlayerState state) {
        int dx = player.getCentreX() - getX();
        // ROM: addi.w #$10,d0; cmpi.w #$20,d0
        if (dx + 0x10 < 0 || dx + 0x10 >= 0x20) return;

        int dy = player.getCentreY() - getY();

        // ROM: addi.w #$10,d1; cmpi.w #$10,d1
        if (dy + 0x10 < 0 || dy + 0x10 >= 0x10) return;

        if (player.getGSpeed() >= 0) return;

        capturePlayer(player, state);
    }

    /**
     * Captures the player into the loop — ROM: loc_39158.
     */
    private void capturePlayer(AbstractPlayableSprite player, PlayerState state) {
        state.phase = 2;  // ROM: addq.b #2,(a4)

        // The native routine changes status/radius fields without writing x_pos
        // or y_pos. Engine rolling setup can move the centre while it swaps the
        // collision box, so retain the native position words (and their packed
        // subpixels) across that representation change.
        short captureX = player.getCentreX();
        short captureY = player.getCentreY();

        // ROM: move.b #1,object_control(a1) — bits 0-6, movement suppressed but CPU/touch remain allowed.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);

        // ROM: move.b #2,anim(a1) — force rolling animation
        player.setRolling(true);
        player.setAnimationId(ANIM_ROLLING);

        // ROM: move.b #$E,y_radius(a1); move.b #7,x_radius(a1)
        player.applyRollingRadii(false);

        // ROM: bset #Status_Roll,status(a1); bclr #Status_Push,status(a1)
        player.setPushing(false);

        // ROM: move.b #$28,angle(a1) — sets angle to ~56 degrees (downward slope).
        // In the ROM, setting the angle implicitly determines the ground mode
        // (angle 0x28 falls in the GROUND range 0x00-0x3F). The engine tracks
        // ground mode as a separate field, so we must reset it explicitly.
        // Without this, a stale LEFTWALL mode from pre-capture terrain collision
        // persists through the entire capture and causes Sonic to snap to the
        // wall surface on release instead of re-evaluating terrain normally.
        player.setAngle(CAPTURE_ANGLE);
        player.setGroundMode(GroundMode.GROUND);
        player.setAir(false);
        NativePositionOps.writeXPosPreserveSubpixel(player, captureX);
        NativePositionOps.writeYPosPreserveSubpixel(player, captureY);

        // Initial vertical progress = player Y - topY
        // ROM: move.w d1,2(a4) — stores as word in upper half of the 16.16 long
        int dy = player.getCentreY() - loopDef.topY;
        state.progressFixed = dy << 16;

        LOG.fine(() -> "HCZTwistingLoop: captured player at progress=" + dy);
    }

    /**
     * Releases the player from object control — ROM: move.b #0,object_control(a1).
     */
    private void releasePlayer(AbstractPlayableSprite player) {
        ObjectControlState.none().applyTo(player);
        LOG.fine("HCZTwistingLoop: released player");
    }

    // =========================================================================
    // Sine-wave slope gravity — ROM: sub_39208
    // =========================================================================

    /**
     * Decomposes ground_vel by angle into x_vel/y_vel, then applies slope
     * gravity. ROM: sub_39208 (sonic3k.asm lines 76566-76602).
     *
     * <p>Gravity of $50 is applied based on sin(angle). Uphill gravity is
     * quartered. Speed capped at $1800 when moving right.
     */
    private void applySlopeGravity(AbstractPlayableSprite player) {
        int angle = player.getAngle() & 0xFF;
        int gSpeed = player.getGSpeed();

        // Decompose ground_vel into x_vel and y_vel
        // ROM: GetSineCosine returns sin/cos scaled by $100 (256)
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);

        // ROM: muls.w ground_vel(a1),d1; asr.l #8,d1; move.w d1,x_vel(a1)
        int xVel = (cos * gSpeed) >> 8;
        // ROM: muls.w ground_vel(a1),d0; asr.l #8,d0; move.w d0,y_vel(a1)
        int yVel = (sin * gSpeed) >> 8;

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);

        // Calculate slope gravity component
        // ROM: second GetSineCosine, muls.w #$50,d0, asr.l #8,d0
        int gravSin = TrigLookupTable.sinHex(angle);
        int gravComponent = (gravSin * SLOPE_GRAVITY) >> 8;

        if (gSpeed >= 0) {
            // Moving right
            if (gravComponent < 0) {
                // Uphill — quarter the gravity
                gravComponent >>= 2;
            }
            if (gSpeed < SPEED_CAP) {
                player.setGSpeed((short) (gSpeed + gravComponent));
            }
        } else {
            // Moving left
            if (gravComponent >= 0) {
                // Uphill for leftward — quarter the gravity
                gravComponent >>= 2;
            }
            player.setGSpeed((short) (gSpeed + gravComponent));
        }
    }

    // =========================================================================
    // Phase handlers — ROM: off_390F2 jump table targets
    // =========================================================================

    /**
     * Phase 2: Ascending spiral — ROM: loc_3925C.
     * Sine-based X oscillation, linear Y from progress.
     * Exit back if progress < 22 and player moving left.
     */
    private void phaseAscendingSpiral(AbstractPlayableSprite player, PlayerState state) {
        int progress = state.getProgressPixels();
        int d0 = progress - PHASE2_EXIT_THRESHOLD;

        if (d0 < 0) {
            // ROM: player has risen above threshold
            if (player.getGSpeed() < 0) {
                // Moving left = backing out of loop
                state.phase = 0;
                releasePlayer(player);
                // ROM: move.b #$70,angle(a1)
                player.setAngle((byte) 0x70);
                applySlopeGravity(player);
                return;
            }
            d0 = 0;  // Clamp to 0
        }

        // ROM: mulu.w #$DD,d0; lsr.w #8,d0 — map to sine input
        int sineInput = (d0 * FREQ_DD) >>> 8;
        positionWithSine(player, state, sineInput, 0);
    }

    /**
     * Phase 4: Angled descent — ROM: loc_392B6.
     * Different sine frequency scaling from phase 2.
     */
    private void phaseAngledDescent(AbstractPlayableSprite player, PlayerState state) {
        int progress = state.getProgressPixels();
        // ROM: mulu.w #$AA,d0; asr.w #8,d0
        int sineInput = (progress * FREQ_AA) >>> 8;
        positionWithSine(player, state, sineInput, 0);
    }

    /**
     * Phase 8: Second sine curve with offset — ROM: loc_3931E.
     * Progress offset $180, additional X bias of $100.
     */
    private void phaseSineCurveOffset(AbstractPlayableSprite player, PlayerState state) {
        int progress = state.getProgressPixels();
        // ROM: subi.w #$180,d0; mulu.w #$AA,d0; asr.w #8,d0
        int d0 = progress - 0x180;
        if (d0 < 0) d0 = 0;  // mulu treats as unsigned
        int sineInput = (d0 * FREQ_AA) >>> 8;
        // ROM: addi.w #$100,d0 after sine calc
        positionWithSine(player, state, sineInput, 0x100);
    }

    /**
     * Phases 6, A, C, E: Linear horizontal offset — ROM: loc_392EE, loc_3935E, loc_3938E, loc_393BE.
     * X = centerX + ((progress - yOffset) * $AA) >> 8
     */
    private void phaseLinearOffset(AbstractPlayableSprite player, PlayerState state, int yOffset) {
        int progress = state.getProgressPixels();
        // ROM: subi.w #offset,d0; muls.w #$AA,d0; asr.l #8,d0
        int d0 = progress - yOffset;
        int xOffset = (d0 * FREQ_AA) >> 8;  // Signed multiply + arithmetic shift

        int x = loopDef.centerX + xOffset;
        int y = loopDef.topY + progress;

        // ROM: move.w d0,x_pos(a1); move.w d0,y_pos(a1) — center coordinates
        NativePositionOps.writeXPosPreserveSubpixel(player, x);
        NativePositionOps.writeYPosPreserveSubpixel(player, y);

        advanceProgress(player, state);
    }

    // =========================================================================
    // Shared positioning helpers
    // =========================================================================

    /**
     * Positions the player using sine-based X oscillation.
     * ROM pattern: GetSineCosine → muls.w #-$2800,d0 → swap d0 → add centerX
     *
     * @param sineInput angle input for sine lookup (0-255 range after masking)
     * @param xBias     additional X offset (e.g., $100 for phase 8)
     */
    private void positionWithSine(AbstractPlayableSprite player, PlayerState state,
                                  int sineInput, int xBias) {
        // ROM: jsr (GetSineCosine).l — returns sin in d0 scaled by $100
        int sin = TrigLookupTable.sinHex(sineInput & 0xFF);
        // ROM: muls.w #-$2800,d0; swap d0
        // sin is -256..256, multiply by -$2800 = -10240
        // Result range: -2621440..2621440. swap = >> 16 → -40..40 pixels
        int xOffset = (sin * SINE_AMPLITUDE) >> 16;
        xOffset += xBias;

        int x = loopDef.centerX + xOffset;
        int y = loopDef.topY + state.getProgressPixels();

        // ROM: move.w d0,x_pos(a1); move.w d0,y_pos(a1) — center coordinates
        NativePositionOps.writeXPosPreserveSubpixel(player, x);
        NativePositionOps.writeYPosPreserveSubpixel(player, y);

        advanceProgress(player, state);
    }

    /**
     * Advances vertical progress by y_vel in 16.16 fixed-point.
     * ROM: move.w y_vel(a1),d0; ext.l d0; asl.l #8,d0; add.l d0,2(a4)
     */
    private void advanceProgress(AbstractPlayableSprite player, PlayerState state) {
        int yVel = player.getYSpeed();
        state.progressFixed += yVel << 8;
    }

    // =========================================================================
    // Rendering — invisible object, no art
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible controller — no rendering
    }

    @Override
    public boolean isPersistent() {
        // sonic3k.asm loc_3909C tests both per-player phase bytes before
        // Delete_Sprite_If_Not_In_Range. A captured player can carry the
        // invisible controller beyond its placement window, but an idle loop
        // must release its SST slot just like any other placed object.
        return p1State.phase != 0 || p2State.phase != 0;
    }
}
