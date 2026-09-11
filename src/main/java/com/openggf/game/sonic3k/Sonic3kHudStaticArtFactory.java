package com.openggf.game.sonic3k;

import com.openggf.level.Pattern;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.level.objects.HudStaticArtFactory;
import com.openggf.level.objects.HudStaticArtFactory.Layout;

public final class Sonic3kHudStaticArtFactory {
    private static final Layout LAYOUT = new Layout(1, null, 1, false);

    private Sonic3kHudStaticArtFactory() {
    }

    public static HudStaticArt create(Pattern[] textPatterns, Pattern[] livesPatterns) {
        return HudStaticArtFactory.create(textPatterns, livesPatterns, LAYOUT);
    }
}
