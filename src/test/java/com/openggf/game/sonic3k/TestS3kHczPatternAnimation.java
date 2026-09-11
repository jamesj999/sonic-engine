package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.level.Level;
import com.openggf.level.Pattern;
import com.openggf.level.animation.AniPlcScriptState;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that HCZ AniPLC scripts are loaded and mutate the level pattern buffer.
 *
 * <p>Hydrocity uses animated background tiles in both acts:
 * HCZ1 script 0 writes to tile $30C and HCZ2 script 0 writes to tile $25E.
 * Without the HCZ AniPLC hookup, those destination tiles remain static.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHczPatternAnimation {
    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void configure() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @BeforeEach
    public void setUp() throws Exception {
        fixture = null;
    }

    @Test
    public void hcz1AnimatedTilesAdvanceAtBackgroundDestination() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(1, 0)
                .build();
        assertGraphChannelsInstalled("s3k.hcz.script.0", "s3k.hcz1.waterline");
        assertGraphRegistrationOrder("s3k.hcz1.waterline");
        assertHcz1GraphDestinationMetadata();
        assertAnimatedTileChanges(0x30C, 12);
        assertHcz1CustomPathChangesDestinationTiles();
    }

    @Test
    public void hcz2AnimatedTilesAdvanceAtBackgroundDestination() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(1, 1)
                .build();
        assertGraphChannelsInstalled("s3k.hcz.script.0", "s3k.hcz2.strips");
        assertGraphRegistrationOrder("s3k.hcz2.strips");
        assertHcz2GraphDestinationMetadata();
        assertAnimatedTileChanges(0x25E, 20);
        assertHcz2CustomPathChangesDestinationTiles();
    }

    @Test
    public void hcz1CustomChannelStillRunsWhenScriptsListIsCleared() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(1, 0)
                .build();

        clearScripts();

        AnimatedTileChannel scriptChannel = requireChannel("s3k.hcz.script.0");
        AnimatedTileChannel customChannel = requireChannel("s3k.hcz1.waterline");
        assertFalse(scriptChannel.guard().allows(), "script channel should stop once scripts are removed");
        assertTrue(customChannel.guard().allows(),
                "HCZ1 custom channel should depend on HCZ custom data, not script presence");

        assertHcz1CustomPathChangesDestinationTiles();
    }

    @Test
    public void hcz2CustomChannelStillRunsWhenScriptsListIsCleared() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(1, 1)
                .build();

        clearScripts();

        AnimatedTileChannel scriptChannel = requireChannel("s3k.hcz.script.0");
        AnimatedTileChannel customChannel = requireChannel("s3k.hcz2.strips");
        assertFalse(scriptChannel.guard().allows(), "script channel should stop once scripts are removed");
        assertTrue(customChannel.guard().allows(),
                "HCZ2 custom channel should depend on HCZ custom data, not script presence");

        assertHcz2CustomPathChangesDestinationTiles();
    }

    @Test
    public void leavingHczClearsRuntimeGlobalGraphChannels() throws Exception {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(1, 0)
                .build();

        assertGraphChannelsInstalled("s3k.hcz.script.0", "s3k.hcz1.waterline");

        GameServices.level().loadZoneAndAct(0, 0);

        assertTrue(GameServices.animatedTileChannelGraph().channels().isEmpty(),
                "Leaving HCZ should clear the runtime-global animated tile graph");
    }

    private void assertGraphChannelsInstalled(String... expectedChannelIds) {
        List<String> channelIds = GameServices.animatedTileChannelGraph().channels().stream()
                .map(AnimatedTileChannel::channelId)
                .toList();
        for (String expectedChannelId : expectedChannelIds) {
            assertTrue(channelIds.contains(expectedChannelId),
                    "Expected HCZ graph channel " + expectedChannelId + " but found " + channelIds);
        }
    }

    private void assertGraphRegistrationOrder(String customChannelId) {
        List<String> expected = new java.util.ArrayList<>();
        for (int i = 0; i < loadScripts().size(); i++) {
            expected.add("s3k.hcz.script." + i);
        }
        expected.add(customChannelId);
        assertEquals(expected, GameServices.animatedTileChannelGraph().channels().stream()
                        .map(AnimatedTileChannel::channelId).toList(),
                "HCZ graph must retain script-first registration order");
    }

    private void assertHcz1GraphDestinationMetadata() {
        List<AniPlcScriptState> scripts = loadScripts();
        assertTrue(scripts.size() >= 2, "Expected HCZ1 AniPLC scripts to be present");

        for (int i = 0; i < scripts.size(); i++) {
            assertScriptChannelDestination("s3k.hcz.script." + i, scripts.get(i));
        }

        AnimatedTileChannel customChannel = requireChannel("s3k.hcz1.waterline");
        assertEquals(0x2DC, customChannel.destinationPlan().primaryTile(),
                "HCZ1 custom channel should advertise the start of its affected strip range");
        assertEquals(Integer.valueOf(0x30B), customChannel.destinationPlan().secondaryTile(),
                "HCZ1 custom channel should advertise the end of its affected strip range");
    }

    private void assertHcz2GraphDestinationMetadata() {
        List<AniPlcScriptState> scripts = loadScripts();
        assertTrue(scripts.size() >= 2, "Expected HCZ2 AniPLC scripts to be present");

        for (int i = 0; i < scripts.size(); i++) {
            assertScriptChannelDestination("s3k.hcz.script." + i, scripts.get(i));
        }

        AnimatedTileChannel customChannel = requireChannel("s3k.hcz2.strips");
        assertEquals(0x2D2, customChannel.destinationPlan().primaryTile(),
                "HCZ2 custom channel should advertise the start of its affected strip range");
        assertEquals(Integer.valueOf(0x31D), customChannel.destinationPlan().secondaryTile(),
                "HCZ2 custom channel should advertise the end of its affected strip range");
    }

    private void assertScriptChannelDestination(String channelId, AniPlcScriptState script) {
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

    private void assertAnimatedTileChanges(int tileIndex, int maxFrames) {
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Pattern pattern = level.getPattern(tileIndex);
        assertNotNull(pattern, "Pattern tile must exist at $" + Integer.toHexString(tileIndex));

        byte[] initial = snapshot(pattern);
        AnimatedPatternManager apm = GameServices.level().getAnimatedPatternManager();
        assertNotNull(apm, "AnimatedPatternManager must be present");
        boolean changed = false;
        for (int frame = 0; frame < maxFrames; frame++) {
            advanceAnimationFrame(apm);
            if (!Arrays.equals(initial, snapshot(level.getPattern(tileIndex)))) {
                changed = true;
                break;
            }
        }

        assertTrue(changed, "Expected animated HCZ tile $" + Integer.toHexString(tileIndex)
                + " to change over " + maxFrames + " frames");
    }

    private void assertHcz1CustomPathChangesDestinationTiles() {
        Camera camera = fixture.camera();
        camera.setFrozen(true);

        camera.setX((short) 0);
        camera.setY((short) 0);
        advanceAnimationFrame();
        byte[] aboveWaterline = snapshotTiles(0x2DC, 0x2E8, 0x2F4, 0x300);

        camera.setY((short) 0x700);
        advanceAnimationFrame();
        byte[] belowWaterline = snapshotTiles(0x2DC, 0x2E8, 0x2F4, 0x300);

        assertFalse(Arrays.equals(aboveWaterline, belowWaterline),
                "Expected HCZ1 custom path to change its destination tiles when the waterline state changes");
    }

    private void assertHcz2CustomPathChangesDestinationTiles() {
        Camera camera = fixture.camera();
        camera.setFrozen(true);
        Sonic3kPatternAnimator animator = resolvePatternAnimator();

        camera.setY((short) 0);
        camera.setX((short) 0);
        advanceAnimationFrame();
        byte[] phaseZero = snapshotTiles(0x2D2, 0x2D6, 0x2DE, 0x2EE);
        int phaseZeroValue = animator.computeHcz2CompositePhase();

        int[] candidateXs = {
                0x20, 0x40, 0x80, 0xC0, 0x100, 0x140, 0x180, 0x1C0,
                0x200, 0x240, 0x280, 0x2C0, 0x300, 0x340, 0x380, 0x3C0,
                0x400, 0x440, 0x480, 0x4C0, 0x500, 0x540, 0x580, 0x5C0,
                0x600, 0x640, 0x680, 0x6C0, 0x700
        };
        for (int candidateX : candidateXs) {
            camera.setX((short) candidateX);
            int candidatePhase = animator.computeHcz2CompositePhase();
            if (candidatePhase == phaseZeroValue) {
                continue;
            }
            advanceAnimationFrame();
            byte[] phaseShifted = snapshotTiles(0x2D2, 0x2D6, 0x2DE, 0x2EE);
            if (!Arrays.equals(phaseZero, phaseShifted)) {
                return;
            }
        }

        fail("Expected HCZ2 custom path to change its destination tiles for at least one composite phase shift");
    }

    private Sonic3kPatternAnimator resolvePatternAnimator() {
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
                fail("Unable to access Sonic3kPatternAnimator: " + e.getMessage());
            }
        }
        fail("Unexpected animated pattern manager type: " + manager.getClass().getName());
        return null;
    }

    private List<AniPlcScriptState> loadScripts() {
        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        try {
            Field field = Sonic3kPatternAnimator.class.getDeclaredField("scripts");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<AniPlcScriptState> scripts = (List<AniPlcScriptState>) field.get(animator);
            return scripts;
        } catch (ReflectiveOperationException e) {
            fail("Unable to access HCZ AniPLC scripts: " + e.getMessage());
            return List.of();
        }
    }

    private void clearScripts() {
        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        try {
            Field field = Sonic3kPatternAnimator.class.getDeclaredField("scripts");
            field.setAccessible(true);
            field.set(animator, List.of());
        } catch (ReflectiveOperationException e) {
            fail("Unable to clear HCZ AniPLC scripts: " + e.getMessage());
        }
    }

    private AnimatedTileChannel requireChannel(String channelId) {
        return GameServices.animatedTileChannelGraph().channels().stream()
                .filter(channel -> channel.channelId().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing HCZ graph channel " + channelId));
    }

    private byte[] snapshotTiles(int... tileIndices) {
        byte[] data = new byte[tileIndices.length * Pattern.PATTERN_SIZE_IN_MEM];
        int offset = 0;
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");
        for (int tileIndex : tileIndices) {
            Pattern pattern = level.getPattern(tileIndex);
            assertNotNull(pattern, "Pattern tile must exist at $" + Integer.toHexString(tileIndex));
            byte[] patternBytes = snapshot(pattern);
            System.arraycopy(patternBytes, 0, data, offset, patternBytes.length);
            offset += patternBytes.length;
        }
        return data;
    }

    private void advanceAnimationFrame() {
        fixture.stepIdleFrames(1);
        AnimatedPatternManager apm = GameServices.level().getAnimatedPatternManager();
        assertNotNull(apm, "AnimatedPatternManager must be present");
        apm.update();
    }

    private void advanceAnimationFrame(AnimatedPatternManager apm) {
        fixture.stepIdleFrames(1);
        apm.update();
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

