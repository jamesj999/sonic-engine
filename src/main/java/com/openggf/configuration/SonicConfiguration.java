package com.openggf.configuration;

import com.openggf.game.ModApi;

/**
 * All configurable properties are put here. Eventually, these will be loaded
 * from a file. Use SonicConfigurationSerivce to retrieve the values for
 * these properties. This way, the service can eventually populate the options
 * from the file.
 * 
 * 
 * 
 * @author james
 * 
 */
@ModApi
public enum SonicConfiguration {
	/**
	 * Current Version number.
	 */
	VERSION,
	/**
	 * Actual width of the screen (number of available x-coordinates).
	 */
	SCREEN_WIDTH_PIXELS,
	/**
	 * Actual height of the screen (number of available y-coordinates).
	 */
	SCREEN_HEIGHT_PIXELS,
	/**
	 * Current width of the screen.
	 */
	SCREEN_WIDTH,
	/**
	 * Current height of the screen.
	 */
	SCREEN_HEIGHT,
	/**
	 * Scale factor for BufferedImage rendering (used for AWT-based debug viewers).
	 */
	SCALE,
	/**
	 * Frames per second to render. Will make the game faster/slower!
	 */
	FPS,
	/**
	 * Normal-play simulation policy for ROM-backed load queues.
	 */
	LOAD_TIME_SIMULATION,
	/*
	 * ALWAYS DEFINE BUTTONS IN THE ORDER: UP, DOWN, LEFT, RIGHT. NOT FOR ANY
	 * TECHNICAL REASON, JUST BECAUSE LEVEL SELECT.
	 */
	/**
	 * Key to look up.
	 */
	UP,
	/**
	 * Key to crouch/roll.
	 */
	DOWN,
	/**
	 * Key to move Sonic left.
	 */
	LEFT,
	/**
	 * Key to move Sonic right.
	 */
	RIGHT,
	/**
	 * Player 1 action button A.
	 */
	P1_A,
	/**
	 * Player 1 action button B.
	 */
	P1_B,
	/**
	 * Player 1 action button C.
	 */
	P1_C,
	/**
	 * Deprecated compatibility key for old flat configs. Runtime gameplay should
	 * read logical Player 1 action buttons via P1_A/P1_B/P1_C.
	 */
	JUMP,
	/**
	 * Player 1 Start. Drives ROM in-game pause (Game_paused / Pause_Loop): a
	 * Start-press edge during level gameplay freezes the level update for the
	 * frame while the frame counter still advances, then a second press resumes.
	 * Distinct from {@link #PAUSE_KEY}, which is the engine's loop/timing-level
	 * window/keyboard pause that also halts audio.
	 */
	START,
	P2_UP,
	P2_DOWN,
	P2_LEFT,
	P2_RIGHT,
	/**
	 * Player 2 action button A.
	 */
	P2_A,
	/**
	 * Player 2 action button B.
	 */
	P2_B,
	/**
	 * Player 2 action button C.
	 */
	P2_C,
	/**
	 * Deprecated compatibility key for old flat configs. Runtime gameplay should
	 * read logical Player 2 action buttons via P2_A/P2_B/P2_C.
	 */
	P2_JUMP,
	P2_START,
	/**
	 * Whether gamepad/controller input is enabled.
	 */
	CONTROLLER_ENABLED,
	/**
	 * Analog deadzone applied to controller axes.
	 */
	CONTROLLER_DEADZONE,
	/**
	 * Controller assignment policy for Player 1.
	 */
	CONTROLLER_PLAYER1,
	/**
	 * Controller assignment policy for Player 2.
	 */
	CONTROLLER_PLAYER2,

	/**
	 * Test button only used in debug
	 */
	TEST,

	/**
	 * Test button for next act
	 */
	NEXT_ACT,

	/**
	 * Test button for next zone
	 */
	NEXT_ZONE,

	/**
	 * Code of the sprite of the main playable character.
	 */
	MAIN_CHARACTER_CODE,
	/**
	 * Whether to display debugging information on screen.
	 */
	DEBUG_VIEW_ENABLED,

