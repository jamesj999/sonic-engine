package com.openggf.game.sonic1.events;

import com.openggf.camera.Camera;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.audio.AudioManager;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.Direction;
import com.openggf.physics.SensorResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;
import com.openggf.game.GameServices;

/**
 * Dynamic water level events for Sonic 1 Labyrinth Zone.
 * ROM: LZWaterFeatures.asm, specifically the DynWater_LZ1/LZ2/LZ3/SBZ3 routines,
 * plus LZWindTunnels and LZWaterSlides.
 *
 * Each act has a state machine ({@code waterRoutine}) that advances as the
 * player progresses through the level, changing the target water height.
 * The {@link WaterSystem} handles the actual gradual movement toward the target.
 *
 * <h3>ROM variable mapping:</h3>
 * <ul>
 *   <li>{@code v_waterpos3} = water target level ({@link WaterSystem#setWaterLevelTarget})</li>
 *   <li>{@code v_waterpos2} = current water level ({@link WaterSystem#setWaterLevelDirect})</li>
 *   <li>{@code v_wtr_routine} = {@link #waterRoutine} (0-based, increments by 1)</li>
 *   <li>{@code v_screenposx} = camera X position</li>
 *   <li>{@code v_player+obY} = player Y position</li>
 *   <li>{@code f_wtunnelmode} = {@link #windTunnelActive} (Sonic currently being pushed)</li>
 *   <li>{@code f_wtunnelallow} = {@link #windTunnelDisabled} (tunnels temporarily disabled)</li>
 *   <li>{@code f_slidemode} = {@link #waterSlideActive} (on a water slide chunk)</li>
 * </ul>
 *
 * <h3>Routine dispatch:</h3>
 * The ROM uses a subq-chain dispatch: routine 0 is checked directly,
 * then routines 1+ are reached by subtracting 1 from the counter until zero.
 * This class uses a switch statement for clarity but preserves the same semantics.
 *
 * <h3>Water movement:</h3>
 * After setting the target ({@code v_waterpos3}), the ROM's {@code LZDynamicWater}
 * moves {@code v_waterpos2} toward the target by {@code f_water} pixels per frame
 * (typically 1). Some routines also set {@code v_waterpos2} directly for instant
 * water level changes (bypassing the gradual movement).
 */
public class Sonic1LZWaterEvents {
    private static final Logger LOGGER = Logger.getLogger(Sonic1LZWaterEvents.class.getName());

    private int waterRoutine;

    // Zone/act tracking for WaterSystem calls
    private int zoneId;
    private int actId;

    // =========================================================================
    // Wind tunnel state (ROM: f_wtunnelmode, f_wtunnelallow)
    // =========================================================================

    /**
     * Whether Sonic is currently being pushed by a wind tunnel.
     * ROM: f_wtunnelmode (set to 1 when inside tunnel, cleared when leaving).
     */
    private boolean windTunnelActive;
    private boolean windTunnelPreserveGroundContact;

    /**
     * Whether wind tunnels are temporarily disabled.
     * ROM: f_wtunnelallow (set by certain objects to prevent tunnel activation).
     * Can be set externally (e.g., by a switch or object interaction).
     */
    private boolean windTunnelDisabled;

    // ROM's v_vbla_byte (global) is read directly via vblaByte() helper for
    // sound-timing parity. No local tunnel-stay counter is kept -- it would
    // diverge from ROM whenever Sonic enters and exits the tunnel zone since
    // ROM's vblank advances every frame regardless.

    // =========================================================================
    // Water slide state (ROM: f_slidemode)
    // =========================================================================

    /**
     * Whether Sonic is currently on a water slide chunk.
     * ROM: f_slidemode.
     */
    private boolean waterSlideActive;

    // =========================================================================
    // Wind tunnel region definitions (ROM: LZWind_Data, lines 377-383)
    //
    // Each region is {left, top, right, bottom} in world coordinates.
    // Act 1 has TWO tunnel regions; Acts 2, 3, and SBZ3 each have ONE.
    // The ROM stores these in a contiguous table and indexes by act*8.
    // Act 1 uses two entries (the first at offset -8 from the base).
    // =========================================================================

    /**
     * Wind tunnel regions per act.
     * From LZWaterFeatures.asm lines 377-382:
     * <pre>
     * LZWind_Data:
     *     dc.w $A80, $300, $C10, $380   ; act 1 set 1
     *     dc.w $F80, $100, $1410, $180  ; act 1 set 2
     *     dc.w $460, $400, $710, $480   ; act 2
     *     dc.w $A20, $600, $1610, $6E0  ; act 3
     *     dc.w $C80, $600, $13D0, $680  ; SBZ act 3
     * </pre>
     */
    private static final int[][] WIND_TUNNEL_ACT1 = {
            {0x0A80, 0x0300, 0x0C10, 0x0380},  // set 1
            {0x0F80, 0x0100, 0x1410, 0x0180},  // set 2
    };
    private static final int[][] WIND_TUNNEL_ACT2 = {
            {0x0460, 0x0400, 0x0710, 0x0480},
    };
    private static final int[][] WIND_TUNNEL_ACT3 = {
            {0x0A20, 0x0600, 0x1610, 0x06E0},
    };
    private static final int[][] WIND_TUNNEL_SBZ3 = {
            {0x0C80, 0x0600, 0x13D0, 0x0680},
    };

    /**
     * Wind tunnel push velocity: $400 subpixels/frame rightward.
     * ROM: move.w #$400,obVelX(a1)
     */
    private static final short WIND_TUNNEL_X_VELOCITY = 0x0400;

