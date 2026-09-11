package com.openggf.game.rewind.schema;

import com.openggf.game.rewind.RewindDeferred;
import com.openggf.game.rewind.RewindTransient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCompactFieldCapturerPolicy {
    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void finalPrimitiveStringAndEnumFieldsAreStructuralAndDoNotBlockCaptureOrRestore() {
        StructuralFinalFixture fixture = new StructuralFinalFixture();

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.mutableValue = 9;
        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(5, fixture.mutableValue);

        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(StructuralFinalFixture.class);
        assertPolicy(schema, "finalInt", RewindFieldPolicy.STRUCTURAL);
        assertPolicy(schema, "finalString", RewindFieldPolicy.STRUCTURAL);
        assertPolicy(schema, "finalEnum", RewindFieldPolicy.STRUCTURAL);
        assertFalse(schema.unsupportedFields().stream()
                .anyMatch(field -> field.key().declaringClassName().equals(StructuralFinalFixture.class.getName())));
    }

    @Test
    void mutableUnsupportedPojoFieldIsRejectedWithClassAndFieldName() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(new UnsupportedPojoFixture()));

        assertTrue(failure.getMessage().contains(UnsupportedPojoFixture.class.getName()));
        assertTrue(failure.getMessage().contains(UnsupportedPojoFixture.class.getName() + ".mutablePojo"));
    }

    @Test
    void valueCollectionFieldsAreCapturedButUnsupportedCollectionShapesAreRejected() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(CollectionFixture.class);

        assertPolicy(schema, "list", RewindFieldPolicy.CAPTURED);
        assertPolicy(schema, "map", RewindFieldPolicy.CAPTURED);
        assertPolicy(schema, "set", RewindFieldPolicy.CAPTURED);
        assertPolicy(schema, "queue", RewindFieldPolicy.UNSUPPORTED);
        assertPolicy(schema, "deque", RewindFieldPolicy.UNSUPPORTED);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(new CollectionFixture()));
        assertFalse(failure.getMessage().contains(CollectionFixture.class.getName() + ".list"));
        assertFalse(failure.getMessage().contains(CollectionFixture.class.getName() + ".map"));
        assertFalse(failure.getMessage().contains(CollectionFixture.class.getName() + ".set"));
        assertTrue(failure.getMessage().contains(CollectionFixture.class.getName() + ".queue"));
        assertTrue(failure.getMessage().contains(CollectionFixture.class.getName() + ".deque"));
    }

    @Test
    void rewindDeferredFieldsAreNotCapturedAndAreDeferredNotUnsupported() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(DeferredFixture.class);

        assertPolicy(schema, "deferredPojo", RewindFieldPolicy.DEFERRED);
        assertFalse(schema.capturedFields().stream()
                .anyMatch(field -> field.key().fieldName().equals("deferredPojo")));
        assertFalse(schema.unsupportedFields().stream()
                .anyMatch(field -> field.key().fieldName().equals("deferredPojo")));

        CompactFieldCapturer.capture(new DeferredFixture());
    }

    @Test
    void rewindTransientFieldsAreSkippedAsTransient() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(TransientFixture.class);

        assertPolicy(schema, "transientPojo", RewindFieldPolicy.TRANSIENT);
        assertFalse(schema.capturedFields().stream()
                .anyMatch(field -> field.key().fieldName().equals("transientPojo")));
        assertFalse(schema.unsupportedFields().stream()
                .anyMatch(field -> field.key().fieldName().equals("transientPojo")));

        CompactFieldCapturer.capture(new TransientFixture());
    }

    @Test
    void recordsWithMutableComponentsRemainUnsupported() {
        RewindClassSchema arraySchema = RewindSchemaRegistry.schemaFor(ArrayRecordFixture.class);
        RewindClassSchema bitSetSchema = RewindSchemaRegistry.schemaFor(BitSetRecordFixture.class);

        assertPolicy(arraySchema, "record", RewindFieldPolicy.UNSUPPORTED);
        assertPolicy(bitSetSchema, "record", RewindFieldPolicy.UNSUPPORTED);

        IllegalStateException arrayFailure = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(new ArrayRecordFixture()));
        IllegalStateException bitSetFailure = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(new BitSetRecordFixture()));

        assertTrue(arrayFailure.getMessage().contains(ArrayRecordFixture.class.getName() + ".record"));
        assertTrue(bitSetFailure.getMessage().contains(BitSetRecordFixture.class.getName() + ".record"));
    }

    private static void assertPolicy(RewindClassSchema schema, String fieldName, RewindFieldPolicy policy) {
        RewindFieldPlan plan = schema.fields().stream()
                .filter(field -> field.key().fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow();
        assertEquals(policy, plan.policy());
    }

    private enum SampleEnum {
        FIRST,
        SECOND
    }

    private static final class StructuralFinalFixture {
        int mutableValue = 5;
        final int finalInt = 1;
        final String finalString = "constant";
        final SampleEnum finalEnum = SampleEnum.SECOND;
    }

    private static final class MutablePojo {
        int value;
    }

    private static final class UnsupportedPojoFixture {
        MutablePojo mutablePojo = new MutablePojo();
    }

    private static final class CollectionFixture {
        List<String> list = List.of("value");
        Map<String, Integer> map = new HashMap<>();
        Set<String> set = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        Deque<String> deque = new ArrayDeque<>();
    }

    private static final class DeferredFixture {
        int capturedValue = 3;
        @RewindDeferred(reason = "resolved externally")
        MutablePojo deferredPojo = new MutablePojo();
    }

    private static final class TransientFixture {
        int capturedValue = 4;
        @RewindTransient(reason = "not rewind state")
        MutablePojo transientPojo = new MutablePojo();
    }

    private record ArrayRecord(int[] values) {}

    private record BitSetRecord(BitSet values) {}

    private static final class ArrayRecordFixture {
        ArrayRecord record = new ArrayRecord(new int[] {1, 2});
    }

    private static final class BitSetRecordFixture {
        BitSetRecord record = new BitSetRecord(new BitSet());
    }
}
