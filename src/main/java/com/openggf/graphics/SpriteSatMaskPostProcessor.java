package com.openggf.graphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a narrow ROM-style sprite-mask post-pass to SAT-like mapping-piece
 * entries before they are expanded into 8x8 tiles.
 *
 * <p>The S3K post-pass scans built SAT entries for tile {@code 0x7C0}, then
 * converts that pair into a hardware sprite mask at SAT X={@code 1}/{@code 0}.
 * In the software renderer we model the same effect as a vertical scanline band
 * that suppresses later SAT entries on overlapping rows. The mask pair itself is
 * not drawn.</p>
 */
public final class SpriteSatMaskPostProcessor {

    private static final int MASK_TILE_WORD = 0x7C0;
    private static final int TILE_HEIGHT = 8;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private SpriteSatMaskPostProcessor() {
    }

    /**
     * Returns a retainable fresh result when masking is enabled. When masking is
     * off (or the input is empty), the input list itself is returned for legacy
     * identity compatibility.
     */
    public static List<SpriteSatEntry> process(List<SpriteSatEntry> entries, boolean spriteMaskEnabled) {
        if (!spriteMaskEnabled || entries == null || entries.isEmpty()) {
            return entries == null ? List.of() : entries;
        }
        return new ArrayList<>(processReusable(entries, true));
    }

    /**
     * Allocation-minimized synchronous render path. Masked results are backed by
     * thread-owned scratch and are invalidated by the next masked call on the
     * same thread; callers must consume them before calling this method again.
     */
    static List<SpriteSatEntry> processReusable(List<SpriteSatEntry> entries, boolean spriteMaskEnabled) {
        if (!spriteMaskEnabled || entries == null || entries.isEmpty()) {
            return entries == null ? List.of() : entries;
        }

        Scratch scratch = SCRATCH.get();
        scratch.reset();
        int preMaskInsertIndex = -1;

        for (int i = 0; i < entries.size(); i++) {
            SpriteSatEntry entry = entries.get(i);
            if (isMaskMarker(entries, i)) {
                SpriteSatEntry companion = entries.get(i + 1);
                int startY = Math.max(entry.y(), companion.y());
                int endYExclusive = Math.min(entry.endYExclusive(), companion.endYExclusive());
                if (startY < endYExclusive) {
                    scratch.addBand(startY, endYExclusive);
                    if (preMaskInsertIndex < 0) {
                        preMaskInsertIndex = scratch.processed.size();
                    }
                }
                i++; // The helper pair becomes the mask; neither piece replays visibly.
                continue;
            }

            if (entry.maskReplayRole() == SpriteMaskReplayRole.PRE_MASK_FRONT && preMaskInsertIndex >= 0) {
                scratch.processed.add(preMaskInsertIndex, entry);
                preMaskInsertIndex++;
                continue;
            }
            clipAgainstBands(entry, scratch);
        }

        return scratch.processed;
    }

    private static boolean isMaskMarker(List<SpriteSatEntry> entries, int index) {
        return index + 1 < entries.size() && entries.get(index).tileWordLow11() == MASK_TILE_WORD;
    }

    private static void clipAgainstBands(SpriteSatEntry entry, Scratch scratch) {
        scratch.rangeA[0] = entry.startRowTile();
        scratch.rangeA[1] = entry.startRowTile() + entry.rowCountTiles();
        int[] remaining = scratch.rangeA;
        int remainingCount = 1;
        int[] next = scratch.rangeB;

        for (int bandIndex = 0; bandIndex < scratch.bandCount; bandIndex++) {
            int removeStartRow = Math.max(entry.startRowTile(),
                    floorDiv(scratch.bandStarts[bandIndex] - entry.y(), TILE_HEIGHT));
            int removeEndRow = Math.min(
                    entry.startRowTile() + entry.rowCountTiles(),
                    ceilDiv(scratch.bandEnds[bandIndex] - entry.y(), TILE_HEIGHT));
            if (removeStartRow >= removeEndRow) {
                continue;
            }
            boolean usingA = remaining == scratch.rangeA;
            scratch.ensureRangeCapacity((remainingCount + 1) * 2);
            remaining = usingA ? scratch.rangeA : scratch.rangeB;
            if (usingA) {
                next = scratch.rangeB;
            } else {
                next = scratch.rangeA;
            }
            int nextCount = 0;
            for (int i = 0; i < remainingCount; i++) {
                int start = remaining[i * 2];
                int end = remaining[i * 2 + 1];
                if (removeEndRow <= start || removeStartRow >= end) {
                    next[nextCount * 2] = start;
                    next[nextCount * 2 + 1] = end;
                    nextCount++;
                    continue;
                }
                if (removeStartRow > start) {
                    next[nextCount * 2] = start;
                    next[nextCount * 2 + 1] = removeStartRow;
                    nextCount++;
                }
                if (removeEndRow < end) {
                    next[nextCount * 2] = removeEndRow;
                    next[nextCount * 2 + 1] = end;
                    nextCount++;
                }
            }
            remaining = next;
            remainingCount = nextCount;
            if (remainingCount == 0) {
                break;
            }
        }

        for (int i = 0; i < remainingCount; i++) {
            int start = remaining[i * 2];
            int rowCount = remaining[i * 2 + 1] - start;
            if (rowCount > 0) {
                scratch.processed.add(entry.clipRows(start, rowCount));
            }
        }
    }

    private static int floorDiv(int numerator, int denominator) {
        return Math.floorDiv(numerator, denominator);
    }

    private static int ceilDiv(int numerator, int denominator) {
        return -Math.floorDiv(-numerator, denominator);
    }

    private static final class Scratch {
        private final ArrayList<SpriteSatEntry> processed = new ArrayList<>();
        private int[] bandStarts = new int[8];
        private int[] bandEnds = new int[8];
        private int bandCount;
        private int[] rangeA = new int[8];
        private int[] rangeB = new int[8];

        private void reset() {
            processed.clear();
            bandCount = 0;
        }

        private void addBand(int start, int end) {
            if (bandCount == bandStarts.length) {
                int size = bandStarts.length * 2;
                bandStarts = java.util.Arrays.copyOf(bandStarts, size);
                bandEnds = java.util.Arrays.copyOf(bandEnds, size);
            }
            bandStarts[bandCount] = start;
            bandEnds[bandCount] = end;
            bandCount++;
        }

        private void ensureRangeCapacity(int required) {
            if (rangeA.length >= required) return;
            int size = Math.max(required, rangeA.length * 2);
            rangeA = java.util.Arrays.copyOf(rangeA, size);
            rangeB = java.util.Arrays.copyOf(rangeB, size);
        }
    }
}
