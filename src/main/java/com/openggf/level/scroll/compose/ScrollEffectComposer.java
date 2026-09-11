package com.openggf.level.scroll.compose;

import com.openggf.level.scroll.M68KMath;

import java.util.Arrays;

/**
 * Frame-local writer for packed scroll output and optional VScroll overlays.
 *
 * <p>Zone scroll handlers populate this composer with horizontal scroll words, vertical scroll
 * factors, optional per-line/per-column overrides, and shake offsets. The handler can then expose
 * the composed output back through the existing scroll-handler interface without reimplementing
 * the same buffer-management logic in each zone.
 */
public final class ScrollEffectComposer {

    public static final int VISIBLE_LINES = M68KMath.VISIBLE_LINES;
    public static final int PER_COLUMN_VSCROLL_COLUMNS = 20;

    private final int[] packedScrollWords;
    private int minScrollOffset;
    private int maxScrollOffset;
    private short vscrollFactorBG;
    private short vscrollFactorFG;
    private int shakeOffsetX;
    private int shakeOffsetY;
    private short[] perLineVScrollBG;
    private short[] perColumnVScrollBG;
    private short[] perColumnVScrollFG;
    private boolean hasPerColumnVScrollBG;

    public ScrollEffectComposer() {
        this(VISIBLE_LINES);
    }

    /** Creates a composer sized for a specific visible-line count. */
    public ScrollEffectComposer(int visibleLines) {
        if (visibleLines < 0) {
            throw new IllegalArgumentException("visibleLines must be non-negative");
        }
        this.packedScrollWords = new int[visibleLines];
        reset();
    }

    /** Clears all composed state so the instance can be reused for the next frame. */
    public void reset() {
        Arrays.fill(packedScrollWords, 0);
        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;
        vscrollFactorBG = 0;
        vscrollFactorFG = 0;
        shakeOffsetX = 0;
        shakeOffsetY = 0;
        perLineVScrollBG = null;
        perColumnVScrollFG = null;
        hasPerColumnVScrollBG = false;
    }

    public int visibleLineCount() {
        return packedScrollWords.length;
    }

    /**
     * Returns the live packed scroll buffer for zero-allocation read access.
     *
     * <p>Callers must treat the returned array as read-only. Use
     * {@link #copyPackedScrollWordsTo(int[])} when a defensive copy is needed.
     */
    public int[] packedScrollWordsView() {
        return packedScrollWords;
    }

    public int[] packedScrollWords() {
        return Arrays.copyOf(packedScrollWords, packedScrollWords.length);
    }

    public void copyPackedScrollWordsTo(int[] target) {
        if (target.length < packedScrollWords.length) {
            throw new IllegalArgumentException("target length must be at least " + packedScrollWords.length);
        }
        System.arraycopy(packedScrollWords, 0, target, 0, packedScrollWords.length);
    }

    public int packedScrollWordAt(int line) {
        checkLine(line);
        return packedScrollWords[line];
    }

    public void writePackedScrollWord(int line, short fgScroll, short bgScroll) {
        checkLine(line);
        packedScrollWords[line] = M68KMath.packScrollWords(fgScroll, bgScroll);
        trackOffset(fgScroll, bgScroll);
    }

    /**
     * Writes a pre-packed (FG << 16 | BG) longword for a single scanline and tracks the
     * resulting BG-FG offset.
     */
    public void writePackedScrollWord(int line, int packedWord) {
        checkLine(line);
        packedScrollWords[line] = packedWord;
        short fgScroll = (short) (packedWord >> 16);
        short bgScroll = (short) packedWord;
        trackOffset(fgScroll, bgScroll);
    }

    /**
     * Fills a contiguous range of scanlines with the same pre-packed scroll word.
     */
    public void fillPackedScrollWords(int startLine, int lineCount, int packedWord) {
        if (lineCount <= 0) {
            return;
        }
        int end = Math.min(packedScrollWords.length, startLine + lineCount);
        int begin = Math.max(0, startLine);
        for (int line = begin; line < end; line++) {
            packedScrollWords[line] = packedWord;
        }
        if (end > begin) {
            short fgScroll = (short) (packedWord >> 16);
            short bgScroll = (short) packedWord;
            trackOffset(fgScroll, bgScroll);
        }
    }

    public void fillPackedScrollWords(int startLine, int lineCount, short fgScroll, short bgScroll) {
        if (lineCount <= 0) {
            return;
        }
        int end = Math.min(packedScrollWords.length, startLine + lineCount);
        for (int line = Math.max(0, startLine); line < end; line++) {
            writePackedScrollWord(line, fgScroll, bgScroll);
        }
    }

