package com.openggf.game.sonic2.credits;

import com.openggf.game.EndingPhase;
import com.openggf.game.EndingProvider;
import com.openggf.game.GameId;
import com.openggf.game.GameServices;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.save.SaveReason;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.game.resources.NativeFadeLifecycle;
import com.openggf.game.resources.NativeFadeLifecycleAware;
import com.openggf.game.resources.NoOpNativeFadeLifecycle;
import com.openggf.graphics.FadeManager;

import java.io.IOException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Sonic 2 implementation of the {@link EndingProvider} interface.
 * <p>
 * Coordinates the full ending sequence after the Death Egg Robot is defeated:
 * <ol>
 *   <li><b>CUTSCENE</b> - Ending photos, sky fall, Tornado rescue
 *       (delegated to {@link Sonic2EndingCutsceneManager})</li>
 *   <li><b>CREDITS_TEXT</b> - 21 credit text slides with fade in/out cycling
 *       (delegated to {@link Sonic2CreditsTextRenderer})</li>
 *   <li><b>POST_CREDITS</b> - "Sonic the Hedgehog 2" logo with palette flash
 *       (delegated to {@link Sonic2LogoFlashManager})</li>
 * </ol>
 * <p>
 * Unlike Sonic 1, S2 credits do not interleave demo playback between text
 * slides, so all demo-related defaults from EndingProvider remain as no-ops.
 * <p>
 * The internal state machine uses finer-grained states than EndingPhase to
 * track fade-in/display/fade-out timing for each credit slide.
 */
public class Sonic2EndingProvider implements EndingProvider, NativeFadeLifecycleAware {
    private static final Logger LOGGER = Logger.getLogger(Sonic2EndingProvider.class.getName());

    /**
     * Internal states for the S2 ending sequence, providing finer-grained
     * control than the public {@link EndingPhase} values.
     */
    private enum InternalState {
        /** Cutscene in progress (photos, sky fall, plane rescue). */
        CUTSCENE,
        /** Fade-to-black after cutscene, before credits begin. */
        CUTSCENE_FADE_OUT,
        /** Fade-in before a credit text slide. */
        CREDITS_FADE_IN,
        /** Credit text slide is being displayed. */
        CREDITS_TEXT,
        /** Fade-out after a credit text slide. */
        CREDITS_FADE_OUT,
        /** Initializing the logo flash manager. */
        LOGO_LOADING,
        /** Logo flash animation and hold. */
        LOGO_FLASH,
        /** Entire ending sequence is complete. */
        FINISHED
    }

    // Sub-component managers
    private Sonic2EndingCutsceneManager cutsceneManager;
    private Sonic2CreditsTextRenderer textRenderer;
    private Sonic2LogoFlashManager logoFlashManager;

    // Internal state machine
    private InternalState state = InternalState.CUTSCENE;
    private int currentSlide;
    private int slideTimer;
    private NativeFadeLifecycle nativeFadeLifecycle = NoOpNativeFadeLifecycle.INSTANCE;
    private final FadeManager injectedFadeManager;

    public Sonic2EndingProvider() {
        this(null);
    }

    Sonic2EndingProvider(FadeManager fadeManager) {
        injectedFadeManager = fadeManager;
    }

    @Override
    public void bindNativeFadeLifecycle(NativeFadeLifecycle lifecycle) {
        nativeFadeLifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
    }

    private Runnable nativeCompletion(Runnable completion) {
        return nativeFadeLifecycle.beginNativeBlockingFade().wrapCompletion(completion);
    }

    // ========================================================================
    // EndingProvider lifecycle
    // ========================================================================

    @Override
    public Optional<SaveReason> saveReasonOnEndingStart() {
        return Optional.of(SaveReason.PROGRESSION_SAVE);
    }

