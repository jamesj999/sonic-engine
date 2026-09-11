package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigCatalog {

    @Test
    void everyConstantExceptVersionHasMeta() {
        for (SonicConfiguration key : SonicConfiguration.values()) {
            if (key == SonicConfiguration.VERSION) {
                continue;
            }
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            assertNotNull(m, "missing catalog meta for " + key);
            assertNotNull(m.type(), "missing type for " + key);
            assertNotNull(m.description(), "missing description for " + key);
            assertFalse(m.description().isBlank(), "blank description for " + key);
        }
    }

    @Test
    void persistedKeysHaveSectionAndLeaf() {
        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            assertTrue(m.persisted(), "emitOrder must contain only persisted keys: " + key);
            assertNotNull(m.section(), "missing section for " + key);
            assertNotNull(m.leaf(), "missing leaf for " + key);
        }
    }

    @Test
    void enumTypedKeysHaveAllowedValues() {
        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            if (m.type() == ConfigType.ENUM) {
                assertFalse(m.allowedValues().isEmpty(), "ENUM key without allowedValues: " + key);
            }
        }
    }

    @Test
    void derivedKeysAreNotInEmitOrder() {
        assertFalse(ConfigCatalog.emitOrder().contains(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertFalse(ConfigCatalog.emitOrder().contains(SonicConfiguration.SCREEN_HEIGHT_PIXELS));
        assertFalse(ConfigCatalog.meta(SonicConfiguration.SCREEN_WIDTH_PIXELS).persisted());
    }

    @Test
    void debugSectionsAreContiguousAndLast() {
        List<SonicConfiguration> order = ConfigCatalog.emitOrder();
        boolean seenDebug = false;
        for (SonicConfiguration key : order) {
            boolean isDebug = ConfigCatalog.meta(key).section().startsWith("debug");
            if (isDebug) {
                seenDebug = true;
            } else {
                assertFalse(seenDebug, "normal key " + key + " appears after a debug key");
            }
        }
        assertTrue(seenDebug, "expected at least one debug.* key");
    }

    @Test
    void reverseLookupRoundTrips() {
        SonicConfiguration key = ConfigCatalog.byPath("input.player1.a");
        assertEquals(SonicConfiguration.P1_A, key);
        assertEquals(SonicConfiguration.JUMP, ConfigCatalog.byPath("input.player1.jump"),
                "deprecated JUMP path must still flatten for migration");
        assertEquals(SonicConfiguration.P2_JUMP, ConfigCatalog.byPath("input.player2.jump"),
                "deprecated P2_JUMP path must still flatten for migration");
        assertFalse(ConfigCatalog.emitOrder().contains(SonicConfiguration.JUMP),
                "deprecated JUMP key must not be emitted");
        assertFalse(ConfigCatalog.emitOrder().contains(SonicConfiguration.P2_JUMP),
                "deprecated P2_JUMP key must not be emitted");
        assertEquals(SonicConfiguration.AUDIO_ENABLED, ConfigCatalog.byPath("audio.enabled"));
        assertNull(ConfigCatalog.byPath("audio"), "a section-only path must not resolve to a key");
        assertNull(ConfigCatalog.byPath("nope.not.real"));
    }

    @Test
    void launchProfileKeysHaveCatalogMetadataAndDefaults(@TempDir Path tempDir) {
        SonicConfiguration[] keys = launchKeys();

        for (SonicConfiguration key : keys) {
            ConfigKeyMeta meta = ConfigCatalog.meta(key);
            assertNotNull(meta, "missing launch metadata for " + key);
            assertTrue(meta.persisted(), "launch keys must be persisted: " + key);
            assertTrue(meta.section().startsWith("launch."), "launch key in wrong section: " + key);
            assertTrue(ConfigCatalog.emitOrder().contains(key), "launch key missing from emit order: " + key);
        }

        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S1_REWIND, false);
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S1_CROSS_GAME_SOURCE, "off");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S1_DEBUG_TOOLS, false);
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S1_ASPECT, "global");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S1_MAIN_CHARACTER, "sonic");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S1_SIDEKICK, "none");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S2_REWIND, false);
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S2_CROSS_GAME_SOURCE, "off");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S2_DEBUG_TOOLS, false);
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S2_ASPECT, "global");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S2_MAIN_CHARACTER, "sonic");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S2_SIDEKICK, "tails");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S3K_REWIND, false);
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S3K_CROSS_GAME_SOURCE, "off");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S3K_DEBUG_TOOLS, false);
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S3K_ASPECT, "global");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S3K_MAIN_CHARACTER, "sonic");
        assertLaunchDefault(cfg, SonicConfiguration.LAUNCH_S3K_SIDEKICK, "tails");
    }

    @Test
    void launchProfileKeysAreOrderedAfterCrossGameAndBeforeDebug() {
        List<SonicConfiguration> order = ConfigCatalog.emitOrder();
        int lastCrossGame = -1;
        int firstLaunch = Integer.MAX_VALUE;
        int lastLaunch = -1;
        int firstDebug = Integer.MAX_VALUE;

        for (int i = 0; i < order.size(); i++) {
            String section = ConfigCatalog.meta(order.get(i)).section();
            if (section.equals("crossGame")) {
                lastCrossGame = i;
            }
            if (section.startsWith("launch.")) {
                firstLaunch = Math.min(firstLaunch, i);
                lastLaunch = i;
            }
            if (section.startsWith("debug.")) {
                firstDebug = Math.min(firstDebug, i);
            }
        }

        assertTrue(lastCrossGame >= 0, "expected crossGame section");
        assertTrue(firstLaunch < Integer.MAX_VALUE, "expected launch section");
        assertTrue(firstDebug < Integer.MAX_VALUE, "expected debug section");
        assertTrue(lastCrossGame < firstLaunch, "launch keys must follow crossGame keys");
        assertTrue(lastLaunch < firstDebug, "launch keys must precede debug keys");
    }

    @Test
    void launchProfileYamlEmitsAfterCrossGameAndBeforeDebug(@TempDir Path tempDir) throws Exception {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        // The file lists only changed settings, so give each section one.
        cfg.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        cfg.setConfigValue(SonicConfiguration.LAUNCH_S1_REWIND,
                cfg.getDefaultValue(SonicConfiguration.LAUNCH_S1_REWIND));
        cfg.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        cfg.saveConfig();

        String yaml = Files.readString(tempDir.resolve("config.yaml"));
        int crossGame = yaml.indexOf("\ncrossGame:");
        int launch = yaml.indexOf("\nlaunch:");
        int debugFence = yaml.indexOf("\n# ════════════════════════════════════════════");

        assertTrue(crossGame >= 0, yaml);
        assertTrue(launch > crossGame, yaml);
        assertTrue(debugFence > launch, yaml);
    }

    @Test
    void launchProfileEnumKeysHaveExactAllowedValues() {
        Set<String> aspectValues = Set.of("global", "NATIVE_4_3", "WIDE_16_10", "WIDE_16_9",
                "ULTRA_21_9", "SUPER_32_9");
        Set<String> crossGameValues = Set.of("off", "s1", "s2", "s3k");
        Set<String> mainValues = Set.of("sonic", "tails", "knuckles");
        Set<String> sidekickValues = Set.of("none", "sonic", "tails", "knuckles");

        assertLaunchEnum("aspect", aspectValues,
                SonicConfiguration.LAUNCH_S1_ASPECT,
                SonicConfiguration.LAUNCH_S2_ASPECT,
                SonicConfiguration.LAUNCH_S3K_ASPECT);
        assertLaunchEnum("crossGameSource", crossGameValues,
                SonicConfiguration.LAUNCH_S1_CROSS_GAME_SOURCE,
                SonicConfiguration.LAUNCH_S2_CROSS_GAME_SOURCE,
                SonicConfiguration.LAUNCH_S3K_CROSS_GAME_SOURCE);
        assertLaunchEnum("mainCharacter", mainValues,
                SonicConfiguration.LAUNCH_S1_MAIN_CHARACTER,
                SonicConfiguration.LAUNCH_S2_MAIN_CHARACTER,
                SonicConfiguration.LAUNCH_S3K_MAIN_CHARACTER);
        assertLaunchEnum("sidekick", sidekickValues,
                SonicConfiguration.LAUNCH_S1_SIDEKICK,
                SonicConfiguration.LAUNCH_S2_SIDEKICK,
                SonicConfiguration.LAUNCH_S3K_SIDEKICK);
    }

    private static void assertLaunchEnum(String leaf, Set<String> allowedValues, SonicConfiguration... keys) {
        for (SonicConfiguration key : keys) {
            ConfigKeyMeta meta = ConfigCatalog.meta(key);
            assertEquals(ConfigType.ENUM, meta.type(), key + " must be ENUM");
            assertEquals(leaf, meta.leaf(), key + " uses unexpected leaf name");
            assertEquals(allowedValues, meta.allowedValues(), key + " allowed values");
        }
    }

    private static void assertLaunchDefault(SonicConfigurationService cfg, SonicConfiguration key, Object expected) {
        assertEquals(expected, cfg.getDefaultValue(key), "default for " + key);
        assertEquals(expected.toString(), cfg.getString(key), "resolved default for " + key);
    }

    private static SonicConfiguration[] launchKeys() {
        return new SonicConfiguration[] {
                SonicConfiguration.LAUNCH_S1_REWIND,
                SonicConfiguration.LAUNCH_S1_CROSS_GAME_SOURCE,
                SonicConfiguration.LAUNCH_S1_DEBUG_TOOLS,
                SonicConfiguration.LAUNCH_S1_ASPECT,
                SonicConfiguration.LAUNCH_S1_MAIN_CHARACTER,
                SonicConfiguration.LAUNCH_S1_SIDEKICK,
                SonicConfiguration.LAUNCH_S2_REWIND,
                SonicConfiguration.LAUNCH_S2_CROSS_GAME_SOURCE,
                SonicConfiguration.LAUNCH_S2_DEBUG_TOOLS,
                SonicConfiguration.LAUNCH_S2_ASPECT,
                SonicConfiguration.LAUNCH_S2_MAIN_CHARACTER,
                SonicConfiguration.LAUNCH_S2_SIDEKICK,
                SonicConfiguration.LAUNCH_S3K_REWIND,
                SonicConfiguration.LAUNCH_S3K_CROSS_GAME_SOURCE,
                SonicConfiguration.LAUNCH_S3K_DEBUG_TOOLS,
                SonicConfiguration.LAUNCH_S3K_ASPECT,
                SonicConfiguration.LAUNCH_S3K_MAIN_CHARACTER,
                SonicConfiguration.LAUNCH_S3K_SIDEKICK
        };
    }
}
