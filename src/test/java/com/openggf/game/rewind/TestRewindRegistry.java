package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestRewindRegistry {

    private static RewindSnapshottable<Integer> intSnap(String key, AtomicInteger ref) {
        return new RewindSnapshottable<>() {
            @Override public String key() { return key; }
            @Override public Integer capture() { return ref.get(); }
            @Override public void restore(Integer s) { ref.set(s); }
        };
    }

    private static RewindSnapshottable<Integer> resettableIntSnap(
            String key, AtomicInteger ref, int resetValue) {
        return new RewindSnapshottable<>() {
            @Override public String key() { return key; }
            @Override public Integer capture() { return ref.get(); }
            @Override public void restore(Integer s) { ref.set(s); }
            @Override public void resetForMissingSnapshot() { ref.set(resetValue); }
        };
    }

    @Test
    void captureWalksRegistrationOrder() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger a = new AtomicInteger(1), b = new AtomicInteger(2);
        reg.register(intSnap("a", a));
        reg.register(intSnap("b", b));
        CompositeSnapshot cs = reg.capture();
        assertEquals(java.util.List.of("a", "b"),
                java.util.List.copyOf(cs.entries().keySet()));
        assertEquals(1, cs.get("a"));
        assertEquals(2, cs.get("b"));
    }

    @Test
    void restoreAppliesEachSnapshot() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger a = new AtomicInteger(1);
        reg.register(intSnap("a", a));
        CompositeSnapshot cs = reg.capture();
        a.set(99);
        reg.restore(cs);
        assertEquals(1, a.get());
    }

    @Test
    void deregisterRemovesSubsystem() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger a = new AtomicInteger(1);
        reg.register(intSnap("a", a));
        reg.deregister("a");
        CompositeSnapshot cs = reg.capture();
        assertTrue(cs.entries().isEmpty());
    }

    @Test
    void duplicateKeyRejected() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger a = new AtomicInteger();
        reg.register(intSnap("dup", a));
        assertThrows(IllegalStateException.class,
                () -> reg.register(intSnap("dup", a)));
    }

    @Test
    void restoreOnUnknownKeyIsTolerated() {
        // If a snapshot has a key that's not registered (e.g. subsystem
        // was removed since capture), restore should silently skip it.
        RewindRegistry reg = new RewindRegistry();
        var entries = new java.util.LinkedHashMap<String, Object>();
        entries.put("ghost", 42);
        reg.restore(new CompositeSnapshot(entries));
        // No exception — pass.
    }
    @Test
    void registeredSubsystemMissingFromSnapshotResetsExplicitly() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger state = new AtomicInteger(99);
        reg.register(resettableIntSnap("late", state, -1));

        reg.restore(new CompositeSnapshot(new java.util.LinkedHashMap<>()));

        assertEquals(-1, state.get(),
                "registered subsystems absent from a snapshot should not retain newer state");
    }

    @Test
    void registeredSubsystemMissingFromSnapshotFailsClosedByDefault() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger state = new AtomicInteger(99);
        reg.register(intSnap("late", state));

        assertThrows(IllegalStateException.class,
                () -> reg.restore(new CompositeSnapshot(new java.util.LinkedHashMap<>())));
    }

    @Test
    void nullSnapshotsAreRejectedAtCapture() {
        RewindRegistry reg = new RewindRegistry();
        RewindSnapshottable<Object> adapter = new RewindSnapshottable<>() {
            @Override public String key() { return "null"; }
            @Override public Object capture() { return null; }
            @Override public void restore(Object snapshot) { }
        };
        reg.register(adapter);

        NullPointerException failure =
                assertThrows(NullPointerException.class, reg::capture);
        assertTrue(failure.getMessage().contains("key: null"));
        assertTrue(failure.getMessage().contains(adapter.getClass().getName()));
    }

    @Test
    void restoreRunsPostRestoreCallbacksAfterSubsystems() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger state = new AtomicInteger(7);
        AtomicInteger callbackSaw = new AtomicInteger(-1);
        reg.register(intSnap("state", state));
        reg.registerPostRestoreCallback("observer", () -> callbackSaw.set(state.get()));

        CompositeSnapshot cs = reg.capture();
        state.set(99);

        reg.restore(cs);

        assertEquals(7, state.get());
        assertEquals(7, callbackSaw.get(),
                "post-restore callbacks must see fully restored subsystem state");
    }

    @Test
    void delayedGameRngRestoreRunsAfterReconstructionSideEffectsAndBeforeCallbacks() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger rngSeed = new AtomicInteger(7);
        AtomicInteger callbackSaw = new AtomicInteger(-1);
        reg.register(intSnap("gamerng", rngSeed));
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "object-manager"; }
            @Override public Integer capture() { return 1; }
            @Override public void restore(Integer snapshot) {
                rngSeed.set(99);
            }
        });
        reg.registerPostRestoreCallback("observer", () -> callbackSaw.set(rngSeed.get()));

        CompositeSnapshot cs = reg.capture();
        rngSeed.set(123);

        reg.restore(cs);

        assertEquals(7, rngSeed.get(),
                "gamerng should be restored after reconstruction-time RNG side effects");
        assertEquals(7, callbackSaw.get(),
                "post-restore callbacks must observe delayed gamerng restore");
    }

    @Test
    void consecutiveCapturesShareLayoutButOwnSeparateValueStorage() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger state = new AtomicInteger(1);
        reg.register(intSnap("state", state));

        CompositeSnapshot first = reg.capture();
        state.set(2);
        CompositeSnapshot second = reg.capture();

        assertSame(first.layout(), second.layout());
        assertFalse(first.sharesValueStorageWith(second));
        assertEquals(1, first.get("state"));
        assertEquals(2, second.get("state"));
    }

    @Test
    void olderSnapshotResetsSubsystemRegisteredAfterCapture() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger original = new AtomicInteger(1);
        AtomicInteger late = new AtomicInteger(2);
        reg.register(intSnap("original", original));
        CompositeSnapshot beforeRegistration = reg.capture();

        reg.register(resettableIntSnap("late", late, -1));
        original.set(99);
        late.set(99);
        reg.restore(beforeRegistration);

        assertEquals(1, original.get());
        assertEquals(-1, late.get());
    }

    @Test
    void snapshotCapturedBeforeAdapterReplacementRestoresByKey() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger oldState = new AtomicInteger(7);
        reg.register(intSnap("state", oldState));
        CompositeSnapshot beforeReplacement = reg.capture();

        reg.deregister("state");
        AtomicInteger replacementState = new AtomicInteger(99);
        reg.register(intSnap("state", replacementState));
        CompositeSnapshot afterReplacement = reg.capture();

        assertNotSame(beforeReplacement.layout(), afterReplacement.layout());
        reg.restore(beforeReplacement);
        assertEquals(7, replacementState.get());
    }

    @Test
    void crossLayoutRestoreSkipsUnknownAndResetsMissingGameRngLastBeforeCallbacks() {
        RewindRegistry reg = new RewindRegistry();
        List<String> events = new ArrayList<>();
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "normal"; }
            @Override public Integer capture() { return 1; }
            @Override public void restore(Integer snapshot) { events.add("normal-restore"); }
        });
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "removed"; }
            @Override public Integer capture() { return 2; }
            @Override public void restore(Integer snapshot) { events.add("removed-restore"); }
        });
        CompositeSnapshot olderSnapshot = reg.capture();

        reg.deregister("removed");
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "gamerng"; }
            @Override public Integer capture() { return 7; }
            @Override public void restore(Integer snapshot) { events.add("rng-restore"); }
            @Override public void resetForMissingSnapshot() { events.add("rng-reset"); }
        });
        reg.registerPostRestoreCallback("observer", () -> events.add("callback"));

        reg.restore(olderSnapshot);

        assertEquals(List.of("normal-restore", "rng-reset", "callback"), events);
    }
}
