package com.openggf.tests.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * CI guard: the historical {@code oggf.trace.hydrate} diagnostic property must
 * remain unset. The committed trace replay path is comparison-only and no
 * longer contains a hydration gate; this catches accidental CI/property
 * reintroduction before it can mask engine divergences.
 */
class TestTraceHydrateSwitchDefault {

    @Test
    void hydrateSwitchDefaultsOff() {
        assertFalse(Boolean.getBoolean("oggf.trace.hydrate"),
                "oggf.trace.hydrate must remain unset in CI — hydration runs are "
                        + "diagnostic only and never count as green");
    }
}
