package com.openggf.tools.audio.parity.s3k;

import java.util.List;

/**
 * Versioned constants for the S3K sound-driver oracle interchange.
 *
 * <p>Two stream versions exist. Both carry the 68k request mailboxes, an
 * ordered CPU-tagged YM/PSG write stream, and a snapshot of driver RAM
 * {@code 1C00h..1FA0h} ({@code zDataStart..zTracksSaveEnd}). The driver
 * projection compares only writes tagged to the Z80; 68k host writes remain
 * authenticated fixture data.
 *
 * <p><b>v1</b> ({@code tools/audio/run_s3k_audio_oracle_reference.sh}) emits
 * one row per emulated frame and samples the RAM with an out-of-band read
 * after the frame advances. It declares {@code sampling: "post_invocation"},
 * but that only holds when the Z80 happens to be idle at the frame boundary:
 * where the driver's work overruns the frame the row holds a strict prefix of
 * {@code zUpdateMusic}'s track-iteration order, truncated at a varying point.
 * v1 is retained for provenance and is not the comparison source.
 *
 * <p><b>v2</b> ({@code tools/audio/run_s3k_audio_oracle_reference_v2.sh})
 * emits one row per <em>completed {@code zVInt} service</em>. The RAM is
 * captured by the observer core as a completion snapshot at the {@code ret}
 * that ends {@code zVInt} (Z80 PC {@code 0084h}; skdisasm
 * {@code Sound/Z80 Sound Driver.asm}:520, pinned by the {@code ;loc_85}
 * comment at :522), so it can never be mid-invocation. A row's {@code frame}
 * is the movie frame the service completed in; a frame that the driver
 * overruns contributes no row, so tick ordinals and movie frames diverge.
 * Writes are partitioned by the same completion boundary, so every write
 * still appears exactly once and in stream order.
 */
public final class S3kAudioParitySchema {
    /** Retired per-frame stream; see the class javadoc for why it is not compared. */
    public static final String VERSION_V1 = "openggf.s3k_audio_oracle_reference.v1";
    /** Live per-service stream sampled at the {@code zVInt} return. */
    public static final String VERSION_V2 = "openggf.s3k_audio_oracle_reference.v2";
    public static final String S3K_LOCKED_ON_SHA1 = "cfbf98c36c776677290a872547ac47c53d2761d6";
    public static final String S3K_LOCKED_ON_CRC32 = "63522553";

    /** Source-CPU tags emitted by the pinned GPGX audio observer. */
    public static final int SOURCE_CPU_Z80 = 1;
    public static final int SOURCE_CPU_M68K = 2;

    /** Z80 address of {@code zDataStart}; the RAM window starts here. */
    public static final int RAM_WINDOW_START = 0x1C00;
    /** Z80 address of {@code zTracksSaveEnd} (= {@code z80_stack_end}); exclusive end. */
    public static final int RAM_WINDOW_END = 0x1FA0;

    /** {@code zTracksStart} (D:176) and {@code zTrack.len} (D:21-96). */
    public static final int MUSIC_TRACKS_START = 0x1C40;
    public static final int SFX_TRACKS_START = 0x1DF0;
    public static final int TRACK_SIZE = 0x30;

    /**
     * The sixteen live track slots in driver order: nine music slots
     * (FM6/DAC first, D:176-186) then seven SFX slots (D:190-206).
     */
    public static final List<String> ROLES = List.of(
            "MUS_DAC", "MUS_FM1", "MUS_FM2", "MUS_FM3", "MUS_FM4", "MUS_FM5",
            "MUS_PSG1", "MUS_PSG2", "MUS_PSG3",
            "SFX_FM3", "SFX_FM4", "SFX_FM5", "SFX_FM6",
            "SFX_PSG1", "SFX_PSG2", "SFX_PSG3");

    /** zTrack base address for each role, same order as {@link #ROLES}. */
    public static final int[] ROLE_TRACK_BASE = {
            0x1C40, 0x1C70, 0x1CA0, 0x1CD0, 0x1D00, 0x1D30,
            0x1D60, 0x1D90, 0x1DC0,
            0x1DF0, 0x1E20, 0x1E50, 0x1E80,
            0x1EB0, 0x1EE0, 0x1F10};

    public static final int MUSIC_ROLE_COUNT = 9;
    public static final int SFX_ROLE_COUNT = 7;

    /** Request-id dispatch boundaries, zPlaySoundByIndex (D:1641-1665). */
    public static final int MUSIC_FIRST = 0x01;
    public static final int MUSIC_LAST = 0x32;
    public static final int SFX_FIRST = 0x33;
    public static final int SFX_LAST = 0xDB;
    public static final int CREDITS_K = 0xDC;
    public static final int CMD_FADE_OUT = 0xE1;
    public static final int CMD_STOP = 0xE2;
    public static final int CMD_MUTE_PSG = 0xE3;
    public static final int CMD_STOP_SFX = 0xE4;
    public static final int CMD_FADE_OUT2 = 0xE5;
    public static final int CMD_SEGA = 0xFF;

    private S3kAudioParitySchema() {
    }
}
