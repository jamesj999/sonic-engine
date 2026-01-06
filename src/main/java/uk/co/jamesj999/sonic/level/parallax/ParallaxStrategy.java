package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;

public interface ParallaxStrategy {
    /**
     * Updates the horizontal scroll buffer for the given zone.
     * @param hScroll The horizontal scroll buffer (224 entries).
     * @param camera The camera instance.
     * @param frameCounter The global frame counter.
     * @param bgScrollY The background Y scroll position.
     * @param actId The current act ID.
     */
    void update(int[] hScroll, Camera camera, int frameCounter, int bgScrollY, int actId);
}
