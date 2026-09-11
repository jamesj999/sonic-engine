package com.openggf.game.sonic3k.objects;

import com.openggf.audio.rewind.AudioCommand;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.camera.Camera;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestMgzEndBossHandoffHeadless {

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 1);
    }

    @AfterAll
    static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    void setUp() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        fixture.camera().setX((short) 0x3C80);
        fixture.camera().setY((short) 0x0600);
        fixture.camera().setMinX((short) 0x3C80);
        fixture.camera().setMaxX((short) 0x3C80);
        fixture.camera().setMinY((short) 0x06A0);
        fixture.camera().setMaxY((short) 0x06A0);
        fixture.sprite().setCentreX((short) 0x3CC0);
        fixture.sprite().setCentreY((short) 0x0700);
        fixture.sprite().setAir(true);
    }

    @Test
    void liveEndBossFloorImpactStartsRealTailsRescueForSonicAlone() throws Exception {
        MgzEndBossInstance boss = new MgzEndBossInstance(new ObjectSpawn(
                0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        GameServices.level().getObjectManager().addDynamicObject(boss);
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "yVel", 0x400);
        boss.getState().routine = staticInt("ROUTINE_END_FLOOR_DROP");
        boss.getState().x = 0x3D20;
        boss.getState().y = 0x0668;

        for (int frame = 0; frame < 240
                && (!isBossTransitionActive() || GameServices.sprites().getSidekicks().isEmpty()); frame++) {
            fixture.stepIdleFrames(1);
        }

        assertTrue(isBossTransitionActive(),
                "Real Obj_MGZEndBoss floor impact should activate Obj_MGZ2_BossTransition through the production frame loop");
        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "Sonic-alone MGZ2 boss transition must add rescue Tails to the real SpriteManager");
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        assertEquals("tails", GameServices.sprites().getSidekickCharacterName(tails));
        assertNotNull(tails.getSpriteRenderer(),
                "Runtime-spawned rescue Tails must have real S3K sidekick art loaded");
        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState());
        assertTrue(mgzEvents().isBossTransitionDeathPlaneDisabled(),
                "The real boss handoff must disable the death plane before Sonic falls below the arena");
        assertFalse(fixture.sprite().getDead(),
                "Sonic should not die during the boss-transition rescue wait");

        String badStateDiagnostic = null;
        StringBuilder carrySamples = new StringBuilder();
        for (int frame = 0; frame < 0x168 + 4; frame++) {
            fixture.stepIdleFrames(1);
            SidekickCpuController.State state = tails.getCpuController().getState();
            if (state == SidekickCpuController.State.CARRY_INIT
                    || state == SidekickCpuController.State.CARRYING
                    || frame % 60 == 0) {
                if (carrySamples.length() < 1200) {
                    carrySamples.append(String.format(
                            " f=%d state=%s tails=(%04X,%04X topY=%04X yv=%04X flag=%02X prop=%02X) sonic=(%04X,%04X topY=%04X yv=%04X ctrl=%s);",
                            frame,
                            state,
                            tails.getCentreX() & 0xFFFF,
                            tails.getCentreY() & 0xFFFF,
                            tails.getY() & 0xFFFF,
                            tails.getYSpeed() & 0xFFFF,
                            tails.getDoubleJumpFlag() & 0xFF,
                            tails.getDoubleJumpProperty() & 0xFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            fixture.sprite().getY() & 0xFFFF,
                            fixture.sprite().getYSpeed() & 0xFFFF,
                            fixture.sprite().isObjectControlled()));
                }
            }
            if (state == SidekickCpuController.State.SPAWNING && badStateDiagnostic == null) {
                badStateDiagnostic = String.format(
                        "frame=%d tails=(%04X,%04X topY=%04X) sonic=(%04X,%04X topY=%04X) "
                                + "camera=(%04X,%04X maxY=%04X targetMaxY=%04X) sonicControlled=%s sonicDead=%s",
                        frame,
                        tails.getCentreX() & 0xFFFF,
                        tails.getCentreY() & 0xFFFF,
                        tails.getY() & 0xFFFF,
                        fixture.sprite().getCentreX() & 0xFFFF,
                        fixture.sprite().getCentreY() & 0xFFFF,
                        fixture.sprite().getY() & 0xFFFF,
                        fixture.camera().getX() & 0xFFFF,
                        fixture.camera().getY() & 0xFFFF,
                        fixture.camera().getMaxY() & 0xFFFF,
                        fixture.camera().getMaxYTarget() & 0xFFFF,
                        fixture.sprite().isObjectControlled(),
                        fixture.sprite().getDead());
            }
        }

        assertTrue(fixture.sprite().isObjectControlled(),
                "After the ROM $168-frame wait, rescue Tails must pick up Sonic instead of leaving him falling");
        SidekickCpuController.State finalState = tails.getCpuController().getState();
        assertTrue(finalState == SidekickCpuController.State.CARRYING
                        || finalState == SidekickCpuController.State.CARRY_INIT,
                "The real sidekick update loop should advance MGZ rescue Tails into carrying; loc_16384 may"
                        + " republish routine $14 while the carry flag remains set"
                        + (badStateDiagnostic != null ? ": " + badStateDiagnostic : "")
                        + " samples:" + carrySamples);
        assertTrue(tails.getCpuController().isFlyingCarrying(),
                "A loc_16384 routine-$14 rearm must retain the active Flying_carrying_Sonic_flag");
        assertEquals(0x22, fixture.sprite().resolveAnimationId(CanonicalAnimation.TAILS_CARRIED),
                "ROM sub_1459E writes Sonic anim=$22 when Tails picks him up");
        assertEquals(0x22, fixture.sprite().getForcedAnimationId(),
                "Carried Sonic should keep the native $22 carried pose while parented to Tails");
        assertEquals(tails.getCentreX(), fixture.sprite().getCentreX(),
                "Carried Sonic should be parented to Tails on X");
        if (finalState == SidekickCpuController.State.CARRYING) {
            assertEquals(tails.getCentreY() + 0x1C, fixture.sprite().getCentreY(),
                    "The carry body should parent Sonic to Tails.y+$1C");
        } else {
            assertTrue(fixture.sprite().getCentreY() >= tails.getCentreY() + 0x1C
                            && fixture.sprite().getCentreY() <= tails.getCentreY() + 0x1F,
                    "The later loc_16384 routine-$14 publication leaves Sonic's same-frame fall visible until the next carry body");
        }
    }

    @Test
    void liveFloorImpactDisablesDeathPlaneBeforePlayerBoundaryAndKeepsImpactSound() throws Exception {
        MgzEndBossInstance boss = new MgzEndBossInstance(new ObjectSpawn(
                0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        GameServices.level().getObjectManager().addDynamicObject(boss);
        setPrivateInt(boss, "waitTimer", 0);
        setPrivateInt(boss, "yVel", 0x400);
        boss.getState().routine = staticInt("ROUTINE_END_FLOOR_DROP");
        boss.getState().x = 0x3D20;
        boss.getState().y = 0x0668;

        for (int frame = 0; frame < 240
                && !boss.willPublishDeathPlaneDisableThisObjectPass(); frame++) {
            fixture.stepIdleFrames(1);
        }
        assertTrue(boss.willPublishDeathPlaneDisableThisObjectPass(),
                "The fixture must reach the retained floor-impact object pass");

        // Cross the engine kill plane in the player slot immediately before
        // the later boss slot executes loc_6C4BE in this same object pass.
        fixture.sprite().setCentreY((short) 0x0781);
        fixture.sprite().setYSpeed((short) 0x0100);
        GameServices.audio().commandTimeline().clear();

        fixture.stepIdleFrames(1);
        assertFalse(fixture.sprite().getDead(),
                "Disable_death_plane must be visible before the player boundary phase");
        assertFalse(fixture.camera().getFrozen(),
                "The MGZ handoff must never transiently enter the death-camera freeze");

        assertTrue(mgzEvents().isBossTransitionDeathPlaneDisabled());
        assertTrue(GameServices.audio().commandTimeline().entries().stream()
                        .map(entry -> entry.command())
                        .anyMatch(command -> command instanceof AudioCommand.PlaySfx sfx
                                && sfx.sfxId() == Sonic3kSfx.BOSS_HIT_FLOOR.id),
                "loc_6C4BE must retain sfx_BossHitFloor");
        assertFalse(GameServices.audio().commandTimeline().entries().stream()
                        .map(entry -> entry.command())
                        .anyMatch(command -> command instanceof AudioCommand.PlaySfx sfx
                                && sfx.sfxId() == Sonic3kSfx.DEATH.id),
                "The rescue handoff must not queue the transient pit-death SFX");

        mgzEvents().setBossTransitionDeathPlaneDisabled(false);
        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager)
                com.openggf.game.GameModuleRegistry.getCurrent().getLevelEventProvider();
        assertFalse(manager.interceptPitDeath(fixture.sprite()),
                "The one-pass anticipation must not suppress genuine later pit deaths");
    }

    @Test
    void eventSpawnedKnucklesBossCompletesRealFightCapsuleResultsAndAutoWalk() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        fixture.camera().setX((short) 0x3A00);
        fixture.camera().setY((short) 0x0000);
        fixture.sprite().setCentreY((short) 0x00A0);
        fixture.sprite().setAir(false);
        mgzEvents().update(1, 0);
        fixture.camera().setX((short) 0x3C80);
        mgzEvents().update(1, 1);

        MgzEndBossKnuxInstance boss = GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(MgzEndBossKnuxInstance.class::isInstance)
                .map(MgzEndBossKnuxInstance.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Sonic3kObjectIds.MGZ_END_BOSS_KNUX, GameServices.gameState().getCurrentBossId());
        assertTrue(GameServices.audio().commandTimeline().entries().stream()
                        .map(entry -> entry.command())
                        .anyMatch(command -> command instanceof AudioCommand.PlayMusic music
                                && music.musicId() == Sonic3kMusic.MINIBOSS.id),
                "The Knuckles event branch must start the ROM miniboss music");
        assertFalse(mgzEvents().isBossTransitionDeathPlaneDisabled(),
                "Knuckles must retain normal death-plane ownership instead of entering Sonic's rescue handoff");
        assertTrue(GameServices.sprites().getSidekicks().isEmpty(),
                "The Knuckles route must not create rescue Tails");
        assertEquals(0x3C80, fixture.camera().getMinX() & 0xFFFF,
                "MGZ2_Resize must own the left arena boundary before spawning $A2");
        assertEquals(0x00A0, fixture.camera().getMinY() & 0xFFFF,
                "Knuckles' placement-path arena is the top MGZ band, $600 above Sonic's arena");
        assertEquals(0x0068, boss.getSpawn().y(),
                "$A2 must spawn in the native top-band coordinate space used by reset y=-$58 and capsule y=$B0");

        for (int frame = 0; frame < 1200 && boss.getNativeRoutineForTesting() < 0x06; frame++) {
            fixture.stepIdleFrames(1);
        }
        assertTrue(boss.getNativeRoutineForTesting() >= 0x06,
                "The real drill child must publish its ready flag and advance the boss choreography");

        for (int hit = 0; hit < 8; hit++) {
            boss.onPlayerAttack(fixture.sprite(), null);
            fixture.stepIdleFrames(0x20);
        }
        assertEquals(0, boss.getCollisionProperty(), "the Knuckles fight must end on hit eight");

        for (int frame = 0; frame < 2 * 60 + 0xB3 + 8 && !boss.isCapsuleSpawnedForTesting(); frame++) {
            fixture.stepIdleFrames(1);
        }
        assertTrue(boss.isCapsuleSpawnedForTesting(),
                "Wait_FadeToLevelMusic and loc_6C890 must always reach the capsule handoff");

        MgzEndBossKnuxEggCapsuleInstance capsule = GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(MgzEndBossKnuxEggCapsuleInstance.class::isInstance)
                .map(MgzEndBossKnuxEggCapsuleInstance.class::cast)
                .findFirst().orElseThrow();
        capsule.onPieceContact(1, fixture.sprite(),
                new SolidContact(true, false, false, true, false), 0);
        for (int frame = 0; frame < 0x42 && !capsule.isResultsStarted(); frame++) {
            fixture.sprite().setAir(false);
            capsule.update(frame, fixture.sprite());
        }
        assertTrue(capsule.isResultsStarted(), "the real upright capsule must start its results object");
        for (int frame = 0; frame < 2400 && !boss.isCompletionReady(); frame++) {
            fixture.stepIdleFrames(1);
        }
        assertTrue(boss.isCompletionReady(), "x=" + (fixture.sprite().getCentreX() & 0xFFFF)
                + " camera=" + (fixture.camera().getX() & 0xFFFF)
                + " input=" + fixture.sprite().getForcedInputMask()
                + " lock=" + fixture.sprite().isControlLocked()
                + " suppress=" + fixture.sprite().isObjectControlSuppressesMovement()
                + " air=" + fixture.sprite().getAir()
                + " dead=" + fixture.sprite().getDead()
                + " g=" + fixture.sprite().getGSpeed()
                + " xv=" + fixture.sprite().getXSpeed()
                + " endActive=" + GameServices.gameState().isEndOfLevelActive());
        assertTrue(boss.isDestroyed(),
                "loc_6C932 must release the retained boss once Knuckles clears camera+$150");
        assertFalse(fixture.sprite().getDead(), "the complete Knuckles route must not end in a fall-forever death");
    }

    @Test
    void bossTransitionRoutine12LeavesTailsFreeToFallDuringWait() {
        triggerAndInitializeBossTransition();

        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState(),
                "Obj_MGZ2_BossTransition leaves rescue Tails at CPU routine $12 during the $168-frame wait");
        assertFalse(tails.isObjectControlled(),
                "ROM routine $12 only clears Ctrl_2_logical; it does not hold Tails with object_control");

        int startY = tails.getCentreY() & 0xFFFF;
        fixture.stepIdleFrames(16);

        assertTrue((tails.getCentreY() & 0xFFFF) > startY,
                "Routine $12 Tails should fall under normal freespace physics while waiting for the handoff timer");
        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState(),
                "Tails should remain in routine $12 until Obj_MGZ2_BossTransition promotes him");
    }

    @Test
    void bossTransitionCarryRecoversWhenTailsIsHurt() {
        triggerAndInitializeBossTransition();

        AbstractPlayableSprite player = fixture.sprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        for (int frame = 0; frame < 0x168 + 8
                && tails.getCpuController().getState() != SidekickCpuController.State.CARRYING; frame++) {
            fixture.stepIdleFrames(1);
        }
        assertEquals(SidekickCpuController.State.CARRYING, tails.getCpuController().getState(),
                "Precondition: rescue Tails should have picked Sonic up before the damage case");
        assertTrue(player.isObjectControlled(),
                "Precondition: Sonic should be actively carried before Tails is hurt");

        tails.applyHurt(player.getCentreX());
        fixture.stepIdleFrames(1);
        assertFalse(player.isObjectControlled(),
                "Tails's hurt routine should immediately release Sonic");

        StringBuilder samples = new StringBuilder();
        for (int frame = 0; frame < 360 && !player.isObjectControlled(); frame++) {
            fixture.stepIdleFrames(1);
            if (frame % 30 == 0 && samples.length() < 1400) {
                samples.append(String.format(
                        " f=%d state=%s hurt=%s tails=(%04X,%04X yv=%04X air=%s flag=%02X prop=%02X) "
                                + "sonic=(%04X,%04X yv=%04X ctrl=%s);",
                        frame,
                        tails.getCpuController().getState(),
                        tails.isHurt(),
                        tails.getCentreX() & 0xFFFF,
                        tails.getCentreY() & 0xFFFF,
                        tails.getYSpeed() & 0xFFFF,
                        tails.getAir(),
                        tails.getDoubleJumpFlag() & 0xFF,
                        tails.getDoubleJumpProperty() & 0xFF,
                        player.getCentreX() & 0xFFFF,
                        player.getCentreY() & 0xFFFF,
                        player.getYSpeed() & 0xFFFF,
                        player.isObjectControlled()));
            }
        }

        assertTrue(player.isObjectControlled(),
                "After hurt recovery, MGZ rescue Tails must resume the carry routine and pick Sonic up again: "
                        + samples);
        assertTrue(tails.getCpuController().isFlyingCarrying(),
                "The re-grab should restore the active Flying_carrying_Sonic_flag equivalent");
    }

    @Test
    void bossTransitionDoesNotReviveFallingSonicDeathRoutineForRescue() {
        triggerAndInitializeBossTransition();

        AbstractPlayableSprite player = fixture.sprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        player.applyPitDeath();
        player.setCentreY((short) 0x0900);
        player.setYSpeed((short) 0x0600);

        fixture.stepIdleFrames(1);

        assertTrue(player.getDead(),
                "Once Sonic has entered a real death routine during the rescue, Obj_MGZ2_BossTransition must not revive him");

        StringBuilder samples = new StringBuilder();
        for (int frame = 0; frame < 0x168 + 16; frame++) {
            fixture.stepIdleFrames(1);
            if (frame % 30 == 0 && samples.length() < 1400) {
                samples.append(String.format(
                        " f=%d state=%s dead=%s tails=(%04X,%04X yv=%04X) sonic=(%04X,%04X yv=%04X ctrl=%s);",
                        frame,
                        tails.getCpuController().getState(),
                        player.getDead(),
                        tails.getCentreX() & 0xFFFF,
                        tails.getCentreY() & 0xFFFF,
                        tails.getYSpeed() & 0xFFFF,
                        player.getCentreX() & 0xFFFF,
                        player.getCentreY() & 0xFFFF,
                        player.getYSpeed() & 0xFFFF,
                        player.isObjectControlled()));
            }
        }

        assertTrue(player.getDead(),
                "Sonic's death sequence should continue instead of being converted back into the rescue path: "
                        + samples);
        assertFalse(player.isObjectControlled(),
                "Rescue Tails must not regrab Sonic after the death routine starts: " + samples);
        assertFalse(tails.getCpuController().isFlyingCarrying(),
                "The MGZ carry flag should stay clear while Sonic is dead");
    }

    @Test
    void liveDefeatHandoffRunsPostResultsPaletteFadeAndRequestsCnz() throws Exception {
        MgzEndBossInstance boss = new MgzEndBossInstance(new ObjectSpawn(
                0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        GameServices.level().getObjectManager().addDynamicObject(boss);
        setPrivateInt(boss, "waitTimer", 0);
        boss.getState().routine = staticInt("ROUTINE_END_DEFEATED");
        boss.getState().defeated = true;

        fixture.stepIdleFrames(1);
        assertFalse(boss.isDestroyed(),
                "Obj_MGZEndBoss must remain alive as the loc_6C2BE/loc_6C2EE handoff controller");

        for (int frame = 0; frame < 140 && !hasObject(Mgz2EndEggCapsuleInstance.class); frame++) {
            fixture.stepIdleFrames(1);
        }

        assertTrue(hasObject(Mgz2EndEggCapsuleInstance.class),
                "loc_694AA should spawn the floating egg prison after Wait_FadeToLevelMusic's callback delay");
        assertFalse(hasObject(Mgz2PostBossPaletteFadeController.class),
                "loc_6C2EE must wait for the results flag before spawning loc_6D104");
        PatternSpriteRenderer bossExplosionRenderer = GameServices.level()
                .getObjectRenderManager()
                .getBossExplosionRenderer();
        assertNotNull(bossExplosionRenderer,
                "MGZ2 defeat/capsule explosions need the shared boss-explosion renderer loaded");
        assertTrue(bossExplosionRenderer.isReady(),
                "MGZ2 defeat/capsule explosions need shared boss-explosion art cached before rendering");

        int[] before = paletteLineRgb(3);
        GameServices.gameState().setEndOfLevelFlag(true);
        fixture.stepIdleFrames(1);

        assertTrue(hasObject(Mgz2PostBossPaletteFadeController.class),
                "When results set End_of_level_flag, the real waiter should spawn loc_6D104");

        int[] after = paletteLineRgb(3);
        for (int frame = 0; frame < 80 && java.util.Arrays.equals(before, after); frame++) {
            fixture.stepIdleFrames(1);
            after = paletteLineRgb(3);
        }
        assertNotEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(after),
                "loc_6D104 should visibly mutate Normal_palette_line_4 toward the night/CNZ fade colors");

        for (int frame = 0; frame < 260 && !GameServices.level().consumeZoneActRequest(); frame++) {
            fixture.stepIdleFrames(1);
        }

        assertEquals(Sonic3kZoneIds.ZONE_CNZ, GameServices.level().getRequestedZone(),
                "The palette fade should finish by requesting StartNewLevel #$300");
        assertEquals(0, GameServices.level().getRequestedAct());
        assertTrue(GameServices.level().isLevelInactiveForTransition(),
                "The final zone request should freeze level updates while GameLoop fades to black");
    }

    private void triggerAndInitializeBossTransition() {
        Camera camera = GameServices.camera();
        // MGZ2_Resize owns these arena limits before the boss creates
        // Obj_MGZ2_BossTransition. The transition object only samples the
        // camera to place itself; it does not establish or maintain the lock.
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x06A0);
        camera.setMinX((short) 0x3C80);
        camera.setMinXTarget((short) 0x3C80);
        camera.setMaxX((short) 0x3C80);
        camera.setMaxXTarget((short) 0x3C80);
        camera.setMinY((short) 0x06A0);
        camera.setMinYTarget((short) 0x06A0);
        camera.setMaxY((short) 0x06A0);
        camera.setMaxYTarget((short) 0x06A0);
        mgzEvents().triggerBossCollapseHandoff();
        fixture.stepIdleFrames(1);
        assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                "The following ExecuteObjects pass must initialize the deferred transition SST and create rescue Tails");
    }

    private boolean isBossTransitionActive() {
        return mgzEvents().isBossTransitionDeathPlaneDisabled();
    }

    private com.openggf.game.sonic3k.events.Sonic3kMGZEvents mgzEvents() {
        var provider = GameServices.module().getLevelEventProvider();
        assertTrue(provider instanceof Sonic3kLevelEventManager,
                "S3K event provider must be Sonic3kLevelEventManager");
        var events = ((Sonic3kLevelEventManager) provider).getMgzEvents();
        assertNotNull(events, "MGZ events should be initialized for MGZ act 2");
        return events;
    }

    private boolean hasObject(Class<?> type) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .anyMatch(type::isInstance);
    }

    private int[] paletteLineRgb(int line) {
        var palette = GameServices.level().getCurrentLevel().getPalette(line);
        int[] rgb = new int[16 * 3];
        for (int i = 0; i < 16; i++) {
            rgb[i * 3] = palette.getColor(i).r & 0xFF;
            rgb[i * 3 + 1] = palette.getColor(i).g & 0xFF;
            rgb[i * 3 + 2] = palette.getColor(i).b & 0xFF;
        }
        return rgb;
    }

    private static int staticInt(String fieldName) throws Exception {
        Field field = MgzDrillingRobotnikInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
