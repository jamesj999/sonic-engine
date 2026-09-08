package com.openggf;

import com.openggf.game.ContinueScreenProvider;
import java.util.function.IntConsumer;

/** Carries the screen's ROM V-int clock through fade callbacks and the level reload. */
final class GameLoopContinueClock {
    private boolean enteredThisIteration;

    void beginIteration() {
        enteredThisIteration = false;
    }

    void enterAfterFade(ContinueScreenProvider provider, int continues, int previousVint,
                        IntConsumer publish) {
        // The final old-mode fade VBlank precedes this callback. Its old mode
        // body will not run, and the new screen must not count that token again.
        provider.initialize(continues, previousVint + 1);
        enteredThisIteration = true;
        publish.accept(provider.currentVintRunCount());
    }

    void update(ContinueScreenProvider provider, Runnable admittedUpdate,
                Runnable consumeInput, IntConsumer publish) {
        if (enteredThisIteration) {
            consumeInput.run();
            return;
        }
        admittedUpdate.run();
        if (provider != null) publish.accept(provider.currentVintRunCount());
    }

    void finishFade(ContinueScreenProvider provider, IntConsumer publish) {
        if (provider == null) return;
        // Fade completion runs before the mode body. Publish this last VBlank
        // before reload inherits ObjectManager's clock and discards the provider.
        provider.advanceFadeFrame();
        publish.accept(provider.currentVintRunCount());
    }
}
