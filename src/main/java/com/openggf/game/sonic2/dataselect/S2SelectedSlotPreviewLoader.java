package com.openggf.game.sonic2.dataselect;

import com.openggf.graphics.RgbaImage;
import com.openggf.game.sonic1.dataselect.S1SelectedSlotPreviewLoader;

import java.util.Map;

/**
 * Sonic 2 selected-slot preview loader.
 *
 * <p>Sonic 2 and Sonic 1 currently share the same runtime PNG-to-pattern conversion contract, so
 * this class is a named adapter that keeps the Sonic 2 donated path explicit without duplicating the
 * quantization logic.</p>
 */
public final class S2SelectedSlotPreviewLoader {
    private final S1SelectedSlotPreviewLoader delegate = new S1SelectedSlotPreviewLoader();

    /**
     * Converts a whole preview map keyed by host zone ID.
     */
    public Map<Integer, S1SelectedSlotPreviewLoader.LoadedPreview> loadAll(Map<Integer, RgbaImage> previews) {
        return delegate.loadAll(previews);
    }

    /**
     * Converts a single Sonic 2 preview image into the donated renderer contract.
     */
    public S1SelectedSlotPreviewLoader.LoadedPreview load(RgbaImage image) {
        return delegate.load(image);
    }
}
