package uk.co.jamesj999.sonic.game.sonic2.specialstage;

/**
 * Lookup tables for decoding Special Stage track mapping frames.
 *
 * These tables are used by the track frame decoder to convert indices
 * from the track mapping data into actual pattern name table entries.
 *
 * The pattern name format is a 16-bit word with the following bit layout:
 *   Bit 15:    Priority (1 = high priority)
 *   Bits 14-13: Palette index (0-3)
 *   Bit 12:    Vertical flip
 *   Bit 11:    Horizontal flip
 *   Bits 10-0:  Tile address (0-2047)
 *
 * Values extracted from s2disasm (Sonic 2 disassembly):
 *   SSPNT_UncLUT - Uncompressed pattern name lookup table (0x264 entries)
 *   SSPNT_RLELUT - RLE pattern name + count lookup table (0x86 entries × 2)
 */
public final class Sonic2TrackLookupTables {

    private Sonic2TrackLookupTables() {}

    /**
     * Creates a pattern name table entry from the component values.
     * Matches the make_block_tile macro from s2disasm.
     *
     * @param addr Tile address (0-2047)
     * @param flx Horizontal flip (0 or 1)
     * @param fly Vertical flip (0 or 1)
     * @param pal Palette index (0-3)
     * @param pri Priority (0 or 1)
     * @return 16-bit pattern name table entry
     */
    private static int makeBlockTile(int addr, int flx, int fly, int pal, int pri) {
        return ((pri & 1) << 15) | ((pal & 3) << 13) | ((fly & 1) << 12) | ((flx & 1) << 11) | (addr & 0x7FF);
    }

