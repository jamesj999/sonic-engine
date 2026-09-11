package com.openggf.level.scroll.compose;

/**
 * Narrow helper for HCZ/LBZ-style midpoint waterline blending.
 *
 * <p>The table is assumed to already contain any gradient source values.
 * This helper only applies the visible lookup remap near the midpoint and the
 * above-water / below-water flat fills that bracket it.
 */
public final class WaterlineBlendComposer {

    private final int upperStartIndex;
    private final int midpointIndex;
    private final int lowerEndExclusive;
    private final int lookupStride;

    public WaterlineBlendComposer(int upperStartIndex,
                                  int midpointIndex,
                                  int lowerEndExclusive,
                                  int lookupStride) {
        if (upperStartIndex < 0 || midpointIndex < upperStartIndex || lowerEndExclusive <= midpointIndex) {
            throw new IllegalArgumentException("Invalid midpoint range");
        }
        if (lookupStride <= 0) {
            throw new IllegalArgumentException("lookupStride must be positive");
        }
        this.upperStartIndex = upperStartIndex;
        this.midpointIndex = midpointIndex;
        this.lowerEndExclusive = lowerEndExclusive;
        this.lookupStride = lookupStride;
    }

    /**
     * Applies the midpoint lookup and the surrounding flat fills to the supplied table.
     *
     * <p>{@code equilibriumDelta} controls whether the lookup expands upward or downward from the
     * midpoint, matching the ROM's waterline blend behavior when the split shifts relative to the
     * camera.
     */
    public void apply(ScrollValueTable table,
                      short equilibriumDelta,
                      short aboveWaterValue,
                      short belowWaterValue,
                      byte[] lookupData) {
        if (equilibriumDelta < 0) {
            applyBackwardLookup(table, equilibriumDelta, lookupData);
            fillRange(table, midpointIndex, lowerEndExclusive, belowWaterValue);
            int count = lookupStride - 1 + equilibriumDelta;
            if (count >= 0) {
                fillRange(table, upperStartIndex, upperStartIndex + count + 1, aboveWaterValue);
            }
            return;
        }

        applyForwardLookup(table, equilibriumDelta, lookupData);
        fillRange(table, upperStartIndex, midpointIndex, aboveWaterValue);
        int count = lookupStride - 1 - equilibriumDelta;
        if (count < 0) {
            return;
        }
        int writeIndex = lowerEndExclusive;
        for (int i = 0; i <= count && writeIndex > midpointIndex; i++) {
            table.set(--writeIndex, belowWaterValue);
        }
    }

    private void applyForwardLookup(ScrollValueTable table, short equilibriumDelta, byte[] lookupData) {
        if (lookupData == null || equilibriumDelta <= 0 || equilibriumDelta >= lookupStride) {
            return;
        }
        int dataOffset = (lookupStride - equilibriumDelta) * lookupStride;
        int count = Math.min(equilibriumDelta, Math.max(0, table.size() - midpointIndex));
        for (int i = 0; i < count; i++) {
            int lookupIndex = dataOffset + i;
            if (lookupIndex >= lookupData.length) {
                break;
            }
            int readIndex = midpointIndex + (lookupData[lookupIndex] & 0xFF);
            if (readIndex >= 0 && readIndex < table.size()) {
                table.set(midpointIndex + i, table.get(readIndex));
            }
        }
    }

    private void applyBackwardLookup(ScrollValueTable table, short equilibriumDelta, byte[] lookupData) {
        if (lookupData == null || equilibriumDelta >= 0 || -equilibriumDelta >= lookupStride) {
            return;
        }
        int dataOffset = (equilibriumDelta + lookupStride) * lookupStride;
        int count = Math.min(-equilibriumDelta, midpointIndex);
        int writeIndex = midpointIndex;
        for (int i = 0; i < count; i++) {
            int lookupIndex = dataOffset + i;
            if (lookupIndex >= lookupData.length) {
                break;
            }
            int readIndex = midpointIndex + (lookupData[lookupIndex] & 0xFF);
            writeIndex--;
            if (readIndex >= 0 && readIndex < table.size() && writeIndex >= 0) {
                table.set(writeIndex, table.get(readIndex));
            }
        }
    }

    private static void fillRange(ScrollValueTable table, int fromInclusive, int toExclusive, short value) {
        int start = Math.max(0, fromInclusive);
        int end = Math.min(table.size(), toExclusive);
        for (int index = start; index < end; index++) {
            table.set(index, value);
        }
    }
}
