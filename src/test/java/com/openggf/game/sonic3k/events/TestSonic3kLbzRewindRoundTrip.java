package com.openggf.game.sonic3k.events;

import com.openggf.game.rewind.schema.ZoneEventSchemaSidecar;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TestSonic3kLbzRewindRoundTrip {

    @Test
    void postTitleBoundaryWorkersRoundTripThroughZoneSidecar() throws Exception {
        Sonic3kLBZEvents original = new Sonic3kLBZEvents();
        setField(original, "activeAct", 1);
        setField(original, "postTitleAct2SizeChangeActive", true);
        setField(original, "postTitleAct2TargetMaxX", 0x4300);
        setField(original, "postTitleAct2TargetMinY", 0x0100);
        setField(original, "postTitleAct2TargetMaxY", 0x0668);
        setField(original, "act2MaxXAccumulator", 0xC000);
        setField(original, "act2MinYAccumulator", 0x8000);
        setField(original, "act2MaxYAccumulator", 0x18000);

        setField(original, "lbz2CopiedWindowActive", true);
        setField(original, "lbz2WindowClearTimer", 1);
        int[] window = new int[64 * 28];
        window[5 * 64] = 0xE123;
        window[27 * 64 + 39] = 0x87FF;
        Field windowField = Sonic3kLBZEvents.class.getDeclaredField("lbz2CopiedWindowDescriptors");
        windowField.setAccessible(true);
        System.arraycopy(window, 0, (int[]) windowField.get(original), 0, window.length);
        setField(original, "lbz2WindowCaptured", true);

        byte[] first = ZoneEventSchemaSidecar.capture(original);
        Sonic3kLBZEvents restored = new Sonic3kLBZEvents();
        ZoneEventSchemaSidecar.restore(restored, first);

        assertArrayEquals(first, ZoneEventSchemaSidecar.capture(restored),
                "LBZ event state must remain byte-identical after rewind restoration");
    }

    private static void setField(Sonic3kLBZEvents events, String name, Object value)
            throws ReflectiveOperationException {
        Field field = Sonic3kLBZEvents.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(events, value);
    }
}
