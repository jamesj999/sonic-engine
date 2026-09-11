package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.S3kZoneSet;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestBubblesBadnikInstance {

    @Test
    void mgzZoneSetResolvesObject9bAsBubblesBadnik() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        assertEquals("BubblesBadnik",
                registry.getPrimaryName(Sonic3kObjectIds.BUBBLES_BADNIK, S3kZoneSet.S3KL));

        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        assertTrue(profile.getBadnikIds().contains(Sonic3kObjectIds.BUBBLES_BADNIK),
                "The S3KL object profile marks ID $9B as a badnik");

        ObjectInstance instance = registry.create(new ObjectSpawn(
                0x0AB0, 0x0DA0, Sonic3kObjectIds.BUBBLES_BADNIK, 0, 0, false, 0x0DA0));
        assertInstanceOf(BubblesBadnikInstance.class, instance);
    }

    @Test
    void objWaitOffscreenFreezesLayoutSpawnUntilVisible() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        AbstractObjectInstance.updateCameraBounds(0x0800, 0x0CA0, 0x0940, 0x0D80, 0);
        BubblesBadnikInstance bubbles = new BubblesBadnikInstance(new ObjectSpawn(
                0x0AB0, 0x0DA0, Sonic3kObjectIds.BUBBLES_BADNIK, 0, 0, false, 0x0DA0));
        bubbles.setServices(new TestObjectServices());

        for (int frame = 0; frame < 20; frame++) {
            bubbles.update(frame, null);
        }

        assertEquals(0x0AB0, bubbles.getX(),
                "ROM Obj_WaitOffscreen keeps the MGZ Bubbles layout position frozen before visible activation");
        assertEquals(0x0DA0, bubbles.getY());
        assertEquals(0, bubbles.getCollisionFlags(),
                "Obj_WaitOffscreen returns before Obj_BubblesBadnik writes collision_flags");
    }
}
