package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.openggf.configuration.KeyChord.Modifier.SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_1;

class TestConfigMigrationService {

    @Test
    void migrateConfig_convertsLegacyAwtArrowAndActionKeys() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.UP.name(), 38);
        config.put(SonicConfiguration.DOWN.name(), 40);
        config.put(SonicConfiguration.LEFT.name(), 37);
        config.put(SonicConfiguration.RIGHT.name(), 39);
        config.put(SonicConfiguration.P1_A.name(), 32);

        ConfigMigrationService service = new ConfigMigrationService();

        assertTrue(service.detectAwtKeyCodes(config));
        service.migrateConfig(config);

        assertEquals(265, config.get(SonicConfiguration.UP.name()));
        assertEquals(264, config.get(SonicConfiguration.DOWN.name()));
        assertEquals(263, config.get(SonicConfiguration.LEFT.name()));
        assertEquals(262, config.get(SonicConfiguration.RIGHT.name()));
        assertEquals(32, config.get(SonicConfiguration.P1_A.name()));
    }

    @Test
    void migrateDeprecatedJumpBindings_convertsNumericAwtValuesWhenSentinelArrowsAreAwt() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.UP.name(), 38);
        config.put(SonicConfiguration.DOWN.name(), 40);
        config.put(SonicConfiguration.LEFT.name(), 37);
        config.put(SonicConfiguration.RIGHT.name(), 39);
        config.put(SonicConfiguration.JUMP.name(), 32);
        config.put(SonicConfiguration.P2_JUMP.name(), 16);

        ConfigMigrationService service = new ConfigMigrationService();

        assertTrue(service.detectAwtKeyCodes(config));
        assertTrue(service.migrateDeprecatedJumpBindings(config));
        assertEquals(GLFW_KEY_SPACE, config.get(SonicConfiguration.P1_A.name()));
        assertEquals(GLFW_KEY_LEFT_SHIFT, config.get(SonicConfiguration.P2_A.name()));
    }

    @Test
    void migrateDeprecatedS1PreviewCoordLogKey_rewritesOldDefaultBinding() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY.name(), GLFW_KEY_WORLD_1);

        ConfigMigrationService service = new ConfigMigrationService();

        assertTrue(service.migrateDeprecatedS1PreviewCoordLogKey(config));
        assertEquals(GLFW_KEY_APOSTROPHE,
                config.get(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY.name()));

        config.put(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY.name(), GLFW_KEY_F8);
        assertTrue(service.migrateDeprecatedS1PreviewCoordLogKey(config));
        assertEquals(GLFW_KEY_APOSTROPHE,
                config.get(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY.name()));
    }

    @Test
    void migrateDeprecatedS1PreviewCoordLogKey_preservesCustomBinding() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY.name(), 83);

        ConfigMigrationService service = new ConfigMigrationService();

        assertFalse(service.migrateDeprecatedS1PreviewCoordLogKey(config));
        assertEquals(83, config.get(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY.name()));
    }

    @Test
    void migrateDeprecatedDisplayColorProfileToggleKey_rewritesUnreliableHashBindings() {
        Map<String, Object> config = new HashMap<>();
        ConfigMigrationService service = new ConfigMigrationService();

        config.put(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name(), GLFW_KEY_WORLD_1);
        assertTrue(service.migrateDeprecatedDisplayColorProfileToggleKey(config));
        assertEquals(GLFW_KEY_V, config.get(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name()));

        config.put(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name(), "WORLD_1");
        assertTrue(service.migrateDeprecatedDisplayColorProfileToggleKey(config));
        assertEquals("V", config.get(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name()));

        config.put(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name(), "#");
        assertTrue(service.migrateDeprecatedDisplayColorProfileToggleKey(config));
        assertEquals("V", config.get(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name()));
    }

    @Test
    void migrateDeprecatedDisplayColorProfileToggleKey_preservesCustomBinding() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name(), "G");

        ConfigMigrationService service = new ConfigMigrationService();

        assertFalse(service.migrateDeprecatedDisplayColorProfileToggleKey(config));
        assertEquals("G", config.get(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY.name()));
    }

    @Test
    void migrateDeprecatedCaptureToggleKey_rewritesEverySpellingOfTheSupersededDefault() {
        ConfigMigrationService service = new ConfigMigrationService();
        String key = SonicConfiguration.CAPTURE_TOGGLE_KEY.name();

        // "79" is the quoted-integer form CONFIGURATION.md documents as a valid
        // binding spelling; it resolves to a bare O exactly as the unquoted 79
        // does, so leaving it unmigrated leaves the reserved key bound.
        for (Object superseded : new Object[] {"O", "GLFW_KEY_O", "KEY_O", GLFW_KEY_O, "79", " o "}) {
            Map<String, Object> config = new HashMap<>();
            config.put(key, superseded);

            assertTrue(service.migrateDeprecatedCaptureToggleKey(config), String.valueOf(superseded));
            assertEquals("SHIFT+O", config.get(key));
        }
    }

    @Test
    void migrateDeprecatedCaptureToggleKey_leavesACustomisedBindingAlone() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.CAPTURE_TOGGLE_KEY.name(), "P");

        assertFalse(new ConfigMigrationService().migrateDeprecatedCaptureToggleKey(config));
        assertEquals("P", config.get(SonicConfiguration.CAPTURE_TOGGLE_KEY.name()));
    }

    @Test
    void migrateDeprecatedCaptureToggleKey_isIdempotent() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.CAPTURE_TOGGLE_KEY.name(), "SHIFT+O");

        assertFalse(new ConfigMigrationService().migrateDeprecatedCaptureToggleKey(config));
    }

    /**
     * The three cases above test the migration function; this one tests that
     * loadConfig actually calls it, which is the part a user would notice.
     * Without the wiring every existing install keeps its bare O, exact matching
     * rejects the held Shift, and Shift+O stops working with the suite green.
     */
    @Test
    void anExistingInstallCarryingTheSupersededDefaultIsMigratedOnLoad(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("config.yaml"), "capture:\n  toggleKey: O\n");

        SonicConfigurationService service = SonicConfigurationService.createStandalone(tempDir);

        assertEquals(KeyChord.of(GLFW_KEY_O, SHIFT),
                service.getKeyChord(SonicConfiguration.CAPTURE_TOGGLE_KEY));
        // The migrated value is the default, so the sparse file drops the key:
        // the superseded bare O is gone and the binding reads SHIFT+O.
        assertFalse(Files.readString(tempDir.resolve("config.yaml")).contains("toggleKey: O"),
                "the migration must be persisted, not re-applied on every launch");
    }

    /**
     * The quoted raw code is a documented binding form, so an install can carry
     * the superseded default spelled that way. Unmigrated it stays a bare O:
     * the reserved key the whole feature exists to keep free, and the key
     * {@code DebugOverlayToggle.OBJECT_DEBUG} also answers to unmodified.
     */
    @Test
    void anInstallSpellingTheSupersededDefaultAsAQuotedRawCodeIsMigratedOnLoad(
            @TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("config.yaml"), "capture:\n  toggleKey: \"79\"\n");

        SonicConfigurationService service = SonicConfigurationService.createStandalone(tempDir);

        assertEquals(KeyChord.of(GLFW_KEY_O, SHIFT),
                service.getKeyChord(SonicConfiguration.CAPTURE_TOGGLE_KEY));
    }
}
