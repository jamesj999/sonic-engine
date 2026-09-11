package com.openggf.game.rewind;

import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindObjectStateBlob;
import com.openggf.game.rewind.schema.RewindSchemaRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindCaptureScratchReuse {

    public static final class ScalarHolder {
        public int alpha;
        public long beta;
        public boolean gamma;
    }

    @Test
    void backToBackCapturesProduceEqualButDistinctBlobs() {
        assertTrue(RewindSchemaRegistry.schemaFor(ScalarHolder.class).unsupportedFields().isEmpty(),
                "ScalarHolder must be fully supported by the compact capturer for this test to be meaningful");

        ScalarHolder target = new ScalarHolder();
        target.alpha = 0x11223344;
        target.beta = 0x0123456789ABCDEFL;
        target.gamma = true;

        RewindObjectStateBlob first = CompactFieldCapturer.capture(target);
        RewindObjectStateBlob second = CompactFieldCapturer.capture(target);

        assertNotSame(first.scalarData(), second.scalarData(),
                "Each capture must return a freshly allocated byte[] even though scratch is pooled");
        assertArrayEquals(first.scalarData(), second.scalarData());
        assertEquals(first.opaqueValues().length, second.opaqueValues().length);
    }

    @Test
    void mutatingTargetBetweenCapturesDoesNotCorruptFirstBlob() {
        ScalarHolder target = new ScalarHolder();
        target.alpha = 0x11111111;
        RewindObjectStateBlob first = CompactFieldCapturer.capture(target);
        byte[] firstBytes = first.scalarData().clone();

        target.alpha = 0x22222222;
        RewindObjectStateBlob second = CompactFieldCapturer.capture(target);

        assertArrayEquals(firstBytes, first.scalarData(),
                "First snapshot's scalarData must not be affected by a later capture");
        assertEquals(firstBytes.length, second.scalarData().length);
    }
}
