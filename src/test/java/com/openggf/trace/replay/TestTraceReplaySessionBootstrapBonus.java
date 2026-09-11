package com.openggf.trace.replay;

import com.openggf.game.BonusStageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceReplaySessionBootstrapBonus {

    @Test
    void bonusTypeMappingCoversGumballAndPachinko() {
        assertEquals(BonusStageType.GUMBALL,
            TraceReplaySessionBootstrap.bonusStageTypeForToken("gumball"));
        assertEquals(BonusStageType.GLOWING_SPHERE,
            TraceReplaySessionBootstrap.bonusStageTypeForToken("pachinko"));
        assertEquals(BonusStageType.SLOT_MACHINE,
            TraceReplaySessionBootstrap.bonusStageTypeForToken("slots"));
        assertThrows(IllegalStateException.class,
            () -> TraceReplaySessionBootstrap.bonusStageTypeForToken("casino"));
        assertThrows(IllegalStateException.class,
            () -> TraceReplaySessionBootstrap.bonusStageTypeForToken(null));
    }
}
