package com.openggf.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonicConfigurationFileBootstrap {
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @TempDir
    Path tempDir;

    /**
     * Reads a saved config.yaml (grouped/nested) back to a flat map using the same
     * flattening logic the service uses on load.
     */
    private static Map<String, Object> readFlatYaml(Path path) throws IOException {
        Map<String, Object> nested = YAML_MAPPER.readValue(path.toFile(), MAP_TYPE);
        return ConfigFlattener.flatten(nested).flat();
    }

    @Test
    void existingValuesAreKeptAndDefaultsAreNeverBackfilled() throws IOException {
        Path configPath = tempDir.resolve("config.yaml");

        // Write a minimal nested YAML: UP lives at input.player1.up
        Map<String, Object> player1 = new LinkedHashMap<>();
        player1.put("up", "W");
        Map<String, Object> inputSection = new LinkedHashMap<>();
        inputSection.put("player1", player1);
        Map<String, Object> sparseConfig = new LinkedHashMap<>();
        sparseConfig.put("input", inputSection);
        YAML_MAPPER.writeValue(configPath.toFile(), sparseConfig);

        SonicConfigurationService service = SonicConfigurationService.createStandalone(tempDir);

        Map<String, Object> persisted = readFlatYaml(configPath);
        assertEquals("W", persisted.get(SonicConfiguration.UP.name()),
                "Existing value should be preserved");
        assertFalse(persisted.containsKey(SonicConfiguration.SCREEN_WIDTH.name()),
                "defaults are never written into the player's file");
        assertFalse(persisted.containsKey(SonicConfiguration.DOWN.name()),
                "key binding defaults are never written into the player's file");
        assertFalse(persisted.containsKey(SonicConfiguration.DEFAULT_ROM.name()),
                "string defaults are never written into the player's file");
        assertEquals(640, service.getInt(SonicConfiguration.SCREEN_WIDTH),
                "an absent key reads its registered default");
        assertEquals("DOWN", service.getString(SonicConfiguration.DOWN));
        assertTrue(Files.readString(configPath).contains(
                ConfigYamlWriter.FORMAT_KEY + ": " + ConfigYamlWriter.SPARSE_FORMAT),
                "the converted file declares the sparse format");
    }

    @Test
    void ensureConfigFileExists_createsDefaultConfigWhenMissing() throws IOException {
        Path configPath = tempDir.resolve("config.yaml");

        SonicConfigurationService service = SonicConfigurationService.createStandalone(tempDir);

        assertFalse(Files.exists(configPath));

        service.ensureConfigFileExists();

        assertTrue(Files.exists(configPath), "First startup should materialize config.yaml");

        Map<String, Object> savedConfig = readFlatYaml(configPath);
        assertTrue(savedConfig.isEmpty(),
                "a fresh install writes no settings: every key reads its default");
        assertEquals(640, service.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertFalse(service.getString(SonicConfiguration.DEFAULT_ROM).isEmpty());
        assertTrue(Files.readString(configPath).contains(ConfigYamlWriter.FORMAT_KEY));
        // The registered defaults are what every unset key reads.
        assertEquals("Q", service.getString(SonicConfiguration.FRAME_STEP_KEY));
        assertEquals("", service.getString(SonicConfiguration.PLAYBACK_MOVIE_PATH));
        assertFalse(service.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
        assertFalse(service.getBoolean(SonicConfiguration.LIVE_REWIND_DETERMINISM_AUDIT));
        assertEquals("R", service.getString(SonicConfiguration.LIVE_REWIND_KEY));
        assertTrue(service.getBoolean(SonicConfiguration.TITLE_SCREEN_ON_STARTUP));
        assertFalse(service.getBoolean(SonicConfiguration.LEVEL_SELECT_ON_STARTUP));
        assertTrue(service.getBoolean(SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP));
        assertTrue(service.getBoolean(SonicConfiguration.SKIP_MOD_ZONE_TITLE_CARDS));
        assertFalse(savedConfig.containsKey(SonicConfiguration.SCREEN_WIDTH_PIXELS.name()),
                "derived width must not be persisted");
        assertFalse(service.getBoolean(SonicConfiguration.DISCORD_RICH_PRESENCE_ENABLED));
        assertTrue(service.getBoolean(SonicConfiguration.DISCORD_RICH_PRESENCE_SHOW_TIMER));
        assertTrue(service.getBoolean(SonicConfiguration.DISCORD_RICH_PRESENCE_SHOW_ZONE));
    }

    @Test
    void malformedConfigIsQuarantinedBeforeSavingDefaults() throws IOException {
        Path configPath = tempDir.resolve("config.yaml");
        Path corruptPath = tempDir.resolve("config.yaml.corrupt");
        String malformed = "debug: [";

        Files.writeString(configPath, malformed);

        SonicConfigurationService service = SonicConfigurationService.createStandalone(tempDir);
        service.saveConfig();

        assertTrue(Files.exists(corruptPath), "malformed config should be quarantined");
        assertEquals(malformed, Files.readString(corruptPath),
                "quarantine copy must preserve the unreadable config bytes");
        assertTrue(Files.exists(configPath), "saving should create a fresh config.yaml");
        assertTrue(readFlatYaml(configPath).isEmpty(), "a fresh file carries no settings");
        assertEquals("s2", service.getString(SonicConfiguration.DEFAULT_ROM));
    }

    @Test
    void transientConfigReadFailureLeavesExistingFileInPlace() throws IOException {
        Path configPath = tempDir.resolve("config.yaml");
        Files.writeString(configPath, "audio:\n  enabled: false\n");

        SonicConfigurationService service = SonicConfigurationService.createStandalone(tempDir, file -> {
            throw new IOException("sharing violation");
        });

        assertEquals("audio:\n  enabled: false\n", Files.readString(configPath));
        assertFalse(Files.exists(tempDir.resolve("config.yaml.corrupt")),
                "transient I/O must not quarantine config.yaml");
        assertTrue(service.getBoolean(SonicConfiguration.AUDIO_ENABLED),
                "service falls back to bundled defaults when the file is temporarily unreadable");
    }
}
