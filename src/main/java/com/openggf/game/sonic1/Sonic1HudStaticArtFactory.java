package com.openggf.game.sonic1;

import com.openggf.level.Pattern;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.level.objects.HudStaticArtFactory;
import com.openggf.level.objects.HudStaticArtFactory.Layout;

public final class Sonic1HudStaticArtFactory {
    private static final Layout LAYOUT = new Layout(0, 0, 0, true);

    private Sonic1HudStaticArtFactory() {
    }

    public static HudStaticArt create(Pattern[] textPatterns, Pattern[] livesPatterns) {
        return HudStaticArtFactory.create(textPatterns, livesPatterns, LAYOUT);
    }
}