	/**
	 * Whether the level editor may be entered from normal gameplay.
	 */
	EDITOR_ENABLED,

	/**
	 * Key to toggle Debug Movement Mode at runtime.
	 */
	DEBUG_MODE_KEY,

	/**
	 * Whether to enable Audio (Music/SFX)
	 */
	AUDIO_ENABLED,

	/**
	 * Display-only color profile used when converting Mega Drive palette colors
	 * for presentation.
	 */
	DISPLAY_COLOR_PROFILE,

	/**
	 * Key to cycle the display color profile at runtime.
	 */
	DISPLAY_COLOR_PROFILE_TOGGLE_KEY,

	/** Display aspect preset (NATIVE_4_3, WIDE_16_10, WIDE_16_9, ULTRA_21_9, SUPER_32_9). Resolves to SCREEN_WIDTH_PIXELS; height stays 224. */
	DISPLAY_ASPECT,
	/** Camera horizontal deadzone behaviour on wide screens: CENTER_SCALED keeps the native 16px deadzone band; PROPORTIONAL scales the band width with the screen width. */
	WIDESCREEN_DEADZONE_MODE,
	/** When true, the display window is derived from DISPLAY_ASPECT at the 2x baseline; when false, SCREEN_WIDTH/SCREEN_HEIGHT are used verbatim. */
	DISPLAY_WINDOW_AUTOSIZE,
	/** Root directory scanned for user display shaders (relative to working dir). */
	DISPLAY_SHADER_LIBRARY_ROOT,
	/** Last selected display shader: "OFF" or a root-relative forward-slash path. */
	DISPLAY_SHADER_SELECTION,
	/** Runtime key to advance to the next display shader. */
	DISPLAY_SHADER_NEXT_KEY,
	/** Runtime key to move to the previous display shader. */
	DISPLAY_SHADER_PREVIOUS_KEY,
	/** Runtime key to open the searchable display shader picker. */
	DISPLAY_SHADER_PICKER_KEY,
	/** Fallback render phase for standalone display shaders (SCENE/PRESENTATION/FINAL). */
	DISPLAY_SHADER_DEFAULT_PHASE,

	/**
	 * Region (NTSC/PAL) for audio timing.
	 */
	REGION,

	/**
	 * Whether to enable DAC Interpolation (smoother sound).
	 */
	DAC_INTERPOLATE,

	/**
	 * Whether to output audio at the internal YM2612 rate (~53kHz).
	 */
	AUDIO_INTERNAL_RATE_OUTPUT,

	/**
	 * Which FM synthesis core renders music and SFX: {@code accurate}
	 * (cycle-exact Nuked-OPN2 port, the parity oracle) or {@code fast}
	 * (register-level clean-room core).
	 */
	AUDIO_FM_CORE,

	/**
	 * Whether to mute FM6 when playing a note on it (if DAC is enabled).
	 * Parity hack from SMPSPlay.
	 */
	FM6_DAC_OFF,

	/**
	 * Key to toggle Special Stage mode (for testing).
	 */
	SPECIAL_STAGE_KEY,

	/**
	 * Key to complete Special Stage with emerald (debug).
	 */
	SPECIAL_STAGE_COMPLETE_KEY,

	/**
	 * Key to fail Special Stage (debug).
	 */
	SPECIAL_STAGE_FAIL_KEY,

	/**
	 * Key to toggle Special Stage sprite debug viewer.
	 */
	SPECIAL_STAGE_SPRITE_DEBUG_KEY,

	/**
	 * Key to cycle Special Stage plane visibility debug modes.
	 */
	SPECIAL_STAGE_PLANE_DEBUG_KEY,

	/**
	 * Key to toggle pause (default: ENTER).
	 */
	PAUSE_KEY,

	/**
	 * Key to step forward one frame while paused (default: Q).
	 */
	FRAME_STEP_KEY,

