package com.openggf.game.sonic3k.objects;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The small bubbles {@code Obj_AirCountdown} emits from the player's face
 * while underwater (sonic3k.asm:33300-33370). The fixed
 * {@code Breathing_bubbles} controller allocates them through the dynamic
 * object path, and each child draws {@code Map_Bubbler} frames 0-2 out of
 * {@code ArtNem_Bubbles} — the same art set the HCZ bubbler uses.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kBreathingBubbles {
    private static final int ZONE_HCZ = Sonic3kZoneIds.ZONE_HCZ;
    private static final int ACT_1 = 0;
    /** Comfortably more than the $3C-frame gap between breathing bursts. */
    private static final int FRAMES = 200;
    /** Ani_AirCountdown byte_18718: the breathing bubble only uses frames 0-2. */
    private static final int MAX_BREATHING_FRAME = 2;
    /** First Map_Bubbler frame that draws a countdown digit. */
    private static final int FIRST_DIGIT_FRAME = 0x09;
    /**
     * Air starts at 30 and drops one per $3C frames, so the digits appear from
     * about frame 1100; drowning death lands just past 1800.
     */
    private static final int DROWNING_FRAMES = 1750;

    @BeforeAll
    public static void configure() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    /**
     * Every test here builds an HCZ1 HeadlessTestFixture and never releases it,
     * and configure() writes S3K_SKIP_INTROS into the configuration singleton.
     * Neither is restored by the per-test reset, so a later class in the same
     * fork that assumes open air (TestMhzMushroomParachuteObjectInstance's wall
     * sensors read terrain through GameServices.levelOrNull()) would otherwise
     * collide with HCZ1 terrain. Tear the session down at the source.
     */
    @AfterEach
    void releaseLevelAndConfiguration() {
        TestEnvironment.resetAll();
    }

    @Test
    public void underwaterPlayerEmitsRenderableBubbles() {
        GraphicsManager.getInstance().initHeadless();
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_HCZ, ACT_1)
                .build();

        AbstractPlayableSprite player = fixture.sprite();
        int waterLevel = GameServices.water().getWaterLevelY(ZONE_HCZ, ACT_1);
        player.setCentreY((short) (waterLevel + 0x60));

        List<S3kAirCountdownObjectInstance> seen = new ArrayList<>();
        List<Integer> frames = new ArrayList<>();
        for (int frame = 0; frame < FRAMES; frame++) {
            fixture.stepIdleFrames(1);
            if (player.getCentreY() < waterLevel + 0x20) {
                player.setCentreY((short) (waterLevel + 0x60));
            }
            for (ObjectInstance object : GameServices.level().getObjectManager().getActiveObjects()) {
                if (object instanceof S3kAirCountdownObjectInstance bubble) {
                    if (!seen.contains(bubble)) {
                        seen.add(bubble);
                    }
                    frames.add(bubble.getMappingFrameForTest());
                }
            }
        }

        assertTrue(player.isInWater(), "player should still be underwater");
        assertFalse(seen.isEmpty(), "expected breathing bubbles to spawn underwater");
        for (int mappingFrame : frames) {
            assertTrue(mappingFrame >= 0 && mappingFrame <= MAX_BREATHING_FRAME,
                    "breathing bubble should stay on Map_Bubbler frames 0-2, saw " + mappingFrame);
        }
        assertTrue(frames.contains(1),
                "expected the bubble to animate past its first Map_Bubbler frame");

        PatternSpriteRenderer renderer = GameServices.level().getObjectRenderManager()
                .getRenderer(Sonic3kObjectArtKeys.BUBBLER);
        assertNotNull(renderer, "Map_Bubbler art set should be loaded so bubbles can draw");
        assertTrue(renderer.isReady(), "Map_Bubbler art set should be ready");
    }

    /**
     * Below 12 air the controller emits countdown bubbles that turn into a
     * digit, park themselves in screen space, then flash
     * (sonic3k.asm:33410-33453). The digits come from {@code ArtUnc_AirCountdown}
     * rather than the bubble sheet.
     */
    @Test
    public void lowAirProducesScreenSpaceCountdownDigits() {
        GraphicsManager.getInstance().initHeadless();
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_HCZ, ACT_1)
                .build();

        AbstractPlayableSprite player = fixture.sprite();
        int waterLevel = GameServices.water().getWaterLevelY(ZONE_HCZ, ACT_1);
        player.setCentreY((short) (waterLevel + 0x60));

        boolean sawDigitFrame = false;
        boolean sawScreenSpaceDigit = false;
        for (int frame = 0; frame < DROWNING_FRAMES && !sawScreenSpaceDigit; frame++) {
            fixture.stepIdleFrames(1);
            if (player.getCentreY() < waterLevel + 0x20) {
                player.setCentreY((short) (waterLevel + 0x60));
            }
            for (ObjectInstance object : GameServices.level().getObjectManager().getActiveObjects()) {
                if (!(object instanceof S3kAirCountdownObjectInstance bubble)) {
                    continue;
                }
                if (bubble.getMappingFrameForTest() >= FIRST_DIGIT_FRAME) {
                    sawDigitFrame = true;
                    sawScreenSpaceDigit |= bubble.isScreenSpaceForTest();
                }
            }
        }

        assertTrue(sawDigitFrame, "expected a countdown bubble to reach a digit mapping frame");
        assertTrue(sawScreenSpaceDigit,
                "expected the digit to park in screen space via AirCountdown_ShowNumber");

        PatternSpriteRenderer digits = GameServices.level().getObjectRenderManager()
                .getRenderer(Sonic3kObjectArtKeys.AIR_COUNTDOWN_DIGITS);
        assertNotNull(digits, "ArtUnc_AirCountdown digit sheet should be loaded");
        assertTrue(digits.isReady(), "digit sheet should be ready");
    }
}
