package com.openggf.level.animation;

import com.openggf.game.rewind.snapshot.PatternAnimatorSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link AniPlcScriptState}'s rewind accessors and the
 * {@link PatternAnimatorSnapshot} record (Track E.1).
 *
 * <p>Tests use minimal stub data — no ROM or OpenGL required.
 */
class TestAniPlcScriptStateSnapshot {

    /** Construct a minimal AniPlcScriptState with no art (no-op tick/prime). */
    private static AniPlcScriptState minimal() {
        return new AniPlcScriptState(
                (byte) 7,   // globalDuration
                0x10,       // destTileIndex
                new int[]{0, 1, 2},
                null,
                4,
                new com.openggf.level.Pattern[0]);
    }

    @Test
    void initialCountersAreZero() {
        AniPlcScriptState s = minimal();
        assertEquals(0, s.getTimer());
        assertEquals(0, s.getFrameIndex());
    }

    @Test
    void restoreCountersRoundTrip() {
        AniPlcScriptState s = minimal();
        s.restoreCounters(5, 2);
        assertEquals(5, s.getTimer());
        assertEquals(2, s.getFrameIndex());
    }

    @Test
    void scriptCounterRecordPreservesFields() {
        PatternAnimatorSnapshot.ScriptCounter sc = new PatternAnimatorSnapshot.ScriptCounter(13, 7);
        assertEquals(13, sc.timer());
        assertEquals(7, sc.frameIndex());
    }

    @Test
    void handlerCounterRecordPreservesAllSlots() {
        PatternAnimatorSnapshot.HandlerCounter hc =
                new PatternAnimatorSnapshot.HandlerCounter(0xAA, 0xBB, 0xCC);
        assertEquals(0xAA, hc.slot0());
        assertEquals(0xBB, hc.slot1());
        assertEquals(0xCC, hc.slot2());
    }

    @Test
    void snapshotRecordPreservesCounterArrays() {
        PatternAnimatorSnapshot.ScriptCounter[] sc = {
                new PatternAnimatorSnapshot.ScriptCounter(3, 1),
                new PatternAnimatorSnapshot.ScriptCounter(7, 0)
        };
        PatternAnimatorSnapshot.HandlerCounter[] hc = {
                new PatternAnimatorSnapshot.HandlerCounter(1, 2, 3)
        };
        byte[] extra = {0x01, 0x02};

        PatternAnimatorSnapshot snap = new PatternAnimatorSnapshot(sc, hc, extra);
        assertEquals(2, snap.scriptCounters().length);
        assertEquals(3, snap.scriptCounters()[0].timer());
        assertEquals(7, snap.scriptCounters()[1].timer());
        assertEquals(1, snap.handlerCounters()[0].slot0());
        assertArrayEquals(extra, snap.extra());
    }

    @Test
    void restoreCountersThenReadBack() {
        AniPlcScriptState s = minimal();
        // Simulate a capture → modify → restore cycle
        s.restoreCounters(0, 0); // initial
        int capturedTimer = s.getTimer();
        int capturedFrame = s.getFrameIndex();

        s.restoreCounters(42, 1);
        assertEquals(42, s.getTimer());
        assertEquals(1, s.getFrameIndex());

        // Restore back to initial
        s.restoreCounters(capturedTimer, capturedFrame);
        assertEquals(0, s.getTimer());
        assertEquals(0, s.getFrameIndex());
    }
}
