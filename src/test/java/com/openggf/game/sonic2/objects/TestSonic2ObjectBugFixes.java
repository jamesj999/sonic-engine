package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.objects.bosses.ARZBossArrow;
import com.openggf.game.sonic2.objects.bosses.ARZBossPillar;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.rules.GameRules;
import com.openggf.game.DamageCause;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.ParallaxManager;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSonic2ObjectBugFixes {

    @Test
    void barrierKeepsStandardSolidObjectExactRightEdgeContact() {
        BarrierObjectInstance barrier = new BarrierObjectInstance(
                new ObjectSpawn(0x1678, 0x0720, Sonic2ObjectIds.BARRIER, 0, 0, false, 0),
                "Barrier");

        assertTrue(barrier.getSolidRoutineProfile().inclusiveRightEdge(),
                "Obj2D's standard SolidObject BHI gate accepts relX == 2*d1");
        assertTrue(barrier.preservesEdgeSubpixelMotion(),
                "Obj2D exact-edge d0=0 must bypass StopCharacter velocity clearing");
    }

    @Test
    void eggPrisonBodyKeepsStandardSolidObjectExactRightEdgeContact() {
        EggPrisonObjectInstance prison = new EggPrisonObjectInstance(
                new ObjectSpawn(0x3202, 0x04C2, Sonic2ObjectIds.EGG_PRISON, 0, 0, false, 0),
                "EggPrison");

        assertTrue(prison.getSolidRoutineProfile().inclusiveRightEdge(),
                "Obj3E's standard SolidObject BHI gate accepts relX == 2*d1");
        assertEquals(0x20, prison.getBalanceWidthPixels(),
                "Obj3E balance uses body width_pixels, not the $B-expanded collision d1");
    }

    @Test
    void eggPrisonButtonKeepsStandardSolidObjectExactRightEdgeContact() {
        EggPrisonButtonObjectInstance button = new EggPrisonButtonObjectInstance(
                new ObjectSpawn(0x2AC0, 0x0240, Sonic2ObjectIds.EGG_PRISON, 0, 0, false, 0));

        assertTrue(button.getSolidRoutineProfile().inclusiveRightEdge(),
                "Obj3E routine 4 accepts the button's exact +$1B edge through SolidObject's BHI gate");
        assertTrue(button.preservesEdgeSubpixelMotion(),
                "the exact edge publishes push without StopCharacter clearing subpixel motion");
    }

    @Test
    void oozLauncherBallCaptureUsesObjectControlWithoutGlobalControlLockedLatch() {
        LauncherBallObjectInstance.clearActiveCaptures();
        ObjectSpawn spawn = new ObjectSpawn(0x1240, 0x02E0, Sonic2ObjectIds.LAUNCHER_BALL, 0x00, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) spawn.x(), (short) spawn.y());
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setLogicalInputState(false, false, true, false, false);
        player.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_LEFT, player.getInputHistory(0));

        LauncherBallObjectInstance launcherBall = new LauncherBallObjectInstance(spawn, "LauncherBall");
        launcherBall.setServices(new StubObjectServices()
                .withPlayerQuery(new ObjectPlayerQuery(() -> player, () -> List.of())));

        launcherBall.update(0, player);

        assertTrue(player.isObjectControlled(),
                "Obj48 writes obj_control=$81, so launcher-ball capture must suppress normal movement.");
        assertFalse(player.isControlLocked(),
                "Obj48 loc_2535E writes obj_control(a1), not global Control_Locked; Obj01_Control must keep "
                        + "refreshing Ctrl_1_Logical before Sonic_RecordPos stores the follower-history word "
                        + "(docs/s2disasm/s2.asm:51341-51367,36233-36252,36342-36353).");

        player.setLogicalInputState(false, false, false, false, false);
        player.endOfTick();
        assertEquals(0, player.getInputHistory(0),
                "With Control_Locked untouched, raw neutral input refreshes Ctrl_1_Logical instead of preserving "
                        + "stale pre-capture LEFT for TailsCPU_Normal's delayed read.");
    }

    @Test
    void oozInvisibleLauncherCaptureUsesObjectControlWithoutGlobalControlLockedLatch() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x1110, 0x0298, Sonic2ObjectIds.OOZ_LAUNCHER, 0x00, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) spawn.x(), (short) spawn.y());
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setLogicalInputState(false, false, true, false, false);
        player.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_LEFT, player.getInputHistory(0));

        OOZLauncherObjectInstance launcher = new OOZLauncherObjectInstance(spawn, "OOZLauncher");
        launcher.setServices(new StubObjectServices());
        Method proximity = OOZLauncherObjectInstance.class.getDeclaredMethod(
                "processProximityDetection", AbstractPlayableSprite.class);
        proximity.setAccessible(true);

        int nextState = (int) proximity.invoke(launcher, player);

        assertEquals(2, nextState, "Obj3D loc_24FC2 advances the per-player launcher state to tracking.");
        assertTrue(player.isObjectControlled(),
                "Obj3D writes obj_control=$81, so invisible-launcher tracking must suppress normal movement.");
        assertFalse(player.isControlLocked(),
                "Obj3D loc_24FC2 writes obj_control(a1), not global Control_Locked; Obj01_Control must keep "
                        + "refreshing Ctrl_1_Logical before Sonic_RecordPos stores the follower-history word "
                        + "(docs/s2disasm/s2.asm:51123-51158,36233-36252,36342-36353).");

        player.setLogicalInputState(false, false, false, false, false);
        player.endOfTick();
        assertEquals(0, player.getInputHistory(0),
                "With Control_Locked untouched, raw neutral input refreshes Ctrl_1_Logical while Obj3D owns "
                        + "movement through obj_control.");
    }

    @Test
    void oozLauncherBallDefersCaptureWhenObj3DJustMovedPlayerIntoRange() throws Exception {
        TestEnvironment.resetAll();
        OOZLauncherObjectInstance.clearActiveLaunchers();
        LauncherBallObjectInstance.clearActiveCaptures();
        ObjectSpawn launcherSpawn = new ObjectSpawn(0x0100, 0x0130,
                Sonic2ObjectIds.OOZ_LAUNCHER, 0x01, 0, false, 0);
        ObjectSpawn ballSpawn = new ObjectSpawn(0x0100, 0x0080,
                Sonic2ObjectIds.LAUNCHER_BALL, 0x00, 0, false, 0);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic",
                (short) launcherSpawn.x(), (short) (launcherSpawn.y() - 0x10));
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setAnimationId(Sonic2AnimationIds.ROLL.id());
        player.setGSpeed((short) 0x0800);
        player.setYSpeed((short) -0x0800);
        player.setAir(true);
        player.setOnObject(true);

        OOZLauncherObjectInstance launcher = new OOZLauncherObjectInstance(launcherSpawn, "OOZLauncher");
        LauncherBallObjectInstance ball = new LauncherBallObjectInstance(ballSpawn, "LauncherBall");
        StubObjectServices services = new StubObjectServices()
                .withPlayerQuery(new ObjectPlayerQuery(() -> player, () -> List.of()));
        launcher.setServices(services);
        ball.setServices(services);

        Method proximity = OOZLauncherObjectInstance.class.getDeclaredMethod(
                "processProximityDetection", AbstractPlayableSprite.class);
        proximity.setAccessible(true);
        assertEquals(2, proximity.invoke(launcher, player));
        Method stateFor = OOZLauncherObjectInstance.class.getDeclaredMethod(
                "stateFor", AbstractPlayableSprite.class);
        stateFor.setAccessible(true);
        Object launcherState = stateFor.invoke(launcher, player);
        Field launcherStateField = launcherState.getClass().getDeclaredField("launcherState");
        launcherStateField.setAccessible(true);
        launcherStateField.setInt(launcherState, 2);
        player.setCentreY((short) 0x0092);
        player.setYSpeed((short) -0x0800);

        Method updateInvisibleLauncher = OOZLauncherObjectInstance.class.getDeclaredMethod(
                "updateInvisibleLauncher", int.class, AbstractPlayableSprite.class);
        updateInvisibleLauncher.setAccessible(true);
        updateInvisibleLauncher.invoke(launcher, 9342, player);
        assertEquals(0x008A, player.getCentreY() & 0xFFFF,
                "Obj3D_MoveCharacter moves with the old y_vel before Obj48's next successful capture "
                        + "(docs/s2disasm/s2.asm:51176-51188).");

        ball.update(9342, player);

        assertEquals(0x008A, player.getCentreY() & 0xFFFF,
                "Obj48 loc_252F0 reads the position at its own slot pass; if Obj3D just crossed "
                        + "from outside to inside the 32px box later in the frame, capture waits "
                        + "until the next pass (docs/s2disasm/s2.asm:51306-51315).");
        assertEquals(0xF800, player.getYSpeed() & 0xFFFF);
        assertEquals(0x0800, player.getGSpeed() & 0xFFFF);

        ball.update(9343, player);

        assertEquals(ballSpawn.y(), player.getCentreY() & 0xFFFF,
                "The next Obj48 detection pass captures once the pre-pass position is already inside.");
        assertEquals(0, player.getYSpeed());
        assertEquals(0x1000, player.getGSpeed() & 0xFFFF);
    }

    @Test
    void steamSpringLaunchClearsObjectRideState() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        SteamSpringObjectInstance spring = new SteamSpringObjectInstance(
                new ObjectSpawn(0x2000, 0x0400, Sonic2ObjectIds.STEAM_SPRING, 0x00, 0, false, 0));
        spring.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2000, (short) 0x0400);
        player.setOnObject(true);
        player.setAir(false);

        Method applySpring = SteamSpringObjectInstance.class.getDeclaredMethod(
                "applySpring", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        applySpring.setAccessible(true);
        applySpring.invoke(spring, player);

        assertFalse(player.isOnObject(),
                "Obj42 spring launch must clear status.player.on_object like ROM loc_26798");
        verify(objectManager).clearRidingObject(player);
    }

    @Test
    void steamSpringRendersPistonMappingFrameSeven() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_STEAM_PISTON)).thenReturn(renderer);

        SteamSpringObjectInstance spring = new SteamSpringObjectInstance(
                new ObjectSpawn(0x2000, 0x0400, Sonic2ObjectIds.STEAM_SPRING, 0x00, 0, false, 0));
        spring.setServices(new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        spring.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderer).drawFrameIndex(7, 0x2000, 0x0410, false, false);
    }

    @Test
    void steamSpringRightEdgeUsesRomInclusiveSolidObjectGate() {
        SteamSpringObjectInstance spring = new SteamSpringObjectInstance(
                new ObjectSpawn(0x04B0, 0x0140, Sonic2ObjectIds.STEAM_SPRING, 0x00, 0, false, 0));
        spring.snapshotPreUpdatePosition();
        ObjectManager manager = buildSingleObjectManager(spring);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setAir(false);
        tails.setXSpeed((short) -0x100);
        tails.setGSpeed((short) -0x100);
        tails.setCentreX((short) 0x04CB);
        tails.setCentreY((short) 0x0150);

        manager.updateSolidContacts(tails);

        assertTrue(tails.getPushing(),
                "Obj42 SolidObject_cont uses bhi, so relX == $1B*2 must still set Status_Push");
        assertEquals(0, tails.getXSpeed());
        assertEquals(0, tails.getGSpeed());
        assertEquals(0x04CB, tails.getCentreX(),
                "Exact right-edge contact has zero shove distance and should not move Tails");
    }

    @Test
    void arzBossPillarPostPhysicsSidePushPreservesTailsVelocity() {
        ARZBossPillar pillar = new ARZBossPillar(
                new ObjectSpawn(0x2A50, 0x0488, Sonic2ObjectIds.ARZ_BOSS, 0x04, 0, false, 0),
                null);
        ObjectManager manager = buildSingleObjectManager(pillar);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCentreX((short) 0x2A73);
        tails.setCentreY((short) 0x04C0);
        tails.setCpuControlled(true);
        tails.setRenderFlagOnScreen(true);
        tails.setAir(false);
        tails.setPushing(false);
        tails.setXSpeed((short) -0x24);
        tails.setGSpeed((short) -0x24);

        assertTrue(pillar.preservesMovingSideContactVelocity(tails),
                "The Round 50 Obj89 handoff is only preserved while Tails' integer x_pos is still "
                        + "at the pillar edge.");
        manager.processImmediateInlineSolidCheckpoint(pillar, null, List.of(tails));

        assertEquals(0xFFDC, tails.getXSpeed() & 0xFFFF);
        assertEquals(0xFFDC, tails.getGSpeed() & 0xFFFF,
                "Obj89's pillar can run an engine-side post-physics checkpoint after Tails has "
                        + "already applied the ROM-visible CPU/movement velocity. Preserve that "
                        + "velocity while the integrated trace replay verifies the same side "
                        + "contact still carries Status_Push "
                        + "(docs/s2disasm/s2.asm:35424-35436,65330-65374).");
    }

    @Test
    void arzBossPillarKeepsSolidLatchInItsMovingSstSlot() {
        ARZBossPillar pillar = new ARZBossPillar(
                new ObjectSpawn(0x2B70, 0x0510, Sonic2ObjectIds.ARZ_BOSS, 0x04, 1, false, 0),
                null);

        assertTrue(pillar.usesInstanceSolidStateLatchKey(),
                "Obj89 pillar movement rewrites y_pos while its push bit remains owned by the same SST slot");
    }

    @Test
    void springboardSuppressesNativeObjectEdgeBalance() {
        SpringboardObjectInstance springboard = new SpringboardObjectInstance(
                new ObjectSpawn(0x07A4, 0x02E9, Sonic2ObjectIds.SPRINGBOARD, 0x00, 0, false, 0),
                "Springboard");

        assertTrue(springboard.suppressesObjectEdgeBalance(),
                "Obj40_Init sets status.npc.no_balancing before entering the sloped-solid routine");
    }

    @Test
    void mczStomperKeepsSolidLatchInItsMovingSstSlot() {
        StomperObjectInstance stomper = new StomperObjectInstance(
                new ObjectSpawn(0x1230, 0x06A0, Sonic2ObjectIds.STOMPER, 0x00, 0, false, 0),
                "Stomper");

        assertTrue(stomper.usesInstanceSolidStateLatchKey(),
                "Obj2A changes y_pos throughout its cycle while one SST slot owns its contact bits");
        assertTrue(stomper.getSolidRoutineProfile().inclusiveRightEdge(),
                "Obj2A's standard SolidObject BHI gate accepts relX == 2*d1");
        assertTrue(stomper.preservesEdgeSubpixelMotion(),
                "Obj2A exact-edge AtEdge contact must preserve velocity and x_sub");
    }

    @Test
    void arzBossPillarMainPlayerSidePushUsesRomStopPath() {
        ARZBossPillar pillar = new ARZBossPillar(
                new ObjectSpawn(0x2A50, 0x0488, Sonic2ObjectIds.ARZ_BOSS, 0x04, 0, false, 0),
                null);

        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sonic.setGameRulesForTest(GameRules.SONIC_2);
        sonic.setWidth(18);
        sonic.setHeight(18);
        sonic.setCentreX((short) 0x2A73);
        sonic.setCentreY((short) 0x04BC);
        sonic.setCpuControlled(false);
        sonic.setRenderFlagOnScreen(true);
        sonic.setAir(false);
        sonic.setPushing(false);
        sonic.setXSpeed((short) -0x15D);
        sonic.setGSpeed((short) -0x15D);

        assertFalse(pillar.preservesMovingSideContactVelocity(sonic),
                "Obj89's temporary-anchor SolidObject call should use SolidObject_StopCharacter "
                        + "for Sonic because no later sidekick CPU/movement slot overwrites the stop "
                        + "(docs/s2disasm/s2.asm:35424-35436,65531-65539).");
    }

    @Test
    void arzBossPillarInsideSidePushNoLongerPreservesTailsVelocityHandoff() {
        ARZBossPillar pillar = new ARZBossPillar(
                new ObjectSpawn(0x2A50, 0x0488, Sonic2ObjectIds.ARZ_BOSS, 0x04, 0, false, 0),
                null);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCentreX((short) 0x2A72);
        tails.setCentreY((short) 0x04C0);
        tails.setCpuControlled(true);
        tails.setRenderFlagOnScreen(true);
        tails.setAir(false);
        tails.setPushing(true);
        tails.setXSpeed((short) -0x48);
        tails.setGSpeed((short) -0x48);

        assertFalse(pillar.preservesMovingSideContactVelocity(tails),
                "Once Tails' integrated x_pos crosses inside Obj89 pillar's right edge, the object-local "
                        + "handoff no longer suppresses ROM SolidObject_StopCharacter "
                        + "(docs/s2disasm/s2.asm:35424-35436).");
    }

    @Test
    void arzBossPillarOnePixelInsideWithoutPushUsesRomStopPath() {
        ARZBossPillar pillar = new ARZBossPillar(
                new ObjectSpawn(0x2A50, 0x0488, Sonic2ObjectIds.ARZ_BOSS, 0x04, 0, false, 0),
                null);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCentreX((short) 0x2A72);
        tails.setCentreY((short) 0x04C0);
        tails.setCpuControlled(true);
        tails.setRenderFlagOnScreen(true);
        tails.setAir(false);
        tails.setPushing(false);
        tails.setXSpeed((short) -0x57);
        tails.setGSpeed((short) -0x57);

        assertFalse(pillar.preservesMovingSideContactVelocity(tails),
                "ARZ2 f5845: after Tails' integrated x_pos crosses one pixel inside Obj89's right edge, "
                        + "ROM reaches SolidObject_StopCharacter even if Status_Push was not already set "
                        + "(docs/s2disasm/s2.asm:35424-35436,65531-65539).");
    }

    @Test
    void arzBossPillarReleasedSidePushKeepsTailsCpuAutoJumpGateVisible() {
        ARZBossPillar pillar = new ARZBossPillar(
                new ObjectSpawn(0x2A50, 0x0488, Sonic2ObjectIds.ARZ_BOSS, 0x04, 0, false, 0),
                null);

        assertTrue(pillar.usesInclusiveRightEdge(),
                "Obj89's standard SolidObject BHI gate accepts relX == 2*d1");
        assertTrue(pillar.preservesEdgeSubpixelMotion(),
                "Obj89's exact-edge d0=0 correction preserves the native x_sub low word");

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCentreX((short) 0x2A73);
        tails.setCentreY((short) 0x04C0);
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setOnObject(false);
        tails.setRolling(false);

        assertTrue(pillar.preservesSidekickCpuPushGraceAfterRideClears(tails),
                "Obj89_Pillar_SolidObject writes Tails' Status_Push before TailsCPU_Normal reads "
                        + "the push-bypass auto-jump gate; the engine may have cleared the local "
                        + "push by the CPU read, so the object exposes a narrow side-edge grace "
                        + "(docs/s2disasm/s2.asm:39287-39300,65330-65339,65531-65539).");

        tails.setCentreX((short) 0x2A70);
        assertFalse(pillar.preservesSidekickCpuPushGraceAfterRideClears(tails));
    }

    @Test
    void arzBossArrowReleasedRideKeepsTailsCpuAutoJumpGateVisible() throws Exception {
        ARZBossArrow arrow = new ARZBossArrow(
                new ObjectSpawn(0x2AFF, 0x0481, Sonic2ObjectIds.ARZ_BOSS, 0x06, 0, false, 0),
                null, null, true);
        arrow.setSlotIndex(0x14);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCentreX((short) 0x2A73);
        tails.setCentreY((short) 0x04C0);
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setOnObject(false);
        tails.setRolling(false);
        tails.setInteractSlotIndex(0x14);

        assertTrue(arrow.preservesMovingSidekickCpuPushAtZeroGraceFromInteractSlot(tails),
                "At ARZ2 f6364 the local grace counter has already reached zero, but Tails' "
                        + "interact slot still dereferences the Obj89 arrow status byte for the "
                        + "same push-bypass CPU read (docs/s2disasm/s2.asm:39297-39300,65689-65704).");
        assertTrue(arrow.publishesSidekickCpuPushFromInteractSlot(tails),
                "TailsCPU_Normal must publish Obj89's retained Status_Push into the same-frame "
                        + "movement/animation dispatch (docs/s2disasm/s2.asm:39297-39300,"
                        + "40484-40491,65689-65704).");
        assertFalse(arrow.preservesSidekickCpuPushGraceAfterRideClears(tails),
                "The ARZ arrow only needs the zero-grace interact-slot bridge; the broader released-ride "
                        + "window would keep Status_Push visible after Tails has reattached to the arrow.");
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sonic.setGameRulesForTest(GameRules.SONIC_2);
        sonic.setWidth(18);
        sonic.setHeight(18);
        sonic.setCentreX((short) 0x2A73);
        sonic.setCentreY((short) 0x04C0);
        sonic.setCpuControlled(false);
        sonic.setAir(false);
        sonic.setOnObject(false);
        sonic.setRolling(false);
        sonic.setInteractSlotIndex(0x14);

        assertFalse(arrow.preservesSidekickCpuPushGraceAfterRideClears(sonic),
                "The push-grace bridge is only for CPU sidekick reads of TailsCPU_Normal.");
        assertFalse(arrow.preservesMovingSidekickCpuPushAtZeroGraceFromInteractSlot(sonic),
                "The zero-grace interact bridge is only for CPU sidekick reads of TailsCPU_Normal.");
        assertFalse(arrow.publishesSidekickCpuPushFromInteractSlot(sonic),
                "The interact-slot push publication is only for CPU sidekick reads of TailsCPU_Normal.");
    }

    @Test
    void arzBossArrowKeepsClearedNativeBalanceWidth() {
        ARZBossArrow arrow = new ARZBossArrow(
                new ObjectSpawn(0x2A77, 0x0481, Sonic2ObjectIds.ARZ_BOSS, 0x06, 0, false, 0),
                null, null, false);

        assertEquals(0, arrow.getBalanceWidthPixels(),
                "Obj89's arrow allocation leaves width_pixels cleared even though its solid collision is wider");
    }

    @Test
    void steamPuffDoesNotUseMarkObjGoneUnloadWindow() {
        SteamPuffObjectInstance puff = new SteamPuffObjectInstance(0x0208, 0x0270, true);

        assertTrue(puff.usesCustomOutOfRangeCheck(),
                "Obj42 routine 4 tails to DisplaySprite, not MarkObjGone");
        assertFalse(puff.isCustomOutOfRange(0x0306),
                "Obj42 steam puffs must survive off-screen until their animation deletes them");
    }

    @Test
    void spikyBlockRendersParentBlockMappingFrameFour() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_SPIKE_BLOCK)).thenReturn(renderer);

        SpikyBlockObjectInstance block = new SpikyBlockObjectInstance(
                new ObjectSpawn(0x1800, 0x0500, Sonic2ObjectIds.SPIKY_BLOCK, 0x00, 0, false, 0),
                "SpikyBlock");
        block.setServices(new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        block.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderer).drawFrameIndex(4, 0x1800, 0x0500, false, false);
    }

    @Test
    void signpostSurvivesMetropolisAct2WhenServicesUseRomZoneId() {
        ObjectManager objectManager = mock(ObjectManager.class);
        SonicConfigurationService config = mock(SonicConfigurationService.class);
        when(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE)).thenReturn("sonic");
        SignpostObjectInstance signpost = new SignpostObjectInstance(
                new ObjectSpawn(0x2800, 0x0300, 0x0D, 0x00, 0, true, 0),
                "Signpost");
        signpost.setServices(new ZoneActServices(objectManager, Sonic2ZoneConstants.ROM_ZONE_MTZ, 1, config));

        signpost.update(0, new TestablePlayableSprite("sonic", (short) 0x2700, (short) 0x0300));

        assertFalse(signpost.isDestroyed(),
                "Obj0D must keep the MTZ Act 2 signpost when currentZone is the ROM zone id");
        verify(objectManager, never()).markRemembered(signpost.getSpawn());
    }

    @Test
    void mtzAct3LongPlatformUsesRomZoneIdForTwoStopConveyor() throws Exception {
        MTZLongPlatformObjectInstance platform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x1CBE, 0x0300, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x05, 0, false, 0));
        platform.setServices(new ZoneActServices(null, Sonic2ZoneConstants.ROM_ZONE_MTZ_3, 0, null));

        platform.update(0, new TestablePlayableSprite("sonic", (short) 0x1CBE, (short) 0x02E0));

        assertEquals(0, intField(platform, "moveSubtype"),
                "MTZ Act 3 subtype-5 conveyor must stop at the first MTZ3 stop point");
        assertEquals(0x1CC0, platform.getX(),
                "Regression setup should land exactly on the first MTZ3 stop point");
    }

    @Test
    void mtzAct3LongPlatformKeepsMovingRightThroughMtz12StopPoint() throws Exception {
        MTZLongPlatformObjectInstance platform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x1BBE, 0x04C8, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x05, 0, false, 0));
        platform.setServices(new ZoneActServices(null, Sonic2ZoneConstants.ROM_ZONE_MTZ_3, 0, null));

        platform.update(0, new TestablePlayableSprite("sonic", (short) 0x1BBE, (short) 0x04AC));
        platform.update(1, new TestablePlayableSprite("sonic", (short) 0x1BC0, (short) 0x04AC));

        assertEquals(0x1BC2, platform.getX(),
                "ROM Obj65 loc_26E4A only treats $1BC0 as a reverse point outside metropolis_zone_2");
        assertEquals(5, intField(platform, "moveSubtype"),
                "MTZ3 must continue subtype-5 conveyor motion until $1CC0 or $2940");
    }

    @Test
    void mtzLongPlatformSubtype5StalesLogicalHorizontalInputWhileRiding() {
        MTZLongPlatformObjectInstance conveyor = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x1C86, 0x04C8, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x05, 0, false, 0));
        MTZLongPlatformObjectInstance secondStopConveyor = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x28AE, 0x04C8, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x05, 0, false, 0));
        MTZLongPlatformObjectInstance lateSecondStopConveyor = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x28FC, 0x04C8, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x05, 0, false, 0));
        MTZLongPlatformObjectInstance earlyConveyor = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x1A7E, 0x04C8, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x05, 0, false, 0));
        MTZLongPlatformObjectInstance stationary = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x1C86, 0x04C8, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x00, 0, false, 0));
        TestablePlayableSprite facingRight = new TestablePlayableSprite("sonic", (short) 0x1C9F, (short) 0x04A8);
        TestablePlayableSprite facingLeft = new TestablePlayableSprite("sonic", (short) 0x1A96, (short) 0x04A8);
        TestablePlayableSprite cpuTails = new TestablePlayableSprite("tails", (short) 0x1C9F, (short) 0x04A8);
        facingLeft.setDirection(Direction.LEFT);
        cpuTails.setCpuControlled(true);

        assertEquals(3, conveyor.staleHorizontalLogicalInputFramesWhileRiding(facingRight, 1, false, true),
                "Obj65 loc_26E4A changes x_pos before SolidObject, while Sonic_Move consumes "
                        + "Ctrl_1_Held_Logical (docs/s2disasm/s2.asm:53159-53220,36552-36567)");
        assertEquals(3, secondStopConveyor.staleHorizontalLogicalInputFramesWhileRiding(facingRight, 1, false, true),
                "MTZ3's second Obj65 stop at $2940 uses the same loc_26E4A/SolidObject timing "
                        + "as the $1CC0 stop approach (docs/s2disasm/s2.asm:53159-53220)");
        assertEquals(0, lateSecondStopConveyor.staleHorizontalLogicalInputFramesWhileRiding(facingRight, 1, false, true),
                "The later MTZ3 right edge at platform X=$28FC is consumed immediately by Sonic_Move");
        assertEquals(0, conveyor.staleHorizontalLogicalInputFramesWhileRiding(facingLeft, 1, false, true),
                "Sonic_MoveRight flips status.player.x_flip and accelerates immediately when Sonic starts facing left");
        assertEquals(0, conveyor.staleHorizontalLogicalInputFramesWhileRiding(cpuTails, 1, false, true),
                "CPU Tails writes Ctrl_2_Logical before Tails_Move consumes it "
                        + "(docs/s2disasm/s2.asm:39381,39673-39688)");
        assertEquals(0, earlyConveyor.staleHorizontalLogicalInputFramesWhileRiding(facingRight, 1, false, true),
                "Earlier subtype-5 movement before the MTZ3 $1CC0 stop approach consumes the right edge immediately");
        assertEquals(0, stationary.staleHorizontalLogicalInputFramesWhileRiding(facingRight, 1, false, true),
                "Only the subtype-5 conveyor carry path uses the stale logical-input window");
    }

    @Test
    void mtzLongPlatformProximityChecksNativeSidekick() throws Exception {
        MTZLongPlatformObjectInstance platform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x0AA0, 0x076C, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x13, 0, false, 0x076C));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0A40, (short) 0x076E);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0A85, (short) 0x076E);
        tails.setCpuControlled(true);
        platform.setServices(new StubObjectServices().withPlayerQuery(
                new ObjectPlayerQuery(() -> sonic, () -> List.of(tails))));
        setIntField(platform, "currentDist", 0x40);
        setIntField(platform, "x", 0x0A60);

        platform.update(0, sonic);

        assertEquals(0x40, intField(platform, "currentDist"),
                "Obj65 loc_26D94 checks Sidekick after MainCharacter before retracting");
        assertEquals(0x0A60, platform.getX(),
                "A native P2/Tails inside the proximity box must keep the fully extended platform stationary");
    }

    @Test
    void mtzLongPlatformDefersBit7ChildCogUntilFirstRoutinePass() {
        ObjectManager objectManager = mock(ObjectManager.class);
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };

        MTZLongPlatformObjectInstance platform = ObjectConstructionContext.construct(services,
                () -> new MTZLongPlatformObjectInstance(
                        new ObjectSpawn(0x0600, 0x01B0, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x80, 0, false, 0)));
        platform.setServices(services);

        verify(objectManager, never()).addDynamicObjectAfterCurrent(
                org.mockito.ArgumentMatchers.any(AbstractObjectInstance.class));

        platform.update(0, new TestablePlayableSprite("sonic", (short) 0x0600, (short) 0x01B0));

        verify(objectManager).addDynamicObjectAfterCurrent(
                org.mockito.ArgumentMatchers.argThat(MTZLongPlatformCogInstance.class::isInstance));
    }

    @Test
    void mtzLongPlatformLandingWidthUsesRomWidthPixels() {
        MTZLongPlatformObjectInstance platform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x0B20, 0x076C, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x13, 1, false, 0x276C));

        assertEquals(0x25, platform.getSolidParams().halfWidth(),
                "Obj65 passes width_pixels+$5 to SolidObject");
        assertEquals(0x20, platform.getTopLandingHalfWidth(null, platform.getSolidParams().halfWidth()),
                "SolidObject_Landed re-reads Obj65 width_pixels, not the common width_pixels+$B default");
    }

    @Test
    void mtzLongPlatformMappingFrameOneSuppressesObjectEdgeBalance() {
        MTZLongPlatformObjectInstance normalPlatform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x0B20, 0x076C, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x00, 0, false, 0));
        MTZLongPlatformObjectInstance noBalancePlatform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x0B20, 0x076C, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x10, 0, false, 0));

        assertFalse(normalPlatform.suppressesObjectEdgeBalance(),
                "Obj65 mapping_frame 0 leaves status.npc.no_balancing clear");
        assertTrue(noBalancePlatform.suppressesObjectEdgeBalance(),
                "Obj65_Init sets status.npc.no_balancing when mapping_frame == 1 (s2.asm:52865-52870)");
    }

    @Test
    void mtzLongPlatformBalanceUsesRomWidthPixels() {
        MTZLongPlatformObjectInstance mtz3Conveyor = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x19C0, 0x04C8, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x04, 1, false, 0));
        MTZLongPlatformObjectInstance widePlatform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x0B20, 0x076C, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x10, 0, false, 0));

        assertEquals(0x40, mtz3Conveyor.getBalanceWidthPixels(),
                "MTZ3 Obj65 subtype $04 balances against its $40 width_pixels, not rendered bounds");
        assertEquals(0x20, widePlatform.getBalanceWidthPixels(),
                "Obj65 subtype $10 balances against the width selected by its ROM property offset");
    }

    @Test
    void mtzLongPlatformOptsIntoZeroXSpeedLeftSideStopCharacter() {
        MTZLongPlatformObjectInstance platform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x1090, 0x01EC, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x00, 0, false, 0));

        assertTrue(platform.zeroXSpeedStopsOnLeftSideContact(),
                "Obj65 reaches S2 SolidObject_InsideLeft with x_vel == 0; that falls through to "
                        + "SolidObject_StopCharacter and clears inertia (docs/s2disasm/s2.asm:35424-35439)");
    }

    @Test
    void mczRotPformsUseSolidObjectContStatusTiming() {
        MCZRotPformsObjectInstance platform = new MCZRotPformsObjectInstance(
                new ObjectSpawn(0x0E80, 0x05A0, Sonic2ObjectIds.MCZ_ROT_PFORMS, 0x00, 0, false, 0),
                "MCZRotPforms");
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0EAB, (short) 0x05F0);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0EAB, (short) 0x05F0);
        tails.setCpuControlled(true);

        assertTrue(platform.usesInclusiveRightEdge(),
                "Obj6A reaches JmpTo13_SolidObject, and SolidObject_cont rejects the right edge with bhi "
                        + "(docs/s2disasm/s2.asm:54276,54301,35344-35354)");
        assertTrue(platform.usesInstanceSolidStateLatchKey(),
                "Obj6A rewrites dynamic spawn coordinates while ROM keeps standing/pushing bits in the live SST slot");
        assertFalse(platform.preservesSidekickCpuPushGraceWhileRiding(sonic));
        assertTrue(platform.preservesSidekickCpuPushGraceWhileRiding(tails),
                "TailsCPU_Normal reads Status_Push before the next Obj6A SolidObject pass clears it");
        assertEquals(8, platform.sidekickCpuPushGraceMinimumFramesWhileRiding(tails),
                "MCZ2 f4485 keeps the post-Obj6A push bit visible to the Tails CPU slot with eight grace frames");
        assertEquals(0x20, platform.getBalanceWidthPixels(),
                "Obj6A balancing consumes the native MTZ width_pixels field");
    }

    @Test
    void slidingSpikesExposeNativeBalanceWidth() {
        SlidingSpikesObjectInstance spikes = new SlidingSpikesObjectInstance(
                new ObjectSpawn(0x0DF7, 0x04B0, Sonic2ObjectIds.SLIDING_SPIKES, 0, 0, false, 0),
                "SlidingSpikes");

        assertEquals(0x40, spikes.getBalanceWidthPixels(),
                "Obj76 balancing uses its $40 width_pixels rather than rendered bounds");
    }

    @Test
    void htzRisingLavaSubtypeSixUsesCpuSidekickObjectOrderInputDelay() {
        RisingLavaObjectInstance lowerRoutePlatform = new RisingLavaObjectInstance(
                new ObjectSpawn(0x1760, 0x07D4, Sonic2ObjectIds.RISING_LAVA, 0x06, 0, false, 0),
                "RisingLava");
        RisingLavaObjectInstance slopedPlatform = new RisingLavaObjectInstance(
                new ObjectSpawn(0x1760, 0x07D4, Sonic2ObjectIds.RISING_LAVA, 0x08, 0, false, 0),
                "RisingLavaSlope");
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x170A, (short) 0x074D);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x170A, (short) 0x074D);
        tails.setCpuControlled(true);

        assertTrue(lowerRoutePlatform.usesSidekickCpuCurrentPushObjectOrderInputDelay(tails),
                "HTZ2 f3322 reaches Obj30 subtype 6's SolidObject_Always/DropOnFloor ordering with "
                        + "Tails' current Status_Push still visible but the adjacent delayed input already flipped");
        assertFalse(lowerRoutePlatform.usesSidekickCpuCurrentPushObjectOrderInputDelay(sonic),
                "The bridge is only for CPU sidekick Ctrl_2 sampling");
        assertFalse(slopedPlatform.usesSidekickCpuCurrentPushObjectOrderInputDelay(tails),
                "Subtype 8 uses SlopedSolid and is not part of the HTZ2 lower-route Obj30 ordering window");
        tails.setCentreX((short) 0x19BA);
        assertFalse(new RisingLavaObjectInstance(
                        new ObjectSpawn(0x1920, 0x06B9, Sonic2ObjectIds.RISING_LAVA, 0x06, 0, false, 0),
                        "RisingLavaRightSide")
                        .usesSidekickCpuCurrentPushObjectOrderInputDelay(tails),
                "HTZ2 f4442 rides subtype 6 on the right side; ROM keeps the normal d1 history word "
                        + "already loaded at s2.asm:39291-39300 instead of the adjacent older input");
    }

    @Test
    void cpzStaircasePreservesRidingPushOnlyAtLowerStepSideOverlap() {
        CPZStaircaseObjectInstance staircase = new CPZStaircaseObjectInstance(
                new ObjectSpawn(0x2090, 0x0350, Sonic2ObjectIds.CPZ_STAIRCASE, 0x01, 1, false, 0),
                "CPZStaircase");
        for (int frame = 0; frame < 0x20; frame++) {
            staircase.update(frame, null);
        }

        TestablePlayableSprite tails = new TestablePlayableSprite(
                "tails", (short) staircase.getPieceX(2), (short) staircase.getPieceY(2));
        tails.setCpuControlled(true);
        tails.setDirection(Direction.RIGHT);

        assertFalse(staircase.preservesRidingPushStatus(tails),
                "CPZ1 f4351 has Tails near the centre of Obj78 slot 0x1F; ROM has no Status_Push, "
                        + "so TailsCPU_Normal must still consume the +1 FollowRight nudge");

        tails.setDirection(Direction.LEFT);
        assertFalse(staircase.preservesSidekickCpuPushGraceWhileRiding(tails),
                "Obj78 CPU-only grace models child-slot side-push visibility; it must not apply when "
                        + "Tails is centered on a stair piece with no adjacent side overlap");
        assertTrue(staircase.preservesSidekickDelayedLeaderPushWhileRiding(tails),
                "Obj78 child SolidObject slots can keep the delayed Sonic_Stat_Record_Buf push visible "
                        + "while CPU Tails rides the folded staircase (docs/s2disasm/s2.asm:55967-56021)");

        tails.setDirection(Direction.RIGHT);
        tails.setCentreX((short) (staircase.getPieceX(3) - staircase.getPieceParams(3).halfWidth()));
        assertTrue(staircase.preservesRidingPushStatus(tails),
                "Obj78's folded multi-piece latch is still needed when the rider is actually pressed "
                        + "into the lower neighbouring child slot's side");

        tails.setCentreY((short) (staircase.getPieceY(3)
                - staircase.getPieceParams(3).airHalfHeight() - tails.getYRadius() - 5));
        assertFalse(staircase.preservesRidingPushStatus(tails),
                "CPZ2 f5296 is horizontally inside the next child edge but vertically above its "
                        + "SolidObject box, so the distant step cannot preserve Status_Push");

        TestablePlayableSprite sonic = new TestablePlayableSprite(
                "sonic", (short) staircase.getPieceX(2), (short) staircase.getPieceY(2));
        assertFalse(staircase.preservesSidekickDelayedLeaderPushWhileRiding(sonic),
                "The delayed leader push bridge is only for CPU sidekick follow control");
    }

    @Test
    void cpzStaircaseKeepsStandardSolidObjectExactRightEdgeContact() {
        CPZStaircaseObjectInstance staircase = new CPZStaircaseObjectInstance(
                new ObjectSpawn(0x1510, 0x0702, Sonic2ObjectIds.CPZ_STAIRCASE, 0x01, 1, false, 0),
                "CPZStaircase");

        assertTrue(staircase.getSolidRoutineProfile().inclusiveRightEdge(),
                "Obj78's SolidObject BHI gate accepts relX == 2*d1");
    }

    @Test
    void mtzPlatformKeepsStandardSolidObjectExactRightEdgeContact() {
        MTZPlatformObjectInstance platform = new MTZPlatformObjectInstance(
                new ObjectSpawn(0x1ECE, 0x0670, Sonic2ObjectIds.MTZ_PLATFORM, 0x04, 0, false, 0),
                "CPZSquarePform");

        assertTrue(platform.getSolidRoutineProfile().inclusiveRightEdge(),
                "Obj6B's SolidObject BHI gate accepts relX == 2*d1");
    }

    @Test
    void cpzStaircaseKeepsCpuTailsCurrentPushWhenFacingHigherAdjacentStep() {
        CPZStaircaseObjectInstance staircase = new CPZStaircaseObjectInstance(
                new ObjectSpawn(0x1510, 0x0702, Sonic2ObjectIds.CPZ_STAIRCASE, 0x01, 1, false, 0),
                "CPZStaircase");
        for (int frame = 0; frame < 0x20; frame++) {
            staircase.update(frame, null);
        }

        TestablePlayableSprite tails = new TestablePlayableSprite(
                "tails", (short) (staircase.getPieceX(1) - 5), (short) staircase.getPieceY(1));
        tails.setCpuControlled(true);
        tails.setDirection(Direction.LEFT);

        assertTrue(staircase.preservesRidingPushStatus(tails),
                "CPZ2 f5285 has Tails on the lower Obj78 child facing the higher child slot; "
                        + "the ROM child SolidObject pass leaves Tails' current Status_Push visible "
                        + "for TailsCPU_Normal's push bypass");
        assertFalse(staircase.preservesSidekickDelayedLeaderPushWhileRiding(tails),
                "When Obj78 already preserves Tails' current Status_Push, the delayed leader sample "
                        + "must not also be forced to pushing or TailsCPU_Normal misses the auto-jump path");
        assertTrue(staircase.preservesSidekickCpuPushGraceWhileRiding(tails),
                "The same child-slot side contact supplies the ROM-visible current push grace when "
                        + "the folded engine status was already cleared before TailsCPU_Normal");
        assertTrue(staircase.usesSidekickCpuPushBypassObjectOrderStatusDelay(tails),
                "TailsCPU_Normal must compare that current push against Obj78's object-order leader "
                        + "status sample, not the final-frame status column");

        TestablePlayableSprite sonic = new TestablePlayableSprite(
                "sonic", (short) (staircase.getPieceX(1) - 5), (short) staircase.getPieceY(1));
        sonic.setDirection(Direction.LEFT);
        assertTrue(staircase.preservesRidingPushStatus(sonic),
                "The same folded child-slot push is visible to Sonic_Stat_Record_Buf when Sonic is "
                        + "also pressed into the higher Obj78 child slot, as at CPZ2 f5221");

        tails.setCentreX((short) (staircase.getPieceX(3) - 5));
        assertTrue(staircase.preservesSidekickDelayedLeaderPushWhileRiding(tails),
                "Later Obj78 child-slot contact still preserves the delayed leader push window that "
                        + "keeps CPZ2 f5221 on the normal follow-steering path");
        assertFalse(staircase.usesSidekickCpuPushBypassObjectOrderStatusDelay(tails),
                "The f5221 later-child window must keep the final delayed leader push sample; only "
                        + "the first-child f5285 handoff uses the object-order status byte");
    }

    @Test
    void mtzConveyorUsesPlatformObjectD3ForLandingSnap() {
        ConveyorObjectInstance conveyor = new ConveyorObjectInstance(
                new ObjectSpawn(0x1720, 0x0519, Sonic2ObjectIds.CONVEYOR, 0x01, 0, false, 0),
                "Conveyor");

        assertEquals(8, conveyor.getSolidParams().groundHalfHeight(),
                "Obj6C_Main passes d3=8 to PlatformObject for both ChkYRange and MvSonicOnPtfm");
    }

    @Test
    void mtzLongPlatformOutOfRangeUsesStoredBaseX() throws Exception {
        MTZLongPlatformObjectInstance platform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x0B20, 0x076C, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x13, 1, false, 0x276C));
        setIntField(platform, "x", 0x0AE0);

        assertEquals(0x0B20, platform.getOutOfRangeReferenceX(),
                "Obj65 loc_26C1C checks objoff_34, not moving x_pos(a0), for MarkObjGone");
    }

    @Test
    void genericPlatformOutOfRangeUsesStoredOriginX() throws Exception {
        ARZPlatformObjectInstance platform = new ARZPlatformObjectInstance(
                new ObjectSpawn(0x1940, 0x06C8, Sonic2ObjectIds.GENERIC_PLATFORM_A, 0x01, 0, false, 0x06C8),
                "GenericPlatform");
        setIntField(platform, "x", 0x190D);

        assertEquals(0x1940, platform.getOutOfRangeReferenceX(),
                "Obj18_Despawn checks obj18_x_origin, not moving x_pos(a0), before deleting");
    }

    @Test
    void largeRotPformOutOfRangeUsesStoredBaseX() throws Exception {
        LargeRotPformObjectInstance platform = new LargeRotPformObjectInstance(
                new ObjectSpawn(0x0BC0, 0x06C0, Sonic2ObjectIds.LARGE_ROT_PFORM, 0x20, 1, false, 0x26C0),
                "LargeRotPform");
        setIntField(platform, "x", 0x0B9A);

        assertEquals(0x0BC0, platform.getOutOfRangeReferenceX(),
                "Obj6E loc_28466 checks objoff_34, not moving x_pos(a0), for MarkObjGone");
    }

    @Test
    void mtzPlatformOutOfRangeUsesStoredBaseX() throws Exception {
        MTZPlatformObjectInstance platform = new MTZPlatformObjectInstance(
                new ObjectSpawn(0x0BC0, 0x0630, Sonic2ObjectIds.MTZ_PLATFORM, 0x02, 1, false, 0x2630),
                "MTZPlatform");
        setIntField(platform, "x", 0x0B63);

        assertEquals(0x0BC0, platform.getOutOfRangeReferenceX(),
                "Obj6B_Main checks objoff_34, not moving x_pos(a0), for MarkObjGone2");
    }

    @Test
    void cnzRectBlockExposesLiveNativePositionAndBalanceWidth() {
        CNZRectBlocksObjectInstance block = new CNZRectBlocksObjectInstance(
                new ObjectSpawn(0x1380, 0x0410, Sonic2ObjectIds.CNZ_RECT_BLOCKS, 0x00, 0, false, 0),
                "CNZRectBlocks");

        assertEquals(0x1358, block.getX());
        assertEquals(0x0428, block.getY());
        assertEquals(0x08, block.getBalanceWidthPixels());
        assertEquals(0, block.getSolidParams().offsetX());
        assertEquals(0, block.getSolidParams().offsetY());
        assertFalse(block.carriesRiderOnHorizontalMove(null));
        assertEquals(0x1380, block.getOutOfRangeReferenceX(),
                "ObjD2 unloads against objoff_30 rather than its moving x_pos");
        assertFalse(block.suppressesObjectEdgeBalance());
    }

    @Test
    void mtzPlatformExposesSubtypeWidthToObjectBalance() {
        MTZPlatformObjectInstance widePlatform = new MTZPlatformObjectInstance(
                new ObjectSpawn(0x0460, 0x052C, Sonic2ObjectIds.MTZ_PLATFORM, 0x07, 0, false, 0),
                "MTZPlatform");
        MTZPlatformObjectInstance narrowPlatform = new MTZPlatformObjectInstance(
                new ObjectSpawn(0x0460, 0x052C, Sonic2ObjectIds.MTZ_PLATFORM, 0x10, 0, false, 0),
                "MTZPlatform");

        assertEquals(0x20, widePlatform.getBalanceWidthPixels());
        assertEquals(0x10, narrowPlatform.getBalanceWidthPixels());
        assertFalse(widePlatform.suppressesObjectEdgeBalance());
    }

    @Test
    void s2SpikesUseLiveRollingRadiusForBottomOverlap() {
        SpikeObjectInstance spikes = new SpikeObjectInstance(
                new ObjectSpawn(0x0C40, 0x0650, Sonic2ObjectIds.SPIKES, 0x30, 2, false, 0x4650),
                "Spikes");

        assertTrue(spikes.fullSolidBottomOverlapUsesCurrentYRadiusOnly(null),
                "Obj36 SolidObject_cont doubles live y_radius(a1), so rolling underside contact must not use stand radius");
    }

    @Test
    void s2SpikesUseSolidObjectAirborneStaleStandingBitReturn() {
        SpikeObjectInstance spikes = new SpikeObjectInstance(
                new ObjectSpawn(0x0C40, 0x0650, Sonic2ObjectIds.SPIKES, 0x30, 2, false, 0x4650),
                "Spikes");

        assertTrue(spikes.airborneStaleStandingBitReturnsNoContact(null),
                "Obj36 calls the shared SolidObject path; an airborne stale standing bit returns before new contact");
    }

    @Test
    void s2SpikesDoNotClaimPushGraceFromUnrelatedSolid() {
        SpikeObjectInstance spikes = new SpikeObjectInstance(
                new ObjectSpawn(0x0CF0, 0x0594, Sonic2ObjectIds.SPIKES, 0x30, 2, false, 0x4650),
                "Spikes");
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0CE3, (short) 0x0574);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0CE3, (short) 0x0574);
        tails.setCpuControlled(true);

        assertFalse(spikes.preservesSidekickCpuPushGraceWhileRiding(sonic));
        assertEquals(Integer.MAX_VALUE, spikes.sidekickCpuPushGraceMinimumFramesWhileRiding(sonic));
        assertEquals(Integer.MAX_VALUE, spikes.sidekickCpuPushGraceMaximumFramesWhileRiding(sonic));
        assertFalse(spikes.preservesSidekickCpuPushGraceWhileRiding(tails),
                "Obj36 must not reinterpret push grace produced by a distinct solid as its own riding state");
        assertEquals(Integer.MAX_VALUE, spikes.sidekickCpuPushGraceMinimumFramesWhileRiding(tails));
        assertEquals(Integer.MAX_VALUE, spikes.sidekickCpuPushGraceMaximumFramesWhileRiding(tails));
    }

    @Test
    void spikeTouchChkHurt2RewindsCurrentYVelocityBeforeHurt() {
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getPreContactYSpeed()).thenReturn((short) 0xFE30);
        SpikeObjectInstance spikes = new SpikeObjectInstance(
                new ObjectSpawn(0x0C40, 0x0650, Sonic2ObjectIds.SPIKES, 0x30, 2, false, 0x4650),
                "Spikes");
        spikes.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });
        PlayableEntity tails = mock(PlayableEntity.class);
        when(tails.isCpuControlled()).thenReturn(true);
        when(tails.getYSpeed()).thenReturn((short) 0xFE68);

        spikes.onSolidContact(tails, new SolidContact(false, false, true, false, false), 0);

        InOrder order = inOrder(tails);
        order.verify(tails).move((short) 0, (short) 0x0198);
        order.verify(tails).applyHurt(0x0C40, DamageCause.SPIKE);
        verify(tails, never()).applyHurt(0x0C40);
        verify(objectManager, never()).getPreContactYSpeed();
    }

    @Test
    void spikeTouchChkHurt2SkipsAfterSolidObjectCrushDeath() {
        SpikeObjectInstance spikes = new SpikeObjectInstance(
                new ObjectSpawn(0x0C40, 0x0650, Sonic2ObjectIds.SPIKES, 0x30, 2, false, 0x4650),
                "Spikes");
        spikes.setServices(new StubObjectServices());
        PlayableEntity tails = mock(PlayableEntity.class);
        when(tails.getDead()).thenReturn(true);
        when(tails.isCpuControlled()).thenReturn(true);
        when(tails.getYSpeed()).thenReturn((short) 0xFE68);

        spikes.onSolidContact(tails, new SolidContact(false, false, true, false, false), 0);

        verify(tails, never()).move((short) 0, (short) 0x0198);
        verify(tails, never()).applyHurt(0x0C40);
        verify(tails, never()).applyHurtOrDeath(0x0C40, true, false);
    }

    @Test
    void mtzPlatformsExposeFullSolidRoutineProfiles() {
        MTZPlatformObjectInstance platform = new MTZPlatformObjectInstance(
                new ObjectSpawn(0x1000, 0x0300, Sonic2ObjectIds.MTZ_PLATFORM, 0x00, 0, false, 0),
                "MTZPlatform");
        MTZLongPlatformObjectInstance longPlatform = new MTZLongPlatformObjectInstance(
                new ObjectSpawn(0x1400, 0x0300, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x00, 0, false, 0));

        SolidRoutineProfile profile = platform.getSolidRoutineProfile();
        SolidRoutineProfile longProfile = longPlatform.getSolidRoutineProfile();

        assertEquals(SolidRoutineKind.FULL_SOLID, profile.kind());
        assertEquals(platform.isTopSolidOnly(), profile.topSolidOnly());
        assertEquals(platform.usesStickyContactBuffer(), profile.stickyContactBuffer());
        assertEquals(SolidRoutineKind.FULL_SOLID, longProfile.kind());
        assertEquals(longPlatform.isTopSolidOnly(), longProfile.topSolidOnly());
        assertEquals(longPlatform.usesStickyContactBuffer(), longProfile.stickyContactBuffer());
        assertEquals(longPlatform.carriesRiderOnHorizontalMove(null),
                longProfile.carriesAirborneRiderAfterExitPlatform());
    }

    @Test
    void mtzPlatformType5StandingContactPreservesYSubpixelWhenArmingFall() throws Exception {
        MTZPlatformObjectInstance platform = new MTZPlatformObjectInstance(
                new ObjectSpawn(0x0460, 0x04EC, Sonic2ObjectIds.MTZ_PLATFORM, 0x05, 0, false, 0),
                "MTZPlatform");
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0460, (short) 0x052C);
        setIntField(platform, "yFixed", (0x04EC << 16) | 0xF000);

        platform.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        platform.update(1, player);

        assertEquals(6, intField(platform, "moveType"),
                "Obj6B type 5 must consume the standing bit on the following Obj6B dispatch");
        assertEquals(platform.getY() << 16 | 0xF000, intField(platform, "yFixed"),
                "Obj6B type 5 uses move.w y_pos and must preserve y_sub for the following ObjectMove");
    }

    @Test
    void mtzPlatformFallingUsesRomSixteenBitSubpixelCarry() throws Exception {
        MTZPlatformObjectInstance platform = new MTZPlatformObjectInstance(
                new ObjectSpawn(0x0460, 0x04EC, Sonic2ObjectIds.MTZ_PLATFORM, 0x06, 0, false, 0),
                "MTZPlatform");
        platform.setServices(new StubObjectServices());
        setIntField(platform, "yFixed", (0x052C << 16) | 0xF000);
        setIntField(platform, "y", 0x052C);
        setIntField(platform, "yVel", 0x0010);

        platform.update(0, new TestablePlayableSprite("sonic", (short) 0x0460, (short) 0x052C));

        assertEquals(0x052D, platform.getY(),
                "ROM loc_27EE2 adds y_vel<<8 to y_pos.w:y_sub.w, so the preserved low word can carry");
        assertEquals(0x0018, intField(platform, "yVel"));
    }

    @Test
    void mtzPlatformBouncyContactArmsBounceBeforeNextDispatch() throws Exception {
        MTZPlatformObjectInstance platform = new MTZPlatformObjectInstance(
                new ObjectSpawn(0x0460, 0x052C, Sonic2ObjectIds.MTZ_PLATFORM, 0x07, 0, false, 0),
                "MTZPlatform");
        platform.setServices(new StubObjectServices());
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0460, (short) 0x050C);

        platform.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        platform.update(1, player);

        assertEquals(8, intField(platform, "bounceAccel"),
                "ROM Obj6B type 7 consumes the standing bit before the following ObjectMove dispatch");
        assertEquals(8, intField(platform, "yVel"),
                "The first post-contact bouncy dispatch must run ObjectMove with old y_vel then add objoff_38");
    }

    @Test
    void mtzTwinStompersPrimeRomMainTicksBeforeFirstContactFrame() throws Exception {
        MTZTwinStompersObjectInstance stomper = new MTZTwinStompersObjectInstance(
                new ObjectSpawn(0x0620, 0x05A0, Sonic2ObjectIds.MTZ_TWIN_STOMPERS, 0x01, 0, false, 0),
                "MTZTwinStompers");
        stomper.setServices(new StubObjectServices());

        assertEquals(0x05A8, stomper.getY(),
                "Obj64 must enter the engine contact window with the ROM's first two main ticks consumed");
        assertEquals(8, intField(stomper, "extension"));
        assertEquals(0x5A, intField(stomper, "timer"));

        stomper.update(0, new TestablePlayableSprite("sonic", (short) 0x0600, (short) 0x05F0));

        assertEquals(0x05B0, stomper.getY(),
                "The following Obj64_Main dispatch continues the ROM 8 px/tick extension cadence");
        assertEquals(0x10, intField(stomper, "extension"));
    }

    @Test
    void skyChaseCloudKeepsSixteenBitSubpixelAccumulator() throws Exception {
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        ParallaxManager parallaxManager = mock(ParallaxManager.class);
        when(parallaxManager.getTornadoVelocityX()).thenReturn(0);
        CloudObjectInstance cloud = new CloudObjectInstance(
                new ObjectSpawn(0x0300, 0x0120, Sonic2ObjectIds.CLOUD, 0x60, 0, false, 0));
        cloud.setServices(new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public ParallaxManager parallaxManager() {
                return parallaxManager;
            }
        });

        cloud.update(0, new TestablePlayableSprite("sonic", (short) 0x0300, (short) 0x0120));

        SubpixelMotion.State motionState = (SubpixelMotion.State) objectField(cloud, "motionState");
        assertEquals(0x02FF, cloud.getX(),
                "ObjB3 ObjectMove should apply the negative fractional carry on the first frame");
        assertEquals(0xC000, motionState.xSub,
                "ObjB3 must preserve the ROM 16.16 low word instead of truncating it to 8 bits");
    }

    @Test
    void collapsingPlatformFragmentFallDeletesUsingFallingParentY() throws Exception {
        StubObjectServices services = new StubObjectServices();
        CollapsingPlatformObjectInstance platform = ObjectConstructionContext.construct(services,
                () -> new CollapsingPlatformObjectInstance(
                        new ObjectSpawn(0x0240, 0x05D0, Sonic2ObjectIds.COLLAPSING_PLATFORM, 0x00, 0, false, 0),
                        "CollapsPform"));
        platform.setServices(services);
        setBooleanField(platform, "collapsed", true);
        setIntField(platform, "parentY", 0x0700);

        AbstractObjectInstance.updateCameraBounds(0x0200, 0x052C, 0x0340, 0x060C, 0);

        platform.snapshotPreUpdatePosition();
        platform.update(222, new TestablePlayableSprite("sonic", (short) 0x0330, (short) 0x058C));

        assertTrue(platform.isDestroyed(),
                "Obj1F_FragmentFall must delete from the falling parent y_pos, not the original spawn y_pos");
    }

    @Test
    void collapsingPlatformFragmentFallDeletesWhenRenderBoxLeavesScreenLeft() throws Exception {
        StubObjectServices services = new StubObjectServices();
        CollapsingPlatformObjectInstance platform = ObjectConstructionContext.construct(services,
                () -> new CollapsingPlatformObjectInstance(
                        new ObjectSpawn(0x0240, 0x05D0, Sonic2ObjectIds.COLLAPSING_PLATFORM, 0x00, 0, false, 0),
                        "CollapsPform"));
        platform.setServices(services);
        setBooleanField(platform, "collapsed", true);
        setIntField(platform, "parentY", 0x05ED);

        AbstractObjectInstance.updateCameraBounds(0x0285, 0x052C, 0x03C5, 0x060C, 0);

        platform.update(221, new TestablePlayableSprite("sonic", (short) 0x0330, (short) 0x058C));

        assertTrue(platform.isDestroyed(),
                "Obj1F_FragmentFall must observe DisplaySprite render_flags, not MarkObjGone's 0x80 unload margin");
    }

    @Test
    void collapsingPlatformFragmentFallUsesApproximateRenderHeight() throws Exception {
        StubObjectServices services = new StubObjectServices();
        CollapsingPlatformObjectInstance platform = ObjectConstructionContext.construct(services,
                () -> new CollapsingPlatformObjectInstance(
                        new ObjectSpawn(0x0441, 0x05B0, Sonic2ObjectIds.COLLAPSING_PLATFORM, 0x00, 0, false, 0),
                        "CollapsPform"));
        platform.setServices(services);
        AbstractObjectInstance.updateCameraBounds(0x0428, 0x0506, 0x0568, 0x05E6, 0);
        platform.update(320, new TestablePlayableSprite("sonic", (short) 0x04C0, (short) 0x0555));

        setBooleanField(platform, "collapsed", true);
        setIntField(platform, "parentY", 0x05FA);

        platform.snapshotPreUpdatePosition();
        platform.update(321, new TestablePlayableSprite("sonic", (short) 0x04C0, (short) 0x0555));

        assertFalse(platform.isDestroyed(),
                "Obj1F lacks render_flags.explicit_height, so BuildSprites keeps it through the 32px approximate Y band");
    }

    @Test
    void collapsingPlatformFragmentFallDeletesFromPreviousRenderFlagWithoutGrace() throws Exception {
        StubObjectServices services = new StubObjectServices();
        CollapsingPlatformObjectInstance platform = ObjectConstructionContext.construct(services,
                () -> new CollapsingPlatformObjectInstance(
                        new ObjectSpawn(0x0441, 0x05B0, Sonic2ObjectIds.COLLAPSING_PLATFORM, 0x00, 0, false, 0),
                        "CollapsPform"));
        platform.setServices(services);
        setBooleanField(platform, "collapsed", true);
        setIntField(platform, "parentY", 0x0606);

        AbstractObjectInstance.updateCameraBounds(0x0428, 0x0506, 0x0568, 0x05E6, 0);

        platform.update(324, new TestablePlayableSprite("sonic", (short) 0x04C0, (short) 0x0555));
        assertTrue(platform.isDestroyed(),
                "Obj1F_FragmentFall deletes immediately when the previous BuildSprites pass cleared on-screen");
    }

    @Test
    void collapsingPlatformFragmentsReuseParentAsFragmentZero() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        StubObjectServices services = new StubObjectServices() {
                    @Override
                    public ObjectManager objectManager() {
                        return objectManager;
                    }
                };
        CollapsingPlatformObjectInstance platform = ObjectConstructionContext.construct(
                services,
                () -> new CollapsingPlatformObjectInstance(
                        new ObjectSpawn(0x0441, 0x05B0, Sonic2ObjectIds.COLLAPSING_PLATFORM, 0x00, 0, false, 0),
                        "CollapsPform"));
        platform.setServices(services);

        Method collapse = CollapsingPlatformObjectInstance.class.getDeclaredMethod("collapse");
        collapse.setAccessible(true);
        collapse.invoke(platform);

        verify(objectManager, times(6)).addDynamicObject(
                org.mockito.ArgumentMatchers.any(CollapsingPlatformObjectInstance.CollapsingPlatformFragmentInstance.class));
        verify(objectManager).markRemembered(platform.getSpawn());
    }

    @Test
    void mtzCogRotationUsesRomVisibleLevelFrameCounter() {
        LevelManager levelManager = mock(LevelManager.class);
        CogObjectInstance cog = new CogObjectInstance(
                new ObjectSpawn(0x0800, 0x0680, Sonic2ObjectIds.COG, 0x00, 0, false, 0),
                "Cog");
        cog.setServices(new StubObjectServices() {
            @Override
            public LevelManager levelManager() {
                return levelManager;
            }
        });

        // LevelManager now advances at the loop top as the ROM does, so its
        // counter IS the ROM-visible Level_frame_counter during the object pass.
        when(levelManager.getFrameCounter()).thenReturn(0x07EF);
        cog.update(0x6CC1, new TestablePlayableSprite("sonic", (short) 0x0800, (short) 0x0600));
        assertEquals(0x0800, cog.getPieceX(0),
                "ROM-visible Level_frame_counter $07EF is not a $10 boundary, so Obj70 must not rotate yet");

        when(levelManager.getFrameCounter()).thenReturn(0x07F0);
        cog.update(0x6CC2, new TestablePlayableSprite("sonic", (short) 0x0800, (short) 0x0600));
        assertEquals(0x080D, cog.getPieceX(0),
                "ROM-visible Level_frame_counter $07F0 advances Obj70 to the next tooth phase");
    }

    @Test
    void mtzCogTeethSuppressNativeObjectEdgeBalance() {
        CogObjectInstance cog = new CogObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic2ObjectIds.COG, 0x00, 0, false, 0),
                "Cog");

        assertTrue(cog.suppressesObjectEdgeBalance(),
                "Obj70 copies status.npc.no_balancing to every cog tooth");
    }

    @Test
    void mtzCogFirstMainExecutionRotatesOnCurrentRomLowByteZero() {
        LevelManager levelManager = mock(LevelManager.class);
        CogObjectInstance cog = new CogObjectInstance(
                new ObjectSpawn(0x0380, 0x0400, Sonic2ObjectIds.COG, 0x00, 0, false, 0),
                "Cog");
        cog.setServices(new StubObjectServices() {
            @Override
            public LevelManager levelManager() {
                return levelManager;
            }
        });

        when(levelManager.getFrameCounter()).thenReturn(0x0520);
        cog.update(0x3751, new TestablePlayableSprite("sonic", (short) 0x0380, (short) 0x03A0));

        assertEquals(0x038D, cog.getPieceX(0),
                "A just-streamed Obj70 whose first Main pass lands on Level_frame_counter low byte zero "
                        + "must take the same rotation tick as the copied ROM tooth slots");
        assertEquals(0x03B8, cog.getPieceY(0));
    }

    @Test
    void mtzCogLandingUsesFullRomWidthPixelsWindow() {
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getFrameCounter()).thenReturn(0x04DB);
        CogObjectInstance cog = new CogObjectInstance(
                new ObjectSpawn(0x0480, 0x0480, Sonic2ObjectIds.COG, 0x00, 0, false, 0),
                "Cog");
        cog.setServices(new StubObjectServices() {
            @Override
            public LevelManager levelManager() {
                return levelManager;
            }
        });
        cog.update(0, new TestablePlayableSprite("sonic", (short) 0x0480, (short) 0x0400));
        cog.snapshotPreUpdatePosition();
        ObjectManager manager = buildSingleObjectManager(cog);

        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sonic.setWidth(18);
        sonic.setHeight(38);
        sonic.setAir(false);
        sonic.setAngle((byte) 0x34);
        sonic.setGroundMode(com.openggf.game.GroundMode.LEFTWALL);
        sonic.setXSpeed((short) 0x014B);
        sonic.setYSpeed((short) 0x0444);
        sonic.setGSpeed((short) 0x047A);
        sonic.setCentreX((short) 0x04D0);
        sonic.setCentreY((short) 0x0464);

        manager.updateSolidContacts(sonic);

        assertTrue(sonic.isOnObject(),
                "Obj70 SolidObject_Landed re-checks width_pixels=$10, so x_pos +8 from tooth centre must land");
        assertFalse(sonic.getAir());
        assertEquals(0, sonic.getAngle() & 0xFF);
        assertEquals(0, sonic.getYSpeed());
        assertEquals(0x014B, sonic.getGSpeed());
    }

    @Test
    void monitorTopLandingUsesPostMoveCrossingWhenFallingFast() {
        OOZLauncherObjectInstance.clearActiveLaunchers();
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x28F0, 0x0391, Sonic2ObjectIds.MONITOR, 0x00, 0, false, 0),
                "Monitor");
        monitor.snapshotPreUpdatePosition();
        ObjectManager manager = buildSingleObjectManager(monitor);

        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0) {
            @Override
            public void setAir(boolean air) {
                setAirForTest(air);
            }
        };
        sonic.setGameRulesForTest(GameRules.SONIC_2);
        sonic.setWidth(18);
        sonic.setHeight(28);
        sonic.setAir(true);
        sonic.setRolling(true);
        sonic.setAnimationId(Sonic2AnimationIds.WALK.id());
        sonic.setXSpeed((short) -0x0060);
        sonic.setYSpeed((short) 0x0690);
        sonic.setGSpeed((short) 0x0001);
        sonic.setCentreX((short) 0x28F4);
        sonic.setCentreY((short) 0x0363);
        sonic.endOfTick();
        sonic.setCentreY((short) 0x036A);

        manager.updateSolidContacts(sonic);

        assertFalse(sonic.getAir(),
                "OOZ1 f7671: Obj26 runs after Sonic movement, so SolidObject_cont sees the top crossing "
                        + "(docs/s2disasm/s2.asm:25617-25623,35344-35500)");
        assertTrue(sonic.isOnObject());
        assertFalse(sonic.getRolling(),
                "SolidObject_Landed reaches Sonic_ResetOnFloor and restores standing radii "
                        + "(docs/s2disasm/s2.asm:35588-35625)");
        assertEquals(0x036E, sonic.getCentreY() & 0xFFFF);
        assertEquals(0, sonic.getYSpeed());
        assertEquals(0xFFA0, sonic.getGSpeed() & 0xFFFF);
    }

    @Test
    void monitorTopLandingTreatsNearbyOozLauncherRollResidueAsRomWalkAnim() {
        OOZLauncherObjectInstance.clearActiveLaunchers();
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x28F0, 0x0391, Sonic2ObjectIds.MONITOR, 0x00, 0, false, 0),
                "Monitor");
        OOZLauncherObjectInstance launcher = new OOZLauncherObjectInstance(
                new ObjectSpawn(0x28C0, 0x0370, Sonic2ObjectIds.OOZ_LAUNCHER, 0x00, 0, false, 0),
                "OOZLauncher");
        monitor.snapshotPreUpdatePosition();
        launcher.snapshotPreUpdatePosition();
        ObjectManager manager = buildObjectManager(monitor, launcher);

        TestablePlayableSprite sonic = ooz1LauncherReleaseMonitorPlayer();
        sonic.setAnimationId(Sonic2AnimationIds.ROLL.id());

        manager.updateSolidContacts(sonic);

        assertFalse(sonic.getAir(),
                "OOZ1 f7671: Obj3D has just cleared obj_control/on_object without writing anim; "
                        + "Obj26 samples the ROM anim byte as Walk before the monitor landing "
                        + "(docs/s2disasm/s2.asm:51159-51170,25617-25623)");
        assertEquals(0x036E, sonic.getCentreY() & 0xFFFF);
        assertEquals(Sonic2AnimationIds.WALK.id(), sonic.getAnimationId());
    }

    @Test
    void monitorTopLandingStillRejectsOrdinaryRollingAirContactWithoutOozLauncherRelease() {
        OOZLauncherObjectInstance.clearActiveLaunchers();
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x2590, 0x00F1, Sonic2ObjectIds.MONITOR, 0x00, 0, false, 0),
                "Monitor");
        monitor.snapshotPreUpdatePosition();
        ObjectManager manager = buildSingleObjectManager(monitor);

        TestablePlayableSprite sonic = ooz1LauncherReleaseMonitorPlayer();
        sonic.setCentreX((short) 0x2594);
        sonic.setCentreY((short) 0x00CA);
        sonic.endOfTick();
        sonic.setCentreY((short) 0x00D0);
        sonic.setAnimationId(Sonic2AnimationIds.ROLL.id());

        manager.updateSolidContacts(sonic);

        assertTrue(sonic.getAir(),
                "Without a live Obj3D off-screen release residue, Obj26's Sonic path must keep "
                        + "rejecting Roll animation contacts (docs/s2disasm/s2.asm:25611-25616)");
        assertEquals(0x00D0, sonic.getCentreY() & 0xFFFF);
    }

    @Test
    void monitorTopLandingRejectsRollAgainAfterOozLauncherResidueEnds() {
        OOZLauncherObjectInstance.clearActiveLaunchers();
        MonitorObjectInstance monitor = new MonitorObjectInstance(
                new ObjectSpawn(0x28F0, 0x0391, Sonic2ObjectIds.MONITOR, 0x00, 0, false, 0),
                "Monitor");
        OOZLauncherObjectInstance launcher = new OOZLauncherObjectInstance(
                new ObjectSpawn(0x28C0, 0x0370, Sonic2ObjectIds.OOZ_LAUNCHER, 0x00, 0, false, 0),
                "OOZLauncher");
        monitor.snapshotPreUpdatePosition();
        launcher.snapshotPreUpdatePosition();
        ObjectManager manager = buildObjectManager(monitor, launcher);

        TestablePlayableSprite sonic = ooz1LauncherReleaseMonitorPlayer();
        sonic.setCentreX((short) 0x28F0);
        sonic.setCentreY((short) 0x036F);
        sonic.setXSpeed((short) -0x00F0);
        sonic.setYSpeed((short) 0x0568);
        sonic.setGSpeed((short) 0);
        sonic.endOfTick();
        sonic.setCentreY((short) 0x0374);
        sonic.setAnimationId(Sonic2AnimationIds.ROLL.id());

        manager.updateSolidContacts(sonic);

        assertTrue(sonic.getAir(),
                "OOZ1 f7731: the ROM has returned Sonic's anim byte to Roll before Obj26 samples it, "
                        + "so the nearby Obj3D fragment must not make SolidObject_Monitor_Sonic solid "
                        + "(docs/s2disasm/s2.asm:25617-25623; BizHawk probe f7731 anim=02/status=07)");
        assertEquals(0x0374, sonic.getCentreY() & 0xFFFF);
        assertEquals(0x0568, sonic.getYSpeed() & 0xFFFF);
    }

    @Test
    void mtzCogGroundedCpuSideContactWithoutStandingBitReachesRomStopCharacterPath() {
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getFrameCounter()).thenReturn(0x04E7);
        CogObjectInstance cog = new CogObjectInstance(
                new ObjectSpawn(0x0480, 0x0480, Sonic2ObjectIds.COG, 0x00, 0, false, 0),
                "Cog");
        cog.setServices(new StubObjectServices() {
            @Override
            public LevelManager levelManager() {
                return levelManager;
            }
        });
        cog.update(0, new TestablePlayableSprite("sonic", (short) 0x0480, (short) 0x0400));
        cog.snapshotPreUpdatePosition();
        ObjectManager manager = buildSingleObjectManager(cog);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x047D, (short) 0x0431);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setXSpeed((short) 0x01E7);
        tails.setGSpeed((short) 0x01EB);

        manager.updateSolidContacts(tails);

        assertTrue(tails.getPushing(),
                "Grounded Obj70 side contact without a standing bit must set Status_Push");
    }

    @Test
    void mtzCogLeftwardGroundedCpuSideContactWithoutStandingBitStillPushes() {
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getFrameCounter()).thenReturn(0x04E7);
        CogObjectInstance cog = new CogObjectInstance(
                new ObjectSpawn(0x0480, 0x0480, Sonic2ObjectIds.COG, 0x00, 0, false, 0),
                "Cog");
        cog.setServices(new StubObjectServices() {
            @Override
            public LevelManager levelManager() {
                return levelManager;
            }
        });
        cog.update(0, new TestablePlayableSprite("sonic", (short) 0x0480, (short) 0x0400));
        cog.snapshotPreUpdatePosition();
        ObjectManager manager = buildSingleObjectManager(cog);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x04B2, (short) 0x042E);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setXSpeed((short) -0x0080);
        tails.setGSpeed((short) -0x0080);

        manager.updateSolidContacts(tails);

        assertTrue(tails.getPushing(),
                "Obj70 reaches SolidObject_cont with no standing bit; moving left must not be mistaken "
                        + "for the stale-standing d4=0 branch (s2.asm:35021-35044, 55080-55141)");
    }

    @Test
    void mtzCogHighSpeedLeftwardReleaseStillSkipsFoldedSiblingSideStop() {
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getFrameCounter()).thenReturn(0x04E7);
        CogObjectInstance cog = new CogObjectInstance(
                new ObjectSpawn(0x0480, 0x0480, Sonic2ObjectIds.COG, 0x00, 0, false, 0),
                "Cog");
        cog.setServices(new StubObjectServices() {
            @Override
            public LevelManager levelManager() {
                return levelManager;
            }
        });
        cog.update(0, new TestablePlayableSprite("sonic", (short) 0x0480, (short) 0x0400));
        cog.snapshotPreUpdatePosition();
        ObjectManager manager = buildSingleObjectManager(cog);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x04B2, (short) 0x042E);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setXSpeed((short) -0x0700);
        tails.setGSpeed((short) -0x0700);

        manager.updateSolidContacts(tails);

        assertFalse(tails.getPushing(),
                "A high-speed leftward Obj70 release is stale folded-slot geometry, not a fresh grounded push");
        assertEquals(-0x0700, tails.getXSpeed(),
                "The folded sibling side path must not run SolidObject_StopCharacter for the stale release");
    }

    @Test
    void mtzCogAirborneHurtCpuSideContactWithoutStandingBitReachesRomStopCharacterPath() {
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getFrameCounter()).thenReturn(0x04E7);
        CogObjectInstance cog = new CogObjectInstance(
                new ObjectSpawn(0x0480, 0x0480, Sonic2ObjectIds.COG, 0x00, 0, false, 0),
                "Cog");
        cog.setServices(new StubObjectServices() {
            @Override
            public LevelManager levelManager() {
                return levelManager;
            }
        });
        cog.update(0, new TestablePlayableSprite("sonic", (short) 0x0480, (short) 0x0400));
        cog.snapshotPreUpdatePosition();
        ObjectManager manager = buildSingleObjectManager(cog);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x04B2, (short) 0x042E);
        tails.setWidth(18);
        tails.setHeight(18);
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setHurt(true);
        tails.setXSpeed((short) -0x0200);
        tails.setYSpeed((short) 0x0170);
        tails.setGSpeed((short) -0x0200);

        manager.updateSolidContacts(tails);

        assertEquals(0, tails.getXSpeed(),
                "Obj02_Hurt does not self-clear x_vel until landing; an airborne clear-bit Obj70 side hit "
                        + "must still reach SolidObject_StopCharacter (s2.asm:41063-41110,35413-35436)");
        assertEquals(0, tails.getGSpeed(),
                "SolidObject_StopCharacter clears inertia/g_speed together with x_vel");
    }

    private static TestablePlayableSprite ooz1LauncherReleaseMonitorPlayer() {
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0) {
            @Override
            public void setAir(boolean air) {
                setAirForTest(air);
            }
        };
        sonic.setGameRulesForTest(GameRules.SONIC_2);
        sonic.setWidth(18);
        sonic.setHeight(28);
        sonic.setAir(true);
        sonic.setRolling(true);
        sonic.setXSpeed((short) -0x0060);
        sonic.setYSpeed((short) 0x0690);
        sonic.setGSpeed((short) 0x0001);
        sonic.setCentreX((short) 0x28F4);
        sonic.setCentreY((short) 0x0363);
        sonic.endOfTick();
        sonic.setCentreY((short) 0x036A);
        return sonic;
    }

    private static int intField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static Object objectField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static ObjectManager buildSingleObjectManager(ObjectInstance instance) {
        return buildObjectManager(instance);
    }

    private static ObjectManager buildObjectManager(ObjectInstance... instances) {
        ObjectRegistry registry = new ObjectRegistry() {
            @Override
            public ObjectInstance create(ObjectSpawn spawn) {
                return instances[0];
            }

            @Override
            public void reportCoverage(List<ObjectSpawn> spawns) {
                // No-op for tests.
            }

            @Override
            public String getPrimaryName(int objectId) {
                return "TEST";
            }
        };

        ObjectManager[] holder = new ObjectManager[1];
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        ObjectManager objectManager = new ObjectManager(List.of(), registry, 0, null, null,
                null, null, services);
        holder[0] = objectManager;
        objectManager.reset(0);
        for (ObjectInstance instance : instances) {
            objectManager.addDynamicObject(instance);
        }
        return objectManager;
    }

    private static final class ZoneActServices extends StubObjectServices {
        private final ObjectManager objectManager;
        private final int romZoneId;
        private final int currentAct;
        private final SonicConfigurationService configuration;

        private ZoneActServices(ObjectManager objectManager, int romZoneId, int currentAct,
                                SonicConfigurationService configuration) {
            this.objectManager = objectManager;
            this.romZoneId = romZoneId;
            this.currentAct = currentAct;
            this.configuration = configuration;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public int romZoneId() {
            return romZoneId;
        }

        @Override
        public int currentAct() {
            return currentAct;
        }

        @Override
        public SonicConfigurationService configuration() {
            return configuration;
        }
    }
}
