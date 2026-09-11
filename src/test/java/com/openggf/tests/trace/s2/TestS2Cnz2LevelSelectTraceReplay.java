package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;

/**
 * DELIBERATE RED. Green until the CNZ slot-machine comparison was wired in on
 * 2026-08-21; this class did not regress, it gained coverage.
 *
 * <p>{@code aux_state.jsonl} has always carried the ROM's own
 * {@code SlotMachineVariables} on every row of this fixture and nothing read
 * it. Comparing it exposes divergences that were present all along and
 * invisible: they sit entirely inside the slot machine's active windows -- a
 * handful at the level-start initial roll, the rest during the single slot
 * session -- and zero rows elsewhere.
 *
 * <p>The owner is the unresolved three-member ordering group: the engine calls
 * SlotMachine from pre-physics while the ROM calls it after the object pass,
 * the object-visible clock is read before its tick, and ObjD6's completion
 * phase is unmodelled. Fixing that group clears this class and lets the
 * comparison be promoted from WARNING to ERROR. Mechanism and the removal
 * condition: docs/status/known-discrepancies.md and the 2026-08-21
 * tick-ownership entries in docs/status/trace-frontier-log.md.
 */
public class TestS2Cnz2LevelSelectTraceReplay extends AbstractS2LevelSelectTraceReplayTest {
    public TestS2Cnz2LevelSelectTraceReplay() {
        super("cnz2", Sonic2ZoneConstants.ZONE_CNZ, 1);
    }
}
