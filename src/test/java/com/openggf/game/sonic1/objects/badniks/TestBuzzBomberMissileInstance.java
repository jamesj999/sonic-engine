package com.openggf.game.sonic1.objects.badniks;

import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestBuzzBomberMissileInstance {

    @Test
    public void missileStaysHarmlessForThirtyCreationFrameTicks() {
        Sonic1BuzzBomberMissileInstance missile =
                new Sonic1BuzzBomberMissileInstance(0, 0, 0x200, 0x200, false, -1);
        missile.setServices(new StubObjectServices());

        assertEquals(0, missile.getCollisionFlags(), "Fresh missile should start harmless");

        for (int i = 0; i < 30; i++) {
            missile.update(i + 1, null);
        }

        assertEquals(0, missile.getCollisionFlags(),
                "Missile should still be in its flare window after 30 execution ticks");

        missile.update(31, null);

        assertEquals(0, missile.getCollisionFlags(),
                "afRoutine should only advance to routine 4; collision is still clear");

        missile.update(32, null);

        assertEquals(0x87, missile.getCollisionFlags(),
                "Missile should arm when routine 4 executes on the following tick");
    }
}
