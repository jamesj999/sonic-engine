package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.LevelData;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tools.ObjectDiscoveryTool.LevelConfig;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestStarPointerBadnikInstance {

    @Test
    void parentTouchUsesLiveSstPositionPublishedAfterMovement() {
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0, 0, false, 0));

        assertTrue(starPointer.usesCurrentTouchResponseState(),
                "Obj_StarPointer moves before publishing its SST pointer to Collision_response_list");
    }

    @Test
    void registryCreatesStarPointerForS3klZones() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry() {
            @Override
            protected int currentRomZoneId() {
                return Sonic3kZoneIds.ZONE_ICZ;
            }
        };

        ObjectInstance instance = registry.create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.STAR_POINTER, 0x06, 0, false, 0));

        assertInstanceOf(StarPointerBadnikInstance.class, instance);
    }

    @Test
    void profileMarksStarPointerImplementedForS3klLevelsOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        LevelConfig icz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_ICECAP_1)
                .findFirst()
                .orElseThrow();
        LevelConfig mhz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst()
                .orElseThrow();

        assertTrue(profile.getImplementedIds(icz1).contains(Sonic3kObjectIds.STAR_POINTER));
        assertFalse(profile.getImplementedIds(mhz1).contains(Sonic3kObjectIds.STAR_POINTER));
    }

    @Test
    void activeRoutineStartsOneDispatchAfterWaitOffscreenInitialization() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0x06, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getDead()).thenReturn(false);

        starPointer.update(0, player);
        assertEquals(160, starPointer.getX(), "init frame should only set velocity and children");

        starPointer.update(1, player);
        assertEquals(160, starPointer.getX(),
                "Process_Sprites does not dispatch loc_8BE74 on the first active pass");

        starPointer.update(2, player);
        assertEquals(159, starPointer.getX(),
                "subtype bits 1-2 = 3 select ROM speed -$100 toward the player");
    }

    @Test
    void offscreenReentryInitializesOnceThenConsumesOneActiveRoutinePass() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(400, 100, Sonic3kObjectIds.STAR_POINTER, 0x06, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getDead()).thenReturn(false);
        ObjectServices services = mock(ObjectServices.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(services.objectManager()).thenReturn(objectManager);
        when(services.playerQuery()).thenReturn(new ObjectPlayerQuery(() -> player, List::of));
        starPointer.setServices(services);

        starPointer.update(0, player);
        verify(objectManager, never()).addDynamicObjectAfterCurrent(
                org.mockito.ArgumentMatchers.any(StarPointerBadnikInstance.OrbitingPointInstance.class));
        assertEquals(400, starPointer.getX(), "offscreen wait must not initialize or move");

        AbstractObjectInstance.updateCameraBounds(100, 0, 419, 223, 0);
        starPointer.update(1, player);
        verify(objectManager, times(4)).addDynamicObjectAfterCurrent(
                org.mockito.ArgumentMatchers.any(StarPointerBadnikInstance.OrbitingPointInstance.class));
        assertEquals(400, starPointer.getX(), "re-entry initializes without active movement");

        starPointer.update(2, player);
        assertEquals(400, starPointer.getX(), "first active dispatch only arms loc_8BE74");

        starPointer.update(3, player);
        starPointer.update(4, player);
        assertEquals(398, starPointer.getX(), "later active dispatches move at the subtype speed");
        verify(objectManager, times(4)).addDynamicObjectAfterCurrent(
                org.mockito.ArgumentMatchers.any(StarPointerBadnikInstance.OrbitingPointInstance.class));
    }

    @Test
    void rewindBetweenInitializationAndActiveDispatchPreservesDelayAndReleaseState() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        ObjectSpawn spawn = new ObjectSpawn(
                160, 100, Sonic3kObjectIds.STAR_POINTER, 0x06, 0, false, 0);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getDead()).thenReturn(false);
        StarPointerBadnikInstance source = new StarPointerBadnikInstance(spawn);

        source.update(0, player);
        PerObjectRewindSnapshot initializedSnapshot = source.captureRewindState();

        StarPointerBadnikInstance restored = new StarPointerBadnikInstance(spawn);
        restored.restoreRewindState(initializedSnapshot);
        restored.update(1, player);
        assertEquals(160, restored.getX(),
                "restored initialized object must still consume its pending active-routine pass");
        assertFalse(restored.shouldReleaseChildren(),
                "pending active-routine pass must not evaluate the release latch");

        restored.update(2, player);
        assertEquals(159, restored.getX(), "movement begins on the following restored dispatch");
        assertTrue(restored.shouldReleaseChildren(),
                "release latch resumes together with ROM loc_8BE74");
    }

    @Test
    void postInitMovementContinuesAfterLeavingCameraXBounds() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0x06, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getDead()).thenReturn(false);

        starPointer.update(0, player);
        AbstractObjectInstance.updateCameraBounds(1000, 0, 1319, 223, 0);
        starPointer.update(1, player);
        starPointer.update(2, player);

        assertEquals(159, starPointer.getX(),
                "ROM loc_8BE74/loc_8BEA6 moves after Obj_WaitOffscreen installs the active routine");
    }

    @Test
    void waitOffscreenStartsWhenDummySpriteRenderBoundsOverlapViewport() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 447, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(478, 100, Sonic3kObjectIds.STAR_POINTER, 0x06, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getDead()).thenReturn(false);

        starPointer.update(0, player);
        starPointer.update(1, player);
        starPointer.update(2, player);

        assertEquals(477, starPointer.getX(),
                "Obj_WaitOffscreen uses a $20x$20 dummy sprite render flag, not a center-point X gate");
    }

    @Test
    void initSpawnsFourOrbitingPoints() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        ObjectServices services = mock(ObjectServices.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(services.objectManager()).thenReturn(objectManager);
        starPointer.setServices(services);

        starPointer.update(0, player);

        verify(objectManager, times(4)).addDynamicObjectAfterCurrent(
                org.mockito.ArgumentMatchers.any(StarPointerBadnikInstance.OrbitingPointInstance.class));
    }

    @Test
    void releaseLatchUsesNearestNativeP2LikeFindSonicTails() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0, 0, false, 0));
        AbstractPlayableSprite sonic = mock(AbstractPlayableSprite.class);
        when(sonic.getCentreX()).thenReturn((short) 20);
        when(sonic.getCentreY()).thenReturn((short) 100);
        when(sonic.getDead()).thenReturn(false);
        AbstractPlayableSprite tails = mock(AbstractPlayableSprite.class);
        when(tails.getCentreX()).thenReturn((short) 100);
        when(tails.getCentreY()).thenReturn((short) 100);
        when(tails.getDead()).thenReturn(false);
        starPointer.setServices(new StubObjectServices().withPlayerQuery(
                new ObjectPlayerQuery(() -> sonic, () -> List.of(tails))));

        starPointer.update(0, sonic);
        starPointer.update(1, sonic);
        starPointer.update(2, sonic);

        assertTrue(starPointer.shouldReleaseChildren(),
                "ROM loc_8BE74 calls Find_SonicTails before latching child release");
    }

    @Test
    void launchFrameStillRefreshesCircularPositionBeforeMovingNextFrame() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 200);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getDead()).thenReturn(false);
        starPointer.update(0, player);
        starPointer.update(1, player);
        starPointer.update(2, player);
        assertTrue(starPointer.shouldReleaseChildren(), "test setup should latch child release");

        StarPointerBadnikInstance.OrbitingPointInstance point =
                new StarPointerBadnikInstance.OrbitingPointInstance(starPointer.getSpawn(), starPointer, 0);
        point.update(1, player); // loc_8BEB0 initialization-only execution
        setIntField(point, "angle", 0xFF);
        setIntField(point, "currentX", 0);
        setIntField(point, "currentY", 0);

        point.update(2, player);

        assertEquals(starPointer.getX(), point.getX(),
                "loc_8BEE6 falls through into MoveSprite_CircularSimple on the launch frame");
        assertEquals(starPointer.getY() + 16, point.getY(),
                "angle 0 refreshes to the parent's lower orbit position before launch movement starts");
    }

    @Test
    void firstChildExecutionOnlyInitializesAndDoesNotPublishTouchEntry() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getDead()).thenReturn(false);
        starPointer.update(0, player);

        StarPointerBadnikInstance.OrbitingPointInstance point =
                new StarPointerBadnikInstance.OrbitingPointInstance(starPointer.getSpawn(), starPointer, 0);

        assertEquals(starPointer.getX(), point.getX());
        assertEquals(starPointer.getY(), point.getY());
        point.update(0, player);
        assertEquals(starPointer.getX(), point.getX(), "loc_8BEB0 does not run circular movement");
        assertEquals(starPointer.getY(), point.getY(), "loc_8BEB0 preserves the copied parent position");
        assertFalse(point.publishesTouchResponseListEntryThisFrame(),
                "loc_8BEB0 returns before Child_DrawTouch_Sprite");

        point.update(2, player);
        assertTrue(point.publishesTouchResponseListEntryThisFrame(),
                "loc_8BEE6 publishes the point after its first active update");
    }

    @Test
    void orbitingPointPreservesParentSubpixelDuringCircularMove() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getDead()).thenReturn(false);
        starPointer.update(0, player);
        starPointer.update(1, player);
        starPointer.update(2, player);

        StarPointerBadnikInstance.OrbitingPointInstance point =
                new StarPointerBadnikInstance.OrbitingPointInstance(starPointer.getSpawn(), starPointer, 0);
        setIntField(point, "angle", 1);

        point.update(0, player);
        point.update(1, player);

        assertEquals(starPointer.getX() + 1, point.getX(),
                "MoveSprite_CircularSimple adds the sine offset to the parent's full longword x_pos");
    }

    @Test
    void orbitingPointDeclaresShieldDeflectTouchResponseProfile() {
        StarPointerBadnikInstance starPointer = new StarPointerBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.STAR_POINTER, 0, 0, false, 0));
        StarPointerBadnikInstance.OrbitingPointInstance point =
                new StarPointerBadnikInstance.OrbitingPointInstance(starPointer.getSpawn(), starPointer, 0);

        TouchResponseProfile expected = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.SHIELD_DEFLECT,
                0x08,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        assertEquals(expected, point.getTouchResponseProfile());
        assertEquals(expected, point.getTouchResponseProfile(false));
        assertDoesNotThrow(() -> StarPointerBadnikInstance.OrbitingPointInstance.class
                .getDeclaredMethod("getTouchResponseProfile"));
        assertDoesNotThrow(() -> StarPointerBadnikInstance.OrbitingPointInstance.class
                .getDeclaredMethod("getTouchResponseProfile", boolean.class));
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
