package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestS2MczRotPformsLifecycle {
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, Sonic2ZoneConstants.ZONE_MCZ, 0);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    @Test
    void subtype18SpawnsChildrenAfterObjectManagerServicesAreAvailable() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "MCZ ObjectManager should be available");

        ObjectSpawn parentSpawn = objectManager.getAllSpawns().stream()
                .filter(spawn -> spawn.objectId() == Sonic2ObjectIds.MCZ_ROT_PFORMS)
                .filter(spawn -> spawn.subtype() == 0x18)
                .findFirst()
                .orElseThrow(() -> new AssertionError("MCZ should contain subtype 0x18 rotating platform parent"));

        GameServices.camera().setX((short) Math.max(0, parentSpawn.x() - 160));
        GameServices.camera().setY((short) Math.max(0, parentSpawn.y() - 96));
        objectManager.reset(GameServices.camera().getX());

        fixture.stepIdleFrames(1);

        Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
        boolean xFlip = (parentSpawn.renderFlags() & 0x01) != 0;
        int rightChildSubtype = xFlip ? 0x0C : 0x06;
        int leftChildSubtype = xFlip ? 0x06 : 0x0C;
        assertTrue(hasLiveRotPformAt(activeObjects, parentSpawn.x() + 0x40, parentSpawn.y() + 0x40, rightChildSubtype),
                () -> "Subtype 0x18 should spawn child 1 at +64,+64 with the ROM subtype selected by x-flip. "
                        + describeRotPforms(activeObjects));
        assertTrue(hasLiveRotPformAt(activeObjects, parentSpawn.x() - 0x40, parentSpawn.y() + 0x40, leftChildSubtype),
                () -> "Subtype 0x18 should spawn child 2 at -64,+64 with the ROM subtype selected by x-flip. "
                        + describeRotPforms(activeObjects));
    }

    private static boolean hasLiveRotPformAt(Collection<ObjectInstance> objects, int x, int y, int subtype) {
        return objects.stream()
                .filter(object -> !object.isDestroyed())
                .map(ObjectInstance::getSpawn)
                .anyMatch(spawn -> spawn.objectId() == Sonic2ObjectIds.MCZ_ROT_PFORMS
                        && spawn.x() == x
                        && spawn.y() == y
                        && spawn.subtype() == subtype);
    }

    private static String describeRotPforms(Collection<ObjectInstance> objects) {
        return objects.stream()
                .filter(object -> object.getSpawn().objectId() == Sonic2ObjectIds.MCZ_ROT_PFORMS)
                .map(object -> {
                    ObjectSpawn spawn = object.getSpawn();
                    return String.format("Obj6A{x=%d,y=%d,sub=0x%02X,destroyed=%s}",
                            spawn.x(), spawn.y(), spawn.subtype(), object.isDestroyed());
                })
                .toList()
                .toString();
    }
}
