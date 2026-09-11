package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kBadnikTouchResponseProfiles {

    @Test
    void caterkillerJrBodyDeclaresCanonicalRenderGateOptOutProfile() {
        CaterkillerJrBodyInstance body = new CaterkillerJrBodyInstance(spawn(), 0, 0);

        assertDeclaresProfileMethods(CaterkillerJrBodyInstance.class);

        TouchResponseProfile profile = body.getTouchResponseProfile();
        assertStandardSingleRegionEnemy(profile);
        assertFalse(profile.requiresRenderFlagForTouch());
        assertEquals(profile, body.getTouchResponseProfile(false));
    }

    @Test
    void sharedS3kBadnikProjectileDeclaresCanonicalShieldDeflectProfile() {
        S3kBadnikProjectileInstance projectile = new S3kBadnikProjectileInstance(
                spawn(), "renderer", 0, 0x120, 0x130, 0x200, -0x100,
                0x20, 0x18, 5, false);

        assertDeclaresProfileMethods(S3kBadnikProjectileInstance.class);

        TouchResponseProfile profile = projectile.getTouchResponseProfile();
        assertShieldDeflectProjectileProfile(profile);
        assertEquals(profile, projectile.getTouchResponseProfile(false));
    }

    @Test
    void blastoidProjectileDeclaresCanonicalShieldDeflectProfile() throws Exception {
        TouchResponseProvider projectile = newBlastoidProjectile();

        assertDeclaresProfileMethods(projectile.getClass());

        TouchResponseProfile profile = projectile.getTouchResponseProfile();
        assertShieldDeflectProjectileProfile(profile);
        assertEquals(profile, projectile.getTouchResponseProfile(false));
    }

    private static TouchResponseProvider newBlastoidProjectile() throws Exception {
        Class<?> projectileClass = Class.forName(
                "com.openggf.game.sonic3k.objects.badniks.BlastoidBadnikInstance$BlastoidProjectile");
        Constructor<?> constructor = projectileClass.getDeclaredConstructor(
                ObjectSpawn.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        return (TouchResponseProvider) constructor.newInstance(spawn(), 0x120, 0x130, 0x200, -0x100);
    }

    private static void assertDeclaresProfileMethods(Class<?> type) {
        assertDoesNotThrow(() -> type.getDeclaredMethod("getTouchResponseProfile"));
        assertDoesNotThrow(() -> type.getDeclaredMethod("getTouchResponseProfile", boolean.class));
    }

    private static void assertShieldDeflectProjectileProfile(TouchResponseProfile profile) {
        assertStandardSingleRegionEnemy(profile);
        assertTrue(profile.requiresRenderFlagForTouch());
        assertEquals(TouchShieldDeflectCapability.SHIELD_DEFLECT, profile.shieldDeflectCapability());
        assertEquals(0x08, profile.shieldReactionFlags());
    }

    private static void assertStandardSingleRegionEnemy(TouchResponseProfile profile) {
        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    private static ObjectSpawn spawn() {
        return new ObjectSpawn(0x100, 0x120, 0x94, 0, 0, false, 0);
    }
}
