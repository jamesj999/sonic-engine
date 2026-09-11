package com.openggf.tests;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestS3kIczIceSpikesObject {

    // Clear any gameplay session leaked by a prior test in this fork so the registry
    // resolves the S3KL zone set (not a leaked SKL zone). Parallel-suite flake fix.
    @BeforeEach
    void clearLeakedGameplaySession() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void registryCreatesIczIceSpikesInstanceAndProfileMarksS3klSlotImplemented() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x2400, 0x0500, Sonic3kObjectIds.ICZ_ICE_SPIKES, 0, 0, false, 0));

        assertInstanceOf(IczIceSpikesObjectInstance.class, instance);
        assertTrue(new Sonic3kObjectProfile().getImplementedIds().contains(Sonic3kObjectIds.ICZ_ICE_SPIKES));
    }

    @Test
    void subtypeZeroUsesRomSolidDimensionsAndSpawnsLowerHurtChild() {
        ObjectManager objectManager = mock(ObjectManager.class);
        IczIceSpikesObjectInstance spikes = new IczIceSpikesObjectInstance(
                new ObjectSpawn(0x2400, 0x0500, Sonic3kObjectIds.ICZ_ICE_SPIKES, 0, 0, false, 0));
        spikes.setServices(new ObjectManagerServices(objectManager));

        spikes.update(0, null);

        SolidObjectParams params = spikes.getSolidParams();
        assertEquals(0x17, params.halfWidth());
        assertEquals(0x08, params.airHalfHeight());
        assertEquals(0x08, params.groundHalfHeight());
        assertEquals(5, spikes.getMappingFrameForTesting());
        assertEquals(Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN, spikes.getArtKeyForTesting());
        assertEquals(0, spikes.getCollisionFlags());

        ArgumentCaptor<AbstractObjectInstance> childCaptor = ArgumentCaptor.forClass(AbstractObjectInstance.class);
        verify(objectManager).addDynamicObjectAfterCurrent(childCaptor.capture());
        AbstractObjectInstance child = childCaptor.getValue();
        assertInstanceOf(TouchResponseProvider.class, child);
        assertEquals(0x2400, child.getX());
        assertEquals(0x050C, child.getY(), "ChildObjDat_8B35C places the hurt child at y_pos+$0C");
        assertEquals(0x98, ((TouchResponseProvider) child).getCollisionFlags());
    }

    @Test
    void subtypeZeroVerticalFlipSpawnsUpperHurtChild() {
        ObjectManager objectManager = mock(ObjectManager.class);
        IczIceSpikesObjectInstance spikes = new IczIceSpikesObjectInstance(
                new ObjectSpawn(0x2400, 0x0500, Sonic3kObjectIds.ICZ_ICE_SPIKES, 0, 0x02, false, 0));
        spikes.setServices(new ObjectManagerServices(objectManager));

        spikes.update(0, null);

        ArgumentCaptor<AbstractObjectInstance> childCaptor = ArgumentCaptor.forClass(AbstractObjectInstance.class);
        verify(objectManager).addDynamicObjectAfterCurrent(childCaptor.capture());
        assertEquals(0x04F4, childCaptor.getValue().getY(),
                "ChildObjDat_8B364 places the hurt child at y_pos-$0C when render_flags bit 1 is set");
    }

    @Test
    void subtypeTwoWaitsForNearbyPlayerThenShakesForSixteenFrames() {
        IczIceSpikesObjectInstance spikes = new IczIceSpikesObjectInstance(
                new ObjectSpawn(0x2400, 0x0500, Sonic3kObjectIds.ICZ_ICE_SPIKES, 0x02, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x23C1, (short) 0x0500);
        player.setCentreX((short) 0x23C0);

        spikes.update(0, player);

        assertFalse(spikes.isShakeActiveForTesting(), "Find_SonicTails uses bhs, so distance $40 does not arm");
        assertEquals(0x2400, spikes.getX());
        assertEquals(0x92, spikes.getCollisionFlags());

        player.setCentreX((short) 0x23C2);
        spikes.update(1, player);
        assertTrue(spikes.isShakeActiveForTesting());
        assertEquals(0x0F, spikes.getShakeTimerForTesting());

        spikes.update(2, player);
        assertEquals(0x2402, spikes.getX());
        spikes.update(3, player);
        assertEquals(0x2400, spikes.getX());

        for (int frame = 4; frame <= 17; frame++) {
            spikes.update(frame, player);
        }

        assertFalse(spikes.isShakeActiveForTesting());
        assertTrue(spikes.isMoveTouchActiveForTesting());
    }

    @Test
    void subtypeTwoCanArmFromNearbySidekickWhenMainPlayerIsOutsideRange() {
        IczIceSpikesObjectInstance spikes = new IczIceSpikesObjectInstance(
                new ObjectSpawn(0x2400, 0x0500, Sonic3kObjectIds.ICZ_ICE_SPIKES, 0x02, 0, false, 0));
        TestablePlayableSprite mainPlayer = new TestablePlayableSprite("sonic", (short) 0x2300, (short) 0x0500);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x23C2, (short) 0x0500);
        spikes.setServices(new SidekickServices(List.of(sidekick)));

        spikes.update(1, mainPlayer);

        assertTrue(spikes.isShakeActiveForTesting(),
                "Obj_ICZIceSpikes calls Find_SonicTails, so a nearby sidekick should arm the nonzero subtype");
    }

    private static final class ObjectManagerServices extends StubObjectServices {
        private final ObjectManager objectManager;

        private ObjectManagerServices(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }

    private static final class SidekickServices extends StubObjectServices {
        private final List<PlayableEntity> sidekicks;

        private SidekickServices(List<PlayableEntity> sidekicks) {
            this.sidekicks = sidekicks;
            withPlayerQuery(new ObjectPlayerQuery(() -> null, this::sidekicks));
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return sidekicks;
        }
    }
}
