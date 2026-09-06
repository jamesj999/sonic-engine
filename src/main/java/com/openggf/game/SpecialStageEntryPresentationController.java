package com.openggf.game;

import com.openggf.graphics.FadeManager;
import com.openggf.game.resources.NativeFadeLifecycle;

import java.util.Objects;

/**
 * Coordinates a special-stage entry's opaque window with its reveal and music.
 *
 * <p>A white entry runs the ROM's own {@code Pal_FadeToWhite} over the level's
 * last frame and then parks white until the provider reports its reveal
 * boundary; a black entry (a screen a native owner already holds black) simply
 * keeps that hold. Either way the reveal starts the stage music and the fade
 * back exactly once.
 */
public final class SpecialStageEntryPresentationController {
    private boolean pending;
    private boolean revealFromBlack;

    public void begin(SpecialStageProvider provider, boolean fromBlack,
                      FadeManager fade, Runnable musicStart) {
        begin(provider, fromBlack, fade, musicStart,
                com.openggf.game.resources.NoOpNativeFadeLifecycle.INSTANCE);
    }

    public void begin(SpecialStageProvider provider, boolean fromBlack,
                      FadeManager fade, Runnable musicStart,
                      NativeFadeLifecycle lifecycle) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(fade, "fade");
        Objects.requireNonNull(musicStart, "musicStart");
        clear();
        if (provider.isEntryPresentationReady()) {
            reveal(fromBlack, fade, musicStart, lifecycle);
            return;
        }
        pending = true;
        revealFromBlack = fromBlack;
        if (fromBlack) {
            fade.holdBlack();
        } else {
            // The ROM fades the level's last frame to white over 22 V-ints
            // (Pal_FadeToWhite, docs/s2disasm/s2.asm:3571-3582; PaletteWhiteOut,
            // "_inc/Palette Fading.asm":313-326; docs/skdisasm/sonic3k.asm:
            // 5232-5242) and then keeps the palette white while the stage
            // loads, so the screen parks white until the provider reaches its
            // reveal boundary. The mode change that started this entry ran
            // inside the level's own frame, so the first colour step belongs
            // to the next V-int.
            fade.startFadeToWhite(null, Integer.MAX_VALUE);
            fade.deferFirstStepToNextVint();
        }
    }

    public void update(SpecialStageProvider provider, FadeManager fade, Runnable musicStart,
                       NativeFadeLifecycle lifecycle) {
        if (pending && provider.isEntryPresentationReady()) {
            reveal(revealFromBlack, fade, musicStart, lifecycle);
        }
    }

    public void update(SpecialStageProvider provider, FadeManager fade, Runnable musicStart) {
        update(provider, fade, musicStart,
                com.openggf.game.resources.NoOpNativeFadeLifecycle.INSTANCE);
    }

    public void clear() {
        pending = false;
        revealFromBlack = false;
    }

    public boolean isPending() {
        return pending;
    }

    private void reveal(boolean fromBlack, FadeManager fade, Runnable musicStart,
                        NativeFadeLifecycle lifecycle) {
        pending = false;
        musicStart.run();
        Runnable completion = lifecycle.beginNativeBlockingFade()
                .wrapCompletion(() -> { });
        if (fromBlack) {
            fade.startFadeFromBlack(completion);
        } else {
            fade.startFadeFromWhite(completion);
        }
    }
}
