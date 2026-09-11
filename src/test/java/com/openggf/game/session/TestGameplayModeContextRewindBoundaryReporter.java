package com.openggf.game.session;

import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestGameplayModeContextRewindBoundaryReporter {

    @Test
    void defaultReporterAcceptsBoundary() {
        GameplayModeContext context = new GameplayModeContext(new WorldSession(new Sonic2GameModule()));

        assertDoesNotThrow(() -> context.markRewindBoundary(RewindBoundary.LEVEL_LOAD));
    }

    @Test
    void installedReporterReceivesBoundary() {
        GameplayModeContext context = new GameplayModeContext(new WorldSession(new Sonic2GameModule()));
        AtomicReference<RewindBoundary> seen = new AtomicReference<>();

        context.setRewindBoundaryReporter(seen::set);
        context.markRewindBoundary(RewindBoundary.SEAMLESS_LEVEL_TRANSITION);

        assertEquals(RewindBoundary.SEAMLESS_LEVEL_TRANSITION, seen.get());
    }

    @Test
    void nullReporterRestoresNoOpReporter() {
        GameplayModeContext context = new GameplayModeContext(new WorldSession(new Sonic2GameModule()));
        AtomicInteger calls = new AtomicInteger();

        context.setRewindBoundaryReporter(boundary -> calls.incrementAndGet());
        context.setRewindBoundaryReporter(null);
        context.markRewindBoundary(RewindBoundary.LEVEL_LOAD);

        assertEquals(0, calls.get());
    }
}
