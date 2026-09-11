package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.debug.DebugOption;
import com.openggf.debug.DebugState;

import static org.junit.jupiter.api.Assertions.*;

public class TestDebugEnums {
    @Test
    public void testDebugOptionNext() {
        assertEquals(DebugOption.B, DebugOption.A.next());
        assertEquals(DebugOption.A, DebugOption.E.next());
    }

    @Test
    public void testDebugStateNext() {
        assertEquals(DebugState.PATTERNS_VIEW, DebugState.NONE.next());
        assertEquals(DebugState.NONE, DebugState.BLOCKS_VIEW.next());
    }
}


