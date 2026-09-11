package com.openggf.game.solid;

import com.openggf.game.rewind.snapshot.SolidExecutionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestSolidExecutionRewindSnapshot {

    @Test
    void keyIsSolidExecution() {
        assertEquals("solid-execution", new DefaultSolidExecutionRegistry().key());
    }

    @Test
    void captureReturnsEmptyRecord() {
        DefaultSolidExecutionRegistry reg = new DefaultSolidExecutionRegistry();
        SolidExecutionSnapshot snap = reg.capture();
        assertNotNull(snap);
        // A fresh registry has no previous-frame standing state, so the snapshot
        // must be the empty/sentinel record (no entries to serialise).
        assertNotNull(snap.previousStanding(), "previousStanding list must not be null");
        assertTrue(snap.previousStanding().isEmpty(),
                "fresh registry must capture an empty previousStanding list, got "
                        + snap.previousStanding());
        assertEquals(new SolidExecutionSnapshot(List.of()), snap,
                "fresh capture must equal the empty snapshot record");
    }

    @Test
    void restoreIsNoOp() {
        // No exception thrown — restore is intentionally a no-op for this registry
        // since object-reference-keyed maps cannot be meaningfully serialised.
        DefaultSolidExecutionRegistry reg = new DefaultSolidExecutionRegistry();
        SolidExecutionSnapshot snap = reg.capture();
        assertDoesNotThrow(() -> reg.restore(snap));
    }
}
