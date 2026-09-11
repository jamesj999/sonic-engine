package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that CNZ animated tiles use the ROM-derived deform phase and install
 * their direct-DMA ownership through the shared animated-tile graph.
 *
 * <p>ROM: {@code AnimateTiles_CNZ} derives its phase from
 * {@code (Events_bg+$10 - Camera_X_pos_BG_copy) & $3F}, then DMA-copies slices
 * from {@code ArtUnc_AniCNZ__6} into VRAM tile range {@code $308+}. These
 * tests pin that behavior to the runtime-owned CNZ deform publication added in
 * the earlier slices, rather than allowing CNZ to drift into a free-running or
 * graph-bypassing implementation.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzPatternAnimation {

    @BeforeAll
    static void configure() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @Test
    void cnzPhaseUsesPublishedDeformInputs() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x03, 0)
                .build();

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        CnzZoneRuntimeState state = requireCnzState();
        state.publishDeformOutputs(0x1C, 0x08);

        assertEquals(0x14, animator.computeCnzPhase(),
                "AnimateTiles_CNZ should derive its phase from the published CNZ deform inputs");
    }

    @Test
    void cnzAnimatedTileGraphInstallsCnzCustomChannel() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x03, 0)
                .build();

        List<String> channelIds = GameServices.animatedTileChannelGraph().channels().stream()
                .map(AnimatedTileChannel::channelId)
                .toList();
        assertTrue(channelIds.contains("s3k.cnz.scroll"),
                "Expected CNZ custom animated-tile channel in graph but found " + channelIds);
        assertTrue(channelIds.contains("s3k.cnz.script.0"),
                "Expected CNZ AniPLC script channel in graph but found " + channelIds);
    }

    @Test
    void onlyHoverFanBladeAniPlcRequestsAnObjectTextureRefresh() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x03, 0)
                .build();

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        AniPlcScriptState hoverFanBlades = new AniPlcScriptState(
                (byte) 3, 0x304, new int[] {0}, null, 4, new Pattern[] {new Pattern()});
        AniPlcScriptState unrelatedCnzArt = new AniPlcScriptState(
                (byte) 3, 0x328, new int[] {0}, null, 4, new Pattern[] {new Pattern()});

        assertTrue(animator.requiresObjectRendererRefresh(hoverFanBlades));
        assertFalse(animator.requiresObjectRendererRefresh(unrelatedCnzArt));
    }

    @Test
    void cnzCustomPathChangesDestinationTilesForAtLeastOnePhaseShift() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x03, 0)
                .build();

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        CnzZoneRuntimeState state = requireCnzState();
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        state.publishDeformOutputs(0x00, 0x00);
        animator.updateCnzBackgroundTilesForGraph();
        byte[] phaseZero = snapshotRange(level, 0x308, 0x327);

        for (int phase = 1; phase < 0x40; phase++) {
            state.publishDeformOutputs(phase, 0x00);
            animator.updateCnzBackgroundTilesForGraph();
            byte[] shifted = snapshotRange(level, 0x308, 0x327);
            if (!Arrays.equals(phaseZero, shifted)) {
                return;
            }
        }

        fail("Expected CNZ custom animated-tile DMA path to change at least one destination tile for a phase shift");
    }

    private static CnzZoneRuntimeState requireCnzState() {
        return GameServices.zoneRuntimeRegistry()
                .currentAs(CnzZoneRuntimeState.class)
                .orElseThrow(() -> new AssertionError("CNZ runtime state should be installed"));
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
}