    /**
     * Uncompressed pattern name lookup table.
     * Indexed by values from segment 2 of the track mapping data.
     * Segment 2 can read either 6-bit or 10-bit indices.
     * 6-bit indices use SSPNT_UncLUT directly (indices 0x00-0x3F).
     * 10-bit indices add an offset to access SSPNT_UncLUT_Part2 (indices 0x40+).
     */
    public static final int[] UNC_LUT = {
        // SSPNT_UncLUT (indices 0x00 to 0x3F)
        makeBlockTile(0x0001,0,0,0,1), makeBlockTile(0x0007,0,0,0,1), makeBlockTile(0x002C,0,0,0,1), makeBlockTile(0x000B,0,0,0,1), // $00
        makeBlockTile(0x0024,0,0,0,1), makeBlockTile(0x0024,1,0,0,1), makeBlockTile(0x0039,0,0,0,1), makeBlockTile(0x002B,1,0,0,1), // $04
        makeBlockTile(0x005D,0,0,0,1), makeBlockTile(0x005D,1,0,0,1), makeBlockTile(0x002B,0,0,0,1), makeBlockTile(0x004A,0,0,0,1), // $08
        makeBlockTile(0x0049,0,0,0,1), makeBlockTile(0x0037,0,0,0,1), makeBlockTile(0x0049,1,0,0,1), makeBlockTile(0x0045,0,0,0,1), // $0C
        makeBlockTile(0x0045,1,0,0,1), makeBlockTile(0x003A,1,0,0,1), makeBlockTile(0x0048,0,0,0,1), makeBlockTile(0x0050,1,0,0,1), // $10
        makeBlockTile(0x0036,0,0,0,1), makeBlockTile(0x0037,1,0,0,1), makeBlockTile(0x003A,0,0,0,1), makeBlockTile(0x0050,0,0,0,1), // $14
        makeBlockTile(0x0042,1,0,0,1), makeBlockTile(0x0042,0,0,0,1), makeBlockTile(0x0015,1,0,0,1), makeBlockTile(0x001D,0,0,0,1), // $18
        makeBlockTile(0x004B,0,0,0,1), makeBlockTile(0x0017,1,0,0,1), makeBlockTile(0x0048,1,0,0,1), makeBlockTile(0x0036,1,0,0,1), // $1C
        makeBlockTile(0x0038,0,0,0,1), makeBlockTile(0x004B,1,0,0,1), makeBlockTile(0x0015,0,0,0,1), makeBlockTile(0x0021,0,0,0,1), // $20
        makeBlockTile(0x0017,0,0,0,1), makeBlockTile(0x0033,0,0,0,1), makeBlockTile(0x001A,0,0,0,1), makeBlockTile(0x002A,0,0,0,1), // $24
        makeBlockTile(0x005E,0,0,0,1), makeBlockTile(0x0028,0,0,0,1), makeBlockTile(0x0030,0,0,0,1), makeBlockTile(0x0021,1,0,0,1), // $28
        makeBlockTile(0x0038,1,0,0,1), makeBlockTile(0x001A,1,0,0,1), makeBlockTile(0x0025,0,0,0,1), makeBlockTile(0x005E,1,0,0,1), // $2C
        makeBlockTile(0x0025,1,0,0,1), makeBlockTile(0x0033,1,0,0,1), makeBlockTile(0x0003,0,0,0,1), makeBlockTile(0x0014,1,0,0,1), // $30
        makeBlockTile(0x0014,0,0,0,1), makeBlockTile(0x0004,0,0,0,1), makeBlockTile(0x004E,0,0,0,1), makeBlockTile(0x0003,1,0,0,1), // $34
        makeBlockTile(0x000C,0,0,0,1), makeBlockTile(0x002A,1,0,0,1), makeBlockTile(0x0002,0,0,0,1), makeBlockTile(0x0051,0,0,0,1), // $38
        makeBlockTile(0x0040,0,0,0,1), makeBlockTile(0x003D,0,0,0,1), makeBlockTile(0x0019,0,0,0,1), makeBlockTile(0x0052,0,0,0,1), // $3C
        // SSPNT_UncLUT_Part2 (indices 0x40 to 0x7F)
        makeBlockTile(0x0009,0,0,0,1), makeBlockTile(0x005A,0,0,0,1), makeBlockTile(0x0030,1,0,0,1), makeBlockTile(0x004E,1,0,0,1), // $40
        makeBlockTile(0x0052,1,0,0,1), makeBlockTile(0x0051,1,0,0,1), makeBlockTile(0x0009,1,0,0,1), makeBlockTile(0x0040,1,0,0,1), // $44
        makeBlockTile(0x002F,0,0,0,1), makeBlockTile(0x005A,1,0,0,1), makeBlockTile(0x0018,1,0,0,1), makeBlockTile(0x0034,0,0,0,1), // $48
        makeBlockTile(0x0019,1,0,0,1), makeBlockTile(0x002F,1,0,0,1), makeBlockTile(0x003D,1,0,0,1), makeBlockTile(0x003E,0,0,0,1), // $4C
        makeBlockTile(0x0018,0,0,0,1), makeBlockTile(0x000C,1,0,0,1), makeBlockTile(0x0012,0,0,0,1), makeBlockTile(0x0004,1,0,0,1), // $50
        makeBlockTile(0x0026,0,0,0,1), makeBlockTile(0x0034,1,0,0,1), makeBlockTile(0x0005,1,0,0,1), makeBlockTile(0x003B,0,0,0,1), // $54
        makeBlockTile(0x003E,1,0,0,1), makeBlockTile(0x003B,1,0,0,1), makeBlockTile(0x0000,0,0,0,1), makeBlockTile(0x0002,1,0,0,1), // $58
        makeBlockTile(0x0005,0,0,0,1), makeBlockTile(0x000D,0,0,0,1), makeBlockTile(0x0055,0,0,0,1), makeBlockTile(0x00AF,0,0,0,1), // $5C
        makeBlockTile(0x001C,0,0,0,1), makeBlockTile(0x001B,0,0,0,1), makeBlockTile(0x000D,1,0,0,1), makeBlockTile(0x0016,0,0,0,1), // $60
        makeBlockTile(0x0012,1,0,0,1), makeBlockTile(0x001F,0,0,0,1), makeBlockTile(0x0032,1,0,0,1), makeBlockTile(0x0013,0,0,0,1), // $64
        makeBlockTile(0x0092,0,0,0,1), makeBlockTile(0x0026,1,0,0,1), makeBlockTile(0x0010,0,0,0,1), makeBlockTile(0x004D,0,0,0,1), // $68
        makeBlockTile(0x0047,0,0,0,1), makeBlockTile(0x0092,1,0,0,1), makeBlockTile(0x0000,1,0,0,1), makeBlockTile(0x0062,0,0,0,1), // $6C
        makeBlockTile(0x0066,0,0,0,1), makeBlockTile(0x0090,0,0,0,1), makeBlockTile(0x0008,0,0,0,1), makeBlockTile(0x007C,1,0,0,1), // $70
        makeBlockTile(0x0067,1,0,0,1), makeBlockTile(0x00F7,1,0,0,1), makeBlockTile(0x000E,0,0,0,1), makeBlockTile(0x0060,0,0,0,1), // $74
        makeBlockTile(0x0032,0,0,0,1), makeBlockTile(0x0094,0,0,0,1), makeBlockTile(0x001C,1,0,0,1), makeBlockTile(0x0105,1,0,0,1), // $78
        makeBlockTile(0x00B0,1,0,0,1), makeBlockTile(0x0059,0,0,0,1), makeBlockTile(0x000F,0,0,0,1), makeBlockTile(0x0067,0,0,0,1), // $7C
        // indices 0x80 to 0xBF
        makeBlockTile(0x0068,0,0,0,1), makeBlockTile(0x0094,1,0,0,1), makeBlockTile(0x007C,0,0,0,1), makeBlockTile(0x00B0,0,0,0,1), // $80
        makeBlockTile(0x00B1,0,0,0,1), makeBlockTile(0x0006,0,0,0,1), makeBlockTile(0x0041,1,0,0,1), makeBlockTile(0x0087,0,0,0,1), // $84
        makeBlockTile(0x0093,0,0,0,1), makeBlockTile(0x00CC,0,0,0,1), makeBlockTile(0x001F,1,0,0,1), makeBlockTile(0x0068,1,0,0,1), // $88
        makeBlockTile(0x0041,0,0,0,1), makeBlockTile(0x008F,0,0,0,1), makeBlockTile(0x0090,1,0,0,1), makeBlockTile(0x00C2,0,0,0,1), // $8C
        makeBlockTile(0x0013,1,0,0,1), makeBlockTile(0x00C2,1,0,0,1), makeBlockTile(0x005C,0,0,0,1), makeBlockTile(0x0064,0,0,0,1), // $90
        makeBlockTile(0x00D8,0,0,0,1), makeBlockTile(0x001B,1,0,0,1), makeBlockTile(0x00CC,1,0,0,1), makeBlockTile(0x0011,1,0,0,1), // $94
        makeBlockTile(0x0055,1,0,0,1), makeBlockTile(0x00E2,1,0,0,1), makeBlockTile(0x00F3,1,0,0,1), makeBlockTile(0x0044,0,0,0,1), // $98
        makeBlockTile(0x00D8,1,0,0,1), makeBlockTile(0x0085,0,0,0,1), makeBlockTile(0x00A1,0,0,0,1), makeBlockTile(0x00C1,0,0,0,1), // $9C
        makeBlockTile(0x0119,0,0,0,1), makeBlockTile(0x0089,1,0,0,1), makeBlockTile(0x000A,1,0,0,1), makeBlockTile(0x0022,1,0,0,1), // $A0
        makeBlockTile(0x003F,0,0,0,1), makeBlockTile(0x005B,0,0,0,1), makeBlockTile(0x007F,0,0,0,1), makeBlockTile(0x0086,1,0,0,1), // $A4
        makeBlockTile(0x0008,1,0,0,1), makeBlockTile(0x0080,0,0,0,1), makeBlockTile(0x0066,1,0,0,1), makeBlockTile(0x00E0,1,0,0,1), // $A8
        makeBlockTile(0x00C1,1,0,0,1), makeBlockTile(0x0020,0,0,0,1), makeBlockTile(0x0022,0,0,0,1), makeBlockTile(0x0054,0,0,0,1), // $AC
        makeBlockTile(0x00D2,0,0,0,1), makeBlockTile(0x0059,1,0,0,1), makeBlockTile(0x00B1,1,0,0,1), makeBlockTile(0x0060,1,0,0,1), // $B0
        makeBlockTile(0x0119,1,0,0,1), makeBlockTile(0x00A4,1,0,0,1), makeBlockTile(0x008F,1,0,0,1), makeBlockTile(0x000A,0,0,0,1), // $B4
        makeBlockTile(0x0061,0,0,0,1), makeBlockTile(0x0075,0,0,0,1), makeBlockTile(0x0095,0,0,0,1), makeBlockTile(0x00B6,0,0,0,1), // $B8
        makeBlockTile(0x00E0,0,0,0,1), makeBlockTile(0x0010,1,0,0,1), makeBlockTile(0x0098,1,0,0,1), makeBlockTile(0x005B,1,0,0,1), // $BC
        // indices 0xC0 to 0xFF
        makeBlockTile(0x00D2,1,0,0,1), makeBlockTile(0x0016,1,0,0,1), makeBlockTile(0x0053,0,0,0,1), makeBlockTile(0x0091,0,0,0,1), // $C0
        makeBlockTile(0x0096,0,0,0,1), makeBlockTile(0x00A4,0,0,0,1), makeBlockTile(0x00DD,0,0,0,1), makeBlockTile(0x00E6,0,0,0,1), // $C4
        makeBlockTile(0x007A,1,0,0,1), makeBlockTile(0x004D,1,0,0,1), makeBlockTile(0x00E6,1,0,0,1), makeBlockTile(0x0011,0,0,0,1), // $C8
        makeBlockTile(0x0057,0,0,0,1), makeBlockTile(0x007A,0,0,0,1), makeBlockTile(0x0086,0,0,0,1), makeBlockTile(0x009E,0,0,0,1), // $CC
        makeBlockTile(0x00DA,0,0,0,1), makeBlockTile(0x0058,0,0,0,1), makeBlockTile(0x00DC,0,0,0,1), makeBlockTile(0x00E3,0,0,0,1), // $D0
        makeBlockTile(0x0063,1,0,0,1), makeBlockTile(0x003C,0,0,0,1), makeBlockTile(0x0056,0,0,0,1), makeBlockTile(0x0069,0,0,0,1), // $D4
        makeBlockTile(0x007E,0,0,0,1), makeBlockTile(0x00AE,0,0,0,1), makeBlockTile(0x00B5,0,0,0,1), makeBlockTile(0x00B8,0,0,0,1), // $D8
        makeBlockTile(0x00CD,0,0,0,1), makeBlockTile(0x00FB,0,0,0,1), makeBlockTile(0x00FF,0,0,0,1), makeBlockTile(0x005C,1,0,0,1), // $DC
        makeBlockTile(0x00CD,1,0,0,1), makeBlockTile(0x0074,1,0,0,1), makeBlockTile(0x00EA,1,0,0,1), makeBlockTile(0x00FF,1,0,0,1), // $E0
        makeBlockTile(0x00B5,1,0,0,1), makeBlockTile(0x0043,0,0,0,1), makeBlockTile(0x006C,0,0,0,1), makeBlockTile(0x0074,0,0,0,1), // $E4
        makeBlockTile(0x0077,0,0,0,1), makeBlockTile(0x0089,0,0,0,1), makeBlockTile(0x0097,0,0,0,1), makeBlockTile(0x009F,0,0,0,1), // $E8
        makeBlockTile(0x00A0,0,0,0,1), makeBlockTile(0x0113,0,0,0,1), makeBlockTile(0x011B,0,0,0,1), makeBlockTile(0x0078,1,0,0,1), // $EC
        makeBlockTile(0x000F,1,0,0,1), makeBlockTile(0x00E1,1,0,0,1), makeBlockTile(0x00FB,1,0,0,1), makeBlockTile(0x0128,1,0,0,1), // $F0
        makeBlockTile(0x0063,0,0,0,1), makeBlockTile(0x0084,0,0,0,1), makeBlockTile(0x008D,0,0,0,1), makeBlockTile(0x00CB,0,0,0,1), // $F4
        makeBlockTile(0x00D7,0,0,0,1), makeBlockTile(0x00E9,0,0,0,1), makeBlockTile(0x0128,0,0,0,1), makeBlockTile(0x0138,0,0,0,1), // $F8
        makeBlockTile(0x00AE,1,0,0,1), makeBlockTile(0x00EC,1,0,0,1), makeBlockTile(0x0031,0,0,0,1), makeBlockTile(0x004C,0,0,0,1), // $FC
        // indices 0x100 to 0x13F
        makeBlockTile(0x00E2,0,0,0,1), makeBlockTile(0x00EA,0,0,0,1), makeBlockTile(0x0064,1,0,0,1), makeBlockTile(0x0029,0,0,0,1), // $100
        makeBlockTile(0x002D,0,0,0,1), makeBlockTile(0x006D,0,0,0,1), makeBlockTile(0x0078,0,0,0,1), makeBlockTile(0x0088,0,0,0,1), // $104
        makeBlockTile(0x00B4,0,0,0,1), makeBlockTile(0x00BE,0,0,0,1), makeBlockTile(0x00CF,0,0,0,1), makeBlockTile(0x00E1,0,0,0,1), // $108
        makeBlockTile(0x00E4,0,0,0,1), makeBlockTile(0x0054,1,0,0,1), makeBlockTile(0x00D6,1,0,0,1), makeBlockTile(0x00D7,1,0,0,1), // $10C
        makeBlockTile(0x0061,1,0,0,1), makeBlockTile(0x012B,1,0,0,1), makeBlockTile(0x0047,1,0,0,1), makeBlockTile(0x0035,0,0,0,1), // $110
        makeBlockTile(0x006A,0,0,0,1), makeBlockTile(0x0072,0,0,0,1), makeBlockTile(0x0073,0,0,0,1), makeBlockTile(0x0098,0,0,0,1), // $114
        makeBlockTile(0x00D5,0,0,0,1), makeBlockTile(0x00D6,0,0,0,1), makeBlockTile(0x0116,0,0,0,1), makeBlockTile(0x011E,0,0,0,1), // $118
        makeBlockTile(0x0126,0,0,0,1), makeBlockTile(0x0127,0,0,0,1), makeBlockTile(0x012F,0,0,0,1), makeBlockTile(0x015D,0,0,0,1), // $11C
        makeBlockTile(0x0069,1,0,0,1), makeBlockTile(0x0088,1,0,0,1), makeBlockTile(0x0075,1,0,0,1), makeBlockTile(0x0097,1,0,0,1), // $120
        makeBlockTile(0x00B4,1,0,0,1), makeBlockTile(0x00D1,1,0,0,1), makeBlockTile(0x00D4,1,0,0,1), makeBlockTile(0x00D5,1,0,0,1), // $124
        makeBlockTile(0x00CB,1,0,0,1), makeBlockTile(0x00E4,1,0,0,1), makeBlockTile(0x0091,1,0,0,1), makeBlockTile(0x0062,1,0,0,1), // $128
        makeBlockTile(0x0006,1,0,0,1), makeBlockTile(0x00B8,1,0,0,1), makeBlockTile(0x0065,0,0,0,1), makeBlockTile(0x006E,0,0,0,1), // $12C
        makeBlockTile(0x0071,0,0,0,1), makeBlockTile(0x007D,0,0,0,1), makeBlockTile(0x00D1,0,0,0,1), makeBlockTile(0x00E7,0,0,0,1), // $130
        makeBlockTile(0x00F9,0,0,0,1), makeBlockTile(0x0108,0,0,0,1), makeBlockTile(0x012E,0,0,0,1), makeBlockTile(0x014B,0,0,0,1), // $134
        makeBlockTile(0x0081,1,0,0,1), makeBlockTile(0x0085,1,0,0,1), makeBlockTile(0x0077,1,0,0,1), makeBlockTile(0x007E,1,0,0,1), // $138
        makeBlockTile(0x0095,1,0,0,1), makeBlockTile(0x00DF,1,0,0,1), makeBlockTile(0x0087,1,0,0,1), makeBlockTile(0x006C,1,0,0,1), // $13C
        // indices 0x140 to 0x17F
        makeBlockTile(0x00F5,1,0,0,1), makeBlockTile(0x0108,1,0,0,1), makeBlockTile(0x0079,1,0,0,1), makeBlockTile(0x006D,1,0,0,1), // $140
        makeBlockTile(0x012A,1,0,0,1), makeBlockTile(0x00AA,1,0,0,1), makeBlockTile(0x001E,0,0,0,1), makeBlockTile(0x0027,0,0,0,1), // $144
        makeBlockTile(0x0046,0,0,0,1), makeBlockTile(0x005F,0,0,0,1), makeBlockTile(0x0070,0,0,0,1), makeBlockTile(0x0079,0,0,0,1), // $148
        makeBlockTile(0x009A,0,0,0,1), makeBlockTile(0x00AA,0,0,0,1), makeBlockTile(0x00C3,0,0,0,1), makeBlockTile(0x00D3,0,0,0,1), // $14C
        makeBlockTile(0x00D4,0,0,0,1), makeBlockTile(0x00DE,0,0,0,1), makeBlockTile(0x00DF,0,0,0,1), makeBlockTile(0x00F8,0,0,0,1), // $150
        makeBlockTile(0x0100,0,0,0,1), makeBlockTile(0x0101,0,0,0,1), makeBlockTile(0x012B,0,0,0,1), makeBlockTile(0x0133,0,0,0,1), // $154
        makeBlockTile(0x0136,0,0,0,1), makeBlockTile(0x0143,0,0,0,1), makeBlockTile(0x0151,0,0,0,1), makeBlockTile(0x002E,1,0,0,1), // $158
        makeBlockTile(0x009E,1,0,0,1), makeBlockTile(0x0099,1,0,0,1), makeBlockTile(0x00D3,1,0,0,1), makeBlockTile(0x00DD,1,0,0,1), // $15C
        makeBlockTile(0x00DE,1,0,0,1), makeBlockTile(0x00E9,1,0,0,1), makeBlockTile(0x00EF,1,0,0,1), makeBlockTile(0x00F0,1,0,0,1), // $160
        makeBlockTile(0x00F8,1,0,0,1), makeBlockTile(0x0127,1,0,0,1), makeBlockTile(0x00BE,1,0,0,1), makeBlockTile(0x0096,1,0,0,1), // $164
        makeBlockTile(0x004F,0,0,0,1), makeBlockTile(0x006F,0,0,0,1), makeBlockTile(0x0081,0,0,0,1), makeBlockTile(0x008B,0,0,0,1), // $168
        makeBlockTile(0x008E,0,0,0,1), makeBlockTile(0x009C,0,0,0,1), makeBlockTile(0x00A3,0,0,0,1), makeBlockTile(0x00B3,0,0,0,1), // $16C
        makeBlockTile(0x00C0,0,0,0,1), makeBlockTile(0x00CE,0,0,0,1), makeBlockTile(0x00F0,0,0,0,1), makeBlockTile(0x00F1,0,0,0,1), // $170
        makeBlockTile(0x00F5,0,0,0,1), makeBlockTile(0x00F7,0,0,0,1), makeBlockTile(0x0102,0,0,0,1), makeBlockTile(0x0104,0,0,0,1), // $174
        makeBlockTile(0x0105,0,0,0,1), makeBlockTile(0x0109,0,0,0,1), makeBlockTile(0x010C,0,0,0,1), makeBlockTile(0x0114,0,0,0,1), // $178
        makeBlockTile(0x0118,0,0,0,1), makeBlockTile(0x0120,0,0,0,1), makeBlockTile(0x0124,0,0,0,1), makeBlockTile(0x0125,0,0,0,1), // $17C
        // indices 0x180 to 0x1BF
        makeBlockTile(0x012A,0,0,0,1), makeBlockTile(0x0130,0,0,0,1), makeBlockTile(0x0132,0,0,0,1), makeBlockTile(0x0137,0,0,0,1), // $180
        makeBlockTile(0x0159,0,0,0,1), makeBlockTile(0x0165,0,0,0,1), makeBlockTile(0x003F,1,0,0,1), makeBlockTile(0x006B,1,0,0,1), // $184
        makeBlockTile(0x0080,1,0,0,1), makeBlockTile(0x0053,1,0,0,1), makeBlockTile(0x00C6,1,0,0,1), makeBlockTile(0x00CF,1,0,0,1), // $188
        makeBlockTile(0x00D9,1,0,0,1), makeBlockTile(0x00DC,1,0,0,1), makeBlockTile(0x0056,1,0,0,1), makeBlockTile(0x00B6,1,0,0,1), // $18C
        makeBlockTile(0x00F9,1,0,0,1), makeBlockTile(0x0102,1,0,0,1), makeBlockTile(0x0104,1,0,0,1), makeBlockTile(0x0115,1,0,0,1), // $190
        makeBlockTile(0x006A,1,0,0,1), makeBlockTile(0x0113,1,0,0,1), makeBlockTile(0x0072,1,0,0,1), makeBlockTile(0x0035,1,0,0,1), // $194
        makeBlockTile(0x0138,1,0,0,1), makeBlockTile(0x015D,1,0,0,1), makeBlockTile(0x0143,1,0,0,1), makeBlockTile(0x0023,0,0,0,1), // $198
        makeBlockTile(0x0076,0,0,0,1), makeBlockTile(0x007B,0,0,0,1), makeBlockTile(0x008A,0,0,0,1), makeBlockTile(0x009D,0,0,0,1), // $19C
        makeBlockTile(0x00A6,0,0,0,1), makeBlockTile(0x00A8,0,0,0,1), makeBlockTile(0x00AC,0,0,0,1), makeBlockTile(0x00B2,0,0,0,1), // $1A0
        makeBlockTile(0x00B7,0,0,0,1), makeBlockTile(0x00BB,0,0,0,1), makeBlockTile(0x00BC,0,0,0,1), makeBlockTile(0x00BD,0,0,0,1), // $1A4
        makeBlockTile(0x00C6,0,0,0,1), makeBlockTile(0x00E5,0,0,0,1), makeBlockTile(0x00E8,0,0,0,1), makeBlockTile(0x00EE,0,0,0,1), // $1A8
        makeBlockTile(0x00F4,0,0,0,1), makeBlockTile(0x010A,0,0,0,1), makeBlockTile(0x010D,0,0,0,1), makeBlockTile(0x0111,0,0,0,1), // $1AC
        makeBlockTile(0x0115,0,0,0,1), makeBlockTile(0x011A,0,0,0,1), makeBlockTile(0x011F,0,0,0,1), makeBlockTile(0x0122,0,0,0,1), // $1B0
        makeBlockTile(0x0123,0,0,0,1), makeBlockTile(0x0139,0,0,0,1), makeBlockTile(0x013A,0,0,0,1), makeBlockTile(0x013C,0,0,0,1), // $1B4
        makeBlockTile(0x0142,0,0,0,1), makeBlockTile(0x0144,0,0,0,1), makeBlockTile(0x0147,0,0,0,1), makeBlockTile(0x0148,0,0,0,1), // $1B8
        makeBlockTile(0x015E,0,0,0,1), makeBlockTile(0x015F,0,0,0,1), makeBlockTile(0x0163,0,0,0,1), makeBlockTile(0x0168,0,0,0,1), // $1BC
        // indices 0x1C0 to 0x1FF
        makeBlockTile(0x016A,0,0,0,1), makeBlockTile(0x016C,0,0,0,1), makeBlockTile(0x0170,0,0,0,1), makeBlockTile(0x00E5,1,0,0,1), // $1C0
        makeBlockTile(0x00CE,1,0,0,1), makeBlockTile(0x00EE,1,0,0,1), makeBlockTile(0x00F1,1,0,0,1), makeBlockTile(0x0084,1,0,0,1), // $1C4
        makeBlockTile(0x00FD,1,0,0,1), makeBlockTile(0x0100,1,0,0,1), makeBlockTile(0x00B9,1,0,0,1), makeBlockTile(0x0117,1,0,0,1), // $1C8
        makeBlockTile(0x0071,1,0,0,1), makeBlockTile(0x0109,1,0,0,1), makeBlockTile(0x010D,1,0,0,1), makeBlockTile(0x0065,1,0,0,1), // $1CC
        makeBlockTile(0x0125,1,0,0,1), makeBlockTile(0x0122,1,0,0,1), makeBlockTile(0x0031,1,0,0,1), makeBlockTile(0x003C,1,0,0,1), // $1D0
        makeBlockTile(0x010F,1,0,0,1), makeBlockTile(0x00C5,1,0,0,1), makeBlockTile(0x0133,1,0,0,1), makeBlockTile(0x0137,1,0,0,1), // $1D4
        makeBlockTile(0x011F,1,0,0,1), makeBlockTile(0x002E,0,0,0,1), makeBlockTile(0x006B,0,0,0,1), makeBlockTile(0x0082,0,0,0,1), // $1D8
        makeBlockTile(0x0083,0,0,0,1), makeBlockTile(0x008C,0,0,0,1), makeBlockTile(0x0099,0,0,0,1), makeBlockTile(0x009B,0,0,0,1), // $1DC
        makeBlockTile(0x00A2,0,0,0,1), makeBlockTile(0x00A5,0,0,0,1), makeBlockTile(0x00A7,0,0,0,1), makeBlockTile(0x00A9,0,0,0,1), // $1E0
        makeBlockTile(0x00AB,0,0,0,1), makeBlockTile(0x00AD,0,0,0,1), makeBlockTile(0x00B9,0,0,0,1), makeBlockTile(0x00BA,0,0,0,1), // $1E4
        makeBlockTile(0x00BF,0,0,0,1), makeBlockTile(0x00C4,0,0,0,1), makeBlockTile(0x00C5,0,0,0,1), makeBlockTile(0x00C7,0,0,0,1), // $1E8
        makeBlockTile(0x00C8,0,0,0,1), makeBlockTile(0x00C9,0,0,0,1), makeBlockTile(0x00CA,0,0,0,1), makeBlockTile(0x00D0,0,0,0,1), // $1EC
        makeBlockTile(0x00D9,0,0,0,1), makeBlockTile(0x00DB,0,0,0,1), makeBlockTile(0x00EB,0,0,0,1), makeBlockTile(0x00EC,0,0,0,1), // $1F0
        makeBlockTile(0x00ED,0,0,0,1), makeBlockTile(0x00EF,0,0,0,1), makeBlockTile(0x00F2,0,0,0,1), makeBlockTile(0x00F3,0,0,0,1), // $1F4
        makeBlockTile(0x00F6,0,0,0,1), makeBlockTile(0x00FA,0,0,0,1), makeBlockTile(0x00FC,0,0,0,1), makeBlockTile(0x00FD,0,0,0,1), // $1F8
        makeBlockTile(0x00FE,0,0,0,1), makeBlockTile(0x0103,0,0,0,1), makeBlockTile(0x0106,0,0,0,1), makeBlockTile(0x0107,0,0,0,1), // $1FC (disasm shows $2FC but that's a comment typo)
        // indices 0x200 to 0x23F
        makeBlockTile(0x010B,0,0,0,1), makeBlockTile(0x010E,0,0,0,1), makeBlockTile(0x010F,0,0,0,1), makeBlockTile(0x0110,0,0,0,1), // $200
        makeBlockTile(0x0112,0,0,0,1), makeBlockTile(0x0117,0,0,0,1), makeBlockTile(0x011C,0,0,0,1), makeBlockTile(0x011D,0,0,0,1), // $204
        makeBlockTile(0x0121,0,0,0,1), makeBlockTile(0x0129,0,0,0,1), makeBlockTile(0x012C,0,0,0,1), makeBlockTile(0x012D,0,0,0,1), // $208
        makeBlockTile(0x0131,0,0,0,1), makeBlockTile(0x0134,0,0,0,1), makeBlockTile(0x0135,0,0,0,1), makeBlockTile(0x013B,0,0,0,1), // $20C
        makeBlockTile(0x013D,0,0,0,1), makeBlockTile(0x013E,0,0,0,1), makeBlockTile(0x013F,0,0,0,1), makeBlockTile(0x0140,0,0,0,1), // $210
        makeBlockTile(0x0141,0,0,0,1), makeBlockTile(0x0145,0,0,0,1), makeBlockTile(0x0146,0,0,0,1), makeBlockTile(0x0149,0,0,0,1), // $214
        makeBlockTile(0x014A,0,0,0,1), makeBlockTile(0x014C,0,0,0,1), makeBlockTile(0x014D,0,0,0,1), makeBlockTile(0x014E,0,0,0,1), // $218
        makeBlockTile(0x014F,0,0,0,1), makeBlockTile(0x0150,0,0,0,1), makeBlockTile(0x0152,0,0,0,1), makeBlockTile(0x0153,0,0,0,1), // $21C
        makeBlockTile(0x0154,0,0,0,1), makeBlockTile(0x0155,0,0,0,1), makeBlockTile(0x0156,0,0,0,1), makeBlockTile(0x0157,0,0,0,1), // $220
        makeBlockTile(0x0158,0,0,0,1), makeBlockTile(0x015A,0,0,0,1), makeBlockTile(0x015B,0,0,0,1), makeBlockTile(0x015C,0,0,0,1), // $224
        makeBlockTile(0x0160,0,0,0,1), makeBlockTile(0x0161,0,0,0,1), makeBlockTile(0x0162,0,0,0,1), makeBlockTile(0x0164,0,0,0,1), // $228
        makeBlockTile(0x0166,0,0,0,1), makeBlockTile(0x0167,0,0,0,1), makeBlockTile(0x0169,0,0,0,1), makeBlockTile(0x016B,0,0,0,1), // $22C
        makeBlockTile(0x016D,0,0,0,1), makeBlockTile(0x016E,0,0,0,1), makeBlockTile(0x016F,0,0,0,1), makeBlockTile(0x0171,0,0,0,1), // $230
        makeBlockTile(0x0172,0,0,0,1), makeBlockTile(0x0173,0,0,0,1), makeBlockTile(0x006E,1,0,0,1), makeBlockTile(0x007D,1,0,0,1), // $234
        makeBlockTile(0x00C3,1,0,0,1), makeBlockTile(0x00DB,1,0,0,1), makeBlockTile(0x00E7,1,0,0,1), makeBlockTile(0x00E8,1,0,0,1), // $238
        makeBlockTile(0x00EB,1,0,0,1), makeBlockTile(0x00ED,1,0,0,1), makeBlockTile(0x00F2,1,0,0,1), makeBlockTile(0x00F6,1,0,0,1), // $23C
        // indices 0x240 to 0x263
        makeBlockTile(0x00FA,1,0,0,1), makeBlockTile(0x00FC,1,0,0,1), makeBlockTile(0x00FE,1,0,0,1), makeBlockTile(0x002D,1,0,0,1), // $240
        makeBlockTile(0x0103,1,0,0,1), makeBlockTile(0x0106,1,0,0,1), makeBlockTile(0x0107,1,0,0,1), makeBlockTile(0x010B,1,0,0,1), // $244
        makeBlockTile(0x0073,1,0,0,1), makeBlockTile(0x009A,1,0,0,1), makeBlockTile(0x0129,1,0,0,1), makeBlockTile(0x012C,1,0,0,1), // $248
        makeBlockTile(0x012D,1,0,0,1), makeBlockTile(0x0111,1,0,0,1), makeBlockTile(0x013C,1,0,0,1), makeBlockTile(0x0120,1,0,0,1), // $24C
        makeBlockTile(0x0146,1,0,0,1), makeBlockTile(0x00A9,1,0,0,1), makeBlockTile(0x009C,1,0,0,1), makeBlockTile(0x0116,1,0,0,1), // $250
        makeBlockTile(0x014F,1,0,0,1), makeBlockTile(0x014C,1,0,0,1), makeBlockTile(0x006F,1,0,0,1), makeBlockTile(0x0158,1,0,0,1), // $254
        makeBlockTile(0x0156,1,0,0,1), makeBlockTile(0x0159,1,0,0,1), makeBlockTile(0x015A,1,0,0,1), makeBlockTile(0x0161,1,0,0,1), // $258
        makeBlockTile(0x007B,1,0,0,1), makeBlockTile(0x0166,1,0,0,1), makeBlockTile(0x011C,1,0,0,1), makeBlockTile(0x0118,1,0,0,1), // $25C
        makeBlockTile(0x00A0,1,0,0,1), makeBlockTile(0x00A3,1,0,0,1), makeBlockTile(0x0167,1,0,0,1), makeBlockTile(0x00A1,1,0,0,1), // $260
    };

