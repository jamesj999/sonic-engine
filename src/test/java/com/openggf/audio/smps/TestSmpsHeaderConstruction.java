package com.openggf.audio.smps;

import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSmpsHeaderConstruction {
    private byte[] header() {
        return new byte[] {0x12, 0x34, 1, 1, 2, 3, 0x45, 0x67, -2, -3,
                0x23, 0x45, -4, -5, -6, -7};
    }

    @Test
    void builtinConstructionNeverDispatchesOverridableParsingOrWordReads() {
        var s1 = new Sonic1SmpsData(header()) {
            @Override protected void parseHeader() { fail("construction hook"); }
            @Override public int read16(int offset) { throw new AssertionError("word hook"); }
        };
        var s2 = new Sonic2SmpsData(header()) {
            @Override protected void parseHeader() { fail("construction hook"); }
            @Override public int read16(int offset) { throw new AssertionError("word hook"); }
        };
        var s3k = new Sonic3kSmpsData(header()) {
            @Override protected void parseHeader() { fail("construction hook"); }
            @Override public int read16(int offset) { throw new AssertionError("word hook"); }
        };
        assertEquals(0x1234, s1.getVoicePtr());
        assertEquals(0x3412, s2.getVoicePtr());
        assertEquals(0x3412, s3k.getVoicePtr());
        assertArrayEquals(new int[] {0x4567}, s1.getFmPointers());
        assertArrayEquals(new int[] {0x6745}, s3k.getFmPointers());
        assertArrayEquals(new int[] {0x4523}, s2.getPsgPointers());
        assertArrayEquals(new int[] {-2}, s1.getFmKeyOffsets());
        assertArrayEquals(new int[] {-3}, s3k.getFmVolumeOffsets());
        assertArrayEquals(new int[] {-4}, s2.getPsgKeyOffsets());
        assertArrayEquals(new int[] {-5}, s1.getPsgVolumeOffsets());
        assertArrayEquals(new int[] {250}, s3k.getPsgModEnvs());
        assertArrayEquals(new int[] {249}, s2.getPsgInstruments());
    }

    @Test
    void shortAndTruncatedHeadersKeepTheirExistingFormatPolicies() {
        for (int length = 0; length < 8; length++) {
            var s1 = new Sonic1SmpsData(new byte[length]);
            var s3k = new Sonic3kSmpsData(new byte[length]);
            assertEquals(1, s1.getDividingTiming());
            assertEquals(1, s3k.getDividingTiming());
            assertEquals(0, s1.getChannels());
            assertEquals(0, s3k.getChannels());
        }
        for (int length : new int[] {8, 9}) {
            byte[] truncated = java.util.Arrays.copyOf(header(), length);
            assertArrayEquals(new int[] {0}, new Sonic1SmpsData(truncated).getFmPointers());
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> new Sonic2SmpsData(truncated));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> new Sonic3kSmpsData(truncated));
        }
        byte[] noFm = header();
        noFm[2] = 0;
        assertEquals(0, new Sonic1SmpsData(noFm).getDacPointer());
        assertEquals(0x6745, new Sonic3kSmpsData(noFm).getDacPointer());
    }

    @Test
    void existingAbstractDataExtensionsRetainTheirConstructorHook() {
        var legacy = new LegacyData();
        assertEquals(1, legacy.calls);
        assertEquals(77, legacy.getTempo());
    }

    private static final class LegacyData extends AbstractSmpsData {
        private int calls;
        private LegacyData() { super(new byte[0], 0); }
        @Override protected void parseHeader() { calls++; tempo = 77; }
        @Override public byte[] getVoice(int id) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }
}
