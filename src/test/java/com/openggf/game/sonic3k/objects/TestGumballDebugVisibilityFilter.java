package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGumballDebugVisibilityFilter {

    @AfterEach
    void tearDown() {
        GumballMachineObjectInstance.resetDebugFiltersForTest();
    }

    @Test
    void sourceFilter_cyclesFromAllToContainerGlassThenBackToAll() {
        assertNull(GumballMachineObjectInstance.getCurrentDebugSourceFilterForTest());

        GumballMachineObjectInstance.cycleDebugSourceFilter();
        assertEquals(GumballMachineObjectInstance.DEBUG_SOURCE_MACHINE_MAIN,
                GumballMachineObjectInstance.getCurrentDebugSourceFilterForTest());

        GumballMachineObjectInstance.cycleDebugSourceFilter();
        assertEquals(GumballMachineObjectInstance.DEBUG_SOURCE_CONTAINER_GLASS,
                GumballMachineObjectInstance.getCurrentDebugSourceFilterForTest());

        for (int i = 0; i < 9; i++) {
            GumballMachineObjectInstance.cycleDebugSourceFilter();
        }
        assertNull(GumballMachineObjectInstance.getCurrentDebugSourceFilterForTest());
    }

    @Test
    void sourceFilter_keepsOnlyMatchingChildSourceVisible() {
        GumballMachineObjectInstance.cycleDebugSourceFilter();
        GumballMachineObjectInstance.cycleDebugSourceFilter();

        assertTrue(GumballMachineObjectInstance.shouldDebugRender(
                2, true, GumballMachineObjectInstance.DEBUG_SOURCE_CONTAINER_GLASS));
        assertFalse(GumballMachineObjectInstance.shouldDebugRender(
                2, true, GumballMachineObjectInstance.DEBUG_SOURCE_MACHINE_MAIN));
        assertFalse(GumballMachineObjectInstance.shouldDebugRender(
                4, false, GumballMachineObjectInstance.DEBUG_SOURCE_PLATFORM_EXTRA));
    }
}


