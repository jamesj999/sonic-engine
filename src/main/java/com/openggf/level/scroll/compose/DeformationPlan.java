package com.openggf.level.scroll.compose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Declarative frame-local deformation bands.
 *
 * <p>A plan describes how a sequence of background scroll values should be expanded across the
 * visible scanlines for one frame. Callers can build plans from constant bands, per-line tables,
 * or by concatenating smaller plans before writing them through {@link ScrollEffectComposer}.
 */
public final class DeformationPlan {

    private final List<Band> bands;

    private DeformationPlan(List<Band> bands) {
        this.bands = bands;
    }

    /** Creates a plan whose entire band uses one background scroll value. */
    public static DeformationPlan constantBand(int lineCount, short bgScroll) {
        return new DeformationPlan(List.of(new ConstantBand(lineCount, bgScroll)));
    }

    /** Creates a plan that writes one explicit background scroll value per scanline. */
    public static DeformationPlan perLineBand(short[] bgScrollValues) {
        return new DeformationPlan(List.of(new PerLineBand(Arrays.copyOf(bgScrollValues, bgScrollValues.length))));
    }

    /** Concatenates multiple plans in order to build a larger deform sequence. */
    public static DeformationPlan sequence(DeformationPlan... plans) {
        List<Band> merged = new ArrayList<>();
        for (DeformationPlan plan : plans) {
            merged.addAll(plan.bands);
        }
        return new DeformationPlan(List.copyOf(merged));
    }

    /** Applies the plan using the identity scroll transform. */
    public int apply(ScrollEffectComposer composer, ScrollComposeContext context, int deformationSkipLines, short fgScroll) {
        return apply(composer, context, deformationSkipLines, fgScroll, ScrollValueTransform.identity());
    }

    public int apply(ScrollEffectComposer composer,
                     ScrollComposeContext context,
                     int deformationSkipLines,
                     short fgScroll,
                     ScrollValueTransform valueTransform) {
        int skip = Math.max(0, deformationSkipLines);
        int line = 0;
        short fallbackBg = 0;
        short lastBandValue = 0;
        boolean hasBand = false;
        boolean wroteAny = false;

        for (Band band : bands) {
            if (line >= composer.visibleLineCount()) {
                break;
            }

            short bandTail = valueTransform.transform(band.lastValue());
            lastBandValue = bandTail;
            hasBand = true;

            int consumed = Math.min(skip, band.lineCount());
            skip -= consumed;

            if (consumed == band.lineCount()) {
                continue;
            }

            if (band instanceof ConstantBand constantBand) {
                short bgScroll = valueTransform.transform(constantBand.bgScroll());
                int writeCount = constantBand.lineCount() - consumed;
                composer.fillPackedScrollWords(line, writeCount, fgScroll, bgScroll);
                line += writeCount;
                fallbackBg = bgScroll;
                wroteAny = true;
                continue;
            }

            PerLineBand perLineBand = (PerLineBand) band;
            short[] values = perLineBand.values();
            for (int i = consumed; i < values.length && line < composer.visibleLineCount(); i++) {
                short bgScroll = valueTransform.transform(values[i]);
                composer.writePackedScrollWord(line++, fgScroll, bgScroll);
                fallbackBg = bgScroll;
                wroteAny = true;
            }
        }

        if (!wroteAny) {
            fallbackBg = hasBand ? lastBandValue : 0;
        }

        while (line < composer.visibleLineCount()) {
            composer.writePackedScrollWord(line++, fgScroll, fallbackBg);
        }
        return composer.visibleLineCount();
    }

    public static int applyTableBands(ScrollEffectComposer composer,
                                      int deformationBaseY,
                                      short fgScroll,
                                      ScrollValueTable sourceValues,
                                      int[] deformHeights,
                                      int tableStartIndex) {
        return applyTableBands(composer, deformationBaseY, fgScroll, sourceValues, deformHeights, tableStartIndex,
                ScrollValueTransform.identity());
    }

    public static int applyTableBands(ScrollEffectComposer composer,
                                      int deformationBaseY,
                                      short fgScroll,
                                      ScrollValueTable sourceValues,
                                      int[] deformHeights,
                                      int tableStartIndex,
                                      ScrollValueTransform valueTransform) {
        int segmentIndex = 0;
        int tableIndex = tableStartIndex;
        int y = (short) deformationBaseY;

        int height = nextHeight(deformHeights, segmentIndex++);
        while ((y - height) >= 0) {
            y -= height;
            tableIndex++;
            height = nextHeight(deformHeights, segmentIndex++);
        }
        y -= height;

        int line = 0;
        int firstCount = -y;
        short bgScroll = valueTransform.transform(readTableValue(sourceValues, tableIndex));
        line = fillLines(composer, line, firstCount, fgScroll, bgScroll);

        while (line < composer.visibleLineCount()) {
            int count = nextHeight(deformHeights, segmentIndex++);
            bgScroll = valueTransform.transform(readTableValue(sourceValues, ++tableIndex));
            line = fillLines(composer, line, count, fgScroll, bgScroll);
        }
        return composer.visibleLineCount();
    }