    /**
     * Pixel offset from left boundary used for the Y-curve adjustment zone.
     * ROM: subi.w #$80,d0 then cmp.w (a2),d0
     * If player X - $80 < left boundary, apply Y-curve adjustment.
     */
    private static final int WIND_TUNNEL_CURVE_OFFSET = 0x80;

    /**
     * Y adjustment per frame for tunnel curve (upward in act 2, downward otherwise).
     * ROM: moveq #2,d0 (negated for act 2).
     */
    private static final int WIND_TUNNEL_Y_CURVE = 2;

    // =========================================================================
    // Water slide constants (ROM: LZWaterSlides, lines 392-458)
    // =========================================================================

    /**
     * Chunk IDs that trigger water slide behavior.
     * ROM: Slide_Chunks (lines 454-457): dc.b 2, 7, 3, $4C, $4B, 8, 4
     * Stored in reverse order in ROM (searched backwards with cmp.b -(a2),d0),
     * but we store in forward order and search normally.
     */
    private static final int[] SLIDE_CHUNK_IDS = {2, 7, 3, 0x4C, 0x4B, 8, 4};

    /**
     * Inertia speed values corresponding to each slide chunk.
     * ROM: Slide_Speeds (lines 450-451): dc.b 10, -11, 10, -10, -11, -12, 11
     * Positive = rightward, negative = leftward (and sets facing-left flag).
     */
    private static final int[] SLIDE_SPEEDS = {10, -11, 10, -10, -11, -12, 11};

    // Layout gap position: v_lvllayout + $80*2 + 6 = FG row 2, column 6.
    // Shared by DynWater_LZ3 routine 0 (writes $4B) and DLE_LZ3 (writes 7).
    private static final int LAYOUT_GAP_X = 6;
    private static final int LAYOUT_GAP_Y = 2;

    private int vblaByte() {
        com.openggf.level.objects.ObjectManager om =
                levelManager() != null ? levelManager().getObjectManager() : null;
        return om != null ? romVisibleVblaByteBeforeObjectExecution(om.getVblaCounter()) : 0;
    }

    /**
     * {@code ObjectManager.vblaCounter} is the engine's ROM-visible VBlank
     * clock for the current level frame. LZWaterFeatures runs before the object
     * dispatcher but reads that already-published value; it must not predict
     * the dispatcher's subsequent increment.
     */
    static int romVisibleVblaByteBeforeObjectExecution(int storedVblaCounter) {
        return storedVblaCounter & 0xFF;
    }

    public Sonic1LZWaterEvents() {
    }

    private Camera camera() {
        return GameServices.camera();
    }

    private WaterSystem waterSystem() {
        return GameServices.water();
    }

    private AudioManager audio() {
        return GameServices.audio();
    }

    private LevelManager levelManager() {
        return GameServices.level();
    }

    private ZoneLayoutMutationPipeline mutationPipeline() {
        return GameServices.zoneLayoutMutationPipeline();
    }

    private Sonic1SwitchManager switchManager() {
        return GameServices.module().getGameService(Sonic1SwitchManager.class);
    }

    /**
     * Initialize water events for a new level.
     *
     * @param zoneId zone index (from level manager, not ROM zone ID)
     * @param actId  act index (0-based)
     */
    public void init(int zoneId, int actId) {
        this.zoneId = zoneId;
        this.actId = actId;
        this.waterRoutine = 0;
        this.windTunnelActive = false;
        this.windTunnelPreserveGroundContact = false;
        this.windTunnelDisabled = false;
        this.waterSlideActive = false;
    }

    /**
     * Run per-frame water event logic. Dispatches to per-act handler.
     * ROM equivalent: DynWater_Index dispatch table.
     */
    public void update() {
        switch (actId) {
            case 0 -> updateLZ1();
            case 1 -> updateLZ2();
            case 2 -> updateLZ3();
        }
    }

    /**
     * Water events for SBZ Act 3 (which uses LZ layout).
     * Called separately because SBZ3 is zone SBZ act 2 in the engine,
     * but uses LZ water mechanics (act index 3 in the ROM's water table).
     * ROM: DynWater_SBZ3
     */
    public void updateSBZ3() {
        int camX = camera().getX() & 0xFFFF;
        int target = 0x228;

        // cmpi.w #$F00,(v_screenposx).w
        if (camX >= 0xF00) {
            target = 0x4C8;
        }

        setTarget(target);
    }

    // =========================================================================
    // LZ Act 1: DynWater_LZ1 (lines 86-141)
    //
    // Three routines:
    //   0: Complex branching based on camera X and player Y (above/below $200)
    //   1: Check if player Y < $2E0 and camera X >= $1300
    //   2+: No further changes (done)
    // =========================================================================

    /**
     * DynWater_LZ1: Act 1 dynamic water.
     * ROM: LZWaterFeatures.asm lines 86-141
     */
    private void updateLZ1() {
        switch (waterRoutine) {
            case 0 -> updateLZ1Routine0();
            case 1 -> updateLZ1Routine1();
            // waterRoutine >= 2: no further changes (rts via .skip)
        }
    }

