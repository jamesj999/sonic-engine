package com.openggf.game.sonic3k;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kIczPatternAnimation {
    @BeforeAll
    static void configure() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @Test
    void iczAnimatedTileGraphInstallsScriptAndCustomChannels() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x05, 0)
                .build();

        List<String> channelIds = GameServices.animatedTileChannelGraph().channels().stream()
                .map(AnimatedTileChannel::channelId)
                .toList();

        assertTrue(channelIds.contains("s3k.icz.script.0"),
                "Expected ICZ AniPLC script channel in graph but found " + channelIds);
        assertTrue(channelIds.contains("s3k.icz.scroll.x"),
                "Expected ICZ horizontal custom channel in graph but found " + channelIds);
        assertTrue(channelIds.contains("s3k.icz1.scroll.y"),
                "Expected ICZ1 vertical custom channel in graph but found " + channelIds);

        List<AniPlcScriptState> scripts = loadScripts();
        assertEquals(1, scripts.size(), "Expected AniPLC_ICZ to contain one script");
        assertScriptChannelDestination("s3k.icz.script.0", scripts.get(0));
    }

    @Test
    void iczCustomPathChangesIndoorDestinationTilesForPhaseShift() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x05, 0)
                .build();

        Camera camera = fixture.camera();
        camera.setFrozen(true);
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        AnimatedPatternManager animator = GameServices.level().getAnimatedPatternManager();
        assertNotNull(animator, "AnimatedPatternManager must be present");
        camera.setX((short) 0x3940);
        camera.setY((short) 0x0700);
        animator.update();
        byte[] phaseA = snapshotTiles(level, 0x10E, 0x116, 0x122, 0x12A, 0x12E, 0x130);

        int[] candidateXs = {0x3960, 0x3980, 0x39C0, 0x3A00, 0x3A40, 0x3A80, 0x3AC0};
        for (int candidateX : candidateXs) {
            camera.setX((short) candidateX);
            animator.update();
            byte[] shifted = snapshotTiles(level, 0x10E, 0x116, 0x122, 0x12A, 0x12E, 0x130);
            if (!Arrays.equals(phaseA, shifted)) {
                return;
            }
        }

        throw new AssertionError("Expected ICZ custom animated tiles to change for at least one indoor phase shift");
    }

    private static void assertScriptChannelDestination(String channelId, AniPlcScriptState script) {
        AnimatedTileChannel channel = requireChannel(channelId);
        assertEquals(script.destinationTileIndex(), channel.destinationPlan().primaryTile(),
                "primary destination tile for " + channelId);
        if (script.tilesPerFrame() > 1) {
            assertEquals(Integer.valueOf(script.destinationTileIndex() + script.tilesPerFrame() - 1),
                    channel.destinationPlan().secondaryTile(),
                    "secondary destination tile for multi-tile " + channelId);
        } else {
            assertNull(channel.destinationPlan().secondaryTile(),
                    "single-tile script channels should not advertise a secondary destination");
        }
    }

    private static AnimatedTileChannel requireChannel(String channelId) {
        return GameServices.animatedTileChannelGraph().channels().stream()
                .filter(channel -> channel.channelId().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing ICZ graph channel " + channelId));
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
            throw new AssertionError("Unable to access ICZ AniPLC scripts", e);
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

    private static byte[] snapshotTiles(Level level, int... tileIndices) {
        byte[] data = new byte[tileIndices.length * Pattern.PATTERN_SIZE_IN_MEM];
        int writeIndex = 0;
        for (int tileIndex : tileIndices) {
            Pattern pattern = level.getPattern(tileIndex);
            assertNotNull(pattern, "Pattern tile must exist at $" + Integer.toHexString(tileIndex));
            byte[] tile = snapshot(pattern);
            System.arraycopy(tile, 0, data, writeIndex, tile.length);
            writeIndex += tile.length;
        }
        return data;
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
