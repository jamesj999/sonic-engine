package com.openggf.debug;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestDebugRendererS3kObjectNames {
    @Test
    void s3kDebugNamesUseSklObjectSetForMhz() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        assertEquals("Butterdroid",
                DebugRenderer.resolveObjectNameForDebug(registry, 0x8F, Sonic3kZoneIds.ZONE_MHZ));
    }

    @Test
    void s3kDebugNamesKeepS3klObjectSetForAiz() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        assertEquals("CaterKillerJr",
                DebugRenderer.resolveObjectNameForDebug(registry, 0x8F, Sonic3kZoneIds.ZONE_AIZ));
    }
}
