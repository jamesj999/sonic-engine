package com.openggf.level.objects;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.objects.Sonic1AnimalsObjectInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1DestructionConfig;
import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDestructionEffects {

    private ObjectManager objectManager;
    private ObjectServices services;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();

        services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public com.openggf.camera.Camera camera() {
                return GameServices.camera();
            }
        };

        objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null,
                null, GameServices.camera(), services);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void sonic1BadnikDestructionDefersAnimalUntilExplosionExecutes() {
        DestructionConfig config = Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
        config = new DestructionConfig(
                config.sfxId(),
                config.animalFactory(),
                config.useRespawnTracking(),
                null,
                config.explosionFactory(),
                config.pointsAllocatedBeforeAnimal());

        DestructionEffects.destroyBadnik(
                0x0100,
                0x0120,
                new ObjectSpawn(0x0100, 0x0120, 0x78, 0, 0, false, 0),
                40,
                null,
                services,
                config);

        assertTrue(objectManager.getActiveObjects().stream()
                        .anyMatch(ExplosionObjectInstance.class::isInstance),
                "S1 badnik destruction should immediately replace the badnik with an explosion");
        assertFalse(objectManager.getActiveObjects().stream()
                        .anyMatch(Sonic1AnimalsObjectInstance.class::isInstance),
                "S1 should not spawn the animal until ExplosionItem routine 0 runs");

        objectManager.update(0, null, List.of(), 1);

        assertTrue(objectManager.getActiveObjects().stream()
                        .anyMatch(Sonic1AnimalsObjectInstance.class::isInstance),
                "S1 explosion processing should spawn the ROM-ported Sonic1 animals object");
        assertEquals(40, objectManager.getActiveObjects().stream()
                        .filter(ExplosionObjectInstance.class::isInstance)
                        .map(ExplosionObjectInstance.class::cast)
                        .mapToInt(ExplosionObjectInstance::getSlotIndex)
                        .findFirst()
                        .orElse(-1),
                "S1 explosion should inherit the destroyed badnik slot");
    }

    @Test
    void defaultBadnikDestructionDefersChildrenUntilExplosionExecutes() {
        DestructionConfig config = new DestructionConfig(
                -1,
                (spawn, svc) -> new RecordingChildObject(spawn, "Animal"),
                false,
                (spawn, svc, pts) -> new RecordingChildObject(spawn, "Points-" + pts),
                null,
                false);

        DestructionEffects.destroyBadnik(
                0x0190,
                0x0078,
                new ObjectSpawn(0x0190, 0x0078, 0xA7, 0, 0, false, 0),
                15,
                null,
                services,
                config);

        assertEquals(1, objectManager.getActiveObjects().stream()
                        .filter(ExplosionObjectInstance.class::isInstance)
                        .count(),
                "default S2/S3K destruction should immediately replace the badnik with Obj_Explosion");
        assertEquals(0, objectManager.getActiveObjects().stream()
                        .filter(RecordingChildObject.class::isInstance)
                        .count(),
                "S3K Obj_Explosion routine 0 allocates animal/points; destroyBadnik must not spawn them directly");
        assertEquals(15, objectManager.getActiveObjects().stream()
                        .filter(ExplosionObjectInstance.class::isInstance)
                        .map(ExplosionObjectInstance.class::cast)
                        .mapToInt(ExplosionObjectInstance::getSlotIndex)
                        .findFirst()
                        .orElse(-1),
                "default explosion should inherit the destroyed badnik slot");

        objectManager.update(0, null, List.of(), 1);

        assertEquals(2, objectManager.getActiveObjects().stream()
                        .filter(RecordingChildObject.class::isInstance)
                        .count(),
                "Explosion update should allocate animal and points children from the explosion routine");
    }

    @Test
    void sonic2AnimalAllocatesPointsOnItsOwnFirstExecuteObjectsPass() {
        DestructionConfig config = new DestructionConfig(
                -1,
                (spawn, svc) -> AnimalObjectInstance.deferredArtVariant(spawn, svc,
                        (pointsSpawn, pointsSvc, points) -> new RecordingChildObject(
                                pointsSpawn, "Points-" + points)),
                true,
                null,
                null,
                false);

        DestructionEffects.destroyBadnik(
                0x078E,
                0x0538,
                new ObjectSpawn(0x078E, 0x0538, 0xA7, 0, 0, false, 0),
                22,
                null,
                services,
                config);

        objectManager.update(0x06DD, null, List.of(), 1);

        assertEquals(1, objectManager.getActiveObjects().stream()
                        .filter(AnimalObjectInstance.class::isInstance)
                        .count(),
                "S2 Obj27_InitWithAnimal allocates Obj28 from the explosion routine "
                        + "(docs/s2disasm/s2.asm:46707-46715)");
        assertEquals(0, objectManager.getActiveObjects().stream()
                        .filter(RecordingChildObject.class::isInstance)
                        .count(),
                "When Obj28 receives a lower free slot than Obj27, ExecuteObjects has already passed it");

        objectManager.update(0x06DD, null, List.of(), 2);

        assertEquals(1, objectManager.getActiveObjects().stream()
                        .filter(RecordingChildObject.class::isInstance)
                        .count(),
                "S2 Obj28_InitRandom allocates Obj29 during the animal's own first routine pass "
                        + "(docs/s2disasm/s2.asm:24596-24636)");
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

    private static final class RecordingChildObject extends AbstractObjectInstance {
        private RecordingChildObject(ObjectSpawn spawn, String name) {
            super(spawn, name);
        }

        @Override
        public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {
        }
    }
}
