package com.openggf.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.lwjgl.glfw.GLFW.*;

class TestConfigKeyNameResolution {

    @TempDir
    Path tempDir;
    private SonicConfigurationService configService;

    @BeforeEach
    void setUp() {
        configService = SonicConfigurationService.createStandalone(tempDir);
    }

    @Test
    void getInt_numericInteger_returnsSameValue() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, 81);
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_numericString_parsesAsInt() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "81");
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_keyNameString_resolvesViaGlfwKeyNameResolver() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "Q");
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_keyNameCaseInsensitive() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "space");
        assertEquals(GLFW_KEY_SPACE, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_keyNameWithGlfwPrefix() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "GLFW_KEY_D");
        assertEquals(GLFW_KEY_D, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_digitKeyNameResolvesToNumberRowKeyBeforeRawIntegerParsing() {
        configService.setConfigValue(SonicConfiguration.JUMP, "1");
        assertEquals(GLFW_KEY_1, configService.getInt(SonicConfiguration.JUMP));
    }

    @Test
    void getInt_reResolvesAfterConfigValueChanges() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "Q");
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));

        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "D");
        assertEquals(GLFW_KEY_D, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_invalidString_fallsBackToDefault() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "banana");
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_invalidString_nonKeyConfig_returnsDefault() {
        configService.setConfigValue(SonicConfiguration.DEBUG_MODE_KEY, "banana");
        assertEquals(GLFW_KEY_D, configService.getInt(SonicConfiguration.DEBUG_MODE_KEY));
    }

    @Test
    void defaults_storeReadableKeyNames() {
        assertEquals("Q", configService.getDefaultValue(SonicConfiguration.FRAME_STEP_KEY));
        assertEquals("D", configService.getDefaultValue(SonicConfiguration.DEBUG_MODE_KEY));
        assertEquals("UP", configService.getDefaultValue(SonicConfiguration.UP));
    }

    @Test
    void defaults_includeDisplayColorProfileSettings() {
        assertEquals("RAW_RGB", configService.getDefaultValue(SonicConfiguration.DISPLAY_COLOR_PROFILE));
        assertEquals("V", configService.getDefaultValue(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY));
    }

    @Test
    void defaults_doNotBindPauseAndPlayer2StartToSameKey() {
        assertNotEquals(
                configService.getInt(SonicConfiguration.PAUSE_KEY),
                configService.getInt(SonicConfiguration.P2_START),
                "P2 Start must be independently usable without toggling engine pause");
    }

    @Test
    void displayFpsIsClampedToPositiveValue() {
        configService.setConfigValue(SonicConfiguration.FPS, 0);
        assertEquals(1, configService.getInt(SonicConfiguration.FPS));

        configService.setConfigValue(SonicConfiguration.FPS, -60);
        assertEquals(1, configService.getInt(SonicConfiguration.FPS));
    }
}
