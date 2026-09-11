package com.openggf.tools.audio.parity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned constants for the S1 music-driver parity interchange. */
public final class AudioParitySchema {
    public static final String VERSION = "openggf.s1_audio_parity_reference.v1";
    public static final String REFERENCE_CAPTURE = "s1_ghz_music_driver_reference";
    public static final String OPENGGF_CAPTURE = "s1_ghz_music_driver_openggf";
    /** Sound-test SFX capture pair: GHZ music plus eight normal SFX, no recurrence proof. */
    public static final String SFX_REFERENCE_CAPTURE = "s1_soundtest_sfx_driver_reference";
    public static final String SFX_OPENGGF_CAPTURE = "s1_soundtest_sfx_driver_openggf";
    public static final String S1_REV01_SHA1 = "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b";
    public static final String S1_REV01_CRC32 = "afe05eee";
    public static final String BK2_SHA256 = "622ff642d0b0835a4f77bee568f2413f288ead3306a8bc2a93e8d8f77f24ca9c";
    public static final String BK2_CORE = "Genplus-gx";
    public static final String BK2_EMULATOR = "Version 2.11";
    public static final String BK2_GAME = "Sonic The Hedgehog (W) (REV01) [!]";
    public static final int BK2_INPUT_ROWS = 989;
    public static final String BK2_OPAQUE_HASH = "09DADB5071EB35050067A32462E39C5F";
    public static final String SFX_BK2_SHA256 =
            "3da775e4fdd3770e9687e178b6a11922873d1021a2013565ebe842451a7a33a2";
    public static final int SFX_BK2_INPUT_ROWS = 2791;
    public static final String SFX_BK2_OPAQUE_HASH = BK2_OPAQUE_HASH;
    /** GHZ-epoch dormant UpdateMusic invocations pinned by the music BK2 transport. */
    public static final int BK2_LAUNCH_INVOCATIONS = 514;
    /** The SFX movie reuses the music movie's full 989-row launch prefix, so the same count. */
    public static final int SFX_BK2_LAUNCH_INVOCATIONS = 514;
    /** Gameplay capture pair: the pinned complete-run movie through early GHZ1 play. */
    public static final String GAMEPLAY_REFERENCE_CAPTURE = "s1_gameplay_ghz1_driver_reference";
    public static final String GAMEPLAY_OPENGGF_CAPTURE = "s1_gameplay_ghz1_driver_openggf";
    public static final String GAMEPLAY_BK2_SHA256 =
            "f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b";
    public static final int GAMEPLAY_BK2_INPUT_ROWS = 225_101;
    public static final String GAMEPLAY_BK2_OPAQUE_HASH = BK2_OPAQUE_HASH;
    /**
     * Dormant UpdateMusic invocations before the real GHZ1 BGM dispatch in the
     * complete-run movie's title/SEGA/menu prefix, distinct from the sound-test
     * movies' shared 514 because this is real title-screen/menu play rather than
     * an immediate sound-test selection. Pinned from the authenticated capture;
     * see docs/status/audio-frontier-log.md for the measurement.
     */
    public static final int GAMEPLAY_BK2_LAUNCH_INVOCATIONS = 341;
    /**
     * One pinned gameplay BK2, by the SHA-256 of the movie archive itself.
     *
     * @param inputRows the movie's input-row count
     * @param launchInvocations dormant UpdateMusic invocations before this
     *     movie's own GHZ1 BGM dispatch, which is a property of its title and
     *     menu play, not of the driver
     */
    public record GameplayMovie(int inputRows, int launchInvocations) {
    }

