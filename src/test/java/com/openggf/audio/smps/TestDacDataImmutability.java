package com.openggf.audio.smps;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDacDataImmutability {

    @Test
    void constructorOwnsSampleMapAndBytes() {
        byte[] sourceBytes = { 0x12, 0x34 };
        Map<Integer, byte[]> sourceSamples = new HashMap<>();
        sourceSamples.put(1, sourceBytes);
        Map<Integer, DacData.DacEntry> sourceMapping = new HashMap<>();
        sourceMapping.put(0x81, new DacData.DacEntry(1, 4));

        DacData data = new DacData(sourceSamples, sourceMapping, 295);
        sourceBytes[0] = 0x55;
        sourceSamples.clear();
        sourceMapping.clear();

        DacData.Sample sample = data.sample(1);
        assertEquals(2, sample.length());
        assertEquals((byte) 0x12, sample.byteAt(0));
        assertNull(data.sample(2));
        assertEquals(1, data.sampleCount());
        assertEquals(1, data.mappingCount());
        assertTrue(data.hasSample(1));
        assertFalse(data.hasSample(2));
        assertEquals(295, data.baseCycles());
        assertEquals(1, data.mappingForNote(0x81).sampleId());
    }

    @Test
    void missingLookupsReturnNullAndSampleUsesNormalIndexedReadBounds() {
        DacData data = new DacData(
                Map.of(1, new byte[] { 0x12 }),
                Map.of(0x81, new DacData.DacEntry(1, 4)),
                295);

        DacData.Sample sample = data.sample(1);
        assertNull(data.sample(2));
        assertNull(data.mappingForNote(0x82));
        assertThrows(IndexOutOfBoundsException.class,
                () -> sample.byteAt(1));
    }

    @Test
    void publicApiDoesNotExposeMutableDacStorage() {
        assertTrue(Modifier.isFinal(DacData.class.getModifiers()));
        assertEquals(0, DacData.class.getFields().length,
                "DAC state must not be exposed through public fields");

        for (Method method : DacData.class.getMethods()) {
            assertFalse(exposesRawSamples(method.getGenericReturnType()),
                    method + " exposes mutable DAC sample storage");
        }
        for (Field field : DacData.class.getFields()) {
            assertFalse(exposesRawSamples(field.getGenericType()),
                    field + " exposes mutable DAC sample storage");
        }

        Set<String> sampleMethods = Arrays.stream(
                        DacData.Sample.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("length", "byteAt"), sampleMethods);
    }

    private static boolean exposesRawSamples(Type type) {
        if (type == byte[].class) {
            return true;
        }
        if (!(type instanceof ParameterizedType parameterized)
                || parameterized.getRawType() != Map.class) {
            return false;
        }
        return Arrays.equals(parameterized.getActualTypeArguments(),
                new Type[] { Integer.class, byte[].class });
    }

}