	/**
	 * Path to BizHawk BK2 movie file for playback debugging.
	 */
	PLAYBACK_MOVIE_PATH,

	/**
	 * Key to toggle playback mode.
	 */
	PLAYBACK_TOGGLE_KEY,

	/**
	 * Key to load/reload BK2 from PLAYBACK_MOVIE_PATH.
	 */
	PLAYBACK_LOAD_KEY,

	/**
	 * Key to toggle playback play/pause.
	 */
	PLAYBACK_PLAY_PAUSE_KEY,

	/**
	 * Key to step the BK2 cursor backward by one frame.
	 */
	PLAYBACK_STEP_BACK_KEY,

	/**
	 * Key to step the BK2 cursor forward by one frame.
	 */
	PLAYBACK_STEP_FORWARD_KEY,

	/**
	 * Key to jump the BK2 cursor backward by a larger interval.
	 */
	PLAYBACK_JUMP_BACK_KEY,

	/**
	 * Key to jump the BK2 cursor forward by a larger interval.
	 */
	PLAYBACK_JUMP_FORWARD_KEY,

	/**
	 * Key to cycle playback rate (1x/2x/4x/8x).
	 */
	PLAYBACK_FAST_RATE_KEY,

	/**
	 * Key to reset BK2 cursor to PLAYBACK_START_OFFSET_FRAME.
	 */
	PLAYBACK_RESET_TO_START_KEY,

	/**
	 * Starting frame offset for BK2 playback.
	 */
	PLAYBACK_START_OFFSET_FRAME,

	/**
	 * Key used with Shift to open/start user recordings, and alone to stop an
	 * active recording.
	 */
	RECORDING_RECORD_KEY,

	/**
	 * Key that instantly retries the current solo time attack from the act start.
	 */
	TIME_ATTACK_RETRY_KEY,

	/**
	 * Key that opens the solo Time Attack menu from the master title screen.
	 */
	TIME_ATTACK_MENU_KEY,

	/** TCP/WebSocket port used by player-hosted LAN time-attack rooms. */
	TIME_ATTACK_NET_HOST_PORT,

	/** Most recently joined LAN host address, used to prefill the join field. */
	TIME_ATTACK_NET_LAST_JOIN_ADDRESS,

	/** Pseudonymous multiplayer display name; blank uses the identity prefix. */
	TIME_ATTACK_NET_DISPLAY_NAME,

	/** Master-server WebSocket URL; blank disables internet-room browsing. */
	TIME_ATTACK_NET_MASTER_URL,

	/** Development-only trust-all TLS switch for the master-server client. */
	TIME_ATTACK_NET_MASTER_TRUST_INSECURE,

	/** Show the roster-fed multiplayer progress strip. */
	TIME_ATTACK_HUD_MINIMAP,

	/**
	 * Key held in visual Trace Test Mode to rewind deterministic engine state.
	 */
	TRACE_REWIND_KEY,

	/**
	 * Trace Test Mode / capture: render the desync ghost(s). Default true.
	 */
	TRACE_SHOW_DESYNC_GHOSTS,
	/**
	 * Trace Test Mode / capture: render the game HUD (rings/score/time). Default true.
	 */
	TRACE_SHOW_GAME_HUD,
	/**
	 * Trace Test Mode / capture: render the debug HUD. When true, the per-element
	 * DebugOverlayToggle states decide which panels show. Default false.
	 */
	TRACE_SHOW_DEBUG_HUD,

