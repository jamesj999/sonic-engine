package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestHCZBlockObjectInstance {

    @Test
    public void registryCreatesHczBlockForId0x40InS3klZoneSet() {
        Sonic3kObjectRegistry registry = new HczRegistry();
        ObjectSpawn spawn = new ObjectSpawn(0x0B80, 0x0580, Sonic3kObjectIds.HCZ_BLOCK, 0, 0, false, 0);

        ObjectInstance instance = registry.create(spawn);

        assertTrue(instance instanceof HCZBlockObjectInstance);
    }

    @Test
    public void primaryNameFor0x40MatchesDisassemblyLabel() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        assertEquals("HCZBlock", registry.getPrimaryName(Sonic3kObjectIds.HCZ_BLOCK));
    }

    @Test
    public void subtypeControlsRomSizedSolidExtents() {
        int[][] expected = {
                {0x10, 0x10},
                {0x20, 0x10},
                {0x30, 0x10},
                {0x40, 0x10}
        };

        for (int subtype = 0; subtype < expected.length; subtype++) {
            HCZBlockObjectInstance instance = new HCZBlockObjectInstance(
                    new ObjectSpawn(0x100, 0x200, Sonic3kObjectIds.HCZ_BLOCK, subtype, 0, false, 0));

            SolidObjectParams params = instance.getSolidParams();
            assertEquals(expected[subtype][0] + 0x0B, params.halfWidth());
            assertEquals(expected[subtype][1], params.airHalfHeight());
            assertEquals(expected[subtype][1] + 1, params.groundHalfHeight());
            assertEquals(expected[subtype][0],
                    instance.getTopLandingHalfWidth(null, params.halfWidth()));
            assertEquals(5, instance.getPriorityBucket());
        }
    }

    @Test
    public void fullSolidRoutinePublishesRomPointerAndConsumesAirborneStandingBit() {
        HCZBlockObjectInstance instance = new HCZBlockObjectInstance(
                new ObjectSpawn(0x100, 0x200, Sonic3kObjectIds.HCZ_BLOCK, 0, 0, false, 0));

        assertEquals(0x0001, instance.romObjectCodePointerHighWord());
        assertTrue(instance.airborneStaleStandingBitReturnsNoContact(null));
    }

    @Test
    public void hczPlanIncludesLevelArtEntryForBlock() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x01, 0);

        Sonic3kPlcArtRegistry.LevelArtEntry block = plan.levelArt().stream()
                .filter(e -> Sonic3kObjectArtKeys.HCZ_BLOCK.equals(e.key()))
                .findFirst()
                .orElse(null);

        assertNotNull(block);
        assertEquals(Sonic3kConstants.MAP_HCZ_BLOCK_ADDR, block.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_HCZ_WATER_RUSH_BLOCK, block.artTileBase());
        assertEquals(2, block.palette());
    }

    private static final class HczRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_HCZ;
        }
    }
}

