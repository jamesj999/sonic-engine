package com.openggf.sprites.playable;

import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_3K)
class TestTailsCarryController {
    private static SharedLevel sharedLevel;
    private HeadlessTestFixture fixture;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
    }

    @AfterAll
    static void disposeLevel() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
    }

    @Test
    void spriteManagerFindsUniqueHumanAmongManyCpuSidekicks() {
        SpriteManager sprites = GameServices.sprites();
        AbstractPlayableSprite main = fixture.sprite();
        AbstractPlayableSprite cameraDecoy = sprites.getSidekicks().get(0);
        for (int i = 0; i < 4; i++) {
            TestablePlayableSprite cpu = new TestablePlayableSprite("cpu-" + i, (short) 0, (short) 0);
            cpu.setCpuControlled(true);
            sprites.addSprite(cpu, "tails");
        }

        assertSame(main, sprites.getMainPlayable());
        assertNotSame(cameraDecoy, sprites.getMainPlayable());
    }

    @Test
    void spriteManagerPrefersConfiguredMainOverManualPlayerTwo() {
        SpriteManager sprites = GameServices.sprites();
        AbstractPlayableSprite configuredMain = fixture.sprite();
        TestablePlayableSprite playerTwo = new TestablePlayableSprite("second-human", (short) 0, (short) 0);
        playerTwo.setCpuControlled(false);
        sprites.addSprite(playerTwo);

        assertSame(configuredMain, sprites.getMainPlayable());
    }

    @Test
    void spriteManagerPrefersSessionMainOverConfiguredMain() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        var module = SessionManager.getCurrentWorldSession().getGameModule();
        SessionManager.openGameplaySession(module,
                SaveSessionContext.noSave("s3k", new SelectedTeam("knuckles", List.of()), 0, 0));
        SpriteManager sprites = new SpriteManager(SonicConfigurationService.getInstance());
        AbstractPlayableSprite configuredMain = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        AbstractPlayableSprite sessionMain = new TestablePlayableSprite("knuckles", (short) 0, (short) 0);
        sprites.addSprite(configuredMain);
        sprites.addSprite(sessionMain);

        assertSame(sessionMain, sprites.getMainPlayable());
    }

    @Test
    void spriteManagerRejectsAmbiguousCompatibilityFallbackWhenNamedMainIsAbsent() {
        SpriteManager sprites = new SpriteManager(SonicConfigurationService.getInstance());
        sprites.addSprite(new TestablePlayableSprite("synthetic-one", (short) 0, (short) 0));
        sprites.addSprite(new TestablePlayableSprite("synthetic-two", (short) 0, (short) 0));

        IllegalStateException ambiguity = assertThrows(IllegalStateException.class, sprites::getMainPlayable,
                "compatibility fallback must not silently choose or discard one of multiple human players");
        assertTrue(ambiguity.getMessage().contains("synthetic-one"));
        assertTrue(ambiguity.getMessage().contains("synthetic-two"));
    }

    @Test
    void carryControllerIsOwnedByPlayableControllerAndNeverCarriesItself() {
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);

        assertSame(tails.getTailsCarryController(), tails.getTailsCarryController());
        assertFalse(tails.getTailsCarryController().tryGrabMainCharacter());

        GameServices.sprites().clearAllSprites();
        tails.setCpuControlled(false);
        GameServices.sprites().addSprite(tails, "tails");
        assertFalse(tails.getTailsCarryController().tryGrabMainCharacter());
    }

    @Test
    void manualGrabUsesExactUnsignedContactWindowsAndImmediateNativeAttachment() {
        AbstractPlayableSprite sonic = fixture.sprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();
        enableManualCarry(tails);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0800);
        tails.setXSpeed((short) 0x0123);
        tails.setYSpeed((short) -0x0040);
        tails.setDirection(com.openggf.physics.Direction.LEFT);
        sonic.setCentreX((short) 0x0FF0);
        sonic.setCentreY((short) 0x0820);
        sonic.setXSpeed((short) 7);
        sonic.setYSpeed((short) 9);
        sonic.setGSpeed((short) 11);
        sonic.setAngle((byte) 37);
        sonic.setAnimationId(2);
        sonic.setAnimationFrameIndex(4);
        sonic.setAnimationTick(7);
        sonic.setAir(true);
        sonic.setObjectControlled(false);
        sonic.setHurt(false);
        sonic.setDead(false);
        sonic.setDebugMode(false);
        sonic.setSpindash(false);

        assertTrue(carry.tryGrabMainCharacter(), "inclusive lower X/Y edges");
        assertTrue(carry.isCarryingMainCharacter());
        assertEquals(tails.getCentreX(), sonic.getCentreX());
        assertEquals((short) (tails.getCentreY() + 0x1C), sonic.getCentreY());
        assertEquals(tails.getXSpeed(), sonic.getXSpeed());
        assertEquals(tails.getYSpeed(), sonic.getYSpeed());
        assertEquals(0, sonic.getGSpeed());
        assertEquals(0, sonic.getAngle());
        assertTrue(sonic.isObjectControlled());
        int carriedAnimationId = sonic.resolveAnimationId(
                com.openggf.game.CanonicalAnimation.TAILS_CARRIED);
        assertEquals(carriedAnimationId, sonic.getForcedAnimationId());
        assertEquals(carriedAnimationId, sonic.getAnimationId(),
                "native attachment publishes the carried animation in the same frame");
        assertEquals(0, sonic.getAnimationFrameIndex());
        assertEquals(0, sonic.getAnimationTick());
        assertTrue(sonic.isObjectControlAllowsCpu(),
                "native object_control=$03 keeps the bits-0-to-6 execution semantics");
        assertTrue(sonic.isObjectControlSuppressesMovement());
        assertFalse(sonic.isTouchResponseSuppressedByObjectControl(),
                "only object_control bit 7 suppresses TouchResponse");

        carry.restore(new TailsCarryController.Snapshot((short) 0, (short) 0,
                false, false, 0, TailsCarryController.CarryContext.NONE));
        sonic.setObjectControlled(false);
        sonic.setCentreX((short) 0x100F);
        sonic.setCentreY((short) 0x082F);
        assertTrue(carry.tryGrabMainCharacter(), "inclusive upper X/Y edges");
    }

    @Test
    void manualGrabRejectsOutsideEdgesAndParticipantBlockers() {
        AbstractPlayableSprite sonic = fixture.sprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();
        enableManualCarry(tails);
        tails.setCentreX((short) 1000);
        tails.setCentreY((short) 500);
        sonic.setAir(true);
        sonic.setObjectControlled(false);
        sonic.setHurt(false);
        sonic.setDead(false);
        sonic.setDebugMode(false);
        sonic.setSpindash(false);

        int[][] outside = {{-0x11, 0x20}, {0x10, 0x20}, {0, 0x1F}, {0, 0x30}};
        for (int[] delta : outside) {
            sonic.setCentreX((short) (1000 + delta[0]));
            sonic.setCentreY((short) (500 + delta[1]));
            assertFalse(carry.tryGrabMainCharacter());
        }

        sonic.setCentreX((short) 1000);
        sonic.setCentreY((short) (500 - 0x21));
        GameServices.gameState().setReverseGravityActive(true);
        assertTrue(carry.tryGrabMainCharacter(), "reverse gravity accepts +0x21..+0x30 above");
        carry.clearAndReleaseMain();
        assertFalse(carry.tryGrabMainCharacter(), "release cooldown blocks immediate regrab");

        carry.restore(new TailsCarryController.Snapshot((short) 0, (short) 0,
                false, false, 0, TailsCarryController.CarryContext.NONE));
        GameServices.gameState().setReverseGravityActive(false);
        sonic.setCentreY((short) (500 + 0x20));
        sonic.setObjectControlled(true);
        assertFalse(carry.tryGrabMainCharacter());
        sonic.setObjectControlled(false);
        sonic.setDebugMode(true);
        assertFalse(carry.tryGrabMainCharacter());
        sonic.setDebugMode(false);
        sonic.setSpindash(true);
        assertFalse(carry.tryGrabMainCharacter());
        sonic.setSpindash(false);
        sonic.setHurt(true);
        assertFalse(carry.tryGrabMainCharacter(), "routine >= 4 rejects pickup");
    }

    @Test
    void carriedMainFollowsPostCollisionCarrierAndExternalVelocityBreaksLatch() {
        AbstractPlayableSprite sonic = prepareManualContact();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();
        assertTrue(carry.tryGrabMainCharacter());

        tails.setCentreX((short) 0x1200);
        tails.setCentreY((short) 0x0400);
        tails.setXSpeed((short) 0x0180);
        tails.setYSpeed((short) -0x0080);
        carry.updateAfterTailsCollision(0);

        assertEquals(tails.getCentreX(), sonic.getCentreX());
        assertEquals((short) (tails.getCentreY() + 0x1C), sonic.getCentreY());
        assertEquals(tails.getXSpeed(), sonic.getXSpeed());
        assertEquals(tails.getYSpeed(), sonic.getYSpeed());
        assertEquals(sonic.getXSpeed(), carry.capture().latchX());
        assertEquals(sonic.getYSpeed(), carry.capture().latchY());

        sonic.setXSpeed((short) (sonic.getXSpeed() + 1));
        carry.updateAfterTailsCollision(0);
        assertFalse(carry.isCarryingMainCharacter());
        assertFalse(sonic.isObjectControlled());
        assertEquals(0x3C, carry.capture().cooldown());
    }

    @Test
    void jumpReleaseUsesExactNeutralAndDirectionalNativeValues() {
        AbstractPlayableSprite sonic = prepareManualContact();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();
        assertTrue(carry.tryGrabMainCharacter());
        carry.updateAfterTailsCollision(AbstractPlayableSprite.INPUT_JUMP);
        assertEquals(0x12, carry.capture().cooldown());
        assertEquals((short) -0x0380, sonic.getYSpeed());
        assertTrue(sonic.getAir());
        assertTrue(sonic.isJumping());
        assertTrue(sonic.getRolling());
        assertFalse(sonic.getRollingJump());
        assertEquals(7, sonic.getXRadius());
        assertEquals(0x0E, sonic.getYRadius());
        assertEquals(2, sonic.getAnimationId());

        carry.restore(new TailsCarryController.Snapshot((short) 0, (short) 0,
                false, false, 0, TailsCarryController.CarryContext.NONE));
        sonic.setObjectControlled(false);
        sonic.setRolling(false);
        sonic.setCentreX(tails.getCentreX());
        sonic.setCentreY((short) (tails.getCentreY() + 0x20));
        assertTrue(carry.tryGrabMainCharacter());
        carry.updateAfterTailsCollision(AbstractPlayableSprite.INPUT_JUMP | AbstractPlayableSprite.INPUT_LEFT);
        assertEquals(0x3C, carry.capture().cooldown());
        assertEquals((short) -0x0200, sonic.getXSpeed());
    }

    @Test
    void landingAndCarrierDisableClearFlightAndUnlockMain() {
        AbstractPlayableSprite sonic = prepareManualContact();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();
        tails.getTailsFlightController().activate();
        assertTrue(carry.tryGrabMainCharacter());
        sonic.setAir(false);
        carry.updateAfterTailsCollision(0);
        assertFalse(carry.isCarryingMainCharacter());
        assertFalse(tails.getTailsFlightController().isActive());
        assertFalse(sonic.isObjectControlled());

        carry.restore(new TailsCarryController.Snapshot((short) 0, (short) 0,
                false, false, 0, TailsCarryController.CarryContext.NONE));
        tails.getTailsFlightController().activate();
        sonic.setCentreX(tails.getCentreX());
        sonic.setCentreY((short) (tails.getCentreY() + 0x20));
        sonic.setAir(true);
        assertTrue(carry.tryGrabMainCharacter());
        tails.setDead(true);
        carry.updateAfterTailsCollision(0);
        assertFalse(carry.isCarryingMainCharacter());
        assertFalse(sonic.isObjectControlled());
        assertEquals(TailsCarryController.CarryContext.NONE, carry.capture().context());
    }

    @Test
    void scriptedContextsUseSharedAttachmentAndSnapshotWithoutParticipantReference() {
        AbstractPlayableSprite sonic = prepareManualContact();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();

        carry.forceScriptedCarry(TailsCarryController.CarryContext.CNZ);
        assertTrue(carry.isCarryingMainCharacter());
        assertEquals(TailsCarryController.CarryContext.CNZ, carry.capture().context());
        assertTrue(sonic.isObjectControlled());

        TailsCarryController.Snapshot saved = carry.capture();
        carry.clearAndReleaseMain();
        carry.restore(saved);
        assertEquals(saved, carry.capture());

        carry.forceScriptedCarry(TailsCarryController.CarryContext.MGZ_BOSS);
        assertEquals(TailsCarryController.CarryContext.MGZ_BOSS, carry.capture().context());
        carry.clearAndReleaseMain();
        assertEquals(TailsCarryController.CarryContext.NONE, carry.capture().context());
    }

    @Test
    void manualGrabRequiresEnabledActiveFlightAndPlayerTwoOwnership() {
        AbstractPlayableSprite sonic = prepareManualContact();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();
        SidekickCpuController cpu = tails.getCpuController();

        tails.getTailsFlightController().clear();
        assertFalse(carry.tryGrabMainCharacter(), "grounded/inactive Tails cannot grab");

        tails.getTailsFlightController().activate();
        cpu.reset();
        cpu.setInitialState(SidekickCpuController.State.NORMAL);
        assertFalse(carry.tryGrabMainCharacter(), "CPU NORMAL ownership cannot manually grab");
        cpu.setInitialState(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY);
        assertFalse(carry.tryGrabMainCharacter(), "CPU recovery ownership cannot manually grab");

        cpu.setInitialState(SidekickCpuController.State.NORMAL);
        cpu.setController2Input(AbstractPlayableSprite.INPUT_RIGHT, AbstractPlayableSprite.INPUT_RIGHT);
        cpu.update(1);
        sonic.setCentreX(tails.getCentreX());
        sonic.setCentreY((short) (tails.getCentreY() + 0x20));
        assertTrue(cpu.isUnderManualControl());
        assertTrue(carry.tryGrabMainCharacter(),
                "active S3K-capable flight under Player 2 ownership can grab the main participant");
    }

    @Test
    void manualP2FlightUsesLiveCarryStateForAirAndUnderwaterFlightUpdates() {
        AbstractPlayableSprite sonic = fixture.sprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();
        tails.setObjectControlled(false);
        tails.setSuppressAirCollision(true);
        tails.setAir(true);
        tails.setJumping(true);
        tails.setRolling(true);
        tails.setDoubleJumpFlag(0);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0400);
        tails.setYSpeed((short) 0);
        SidekickCpuController cpu = tails.getCpuController();
        cpu.setInitialState(SidekickCpuController.State.NORMAL);

        dispatchManualP2Movement(tails, AbstractPlayableSprite.INPUT_RIGHT,
                AbstractPlayableSprite.INPUT_RIGHT);
        dispatchManualP2Movement(tails,
                AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                AbstractPlayableSprite.INPUT_JUMP);
        assertTrue(tails.getTailsFlightController().isActive(),
                "real P2 release/repress dispatch activates flight");

        sonic.setCentreX(tails.getCentreX());
        sonic.setCentreY((short) (tails.getCentreY() + 0x20));
        sonic.setAir(true);
        sonic.setObjectControlled(false);
        sonic.setHurt(false);
        sonic.setDead(false);
        sonic.setDebugMode(false);
        sonic.setSpindash(false);
        assertTrue(carry.tryGrabMainCharacter(), "active manually controlled Tails grabs the main player");

        dispatchManualP2Movement(tails, AbstractPlayableSprite.INPUT_RIGHT, 0);
        assertEquals(0x22, tails.getAnimationId(),
                "the next manual flight frame selects the live air-carry animation");
        carry.updateAfterTailsCollision(0);

        tails.setInWater(true);
        tails.setDoubleJumpFlag(1);
        tails.setDoubleJumpProperty((byte) 10);
        tails.setYSpeed((short) 0);
        GameServices.sprites().setFrameCounter(3);
        dispatchManualP2Movement(tails,
                AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                AbstractPlayableSprite.INPUT_JUMP);

        assertEquals(1, tails.getDoubleJumpFlag(),
                "carrying underwater suppresses a new flap from ready state");
        assertEquals(9, tails.getDoubleJumpProperty() & 0xFF,
                "the odd-frame flight timer still decrements while the flap is suppressed");
        assertEquals(0x27, tails.getAnimationId(),
                "underwater carrying selects the shared swim-carry animation");
    }

    @Test
    void cpuResetAndCarrierObjectControlTakeoverReleaseMain() {
        AbstractPlayableSprite sonic = prepareManualContact();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        TailsCarryController carry = tails.getTailsCarryController();
        SidekickCpuController cpu = tails.getCpuController();

        carry.forceScriptedCarry(TailsCarryController.CarryContext.CNZ);
        cpu.reset();
        assertFalse(carry.isCarryingMainCharacter());
        assertFalse(sonic.isObjectControlled());
        assertEquals(TailsCarryController.CarryContext.NONE, carry.capture().context());

        carry.forceScriptedCarry(TailsCarryController.CarryContext.CNZ);
        tails.setObjectControlled(true);
        carry.updateAfterTailsCollision(0);
        assertFalse(carry.isCarryingMainCharacter());
        assertFalse(sonic.isObjectControlled());
    }

    @Test
    void cpuAndManualPostCollisionPathsDecrementCooldownOnlyOncePerFrame() {
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        SidekickCpuController cpu = tails.getCpuController();
        TailsCarryController carry = tails.getTailsCarryController();
        carry.restore(new TailsCarryController.Snapshot(
                (short) 0, (short) 0, false, false, 2, TailsCarryController.CarryContext.NONE));
        cpu.setInitialState(SidekickCpuController.State.NORMAL);
        cpu.setController2Input(AbstractPlayableSprite.INPUT_RIGHT, AbstractPlayableSprite.INPUT_RIGHT);

        cpu.update(10);
        carry.updateAfterTailsCollision(0);

        assertEquals(1, carry.capture().cooldown());
    }

    private AbstractPlayableSprite prepareManualContact() {
        AbstractPlayableSprite sonic = fixture.sprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        tails.getTailsCarryController().restore(new TailsCarryController.Snapshot(
                (short) 0, (short) 0, false, false, 0, TailsCarryController.CarryContext.NONE));
        enableManualCarry(tails);
        tails.setDead(false);
        tails.setHurt(false);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0400);
        tails.setXSpeed((short) 0x0100);
        tails.setYSpeed((short) 0);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x0420);
        sonic.setAir(true);
        sonic.setObjectControlled(false);
        sonic.setHurt(false);
        sonic.setDead(false);
        sonic.setDebugMode(false);
        sonic.setSpindash(false);
        return sonic;
    }

    private void enableManualCarry(AbstractPlayableSprite tails) {
        tails.setObjectControlled(false);
        tails.getTailsFlightController().activate();
        SidekickCpuController cpu = tails.getCpuController();
        if (cpu != null) {
            cpu.setInitialState(SidekickCpuController.State.NORMAL);
            cpu.setController2Input(AbstractPlayableSprite.INPUT_RIGHT, AbstractPlayableSprite.INPUT_RIGHT);
            cpu.update(0);
        }
    }

    private void dispatchManualP2Movement(AbstractPlayableSprite tails, int held, int logical) {
        SidekickCpuController cpu = tails.getCpuController();
        cpu.setController2Input(held, logical);
        cpu.update(GameServices.sprites().getFrameCounter() + 1);
        if (cpu.getInputJumpPress()) {
            tails.setForcedJumpPress(true);
        }
        tails.getMovementManager().handleMovement(
                cpu.getInputUp(), cpu.getInputDown(), cpu.getInputLeft(), cpu.getInputRight(),
                cpu.getInputJump(), false, false, false);
    }
}
