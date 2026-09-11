package com.openggf;

import com.openggf.game.SpecialStageProvider;
import com.openggf.game.SpecialStageStartupPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class TestTraceSessionLauncherSpecialStageEntry {

    @Test
    void traceEntryRequestsAccurateStartupBeforeDisablingNativeLag() {
        GameLoop loop = mock(GameLoop.class);
        SpecialStageProvider provider = mock(SpecialStageProvider.class);

        TraceSessionLauncher.enterSpecialStageTrace(loop, provider, 4);

        InOrder order = inOrder(loop, provider);
        order.verify(loop).doEnterSpecialStage(provider, 4, true,
                SpecialStageStartupPolicy.TRACE_ACCURATE);
        order.verify(provider).setLagCompensation(0);
    }
}
