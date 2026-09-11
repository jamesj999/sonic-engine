package com.openggf.tests;

import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TestS3kIczSnowPileObject {
    private static final int ICZ_SNOW_PILE_ID = 0xB9;

    // Clear any gameplay session leaked by a prior test in this fork so the registry
    // resolves the S3KL zone set (not a leaked SKL zone). Parallel-suite flake fix.
    @BeforeEach
    void clearLeakedGameplaySession() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void registryCreatesSnowPileAndProfileMarksS3klSlotImplemented() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(spawn(0x1000, 0x1000, 0));

        assertNotEquals("PlaceholderObjectInstance", instance.getClass().getSimpleName());
        assertTrue(new Sonic3kObjectProfile().getImplementedIds().contains(ICZ_SNOW_PILE_ID));
    }

    @Test
    void subtypeZeroSlowsGroundedMainPlayerBelowBreakSpeed() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(spawn(0x1000, 0x1000, 0));
        TestablePlayableSprite player = playerAt(0x1000, 0x1000);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);

        instance.update(1, player);

        assertEquals(0x0200, player.getGSpeed() & 0xFFFF);
    }

    @Test
    void subtypeZeroBreaksIntoSixPiecesWhenGroundedPlayerIsFastEnough() {
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectInstance instance = new Sonic3kObjectRegistry().create(spawn(0x1000, 0x1000, 0));
        setServices(instance, new ObjectManagerServices(objectManager));
        TestablePlayableSprite player = playerAt(0x1000, 0x1000);
        player.setXSpeed((short) 0x0600);

        instance.update(1, player);

        assertTrue(((AbstractObjectInstance) instance).isDestroyed());
        verify(objectManager, times(6)).addDynamicObjectAfterCurrent(any(AbstractObjectInstance.class));
    }

    @Test
    void subtypeEightLaunchesMainPlayerRightAndSpawnsTwoPieces() {
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectInstance instance = new Sonic3kObjectRegistry().create(spawn(0x1000, 0x1000, 0x08));
        setServices(instance, new ObjectManagerServices(objectManager));
        TestablePlayableSprite player = playerAt(0x1000, 0x1000);

        instance.update(1, player);

        assertEquals(0x0800, player.getXSpeed() & 0xFFFF);
        assertEquals(0x0800, player.getGSpeed() & 0xFFFF);
        assertTrue(((AbstractObjectInstance) instance).isDestroyed());

        ArgumentCaptor<AbstractObjectInstance> childCaptor = ArgumentCaptor.forClass(AbstractObjectInstance.class);
        verify(objectManager, times(2)).addDynamicObjectAfterCurrent(childCaptor.capture());
        List<AbstractObjectInstance> children = childCaptor.getAllValues();
        assertEquals(0x1000, children.get(0).getX());
        assertEquals(0x0FF8, children.get(0).getY());
        assertEquals(0x1000, children.get(1).getX());
        assertEquals(0x1008, children.get(1).getY());
    }

    @Test
    void subtypeSixteenWithBitSevenRequestsLaunchBaseTransition() {
        TransitionServices services = new TransitionServices(mock(ObjectManager.class));
        ObjectInstance instance = new Sonic3kObjectRegistry().create(spawn(0x1000, 0x1000, 0x90));
        setServices(instance, services);
        TestablePlayableSprite player = playerAt(0x1000, 0x1000);

        instance.update(1, player);

        assertEquals(6, services.requestedZone);
        assertEquals(0, services.requestedAct);
        assertTrue(services.deactivateLevelNow);
        verify(services.objectManager, times(4)).addDynamicObjectAfterCurrent(any(AbstractObjectInstance.class));
    }

    private static ObjectSpawn spawn(int x, int y, int subtype) {
        return new ObjectSpawn(x, y, ICZ_SNOW_PILE_ID, subtype, 0, false, 0);
    }

    private static TestablePlayableSprite playerAt(int x, int y) {
        return new TestablePlayableSprite("sonic", (short) x, (short) y);
    }

    private static void setServices(ObjectInstance instance, StubObjectServices services) {
        assertInstanceOf(AbstractObjectInstance.class, instance);
        ((AbstractObjectInstance) instance).setServices(services);
    }

    private static class ObjectManagerServices extends StubObjectServices {
        final ObjectManager objectManager;

        ObjectManagerServices(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }

    private static final class TransitionServices extends ObjectManagerServices {
        private int requestedZone = -1;
        private int requestedAct = -1;
        private boolean deactivateLevelNow;

        TransitionServices(ObjectManager objectManager) {
            super(objectManager);
        }

        @Override
        public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {
            this.requestedZone = zone;
            this.requestedAct = act;
            this.deactivateLevelNow = deactivateLevelNow;
        }
    }
}
