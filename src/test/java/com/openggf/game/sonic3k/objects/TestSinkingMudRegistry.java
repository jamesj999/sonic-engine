package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.S3kZoneSet;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Id $4F is {@code Obj_SinkingMud} in Sprite_Listing3 (SK Set 1, zones 0-6) but
 * {@code Obj_DEZStaircase} in Sprite_ListingK (SK Set 2, zones 7-13); the table is
 * selected by {@code Current_zone} at {@code sonic3k.asm loc_1B6A8}.
 */
class TestSinkingMudRegistry {

    private static ObjectSpawn spawn() {
        return new ObjectSpawn(0x0340, 0x0180, Sonic3kObjectIds.SINKING_MUD, 0x06, 0, false, 0);
    }

    @Test
    void registryCreatesSinkingMudInS3klZoneSet() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_AIZ);

        assertInstanceOf(SinkingMudObjectInstance.class, registry.create(spawn()));
    }

    @Test
    void registryKeepsId0x4FPlaceholderInSklZoneSet() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_DEZ);

        ObjectInstance instance = registry.create(spawn());

        assertInstanceOf(PlaceholderObjectInstance.class, instance);
        assertEquals("DEZStaircase", registry.getPrimaryName(Sonic3kObjectIds.SINKING_MUD, S3kZoneSet.SKL));
        assertEquals("SinkingMud", registry.getPrimaryName(Sonic3kObjectIds.SINKING_MUD, S3kZoneSet.S3KL));
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
