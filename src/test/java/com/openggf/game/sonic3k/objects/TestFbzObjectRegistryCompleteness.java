package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.LevelData;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.tools.ObjectDiscoveryTool.LevelConfig;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@com.openggf.tests.rules.RequiresRom(com.openggf.tests.rules.SonicGame.SONIC_3K)
class TestFbzObjectRegistryCompleteness {
    private static final Set<Integer> CONCRETE_FBZ_IDS = Set.of(
            0x00, 0x01, 0x02, 0x07, 0x08, 0x0F, 0x26, 0x28, 0x2A,
            0x2F, 0x33, 0x34, 0x3D, 0x6A, 0x6B,
            0x6F, 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
            0x79, 0x7A, 0x7B, 0x7C, 0x7D, 0x7E, 0x7F, 0x8A,
            0xCE, 0xCF, 0xD0, 0xE0, 0xE1,
            0xE2, 0xE3, 0xE4, 0xE5, 0xFF,
            0x80, 0x85, 0xA8, 0xA9, 0xAA, 0xAB);

    @Test
    void fbzProfileIncludesTheCheckedConcreteFactoryInventory() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        LevelConfig fbz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_FLYING_BATTERY_1)
                .findFirst().orElseThrow();

        // The profile describes every factory available in the S3KL zone set,
        // including objects not placed on the FBZ route. The dedicated profile
        // registry guard checks that full set exactly; this inventory checks FBZ.
        assertTrue(profile.getImplementedIds(fbz1).containsAll(CONCRETE_FBZ_IDS),
                "discovery must classify every checked FBZ factory as implemented");
    }

    @Test
    void checkedLayoutsContainNoPlaceholderPlacements() throws IOException {
        Sonic3kObjectRegistry registry = new FbzTestRegistry();
        List<ObjectSpawn> placements = new java.util.ArrayList<>();
        placements.addAll(TestFbzObjectInventory.load("1.bin"));
        placements.addAll(TestFbzObjectInventory.load("2.bin"));

        long placeholders = placements.stream()
                .map(registry::create)
                .filter(PlaceholderObjectInstance.class::isInstance)
                .count();
        assertEquals(0, placeholders);

        for (ObjectSpawn spawn : placements) {
            ObjectInstance instance = registry.create(spawn);
            assertEquals(CONCRETE_FBZ_IDS.contains(spawn.objectId()),
                    !(instance instanceof PlaceholderObjectInstance),
                    "factory classification for S3KL ID $" + Integer.toHexString(spawn.objectId()));
        }
    }

    @Test
    void mhzZoneSetRetainsTheCutsceneRemapsForA8AndA9() {
        Sonic3kObjectRegistry registry = new MhzTestRegistry();
        assertInstanceOf(Mhz1CutsceneKnucklesInstance.class,
                registry.create(new ObjectSpawn(0, 0, 0xA8, 0, 0, false, 0)));
        assertInstanceOf(Mhz1CutsceneButtonInstance.class,
                registry.create(new ObjectSpawn(0, 0, 0xA9, 0, 0, false, 0)));
    }

    @Test void pointerTableFactoriesFollowZoneSetRatherThanExactFbzZone() {
        Sonic3kObjectRegistry aiz = new ZoneTestRegistry(Sonic3kZoneIds.ZONE_AIZ);
        Sonic3kObjectRegistry lbz = new ZoneTestRegistry(Sonic3kZoneIds.ZONE_LBZ);
        Sonic3kObjectRegistry mhz = new ZoneTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        for (Sonic3kObjectRegistry registry : List.of(aiz, lbz)) {
            assertInstanceOf(FbzExitHallInstance.class, registry.create(spawn(0x8A, 0)));
            assertInstanceOf(FbzExitDoorInstance.class, registry.create(spawn(0xCE, 0)));
            assertInstanceOf(FbzEggPrisonInstance.class, registry.create(spawn(0xCF, 0)));
            assertInstanceOf(FbzSpringPlungerInstance.class, registry.create(spawn(0xD0, 0)));
        }
        assertInstanceOf(FbzExitHallInstance.class, mhz.create(spawn(0x8A, 0)),
                "$8A points to Obj_FBZExitHall in both SK sets");
        assertInstanceOf(PlaceholderObjectInstance.class, mhz.create(spawn(0xCE, 0)));
        assertInstanceOf(PlaceholderObjectInstance.class, mhz.create(spawn(0xCF, 0)));
        assertInstanceOf(PlaceholderObjectInstance.class, mhz.create(spawn(0xD0, 0)));
    }

    @Test void discoveryProfileUsesTheSameZoneSetBreadthAsTheRegistry() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        LevelConfig aiz = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_ANGEL_ISLAND_1)
                .findFirst().orElseThrow();
        LevelConfig mhz = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst().orElseThrow();

        assertTrue(profile.getImplementedIds(aiz).containsAll(Set.of(0x8A,0xCE,0xCF,0xD0)));
        assertTrue(profile.getImplementedIds(mhz).contains(0x8A));
        assertFalse(profile.getImplementedIds(mhz).contains(0xCE));
        assertFalse(profile.getImplementedIds(mhz).contains(0xCF));
        assertFalse(profile.getImplementedIds(mhz).contains(0xD0));
    }

    private static ObjectSpawn spawn(int id, int subtype) {
        return new ObjectSpawn(0, 0, id, subtype, 0, false, 0);
    }

    private static final class FbzTestRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_FBZ;
        }
    }

    private static final class MhzTestRegistry extends Sonic3kObjectRegistry {
        @Override protected int currentRomZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
    }

    private static final class ZoneTestRegistry extends Sonic3kObjectRegistry {
        private final int zone;
        private ZoneTestRegistry(int zone) { this.zone = zone; }
        @Override protected int currentRomZoneId() { return zone; }
    }
}