    public static int applyFlaggedTableBands(ScrollEffectComposer composer,
                                             int deformationBaseY,
                                             short fgScroll,
                                             ScrollValueTable sourceValues,
                                             int[] deformHeights,
                                             int tableStartIndex,
                                             ScrollValueTransform valueTransform) {
        int bandIndex = 0;
        int valueIndex = tableStartIndex;
        int remainingY = (short) deformationBaseY;
        int line = 0;
        short fallbackBg = valueTransform.transform(readTableValue(sourceValues, valueIndex));

        while (bandIndex < deformHeights.length) {
            int rawHeight = deformHeights[bandIndex];
            boolean perLine = (rawHeight & 0x8000) != 0;
            int height = rawHeight & 0x7FFF;
            if (height == 0) {
                height = 1;
            }

            int nextY = remainingY - height;
            if (nextY < 0) {
                int invisibleLines = Math.max(0, remainingY);
                int visibleLines = -nextY;
                if (perLine) {
                    valueIndex += invisibleLines;
                    for (int i = 0; i < visibleLines && line < composer.visibleLineCount(); i++) {
                        short bgScroll = valueTransform.transform(readTableValue(sourceValues, valueIndex++));
                        composer.writePackedScrollWord(line++, fgScroll, bgScroll);
                        fallbackBg = bgScroll;
                    }
                } else {
                    short bgScroll = valueTransform.transform(readTableValue(sourceValues, valueIndex++));
                    line = fillLines(composer, line, visibleLines, fgScroll, bgScroll);
                    fallbackBg = bgScroll;
                }
                bandIndex++;
                break;
            }

            if (perLine) {
                int lastSkippedIndex = Math.max(valueIndex, valueIndex + height - 1);
                fallbackBg = valueTransform.transform(readTableValue(sourceValues, lastSkippedIndex));
                valueIndex += height;
            } else {
                fallbackBg = valueTransform.transform(readTableValue(sourceValues, valueIndex));
                valueIndex++;
            }
            remainingY = nextY;
            bandIndex++;
        }

        while (line < composer.visibleLineCount() && bandIndex < deformHeights.length) {
            int rawHeight = deformHeights[bandIndex++];
            boolean perLine = (rawHeight & 0x8000) != 0;
            int height = rawHeight & 0x7FFF;
            if (height == 0) {
                height = 1;
            }
            int count = Math.min(height, composer.visibleLineCount() - line);
            if (perLine) {
                for (int i = 0; i < count && line < composer.visibleLineCount(); i++) {
                    short bgScroll = valueTransform.transform(readTableValue(sourceValues, valueIndex++));
                    composer.writePackedScrollWord(line++, fgScroll, bgScroll);
                    fallbackBg = bgScroll;
                }
            } else {
                short bgScroll = valueTransform.transform(readTableValue(sourceValues, valueIndex++));
                line = fillLines(composer, line, count, fgScroll, bgScroll);
                fallbackBg = bgScroll;
            }
        }

        while (line < composer.visibleLineCount()) {
            composer.writePackedScrollWord(line++, fgScroll, fallbackBg);
        }
        return composer.visibleLineCount();
    }

    @FunctionalInterface
    public interface ScrollValueTransform {
        short transform(short value);

        static ScrollValueTransform identity() {
            return value -> value;
        }
    }

    private sealed interface Band permits ConstantBand, PerLineBand {
        int lineCount();

        short lastValue();
    }

    private record ConstantBand(int lineCount, short bgScroll) implements Band {
        private ConstantBand {
            if (lineCount < 0) {
                throw new IllegalArgumentException("lineCount must be non-negative");
            }
        }

        @Override
        public short lastValue() {
            return bgScroll;
        }
    }

    private record PerLineBand(short[] values) implements Band {
        private PerLineBand {
            if (values == null) {
                throw new IllegalArgumentException("values must not be null");
            }
        }

        @Override
        public int lineCount() {
            return values.length;
        }

        @Override
        public short lastValue() {
            return values.length == 0 ? 0 : values[values.length - 1];
        }
    }

    private static int nextHeight(int[] deformHeights, int index) {
        if (index >= deformHeights.length) {
            return 0x7FFF;
        }
        int value = deformHeights[index] & 0x7FFF;
        return value == 0 ? 1 : value;
    }

    private static short readTableValue(ScrollValueTable sourceValues, int index) {
        if (sourceValues.size() == 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(index, sourceValues.size() - 1));
        return sourceValues.get(clamped);
    }

    private static int fillLines(ScrollEffectComposer composer,
                                 int startLine,
                                 int lineCount,
                                 short fgScroll,
                                 short bgScroll) {
        if (lineCount <= 0 || startLine >= composer.visibleLineCount()) {
            return startLine;
        }
        composer.fillPackedScrollWords(startLine, lineCount, fgScroll, bgScroll);
        return Math.min(composer.visibleLineCount(), startLine + lineCount);
    }
}
