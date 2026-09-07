package com.openggf.configuration;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_1;

/**
 * Service to detect and migrate AWT key codes to GLFW key codes in config files.
 * This handles backwards compatibility when users upgrade from the JOGL build
 * (which used AWT KeyEvent codes) to the LWJGL build (which uses GLFW codes).
 */
public class ConfigMigrationService {

    private static final Logger LOGGER = Logger.getLogger(ConfigMigrationService.class.getName());

    // AWT arrow key codes (used as detection sentinel)
    private static final int AWT_VK_LEFT = 37;
    private static final int AWT_VK_UP = 38;
    private static final int AWT_VK_RIGHT = 39;
    private static final int AWT_VK_DOWN = 40;

    // Key config properties that should be migrated
    private static final List<SonicConfiguration> KEY_CONFIGS = List.of(
        SonicConfiguration.UP,
        SonicConfiguration.DOWN,
        SonicConfiguration.LEFT,
        SonicConfiguration.RIGHT,
        SonicConfiguration.P1_A,
        SonicConfiguration.P1_B,
        SonicConfiguration.P1_C,
        SonicConfiguration.P2_UP,
        SonicConfiguration.P2_DOWN,
        SonicConfiguration.P2_LEFT,
        SonicConfiguration.P2_RIGHT,
        SonicConfiguration.P2_A,
        SonicConfiguration.P2_B,
        SonicConfiguration.P2_C,
        SonicConfiguration.P2_START,
        SonicConfiguration.TEST,
        SonicConfiguration.NEXT_ACT,
        SonicConfiguration.NEXT_ZONE,
        SonicConfiguration.DEBUG_MODE_KEY,
        SonicConfiguration.SPECIAL_STAGE_KEY,
        SonicConfiguration.SPECIAL_STAGE_COMPLETE_KEY,
        SonicConfiguration.SPECIAL_STAGE_FAIL_KEY,
        SonicConfiguration.SPECIAL_STAGE_SPRITE_DEBUG_KEY,
        SonicConfiguration.SPECIAL_STAGE_PLANE_DEBUG_KEY,
        SonicConfiguration.PAUSE_KEY,
        SonicConfiguration.FRAME_STEP_KEY,
        SonicConfiguration.TRACE_REWIND_KEY,
        SonicConfiguration.LIVE_REWIND_KEY,
        SonicConfiguration.DEBUG_LAST_CHECKPOINT_KEY,
        SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY,
        SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY
    );

    /**
     * Detect whether the config file contains AWT key codes.
     * Uses the arrow keys as a sentinel: AWT uses 37-40, GLFW uses 262-265.
     *
     * @param config The config map to check
     * @return true if AWT key codes are detected
     */
    public boolean detectAwtKeyCodes(Map<String, Object> config) {
        Integer up = getIntValue(config, SonicConfiguration.UP.name());
        Integer down = getIntValue(config, SonicConfiguration.DOWN.name());
        Integer left = getIntValue(config, SonicConfiguration.LEFT.name());
        Integer right = getIntValue(config, SonicConfiguration.RIGHT.name());

        if (up == null || down == null || left == null || right == null) {
            return false; // Missing keys will use defaults
        }

        // AWT pattern: UP=38, DOWN=40, LEFT=37, RIGHT=39
        return up == AWT_VK_UP && down == AWT_VK_DOWN
            && left == AWT_VK_LEFT && right == AWT_VK_RIGHT;
    }

    /**
     * Migrate all key codes in the config from AWT to GLFW format.
     *
     * @param config The config map to migrate (modified in place)
     */
    public void migrateConfig(Map<String, Object> config) {
        LOGGER.info("[ConfigMigration] Migrating config from AWT to GLFW key codes...");
        int migrated = 0;

        for (SonicConfiguration keyConfig : KEY_CONFIGS) {
            Integer awtCode = getIntValue(config, keyConfig.name());
            if (awtCode != null) {
                int glfwCode = LegacyAwtKeyCodeMapper.toGlfw(awtCode);
                if (glfwCode != awtCode) {
                    config.put(keyConfig.name(), glfwCode);
                    LOGGER.info("[ConfigMigration] Migrated " + keyConfig.name() + ": " + awtCode + " -> " + glfwCode);
                    migrated++;
                }
            }
        }

        LOGGER.info("[ConfigMigration] Migrated " + migrated + " key bindings to GLFW codes");
    }

