package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.LevelData;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tools.ObjectDiscoveryTool.LevelConfig;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCorkeyBadnikInstance {

    @Test
    void registryCreatesCorkeyAndProfileMarksS3klSlotOnly() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.CORKEY, 0, 0, false, 0));

        assertInstanceOf(CorkeyBadnikInstance.class, instance);

        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        LevelConfig lbz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_LAUNCH_BASE_1)
                .findFirst()
                .orElseThrow();
        LevelConfig mhz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst()
                .orElseThrow();

        assertTrue(profile.getImplementedIds(lbz1).contains(Sonic3kObjectIds.CORKEY));
        assertFalse(profile.getImplementedIds(mhz1).contains(Sonic3kObjectIds.CORKEY));
    }

    @Test
    void exposesRomCollisionFlagsAndPriority() {
        CorkeyBadnikInstance corkey = create(0);

        assertEquals(0x0B, corkey.getCollisionFlags());
        assertEquals(5, corkey.getPriorityBucket());
    }

    @Test
    void renderedPlaceholderRestoresEntryBeforeInitializationAndPatrol() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        CorkeyBadnikInstance corkey = create(0);
        ObjectServices services = servicesWithObjectManager();
        ObjectManager objectManager = services.objectManager();
        corkey.setServices(services);

        corkey.update(0, null);
        verify(objectManager, times(0)).addDynamicObjectAfterCurrent(org.mockito.ArgumentMatchers.any());

        corkey.refreshPostCameraRenderState();
        corkey.update(1, null);
        verify(objectManager, times(0)).addDynamicObjectAfterCurrent(org.mockito.ArgumentMatchers.any());

        corkey.update(2, null);

        assertEquals(-1, corkey.movementStepForTesting(),
                "loc_8C746 stores -1 in $40 when render_flags bit 0 is clear");
        assertEquals(0x30, corkey.movementTimerForTesting(),
                "fallback test RNG follows loc_8C780's nonzero high-nibble timer rule");
        ArgumentCaptor<ObjectInstance> childCaptor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager).addDynamicObjectAfterCurrent(childCaptor.capture());
        ObjectInstance child = childCaptor.getValue();
        assertInstanceOf(CorkeyBadnikInstance.CorkeyNozzleChild.class, child);
        assertEquals(0x0200, child.getX());
        assertEquals(0x010C, child.getY());

        corkey.update(3, null);

        assertEquals(0x01FF, corkey.getX());
    }

    @Test
    void flippedSpawnPatrolsRightThenSubtypeZeroImmediatelyReverses() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        CorkeyBadnikInstance corkey = create(1);
        corkey.setServices(servicesWithObjectManager());

        activate(corkey);
        corkey.update(3, null);

        assertEquals(-1, corkey.movementStepForTesting(),
                "subtype 0 leaves Obj_Wait's word countdown at zero, so it reverses after every patrol move");
        assertEquals(0x0201, corkey.getX());
    }

    @Test
    void subtypeControlsPatrolTurnaround() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        CorkeyBadnikInstance corkey = createWithSubtype(2, 0);
        corkey.setServices(servicesWithObjectManager());

        activate(corkey);
        corkey.update(3, null);
        corkey.update(4, null);
        corkey.update(5, null);

        assertEquals(1, corkey.movementStepForTesting(),
                "Obj_Wait calls loc_8C7BC after the subtype countdown expires, reversing $40");
        assertEquals(0x01FD, corkey.getX(),
                "The ROM reverses after moving on the expiry frame, not before");
    }

    @Test
    void nozzleFiringCycleSpawnsThreeHurtShots() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        CorkeyBadnikInstance corkey = create(0);
        ObjectServices services = servicesWithObjectManager();
        ObjectManager objectManager = services.objectManager();
        corkey.setServices(services);
        activate(corkey);

        ArgumentCaptor<ObjectInstance> childCaptor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager).addDynamicObjectAfterCurrent(childCaptor.capture());
        CorkeyBadnikInstance.CorkeyNozzleChild nozzle =
                (CorkeyBadnikInstance.CorkeyNozzleChild) childCaptor.getValue();
        nozzle.setServices(services);

        for (int frame = 1; frame <= 0x31; frame++) {
            corkey.update(frame, null);
        }
        assertTrue(corkey.firingLatchForTesting());

        for (int frame = 0; frame < 128; frame++) {
            nozzle.update(frame, null);
        }

        ArgumentCaptor<ObjectInstance> allChildren = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(4)).addDynamicObjectAfterCurrent(allChildren.capture());
        List<CorkeyBadnikInstance.CorkeyShotChild> shots = allChildren.getAllValues().stream()
                .filter(CorkeyBadnikInstance.CorkeyShotChild.class::isInstance)
                .map(CorkeyBadnikInstance.CorkeyShotChild.class::cast)
                .toList();

        assertEquals(3, shots.size(), "loc_8C850/864/878 spawn shots on raw loops 4, 5, and 6");
        assertTrue(shots.stream().allMatch(shot -> shot.getCollisionFlags() == 0xA0));
        assertTrue(shots.stream().allMatch(shot -> shot.getPriorityBucket() == 5));
    }

    @Test
    void shotPlaysLaserSfxOnFirstUpdate() {
        CorkeyBadnikInstance.CorkeyShotChild shot = new CorkeyBadnikInstance.CorkeyShotChild(
                new ObjectSpawn(0x0200, 0x0160, Sonic3kObjectIds.CORKEY, 0, 0, false, 0),
                0x0200, 0x0160, new int[]{6, 0, 6, 0, -0x0C});
        ObjectServices services = servicesWithObjectManager();
        shot.setServices(services);

        shot.update(0, null);

        verify(services).playSfx(Sonic3kSfx.LASER.id);
    }

    private static CorkeyBadnikInstance create(int renderFlags) {
        return createWithSubtype(0, renderFlags);
    }

    private static CorkeyBadnikInstance createWithSubtype(int subtype, int renderFlags) {
        return new CorkeyBadnikInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.CORKEY, subtype, renderFlags, false, 0));
    }

    private static void activate(CorkeyBadnikInstance corkey) {
        corkey.update(0, null);
        corkey.refreshPostCameraRenderState();
        corkey.update(1, null);
        corkey.update(2, null);
    }

    private static ObjectServices servicesWithObjectManager() {
        ObjectServices services = mock(ObjectServices.class);
        when(services.objectManager()).thenReturn(mock(ObjectManager.class));
        return services;
    }
}