	/** Output directory for trace capture videos. */
	CAPTURE_OUTPUT_DIR,
	/** Integer nearest-neighbor upscale factor for trace capture output. */
	CAPTURE_SCALE,
	/** Output frame rate for trace capture. */
	CAPTURE_FPS,
	/** Trace capture video codec (e.g. "ffv1"). */
	CAPTURE_CODEC,
	/** Capture audio codec: flac (lossless), aac or mp3 (both lossy). */
	CAPTURE_AUDIO_CODEC,
	/** Output container extension for recordings, e.g. mkv or mp4. */
	CAPTURE_CONTAINER,
	/** Memory budget in MB for the live-recording encoder queue. */
	CAPTURE_QUEUE_BUDGET_MB,
	/** ffmpeg encode-pass thread count; 0 lets ffmpeg choose. */
	CAPTURE_ENCODER_THREADS,
	/** x264/x265 speed preset; blank uses the encoder default. */
	CAPTURE_ENCODER_PRESET,
	/** Advanced: full argument list for the first ffmpeg pass. */
	CAPTURE_FFMPEG_PASS1_ARGS,
	/** Advanced: full argument list for the second ffmpeg pass; empty skips it. */
	CAPTURE_FFMPEG_PASS2_ARGS,
	/** Key used with Shift to toggle live viewport audio/video recording. */
	CAPTURE_TOGGLE_KEY,

	/**
	 * Whether held-key rewind is enabled during ordinary live level play.
	 */
	LIVE_REWIND_ENABLED,

	/**
	 * Whether live rewind audits completed keyframe segments for determinism.
	 */
	LIVE_REWIND_DETERMINISM_AUDIT,

	/**
	 * Key held during ordinary live level play to rewind deterministic gameplay state.
	 */
	LIVE_REWIND_KEY,

	/**
	 * Modifier key held together with the rewind key for half-speed rewind.
	 * The mirrored left/right variant of a modifier key also counts.
	 */
	LIVE_REWIND_HALF_SPEED_KEY,

	/**
	 * Modifier key held together with the rewind key for double-speed rewind.
	 * The mirrored left/right variant of a modifier key also counts.
	 */
	LIVE_REWIND_DOUBLE_SPEED_KEY,

	/**
	 * Whether live rewind continues with a short decelerating tape-coast after key release.
	 */
	LIVE_REWIND_TAPE_COAST_ENABLED,

	/**
	 * Per-tick live rewind speed increase when tape-coast policy is enabled.
	 */
	LIVE_REWIND_TAPE_COAST_ACCELERATION,

	/**
	 * Per-tick live rewind speed decrease after release when tape-coast policy is enabled.
	 */
	LIVE_REWIND_TAPE_COAST_DECELERATION,

	/**
	 * Maximum live rewind steps per tick when tape-coast policy is enabled.
	 */
	LIVE_REWIND_TAPE_COAST_MAX_STEPS,

	/**
	 * Minimum live rewind steps per tick when tape-coast policy is enabled.
	 * Values below 1.0 produce slow-motion rewind: the speed controller's
	 * fractional accumulator stretches a single physics step across multiple
	 * visual frames. The first held frame snaps speed to this value before
	 * acceleration ramps it up to {@link #LIVE_REWIND_TAPE_COAST_MAX_STEPS}.
	 */
	LIVE_REWIND_TAPE_COAST_MIN_STEPS,

	/**
	 * Whether the VHS picture-search shader effect renders while live rewind is active.
	 */
	LIVE_REWIND_VHS_EFFECT,

	/**
	 * Whether the VHS rewind effect includes the scrolling tear bands. When false,
	 * the remaining effects (jitter, chroma bleed, dropouts, head-switch strip,
	 * wobble) still render while live rewind is active.
	 */
	LIVE_REWIND_VHS_TEAR_BANDS,

	/**
	 * Seconds of live rewind keyframe and input history to retain. The actual
	 * retained window may be up to one keyframe interval longer so replay always
	 * has a complete keyframe-to-target input segment.
	 */
	REWIND_HISTORY_SECONDS,

	/**
	 * How the rewind audio PCM history ring is sized. Accepted values are
	 * {@code "time"} (cap by seconds, see {@link #REWIND_AUDIO_HISTORY_SECONDS})
	 * or {@code "size"} (cap by megabytes, see
	 * {@link #REWIND_AUDIO_HISTORY_SIZE_MB}). When the cap is exceeded the
	 * oldest audio frames are overwritten; held rewind beyond that point
	 * plays silence (or, with the audio-rewind feature branch installed,
	 * triggers the reverse resynthesizer).
	 */
	REWIND_AUDIO_HISTORY_LIMIT_TYPE,

