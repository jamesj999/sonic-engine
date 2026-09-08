package com.openggf.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * config.yaml holds only the settings the player set. Defaults live in
 * {@code SonicConfigurationService} and are documented by the bundled
 * template, so a default that changes in a later build reaches every install
 * that never touched the key, with nothing to bump and nothing to migrate.
 */
class TestSparseUserConfig {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @Test
    void onlyChangedSettingsArePersisted(@TempDir Path dir) throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(dir);
        config.setConfigValue(SonicConfiguration.FPS, 50);
        config.saveConfig();

        Map<String, Object> persisted = readFlat(dir.resolve("config.yaml"));
        assertEquals(Map.of(SonicConfiguration.FPS.name(), 50), persisted);

        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(dir);
        assertEquals(50, reloaded.getInt(SonicConfiguration.FPS));
        assertEquals(640, reloaded.getInt(SonicConfiguration.SCREEN_WIDTH));
    }

    @Test
    void aLegacyMaterialisedFileKeepsOnlyTheSettingsThatDifferFromDefaults(@TempDir Path dir)
            throws Exception {
        // The old template copied every default into the player's file, and
        // its loadTimeSimulation default was NONE at the time.
        Map<String, Object> nested = bundledNested();
        ((Map<String, Object>) nested.get("display")).put("fps", 50);
        ((Map<String, Object>) nested.get("gameplay")).put("loadTimeSimulation", "NONE");
        new YAMLMapper().writeValue(dir.resolve("config.yaml").toFile(), nested);

        SonicConfigurationService config = SonicConfigurationService.createStandalone(dir);

        assertEquals(50, config.getInt(SonicConfiguration.FPS), "a changed value survives");
        assertEquals("FAST", config.getString(SonicConfiguration.LOAD_TIME_SIMULATION),
                "a value still at its former default follows the new default");
        Map<String, Object> persisted = readFlat(dir.resolve("config.yaml"));
        assertEquals(Map.of(SonicConfiguration.FPS.name(), 50), persisted,
                "every materialised default is dropped on conversion");
        assertTrue(Files.readString(dir.resolve("config.yaml"))
                .contains(ConfigYamlWriter.FORMAT_KEY + ": " + ConfigYamlWriter.SPARSE_FORMAT));
    }

    @Test
    void aFormerDefaultSetDeliberatelyAfterConversionIsKept(@TempDir Path dir) throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(dir);
        config.ensureConfigFileExists();
        config.setConfigValue(SonicConfiguration.LOAD_TIME_SIMULATION, "NONE");
        config.saveConfig();

        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(dir);

        assertEquals("NONE", reloaded.getString(SonicConfiguration.LOAD_TIME_SIMULATION));
        assertEquals("NONE", readFlat(dir.resolve("config.yaml"))
                .get(SonicConfiguration.LOAD_TIME_SIMULATION.name()));
    }

    @Test
    void anInvalidEnumValueFallsBackToTheDefaultWithoutBeingRewritten(@TempDir Path dir)
            throws Exception {
        Map<String, Object> gameplay = new LinkedHashMap<>();
        gameplay.put("loadTimeSimulation", "BOGUS");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put(ConfigYamlWriter.FORMAT_KEY, ConfigYamlWriter.SPARSE_FORMAT);
        nested.put("gameplay", gameplay);
        new YAMLMapper().writeValue(dir.resolve("config.yaml").toFile(), nested);

        SonicConfigurationService config = SonicConfigurationService.createStandalone(dir);

        assertEquals("FAST", config.getString(SonicConfiguration.LOAD_TIME_SIMULATION));
    }

    /**
     * The bundled template is the documentation of the defaults, so its values
     * must be the defaults. This is the whole procedure for changing one:
     * change {@code putDefault} and the template together (and the
     * CONFIGURATION.md row); every install that never set the key follows.
     */
    @Test
    void theBundledTemplateDocumentsTheCodeDefaults(@TempDir Path dir) throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(dir);
        Map<String, Object> template = ConfigFlattener.flatten(bundledNested()).flat();

        List<String> drift = new ArrayList<>();
        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            Object registered = config.getDefaultValue(key);
            Object documented = template.get(key.name());
            if (registered == null || documented == null) {
                continue;
            }
            if (!ConfigMigrationService.sameValue(key, documented, registered)) {
                drift.add(key.name() + ": template=" + documented + " default=" + registered);
            }
        }
        assertTrue(drift.isEmpty(), "bundled config.yaml disagrees with putDefault: " + drift);
    }

    private static Map<String, Object> bundledNested() throws Exception {
        try (InputStream is = TestSparseUserConfig.class.getResourceAsStream("/config.yaml")) {
            assertNotNull(is, "bundled /config.yaml must exist");
            return new YAMLMapper().readValue(is, MAP_TYPE);
        }
    }

    private static Map<String, Object> readFlat(Path path) throws Exception {
        Map<String, Object> nested = new YAMLMapper().readValue(path.toFile(), MAP_TYPE);
        nested.remove(ConfigYamlWriter.FORMAT_KEY);
        return ConfigFlattener.flatten(nested).flat();
    }
}