    /**
     * DynWater_LZ1 routine 0.
     * Default target $B8. Branches on camera X and player Y.
     *
     * If player Y < $200 (above):
     *   X >= $600:  target = $108
     *   X >= $C80:  target = $E8
     *   X >= $1500: target = $108
     *
     * If player Y >= $200 (below):
     *   X >= $600:  target = $108
     *   X >= $C00:  target = $318
     *   X >= $1080: target = $5C8 (+ set switch flag 5)
     *   X >= $1380: target = $3A8, wait for waterpos2 to reach $3A8, then advance
     */
    private void updateLZ1Routine0() {
        int camX = camera().getX() & 0xFFFF;
        int target = 0xB8; // move.w #$B8,d1

        // cmpi.w #$600,d0
        if (camX < 0x600) {
            setTarget(target);
            return;
        }

        target = 0x108; // move.w #$108,d1

        // cmpi.w #$200,(v_player+obY).w
        int playerY = getPlayerY();
        if (playerY < 0x200) {
            // .sonicishigh branch
            updateLZ1Routine0SonicHigh(camX, target);
        } else {
            // Player is below $200 (or equal)
            updateLZ1Routine0SonicLow(camX, target);
        }
    }

    /**
     * DynWater_LZ1 routine 0, player Y < $200 (.sonicishigh).
     * ROM: lines 115-122
     */
    private void updateLZ1Routine0SonicHigh(int camX, int target) {
        // cmpi.w #$C80,d0
        if (camX < 0xC80) {
            setTarget(target);
            return;
        }

        // move.w #$E8,d1
        target = 0xE8;

        // cmpi.w #$1500,d0
        if (camX < 0x1500) {
            setTarget(target);
            return;
        }

        // move.w #$108,d1
        target = 0x108;
        setTarget(target);
    }

    /**
     * DynWater_LZ1 routine 0, player Y >= $200 (main path after $600 check).
     * ROM: lines 96-112
     */
    private void updateLZ1Routine0SonicLow(int camX, int target) {
        // cmpi.w #$C00,d0
        if (camX < 0xC00) {
            setTarget(target);
            return;
        }

        // move.w #$318,d1
        target = 0x318;

        // cmpi.w #$1080,d0
        if (camX < 0x1080) {
            setTarget(target);
            return;
        }

        // move.b #$80,(f_switch+5).w - set switch flag 5 bit 7
        switchManager().setBit(5, 7);

        // move.w #$5C8,d1
        target = 0x5C8;

        // cmpi.w #$1380,d0
        if (camX < 0x1380) {
            setTarget(target);
            return;
        }

        // move.w #$3A8,d1
        target = 0x3A8;

        // cmp.w (v_waterpos2).w,d1 ; has water reached last height?
        // bne.s .setwater          ; if not, branch
        int currentWater = waterSystem().getWaterLevelY(zoneId, actId);
        if (currentWater != target) {
            setTarget(target);
            return;
        }

        // move.b #1,(v_wtr_routine).w ; use second routine next
        waterRoutine = 1;
        setTarget(target);
    }

    /**
     * DynWater_LZ1 routine 1 (.routine2 in ROM, v_wtr_routine=1).
     * ROM: lines 125-141
     *
     * Checks if player Y < $2E0. If so, and camera X >= $1300,
     * sets target to $108 and advances to routine 2 (done).
     * Otherwise keeps target at $3A8.
     */
    private void updateLZ1Routine1() {
        int camX = camera().getX() & 0xFFFF;

        // cmpi.w #$2E0,(v_player+obY).w
        int playerY = getPlayerY();
        if (playerY >= 0x2E0) {
            return; // bhs.s .skip -> rts
        }

        int target = 0x3A8; // move.w #$3A8,d1

        // cmpi.w #$1300,d0
        if (camX < 0x1300) {
            setTarget(target);
            return;
        }

        // move.w #$108,d1
        target = 0x108;

        // move.b #2,(v_wtr_routine).w
        waterRoutine = 2;

        setTarget(target);
    }

    // =========================================================================
    // LZ Act 2: DynWater_LZ2 (lines 143-155)
    //
    // Single routine, no state advancement.
    // Simply sets target based on camera X thresholds.
    // =========================================================================

    /**
     * DynWater_LZ2: Act 2 dynamic water.
     * ROM: LZWaterFeatures.asm lines 143-155
     *
     * Default $328, rises to $3C8 at X >= $500, then $428 at X >= $B00.
     */
    private void updateLZ2() {
        int camX = camera().getX() & 0xFFFF;
        int target = 0x328; // move.w #$328,d1

        // cmpi.w #$500,d0
        if (camX >= 0x500) {
            target = 0x3C8; // move.w #$3C8,d1
        }

        // cmpi.w #$B00,d0
        if (camX >= 0xB00) {
            target = 0x428; // move.w #$428,d1
        }

        setTarget(target);
    }

    // =========================================================================
    // LZ Act 3: DynWater_LZ3 (lines 158-259)
    //
    // Five routines (0-4), the most complex water sequence in the game.
    // Includes instant water level changes (writing v_waterpos2 directly),
    // level layout modifications, sound effects, and switch flag setting.
    // =========================================================================

    /**
     * DynWater_LZ3: Act 3 dynamic water.
     * ROM: LZWaterFeatures.asm lines 158-259
     */
    private void updateLZ3() {
        switch (waterRoutine) {
            case 0 -> updateLZ3Routine0();
            case 1 -> updateLZ3Routine1();
            case 2 -> updateLZ3Routine2();
            case 3 -> updateLZ3Routine3();
            case 4 -> updateLZ3Routine4();
            // waterRoutine >= 5: no code (would fall through all subqs with no match)
        }
    }