    public int getMinScrollOffset() {
        return minScrollOffset;
    }

    public int getMaxScrollOffset() {
        return maxScrollOffset;
    }

    public void recalculateTrackedOffsets() {
        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;
        for (int packedScrollWord : packedScrollWords) {
            short fgScroll = (short) (packedScrollWord >> 16);
            short bgScroll = (short) packedScrollWord;
            trackOffset(fgScroll, bgScroll);
        }
    }

    public short getVscrollFactorBG() {
        return vscrollFactorBG;
    }

    public void setVscrollFactorBG(short vscrollFactorBG) {
        this.vscrollFactorBG = vscrollFactorBG;
    }

    public short getVscrollFactorFG() {
        return vscrollFactorFG;
    }

    public void setVscrollFactorFG(short vscrollFactorFG) {
        this.vscrollFactorFG = vscrollFactorFG;
    }

    public int getShakeOffsetX() {
        return shakeOffsetX;
    }

    public void setShakeOffsetX(int shakeOffsetX) {
        this.shakeOffsetX = shakeOffsetX;
    }

    public int getShakeOffsetY() {
        return shakeOffsetY;
    }

    public void setShakeOffsetY(int shakeOffsetY) {
        this.shakeOffsetY = shakeOffsetY;
    }

    public short[] getPerLineVScrollBG() {
        return copyOrNull(perLineVScrollBG);
    }

    public void copyPerLineVScrollBGTo(short[] target) {
        copyToTarget(perLineVScrollBG, target);
    }

    public void setPerLineVScrollBG(short[] perLineVScrollBG) {
        this.perLineVScrollBG = copyOrNull(perLineVScrollBG);
    }

    public short[] getPerColumnVScrollBG() {
        return hasPerColumnVScrollBG ? perColumnVScrollBG : null;
    }

    public void copyPerColumnVScrollBGTo(short[] target) {
        copyToTarget(hasPerColumnVScrollBG ? perColumnVScrollBG : null, target);
    }

    public void setPerColumnVScrollBG(short[] perColumnVScrollBG) {
        if (perColumnVScrollBG == null) {
            clearPerColumnVScrollBG();
            return;
        }
        if (this.perColumnVScrollBG == null || this.perColumnVScrollBG.length != perColumnVScrollBG.length) {
            this.perColumnVScrollBG = Arrays.copyOf(perColumnVScrollBG, perColumnVScrollBG.length);
        } else {
            System.arraycopy(perColumnVScrollBG, 0, this.perColumnVScrollBG, 0, perColumnVScrollBG.length);
        }
        hasPerColumnVScrollBG = true;
    }

    public short[] writablePerColumnVScrollBG(int columnCount) {
        if (columnCount < 0) {
            throw new IllegalArgumentException("columnCount must be non-negative");
        }
        if (perColumnVScrollBG == null || perColumnVScrollBG.length != columnCount) {
            perColumnVScrollBG = new short[columnCount];
        }
        hasPerColumnVScrollBG = true;
        return perColumnVScrollBG;
    }

    public void clearPerColumnVScrollBG() {
        hasPerColumnVScrollBG = false;
    }

    public short[] getPerColumnVScrollFG() {
        return copyOrNull(perColumnVScrollFG);
    }

    public void copyPerColumnVScrollFGTo(short[] target) {
        copyToTarget(perColumnVScrollFG, target);
    }

    public void setPerColumnVScrollFG(short[] perColumnVScrollFG) {
        this.perColumnVScrollFG = copyOrNull(perColumnVScrollFG);
    }

    private void trackOffset(short fgScroll, short bgScroll) {
        int offset = bgScroll - fgScroll;
        if (offset < minScrollOffset) {
            minScrollOffset = offset;
        }
        if (offset > maxScrollOffset) {
            maxScrollOffset = offset;
        }
    }

    private void checkLine(int line) {
        if (line < 0 || line >= packedScrollWords.length) {
            throw new IndexOutOfBoundsException("line " + line + " outside 0.." + (packedScrollWords.length - 1));
        }
    }

    private short[] copyOrNull(short[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }

    private void copyToTarget(short[] values, short[] target) {
        if (values == null) {
            throw new IllegalStateException("No scroll values are available to copy");
        }
        if (target.length < values.length) {
            throw new IllegalArgumentException("target length must be at least " + values.length);
        }
        System.arraycopy(values, 0, target, 0, values.length);
    }
}
