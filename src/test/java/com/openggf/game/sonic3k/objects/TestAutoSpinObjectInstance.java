package com.openggf.game.sonic3k.objects;

import com.openggf.game.GroundMode;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAutoSpinObjectInstance {

    @Test
    void nativeP2QuerySidekickCanTriggerAutoSpinWhenRawSidekickListIsEmpty() {
        AutoSpinObjectInstance trigger = new AutoSpinObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.AUTO_SPIN, 0, 0, false, 0));
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0080, (short) 0x0100);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x00F0, (short) 0x0100);
        trigger.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        trigger.update(0, main);
        sidekick.setCentreX((short) 0x0100);
        trigger.update(1, main);

        assertTrue(sidekick.getPinballMode(),
                "Obj_AutoSpin has only native P1/P2 crossing flags, so P2 must come from ObjectPlayerQuery NATIVE_P1_P2");
        assertTrue(sidekick.getRolling());
        assertEquals((short) 0x0580, sidekick.getGSpeed());
    }

    @Test
    void verticalAutoSpinPreservesXPositionWhenForcedRollChangesWallModeWidth() {
        AutoSpinObjectInstance trigger = new AutoSpinObjectInstance(
                new ObjectSpawn(0x2AB8, 0x0590, Sonic3kObjectIds.AUTO_SPIN, 0x04, 0, false, 0));
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x2AB3, (short) 0x058E);
        main.setGroundMode(GroundMode.RIGHTWALL);
        main.setCentreXPreserveSubpixel((short) 0x2AB3);
        main.setCentreYPreserveSubpixel((short) 0x058E);
        trigger.setServices(new QueryOnlyPlayerServices(main, List.of()));

        trigger.update(0, main);
        main.setCentreYPreserveSubpixel((short) 0x059B);
        trigger.update(1, main);

        assertTrue(main.getRolling());
        assertEquals((short) 0x2AB3, main.getCentreX(),
                "ROM Obj_AutoSpin changes radii and y_pos only; x_pos must not move when wall-mode width shrinks");
        assertEquals((short) 0x05A0, main.getCentreY());
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }
}
