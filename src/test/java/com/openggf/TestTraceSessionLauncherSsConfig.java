package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.TraceMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Validates per-game special-stage launch config helper for trace replay.
 */
class TestTraceSessionLauncherSsConfig {

    private SonicConfigurationService config;
    private Object savedMain;
    private Object savedSidekick;
    private Object savedCrossGame;
    private Object savedSkipIntros;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetPerTest();
        config = SonicConfigurationService.getInstance();
        savedMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        savedSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        savedCrossGame = config.getConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED);
        savedSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
    }

    @AfterEach
    void tearDown() {
        if (savedMain != null) {
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, savedMain);
        }
        if (savedSidekick != null) {
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, savedSidekick);
        }
        if (savedCrossGame != null) {
            config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, savedCrossGame);
        }
        if (savedSkipIntros != null) {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, savedSkipIntros);
        }
    }

    @Test
    void s2MetadataWithFreshLoadFalsePreservesPreChangeConfig() {
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        TraceMetadata meta = metadataWithTeam("s2", "tails", "knuckles");
        TraceSessionLauncher.applyPerGameSpecialStageConfig(config, meta, false);

        // S2 should apply team and cross-game settings.
        assertEquals("tails", config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("knuckles", config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertFalse((Boolean) config.getConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        // S3K_SKIP_INTROS should not be touched (remains true from setup).
        assertEquals(true, config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS));
    }

    @Test
    void s3kMetadataWithFreshLoadFalseDoesNotSetSkipIntros() {
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        TraceMetadata meta = metadataWithTeam("s3k", "sonic", "tails");
        TraceSessionLauncher.applyPerGameSpecialStageConfig(config, meta, false);

        // Team and cross-game settings are applied.
        assertEquals("sonic", config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("tails", config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertFalse((Boolean) config.getConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        // S3K_SKIP_INTROS should remain true (the fresh-load signal is false).
        assertEquals(true, config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS));
    }

    @Test
    void s3kMetadataWithFreshLoadTrueSetsSkipIntrosFalse() {
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        TraceMetadata meta = metadataWithTeam("s3k", "sonic", "tails");
        TraceSessionLauncher.applyPerGameSpecialStageConfig(config, meta, true);

        // Team and cross-game settings are applied.
        assertEquals("sonic", config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("tails", config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertFalse((Boolean) config.getConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        // S3K_SKIP_INTROS should be set to false (the fresh-load signal is true).
        assertEquals(false, config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS));
    }

    @Test
    void s3kMetadataWithFreshLoadFieldTrueSetsSkipIntrosFalse() {
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        TraceMetadata meta = metadataWithFreshLoad("s3k", "sonic", true, "tails");
        TraceSessionLauncher.prepareSpecialStageConfiguration(meta);

        // Team and cross-game settings are applied.
        assertEquals("sonic", config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("tails", config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertFalse((Boolean) config.getConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        // S3K_SKIP_INTROS should be set to false (the fresh-load field is true).
        assertEquals(false, config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS));
    }

    @Test
    void s3kMetadataWithFreshLoadFieldFalsePreservesConfig() {
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        TraceMetadata meta = metadataWithFreshLoad("s3k", "sonic", false, "tails");
        TraceSessionLauncher.prepareSpecialStageConfiguration(meta);

        // Team and cross-game settings are applied.
        assertEquals("sonic", config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("tails", config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertFalse((Boolean) config.getConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        // S3K_SKIP_INTROS should not be modified (the fresh-load field is false).
        assertEquals(true, config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS));
    }

    @Test
    void s3kMetadataWithAbsentFreshLoadPreservesConfig() {
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        TraceMetadata meta = metadataWithTeam("s3k", "sonic", "tails");
        TraceSessionLauncher.prepareSpecialStageConfiguration(meta);

        // Team and cross-game settings are applied.
        assertEquals("sonic", config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("tails", config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertFalse((Boolean) config.getConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        // S3K_SKIP_INTROS should not be modified (the fresh-load field is absent/null).
        assertEquals(true, config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS));
    }

    private TraceMetadata metadataWithTeam(String gameId, String mainChar, String... sidekicks) {
        return metadataWithFreshLoad(gameId, mainChar, null, sidekicks);
    }

    private TraceMetadata metadataWithFreshLoad(String gameId, String mainChar, Boolean freshLoad,
                                                 String... sidekicks) {
        return new TraceMetadata(
                gameId,
                "TEST",
                0,
                0,
                0,
                null,
                0,
                "0x0000",
                "0x0000",
                null,
                null,
                null,
                5,
                null,
                null,
                null,
                null /* aux_schema_extras */,
                null,
                null,
                null,
                null,
                null,
                null,
                mainChar,
                List.of(sidekicks),
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                freshLoad,
                null);
    }
}
