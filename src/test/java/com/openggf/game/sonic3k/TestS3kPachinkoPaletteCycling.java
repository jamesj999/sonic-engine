package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.animation.AnimatedPaletteManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kPachinkoPaletteCycling {
    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void configure() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @BeforeEach
    public void setUp() {
        fixture = null;
    }

    @Test
    public void pachinkoLine4GlowColorsCycle() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x14, 0)
                .build();

        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level);
        AnimatedPaletteManager cycler = GameServices.level().getAnimatedPaletteManager();
        assertNotNull(cycler);

        Palette.Color color = level.getPalette(3).getColor(8);
        int initialR = color.r & 0xFF;
        int initialG = color.g & 0xFF;
        int initialB = color.b & 0xFF;

        boolean changed = false;
        for (int frame = 0; frame < 40; frame++) {
            fixture.stepIdleFrames(1);
            cycler.update();
            if ((color.r & 0xFF) != initialR
                    || (color.g & 0xFF) != initialG
                    || (color.b & 0xFF) != initialB) {
                changed = true;
                break;
            }
        }

        assertTrue(changed, "Expected Pachinko palette[3] color 8 to cycle");
    }

    @Test
    public void pachinkoLine3BackgroundColorsCycle() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x14, 0)
                .build();

        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level);
        AnimatedPaletteManager cycler = GameServices.level().getAnimatedPaletteManager();
        assertNotNull(cycler);

        Palette.Color color = level.getPalette(2).getColor(1);
        int initialR = color.r & 0xFF;
        int initialG = color.g & 0xFF;
        int initialB = color.b & 0xFF;

        boolean changed = false;
        for (int frame = 0; frame < 80; frame++) {
            fixture.stepIdleFrames(1);
            cycler.update();
            if ((color.r & 0xFF) != initialR
                    || (color.g & 0xFF) != initialG
                    || (color.b & 0xFF) != initialB) {
                changed = true;
                break;
            }
        }

        assertTrue(changed, "Expected Pachinko palette[2] color 1 to cycle");
    }

    @Test
    public void pachinkoCycleSubmitsPaletteOwnershipClaims() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x14, 0)
                .build();

        AnimatedPaletteManager cycler = GameServices.level().getAnimatedPaletteManager();
        assertNotNull(cycler);
        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();

        cycler.update();

        for (int color = 8; color <= 14; color++) {
            org.junit.jupiter.api.Assertions.assertEquals(S3kPaletteOwners.PACHINKO_ZONE_CYCLE,
                    registry.ownerAt(PaletteSurface.NORMAL, 3, color),
                    "Pachinko line-4 cycle should claim palette[3] color " + color);
        }
        for (int color = 1; color <= 15; color++) {
            org.junit.jupiter.api.Assertions.assertEquals(S3kPaletteOwners.PACHINKO_ZONE_CYCLE,
                    registry.ownerAt(PaletteSurface.NORMAL, 2, color),
                    "Pachinko line-3 cycle should claim palette[2] color " + color);
        }
    }
}


