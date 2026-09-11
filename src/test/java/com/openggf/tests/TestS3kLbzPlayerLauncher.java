package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kLbzPlayerLauncher {
    private static final int OBJECT_ID_LBZ_PLAYER_LAUNCHER = 0x15;
    private static final int LAUNCHER_X = 0x1000;
    private static final int LAUNCHER_Y = 0x0500;

    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;
    private AbstractPlayableSprite sonic;

    @BeforeAll
    static void configure() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
    }

    @AfterAll
    static void restoreConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 0)
                .startPosition((short) LAUNCHER_X, (short) LAUNCHER_Y)
                .startPositionIsCentre()
                .build();
        sonic = fixture.sprite();
        sonic.setCentreX((short) LAUNCHER_X);
        sonic.setCentreY((short) LAUNCHER_Y);
        sonic.setAir(false);
        sonic.setXSpeed((short) 0);
        sonic.setGSpeed((short) 0);
        sonic.setMoveLockTimer(0);
        sonic.setDirection(Direction.RIGHT);
        sonic.setPushing(true);
    }

    @Test
    void skipIntrosStillRunsLbzGroundLaunchAtTitleCardSeam() {
        ObjectInstance intro = GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(object -> "LBZ1GroundLaunchIntro".equals(object.getName()))
                .findFirst()
                .orElse(null);
        assertTrue(intro != null, "production LBZ1 load must install the ground launch intro");
        GameServices.level().getObjectManager().removeDynamicObject(intro);

        assertTrue(GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager);
        ((Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider())
                .applyZonePlayerStateAfterTitleCard();

        assertTrue(GameServices.level().getObjectManager().getActiveObjects().stream()
                        .anyMatch(object -> "LBZ1GroundLaunchIntro".equals(object.getName())),
                "generic skip-intros must not suppress LBZ1 ground launch setup");
    }

    @Test
    void airbornePlayerInsideLauncherDoesNotLaunch() {
        ObjectInstance launcher = createLauncher(0x00, 0);
        sonic.setAir(true);

        for (int i = 0; i < 4; i++) {
            launcher.update(i, sonic);
        }

        assertEquals(0, sonic.getXSpeed());
        assertEquals(0, sonic.getGSpeed());
        assertEquals(0, sonic.getMoveLockTimer());
    }

    @Test
    void groundedPlayerLaunchesOnFourthOverlappingFrameUsingSubtypeSpeedAndFacing() {
        ObjectInstance launcher = createLauncher(0x02, 1);

        for (int i = 0; i < 3; i++) {
            launcher.update(i, sonic);
            assertEquals(0, sonic.getXSpeed(),
                    "ROM waits until counter $38 reaches 4 before writing x_vel");
        }

        launcher.update(3, sonic);

        assertEquals((short) -0x0A00, sonic.getXSpeed());
        assertEquals((short) -0x0A00, sonic.getGSpeed());
        assertEquals(15, sonic.getMoveLockTimer());
        assertEquals(Direction.LEFT, sonic.getDirection());
    }

    private ObjectInstance createLauncher(int subtype, int renderFlags) {
        ObjectSpawn spawn = new ObjectSpawn(LAUNCHER_X, LAUNCHER_Y,
                OBJECT_ID_LBZ_PLAYER_LAUNCHER, subtype, renderFlags, false, 0);
        ObjectInstance launcher = GameServices.module().createObjectRegistry().create(spawn);
        assertEquals("LBZPlayerLauncher", launcher.getName());
        GameServices.level().getObjectManager().addDynamicObject(launcher);
        return launcher;
    }
}
