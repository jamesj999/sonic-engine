package com.openggf.capture;

/** Drains the current frame's stereo PCM into {@code target}. */
public interface AudioFrameTap {
    /**
     * @param target interleaved stereo buffer, sized for the max samples/frame*2
     * @return the number of stereo frames written (0..target.length/2)
     */
    int drain(short[] target);
}
