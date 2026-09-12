package com.openggf.game;

import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.MenuPixelFont;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TestNativeModNoticePaging {
    @Test
    void controllerAcceptPagesBeforeDismissingAndPreservesAllLines() {
        var fade = mock(FadeManager.class);
        var screen = new NativeModNoticeScreen(fade,
                IntStream.range(0, 25).mapToObj(i -> "mod " + i).toList());
        var input = new InputHandler();
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, 0, InputActionMasks.ACTION_A, false, false),
                PlayerInputState.neutral()));
        assertEquals(3, screen.pageCount());
        screen.update(input);
        assertEquals(1, screen.currentPage());
        verify(fade, never()).startFadeToBlack(any());
        screen.update(input);
        assertEquals(2, screen.currentPage());
        verify(fade, never()).startFadeToBlack(any());
        screen.update(input);
        verify(fade).startFadeToBlack(any(Runnable.class));
    }

    @Test
    void bothNoticesUseNativeCompactCellsWithEnoughLineClearance() {
        assertEquals(6, MenuPixelFont.glyphAdvance(NativeModNoticeScreen.BODY_SCALE));
        assertEquals(6, MenuPixelFont.glyphAdvance(LegalDisclaimerScreen.BODY_SCALE));
        assertTrue(MenuPixelFont.glyphLineHeight(LegalDisclaimerScreen.BODY_SCALE)
                <= LegalDisclaimerScreen.BODY_LINE_HEIGHT);
    }
}
