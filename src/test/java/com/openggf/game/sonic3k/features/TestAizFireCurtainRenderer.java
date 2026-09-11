package com.openggf.game.sonic3k.features;

import com.openggf.game.sonic3k.Sonic3kLoadBootstrap;
import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.events.FireCurtainStage;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAizFireCurtainRenderer {
    private static final int SAMPLE_PATTERN_MASK = 0x7FF;

    private static AizFireCurtainRenderer rendererWithSampler() {
        return new AizFireCurtainRenderer((worldX, worldY) -> {
            int pattern = ((worldX / 8) + (worldY / 8)) & SAMPLE_PATTERN_MASK;
            return (3 << 13) | pattern;
        });
    }

    @Test
    public void compositionFillsBottomBandForEveryVisibleColumn() {
        AizFireCurtainRenderer renderer = rendererWithSampler();
        FireCurtainRenderState state = new FireCurtainRenderState(
                true,
                96,
                24,
                12,
                0x1000,
                0x0180,
                new int[] {0, -1, -2, -3, -4, -5, -6, -7, -8, -7, -6, -5, -4, -3, -2, -1, 0, -1, -2, -3},
                FireCurtainStage.AIZ1_RISING);

        AizFireCurtainRenderer.CurtainCompositionPlan plan = renderer.buildCompositionPlan(state, 320, 224);

        assertFalse(plan.columns().isEmpty(),
                "an active rising curtain must emit visible columns to validate");
        for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
            boolean coversBottom = false;
            for (AizFireCurtainRenderer.TileDraw draw : column.draws()) {
                if (draw.screenY() <= 223 && draw.screenY() + 8 > 223) {
                    coversBottom = true;
                    break;
                }
            }
            assertTrue(coversBottom, "Column " + column.columnIndex() + " left a bottom gap");
        }
    }

    @Test
    public void compositionDoesNotEmitColumnsAboveTheirVisibleRegion() {
        AizFireCurtainRenderer renderer = rendererWithSampler();
        FireCurtainRenderState state = new FireCurtainRenderState(
                true,
                80,
                0,
                6,
                0x1000,
                0x0180,
                new int[20],
                FireCurtainStage.AIZ1_RISING);

        AizFireCurtainRenderer.CurtainCompositionPlan plan = renderer.buildCompositionPlan(state, 320, 224);

        for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
            assertFalse(column.draws().isEmpty());
            int highestTileTop = Integer.MAX_VALUE;
            for (AizFireCurtainRenderer.TileDraw draw : column.draws()) {
                highestTileTop = Math.min(highestTileTop, draw.screenY());
            }
            assertTrue(highestTileTop >= column.topY() - 8, "Column " + column.columnIndex() + " should keep at most one clip-padding tile above the top edge");
        }
    }

    @Test
    public void waveOffsetsChangeColumnTopDeterministically() {
        AizFireCurtainRenderer renderer = rendererWithSampler();
        FireCurtainRenderState flat = new FireCurtainRenderState(
                true,
                72,
                0,
                4,
                0x1000,
                0x0180,
                new int[20],
                FireCurtainStage.AIZ1_RISING);
        FireCurtainRenderState wavy = new FireCurtainRenderState(
                true,
                72,
                0,
                4,
                0x1000,
                0x0180,
                new int[] {0, -2, -4, -6, -8, -6, -4, -2, 0, -1, -3, -5, -7, -5, -3, -1, 0, -2, -4, -6},
                FireCurtainStage.AIZ1_RISING);

        AizFireCurtainRenderer.CurtainCompositionPlan flatPlan = renderer.buildCompositionPlan(flat, 320, 224);
        AizFireCurtainRenderer.CurtainCompositionPlan wavyPlan = renderer.buildCompositionPlan(wavy, 320, 224);

        assertEquals(flatPlan.columns().size(), wavyPlan.columns().size());
        assertEquals(152, flatPlan.columns().get(0).topY());
        assertEquals(144, wavyPlan.columns().get(4).topY());
    }

    @Test
    public void compositionSamplesConfiguredSourceStripCoordinates() {
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer((worldX, worldY) ->
                (3 << 13) | (((worldX / 8) ^ (worldY / 8)) & SAMPLE_PATTERN_MASK));
        FireCurtainRenderState state = new FireCurtainRenderState(
                true,
                224,
                18,
                30,
                0x0200,
                0x01A0,
                new int[20],
                FireCurtainStage.AIZ1_RISING);

        AizFireCurtainRenderer.CurtainCompositionPlan plan = renderer.buildCompositionPlan(state, 320, 224);
        AizFireCurtainRenderer.ColumnRenderPlan firstColumn = plan.columns().get(0);
        AizFireCurtainRenderer.TileDraw bottomTile = firstColumn.draws().get(0);
        int sourceY = state.sourceWorldY() + bottomTile.screenY();
        int expected = (3 << 13) | ((((state.sourceWorldX() + bottomTile.screenX()) / 8)
                ^ (sourceY / 8)) & SAMPLE_PATTERN_MASK);
        assertEquals(expected, bottomTile.descriptor());
    }

    @Test
    public void compositionForcesFirePaletteLineRegardlessOfSampledDescriptorPalette() {
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer((worldX, worldY) ->
                (2 << 13) | (((worldX / 8) + (worldY / 8)) & SAMPLE_PATTERN_MASK));
        FireCurtainRenderState state = new FireCurtainRenderState(
                true,
                96,
                8,
                12,
                0x1000,
                0x0180,
                new int[20],
                FireCurtainStage.AIZ1_RISING);

        AizFireCurtainRenderer.CurtainCompositionPlan plan = renderer.buildCompositionPlan(state, 320, 224);

        assertFalse(plan.columns().isEmpty(),
                "an active rising curtain must emit visible columns to validate the palette force");
        for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
            for (AizFireCurtainRenderer.TileDraw draw : column.draws()) {
                assertEquals(3, (draw.descriptor() >> 13) & 0x3, "Curtain tiles must always use palette line 4");
            }
        }
    }

    @Test
    public void postMutationStagesWithoutCachedDescriptorsFailClosed() {
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        FireCurtainRenderState refresh = new FireCurtainRenderState(
                true,
                224,
                8,
                13,
                0x0200,
                0x0210,
                new int[20],
                FireCurtainStage.AIZ1_REFRESH,
                0x500,
                121);

        AizFireCurtainRenderer.CurtainCompositionPlan plan = renderer.buildCompositionPlan(refresh, 320, 224);
        assertTrue(plan.columns().isEmpty(),
                "Post-mutation must not synthesize fire descriptors when the ROM-backed cache is absent");
    }

    @Test
    public void negativeWaveOffsetDoesNotCreateGapAtCurtainTop() {
        AizFireCurtainRenderer renderer = rendererWithSampler();
        int[] waveOffsets = new int[20];
        for (int i = 0; i < 20; i++) {
            waveOffsets[i] = -15;
        }
        FireCurtainRenderState state = new FireCurtainRenderState(
                true, 224, 0, 8, 0x1000, 0x0180,
                waveOffsets, FireCurtainStage.AIZ1_RISING);

        AizFireCurtainRenderer.CurtainCompositionPlan plan =
                renderer.buildCompositionPlan(state, 320, 224);

        assertFalse(plan.columns().isEmpty(),
                "a fully-raised curtain with negative wave offsets must emit visible columns");
        for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
            assertTrue(column.topY() <= 0, "Column " + column.columnIndex()
                            + " has gap at top: topY=" + column.topY());
        }
    }

    @Test
    public void fireTilesWrapBeyondOriginalZoneBoundaries() {
        AizFireCurtainRenderer renderer = rendererWithSampler();
        FireCurtainRenderState state = new FireCurtainRenderState(
                true, 224, 0, 8, 0x1000, 0x0400,
                new int[20], FireCurtainStage.AIZ1_REFRESH,
                0x500, 121);

        AizFireCurtainRenderer.CurtainCompositionPlan plan =
                renderer.buildCompositionPlan(state, 320, 224);

        assertFalse(plan.columns().isEmpty(), "Should still produce fire tiles via wrapping when bgY > 0x310");
    }

    @Test
    public void act2ContinuationWrapsCachedCurtainBeforeWaitFireLatch() throws Exception {
        Sonic3kAIZEvents events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        markCachedFireDescriptors(renderer, 40);

        for (int phaseOrdinal : new int[] {4, 5}) {
            events.setFireSequencePhaseOrdinal(phaseOrdinal);
            events.setAct2WaitFireDrawActive(false);
            events.setFireBgCopyFixed(0x0400_0000);
            events.setFireTransitionFrames(240);
            events.setFireOverlayTileCount(121);

            FireCurtainRenderState state = events.getFireCurtainRenderState(224);
            assertTrue(state.active(), "AIZ2 continuation phase must keep the curtain active");
            assertTrue(state.wrapFireTiles(),
                    "AIZ2 fire continuation must wrap the cached curtain while FireRedraw/WaitFire is active");

            AizFireCurtainRenderer.CurtainCompositionPlan plan =
                    renderer.buildCompositionPlan(state, 320, 224);
            assertFalse(plan.columns().isEmpty(),
                    "AIZ2 continuation must render cached fire tiles after the rise crosses the fire-zone boundary");
        }
    }

    @Test
    public void latchedWaitFireReleasesCachedCurtainWithoutRefillingDenseBody() throws Exception {
        Sonic3kAIZEvents events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        markCachedFireDescriptors(renderer, 40);

        events.setFireSequencePhaseOrdinal(5);
        events.setAct2WaitFireDrawActive(true);
        events.setFireBgCopyFixed(0x02F0_0000);
        events.setFireTransitionFrames(0);
        events.setFireOverlayTileCount(121);

        FireCurtainRenderState state = events.getFireCurtainRenderState(224);
        assertTrue(state.active(), "Latched WaitFire must keep the curtain active during its outro");
        assertEquals(0x0200, state.sourceWorldX(),
                "Latched WaitFire must retain the ROM's AIZ2 source strip");
        assertFalse(state.wrapFireTiles(),
                "Latched WaitFire must let the trailing fire rows scroll off instead of refilling the body");

        AizFireCurtainRenderer.CurtainCompositionPlan plan =
                renderer.buildCompositionPlan(state, 320, 224);
        assertFalse(plan.columns().isEmpty(),
                "Latched WaitFire must render the finite trailing fire tail");
        for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
            for (AizFireCurtainRenderer.TileDraw draw : column.draws()) {
                assertTrue(draw.renderPatternId() >= 0x500 + 60
                                && draw.renderPatternId() < 0x500 + 66,
                        "Latched WaitFire must not wrap a row back into the dense curtain body: "
                                + Integer.toHexString(draw.renderPatternId()));
            }
        }
    }

    @Test
    public void act2ContinuationWithoutCachedDescriptorsFailsClosed() {
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        FireCurtainRenderState redraw = new FireCurtainRenderState(
                true,
                224,
                8,
                16,
                0x0200,
                0x0210,
                new int[20],
                FireCurtainStage.AIZ2_REDRAW,
                0x500,
                121);

        AizFireCurtainRenderer.CurtainCompositionPlan plan = renderer.buildCompositionPlan(redraw, 320, 224);
        assertTrue(plan.columns().isEmpty(),
                "Act 2 continuation must use cached ROM descriptors, not synthetic overlay tiles");
    }

    @ParameterizedTest
    @CsvSource({"320,40", "352,44", "400,50", "528,66", "800,100"})
    public void cachedCurtainCoversTheEntireConfiguredViewport(int screenWidth, int cacheColumns)
            throws Exception {
        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        markCachedFireDescriptors(renderer, cacheColumns);
        FireCurtainRenderState state = new FireCurtainRenderState(
                true,
                224,
                0,
                8,
                0x0200,
                0x0400,
                new int[20],
                FireCurtainStage.AIZ2_REDRAW,
                0x500,
                121);

        AizFireCurtainRenderer.CurtainCompositionPlan plan =
                renderer.buildCompositionPlan(state, screenWidth, 224);

        int rightmostDrawEdge = plan.columns().stream()
                .flatMap(column -> column.draws().stream())
                .mapToInt(draw -> draw.screenX() + 8)
                .max()
                .orElse(-1);
        assertTrue(rightmostDrawEdge >= screenWidth,
                "cached AIZ fire must fill the right edge at every configured viewport width");
    }

    private static void markCachedFireDescriptors(AizFireCurtainRenderer renderer, int cacheColumns)
            throws Exception {
        Field descriptors = AizFireCurtainRenderer.class.getDeclaredField("cachedFireDescriptors");
        descriptors.setAccessible(true);
        int[][] cached = new int[66][cacheColumns];
        for (int row = 0; row < cached.length; row++) {
            for (int column = 0; column < cached[row].length; column++) {
                cached[row][column] = (3 << 13) | (0x500 + row);
            }
        }
        descriptors.set(renderer, cached);

        Field cachedFlag = AizFireCurtainRenderer.class.getDeclaredField("fireDescriptorsCached");
        cachedFlag.setAccessible(true);
        cachedFlag.setBoolean(renderer, true);
    }
}
