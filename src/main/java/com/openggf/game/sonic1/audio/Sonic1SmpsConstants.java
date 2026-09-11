package com.openggf.game.sonic1.audio;

/**
 * SMPS constants for the Sonic 1 68000 sound driver.
 * Addresses verified against REV01 ROM binary via {@code verify-audio} tool.
 *
 * <p>Unlike Sonic 2 which uses a Z80-based driver, Sonic 1 runs the SMPS driver
 * on the 68000 CPU with the Z80 only handling DAC sample playback.
 */
public final class Sonic1SmpsConstants {

    // -----------------------------------------------------------------------
    // ROM pointer table addresses (verified via verify-audio tool against REV01 ROM)
    // Go_ pointer table at 0x71990 cross-validated all addresses.
    // -----------------------------------------------------------------------

    /** Music pointer table: 19 entries, 4 bytes each (32-bit BE pointers). */
    public static final int MUSIC_PTR_TABLE_ADDR = 0x071A9C;

    /** SFX pointer table: 48 entries, 4 bytes each (32-bit BE pointers). */
    public static final int SFX_PTR_TABLE_ADDR = 0x078B44;

    /** Special SFX pointer table: 1 entry (sfx_Waterfall / 0xD0). */
    public static final int SPECIAL_SFX_PTR_TABLE_ADDR = 0x078C04;

    /** PSG envelope pointer table: 9 entries, 4 bytes each. */
    public static final int PSG_ENV_PTR_TABLE_ADDR = 0x0719A8;

    /**
     * Speed-up tempo table: 8 bytes, one per zone music ID (0x81-0x88).
     * When speed shoes are active, these tempos replace the track's main tempo.
     */
    public static final int SPEED_UP_INDEX_ADDR = 0x071A94;

    /** Raw PCM data for the "SEGA" voice sample. */
    public static final int SEGA_SOUND_ADDR = 0x079688;

    /** Size of the "SEGA" PCM sample. */
    public static final int SEGA_SOUND_SIZE = 0x6978;

    /** Source sample rate of the "SEGA" PCM sample. */
    public static final int SEGA_SOUND_SAMPLE_RATE = 16_500;

    /**
     * Sound priority table (SoundPriorities label in s1disasm).
     * Located after MusicIndex in ROM, confirmed via Go_ pointer table.
     */
    public static final int SOUND_PRIORITIES_ADDR = 0x071AE8;

    // -----------------------------------------------------------------------
    // Table sizing constants
    // -----------------------------------------------------------------------

    /** Total number of music tracks (driver table size). */
    public static final int MUSIC_COUNT = 19;

    /** Number of standard SFX entries in the SFX pointer table (0xA0-0xCF). */
    public static final int SFX_COUNT = 48;

    /** First special SFX ID (sfx_Waterfall). */
    public static final int SPECIAL_SFX_ID_BASE = 0xD0;

    /** Number of special SFX. */
    public static final int SPECIAL_SFX_COUNT = 1;

    // -----------------------------------------------------------------------
    // System commands (sound queue IDs, not SMPS coord flags)
    // -----------------------------------------------------------------------

    /** Fade out current music. */
    public static final int CMD_FADE_OUT = 0xE0;

    /** Play "SEGA" PCM sample. */
    public static final int CMD_SEGA = 0xE1;

    /** Speed up current music (speed shoes on). */
    public static final int CMD_SPEED_UP = 0xE2;

    /** Slow down current music (speed shoes off). */
    public static final int CMD_SLOW_DOWN = 0xE3;

    /** Stop all sound and music. */
    public static final int CMD_STOP_ALL = 0xE4;

    // -----------------------------------------------------------------------
    // DAC constants
    // -----------------------------------------------------------------------

    /** DAC driver Z80 code, Kosinski compressed in ROM. */
    public static final int DAC_DRIVER_ADDR = 0x072E7C;

    /** Offset within Z80 RAM where the DAC sample pointer table lives. */
    public static final int DAC_PTR_TABLE_Z80_OFFSET = 0x00D6;

    /** Number of DAC samples: Kick, Snare, Timpani. */
    public static final int DAC_SAMPLE_COUNT = 3;

    /** Base DAC sample note ID (0x81 = Kick). */
    public static final int DAC_SAMPLE_ID_BASE = 0x81;

    /**
     * Timpani pitch modifier table in ROM (4 bytes for variants 0x88-0x8B).
     * Each byte is a djnz loop counter derived from {@code timpaniLoopCounter(scale)}.
     * Located at the {@code DAC_sample_rate} label in s1.sounddriver.asm.
     */
    public static final int DAC_SAMPLE_RATE_TABLE_ADDR = 0x071CC4;

    // -----------------------------------------------------------------------
    // Sound priority table
    // -----------------------------------------------------------------------

    /**
     * Sound priority table from s1disasm SoundPriorities label.
     * Covers the unified range 0x81-0xE4 (100 entries).
     *
     * <p>Index = soundId - 0x81.
     *
     * <p>Priority semantics:
     * <ul>
     *   <li>Higher value = higher priority</li>
     *   <li>Bit 7 set (0x80+) = won't store priority (allows any subsequent sound to override)</li>
     * </ul>
     *
     * <p>Exact values from s1disasm/s1.sounddriver.asm:
     * <pre>
     * ; $81-$8F (15 entries): music - all 0x90
     * ; $90-$9F (16 entries): music/unused - all 0x90
     * ; $A0-$AF (16 entries): SFX
     * ; $B0-$BF (16 entries): SFX
     * ; $C0-$CF (16 entries): SFX
     * ; $D0-$DF (16 entries): special SFX - all 0x80
     * ; $E0-$E4 (5 entries): commands - all 0x90
     * </pre>
     */
    private static final int[] SOUND_PRIORITIES = {
            // Music 0x81-0x8F (15 entries) - all priority 0x90
            0x90, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90,
            0x90, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90,
            // 0x90-0x9F (16 entries) - music/unused range, all 0x90
            0x90, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90,
            0x90, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90,
            // SFX 0xA0-0xAF (16 entries)
            0x80, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70,
            0x70, 0x70, 0x68, 0x70, 0x70, 0x70, 0x60, 0x70,
            // SFX 0xB0-0xBF (16 entries)
            0x70, 0x60, 0x70, 0x60, 0x70, 0x70, 0x70, 0x70,
            0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x7F,
            // SFX 0xC0-0xCF (16 entries)
            0x60, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70,
            0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70,
            // Special SFX 0xD0-0xDF (16 entries) - bit 7 set = non-storing
            0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
            0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
            // Commands 0xE0-0xE4 (5 entries)
            0x90, 0x90, 0x90, 0x90, 0x90
    };

    /**
     * Get priority for any sound ID in the 0x81-0xE4 range.
     *
     * @param soundId the sound/music/command ID
     * @return priority value, or 0x70 (default SFX priority) if out of range
     */
    public static int getSfxPriority(int soundId) {
        int index = soundId - Sonic1Music.ID_BASE; // 0x81 base
        if (index >= 0 && index < SOUND_PRIORITIES.length) {
            return SOUND_PRIORITIES[index];
        }
        return 0x70; // default
    }

    private Sonic1SmpsConstants() {
    }
}
