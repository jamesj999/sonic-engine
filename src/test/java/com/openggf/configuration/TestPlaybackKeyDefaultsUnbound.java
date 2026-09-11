package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;

class TestPlaybackKeyDefaultsUnbound {

    @Test
    void playbackKeysDefaultUnboundAndShaderCycleKeysDefaultBound(@TempDir Path tempDir) {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_TOGGLE_KEY));
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_LOAD_KEY));
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_PLAY_PAUSE_KEY));
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_STEP_BACK_KEY));
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_STEP_FORWARD_KEY));
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_JUMP_BACK_KEY));
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_JUMP_FORWARD_KEY));
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_FAST_RATE_KEY));
        assertEquals(-1, config.getInt(SonicConfiguration.PLAYBACK_RESET_TO_START_KEY));

        assertEquals("OFF", config.getString(SonicConfiguration.DISPLAY_SHADER_SELECTION));
        assertEquals(GLFW_KEY_RIGHT_BRACKET, config.getInt(SonicConfiguration.DISPLAY_SHADER_NEXT_KEY));
        assertEquals(GLFW_KEY_LEFT_BRACKET, config.getInt(SonicConfiguration.DISPLAY_SHADER_PREVIOUS_KEY));
        assertEquals(GLFW_KEY_BACKSLASH, config.getInt(SonicConfiguration.DISPLAY_SHADER_PICKER_KEY));
    }

    @Test
    void existingUnquotedOffShaderSelectionMigratesBackToOffString(@TempDir Path tempDir) throws IOException {
        Path configYaml = tempDir.resolve("config.yaml");
        Files.writeString(configYaml, """
                display:
                  shaderSelection: OFF
                """);

        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        assertEquals("OFF", config.getString(SonicConfiguration.DISPLAY_SHADER_SELECTION));
        // Normalised to the default, the key leaves the sparse file altogether.
        assertFalse(Files.readString(configYaml).contains("shaderSelection"));
    }
}
