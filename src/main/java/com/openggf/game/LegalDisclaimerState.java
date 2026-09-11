package com.openggf.game;

/**
 * State machine for {@link LegalDisclaimerScreen}, extracted into its own
 * class so it can be exercised headlessly without GL resources.
 * <p>
 * Phases:
 * <ol>
 *   <li>{@link Phase#FADE_IN} — fade-from-black animation is playing. Input
 *       ignored. Transitions to {@link Phase#READING} when
 *       {@link #onFadeInComplete()} is called.</li>
 *   <li>{@link Phase#READING} — 5 second readability gate. Input ignored.
 *       Transitions to {@link Phase#DISMISSIBLE} after
 *       {@link #READING_FRAMES} ticks.</li>
 *   <li>{@link Phase#DISMISSIBLE} — "Press any key to continue" prompt is
 *       visible. Any key transitions to {@link Phase#EXITING}.</li>
 *   <li>{@link Phase#EXITING} — fade-to-black is playing. Input ignored.
 *       {@link #onFadeOutComplete()} sets {@link #isDismissed()} to
 *       {@code true} so the host loop can hand off.</li>
 * </ol>
 */
public final class LegalDisclaimerState {

    /** 5 seconds at 60 fps. */
    public static final int READING_FRAMES = 300;

    public enum Phase {
        FADE_IN,
        READING,
        DISMISSIBLE,
        EXITING
    }

    private Phase phase = Phase.FADE_IN;
    private int readingFrameCounter;
    private int dismissibleFrameCounter;
    private boolean dismissed;

    public Phase getPhase() {
        return phase;
    }

    public int getReadingFrameCounter() {
        return readingFrameCounter;
    }

    /**
     * Frames elapsed since {@link Phase#DISMISSIBLE} began, used by the
     * screen to ramp the dismiss prompt from invisible to fully visible
     * before its pulse cycle takes over.
     */
    public int getDismissibleFrameCounter() {
        return dismissibleFrameCounter;
    }

    public boolean isDismissed() {
        return dismissed;
    }

    public boolean isDismissPromptVisible() {
        return phase == Phase.DISMISSIBLE;
    }

    public void onFadeInComplete() {
        if (phase == Phase.FADE_IN) {
            phase = Phase.READING;
            readingFrameCounter = 0;
        }
    }

    public void onFadeOutComplete() {
        if (phase == Phase.EXITING) {
            dismissed = true;
        }
    }

    /**
     * Advances one frame.
     *
     * @param anyKeyJustPressed whether any key transitioned to pressed
     *                          this frame (from
     *                          {@link com.openggf.control.InputHandler#isAnyKeyJustPressed()})
     * @return {@code true} if the phase transitioned to
     *         {@link Phase#EXITING} on this tick — useful for the host
     *         screen to kick off the fade-to-black exactly once.
     */
    public boolean tick(boolean anyKeyJustPressed) {
        switch (phase) {
            case READING -> {
                readingFrameCounter++;
                if (readingFrameCounter >= READING_FRAMES) {
                    phase = Phase.DISMISSIBLE;
                    dismissibleFrameCounter = 0;
                }
            }
            case DISMISSIBLE -> {
                dismissibleFrameCounter++;
                if (anyKeyJustPressed) {
                    phase = Phase.EXITING;
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }
}
