package com.openggf.game.sonic1;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestSonic1RingPlacement {

    @Test
    void ringSpawnMappingUsesObjectSpawnValueSemantics() {
        ObjectSpawn original = new ObjectSpawn(
                0x0200, 0x0300, 0x25, 0x02, 0, false, 0x0300, 17);
        ObjectSpawn equivalent = new ObjectSpawn(
                0x0200, 0x0300, 0x25, 0x02, 0, false, 0x0300, 17);

        Sonic1RingPlacement.Result result = new Sonic1RingPlacement()
                .extract(List.of(original));

        List<RingSpawn> expanded = result.ringSpawnMapping().get(equivalent);

        assertNotNull(expanded);
        assertEquals(List.of(
                new RingSpawn(0x0200, 0x0300),
                new RingSpawn(0x0210, 0x0300),
                new RingSpawn(0x0220, 0x0300)), expanded);
    }
}
