package com.openggf;

import com.openggf.game.ContinueScreenProvider;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestContinueClockLifecycle {
    @Test void entryCompletionIsCountedOnceAndSetupWaitSurvivesPublication() {
        var clock = new GameLoopContinueClock();
        var provider = new Screen(1);
        var global = new AtomicInteger(100);
        var inputs = new AtomicInteger();
        clock.beginIteration();
        clock.enterAfterFade(provider, 2, global.get(), global::set);
        clock.update(provider, () -> provider.update(false, false), inputs::incrementAndGet, global::set);
        assertEquals(102, global.get()); // old fade's final VBlank plus screen setup wait
        assertEquals(0, provider.objectTicks);
        assertEquals(1, inputs.get());
    }

    @Test void allScreenAndFadeTicksReachReloadClockWithoutExtraObjectPass() {
        var clock = new GameLoopContinueClock();
        var provider = new Screen(0);
        var global = new AtomicInteger(255);
        clock.enterAfterFade(provider, 1, global.get(), global::set);
        for (int i = 0; i < 22; i++) {
            clock.beginIteration();
            clock.update(provider, provider::advanceFadeFrame, () -> {}, global::set);
        }
        for (int i = 0; i < 90; i++) {
            clock.beginIteration();
            clock.update(provider, () -> provider.update(false, false), () -> {}, global::set);
        }
        for (int i = 0; i < 21; i++) {
            clock.beginIteration();
            clock.update(provider, provider::advanceFadeFrame, () -> {}, global::set);
        }
        clock.beginIteration();
        clock.finishFade(provider, global::set);
        assertEquals(390, global.get());
        assertEquals(90, provider.objectTicks);
        provider.reset();
        assertEquals(390, global.get(), "reload inherits the published clock after provider disposal");
    }

    @Test void pausedEntryDoesNotSuppressFirstAdmittedTickAfterResume() {
        var clock = new GameLoopContinueClock();
        var provider = new Screen(0);
        var global = new AtomicInteger();
        clock.enterAfterFade(provider, 1, 10, global::set);
        // Window pause prevents the body from executing on the entry iteration.
        clock.beginIteration();
        clock.beginIteration();
        assertEquals(11, global.get());
        clock.update(provider, provider::advanceFadeFrame, () -> {}, global::set);
        assertEquals(12, global.get());
    }

    private static final class Screen implements ContinueScreenProvider {
        private final int setupWaits;
        private int vint;
        private int objectTicks;
        Screen(int setupWaits) { this.setupWaits = setupWaits; }
        @Override public void initialize(int continues) { initialize(continues, 0); }
        @Override public void initialize(int continues, int seed) { vint = seed + setupWaits; }
        @Override public int currentVintRunCount() { return vint; }
        @Override public void advanceFadeFrame() { vint++; }
        @Override public void update(boolean start, boolean start2) { vint++; objectTicks++; }
        @Override public void reset() { vint = 0; }
        @Override public void draw() { }
        @Override public boolean isAccepted() { return true; }
        @Override public boolean isFinished() { return false; }
    }
}