    @Override
    public void initialize() {
        try {
            DynamicArtLifecycleService dynamicArt =
                    GameServices.dynamicArtLifecycleOrNull();
            Sonic2EndingDynamicArtDecisionSink decisionSink = dynamicArt == null
                    ? Sonic2EndingDynamicArtDecisionSink.NONE
                    : decision -> dynamicArt.observePlayerDplc(
                            GameId.S2, decision.owner(), decision.mappingFrame(),
                            decision.dplcFrame(), decision.kind());
            cutsceneManager = new Sonic2EndingCutsceneManager(decisionSink);
            cutsceneManager.initialize(GameServices.rom().getRom());
        } catch (IOException e) {
            LOGGER.warning("Failed to get ROM for cutscene: " + e.getMessage());
            cutsceneManager = null;
        }

        state = InternalState.CUTSCENE;
        currentSlide = 0;
        slideTimer = 0;
        textRenderer = null;
        logoFlashManager = null;

        LOGGER.info("Sonic2EndingProvider initialized");
    }

    @Override
    public void update() {
        switch (state) {
            case CUTSCENE -> {
                if (cutsceneManager != null) {
                    cutsceneManager.update();
                    if (cutsceneManager.isDone()) {
                        // Fade to black before transitioning to credits
                        // ROM: PaletteFadeOut after plane flyaway
                        slideTimer = 0;
                        state = InternalState.CUTSCENE_FADE_OUT;
                        fadeManager().startFadeToBlack(nativeCompletion(() -> { }));
                    }
                } else {
                    // No cutscene manager (ROM unavailable) -- skip to credits
                    transitionToCreditsFadeIn();
                }
            }
            case CUTSCENE_FADE_OUT -> {
                // Wait for fade-to-black to complete (timer matches ROM timing)
                slideTimer++;
                if (slideTimer >= Sonic2CreditsData.FADE_DURATION) {
                    transitionToCreditsFadeIn();
                }
            }
            case CREDITS_FADE_IN -> {
                slideTimer++;
                if (slideTimer >= Sonic2CreditsData.FADE_DURATION) {
                    slideTimer = 0;
                    state = InternalState.CREDITS_TEXT;
                }
            }
            case CREDITS_TEXT -> {
                slideTimer++;
                if (slideTimer >= Sonic2CreditsData.SLIDE_DURATION_60FPS) {
                    // ROM: PaletteFadeOut after each credit slide
                    slideTimer = 0;
                    state = InternalState.CREDITS_FADE_OUT;
                    fadeManager().startFadeToBlack(nativeCompletion(() -> { }));
                }
            }
            case CREDITS_FADE_OUT -> {
                slideTimer++;
                if (slideTimer >= Sonic2CreditsData.FADE_DURATION) {
                    currentSlide++;
                    if (currentSlide >= Sonic2CreditsData.TOTAL_CREDITS) {
                        // All 21 slides complete -- transition to logo flash
                        transitionToLogoLoading();
                    } else {
                        // ROM: PaletteFadeIn for next credit slide
                        slideTimer = 0;
                        state = InternalState.CREDITS_FADE_IN;
                        fadeManager().startFadeFromBlack(nativeCompletion(() -> { }));
                    }
                }
            }
            case LOGO_LOADING -> {
                logoFlashManager = new Sonic2LogoFlashManager();
                logoFlashManager.initialize();
                state = InternalState.LOGO_FLASH;
                // ROM: PaletteFadeIn to reveal logo
                fadeManager().startFadeFromBlack(nativeCompletion(() -> { }));
            }
            case LOGO_FLASH -> {
                // Logo flash update is driven by GameLoop.updateEndingPostCredits()
                // which passes the real InputHandler for button skip detection.
                // We only check completion here; GameLoop calls logoFlash.update(inputHandler).
                if (logoFlashManager.isDone()) {
                    state = InternalState.FINISHED;
                    LOGGER.info("Sonic2 ending sequence complete");
                }
            }
            case FINISHED -> {
                // Nothing to do
            }
        }
    }

    @Override
    public void draw() {
        switch (state) {
            case CUTSCENE, CUTSCENE_FADE_OUT -> {
                if (cutsceneManager != null) {
                    cutsceneManager.draw();
                }
            }
            case CREDITS_FADE_IN, CREDITS_TEXT, CREDITS_FADE_OUT -> {
                if (textRenderer != null) {
                    textRenderer.draw(currentSlide);
                }
            }
            case LOGO_LOADING, LOGO_FLASH -> {
                if (logoFlashManager != null) {
                    logoFlashManager.draw();
                }
            }
            case FINISHED -> {
                // Nothing to draw
            }
        }
    }

