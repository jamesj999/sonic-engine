package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.boss.AbstractBossInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kBossTouchResponseProfiles {

    @Test
    void aizEndBossBombDeclaresFireShieldTouchResponseProfile() {
        AizEndBossBombChild bomb = new AizEndBossBombChild(null, 0x4800, 0x180, 0);

        assertDeclaresProfileMethods(AizEndBossBombChild.class);

        TouchResponseProfile profile = bomb.getTouchResponseProfile();
        assertFireShieldSingleRegionEnemy(profile);
        assertEquals(profile, bomb.getTouchResponseProfile(false));
    }

    @Test
    void aizEndBossFlameDeclaresFireShieldTouchResponseProfile() throws Exception {
        assertDeclaresProfileMethods(AizEndBossFlameChild.class);

        TouchResponseProfile profile = declaredProfile(AizEndBossFlameChild.class);
        assertFireShieldSingleRegionEnemy(profile);
    }

    @Test
    void aizMinibossBodyDeclaresFireShieldTouchResponseProfile() {
        AizMinibossBodyChild body = new AizMinibossBodyChild(new DummyBoss());

        assertDeclaresProfileMethods(AizMinibossBodyChild.class);

        TouchResponseProfile profile = body.getTouchResponseProfile();
        assertFireShieldSingleRegionEnemy(profile);
        assertEquals(profile, body.getTouchResponseProfile(false));
        assertTrue(body.usesCurrentTouchResponseState(),
                "Child_DrawTouch_Sprite2 publishes the live refreshed body pointer");
    }

    @Test
    void aizMinibossParentTouchResponseIncludesCpuSidekick() {
        AizMinibossInstance boss = new AizMinibossInstance(
                new ObjectSpawn(0x11F0, 0x0335, 0x91, 0, 0, false, 0));

        TouchResponseProfile profile = boss.getTouchResponseProfile();

        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                profile.actorContextPolicy());
        assertTrue(boss.usesCurrentTouchResponseState(),
                "Collision_response_list keeps the live AIZ miniboss object-RAM pointer");
    }

    @Test
    void mgzMinibossPublishesLivePostMovementTouchPosition() {
        MgzMinibossInstance boss = new MgzMinibossInstance(
                new ObjectSpawn(0x2F00, 0x0E60, 0x9F, 0, 0, false, 0));

        assertTrue(boss.usesCurrentTouchResponseState(),
                "Draw_And_Touch_Sprite publishes the MGZ miniboss SST after its movement routine");
    }

    @Test
    void cnzMinibossPublishesLivePostMovementTouchPosition() {
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x32C0, 0x02B8, 0xA6, 0, 0, false, 0));

        assertTrue(boss.usesCurrentTouchResponseState(),
                "Draw_And_Touch_Sprite publishes the CNZ miniboss SST after its movement routine");
    }

    @Test
    void hczMinibossDeclaresDynamicMultiRegionTouchResponseProfile() {
        HczMinibossInstance boss = new HczMinibossInstance(
                new ObjectSpawn(0x3600, 0x0500, 0x99, 0, 0, false, 0));

        assertDeclaresProfileMethods(HczMinibossInstance.class);

        TouchResponseProfile singleRegion = boss.getTouchResponseProfile(false);
        assertStandardEnemy(singleRegion);
        assertTrue(singleRegion.requiresRenderFlagForTouch());
        assertFalse(singleRegion.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                singleRegion.stopAfterFirstOverlapPolicy());

        TouchResponseProfile multiRegion = boss.getTouchResponseProfile(true);
        assertStandardEnemy(multiRegion);
        assertTrue(multiRegion.requiresRenderFlagForTouch());
        assertTrue(multiRegion.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                multiRegion.stopAfterFirstOverlapPolicy());

        boss.getState().routine = 6;
        assertEquals(multiRegion, boss.getTouchResponseProfile());
    }

    @Test
    void cnzMinibossClosedBodyUsesRomObjDatCollisionByte() {
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x32C0, 0x0292, 0xA6, 0, 0, false, 0));

        assertEquals(0x0C, boss.getCollisionFlags(),
                "ObjDat_CNZMiniboss stores collision_flags byte $0C after width/height/frame");
        assertEquals(6, boss.getCollisionProperty());
        assertEquals(TouchCategoryDecodeMode.NORMAL,
                boss.getTouchResponseProfile().categoryDecodeMode());
    }

    private static void assertFireShieldSingleRegionEnemy(TouchResponseProfile profile) {
        assertStandardEnemy(profile);
        assertTrue(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0x10, profile.shieldReactionFlags());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    private static void assertStandardEnemy(TouchResponseProfile profile) {
        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
    }

    private static void assertDeclaresProfileMethods(Class<?> type) {
        assertDoesNotThrow(() -> type.getDeclaredMethod("getTouchResponseProfile"));
        assertDoesNotThrow(() -> type.getDeclaredMethod("getTouchResponseProfile", boolean.class));
    }

    private static TouchResponseProfile declaredProfile(Class<?> type) throws Exception {
        var field = type.getDeclaredField("TOUCH_RESPONSE_PROFILE");
        field.setAccessible(true);
        return (TouchResponseProfile) field.get(null);
    }

    private static AizEndBossInstance buildAizEndBoss() throws Exception {
        AizEndBossInstance boss = new AizEndBossInstance(
                new ObjectSpawn(0x48E0, 0x015A, 0x92, 0, 0, false, 0));
        boss.setServices(new TestObjectServices());
        return boss;
    }

    private static AizEndBossPropellerChild buildAizEndBossPropeller() throws Exception {
        AizEndBossInstance boss = buildAizEndBoss();
        AizEndBossArmChild arm = new AizEndBossArmChild(boss, 0, 0, 0);
        AizEndBossPropellerChild propeller = new AizEndBossPropellerChild(boss, arm, 0);
        propeller.setPosition(0x4800, 0x0180);
        return propeller;
    }

    private static final class DummyBoss extends AbstractBossInstance {
        private DummyBoss() {
            super(new ObjectSpawn(0x1200, 0x0300, 0x91, 0, 0, false, 0), "DummyBoss");
        }

        @Override
        protected void initializeBossState() {
            state.routine = 0;
            state.hitCount = 6;
        }

        @Override
        protected int getInitialHitCount() {
            return 6;
        }

        @Override
        protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        protected void onHitTaken(int remainingHits) {
        }

        @Override
        protected int getCollisionSizeIndex() {
            return 0x0F;
        }

        @Override
        protected int getBossHitSfxId() {
            return 0;
        }

        @Override
        protected int getBossExplosionSfxId() {
            return 0;
        }
    }
}
