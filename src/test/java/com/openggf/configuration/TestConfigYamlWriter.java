package com.openggf.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigYamlWriter {

    private Map<String, Object> defaults() {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            Object v = switch (m.type()) {
                case BOOL -> Boolean.FALSE;
                case INT -> 0;
                case DOUBLE -> 0.0;
                case KEY -> "SPACE";
                default -> "x";
            };
            flat.put(key.name(), v);
        }
        // a value that must be quoted (spaces and punctuation)
        flat.put(SonicConfiguration.SONIC_2_ROM.name(), "custom rom path!.gen");
        flat.put(SonicConfiguration.PLAYBACK_MOVIE_PATH.name(), "");
        return flat;
    }

    @Test
    void emitsParseableYamlWithSectionsAndDebugFence() throws Exception {
        String yaml = new ConfigYamlWriter().write(defaults());
        assertTrue(yaml.contains("display:"), yaml);
        assertTrue(yaml.contains("# ── Display ──"), yaml);
        assertTrue(yaml.contains("\ndebug:"), yaml);
        assertTrue(yaml.contains("DEBUG"), yaml);
        ObjectMapper mapper = new YAMLMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(yaml, Map.class);
        ConfigFlattener.Result r = ConfigFlattener.flatten(parsed);
        assertTrue(r.unknownKeys().isEmpty(), "unexpected unknown keys: " + r.unknownKeys());
        assertEquals("custom rom path!.gen",
                r.flat().get(SonicConfiguration.SONIC_2_ROM.name()));
    }

    @Test
    void derivedKeysAreNeverEmitted() {
        String yaml = new ConfigYamlWriter().write(defaults());
        assertFalse(yaml.contains("SCREEN_WIDTH_PIXELS"));
        assertFalse(yaml.contains("pixelWidth"));
    }

    @Test
    void outputIsDeterministic() {
        ConfigYamlWriter w = new ConfigYamlWriter();
        assertEquals(w.write(defaults()), w.write(defaults()));
    }

    @Test
    void digitKeyNamesStayStringsAfterYamlRoundTrip() throws Exception {
        Map<String, Object> flat = defaults();
        flat.put(SonicConfiguration.P1_A.name(), 49);

        String yaml = new ConfigYamlWriter().write(flat);

        assertTrue(yaml.contains("a: \"1\""), yaml);
        ObjectMapper mapper = new YAMLMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(yaml, Map.class);
        ConfigFlattener.Result r = ConfigFlattener.flatten(parsed);
        assertEquals("1", r.flat().get(SonicConfiguration.P1_A.name()));
    }

    /**
     * A saved config must round-trip a chord, and in one canonical spelling, so
     * a value the player typed in any case or modifier order comes back the same.
     */
    @Test
    void aChordedKeyValueIsWrittenInCanonicalForm() {
        Map<String, Object> config = defaults();
        config.put(SonicConfiguration.CAPTURE_TOGGLE_KEY.name(), "shift+o");

        assertTrue(new ConfigYamlWriter().write(config).contains("toggleKey: SHIFT+O"));
    }
}
