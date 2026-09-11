package com.openggf.tests;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCollapsingBridgeParity {
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic rider;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 0);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        rider = (Sonic) fixture.sprite();
    }

    @Test
    void mgzCollapseWave_isNotSolidForUntrackedPlayers() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x00);
        Sonic bystander = new Sonic("sonic", (short) 0, (short) 0);
        rider.setCentreX((short) 8);
        rider.setOnObject(true);
        bystander.setCentreX((short) 8);

        invokePerformCollapse(bridge, rider);

        assertFalse(bridge.isSolidFor(bystander),
                "ROM collapse-wave logic stops accepting new SolidObjectTop contacts once the bridge shatters");
    }

    @Test
    void mgzCollapseWave_staysSolidForTheRiderWhoTriggeredIt() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x00);
        Sonic rider = new Sonic("sonic", (short) 0, (short) 0);
        rider.setCentreX((short) 8);
        rider.setOnObject(true);
        GameServices.level().getObjectManager().forceRidingObjectForBootstrap(rider, bridge);

        invokePerformCollapse(bridge, rider);

        assertTrue(bridge.isSolidFor(rider),
                "The rider that triggered the collapse should remain supported until the release wave reaches them");
    }

    @Test
    void triggerCollapse_doesNotClaimPlayerStandingOnDifferentSolid() throws Exception {
        CollapsingBridgeObjectInstance triggeredBridge = GameServices.level().getObjectManager()
                .createDynamicObject(() -> new CollapsingBridgeObjectInstance(
                        new ObjectSpawn(0, 0, 0x0F, 0, 0x00, false, 0)));
        initialiseMgzBridge(triggeredBridge, 0x00);
        CollapsingBridgeObjectInstance actualSupport = new CollapsingBridgeObjectInstance(
                new ObjectSpawn(0x100, 0, 0x0F, 0, 0x00, false, 0));
        initialiseMgzBridge(actualSupport, 0x00);
        rider.setOnObject(true);
        GameServices.level().getObjectManager().forceRidingObjectForBootstrap(rider, actualSupport);

        invokePerformCollapse(triggeredBridge, rider);

        assertFalse(triggeredBridge.isSolidFor(rider),
                "The bridge must snapshot its own standing bit, not global Status_OnObj");
    }

    @Test
    void terrainHandoffClearsBridgeOwnershipBeforeLaterCollapse() throws Exception {
        CollapsingBridgeObjectInstance bridge = GameServices.level().getObjectManager()
                .createDynamicObject(() -> new CollapsingBridgeObjectInstance(
                        new ObjectSpawn(0, 0, 0x0F, 0, 0x00, false, 0)));
        initialiseMgzBridge(bridge, 0x00);
        rider.setCentreX((short) 0);
        GameServices.level().getObjectManager().forceRidingObjectForBootstrap(rider, bridge);

        rider.setCentreX((short) 0x100);
        GameServices.level().getObjectManager()
                .processImmediateInlineSolidCheckpoint(bridge, rider, java.util.List.of());

        assertFalse(GameServices.level().getObjectManager().isRidingObject(rider, bridge),
                "SolidObjectTop walk-off must retire the engine ride owner with the native standing bit");
        assertFalse(GameServices.level().getObjectManager().hasObjectStandingBit(rider, bridge));

        // The terrain pass grounds Sonic after the bridge's walk-off path.
        rider.setAir(false);
        invokePerformCollapse(bridge, rider);
        bridge.update(1, rider);

        assertFalse(rider.getAir(),
                "a later collapse must not release a player already handed off to terrain");
    }

    @Test
    void collapseWaveRelease_publishesNativePreviousAnimationSentinel() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x00);
        rider.getAnimationManager().publishPreviousAnimationId(2);
        rider.setPushing(true);

        invokeReleaseCollapseRider(bridge, rider);

        assertEquals(1, rider.getAnimationManager().captureRewindState().lastAnimationId(),
                "Check_CollapsePlayerRelease writes prev_anim=1");
        assertFalse(rider.getPushing(),
                "Check_CollapsePlayerRelease clears Status_Push together with Status_OnObj");
    }

    @Test
    void freshTopContact_rejectsExactSurfaceBoundary() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x00);

        assertTrue(bridge.rejectsZeroDistanceTopSolidLanding(),
                "SolidObjectTop's unsigned -$10 comparison excludes d0=0");
    }

    @Test
    void topContact_usesSolidObjectTopRelativeLandingSnap() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x20);

        assertFalse(bridge.usesPlatformObjectLandingSnap(),
                "SolidObjectTop must retain its y_pos += d0 + 3 result when Player_TouchFloor resets custom radii");
    }

    @Test
    void collapsedParent_offscreenDeleteAllowsPlacementRespawn() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x00);
        setIntField(bridge, "state", 3);
        setIntField(bridge, "y", 0x7000);
        bridge.refreshPostCameraRenderState();

        bridge.update(0, rider);

        assertTrue(bridge.isDestroyed());
        assertTrue(bridge.isDestroyedRespawnable(),
                "ObjPlatformCollapse_SmashObject clears the placement respawn latch");
    }

    @Test
    void collapsedParent_consumesPriorRenderFlagBeforeMoveSprite() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x00);
        setIntField(bridge, "state", 3);
        setIntField(bridge, "y", 0x80);
        setIntField(bridge, "velY", 0x100);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        try {
            bridge.refreshPostCameraRenderState();
            bridge.update(0, rider);

            assertFalse(bridge.isDestroyed());
            assertEquals(0x81, bridge.getY(),
                    "MoveSprite must add the old y_vel before applying gravity");
            assertEquals(0x138, getIntField(bridge, "velY"));

            AbstractObjectInstance.updateCameraBounds(0x1000, 0, 0x1140, 224, 0);
            bridge.refreshPostCameraRenderState();
            bridge.update(1, rider);
            assertTrue(bridge.isDestroyed(),
                    "Obj_PlatformCollapseFall deletes before another movement step");
        } finally {
            AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        }
    }

    @Test
    void bridgeFragment_consumesPriorRenderFlagBeforeMoveSprite() {
        CollapsingBridgeObjectInstance.BridgeFragment fragment =
                new CollapsingBridgeObjectInstance.BridgeFragment(
                        0x80, 0x80, 0, 0, 0,
                        Sonic3kObjectArtKeys.COLLAPSING_BRIDGE_MGZ,
                        false, false);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        try {
            fragment.refreshPostCameraRenderState();
            AbstractObjectInstance.updateCameraBounds(0x1000, 0, 0x1140, 224, 0);

            fragment.update(0, null);
            assertFalse(fragment.isDestroyed(),
                    "fall dispatch must retain the preceding Render_Sprites result");

            fragment.refreshPostCameraRenderState();
            fragment.update(1, null);
            assertTrue(fragment.isDestroyed());
        } finally {
            AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        }
    }

    @Test
    void standingContact_defersFirstCountdownTickToFollowingObjectPass() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x02);
        bridge.onSolidContact(rider, new SolidContact(true, false, false, true, false), 0);

        bridge.update(0, rider);
        assertEquals(0, getIntField(bridge, "state"));
        assertEquals(40, getIntField(bridge, "collapseTimer"));

        bridge.update(1, rider);
        assertEquals(1, getIntField(bridge, "state"));
        assertEquals(40, getIntField(bridge, "collapseTimer"));
    }

    @Test
    void mgzStomp_clearsOnObjectButPreservesGroundedStatus() throws Exception {
        CollapsingBridgeObjectInstance bridge = newMgzBridge(0x20);
        rider.setAir(false);
        rider.setOnObject(true);
        rider.setWallCling(true);

        Method stomp = CollapsingBridgeObjectInstance.class.getDeclaredMethod(
                "performMgzStomp", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        stomp.setAccessible(true);
        stomp.invoke(bridge, rider);

        assertFalse(rider.getAir(),
                "loc_209FC clears OnObj but does not set Status_InAir");
        assertFalse(rider.isOnObject());
        assertEquals(3, getIntField(bridge, "state"));
    }

    private static CollapsingBridgeObjectInstance newMgzBridge(int subtype) throws Exception {
        CollapsingBridgeObjectInstance bridge = new CollapsingBridgeObjectInstance(
                new ObjectSpawn(0, 0, 0x0F, subtype, 0x00, false, 0));
        initialiseMgzBridge(bridge, subtype);
        return bridge;
    }

    private static void initialiseMgzBridge(CollapsingBridgeObjectInstance bridge, int subtype)
            throws Exception {
        Method initMgz = CollapsingBridgeObjectInstance.class.getDeclaredMethod("initMGZ", int.class);
        initMgz.setAccessible(true);
        initMgz.invoke(bridge, subtype);
    }

    private static void invokePerformCollapse(CollapsingBridgeObjectInstance bridge, Sonic rider) throws Exception {
        Method performCollapse = CollapsingBridgeObjectInstance.class.getDeclaredMethod(
                "performCollapse",
                com.openggf.sprites.playable.AbstractPlayableSprite.class);
        performCollapse.setAccessible(true);
        performCollapse.invoke(bridge, rider);
    }

    private static void invokeReleaseCollapseRider(CollapsingBridgeObjectInstance bridge, Sonic rider)
            throws Exception {
        Method release = CollapsingBridgeObjectInstance.class.getDeclaredMethod(
                "releaseCollapseRider",
                com.openggf.sprites.playable.AbstractPlayableSprite.class);
        release.setAccessible(true);
        release.invoke(bridge, rider);
    }

    private static void setIntField(CollapsingBridgeObjectInstance bridge, String name, int value)
            throws Exception {
        Field field = CollapsingBridgeObjectInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(bridge, value);
    }

    private static int getIntField(CollapsingBridgeObjectInstance bridge, String name) throws Exception {
        Field field = CollapsingBridgeObjectInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(bridge);
    }
}
