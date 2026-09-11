package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * MTZ Nut Object (Obj69) - Screw nut from Metropolis Zone.
 * <p>
 * A nut sits on a threaded column. When the player stands on it and walks
 * to one side, the nut rotates and moves vertically (like turning a screw).
 * Walking left screws the nut downward, walking right screws it upward
 * (or vice versa depending on the player's side).
 * <p>
 * The nut has a maximum travel distance determined by the subtype. If bit 7
 * of the subtype is set, the nut falls off the thread when it reaches the end.
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bits 0-6: Travel range factor. Range = (subtype &amp; 0x7F) * 8 pixels</li>
 *   <li>Bit 7: If set, nut falls off when reaching max travel (routine 4)</li>
 * </ul>
 * <p>
 * <b>Per-player state machine (3 modes):</b>
 * <ul>
 *   <li>Mode 0: Idle - waiting for player to stand on nut</li>
 *   <li>Mode 2: Aligning - centering player on nut (within 16px)</li>
 *   <li>Mode 4: Screwing - player pushes nut, accumulating displacement</li>
 * </ul>
 * <p>
 * <b>Two-player handling:</b> ROM Obj69_Main (s2.asm:53523-53531) runs
 * Obj69_Action TWICE — once for MainCharacter (objoff_38 / p1_standing_bit) and
 * once for Sidekick (objoff_3C / p2_standing_bit) — each gated by that player's
 * own standing bit. This class mirrors that with per-player action state and a
 * per-player standing flag.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm Obj69, lines 53465-53663
 */
public class NutObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // Solid object collision dimensions (from disassembly lines 53532-53534)
    // d1 = $2B (half-width = 43), d2 = $C (y_radius = 12), d3 = $D (ground height = 13)
    private static final int HALF_WIDTH = 0x2B;
    private static final int Y_RADIUS = 0x0C;
    private static final int GROUND_HALF_HEIGHT = 0x0D;

    // Width in pixels (from line 53510: move.b #$20,width_pixels(a0))
    private static final int WIDTH_PIXELS = 0x20;

    // Alignment threshold (from line 53578: cmpi.w #$10,d0)
    private static final int ALIGN_THRESHOLD = 0x10;

    // Falling gravity (from line 53642: addi.w #$38,y_vel(a0))
    private static final int FALL_GRAVITY = 0x38;

    // Routine states
    private static final int ROUTINE_MAIN = 2;
    private static final int ROUTINE_FALLING = 4;
    private static final int ROUTINE_LANDED = 6;

    // Per-player action modes (from Obj69_Modes offset table)
    private static final int MODE_IDLE = 0;
    private static final int MODE_ALIGNING = 2;
    private static final int MODE_SCREWING = 4;

    // Position tracking
    private int x;                  // Current X (constant)
    private int y;                  // Current Y position
    private int baseY;              // objoff_32 - Original Y position
    private int accumulator;        // objoff_34 - Accumulated screw displacement
    private int maxTravel;          // objoff_36 - Max travel distance in pixels
    private int mappingFrame;       // Current animation frame (0-3)

    // Routine state
    private int routine;

    // Falling state (routine 4)
    private int yVel;               // Y velocity (word, in 1/256 pixel units)
    private int ySub;               // Sub-pixel Y accumulator (low 16 bits of 32-bit position)

    // Per-player action state (objoff_38 for P1, objoff_3C for P2).
    // Each stores: byte 0 = mode, byte 1 = direction (0=right push, 1=left push).
    private static final class PlayerActionState {
        int mode = MODE_IDLE;
        int direction = 0;   // 0 = obj right of player, 1 = obj left of player
    }

    private final PlayerActionState p1 = new PlayerActionState();
    private final PlayerActionState p2 = new PlayerActionState();

    // Per-player standing detection. ROM gates each Obj69_Action pass on
    // p1_standing_bit / p2_standing_bit (set by SolidObject when that specific
    // player rides the nut). contactStandingP1/P2 are written by onSolidContact
    // for the matching player and latched into standingP1/standingP2 each update.
    private boolean contactStandingP1;
    private boolean contactStandingP2;
    private boolean standingP1;
    private boolean standingP2;

    // Subtype flags
    private boolean fallsOff;       // Bit 7 of subtype: nut falls off at max travel

    public NutObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        init();
    }

    @Override
    public NutObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new NutObjectInstance(ctx.spawn(), "Nut");
    }

    private void init() {
        x = spawn.x();
        baseY = spawn.y();
        y = baseY;

        // subtype & 0x7F, then << 3 (from lines 53514-53517)
        int subtypeVal = spawn.subtype() & 0xFF;
        fallsOff = (subtypeVal & 0x80) != 0;
        maxTravel = (subtypeVal & 0x7F) << 3;

        accumulator = 0;
        mappingFrame = 0;
        routine = ROUTINE_MAIN;
        yVel = 0;
        ySub = 0;

        p1.mode = MODE_IDLE;
        p1.direction = 0;
        p2.mode = MODE_IDLE;
        p2.direction = 0;

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
    public int getBalanceWidthPixels() {
        // Obj69_Init writes width_pixels=$20 (docs/s2disasm/s2.asm:53981-53991).
        // Sonic_Move/Tails_Move use that SST byte for object-edge balance, not
        // SolidObject's wider d1=$2B collision extent (s2.asm:36285,39359,
        // 53996-54013). Keeping those two widths distinct prevents an interior
        // rider from being classified as balancing at the nut's sprite edge.
        return WIDTH_PIXELS;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, Y_RADIUS, GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Uses JmpTo12_SolidObject (full solid from all sides)
        return false;
    }

    @Override
    public boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        // Obj69 tail-calls the shared S2 SolidObject helper with d1=$2B,
        // d2=$C, d3=$D (docs/s2disasm/s2.asm:53532-53539). SolidObject_cont
        // builds both vertical reject halves from live y_radius(a1)
        // (docs/s2disasm/s2.asm:35156-35169).
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        return !isDestroyed();
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // Obj69 tail-calls S2 SolidObject after its action passes
        // (docs/s2disasm/s2.asm:54006-54013). Once the helper reaches the
        // per-player stale-standing branch, an already-airborne rider clears
        // Status_OnObj and returns before SolidObject_cont regardless of player
        // slot; native P2 has an additional off-screen early return before this
        // branch (s2.asm:35022-35046).
        return player instanceof AbstractPlayableSprite;
    }

    @Override
    public boolean suppressesGroundingRecoveryFromAirborneStaleRide(PlayableEntity player) {
        // Obj69 runs player movement before its late SolidObject tail, so a
        // stale airborne ride must not be converted back into ground support
        // before Obj69 can clear it (s2.asm:35028-35046,54006-54013).
        return player instanceof AbstractPlayableSprite;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (!(contact.standing() || contact.touchTop())) {
            return;
        }
        // ROM sets p1_standing_bit / p2_standing_bit per player; record which
        // engine player is riding so the matching Obj69_Action pass sees standing.
        if (playerEntity == resolveSidekick()) {
            contactStandingP2 = true;
        } else {
            contactStandingP1 = true;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Latch per-player standing detected during this frame's collision pass.
        standingP1 = contactStandingP1;
        standingP2 = contactStandingP2;
        contactStandingP1 = false;
        contactStandingP2 = false;

        switch (routine) {
            case ROUTINE_MAIN -> updateMain();
            case ROUTINE_FALLING -> {
                updateFalling();
                // ROM loc_279FC falls through to loc_278F4 (s2.asm:53655), so the
                // SolidObject + MarkObjGone tail also runs while falling. The
                // engine provides solid collision via SolidObjectProvider and
                // despawn via placement windowing each frame regardless of routine.
            }
            case ROUTINE_LANDED -> {
                // ROM routine 6 IS loc_278F4: SolidObject + MarkObjGone only.
                // Handled by the SolidObjectProvider interface + placement windowing.
            }
        }

        // Mask Y to 11 bits (from line 53531: andi.w #$7FF,y_pos(a0))
        y &= 0x7FF;

        updateDynamicSpawn(x, y);
    }

    /**
     * Resolves the native sidekick (ROM Sidekick), or {@code null} when the
     * active session has no sidekick. Used to attribute solid contacts and to
     * run the second Obj69_Action pass.
     */
    private AbstractPlayableSprite resolveSidekick() {
        try {
            PlayableEntity p2 = services().playerQuery().nativeP2OrNull();
            if (p2 instanceof AbstractPlayableSprite sprite) {
                return sprite;
            }
        } catch (RuntimeException e) {
            // Query layer unavailable - no sidekick.
        }
        return null;
    }

    /**
     * Resolves the native main character (ROM MainCharacter).
     */
    private AbstractPlayableSprite resolveMainCharacter() {
        try {
            PlayableEntity main = services().playerQuery().mainPlayerOrNull();
            if (main instanceof AbstractPlayableSprite sprite) {
                return sprite;
            }
        } catch (RuntimeException e) {
            // Query layer unavailable.
        }
        return null;
    }

    /**
     * Routine 2: Main behavior - process player action.
     * ROM: Obj69_Main (lines 53523-53531) runs Obj69_Action TWICE: once for
     * MainCharacter (objoff_38 / p1_standing_bit) then once for Sidekick
     * (objoff_3C / p2_standing_bit). Each pass is gated by that player's own
     * standing bit.
     */
    private void updateMain() {
        AbstractPlayableSprite mainPlayer = resolveMainCharacter();
        AbstractPlayableSprite sidekickPlayer = resolveSidekick();

        if (mainPlayer != null) {
            processPlayerAction(p1, mainPlayer, isStandingOnThis(mainPlayer, standingP1));
        }
        // Only run the sidekick pass when the routine is still MAIN. (The first
        // pass can advance routine to FALLING; the ROM would still fall through
        // to loc_278F4, but the second Obj69_Action selector reads objoff_3C and
        // would no-op on a falling nut since the sidekick's standing bit clears.)
        if (sidekickPlayer != null && routine == ROUTINE_MAIN) {
            processPlayerAction(p2, sidekickPlayer, isStandingOnThis(sidekickPlayer, standingP2));
        }
    }

    private boolean isStandingOnThis(AbstractPlayableSprite player, boolean callbackStanding) {
        if (callbackStanding) {
            return true;
        }
        try {
            ObjectManager objectManager = services().objectManager();
            // Obj69_Action reads the object's standing bit, but S2 SolidObject
            // clears both Status_OnObj and that bit on a continued-ride exit.
            // CPU Tails can be skipped by the P2 off-screen gate before that
            // branch, so preserve the existing object-bit fallback for CPU
            // sidekicks while requiring live Status_OnObj for P1.
            boolean statusOnObjectVisible = player.isCpuControlled() || player.isOnObject();
            return statusOnObjectVisible
                    && objectManager != null
                    && objectManager.hasObjectStandingBit(player, this);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Process the per-player action state machine.
     * ROM: Obj69_Action (lines 53533-53641)
     * <p>
     * The ROM tracks standing via status bits (p1_standing_bit / p2_standing_bit).
     * If the player is NOT standing, the mode resets to 0.
     */
    private void processPlayerAction(PlayerActionState ps, AbstractPlayableSprite player, boolean standing) {
        // ROM: btst d6,status(a0) / bne.s + / clr.b (a4)
        // If player is not standing on nut, reset mode to idle
        if (!standing) {
            ps.mode = MODE_IDLE;
        }

        switch (ps.mode) {
            case MODE_IDLE -> actionIdle(ps, player, standing);
            case MODE_ALIGNING -> actionAligning(ps, player);
            case MODE_SCREWING -> actionScrewing(ps, player);
        }
    }

    /**
     * Mode 0: Wait for player to stand on the nut.
     * When standing detected, determine push direction and fall through to aligning mode.
     * ROM: loc_2792C (lines 53557-53568) - falls through to loc_2794C
     */
    private void actionIdle(PlayerActionState ps, AbstractPlayableSprite player, boolean standing) {
        if (!standing) {
            return;
        }

        // Advance to aligning mode
        ps.mode = MODE_ALIGNING;

        // Determine direction: 0 = obj is right of player, 1 = obj is left of player
        // ROM: move.w x_pos(a0),d0 / sub.w x_pos(a1),d0
        // d0 = obj_x - player_x
        // bcc.s = if d0 >= 0 (obj right of player), direction stays 0
        // Otherwise (obj left of player), direction = 1
        int dx = x - player.getCentreX();
        ps.direction = (dx < 0) ? 1 : 0;

        // ROM falls through directly to aligning code (loc_2794C) - process immediately
        actionAligning(ps, player);
    }

    /**
     * Mode 2: Align player to nut center.
     * Checks if player is within ALIGN_THRESHOLD of nut center. If not, snaps player.
     * ROM: loc_2794C (lines 53570-53583)
     */
    private void actionAligning(PlayerActionState ps, AbstractPlayableSprite player) {
        // ROM: move.w x_pos(a1),d0 / sub.w x_pos(a0),d0
        int dx = player.getCentreX() - x;

        // If direction == 1 (player to the left), add 0x0F to dx
        // ROM: tst.b 1(a4) / beq.s + / addi.w #$F,d0
        if (ps.direction != 0) {
            dx += 0x0F;
        }

        // Check if within threshold (unsigned comparison)
        // ROM: cmpi.w #$10,d0 / bhs.s + (rts) - if d0 >= 16 (unsigned), skip
        // Only snap and advance when player is within 16px of center
        if ((dx & 0xFFFF) < ALIGN_THRESHOLD) {
            // Aligned - snap player X to nut X and advance to screwing
            // ROM: move.w x_pos(a0),x_pos(a1) / addq.b #2,(a4)
            NativePositionOps.writeXPosPreserveSubpixel(player, x);
            ps.mode = MODE_SCREWING;
        }
    }

    /**
     * Mode 4: Screw movement. Player displacement drives nut rotation and vertical movement.
     * ROM: loc_2796E (lines 53586-53637) and loc_279D4 (lines 53625-53637)
     * <p>
     * dx = player_x - nut_x. If player moves left (dx < 0), nut screws in one direction.
     * If right (dx >= 0), the other direction.
     * The delta is accumulated in objoff_34, which drives both Y position and rotation frame.
     */
    private void actionScrewing(PlayerActionState ps, AbstractPlayableSprite player) {
        int dx = player.getCentreX() - x;

        if (dx < 0) {
            // Player pushed left (ROM: loc_2796E, lines 53586-53622)
            // Accumulate negative displacement
            accumulator += dx;

            // Snap player X to nut center
            NativePositionOps.writeXPosPreserveSubpixel(player, x);

            // Calculate vertical position from accumulator
            // ROM: asr.w #3,d0 / move.w d0,d1
            int shifted = accumulator >> 3;
            int d1 = shifted;

            // Animation frame: (shifted >> 1) & 3
            // ROM: asr.w #1,d0 / andi.w #3,d0
            mappingFrame = (shifted >> 1) & 3;

            // Y position: baseY - shifted (negated: neg.w d1 / add.w objoff_32,d1)
            y = baseY + (-d1);

            // Check if reached max travel
            // ROM: sub.w objoff_32(a0),d1 / ... / cmp.w d0,d1
            int travel = y - baseY;
            if (travel >= maxTravel && maxTravel > 0) {
                // Clamp to max travel
                y = baseY + maxTravel;

                // Reset accumulator: lsl.w #3,d0 / neg.w d0 / move.w d0,objoff_34
                accumulator = -(maxTravel << 3);
                mappingFrame = 0;

                // Check if nut should fall off (bit 7 of subtype)
                if (fallsOff) {
                    routine = ROUTINE_FALLING;
                } else {
                    // Reset player mode to idle
                    ps.mode = MODE_IDLE;
                }
            }
        } else {
            // Player pushed right (ROM: loc_279D4, lines 53625-53637)
            // Same accumulation but positive direction - no max travel check on this path
            accumulator += dx;

            // Snap player X to nut center
            NativePositionOps.writeXPosPreserveSubpixel(player, x);

            // Calculate vertical position from accumulator
            int shifted = accumulator >> 3;

            // Animation frame
            mappingFrame = (shifted >> 1) & 3;

            // Y position: baseY - shifted
            y = baseY + (-shifted);
        }
    }

    /**
     * Routine 4: Falling after detaching from thread.
     * ROM: loc_279FC (lines 53640-53651)
     * <p>
     * Object falls with gravity, checks for floor collision.
     * When it lands, advances to routine 6 (landed/stationary).
     */
    private void updateFalling() {
        // ObjectMove: ROM applies velocity to 32-bit position (y_pos.w:y_sub.w)
        // vel is sign-extended to long, shifted left 8, then added to 32-bit pos
        // This is equivalent to: (y << 16 | ySub) += (yVel << 8)
        int pos32 = (y << 16) | (ySub & 0xFFFF);
        pos32 += (yVel << 8);
        y = (short) (pos32 >> 16);
        ySub = pos32 & 0xFFFF;

        // Add gravity: addi.w #$38,y_vel(a0)
        yVel += FALL_GRAVITY;

        // Check floor collision: jsrto JmpTo_ObjCheckFloorDist
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, 0x0B);

        // ROM: tst.w d1 / bpl.w + (skip if positive/zero = no floor)
        if (floor.distance() < 0) {
            // Landed: add.w d1,y_pos(a0)
            y += floor.distance();
            y &= 0x7FF;
            yVel = 0;
            ySub = 0;
            // Advance to landed routine (routine 4 + 2 = 6)
            routine = ROUTINE_LANDED;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.MTZ_NUT);
        if (renderer != null) {
            // Clamp frame to 0-3
            int frame = mappingFrame & 3;
            renderer.drawFrameIndex(frame, x, y, false, false);
            return;
        }
        appendDebugRender(commands);
    }

    private void appendDebugRender(List<GLCommand> commands) {
        int left = x - HALF_WIDTH;
        int right = x + HALF_WIDTH;
        int top = y - Y_RADIUS;
        int bottom = y + GROUND_HALF_HEIGHT;

        float r = 0.8f, g = 0.6f, b = 0.2f;

        // Collision box
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, top, 0, 0));

        // Center cross
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, x - 4, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, x + 4, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, x, y - 4, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, x, y + 4, 0, 0));

        // Show max travel range
        if (maxTravel > 0) {
            int travelEnd = baseY + maxTravel;
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    0.5f, 0.5f, 0.2f, x - 8, travelEnd, 0, 0));
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    0.5f, 0.5f, 0.2f, x + 8, travelEnd, 0, 0));
        }
    }

    @Override
    public int getPriorityBucket() {
        // From disassembly: move.b #4,priority(a0)
        return RenderPriority.clamp(4);
    }
}
