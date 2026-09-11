package com.openggf.game.render;

import com.openggf.util.ShortIndexedView;

import java.util.Arrays;

/**
 * Frame-local render-mode state consumed during one queued render frame.
 *
 * <p>Controller-resolved instances are double-buffered. Their column storage is
 * private and exposed to render consumers only through a read-only indexed view.
 */
public final class AdvancedRenderFrameState {
    private static final AdvancedRenderFrameState DISABLED =
            new AdvancedRenderFrameState(false, false, null);

    private boolean enableForegroundHeatHaze;
    private boolean enablePerLineForegroundScroll;
    private short[] foregroundPerColumnVScrollOverride;
    private int foregroundPerColumnVScrollLength;
    private boolean hasForegroundPerColumnVScrollOverride;
    private final ColumnView foregroundPerColumnVScrollView = new ColumnView();

    public AdvancedRenderFrameState(boolean enableForegroundHeatHaze,
                                    boolean enablePerLineForegroundScroll,
                                    short[] foregroundPerColumnVScrollOverride) {
        write(enableForegroundHeatHaze, enablePerLineForegroundScroll, foregroundPerColumnVScrollOverride);
    }

    public static AdvancedRenderFrameState disabled() {
        return DISABLED;
    }

    public boolean enableForegroundHeatHaze() {
        return enableForegroundHeatHaze;
    }

    public boolean enablePerLineForegroundScroll() {
        return enablePerLineForegroundScroll;
    }

    /** Compatibility accessor returning a defensive copy, never the frame backing. */
    public short[] foregroundPerColumnVScrollOverride() {
        if (!hasForegroundPerColumnVScrollOverride) {
            return null;
        }
        return foregroundPerColumnVScrollLength == 0
                ? new short[0]
                : Arrays.copyOf(foregroundPerColumnVScrollOverride, foregroundPerColumnVScrollLength);
    }

    /** Read-only, allocation-free view shared by all render passes in this frame. */
    public ShortIndexedView foregroundPerColumnVScrollView() {
        return hasForegroundPerColumnVScrollOverride ? foregroundPerColumnVScrollView : null;
    }

    Object foregroundPerColumnVScrollBackingIdentity() {
        return foregroundPerColumnVScrollOverride;
    }

    private void write(boolean heatHaze, boolean perLineScroll, short[] columns) {
        enableForegroundHeatHaze = heatHaze;
        enablePerLineForegroundScroll = perLineScroll;
        int length = columns == null ? 0 : columns.length;
        hasForegroundPerColumnVScrollOverride = columns != null;
        if (length > 0) {
            if (foregroundPerColumnVScrollOverride == null
                    || foregroundPerColumnVScrollOverride.length < length) {
                foregroundPerColumnVScrollOverride = new short[length];
            }
            System.arraycopy(columns, 0, foregroundPerColumnVScrollOverride, 0, length);
        }
        foregroundPerColumnVScrollLength = length;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enableForegroundHeatHaze;
        private boolean enablePerLineForegroundScroll;
        private short[] foregroundPerColumnVScrollOverride;

        public Builder enableForegroundHeatHaze() {
            this.enableForegroundHeatHaze = true;
            return this;
        }

        public Builder enablePerLineForegroundScroll() {
            this.enablePerLineForegroundScroll = true;
            return this;
        }

        public Builder setForegroundPerColumnVScrollOverride(short[] foregroundPerColumnVScrollOverride) {
            this.foregroundPerColumnVScrollOverride = foregroundPerColumnVScrollOverride;
            return this;
        }

        public AdvancedRenderFrameState build() {
            if (!enableForegroundHeatHaze
                    && !enablePerLineForegroundScroll
                    && foregroundPerColumnVScrollOverride == null) {
                return DISABLED;
            }
            return new AdvancedRenderFrameState(
                    enableForegroundHeatHaze,
                    enablePerLineForegroundScroll,
                    foregroundPerColumnVScrollOverride);
        }

        void reset() {
            enableForegroundHeatHaze = false;
            enablePerLineForegroundScroll = false;
            foregroundPerColumnVScrollOverride = null;
        }

        AdvancedRenderFrameState buildInto(AdvancedRenderFrameState target) {
            if (!enableForegroundHeatHaze
                    && !enablePerLineForegroundScroll
                    && foregroundPerColumnVScrollOverride == null) {
                return DISABLED;
            }
            target.write(enableForegroundHeatHaze,
                    enablePerLineForegroundScroll,
                    foregroundPerColumnVScrollOverride);
            return target;
        }
    }

    private final class ColumnView implements ShortIndexedView {
        @Override
        public int size() {
            return foregroundPerColumnVScrollLength;
        }

        @Override
        public short get(int index) {
            if (index < 0 || index >= foregroundPerColumnVScrollLength) {
                throw new IndexOutOfBoundsException(index);
            }
            return foregroundPerColumnVScrollOverride[index];
        }
    }
}