    /** SHA-256 of the second pinned gameplay movie, a different complete run of the same ROM. */
    public static final String GAMEPLAY2_BK2_SHA256 =
            "f744c814d8e00d6c367f7fe83bb663cab123b5a4ed385a320d71b74d63146bde";
    public static final int GAMEPLAY2_BK2_INPUT_ROWS = 195_493;
    /**
     * This movie's own title/SEGA/menu prefix is shorter than the first
     * gameplay movie's, so it reaches the GHZ1 BGM dispatch after fewer
     * dormant invocations. Pinned from the authenticated capture; see
     * docs/status/audio-frontier-log.md for the measurement.
     */
    public static final int GAMEPLAY2_BK2_LAUNCH_INVOCATIONS = 269;
    /**
     * Every BK2 the gameplay capture kind accepts. The oracle's bar is any
     * BK2, not one BK2, so a gameplay reference is identified by which pinned
     * movie it came from rather than by a single hard-coded digest.
     */
    public static final Map<String, GameplayMovie> GAMEPLAY_MOVIES = Map.of(
            GAMEPLAY_BK2_SHA256,
            new GameplayMovie(GAMEPLAY_BK2_INPUT_ROWS, GAMEPLAY_BK2_LAUNCH_INVOCATIONS),
            GAMEPLAY2_BK2_SHA256,
            new GameplayMovie(GAMEPLAY2_BK2_INPUT_ROWS, GAMEPLAY2_BK2_LAUNCH_INVOCATIONS));
    /**
     * Per-song window capture pair: one window of a complete-run movie, opened
     * by a {@code Sound_PlayBGM} dispatch and closed by the next one.
     *
     * <p>The gameplay capture kind above covers a movie's first window only,
     * and pins that window's dormant launch-invocation count as a property of
     * its movie. Windows after the first have no dormant prefix at all -- the
     * driver is already running when they open -- so they are a separate kind
     * rather than a widening of the gameplay contract, which leaves every
     * committed gameplay fixture's validation untouched.
     *
     * <p>A window is single-song by construction, which is the whole point:
     * the reference normalizes sequence positions against one song's ROM asset
     * range and the engine host drives one music sequencer. Chaining windows
     * covers a whole run without either side needing a multi-song contract.
     */
    public static final String RUN_WINDOW_REFERENCE_CAPTURE = "s1_run_song_window_driver_reference";
    public static final String RUN_WINDOW_OPENGGF_CAPTURE = "s1_run_song_window_driver_openggf";
    /** S1 music ids, {@code bgm__First} through the last music pointer-table entry. */
    public static final int MUSIC_ID_BASE = 0x81;
    public static final int MUSIC_ID_MAX = 0x93;
    public static final int MAX_INVOCATIONS = 36_000;
    public static final String METADATA_TYPE = "capture_metadata";
    public static final String TICK_TYPE = "tick";
    public static final List<String> ROLES = List.of(
            "DAC", "FM1", "FM2", "FM3", "FM4", "FM5", "FM6", "PSG1", "PSG2", "PSG3");
    public static final Map<String, String> HARDWARE_BY_ROLE = Map.ofEntries(
            Map.entry("DAC", "DAC"), Map.entry("FM1", "FM1"), Map.entry("FM2", "FM2"),
            Map.entry("FM3", "FM3"), Map.entry("FM4", "FM4"), Map.entry("FM5", "FM5"),
            Map.entry("FM6", "FM6"), Map.entry("PSG1", "PSG1"), Map.entry("PSG2", "PSG2"),
            Map.entry("PSG3", "PSG3"));
    public static final Set<String> METADATA_FIELDS = Set.of(
            "type", "schema", "capture", "cycle_start", "period", "terminal_record_count", "rom",
            "callback_contract", "diagnostic_fields", "gating_fields", "launch_update_music_invocations",
            "movie", "music_id", "window");
    public static final List<String> DIAGNOSTIC_GLOBAL_FIELDS = List.of(
            "priority", "pause", "fade flags", "queues", "sound id", "voice selector", "DAC update",
            "1-up", "speed-up reload", "communication", "ring speaker", "push");
    public static final List<String> DIAGNOSTIC_TRACK_FIELDS = List.of(
            "resting", "note fill", "modulation phase", "raw status", "raw voice control");
    public static final List<String> GATING_GLOBAL_FIELDS = List.of(
            "tempo timeout", "tempo reload", "speed-up", "fade state");
    /** The SFX capture additionally gates on the per-invocation dispatch sequence. */
    public static final List<String> SFX_GATING_GLOBAL_FIELDS = List.of(
            "tempo timeout", "tempo reload", "speed-up", "fade state", "dispatches");
    public static final List<String> GATING_TRACK_FIELDS = List.of(
            "active", "role", "hardware", "overridden", "do not attack", "modulation enabled",
            "sequence position", "transpose", "volume", "pan/AMS/FMS", "voice/envelope", "duration",
            "duration reload", "PSG envelope cursor", "base frequency", "detune", "live loop counters",
            "live return stack");

    private AudioParitySchema() {
    }
}
