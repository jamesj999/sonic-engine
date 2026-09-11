package com.openggf.game.sonic3k.audio;

/**
 * SMPS constants for the Sonic 3 &amp; Knuckles Z80 sound driver.
 * Addresses from {@code docs/SMPS-rips/Sonic & Knuckles/Pointers.txt}.
 *
 * <p>S3K uses a modified SMPS Z80 Type 2 driver (per DefDrv.txt), similar to
 * Sonic 2 but with differences in base note, voice operator order, and
 * bank-switched music/DAC data.
 */
public final class Sonic3kSmpsConstants {

    // -----------------------------------------------------------------------
    // ROM addresses for Kosinski-compressed Z80 driver data
    // -----------------------------------------------------------------------

    /** Z80 sound driver, Kosinski compressed at this ROM offset. */
    public static final int Z80_DRIVER_ADDR = 0x0F6960;

    /** Additional Z80 data (goes to Z80 RAM 0x1300), Kosinski compressed. */
    public static final int Z80_ADDITIONAL_DATA_ADDR = 0x0F7760;

    // -----------------------------------------------------------------------
    // Z80 RAM addresses (from Pointers.txt, S&K final)
    // -----------------------------------------------------------------------

    /** General pointer list in Z80 RAM. */
    public static final int Z80_GENERAL_PTR_LIST = 0x1300;

    /** Global instrument (voice) table in Z80 RAM. */
    public static final int Z80_GLOBAL_INSTRUMENT_TABLE = 0x17D8;

    /** Music bank list in Z80 RAM (maps music IDs to ROM banks). */
    public static final int Z80_MUSIC_BANK_LIST = 0x0B65;

    /** Music pointer list in Z80 RAM (Z80 offsets within banks). */
    public static final int Z80_MUSIC_PTR_LIST = 0x1618;

    /** SFX pointer list in Z80 RAM (points to bank 0F8000). */
    public static final int Z80_SFX_PTR_LIST = 0x167E;

    /** Modulation envelope pointer list in Z80 RAM. */
    public static final int Z80_MOD_PTR_LIST = 0x130E;

    /** PSG envelope pointer list in Z80 RAM. */
    public static final int Z80_PSG_PTR_LIST = 0x1387;

    /** DAC bank list in Z80 RAM (starts with entry for note 0x80). */
    public static final int Z80_DAC_BANK_LIST = 0x00D6;

    /** DAC drum pointer list (Z80 bank offset, starts with entry for note 0x81). */
    public static final int Z80_DAC_DRUM_PTR_LIST = 0x8000;

    // -----------------------------------------------------------------------
    // ROM addresses for audio data
    // -----------------------------------------------------------------------

    /** SEGA PCM sound ROM address. */
    public static final int SEGA_SOUND_ADDR = 0x0F8000;

    /** Size of the S3K SEGA PCM sample. */
    public static final int SEGA_SOUND_SIZE = 0x5E2F;

    /** Source sample rate of the S3K SEGA PCM sample. */
    public static final int SEGA_SOUND_SAMPLE_RATE = 0x3862;

    /** SFX bank base ROM address (SFX pointer list points here). */
    public static final int SFX_BANK_BASE = 0x0F8000;

    // -----------------------------------------------------------------------
    // Z80 bank constants (same as S2)
    // -----------------------------------------------------------------------

    /** Z80 bank base address. */
    public static final int Z80_BANK_BASE = 0x8000;

    /** Mask to convert Z80 address to bank-relative offset. */
    public static final int Z80_BANK_MASK = 0x7FFF;

    // -----------------------------------------------------------------------
    // Sound RAM and speed shoes
    // -----------------------------------------------------------------------

    /** Sound RAM start in Z80 address space. */
    public static final int Z80_SOUND_RAM = 0x1C00;

    /**
     * Speed shoes register in Z80 RAM.
     * Writing a non-zero value here speeds up music by updating it multiple
     * times per frame. Value of 0x08 = 125% speed (standard speed shoes).
     */
    public static final int Z80_SPEED_SHOES_REG = 0x1C08;

    // -----------------------------------------------------------------------
    // DPCM delta table (from Pointers.txt, Z80 driver offset 0x1116 in S&K)
    // -----------------------------------------------------------------------

    /**
     * DPCM delta table for DAC sample decompression.
     * Same table used across all S3K driver variants.
     */
    public static final int[] DPCM_DELTA_TABLE = {
            0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40,
            0x80, 0xFF, 0xFE, 0xFC, 0xF8, 0xF0, 0xE0, 0xC0
    };

    // -----------------------------------------------------------------------
    // DAC note range
    // -----------------------------------------------------------------------

    /** First DAC note ID. */
    public static final int DAC_NOTE_BASE = 0x81;

    /** Last DAC note ID in vanilla S&K (sounds 0x9C+ point to locked-on S3). */
    public static final int DAC_NOTE_MAX = 0xDF;

    // -----------------------------------------------------------------------
    // S3 standalone driver (uncompressed in combined S3&K ROM)
    // -----------------------------------------------------------------------

    /** S3 Z80 driver ROM address (uncompressed, raw data). */
    public static final int S3_Z80_DRIVER_ADDR = 0x0E6000;

    /** S3 music bank list Z80 RAM offset (1-byte entries). */
    public static final int S3_Z80_MUSIC_BANK_LIST = 0x0B48;

    /** S3 music pointer list Z80 RAM offset (2-byte LE entries). */
    public static final int S3_Z80_MUSIC_PTR_LIST = 0x161A;

    /** Number of music entries in the S3 driver (IDs 0x01-0x32). */
    public static final int S3_Z80_MUSIC_COUNT = 50;

    /**
     * Offset of the S3 ROM data within the combined S3&K ROM file.
     * The combined ROM concatenates S&K (first 2MB) and S3 (second 2MB).
     * In the 68K address space, the locked-on S3 cart is at 0x200000.
     */
    public static final int S3_ROM_OFFSET_IN_COMBINED = 0x200000;

    // -----------------------------------------------------------------------
    // System commands (sound queue IDs)
    // -----------------------------------------------------------------------

    /** Out-of-table stop, routed by zPlaySoundByIndex to zStopAllSound. */
    public static final int CMD_STOP = 0xE0;

    /** Fade out current music. */
    public static final int CMD_FADE_OUT = 0xE1;

    /** Stop all sound and music through zStopAllSound. */
    public static final int CMD_STOP_ALL = 0xE2;

    /** Transiently silence all four PSG channels. */
    public static final int CMD_PSG_SILENCE = 0xE3;

    /** Stop the seven active SFX slots while preserving music state. */
    public static final int CMD_STOP_SFX = 0xE4;

    /** Alternate fade command routed to the same zFadeOutMusic routine. */
    public static final int CMD_FADE_OUT_ALT = 0xE5;

    /** Stop the active SEGA PCM sample. */
    public static final int CMD_STOP_SEGA = 0xFE;

    /** Play "SEGA" PCM sample. */
    public static final int CMD_SEGA = 0xFF;

    /** Direct zTempoSpeedup value written when speed shoes are collected. */
    public static final int SPEED_MULTIPLIER_ON = 8;

    /** Engine semantic normal-speed value for the source's direct zero. */
    public static final int SPEED_MULTIPLIER_OFF = 1;

    private Sonic3kSmpsConstants() {
    }
}
