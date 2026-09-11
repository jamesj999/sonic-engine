package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.BigRingReturnState;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic3k.Sonic3kGameModule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestSonic3kBootstrapResolver {

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void resolvesSkipIntroWhenFlagEnabled() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldChar = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

            Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(0, 0);
            assertEquals(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, bootstrap.mode());
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkip);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldChar);
        }
    }

    @Test
    public void resolvesSkipIntroForNonSonicMainCharacter() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldChar = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");

            Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(0, 0);
            assertEquals(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, bootstrap.mode());
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkip);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldChar);
        }
    }

    @Test
    public void resolvesNormalOutsideAiz1() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldChar = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

            Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(1, 0);
            assertEquals(Sonic3kLoadBootstrap.Mode.NORMAL, bootstrap.mode());
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkip);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldChar);
        }
    }

    @Test
    public void resolvesIntroWithPositionWhenIntroEnabled() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldChar = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

            Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(0, 0);
            assertEquals(Sonic3kLoadBootstrap.Mode.INTRO, bootstrap.mode());
            assertTrue(bootstrap.hasIntroStartPosition());
            assertArrayEquals(new int[]{0x40, 0x420}, bootstrap.introStartPosition());
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkip);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldChar);
        }
    }

    @Test
    public void resolvesPostIntroBootstrapForAizBigRingReturn() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldChar = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

            GameplayModeContext gameplayMode = SessionManager.openGameplaySession(
                    new Sonic3kGameModule());
            GameplaySessionFactory.attachManagers(gameplayMode, EngineServices.current());
            GameServices.level().saveBigRingReturn(new BigRingReturnState(
                    0x800, 0x400, 0x780, 0x300, 12,
                    (byte) 0, (byte) 0, 0x390, 0, 0));

            Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(0, 0);

            assertEquals(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, bootstrap.mode());
            assertEquals(Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX,
                    Sonic3k.resolveLevelLoadBlockIndex(0, 0, bootstrap));
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkip);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldChar);
        }
    }

    @Test
    public void resolvesSkipIntroForSessionSelectedKnucklesEvenWhenConfigIsSonic() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        Object oldChar = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            SessionManager.openGameplaySession(new Sonic3kGameModule(),
                    SaveSessionContext.noSave("s3k", new SelectedTeam("knuckles", List.of()), 0, 0));

            Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(0, 0);
            assertEquals(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, bootstrap.mode());
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkip);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldChar);
        }
    }
}

