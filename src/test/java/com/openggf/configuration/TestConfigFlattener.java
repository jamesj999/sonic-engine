package com.openggf.configuration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigFlattener {

    private Map<String, Object> nested() {
        Map<String, Object> player1 = new LinkedHashMap<>();
        player1.put("a", "SPACE");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("player1", player1);
        input.put("pause", "ENTER");
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("enabled", Boolean.TRUE);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("input", input);
        root.put("audio", audio);
        return root;
    }

    @Test
    void flattensKnownLeavesToEnumNames() {
        ConfigFlattener.Result r = ConfigFlattener.flatten(nested());
        assertEquals("SPACE", r.flat().get("P1_A"));
        assertEquals("ENTER", r.flat().get("PAUSE_KEY"));
        assertEquals(Boolean.TRUE, r.flat().get("AUDIO_ENABLED"));
        assertTrue(r.unknownKeys().isEmpty());
    }

    @Test
    void collectsUnknownLeaves() {
        Map<String, Object> root = nested();
        @SuppressWarnings("unchecked")
        Map<String, Object> audio = (Map<String, Object>) root.get("audio");
        audio.put("bogusKey", 1);
        ConfigFlattener.Result r = ConfigFlattener.flatten(root);
        assertTrue(r.unknownKeys().contains("audio.bogusKey"));
        assertFalse(r.flat().containsKey("bogusKey"));
    }

    @Test
    void emptyOrNullInputYieldsEmptyResult() {
        assertTrue(ConfigFlattener.flatten(null).flat().isEmpty());
        assertTrue(ConfigFlattener.flatten(Map.of()).flat().isEmpty());
    }
}
