package com.openggf.game.sonic2.audio;

public final class Sonic2SmpsConstants {
    // Note: the previous {@code MUSIC_FLAGS_ADDR} / {@code MUSIC_PTR_TABLE_ADDR}
    // constants pointed inside the Saxman-compressed Z80 driver blob in 68K
    // ROM, where {@code zMasterPlaylist} only exists in compressed form. They
    // were removed when {@code Sonic2SmpsLoader#resolveMusicOffsetFromRom}
    // (which read those bytes as if they were uncompressed) was retired in
    // favour of the hardcoded REV01 musicMap. Reintroduce ROM-driven music
    // resolution only after the Z80 driver is decompressed first and the
    // engine's {@code Sonic2Music} ID scheme is reconciled with the driver's
    // {@code zMasterPlaylist} entry order.

    public static final int Z80_COMPRESSED_LOAD_ADDR = 0x1380;
    public static final int Z80_UNCOMPRESSED_LOAD_ADDR = 0x1C00;
    public static final int Z80_BANK_BASE = 0x8000;
    public static final int Z80_BANK_MASK = 0x7FFF;

    public static final int SFX_POINTER_TABLE_ADDR = 0x0FEE91;
    public static final int SFX_BANK_BASE = 0x0F8000;
    public static final int SFX_BANK_SIZE = 0x8000;
    public static final int SFX_VOICE_TABLE_PADDING = 0x100;

    public static final int PSG_ENVELOPE_TABLE_ADDR = 0x0F2E5C;
    public static final int PSG_ENVELOPE_BANK_BASE = 0x0F0000;

    public static final int PCM_BANK_START = 0x0E0000;
    public static final int PCM_SAMPLE_PTR_TABLE_ADDR = 0x0ECF7C;
    public static final int PCM_SAMPLE_MAP_ADDR = 0x0ECF9C;
    public static final int PCM_SAMPLE_ID_BASE = 0x81;
    public static final int PCM_SAMPLE_COUNT = 7;
    public static final int PCM_MAPPING_COUNT = 17;

    /** Sonic 2 SEGA screen command (SndID_SegaSound / CmdPtr_SegaSound). */
    public static final int CMD_SEGA = 0xFA;

    /** Raw SEGA PCM sample aligned to the end of the 0x0E0000 PCM bank. */
    public static final int SEGA_SOUND_ADDR = 0x0F1E8C;

    /** Size_of_SEGA_sound from {@code docs/s2disasm/s2.constants.asm}. */
    public static final int SEGA_SOUND_SIZE = 0x6174;

    /** Source sample rate used by the generated Sonic 2 SEGA PCM include. */
    public static final int SEGA_SOUND_SAMPLE_RATE = 16_500;

    /**
     * SFX priority table from Sonic 2 Z80 driver (zSFXPriority).
     * Index = SFX ID - Sonic2Sfx.ID_BASE (0xA0).
     * Higher values = higher priority. 0x80 = special (jump sound, exits immediately).
     */
    public static final int[] SFX_PRIORITY_TABLE = {
            0x80, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x68, 0x70, 0x70, 0x70, 0x60, 0x70,
            0x70, 0x60, 0x70, 0x60, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x7F,
            0x6F, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x6F, 0x70, 0x70,
            0x70, 0x60, 0x60, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x60, 0x62, 0x60, 0x60, 0x60, 0x70,
            0x70, 0x70, 0x70, 0x70, 0x60, 0x60, 0x60, 0x6F, 0x70, 0x70, 0x6F, 0x6F, 0x70, 0x71, 0x70, 0x70,
            0x6F
    };

    /**
     * Get priority for an SFX ID. Returns 0x70 (default) if out of range.
     */
    public static int getSfxPriority(int sfxId) {
        int index = sfxId - Sonic2Sfx.ID_BASE;
        if (index >= 0 && index < SFX_PRIORITY_TABLE.length) {
            return SFX_PRIORITY_TABLE[index];
        }
        return 0x70; // Default priority
    }

    // -----------------------------------------------------------------------
    // System commands (sound queue IDs)
    // -----------------------------------------------------------------------

    /** Speed up current music (speed shoes on). */
    public static final int CMD_SPEED_UP = 0xFB;

    /** Slow down current music (speed shoes off). */
    public static final int CMD_SLOW_DOWN = 0xFC;

    // -----------------------------------------------------------------------
    // Uncompressed-track ROM addresses & explicit sizes
    //
    // Sonic 2's "uncompressed" tracks (1-Up, Game Over, Got an Emerald,
    // Credits) are flagged with bit 5 (0x20) in their music ID. They are
    // stored back-to-back at the addresses below; track size is computed by
    // distance to the next track's start. The Credits track is a long
    // medley with no following track entry, so it gets a fixed buffer.
    //
    // The tuples here are referenced by:
    //   - Sonic2SmpsLoader.musicMap (the empirical fallback)
    //   - Sonic2SmpsLoader.calculateUncompressedSize (size lookup)
    // -----------------------------------------------------------------------

    /** ROM offset of the 1-Up jingle (uncompressed). */
    public static final int UNCOMPRESSED_EXTRA_LIFE_ADDR = 0x0FD48D;

    /** Size of the 1-Up jingle (distance to Game Over). */
    public static final int UNCOMPRESSED_EXTRA_LIFE_SIZE = 0xED;

    /** ROM offset of the Game Over jingle (uncompressed). */
    public static final int UNCOMPRESSED_GAME_OVER_ADDR = 0x0FD57A;

    /** Size of the Game Over jingle (distance to Got Emerald). */
    public static final int UNCOMPRESSED_GAME_OVER_SIZE = 0x14F;

    /** ROM offset of the Got-Emerald jingle (uncompressed). */
    public static final int UNCOMPRESSED_GOT_EMERALD_ADDR = 0x0FD6C9;

    /** Size of the Got-Emerald jingle (distance to Credits). */
    public static final int UNCOMPRESSED_GOT_EMERALD_SIZE = 0xCE;

    /** ROM offset of the Credits medley (uncompressed). */
    public static final int UNCOMPRESSED_CREDITS_ADDR = 0x0FD797;

    /**
     * Size of the Credits medley. Conservative upper bound — the medley has
     * no following track entry to derive a tighter size from.
     */
    public static final int UNCOMPRESSED_CREDITS_SIZE = 0x2000;

    private Sonic2SmpsConstants() {
    }
}
