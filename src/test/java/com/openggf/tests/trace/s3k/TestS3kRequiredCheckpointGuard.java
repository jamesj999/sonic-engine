package com.openggf.tests.trace.s3k;

import com.openggf.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kRequiredCheckpointGuard {

    private final S3kRequiredCheckpointGuard guard = new S3kRequiredCheckpointGuard();

    @Test
    void ignoresNonEntryCheckpoint() {
        assertDoesNotThrow(() -> guard.validateStrictEntry(
                5610,
                new TraceEvent.Checkpoint(5610, "aiz2_reload_resume", 0, 1, 0, 12, null),
                null));
    }

    @Test
    void acceptsMatchingFireTransitionCheckpoint() {
        TraceEvent.Checkpoint traceCheckpoint =
                new TraceEvent.Checkpoint(1651, "aiz1_fire_transition_begin", 0, 0, 0, 12, null);
        TraceEvent.Checkpoint engineCheckpoint =
                new TraceEvent.Checkpoint(1651, "aiz1_fire_transition_begin", 0, 0, 0, 12, null);

        assertDoesNotThrow(() -> guard.validateStrictEntry(
                1651,
                traceCheckpoint,
                engineCheckpoint));
    }

    @Test
    void acceptsMatchingIntroBeginCheckpoint() {
        assertDoesNotThrow(() -> guard.validateStrictEntry(
                0,
                new TraceEvent.Checkpoint(0, "intro_begin", null, null, null, 12, null),
                new TraceEvent.Checkpoint(0, "intro_begin", null, null, null, 12, null)));
    }

    @Test
    void acceptsEarlierReachedFireTransitionCheckpoint() {
        assertDoesNotThrow(() -> guard.validateStrictEntry(
                1651,
                new TraceEvent.Checkpoint(1651, "aiz1_fire_transition_begin", 0, 0, 0, 12, null),
                null,
                Set.of("intro_begin", "gameplay_start", "aiz1_fire_transition_begin")));
    }

    @Test
    void rejectsMissingFireTransitionCheckpoint() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> guard.validateStrictEntry(
                        1651,
                        new TraceEvent.Checkpoint(1651, "aiz1_fire_transition_begin", 0, 0, 0, 12, null),
                        null));

        assertTrue(exception.getMessage().contains("aiz1_fire_transition_begin"));
        assertTrue(exception.getMessage().contains("1651"));
    }

    @Test
    void rejectsMismatchedStrictEntryCheckpoint() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> guard.validateStrictEntry(
                        1651,
                        new TraceEvent.Checkpoint(1651, "aiz1_fire_transition_begin", 0, 0, 0, 12, null),
                        new TraceEvent.Checkpoint(1651, "intro_begin", null, null, null, 12, null)));

        assertTrue(exception.getMessage().contains("aiz1_fire_transition_begin"));
        assertTrue(exception.getMessage().contains("intro_begin"));
    }
}