    /**
     * DynWater_LZ3 routine 0.
     * ROM: lines 158-180
     *
     * Default target $900. Waits for camera X >= $600 AND player Y
     * between $3C0 and $600. When triggered: set target to $4C8,
     * set v_waterpos2 directly (instant), modify level layout,
     * play rumbling sound, advance to routine 1.
     *
     * Note: In routine 0, both v_waterpos3 AND v_waterpos2 are set every
     * frame (ROM line 179: move.w d1,(v_waterpos2).w), making the water
     * level instant rather than gradual.
     */
    private void updateLZ3Routine0() {
        int camX = camera().getX() & 0xFFFF;
        int target = 0x900; // move.w #$900,d1

        // cmpi.w #$600,d0
        if (camX >= 0x600) {
            int playerY = getPlayerY();

            // cmpi.w #$3C0,(v_player+obY).w
            // cmpi.w #$600,(v_player+obY).w
            if (playerY >= 0x3C0 && playerY < 0x600) {
                // Trigger: water rises, layout changes, sound plays
                target = 0x4C8; // move.w #$4C8,d1

                // move.b #$4B,(v_lvllayout+$80*2+6).w
                // Write water slide chunk to layout position FG row 2, column 6
                writeLayoutChunk(0x4B);

                // move.b #1,(v_wtr_routine).w
                waterRoutine = 1;

                // move.w #sfx_Rumbling,d0 / bsr.w QueueSound2
                audio().playSfx(Sonic1Sfx.RUMBLING.id);
                LOGGER.fine("LZ3 routine 0: Water triggered, advancing to routine 1");
            }
        }

        // .setwaterlz3:
        // move.w d1,(v_waterpos3).w
        // move.w d1,(v_waterpos2).w  ; change water height instantly
        setTarget(target);
        setDirect(target);
    }

    /**
     * DynWater_LZ3 routine 1 (.routine2 in ROM, v_wtr_routine=1).
     * ROM: lines 183-208
     *
     * Complex logic with player Y checks and instant water level sets.
     * Default target $4C8, then $308 at X >= $770.
     * At X >= $1400, checks player Y and current water target to determine
     * whether to use $508 or $308. Advances to routine 2 at X >= $1770.
     */
    private void updateLZ3Routine1() {
        int camX = camera().getX() & 0xFFFF;
        int target = 0x4C8; // move.w #$4C8,d1

        // cmpi.w #$770,d0
        if (camX < 0x770) {
            setTarget(target);
            return;
        }

        target = 0x308; // move.w #$308,d1

        // cmpi.w #$1400,d0
        if (camX < 0x1400) {
            setTarget(target);
            return;
        }

        // At this point camX >= $1400
        // cmpi.w #$508,(v_waterpos3).w
        int currentTarget = waterSystem().getWaterLevelTarget(zoneId, actId);
        if (currentTarget == 0x508) {
            // beq.s .sonicislow - if target is already $508, go to sonicislow
            target = 0x508;
            setDirect(target);

            // cmpi.w #$1770,d0
            if (camX >= 0x1770) {
                // move.b #2,(v_wtr_routine).w
                waterRoutine = 2;
            }

            setTarget(target);
            return;
        }

        // cmpi.w #$600,(v_player+obY).w
        int playerY = getPlayerY();
        if (playerY >= 0x600) {
            // bhs.s .sonicislow
            target = 0x508;
            setDirect(target);

            // cmpi.w #$1770,d0
            if (camX >= 0x1770) {
                waterRoutine = 2;
            }

            setTarget(target);
            return;
        }

        // cmpi.w #$280,(v_player+obY).w
        if (playerY >= 0x280) {
            // bhs.s .setwater2 - player is between $280 and $600, keep $308
            setTarget(target);
            return;
        }

        // Player Y < $280: fall through to .sonicislow
        target = 0x508;
        setDirect(target);

        // cmpi.w #$1770,d0
        if (camX >= 0x1770) {
            waterRoutine = 2;
        }

        setTarget(target);
    }

    /**
     * DynWater_LZ3 routine 2 (.routine3 in ROM, v_wtr_routine=2).
     * ROM: lines 211-228
     *
     * Default target $508. At X >= $1860, target = $188.
     * Advances to routine 3 when X >= $1AF0 OR when waterpos2 == $188.
     */
    private void updateLZ3Routine2() {
        int camX = camera().getX() & 0xFFFF;
        int target = 0x508; // move.w #$508,d1

        // cmpi.w #$1860,d0
        if (camX < 0x1860) {
            setTarget(target);
            return;
        }

        target = 0x188; // move.w #$188,d1

        // cmpi.w #$1AF0,d0
        if (camX >= 0x1AF0) {
            // bhs.s .loc_3DC6 - advance unconditionally
            waterRoutine = 3;
            setTarget(target);
            return;
        }

        // cmp.w (v_waterpos2).w,d1 ; has water reached target?
        // bne.s .setwater3         ; if not, branch
        int currentWater = waterSystem().getWaterLevelY(zoneId, actId);
        if (currentWater == target) {
            // .loc_3DC6: move.b #3,(v_wtr_routine).w
            waterRoutine = 3;
        }

        setTarget(target);
    }

