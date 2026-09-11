package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestMonkeyDudeBadnikInstance {

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void collisionAnchorFollowsFacingOffsetWhenMonkeyTurnsRightAndBack() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        MonkeyDudeBadnikInstance monkey = new MonkeyDudeBadnikInstance(
                new ObjectSpawn(100, 100, 0x8E, 0x04, 0, false, 0));
        TestPlayer player = player(140, 100);

        runVisibleInitFrames(monkey, player);
        assertEquals(100, monkey.getCollisionX(), "Initial left-facing collision anchor");

        monkey.updateMovement(0, player);
        assertEquals(132, monkey.getCollisionX(),
                "Collision anchor must follow the rendered body when MonkeyDude turns right");

        player.setCentreX((short) 60);
        monkey.updateMovement(0, player);
        assertEquals(100, monkey.getCollisionX(),
                "Collision anchor must return with the rendered body when MonkeyDude turns left");
    }

    @Test
    void collisionAnchorFollowsFacingOffsetWhenMonkeyTurnsLeftAndBack() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        MonkeyDudeBadnikInstance monkey = new MonkeyDudeBadnikInstance(
                new ObjectSpawn(100, 100, 0x8E, 0x04, 1, false, 0));
        TestPlayer player = player(60, 100);

        runVisibleInitFrames(monkey, player);
        assertEquals(100, monkey.getCollisionX(), "Initial right-facing collision anchor");

        monkey.updateMovement(0, player);
        assertEquals(68, monkey.getCollisionX(),
                "Collision anchor must follow the rendered body when MonkeyDude turns left");

        player.setCentreX((short) 140);
        monkey.updateMovement(0, player);
        assertEquals(100, monkey.getCollisionX(),
                "Collision anchor must return with the rendered body when MonkeyDude turns right");
    }

    private static void runVisibleInitFrames(MonkeyDudeBadnikInstance monkey, TestPlayer player) {
        monkey.updateMovement(0, player);
        monkey.updateMovement(0, player);
    }

    private static TestPlayer player(int x, int y) {
        TestPlayer player = new TestPlayer();
        player.setCentreX((short) x);
        player.setCentreY((short) y);
        return player;
    }

    private static final class TestPlayer extends AbstractPlayableSprite implements PlayableEntity {
        private TestPlayer() {
            super("test", (short) 0, (short) 0);
        }

        @Override
        public void draw() {
        }

        @Override
        protected void defineSpeeds() {
        }

        @Override
        protected void createSensorLines() {
        }
    }
}
