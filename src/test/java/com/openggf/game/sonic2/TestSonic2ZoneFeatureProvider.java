package com.openggf.game.sonic2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic2ZoneFeatureProvider {

    @Test
    void cpz2SpritePaletteSplitMatchesVisualWaterLevel() {
        Sonic2ZoneFeatureProvider provider = new Sonic2ZoneFeatureProvider();

        assertEquals(0.0f,
                provider.getWaterlineOffset(Sonic2ZoneConstants.ROM_ZONE_CPZ, 1));
    }
}
