package com.openggf.game.sonic1.dataselect;

import com.openggf.graphics.RgbaImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1SelectedSlotPreviewLoader {

    @Test
    void load_buildsFullTileGridPaletteAndFrame() {
        int[] pixels = new int[S1DataSelectImageGenerator.PREVIEW_WIDTH * S1DataSelectImageGenerator.PREVIEW_HEIGHT];
        for (int y = 0; y < S1DataSelectImageGenerator.PREVIEW_HEIGHT; y++) {
            for (int x = 0; x < S1DataSelectImageGenerator.PREVIEW_WIDTH; x++) {
                int r = (x * 255) / Math.max(1, S1DataSelectImageGenerator.PREVIEW_WIDTH - 1);
                int g = (y * 255) / Math.max(1, S1DataSelectImageGenerator.PREVIEW_HEIGHT - 1);
                int b = ((x + y) * 255)
                        / Math.max(1, (S1DataSelectImageGenerator.PREVIEW_WIDTH - 1)
                        + (S1DataSelectImageGenerator.PREVIEW_HEIGHT - 1));
                pixels[y * S1DataSelectImageGenerator.PREVIEW_WIDTH + x] =
                        0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        RgbaImage image = new RgbaImage(
                S1DataSelectImageGenerator.PREVIEW_WIDTH,
                S1DataSelectImageGenerator.PREVIEW_HEIGHT,
                pixels);

        S1SelectedSlotPreviewLoader.LoadedPreview preview = new S1SelectedSlotPreviewLoader().load(image);

        assertEquals(70, preview.patterns().length);
        assertNotNull(preview.palette());
        assertNotNull(preview.frame());
        assertEquals(70, preview.frame().pieces().size());
        assertEquals(0x31B, preview.frame().pieces().getFirst().tileIndex());
        assertTrue((preview.patterns()[0].getPixel(0, 0) & 0xFF) > 0,
                "quantized preview tiles should resolve to visible palette entries");
    }
}
