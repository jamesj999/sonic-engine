package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TestS2WfzBossLaserWall {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0400, Sonic2ObjectIds.WFZ_BOSS, 0, 0, false, 10);

    @Test
    void activeWallAlternatesVisibilityButRemainsSolid() {
        Sonic2WFZBossInstance.WFZLaserWall wall = newWall();

        wall.update(1, null);
        boolean first = wall.isVisibleThisFrameForTest();
        wall.update(2, null);
        boolean second = wall.isVisibleThisFrameForTest();
        wall.update(3, null);
        boolean third = wall.isVisibleThisFrameForTest();

        assertNotEquals(first, second);
        assertEquals(first, third);
        assertEquals(new SolidObjectParams(0x13, 0x40, 0x80), wall.getSolidParams());
    }

    private static Sonic2WFZBossInstance.WFZLaserWall newWall() {
        StubObjectServices services = new StubObjectServices();
        Sonic2WFZBossInstance boss = ObjectConstructionContext.construct(
                services,
                () -> new Sonic2WFZBossInstance(BOSS_SPAWN));
        boss.setServices(services);
        Sonic2WFZBossInstance.WFZLaserWall wall = ObjectConstructionContext.construct(
                services,
                () -> new Sonic2WFZBossInstance.WFZLaserWall(boss, 0x0078, 0x0460));
        wall.setServices(services);
        return wall;
    }
}
