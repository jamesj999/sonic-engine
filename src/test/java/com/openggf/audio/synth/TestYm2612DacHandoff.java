package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Distinguishes the logical enqueue observer from the actual sample handoff. */
class TestYm2612DacHandoff {
    @Test
    void queuedEnableIsLogicalButPhysicalHandoffKeepsNewSampleIntact() {
        byte[] first = new byte[1000];
        Arrays.fill(first, (byte) 0x11);
        byte[] second = {(byte) 0xE1, (byte) 0xE2, (byte) 0xE3, (byte) 0xE4};
        Ym2612Chip chip = new Ym2612Chip();
        chip.setDacData(new DacData(Map.of(1, first, 2, second), Map.of(
                0x81, new DacData.DacEntry(1, 1),
                0x82, new DacData.DacEntry(2, 1)), 297));
        List<Integer> logical = new ArrayList<>();
        List<Integer> physical = new ArrayList<>();
        chip.setWriteObserver(new ChipWriteObserver() {
            private int address;
            @Override public void onYm2612Write(int port, int register, int value) {
                if (port == 0 && register == 0x2A) logical.add(value);
                if (port == 0 && register == 0x2B) logical.add(-value);
            }
            @Override public void onPsgWrite(int value) { }
            @Override public boolean observesPhysicalWrites() { return true; }
            @Override public void onYm2612BusWrite(long cycle, int busPort, int value,
                    PhysicalWriteOrigin origin) {
                if (busPort == 0) address = value;
                if (busPort == 1 && address == 0x2A) physical.add(value);
            }
        });
        chip.playDac(0x81);
        chip.renderStereo(new int[20], new int[20]);
        assertTrue(physical.contains(0x11), "the first sample was actually streaming");
        logical.clear();
        physical.clear();

        // These writes must drain before the next queued play. The old sample
        // can become pending while the bus is occupied; the new play replaces it.
        for (int i = 0; i < 16; i++) chip.write(0, 0x22, 0);
        chip.playDac(0x82);
        chip.write(0, 0x2B, 0x80);
        assertEquals(List.of(-128), logical, "enable is observed when enqueued");
        assertTrue(physical.isEmpty(), "no queued operation has reached the chip yet");
        chip.renderStereo(new int[200], new int[200]);

        assertTrue(logical.indexOf(0xE1) > logical.indexOf(-128),
                "the queued enable is visible before the new sample reaches the chip");
        int handoff = physical.indexOf(0xE1);
        assertTrue(handoff >= 0, "the new sample reaches the physical bus");
        assertTrue(physical.subList(0, handoff).stream().allMatch(value -> value == 0x11));
        assertEquals(List.of(0xE1, 0xE2, 0xE3, 0xE4), physical.subList(handoff, physical.size()),
                "the new sample starts at byte zero, with no old byte or duplicated byte after it");
    }
}
