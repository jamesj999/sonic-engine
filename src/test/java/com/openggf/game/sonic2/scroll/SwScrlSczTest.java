package com.openggf.game.sonic2.scroll;

import com.openggf.camera.Camera;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SCZ drives the camera itself and keeps its scroll state (BG X accumulator,
 * level-event routine index, tornado velocity) in the LOGICAL frame step
 * ({@code advanceCameraForFrame}), not render-time {@code update()}. That state
 * is not derivable from the frame counter, so it must round-trip through the
 * rewind snapshot; otherwise a rewind leaves the Sky Chase background (and the
 * auto-scroll phase) drifting away from the restored frame.
 */
@ExtendWith(SingletonResetExtension.class)
class SwScrlSczTest {

    @Test
    void rewindStateRoundTripsThroughCaptureRestore() {
        SwScrlScz handler = new SwScrlScz();
        handler.init();

        Camera cam = new Camera();
        cam.setX((short) 0);
        cam.setY((short) 0);

        // Advance a few frames: routine 0 -> 1 (velX = 1), BG X accumulator grows.
        for (int i = 0; i < 5; i++) {
            handler.advanceCameraForFrame(cam, 0);
        }
        Object captured = handler.captureRewindState();

        // Drive further: the BG X accumulator keeps growing.
        for (int i = 0; i < 10; i++) {
            handler.advanceCameraForFrame(cam, 0);
        }
        assertNotEquals(captured, handler.captureRewindState(),
                "SCZ scroll state should advance across frames");

        // A rewind restore must reproduce the captured state exactly.
        handler.restoreRewindState(captured);
        assertEquals(captured, handler.captureRewindState(),
                "restoreRewindState must reproduce the captured SCZ scroll state");
    }
}
