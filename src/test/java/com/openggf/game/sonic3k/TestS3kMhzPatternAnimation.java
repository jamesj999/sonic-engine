package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that MHZ animated tiles consume the parallax outputs published by
 * {@code MHZ_Deform}, matching the ROM {@code AnimateTiles_MHZ} phase inputs.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kMhzPatternAnimation {

    @BeforeAll
    static void configure() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @Test
    void mhzTilePhasesUsePublishedDeformOutputs() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        MhzZoneRuntimeState state = GameServices.zoneRuntimeRegistry()
                .currentAs(MhzZoneRuntimeState.class)
                .orElseThrow(() -> new AssertionError("MHZ runtime state should be installed"));

        state.publishDeformOutputs(0x1234, 0x1289, 0x1262);

        assertEquals(0x0E, animator.computeMhzBackgroundLayer1Phase(),
                "AnimateTiles_MHZ layer 1 should use (Events_bg+$12 - Camera_X_pos_BG_copy) & $1F");
        assertEquals(0x15, animator.computeMhzBackgroundLayer2Phase(),
                "AnimateTiles_MHZ layer 2 should use (Events_bg+$10 - Camera_X_pos_BG_copy) & $3F");
    }

    @Test
    void mhzAnimatedTileGraphInstallsScrollAndScriptChannels() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        List<String> channelIds = GameServices.animatedTileChannelGraph().channels().stream()
                .map(AnimatedTileChannel::channelId)
                .toList();

        assertTrue(channelIds.contains("s3k.mhz.bg1"),
                "Expected MHZ first scroll-tile channel in graph but found " + channelIds);
        assertTrue(channelIds.contains("s3k.mhz.bg2"),
                "Expected MHZ second scroll-tile channel in graph but found " + channelIds);
        assertTrue(channelIds.contains("s3k.mhz.mushroomCapCounter"),
                "Expected MHZ mushroom-cap position counter channel in graph but found " + channelIds);
        assertTrue(channelIds.contains("s3k.mhz.script.0"),
                "Expected MHZ AniPLC script channel in graph but found " + channelIds);
    }

    @Test
    void mhzAnimatedTileGraphAdvancesMushroomCapCounterLikeRomAnimCounters() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        MhzZoneRuntimeState state = GameServices.zoneRuntimeRegistry()
                .currentAs(MhzZoneRuntimeState.class)
                .orElseThrow(() -> new AssertionError("MHZ runtime state should be installed"));
        AnimatedPatternManager manager = GameServices.level().getAnimatedPatternManager();
        assertNotNull(manager, "AnimatedPatternManager must be present");

        state.publishMushroomCapPositionCounter(0x54);
        manager.update();
        assertEquals(0x56, state.mushroomCapPositionCounter(),
                "AnimateTiles_MHZ adds 2 to Anim_Counters+$F each update");

        manager.update();
        assertEquals(0, state.mushroomCapPositionCounter(),
                "AnimateTiles_MHZ wraps Anim_Counters+$F to zero when it reaches $58");
    }

    @Test
    void mhzReplayBootstrapPreludeDoesNotAdvanceMushroomCapCounter() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        MhzZoneRuntimeState state = GameServices.zoneRuntimeRegistry()
                .currentAs(MhzZoneRuntimeState.class)
                .orElseThrow(() -> new AssertionError("MHZ runtime state should be installed"));
        AnimatedPatternManager manager = GameServices.level().getAnimatedPatternManager();
        Sonic3kLevelAnimationManager s3kManager =
                assertInstanceOf(Sonic3kLevelAnimationManager.class, manager,
                        "S3K installs Sonic3kLevelAnimationManager");

        state.publishMushroomCapPositionCounter(0x20);

        // The trace-replay bootstrap prelude warms the ROM's pre-first-frame VRAM
        // tile state, but Anim_Counters+$F is a stateful accumulator already seeded
        // to its ROM LevelLoop frame-0 phase (loc_6468 pre-loop Animate_Tiles). The
        // warmup pass must not advance it, or caps read two bob-steps ahead.
        s3kManager.updatePatternsOnlyForReplayBootstrap();
        assertEquals(0x20, state.mushroomCapPositionCounter(),
                "Replay-bootstrap VRAM warmup must preserve the mushroom-cap "
                        + "Anim_Counters+$F accumulator");

        // A normal per-frame Animate_Tiles pass still advances it by 2.
        manager.update();
        assertEquals(0x22, state.mushroomCapPositionCounter(),
                "Normal AnimateTiles_MHZ still adds 2 to Anim_Counters+$F each frame");
    }

    @Test
    void changeRingFrameGlobalAngleAdvancesAfterObjectsAndSurvivesRewind() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        Sonic3kLevelAnimationManager manager = assertInstanceOf(
                Sonic3kLevelAnimationManager.class,
                GameServices.level().getAnimatedPatternManager());
        int initialAngle = manager.aizVineAngleWord();

        manager.updatePatternsOnlyForReplayBootstrap();
        assertEquals(initialAngle, manager.aizVineAngleWord(),
                "The setup Animate_Tiles prelude is not ROM ChangeRingFrame");

        var snapshot = manager.capture();
        manager.update();
        assertEquals((initialAngle + 0x180) & 0xFFFF, manager.aizVineAngleWord(),
                "ChangeRingFrame adds $180 to AIZ_vine_angle after Process_Sprites");

        manager.restore(snapshot);
        assertEquals(initialAngle, manager.aizVineAngleWord(),
                "The independent global animation word must restore with its owning adapter");
    }

    @Test
    void mhzMushroomCapCounterStartsAfterRomPreLoopAnimateTilesPass() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        MhzZoneRuntimeState state = GameServices.zoneRuntimeRegistry()
                .currentAs(MhzZoneRuntimeState.class)
                .orElseThrow(() -> new AssertionError("MHZ runtime state should be installed"));

        assertEquals(2, state.mushroomCapPositionCounter(),
                "Level setup runs Process_Sprites then Animate_Tiles before LevelLoop; "
                        + "AnimateTiles_MHZ increments Anim_Counters+$F before mushroom caps' first loop read");
    }

    @Test
    void mhzCustomPathChangesDestinationTilesForAtLeastOnePhaseShift() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        Sonic3kPatternAnimator animator = resolvePatternAnimator();
        MhzZoneRuntimeState state = GameServices.zoneRuntimeRegistry()
                .currentAs(MhzZoneRuntimeState.class)
                .orElseThrow(() -> new AssertionError("MHZ runtime state should be installed"));
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        state.publishDeformOutputs(0x0000, 0x0000, 0x0000);
        animator.updateMhzBackgroundLayer1ForGraph();
        animator.updateMhzBackgroundLayer2ForGraph();
        byte[] phaseZero = snapshotRange(level, 0x1B8, 0x1F4);

        for (int phase = 1; phase < 0x40; phase++) {
            state.publishDeformOutputs(0x0000, phase, phase);
            animator.updateMhzBackgroundLayer1ForGraph();
            animator.updateMhzBackgroundLayer2ForGraph();
            byte[] shifted = snapshotRange(level, 0x1B8, 0x1F4);
            if (!Arrays.equals(phaseZero, shifted)) {
                return;
            }
        }

        fail("Expected MHZ custom animated-tile DMA path to change at least one destination tile for a phase shift");
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
