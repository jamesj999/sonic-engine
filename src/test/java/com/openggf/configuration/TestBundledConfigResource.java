package com.openggf.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestBundledConfigResource {

    @Test
    void bundledYamlExistsAndFlattensCleanly() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/config.yaml")) {
            assertNotNull(is, "bundled /config.yaml must exist");
            ObjectMapper mapper = new YAMLMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = mapper.readValue(is, Map.class);
            ConfigFlattener.Result r = ConfigFlattener.flatten(nested);
            assertTrue(r.unknownKeys().isEmpty(), "bundled config has unknown keys: " + r.unknownKeys());
            assertTrue(r.flat().containsKey(SonicConfiguration.DEFAULT_ROM.name()));
            assertTrue(r.flat().containsKey(SonicConfiguration.START.name()),
                    "bundled config should expose Player 1 Start");
            assertTrue(r.flat().containsKey(SonicConfiguration.CAPTURE_OUTPUT_DIR.name()),
                    "bundled config should expose trace capture outputDir");
            assertTrue(r.flat().containsKey(SonicConfiguration.CAPTURE_SCALE.name()),
                    "bundled config should expose trace capture scale");
            assertTrue(r.flat().containsKey(SonicConfiguration.CAPTURE_FPS.name()),
                    "bundled config should expose trace capture fps");
            assertTrue(r.flat().containsKey(SonicConfiguration.CAPTURE_CODEC.name()),
                    "bundled config should expose trace capture codec");
            assertEquals(Boolean.TRUE, r.flat().get(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS.name()));
            assertEquals(Boolean.TRUE, r.flat().get(SonicConfiguration.TRACE_SHOW_GAME_HUD.name()));
            assertEquals(Boolean.FALSE, r.flat().get(SonicConfiguration.TRACE_SHOW_DEBUG_HUD.name()));
        }
    }

    @Test
    void legacyJsonResourceIsGone() {
        assertNull(getClass().getResourceAsStream("/config.json"),
                "bundled config.json should be replaced by config.yaml");
    }
}
