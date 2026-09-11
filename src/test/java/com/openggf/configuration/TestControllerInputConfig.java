package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;

class TestControllerInputConfig {

    @TempDir
    Path tempDir;

    @Test
    void defaultsKeepKeyboardBAndCUnbound() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        assertEquals(GLFW_KEY_SPACE, config.getInt(SonicConfiguration.P1_A));
        assertEquals(-1, config.getInt(SonicConfiguration.P1_B));
        assertEquals(-1, config.getInt(SonicConfiguration.P1_C));
        assertEquals(GLFW_KEY_RIGHT_SHIFT, config.getInt(SonicConfiguration.P2_A));
        assertEquals(-1, config.getInt(SonicConfiguration.P2_B));
        assertEquals(-1, config.getInt(SonicConfiguration.P2_C));
        assertEquals(GLFW_KEY_RIGHT_CONTROL, config.getInt(SonicConfiguration.P2_START));
        assertTrue(config.getBoolean(SonicConfiguration.CONTROLLER_ENABLED));
        assertEquals(0.35, config.getDouble(SonicConfiguration.CONTROLLER_DEADZONE), 0.0001);
        assertEquals("auto", config.getString(SonicConfiguration.CONTROLLER_PLAYER1));
        assertEquals("auto", config.getString(SonicConfiguration.CONTROLLER_PLAYER2));
    }

    @Test
    void configCatalogExposesControllerPaths() {
        assertSame(SonicConfiguration.CONTROLLER_ENABLED, ConfigCatalog.byPath("input.controller.enabled"));
        assertSame(SonicConfiguration.CONTROLLER_DEADZONE, ConfigCatalog.byPath("input.controller.deadzone"));
        assertSame(SonicConfiguration.CONTROLLER_PLAYER1, ConfigCatalog.byPath("input.controller.player1"));
        assertSame(SonicConfiguration.CONTROLLER_PLAYER2, ConfigCatalog.byPath("input.controller.player2"));
        assertSame(SonicConfiguration.P1_A, ConfigCatalog.byPath("input.player1.a"));
        assertSame(SonicConfiguration.P1_B, ConfigCatalog.byPath("input.player1.b"));
        assertSame(SonicConfiguration.P1_C, ConfigCatalog.byPath("input.player1.c"));
        assertSame(SonicConfiguration.P2_A, ConfigCatalog.byPath("input.player2.a"));
        assertSame(SonicConfiguration.P2_B, ConfigCatalog.byPath("input.player2.b"));
        assertSame(SonicConfiguration.P2_C, ConfigCatalog.byPath("input.player2.c"));
        assertEquals(ConfigType.ENUM, ConfigCatalog.meta(SonicConfiguration.CONTROLLER_PLAYER1).type());
        assertEquals(Set.of("auto", "none"),
                ConfigCatalog.meta(SonicConfiguration.CONTROLLER_PLAYER1).allowedValues());
        assertEquals(Set.of("auto", "none"),
                ConfigCatalog.meta(SonicConfiguration.CONTROLLER_PLAYER2).allowedValues());
    }

    @Test
    void deprecatedJumpKeysAliasToAWhenUnset() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        assertEquals(config.getInt(SonicConfiguration.P1_A), config.getInt(SonicConfiguration.JUMP));
        assertEquals(config.getInt(SonicConfiguration.P2_A), config.getInt(SonicConfiguration.P2_JUMP));
    }

    @Test
    void explicitDeprecatedJumpValueStillWins() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), "ignored: true\n");
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir, file -> {
            Map<String, Object> flat = new HashMap<>();
            flat.put(SonicConfiguration.JUMP.name(), "Z");
            flat.put(SonicConfiguration.P1_A.name(), "SPACE");
            return flat;
        });

        assertEquals(GLFW_KEY_Z, config.getInt(SonicConfiguration.JUMP));
        assertEquals(GLFW_KEY_SPACE, config.getInt(SonicConfiguration.P1_A));
    }

    @Test
    void oldNestedJumpYamlMigratesToAAndRewritesWithoutJump() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), """
                input:
                  player1:
                    jump: Z
                  player2:
                    jump: LEFT_SHIFT
                """);

        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        assertEquals(GLFW_KEY_Z, config.getInt(SonicConfiguration.P1_A));
        assertEquals(GLFW_KEY_LEFT_SHIFT, config.getInt(SonicConfiguration.P2_A));
        String saved = Files.readString(tempDir.resolve("config.yaml"));
        assertTrue(saved.contains("a: Z"), saved);
        assertFalse(saved.contains("jump:"), saved);
    }

    @Test
    void oldJumpKeysMigrateToAWhenNewAIsMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.JUMP.name(), "Z");
        config.put(SonicConfiguration.P2_JUMP.name(), "RIGHT_SHIFT");

        assertTrue(new ConfigMigrationService().migrateDeprecatedJumpBindings(config));

        assertEquals("Z", config.get(SonicConfiguration.P1_A.name()));
        assertEquals("RIGHT_SHIFT", config.get(SonicConfiguration.P2_A.name()));
    }

    @Test
    void newAWinsWhenOldJumpAlsoExists() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.JUMP.name(), "Z");
        config.put(SonicConfiguration.P1_A.name(), "SPACE");

        assertFalse(new ConfigMigrationService().migrateDeprecatedJumpBindings(config));

        assertEquals("SPACE", config.get(SonicConfiguration.P1_A.name()));
    }
}
