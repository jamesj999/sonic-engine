package com.openggf.tests.trace.s1;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@RequiresRom(SonicGame.SONIC_1)
class DebugS1Mz1SlotLayoutProbe {

    private final S1Mz1SlotLayoutHarness harness = new S1Mz1SlotLayoutHarness();

    @Test
    void slotSuffixStillMatchesRecordedRomLayoutBeforeBatbrainRegion() throws Exception {
        assertDoesNotThrow(() -> harness.slotSuffixStillMatchesRecordedRomLayoutBeforeBatbrainRegion());
    }
}
