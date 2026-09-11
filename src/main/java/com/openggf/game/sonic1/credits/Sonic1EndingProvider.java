package com.openggf.game.sonic1.credits;

import com.openggf.game.DemoLamppostState;
import com.openggf.game.EndingPhase;
import com.openggf.game.EndingProvider;
import com.openggf.graphics.FadeManager;
import com.openggf.game.resources.NativeFadeLifecycle;
import com.openggf.game.resources.NativeFadeLifecycleAware;
import com.openggf.game.resources.NoOpNativeFadeLifecycle;
import com.openggf.game.resources.PlcLifecyclePhase;

import java.util.Optional;
import java.util.logging.Logger;
import com.openggf.game.GameServices;

/**
 * Sonic 1 implementation of the {@link EndingProvider} interface.
 * <p>
 * Wraps the existing {@link Sonic1CreditsManager} (credits text + demo playback)
 * and {@link TryAgainEndManager} (post-credits TRY AGAIN / END screen) into the
 * game-agnostic ending provider contract. The internal managers are not modified;
 * this adapter translates their state into {@link EndingPhase} values and
 * delegates all demo-related accessors.
 * <p>
 * Phase mapping from {@link Sonic1CreditsManager.State}:
 * <ul>
 *   <li>TEXT_FADE_IN, TEXT_DISPLAY, TEXT_FADE_OUT &rarr; {@link EndingPhase#CREDITS_TEXT}</li>
 *   <li>DEMO_LOADING, DEMO_FADE_IN, DEMO_PLAYING, DEMO_FADING_OUT &rarr; {@link EndingPhase#CREDITS_DEMO}</li>
 *   <li>FINISHED &rarr; {@link EndingPhase#POST_CREDITS} (triggers TryAgainEnd)</li>
 * </ul>
 */
public class Sonic1EndingProvider implements EndingProvider, NativeFadeLifecycleAware {
    private static final Logger LOGGER = Logger.getLogger(Sonic1EndingProvider.class.getName());

    private Sonic1CreditsManager creditsManager;
    private TryAgainEndManager tryAgainEndManager;
    private EndingPhase currentPhase = EndingPhase.CREDITS_TEXT;
    private boolean complete;
    private NativeFadeLifecycle nativeFadeLifecycle = NoOpNativeFadeLifecycle.INSTANCE;
    private final FadeManager injectedFadeManager;
    private final Runnable injectedPostCreditsInitializer;

    public Sonic1EndingProvider() {
        this(null, null);
    }

    Sonic1EndingProvider(FadeManager fadeManager, Runnable postCreditsInitializer) {
        injectedFadeManager = fadeManager;
        injectedPostCreditsInitializer = postCreditsInitializer;
    }

    @Override
    public void bindNativeFadeLifecycle(NativeFadeLifecycle lifecycle) {
        nativeFadeLifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
    }

    // ========================================================================
    // EndingProvider lifecycle
    // ========================================================================

    @Override
    public void initialize() {
        creditsManager = new Sonic1CreditsManager(nativeFadeLifecycle);
        creditsManager.initialize();
        currentPhase = EndingPhase.CREDITS_TEXT;
        complete = false;
        tryAgainEndManager = null;

        LOGGER.info("Sonic1EndingProvider initialized");
    }

    @Override
    public void update() {
        if (complete) {
            return;
        }

        if (currentPhase == EndingPhase.POST_CREDITS) {
            // TryAgainEndManager is updated externally by GameLoop (needs InputHandler)
            return;
        }

        if (creditsManager != null) {
            creditsManager.update();
            updatePhaseFromCreditsState();
        }
    }

    void bindCreditsManagerForLifecycleTest(Sonic1CreditsManager manager) {
        creditsManager = java.util.Objects.requireNonNull(manager, "manager");
        currentPhase = EndingPhase.CREDITS_DEMO;
    }

