package com.openggf.sprites.managers;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.sprites.playable.Tails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SingletonResetExtension.class)
class TestSpriteManagerMainTailsTailsDispatch {

    @Test
    void postDynamicFixedSlotAdvancesMainPlayableTailsInS3k() {
        SpriteManager manager = new SpriteManager();
        Tails main = configuredMainTails();
        TailsTailsController controller = new TailsTailsController(main, null, true);
        main.setAnimationId(0x05); // TailsAni_Wait -> Obj_Tails_Tail swish
        main.setTailsTailsController(controller);
        manager.addSprite(main);

        manager.advanceTailsTailsAfterObjectExecution();

        TailsTailsController.RewindState state = controller.captureRewindState();
        assertEquals(1, state.currentAnim(), "main Tails must select the S3K idle tail animation");
        assertEquals(0x22, state.mappingFrame(), "main Tails must publish the first idle tail frame");
    }

    @Test
    void postDynamicFixedSlotAdvancesMainPlayableTailsDuringFlightInS3k() {
        SpriteManager manager = new SpriteManager();
        Tails main = configuredMainTails();
        TailsTailsController controller = new TailsTailsController(main, null, true);
        main.setAnimationId(0x20); // TailsAni_Fly -> Obj_Tails_Tail Fly1
        main.setTailsTailsController(controller);
        manager.addSprite(main);

        manager.advanceTailsTailsAfterObjectExecution();

        TailsTailsController.RewindState state = controller.captureRewindState();
        assertEquals(0xB, state.currentAnim(), "main Tails must select the S3K flight tail animation");
        assertEquals(0x27, state.mappingFrame(), "main Tails must publish the first flight tail frame");
    }

    @Test
    void initialS3kFixedSlotAdvancesMainPlayableTails() {
        SpriteManager manager = new SpriteManager();
        Tails main = configuredMainTails();
        TailsTailsController controller = new TailsTailsController(main, null, true);
        main.setAnimationId(0x05);
        main.setTailsTailsController(controller);
        manager.addSprite(main);

        manager.processInitialTailsFixedSlot();

        assertEquals(1, controller.captureRewindState().currentAnim(),
                "S3K slot 97 must initialize the main Tails appendage in solo Tails mode");
    }

    @Test
    void initialS3kFixedSlotKeepsTheRomPlayerTwoFallback() {
        SpriteManager manager = new SpriteManager();
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestablePlayableSprite firstSidekick = new TestablePlayableSprite("knuckles", (short) 0, (short) 0);
        TestablePlayableSprite laterSidekick = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        TailsTailsController laterController = new TailsTailsController(laterSidekick, null, true);
        laterSidekick.setAnimationId(0x05);
        laterSidekick.setTailsTailsController(laterController);
        firstSidekick.setCpuControlled(true);
        laterSidekick.setCpuControlled(true);
        manager.addSprite(main);
        manager.addSprite(firstSidekick, "knuckles");
        manager.addSprite(laterSidekick, "tails");

        manager.processInitialTailsFixedSlot();

        assertEquals(0, laterController.captureRewindState().currentAnim(),
                "slot 97 must not scan past the native Player 2 sidekick");
    }

    /**
     * configuredMainTails() writes MAIN_CHARACTER_CODE into the configuration
     * singleton, which SingletonResetExtension does not restore; a later class
     * that loads a level in @BeforeAll (TestInitialPlayableProcessSpritesPass)
     * would otherwise spawn Tails as the main character.
     */
    @AfterEach
    void restoreConfiguration() {
        TestEnvironment.resetAll();
    }

    private static Tails configuredMainTails() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE, "tails");
        return new Tails("tails", (short) 0, (short) 0);
    }
}
