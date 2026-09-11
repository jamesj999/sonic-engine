package com.openggf.trace.replay;

import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.trace.TraceData;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTraceReplaySessionBootstrapClockParity {

    @Test
    void preparedReplaySeedsTheObjectClockOneTickBeforeRecordedRowZero() {
        assertPreRowSeed(0, -1);
        assertPreRowSeed(0xFFFF, 0xFFFE);
    }

    private static void assertPreRowSeed(int initialVblank, int expectedSeed) {
        TraceData trace = mock(TraceData.class);
        LevelManager level = mock(LevelManager.class);
        ObjectManager objects = mock(ObjectManager.class);
        when(trace.initialVblankCounter()).thenReturn(initialVblank);
        when(level.getObjectManager()).thenReturn(objects);

        try (MockedStatic<GameServices> services = mockStatic(GameServices.class)) {
            services.when(GameServices::levelOrNull).thenReturn(level);

            TraceReplaySessionBootstrap.alignObjectVblankCounterForReplayStart(trace);
        }

        verify(objects).initVblaCounter(expectedSeed);
    }
}
