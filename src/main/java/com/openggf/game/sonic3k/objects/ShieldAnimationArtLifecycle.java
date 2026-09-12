package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.RewindStateful;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared S3K shield playback and DPLC renderer lifecycle.
 *
 * <p>The playback order intentionally differs from {@code ObjectAnimationState}:
 * each update advances the frame before publishing it, and a {@code SWITCH} end
 * action immediately initializes and publishes its target animation. This is the
 * Obj_FireShield / Obj_BubbleShield / Obj_LightningShield / Obj_InstaShield
 * convention, not the generic AnimateSprite convention.
 */
final class ShieldAnimationArtLifecycle
        implements RewindStateful<ShieldAnimationArtLifecycle.RewindState> {

    record Art(PlayerSpriteRenderer renderer, SpriteAnimationSet animationSet) {
    }

    record RewindState(int animationId, int frameIndex, int delayCounter, int mappingFrame) {
    }

    private PlayerSpriteRenderer dplcRenderer;
    private SpriteAnimationSet animationSet;
    private PlayerSpriteRenderer boundRenderer;
    private boolean artRefreshPending;
    /** Rewind can restore the cursor before this reconstructed shield has bound ROM art. */
    private boolean restoredPlaybackAwaitingArt;
    private int animationId;
    private int frameIndex;
    private int delayCounter;
    private int mappingFrame;

    ShieldAnimationArtLifecycle(int initialAnimationId) {
        animationId = initialAnimationId;
    }

    void ensureArtLoaded(Supplier<Art> artSupplier) {
        Objects.requireNonNull(artSupplier, "artSupplier");
        if (artRefreshPending) {
            boundRenderer = null;
            invalidateDplcCache();
            artRefreshPending = false;
        }
        if (dplcRenderer != null && animationSet != null) {
            return;
        }

        Art art = artSupplier.get();
        if (art == null) {
            return;
        }
        if (dplcRenderer == null && art.renderer() != null) {
            dplcRenderer = art.renderer();
            if (dplcRenderer != boundRenderer) {
                dplcRenderer.invalidateDplcCache();
                boundRenderer = dplcRenderer;
            }
        }
        if (animationSet == null && art.animationSet() != null) {
            animationSet = art.animationSet();
            if (!restoredPlaybackAwaitingArt) {
                initializeAnimation(animationId);
            }
            restoredPlaybackAwaitingArt = false;
        }
    }

    void refreshArtAfterRewindRestore() {
        artRefreshPending = true;
        boundRenderer = null;
        invalidateDplcCache();
    }

    void invalidateDplcCache() {
        if (dplcRenderer != null) {
            dplcRenderer.invalidateDplcCache();
        }
    }

    void setAnimation(int nextAnimationId) {
        if (nextAnimationId != animationId) {
            restoredPlaybackAwaitingArt = false;
            initializeAnimation(nextAnimationId);
        }
    }

    void restartAnimation(int nextAnimationId) {
        restoredPlaybackAwaitingArt = false;
        initializeAnimation(nextAnimationId);
    }

    void stepAnimation() {
        if (animationSet == null) {
            return;
        }
        SpriteAnimationScript script = animationSet.getScript(animationId);
        if (script == null || script.frames().isEmpty()) {
            return;
        }
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }
        delayCounter = script.delay();

        frameIndex++;
        if (frameIndex >= script.frames().size()) {
            switch (script.endAction()) {
                case LOOP -> frameIndex = 0;
                case LOOP_BACK -> frameIndex = Math.max(0, script.frames().size() - script.endParam());
                case SWITCH -> {
                    initializeAnimation(script.endParam());
                    return;
                }
                case HOLD -> frameIndex = script.frames().size() - 1;
            }
        }
        mappingFrame = script.frames().get(frameIndex);
    }

    PlayerSpriteRenderer renderer() {
        return dplcRenderer;
    }

    int animationId() {
        return animationId;
    }

    int frameIndex() {
        return frameIndex;
    }

    int delayCounter() {
        return delayCounter;
    }

    int mappingFrame() {
        return mappingFrame;
    }

    @Override
    public RewindState captureRewindStateValue() {
        return new RewindState(animationId, frameIndex, delayCounter, mappingFrame);
    }

    @Override
    public void restoreRewindStateValue(RewindState state) {
        animationId = state.animationId();
        frameIndex = state.frameIndex();
        delayCounter = state.delayCounter();
        mappingFrame = state.mappingFrame();
        restoredPlaybackAwaitingArt = true;
    }

    private void initializeAnimation(int nextAnimationId) {
        animationId = nextAnimationId;
        frameIndex = 0;
        if (animationSet == null) {
            return;
        }
        SpriteAnimationScript script = animationSet.getScript(nextAnimationId);
        if (script == null) {
            return;
        }
        delayCounter = script.delay();
        if (!script.frames().isEmpty()) {
            mappingFrame = script.frames().getFirst();
        }
    }
}