    /**
     * Offset to add for 10-bit indices to access the extended lookup table.
     * The 10-bit read path adds this offset to reach Part2 of the table.
     */
    public static final int UNC_LUT_PART2_OFFSET = 0x40;

    /**
     * RLE pattern name + count lookup table.
     * Indexed by values from segment 3 of the track mapping data.
     * Each entry is 2 words: pattern name, then repeat count.
     * Format: { pattern, count }
     */
    public static final int[][] RLE_LUT = {
        // SSPNT_RLELUT (indices 0x00 to 0x3F)
        {makeBlockTile(0x0007,0,0,0,0), 0x0001}, {makeBlockTile(0x0001,0,0,0,0), 0x0001}, // $00
        {makeBlockTile(0x004A,0,0,0,0), 0x0001}, {makeBlockTile(0x0039,0,0,0,0), 0x0003}, // $02
        {makeBlockTile(0x0001,0,0,0,0), 0x0005}, {makeBlockTile(0x0028,0,0,0,0), 0x0007}, // $04
        {makeBlockTile(0x002C,0,0,0,0), 0x0001}, {makeBlockTile(0x0001,0,0,0,0), 0x0002}, // $06
        {makeBlockTile(0x0028,0,0,0,0), 0x0005}, {makeBlockTile(0x0039,0,0,0,0), 0x0001}, // $08
        {makeBlockTile(0x0028,0,0,0,0), 0x0009}, {makeBlockTile(0x0001,0,0,0,0), 0x0004}, // $0A
        {makeBlockTile(0x0028,0,0,0,0), 0x0006}, {makeBlockTile(0x0028,0,0,0,0), 0x0003}, // $0C
        {makeBlockTile(0x004A,0,0,0,0), 0x0002}, {makeBlockTile(0x0001,0,0,0,0), 0x0003}, // $0E
        {makeBlockTile(0x0028,0,0,0,0), 0x0004}, {makeBlockTile(0x0039,0,0,0,0), 0x0002}, // $10
        {makeBlockTile(0x0039,0,0,0,0), 0x0004}, {makeBlockTile(0x0001,0,0,0,0), 0x0006}, // $12
        {makeBlockTile(0x0007,0,0,0,0), 0x0002}, {makeBlockTile(0x002C,0,0,0,0), 0x0002}, // $14
        {makeBlockTile(0x0028,0,0,0,0), 0x0001}, {makeBlockTile(0x001D,0,0,0,0), 0x0001}, // $16
        {makeBlockTile(0x0028,0,0,0,0), 0x0008}, {makeBlockTile(0x0028,0,0,0,0), 0x0002}, // $18
        {makeBlockTile(0x0007,0,0,0,0), 0x0003}, {makeBlockTile(0x0001,0,0,0,0), 0x0007}, // $1A
        {makeBlockTile(0x0028,0,0,0,0), 0x000B}, {makeBlockTile(0x0039,0,0,0,0), 0x0005}, // $1C
        {makeBlockTile(0x001D,0,0,0,0), 0x0003}, {makeBlockTile(0x001D,0,0,0,0), 0x0004}, // $1E
        {makeBlockTile(0x001D,0,0,0,0), 0x0002}, {makeBlockTile(0x001D,0,0,0,0), 0x0005}, // $20
        {makeBlockTile(0x0028,0,0,0,0), 0x000D}, {makeBlockTile(0x000B,0,0,0,0), 0x0001}, // $22
        {makeBlockTile(0x0028,0,0,0,0), 0x000A}, {makeBlockTile(0x0039,0,0,0,0), 0x0006}, // $24
        {makeBlockTile(0x0039,0,0,0,0), 0x0007}, {makeBlockTile(0x002C,0,0,0,0), 0x0003}, // $26
        {makeBlockTile(0x001D,0,0,0,0), 0x0009}, {makeBlockTile(0x004A,0,0,0,0), 0x0003}, // $28
        {makeBlockTile(0x001D,0,0,0,0), 0x0007}, {makeBlockTile(0x0028,0,0,0,0), 0x000F}, // $2A
        {makeBlockTile(0x001D,0,0,0,0), 0x000B}, {makeBlockTile(0x001D,0,0,0,0), 0x0011}, // $2C
        {makeBlockTile(0x001D,0,0,0,0), 0x000D}, {makeBlockTile(0x001D,0,0,0,0), 0x0008}, // $2E
        {makeBlockTile(0x0028,0,0,0,0), 0x0011}, {makeBlockTile(0x001D,0,0,0,0), 0x0006}, // $30
        {makeBlockTile(0x000B,0,0,0,0), 0x0002}, {makeBlockTile(0x001D,0,0,0,0), 0x0015}, // $32
        {makeBlockTile(0x0028,0,0,0,0), 0x000C}, {makeBlockTile(0x001D,0,0,0,0), 0x000A}, // $34
        {makeBlockTile(0x0028,0,0,0,0), 0x000E}, {makeBlockTile(0x0001,0,0,0,0), 0x0008}, // $36
        {makeBlockTile(0x001D,0,0,0,0), 0x000F}, {makeBlockTile(0x0028,0,0,0,0), 0x0010}, // $38
        {makeBlockTile(0x0007,0,0,0,0), 0x0006}, {makeBlockTile(0x001D,0,0,0,0), 0x0013}, // $3A
        {makeBlockTile(0x004A,0,0,0,0), 0x0004}, {makeBlockTile(0x001D,0,0,0,0), 0x0017}, // $3C
        {makeBlockTile(0x0007,0,0,0,0), 0x0004}, {makeBlockTile(0x000B,0,0,0,0), 0x0003}, // $3E
        // SSPNT_RLELUT_Part2 (indices 0x40 to 0x85)
        {makeBlockTile(0x001D,0,0,0,0), 0x001B}, {makeBlockTile(0x004A,0,0,0,0), 0x0006}, // $40
        {makeBlockTile(0x001D,0,0,0,0), 0x001D}, {makeBlockTile(0x004A,0,0,0,0), 0x0005}, // $42
        {makeBlockTile(0x0001,0,0,0,0), 0x0009}, {makeBlockTile(0x0007,0,0,0,0), 0x0005}, // $44
        {makeBlockTile(0x001D,0,0,0,0), 0x001E}, {makeBlockTile(0x001D,0,0,0,0), 0x0019}, // $46
        {makeBlockTile(0x0001,0,0,0,0), 0x0011}, {makeBlockTile(0x001D,0,0,0,0), 0x000C}, // $48
        {makeBlockTile(0x001D,0,0,0,0), 0x007F}, {makeBlockTile(0x002C,0,0,0,0), 0x0004}, // $4A
        {makeBlockTile(0x001D,0,0,0,0), 0x000E}, {makeBlockTile(0x001D,0,0,0,0), 0x001C}, // $4C
        {makeBlockTile(0x004A,0,0,0,0), 0x000A}, {makeBlockTile(0x001D,0,0,0,0), 0x001A}, // $4E
        {makeBlockTile(0x004A,0,0,0,0), 0x0007}, {makeBlockTile(0x001D,0,0,0,0), 0x0018}, // $50
        {makeBlockTile(0x000B,0,0,0,0), 0x0004}, {makeBlockTile(0x001D,0,0,0,0), 0x0012}, // $52
        {makeBlockTile(0x001D,0,0,0,0), 0x0010}, {makeBlockTile(0x0001,0,0,0,0), 0x000F}, // $54
        {makeBlockTile(0x000B,0,0,0,0), 0x0005}, {makeBlockTile(0x0001,0,0,0,0), 0x000D}, // $56
        {makeBlockTile(0x0001,0,0,0,0), 0x0013}, {makeBlockTile(0x004A,0,0,0,0), 0x0009}, // $58
        {makeBlockTile(0x004A,0,0,0,0), 0x000B}, {makeBlockTile(0x004A,0,0,0,0), 0x000C}, // $5A
        {makeBlockTile(0x002C,0,0,0,0), 0x0005}, {makeBlockTile(0x001D,0,0,0,0), 0x0014}, // $5C
        {makeBlockTile(0x000B,0,0,0,0), 0x0007}, {makeBlockTile(0x001D,0,0,0,0), 0x0016}, // $5E
        {makeBlockTile(0x0001,0,0,0,0), 0x000C}, {makeBlockTile(0x0001,0,0,0,0), 0x000E}, // $60
        {makeBlockTile(0x004A,0,0,0,0), 0x0008}, {makeBlockTile(0x001D,0,0,0,0), 0x005F}, // $62
        {makeBlockTile(0x0001,0,0,0,0), 0x000A}, {makeBlockTile(0x000B,0,0,0,0), 0x0006}, // $64
        {makeBlockTile(0x000B,0,0,0,0), 0x0008}, {makeBlockTile(0x000B,0,0,0,0), 0x000A}, // $66
        {makeBlockTile(0x0039,0,0,0,0), 0x0008}, {makeBlockTile(0x000B,0,0,0,0), 0x0009}, // $68
        {makeBlockTile(0x002C,0,0,0,0), 0x0006}, {makeBlockTile(0x0001,0,0,0,0), 0x0010}, // $6A
        {makeBlockTile(0x000B,0,0,0,0), 0x000C}, {makeBlockTile(0x0001,0,0,0,0), 0x000B}, // $6C
        {makeBlockTile(0x0001,0,0,0,0), 0x0012}, {makeBlockTile(0x0007,0,0,0,0), 0x0007}, // $6E
        {makeBlockTile(0x001D,0,0,0,0), 0x001F}, {makeBlockTile(0x0028,0,0,0,0), 0x0012}, // $70
        {makeBlockTile(0x000B,0,0,0,0), 0x000B}, {makeBlockTile(0x002C,0,0,0,0), 0x0007}, // $72
        {makeBlockTile(0x002C,0,0,0,0), 0x000B}, {makeBlockTile(0x001D,0,0,0,0), 0x0023}, // $74
        {makeBlockTile(0x0001,0,0,0,0), 0x0015}, {makeBlockTile(0x002C,0,0,0,0), 0x0008}, // $76
        {makeBlockTile(0x001D,0,0,0,0), 0x002E}, {makeBlockTile(0x001D,0,0,0,0), 0x003F}, // $78
        {makeBlockTile(0x0001,0,0,0,0), 0x0014}, {makeBlockTile(0x000B,0,0,0,0), 0x000D}, // $7A
        {makeBlockTile(0x002C,0,0,0,0), 0x0009}, {makeBlockTile(0x002C,0,0,0,0), 0x000A}, // $7C
        {makeBlockTile(0x001D,0,0,0,0), 0x0025}, {makeBlockTile(0x001D,0,0,0,0), 0x0055}, // $7E
        {makeBlockTile(0x001D,0,0,0,0), 0x0071}, {makeBlockTile(0x001D,0,0,0,0), 0x007C}, // $80
        {makeBlockTile(0x004A,0,0,0,0), 0x000D}, {makeBlockTile(0x002C,0,0,0,0), 0x000C}, // $82
        {makeBlockTile(0x002C,0,0,0,0), 0x000F}, {makeBlockTile(0x002C,0,0,0,0), 0x0010}, // $84
    };

    /**
     * Offset to add for 7-bit RLE indices to access the extended lookup table.
     * The 7-bit read path adds this offset to reach Part2 of the RLE table.
     */
    public static final int RLE_LUT_PART2_OFFSET = 0x40;

    /**
     * Gets a pattern name from the uncompressed lookup table.
     *
     * @param index The index (0-0x263)
     * @param extended True if using 10-bit extended index
     * @return The pattern name word
     */
    public static int getUncPattern(int index, boolean extended) {
        int actualIndex = extended ? index + UNC_LUT_PART2_OFFSET : index;
        if (actualIndex >= 0 && actualIndex < UNC_LUT.length) {
            return UNC_LUT[actualIndex];
        }
        return 0;
    }

    /**
     * Gets an RLE entry from the lookup table.
     *
     * @param index The index (0-0x85)
     * @return Array of [pattern, count]
     */
    public static int[] getRleEntry(int index) {
        if (index >= 0 && index < RLE_LUT.length) {
            return RLE_LUT[index];
        }
        return new int[] {0, 0};
    }
}
