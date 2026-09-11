package com.openggf.game.rewind;

import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.level.resources.NemesisPlcServiceQueue;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for the logical S1/S2 PLC FIFO across a rewind restore. */
class TestNemesisPlcQueueRewind {

    @Test
    void captureAdvanceRestoreReplayPreservesBusySequenceForEveryHeadState() {
        assertReplayState(new NemesisPlcServiceQueue(), 3);

        NemesisPlcServiceQueue unprepared = queued(6, 3);
        assertReplayState(unprepared, 3);

        NemesisPlcServiceQueue partialHead = queued(8, 3);
        partialHead.prepareHead();
        partialHead.servicePatterns(3);
        assertReplayState(partialHead, 3);

        NemesisPlcServiceQueue completedHead = queued(3, 6);
        completedHead.prepareHead();
        completedHead.servicePatterns(3);
        assertReplayState(completedHead, 3);

        NemesisPlcServiceQueue preparedSecond = queued(3, 6);
        preparedSecond.prepareHead();
        preparedSecond.servicePatterns(3);
        preparedSecond.prepareHead();
        assertReplayState(preparedSecond, 3);
    }

    @Test
    void captureAdvanceRestoreReplayPreservesAppendedAndIdleReplacementAndClearStates() {
        NemesisPlcServiceQueue appended = queued(3);
        appended.append(definition(2, 0x200, 0x40), List.of(6));
        assertReplayState(appended, 3);

        NemesisPlcServiceQueue replaced = queued(3);
        replaced.replaceQueued(definition(2, 0x200, 0x40), List.of(6));
        assertReplayState(replaced, 3);

        NemesisPlcServiceQueue cleared = queued(3);
        cleared.clearQueued();
        assertReplayState(cleared, 3);
    }

    @Test
    void rejectedActiveMutationsLeaveSnapshotUnchangedBeforeAndAfterRestore() {
        NemesisPlcServiceQueue queue = queued(6, 3);
        queue.prepareHead();
        NemesisPlcQueueSnapshot before = queue.capture();

        assertThrows(IllegalStateException.class,
                () -> queue.replaceQueued(definition(9, 0x900, 0x90), List.of(3)));
        assertThrows(IllegalStateException.class, queue::clearQueued);
        assertEquals(before, queue.capture());

        queue.servicePatterns(3);
        queue.restore(before);
        assertThrows(IllegalStateException.class,
                () -> queue.replaceQueued(definition(9, 0x900, 0x90), List.of(3)));
        assertThrows(IllegalStateException.class, queue::clearQueued);
        assertEquals(before, queue.capture());
    }

    private static void assertReplayState(NemesisPlcServiceQueue queue, int budget) {
        NemesisPlcQueueSnapshot snapshot = queue.capture();
        List<Boolean> original = advanceBusySequence(queue, budget);

        queue.restore(snapshot);
        assertEquals(snapshot, queue.capture(), "restore must be in-place and exact");
        assertEquals(original, advanceBusySequence(queue, budget),
                "restored PLC work must release consumers on the original frame");
    }

    private static List<Boolean> advanceBusySequence(NemesisPlcServiceQueue queue, int budget) {
        java.util.ArrayList<Boolean> busy = new java.util.ArrayList<>();
        busy.add(queue.isBusy());
        for (int frame = 0; frame < 12 && queue.isBusy(); frame++) {
            queue.prepareHead();
            queue.servicePatterns(budget);
            busy.add(queue.isBusy());
        }
        return List.copyOf(busy);
    }

    private static NemesisPlcServiceQueue queued(int... patternCounts) {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        int[] pairs = new int[patternCounts.length * 2];
        for (int index = 0; index < patternCounts.length; index++) {
            pairs[index * 2] = 0x100 + index * 0x100;
            pairs[index * 2 + 1] = 0x20 + index * 0x20;
        }
        queue.append(definition(1, pairs), java.util.Arrays.stream(patternCounts).boxed().toList());
        return queue;
    }

    private static PlcDefinition definition(int plcId, int... pairs) {
        java.util.ArrayList<PlcEntry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < pairs.length; index += 2) {
            entries.add(new PlcEntry(pairs[index], pairs[index + 1]));
        }
        return new PlcDefinition(plcId, entries);
    }
}
