package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.ShieldType;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * AutomaticTunnel (Object 0x24).
 * <p>
 * Captures the player and guides them along a predefined waypoint path through
 * cylindrical tube sections. Used in AIZ (tubes) and LBZ (water tunnels).
 * <p>
 * ROM: Obj_AutomaticTunnel (sonic3k.asm lines 57180-57457).
 * Path data: AutoTunnel_Data (sonic3k.asm lines 202488-203387).
 *
 * <h3>Subtype Encoding:</h3>
 * <ul>
 *   <li>Bits 0-4 (0x1F): Path ID (0-25)</li>
 *   <li>Bit 5 (0x20): LBZ2 mode — strip fire/lightning shields on entry, spawn exhaust on exit</li>
 *   <li>Bit 6 (0x40): Maintain velocity on exit (skip zeroing x/y vel)</li>
 *   <li>Bit 7 (0x80): Reverse path traversal direction</li>
 * </ul>
 *
 * <h3>State Machine (per-character):</h3>
 * <ul>
 *   <li>State 0 (INIT): Detect player in capture zone, lock and launch into path</li>
 *   <li>State 2 (RUN): Follow waypoints with calculated velocity</li>
 *   <li>State 4 (LAST_MOVE): 2-frame gravity exit, then release</li>
 * </ul>
 */
