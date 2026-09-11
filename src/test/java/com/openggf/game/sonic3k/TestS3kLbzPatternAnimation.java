package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kLbzPatternAnimation {
    @BeforeAll
    static void configure() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @Test
    void lbz1InstallsAnimatedTileGraphChannels() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x06, 0)
                .build();

        assertGraphChannelsInstalled(
                "s3k.lbz.shared",
                "s3k.lbz1.scroll",
                "s3k.lbz1.script.0",
                "s3k.lbz1.spec.0",
                "s3k.lbz1.spec.1");
    }

    @Test
    void lbz1AnimatedTileUploadsReplacePlaceholderPatterns() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x06, 0)
                .build();

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        assertRangesChangeAfterUpdates(animator, 4,
                new TileRange(0x160, 0x16F),
                new TileRange(0x350, 0x364));
    }

    @Test
    void lbz1AlarmTilesAnimateOnlyWhileAlarmStateIsActive() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x06, 0)
                .build();

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        TileRange alarmTiles = new TileRange(0x365, 0x36C);
        byte[] inactiveBefore = snapshotRange(level, alarmTiles.startTile(), alarmTiles.endTileInclusive());
        for (int i = 0; i < 4; i++) {
            animator.update();
        }
        assertArrayEquals(inactiveBefore,
                snapshotRange(level, alarmTiles.startTile(), alarmTiles.endTileInclusive()),
                "LBZ1 alarm AniPLC script must stay on its resting frame while Anim_Counters+4 is clear");

        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(GameServices.zoneRuntimeRegistry())
                .orElseThrow(() -> new AssertionError("Expected LBZ runtime state"));
        state.setAlarmAnimationActive(true);
        assertRangesChangeAfterUpdates(animator, 4,
                new TileRange(0x365, 0x36C));
    }

    @Test
    void lbz1ScrollPhaseUsesCurrentDeformCameraStateWhenParallaxCacheIsStale() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x06, 0)
                .build();

        fixture.camera().setFrozen(true);
        fixture.camera().setX((short) 0x0300);
        Field bgCameraField = GameServices.parallax().getClass().getDeclaredField("cachedBgCameraX");
        bgCameraField.setAccessible(true);
        bgCameraField.setInt(GameServices.parallax(), 0x000A);

        Sonic3kPatternAnimator animator = resolvePatternAnimator();

        assertEquals(0x08, animator.computeLbz1ScrollPhase(),
                "AnimateTiles_LBZ1 phase should use current-frame Events_bg+$10 and Camera_X_pos_BG_copy");
    }

    @Test
    void lbz2AnimatedTileUploadsReplacePlaceholderPatterns() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x06, 1)
                .build();

        assertGraphChannelsInstalled(
                "s3k.lbz.shared",
                "s3k.lbz2.rideTrigger",
                "s3k.lbz2.scroll",
                "s3k.lbz2.waterline",
                "s3k.lbz2.script.0",
                "s3k.lbz2.script.1");

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        assertRangesChangeAfterUpdates(animator, 1,
                new TileRange(0x160, 0x16F),
                new TileRange(0x2D3, 0x2E2),
                new TileRange(0x2E3, 0x2E4));
    }

    @Test
    void lbz2RideTriggerConsumesRuntimeSignalAndSkipsRomGatedScrollUploadForOneFrame() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x06, 1)
                .build();

        fixture.camera().setFrozen(true);
        fixture.camera().setX((short) 0);

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(GameServices.zoneRuntimeRegistry())
                .orElseThrow(() -> new AssertionError("Expected LBZ runtime state"));
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        animator.update();
        TileRange scrollTiles = new TileRange(0x2E3, 0x2E4);
        byte[] phaseZero = snapshotRange(level, scrollTiles.startTile(), scrollTiles.endTileInclusive());

        fixture.camera().setX((short) 0x20);
        state.setLbz2RideAnimatedTileGateActive(true);
        animator.update();

        assertTrue(state.isLbz2RideAnimatedTileGateActive(),
                "Anim_Counters+$F stays set after Obj_LBZ2RobotnikShip starts the ride");
        assertArrayEquals(phaseZero,
                snapshotRange(level, scrollTiles.startTile(), scrollTiles.endTileInclusive()),
                "Anim_Counters+$F skips the LBZ2 scroll-tile upload while the gate remains set");

        animator.update();
        assertArrayEquals(phaseZero,
                snapshotRange(level, scrollTiles.startTile(), scrollTiles.endTileInclusive()),
                "the LBZ2 scroll-tile upload stays gated on later frames while Anim_Counters+$F is set");

        state.setLbz2RideAnimatedTileGateActive(false);
        state.publishLbz2DeformOutputs(0, 0x000F, 0);
        animator.update();

        byte[] afterUngatedFrame = snapshotRange(level, scrollTiles.startTile(), scrollTiles.endTileInclusive());
        if (Arrays.equals(phaseZero, afterUngatedFrame)) {
            fail("Expected LBZ2 scroll tiles to advance after Anim_Counters+$F is explicitly cleared");
        }
    }

    @Test
    void lbz2WaterlineComposeReadsThroughAdjacentBgArtForNearSurfaceRows() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x06, 1)
                .build();

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(GameServices.zoneRuntimeRegistry())
                .orElseThrow(() -> new AssertionError("Expected LBZ runtime state"));
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        state.publishLbz2DeformOutputs(1, 0, 0);
        animator.update();

        byte[] waterlineAbove = getByteArrayField(animator, "lbz2WaterlineAboveData");
        byte[] upperBg = getByteArrayField(animator, "lbz2UpperBgData");
        byte[] waterlineScroll = getByteArrayField(animator, "lbzWaterlineScrollData");
        byte[] expected = expectedLbz2WaterlineSnapshot(waterlineAbove, upperBg, waterlineScroll, 0x0FC0);

        assertArrayEquals(expected, snapshotRange(level, 0x2D3, 0x2E2),
                "ROM sub_27F66 reads lookup values $80..$BF past ArtUnc_AniLBZ2_WaterlineAbove"
                        + " into the immediately following UpperBG art.");
    }

    private static void assertGraphChannelsInstalled(String... expectedChannelIds) {
        List<String> channelIds = GameServices.animatedTileChannelGraph().channels().stream()
                .map(AnimatedTileChannel::channelId)
                .toList();
        for (String expectedChannelId : expectedChannelIds) {
            assertTrue(channelIds.contains(expectedChannelId),
                    "Expected LBZ graph channel " + expectedChannelId + " but found " + channelIds);
        }
    }

    private static void assertRangesChangeAfterUpdates(Sonic3kPatternAnimator animator,
                                                       int maxFrames,
                                                       TileRange... ranges) {
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        byte[][] before = new byte[ranges.length][];
        boolean[] changed = new boolean[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            before[i] = snapshotRange(level, ranges[i].startTile(), ranges[i].endTileInclusive());
        }

        for (int i = 0; i < maxFrames; i++) {
            animator.update();
            for (int rangeIndex = 0; rangeIndex < ranges.length; rangeIndex++) {
                if (changed[rangeIndex]) {
                    continue;
                }
                TileRange range = ranges[rangeIndex];
                byte[] after = snapshotRange(level, range.startTile(), range.endTileInclusive());
                changed[rangeIndex] = !Arrays.equals(before[rangeIndex], after);
            }
        }

        for (int i = 0; i < ranges.length; i++) {
            if (!changed[i]) {
                TileRange range = ranges[i];
                fail("Expected LBZ animated tile upload to change $" + Integer.toHexString(range.startTile())
                        + "-$" + Integer.toHexString(range.endTileInclusive()));
            }
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

    private static byte[] getByteArrayField(Sonic3kPatternAnimator animator, String fieldName) throws Exception {
        Field field = Sonic3kPatternAnimator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (byte[]) field.get(animator);
    }

    private static byte[] expectedLbz2WaterlineSnapshot(byte[] waterlineArt,
                                                        byte[] adjacentBgArt,
                                                        byte[] waterlineScroll,
                                                        int tableOffset) {
        byte[] source = new byte[waterlineArt.length + adjacentBgArt.length];
        System.arraycopy(waterlineArt, 0, source, 0, waterlineArt.length);
        System.arraycopy(adjacentBgArt, 0, source, waterlineArt.length, adjacentBgArt.length);

        byte[] composed = new byte[0x200];
        for (int i = 0; i < 0x40; i++) {
            int sourceByteOffset = (waterlineScroll[tableOffset + i] & 0xFF) << 2;
            System.arraycopy(source, sourceByteOffset, composed, i << 2, 4);
            System.arraycopy(source, 0x100 + sourceByteOffset, composed, 0x100 + (i << 2), 4);
        }

        return rawSegaPatternsToSnapshot(composed);
    }

    private static byte[] rawSegaPatternsToSnapshot(byte[] rawData) {
        int tileCount = rawData.length / Pattern.PATTERN_SIZE_IN_ROM;
        byte[] data = new byte[tileCount * Pattern.PATTERN_SIZE_IN_MEM];
        Pattern pattern = new Pattern();
        byte[] tileBytes = new byte[Pattern.PATTERN_SIZE_IN_ROM];
        int writeOffset = 0;
        for (int tile = 0; tile < tileCount; tile++) {
            System.arraycopy(rawData, tile * Pattern.PATTERN_SIZE_IN_ROM,
                    tileBytes, 0, Pattern.PATTERN_SIZE_IN_ROM);
            pattern.fromSegaFormat(tileBytes);
            byte[] pixels = snapshot(pattern);
            System.arraycopy(pixels, 0, data, writeOffset, pixels.length);
            writeOffset += pixels.length;
        }
        return data;
    }

    private static byte[] snapshotRange(Level level, int startTile, int endTileInclusive) {
        int tileCount = endTileInclusive - startTile + 1;
        byte[] data = new byte[tileCount * Pattern.PATTERN_SIZE_IN_MEM];
        int writeOffset = 0;
        for (int tileIndex = startTile; tileIndex <= endTileInclusive; tileIndex++) {
            Pattern pattern = level.getPattern(tileIndex);
            assertNotNull(pattern, "Pattern tile must exist at $" + Integer.toHexString(tileIndex));
            byte[] tile = snapshot(pattern);
            System.arraycopy(tile, 0, data, writeOffset, tile.length);
            writeOffset += tile.length;
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

    private record TileRange(int startTile, int endTileInclusive) {
    }
}
