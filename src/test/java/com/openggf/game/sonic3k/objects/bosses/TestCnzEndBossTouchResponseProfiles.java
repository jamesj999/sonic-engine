package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzEndBossTouchResponseProfiles {

    @Test
    void bossArmAndMagnetDeclareCanonicalMultiRegionEnemyProfiles() throws Exception {
        CnzEndBossInstance boss = new CnzEndBossInstance(
                new ObjectSpawn(0x4740, 0x0240, 0xA7, 0, 0, false, 0));
        field(boss, "startupComplete").setBoolean(boss, true);
        CnzEndBossArmChild arm = new CnzEndBossArmChild(boss, 0);
        CnzEndBossMagnetChild magnet = new CnzEndBossMagnetChild(boss);

        assertPublisher(boss, boss.getCentreX(), boss.getCentreY(), 0x06, 8);
        assertPublisher(arm, arm.getCentreX(), arm.getCentreY(), 0x9E, 0);
        assertPublisher(magnet, magnet.getCentreX(), magnet.getCentreY(), 0x8B, 0);
    }

    private static void assertPublisher(TouchResponseProvider publisher,
                                        int expectedX,
                                        int expectedY,
                                        int expectedFlags,
                                        int expectedProperty) throws Exception {
        Class<?> concreteClass = publisher.getClass();
        assertEquals(concreteClass,
                concreteClass.getDeclaredMethod("getTouchResponseProfile").getDeclaringClass());
        assertEquals(concreteClass,
                concreteClass.getDeclaredMethod("getTouchResponseProfile", boolean.class).getDeclaringClass());

        TouchResponseProfile profile = publisher.getTouchResponseProfile();
        assertEquals(profile, publisher.getTouchResponseProfile(true));
        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertTrue(profile.multiRegionSource());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0, profile.shieldReactionFlags());
        assertFalse(profile.enablesPostSpecialTouchAirborneSideVelocityPreservation());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                profile.stopAfterFirstOverlapPolicy());

        TouchResponseProvider.TouchRegion[] regions = publisher.getMultiTouchRegions();
        assertNotNull(regions);
        assertEquals(1, regions.length);
        assertEquals(expectedX, regions[0].x());
        assertEquals(expectedY, regions[0].y());
        assertEquals(expectedFlags, regions[0].collisionFlags());
        assertEquals(0, regions[0].shieldReactionFlags());
        assertEquals(expectedProperty, publisher.getCollisionProperty());
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
