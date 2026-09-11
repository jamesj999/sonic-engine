package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzGiantWheelInstance {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void groundedInsideWheelClampsNormalDirectionToMinimumGroundSpeedOnly() {
        CnzGiantWheelInstance wheel = createWheel(0, 0x0F80, 0x0138);
        TestablePlayableSprite player = createGroundedPlayer(0x0F5F, 0x00F1, 0x02A2);
        player.setXSpeed((short) 0x02A2);

        wheel.update(0, player);

        assertEquals((short) 0x0400, player.getGSpeed());
        assertEquals((short) 0x02A2, player.getXSpeed());
        assertTrue(player.isStickToConvex());
        assertFalse(player.getPushing());
    }

    @Test
    void normalDirectionCapsHighGroundSpeed() {
        CnzGiantWheelInstance wheel = createWheel(0, 0x0200, 0x0200);
        TestablePlayableSprite player = createGroundedPlayer(0x0200, 0x0200, 0x1200);

        wheel.update(0, player);

        assertEquals((short) 0x0F00, player.getGSpeed());
    }

    @Test
    void flippedDirectionClampsNegativeGroundSpeedRange() {
        CnzGiantWheelInstance wheel = createWheel(1, 0x0200, 0x0200);
        TestablePlayableSprite slowPlayer = createGroundedPlayer(0x0200, 0x0200, -0x0100);

        wheel.update(0, slowPlayer);

        assertEquals((short) -0x0400, slowPlayer.getGSpeed());

        TestablePlayableSprite fastPlayer = createGroundedPlayer(0x0200, 0x0200, -0x1200);
        wheel.update(1, fastPlayer);

        assertEquals((short) -0x0F00, fastPlayer.getGSpeed());
    }

    @Test
    void airborneInsideWheelClearsAttachmentButDoesNotClampOrClearConvex() {
        CnzGiantWheelInstance wheel = createWheel(0, 0x0200, 0x0200);
        TestablePlayableSprite player = createGroundedPlayer(0x0200, 0x0200, 0x0200);
        wheel.update(0, player);
        assertTrue(player.isStickToConvex());

        player.setAir(true);
        player.setGSpeed((short) 0x0200);
        wheel.update(1, player);

        assertEquals((short) 0x0200, player.getGSpeed());
        assertTrue(player.isStickToConvex());
    }

    @Test
    void leavingWheelClearsConvexOnlyWhenPreviouslyAttached() {
        CnzGiantWheelInstance wheel = createWheel(0, 0x0200, 0x0200);
        TestablePlayableSprite player = createGroundedPlayer(0x0200, 0x0200, 0x0400);
        wheel.update(0, player);
        assertTrue(player.isStickToConvex());

        player.setCentreX((short) 0x0300);
        player.setCentreY((short) 0x0300);
        wheel.update(1, player);

        assertFalse(player.isStickToConvex());
    }

    @Test
    void nativeP2LandingInsideWheelReceivesRomMinimumGroundSpeed() {
        CnzGiantWheelInstance wheel = createWheel(0, 0x2980, 0x0538);
        TestablePlayableSprite sonic = createGroundedPlayer(0x2800, 0x0400, 0);
        TestablePlayableSprite tails = createGroundedPlayer(0x298B, 0x04F0, -0x015C);
        wheel.setServices(new TestObjectServices() {
            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> sonic, () -> List.of(tails));
            }

            @Override
            public List<PlayableEntity> sidekicks() {
                return List.of(tails);
            }
        });

        wheel.update(0, sonic);

        assertEquals((short) 0x0400, tails.getGSpeed(),
                "Obj_CNZGiantWheel runs sub_328E8 for Player_2 after Player_1");
        assertTrue(tails.isStickToConvex());
    }

    private static CnzGiantWheelInstance createWheel(int renderFlags, int x, int y) {
        return new CnzGiantWheelInstance(
                new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_GIANT_WHEEL, 0, renderFlags, false, 0));
    }

    private static TestablePlayableSprite createGroundedPlayer(int x, int y, int gSpeed) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) x);
        player.setCentreY((short) y);
        player.setAir(false);
        player.setGSpeed((short) gSpeed);
        player.setPushing(true);
        return player;
    }
}
