package com.openggf.game.launch;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_1;
import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestLaunchProfileApplier {

    @TempDir
    Path tempDir;

    @Test
    void applySetsAlwaysManagedSessionOverrides() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        LaunchProfile profile = new LaunchProfile(true, "s2", true, "global", "tails", "sonic");

        new LaunchProfileApplier(config).apply(profile, SONIC_1);

        assertTrue(config.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
        assertTrue(config.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        assertEquals("s2", config.getString(SonicConfiguration.CROSS_GAME_SOURCE));
        assertTrue(config.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED));
        assertEquals("tails", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("sonic", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
    }

    @Test
    void applyClampsCharactersUnavailableFromLaunchDonor() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        LaunchProfile invalid = new LaunchProfile(false, "s2", false, "global", "knuckles", "knuckles");

        new LaunchProfileApplier(config).apply(invalid, SONIC_1);

        assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
    }

    @Test
    void donationOffOverridesRuleAndDefaultSource() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CROSS_GAME_SOURCE, "s3k");

        new LaunchProfileApplier(config).apply(
                new LaunchProfile(false, "off", false, "global", "sonic", "none"), SONIC_1);

        assertFalse(config.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        assertEquals(config.getDefaultValue(SonicConfiguration.CROSS_GAME_SOURCE),
                config.getString(SonicConfiguration.CROSS_GAME_SOURCE));
    }

    @Test
    void sidekickNoneMapsToRuntimeDisableConvention() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        new LaunchProfileApplier(config).apply(
                new LaunchProfile(false, "off", false, "global", "sonic", "none"), SONIC_1);

        assertEquals("", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
    }

    @Test
    void s3kDonationLeavesCharacterSelectionToDataSelect() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "knuckles");

        new LaunchProfileApplier(config).apply(
                new LaunchProfile(false, "s3k", false, "global", "sonic", "none"), SONIC_2);

        assertFalse(config.hasSessionOverride(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertFalse(config.hasSessionOverride(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertEquals("tails", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("knuckles", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
    }

    @Test
    void globalAspectCreatesNoDisplayAspectOverride() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        new LaunchProfileApplier(config).apply(
                new LaunchProfile(false, "off", false, "global", "sonic", "tails"), SONIC_2);

        assertFalse(config.hasSessionOverride(SonicConfiguration.DISPLAY_ASPECT));
    }

    @Test
    void pinnedAspectCreatesOverrideAndResolvesWidescreenDimensions() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.resolveDisplayAspect();

        new LaunchProfileApplier(config).apply(
                new LaunchProfile(false, "off", false, "WIDE_16_9", "sonic", "tails"), SONIC_2);

        assertTrue(config.hasSessionOverride(SonicConfiguration.DISPLAY_ASPECT));
        assertEquals("WIDE_16_9", config.getString(SonicConfiguration.DISPLAY_ASPECT));

        config.resolveDisplayAspect();
        assertEquals(400, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(800, config.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(448, config.getInt(SonicConfiguration.SCREEN_HEIGHT));

        config.clearSessionOverrides();
        config.resolveDisplayAspect();
        assertEquals(320, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(640, config.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(448, config.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }

    @Test
    void pinnedAspectStillResolvesNativeInTestMode() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);

        new LaunchProfileApplier(config).apply(
                new LaunchProfile(false, "off", false, "ULTRA_21_9", "sonic", "tails"), SONIC_2);
        config.resolveDisplayAspect();

        assertEquals(320, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
    }

    @Test
    void applyDoesNotPersistOrClearSessionOverrides() {
        SonicConfigurationService config = spy(SonicConfigurationService.createStandalone(tempDir));
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        config.setSessionOverride(SonicConfiguration.FPS, 30);

        new LaunchProfileApplier(config).apply(
                new LaunchProfile(true, "s3k", true, "WIDE_16_9", "knuckles", "none"), SONIC_2);

        verify(config, never()).saveConfig();
        assertEquals(30, config.getInt(SonicConfiguration.FPS));

        config.saveConfig();
        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(tempDir);
        assertEquals("sonic", reloaded.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("tails", reloaded.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertFalse(reloaded.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
    }
}
