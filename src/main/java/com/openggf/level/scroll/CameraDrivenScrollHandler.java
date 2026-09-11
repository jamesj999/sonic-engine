package com.openggf.level.scroll;

import com.openggf.camera.Camera;

/**
 * Scroll handlers that own foreground camera movement as gameplay state.
 */
public interface CameraDrivenScrollHandler {

    /**
     * Advances the zone's camera-driving scroll logic for one gameplay frame.
     *
     * @return true when the normal player-follow camera step should be skipped
     */
    boolean advanceCameraForFrame(Camera camera, int actId);
}