    @Override
    public void draw() {
        if (currentPhase == EndingPhase.CREDITS_TEXT && creditsManager != null) {
            creditsManager.drawCreditText();
        } else if (currentPhase == EndingPhase.POST_CREDITS && tryAgainEndManager != null) {
            tryAgainEndManager.draw();
        }
        // CREDITS_DEMO drawing is handled by the level renderer in Engine.java
    }

    @Override
    public EndingPhase getCurrentPhase() {
        return currentPhase;
    }

    @Override
    public Optional<PlcLifecyclePhase> plcLifecyclePhaseOverride() {
        if (creditsManager != null
                && creditsManager.getState() == Sonic1CreditsManager.State.DEMO_FADING_OUT) {
            return Optional.of(PlcLifecyclePhase.CREDITS_DEMO_FADE);
        }
        return Optional.empty();
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    // ========================================================================
    // Demo playback support
    // ========================================================================

    @Override
    public boolean hasDemoLoadRequest() {
        return creditsManager != null && creditsManager.consumeDemoLoadRequest();
    }

    @Override
    public void consumeDemoLoadRequest() {
        // Already consumed in hasDemoLoadRequest() — Sonic1CreditsManager uses
        // a consume-on-read pattern, so this is intentionally a no-op.
    }

    @Override
    public int getDemoZone() {
        return creditsManager != null ? creditsManager.getDemoZone() : 0;
    }

    @Override
    public int getDemoAct() {
        return creditsManager != null ? creditsManager.getDemoAct() : 0;
    }

    @Override
    public int getDemoStartX() {
        return creditsManager != null ? creditsManager.getDemoStartX() : 0;
    }

    @Override
    public int getDemoStartY() {
        return creditsManager != null ? creditsManager.getDemoStartY() : 0;
    }

    @Override
    public void onDemoZoneLoaded() {
        if (creditsManager != null) {
            creditsManager.onDemoZoneLoaded();
        }
    }

    @Override
    public int getDemoInputMask() {
        return creditsManager != null ? creditsManager.getDemoInputMask() : 0;
    }

    @Override
    public boolean shouldRunDemoGameplay() {
        return creditsManager != null && creditsManager.shouldRunDemoGameplay();
    }

    @Override
    public boolean shouldRenderDemoSpritesOverFade() {
        return creditsManager != null && creditsManager.shouldRenderDemoSpritesOverFade();
    }

    @Override
    public boolean shouldAdvanceFrozenDemoScene() {
        return creditsManager != null && creditsManager.shouldAdvanceFrozenDemoScene();
    }

    @Override
    public boolean isScrollFrozen() {
        return creditsManager != null && creditsManager.isScrollFrozen();
    }

    @Override
    public boolean hasTextReturnRequest() {
        return creditsManager != null && creditsManager.consumeTextReturnRequest();
    }

    @Override
    public void consumeTextReturnRequest() {
        // Already consumed in hasTextReturnRequest() — same consume-on-read pattern.
    }

    @Override
    public DemoLamppostState getDemoLamppostState() {
        if (creditsManager != null && creditsManager.isLzDemo()) {
            return new DemoLamppostState(
                    Sonic1CreditsDemoData.LZ_LAMP_X,
                    Sonic1CreditsDemoData.LZ_LAMP_Y,
                    Sonic1CreditsDemoData.LZ_LAMP_RINGS,
                    Sonic1CreditsDemoData.LZ_LAMP_CAMERA_X,
                    Sonic1CreditsDemoData.LZ_LAMP_CAMERA_Y,
                    Sonic1CreditsDemoData.LZ_LAMP_BOTTOM_BND,
                    Sonic1CreditsDemoData.LZ_LAMP_WATER_HEIGHT,
                    Sonic1CreditsDemoData.LZ_LAMP_WATER_ROUTINE
            );
        }
        return null;
    }

    @Override
    public void onReturnToText() {
        if (creditsManager != null) {
            creditsManager.onReturnToText();
        }
    }

    // ========================================================================
    // Manager accessors (for GameLoop S1-specific logic)
    // ========================================================================

    /**
     * Returns the underlying credits manager for S1-specific GameLoop integration.
     * Used for LZ lamppost state setup and other S1-specific demo loading logic.
     *
     * @return the credits manager, or null if not yet initialized
     */
    public Sonic1CreditsManager getCreditsManager() {
        return creditsManager;
    }

    /**
     * Returns the TRY AGAIN / END manager for S1-specific GameLoop integration.
     * GameLoop needs direct access to pass the InputHandler for START press detection.
     *
     * @return the try again end manager, or null if not yet in POST_CREDITS phase
     */
    public TryAgainEndManager getTryAgainEndManager() {
        return tryAgainEndManager;
    }

    @Override
    public void updatePostCredits(com.openggf.control.InputHandler inputHandler) {
        if (tryAgainEndManager != null) {
            tryAgainEndManager.update(inputHandler);
        }
    }

    @Override
    public boolean consumePostCreditsExitRequest() {
        return tryAgainEndManager != null && tryAgainEndManager.consumeExitRequest();
    }

    // ========================================================================
    // Internal phase management
    // ========================================================================

    /**
     * Maps the current {@link Sonic1CreditsManager.State} to an {@link EndingPhase}.
     * Also detects the credits-finished flag to trigger the POST_CREDITS transition.
     */
    private void updatePhaseFromCreditsState() {
        if (creditsManager == null) {
            return;
        }

        // Check if credits sequence finished (after "PRESENTED BY SEGA" fade-out)
        if (creditsManager.consumeFinishedRequest()) {
            transitionToPostCredits();
            return;
        }

        Sonic1CreditsManager.State state = creditsManager.getState();
        currentPhase = switch (state) {
            case TEXT_FADE_IN, TEXT_DISPLAY, TEXT_FADE_OUT -> EndingPhase.CREDITS_TEXT;
            case DEMO_LOADING, DEMO_LOAD_DELAY, DEMO_FADE_IN, DEMO_PLAYING, DEMO_FADING_OUT -> EndingPhase.CREDITS_DEMO;
            case FINISHED -> EndingPhase.POST_CREDITS;
        };
    }

    /**
     * Transitions from credits to the TRY AGAIN / END screen.
     * Creates and initializes the TryAgainEndManager.
     */
    private void transitionToPostCredits() {
        currentPhase = EndingPhase.POST_CREDITS;

        // Fade out credits music before entering TRY AGAIN
        GameServices.audio().fadeOutMusic();

        FadeManager fadeManager = fadeManager();
        if (!fadeManager.isActive()) {
            fadeManager.startFadeToBlack(nativeFadeLifecycle.beginNativeBlockingFade()
                    .wrapCompletion(this::initTryAgainEnd));
        } else {
            initTryAgainEnd();
        }
    }

    private void initTryAgainEnd() {
        if (injectedPostCreditsInitializer != null) {
            injectedPostCreditsInitializer.run();
        } else {
            tryAgainEndManager = new TryAgainEndManager();
            tryAgainEndManager.initialize();
        }
        fadeManager().startFadeFromBlack(nativeFadeLifecycle.beginNativeBlockingFade()
                .wrapCompletion(() -> { }));
        LOGGER.info("Sonic1EndingProvider: transitioned to POST_CREDITS (TRY AGAIN / END)");
    }

    private FadeManager fadeManager() {
        return injectedFadeManager != null ? injectedFadeManager : GameServices.fade();
    }

    void transitionToPostCreditsForLifecycleTest() {
        transitionToPostCredits();
    }

    /**
     * Called by GameLoop when the TRY AGAIN / END screen signals exit.
     * Marks the ending sequence as complete.
     */
    public void markComplete() {
        complete = true;
        currentPhase = EndingPhase.FINISHED;
    }
}
