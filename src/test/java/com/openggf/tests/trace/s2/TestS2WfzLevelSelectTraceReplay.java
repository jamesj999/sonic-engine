package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;

/**
 * DELIBERATE RED. Green until the ObjB2 tornado comparison was wired in on
 * 2026-08-21; this class did not regress, it gained coverage.
 *
 * <p>{@code aux_state.jsonl} has always carried ObjB2's SST on every row of this
 * fixture and nothing read it. Comparing it exposes a small number of discrete,
 * pre-existing divergences — five spans across 24,056 rows on the two fixtures
 * combined, each one a specific nameable mismatch rather than a cascade.
 *
 * <p>Mechanism, measured divergences and the removal condition:
 * docs/status/known-discrepancies.md, plus the 2026-08-21 tornado entry in
 * docs/status/trace-frontier-log.md.
 */
public class TestS2WfzLevelSelectTraceReplay extends AbstractS2LevelSelectTraceReplayTest {
    public TestS2WfzLevelSelectTraceReplay() {
        super("wfz", Sonic2ZoneConstants.ZONE_WFZ);
    }
}
