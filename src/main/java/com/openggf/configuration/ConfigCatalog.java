package com.openggf.configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.openggf.configuration.ConfigKeyMeta.derived;
import static com.openggf.configuration.ConfigKeyMeta.of;
import static com.openggf.configuration.ConfigKeyMeta.ofEnum;
import static com.openggf.configuration.ConfigType.BOOL;
import static com.openggf.configuration.ConfigType.DOUBLE;
import static com.openggf.configuration.ConfigType.INT;
import static com.openggf.configuration.ConfigType.KEY;
import static com.openggf.configuration.ConfigType.STRING;
import static com.openggf.configuration.SonicConfiguration.*;

/**
 * Metadata table over {@link SonicConfiguration}. The insertion order of
 * {@link #META} is the on-disk emit order: all normal sections first, then the
 * fenced {@code debug.*} block. DERIVED keys (never persisted) are included for
 * completeness but excluded from {@link #emitOrder()}.
 */
public final class ConfigCatalog {

    private static final Map<SonicConfiguration, ConfigKeyMeta> META = new LinkedHashMap<>();
    private static final List<SonicConfiguration> EMIT_ORDER;
    private static final Map<String, SonicConfiguration> BY_PATH = new LinkedHashMap<>();
    private static final Map<String, String> SECTION_TITLES = new LinkedHashMap<>();
    private static final Set<String> LAUNCH_ASPECT_VALUES = Set.of(
            "global", "NATIVE_4_3", "WIDE_16_10", "WIDE_16_9", "ULTRA_21_9", "SUPER_32_9");
    private static final Set<String> LAUNCH_CROSS_GAME_VALUES = Set.of("off", "s1", "s2", "s3k");
    private static final Set<String> LAUNCH_MAIN_CHARACTER_VALUES = Set.of("sonic", "tails", "knuckles");
    private static final Set<String> LAUNCH_SIDEKICK_VALUES = Set.of("none", "sonic", "tails", "knuckles");
    private static final Set<String> CONTROLLER_ASSIGNMENT_VALUES = Set.of("auto", "none");

    private static void put(SonicConfiguration key, ConfigKeyMeta meta) {
        META.put(key, meta);
    }

