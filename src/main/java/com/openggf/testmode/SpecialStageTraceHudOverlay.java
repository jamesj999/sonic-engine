package com.openggf.testmode;

import com.openggf.graphics.PixelFontTextRenderer;
import com.openggf.trace.TraceHudModel;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Special-stage adapter for the common visual trace HUD.
 *
 * <p>Keeping this as a thin adapter is deliberate: special stages and level
 * segments must render the same diagnostics, input glyphs, pause message, and
 * completion state. Only the model (row-driver versus live comparator) differs.
 */
public final class SpecialStageTraceHudOverlay implements TraceSessionOverlay {
    private final TraceHudOverlay common;

    /** Compatibility constructor used by standalone sessions. */
    public SpecialStageTraceHudOverlay(
            Supplier<String> label,
            IntSupplier cursor,
            IntSupplier rowCount,
            BooleanSupplier lagRow) {
        this(new CompatibilityModel(cursor, rowCount, lagRow),
                label, () -> null, () -> null);
    }

    public SpecialStageTraceHudOverlay(
            TraceHudModel model,
            Supplier<String> label,
            Supplier<String> focusLabel,
            Supplier<String> rewindStatus) {
        // The label is retained as a camera/focus-compatible supplier for
        // callers that used the old constructor; the visible layout is owned
        // exclusively by TraceHudOverlay.
        this.common = new TraceHudOverlay(model,
                TraceHudOverlay::configuredPauseKeyLabel,
                TraceHudOverlay::isGameLoopPaused,
                focusLabel,
                rewindStatus);
    }

    @Override
    public void render(PixelFontTextRenderer text) {
        common.render(text);
    }

    private record CompatibilityModel(
            IntSupplier cursor,
            IntSupplier rowCount,
            BooleanSupplier lagRow) implements TraceHudModel {
        @Override public int errorCount() { return 0; }
        @Override public int warningCount() { return 0; }
        @Override public int laggedFrames() { return lagRow.getAsBoolean() ? 1 : 0; }
        @Override public int recentActionMask() { return 0; }
        @Override public int recentInputMask() { return 0; }
        @Override public boolean recentStartPressed() { return false; }
        @Override public java.util.List<com.openggf.trace.live.MismatchEntry>
                recentMismatches() { return java.util.List.of(); }
        @Override public boolean hasRecordingDesync() { return false; }
        @Override public boolean isComplete() {
            return cursor.getAsInt() >= rowCount.getAsInt();
        }
    }
}
