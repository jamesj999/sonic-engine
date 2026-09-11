package com.openggf.game.sonic3k.features;

import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance.HCZBreakableBarState;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * HCZ Water Tunnel physics — a global per-frame subroutine that pushes the
 * player through water pipes in Hydrocity Zone.
 *
 * <p>ROM equivalent: {@code sub_6F4A} / {@code HCZ_WaterTunnels}
 * (sonic3k.asm:8818–8929).
 *
 * <p>Each frame, the system checks whether the player is within any rectangular
 * tunnel region. If so, it applies the region's velocity to push the player
 * along the pipe, forces the floating animation ($0F = FLOAT2), sets rolling
 * status, clears the double-jump flag, and allows the player to nudge
 * perpendicular to the main flow using the D-pad.
 *
 * <p>When the player exits all tunnel regions and was previously inside one,
 * the exit animation ($1A = HURT) is applied and the wind-tunnel flag is cleared.
 *
 * <h3>Tunnel entry format (ROM: 7 words = 14 bytes per entry)</h3>
 * <pre>
 *   minX, minY, maxX, maxY, xVel, yVel, influenceFlag
 * </pre>
 * {@code influenceFlag}: 0 = D-pad nudges Y (horizontal flow), non-zero = D-pad
 * nudges X (vertical flow).
 *
 * <h3>Velocity-to-position math (two-step displacement)</h3>
 * The ROM applies tunnel velocity twice per frame:
 * <ol>
 *   <li>{@code ext.l d0; lsl.l #8,d0; add.l d0,x_pos(a1)} — direct position
 *       update from the tunnel velocity (step 1).</li>
 *   <li>{@code move.w d0,x_vel(a1)} — stores the tunnel velocity in the player's
 *       velocity field. Standard physics ({@code ObjectMoveAndFall}) then adds
 *       gravity and applies the combined velocity to position (step 2).</li>
 * </ol>
 * We replicate step 1 with the subpixel accumulator, and step 2 by leaving
 * the tunnel velocity in the player's speed fields for physics to use.
 */
public final class HCZWaterTunnelHandler {
    private HCZWaterTunnelHandler() {}

    // =========================================================================
    // Per-player wind-tunnel state (ROM: WindTunnel_flag / WindTunnel_flag_P2)
    // =========================================================================

    private static boolean windTunnelFlagP1;
    private static boolean windTunnelFlagP2;

    /** Influence flag of the tunnel entry each player is currently inside.
     *  0 = horizontal flow (D-pad nudges Y), non-zero = vertical flow (D-pad nudges X). */
    private static int activeTunnelInfluenceP1;
    private static int activeTunnelInfluenceP2;

    private static int exitAnimTimerP1;
    private static int exitAnimTimerP2;

    // =========================================================================
    // Tunnel region tables
    //
    // ROM: HCZ1_WaterTunLocs (sonic3k.asm:8932) — 15 entries
    //      HCZ2_WaterTunLocs (sonic3k.asm:8949) — 2 entries
    //
    // Each row: {minX, minY, maxX, maxY, xVel, yVel, influenceFlag}
    // Velocities are signed 16-bit subpixel values (0x100 = 1 pixel/frame).
    // influenceFlag: 0 = Y nudge (horizontal tunnel), non-zero = X nudge (vertical tunnel).
    // =========================================================================

    private static final int[][] HCZ1_TUNNELS = {
            {0x0380, 0x0580, 0x05A0, 0x05C0,  0x03F0, -0x0020, 0},
            {0x05A0, 0x0560, 0x0A80, 0x05C0,  0x03F0, -0x0010, 0},
            {0x1400, 0x0A80, 0x15A0, 0x0AC0,  0x0400,  0x0000, 0},
            {0x15A0, 0x0A40, 0x1960, 0x0AC0,  0x0400, -0x0040, 0},
            {0x1990, 0x0780, 0x19E0, 0x07F0,  0x0000, -0x0400, 0x100},
            {0x1990, 0x07F0, 0x19F0, 0x0878, -0x0140, -0x0400, 0x100},
            {0x1990, 0x0878, 0x19F0, 0x08FD,  0x0140, -0x0400, 0x100},
            {0x1990, 0x08FD, 0x19F0, 0x0978, -0x0140, -0x0400, 0x100},
            {0x1990, 0x0978, 0x19F0, 0x0A10,  0x0100, -0x0400, 0x100},
            {0x1960, 0x0A10, 0x19D0, 0x0A80,  0x0300, -0x0280, 0x100},
            {0x2B00, 0x0800, 0x2C20, 0x0840,  0x0400,  0x0000, 0},
            {0x2C20, 0x07C0, 0x2EE0, 0x0840,  0x0400, -0x0040, 0},
            {0x2EE0, 0x0790, 0x2F50, 0x0800,  0x0300, -0x0300, 0x100},
            {0x2F00, 0x0700, 0x2F70, 0x0790,  0x0100, -0x0400, 0x100},
            {0x2F30, 0x0680, 0x2F70, 0x0700,  0x0000, -0x0400, 0x100},
    };

    private static final int[][] HCZ2_TUNNELS = {
            {0x3980, 0x0800, 0x3AA0, 0x0840, 0x0400, 0x0000, 0},
            {0x3AA0, 0x07C0, 0x3F00, 0x0840, 0x0400, -0x0040, 0},
    };

    // Table entry field indices
    private static final int MIN_X = 0;
    private static final int MIN_Y = 1;
    private static final int MAX_X = 2;
    private static final int MAX_Y = 3;
    private static final int X_VEL = 4;
    private static final int Y_VEL = 5;
    private static final int INFLUENCE_FLAG = 6;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs the HCZ water tunnel check for all players.
     * Call once per frame from the zone feature provider's pre-physics update
     * when the current zone is HCZ.
     *
     * <p>ROM: {@code sub_6F4A} (sonic3k.asm:8818).
     *
     * @param act current act index (0 = HCZ1, 1 = HCZ2)
     */
    public static void update(int act) {
        update(playerQueryFromGameServices(), act);
    }

    static void update(ObjectPlayerQuery query, int act) {
        // ROM: tst.w (Debug_placement_mode).w / bne locret_705A
        // Checked per-player below.

        AbstractPlayableSprite player = asPlayableSprite(query.mainPlayerOrNull());
        if (player == null) {
            return;
        }

        // ROM: cmpi.w #2,(Player_mode).w / beq.s loc_6F82
        // Special Knuckles-alone handling: uses WindTunnel_flag_P2 slot for P1.
        // In our engine this detail is captured by using separate flag fields.
        // For simplicity, process P1 with its own flag, sidekicks with theirs.

        int[][] tunnels = (act == 0) ? HCZ1_TUNNELS : HCZ2_TUNNELS;

        if (!player.isDebugMode()) {
            windTunnelFlagP1 = processPlayer(player, tunnels, windTunnelFlagP1, 0);
        }
        if (exitAnimTimerP1 > 0) {
            if (!player.getAir()) {
                exitAnimTimerP1 = 0;
                player.setForcedAnimationId(-1);
            }
        }

        AbstractPlayableSprite p2 = nativeP2From(query);
        if (p2 != null) {
            if (!p2.isDebugMode()) {
                windTunnelFlagP2 = processPlayer(p2, tunnels, windTunnelFlagP2, 1);
            }
            if (exitAnimTimerP2 > 0) {
                if (!p2.getAir()) {
                    exitAnimTimerP2 = 0;
                    p2.setForcedAnimationId(-1);
                }
            }
        }
    }

    /**
     * Releases the temporary {@code anim=$1A} tunnel-exit owner as soon as the
     * player reaches {@code Player_TouchFloor}. The ordinary feature update
     * runs after the playable slot, which is too late for that slot's
     * {@code Animate_Sonic}/{@code Animate_Tails} call; consuming the native
     * exit latch from the landing callback lets the landing's Walk write own
     * the same frame (sonic3k.asm {@code loc_7046} and {@code loc_121B6}).
     */
    public static boolean consumeExitAnimationOnLanding(AbstractPlayableSprite player) {
        return consumeExitAnimationOnLanding(playerQueryFromGameServices(), player);
    }

    static boolean consumeExitAnimationOnLanding(ObjectPlayerQuery query,
                                                  AbstractPlayableSprite player) {
        if (player == null || query == null) {
            return false;
        }
        if (player == asPlayableSprite(query.mainPlayerOrNull()) && exitAnimTimerP1 > 0) {
            exitAnimTimerP1 = 0;
            player.setForcedAnimationId(-1);
            return true;
        }
        if (player == nativeP2From(query) && exitAnimTimerP2 > 0) {
            exitAnimTimerP2 = 0;
            player.setForcedAnimationId(-1);
            return true;
        }
        return false;
    }

    /**
     * Resets static state. Call on level load or engine reset.
     */
    public static void reset() {
        windTunnelFlagP1 = false;
        windTunnelFlagP2 = false;
        activeTunnelInfluenceP1 = 0;
        activeTunnelInfluenceP2 = 0;
        exitAnimTimerP1 = 0;
        exitAnimTimerP2 = 0;
    }

    /** Immutable rewind snapshot of per-player wind-tunnel state. */
    public record Snapshot(
            boolean windTunnelFlagP1, boolean windTunnelFlagP2,
            int activeTunnelInfluenceP1, int activeTunnelInfluenceP2,
            int exitAnimTimerP1, int exitAnimTimerP2) {
    }

    /** Captures the current per-player wind-tunnel state for rewind snapshots. */
    public static Snapshot snapshot() {
        return new Snapshot(windTunnelFlagP1, windTunnelFlagP2,
                activeTunnelInfluenceP1, activeTunnelInfluenceP2,
                exitAnimTimerP1, exitAnimTimerP2);
    }

    /** Restores per-player wind-tunnel state from a previously captured snapshot. */
    public static void restore(Snapshot snapshot) {
        windTunnelFlagP1 = snapshot.windTunnelFlagP1();
        windTunnelFlagP2 = snapshot.windTunnelFlagP2();
        activeTunnelInfluenceP1 = snapshot.activeTunnelInfluenceP1();
        activeTunnelInfluenceP2 = snapshot.activeTunnelInfluenceP2();
        exitAnimTimerP1 = snapshot.exitAnimTimerP1();
        exitAnimTimerP2 = snapshot.exitAnimTimerP2();
    }

    /**
     * Returns whether the given player is currently being moved by the
     * wind tunnel system.
     *
     * @param playerIndex 0 for P1, 1 for P2/sidekick
     */
    public static boolean isPlayerInTunnel(int playerIndex) {
        return playerIndex == 0 ? windTunnelFlagP1 : windTunnelFlagP2;
    }


    /**
     * Returns whether HCZ should temporarily present as "dry" for palette/waterline
     * purposes during the water-rush/tunnel sequence.
     */
    public static boolean shouldForceDryPresentation() {
        return windTunnelFlagP1 || windTunnelFlagP2;
    }

    // =========================================================================
    // Core tunnel logic (ROM: HCZ_WaterTunnels)
    // =========================================================================

    private static boolean processPlayer(AbstractPlayableSprite player,
                                          int[][] tunnels,
                                          boolean wasInTunnel,
                                          int playerIndex) {
        int playerX = player.getCentreX() & 0xFFFF;
        int playerY = player.getCentreY() & 0xFFFF;

        for (int[] entry : tunnels) {
            // ROM: cmp.w (a2),d0 / blo loc_7046
            if (playerX < entry[MIN_X]) continue;
            // ROM: cmp.w 4(a2),d0 / bhs loc_7046
            if (playerX >= entry[MAX_X]) continue;
            // ROM: cmp.w 2(a2),d1 / blo loc_7046
            if (playerY < entry[MIN_Y]) continue;
            // ROM: cmp.w 6(a2),d1 / bhs loc_7046
            if (playerY >= entry[MAX_Y]) continue;

            // Player is inside this tunnel region.

            // ROM: cmpi.b #4,routine(a1) / bhs loc_7058
            if (player.isHurt() || player.getDead()) {
                return false;
            }

            // ROM: btst d5,(_unkF7C7).w / bne locret_702E
            if (HCZBreakableBarState.testBit(playerIndex)) {
                return wasInTunnel;
            }

            // ROM: tst.b object_control(a1) / bne loc_7058
            if (player.isObjectControlled()) {
                return false;
            }

            // Set per-sprite collision flags for PlayableSpriteMovement.
            // WindTunnel_flag makes airborne floor checks run even while the
            // tunnel velocity points upward, keeping the player constrained
            // against pipe surfaces for horizontal and vertical entries.
            player.setSuppressAirCollision(false);
            player.setForceFloorCheck(true);

            if (playerIndex == 0) {
                activeTunnelInfluenceP1 = entry[INFLUENCE_FLAG];
            } else {
                activeTunnelInfluenceP2 = entry[INFLUENCE_FLAG];
            }

            short xVel = (short) entry[X_VEL];
            short yVel = (short) entry[Y_VEL];

            // ROM: move.w d0,x_vel(a1) — store tunnel velocity in player fields.
            // ObjectMoveAndFall will add gravity and apply the combined velocity
            // to position as the second displacement step.
            player.setXSpeed(xVel);
            player.setYSpeed(yVel);

            // ROM: ext.l d0 / lsl.l #8,d0 / add.l d0,x_pos(a1) — first
            // displacement step: add velocity<<8 to the 32-bit position.
            // player.move() does exactly this (adds speed<<8 to pixel:subpixel).
            player.move(xVel, yVel);
            // ROM: move.b #$F,anim(a1)
            player.setAnimationId(Sonic3kAnimationIds.FLOAT2);
            player.setForcedAnimationId(Sonic3kAnimationIds.FLOAT2);

            // ROM: bset #1,status(a1) — sets InAir status bit.
            player.setAir(true);

            // ROM: move.b #0,double_jump_flag(a1)
            player.setDoubleJumpFlag(0);

            // ROM: D-pad nudge — direct pixel modification without collision
            // checks (subq.w/addq.w on x_pos/y_pos).
            if (entry[INFLUENCE_FLAG] == 0) {
                // ROM: btst #button_up,d6 / beq loc_7024 / subq.w #1,y_pos(a1)
                if (player.isUpPressed()) {
                    player.shiftY(-1);
                }
                // ROM: btst #button_down,d6 / beq locret_702E / addq.w #1,y_pos(a1)
                if (player.isDownPressed()) {
                    player.shiftY(1);
                }
            } else {
                // ROM: btst #button_left,d6 / beq loc_703A / subq.w #1,x_pos(a1)
                if (player.isLeftPressed()) {
                    player.shiftX(-1);
                }
                // ROM: btst #button_right,d6 / beq locret_7044 / addq.w #1,x_pos(a1)
                if (player.isRightPressed()) {
                    player.shiftX(1);
                }
            }

            return true;
        }

        // No ROM tunnel matched. Check engine-side pipe continuation:
        // when already in the tunnel and above entry 14 (minY=0x0680) but
        // still within the pipe column and above the twisting loop topY
        // (0x0620), continue pushing upward. The ROM handles this via the
        // twisting loop object, but it may not be spawned in time.
        if (wasInTunnel
                && playerX >= 0x2F30 && playerX < 0x2F70
                && playerY < 0x0680 && playerY >= 0x0440
                && !player.isObjectControlled()) {
            // Vertical continuation — suppress collision like vertical tunnels.
            player.setSuppressAirCollision(true);
            player.setForceFloorCheck(false);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) -0x0400);
            player.setGSpeed((short) 0);
            player.move((short) 0, (short) -0x0400);
            player.setAnimationId(Sonic3kAnimationIds.FLOAT2);
            player.setForcedAnimationId(Sonic3kAnimationIds.FLOAT2);
            player.setAir(true);
            player.setDoubleJumpFlag(0);
            return true;
        }

        // No tunnel matched and no continuation applies — clear collision flags.
        player.setSuppressAirCollision(false);
        player.setForceFloorCheck(false);

        // ROM: tst.b (a3) / beq locret_705A / move.b #$1A,anim(a1)
        if (wasInTunnel) {
            if (playerIndex == 0) {
                exitAnimTimerP1 = 1;
            } else {
                exitAnimTimerP2 = 1;
            }
            player.setAnimationId(Sonic3kAnimationIds.HURT);
            player.setForcedAnimationId(Sonic3kAnimationIds.HURT);
        }

        return false;
    }

    private static ObjectPlayerQuery playerQueryFromGameServices() {
        AbstractPlayableSprite mainPlayer = GameServices.camera().getFocusedSprite();
        SpriteManager sprites = GameServices.spritesOrNull();
        List<? extends PlayableEntity> sidekicks = sprites != null
                ? List.copyOf(sprites.getSidekicks())
                : List.of();
        return new ObjectPlayerQuery(
                () -> mainPlayer,
                () -> sidekicks);
    }

    private static AbstractPlayableSprite nativeP2From(ObjectPlayerQuery query) {
        return asPlayableSprite(query.nativeP2OrNull());
    }

    private static AbstractPlayableSprite asPlayableSprite(PlayableEntity player) {
        return player instanceof AbstractPlayableSprite sprite ? sprite : null;
    }
}
