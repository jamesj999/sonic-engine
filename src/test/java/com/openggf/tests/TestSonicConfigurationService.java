package com.openggf.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE;

public class TestSonicConfigurationService {

    @TempDir
    Path tempDir;

    @Test
    public void testUpdateConfig() {
        SonicConfigurationService service = SonicConfigurationService.createStandalone(tempDir);

        // Save original value
        boolean originalDebug = service.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);

        // Update value
        service.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, !originalDebug);

        // Verify update in memory
        assertEquals(!originalDebug, service.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED));
    }

    @Test
    public void testSaveConfig() throws IOException {
        SonicConfigurationService service = SonicConfigurationService.createStandalone(tempDir);
        Path configPath = tempDir.resolve("config.yaml");

        service.saveConfig();

        assertTrue(Files.exists(configPath), "saveConfig should create config.yaml");
        assertTrue(Files.size(configPath) > 0, "config.yaml should not be empty");
    }

    @Test
    public void saveConfig_usesTempFileAndAtomicMove() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/openggf/configuration/SonicConfigurationService.java"));

        assertTrue(source.contains("Files.createTempFile"),
                "saveConfig must write config.yaml through a sibling temp file");
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"),
                "saveConfig must publish config.yaml with an atomic move");
        assertFalse(source.contains("Files.writeString(target.toPath()"),
                "saveConfig must not write config.yaml directly");
    }

    @Test
    public void testGetters() {
        SonicConfigurationService svc = SonicConfigurationService.createStandalone(tempDir);
        svc.resetToDefaults();
        assertEquals(640, svc.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(320, svc.getShort(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        // DEBUG_VIEW_ENABLED is environment-dependent; just verify it returns a value
        svc.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
        svc.getBoolean(SonicConfiguration.EDITOR_ENABLED);
        assertEquals(1.0, svc.getDouble(SonicConfiguration.SCALE), 0.001);
        // Per-game ROM defaults are always populated
        assertEquals("s2.gen",
                svc.getString(SonicConfiguration.SONIC_2_ROM));
        // DEFAULT_ROM is always populated (from config.yaml or applyDefaults)
        assertFalse(svc.getString(SonicConfiguration.DEFAULT_ROM).isEmpty());
        assertEquals("", svc.getString(SonicConfiguration.PLAYBACK_MOVIE_PATH));
        assertTrue(svc.getBoolean(SonicConfiguration.TITLE_SCREEN_ON_STARTUP));
        assertFalse(svc.getBoolean(SonicConfiguration.LEVEL_SELECT_ON_STARTUP));
        assertTrue(svc.getBoolean(SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP));
        assertEquals(GLFW_KEY_APOSTROPHE, svc.getInt(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY));
        assertFalse(svc.getBoolean(SonicConfiguration.CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE));
    }
}
