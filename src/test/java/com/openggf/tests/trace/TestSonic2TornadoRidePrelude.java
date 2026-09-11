package com.openggf.tests.trace;

import com.openggf.game.sonic2.trace.Sonic2TornadoRidePrelude;
import com.openggf.game.sonic2.objects.TornadoObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic2TornadoRidePrelude {

    @Test
    void derivesSczSeedsFromNativePreludeFormulas() {
        Sonic2TornadoRidePrelude.Seed seed = Sonic2TornadoRidePrelude.forTornado(tornado(0x50));

        assertEquals(0x5000, seed.playerYSubpixel());
        assertEquals(0xC0, seed.tornadoYSubpixel8());
    }

    @Test
    void derivesWfzSeedsFromNativePreludeFormulas() {
        Sonic2TornadoRidePrelude.Seed seed = Sonic2TornadoRidePrelude.forTornado(tornado(0x52));

        assertEquals(0x3800, seed.playerYSubpixel());
        assertEquals(0, seed.tornadoYSubpixel8());
    }

    @Test
    void leavesOtherTornadoRoutinesClean() {
        Sonic2TornadoRidePrelude.Seed seed = Sonic2TornadoRidePrelude.forTornado(tornado(0x54));

        assertEquals(0, seed.playerYSubpixel());
        assertEquals(0, seed.tornadoYSubpixel8());
    }

    private static TornadoObjectInstance tornado(int subtype) {
        return new TornadoObjectInstance(new ObjectSpawn(0, 0, 0xB2, subtype, 0, false, 0));
    }
}
