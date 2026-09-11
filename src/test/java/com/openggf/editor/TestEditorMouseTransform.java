package com.openggf.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEditorMouseTransform {

    @Test
    void mapsMouseToWorldTileAtOneTimesScale() {
        EditorMouseTransform.Result result = EditorMouseTransform.toWorldTile(
                64, 96,
                128, 64,
                0, 0, 320, 224,
                32, 32, 16);

        assertTrue(result.inViewport());
        assertEquals(192, result.worldX());
        assertEquals(160, result.worldY());
        assertEquals(6, result.tileX());
        assertEquals(5, result.tileY());
    }

    @Test
    void mapsMouseToWorldTileAtTwoTimesScaleWithViewportOffset() {
        EditorMouseTransform.Result result = EditorMouseTransform.toWorldTile(
                20 + 128, 12 + 192,
                128, 64,
                20, 12, 640, 448,
                32, 32, 16);

        assertTrue(result.inViewport());
        assertEquals(192, result.worldX());
        assertEquals(160, result.worldY());
        assertEquals(6, result.tileX());
        assertEquals(5, result.tileY());
    }

    @Test
    void mapsMouseToWorldTileAtThreeTimesScale() {
        EditorMouseTransform.Result result = EditorMouseTransform.toWorldTile(
                300, 288,
                10, 20,
                12, 9, 960, 672,
                16, 64, 64);

        assertTrue(result.inViewport());
        assertEquals(106, result.worldX());
        assertEquals(113, result.worldY());
        assertEquals(6, result.tileX());
        assertEquals(7, result.tileY());
    }

    @Test
    void returnsOutsideWhenMouseIsInLetterbox() {
        EditorMouseTransform.Result result = EditorMouseTransform.toWorldTile(
                8, 100,
                0, 0,
                20, 12, 640, 448,
                32, 32, 16);

        assertFalse(result.inViewport());
        assertEquals(-1, result.tileX());
        assertEquals(-1, result.tileY());
    }

    @Test
    void clampsTileToMapBounds() {
        EditorMouseTransform.Result result = EditorMouseTransform.toWorldTile(
                319, 223,
                2000, 2000,
                0, 0, 320, 224,
                32, 4, 3);

        assertTrue(result.inViewport());
        assertEquals(3, result.tileX());
        assertEquals(2, result.tileY());
    }
}
