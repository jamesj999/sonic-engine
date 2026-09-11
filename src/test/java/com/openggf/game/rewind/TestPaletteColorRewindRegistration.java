package com.openggf.game.rewind;

import com.openggf.game.palette.PaletteColorStateAdapter;
import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPaletteColorRewindRegistration {

    @Test
    public void compositeCaptureRestoreRevertsPaletteColors() {
        Palette[] normal = new Palette[] { new Palette(), new Palette() };
        normal[1].colors[15].r = (byte) 0x33;

        RewindRegistry registry = new RewindRegistry(null);
        registry.register(new PaletteColorStateAdapter(() -> normal, () -> null, () -> null));

        CompositeSnapshot snap = registry.capture();
        assertTrue(snap.containsKey("palette-colors"));

        normal[1].colors[15].r = (byte) 0x77; // post-capture mutation
        registry.restore(snap);

        assertEquals((byte) 0x33, normal[1].colors[15].r);
    }
}
