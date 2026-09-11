package com.openggf.debug;

import com.openggf.physics.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDebugRendererSensorLabelLayout {

    @Test
    void rightSideLabelsStackWithoutVerticalOverlap() {
        List<DebugRenderer.SensorLabelPlacement> placements = DebugRenderer.layoutSensorLabelsForSide(
                Direction.RIGHT,
                160,
                112,
                10,
                19,
                5,
                8,
                10,
                3,
                List.of(
                        new DebugRenderer.SensorLabelSpec(0, 100, 24),
                        new DebugRenderer.SensorLabelSpec(1, 112, 20),
                        new DebugRenderer.SensorLabelSpec(2, 124, 28)
                ));

        assertEquals(3, placements.size());
        assertEquals(178, placements.get(0).drawX());
        assertEquals(178, placements.get(1).drawX());
        assertEquals(178, placements.get(2).drawX());
        assertEquals(120, placements.get(0).drawY());
        assertEquals(112, placements.get(1).drawY());
        assertEquals(104, placements.get(2).drawY());
    }

    @Test
    void leftSideLabelsClearSpriteBoundsUsingWidestLabel() {
        List<DebugRenderer.SensorLabelPlacement> placements = DebugRenderer.layoutSensorLabelsForSide(
                Direction.LEFT,
                160,
                112,
                10,
                19,
                5,
                8,
                10,
                3,
                List.of(
                        new DebugRenderer.SensorLabelSpec(0, 104, 18),
                        new DebugRenderer.SensorLabelSpec(1, 120, 30)
                ));

        int spriteLeftBoundary = 160 - 10 - 8;
        for (DebugRenderer.SensorLabelPlacement placement : placements) {
            assertTrue(placement.drawX() + placement.spec().labelWidth() <= spriteLeftBoundary);
        }
        assertEquals(112 + 4, placements.get(0).drawY());
        assertEquals(112 - 4, placements.get(1).drawY());
    }

    @Test
    void downwardLabelsSpreadAcrossSingleRowWithoutOverlap() {
        List<DebugRenderer.SensorLabelPlacement> placements = DebugRenderer.layoutSensorLabelsForSide(
                Direction.DOWN,
                160,
                112,
                10,
                19,
                5,
                8,
                10,
                3,
                List.of(
                        new DebugRenderer.SensorLabelSpec(130, 0, 24),
                        new DebugRenderer.SensorLabelSpec(160, 1, 18),
                        new DebugRenderer.SensorLabelSpec(190, 2, 30)
                ));

        assertEquals(3, placements.size());
        assertEquals(83, placements.get(0).drawY());
        assertEquals(83, placements.get(1).drawY());
        assertEquals(83, placements.get(2).drawY());
        assertTrue(placements.get(1).drawX()
                >= placements.get(0).drawX() + placements.get(0).spec().labelWidth() + 3);
        assertTrue(placements.get(2).drawX()
                >= placements.get(1).drawX() + placements.get(1).spec().labelWidth() + 3);

        int leftEdge = placements.get(0).drawX();
        DebugRenderer.SensorLabelPlacement rightmost = placements.get(2);
        int rightEdge = rightmost.drawX() + rightmost.spec().labelWidth();
        assertEquals(160, (leftEdge + rightEdge) / 2);
    }
}
