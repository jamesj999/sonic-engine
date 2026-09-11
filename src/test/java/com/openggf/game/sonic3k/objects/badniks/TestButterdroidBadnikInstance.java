package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.GameStateManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestButterdroidBadnikInstance {

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void registryCreatesButterdroidForSklSlot8fInMhz() {
        Sonic3kObjectRegistry registry = new MhzRegistry();

        ObjectInstance instance = registry.create(new ObjectSpawn(0x180, 0x100,
                Sonic3kObjectIds.BUTTERDROID, 0, 0, false, 0));

        assertInstanceOf(ButterdroidBadnikInstance.class, instance);
    }

    @Test
    void constantsUseSkSideButterdroidData() {
        assertEquals(0x16652A, Sonic3kConstants.ART_UNC_BUTTERDROID_ADDR);
        assertEquals(1376, Sonic3kConstants.ART_UNC_BUTTERDROID_SIZE);
        assertEquals(0x08E12E, Sonic3kConstants.MAP_BUTTERDROID_ADDR);
        assertEquals(0x08E160, Sonic3kConstants.DPLC_BUTTERDROID_ADDR);
    }

    @Test
    void usesRomPriorityBucketFromObjSlot() {
        ButterdroidBadnikInstance butterdroid = butterdroid();

        assertEquals(5, butterdroid.getPriorityBucket(),
                "ObjSlot_Butterdroid priority word $280 maps to render bucket 5");
    }

    @Test
    void usesRomRenderBoundsFromObjSlot() {
        ButterdroidBadnikInstance butterdroid = butterdroid();

        assertEquals(0x0C, butterdroid.getOnScreenHalfWidth(),
                "ObjSlot_Butterdroid width_pixels byte is $0C");
        assertEquals(0x0C, butterdroid.getOnScreenHalfHeight(),
                "ObjSlot_Butterdroid height_pixels byte is $0C");
    }

    @Test
    void chasesPlayerWithRomAccelerationAndClamp() {
        putButterdroidOnScreen();
        ButterdroidBadnikInstance butterdroid = butterdroid();
        TestablePlayableSprite player = player(0x240, 0x120);

        butterdroid.update(0, player);
        butterdroid.update(1, player);
        butterdroid.update(2, player);

        assertEquals(4, butterdroid.getXVelocity());
        assertEquals(4, butterdroid.getYVelocity());
        assertEquals(1, butterdroid.getMappingFrame(),
                "loc_8E0DA tail-calls Animate_Raw over AniRaw_Butterdroid; "
                        + "the first main tick advances from anim_frame 0 to script byte 2");
        assertEquals(0x180, butterdroid.getX());
        assertEquals(0x100, butterdroid.getY());
        assertFalse(butterdroid.isFacingLeft());

        for (int frame = 3; frame <= 82; frame++) {
            butterdroid.update(frame, player);
        }

        assertTrue(butterdroid.getXVelocity() <= 0x100);
        assertTrue(butterdroid.getYVelocity() <= 0x100);
    }

    @Test
    void chaseUsesClosestNativePlayerFromFindSonicTails() {
        putButterdroidOnScreen();
        TestablePlayableSprite sidekick = player(0x1A0, 0x120);
        ButterdroidBadnikInstance butterdroid = new ButterdroidBadnikInstance(new ObjectSpawn(
                0x180, 0x100, Sonic3kObjectIds.BUTTERDROID, 0, 0, false, 0));
        butterdroid.setServices(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = player(0x080, 0x100);

        butterdroid.update(0, sonic);
        butterdroid.update(1, sonic);
        butterdroid.update(2, sonic);

        assertEquals(4, butterdroid.getXVelocity(),
                "loc_8E0DA calls Find_SonicTails before Chase_Object");
        assertEquals(4, butterdroid.getYVelocity());
        assertFalse(butterdroid.isFacingLeft());
    }

    @Test
    void findSonicTailsUsesSignedWordDistanceAtCoordinateWrap() {
        AbstractObjectInstance.updateCameraBounds(0xFF00, 0x80, 0x10040, 0x160, 0);
        TestablePlayableSprite sidekick = player(0x7F00, 0x080);
        ButterdroidBadnikInstance butterdroid = new ButterdroidBadnikInstance(new ObjectSpawn(
                0xFFE0, 0x100, Sonic3kObjectIds.BUTTERDROID, 0, 0, false, 0));
        butterdroid.setServices(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = player(0x0020, 0x120);

        butterdroid.update(0, sonic);
        butterdroid.update(1, sonic);
        butterdroid.update(2, sonic);

        assertEquals(4, butterdroid.getYVelocity(),
                "Find_SonicTails subtracts 16-bit x_pos words; $FFE0-$0020 is a $40 wrapped distance");
        assertFalse(butterdroid.isFacingLeft(),
                "Find_SonicTails d0 is nonzero when the 16-bit x delta is negative, even across the word wrap");
    }

    @Test
    void chaseObjectComparesWrappedSixteenBitPositionsLikeRomCmpWord() {
        AbstractObjectInstance.updateCameraBounds(0, 0x80, 0x140, 0x160, 0);
        ButterdroidBadnikInstance butterdroid = new ButterdroidBadnikInstance(new ObjectSpawn(
                0x0040, 0x100, Sonic3kObjectIds.BUTTERDROID, 0, 0, false, 0));
        butterdroid.setServices(new TestObjectServices().withGameState(mock(GameStateManager.class)));
        TestablePlayableSprite player = player(0xFFE0, 0x100);

        butterdroid.update(0, player);
        butterdroid.update(1, player);
        setBadnikInt(butterdroid, "currentX", 0x10040);
        butterdroid.update(2, player);

        assertEquals(4, butterdroid.getXVelocity(),
                "Chase_Object uses cmp.w x_pos(a1),d4; $0040 is below $FFE0 as an unsigned word, "
                        + "so the first wrapped X chase step keeps positive acceleration");
    }

    @Test
    void chaseObjectStillUsesDeadTargetBecauseRomHasNoDeadGate() {
        putButterdroidOnScreen();
        ButterdroidBadnikInstance butterdroid = butterdroid();
        TestablePlayableSprite player = player(0x240, 0x120);
        player.setDead(true);

        butterdroid.update(0, player);
        butterdroid.update(1, player);
        butterdroid.update(2, player);

        assertEquals(4, butterdroid.getXVelocity(),
                "loc_8E0DA calls Find_SonicTails then Chase_Object without checking the player death flag");
        assertEquals(4, butterdroid.getYVelocity());
        assertFalse(butterdroid.isFacingLeft());
    }

    @Test
    void flipsTowardPlayerAndUsesRawAnimationLoop() {
        putButterdroidOnScreen();
        ButterdroidBadnikInstance butterdroid = butterdroid();
        TestablePlayableSprite player = player(0x100, 0x100);

        butterdroid.update(0, player);
        butterdroid.update(1, player);
        butterdroid.update(2, player);

        assertTrue(butterdroid.isFacingLeft());

        for (int frame = 3; frame <= 10; frame++) {
            butterdroid.update(frame, player);
        }

        assertEquals(2, butterdroid.getMappingFrame(),
                "after the first active tick advances to frame 1, Animate_Raw delay 7 advances to frame 2 eight active ticks later");
    }

    @Test
    void objWaitOffscreenSuppressesChaseAnimationAndCollisionUntilVisible() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        ButterdroidBadnikInstance butterdroid = butterdroid();
        TestablePlayableSprite player = player(0x240, 0x120);

        butterdroid.update(0, player);

        assertEquals(0, butterdroid.getXVelocity(),
                "Obj_WaitOffscreen returns before Butterdroid's Chase_Object routine while off-screen");
        assertEquals(0, butterdroid.getYVelocity());
        assertEquals(0, butterdroid.getMappingFrame());
        assertEquals(0, butterdroid.getCollisionFlags(),
                "Obj_WaitOffscreen runs before SetUp_ObjAttributesSlotted, so collision_flags remain clear");

        putButterdroidOnScreen();
        butterdroid.update(1, player);

        assertEquals(0, butterdroid.getXVelocity(),
                "the restored Butterdroid entry point runs on the next frame after the wait wrapper sees it");
        assertEquals(0, butterdroid.getCollisionFlags());

        butterdroid.update(2, player);

        assertEquals(0, butterdroid.getXVelocity(),
                "the first restored normal frame runs SetUp_ObjAttributesSlotted and returns before Chase_Object");
        assertEquals(0x17, butterdroid.getCollisionFlags());

        butterdroid.update(3, player);

        assertEquals(4, butterdroid.getXVelocity());
        assertEquals(4, butterdroid.getYVelocity());
        assertEquals(0x17, butterdroid.getCollisionFlags());
    }

    private static ButterdroidBadnikInstance butterdroid() {
        ButterdroidBadnikInstance butterdroid = new ButterdroidBadnikInstance(new ObjectSpawn(
                0x180, 0x100, Sonic3kObjectIds.BUTTERDROID, 0, 0, false, 0));
        butterdroid.setServices(new TestObjectServices().withGameState(mock(GameStateManager.class)));
        return butterdroid;
    }

    private static void putButterdroidOnScreen() {
        AbstractObjectInstance.updateCameraBounds(0x100, 0x80, 0x240, 0x160, 0);
    }

    private static TestablePlayableSprite player(int x, int y) {
        return new TestablePlayableSprite("sonic", (short) x, (short) y);
    }

    private static void setBadnikInt(ButterdroidBadnikInstance butterdroid, String fieldName, int value) {
        try {
            Field field = ButterdroidBadnikInstance.class.getSuperclass().getSuperclass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(butterdroid, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class MhzRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
        }
    }
}
