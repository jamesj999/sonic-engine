package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.debug.DebugOption;
import uk.co.jamesj999.sonic.debug.DebugState;

import static org.junit.Assert.*;

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
