package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;

/**
 * Per-frame input source. v1 reads from a recorded trace; v2 will swap
 * in a live recorder that captures user inputs each frame.
 */
public interface InputSource {

    /** Total number of available frames. */
    int frameCount();

    /** Inputs for the given frame. Caller must respect 0 ≤ frame < frameCount(). */
    Bk2FrameInput read(int frame);
}
