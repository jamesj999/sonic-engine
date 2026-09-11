package com.openggf.level.scroll.compose;

import java.util.Arrays;

/**
 * Applies a contiguous source table into sparse target indices.
 */
public final class ScatterFillPlan {

    private final int[] targetIndices;

    public ScatterFillPlan(int... targetIndices) {
        this.targetIndices = Arrays.copyOf(targetIndices, targetIndices.length);
        validateNoDuplicates();
    }

    public void apply(ScrollValueTable source, ScrollValueTable target) {
        if (source.size() != targetIndices.length) {
            throw new IllegalArgumentException("source size must match scatter target count");
        }

        boolean[] seen = new boolean[target.size()];
        for (int i = 0; i < targetIndices.length; i++) {
            int targetIndex = targetIndices[i];
            validateTargetIndex(targetIndex, target.size());
            if (seen[targetIndex]) {
                throw new IllegalArgumentException("duplicate target index: " + targetIndex);
            }
            seen[targetIndex] = true;
            target.set(targetIndex, source.get(i));
        }
    }

    private void validateNoDuplicates() {
        for (int i = 0; i < targetIndices.length; i++) {
            for (int j = i + 1; j < targetIndices.length; j++) {
                if (targetIndices[i] == targetIndices[j]) {
                    throw new IllegalArgumentException("duplicate target index: " + targetIndices[i]);
                }
            }
        }
    }

    private void validateTargetIndex(int targetIndex, int targetSize) {
        if (targetIndex < 0 || targetIndex >= targetSize) {
            throw new IllegalArgumentException("target index out of range: " + targetIndex);
        }
    }
}
