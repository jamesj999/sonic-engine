package com.openggf.game;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TestMenuLoadTask {
    @Test void slowReadDoesNotBlockItsOwnerAndCancellationInterruptsIt() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        var load = new MenuLoadTask<>(() -> {
            started.countDown();
            try { gate.await(); } catch (InterruptedException e) { interrupted.countDown(); throw e; }
            return "late";
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertFalse(load.done());
        load.cancel();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.CancellationException.class, load::result);
    }

    @Test void resultsAndFailuresAreConsumedByTheOwner() throws Exception {
        var load = new MenuLoadTask<>(() -> "catalog");
        assertEquals("catalog", load.result());
        var failed = new MenuLoadTask<>(() -> { throw new java.io.IOException("Unreadable directory"); });
        var error = assertThrows(java.util.concurrent.ExecutionException.class, failed::result);
        assertEquals("Unreadable directory", error.getCause().getMessage());
    }
}
