package com.openggf.game.sonic1.objects;

import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1PoleThatBreaksObjectInstance {

    private static final TouchResponseResult TOUCH_RESULT =
            new TouchResponseResult(0x21, 0x20, 0x20, TouchCategory.SPECIAL);

    @Test
    public void touchResponseProfilePreservesContinuousSingleRegionSpecialTouch() {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 4);

        TouchResponseProfile profile = pole.getTouchResponseProfile(false);

        assertEquals(profile, pole.getTouchResponseProfile());
        assertEquals(profile, pole.getTouchResponseProfile(true));
        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertTrue(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0, profile.shieldReactionFlags());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    public void grabsPlayerFromRightAndLocksToHangState() {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 4);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player);

        assertTrue(player.isObjectControlled());
        assertEquals(200 + 0x14, player.getCentreX());
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(Sonic1AnimationIds.HANG.id(), player.getAnimationId());
        assertEquals(Direction.RIGHT, player.getDirection());
    }

    @Test
    public void grabAndClimbPreservePlayerSubpixels() {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 0);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);
        player.setSubpixelRaw(0x6400, 0x9000);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player);

        assertEquals(200 + 0x14, player.getCentreX());
        assertEquals(0x6400, player.getXSubpixelRaw(),
                "Obj0B .grab uses move.w d0,obX(a1), preserving x_sub");
        assertEquals(0x9000, player.getYSubpixelRaw());

        player.setDirectionalInputPressed(true, false, false, false);
        pole.update(2, player);

        assertEquals(319, player.getCentreY());
        assertEquals(0x6400, player.getXSubpixelRaw());
        assertEquals(0x9000, player.getYSubpixelRaw(),
                "Obj0B .moveup uses subq.w/move.w on obY(a1), preserving y_sub");
    }

    @Test
    public void subtypeZeroNeverAutoBreaksWhileGrabbed() {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 0);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player);
        for (int i = 2; i <= 240; i++) {
            pole.update(i, player);
        }

        assertTrue(player.isObjectControlled());
        assertEquals(0x61, pole.getCollisionFlags());
    }

    @Test
    public void subtypeOneBreaksAfterSixtyFrames() throws Exception {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 1);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player); // grab (poleTime = 60)
        // Decrement 59 times (poleTime 60 -> 1), still grabbed
        for (int i = 2; i <= 60; i++) {
            pole.update(i, player);
        }
        assertTrue(player.isObjectControlled());
        // 60th decrement: poleTime 1 -> 0, break + release
        pole.update(61, player);

        assertFalse(player.isObjectControlled());
        assertEquals(0, pole.getCollisionFlags());
        assertEquals(1, getPrivateInt(pole, "mappingFrame"));
    }

    @Test
    public void jumpPressReleasesPole() throws Exception {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 0);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player); // grab
        player.setJumpInputPressed(true);
        pole.update(2, player); // edge-trigger release

        assertFalse(player.isObjectControlled());
        assertEquals(0, pole.getCollisionFlags());
        assertEquals(1, getPrivateInt(pole, "mappingFrame"));
    }

    @Test
    public void forcedJumpPressReleasesPole() throws Exception {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 0);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player); // grab
        player.setForcedJumpPress(true);
        pole.update(2, player); // demo-style edge-trigger release

        assertFalse(player.isObjectControlled());
        assertEquals(0, pole.getCollisionFlags());
        assertEquals(1, getPrivateInt(pole, "mappingFrame"));
    }

    @Test
    public void jumpReleaseDoesNotSuppressNextPlayableFrameJumpEdge() {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 0);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player); // grab
        player.setJumpInputPressed(true);
        pole.update(2, player); // release

        assertFalse(player.consumeSuppressNextJumpPress(),
                "Pole release happens after Sonic's routine, so the next playable frame must still see the jump edge");
    }

    @Test
    public void forcedInputMaskMovesPlayerOnPole() {
        // Simulates demo playback: no keyboard input, only forcedInputMask (demo data).
        // Before the fix, isUpPressed()/isDownPressed() only checked raw keyboard state,
        // so demo up/down input was invisible to the pole.
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 0);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player); // grab

        int startY = player.getCentreY();

        // Forced UP input (demo data) â€” no keyboard input set
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        for (int i = 2; i <= 10; i++) {
            pole.update(i, player);
        }
        assertTrue(player.getCentreY() < startY, "Forced UP should move player upward on pole");

        int afterUpY = player.getCentreY();

        // Forced DOWN input (demo data)
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_DOWN);
        for (int i = 11; i <= 20; i++) {
            pole.update(i, player);
        }
        assertTrue(player.getCentreY() > afterUpY, "Forced DOWN should move player downward on pole");

        player.clearForcedInputMask();
    }

    @Test
    public void upDownInputClampsToRomRange() {
        Sonic1PoleThatBreaksObjectInstance pole = createPole(200, 320, 0);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 240);
        player.setCentreY((short) 320);

        pole.onTouchResponse(player, TOUCH_RESULT, 1);
        pole.update(1, player); // grab

        player.setDirectionalInputPressed(true, false, false, false);
        for (int i = 2; i <= 80; i++) {
            pole.update(i, player);
        }
        assertEquals(320 - 0x18, player.getCentreY());

        player.setDirectionalInputPressed(false, true, false, false);
        for (int i = 81; i <= 180; i++) {
            pole.update(i, player);
        }
        assertEquals(320 + 0x0C, player.getCentreY());
    }

    private static Sonic1PoleThatBreaksObjectInstance createPole(int x, int y, int subtype) {
        Sonic1PoleThatBreaksObjectInstance pole = new Sonic1PoleThatBreaksObjectInstance(
                new ObjectSpawn(x, y, 0x0B, subtype, 0, false, 0));
        pole.setServices(new TestObjectServices());
        return pole;
    }

    private static int getPrivateInt(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

}

