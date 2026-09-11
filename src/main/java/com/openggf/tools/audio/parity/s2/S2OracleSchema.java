package com.openggf.tools.audio.parity.s2;

/**
 * Versioned constants for the S2 driver-oracle fixture: a windowed raw capture
 * (full Z80 RAM image plus attributed YM/PSG bus events per driver invocation)
 * of the pinned complete-emeralds movie, recorded by the TraceChaser headless
 * harness with the patch-0001 GPGX audio observer.
 */
public final class S2OracleSchema {
    public static final String PAYLOAD_SCHEMA = "openggf.s2-oracle-audio-raw.v1";
    public static final String FIXTURE_RESOURCE =
            "/audio/parity/s2/s2-ehz-reload-w10150-10900.raw.jsonl.gz";
    public static final String S2_REV01_SHA1 = "8bca5dcef1af3e00098666fd892dc1c2a76333f9";
    public static final String BK2_SHA256 =
            "e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5";
    public static final String SERVICE_MANIFEST_SHA256 =
            "ef8f8103c38d70e41cb09cb29751f56815a0401709dc509071aa514d614813a0";
    public static final String PAYLOAD_GZ_SHA256 =
            "a734f4d5db9ef76af870d75118fb47518368451ad6b92f46e7c2fae88b7b3c2a";
    public static final String PAYLOAD_RAW_SHA256 =
            "7bb1df0e5efd9dba8e3cb7c6691859c2356f1ddadfded3c230ddc94b3ea6b113";

    /** First published movie row (a publication-epoch boundary, not a driver reset). */
    public static final int FIRST_ROW = 10_150;
    /** Exclusive end of the published window. */
    public static final int EXCLUSIVE_END = 10_900;
    /**
     * The comparison anchor: the row whose {@code zVInt} consumed the EHZ music
     * request after the first special stage ({@code zCurSong} 9Ah to 82h), so
     * {@code zInitMusicPlayback} + {@code zBGMLoad} and the first track update
     * all happened inside this row (s2.sounddriver.asm:1667-2006).
     */
    public static final int ANCHOR_ROW = 10_195;
    /** The ROM driver request id the anchor row loads (EHZ music). */
    public static final int ANCHOR_ROM_MUSIC_ID = 0x82;
    /** Movie row whose zVInt consumed the speed-up command (FBh). */
    public static final int SPEED_UP_ROW = 10_791;

    /** Z80 RAM image bounds captured per row. */
    public static final int STATE_BYTES = 0x2000;

    private S2OracleSchema() {
    }
}
