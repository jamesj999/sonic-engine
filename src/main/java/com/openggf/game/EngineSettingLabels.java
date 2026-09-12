package com.openggf.game;

import com.openggf.configuration.ConfigCatalog;
import com.openggf.configuration.ConfigKeyMeta;
import com.openggf.configuration.ConfigType;
import com.openggf.configuration.SonicConfiguration;

import java.util.Locale;

/** Presentation names; persisted keys and values remain owned by ConfigCatalog. */
final class EngineSettingLabels {
    private EngineSettingLabels() { }

    static String label(SonicConfiguration key) {
        return switch (key) {
            case DISPLAY_ASPECT -> "Screen shape";
            case DISPLAY_WINDOW_AUTOSIZE -> "Automatic window size";
            case SCREEN_WIDTH -> "Window width";
            case SCREEN_HEIGHT -> "Window height";
            case SCALE -> "Debug viewer scale";
            case FPS -> "Game speed (frames per second)";
            case DISPLAY_SHADER_LIBRARY_ROOT -> "Shader library folder";
            case DISPLAY_SHADER_SELECTION -> "Display shader";
            case DISPLAY_SHADER_NEXT_KEY -> "Next shader shortcut";
            case DISPLAY_SHADER_PREVIOUS_KEY -> "Previous shader shortcut";
            case DISPLAY_SHADER_PICKER_KEY -> "Shader picker shortcut";
            case DISPLAY_SHADER_DEFAULT_PHASE -> "Shader rendering phase";
            case DISPLAY_COLOR_PROFILE -> "Colour profile";
            case DISPLAY_COLOR_PROFILE_TOGGLE_KEY -> "Colour profile shortcut";
            case WIDESCREEN_DEADZONE_MODE -> "Wide camera movement";
            case AUDIO_ENABLED -> "Music and sound effects";
            case AUDIO_FM_CORE -> "FM sound chip";
            case REGION -> "Audio region";
            case DAC_INTERPOLATE -> "Smooth sample playback";
            case AUDIO_INTERNAL_RATE_OUTPUT -> "Native sound chip sample rate";
            case FM6_DAC_OFF -> "FM channel 6 compatibility";
            case PAUSE_KEY -> "Engine pause shortcut";
            case CONTROLLER_ENABLED -> "Enable controllers";
            case CONTROLLER_DEADZONE -> "Controller stick deadzone";
            case CONTROLLER_PLAYER1 -> "Player 1 controller";
            case CONTROLLER_PLAYER2 -> "Player 2 controller";
            case SONIC_1_ROM -> "Sonic 1 ROM file";
            case SONIC_2_ROM -> "Sonic 2 ROM file";
            case SONIC_3K_ROM -> "Sonic 3 & Knuckles ROM file";
            case DEFAULT_ROM -> "Default game";
            case MAIN_CHARACTER_CODE -> "Main character";
            case SIDEKICK_CHARACTER_CODE -> "Companion character";
            case DATA_SELECT_EXTRA_PLAYER_COMBOS -> "Extra character teams";
            case MASTER_TITLE_SCREEN_ON_STARTUP -> "Start at the OpenGGF hub";
            case TITLE_SCREEN_ON_STARTUP -> "Show the original title screen";
            case SHOW_LEGAL_DISCLAIMER_ON_STARTUP -> "Show the startup disclaimer";
            case LEVEL_SELECT_ON_STARTUP -> "Start at level select";
            case LIVE_REWIND_ENABLED -> "Enable live rewind";
            case LOAD_TIME_SIMULATION -> "ROM loading timing";
            case CONFIG_PRESERVE_EXPLICIT_DEFAULTS -> "Keep explicit default settings";
            case TEST_MODE_ENABLED -> "Start in trace replay mode";
            case TRACE_CATALOG_DIR -> "Trace replay folder";
            case PLAYBACK_MOVIE_PATH -> "Playback movie file";
            case CAPTURE_OUTPUT_DIR -> "Recording output folder";
            case CAPTURE_FFMPEG_PASS1_ARGS -> "Encoder command arguments";
            case CAPTURE_FFMPEG_PASS2_ARGS -> "Audio mux command arguments";
            case TIME_ATTACK_NET_LAST_JOIN_ADDRESS -> "Last LAN host address";
            case TIME_ATTACK_NET_DISPLAY_NAME -> "Multiplayer display name";
            case TIME_ATTACK_NET_MASTER_URL -> "Internet room server URL";
            case TIME_ATTACK_NET_MASTER_TRUST_INSECURE -> "Allow unverified server TLS";
            case TIME_ATTACK_NET_HOST_PORT -> "LAN hosting port";
            default -> contextualLabel(ConfigCatalog.meta(key));
        };
    }

    private static String contextualLabel(ConfigKeyMeta meta) {
        String prefix = "";
        String section = meta.section();
        if (section.startsWith("input.player1")) prefix = "Player 1 ";
        else if (section.startsWith("input.player2")) prefix = "Player 2 ";
        else if (section.startsWith("launch.")) prefix = switch (section.substring(7)) {
            case "s1" -> "S1: ";
            case "s2" -> "S2: ";
            default -> "S3K: ";
        };
        else if (section.startsWith("discord")) prefix = "Discord: ";
        else if (section.startsWith("debug.playback")) prefix = "Playback: ";
        else if (section.startsWith("debug.trace")) prefix = "Trace: ";
        else if (section.startsWith("debug.crossGame")) prefix = "Cross-game: ";
        String words = words(meta.leaf());
        if (meta.type() == ConfigType.KEY && !words.toLowerCase(Locale.ROOT).contains("key")) words += " shortcut";
        return prefix + words;
    }

    static String value(SonicConfiguration key, String raw) {
        if (raw.isEmpty()) return ConfigCatalog.meta(key).type() == ConfigType.KEY ? "Unbound" : "(empty)";
        if (ConfigCatalog.meta(key).type() == ConfigType.BOOL) return Boolean.parseBoolean(raw) ? "On" : "Off";
        if (ConfigCatalog.meta(key).type() != ConfigType.ENUM) return raw;
        return switch (raw) {
            case "NATIVE_4_3" -> "Native 4:3";
            case "WIDE_16_10" -> "Wide 16:10";
            case "WIDE_16_9" -> "Wide 16:9";
            case "ULTRA_21_9" -> "Ultra-wide 21:9";
            case "SUPER_32_9" -> "Super-wide 32:9";
            case "global" -> "Use engine setting";
            case "s1" -> "Sonic 1";
            case "s2" -> "Sonic 2";
            case "s3k" -> "Sonic 3 & Knuckles";
            case "RAW_RGB" -> "Raw RGB";
            case "MD_ANALOG" -> "Mega Drive analogue";
            case "NTSC_SOFT" -> "Soft NTSC";
            case "CENTER_SCALED" -> "Native centre deadzone";
            case "PROPORTIONAL" -> "Scale with screen width";
            case "NTSC", "PAL", "SCENE", "PRESENTATION", "FINAL" -> raw;
            default -> words(raw);
        };
    }

    private static String words(String value) {
        String text = value.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return text;
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return text.replace("Fps", "FPS").replace(" fps", " FPS").replace("dac", "DAC")
                .replace("Dac", "DAC").replace("fm ", "FM ").replace("Ffmpeg", "FFmpeg")
                .replace("tls", "TLS").replace("Mb", "MB").replace(" mb", " MB")
                .replace("Hud", "HUD").replace(" hud", " HUD");
    }
}