    /**
     * DynWater_LZ3 routine 3 (.routine4 in ROM, v_wtr_routine=3).
     * ROM: lines 231-250
     *
     * Default target $188, sets both target and direct.
     * At X >= $1AF0, target/direct = $900.
     * At X >= $1BC0, performs complex instant setup:
     *   target = $608, direct = $7C0, set switch flag 8, advance to routine 4.
     */
    private void updateLZ3Routine3() {
        int camX = camera().getX() & 0xFFFF;
        int target = 0x188; // move.w #$188,d1

        // cmpi.w #$1AF0,d0
        if (camX < 0x1AF0) {
            // .setwater4: set both target and direct
            setTarget(target);
            setDirect(target);
            return;
        }

        target = 0x900; // move.w #$900,d1

        // cmpi.w #$1BC0,d0
        if (camX < 0x1BC0) {
            // .setwater4: set both target and direct
            setTarget(target);
            setDirect(target);
            return;
        }

        // X >= $1BC0: special instant water setup
        // move.b #4,(v_wtr_routine).w
        waterRoutine = 4;

        // move.w #$608,(v_waterpos3).w
        setTarget(0x608);

        // move.w #$7C0,(v_waterpos2).w
        setDirect(0x7C0);

        // move.b #1,(f_switch+8).w - set switch flag 8 bit 0
        switchManager().setBit(8, 0);

        LOGGER.fine("LZ3 routine 3: Instant water setup at X >= $1BC0");
    }

    /**
     * DynWater_LZ3 routine 4 (.routine5 in ROM, v_wtr_routine=4).
     * ROM: lines 253-259
     *
     * Final routine. At X >= $1E00, sets target to $128.
     */
    private void updateLZ3Routine4() {
        int camX = camera().getX() & 0xFFFF;

        // cmpi.w #$1E00,d0
        if (camX >= 0x1E00) {
            // move.w #$128,(v_waterpos3).w
            setTarget(0x128);
        }
        // Otherwise: .dontset -> rts (no change)
    }

    // =========================================================================
    // Wind Tunnels: LZWindTunnels (LZWaterFeatures.asm lines 279-373)
    //
    // Horizontal underwater current zones that push Sonic rightward at $400
    // subpixels/frame. Each act defines rectangular regions. Act 1 has two
    // tunnel regions; all other acts have one.
    //
    // The ROM's control flow:
    //   1. Skip if debug mode active
    //   2. Select tunnel regions for current act
    //   3. For each region, check if Sonic's centre is inside
    //   4. If inside: play rushing water sound every $40 frames,
    //      skip if tunnels disabled (f_wtunnelallow), skip if hurt/dying
    //   5. Set f_wtunnelmode, apply X push and Y curve adjustment,
    //      set floating animation, allow up/down input to nudge Y
    //   6. If no tunnel matched: clear f_wtunnelmode and restore walk anim
    // =========================================================================

