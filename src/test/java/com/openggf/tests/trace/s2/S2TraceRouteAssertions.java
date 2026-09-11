package com.openggf.tests.trace.s2;

import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceMetadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class S2TraceRouteAssertions {
    private S2TraceRouteAssertions() {
    }

    static void assertRoute(TraceData trace, String zoneSlug, int engineZoneId,
                            int romZoneId, int metadataAct) {
        TraceMetadata metadata = trace.metadata();
        assertEquals("s2", metadata.game(), "trace game");
        assertEquals(zoneSlug, metadata.zone(), "trace zone slug");
        assertEquals(engineZoneId, metadata.zoneId(), "engine progression zone_id");
        assertEquals(romZoneId, metadata.romZoneId(), "raw S2 ROM zone id");
        assertEquals(metadataAct, metadata.act(), "1-based metadata act");
        assertTrue(hasFrameZeroGameplayMarker(trace),
                "frame 0 must include zone_act_state or gameplay_start checkpoint with game_mode=12");
    }

    private static boolean hasFrameZeroGameplayMarker(TraceData trace) {
        return trace.getEventsForFrame(0).stream().anyMatch(event -> {
            if (event instanceof TraceEvent.ZoneActState state) {
                return Integer.valueOf(12).equals(state.gameMode());
            }
            if (event instanceof TraceEvent.Checkpoint checkpoint) {
                return "gameplay_start".equals(checkpoint.name())
                        && Integer.valueOf(12).equals(checkpoint.gameMode());
            }
            return false;
        });
    }
}
