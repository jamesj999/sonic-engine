package com.openggf.game.rewind.schema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCompactFieldCapturer {
    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void restoresSupportedScalarValues() {
        ScalarFixture fixture = new ScalarFixture();

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.mutate();
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(12, fixture.intValue);
        assertTrue(fixture.booleanValue);
        assertEquals(0x0102030405060708L, fixture.longValue);
        assertEquals(1.25f, fixture.floatValue);
        assertEquals(2.5d, fixture.doubleValue);
        assertEquals("captured", fixture.stringValue);
        assertEquals(SampleEnum.BETA, fixture.enumValue);
    }

    @Test
    void restoresPrimitiveArraysEnumArraysBitSetAndRecordValues() {
        CompositeFixture fixture = new CompositeFixture();

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.mutate();
        CompactFieldCapturer.restore(fixture, blob);

        assertArrayEquals(new int[] {1, 2, 3}, fixture.intArray);
        assertArrayEquals(new boolean[] {true, false, true}, fixture.booleanArray);
        assertArrayEquals(new long[] {4L, 5L}, fixture.longArray);
        assertArrayEquals(new float[] {1.5f, 2.5f}, fixture.floatArray);
        assertArrayEquals(new double[] {3.5d, 4.5d}, fixture.doubleArray);
        assertArrayEquals(new SampleEnum[] {SampleEnum.ALPHA, SampleEnum.GAMMA}, fixture.enumArray);
        assertEquals(bitSet(1, 5, 9), fixture.bits);
        assertEquals(new SampleRecord(7, true, SampleEnum.GAMMA), fixture.record);
    }

    @Test
    void blobDefensivelyCopiesScalarDataAndOpaqueValues() {
        Object[] opaque = {"first"};
        byte[] scalar = {1, 2, 3};
        RewindObjectStateBlob blob = new RewindObjectStateBlob(4, ScalarFixture.class, scalar, opaque);

        scalar[0] = 9;
        opaque[0] = "changed";
        byte[] returnedScalar = blob.scalarData();
        Object[] returnedOpaque = blob.opaqueValues();
        returnedScalar[1] = 8;
        returnedOpaque[0] = "changed again";

        assertArrayEquals(new byte[] {1, 2, 3}, blob.scalarData());
        assertEquals("first", blob.opaqueValues()[0]);
    }

    private static BitSet bitSet(int... indexes) {
        BitSet bits = new BitSet();
        for (int index : indexes) {
            bits.set(index);
        }
        return bits;
    }

    private enum SampleEnum {
        ALPHA,
        BETA,
        GAMMA
    }

    private record SampleRecord(int value, boolean enabled, SampleEnum mode) {}

    private static final class ScalarFixture {
        int intValue = 12;
        boolean booleanValue = true;
        long longValue = 0x0102030405060708L;
        float floatValue = 1.25f;
        double doubleValue = 2.5d;
        String stringValue = "captured";
        SampleEnum enumValue = SampleEnum.BETA;

        void mutate() {
            intValue = -1;
            booleanValue = false;
            longValue = -2L;
            floatValue = -3.0f;
            doubleValue = -4.0d;
            stringValue = "mutated";
            enumValue = SampleEnum.ALPHA;
        }
    }

    private static final class CompositeFixture {
        int[] intArray = {1, 2, 3};
        boolean[] booleanArray = {true, false, true};
        long[] longArray = {4L, 5L};
        float[] floatArray = {1.5f, 2.5f};
        double[] doubleArray = {3.5d, 4.5d};
        SampleEnum[] enumArray = {SampleEnum.ALPHA, SampleEnum.GAMMA};
        BitSet bits = bitSet(1, 5, 9);
        SampleRecord record = new SampleRecord(7, true, SampleEnum.GAMMA);

        void mutate() {
            intArray = new int[] {-1};
            booleanArray = new boolean[] {false};
            longArray = new long[] {-2L};
            floatArray = new float[] {-3.0f};
            doubleArray = new double[] {-4.0d};
            enumArray = new SampleEnum[] {SampleEnum.BETA};
            bits = bitSet(2, 4);
            record = new SampleRecord(-5, false, SampleEnum.ALPHA);
        }
    }
}