	/**
	 * Seconds of stereo PCM history kept for held-rewind playback when
	 * {@link #REWIND_AUDIO_HISTORY_LIMIT_TYPE} is {@code "time"}.
	 */
	REWIND_AUDIO_HISTORY_SECONDS,

	/**
	 * Megabytes of stereo PCM history kept for held-rewind playback when
	 * {@link #REWIND_AUDIO_HISTORY_LIMIT_TYPE} is {@code "size"}. Stereo
	 * 16-bit at 48 kHz consumes ~192 KB per second, so 10 MB is roughly
	 * 54 seconds at that rate (~57 seconds at 44.1 kHz).
	 */
	REWIND_AUDIO_HISTORY_SIZE_MB,

	/**
	 * Key to teleport player to the last checkpoint (debug).
	 */
	DEBUG_LAST_CHECKPOINT_KEY,

	/**
	 * Key to open the Level Select screen (debug).
	 */
	LEVEL_SELECT_KEY,

	/**
	 * Whether to show the Title Screen on startup.
	 */
	TITLE_SCREEN_ON_STARTUP,

	/** Skip Sonic 2 title cards for mod zones until mod-supplied title art exists. */
	SKIP_MOD_ZONE_TITLE_CARDS,

	/**
	 * Whether to show the Level Select screen on startup instead of loading EHZ.
	 */
	LEVEL_SELECT_ON_STARTUP,

	/**
	 * Code of the sprite of the CPU-controlled sidekick character.
	 * Set to "tails" or "sonic" to spawn a sidekick, or empty string to disable.
	 */
	SIDEKICK_CHARACTER_CODE,

	/**
	 * Semicolon-separated list of extra player combinations for the data select screen.
	 * Each combo is a comma-separated list: main character first, then sidekicks.
	 * Example: "sonic,knuckles;knuckles,tails"
	 */
	DATA_SELECT_EXTRA_PLAYER_COMBOS,

	/**
	 * Filename for the Sonic 1 ROM.
	 */
	SONIC_1_ROM,

	/**
	 * Filename for the Sonic 2 ROM.
	 */
	SONIC_2_ROM,

	/**
	 * Filename for the Sonic 3&K ROM.
	 */
	SONIC_3K_ROM,

	/**
	 * If true, zone intro sequences (AIZ biplane, etc.) are skipped and
	 * gameplay-ready bootstrap data is used instead.
	 */
	S3K_SKIP_INTROS,

	/**
	 * Which game to load by default: "s1", "s2", or "s3k".
	 * Used to select which per-game ROM key (SONIC_1_ROM, etc.) to load.
	 */
	DEFAULT_ROM,

	/**
	 * Key to toggle Super Sonic debug mode at runtime (default: U).
	 * Only active when DEBUG_VIEW_ENABLED is true.
	 */
	SUPER_SONIC_DEBUG_KEY,

	/**
	 * Key to give all chaos emeralds (debug, default: E).
	 */
	GIVE_EMERALDS_KEY,

	/**
	 * Key to request the highest S3K transformation tier (debug, default: Shift+U).
	 */
	HYPER_FORM_DEBUG_KEY,

	/**
	 * Key to give all super emeralds (debug, default: Shift+E).
	 */
	GIVE_SUPER_EMERALDS_KEY,

	/**
	 * Whether to show the master title screen (game selection) on startup.
	 */
	MASTER_TITLE_SCREEN_ON_STARTUP,

	/**
	 * Whether to show the legal disclaimer screen on startup before the
	 * master title screen. Default true. Test harnesses set this false.
	 */
	SHOW_LEGAL_DISCLAIMER_ON_STARTUP,

	/**
	 * Whether to enable cross-game feature donation (e.g., S2 sprites in S1).
	 * When false (default), the base game runs unmodified.
	 */
	CROSS_GAME_FEATURES_ENABLED,

