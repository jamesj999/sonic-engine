package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TestMasherBadnikInstance {

    @Test
    void movementMatchesRomTraceWindowBeforeBounce() {
        MasherBadnikInstance masher = newMasher();
        AbstractPlayableSprite sonic = idlePlayer();

        List<Integer> yPositions = new ArrayList<>();
        for (int frame = 0; frame <= 42; frame++) {
            masher.update(frame, sonic);
            if (frame >= 30) {
                yPositions.add(masher.getY());
            }
        }

        assertEquals(List.of(
                        0x027F, 0x027E, 0x027D, 0x027C, 0x027B, 0x027B, 0x027A,
                        0x0279, 0x0279, 0x0279, 0x0278, 0x0278, 0x0278),
                yPositions,
                "Obj5C should follow ObjectMove's 32-bit fixed-point arc seen in the EHZ trace");
    }

    @Test
    void bouncePreservesSubpixelCarryIntoNextJump() {
        MasherBadnikInstance masher = newMasher();
        AbstractPlayableSprite sonic = idlePlayer();

        List<Integer> yPositions = new ArrayList<>();
        for (int frame = 0; frame <= 90; frame++) {
            masher.update(frame, sonic);
            if (frame >= 86) {
                yPositions.add(masher.getY());
            }
        }

        assertEquals(List.of(0x02D0, 0x02CB, 0x02C6, 0x02C2, 0x02BD),
                yPositions,
                "Obj5C's move.w to y_pos preserves y_sub when it clamps at the bottom");
    }

    @Test
    void hydrateRestoresFixedPointPhaseAndInitialY() {
        MasherBadnikInstance masher = newMasher();
        masher.hydrateFromRomSnapshot(new RomObjectSnapshot(
                Map.of(),
                Map.of(
                        0x08, 0x0578,
                        0x0C, 0x02D0,
                        0x0E, 0xB800,
                        0x12, 0xFB00,
                        0x30, 0x02D0)));

        masher.update(87, idlePlayer());

        assertEquals(0x02CB, masher.getY(),
                "Obj5C hydration must restore y_sub, y_vel, and initial y position for trace replay");
    }

    @Test
    void exposesStandardEnemyTouchResponseProfile() {
        MasherBadnikInstance masher = newMasher();

        assertEquals(TouchResponseProfile.standardEnemy(), masher.getTouchResponseProfile());
        assertEquals(0x09, masher.getCollisionFlags(),
                "Profile adoption must not replace dynamic collision flag ownership");
    }

    private static MasherBadnikInstance newMasher() {
        MasherBadnikInstance masher = new MasherBadnikInstance(
                new ObjectSpawn(0x0578, 0x02D0, 0x5C, 0, 0, false, 0));
        masher.setServices(new TestObjectServices());
        return masher;
    }

    private static AbstractPlayableSprite idlePlayer() {
        return mock(AbstractPlayableSprite.class);
    }
}