    /**
     * Migrates deprecated flat JUMP/P2_JUMP bindings to the logical A button
     * bindings. Existing P1_A/P2_A values are treated as user-authored and win.
     *
     * @param config The config map to migrate (modified in place)
     * @return true if any binding was copied
     */
    /**
     * Converts a legacy config.yaml that materialised every default into the
     * sparse user-settings format, once.
     *
     * <p>Older builds copied the bundled template into the player's file and
     * back-filled every registered default, so a value equal to the default
     * cannot be told from a choice. Such keys are dropped: reads fall through
     * to the same default, and a default that changes later then applies. The
     * only defaults that changed before this format existed are listed in
     * {@link #FORMER_DEFAULTS}; a key still holding its former default is
     * dropped the same way. The caller stamps the file with
     * {@link ConfigYamlWriter#FORMAT_KEY} so none of this runs again, which is
     * what lets a player set a former default deliberately afterwards.
     *
     * @return true if any key was dropped
     */
    public boolean convertMaterialisedDefaults(
            Map<String, Object> config, Map<String, Object> defaults) {
        if (config == null) {
            return false;
        }
        int dropped = 0;
        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            Object value = config.get(key.name());
            if (value == null) {
                continue;
            }
            Object current = defaults.get(key.name());
            String former = FORMER_DEFAULTS.get(key);
            if ((current != null && sameValue(key, value, current))
                    || (former != null && sameValue(key, value, former))) {
                config.remove(key.name());
                dropped++;
            }
        }
        LOGGER.info("[ConfigMigration] Converted config.yaml to the sparse format; dropped "
                + dropped + " materialised default(s)");
        return dropped > 0;
    }

    /**
     * Defaults that changed while config.yaml still materialised every value,
     * keyed by the value the old template wrote. Closed: defaults that change
     * after the sparse format need no entry here, because absent keys follow
     * the code default on their own.
     */
    static final Map<SonicConfiguration, String> FORMER_DEFAULTS = Map.of(
            SonicConfiguration.LOAD_TIME_SIMULATION, "NONE");

    /** Compares a persisted value with a default in the key's own type. */
    static boolean sameValue(SonicConfiguration key, Object value, Object reference) {
        ConfigKeyMeta meta = ConfigCatalog.meta(key);
        String a = String.valueOf(value).trim();
        String b = String.valueOf(reference).trim();
        return switch (meta.type()) {
            case BOOL -> Boolean.parseBoolean(a) == Boolean.parseBoolean(b);
            case INT, DOUBLE -> {
                try {
                    yield Double.parseDouble(a) == Double.parseDouble(b);
                } catch (NumberFormatException e) {
                    yield a.equals(b);
                }
            }
            case KEY -> KeyChord.parse(value).equals(KeyChord.parse(reference));
            case STRING, ENUM -> a.equals(b);
        };
    }

    public boolean migrateDeprecatedJumpBindings(Map<String, Object> config) {
        if (config == null) {
            return false;
        }
        boolean awtKeyCodes = detectAwtKeyCodes(config);
        boolean changed = false;
        changed |= migrateDeprecatedJumpBinding(config, SonicConfiguration.JUMP, SonicConfiguration.P1_A, awtKeyCodes);
        changed |= migrateDeprecatedJumpBinding(config, SonicConfiguration.P2_JUMP, SonicConfiguration.P2_A, awtKeyCodes);
        return changed;
    }

    private boolean migrateDeprecatedJumpBinding(
            Map<String, Object> config,
            SonicConfiguration oldKey,
            SonicConfiguration newKey,
            boolean awtKeyCodes) {
        String oldName = oldKey.name();
        String newName = newKey.name();
        if (!config.containsKey(oldName) || config.containsKey(newName)) {
            return false;
        }
        Object value = migratedDeprecatedJumpValue(config.get(oldName), awtKeyCodes);
        config.put(newName, value);
        LOGGER.info("[ConfigMigration] Migrated " + oldName + " -> " + newName + ": " + value);
        return true;
    }

    private Object migratedDeprecatedJumpValue(Object value, boolean awtKeyCodes) {
        if (awtKeyCodes && value instanceof Number number) {
            return LegacyAwtKeyCodeMapper.toGlfw(number.intValue());
        }
        return value;
    }

    /**
     * Migrates the S1 preview-coordinate log key off deprecated defaults.
     * Only rewrites the binding when it still matches an old generated default,
     * leaving any user-customized binding untouched.
     *
     * @param config The config map to migrate (modified in place)
     * @return true if the key binding was updated
     */
    public boolean migrateDeprecatedS1PreviewCoordLogKey(Map<String, Object> config) {
        Integer keyCode = getIntValue(config, SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY.name());
        if (keyCode == null) {
            return false;
        }
        if (keyCode != GLFW_KEY_WORLD_1 && keyCode != GLFW_KEY_F8) {
            return false;
        }
        config.put(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY.name(), GLFW_KEY_APOSTROPHE);
        LOGGER.info("[ConfigMigration] Migrated CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY: "
                + keyCode + " -> " + GLFW_KEY_APOSTROPHE);
        return true;
    }

    /**
     * Migrates the display color-profile toggle off the layout-dependent '#'
     * binding. GLFW reports that key differently across keyboard layouts, so the
     * default is now the plain V key.
     *
     * @param config The config map to migrate (modified in place)
     * @return true if the key binding was updated
     */
    public boolean migrateDeprecatedDisplayColorProfileToggleKey(Map<String, Object> config) {
        String key = SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name();
        Object value = config.get(key);
        if (value instanceof Number number) {
            int keyCode = number.intValue();
            if (keyCode != GLFW_KEY_WORLD_1) {
                return false;
            }
            config.put(key, GLFW_KEY_V);
            LOGGER.info("[ConfigMigration] Migrated DISPLAY_COLOR_PROFILE_TOGGLE_KEY: "
                    + keyCode + " -> " + GLFW_KEY_V);
            return true;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toUpperCase();
            if (!normalized.equals("#")
                    && !normalized.equals("WORLD_1")
                    && !normalized.equals("GLFW_KEY_WORLD_1")
                    && !normalized.equals("KEY_WORLD_1")) {
                return false;
            }
            config.put(key, "V");
            LOGGER.info("[ConfigMigration] Migrated DISPLAY_COLOR_PROFILE_TOGGLE_KEY: "
                    + text + " -> V");
            return true;
        }
        return false;
    }

    /**
     * Migrates the live-capture toggle onto the chord that spells out the Shift
     * the engine used to hardcode. Every install that has launched the engine has
     * a literal {@code capture.toggleKey: O} persisted, and that bare O wins over
     * the new default — exact modifier matching would then reject the held Shift,
     * so Shift+O would stop working and a stray O would start a recording.
     *
     * <p>Only the superseded default is rewritten. A binding the player chose is
     * left exactly as written, because inferring {@code SHIFT+<their key>} would
     * silently rewrite a value they chose.
     *
     * <p>The value is matched through {@link KeyChord#parse}, not against a list
     * of spellings: every documented binding form that resolves to an unmodified
     * O — {@code O}, {@code KEY_O}, {@code GLFW_KEY_O}, {@code 79} and the quoted
     * {@code "79"} — is the reserved key, and one missed spelling leaves it bound.
     *
     * @param config The config map to migrate (modified in place)
     * @return true if the key binding was updated
     */
    public boolean migrateDeprecatedCaptureToggleKey(Map<String, Object> config) {
        String key = SonicConfiguration.CAPTURE_TOGGLE_KEY.name();
        Object value = config.get(key);
        if (value instanceof Number number) {
            if (number.intValue() != GLFW_KEY_O) {
                return false;
            }
            config.put(key, "SHIFT+O");
            LOGGER.info("[ConfigMigration] Migrated CAPTURE_TOGGLE_KEY: "
                    + number.intValue() + " -> SHIFT+O");
            return true;
        }
        if (value instanceof String text) {
            KeyChord chord = KeyChord.parse(text);
            if (chord.keyCode() != GLFW_KEY_O || !chord.modifiers().isEmpty()) {
                return false;
            }
            config.put(key, "SHIFT+O");
            LOGGER.info("[ConfigMigration] Migrated CAPTURE_TOGGLE_KEY: " + text + " -> SHIFT+O");
            return true;
        }
        return false;
    }

    private Integer getIntValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }
}
