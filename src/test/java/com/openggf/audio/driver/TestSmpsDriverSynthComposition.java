package com.openggf.audio.driver;

import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.Synthesizer;
import com.openggf.audio.synth.VirtualSynthesizer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsDriverSynthComposition {

    @Test
    void driverDoesNotExtendVirtualSynthesizer() {
        assertFalse(VirtualSynthesizer.class.isAssignableFrom(SmpsDriver.class));
        assertTrue(Synthesizer.class.isAssignableFrom(SmpsDriver.class));
    }

    @Test
    void ownedAdapterPreservesWriteRenderAndSnapshotBehavior() {
        List<ChipWrite> writes = new ArrayList<>();
        ChipWriteObserver observer = new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
                writes.add(new YmWrite(port, register, value));
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add(new PsgWrite(value));
            }
        };
        SmpsDriver driver = SmpsDriverTestAccess.create(
                48_000.0, observer);
        writes.clear();

        Object source = new Object();
        driver.writeFm(source, 0, 0x2B, 0x80);
        driver.writePsg(source, 0x9F);
        short[] pcm = new short[1_600];

        assertEquals(pcm.length, SmpsDriverTestAccess.read(driver, pcm));
        assertEquals(List.of(new YmWrite(0, 0x2B, 0x80), new PsgWrite(0x9F)),
                writes);
        assertNotNull(driver.captureSnapshot());
    }

    private sealed interface ChipWrite permits YmWrite, PsgWrite {
    }

    private record YmWrite(int port, int register, int value)
            implements ChipWrite {
    }

    private record PsgWrite(int value) implements ChipWrite {
    }
}
