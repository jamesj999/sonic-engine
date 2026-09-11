package com.openggf.game;

import com.openggf.graphics.FadeManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestSpecialStageEntryPresentationController {

    @Test
    void readyWhiteEntryStartsMusicThenRevealImmediately() {
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        FadeManager fade = mock(FadeManager.class);
        Runnable music = mock(Runnable.class);
        when(provider.isEntryPresentationReady()).thenReturn(true);
        SpecialStageEntryPresentationController controller =
                new SpecialStageEntryPresentationController();

        controller.begin(provider, false, fade, music);

        InOrder order = inOrder(music, fade);
        order.verify(music).run();
        order.verify(fade).startFadeFromWhite(any());
        assertFalse(controller.isPending());
    }

    @Test
    void deferredBlackEntryHoldsThenStartsExactlyOnceAtReadiness() {
        AtomicBoolean ready = new AtomicBoolean(false);
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        FadeManager fade = mock(FadeManager.class);
        Runnable music = mock(Runnable.class);
        when(provider.isEntryPresentationReady()).thenAnswer(ignored -> ready.get());
        SpecialStageEntryPresentationController controller =
                new SpecialStageEntryPresentationController();

        controller.begin(provider, true, fade, music);

        verify(fade).holdBlack();
        verify(music, never()).run();
        assertTrue(controller.isPending());

        ready.set(true);
        controller.update(provider, fade, music);
        controller.update(provider, fade, music);

        InOrder order = inOrder(music, fade);
        order.verify(music).run();
        order.verify(fade).startFadeFromBlack(any());
        verifyNoMoreInteractions(music);
        assertFalse(controller.isPending());
    }

    @Test
    void deferredWhiteEntryFadesToWhiteFromTheNextVintAndParks() {
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        FadeManager fade = mock(FadeManager.class);
        Runnable music = mock(Runnable.class);
        when(provider.isEntryPresentationReady()).thenReturn(false);
        SpecialStageEntryPresentationController controller =
                new SpecialStageEntryPresentationController();

        controller.begin(provider, false, fade, music);

        InOrder order = inOrder(fade);
        order.verify(fade).startFadeToWhite(isNull(), eq(Integer.MAX_VALUE));
        order.verify(fade).deferFirstStepToNextVint();
        verify(fade, never()).holdWhite();
        verify(fade, never()).holdBlack();
        verify(music, never()).run();
        assertTrue(controller.isPending());
    }

    @Test
    void deferredWhiteEntryRevealsExactlyOnceWhenReady() {
        AtomicBoolean ready = new AtomicBoolean(false);
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        FadeManager fade = mock(FadeManager.class);
        Runnable music = mock(Runnable.class);
        when(provider.isEntryPresentationReady()).thenAnswer(ignored -> ready.get());
        SpecialStageEntryPresentationController controller =
                new SpecialStageEntryPresentationController();
        controller.begin(provider, false, fade, music);

        controller.update(provider, fade, music);
        ready.set(true);
        controller.update(provider, fade, music);
        controller.update(provider, fade, music);

        InOrder order = inOrder(music, fade);
        order.verify(music).run();
        order.verify(fade).startFadeFromWhite(any());
        verify(music, times(1)).run();
        verify(fade, times(1)).startFadeFromWhite(any());
        assertFalse(controller.isPending());
    }

    @Test
    void clearPreventsAbandonedEntryFromStartingLater() {
        AtomicBoolean ready = new AtomicBoolean(false);
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        FadeManager fade = mock(FadeManager.class);
        Runnable music = mock(Runnable.class);
        when(provider.isEntryPresentationReady()).thenAnswer(ignored -> ready.get());
        SpecialStageEntryPresentationController controller =
                new SpecialStageEntryPresentationController();
        controller.begin(provider, true, fade, music);

        controller.clear();
        ready.set(true);
        controller.update(provider, fade, music);

        verify(music, never()).run();
        verify(fade, never()).startFadeFromBlack(any());
        assertFalse(controller.isPending());
    }
}
