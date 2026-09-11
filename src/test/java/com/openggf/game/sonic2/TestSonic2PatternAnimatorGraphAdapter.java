package com.openggf.game.sonic2;

import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileCachePolicy;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.animation.ChannelContext;
import com.openggf.game.animation.DestinationPlan;
import com.openggf.level.Level;
import com.openggf.level.Pattern;
import com.openggf.level.animation.AniPlcScriptState;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2PatternAnimatorGraphAdapter {

    @Test
    void ehzAnimatedTileStillChangesThroughGraphBackedAdapter() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();

        AnimatedTileChannelGraph graph = GameServices.animatedTileChannelGraph();
        assertFalse(graph.channels().isEmpty(), "EHZ scripted animation should install graph channels");
        assertTrue(graph.channels().stream().allMatch(channel ->
                        channel.channelId().startsWith("s2.script.")
                                && channel.applyStrategy().getClass().getSimpleName().equals("ScriptFramesApplyStrategy")),
                "EHZ animated tiles should be backed by ScriptFrames channels");

        Level level = GameServices.level().getCurrentLevel();
        AnimatedPatternManager apm = GameServices.level().getAnimatedPatternManager();
        assertNotNull(apm, "AnimatedPatternManager must be present");
        List<Integer> destinationTiles = loadAnimatedDestinationTiles(GameServices.level().getAnimatedPatternManager(), level.getPatternCount());
        assertFalse(destinationTiles.isEmpty(), "Expected at least one Sonic 2 script destination tile");

        byte[][] initial = new byte[destinationTiles.size()][];
        for (int i = 0; i < destinationTiles.size(); i++) {
            initial[i] = snapshot(level.getPattern(destinationTiles.get(i)));
        }
        boolean changed = false;
        for (int frame = 0; frame < 320; frame++) {
            fixture.stepIdleFrames(1);
            apm.update();
            for (int i = 0; i < destinationTiles.size(); i++) {
                if (!Arrays.equals(initial[i], snapshot(level.getPattern(destinationTiles.get(i))))) {
                    changed = true;
                    break;
                }
            }
            if (changed) {
                break;
            }
        }

        assertTrue(changed, "Expected EHZ animated tile to change through graph-backed adapter");
    }

    @Test
    void updateSeedsGraphContextWithCurrentZoneActAndRuntimeState() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(4, 1)
                .build();

        AnimatedTileChannelGraph graph = GameServices.animatedTileChannelGraph();
        AtomicReference<ChannelContext> phaseContext = new AtomicReference<>();
        AtomicReference<ChannelContext> applyContext = new AtomicReference<>();
        AnimatedTileChannel probe = new AnimatedTileChannel(
                "s2.test.context",
                () -> true,
                ctx -> {
                    phaseContext.set(ctx);
                    return 1;
                },
                DestinationPlan.single(0x120),
                AnimatedTileCachePolicy.ALWAYS,
                applyContext::set);
        graph.install(List.of(probe));

        loadPatternAnimator(GameServices.level().getAnimatedPatternManager()).update();

        assertNotNull(phaseContext.get(), "Expected phaseSource to receive a channel context");
        assertNotNull(applyContext.get(), "Expected applyStrategy to receive a channel context");
        assertEquals(GameServices.level().getCurrentLevel().getZoneIndex(), phaseContext.get().zoneIndex(), "zoneIndex");
        assertEquals(GameServices.level().getCurrentAct(), phaseContext.get().actIndex(), "actIndex");
        assertSame(GameServices.zoneRuntimeState(), phaseContext.get().runtimeState(), "runtimeState");
        assertEquals(GameServices.level().getCurrentLevel().getZoneIndex(), applyContext.get().zoneIndex(), "zoneIndex");
        assertEquals(GameServices.level().getCurrentAct(), applyContext.get().actIndex(), "actIndex");
        assertSame(GameServices.zoneRuntimeState(), applyContext.get().runtimeState(), "runtimeState");
    }

    @Test
    void graphChannelsPreserveScriptDestinationMetadata() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();

        AnimatedTileChannelGraph graph = GameServices.animatedTileChannelGraph();
        List<AniPlcScriptState> scripts = loadScripts(GameServices.level().getAnimatedPatternManager());
        assertEquals(scripts.size(), graph.channels().size(), "Expected one graph channel per Sonic 2 AniPLC script");

        for (int i = 0; i < scripts.size(); i++) {
            AnimatedTileChannel channel = graph.channels().get(i);
            AniPlcScriptState script = scripts.get(i);
            assertEquals("s2.script." + i, channel.channelId(),
                    "Sonic 2 graph must retain AniPLC registration order");
            assertEquals(script.destinationTileIndex(), channel.destinationPlan().primaryTile(),
                    "primary destination tile for script " + i);
            if (script.tilesPerFrame() > 1) {
                assertEquals(Integer.valueOf(script.destinationTileIndex() + script.tilesPerFrame() - 1),
                        channel.destinationPlan().secondaryTile(),
                        "secondary destination tile for multi-tile script " + i);
            } else {
                assertNull(channel.destinationPlan().secondaryTile(),
                        "single-tile scripts should not advertise a secondary destination");
            }
        }
    }

    @Test
    void loadingNullListZoneClearsRuntimeGlobalGraphChannels() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();

        AnimatedTileChannelGraph graph = GameServices.animatedTileChannelGraph();
        assertFalse(graph.channels().isEmpty(), "EHZ should install scripted animation channels");

        GameServices.level().loadZoneAndAct(9, 0);

        assertTrue(graph.channels().isEmpty(), "NULL_LIST zones should clear stale Sonic 2 graph channels");
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

    private static List<Integer> loadAnimatedDestinationTiles(Object animationManager, int patternCount) {
        List<AniPlcScriptState> scripts = loadScripts(animationManager);
        return scripts.stream()
                .filter(script -> readFrameCount(script) > 1)
                .map(AniPlcScriptState::destinationTileIndex)
                .filter(tile -> tile >= 0 && tile < patternCount)
                .toList();
    }

    private static Sonic2PatternAnimator loadPatternAnimator(Object animationManager) {
        try {
            Field patternAnimatorField = animationManager.getClass().getDeclaredField("patternAnimator");
            patternAnimatorField.setAccessible(true);
            return (Sonic2PatternAnimator) patternAnimatorField.get(animationManager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect Sonic 2 level animation manager", e);
        }
    }

    private static List<AniPlcScriptState> loadScripts(Object animationManager) {
        try {
            Sonic2PatternAnimator patternAnimator = loadPatternAnimator(animationManager);
            Field scriptsField = patternAnimator.getClass().getDeclaredField("scripts");
            scriptsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<AniPlcScriptState> scripts = (List<AniPlcScriptState>) scriptsField.get(patternAnimator);
            return scripts;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect Sonic 2 AniPLC scripts", e);
        }
    }

    private static int readFrameCount(AniPlcScriptState script) {
        try {
            Field framesField = AniPlcScriptState.class.getDeclaredField("frameTileIds");
            framesField.setAccessible(true);
            return ((int[]) framesField.get(script)).length;
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to read AniPlcScriptState.frameTileIds", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect AniPlcScriptState.frameTileIds", e);
        }
    }

}
