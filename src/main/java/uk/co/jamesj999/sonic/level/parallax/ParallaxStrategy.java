package uk.co.jamesj999.sonic.level.parallax;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;

public interface ParallaxStrategy {
    void load(Rom rom);
    void update(Camera cam, int frameCounter, int bgScrollY, int[] hScroll);
}
