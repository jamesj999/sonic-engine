package com.openggf.game.launch;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.MasterTitleScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_1;
import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_2;
import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_3K;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLaunchProfileStore {

    @TempDir
    Path tempDir;

    @Test
    void profileRoundTripsForEachGame() {
        assertRoundTrip(SONIC_1, new LaunchProfile(true, "s2", true, "WIDE_16_10", "tails", "sonic"));
        assertRoundTrip(SONIC_2, new LaunchProfile(true, "s3k", true, "WIDE_16_9", "knuckles", "none"));
        assertRoundTrip(SONIC_3K, new LaunchProfile(true, "s1", true, "ULTRA_21_9", "sonic", "sonic"));
    }

    @Test
    void outOfSetValuesAreClampedByCatalogValidation() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), """
                launch:
                  s1:
                    aspect: CINEMASCOPE
                """);

        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        LaunchProfile loaded = new LaunchProfileStore(config).load(SONIC_1);

        assertEquals("global", loaded.aspect());
    }

    @Test
    void donorEqualToLaunchedGameClampsToOffOnLoad() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), """
                launch:
                  s2:
                    crossGameSource: s2
                """);

        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        LaunchProfile loaded = new LaunchProfileStore(config).load(SONIC_2);

        assertEquals("off", loaded.crossGameSource());
    }

    @Test
    void loadClampsCharactersUnavailableFromConfiguredDonor() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), """
                launch:
                  s1:
                    crossGameSource: s2
                    mainCharacter: knuckles
                    sidekick: knuckles
                """);

        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        LaunchProfile loaded = new LaunchProfileStore(config).load(SONIC_1);

        assertEquals("s2", loaded.crossGameSource());
        assertEquals("sonic", loaded.mainCharacter());
        assertEquals("none", loaded.sidekick());
    }

    @Test
    void saveClampsCharactersUnavailableFromConfiguredDonor() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        LaunchProfile invalid = new LaunchProfile(false, "s2", false, "global", "knuckles", "knuckles");

        new LaunchProfileStore(config).save(SONIC_1, invalid);

        assertEquals("s2", config.getString(SonicConfiguration.LAUNCH_S1_CROSS_GAME_SOURCE));
        assertEquals("sonic", config.getString(SonicConfiguration.LAUNCH_S1_MAIN_CHARACTER));
        assertEquals("none", config.getString(SonicConfiguration.LAUNCH_S1_SIDEKICK));
    }

    @Test
    void saveChangesOnlyLaunchPreferencesAndPreservesUnrelatedValues() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CROSS_GAME_SOURCE, "s3k");
        config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "SUPER_32_9");
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        new LaunchProfileStore(config).save(SONIC_1,
                new LaunchProfile(true, "s2", true, "WIDE_16_9", "tails", "none"));

        assertFalse(config.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
        assertEquals(true, config.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        assertEquals("s3k", config.getString(SonicConfiguration.CROSS_GAME_SOURCE));
        assertFalse(config.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED));
        assertEquals("SUPER_32_9", config.getString(SonicConfiguration.DISPLAY_ASPECT));
        assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("tails", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
    }

    @Test
    void failedSaveReportsErrorWithoutChangingAnyLiveProfileFieldsAndCanRetry() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        LaunchProfileStore store = new LaunchProfileStore(config);
        LaunchProfile original = store.load(SONIC_1);
        LaunchProfile edited = new LaunchProfile(true, "s2", true, "WIDE_16_9", "tails", "none");
        Path target = tempDir.resolve("config.yaml");
        Files.deleteIfExists(target);
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep"), "existing content");

        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> store.save(SONIC_1, edited));
        assertTrue(failure.getMessage().contains("launch profile"));
        assertEquals(original, store.load(SONIC_1));
        assertEquals("existing content", Files.readString(target.resolve("keep")));

        Files.delete(target.resolve("keep"));
        Files.delete(target);
        store.save(SONIC_1, edited);
        assertEquals(edited, store.load(SONIC_1));
        assertEquals(edited, new LaunchProfileStore(SonicConfigurationService.createStandalone(tempDir)).load(SONIC_1));
    }

    @Test
    void atomicSaveDoesNotPersistSessionOverrides() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "SUPER_32_9");
        LaunchProfile edited = new LaunchProfile(true, "s2", false, "WIDE_16_9", "tails", "none");
        new LaunchProfileStore(config).save(SONIC_1, edited);

        assertEquals("SUPER_32_9", config.getString(SonicConfiguration.DISPLAY_ASPECT));
        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(tempDir);
        assertEquals("NATIVE_4_3", reloaded.getString(SonicConfiguration.DISPLAY_ASPECT));
        assertEquals(edited, new LaunchProfileStore(reloaded).load(SONIC_1));
    }

    private void assertRoundTrip(MasterTitleScreen.GameEntry entry, LaunchProfile profile) {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        new LaunchProfileStore(config).save(entry, profile);

        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(tempDir);
        LaunchProfile loaded = new LaunchProfileStore(reloaded).load(entry);

        assertEquals(profile, loaded);
    }
}
