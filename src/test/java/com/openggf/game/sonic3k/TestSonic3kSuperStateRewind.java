package com.openggf.game.sonic3k;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.PhysicsProfile;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.SuperState;
import com.openggf.sprites.playable.SuperStateController;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestSonic3kSuperStateRewind {
    private Sonic sonic;
    private Sonic3kSuperStateController controller;

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
        GameServices.level().resetLevelGamestate(GameModuleRegistry.getCurrent().createLevelState());
        for (int i = 0; i < 7; i++) {
            GameServices.gameState().markEmeraldCollected(i);
        }
        sonic = new Sonic("sonic", (short) 0, (short) 0);
        sonic.setRingCount(50);
        GameServices.sprites().clearAllSprites();
        GameServices.sprites().addSprite(sonic, "sonic");
        controller = new Sonic3kSuperStateController(sonic);
        sonic.setSuperStateController(controller);
    }

    @Test
    void midTransformationControllerTimingRoundTripsThroughPlayerSnapshot() {
        assertTrue(controller.activateFromAirAbility());
        for (int i = 0; i < 5; i++) {
            controller.update();
        }
        PerObjectRewindSnapshot snapshot = sonic.captureRewindState();
        int ringsAtSnapshot = sonic.getRingCount();
        SuperStateController.RewindState expected =
                snapshot.playerExtra().controllerState().superStateState();
        assertNotNull(expected);

        for (int i = 0; i < 7; i++) {
            controller.update();
        }
        sonic.restoreRewindState(snapshot);

        assertEquals(expected, controller.captureRewindState());
        assertEquals(SuperState.TRANSFORMING, controller.getState());
        assertEquals(ringsAtSnapshot, sonic.getRingCount(),
                "restore must preserve the captured ring drain without replaying it");
    }

    @Test
    void activeSuperRestoreReconcilesPhysicsWithoutReplayingActivation() {
        assertTrue(controller.activateFromAirAbility());
        for (int i = 0; i < 30; i++) {
            controller.update();
        }
        assertEquals(SuperState.SUPER, controller.getState());
        PerObjectRewindSnapshot snapshot = sonic.captureRewindState();
        int ringsAtSnapshot = sonic.getRingCount();

        controller.debugDeactivate();
        assertEquals(PhysicsProfile.SONIC_2_SONIC, sonic.getPhysicsProfile());
        sonic.restoreRewindState(snapshot);

        assertEquals(SuperState.SUPER, controller.getState());
        assertEquals(PhysicsProfile.SONIC_3K_SUPER_SONIC, sonic.getPhysicsProfile());
        assertEquals(ringsAtSnapshot, sonic.getRingCount());
    }

    @Test
    void knucklesRestoreUsesCharacterSpecificNormalAndSuperProfiles() {
        Knuckles knuckles = new Knuckles("knuckles", (short) 0, (short) 0);
        knuckles.setRingCount(50);
        GameServices.sprites().clearAllSprites();
        GameServices.sprites().addSprite(knuckles, "knuckles");
        Sonic3kSuperStateController knucklesController = new Sonic3kSuperStateController(knuckles);
        knuckles.setSuperStateController(knucklesController);

        assertTrue(knucklesController.activateFromAirAbility());
        for (int i = 0; i < 30; i++) {
            knucklesController.update();
        }
        PerObjectRewindSnapshot snapshot = knuckles.captureRewindState();
        assertEquals(PhysicsProfile.SONIC_3K_SUPER_KNUCKLES, knuckles.getPhysicsProfile());

        knucklesController.debugDeactivate();
        assertEquals(PhysicsProfile.SONIC_3K_KNUCKLES, knuckles.getPhysicsProfile());
        knuckles.restoreRewindState(snapshot);

        assertEquals(SuperState.SUPER, knucklesController.getState());
        assertEquals(PhysicsProfile.SONIC_3K_SUPER_KNUCKLES, knuckles.getPhysicsProfile());
    }

    @Test
    void normalUnderwaterPhysicsSurvivesRoundTripFromSuperState() {
        sonic.setInWater(true);
        PerObjectRewindSnapshot normalUnderwater = sonic.captureRewindState();
        assertEquals(0x380, sonic.getJump());

        assertTrue(controller.activateFromAirAbility());
        for (int i = 0; i < 30; i++) {
            controller.update();
        }
        sonic.restoreRewindState(normalUnderwater);

        assertEquals(SuperState.NORMAL, controller.getState());
        assertEquals(0x380, sonic.getJump());
        assertEquals(PhysicsProfile.SONIC_2_SONIC, sonic.getPhysicsProfile());
    }

    @Test
    void normalSpeedShoesPhysicsSurvivesRoundTripFromSuperState() {
        sonic.giveSpeedShoes();
        PerObjectRewindSnapshot normalWithShoes = sonic.captureRewindState();
        short expectedMax = sonic.getMax();

        assertTrue(controller.activateFromAirAbility());
        for (int i = 0; i < 30; i++) {
            controller.update();
        }
        sonic.restoreRewindState(normalWithShoes);

        assertEquals(SuperState.NORMAL, controller.getState());
        assertEquals(expectedMax, sonic.getMax());
        assertEquals(PhysicsProfile.SONIC_2_SONIC, sonic.getPhysicsProfile());
    }
}
