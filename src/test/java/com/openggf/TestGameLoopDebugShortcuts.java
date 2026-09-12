package com.openggf;

import com.openggf.game.GameMode;
import com.openggf.game.SpecialStageProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.mockito.Mockito.*;

class TestGameLoopDebugShortcuts {
    @Test
    void completionUsesActiveProviderRequirementAndDoesNotRunOutsideSpecialStage() {
        AtomicReference<GameMode> mode = new AtomicReference<>(GameMode.LEVEL);
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        @SuppressWarnings("unchecked")
        BiConsumer<Boolean, Integer> results = mock(BiConsumer.class);
        GameLoopDebugShortcuts shortcuts = new GameLoopDebugShortcuts(mode::get, () -> provider, results);

        shortcuts.debugCompleteSpecialStageWithEmerald();
        shortcuts.debugFailSpecialStage();
        verifyNoInteractions(provider, results);

        mode.set(GameMode.SPECIAL_STAGE);
        when(provider.getCurrentStage()).thenReturn(3);
        when(provider.getDebugCompletionRingCount(3)).thenReturn(140);
        shortcuts.debugCompleteSpecialStageWithEmerald();
        var order = inOrder(provider, results);
        order.verify(provider).setEmeraldCollected(true);
        order.verify(provider).getCurrentStage();
        order.verify(provider).getDebugCompletionRingCount(3);
        order.verify(results).accept(true, 140);

        shortcuts.debugFailSpecialStage();
        verify(results).accept(false, 15);
        verifyNoMoreInteractions(results);
    }
}
