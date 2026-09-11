package com.openggf.game.sonic3k.audio.smps;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Direct operand-alignment checks for the three native FF meta formats. */
class TestSonic3kSmpsMetaCommandOperands {

    private static final DacData EMPTY_DAC = new DacData(new HashMap<>(), new HashMap<>(), 297);

    @Test
    void ff01ConsumesOneOperand() {
        assertMetaTrackEndsAfter(new byte[] {(byte) 0xFF, 0x01, 0x7A, (byte) 0xF2});
    }

    @Test
    void ff02ConsumesOneOperand() {
        assertMetaTrackEndsAfter(new byte[] {(byte) 0xFF, 0x02, 0x7A, (byte) 0xF2});
    }

    @Test
    void ff03ConsumesThreeOperands() {
        assertMetaTrackEndsAfter(new byte[] {(byte) 0xFF, 0x03, 0x12, 0x34, 0x56, (byte) 0xF2});
    }

    private static void assertMetaTrackEndsAfter(byte[] trackBytes) {
        byte[] data = new byte[0x100];
        data[0x02] = 2; // DAC + one FM track
        setLe16(data, 0x0A, 0x40); // second FM/DAC entry is the synthetic FM track
        System.arraycopy(trackBytes, 0, data, 0x40, trackBytes.length);
        Sonic3kSmpsData smps = new Sonic3kSmpsData(data, 0);
        SmpsSequencer sequencer = new SmpsSequencer(smps, EMPTY_DAC, new VirtualSynthesizer(),
                Sonic3kSmpsSequencerConfig.CONFIG);

        sequencer.advanceSamples(20_000);
        SmpsSequencer.Track fm = sequencer.getTracks().stream()
                .filter(track -> track.type == SmpsSequencer.TrackType.FM)
                .findFirst().orElseThrow();
        assertFalse(fm.active, "the aligned F2 terminator must stop the FM track");
        assertEquals(0x40 + trackBytes.length, fm.pos,
                "meta command must consume exactly its native operand width");
    }

    private static void setLe16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }
}
