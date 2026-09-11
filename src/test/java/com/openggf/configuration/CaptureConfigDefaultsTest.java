package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

import static com.openggf.configuration.KeyChord.Modifier.SHIFT;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;

class CaptureConfigDefaultsTest {

    @TempDir
    Path configDirectory;

    @Test
    void captureDefaults() {
        String previous = System.getProperty("openggf.test.diagnostics");
        System.clearProperty("openggf.test.diagnostics");
        try {
            SonicConfigurationService c =
                    SonicConfigurationService.createStandalone(configDirectory);
            assertEquals("target/trace-videos", c.getString(SonicConfiguration.CAPTURE_OUTPUT_DIR));
            assertEquals(4, c.getInt(SonicConfiguration.CAPTURE_SCALE));
            assertEquals(60, c.getInt(SonicConfiguration.CAPTURE_FPS));
            assertEquals("ffv1", c.getString(SonicConfiguration.CAPTURE_CODEC));
            assertEquals(GLFW_KEY_O, c.getInt(SonicConfiguration.CAPTURE_TOGGLE_KEY));
        } finally {
            if (previous == null) {
                System.clearProperty("openggf.test.diagnostics");
            } else {
                System.setProperty("openggf.test.diagnostics", previous);
            }
        }
    }

    @Test
    void resourceYamlDeclaresLiveCaptureToggleKey() {
        try (InputStream in = getClass().getResourceAsStream("/config.yaml")) {
            assertNotNull(in);
            Map<String, Object> yaml = new Yaml().load(in);
            ConfigFlattener.Result flattened = ConfigFlattener.flatten(yaml);
            assertEquals("SHIFT+O",
                    flattened.flat().get(SonicConfiguration.CAPTURE_TOGGLE_KEY.name()));
        } catch (Exception e) {
            fail(e);
        }
    }

    /**
     * The shortcut a fresh install answers to. {@code captureDefaults} above
     * asserts the same binding still yields its bare key through getInt, which
     * is what keeps the unconverted bindings' contract intact.
     */
    @Test
    void theDefaultBindingIsShiftO() {
        SonicConfigurationService c =
                SonicConfigurationService.createStandalone(configDirectory);

        assertEquals(KeyChord.of(GLFW_KEY_O, SHIFT),
                c.getKeyChord(SonicConfiguration.CAPTURE_TOGGLE_KEY));
    }
}
