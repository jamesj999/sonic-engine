package com.openggf.testmode;

import com.openggf.graphics.PixelFontTextRenderer;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestSpecialStageTraceHudOverlay {

    @Test
    void renderUsesTheCommonTraceHudSections() {
        PixelFontTextRenderer text = mock(PixelFontTextRenderer.class);
        SpecialStageTraceHudOverlay overlay =
                new SpecialStageTraceHudOverlay(
                        () -> "S1 SPECIAL STAGE", () -> 12, () -> 100,
                        () -> true);

        overlay.render(text);

        var order = inOrder(text);
        order.verify(text).beginBatch();
        verify(text).drawShadowedText(eq("ERRORS    0"),
                anyInt(), anyInt(), any(), anyFloat());
        verify(text).drawShadowedText(eq("WARN      0"),
                anyInt(), anyInt(), any(), anyFloat());
        verify(text).drawShadowedText(eq("LAG       1"),
                anyInt(), anyInt(), any(), anyFloat());
        order.verify(text).endBatch();
    }
}
