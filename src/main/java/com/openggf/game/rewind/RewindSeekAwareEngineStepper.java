package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;

/**
 * Optional extension for steppers that keep frame-edge state outside the
 * rewind registry, such as input handlers that need the previous-frame button
 * state for just-pressed detection.
 */
public interface RewindSeekAwareEngineStepper extends EngineStepper {
    void restoreToFrame(int frame, Bk2FrameInput inputAtFrame);
}