    @Override
    public EndingPhase getCurrentPhase() {
        return switch (state) {
            case CUTSCENE, CUTSCENE_FADE_OUT -> EndingPhase.CUTSCENE;
            case CREDITS_FADE_IN, CREDITS_TEXT, CREDITS_FADE_OUT -> EndingPhase.CREDITS_TEXT;
            case LOGO_LOADING, LOGO_FLASH -> EndingPhase.POST_CREDITS;
            case FINISHED -> EndingPhase.FINISHED;
        };
    }

    @Override
    public boolean isComplete() {
        return state == InternalState.FINISHED;
    }

    @Override
    public void setClearColor() {
        if (cutsceneManager != null && (state == InternalState.CUTSCENE || state == InternalState.CUTSCENE_FADE_OUT)) {
            cutsceneManager.setClearColor();
        } else {
            // Credits text and logo phases: black background
            org.lwjgl.opengl.GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }
    }

    @Override
    public boolean needsLevelBackground() {
        if (cutsceneManager != null && (state == InternalState.CUTSCENE || state == InternalState.CUTSCENE_FADE_OUT)) {
            return cutsceneManager.needsLevelBackground();
        }
        return false;
    }

    @Override
    public int getBackgroundVscroll() {
        if (cutsceneManager != null) {
            return cutsceneManager.getBackgroundVscroll();
        }
        return 0;
    }

    @Override
    public float[] getBackdropColorOverride() {
        if (cutsceneManager != null && (state == InternalState.CUTSCENE || state == InternalState.CUTSCENE_FADE_OUT)) {
            return cutsceneManager.getBackdropColorOverride();
        }
        return null;
    }

    // ========================================================================
    // Manager accessors (for GameLoop integration)
    // ========================================================================

    /**
     * Returns the logo flash manager for GameLoop integration.
     * GameLoop needs direct access to pass the InputHandler for button skip
     * detection during the POST_CREDITS phase.
     *
     * @return the logo flash manager, or null if not yet in POST_CREDITS phase
     */
    public Sonic2LogoFlashManager getLogoFlashManager() {
        return logoFlashManager;
    }

    @Override
    public void updatePostCredits(com.openggf.control.InputHandler inputHandler) {
        if (logoFlashManager != null) {
            logoFlashManager.update(inputHandler);
        } else {
            // Logo not yet loaded -- let provider advance its internal state
            update();
        }
    }

    @Override
    public boolean consumePostCreditsExitRequest() {
        if (logoFlashManager != null && logoFlashManager.isDone()) {
            return true;
        }
        return isComplete();
    }

    /**
     * Returns the current credit slide index (0-20).
     * Useful for debugging/testing.
     */
    public int getCurrentSlide() {
        return currentSlide;
    }

    // ========================================================================
    // Internal transitions
    // ========================================================================

    /**
     * Transitions from cutscene to credits text.
     * Starts credits music and initializes the text renderer.
     */
    private void transitionToCreditsFadeIn() {
        // Play credits music (ROM: move.w #MusID_Credits,d0)
        GameServices.audio().playMusic(Sonic2AudioConstants.MUS_CREDITS);

        // Initialize credits text renderer
        textRenderer = new Sonic2CreditsTextRenderer();
        textRenderer.initialize();

        currentSlide = 0;
        slideTimer = 0;
        state = InternalState.CREDITS_FADE_IN;

        // ROM: PaletteFadeIn to reveal first credit slide
        fadeManager().startFadeFromBlack(nativeCompletion(() -> { }));

        LOGGER.info("Sonic2EndingProvider: cutscene complete, starting credits text");
    }

    private FadeManager fadeManager() {
        return injectedFadeManager != null ? injectedFadeManager : GameServices.fade();
    }

    void beginCreditsTextExitForLifecycleTest() {
        state = InternalState.CREDITS_TEXT;
        currentSlide = 0;
        slideTimer = Sonic2CreditsData.SLIDE_DURATION_60FPS - 1;
    }

    /**
     * Transitions from credits text to logo flash loading.
     */
    private void transitionToLogoLoading() {
        state = InternalState.LOGO_LOADING;
        LOGGER.info("Sonic2EndingProvider: all credit slides complete, loading logo");
    }
}
