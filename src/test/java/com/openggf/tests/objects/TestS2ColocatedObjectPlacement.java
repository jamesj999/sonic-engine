package com.openggf.tests.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.objects.MCZBrickObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SingletonResetExtension.class)
class TestS2ColocatedObjectPlacement {

    private static final int MCZ_OBJ75_X = 0x1740;
    private static final int MCZ_OBJ75_Y = 0x0690;
    private static final int MCZ_OBJ75_ID = 0x75;

    private ObjectManager objectManager;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        GameServices.camera().setX((short) 0x16F6);
        GameServices.camera().setY((short) 0x042C);

        ObjectServices objectServices = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public com.openggf.camera.Camera camera() {
                return GameServices.camera();
            }
        };

        ObjectSpawn spikeBall = new ObjectSpawn(
                MCZ_OBJ75_X, MCZ_OBJ75_Y, MCZ_OBJ75_ID, 0x17, 0x02, false, 0x4690, 0);
        ObjectSpawn brick = new ObjectSpawn(
                MCZ_OBJ75_X, MCZ_OBJ75_Y, MCZ_OBJ75_ID, 0x0F, 0x00, false, 0x0690, 1);
        objectManager = new ObjectManager(List.of(spikeBall, brick), new RecordingS2Registry(),
                0, null, null, null, GameServices.camera(), objectServices);
        objectManager.enableExecThenLoadPlacement();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void s2LoadsColocatedObj75SpikeBallAndBrickRecords() {
        objectManager.update(GameServices.camera().getX(), null, List.of(), 1, false);

        List<Integer> loadedSubtypes = objectManager.getActiveObjects().stream()
                .filter(RecordingObject.class::isInstance)
                .map(RecordingObject.class::cast)
                .map(RecordingObject::originalSubtype)
                .sorted()
                .collect(Collectors.toList());

        assertEquals(List.of(0x0F, 0x17), loadedSubtypes,
                "MCZ2 stores Obj75 subtype $17 and $0F at the same x/y; both records must load");
    }

    @Test
    void mczObj75SpikeBallAllocatesDisplayChildSlotOnFirstExecution() {
        ObjectSpawn spikeBall = new ObjectSpawn(
                MCZ_OBJ75_X, MCZ_OBJ75_Y, MCZ_OBJ75_ID, 0x17, 0x02, false, 0x4690, 0);
        objectManager = new ObjectManager(List.of(spikeBall), new Sonic2ObjectRegistry(),
                0, null, null, null, GameServices.camera(), objectManagerServices());
        objectManager.enableExecThenLoadPlacement();

        objectManager.update(GameServices.camera().getX(), null, List.of(), 1, false);
        objectManager.update(GameServices.camera().getX(), null, List.of(), 2, false);

        List<AbstractObjectInstance> obj75Objects = objectManager.getActiveObjects().stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(instance -> instance.getSpawn() != null
                        && instance.getSpawn().objectId() == MCZ_OBJ75_ID)
                .collect(Collectors.toList());

        assertEquals(2, obj75Objects.size(),
                "ROM Obj75_Init allocates a display-only multi-sprite child for spike-ball subtypes");
        assertEquals(List.of(16, 17), obj75Objects.stream()
                        .map(AbstractObjectInstance::getSlotIndex)
                        .sorted()
                        .collect(Collectors.toList()),
                "Obj75 display child must consume the next SST slot after the parent");
        assertEquals(1, obj75Objects.stream()
                        .filter(MCZBrickObjectInstance.class::isInstance)
                        .count(),
                "only the parent should own Obj75 collision/update behavior");
    }

    private static final class RecordingS2Registry implements ObjectRegistry {
        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_2;
        }

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return new RecordingObject(spawn);
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "recording";
        }
    }

    private ObjectServices objectManagerServices() {
        return new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public com.openggf.camera.Camera camera() {
                return GameServices.camera();
            }
        };
    }

    private static final class RecordingObject extends AbstractObjectInstance {
        private final int originalSubtype;

        private RecordingObject(ObjectSpawn spawn) {
            super(spawn, "recording");
            this.originalSubtype = spawn.subtype();
        }

        private int originalSubtype() {
            return originalSubtype;
        }

        @Override
        public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {
        }
    }
}
