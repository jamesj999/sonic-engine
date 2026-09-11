package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class TestInLevelTitleCardCoordinator {

    /**
     * A results-return title card only locks control on entry. The
     * fresh-player prelude deliberately does not run here: the ROM's pass is
     * {@code Level_StartGame}'s, after the locked card loop drains its PLCs,
     * and the release path runs it via
     * {@link TitleCardProvider#shouldRunPlayerPreludeAtRelease()}.
     */
    @Test
    void resultsTitleCardOnlyLocksControlOnEntry() {
        @SuppressWarnings("unchecked")
        Consumer<Boolean> controlLock = mock(Consumer.class);

        InLevelTitleCardCoordinator.prepareResultsTransition(controlLock);

        verify(controlLock).accept(true);
        verifyNoMoreInteractions(controlLock);
    }
}
