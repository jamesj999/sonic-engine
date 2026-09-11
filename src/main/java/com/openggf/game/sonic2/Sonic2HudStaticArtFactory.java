package com.openggf.game.sonic2;

import com.openggf.level.Pattern;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.level.objects.HudStaticArtFactory;
import com.openggf.level.objects.HudStaticArtFactory.Layout;

public final class Sonic2HudStaticArtFactory {
    private static final Layout NATIVE_LAYOUT = new Layout(1, 0, 1, false);
    private static final Layout DONOR_LAYOUT = new Layout(1, 0, 0, false);

    private Sonic2HudStaticArtFactory() {
    }

    public static HudStaticArt create(Pattern[] textPatterns, Pattern[] livesPatterns, boolean donorUsesIconPalette) {
        return HudStaticArtFactory.create(
                textPatterns,
                livesPatterns,
                donorUsesIconPalette ? DONOR_LAYOUT : NATIVE_LAYOUT);
    }
}
