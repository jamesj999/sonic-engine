package com.openggf.game.rewind;

import com.openggf.game.rewind.snapshot.GenericObjectSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestRewindCodecFieldScratchReuse {

    public static final class CodecFinalCollectionHolder {
        public final List<Integer> values = new ArrayList<>();
    }

    @Test
    void backToBackCapturesOfFinalCollectionFieldRoundTripIndependently() {
        CodecFinalCollectionHolder target = new CodecFinalCollectionHolder();
        target.values.add(1);
        target.values.add(2);
        target.values.add(3);

        GenericObjectSnapshot first = GenericFieldCapturer.capture(target);
        GenericObjectSnapshot second = GenericFieldCapturer.capture(target);
        assertNotNull(first);
        assertNotNull(second);

        // Restoring the second snapshot into a fresh holder must reproduce
        // the captured state — proves the pooled scratch was drained into
        // independent storage on each capture.
        CodecFinalCollectionHolder restored = new CodecFinalCollectionHolder();
        GenericFieldCapturer.restore(restored, second);
        assertEquals(List.of(1, 2, 3), restored.values);
    }

    @Test
    void mutatingFinalCollectionBetweenCapturesDoesNotCorruptFirstSnapshot() {
        CodecFinalCollectionHolder target = new CodecFinalCollectionHolder();
        target.values.add(10);
        target.values.add(20);
        GenericObjectSnapshot first = GenericFieldCapturer.capture(target);

        // Mutate the collection in place, then capture again to overwrite
        // the pooled scratch. The first snapshot must still restore the
        // pre-mutation state.
        target.values.clear();
        target.values.add(99);
        GenericFieldCapturer.capture(target);

        CodecFinalCollectionHolder restored = new CodecFinalCollectionHolder();
        GenericFieldCapturer.restore(restored, first);
        assertEquals(List.of(10, 20), restored.values,
                "First snapshot must restore the pre-mutation values, proving the pooled "
                        + "scratch did not bleed the second capture into the first snapshot");
        assertEquals(List.of(99), target.values);  // sanity: target itself was actually mutated
    }
}
