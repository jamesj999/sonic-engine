package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss2Instance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestLbz2EndSequenceRegistry {

    @Test
    void registryRoutesLbz2EndSequenceObjectsInS3klLbzContext() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        assertInstanceOf(Lbz2RobotnikShipInstance.class,
                createWithServices(registry, spawn(Sonic3kObjectIds.LBZ2_ROBOTNIK_SHIP)));
        assertInstanceOf(LbzKnuxPillarInstance.class,
                createWithServices(registry, spawn(Sonic3kObjectIds.LBZ_KNUX_PILLAR)));
        assertInstanceOf(LbzFinalBoss1Instance.class,
                createWithServices(registry, spawn(Sonic3kObjectIds.LBZ_FINAL_BOSS_1)));
        assertInstanceOf(LbzEndBossInstance.class,
                createWithServices(registry, spawn(Sonic3kObjectIds.LBZ_END_BOSS)));
        assertInstanceOf(LbzFinalBoss2Instance.class,
                createWithServices(registry, spawn(Sonic3kObjectIds.LBZ_FINAL_BOSS_2)));
    }

    @Test
    void registryKeepsLbz2EndSequenceSlotsPlaceholderOutsideS3klLbzContext() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        assertEndSequencePlaceholders(registry);
    }

    @Test
    void registryKeepsLbz2EndSequenceSlotsPlaceholderInS3klNonLbzContext() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_AIZ);

        assertEndSequencePlaceholders(registry);
    }

    private static void assertEndSequencePlaceholders(Sonic3kObjectRegistry registry) {
        assertPlaceholder(registry.create(spawn(Sonic3kObjectIds.LBZ2_ROBOTNIK_SHIP)));
        assertPlaceholder(registry.create(spawn(Sonic3kObjectIds.LBZ_KNUX_PILLAR)));
        assertPlaceholder(registry.create(spawn(Sonic3kObjectIds.LBZ_FINAL_BOSS_1)));
        assertPlaceholder(registry.create(spawn(Sonic3kObjectIds.LBZ_END_BOSS)));
        assertPlaceholder(registry.create(spawn(Sonic3kObjectIds.LBZ_FINAL_BOSS_2)));
    }

    @Test
    void cutsceneKnucklesSubtype18RoutesToLbz2Cameo() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        ObjectInstance knuckles = registry.create(new ObjectSpawn(
                0x3E28, 0x0608, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x18, 0, false, 0));

        assertInstanceOf(CutsceneKnucklesLbz2Instance.class, knuckles);
    }

    @Test
    void lbz2EndSequenceObjectIdsMatchS3klPointerTableSlots() {
        assertEquals(0xC6, Sonic3kObjectIds.LBZ2_ROBOTNIK_SHIP);
        assertEquals(0xC8, Sonic3kObjectIds.LBZ_KNUX_PILLAR);
        assertEquals(0xCA, Sonic3kObjectIds.LBZ_FINAL_BOSS_1);
        assertEquals(0xCB, Sonic3kObjectIds.LBZ_END_BOSS);
        assertEquals(0xCC, Sonic3kObjectIds.LBZ_FINAL_BOSS_2);
    }

    private static ObjectSpawn spawn(int objectId) {
        return new ObjectSpawn(0x3B00, 0x05F8, objectId, 0, 0, false, 0);
    }

    private static ObjectInstance createWithServices(Sonic3kObjectRegistry registry, ObjectSpawn spawn) {
        return ObjectConstructionContext.construct(new TestObjectServices(), () -> registry.create(spawn));
    }

    private static void assertPlaceholder(ObjectInstance instance) {
        assertInstanceOf(PlaceholderObjectInstance.class, instance);
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
