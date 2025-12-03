package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.camera.Camera;

import static org.junit.Assert.assertEquals;

public class TestCamera {

    @Test
    public void testSetAndGetBoundaries() {
        Camera camera = Camera.getInstance();

        short minX = 0;
        short maxX = 1000;
        short minY = 0;
        short maxY = 500;

        camera.setMinX(minX);
        camera.setMaxX(maxX);
        camera.setMinY(minY);
        camera.setMaxY(maxY);

        assertEquals(minX, camera.getMinX());
        assertEquals(maxX, camera.getMaxX());
        assertEquals(minY, camera.getMinY());
        assertEquals(maxY, camera.getMaxY());
    }
}
