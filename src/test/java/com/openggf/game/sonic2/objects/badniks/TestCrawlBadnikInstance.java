package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.sprites.playable.Sonic;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCrawlBadnikInstance {

    @Test
    void touchProfileNamesSonic2AttackSpecialPolicyWhileKeepingBadnikDefaults() {
        CrawlBadnikInstance crawl = new CrawlBadnikInstance(
                new ObjectSpawn(0x0F00, 0x0400, 0xC8, 0, 0, false, 0));

        TouchResponseProfile profile = crawl.getTouchResponseProfile();

        assertEquals(TouchCategoryDecodeMode.SONIC2_SPECIAL_PROPERTY, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0, profile.shieldReactionFlags());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());

        assertTrue(crawl.usesSonic2TouchSpecialPropertyResponse());
        assertFalse(crawl.requiresContinuousTouchCallbacks());
        assertTrue(crawl.requiresRenderFlagForTouch());
        assertEquals(0x17, crawl.getCollisionFlags());
        assertEquals(0, crawl.getCollisionProperty());
        assertEquals(profile, crawl.getTouchResponseProfile(false));
    }

    @Test
    void nonRollingSpecialTouchSwitchesAttackingCrawlToHurtCollision() throws Exception {
        CrawlBadnikInstance crawl = new CrawlBadnikInstance(
                new ObjectSpawn(0x0F00, 0x0400, 0xC8, 0, 0, false, 0));
        crawl.setServices(new TestObjectServices());
        forceState(crawl, "ATTACKING", "WALKING");

        Sonic sonic = new Sonic("sonic", (short) 0x0F00, (short) 0x0400);
        sonic.setRolling(false);
        sonic.setRollingJump(false);
        sonic.setJumping(false);
        sonic.setAir(false);
        sonic.setInvincibleFrames(0);

        crawl.onTouchResponse(sonic, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        crawl.update(0, sonic);

        assertEquals(0x97, crawl.getCollisionFlags());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void forceState(CrawlBadnikInstance crawl, String stateName, String previousStateName)
            throws ReflectiveOperationException {
        Field stateField = CrawlBadnikInstance.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(crawl, Enum.valueOf((Class<Enum>) stateField.getType(), stateName));

        Field previousStateField = CrawlBadnikInstance.class.getDeclaredField("previousState");
        previousStateField.setAccessible(true);
        previousStateField.set(crawl, Enum.valueOf((Class<Enum>) previousStateField.getType(), previousStateName));
    }
}