    static {
        // ---- DERIVED (never on disk) ----
        put(SCREEN_WIDTH_PIXELS, derived(INT, "Derived screen width in pixels (from display.aspect)"));
        put(SCREEN_HEIGHT_PIXELS, derived(INT, "Derived screen height in pixels (always 224)"));

        // ───────────────── NORMAL SECTIONS ─────────────────

        // display
        put(DISPLAY_ASPECT, ofEnum("display", "aspect",
                "Display aspect preset; resolves screen pixel width, height stays 224",
                Set.of("NATIVE_4_3", "WIDE_16_10", "WIDE_16_9", "ULTRA_21_9", "SUPER_32_9")));
        put(DISPLAY_WINDOW_AUTOSIZE, of("display", "windowAutosize", BOOL,
                "Derive the window size from the aspect preset at the 2x baseline"));
        put(DISPLAY_SHADER_LIBRARY_ROOT, of("display", "shaderLibraryRoot", STRING,
                "Root directory scanned for user display shaders"));
        put(DISPLAY_SHADER_SELECTION, of("display", "shaderSelection", STRING,
                "Last selected display shader: OFF or a root-relative forward-slash path"));
        put(DISPLAY_SHADER_NEXT_KEY, of("display", "shaderNextKey", KEY,
                "Runtime key to advance to the next display shader"));
        put(DISPLAY_SHADER_PREVIOUS_KEY, of("display", "shaderPreviousKey", KEY,
                "Runtime key to move to the previous display shader"));
        put(DISPLAY_SHADER_PICKER_KEY, of("display", "shaderPickerKey", KEY,
                "Runtime key to open the searchable display shader picker"));
        put(DISPLAY_SHADER_DEFAULT_PHASE, ofEnum("display", "shaderDefaultPhase",
                "Fallback render phase for standalone display shaders",
                Set.of("SCENE", "PRESENTATION", "FINAL")));
        put(WIDESCREEN_DEADZONE_MODE, ofEnum("display", "deadzoneMode",
                "Camera horizontal deadzone behaviour on wide screens",
                Set.of("CENTER_SCALED", "PROPORTIONAL")));
        put(DISPLAY_COLOR_PROFILE, ofEnum("display", "colorProfile",
                "Display-only color profile for Mega Drive palette presentation",
                Set.of("RAW_RGB", "MD_ANALOG", "NTSC_SOFT")));
        put(DISPLAY_COLOR_PROFILE_TOGGLE_KEY, of("display", "colorProfileToggleKey", KEY,
                "Runtime key to cycle the display color profile"));
        put(FPS, of("display", "fps", INT, "Frames per second to render (changes game speed)"));

        // gameplay
        put(LOAD_TIME_SIMULATION, ofEnum("gameplay", "loadTimeSimulation",
                "Normal-play simulation policy for ROM-backed load queues",
                Set.of("NONE", "PROFILED", "FAST", "REALISTIC")));

        // input (player-agnostic leaf first, then per-player subsections)
        put(PAUSE_KEY, of("input", "pause", KEY,
                "Toggle pause; the gamepad Start button also toggles it"));
        put(CONTROLLER_ENABLED, of("input.controller", "enabled", BOOL, "Enable gamepad/controller input"));
        put(CONTROLLER_DEADZONE, of("input.controller", "deadzone", DOUBLE,
                "Analog controller deadzone"));
        put(CONTROLLER_PLAYER1, ofEnum("input.controller", "player1",
                "Controller assignment for Player 1", CONTROLLER_ASSIGNMENT_VALUES));
        put(CONTROLLER_PLAYER2, ofEnum("input.controller", "player2",
                "Controller assignment for Player 2", CONTROLLER_ASSIGNMENT_VALUES));
        put(UP, of("input.player1", "up", KEY, "Player 1: look up"));
        put(DOWN, of("input.player1", "down", KEY, "Player 1: crouch/roll"));
        put(LEFT, of("input.player1", "left", KEY, "Player 1: move left"));
        put(RIGHT, of("input.player1", "right", KEY, "Player 1: move right"));
        put(P1_A, of("input.player1", "a", KEY, "Player 1: action button A"));
        put(P1_B, of("input.player1", "b", KEY, "Player 1: action button B"));
        put(P1_C, of("input.player1", "c", KEY, "Player 1: action button C"));
        put(JUMP, derived(KEY,
                "Deprecated flat-key compatibility alias for Player 1 action button A"));
        put(START, of("input.player1", "start", KEY,
                "Player 1: start (in-game pause). Keyboard-only -- gamepad Start toggles PAUSE_KEY's pause instead"));
        put(P2_UP, of("input.player2", "up", KEY, "Player 2: look up"));
        put(P2_DOWN, of("input.player2", "down", KEY, "Player 2: crouch/roll"));
        put(P2_LEFT, of("input.player2", "left", KEY, "Player 2: move left"));
        put(P2_RIGHT, of("input.player2", "right", KEY, "Player 2: move right"));
        put(P2_A, of("input.player2", "a", KEY, "Player 2: action button A"));
        put(P2_B, of("input.player2", "b", KEY, "Player 2: action button B"));
        put(P2_C, of("input.player2", "c", KEY, "Player 2: action button C"));
        put(P2_JUMP, derived(KEY,
                "Deprecated flat-key compatibility alias for Player 2 action button A"));
        put(P2_START, of("input.player2", "start", KEY, "Player 2: start"));

        // audio
        put(AUDIO_ENABLED, of("audio", "enabled", BOOL, "Enable music and SFX"));
        put(REGION, ofEnum("audio", "region", "Region for audio timing", Set.of("NTSC", "PAL")));
        put(DAC_INTERPOLATE, of("audio", "dacInterpolate", BOOL, "DAC interpolation (optional smoothing; disabled for hardware parity)"));
        put(AUDIO_FM_CORE, ofEnum("audio", "fmCore",
                "FM synthesis core: accurate (cycle-exact Nuked-OPN2, the parity oracle) or fast (register-level)",
                Set.of("accurate", "fast")));
        put(AUDIO_INTERNAL_RATE_OUTPUT, of("audio", "internalRateOutput", BOOL,
                "Output audio at the internal YM2612 rate (~53kHz)"));
        put(FM6_DAC_OFF, of("audio", "fm6DacOff", BOOL,
                "Mute FM6 when a note plays on it while DAC is enabled (SMPSPlay parity hack)"));

        // characters
        put(MAIN_CHARACTER_CODE, of("characters", "main", STRING, "Sprite code of the main playable character"));
        put(SIDEKICK_CHARACTER_CODE, of("characters", "sidekick", STRING,
                "Sprite code of the CPU sidekick; empty string disables the sidekick"));
        put(DATA_SELECT_EXTRA_PLAYER_COMBOS, of("characters", "dataSelectExtraCombos", STRING,
                "Semicolon-separated extra player combos for the data select screen"));

        // roms
        put(SONIC_1_ROM, of("roms", "sonic1", STRING, "Filename of the Sonic 1 ROM"));
        put(SONIC_2_ROM, of("roms", "sonic2", STRING, "Filename of the Sonic 2 ROM"));
        put(SONIC_3K_ROM, of("roms", "sonic3k", STRING, "Filename of the Sonic 3&K ROM"));
        put(DEFAULT_ROM, ofEnum("roms", "default", "Which game to load by default",
                Set.of("s1", "s2", "s3k")));

        // startup
        put(TITLE_SCREEN_ON_STARTUP, of("startup", "titleScreen", BOOL, "Show the title screen on startup"));
        put(MASTER_TITLE_SCREEN_ON_STARTUP, of("startup", "masterTitleScreen", BOOL,
                "Show the master (game-selection) title screen on startup"));
        put(SHOW_LEGAL_DISCLAIMER_ON_STARTUP, of("startup", "legalDisclaimer", BOOL,
                "Show the legal disclaimer screen before the master title screen"));

        // mods
        put(SKIP_MOD_ZONE_TITLE_CARDS, of("mods", "skipModZoneTitleCards", BOOL,
                "Skip unsupported title cards when entering additive mod zones"));

        // rewind (player-facing live rewind)
        put(LIVE_REWIND_ENABLED, of("rewind", "liveEnabled", BOOL,
                "Enable held-key rewind during ordinary live play"));
        put(LIVE_REWIND_KEY, of("rewind", "liveKey", KEY,
                "Key held to rewind during live play; the gamepad left bumper (L1/LB) also holds rewind"));
        put(LIVE_REWIND_HALF_SPEED_KEY, of("rewind", "liveHalfSpeedKey", KEY,
                "Modifier held with the rewind key for half-speed rewind"));
        put(LIVE_REWIND_DOUBLE_SPEED_KEY, of("rewind", "liveDoubleSpeedKey", KEY,
                "Modifier held with the rewind key for double-speed rewind"));
        put(LIVE_REWIND_TAPE_COAST_ENABLED, of("rewind", "tapeCoastEnabled", BOOL,
                "Continue rewinding with a decelerating tape-coast after key release"));
        put(LIVE_REWIND_TAPE_COAST_ACCELERATION, of("rewind", "tapeCoastAcceleration", DOUBLE,
                "Per-tick speed increase while tape-coast is held"));
        put(LIVE_REWIND_TAPE_COAST_DECELERATION, of("rewind", "tapeCoastDeceleration", DOUBLE,
                "Per-tick speed decrease after release"));
        put(LIVE_REWIND_TAPE_COAST_MAX_STEPS, of("rewind", "tapeCoastMaxSteps", DOUBLE,
                "Maximum rewind steps per tick"));
        put(LIVE_REWIND_TAPE_COAST_MIN_STEPS, of("rewind", "tapeCoastMinSteps", DOUBLE,
                "Minimum rewind steps per tick; below 1.0 gives slow-motion rewind"));
        put(LIVE_REWIND_VHS_EFFECT, of("rewind", "vhsEffect", BOOL,
                "Render a VHS picture-search effect while live rewind is active"));
        put(LIVE_REWIND_VHS_TEAR_BANDS, of("rewind", "vhsTearBands", BOOL,
                "Include the scrolling tear bands in the VHS rewind effect"));
        put(REWIND_HISTORY_SECONDS, of("rewind", "historySeconds", INT,
                "Seconds of live rewind keyframe and input history to retain"));
        put(REWIND_AUDIO_HISTORY_LIMIT_TYPE, ofEnum("rewind", "audioHistoryLimitType",
                "How the rewind audio PCM history ring is sized", Set.of("time", "size")));
        put(REWIND_AUDIO_HISTORY_SECONDS, of("rewind", "audioHistorySeconds", INT,
                "Seconds of PCM history kept when audioHistoryLimitType=time"));
        put(REWIND_AUDIO_HISTORY_SIZE_MB, of("rewind", "audioHistorySizeMb", INT,
                "Megabytes of PCM history kept when audioHistoryLimitType=size"));

        // crossGame (user-facing donation toggles)
        put(CROSS_GAME_FEATURES_ENABLED, of("crossGame", "enabled", BOOL,
                "Enable cross-game feature donation (e.g. S2 sprites in S1)"));
        put(CROSS_GAME_SOURCE, ofEnum("crossGame", "source",
                "Donor game for cross-game features", Set.of("s2", "s3k")));

        // launch (per-game master-title profile defaults)
        put(LAUNCH_S1_REWIND, of("launch.s1", "rewind", BOOL,
                "Default Sonic 1 launch profile: enable live rewind"));
        put(LAUNCH_S1_CROSS_GAME_SOURCE, ofEnum("launch.s1", "crossGameSource",
                "Default Sonic 1 launch profile: cross-game donor", LAUNCH_CROSS_GAME_VALUES));
        put(LAUNCH_S1_DEBUG_TOOLS, of("launch.s1", "debugTools", BOOL,
                "Default Sonic 1 launch profile: enable debug tools"));
        put(LAUNCH_S1_ASPECT, ofEnum("launch.s1", "aspect",
                "Default Sonic 1 launch profile: display aspect override", LAUNCH_ASPECT_VALUES));
        put(LAUNCH_S1_MAIN_CHARACTER, ofEnum("launch.s1", "mainCharacter",
                "Default Sonic 1 launch profile: main character", LAUNCH_MAIN_CHARACTER_VALUES));
        put(LAUNCH_S1_SIDEKICK, ofEnum("launch.s1", "sidekick",
                "Default Sonic 1 launch profile: sidekick character", LAUNCH_SIDEKICK_VALUES));

        put(LAUNCH_S2_REWIND, of("launch.s2", "rewind", BOOL,
                "Default Sonic 2 launch profile: enable live rewind"));
        put(LAUNCH_S2_CROSS_GAME_SOURCE, ofEnum("launch.s2", "crossGameSource",
                "Default Sonic 2 launch profile: cross-game donor", LAUNCH_CROSS_GAME_VALUES));
        put(LAUNCH_S2_DEBUG_TOOLS, of("launch.s2", "debugTools", BOOL,
                "Default Sonic 2 launch profile: enable debug tools"));
        put(LAUNCH_S2_ASPECT, ofEnum("launch.s2", "aspect",
                "Default Sonic 2 launch profile: display aspect override", LAUNCH_ASPECT_VALUES));
        put(LAUNCH_S2_MAIN_CHARACTER, ofEnum("launch.s2", "mainCharacter",
                "Default Sonic 2 launch profile: main character", LAUNCH_MAIN_CHARACTER_VALUES));
        put(LAUNCH_S2_SIDEKICK, ofEnum("launch.s2", "sidekick",
                "Default Sonic 2 launch profile: sidekick character", LAUNCH_SIDEKICK_VALUES));

        put(LAUNCH_S3K_REWIND, of("launch.s3k", "rewind", BOOL,
                "Default Sonic 3&K launch profile: enable live rewind"));
        put(LAUNCH_S3K_CROSS_GAME_SOURCE, ofEnum("launch.s3k", "crossGameSource",
                "Default Sonic 3&K launch profile: cross-game donor", LAUNCH_CROSS_GAME_VALUES));
        put(LAUNCH_S3K_DEBUG_TOOLS, of("launch.s3k", "debugTools", BOOL,
                "Default Sonic 3&K launch profile: enable debug tools"));
        put(LAUNCH_S3K_ASPECT, ofEnum("launch.s3k", "aspect",
                "Default Sonic 3&K launch profile: display aspect override", LAUNCH_ASPECT_VALUES));
        put(LAUNCH_S3K_MAIN_CHARACTER, ofEnum("launch.s3k", "mainCharacter",
                "Default Sonic 3&K launch profile: main character", LAUNCH_MAIN_CHARACTER_VALUES));
        put(LAUNCH_S3K_SIDEKICK, ofEnum("launch.s3k", "sidekick",
                "Default Sonic 3&K launch profile: sidekick character", LAUNCH_SIDEKICK_VALUES));

        // discord
        put(DISCORD_RICH_PRESENCE_ENABLED, of("discord", "enabled", BOOL,
                "Enable Discord Rich Presence updates"));
        put(DISCORD_RICH_PRESENCE_SHOW_TIMER, of("discord", "showTimer", BOOL,
                "Show the level timer in Rich Presence"));
        put(DISCORD_RICH_PRESENCE_SHOW_ZONE, of("discord", "showZone", BOOL,
                "Show the zone and act in Rich Presence"));

        // capture (trace video capture)
        put(CAPTURE_OUTPUT_DIR, of("capture", "outputDir", STRING,
                "Output directory for trace capture videos"));
        put(CAPTURE_SCALE, of("capture", "scale", INT,
                "Integer nearest-neighbor upscale factor for capture output"));
        put(CAPTURE_FPS, of("capture", "fps", INT, "Output frame rate for trace capture"));
        put(CAPTURE_CODEC, of("capture", "codec", STRING,
                "Capture video codec: ffv1, h264 or h265. All three are lossless"
                        + " (h264/h265 encode RGB directly; the usual yuv444p"
                        + " 'lossless' settings are not byte-exact for RGB input)."
                        + " ffv1 is the most widely playable; h264/h265 produce"
                        + " smaller files but in an RGB form some players reject"));
        put(CAPTURE_AUDIO_CODEC, of("capture", "audioCodec", STRING,
                "Capture audio codec: flac, aac or mp3. WARNING: aac and mp3 are"
                        + " LOSSY - the recorded audio will not match what the"
                        + " engine produced. flac is lossless and is the default"));
        put(CAPTURE_QUEUE_BUDGET_MB, of("capture", "queueBudgetMb", INT,
                "Memory budget in MB for the live-recording encoder queue. The"
                        + " queue is sized in whole frames from this budget and"
                        + " the recording viewport, so it holds fewer frames at"
                        + " larger window sizes. A deeper queue absorbs longer"
                        + " encoder stalls -- lossless FFV1 falls behind on"
                        + " high-motion content such as fast-forwarded trace"
                        + " playback -- before backpressure stalls the game loop"));
        put(CAPTURE_ENCODER_THREADS, of("capture", "encoderThreads", INT,
                "ffmpeg thread count for the encode pass. 0 lets ffmpeg choose,"
                        + " normally one thread per core. FFV1 is additionally"
                        + " sliced so threads can be used at all; x264/x265"
                        + " already thread internally, so raising this rarely"
                        + " helps them -- use encoderPreset instead"));
        put(CAPTURE_ENCODER_PRESET, of("capture", "encoderPreset", STRING,
                "x264/x265 speed preset: ultrafast, superfast, veryfast, faster,"
                        + " fast, medium, slow, slower, veryslow or placebo."
                        + " Blank uses the encoder's own default (medium)."
                        + " Ignored by FFV1, which has no preset. Presets trade"
                        + " encode speed for file size and never affect"
                        + " losslessness. Defaults to fast: libx265 at medium"
                        + " keeps up with ordinary play but not with"
                        + " high-motion content such as fast-forwarded trace"
                        + " playback, where each recorded frame is several"
                        + " gameplay frames from the last"));
        put(CAPTURE_CONTAINER, of("capture", "container", STRING,
                "Recording file extension, e.g. mkv or mp4. ffmpeg picks its"
                        + " muxer from this. Recent ffmpeg will write every codec"
                        + " here into either container, but player support is much"
                        + " narrower: mkv plays everything, while mp4 is portable"
                        + " only with h264/h265 + aac. mp4 holding ffv1 or flac is"
                        + " a valid file most players will refuse"));
        put(CAPTURE_FFMPEG_PASS1_ARGS, of("capture", "ffmpegPass1Args", STRING,
                "ADVANCED. Full ffmpeg argument list for the encode pass."
                        + " 'default' uses the engine's command. Placeholders:"
                        + " {width} {height} {fps} {scale} {scaledWidth}"
                        + " {scaledHeight} {videoCodecArgs} {videoOut}."
                        + " Frames always arrive as rawvideo/rgba on pipe:0."
                        + " Cannot be empty: this pass encodes the frames"));
        put(CAPTURE_FFMPEG_PASS2_ARGS, of("capture", "ffmpegPass2Args", STRING,
                "ADVANCED. Full ffmpeg argument list for the mux pass."
                        + " 'default' uses the engine's command. Placeholders:"
                        + " {videoIn} {audioIn} {sampleRate} {audioCodecArgs}"
                        + " {output}. Leave EMPTY to skip muxing entirely: the"
                        + " encode pass output is published as-is and the"
                        + " recording has NO AUDIO"));
        put(CAPTURE_TOGGLE_KEY, of("capture", "toggleKey", KEY,
                "Live viewport recording toggle. Modifiers belong in the value"
                        + " (e.g. SHIFT+O, CTRL+SHIFT+O). A bare O is reserved and"
                        + " is migrated back to SHIFT+O"));

        // timeAttack
        put(TIME_ATTACK_RETRY_KEY, of("timeAttack", "retryKey", KEY,
                "Key that instantly retries the current time attack from the act start."));
        put(TIME_ATTACK_MENU_KEY, of("timeAttack", "menuKey", KEY,
                "Key that opens the solo Time Attack menu from the master title screen."));
        put(TIME_ATTACK_NET_HOST_PORT, of("timeAttack.net", "hostPort", INT,
                "TCP/WebSocket port for player-hosted LAN race rooms."));
        put(TIME_ATTACK_NET_LAST_JOIN_ADDRESS, of("timeAttack.net", "lastJoinAddress", STRING,
                "Most recently joined LAN race address."));
        put(TIME_ATTACK_NET_DISPLAY_NAME, of("timeAttack.net", "displayName", STRING,
                "Pseudonymous multiplayer race display name."));
        put(TIME_ATTACK_NET_MASTER_URL, of("timeAttack.net", "masterUrl", STRING,
                "Master-server WebSocket URL for internet race browsing."));
        put(TIME_ATTACK_NET_MASTER_TRUST_INSECURE, of("timeAttack.net", "masterTrustInsecure", BOOL,
                "Development-only trust-all TLS mode for the master server."));
        put(TIME_ATTACK_HUD_MINIMAP, of("timeAttack.hud", "minimap", BOOL,
                "Show the multiplayer minimap progress strip."));

        // ───────────────── DEBUG BLOCK ─────────────────

        // debug.flags
        put(DEBUG_VIEW_ENABLED, of("debug.flags", "debugView", BOOL,
                "Enable the debug overlay subsystem; visible HUD starts hidden until toggled"));
        put(EDITOR_ENABLED, of("debug.flags", "editor", BOOL, "Allow entering the level editor from gameplay"));

        // debug.keys
        put(TEST, of("debug.keys", "test", KEY, "Debug-only test button"));
        put(NEXT_ACT, of("debug.keys", "nextAct", KEY, "Advance to the next act"));
        put(NEXT_ZONE, of("debug.keys", "nextZone", KEY, "Advance to the next zone"));
        put(DEBUG_MODE_KEY, of("debug.keys", "debugMode", KEY,
                "Toggle debug movement mode; the gamepad north face button (Y/Triangle) also toggles it"));
        put(FRAME_STEP_KEY, of("debug.keys", "frameStep", KEY,
                "Step forward one frame while paused; the gamepad right bumper (RB/R1) also steps a frame"));
        put(DEBUG_LAST_CHECKPOINT_KEY, of("debug.keys", "lastCheckpoint", KEY,
                "Teleport to the last checkpoint"));
        put(LEVEL_SELECT_KEY, of("debug.keys", "levelSelect", KEY, "Open the level select screen"));
        put(SUPER_SONIC_DEBUG_KEY, of("debug.keys", "superSonic", KEY, "Toggle Super Sonic debug mode"));
        put(GIVE_EMERALDS_KEY, of("debug.keys", "giveEmeralds", KEY, "Give all chaos emeralds"));
        put(HYPER_FORM_DEBUG_KEY, of("debug.keys", "hyperForm", KEY,
                "Toggle the highest unlocked S3K form"));
        put(GIVE_SUPER_EMERALDS_KEY, of("debug.keys", "giveSuperEmeralds", KEY,
                "Give all super emeralds"));
        put(SPECIAL_STAGE_KEY, of("debug.keys", "specialStage", KEY, "Toggle special stage mode"));
        put(SPECIAL_STAGE_COMPLETE_KEY, of("debug.keys", "specialStageComplete", KEY,
                "Complete the special stage with an emerald"));
        put(SPECIAL_STAGE_FAIL_KEY, of("debug.keys", "specialStageFail", KEY, "Fail the special stage"));
        put(SPECIAL_STAGE_SPRITE_DEBUG_KEY, of("debug.keys", "specialStageSpriteDebug", KEY,
                "Toggle the special stage sprite debug viewer"));
        put(SPECIAL_STAGE_PLANE_DEBUG_KEY, of("debug.keys", "specialStagePlaneDebug", KEY,
                "Cycle special stage plane visibility debug modes"));

        // debug.startup
        put(LEVEL_SELECT_ON_STARTUP, of("debug.startup", "levelSelectOnStartup", BOOL,
                "Open Level Select on startup instead of loading the first zone"));
        put(S3K_SKIP_INTROS, of("debug.startup", "s3kSkipIntros", BOOL,
                "Skip S3K zone intro sequences (AIZ biplane, etc.)"));

        // debug.playback
        put(PLAYBACK_MOVIE_PATH, of("debug.playback", "moviePath", STRING,
                "Path to a BizHawk BK2 movie for playback debugging"));
        put(PLAYBACK_TOGGLE_KEY, of("debug.playback", "toggleKey", KEY, "Toggle playback mode"));
        put(PLAYBACK_LOAD_KEY, of("debug.playback", "loadKey", KEY, "Load/reload the BK2 movie"));
        put(PLAYBACK_PLAY_PAUSE_KEY, of("debug.playback", "playPauseKey", KEY, "Toggle playback play/pause"));
        put(PLAYBACK_STEP_BACK_KEY, of("debug.playback", "stepBackKey", KEY, "Step the cursor back one frame"));
        put(PLAYBACK_STEP_FORWARD_KEY, of("debug.playback", "stepForwardKey", KEY,
                "Step the cursor forward one frame"));
        put(PLAYBACK_JUMP_BACK_KEY, of("debug.playback", "jumpBackKey", KEY,
                "Jump the cursor back by a larger interval"));
        put(PLAYBACK_JUMP_FORWARD_KEY, of("debug.playback", "jumpForwardKey", KEY,
                "Jump the cursor forward by a larger interval"));
        put(PLAYBACK_FAST_RATE_KEY, of("debug.playback", "fastRateKey", KEY,
                "Cycle playback rate (1x/2x/4x/8x)"));
        put(PLAYBACK_RESET_TO_START_KEY, of("debug.playback", "resetToStartKey", KEY,
                "Reset the cursor to the start offset"));
        put(PLAYBACK_START_OFFSET_FRAME, of("debug.playback", "startOffsetFrame", INT,
                "Starting frame offset for BK2 playback"));

        // debug.recording
        put(RECORDING_RECORD_KEY, of("debug.recording", "recordKey", KEY,
                "User recording key: Shift+key starts/opens recordings, key alone stops active recording"));

        // debug.traceRewind
        put(TRACE_REWIND_KEY, of("debug.traceRewind", "key", KEY,
                "Key held in Trace Test Mode to rewind deterministic engine state"));

        // debug.rewind
        put(LIVE_REWIND_DETERMINISM_AUDIT, of("debug.rewind", "determinismAudit", BOOL,
                "Audit live-rewind determinism: re-simulate each completed keyframe segment "
                + "and log the first state divergence (debug; ~2x frame cost)"));

        // debug.traceRender (Trace Test Mode + capture visibility)
        put(TRACE_SHOW_DESYNC_GHOSTS, of("debug.traceRender", "showDesyncGhosts", BOOL,
                "Render the desync ghost(s) in Trace Test Mode and trace capture"));
        put(TRACE_SHOW_GAME_HUD, of("debug.traceRender", "showGameHud", BOOL,
                "Render the game HUD (rings/score/time) during trace replay and capture"));
        put(TRACE_SHOW_DEBUG_HUD, of("debug.traceRender", "showDebugHud", BOOL,
                "Render the debug HUD during trace replay and capture (per-panel toggles still apply)"));

        // debug.testMode
        put(TEST_MODE_ENABLED, of("debug.testMode", "enabled", BOOL,
                "Replace the master title with the trace picker (dev-only)"));
        put(TRACE_CATALOG_DIR, of("debug.testMode", "catalogDir", STRING,
                "Directory scanned for traces when test mode is enabled"));

        // debug.crossGame (debug tooling)
        put(CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE, of("debug.crossGame",
                "s1DataSelectImageGenOverride", BOOL, "Force regeneration of the S1 data-select image cache"));
        put(CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE, of("debug.crossGame",
                "s2DataSelectImageGenOverride", BOOL, "Force regeneration of the S2 data-select image cache"));
        put(CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY, of("debug.crossGame",
                "s1DataSelectImageCoordLogKey", KEY,
                "Log the current camera position as an S1 data-select preview override"));

        // debug.window (DEPRECATED manual window/scale)
        put(SCREEN_WIDTH, of("debug.window", "width", INT,
                "DEPRECATED manual window width; used only when display.windowAutosize=false"));
        put(SCREEN_HEIGHT, of("debug.window", "height", INT,
                "DEPRECATED manual window height; used only when display.windowAutosize=false"));
        put(SCALE, of("debug.window", "scale", DOUBLE,
                "DEPRECATED AWT debug-viewer scale factor"));

        // ---- Derived index structures ----
        List<SonicConfiguration> order = new ArrayList<>();
        for (Map.Entry<SonicConfiguration, ConfigKeyMeta> e : META.entrySet()) {
            ConfigKeyMeta m = e.getValue();
            if (m.persisted()) {
                order.add(e.getKey());
                BY_PATH.put(m.path(), e.getKey());
            }
        }
        // Deprecated YAML paths are accepted for migration but never emitted.
        BY_PATH.put("input.player1.jump", JUMP);
        BY_PATH.put("input.player2.jump", P2_JUMP);
        EMIT_ORDER = List.copyOf(order);

        // Top-level normal sections only. debug.* sub-sections are intentionally untitled
        // (the writer fences the whole debug block with a single banner instead).
        SECTION_TITLES.put("display", "Display");
        SECTION_TITLES.put("input", "Input");
        SECTION_TITLES.put("audio", "Audio");
        SECTION_TITLES.put("characters", "Characters");
        SECTION_TITLES.put("roms", "ROMs");
        SECTION_TITLES.put("startup", "Startup");
        SECTION_TITLES.put("rewind", "Rewind (live)");
        SECTION_TITLES.put("crossGame", "Cross-Game");
        SECTION_TITLES.put("launch", "Launch Profiles");
        SECTION_TITLES.put("discord", "Discord Rich Presence");
        SECTION_TITLES.put("capture", "Trace Capture");
    }

    private ConfigCatalog() {
    }

    public static ConfigKeyMeta meta(SonicConfiguration key) {
        return META.get(key);
    }

    /** Persisted keys only, in on-disk emit order (insertion order of META). */
    public static List<SonicConfiguration> emitOrder() {
        return EMIT_ORDER;
    }

    /** Reverse lookup: dotted {@code section.leaf} path → key, or {@code null} if unknown. */
    public static SonicConfiguration byPath(String path) {
        return BY_PATH.get(path);
    }

    /**
     * Human title for a TOP-LEVEL section (the first dotted segment, e.g. {@code "input"} or
     * {@code "audio"}) — pass the first segment, not a full sub-section path like
     * {@code "input.player1"}. Returns the raw name if no title is registered.
     */
    public static String title(String topLevelSection) {
        return SECTION_TITLES.getOrDefault(topLevelSection, topLevelSection);
    }
}
