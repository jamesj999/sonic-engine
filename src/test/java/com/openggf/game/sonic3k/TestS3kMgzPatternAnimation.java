package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.level.Level;
import com.openggf.level.Pattern;
import com.openggf.level.animation.AniPlcScriptState;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kMgzPatternAnimation {
    @BeforeAll
    public static void configure() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @Test
    public void mgz1AnimatedTilesAdvanceAtBackgroundDestinations() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(2, 0)
                .build();

        assertGraphChannelsInstalled("s3k.mgz.script.0", "s3k.mgz.script.1");
        assertScriptChannelDestinations();
        assertAnimatedTileChanges(fixture, 0x222, 16);
        assertAnimatedTileChanges(fixture, 0x252, 24);
    }

    @Test
    public void mgz2AnimatedTilesAdvanceAtBackgroundDestinations() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(2, 1)
                .build();

        assertGraphChannelsInstalled("s3k.mgz.script.0", "s3k.mgz.script.1");
        assertScriptChannelDestinations();
        assertAnimatedTileChanges(fixture, 0x222, 16);
        assertAnimatedTileChanges(fixture, 0x252, 24);
    }

    @Test
    public void mgzBossFlagPausesAnimatedTileGraphChannels() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(2, 0)
                .build();

        AbstractLevelEventManager levelEvents = requireLevelEventManager();
        AnimatedTileChannel script0 = requireChannel("s3k.mgz.script.0");
        AnimatedTileChannel script1 = requireChannel("s3k.mgz.script.1");
        assertTrue(script0.guard().allows(), "MGZ script channel should run before boss lock");
        assertTrue(script1.guard().allows(), "MGZ script channel should run before boss lock");

        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        levelEvents.setBossActive(true);
        assertFalse(script0.guard().allows(), "MGZ script channel should stop during boss lock");
        assertFalse(script1.guard().allows(), "MGZ script channel should stop during boss lock");

        byte[] paused222 = snapshot(level.getPattern(0x222));
        byte[] paused252 = snapshot(level.getPattern(0x252));
        for (int i = 0; i < 24; i++) {
            advanceAnimationFrame(fixture);
        }
        assertTrue(Arrays.equals(paused222, snapshot(level.getPattern(0x222))),
                "Expected MGZ tile $222 to remain stable while Boss_flag is active");
        assertTrue(Arrays.equals(paused252, snapshot(level.getPattern(0x252))),
                "Expected MGZ tile $252 to remain stable while Boss_flag is active");

        levelEvents.setBossActive(false);
        assertTrue(script0.guard().allows(), "MGZ script channel should resume after boss lock clears");
        assertTrue(script1.guard().allows(), "MGZ script channel should resume after boss lock clears");

        assertAnimatedTileChanges(fixture, 0x222, 16);
        assertAnimatedTileChanges(fixture, 0x252, 24);
    }

    private void assertGraphChannelsInstalled(String... expectedChannelIds) {
        List<String> channelIds = GameServices.animatedTileChannelGraph().channels().stream()
                .map(AnimatedTileChannel::channelId)
                .toList();
        for (String expectedChannelId : expectedChannelIds) {
            assertTrue(channelIds.contains(expectedChannelId),
                    "Expected MGZ graph channel " + expectedChannelId + " but found " + channelIds);
        }
    }

    private void assertScriptChannelDestinations() {
        List<AniPlcScriptState> scripts = loadScripts();
        assertEquals(2, scripts.size(), "Expected the shared MGZ AniPLC list to contain two scripts");
        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            AnimatedTileChannel channel = requireChannel("s3k.mgz.script." + i);
            assertEquals(script.destinationTileIndex(), channel.destinationPlan().primaryTile(),
                    "primary destination tile for MGZ script channel " + i);
            if (script.tilesPerFrame() > 1) {
                assertEquals(Integer.valueOf(script.destinationTileIndex() + script.tilesPerFrame() - 1),
                        channel.destinationPlan().secondaryTile(),
                        "secondary destination tile for MGZ script channel " + i);
            } else {
                assertNull(channel.destinationPlan().secondaryTile(),
                        "single-tile MGZ script channels should not advertise a secondary destination");
            }
        }
    }

    private void assertAnimatedTileChanges(HeadlessTestFixture fixture, int tileIndex, int maxFrames) {
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Pattern pattern = level.getPattern(tileIndex);
        assertNotNull(pattern, "Pattern tile must exist at $" + Integer.toHexString(tileIndex));
        byte[] initial = snapshot(pattern);

        for (int frame = 0; frame < maxFrames; frame++) {
            advanceAnimationFrame(fixture);
            if (!Arrays.equals(initial, snapshot(level.getPattern(tileIndex)))) {
                return;
            }
        }

        fail("Expected animated MGZ tile $" + Integer.toHexString(tileIndex)
                + " to change over " + maxFrames + " frames");
    }

    private static void advanceAnimationFrame(HeadlessTestFixture fixture) {
        fixture.stepIdleFrames(1);
        AnimatedPatternManager apm = GameServices.level().getAnimatedPatternManager();
        assertNotNull(apm, "AnimatedPatternManager must be present");
        apm.update();
    }

    private static AbstractLevelEventManager requireLevelEventManager() {
        if (GameServices.module().getLevelEventProvider() instanceof AbstractLevelEventManager manager) {
            return manager;
        }
        throw new AssertionError("Expected an AbstractLevelEventManager-backed level event provider");
    }

    private static List<AniPlcScriptState> loadScripts() {
        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        try {
            Field field = Sonic3kPatternAnimator.class.getDeclaredField("scripts");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<AniPlcScriptState> scripts = (List<AniPlcScriptState>) field.get(animator);
            return scripts;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to access MGZ AniPLC scripts", e);
        }
    }

    private static Sonic3kPatternAnimator resolvePatternAnimator() {
        AnimatedPatternManager manager = GameServices.level().getAnimatedPatternManager();
        assertNotNull(manager, "AnimatedPatternManager must be present");
        if (manager instanceof Sonic3kPatternAnimator animator) {
            return animator;
        }
        if (manager instanceof Sonic3kLevelAnimationManager levelAnimator) {
            try {
                Field field = Sonic3kLevelAnimationManager.class.getDeclaredField("patternAnimator");
                field.setAccessible(true);
                Object value = field.get(levelAnimator);
                if (value instanceof Sonic3kPatternAnimator animator) {
                    return animator;
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Unable to access Sonic3kPatternAnimator", e);
            }
        }
        throw new AssertionError("Unexpected AnimatedPatternManager type: " + manager.getClass().getName());
    }

    private static AnimatedTileChannel requireChannel(String channelId) {
        return GameServices.animatedTileChannelGraph().channels().stream()
                .filter(channel -> channel.channelId().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing MGZ graph channel " + channelId));
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
