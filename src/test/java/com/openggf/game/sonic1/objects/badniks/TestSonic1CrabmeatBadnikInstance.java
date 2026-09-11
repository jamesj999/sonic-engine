package com.openggf.game.sonic1.objects.badniks;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1CrabmeatBadnikInstance {

    @Test
    void firstActionFrameObservesClearedRenderFlagFromInvisibleInit() throws Exception {
        AlwaysOnScreenCrabmeat crab = new AlwaysOnScreenCrabmeat();
        setPrivate(crab, "initialized", true);

        crab.updateMovement(0, null);

        assertEquals(1, getPrivateInt(crab, "secondaryState"),
                "The first action frame starts the scuttle routine");
        assertEquals(0, getPrivateInt(crab, "crabMode") & 0x02,
                "Crab_Main does not display, so its next obRender test must take the off-screen path");
    }

    @Test
    void horizontallyAlignedButVerticallyOffscreenDoesNotEnterFirePhase() throws Exception {
        HorizontallyVisibleCrabmeat crab = new HorizontallyVisibleCrabmeat();
        setPrivate(crab, "initialized", true);
        setPrivate(crab, "renderFlagClearFromInvisibleInit", false);
        setPrivate(crab, "crabMode", 0x01);

        crab.updateMovement(0, null);

        assertEquals(1, getPrivateInt(crab, "secondaryState"),
                "An off-screen Crabmeat resumes scuttling when its wait expires");
        assertEquals(0, getPrivateInt(crab, "crabMode") & 0x02,
                "ROM obRender includes the BuildSprites Y gate, not only camera X");
    }

    private static void setPrivate(Object target, String name, Object value) throws Exception {
        Field field = Sonic1CrabmeatBadnikInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int getPrivateInt(Object target, String name) throws Exception {
        Field field = Sonic1CrabmeatBadnikInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static final class AlwaysOnScreenCrabmeat extends Sonic1CrabmeatBadnikInstance {
        private AlwaysOnScreenCrabmeat() {
            super(new ObjectSpawn(0x100, 0x100, 0x1F, 0, 0, false, 0));
        }

        @Override
        protected boolean isOnScreenX() {
            return true;
        }

        @Override
        protected boolean isWithinRenderSpriteBounds(int xMargin, int yMargin) {
            return true;
        }
    }

    private static final class HorizontallyVisibleCrabmeat extends Sonic1CrabmeatBadnikInstance {
        private HorizontallyVisibleCrabmeat() {
            super(new ObjectSpawn(0x100, 0x100, 0x1F, 0, 0, false, 0));
        }

        @Override
        protected boolean isOnScreenX() {
            return true;
        }

        @Override
        protected boolean isWithinRenderSpriteBounds(int xMargin, int yMargin) {
            return false;
        }
    }
}
