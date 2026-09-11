package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameRng;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TestAnimalObjectRngOwnership {
    private static final long INITIAL_SEED = 0x13579BDFL;
    private static final ObjectSpawn ANIMAL_SPAWN =
            new ObjectSpawn(0x100, 0x120, 0x28, 0, 0, false, 0);

    @Test
    void deferredAnimalConsumesRngOnceOnItsFirstOwnDispatch() {
        RecordingServices services = new RecordingServices(INITIAL_SEED);
        GameRng control = new GameRng(GameRng.Flavour.S3K, INITIAL_SEED);
        control.nextBits(1);

        AnimalObjectInstance animal =
                AnimalObjectInstance.deferredArtVariant(ANIMAL_SPAWN, services, null);
        animal.setServices(services);

        assertEquals(INITIAL_SEED, services.rng().getSeed(),
                "AllocateObject only installs Obj_Animal; construction must not execute loc_2C924");

        animal.update(0, null);
        assertEquals(control.getSeed(), services.rng().getSeed(),
                "the animal's first SST dispatch must consume exactly one Random_Number result");

        animal.update(1, null);
        assertEquals(control.getSeed(), services.rng().getSeed(),
                "later animal routines must not redraw the art variant");
    }

    @Test
    void rewindRecreateDoesNotConsumeAnimalRng() {
        RecordingServices services = new RecordingServices(INITIAL_SEED);
        AnimalObjectInstance animal =
                AnimalObjectInstance.deferredArtVariant(ANIMAL_SPAWN, services, null);

        animal.recreateForRewind(new RewindRecreateContext(ANIMAL_SPAWN, null, services));

        assertEquals(INITIAL_SEED, services.rng().getSeed(),
                "rewind recreate restores captured artVariant and must not execute Random_Number");
    }

    @Test
    void interveningSlotConsumesRngBeforeNewAnimalRunsItsOwnDispatch() {
        RecordingServices services = new RecordingServices(INITIAL_SEED);
        Camera camera = mock(Camera.class);
        services.camera = camera;
        ObjectManager manager = new ObjectManager(
                List.of(), new NoOpObjectRegistry(), 0, null, null,
                null, camera, services);
        services.objectManager = manager;

        ExplosionObjectInstance explosion = new ExplosionObjectInstance(
                0x27, 0x100, 0x120, null,
                (spawn, svc) -> AnimalObjectInstance.deferredArtVariant(spawn, svc, null),
                null, 0, false);
        RecordingRngConsumer intervening = new RecordingRngConsumer(
                new ObjectSpawn(0x110, 0x120, 0, 0, 0, false, 0));
        manager.addDynamicObjectAtSlot(explosion, 4);
        manager.addDynamicObjectAtSlot(intervening, 5);

        GameRng control = new GameRng(GameRng.Flavour.S3K, INITIAL_SEED);
        int expectedInterveningResult = control.nextRaw();
        control.nextBits(1);

        manager.update(0, null, List.of(), 1);

        assertEquals(expectedInterveningResult, intervening.result,
                "slot 5 must consume RNG after the slot-4 explosion allocates, before the later animal slot");
        manager.update(1, null, List.of(), 2);
        assertEquals(control.getSeed(), services.rng().getSeed(),
                "the allocated animal must consume the following result only when its own slot dispatches");
    }

    private static final class RecordingServices extends StubObjectServices {
        private final GameRng rng;
        private ObjectManager objectManager;
        private Camera camera = mock(Camera.class);

        private RecordingServices(long seed) {
            rng = new GameRng(GameRng.Flavour.S3K, seed);
        }

        @Override
        public GameRng rng() {
            return rng;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public Camera camera() {
            return camera;
        }
    }

    private static final class RecordingRngConsumer extends AbstractObjectInstance {
        private int result;

        private RecordingRngConsumer(ObjectSpawn spawn) {
            super(spawn, "RngConsumer");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            result = services().rng().nextRaw();
            setDestroyed(true);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class NoOpObjectRegistry implements ObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "noop";
        }
    }
}
