package com.openggf.game.sonic2.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestBumperObjectInstance {

    @Test
    void touchProfileNamesSonic2SpecialLatchPolicy() {
        BumperObjectInstance bumper = new BumperObjectInstance(
                new ObjectSpawn(0x0F00, 0x0400, 0x44, 0, 0, false, 0),
                "Bumper");

        TouchResponseProfile profile = bumper.getTouchResponseProfile();

        assertEquals(TouchCategoryDecodeMode.SONIC2_SPECIAL_PROPERTY, profile.categoryDecodeMode());
        assertTrue(profile.continuousCallbacks());
        assertFalse(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0, profile.shieldReactionFlags());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());

        assertTrue(bumper.usesSonic2TouchSpecialPropertyResponse());
        assertTrue(bumper.requiresContinuousTouchCallbacks());
        assertFalse(bumper.requiresRenderFlagForTouch());
        assertEquals(0xD7, bumper.getCollisionFlags());
        assertEquals(0, bumper.getCollisionProperty());
        assertEquals(profile, bumper.getTouchResponseProfile(false));
    }
}