public class AutomaticTunnelObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AutomaticTunnelObjectInstance.class.getName());

    // =========================================================================
    // Constants from disassembly
    // =========================================================================

    /** Path traversal speed — ROM: move.w #$1000,d2 (line 57285) */
    private static final int PATH_SPEED = 0x1000;

    /** Rolling ground speed on capture — ROM: move.w #$800,ground_vel(a1) (line 57238) */
    private static final short CAPTURE_GROUND_VEL = 0x0800;

    /** Gravity applied during LAST_MOVE — ROM: addi.w #$38,y_vel(a1) (line 57338) */
    private static final int EXIT_GRAVITY = 0x38;

    /** Duration of LAST_MOVE phase in frames — ROM: move.b #2,2(a4) (line 57291) */
    private static final int EXIT_FRAMES = 2;

    // =========================================================================
    // Path data — AutoTunnel_Data (sonic3k.asm lines 202488-203387)
    // Each path: flat array of X, Y coordinate pairs.
    // =========================================================================

    static final int[][] PATHS = {
            // Path 0 (AutoTunnel_00): 3 waypoints
            {0x0F60, 0x0578, 0x0F60, 0x0548, 0x0F60, 0x0378},
            // Path 1 (AutoTunnel_01_02): 14 waypoints
            {0x0D40, 0x0770, 0x0D48, 0x0770, 0x0D50, 0x0770, 0x0D58, 0x0770,
             0x0D60, 0x0770, 0x0DB0, 0x0770, 0x0DD0, 0x077C, 0x0DE0, 0x079C,
             0x0DD6, 0x07BC, 0x0DB6, 0x07CE, 0x0D96, 0x07CE, 0x0D86, 0x07C8,
             0x0D70, 0x07A8, 0x0D70, 0x0688},
            // Path 2 = Path 1 (shared in ROM)
            {0x0D40, 0x0770, 0x0D48, 0x0770, 0x0D50, 0x0770, 0x0D58, 0x0770,
             0x0D60, 0x0770, 0x0DB0, 0x0770, 0x0DD0, 0x077C, 0x0DE0, 0x079C,
             0x0DD6, 0x07BC, 0x0DB6, 0x07CE, 0x0D96, 0x07CE, 0x0D86, 0x07C8,
             0x0D70, 0x07A8, 0x0D70, 0x0688},
            // Path 3 (AutoTunnel_03): 10 waypoints
            {0x0D30, 0x0770, 0x0DB0, 0x0770, 0x0DD0, 0x077C, 0x0DE0, 0x079C,
             0x0DD6, 0x07BC, 0x0DB6, 0x07CE, 0x0D96, 0x07CE, 0x0D86, 0x07C8,
             0x0D70, 0x07A8, 0x0D70, 0x0748},
            // Path 4 (AutoTunnel_04): 14 waypoints
            {0x2CC0, 0x09F0, 0x2CC8, 0x09F0, 0x2CD0, 0x09F0, 0x2CD8, 0x09F0,
             0x2CE0, 0x09F0, 0x2D30, 0x09F0, 0x2D50, 0x09FC, 0x2D60, 0x0A1C,
             0x2D56, 0x0A3C, 0x2D36, 0x0A4E, 0x2D16, 0x0A4E, 0x2D06, 0x0A48,
             0x2CF0, 0x0A28, 0x2CF0, 0x0908},
            // Path 5 (AutoTunnel_05): 10 waypoints
            {0x2CB0, 0x09F0, 0x2D30, 0x09F0, 0x2D50, 0x09FC, 0x2D60, 0x0A1C,
             0x2D56, 0x0A3C, 0x2D36, 0x0A4E, 0x2D16, 0x0A4E, 0x2D06, 0x0A48,
             0x2CF0, 0x0A28, 0x2CF0, 0x09C8},
            // Path 6 (AutoTunnel_06): 14 waypoints
            {0x3640, 0x0A70, 0x3648, 0x0A70, 0x3650, 0x0A70, 0x3658, 0x0A70,
             0x3660, 0x0A70, 0x36B0, 0x0A70, 0x36D0, 0x0A7C, 0x36E0, 0x0A9C,
             0x36D6, 0x0ABC, 0x36B6, 0x0ACE, 0x3696, 0x0ACE, 0x3686, 0x0AC8,
             0x3670, 0x0AA8, 0x3670, 0x0988},
            // Path 7 (AutoTunnel_07): 10 waypoints
            {0x3630, 0x0A70, 0x36B0, 0x0A70, 0x36D0, 0x0A7C, 0x36E0, 0x0A9C,
             0x36D6, 0x0ABC, 0x36B6, 0x0ACE, 0x3696, 0x0ACE, 0x3686, 0x0AC8,
             0x3670, 0x0AA8, 0x3670, 0x0A48},
            // Path 8 (AutoTunnel_08): 14 waypoints
            {0x37C0, 0x07F0, 0x37C8, 0x07F0, 0x37D0, 0x07F0, 0x37D8, 0x07F0,
             0x37E0, 0x07F0, 0x3830, 0x07F0, 0x3850, 0x07FC, 0x3860, 0x081C,
             0x3856, 0x083C, 0x3836, 0x084E, 0x3816, 0x084E, 0x3806, 0x0848,
             0x37F0, 0x0828, 0x37F0, 0x0708},
            // Path 9 (AutoTunnel_09): 10 waypoints
            {0x37B0, 0x07F0, 0x3830, 0x07F0, 0x3850, 0x07FC, 0x3860, 0x081C,
             0x3856, 0x083C, 0x3836, 0x084E, 0x3816, 0x084E, 0x3806, 0x0848,
             0x37F0, 0x0828, 0x37F0, 0x07C8},
            // Path 10 (AutoTunnel_0A): 14 waypoints
            {0x29C0, 0x0470, 0x29C8, 0x0470, 0x29D0, 0x0470, 0x29D8, 0x0470,
             0x29E0, 0x0470, 0x2A30, 0x0470, 0x2A50, 0x047C, 0x2A60, 0x049C,
             0x2A56, 0x04BC, 0x2A36, 0x04CE, 0x2A16, 0x04CE, 0x2A06, 0x04C8,
             0x29F0, 0x04A8, 0x29F0, 0x0388},
            // Path 11 (AutoTunnel_0B): 10 waypoints
            {0x29B0, 0x0470, 0x2A30, 0x0470, 0x2A50, 0x047C, 0x2A60, 0x049C,
             0x2A56, 0x04BC, 0x2A36, 0x04CE, 0x2A16, 0x04CE, 0x2A06, 0x04C8,
             0x29F0, 0x04A8, 0x29F0, 0x0448},
            // Path 12 (AutoTunnel_0C): 65 waypoints
            {0x26C0, 0x0530, 0x26C0, 0x06E0, 0x26B2, 0x0700, 0x2692, 0x0710,
             0x25F2, 0x0710, 0x25D2, 0x0704, 0x25C0, 0x06E4, 0x25C0, 0x04B4,
             0x25B0, 0x0484, 0x2590, 0x0464, 0x2560, 0x0450, 0x24D0, 0x0450,
             0x2490, 0x043B, 0x2450, 0x041F, 0x2400, 0x0410, 0x2300, 0x0410,
             0x22D0, 0x0415, 0x22A0, 0x042B, 0x2280, 0x0448, 0x2240, 0x0468,
             0x2200, 0x0470, 0x21C0, 0x0468, 0x2180, 0x0448, 0x2160, 0x042B,
             0x2130, 0x0415, 0x2100, 0x0410, 0x20D0, 0x0415, 0x20A0, 0x042B,
             0x2080, 0x0448, 0x2040, 0x0468, 0x2000, 0x0470, 0x1FC0, 0x0468,
             0x1F80, 0x0448, 0x1F60, 0x042B, 0x1F30, 0x0415, 0x1F00, 0x0410,
             0x1ED0, 0x0415, 0x1EA0, 0x042B, 0x1E80, 0x0448, 0x1E40, 0x0468,
             0x1E00, 0x0470, 0x1C70, 0x0470, 0x1C40, 0x0440, 0x1C40, 0x0320,
             0x1C50, 0x0300, 0x1C70, 0x02F0, 0x1F80, 0x02F0, 0x1FD0, 0x02E4,
             0x2000, 0x02C8, 0x2020, 0x02AB, 0x2040, 0x029A, 0x2080, 0x0290,
             0x20C0, 0x02A7, 0x2170, 0x0357, 0x21B0, 0x0370, 0x2400, 0x0370,
             0x2440, 0x0380, 0x2480, 0x0390, 0x24B0, 0x0384, 0x24C0, 0x0364,
             0x24C0, 0x00C4, 0x2490, 0x0090, 0x2450, 0x009C, 0x2440, 0x00CC,
             0x2440, 0x00FC},
            // Path 13 (AutoTunnel_0D): 25 waypoints
            {0x33C0, 0x0130, 0x33C0, 0x01E0, 0x33D0, 0x0200, 0x3400, 0x0210,
             0x3450, 0x0220, 0x34A0, 0x0270, 0x34C0, 0x02A0, 0x34C0, 0x0460,
             0x34CE, 0x0480, 0x34F0, 0x0490, 0x3710, 0x0490, 0x372E, 0x0480,
             0x3740, 0x0460, 0x3740, 0x0330, 0x3720, 0x0310, 0x35F0, 0x0310,
             0x35CE, 0x0300, 0x35C0, 0x02E0, 0x35C0, 0x0040, 0x35CC, 0x0020,
             0x3600, 0x0010, 0x3690, 0x0010, 0x36B4, 0x0020, 0x36C0, 0x0040,
             0x36C0, 0x0080},
            // Path 14 (AutoTunnel_0E): 14 waypoints
            {0x14C0, 0x0AB0, 0x14C0, 0x0B60, 0x14D0, 0x0B80, 0x14F0, 0x0B90,
             0x1610, 0x0B90, 0x1630, 0x0B80, 0x1640, 0x0B60, 0x1640, 0x08C0,
             0x1650, 0x08A0, 0x1670, 0x0890, 0x1890, 0x0890, 0x18B0, 0x089C,
             0x18C0, 0x08BC, 0x18C0, 0x08FC},
            // Path 15 (AutoTunnel_0F): 14 waypoints
            {0x3840, 0x0730, 0x3840, 0x0860, 0x3832, 0x0880, 0x3802, 0x0890,
             0x37D2, 0x0884, 0x37C0, 0x0864, 0x37C0, 0x03D4, 0x37D0, 0x03B4,
             0x37F0, 0x039C, 0x3820, 0x0390, 0x3990, 0x0390, 0x39B0, 0x039C,
             0x39C0, 0x03BC, 0x39C0, 0x03FC},
            // Path 16 (AutoTunnel_10): 31 waypoints
            {0x0F60, 0x05C8, 0x0F60, 0x0950, 0x0F64, 0x0980, 0x0F68, 0x0990,
             0x0F73, 0x09B0, 0x0F82, 0x09D0, 0x0F8C, 0x09E0, 0x0F98, 0x09F0,
             0x0FA5, 0x0A00, 0x0FB5, 0x0A10, 0x0FC5, 0x0A1C, 0x0FD5, 0x0A28,
             0x0FF5, 0x0A38, 0x1005, 0x0A40, 0x1025, 0x0A4A, 0x1035, 0x0A4C,
             0x1055, 0x0A50, 0x1265, 0x0A50, 0x12A5, 0x0A48, 0x12C5, 0x0A3C,
             0x12E5, 0x0A2C, 0x12F5, 0x0A20, 0x1305, 0x0A14, 0x1315, 0x0A08,
             0x1320, 0x09F8, 0x132F, 0x09E8, 0x1343, 0x09C8, 0x1350, 0x09A8,
             0x135A, 0x0988, 0x1360, 0x0958, 0x1360, 0x0878},
            // Path 17 (AutoTunnel_11): 31 waypoints
            {0x3760, 0x01C8, 0x3760, 0x0510, 0x375A, 0x0540, 0x3750, 0x0560,
             0x3743, 0x0580, 0x372F, 0x05A0, 0x3720, 0x05B0, 0x3715, 0x05C0,
             0x3705, 0x05CC, 0x36F5, 0x05D8, 0x36E5, 0x05E4, 0x36C5, 0x05F4,
             0x36A5, 0x0600, 0x3665, 0x0608, 0x3655, 0x0608, 0x3635, 0x0604,
             0x3625, 0x0602, 0x3605, 0x05F8, 0x35F5, 0x05F0, 0x35D5, 0x05E0,
             0x35C5, 0x05D4, 0x35B5, 0x05C8, 0x35A5, 0x05B8, 0x3598, 0x05A8,
             0x358C, 0x0598, 0x3582, 0x0588, 0x3573, 0x0568, 0x3568, 0x0548,
             0x3564, 0x0538, 0x3560, 0x0508, 0x3560, 0x0478},
            // Path 18 (AutoTunnel_12): 31 waypoints
            {0x3460, 0x05C8, 0x3460, 0x0690, 0x345A, 0x06C0, 0x3450, 0x06E0,
             0x3443, 0x0700, 0x342F, 0x0720, 0x3420, 0x0730, 0x3415, 0x0740,
             0x3405, 0x074C, 0x33F5, 0x0758, 0x33E5, 0x0764, 0x33C5, 0x0774,
             0x33A5, 0x0780, 0x3365, 0x0788, 0x3355, 0x0788, 0x3335, 0x0784,
             0x3325, 0x0782, 0x3305, 0x0778, 0x32F5, 0x0770, 0x32D5, 0x0760,
             0x32C5, 0x0754, 0x32B5, 0x0748, 0x32A5, 0x0738, 0x3298, 0x0728,
             0x328C, 0x0718, 0x3282, 0x0708, 0x3273, 0x06E8, 0x3268, 0x06C8,
             0x3264, 0x06B8, 0x3260, 0x0688, 0x3260, 0x05F8},
            // Path 19 (AutoTunnel_13): 10 waypoints
            {0x1C70, 0x0730, 0x1C70, 0x06C0, 0x1C62, 0x06A0, 0x1C42, 0x0692,
             0x1C32, 0x0692, 0x1C12, 0x069B, 0x1C00, 0x06BB, 0x1C08, 0x06DB,
             0x1C28, 0x06F0, 0x1CA8, 0x06F0},
            // Path 20 (AutoTunnel_14): 10 waypoints
            {0x3670, 0x0830, 0x3670, 0x07C0, 0x3662, 0x07A0, 0x3642, 0x0792,
             0x3632, 0x0792, 0x3612, 0x079B, 0x3600, 0x07BB, 0x3608, 0x07DB,
             0x3628, 0x07F0, 0x36A8, 0x07F0},
            // Path 21 (AutoTunnel_15): 12 waypoints — LRZ2
            {0x11B8, 0x06F0, 0x1270, 0x06F0, 0x128C, 0x06F3, 0x12A1, 0x06FE,
             0x12AD, 0x0710, 0x12B0, 0x0728, 0x12B0, 0x08B0, 0x12AC, 0x08D1,
             0x12A0, 0x08E3, 0x128C, 0x08EE, 0x1270, 0x08F0, 0x11B8, 0x08F0},
            // Path 22 (AutoTunnel_16): 32 waypoints — LRZ2
            {0x17B8, 0x0B70, 0x1870, 0x0B70, 0x1890, 0x0B6D, 0x18A0, 0x0B63,
             0x18AD, 0x0B53, 0x18B0, 0x0B33, 0x18B0, 0x08B0, 0x18B2, 0x0893,
             0x18BC, 0x0880, 0x18CE, 0x0872, 0x18F0, 0x0870, 0x1A70, 0x0870,
             0x1A90, 0x086D, 0x1AA2, 0x0862, 0x1AAE, 0x084E, 0x1AB0, 0x0830,
             0x1AB0, 0x06B0, 0x1AB2, 0x0692, 0x1ABD, 0x067E, 0x1AD2, 0x0671,
             0x1AF0, 0x0670, 0x1B70, 0x0670, 0x1B90, 0x066D, 0x1BA2, 0x0662,
             0x1BAF, 0x064E, 0x1BB0, 0x0630, 0x1BB0, 0x04B0, 0x1BB0, 0x0495,
             0x1BA2, 0x047E, 0x1B8D, 0x0471, 0x1B70, 0x0470, 0x1AB8, 0x0470},
            // Path 23 (AutoTunnel_17): 11 waypoints — LRZ2
            {0x22B8, 0x0070, 0x2370, 0x0070, 0x2390, 0x0073, 0x23A1, 0x007E,
             0x23AD, 0x0090, 0x23B0, 0x00B0, 0x23B0, 0x01B0, 0x23B2, 0x01D1,
             0x23BF, 0x01E4, 0x23D6, 0x01F0, 0x2448, 0x01F0},
            // Path 24 (AutoTunnel_18): 22 waypoints — LRZ2
            {0x2D48, 0x07F0, 0x2CF0, 0x07F0, 0x2CD0, 0x07EE, 0x2CBD, 0x07E3,
             0x2CB2, 0x07D0, 0x2CB0, 0x07B0, 0x2CB0, 0x0430, 0x2CB1, 0x0411,
             0x2CBB, 0x03FF, 0x2CCF, 0x03F2, 0x2CF0, 0x03F0, 0x2D70, 0x03F0,
             0x2D90, 0x03ED, 0x2DA2, 0x03E2, 0x2DAF, 0x03CE, 0x2DB0, 0x03B0,
             0x2DB0, 0x0330, 0x2DB2, 0x0311, 0x2DBC, 0x02FE, 0x2DD1, 0x02F1,
             0x2DEF, 0x02F0, 0x30F0, 0x02F0},
            // Path 25 (AutoTunnel_19): 12 waypoints — LRZ2
            {0x3A38, 0x03F0, 0x3AF0, 0x03F0, 0x3B10, 0x03EE, 0x3B23, 0x03E0,
             0x3B2F, 0x03CA, 0x3B30, 0x03B0, 0x3B30, 0x0230, 0x3B32, 0x0211,
             0x3B3C, 0x01FF, 0x3B50, 0x01F2, 0x3B70, 0x01F0, 0x3BC8, 0x01F0},
    };

    // =========================================================================
    // Per-character state
    // =========================================================================

    /**
     * Tracks one player's state within the tunnel.
     * ROM: 10-byte block at objoff_30 (P1) / objoff_3A (P2).
     */
    private static class CharState {
        /** Current phase: 0=INIT, 2=RUN, 4=LAST_MOVE */
        int phase;
        /** Frame countdown timer (ROM: 2(a4), high byte of word) */
        int duration;
        /** Bytes of path data remaining (ROM: 4(a4)) */
        int pathRemaining;
        /** Current index into path array (ROM: 6(a4) as pointer) */
        int pathIndex;
        /** Path data array */
        int[] path;
        /** Traversing path backwards */
        boolean reverse;
    }

    // =========================================================================
    // Instance state
    // =========================================================================

    private int subtype;
    private boolean reversePath;      // bit 7
    private boolean maintainVelocity; // bit 6
    private boolean lbz2Mode;         // bit 5
    private int pathId;               // bits 0-4

    private final CharState p1State = new CharState();
    private final CharState p2State = new CharState();

    public AutomaticTunnelObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AutomaticTunnel");
        this.subtype = spawn.subtype();
        this.reversePath = (subtype & 0x80) != 0;
        this.maintainVelocity = (subtype & 0x40) != 0;
        this.lbz2Mode = (subtype & 0x20) != 0;
        this.pathId = subtype & 0x1F;
    }

    @Override
    public AutomaticTunnelObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AutomaticTunnelObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM: loc_295E8 — process both players independently
        AbstractPlayableSprite player1 = (AbstractPlayableSprite) playerEntity;
        if (player1 != null) {
            processCharacter(player1, p1State);
        }

        // Process native Player 2 only. Extra engine sidekicks are intentionally
        // outside the ROM tunnel state block.
        PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
        if (nativeP2 instanceof AbstractPlayableSprite sidekick) {
            processCharacter(sidekick, p2State);
        }
    }

    // =========================================================================
    // Per-character state machine — ROM: sub_2960E
    // =========================================================================

    private void processCharacter(AbstractPlayableSprite player, CharState state) {
        switch (state.phase) {
            case 0 -> checkCapture(player, state);
            case 2 -> updatePathFollow(player, state);
            case 4 -> updateLastMove(player, state);
        }
    }

    // =========================================================================
    // State 0: Capture detection — ROM: Obj_AutoTunnelInit (line 57219)
    // =========================================================================

    private void checkCapture(AbstractPlayableSprite player, CharState state) {
        if (player.isDebugMode()) return;

        // ROM: move.w x_pos(a1),d0; sub.w x_pos(a0),d0; addi.w #$10,d0; cmpi.w #$20,d0
        int dx = player.getCentreX() - spawn.x() + 0x10;
        if (dx < 0 || dx >= 0x20) return;

        // ROM: move.w y_pos(a1),d1; sub.w y_pos(a0),d1; addi.w #$18,d1; cmpi.w #$28,d1
        int dy = player.getCentreY() - spawn.y() + 0x18;
        if (dy < 0 || dy >= 0x28) return;

        // ROM: tst.b object_control(a1); bne.s locret
        if (player.isObjectControlled()) return;

        // === Capture the player ===

        // ROM: addq.b #2,(a4)
        state.phase = 2;

        // ROM: move.b #$81,object_control(a1)
        ObjectControlState.nativeBit7FullControl().applyTo(player);

        // ROM: move.b #2,anim(a1)
        player.setAnimationId(2);

        // ROM: clr.b jumping(a1)
        player.setJumping(false);

        // ROM: move.w #$800,ground_vel(a1)
        player.setGSpeed(CAPTURE_GROUND_VEL);

        // ROM: move.w #0,x_vel(a1); move.w #0,y_vel(a1)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        // ROM: bclr #p1_pushing_bit,status(a0) -- the tunnel clears its OWN
        // p1 bit and ONLY p1, even when the captured character is Player_2
        // (docs/skdisasm/sonic3k.asm:57246). That asymmetry is the ROM's, so it
        // is modelled as written rather than generalised per-character.
        services().objectManager().solidContacts().releaseObjectPushLatch(
                services().playerQuery().mainPlayerOrNull(), this);
        // ROM: bclr #Status_Push,status(a1); bset #Status_InAir,status(a1)
        player.setPushing(false);
        player.setAir(true);

        // ROM: move.w x_pos(a0),x_pos(a1); move.w y_pos(a0),y_pos(a1)
        NativePositionOps.writeXPosPreserveSubpixel(player, spawn.x());
        NativePositionOps.writeYPosPreserveSubpixel(player, spawn.y());

        // Setup path and calculate initial velocity
        setupPath(player, state);

        // ROM: moveq #signextendB(sfx_Roll),d0; jsr (Play_SFX).l
        playSfx(GameSound.ROLLING);

        // ROM: LBZ2 shield stripping (lines 57250-57261)
        if (lbz2Mode && services().currentAct() > 0) {
            stripElementalShields(player);
        }
    }

    /**
     * Strip fire and lightning shields (LBZ2 water tunnels).
     * ROM lines 57254-57261: bclr Status_FireShield, bclr Status_LtngShield.
     * Bubble shield is intentionally preserved (underwater protection).
     */
    private void stripElementalShields(AbstractPlayableSprite player) {
        ShieldType type = player.getShieldType();
        if (type == ShieldType.FIRE || type == ShieldType.LIGHTNING) {
            player.removeShield();
        }
    }

    // =========================================================================
    // Path setup — ROM: AutoTunnel_GetPath (line 57344)
    // =========================================================================

    private void setupPath(AbstractPlayableSprite player, CharState state) {
        int id = pathId;

        if (reversePath) {
            // ROM: negative subtype branch (line 57346)
            setupReversePath(player, state, id);
        } else {
            // ROM: positive subtype branch (line 57364)
            // Tails redirect: path 0x10 -> path 0 when playing as Tails alone
            if (id == 0x10 && getPlayerCharacter() == PlayerCharacter.TAILS_ALONE) {
                id = 0;
            }
            setupForwardPath(player, state, id);
        }
    }

    private void setupForwardPath(AbstractPlayableSprite player, CharState state, int id) {
        if (id >= PATHS.length) {
            LOG.warning("AutomaticTunnel: path ID " + id + " out of range");
            releasePlayer(player, state);
            return;
        }

        state.path = PATHS[id];
        state.reverse = false;

        // ROM: move.w (a2)+,4(a4); subq.w #4,4(a4)
        // Path size in ROM bytes = waypoints * 4. pathRemaining = (waypointCount - 1) * 4.
        int waypointCount = state.path.length / 2;
        state.pathRemaining = (waypointCount - 1) * 4;

        // ROM: move.w (a2)+,d4; move.w d4,x_pos(a1); move.w (a2)+,d5; move.w d5,y_pos(a1)
        NativePositionOps.writeXPosPreserveSubpixel(player, state.path[0]);
        NativePositionOps.writeYPosPreserveSubpixel(player, state.path[1]);
        state.pathIndex = 2;

        // Calculate velocity to next waypoint
        calculateVelocity(player, state, state.path[2], state.path[3]);
    }

    private void setupReversePath(AbstractPlayableSprite player, CharState state, int id) {
        // ROM: andi.w #$1F,d0 (mask after sign test)
        if (id >= PATHS.length) {
            LOG.warning("AutomaticTunnel: path ID " + id + " out of range");
            releasePlayer(player, state);
            return;
        }

        state.path = PATHS[id];
        state.reverse = true;

        int waypointCount = state.path.length / 2;
        state.pathRemaining = (waypointCount - 1) * 4;

        // ROM: lea (a2,d0.w),a2 — jump to end of path data
        int lastIdx = state.path.length - 2;
        NativePositionOps.writeXPosPreserveSubpixel(player, state.path[lastIdx]);
        NativePositionOps.writeYPosPreserveSubpixel(player, state.path[lastIdx + 1]);

        // ROM: subq.w #8,a2 — back up one waypoint
        state.pathIndex = lastIdx - 2;

        // Calculate velocity to next (previous) waypoint
        calculateVelocity(player, state, state.path[state.pathIndex], state.path[state.pathIndex + 1]);
    }

    // =========================================================================
    // State 2: Path following — ROM: Obj_AutoTunnelRun (line 57267)
    // =========================================================================

    private void updatePathFollow(AbstractPlayableSprite player, CharState state) {
        // ROM: subq.b #1,2(a4); bhi.w loc_29768
        state.duration--;
        if (state.duration > 0) {
            moveCharacter(player);
            return;
        }

        // Timer expired — snap to current waypoint
        // ROM: movea.l 6(a4),a2; move.w (a2)+,d4; move.w d4,x_pos(a1); ...
        int waypointX = state.path[state.pathIndex];
        int waypointY = state.path[state.pathIndex + 1];
        NativePositionOps.writeXPosPreserveSubpixel(player, waypointX);
        NativePositionOps.writeYPosPreserveSubpixel(player, waypointY);

        // Advance path pointer
        // ROM: tst.b subtype(a0); bpl.s +; subq.w #8,a2
        if (state.reverse) {
            state.pathIndex -= 2;
        } else {
            state.pathIndex += 2;
        }

        // ROM: subq.w #4,4(a4); beq.s loc_2970A
        state.pathRemaining -= 4;
        if (state.pathRemaining <= 0) {
            // Path exhausted — transition to LAST_MOVE
            beginExit(player, state);
            // ROM: loc_2970A falls through to loc_29768, so the retained exit
            // velocity is applied immediately after the final waypoint snap.
            moveCharacter(player);
            return;
        }

        // Bounds check
        if (state.pathIndex < 0 || state.pathIndex + 1 >= state.path.length) {
            beginExit(player, state);
            return;
        }

        // Calculate velocity to next waypoint
        // ROM: move.w (a2)+,d4; move.w (a2)+,d5; move.w #$1000,d2; bra.w AutoTunnel_CalcSpeed
        int targetX = state.path[state.pathIndex];
        int targetY = state.path[state.pathIndex + 1];
        calculateVelocity(player, state, targetX, targetY);
    }

    /**
     * Begin the exit phase — ROM: loc_2970A (line 57289).
     */
    private void beginExit(AbstractPlayableSprite player, CharState state) {
        // ROM: addq.b #2,(a4) — advance to LAST_MOVE
        state.phase = 4;

        // ROM: move.b #2,2(a4)
        state.duration = EXIT_FRAMES;

        // ROM: andi.w #$FFF,y_pos(a1)
        int y = player.getCentreY() & 0xFFF;
        NativePositionOps.writeYPosPreserveSubpixel(player, y);

        // ROM: btst #6,subtype(a0); bne.s loc_2972C
        if (!maintainVelocity) {
            // ROM: move.w #0,x_vel(a1); move.w #0,y_vel(a1)
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
        }

        // ROM: moveq #signextendB(sfx_TubeLauncher),d0; jsr (Play_SFX).l
        playSfx(Sonic3kSfx.TUBE_LAUNCHER.id);

        // ROM: btst #5,subtype(a0), then allocate Obj_TunnelExhaustControl at the player.
        if (lbz2Mode) {
            int exhaustX = player.getCentreX();
            int exhaustY = player.getCentreY();
            int exhaustXVel = player.getXSpeed();
            int exhaustYVel = player.getYSpeed();
            spawnChild(() -> new TunnelExhaustControlObjectInstance(
                    buildSpawnAt(exhaustX, exhaustY), 0, exhaustXVel, exhaustYVel));
        }
    }

    // =========================================================================
    // State 4: Last move with gravity — ROM: Obj_AutoTunnelLastMove (line 57331)
    // =========================================================================

    private void updateLastMove(AbstractPlayableSprite player, CharState state) {
        // ROM: subq.b #1,2(a4); bne.s loc_2979A
        state.duration--;
        if (state.duration <= 0) {
            // ROM: clr.b object_control(a1); clr.b (a4)
            releasePlayer(player, state);
        }

        // ROM: addi.w #$38,y_vel(a1)
        player.setYSpeed((short) (player.getYSpeed() + EXIT_GRAVITY));

        // ROM: bra.s loc_29768 — move player
        moveCharacter(player);
    }

    // =========================================================================
    // Velocity calculation — ROM: AutoTunnel_CalcSpeed (line 57390)
    // Identical algorithm to MTZ spin tube.
    // =========================================================================

    /**
     * Calculates velocity to move from current position to target waypoint.
     * <p>
     * ROM algorithm: speed = 0x1000 on the dominant axis (whichever distance is
     * larger). Cross-axis velocity is proportionally scaled. Duration (frame count)
     * is the quotient of the dominant distance / speed, stored as the high byte.
     */
    private void calculateVelocity(AbstractPlayableSprite player, CharState state,
                                    int targetX, int targetY) {
        int currentX = player.getCentreX();
        int currentY = player.getCentreY();
        int dx = targetX - currentX;
        int dy = targetY - currentY;
        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);

        int speed = PATH_SPEED;
        int xVel, yVel, duration;

        if (absDy >= absDx) {
            // Y is dominant axis
            // ROM: move.w d3,y_vel(a1) — d3 = speed, negated if dy < 0
            yVel = (dy >= 0) ? speed : -speed;

            // ROM: divs.w d3,d1 -> duration = (dy << 16) / yVel
            if (dy != 0) {
                duration = (int) (((long) dy << 16) / yVel);
            } else {
                duration = 0;
            }

            // ROM: divs.w d1,d0 -> xVel = (dx << 16) / duration
            if (duration != 0) {
                xVel = (int) (((long) dx << 16) / duration);
            } else {
                xVel = 0;
            }

            state.duration = (Math.abs(duration) >> 8) & 0xFF;
        } else {
            // X is dominant axis
            // ROM: move.w d2,x_vel(a1) — d2 = speed, negated if dx < 0
            xVel = (dx >= 0) ? speed : -speed;

            // ROM: divs.w d2,d0 -> duration = (dx << 16) / xVel
            if (dx != 0) {
                duration = (int) (((long) dx << 16) / xVel);
            } else {
                duration = 0;
            }

            // ROM: divs.w d0,d1 -> yVel = (dy << 16) / duration
            if (duration != 0) {
                yVel = (int) (((long) dy << 16) / duration);
            } else {
                yVel = 0;
            }

            state.duration = (Math.abs(duration) >> 8) & 0xFF;
        }

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);
    }

    // =========================================================================
    // Movement — ROM: loc_29768 (line 57315)
    // =========================================================================

    /**
     * Moves the player by their current velocity using 16.16 fixed-point math.
     * ROM: move.l x_pos(a1),d2; ext.l d0; asl.l #8,d0; add.l d0,d2; ...
     */
    private void moveCharacter(AbstractPlayableSprite player) {
        player.move(player.getXSpeed(), player.getYSpeed());
    }

    // =========================================================================
    // Release — clears object control and resets state
    // =========================================================================

    private void releasePlayer(AbstractPlayableSprite player, CharState state) {
        ObjectControlState.none().applyTo(player);
        state.phase = 0;
        state.path = null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PlayerCharacter getPlayerCharacter() {
        if (services().levelEventProvider() instanceof Sonic3kLevelEventManager s3k) {
            return s3k.getPlayerCharacter();
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }

    private void playSfx(GameSound sound) {
        try {
            services().playSfx(sound);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    private void playSfx(int soundId) {
        try {
            services().playSfx(soundId);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
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
        // Keep alive while either character is captured
        return p1State.phase != 0 || p2State.phase != 0;
    }
}