    /**
     * Update wind tunnel (water current) logic. Called once per frame.
     * ROM equivalent: LZWindTunnels subroutine.
     *
     * <p>Must be called BEFORE {@link #update()} (dynamic water) each frame,
     * matching the ROM's call order in LZWaterFeatures.
     */
    public void updateWindTunnels() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }

        // ROM: tst.w (v_debuguse).w / bne.w .quit
        if (player.isDebugMode()) {
            return;
        }

        // Get the tunnel regions for the current act
        int[][] tunnelRegions = getWindTunnelRegions();
        if (tunnelRegions == null) {
            return;
        }

        int playerX = player.getCentreX() & 0xFFFF;
        int playerY = player.getCentreY() & 0xFFFF;

        // Check each tunnel region
        for (int[] region : tunnelRegions) {
            int left = region[0];
            int top = region[1];
            int right = region[2];
            int bottom = region[3];

            // ROM: cmp.w (a2),d0 / blo.w .chknext   (X < left)
            // ROM: cmp.w 4(a2),d0 / bhs.w .chknext  (X >= right)
            // ROM: cmp.w 2(a2),d2 / blo.w .chknext   (Y < top)
            // ROM: cmp.w 6(a2),d2 / bhs.s .chknext   (Y >= bottom)
            if (playerX < left || playerX >= right || playerY < top || playerY >= bottom) {
                continue;
            }

            // Player is inside this tunnel region

            // ROM: play rushing water sound every $40 (64) frames.
            // Uses the global v_vbla_byte for parity with ROM frame-cycle phasing.
            // move.b (v_vbla_byte).w,d0 / andi.b #$3F,d0 / bne.s .skipsound
            if ((vblaByte() & 0x3F) == 0) {
                audio().playSfx(Sonic1Sfx.WATERFALL.id);
            }

            // ROM: tst.b (f_wtunnelallow).w / bne.w .quit
            if (windTunnelDisabled) {
                windTunnelPreserveGroundContact = false;
                return;
            }

            // ROM: cmpi.b #4,obRoutine(a1) / bhs.s .clrquit
            // obRoutine >= 4 means Sonic is hurt or dying
            if (player.isHurt() || player.getDead()) {
                windTunnelActive = false;
                windTunnelPreserveGroundContact = false;
                player.setForcedAnimationId(-1);
                return;
            }

            // ROM: move.b #1,(f_wtunnelmode).w
            windTunnelActive = true;
            windTunnelPreserveGroundContact = !player.getAir();

            // ROM REV01 parity: the non-FixBugs path clobbers d0 with
            // v_vblank_byte during the sound gate and then reuses d0 for the
            // curve check as if it still held obX. On sound frames d0 is the
            // waterfall SFX id; otherwise only its low byte is replaced.
            // Reference: docs/s1disasm/_inc/LZWaterFeatures.asm:309-331.
            int curveCheckX = (playerX & 0xFF00) | (vblaByte() & 0x3F);
            if ((vblaByte() & 0x3F) == 0) {
                curveCheckX = Sonic1Sfx.WATERFALL.id;
            }
            if ((curveCheckX - WIND_TUNNEL_CURVE_OFFSET) < left) {
                // ROM: moveq #2,d0
                // In act 2, Y adjustment is negated (Sonic curves upward)
                // cmpi.b #1,(v_act).w / bne.s .notact2 / neg.w d0
                int yAdjust = WIND_TUNNEL_Y_CURVE;
                if (actId == 1) {
                    yAdjust = -yAdjust;
                }
                // ROM: add.w d0,obY(a1) -- word-only write, preserves obSubpixelY.
                // (s1disasm/_inc/LZWaterFeatures.asm:338)
                player.setCentreYPreserveSubpixel((short) (player.getCentreY() + yAdjust));
            }

            // ROM: .movesonic:
            // addq.w #4,obX(a1)  - move Sonic 4 pixels rightward
            // ROM uses addq.w (word write), preserving obSubpixelX. Without this
            // preservation, Sonic's sub_x is zeroed every frame in the tunnel,
            // accumulating divergence vs the reference trace. (LZWaterFeatures.asm:341)
            player.setCentreXPreserveSubpixel((short) (player.getCentreX() + 4));

            // move.w #$400,obVelX(a1)  - set horizontal velocity
            player.setXSpeed(WIND_TUNNEL_X_VELOCITY);

            // move.w #0,obVelY(a1)  - clear vertical velocity
            player.setYSpeed((short) 0);

            // move.b #id_Float2,obAnim(a1)  - floating animation
            player.setAnimationId(Sonic1AnimationIds.FLOAT2);
            player.setForcedAnimationId(Sonic1AnimationIds.FLOAT2);

            // bset #1,obStatus(a1)  - set airborne flag
            player.setAir(true);

            // ROM: btst #bitUp,(v_jpadhold2).w / beq.s .down
            // subq.w #1,obY(a1)  - nudge up (word write, preserves obSubpixelY)
            // (LZWaterFeatures.asm:348)
            if (isLogicalDirectionHeld(player, AbstractPlayableSprite.INPUT_UP)) {
                player.setCentreYPreserveSubpixel((short) (player.getCentreY() - 1));
            }

            // ROM: btst #bitDn,(v_jpadhold2).w / beq.s .end
            // addq.w #1,obY(a1)  - nudge down (word write, preserves obSubpixelY)
            // (LZWaterFeatures.asm:353)
            if (isLogicalDirectionHeld(player, AbstractPlayableSprite.INPUT_DOWN)) {
                player.setCentreYPreserveSubpixel((short) (player.getCentreY() + 1));
            }

            return; // Done - Sonic is in a tunnel
        }

        // No tunnel matched
        // ROM: .chknext / dbf / tst.b (f_wtunnelmode).w / beq.s .quit
        if (windTunnelActive) {
            // ROM: move.b #id_Walk,obAnim(a1) - restore walk animation
            player.setAnimationId(Sonic1AnimationIds.WALK);
            player.setForcedAnimationId(-1);
            // ROM: .clrquit: clr.b (f_wtunnelmode).w
            windTunnelActive = false;
            windTunnelPreserveGroundContact = false;
        }
    }

    /**
     * Get the wind tunnel regions for the current act.
     * ROM: LZWind_Data indexed by act number.
     *
     * @return array of {left, top, right, bottom} regions, or null if no tunnels
     */
    private int[][] getWindTunnelRegions() {
        return switch (actId) {
            case 0 -> WIND_TUNNEL_ACT1; // Act 1: 2 tunnels
            case 1 -> WIND_TUNNEL_ACT2; // Act 2: 1 tunnel
            case 2 -> WIND_TUNNEL_ACT3; // Act 3: 1 tunnel
            default -> null;
        };
    }

    /**
     * LZWaterFeatures executes before Sonic_Control copies the current physical
     * pad word into {@code v_jpadhold2}. Tunnel nudges therefore read the
     * logical word left by the preceding player tick, while later objects such
     * as Obj0B can still read the current physical pad state.
     */
    static boolean isLogicalDirectionHeld(AbstractPlayableSprite player, int directionMask) {
        return player != null && (player.getLogicalInputState() & directionMask) != 0;
    }

    /**
     * Update wind tunnels for SBZ Act 3.
     * SBZ3 uses LZ water mechanics with its own tunnel region.
     */
    public void updateWindTunnelsSBZ3() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null || player.isDebugMode()) {
            return;
        }

        int playerX = player.getCentreX() & 0xFFFF;
        int playerY = player.getCentreY() & 0xFFFF;

        for (int[] region : WIND_TUNNEL_SBZ3) {
            if (playerX < region[0] || playerX >= region[2] || playerY < region[1] || playerY >= region[3]) {
                continue;
            }

            // Player is inside this tunnel region.
            // Use the global v_vbla_byte for sound timing parity with ROM.
            if ((vblaByte() & 0x3F) == 0) {
                audio().playSfx(Sonic1Sfx.WATERFALL.id);
            }

            if (windTunnelDisabled) {
                windTunnelPreserveGroundContact = false;
                return;
            }

            if (player.isHurt() || player.getDead()) {
                windTunnelActive = false;
                windTunnelPreserveGroundContact = false;
                player.setForcedAnimationId(-1);
                return;
            }

            windTunnelActive = true;
            windTunnelPreserveGroundContact = !player.getAir();

            // SBZ3 curve: no act-specific negation (not act 2)
            // ROM uses add.w / addq.w / subq.w word writes, preserving obSubpixelX/Y.
            // (s1disasm/_inc/LZWaterFeatures.asm:338,341,348,353)
            //
            // Same KNOWN DISCREPANCY as LZ wind tunnels: the original REV01 ROM
            // has a `move.b (v_vbla_byte).w,d0` bug that occasionally fires the
            // curve outside its intended X range. We implement the FixBugs path.
            if ((playerX - WIND_TUNNEL_CURVE_OFFSET) < region[0]) {
                player.setCentreYPreserveSubpixel((short) (player.getCentreY() + WIND_TUNNEL_Y_CURVE));
            }

            player.setCentreXPreserveSubpixel((short) (player.getCentreX() + 4));
            player.setXSpeed(WIND_TUNNEL_X_VELOCITY);
            player.setYSpeed((short) 0);
            player.setAnimationId(Sonic1AnimationIds.FLOAT2);
            player.setForcedAnimationId(Sonic1AnimationIds.FLOAT2);
            player.setAir(true);

            if (isLogicalDirectionHeld(player, AbstractPlayableSprite.INPUT_UP)) {
                player.setCentreYPreserveSubpixel((short) (player.getCentreY() - 1));
            }
            if (isLogicalDirectionHeld(player, AbstractPlayableSprite.INPUT_DOWN)) {
                player.setCentreYPreserveSubpixel((short) (player.getCentreY() + 1));
            }
            return;
        }

        if (windTunnelActive) {
            player.setAnimationId(Sonic1AnimationIds.WALK);
            player.setForcedAnimationId(-1);
            windTunnelActive = false;
            windTunnelPreserveGroundContact = false;
        }
    }

    // =========================================================================
    // Water Slides: LZWaterSlides (LZWaterFeatures.asm lines 392-458)
    //
    // Certain terrain chunks in LZ make Sonic slide with a preset inertia.
    // The ROM reads the current chunk ID from the level layout at the player's
    // position, then matches against a table of 7 slide chunk IDs.
    //
    // Each chunk ID maps to a signed inertia value that sets Sonic's ground speed.
    // Positive values push right, negative values push left (and set facing-left).
    //
    // The slide is only active when Sonic is on the ground (not jumping).
    // When Sonic leaves a slide chunk, he gets 5 frames of normal control before
    // full movement resumes (objoff_3E countdown).
    //
    // Chunk ID lookup is implemented via findSlideChunkIndex() below,
    // with constants from the disassembly (s1disasm: _inc/WaterSlide.asm).
    // =========================================================================

    /**
     * Check for water slide chunks under the player and apply slide physics.
     * ROM equivalent: LZWaterSlides subroutine.
     *
     * <p>The level event manager samples the 128x128 chunk ID at the player's
     * ROM obX/obY-equivalent position and passes it here. This method then
     * matches that chunk ID against {@link #SLIDE_CHUNK_IDS} and applies the
     * corresponding {@link #SLIDE_SPEEDS} inertia.
     *
     * <p>Slide chunk IDs (ROM: Slide_Chunks): 2, 7, 3, $4C, $4B, 8, 4
     * <p>Slide speeds (ROM: Slide_Speeds): 10, -11, 10, -10, -11, -12, 11
     * <p>Positive speed = rightward, negative = leftward.
     *
     * @param chunkIdAtPlayer 128x128 block ID sampled from ROM obX/obY,
     *                        or -1 if not available
     */
    public void checkWaterSlide(int chunkIdAtPlayer) {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }

        // ROM: LZWaterFeatures runs before ExecuteObjects, so objects that set
        // f_playerctrl (e.g., PoleThatBreaks) overwrite obAnim AFTER this code.
        // In our engine objects update first, so skip slide logic when an object
        // has control to avoid overriding the object's animation.
        if (player.isObjectControlled()) {
            if (waterSlideActive) {
                handleSlideExit(player);
            }
            return;
        }

        int matchIndex = -1;
        // ROM: btst #1,obStatus(a1) / bne.s loc_3F6A
        // We only attempt chunk matching while grounded and with a valid chunk ID.
        if (!player.getAir()) {
            matchIndex = findSlideChunkIndex(chunkIdAtPlayer);
        }

        if (matchIndex < 0) {
            // ROM loc_3F6A immediately clears f_slidemode on the first
            // non-matching chunk after a slide (docs/s1disasm/_inc/
            // LZWaterFeatures.asm:408-415). Keeping slide mode alive for
            // hysteresis skips Sonic_Move input longer than the ROM.
            handleSlideExit(player);
            return;
        }

        // ROM: LZSlide_Move
        // bclr #0,obStatus(a1)  - clear facing-left flag
        // move.b Slide_Speeds(pc,d1.w),d0
        int speed = SLIDE_SPEEDS[matchIndex];

        // ROM: move.b d0,obInertia(a1) / bpl.s loc_3F9A
        // If speed is negative, set facing-left flag
        if (speed < 0) {
            // ROM: bset #0,obStatus(a1)
            player.setDirection(Direction.LEFT);
        } else {
            player.setDirection(Direction.RIGHT);
        }

        // ROM: clr.b obInertia+1(a1)  - clear low byte (set inertia to whole value)
        // Set gSpeed as speed * 256 (converting from byte inertia to subpixel speed)
        // The ROM stores inertia as a signed byte in the high byte of the word,
        // with the low byte cleared. In our engine, gSpeed is in subpixels (256 = 1px).
        player.setGSpeed((short) (speed * 256));

        // ROM: move.b #id_WaterSlide,obAnim(a1)
        player.setAnimationId(Sonic1AnimationIds.WATER_SLIDE);
        player.setSliding(true);

        // ROM: move.b #1,(f_slidemode).w
        waterSlideActive = true;

        // ROM: play waterfall SFX every $20 (32) frames
        // move.b (v_vbla_byte).w,d0 / andi.b #$1F,d0 / bne.s locret_3FBE
        // Note: water slides use $1F mask (every 32 frames), not $3F like tunnels.
        // Uses the global v_vbla_byte for parity with ROM frame-cycle phasing.
        if ((vblaByte() & 0x1F) == 0) {
            audio().playSfx(Sonic1Sfx.WATERFALL.id);
        }
    }

    /**
     * Handle exiting a water slide (when no slide chunk is matched).
     * ROM: loc_3F6A / locret_3F7A
     *
     * <p>When leaving a slide, the ROM sets objoff_3E to 5 (a brief cooldown
     * before normal movement resumes) and clears f_slidemode.
     */
    private void handleSlideExit(AbstractPlayableSprite player) {
        if (!waterSlideActive) {
            return;
        }
        // ROM: move.w #5,objoff_3E(a1)
        // Brief control lockout (move_lock) after leaving the water slide.
        // Blocks left/right input for 5 frames, allowing momentum to carry.
        player.setMoveLockTimer(5);

        // ROM: clr.b (f_slidemode).w
        waterSlideActive = false;
        player.setSliding(false);
    }

    private int findSlideChunkIndex(int chunkId) {
        if (chunkId < 0) {
            return -1;
        }
        // ROM scans table backwards; forward scan is equivalent for membership.
        for (int i = 0; i < SLIDE_CHUNK_IDS.length; i++) {
            if (SLIDE_CHUNK_IDS[i] == chunkId) {
                return i;
            }
        }
        return -1;
    }

    // =========================================================================
    // Wind tunnel / water slide state accessors
    // =========================================================================

    /**
     * Check if Sonic is currently being pushed by a wind tunnel.
     * ROM: f_wtunnelmode
     */
    public boolean isWindTunnelActive() {
        return windTunnelActive;
    }

    public boolean allowsFlatZeroDistanceLanding(AbstractPlayableSprite player, SensorResult support) {
        if (!windTunnelActive || player == null || support == null || support.distance() != 0) {
            return false;
        }
        int angle = support.angle() & 0xFF;
        if (angle != 0x00 && angle != 0xFF) {
            return false;
        }
        int playerX = player.getCentreX() & 0xFFFF;
        // Preserve a contact that existed when the tunnel took control until
        // Sonic leaves the flap-door lip. An already-airborne player must not
        // be landed merely because a floor probe reports an exact zero: the ROM
        // keeps the airborne bit and tunnel Y velocity in that case.
        return actId == 2 && windTunnelPreserveGroundContact && playerX <= 0x0B00;
    }

    /**
     * Check if wind tunnels are temporarily disabled.
     * ROM: f_wtunnelallow
     */
    public boolean isWindTunnelDisabled() {
        return windTunnelDisabled;
    }

    /**
     * Set whether wind tunnels are disabled.
     * Called by objects or switches that need to prevent tunnel activation.
     * ROM: f_wtunnelallow
     */
    public void setWindTunnelDisabled(boolean disabled) {
        this.windTunnelDisabled = disabled;
    }

    /**
     * Check if Sonic is currently on a water slide.
     * ROM: f_slidemode
     */
    public boolean isWaterSlideActive() {
        return waterSlideActive;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Get the player's Y position (v_player+obY in ROM).
     * Returns the raw Y coordinate used for position comparisons.
     */
    private int getPlayerY() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return 0;
        }
        // ROM uses obY which is the top of the sprite frame.
        // In our engine, getCentreY() returns the physics centre.
        // However, the ROM's v_player+obY is the object's Y position
        // which corresponds to the sprite's centre position in Sonic's case.
        // Use getCentreY() to match ROM behavior.
        return player.getCentreY() & 0xFFFF;
    }

    /**
     * Set the water target level (v_waterpos3).
     * The WaterSystem will gradually move the current level toward this.
     */
    private void setTarget(int targetY) {
        waterSystem().setWaterLevelTarget(zoneId, actId, targetY);
    }

    /**
     * Set the current water level directly (v_waterpos2), bypassing
     * the gradual movement. Used when the ROM writes to v_waterpos2 directly.
     */
    private void setDirect(int currentY) {
        waterSystem().setWaterLevelDirect(zoneId, actId, currentY);
    }

    /**
     * Write a chunk ID to the layout gap position (v_lvllayout+$80*2+6).
     * Used by DynWater_LZ3 routine 0 to write $4B (water slide chunk).
     */
    private void writeLayoutChunk(int chunkId) {
        Level level = levelManager().getCurrentLevel();
        if (level == null) {
            return;
        }
        if (level.getMap() == null) {
            return;
        }
        mutationPipeline().queue(context -> {
            try {
                return context.surface().setBlockInMap(0,
                        LAYOUT_GAP_X, LAYOUT_GAP_Y, chunkId);
            } catch (IllegalArgumentException e) {
                return MutationEffects.NONE;
            }
        });
    }

    /**
     * Get the current water routine counter.
     * Used by lamppost save/restore (ROM: v_wtr_routine).
     */
    public int getWaterRoutine() {
        return waterRoutine;
    }

    /**
     * Set the water routine counter.
     * Used by lamppost restore.
     */
    public void setWaterRoutine(int routine) {
        this.waterRoutine = routine;
    }
}
