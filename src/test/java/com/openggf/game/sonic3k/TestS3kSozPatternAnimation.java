package com.openggf.game.sonic3k;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.level.Level;
import com.openggf.level.Pattern;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSozPatternAnimation {
    @BeforeAll
    public static void configure() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @Test
    public void soz1ScrollDrivenAnimatedTileStillChanges() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x08, 0)
                .build();

        List<String> channelIds = GameServices.animatedTileChannelGraph().channels().stream()
                .map(AnimatedTileChannel::channelId)
                .toList();
        assertTrue(channelIds.contains("s3k.soz.script.0"),
                "Expected shared SOZ/LRZ AniPLC script channel in graph but found " + channelIds);
        assertTrue(channelIds.contains("s3k.soz1.scroll"),
                "Expected SOZ1 scroll channel in graph but found " + channelIds);

        Level level = GameServices.level().getCurrentLevel();
        Pattern pattern = level.getPattern(0x330);
        assertNotNull(pattern, "Expected SOZ1 animated destination tile");

        Camera camera = fixture.camera();
        camera.setFrozen(true);

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        camera.setX((short) 0);
        camera.setY((short) 0);
        animator.updateSoz1BackgroundTilesForGraph();
        byte[] phaseZero = snapshotTiles(level, 0x330, 0x333, 0x336, 0x339, 0x33C);
        int phaseZeroValue = animator.computeSoz1Phase();

        int[] candidateXs = {
                0x10, 0x20, 0x30, 0x40, 0x60, 0x80, 0xA0, 0xC0,
                0x100, 0x140, 0x180, 0x1C0, 0x200, 0x240, 0x280, 0x2C0
        };
        for (int candidateX : candidateXs) {
            camera.setX((short) candidateX);
            int candidatePhase = animator.computeSoz1Phase();
            if (candidatePhase == phaseZeroValue) {
                continue;
            }
            animator.updateSoz1BackgroundTilesForGraph();
            byte[] shifted = snapshotTiles(level, 0x330, 0x333, 0x336, 0x339, 0x33C);
            if (!Arrays.equals(phaseZero, shifted)) {
                return;
            }
        }

        throw new AssertionError("Expected SOZ1 scroll-driven animated tile to change for at least one phase shift");
    }

    @Test
    public void soz1ScrollTileRemainsStableWhileCameraPhaseIsStable() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x08, 0)
                .build();

        Camera camera = fixture.camera();
        camera.setFrozen(true);

        Level level = GameServices.level().getCurrentLevel();
        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        animator.updateSoz1BackgroundTilesForGraph();
        byte[] initial = snapshot(level.getPattern(0x330));

        for (int i = 0; i < 8; i++) {
            animator.updateSoz1BackgroundTilesForGraph();
        }

        assertTrue(Arrays.equals(initial, snapshot(level.getPattern(0x330))),
                "Expected SOZ1 custom destination tile to remain stable while camera phase is unchanged");
    }

    @Test
    public void soz1BossArenaCompatibilityBridgeForcesPhaseZero() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x08, 0)
                .build();

        Camera camera = fixture.camera();
        camera.setFrozen(true);
        camera.setX((short) 0x4380);
        camera.setY((short) 0x0960);

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        assertNotEquals(0, animator.computeSoz1Phase(),
                "Sanity check: SOZ1 phase should not already be zero before the boss lock bridge is active");

        camera.setMinX((short) 0x4180);
        camera.setMinY((short) 0x0960);

        assertEquals(0, animator.computeSoz1Phase(),
                "Expected SOZ1 boss-arena compatibility bridge to force the custom phase back to zero");
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

    private static byte[] snapshotTiles(Level level, int... tileIndices) {
        byte[] data = new byte[tileIndices.length * Pattern.PATTERN_SIZE_IN_MEM];
        int writeIndex = 0;
        for (int tileIndex : tileIndices) {
            byte[] tile = snapshot(level.getPattern(tileIndex));
            System.arraycopy(tile, 0, data, writeIndex, tile.length);
            writeIndex += tile.length;
        }
        return data;
    }
}
