package com.openggf;

import com.openggf.control.InputActionMasks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestSpecialStageLogicalInput {

    @Test
    void mapsLogicalActionsToMegaDriveButtonIdentity() {
        assertEquals(0x40, InputActionMasks.toMegaDriveButtonBits(InputActionMasks.ACTION_A));
        assertEquals(0x10, InputActionMasks.toMegaDriveButtonBits(InputActionMasks.ACTION_B));
        assertEquals(0x20, InputActionMasks.toMegaDriveButtonBits(InputActionMasks.ACTION_C));
        assertEquals(0x70, InputActionMasks.toMegaDriveButtonBits(InputActionMasks.ACTION_ALL));
    }
}
