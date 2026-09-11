package com.openggf.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reporting integration for {@link BootstrapDivergence}. Exercises
 * {@link DivergenceReport}/{@link TraceEventFormatter} additions so the
 * bootstrap divergences appear in both JSON and text output ahead of
 * per-frame divergences.
 */
class TestBootstrapComparatorReporting {

    /**
     * Two divergences (ERROR + WARNING) feed through {@link DivergenceReport}
     * and surface in JSON output under a {@code bootstrap} block sorted ERROR
     * first.
     */
    @Test
    void json_output_lists_bootstrap_block_in_severity_order() throws Exception {
        BootstrapDivergence errorEntry = new BootstrapDivergence(
                "tails_cpu.routine",
                BootstrapDivergence.Severity.ERROR,
                "0x02", "0x00",
                "tails CPU routine mismatch");
        BootstrapDivergence warningEntry = new BootstrapDivergence(
                "player_history.snapshot",
                BootstrapDivergence.Severity.WARNING,
                "present", "missing",
                "player_history_snapshot missing from trace");

        // Feed WARNING first so we can prove the report sorts.
        DivergenceReport report = new DivergenceReport(
                List.of(),
                /* traceData */ null,
                List.of(warningEntry, errorEntry));

        String json = report.toJson();
        assertNotNull(json, "JSON output must not be null");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        JsonNode bootstrap = root.get("bootstrap");
        if (bootstrap == null) {
            fail("JSON output must contain a 'bootstrap' block. Got: " + json);
        }
        assertTrue(bootstrap.isArray(),
                () -> "'bootstrap' must be an array. Got: " + bootstrap);
        assertEquals(2, bootstrap.size(),
                () -> "Expected 2 bootstrap divergences in JSON output. Got: " + bootstrap);

        JsonNode first = bootstrap.get(0);
        JsonNode second = bootstrap.get(1);
        assertEquals("ERROR", first.get("severity").asText(),
                "First entry must be ERROR severity (sorted before WARNING)");
        assertEquals("tails_cpu.routine", first.get("field").asText());
        assertEquals("WARNING", second.get("severity").asText());
        assertEquals("player_history.snapshot", second.get("field").asText());
    }

    /**
     * Context window text output renders the bootstrap header before the
     * per-frame section header.
     */
    @Test
    void context_window_renders_bootstrap_header_before_per_frame() {
        BootstrapDivergence entry = new BootstrapDivergence(
                "object_slot[5].x_pos",
                BootstrapDivergence.Severity.ERROR,
                "0x0100", "0x0110",
                "object slot 5 x_pos mismatch");
        DivergenceReport report = new DivergenceReport(
                List.of(),
                /* traceData */ null,
                List.of(entry));

        String context = report.getContextWindow(0, 3);
        int bootstrapIdx = context.indexOf("=== Bootstrap (frame 0) ===");
        int perFrameIdx = context.indexOf("=== Per-frame ===");
        if (bootstrapIdx < 0) {
            fail("Context output must contain '=== Bootstrap (frame 0) ===' header. Got:\n"
                    + context);
        }
        if (perFrameIdx < 0) {
            fail("Context output must contain '=== Per-frame ===' header. Got:\n"
                    + context);
        }
        assertTrue(bootstrapIdx < perFrameIdx,
                () -> "Bootstrap header must precede per-frame header. Got:\n" + context);
        assertTrue(context.contains("object_slot[5].x_pos"),
                () -> "Context output should include the divergent field name. Got:\n"
                        + context);
    }
}
