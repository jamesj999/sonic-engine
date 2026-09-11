package com.openggf.game.sonic1.credits;

/**
 * Static data constants for the Sonic 1 ending credits demo sequence.
 * <p>
 * From the S1 disassembly:
 * <ul>
 *   <li>{@code EndingDemoLoad} (sonic.asm:3827) — credits demo entry point</li>
 *   <li>{@code EndDemo_Levels} (sonic.asm:3865) — level order</li>
 *   <li>{@code DemoEndDataPtr} (MoveSonicInDemo.asm:100) — demo input pointers</li>
 *   <li>{@code EndDemo_LampVar} (sonic.asm:3879) — LZ lamppost state</li>
 * </ul>
 */
public final class Sonic1CreditsDemoData {

    private Sonic1CreditsDemoData() {}

    /** Total number of credit screens (0-8). Credits 0-7 have demos; credit 8 is text-only. */
    public static final int TOTAL_CREDITS = 9;

    /** Number of credits with demo playback (0-7). */
    public static final int DEMO_CREDITS = 8;

    // ========================================================================
    // Level order for ending demos (from misc/Demo Level Order - Ending.bin)
    // Each entry is a word: high byte = zone, low byte = act
    // ========================================================================

    /**
     * Zone indices for each credit demo (0-7).
     * These use Sonic1ZoneRegistry indices (gameplay progression order),
     * NOT ROM zone IDs. The ROM uses GHZ=0,LZ=1,MZ=2,SLZ=3,SYZ=4,SBZ=5
     * but the registry uses GHZ=0,MZ=1,SYZ=2,LZ=3,SLZ=4,SBZ=5.
     */
    public static final int[] DEMO_ZONE = {
        0, // Credit 0: GHZ  (ROM zone 0x00, registry index 0)
        1, // Credit 1: MZ   (ROM zone 0x02, registry index 1)
        2, // Credit 2: SYZ  (ROM zone 0x04, registry index 2)
        3, // Credit 3: LZ   (ROM zone 0x01, registry index 3)
        4, // Credit 4: SLZ  (ROM zone 0x03, registry index 4)
        5, // Credit 5: SBZ  (ROM zone 0x05, registry index 5)
        5, // Credit 6: SBZ  (ROM zone 0x05, registry index 5)
        0, // Credit 7: GHZ  (ROM zone 0x00, registry index 0)
    };

    /** Act indices for each credit demo (0-7). */
    public static final int[] DEMO_ACT = {
        0, // Credit 0: GHZ Act 1
        1, // Credit 1: MZ Act 2
        2, // Credit 2: SYZ Act 3
        2, // Credit 3: LZ Act 3
        2, // Credit 4: SLZ Act 3
        0, // Credit 5: SBZ Act 1
        1, // Credit 6: SBZ Act 2
        0, // Credit 7: GHZ Act 1
    };

    // ========================================================================
    // Player start positions (from startpos/*.bin files)
    // ========================================================================

    /** Player start X positions for each credit demo (0-7). */
    public static final int[] START_X = {
        0x0050, // Credit 0: GHZ1
        0x0EA0, // Credit 1: MZ2
        0x1750, // Credit 2: SYZ3
        0x0A00, // Credit 3: LZ3
        0x0BB0, // Credit 4: SLZ3
        0x1570, // Credit 5: SBZ1
        0x01B0, // Credit 6: SBZ2
        0x1400, // Credit 7: GHZ1 (second demo)
    };

    /** Player start Y positions for each credit demo (0-7). */
    public static final int[] START_Y = {
        0x03B0, // Credit 0: GHZ1
        0x046C, // Credit 1: MZ2
        0x00BD, // Credit 2: SYZ3
        0x062C, // Credit 3: LZ3
        0x004C, // Credit 4: SLZ3
        0x016C, // Credit 5: SBZ1
        0x072C, // Credit 6: SBZ2
        0x02AC, // Credit 7: GHZ1 (second demo)
    };

    // ========================================================================
    // Demo timers (frames)
    // ========================================================================

    /**
     * Demo playback duration for each credit (frames).
     * ROM: sonic.asm:2890-2896 (Level_Demo) — credits demos default to 540
     * frames; v_creditsnum==4 (already incremented after EndingDemoLoad)
     * shortens the LZ Act 3 demo to 510 frames.
     */
    public static final int[] DEMO_TIMER = {
        540, // Credit 0: GHZ1
        540, // Credit 1: MZ2
        540, // Credit 2: SYZ3
        510, // Credit 3: LZ3 (shorter — ROM: v_creditsnum==4 after increment)
        540, // Credit 4: SLZ3
        540, // Credit 5: SBZ1
        540, // Credit 6: SBZ2
        540, // Credit 7: GHZ1
    };

