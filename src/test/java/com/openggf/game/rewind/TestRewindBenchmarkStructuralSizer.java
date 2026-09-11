package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestRewindBenchmarkStructuralSizer {

    @Test
    void sizesPackagePrivateNestedRecordIncludingScalarPayload()
            throws ReflectiveOperationException {
        Object snapshot = newPackagePrivateRingConverterSnapshot(37);

        long bytes = assertDoesNotThrow(
                () -> RewindBenchmark.estimateStructuralSize(snapshot));

        assertEquals(24L, bytes,
                "record header and int component should both contribute to retained size");
    }

    private static Object newPackagePrivateRingConverterSnapshot(int seedBlueConverted)
            throws ReflectiveOperationException {
        Class<?> snapshotType = Class.forName(
                "com.openggf.game.sonic3k.specialstage."
                        + "Sonic3kSpecialStageSnapshot$RingConverterSnapshot");
        Constructor<?> constructor = snapshotType.getDeclaredConstructor(int.class);
        constructor.trySetAccessible();
        return constructor.newInstance(seedBlueConverted);
    }
}
