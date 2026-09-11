package com.openggf.game;

import com.openggf.tests.TestTempFiles;
import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestPowerUpGraphicsRegression {

    @AfterEach
    void tearDown() {
        CrossGameFeatureProvider.getInstance().resetState();
        GraphicsManager.getInstance().resetState();
        RomManager.getInstance().close();
        SessionManager.clear();
        GameModuleRegistry.reset();
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void invincibilityStarsKeepRendererWhenSpawnedFromPowerUpSpawner() throws Exception {
        Sonic player = loadSonic2Player(false);

        player.giveInvincibility();

        Object stars = player.getInvincibilityObject();
        assertNotNull(stars, "Invincibility should create a star object");
        assertNotNull(readField(stars, "renderer"),
                "Invincibility stars should resolve their renderer during power-up spawn");
    }

    @Test
    void crossGameS2LoadCreatesPersistentInstaShieldObject() throws Exception {
        Sonic player = loadSonic2Player(true);

        Object instaShield = player.getInstaShieldObject();
        assertNotNull(instaShield,
                "Sonic should have a persistent insta-shield object after cross-game S2 level load");
        assertNotNull(readField(instaShield, "dplcRenderer"),
                "Persistent insta-shield object should have donor art renderer ready");
    }

    @Test
    void crossGameInstaShieldRendererUsesDonorPaletteContext() throws Exception {
        Sonic player = loadSonic2Player(true);

        Object instaShield = player.getInstaShieldObject();
        assertNotNull(instaShield,
                "Sonic should have a persistent insta-shield object after cross-game S2 level load");
        Object renderer = readField(instaShield, "dplcRenderer");
        assertNotNull(renderer,
                "Persistent insta-shield object should have donor art renderer ready");

        assertSame(CrossGameFeatureProvider.getInstance().getDonorRenderContext(),
                readField(renderer, "renderContext"),
                "Donated insta-shield art must render against the S3K donor palette block");
    }

    @Test
    void sonic2RomLookupHonorsConfiguredMavenPropertyPath() throws Exception {
        Path configuredRom = TestTempFiles.createTempFile("openggf-configured-sonic2", ".gen");
        String previous = System.getProperty("sonic2.rom.path");
        System.setProperty("sonic2.rom.path", configuredRom.toString());
        try {
            assertEquals(configuredRom.toFile(), resolveSonic2RomFile(),
                    "release CI passes SONIC2_ROM_PATH through -Dsonic2.rom.path; this regression must not fall back to root filenames");
        } finally {
            restoreProperty("sonic2.rom.path", previous);
            Files.deleteIfExists(configuredRom);
        }
    }

    @Test
    void s3kDonorRomLookupHonorsConfiguredMavenPropertyPath() throws Exception {
        Path configuredRom = TestTempFiles.createTempFile("openggf-configured-s3k", ".gen");
        String previous = System.getProperty("s3k.rom.path");
        System.setProperty("s3k.rom.path", configuredRom.toString());
        try {
            assertEquals(configuredRom.toFile(), resolveSonic3kRomFile(),
                    "release CI passes S3K_ROM_PATH through -Ds3k.rom.path; cross-game donor setup must use that path");
        } finally {
            restoreProperty("s3k.rom.path", previous);
            Files.deleteIfExists(configuredRom);
        }
    }

    private Sonic loadSonic2Player(boolean crossGame) throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, crossGame);
        config.setConfigValue(SonicConfiguration.CROSS_GAME_SOURCE, "s3k");

        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();

        Rom primaryRom = openRom(resolveSonic2RomFile());
        RomManager.getInstance().setRom(primaryRom);

        if (crossGame) {
            File s3kRom = resolveSonic3kRomFile();
            assumeTrue(s3kRom != null,
                    "S3K donor ROM is required for cross-game insta-shield regression coverage");
            config.setConfigValue(SonicConfiguration.SONIC_3K_ROM, s3kRom.getPath());
            CrossGameFeatureProvider.getInstance().resetState();
            CrossGameFeatureProvider.getInstance().initialize("s3k");
        }

        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();

        Sonic player = new Sonic("sonic", (short) 100, (short) 624);
        GameServices.sprites().addSprite(player);
        GameServices.level().loadZoneAndAct(0, 0);
        return player;
    }

    private static File resolveSonic2RomFile() {
        File configured = RomTestUtils.ensureSonic2RomAvailable();
        if (configured != null) {
            return configured;
        }
        File legacyRev00 = Path.of("Sonic The Hedgehog 2 (W) (REV00) [!].gen").toFile();
        return legacyRev00.exists() ? legacyRev00 : null;
    }

    private static File resolveSonic3kRomFile() {
        return RomTestUtils.ensureSonic3kRomAvailable();
    }

    private static Rom openRom(File romFile) throws IOException {
        assumeTrue(romFile != null, "Required Sonic 2 ROM not available");
        Rom rom = new Rom();
        if (rom.open(romFile.getPath())) {
            return rom;
        }
        throw new IOException("ROM unavailable");
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