    // ========================================================================
    // Demo data ROM offsets (from DemoEndDataPtr at 0x0040A4)
    // ========================================================================

    /** ROM offsets for each ending demo's input data. */
    public static final int[] DEMO_DATA_ADDR = {
        0x5D5E, // Demo_EndGHZ1
        0x5D8C, // Demo_EndMZ
        0x5DCC, // Demo_EndSYZ
        0x5DFC, // Demo_EndLZ
        0x5E2C, // Demo_EndSLZ
        0x5E4C, // Demo_EndSBZ1
        0x5E6C, // Demo_EndSBZ2
        0x5E9C, // Demo_EndGHZ2
    };

    /** Sizes of each demo data block (bytes). */
    public static final int[] DEMO_DATA_SIZE = {
        46, // Demo_EndGHZ1
        64, // Demo_EndMZ
        48, // Demo_EndSYZ
        48, // Demo_EndLZ
        32, // Demo_EndSLZ
        32, // Demo_EndSBZ1
        48, // Demo_EndSBZ2
        48, // Demo_EndGHZ2
    };

    // ========================================================================
    // LZ lamppost state (credit 3 only)
    // From EndDemo_LampVar (sonic.asm:3879-3890)
    // ROM checks v_creditsnum==4 (already incremented) → original credit 3 (LZ Act 3).
    // Despite the s1disasm label saying "Star Light Zone", the water height (0x308)
    // and position data confirm this is for Labyrinth Zone.
    // ========================================================================

    /** LZ demo lamppost number. */
    public static final int LZ_LAMP_NUM         = 1;
    /** LZ demo lamppost X position. */
    public static final int LZ_LAMP_X           = 0x0A00;
    /** LZ demo lamppost Y position. */
    public static final int LZ_LAMP_Y           = 0x062C;
    /** LZ demo ring count at lamppost. */
    public static final int LZ_LAMP_RINGS       = 13;
    /** LZ demo bottom boundary at lamppost. */
    public static final int LZ_LAMP_BOTTOM_BND  = 0x0800;
    /** LZ demo camera X at lamppost. */
    public static final int LZ_LAMP_CAMERA_X    = 0x0957;
    /** LZ demo camera Y at lamppost. */
    public static final int LZ_LAMP_CAMERA_Y    = 0x05CC;
    /** LZ demo water height at lamppost. ROM: EndDemo_LampVar dc.w $308 */
    public static final int LZ_LAMP_WATER_HEIGHT = 0x0308;
    /** LZ demo water routine at lamppost. ROM: EndDemo_LampVar dc.b 1 (v_wtr_routine) */
    public static final int LZ_LAMP_WATER_ROUTINE = 1;
    // There is deliberately no LZ vblank-phase constant here. EndDemo_LampVar
    // (s1disasm/sonic.asm:3879) has no v_vblank_count field, and the ROM's
    // v_vblank_count is only ever written by VBlank_Exit's addq.l #1
    // (s1disasm/sonic.asm:685) -- it free-runs from console reset and is never
    // reset on level load. Any constant here would be measured from one
    // recording, not read from the ROM.

    /** Text display duration (frames). ROM: move.w #120,(v_generictimer).w */
    public static final int TEXT_DISPLAY_FRAMES = 120;

    // There is deliberately no per-credit text pacing table here. Cred_WaitLoop
    // (s1disasm/sonic.asm:3880-3889) holds a credits page up on a joint gate --
    // `tst.w (v_generictimer).w` (TEXT_DISPLAY_FRAMES above) and
    // `tst.l (v_plc_buffer).w` -- so the extra hold is exactly the residual PLC
    // drain of the next demo's level PLC plus plcid_Main2, decompressing nine
    // tiles per frame under VBlank_Title's ProcessPLC_9Tiles (sonic.asm:781).
    // Sonic1CreditsManager.plcBufferOccupied models that second gate against the
    // live Sonic1PlcService FIFO; any array here would be measured from one
    // recording rather than read out of the ROM.

    /**
     * ROM: Level_Delay runs 4 hidden frames after level load and before
     * {@code PalFadeIn_Alt} / the main level loop starts.
     */
    public static final int DEMO_LOAD_DELAY_FRAMES = 4;

    /** Demo fadeout duration (frames). */
    public static final int DEMO_FADEOUT_FRAMES = 60;
}
