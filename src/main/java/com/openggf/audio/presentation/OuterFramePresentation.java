package com.openggf.audio.presentation;

import com.openggf.audio.AudioManager;

import java.util.Objects;

/**
 * The shared production outer-frame audio boundary.
 *
 * <p>Engine display and every headless presentation driver enter here so that
 * exactly one {@link AudioManager#presentFrame(PresentationMode)} happens per
 * presented outer frame. Speaker output, PCM history, and capture leases are
 * all clocked by that single call, so a second entry point — or a
 * fast-forward loop that presents once per simulation step rather than once
 * per presented frame — would let them drift apart.
 *
 * <p>Mode selection is deliberately total and ordered: a modal picker, a pause,
 * or a frame-step request all yield fresh silence without moving the cursor or
 * history; an active reverse presentation reads history rather than
 * synthesizing; everything else advances forward.
 *
 * <p>Extracted from {@code GameLoop} so the release-critical loop class does
 * not own audio presentation policy. {@code GameLoop} keeps thin delegating
 * methods for its existing callers.
 */
public final class OuterFramePresentation {

    /** Test seam: observes the mode each presented outer frame resolved to. */
    @FunctionalInterface
    public interface Probe {
        void presented(PresentationMode mode);
    }

    private final AudioManager audioManager;
    private Probe probe = ignored -> { };

    public OuterFramePresentation(AudioManager audioManager) {
        this.audioManager = Objects.requireNonNull(audioManager, "audioManager");
    }

    /** Resolves the mode this outer frame presents in, without presenting it. */
    public PresentationMode modeFor(boolean modalPicker, boolean paused,
                                    boolean frameStepRequested) {
        if (modalPicker || paused || frameStepRequested) {
            return PresentationMode.SILENT;
        }
        return audioManager.isReverseAudioPresentationActive()
                ? PresentationMode.REVERSE
                : PresentationMode.FORWARD;
    }

    /** Presents exactly one outer frame in its resolved mode. */
    public void present(boolean modalPicker, boolean paused,
                        boolean frameStepRequested) {
        PresentationMode mode = modeFor(modalPicker, paused, frameStepRequested);
        audioManager.presentFrame(mode);
        probe.presented(mode);
    }

    public void setProbe(Probe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }
}
