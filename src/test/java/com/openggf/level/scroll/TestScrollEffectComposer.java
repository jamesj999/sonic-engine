package com.openggf.level.scroll;

import com.openggf.level.scroll.compose.DeformationPlan;
import com.openggf.level.scroll.compose.ScrollComposeContext;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import com.openggf.level.scroll.compose.ScrollValueTable;
import com.openggf.level.scroll.compose.ScatterFillPlan;
import org.junit.jupiter.api.Test;

import static com.openggf.level.scroll.M68KMath.packScrollWords;
import static com.openggf.level.scroll.M68KMath.unpackBG;
import static com.openggf.level.scroll.M68KMath.unpackFG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestScrollEffectComposer {

    @Test
    public void constantBandWritesExpectedVisibleScanlines() {
        ScrollEffectComposer composer = new ScrollEffectComposer();
        DeformationPlan plan = DeformationPlan.sequence(
                DeformationPlan.constantBand(6, (short) 0x0010),
                DeformationPlan.constantBand(4, (short) 0x0020)
        );

        int written = plan.apply(composer, new ScrollComposeContext(0, 0, 0, 0), 0, (short) 0x0130);

        assertEquals(ScrollEffectComposer.VISIBLE_LINES, written);
        for (int line = 0; line < 6; line++) {
            assertEquals(packScrollWords((short) 0x0130, (short) 0x0010), composer.packedScrollWordAt(line));
        }
        for (int line = 6; line < 10; line++) {
            assertEquals(packScrollWords((short) 0x0130, (short) 0x0020), composer.packedScrollWordAt(line));
        }
        for (int line = 10; line < ScrollEffectComposer.VISIBLE_LINES; line++) {
            assertEquals(packScrollWords((short) 0x0130, (short) 0x0020), composer.packedScrollWordAt(line));
        }
    }

    @Test
    public void perLineBandConsumesOneSourceValuePerLine() {
        ScrollEffectComposer composer = new ScrollEffectComposer();
        DeformationPlan plan = DeformationPlan.perLineBand(new short[] {
                0x0007, 0x0008, 0x0009, 0x000A
        });

        plan.apply(composer, new ScrollComposeContext(0, 0, 0, 0), 0, (short) 0x0120);

        assertEquals((short) 0x0007, unpackBG(composer.packedScrollWordAt(0)));
        assertEquals((short) 0x0008, unpackBG(composer.packedScrollWordAt(1)));
        assertEquals((short) 0x0009, unpackBG(composer.packedScrollWordAt(2)));
        assertEquals((short) 0x000A, unpackBG(composer.packedScrollWordAt(3)));
    }

    @Test
    public void explicitDeformationSkipStartsInsideBand() {
        ScrollEffectComposer composer = new ScrollEffectComposer();
        DeformationPlan plan = DeformationPlan.sequence(
                DeformationPlan.constantBand(3, (short) 0x0010),
                DeformationPlan.perLineBand(new short[] {0x0020, 0x0021, 0x0022}),
                DeformationPlan.constantBand(2, (short) 0x0030)
        );

        plan.apply(composer, new ScrollComposeContext(0, 999, 0, 0), 4, (short) 0x0100);

        assertEquals((short) 0x0021, unpackBG(composer.packedScrollWordAt(0)));
        assertEquals((short) 0x0022, unpackBG(composer.packedScrollWordAt(1)));
        assertEquals((short) 0x0030, unpackBG(composer.packedScrollWordAt(2)));
        assertEquals((short) 0x0030, unpackBG(composer.packedScrollWordAt(3)));
    }

    @Test
    public void scatterFillWritesValuesIntoNonContiguousSlots() {
        ScrollValueTable source = ScrollValueTable.from((short) 11, (short) 12, (short) 13);
        ScrollValueTable target = ScrollValueTable.ofLength(6);
        ScatterFillPlan plan = new ScatterFillPlan(4, 1, 5);

        plan.apply(source, target);

        assertArrayEquals(new short[] {0, 12, 0, 0, 11, 13}, target.toArray());
    }

    @Test
    public void scatterFillRejectsMismatchedSourceShape() {
        ScrollValueTable source = ScrollValueTable.from((short) 1, (short) 2);
        ScrollValueTable target = ScrollValueTable.ofLength(4);
        ScatterFillPlan plan = new ScatterFillPlan(3, 1, 0);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> plan.apply(source, target));
    }

    @Test
    public void scatterFillRejectsDuplicateAndOutOfRangeTargets() {
        ScrollValueTable source = ScrollValueTable.from((short) 1, (short) 2, (short) 3);
        ScrollValueTable target = ScrollValueTable.ofLength(3);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ScatterFillPlan(0, 1, 1).apply(source, target));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ScatterFillPlan(0, 1, 3).apply(source, target));
    }

    @Test
    public void deformationTransformHookCanNegateValues() {
        ScrollEffectComposer composer = new ScrollEffectComposer();
        DeformationPlan plan = DeformationPlan.constantBand(2, (short) 0x0011);

        plan.apply(composer, new ScrollComposeContext(0, 0, 0, 0), 0, (short) 0x0120,
                value -> (short) -value);

        assertEquals(packScrollWords((short) 0x0120, (short) 0xFFEF), composer.packedScrollWordAt(0));
        assertEquals(packScrollWords((short) 0x0120, (short) 0xFFEF), composer.packedScrollWordAt(1));
    }

    @Test
    public void skipPastEndKeepsLastAuthoredBandValue() {
        ScrollEffectComposer composer = new ScrollEffectComposer();
        DeformationPlan plan = DeformationPlan.sequence(
                DeformationPlan.constantBand(2, (short) 0x0010),
                DeformationPlan.perLineBand(new short[] {0x0020, 0x0021})
        );

        plan.apply(composer, new ScrollComposeContext(0, 0, 0, 0), 99, (short) 0x0100);

        for (int line = 0; line < ScrollEffectComposer.VISIBLE_LINES; line++) {
            assertEquals(packScrollWords((short) 0x0100, (short) 0x0021), composer.packedScrollWordAt(line));
        }
    }

    @Test
    public void composerTracksOffsetBoundsAndVScrollArrays() {
        ScrollEffectComposer composer = new ScrollEffectComposer();
        composer.writePackedScrollWord(0, (short) 10, (short) 13);
        composer.writePackedScrollWord(1, (short) 10, (short) 2);
        composer.writePackedScrollWord(2, (short) -1, (short) 5);

        assertEquals(-8, composer.getMinScrollOffset());
        assertEquals(6, composer.getMaxScrollOffset());
        assertEquals(packScrollWords((short) 10, (short) 13), composer.packedScrollWordAt(0));
        assertEquals(packScrollWords((short) 10, (short) 2), composer.packedScrollWordAt(1));
        assertEquals(packScrollWords((short) -1, (short) 5), composer.packedScrollWordAt(2));

        short[] perLine = {(short) 1, (short) 2, (short) 3};
        short[] perColumnBg = {(short) 4, (short) 5};
        short[] perColumnFg = {(short) 6, (short) 7};
        composer.setPerLineVScrollBG(perLine);
        composer.setPerColumnVScrollBG(perColumnBg);
        composer.setPerColumnVScrollFG(perColumnFg);
        composer.setVscrollFactorBG((short) 9);
        composer.setVscrollFactorFG((short) 10);

        assertArrayEquals(perLine, composer.getPerLineVScrollBG());
        assertArrayEquals(perColumnBg, composer.getPerColumnVScrollBG());
        assertArrayEquals(perColumnFg, composer.getPerColumnVScrollFG());
        assertEquals((short) 9, composer.getVscrollFactorBG());
        assertEquals((short) 10, composer.getVscrollFactorFG());
    }

    @Test
    public void copyHelpersPreservePackedScrollAndVScrollBuffers() {
        ScrollEffectComposer composer = new ScrollEffectComposer();
        composer.writePackedScrollWord(0, (short) 10, (short) 13);
        composer.writePackedScrollWord(1, (short) 10, (short) 2);
        composer.writePackedScrollWord(2, (short) -1, (short) 5);
        composer.setPerLineVScrollBG(new short[] {(short) 1, (short) 2, (short) 3});
        composer.setPerColumnVScrollBG(new short[] {(short) 4, (short) 5});
        composer.setPerColumnVScrollFG(new short[] {(short) 6, (short) 7});

        int[] packed = new int[composer.visibleLineCount()];
        short[] perLine = new short[3];
        short[] perColumnBg = new short[2];
        short[] perColumnFg = new short[2];

        composer.copyPackedScrollWordsTo(packed);
        composer.copyPerLineVScrollBGTo(perLine);
        composer.copyPerColumnVScrollBGTo(perColumnBg);
        composer.copyPerColumnVScrollFGTo(perColumnFg);

        assertEquals(packScrollWords((short) 10, (short) 13), packed[0]);
        assertEquals(packScrollWords((short) 10, (short) 2), packed[1]);
        assertEquals(packScrollWords((short) -1, (short) 5), packed[2]);
        assertArrayEquals(new short[] {(short) 1, (short) 2, (short) 3}, perLine);
        assertArrayEquals(new short[] {(short) 4, (short) 5}, perColumnBg);
        assertArrayEquals(new short[] {(short) 6, (short) 7}, perColumnFg);
    }

    @Test
    public void composerStoresShakeOffsets() {
        ScrollEffectComposer composer = new ScrollEffectComposer();

        composer.setShakeOffsetX(6);
        composer.setShakeOffsetY(-3);

        assertEquals(6, composer.getShakeOffsetX());
        assertEquals(-3, composer.getShakeOffsetY());
    }
}
