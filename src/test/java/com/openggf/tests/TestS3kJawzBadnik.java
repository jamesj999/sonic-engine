package com.openggf.tests;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.HczHarmfulExplosionObjectInstance;
import com.openggf.game.sonic3k.objects.badniks.JawzBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchResponseResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestS3kJawzBadnik {

    @Test
    public void jawzInitializesVelocityTowardPlayerOnFirstVisibleFrame() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);

        JawzBadnikInstance jawz = new JawzBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.JAWZ, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 80);

        jawz.update(0, player); // Obj_WaitOffscreen placeholder dispatch
        assertEquals(160, jawz.getX(), "Jawz should not move on the initialization frame");

        jawz.refreshPostCameraRenderState(); // Render_Sprites sets render_flags bit 7
        jawz.update(1, player); // Obj_WaitOffscreen restores the saved entry point
        assertEquals(160, jawz.getX(), "Jawz initialization should not move the object");

        jawz.update(2, player); // Obj_Jawz initializes velocity
        assertEquals(160, jawz.getX(), "Jawz velocity initialization should not move the object");

        jawz.update(3, player);
        assertEquals(158, jawz.getX(), "Jawz should move toward the player on the next frame");
        assertEquals(1, readMappingFrame(jawz), "Jawz should advance to the second animation frame after moving");
    }

    @Test
    public void jawzTracksRightWhenPlayerIsToTheRight() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);

        JawzBadnikInstance jawz = new JawzBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.JAWZ, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 240);

        jawz.update(0, player);
        jawz.refreshPostCameraRenderState();
        jawz.update(1, player);
        jawz.update(2, player);
        jawz.update(3, player);

        assertEquals(162, jawz.getX(), "Jawz should move right when the player is on the right");
    }

    @Test
    public void jawzResumesSetupWhenAnOffscreenPlaceholderBecomesVisible() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);

        JawzBadnikInstance jawz = new JawzBadnikInstance(
                new ObjectSpawn(0x400, 100, Sonic3kObjectIds.JAWZ, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x500);

        jawz.update(0, player);
        assertEquals(0x400, jawz.getX(), "The offscreen placeholder must remain stationary");

        AbstractObjectInstance.updateCameraBounds(0x380, 0, 0x4BF, 223, 0);
        jawz.refreshPostCameraRenderState();
        jawz.update(1, player);
        assertEquals(0x400, jawz.getX(),
                "Restoring the saved Obj_Jawz entry consumes its own dispatch");

        jawz.update(2, player);
        assertEquals(0x400, jawz.getX(),
                "The restored Obj_Jawz entry initializes velocity without moving");

        jawz.update(3, player);
        assertEquals(0x402, jawz.getX(),
                "The resumed Jawz routine must execute MoveSprite2 on the following dispatch");
    }

    @Test
    public void jawzUsesNativeSpecialPropertyTouchAccumulation() {
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 160, (short) 100);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 160, (short) 100);
        JawzBadnikInstance jawz = new JawzBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.JAWZ, 0, 0, false, 0));
        jawz.setServices(new TestObjectServices() {
            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> sonic, () -> java.util.List.of(tails));
            }
        });
        TouchResponseResult result = new TouchResponseResult(
                0x17, 8, 8, TouchCategory.SPECIAL, 0);

        jawz.onTouchResponse(sonic, result, 1);
        assertEquals(1, jawz.getCollisionProperty());
        jawz.onTouchResponse(tails, result, 1);

        assertEquals(3, jawz.getCollisionProperty(),
                "Touch_Special adds one for P1 and two for P2, selecting P2 when both overlap");
        assertEquals(0xD7, jawz.getCollisionFlags());
        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY,
                jawz.getTouchResponseProfile().categoryDecodeMode());
        assertTrue(jawz.requiresContinuousTouchCallbacks());
    }

    @Test
    public void vulnerablePlayerContactReplacesJawzWithHarmfulHczExplosion() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 319, 223, 0);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 160, (short) 100);
        ObjectManager[] managerRef = new ObjectManager[1];
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return managerRef[0];
            }

            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> sonic, List::of);
            }
        };
        ObjectManager manager = new ObjectManager(
                List.of(), null, -1, null, null, null, null, services);
        managerRef[0] = manager;
        JawzBadnikInstance jawz = manager.createDynamicObject(() -> new JawzBadnikInstance(
                new ObjectSpawn(160, 100, Sonic3kObjectIds.JAWZ, 0, 0, false, 0)));

        jawz.update(0, sonic);
        jawz.refreshPostCameraRenderState();
        jawz.update(1, sonic);
        jawz.update(2, sonic);
        jawz.onTouchResponse(sonic,
                new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL, 0), 3);
        jawz.update(3, sonic);

        assertTrue(jawz.isDestroyed());
        List<HczHarmfulExplosionObjectInstance> explosions = manager.getActiveObjects().stream()
                .filter(HczHarmfulExplosionObjectInstance.class::isInstance)
                .map(HczHarmfulExplosionObjectInstance.class::cast)
                .toList();
        assertEquals(1, explosions.size(),
                "ROM creates one HCZEndBoss_ExplosionChild before deleting Jawz");
        assertEquals(jawz.getX(), explosions.getFirst().getX(),
                "Jawz moves before checking collision_property and creating the child");
        assertEquals(jawz.getY(), explosions.getFirst().getY());
        assertEquals(jawz.getSlotIndex() + 1, explosions.getFirst().getSlotIndex(),
                "CreateChild1_Normal allocates the next free SST slot after Jawz");
    }

    private static int readMappingFrame(JawzBadnikInstance jawz) {
        try {
            Field field = jawz.getClass().getSuperclass().getDeclaredField("mappingFrame");
            field.setAccessible(true);
            return field.getInt(jawz);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read Jawz mapping frame", e);
        }
    }
}
