package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.level.Level;
import com.openggf.level.Pattern;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kPachinkoPatternAnimation {
    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void configure() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @BeforeEach
    public void setUp() {
        fixture = null;
    }

    @Test
    public void pachinkoAnimatedTilesAdvanceAtAniPlcDestination() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x14, 0)
                .build();

        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Pattern pattern = level.getPattern(0x125);
        assertNotNull(pattern, "Pattern tile must exist at $125");

        byte[] initial = snapshot(pattern);
        AnimatedPatternManager apm = GameServices.level().getAnimatedPatternManager();
        assertNotNull(apm, "AnimatedPatternManager must be present");

        boolean changed = false;
        for (int frame = 0; frame < 12; frame++) {
            fixture.stepIdleFrames(1);
            apm.update();
            if (!Arrays.equals(initial, snapshot(level.getPattern(0x125)))) {
                changed = true;
                break;
            }
        }

        assertFalse(!changed, "Expected animated Pachinko tile $125 to remain static over 12 frames");
    }

    @Test
    public void pachinkoBackgroundTilesAdvanceAtDmaDestination() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x14, 0)
                .build();

        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Pattern pattern = level.getPattern(0x0E9);
        assertNotNull(pattern, "Pattern tile must exist at $0E9");

        byte[] initial = snapshot(pattern);
        AnimatedPatternManager apm = GameServices.level().getAnimatedPatternManager();
        assertNotNull(apm, "AnimatedPatternManager must be present");

        boolean changed = false;
        for (int frame = 0; frame < 6; frame++) {
            fixture.stepIdleFrames(1);
            apm.update();
            if (!Arrays.equals(initial, snapshot(level.getPattern(0x0E9)))) {
                changed = true;
                break;
            }
        }

        assertFalse(!changed, "Expected animated Pachinko tile $0E9 to remain static over 6 frames");
    }

    private static byte[] snapshot(Pattern pattern) {
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_MEM];
        int index = 0;
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                data[index++] = pattern.getPixel(x, y);
            }
        }
        return data;
    }
}


