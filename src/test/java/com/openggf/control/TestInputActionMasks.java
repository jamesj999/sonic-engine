package com.openggf.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestInputActionMasks {

    @Test
    void actionBitsUseBk2Convention() {
        assertEquals(0x01, InputActionMasks.ACTION_A);
        assertEquals(0x02, InputActionMasks.ACTION_B);
        assertEquals(0x04, InputActionMasks.ACTION_C);
        assertEquals(0x07, InputActionMasks.ACTION_ALL);
    }

    @Test
    void actionBitsMapToMegaDriveControllerByteLayout() {
        assertEquals(0x40, InputActionMasks.toMegaDriveButtonBits(InputActionMasks.ACTION_A));
        assertEquals(0x10, InputActionMasks.toMegaDriveButtonBits(InputActionMasks.ACTION_B));
        assertEquals(0x20, InputActionMasks.toMegaDriveButtonBits(InputActionMasks.ACTION_C));
        assertEquals(0x70, InputActionMasks.toMegaDriveButtonBits(InputActionMasks.ACTION_ALL));
    }

    @Test
    void actionMaskIsLimitedToThreeButtons() {
        assertEquals(0x07, InputActionMasks.sanitizeActionMask(0xFF));
        assertEquals(0x70, InputActionMasks.toMegaDriveButtonBits(0xFF));
    }
}
