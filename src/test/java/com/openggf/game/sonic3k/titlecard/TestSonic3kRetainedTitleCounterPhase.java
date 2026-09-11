package com.openggf.game.sonic3k.titlecard;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kRetainedTitleCounterPhase {

    @Test
    void retainedOwnerKeepsHistoryProjectionAcrossGamestateReset() throws Exception {
        Sonic3kTitleCardManager manager = new Sonic3kTitleCardManager();
        setBoolean(manager, "inLevelMode", true);
        setBoolean(manager, "retainedResultsHeldLevelCounterOwned", true);
        setBoolean(manager, "resetLevelGamestateOnInLevelDisplay", true);

        assertTrue(manager.projectsRetainedResultsSpriteCadence());

        setBoolean(manager, "resetLevelGamestateOnInLevelDisplay", false);

        assertTrue(manager.projectsRetainedResultsSpriteCadence(),
                "Sonic_RecordPos continues advancing during retained playable-slot slices");
    }

    @Test
    void retainedOwnerCanOutliveTheVisibleOverlay() throws Exception {
        Sonic3kTitleCardManager manager = new Sonic3kTitleCardManager();
        setBoolean(manager, "inLevelMode", true);
        setBoolean(manager, "retainedResultsHeldLevelCounterOwned", true);
        setField(manager, "state", Sonic3kTitleCardState.COMPLETE);

        assertFalse(manager.isOverlayActive());
        assertTrue(manager.ownsRetainedResultsHeldLevelCounter());
        assertTrue(manager.projectsRetainedResultsSpriteCadence());
    }

    private static void setBoolean(Sonic3kTitleCardManager manager, String name, boolean value)
            throws ReflectiveOperationException {
        Field field = Sonic3kTitleCardManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(manager, value);
    }

    private static void setField(Sonic3kTitleCardManager manager, String name, Object value)
            throws ReflectiveOperationException {
        Field field = Sonic3kTitleCardManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }
}
