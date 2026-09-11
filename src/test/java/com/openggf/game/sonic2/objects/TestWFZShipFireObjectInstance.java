package com.openggf.game.sonic2.objects;

import com.openggf.game.LevelEventProvider;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.ParallaxManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestWFZShipFireObjectInstance {

    @Test
    void shipFireTracksWfzEventBgXOffsetInsteadOfGenericParallaxOffset() {
        Sonic2LevelEventManager events = wfzEventsWithBgXOffset(0x0120);
        ParallaxManager parallax = mock(ParallaxManager.class);
        when(parallax.getCameraBgXOffset()).thenReturn(0x0020);

        WFZShipFireObjectInstance fire = shipFire(events, parallax);
        fire.update(0, player());
        assertEquals(0x1000, fire.getX(),
                "ObjBC_Init only saves objoff_2C and returns; BG offset is applied from ObjBC_Main next frame");

        fire.update(1, player());

        assertEquals(0x1120, fire.getX(),
                "ObjBC reads Camera_BG_X_offset from WFZ event state, not the generic parallax fallback");
        assertFalse(fire.isDestroyed());
    }

    @Test
    void shipFireDeletesWhenWfzEventBgXOffsetReachesRomThreshold() {
        Sonic2LevelEventManager events = wfzEventsWithBgXOffset(0x0380);
        ParallaxManager parallax = mock(ParallaxManager.class);
        when(parallax.getCameraBgXOffset()).thenReturn(0);

        WFZShipFireObjectInstance fire = shipFire(events, parallax);
        fire.update(0, player());
        assertFalse(fire.isDestroyed(),
                "ObjBC_Init returns before the BG offset delete check runs");

        fire.update(1, player());

        assertTrue(fire.isDestroyed(),
                "ObjBC deletes at Camera_BG_X_offset >= $380 using the WFZ event-owned offset");
    }

    @Test
    void shipFireBypassesGenericCullingUntilItsRomDeleteThreshold() {
        Sonic2LevelEventManager events = wfzEventsWithBgXOffset(0x037F);
        ParallaxManager parallax = mock(ParallaxManager.class);
        WFZShipFireObjectInstance fire = shipFire(events, parallax);

        assertTrue(fire.isPersistent(),
                "ObjBC never calls MarkObjGone; its Camera_BG_X_offset check owns deletion");

        fire.update(0, player());
        fire.update(1, player());

        assertFalse(fire.isDestroyed());
    }

    private static WFZShipFireObjectInstance shipFire(Sonic2LevelEventManager events, ParallaxManager parallax) {
        WFZShipFireObjectInstance fire = new WFZShipFireObjectInstance(
                new ObjectSpawn(0x1000, 0x0500, Sonic2ObjectIds.WFZ_SHIP_FIRE, 0x7C, 0, false, 0));
        fire.setServices(new WfzEventServices(events).withParallaxManager(parallax));
        return fire;
    }

    private static Sonic2LevelEventManager wfzEventsWithBgXOffset(int offset) {
        Sonic2LevelEventManager events = new Sonic2LevelEventManager();
        events.initLevel(Sonic2LevelEventManager.ZONE_WFZ, 0);
        events.getWfzEvents().setBgXOffsetForTest(offset);
        return events;
    }

    private static TestablePlayableSprite player() {
        return new TestablePlayableSprite("sonic", (short) 0x1000, (short) 0x0500);
    }

    private static final class WfzEventServices extends TestObjectServices {
        private final LevelEventProvider levelEventProvider;

        private WfzEventServices(LevelEventProvider levelEventProvider) {
            this.levelEventProvider = levelEventProvider;
        }

        @Override
        public LevelEventProvider levelEventProvider() {
            return levelEventProvider;
        }
    }
}
