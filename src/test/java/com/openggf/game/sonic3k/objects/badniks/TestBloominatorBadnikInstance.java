package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestBloominatorBadnikInstance {
    private static final ObjectSpawn SPAWN =
            new ObjectSpawn(0x0120, 0x0100, 0x8C, 0, 0, false, 0);

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 512, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void waitOffscreenUsesPlaceholderDispatchBeforeEnablingCollision() {
        BloominatorBadnikInstance bloominator = new BloominatorBadnikInstance(SPAWN);
        TestablePlayableSprite player = player();

        assertEquals(0x0C, bloominator.getOnScreenHalfWidth());
        assertEquals(0x18, bloominator.getOnScreenHalfHeight());
        assertEquals(0, bloominator.getCollisionFlags());

        bloominator.update(0, player);
        assertEquals(0, bloominator.getCollisionFlags(),
                "restoring the saved operation pointer consumes one dispatch");

        bloominator.update(1, player);
        assertEquals(0x23, bloominator.getCollisionFlags(),
                "ObjDat collision activates when the native init dispatch completes");
    }

    @Test
    void firstProjectileFiresWhenRawAnimationLoadsStepThree() throws Exception {
        BloominatorBadnikInstance bloominator = new BloominatorBadnikInstance(SPAWN);
        bloominator.setServices(new StubObjectServices());
        TestablePlayableSprite player = player();

        for (int frame = 0; frame <= 48; frame++) {
            bloominator.update(frame, player);
        }
        assertEquals(0, shotCount(bloominator),
                "animation delays must elapse before script offset 6 is loaded");

        bloominator.update(49, player);

        assertEquals(1, shotCount(bloominator));
        S3kBadnikProjectileInstance projectile = new S3kBadnikProjectileInstance(
                SPAWN, "", 0, 0, 0, 0, 0, 0, 0, 0, false);
        assertTrue(projectile.usesCurrentTouchResponseState());
    }

    private static TestablePlayableSprite player() {
        return new TestablePlayableSprite("sonic", (short) 0x0120, (short) 0x0100);
    }

    private static int shotCount(BloominatorBadnikInstance bloominator) throws Exception {
        Field field = BloominatorBadnikInstance.class.getDeclaredField("shotToggleCounter");
        field.setAccessible(true);
        return field.getInt(bloominator);
    }
}
