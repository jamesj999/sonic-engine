package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGumballItemPriority {

    @Test
    void staticGumballItem_usesLowBucket4Priority() {
        GumballItemObjectInstance item =
                new GumballItemObjectInstance(new ObjectSpawn(0x100, 0x180, 0xEB, 0x00, 0, false, 0));

        assertEquals(4, item.getPriorityBucket());
        assertFalse(item.isHighPriority());
    }

    @Test
    void machineEjectedGumballItem_usesRomHighBucket2Priority() {
        GumballItemObjectInstance item =
                new GumballItemObjectInstance(new ObjectSpawn(0x100, 0x180, 0xEB, 0x00, 0, false, 0), 0, true);

        assertEquals(2, item.getPriorityBucket());
        assertTrue(item.isHighPriority());
    }

    @Test
    void machineEjectedGumballItem_keepsDistinctRomPriorityFromStaticItems() {
        GumballItemObjectInstance staticItem =
                new GumballItemObjectInstance(new ObjectSpawn(0x100, 0x180, 0xEB, 0x00, 0, false, 0));
        GumballItemObjectInstance ejectedItem =
                new GumballItemObjectInstance(new ObjectSpawn(0x100, 0x180, 0xEB, 0x00, 0, false, 0), 0, true);

        assertEquals(4, staticItem.getPriorityBucket());
        assertEquals(2, ejectedItem.getPriorityBucket());
        assertNotEquals(staticItem.getPriorityBucket(), ejectedItem.getPriorityBucket());
        assertFalse(staticItem.isHighPriority());
        assertTrue(ejectedItem.isHighPriority());
    }

    @Test
    void pachinkoFloatItem_keepsRegularLowPriorityAttributes() {
        GumballItemObjectInstance item =
                GumballItemObjectInstance.createPachinkoItem(
                        new ObjectSpawn(0x100, 0x180, 0xEB, 0x00, 0, false, 0));

        assertEquals(4, item.getPriorityBucket());
        assertFalse(item.isHighPriority());
    }

    @Test
    void touchProfileKeepsDefaultEdgeTriggeredSpecialItemPolicy() {
        GumballItemObjectInstance item =
                new GumballItemObjectInstance(new ObjectSpawn(0x100, 0x180, 0xEB, 0x00, 0, false, 0));

        TouchResponseProfile profile = item.getTouchResponseProfile();

        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0, profile.shieldReactionFlags());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());

        assertFalse(item.requiresContinuousTouchCallbacks());
        assertDoesNotThrow(() -> GumballItemObjectInstance.class.getDeclaredMethod("getTouchResponseProfile"));
        assertEquals(profile, item.getTouchResponseProfile(false));
        assertDoesNotThrow(() -> GumballItemObjectInstance.class.getDeclaredMethod(
                "getTouchResponseProfile", boolean.class));
    }
}

