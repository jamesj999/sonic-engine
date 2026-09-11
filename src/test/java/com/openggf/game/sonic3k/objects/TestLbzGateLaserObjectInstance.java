package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TestLbzGateLaserObjectInstance {

    @Test
    void registryRoutesS3klSlot21ToLbzGateLaserOnly() {
        Sonic3kObjectRegistry lbzRegistry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);
        ObjectInstance gateLaser = lbzRegistry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_GATE_LASER, 0x88, 0, false, 0));

        assertFalse(gateLaser instanceof PlaceholderObjectInstance,
                "S3KL slot $21 is Obj_LBZGateLaser and must not remain a placeholder");
        assertEquals("LBZGateLaser", gateLaser.getName());
        assertInstanceOf(LbzGateLaserObjectInstance.class, gateLaser);

        Sonic3kObjectRegistry aizRegistry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_AIZ);
        ObjectInstance aizObject = aizRegistry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_GATE_LASER, 0, 0, false, 0));

        assertInstanceOf(PlaceholderObjectInstance.class, aizObject,
                "S3KL slot $21 is Obj_LBZGateLaser only in LBZ; AIZ must keep its zone-table object placeholder");
        assertEquals("LBZGateLaser", aizObject.getName());

        Sonic3kObjectRegistry lrzRegistry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LRZ);
        ObjectInstance lrzObject = lrzRegistry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_GATE_LASER, 0, 0, false, 0));

        assertInstanceOf(PlaceholderObjectInstance.class, lrzObject,
                "SKL slot $21 is Obj_LRZSmashingSpikePlatform, not the LBZ gate laser");
        assertEquals("LRZSmashingSpikePlatform", lrzObject.getName());
    }

    @Test
    void objectProfileMarksGateLaserImplementedForS3klOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        var lbz2 = profile.getLevels().stream()
                .filter(level -> level.levelData() == com.openggf.level.LevelData.S3K_LAUNCH_BASE_2)
                .findFirst().orElseThrow();
        var aiz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == com.openggf.level.LevelData.S3K_ANGEL_ISLAND_1)
                .findFirst().orElseThrow();
        var mhz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == com.openggf.level.LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst().orElseThrow();

        assertTrue(profile.getImplementedIds(lbz2).contains(Sonic3kObjectIds.LBZ_GATE_LASER));
        assertFalse(profile.getImplementedIds(aiz1).contains(Sonic3kObjectIds.LBZ_GATE_LASER),
                "Object ID $21 is an LBZ implementation, not every S3KL zone");
        assertFalse(profile.getImplementedIds(mhz1).contains(Sonic3kObjectIds.LBZ_GATE_LASER),
                "Object ID $21 belongs to a different SKL object and must stay out of the SKL implemented set");
    }

    @Test
    void firstParentUpdateSpawnsTwoFallingHalvesAndPlaysEnergyZap() {
        RecordingServices services = new RecordingServices();
        LbzGateLaserObjectInstance laser = createLaser(services, 0x88);

        laser.update(0, null);

        assertEquals(0x48, laser.spawnTimerForTesting(),
                "subtype high nibble $8 gives (($88 >> 1) & $78) + 8 = $48 frames");
        assertEquals(List.of(Sonic3kSfx.ENERGY_ZAP.id), services.playedSfx);
        assertEquals(2, services.children.size());

        LbzGateLaserBeamInstance left = assertInstanceOf(LbzGateLaserBeamInstance.class, services.children.get(0));
        LbzGateLaserBeamInstance right = assertInstanceOf(LbzGateLaserBeamInstance.class, services.children.get(1));

        assertEquals(0x0200, left.getX());
        assertEquals(0x0100, left.getY());
        assertEquals(0x0140, left.targetYForTesting(),
                "subtype low nibble $8 gives target y_pos = spawn + ($8 << 3)");
        assertEquals(1, left.mappingFrameForTesting());
        assertEquals(0, left.getCollisionFlags());
        assertFalse(left.publishesTouchResponseListEntryThisFrame(),
                "The safe first half stays on loc_2941C and must not publish to Collision_response_list");
        assertEquals(3, left.getPriorityBucket());

        assertEquals(2, right.mappingFrameForTesting());
        assertEquals(0x98, right.getCollisionFlags());
        assertTrue(right.publishesTouchResponseListEntryThisFrame(),
                "Only the second hurtful half reaches loc_29416/Add_SpriteToCollisionResponseList");
        assertEquals(1, right.getPriorityBucket(),
                "The second allocated half overrides priority to $80");
        assertEquals(TouchCategoryDecodeMode.NORMAL,
                assertInstanceOf(TouchResponseProvider.class, right).getTouchResponseProfile().categoryDecodeMode());
    }

    @Test
    void spawnedBeamFallsFourPixelsPerFrameFlickersAndWarpsAwayAfterTargetOldY() {
        RecordingServices services = new RecordingServices();
        LbzGateLaserObjectInstance laser = createLaser(services, 0x02);
        laser.update(0, null);
        LbzGateLaserBeamInstance beam = assertInstanceOf(LbzGateLaserBeamInstance.class, services.children.get(1));

        assertEquals(0x0100, beam.getY());
        assertEquals(0x0110, beam.targetYForTesting());
        assertEquals(4, beam.renderFlagsForTesting());

        beam.update(0, null);
        assertEquals(0x0104, beam.getY());
        assertEquals(4, beam.renderFlagsForTesting(),
                "ROM toggles render_flags bit 1 only when (Level_frame_counter+1)&1 == 0");

        beam.update(1, null);
        assertEquals(0x0108, beam.getY());
        assertEquals(6, beam.renderFlagsForTesting());

        beam.update(2, null);
        beam.update(3, null);
        assertEquals(0x0110, beam.getY());
        assertEquals(0x0200, beam.getX(),
                "cmp.w compares the old y_pos, so reaching target y does not warp until the next update");

        beam.update(4, null);
        assertEquals(0x7FF0, beam.getX());
        assertEquals(0, beam.getCollisionFlags());
    }

    private static LbzGateLaserObjectInstance createLaser(RecordingServices services, int subtype) {
        LbzGateLaserObjectInstance laser = new LbzGateLaserObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.LBZ_GATE_LASER, subtype, 0, false, 0));
        laser.setServices(services);
        return laser;
    }

    private static final class RecordingServices extends StubObjectServices {
        private final ObjectManager objectManager;
        private final List<AbstractObjectInstance> children = new ArrayList<>();
        private final List<Integer> playedSfx = new ArrayList<>();

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                children.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any(AbstractObjectInstance.class));
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
