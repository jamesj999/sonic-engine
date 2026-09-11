package com.openggf.game.rewind.schema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestRewindRecordCodecs {
    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void recordFieldsAreCapturedByComponentInsteadOfOpaqueIdentity() {
        RecordFixture fixture = new RecordFixture();
        SampleRecord original = fixture.record;

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
        fixture.record = new SampleRecord(99, false, "changed", SampleEnum.SECOND, 300);

        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(original, fixture.record);
        assertNotSame(original, fixture.record);
    }

    @Test
    void nullableRecordFieldsAndReferenceComponentsRoundTrip() {
        NullableRecordFixture fixture = new NullableRecordFixture();
        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);

        fixture.record = new NullableRecord("changed", SampleEnum.FIRST, 7);
        fixture.nullRecord = new NullableRecord("created", SampleEnum.SECOND, 8);

        CompactFieldCapturer.restore(fixture, blob);

        assertEquals(new NullableRecord(null, null, null), fixture.record);
        assertNull(fixture.nullRecord);
    }

    private enum SampleEnum {
        FIRST,
        SECOND
    }

    private record SampleRecord(int value, boolean enabled, String name, SampleEnum mode, Integer optional) {}

    private record NullableRecord(String name, SampleEnum mode, Integer optional) {}

    private record MutableRecord(BitSet values) {}

    private static final class RecordFixture {
        SampleRecord record = new SampleRecord(12, true, "captured", SampleEnum.FIRST, 25);
    }

    private static final class NullableRecordFixture {
        NullableRecord record = new NullableRecord(null, null, null);
        NullableRecord nullRecord;
    }

    @SuppressWarnings("unused")
    private static final class MutableRecordFixture {
        MutableRecord record = new MutableRecord(new BitSet());
    }
}