	/**
	 * Whether to force regeneration of the Sonic 1 data select image cache.
	 */
	CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE,

	/**
	 * Whether to force regeneration of the Sonic 2 data select image cache.
	 */
	CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE,

	/**
	 * Debug key to log the current camera position as an S1 data select preview override.
	 */
	CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY,

	/**
	 * Which game to use as the donor for cross-game features: "s2" or "s3k".
	 * Only used when CROSS_GAME_FEATURES_ENABLED is true.
	 */
	CROSS_GAME_SOURCE,

	/** Per-game launch profile for Sonic 1: launch.s1.rewind. */
	LAUNCH_S1_REWIND,
	/** Per-game launch profile for Sonic 1: launch.s1.crossGameSource. */
	LAUNCH_S1_CROSS_GAME_SOURCE,
	/** Per-game launch profile for Sonic 1: launch.s1.debugTools. */
	LAUNCH_S1_DEBUG_TOOLS,
	/** Per-game launch profile for Sonic 1: launch.s1.aspect. */
	LAUNCH_S1_ASPECT,
	/** Per-game launch profile for Sonic 1: launch.s1.mainCharacter. */
	LAUNCH_S1_MAIN_CHARACTER,
	/** Per-game launch profile for Sonic 1: launch.s1.sidekick. */
	LAUNCH_S1_SIDEKICK,

	/** Per-game launch profile for Sonic 2: launch.s2.rewind. */
	LAUNCH_S2_REWIND,
	/** Per-game launch profile for Sonic 2: launch.s2.crossGameSource. */
	LAUNCH_S2_CROSS_GAME_SOURCE,
	/** Per-game launch profile for Sonic 2: launch.s2.debugTools. */
	LAUNCH_S2_DEBUG_TOOLS,
	/** Per-game launch profile for Sonic 2: launch.s2.aspect. */
	LAUNCH_S2_ASPECT,
	/** Per-game launch profile for Sonic 2: launch.s2.mainCharacter. */
	LAUNCH_S2_MAIN_CHARACTER,
	/** Per-game launch profile for Sonic 2: launch.s2.sidekick. */
	LAUNCH_S2_SIDEKICK,

	/** Per-game launch profile for Sonic 3&K: launch.s3k.rewind. */
	LAUNCH_S3K_REWIND,
	/** Per-game launch profile for Sonic 3&K: launch.s3k.crossGameSource. */
	LAUNCH_S3K_CROSS_GAME_SOURCE,
	/** Per-game launch profile for Sonic 3&K: launch.s3k.debugTools. */
	LAUNCH_S3K_DEBUG_TOOLS,
	/** Per-game launch profile for Sonic 3&K: launch.s3k.aspect. */
	LAUNCH_S3K_ASPECT,
	/** Per-game launch profile for Sonic 3&K: launch.s3k.mainCharacter. */
	LAUNCH_S3K_MAIN_CHARACTER,
	/** Per-game launch profile for Sonic 3&K: launch.s3k.sidekick. */
	LAUNCH_S3K_SIDEKICK,

	/**
	 * When true, the master title screen becomes the Trace Test Mode
	 * picker (lists all traces under TRACE_CATALOG_DIR, plays the chosen
	 * one back inside the live engine). Dev-only. Default false.
	 */
	TEST_MODE_ENABLED,

	/**
	 * Directory scanned by TraceCatalog when TEST_MODE_ENABLED is true.
	 * Resolved against user.dir. Default "src/test/resources/traces".
	 */
	TRACE_CATALOG_DIR,

	/**
	 * Enables opt-in Discord Rich Presence updates through the desktop client.
	 */
	DISCORD_RICH_PRESENCE_ENABLED,

	/**
	 * Shows the level timer in Discord Rich Presence gameplay state text.
	 */
	DISCORD_RICH_PRESENCE_SHOW_TIMER,

	/**
	 * Shows the zone and act in Discord Rich Presence gameplay state text.
	 */
	DISCORD_RICH_PRESENCE_SHOW_ZONE;

}
