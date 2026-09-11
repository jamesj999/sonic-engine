package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMGZSwingingPlatformObjectInstance {

    private Camera camera;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        camera = GameServices.camera();
        camera.resetState();
        camera.setX((short) 0x0100);
        camera.setY((short) 0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void registryCreatesMgzSwingingPlatformInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x1200, 0x0600, Sonic3kObjectIds.MGZ_SWINGING_PLATFORM, 0x00, 0x00, false, 0));

        assertInstanceOf(MGZSwingingPlatformObjectInstance.class, instance);
    }

    @Test
    void riderCosineResidueUsesTheNativeSstSpecificAngleSequence() {
        assertTrue(MGZSwingingPlatformObjectInstance.hasLaterSlotRiderCosineResidue(0x62, 6));
        assertFalse(MGZSwingingPlatformObjectInstance.hasLaterSlotRiderCosineResidue(0x62, 7),
                "Slot 7 inherits an ordinary d1 high word at angle $62");
        assertFalse(MGZSwingingPlatformObjectInstance.hasLaterSlotRiderCosineResidue(0x6D, 6));
        assertTrue(MGZSwingingPlatformObjectInstance.hasLaterSlotRiderCosineResidue(0x6D, 7),
                "Slot 7 inherits the rounding residue at angle $6D");
        assertFalse(MGZSwingingPlatformObjectInstance.hasLaterSlotRiderCosineResidue(0x6D, 8));
    }

    @Test
    void outOfRangeCheckUsesPivotXInsteadOfSwingingEndpoint() {
        ObjectSpawn spawn = new ObjectSpawn(
                0x037F, 0x0600, Sonic3kObjectIds.MGZ_SWINGING_PLATFORM, 0x00, 0x00, false, 0);
        MGZSwingingPlatformObjectInstance instance = new MGZSwingingPlatformObjectInstance(spawn);

        assertEquals(0x03CF, instance.getX(), "Sanity check: angle 0 places endpoint 80 px right of pivot");
        assertEquals(0x037F, instance.getOutOfRangeReferenceX(),
                "ROM Obj_MGZSwingingPlatform passes saved pivot $30(a0) to Sprite_OnScreen_Test2");
    }

    @Test
    void objectManagerKeepsPlatformAliveWhenEndpointLeavesRangeButPivotDoesNot() {
        ObjectSpawn spawn = new ObjectSpawn(
                0x037F, 0x0600, Sonic3kObjectIds.MGZ_SWINGING_PLATFORM, 0x00, 0x00, false, 0);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager objectManager = new ObjectManager(List.of(spawn), registry, 0, null, null,
                null, camera, services());
        objectManager.reset(0x0100);

        assertNotNull(registry.instance, "Sanity check: right-edge platform should load into the initial window");
        objectManager.update(0x0100, null, List.of(), 1, false);

        assertTrue(objectManager.getActiveObjects().contains(registry.instance),
                "ROM Sprite_OnScreen_Test2 uses the pivot/origin X, so a swinging endpoint crossing the "
                        + "offscreen bucket must not unload the whole platform");
    }

    @Test
    void reservesRomVisualChildSlotAfterParentExecutes() {
        ObjectSpawn spawn = new ObjectSpawn(
                0x037F, 0x0600, Sonic3kObjectIds.MGZ_SWINGING_PLATFORM, 0x00, 0x00, false, 0);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager[] managerRef = new ObjectManager[1];
        ObjectServices objectServices = new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ObjectManager objectManager() {
                return managerRef[0];
            }
        };
        ObjectManager objectManager = new ObjectManager(List.of(spawn), registry, 0, null, null,
                null, camera, objectServices);
        managerRef[0] = objectManager;
        objectManager.reset(0x0100);

        assertNotNull(registry.instance, "Sanity check: platform should load into the initial window");
        assertEquals(4, registry.instance.getSlotIndex(),
                "S3K AllocateObject pre-increments from Dynamic_object_RAM, so normal dynamic SST allocation starts at slot 4");

        objectManager.update(0x0100, null, List.of(), 1, false);

        assertEquals(6, objectManager.allocateSlotAfter(registry.instance.getSlotIndex()),
                "ROM Obj_MGZSwingingPlatform allocates one loc_3406E child after the parent "
                        + "(docs/skdisasm/sonic3k.asm:70468-70499), so the next free slot after "
                        + "the parent must skip that reserved child SST entry");
    }

    private ObjectServices services() {
        return new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
    }

    private static final class TrackingRegistry implements ObjectRegistry {
        private MGZSwingingPlatformObjectInstance instance;

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            instance = new MGZSwingingPlatformObjectInstance(spawn);
            return instance;
        }

        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_3K;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "MGZSwingingPlatform";
        }
    }
}
