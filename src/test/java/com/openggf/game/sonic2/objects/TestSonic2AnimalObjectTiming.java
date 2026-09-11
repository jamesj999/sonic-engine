package com.openggf.game.sonic2.objects;

import com.openggf.game.GameRng;
import com.openggf.level.objects.AnimalObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic2AnimalObjectTiming {
    @Test
    void deferredSonic2AnimalArtVariantConsumesRngOnFirstRoutinePass() {
        GameRng control = new GameRng(GameRng.Flavour.S1_S2, 0x13579BDFL);
        control.nextBits(1);
        RecordingServices services = new RecordingServices(0x13579BDFL);

        AnimalObjectInstance animal = AnimalObjectInstance.deferredArtVariant(
                new ObjectSpawn(0x100, 0x120, 0x28, 0, 0, false, 0), services, null);
        animal.setServices(services);

        assertEquals(0x13579BDFL, services.rng().getSeed());

        animal.update(0, null);

        assertEquals(control.getSeed(), services.rng().getSeed());
    }

    private static final class RecordingServices extends StubObjectServices {
        private final GameRng rng;

        private RecordingServices(long seed) {
            this.rng = new GameRng(GameRng.Flavour.S1_S2, seed);
        }

        @Override
        public GameRng rng() {
            return rng;
        }
    }
}
