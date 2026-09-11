package com.openggf.level.resources;

import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestNemesisPlcServiceQueue {

    @Test
    void appendPreservesFifoAndDuplicateEntries() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        PlcDefinition first = definition(1, 0x100, 0x20, 0x200, 0x40);
        PlcDefinition duplicate = definition(2, 0x100, 0x20);

        queue.append(first, List.of(3, 6));
        queue.append(duplicate, List.of(9));

        assertEquals(List.of(
                entry(0x100, 0x20, 3, 3),
                entry(0x200, 0x40, 6, 6),
                entry(0x100, 0x20, 9, 9)),
                queue.capture().queuedEntries());
    }

    @Test
    void replaceQueuedRequiresIdleDecoder() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20), List.of(3));
        queue.replaceQueued(definition(2, 0x200, 0x40), List.of(6));
        queue.prepareHead();

        assertThrows(IllegalStateException.class,
                () -> queue.replaceQueued(definition(3, 0x300, 0x60), List.of(9)));
        assertEquals(entry(0x200, 0x40, 6, 6), queue.capture().activeEntry());
    }

    @Test
    void clearQueuedRequiresIdleDecoder() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20), List.of(3));
        queue.prepareHead();

        assertThrows(IllegalStateException.class, queue::clearQueued);
        assertEquals(entry(0x100, 0x20, 3, 3), queue.capture().activeEntry());
    }

    @Test
    void prepareHeadConsumesNoPatterns() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20, 0x200, 0x40), List.of(3, 6));

        queue.prepareHead();

        NemesisPlcQueueSnapshot snapshot = queue.capture();
        assertEquals(entry(0x100, 0x20, 3, 3), snapshot.activeEntry());
        assertEquals(List.of(entry(0x200, 0x40, 6, 6)), snapshot.queuedEntries());
    }

    @Test
    void serviceRequiresPreparedHead() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20), List.of(3));

        assertDoesNotThrow(() -> queue.servicePatterns(3));
        assertNull(queue.capture().activeEntry());
        assertEquals(List.of(entry(0x100, 0x20, 3, 3)), queue.capture().queuedEntries());
    }

    @Test
    void serviceHonorsThreeSixAndNinePatternBudgets() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20), List.of(20));
        queue.prepareHead();

        queue.servicePatterns(3);
        assertEquals(17, queue.capture().activeEntry().remainingPatterns());
        queue.servicePatterns(6);
        assertEquals(11, queue.capture().activeEntry().remainingPatterns());
        queue.servicePatterns(9);
        assertEquals(2, queue.capture().activeEntry().remainingPatterns());
    }

    @Test
    void serviceRejectsNonPositiveBudgetsWithoutMutatingPreparedHead() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20), List.of(3));
        queue.prepareHead();
        NemesisPlcQueueSnapshot before = queue.capture();

        assertThrows(IllegalArgumentException.class, () -> queue.servicePatterns(0));
        assertThrows(IllegalArgumentException.class, () -> queue.servicePatterns(-1));

        assertEquals(before, queue.capture());
    }

    @Test
    void completingEntryLeavesNextHeadUnprepared() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20, 0x200, 0x40), List.of(3, 6));
        queue.prepareHead();

        queue.servicePatterns(3);

        assertNull(queue.capture().activeEntry());
        assertEquals(List.of(entry(0x200, 0x40, 6, 6)), queue.capture().queuedEntries());
        queue.servicePatterns(6);
        assertEquals(List.of(entry(0x200, 0x40, 6, 6)), queue.capture().queuedEntries());
    }

    @Test
    void busyCoversPreparedAndUnpreparedEntries() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        assertFalse(queue.isBusy());

        queue.append(definition(1, 0x100, 0x20, 0x200, 0x40), List.of(3, 6));
        assertTrue(queue.isBusy());
        queue.prepareHead();
        assertTrue(queue.isBusy());
        queue.servicePatterns(3);
        assertTrue(queue.isBusy());
        queue.prepareHead();
        queue.servicePatterns(6);
        assertFalse(queue.isBusy());
    }

    @Test
    void invalidCountsDoNotMutateQueue() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20), List.of(3));
        NemesisPlcQueueSnapshot before = queue.capture();
        PlcDefinition twoEntries = definition(2, 0x200, 0x40, 0x300, 0x60);

        assertThrows(IllegalArgumentException.class, () -> queue.append(twoEntries, List.of(6)));
        assertThrows(IllegalArgumentException.class, () -> queue.append(twoEntries, List.of(6, 0)));
        assertThrows(IllegalArgumentException.class, () -> queue.replaceQueued(twoEntries, List.of(6, -1)));
        assertThrows(IllegalArgumentException.class, () -> queue.restore(new NemesisPlcQueueSnapshot(
                entry(0x400, 0x80, 3, 4), List.of())));

        assertEquals(before, queue.capture());
    }

    @Test
    void snapshotRoundTripsPartialHeadAndPendingTail() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20, 0x200, 0x40), List.of(10, 6));
        queue.append(definition(2, 0x300, 0x60), List.of(9));
        queue.prepareHead();
        queue.servicePatterns(3);
        NemesisPlcQueueSnapshot snapshot = queue.capture();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.queuedEntries().add(entry(0x400, 0x80, 3, 3)));

        NemesisPlcServiceQueue restored = new NemesisPlcServiceQueue();
        restored.restore(snapshot);

        assertEquals(snapshot, restored.capture());
    }

    @Test
    void restoreRejectsImpossibleProgress() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20), List.of(3));
        NemesisPlcQueueSnapshot before = queue.capture();

        NemesisPlcQueueSnapshot impossible = new NemesisPlcQueueSnapshot(null,
                List.of(entry(0x200, 0x40, 6, 5)));

        assertThrows(IllegalArgumentException.class, () -> queue.restore(impossible));
        assertEquals(before, queue.capture());
    }

    @Test
    void restoreDoesNotApplyLeadingValidEntryBeforeRejectingInvalidTail() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(definition(1, 0x100, 0x20), List.of(3));
        NemesisPlcQueueSnapshot before = queue.capture();
        NemesisPlcQueueSnapshot invalidTail = new NemesisPlcQueueSnapshot(null, List.of(
                entry(0x200, 0x40, 6, 6),
                entry(0x300, 0x60, 9, 8)));

        assertThrows(IllegalArgumentException.class, () -> queue.restore(invalidTail));

        assertEquals(before, queue.capture());
    }

    private static PlcDefinition definition(int plcId, int... sourceAndDestination) {
        if (sourceAndDestination.length % 2 != 0) {
            throw new IllegalArgumentException("source/destination arguments must be paired");
        }
        List<PlcEntry> entries = new ArrayList<>();
        for (int index = 0; index < sourceAndDestination.length; index += 2) {
            entries.add(new PlcEntry(sourceAndDestination[index], sourceAndDestination[index + 1]));
        }
        return new PlcDefinition(plcId, entries);
    }

    private static NemesisPlcQueueSnapshot.Entry entry(
            int sourceAddress, int destinationTile, int totalPatterns, int remainingPatterns) {
        return new NemesisPlcQueueSnapshot.Entry(
                sourceAddress, destinationTile, totalPatterns, remainingPatterns);
    }
}
