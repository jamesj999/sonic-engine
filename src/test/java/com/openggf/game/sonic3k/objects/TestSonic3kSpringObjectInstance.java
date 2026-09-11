package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kSpringObjectInstance {

    private static final class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) {
            super(code, (short) 0, (short) 0);
        }

        @Override
        public void draw() {
        }

        @Override
        public void defineSpeeds() {
        }

        @Override
        protected void createSensorLines() {
        }
    }

    private static final class QueryBackedServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<PlayableEntity> queriedSidekicks;

        QueryBackedServices(PlayableEntity main, List<PlayableEntity> queriedSidekicks) {
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

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void exposesFullSolidRoutineProfileForVerticalAndHorizontalSprings() {
        Sonic3kSpringObjectInstance vertical = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x00, 0, false, 0));
        Sonic3kSpringObjectInstance horizontal = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x10, 0, false, 0));

        SolidRoutineProfile verticalProfile = vertical.getSolidRoutineProfile();
        SolidRoutineProfile horizontalProfile = horizontal.getSolidRoutineProfile();

        assertEquals(SolidRoutineKind.FULL_SOLID, verticalProfile.kind());
        // ROM SolidObject_cont uses an inclusive right edge for every Obj_Spring
        // variant (cmp.w d3,d0 / bhi.w, sonic3k.asm:41394-41401), not only the
        // horizontal one. AIZ1 CPU-Tails f4234 proved the vertical spring keeps
        // SolidObject side-contact/Status_Push alive when Tails' centre sits on
        // the spring's exact right edge.
        assertTrue(verticalProfile.inclusiveRightEdge());
        assertTrue(verticalProfile.bypassesOffscreenSolidGate());
        assertEquals(SolidRoutineKind.FULL_SOLID, horizontalProfile.kind());
        assertTrue(horizontalProfile.inclusiveRightEdge());
        assertTrue(horizontalProfile.bypassesOffscreenSolidGate());
    }

    @Test
    void nativeInitExecutionDoesNotRunHorizontalSpringRoutineUntilNextFrame() {
        // Obj_Spring rewrites (a0) to Obj_Spring_Horizontal through Spring_Common
        // and returns (sonic3k.asm:47500-47652). The horizontal routine therefore
        // cannot execute SolidObjectFull2_1P/sub_2326C until the next object pass.
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x14B8, 0x0770, Sonic3kObjectIds.SPRING, 0x10, 1, false, 0));
        TestableSprite tails = new TestableSprite("tails");
        tails.setCentreX((short) 0x14B5);
        tails.setCentreY((short) 0x0770);
        tails.setGSpeed((short) 0x24);
        spring.setServices(new QueryBackedServices(tails, List.of())
                .withGameState(new GameStateManager())
                .withIsolatedObjectManager());

        spring.update(1846, tails);
        assertFalse(spring.isSolidFor(tails),
                "Obj_Spring's init-only execution must not collide on its spawn frame");
        assertEquals(0x24, tails.getGSpeed(),
                "init must not run the horizontal proactive trigger");

        spring.update(1847, tails);
        assertTrue(spring.isSolidFor(tails),
                "Obj_Spring_Horizontal becomes executable on the following object pass");
    }

    @Test
    void reverseGravitySwapsVerticalSpringDirectionDuringNativeInit() throws Exception {
        GameStateManager gameState = new GameStateManager();
        gameState.setReverseGravityActive(true);

        Sonic3kSpringObjectInstance up = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x00, 0, false, 0));
        up.setServices(new TestObjectServices()
                .withGameState(gameState).withIsolatedObjectManager());
        invoke(up, "ensureInitialized");

        Sonic3kSpringObjectInstance down = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x20, 0, false, 0));
        down.setServices(new TestObjectServices()
                .withGameState(gameState).withIsolatedObjectManager());
        invoke(down, "ensureInitialized");

        assertEquals(4, intField(up, "springType"),
                "Reverse_gravity_flag swaps native UP to DOWN during Obj_Spring init");
        assertEquals(0, intField(down, "springType"),
                "Reverse_gravity_flag swaps native DOWN to UP during Obj_Spring init");
    }

    @Test
    void verticalSpringSolidParamsMatchRom() {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x00, 0, false, 0));

        SolidObjectParams params = spring.getSolidParams();

        assertEquals(27, params.halfWidth());
        assertEquals(8, params.airHalfHeight());
        assertEquals(16, params.groundHalfHeight(),
                "S3K vertical springs use d3=$10 in the ROM standing path");
    }

    @Test
    void upSpringPositionNudgePreservesYSubpixel() throws Exception {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x00, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setCentreY((short) 0x0100);
        player.setSubpixelRaw(0, 0x5200);

        invoke(spring, "applyUpSpring", new Class<?>[]{AbstractPlayableSprite.class}, player);

        assertEquals(0x0108, player.getCentreY() & 0xFFFF);
        assertEquals(0x5200, player.getYSubpixelRaw(),
                "ROM addq.w to y_pos preserves the existing subpixel fraction");
    }

    @Test
    void upSpringPreservesGSpeedWhenSubtypeDoesNotOverrideInertia() throws Exception {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x00, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setXSpeed((short) 0x0240);
        player.setGSpeed((short) 0x0B54);

        invoke(spring, "applyUpSpring", new Class<?>[]{AbstractPlayableSprite.class}, player);

        assertEquals(0x0240, player.getXSpeed() & 0xFFFF,
                "Up spring without subtype bit 7 should leave xSpeed unchanged");
        assertEquals(0x0B54, player.getGSpeed() & 0xFFFF,
                "ROM up spring leaves inertia untouched unless subtype bit 0 overrides it");
    }

    @Test
    void horizontalSpringPositionNudgePreservesXSubpixelAndUsesMoveLock() throws Exception {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.SPRING, 0x10, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setCentreX((short) 0x0200);
        player.setSubpixelRaw(0x3700, 0);

        invoke(spring, "applyHorizontalSpring", new Class<?>[]{AbstractPlayableSprite.class}, player);

        assertEquals(0x01F8, player.getCentreX() & 0xFFFF);
        assertEquals(0x3700, player.getXSubpixelRaw(),
                "ROM addq/subi.w x_pos during horizontal spring launch preserves x_sub");
        assertEquals(0x1000, player.getXSpeed() & 0xFFFF);
        assertEquals(0x1000, player.getGSpeed() & 0xFFFF);
        assertEquals(15, player.getMoveLockTimer());
        assertFalse(player.getAir(), "Horizontal springs keep the player grounded");
    }

    @Test
    void airborneHorizontalSpringSideContactLaunchesWithoutGroundPushingFlag() throws Exception {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.SPRING, 0x10, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setCentreX((short) 0x0208);
        player.setAir(true);
        player.setRolling(true);
        player.setXSpeed((short) -0x05CF);
        player.setGSpeed((short) -0x05CF);

        spring.onSolidContact(player, new SolidContact(false, true, false, false, false, 0, false), 0);

        assertEquals(0x0200, player.getCentreX() & 0xFFFF,
                "sub_23190 applies the horizontal spring nudge even for airborne side contact");
        assertEquals(0x1000, player.getXSpeed() & 0xFFFF);
        assertEquals(0x1000, player.getGSpeed() & 0xFFFF);
        assertEquals(true, player.getAir(),
                "sub_23190 does not clear Status_InAir for an airborne side-contact launch");
    }

    @Test
    void allSpringVariantsUseInclusiveSolidRightEdge() throws Exception {
        Sonic3kSpringObjectInstance horizontal = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.SPRING, 0x10, 0, false, 0));
        horizontal.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(horizontal, "ensureInitialized");

        Sonic3kSpringObjectInstance vertical = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.SPRING, 0x00, 0, false, 0));
        vertical.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(vertical, "ensureInitialized");

        // Every Obj_Spring variant resolves solidity through SolidObjectFull2_1P
        // -> SolidObject_cont, whose x-window rejects only with bhi (d0 > d1*2),
        // making the right edge inclusive (sonic3k.asm:41394-41401). AIZ1 f4234.
        assertEquals(true, horizontal.usesInclusiveRightEdge(),
                "Obj_Spring_Horizontal uses SolidObjectFull2_1P, whose x-window rejects with bhi");
        assertEquals(true, vertical.usesInclusiveRightEdge(),
                "Obj_Spring (vertical) also reaches SolidObject_cont's inclusive bhi x-window");
    }

    @Test
    void horizontalSpringLandingHandoffTriggersOnDescendingAirborneFrame() throws Exception {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x1D37, 0x08B0, Sonic3kObjectIds.SPRING, 0x12, 1, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite tooEarly = new TestableSprite("tails_p2");
        tooEarly.setCentreX((short) 0x1D21);
        tooEarly.setCentreY((short) 0x08AF);
        tooEarly.setAir(true);
        tooEarly.setXSpeed((short) -0x0048);
        tooEarly.setYSpeed((short) 0x0150);

        invoke(spring, "checkHorizontalApproach", new Class<?>[]{AbstractPlayableSprite.class}, tooEarly);
        assertEquals(0xFFB8, tooEarly.getXSpeed() & 0xFFFF,
                "The landing handoff must not fire before the player reaches the spring's Y line");

        TestableSprite player = new TestableSprite("tails_p2");
        player.setCentreX((short) 0x1D21);
        player.setCentreY((short) 0x08B0);
        player.setAir(true);
        player.setXSpeed((short) -0x0048);
        player.setYSpeed((short) 0x0150);
        player.setGSpeed((short) 0);
        player.setAngle((byte) 0xCE);
        player.setGroundMode(GroundMode.RIGHTWALL);

        invoke(spring, "checkHorizontalApproach", new Class<?>[]{AbstractPlayableSprite.class}, player);

        assertEquals(0x1D29, player.getCentreX() & 0xFFFF,
                "CNZ F3649: flipped horizontal spring applies the ROM +8 x_pos nudge");
        assertEquals(0xF600, player.getXSpeed() & 0xFFFF);
        assertEquals(0xF600, player.getGSpeed() & 0xFFFF);
        assertEquals(0, player.getAngle() & 0xFF,
                "The same-frame landing handoff must clear the cage-orbit angle before the spring launch");
        assertEquals(GroundMode.GROUND, player.getGroundMode(),
                "The same-frame landing handoff must mirror ROM's grounded mode before sub_2326C fires");
        assertFalse(player.getAir(),
                "The handoff mirrors ROM's same-frame air->ground transition before the horizontal spring launch");
    }

    @Test
    void horizontalApproachUsesRomHalfOpenCoordinateWindow() throws Exception {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x1888, 0x0330, Sonic3kObjectIds.SPRING, 0x10, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("tails_p2");
        player.setCentreX((short) 0x18B0); // springX + $28: bhs rejects.
        player.setCentreY((short) 0x0347);
        player.setGSpeed((short) 0x0312);

        invoke(spring, "checkHorizontalApproach", new Class<?>[]{AbstractPlayableSprite.class}, player);
        assertEquals(0x18B0, player.getCentreX() & 0xFFFF,
                "sub_2326C rejects the exclusive +$28 X edge");
        assertEquals(0, player.getXSpeed());

        player.setCentreX((short) 0x18AF); // springX + $27: inside.
        invoke(spring, "checkHorizontalApproach", new Class<?>[]{AbstractPlayableSprite.class}, player);
        assertEquals(0x18A7, player.getCentreX() & 0xFFFF);
        assertEquals(0x1000, player.getXSpeed() & 0xFFFF);
    }

    @Test
    void underwaterAirborneHorizontalSpringApproachDoesNotUseLandingHandoff() throws Exception {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x031A, 0x0610, Sonic3kObjectIds.SPRING, 0x10, 1, true, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("tails_p2");
        player.setCentreX((short) 0x02F2);
        player.setCentreY((short) 0x0611);
        player.setAir(true);
        player.setInWater(true);
        player.setXSpeed((short) 0x0300);
        player.setYSpeed((short) 0x0390);
        player.setGSpeed((short) 0xFFD0);

        invoke(spring, "checkHorizontalApproach", new Class<?>[]{AbstractPlayableSprite.class}, player);

        assertEquals(0x02F2, player.getCentreX() & 0xFFFF,
                "HCZ F624: underwater airborne Tails must not receive the horizontal-spring x_pos nudge");
        assertEquals(0x0300, player.getXSpeed() & 0xFFFF);
        assertEquals(0x0390, player.getYSpeed() & 0xFFFF,
                "Underwater airborne approach keeps fall velocity instead of clearing y_vel for a grounded handoff");
        assertTrue(player.getAir());
    }

    @Test
    void horizontalApproachUsesNativeP2FromPlayerQueryWithoutPromotingExtraSidekicks() {
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.SPRING, 0x10, 0, false, 0));

        TestableSprite main = new TestableSprite("sonic");
        main.setCentreX((short) 0x0100);
        main.setCentreY((short) 0x0100);
        main.setGSpeed((short) 0x0400);

        TestableSprite nativeP2 = new TestableSprite("tails");
        nativeP2.setCentreX((short) 0x0220);
        nativeP2.setCentreY((short) 0x0100);
        nativeP2.setGSpeed((short) 0x0400);

        TestableSprite extraSidekick = new TestableSprite("knuckles");
        extraSidekick.setCentreX((short) 0x0218);
        extraSidekick.setCentreY((short) 0x0100);
        extraSidekick.setGSpeed((short) 0x0400);

        spring.setServices(new QueryBackedServices(main, List.of(nativeP2, extraSidekick))
                .withGameState(new GameStateManager())
                .withIsolatedObjectManager());

        spring.update(0, main); // Obj_Spring init-only execution
        spring.update(1, main); // Obj_Spring_Horizontal

        assertEquals(0x0218, nativeP2.getCentreX() & 0xFFFF,
                "ROM Player_2 approach block should resolve native P2 through ObjectPlayerQuery");
        assertEquals(0x1000, nativeP2.getXSpeed() & 0xFFFF);
        assertEquals(0x0218, extraSidekick.getCentreX() & 0xFFFF,
                "Extra engine sidekicks must not be promoted into the native Player_2 approach block");
        assertEquals(0, extraSidekick.getXSpeed());
    }

    @Test
    void upSpringRestoresControlAndClearsStatusOnObjAfterSettingAir() throws Exception {
        // ROM cite: sub_22F98 (sonic3k.asm:47723-47724)
        //   bset #1,status(a1)   ; Status_InAir
        //   bclr #3,status(a1)   ; Status_OnObj
        // SolidObjectFull2_1P just landed the player on the spring (set OnObj=1);
        // the trigger sub immediately clears it as the player launches off.
        // Without this clear, OnObj remains true into subsequent frames where
        // ROM has it cleared (causes mid-frame Tails CPU follow-steering bias
        // at loc_13DA6 / sonic3k.asm:26690).
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x00, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setOnObject(true); // mirrors SolidObjectFull2_1P landing-bset
        player.setAir(false);
        player.setJumping(true);
        player.setHurt(true);

        invoke(spring, "applyUpSpring", new Class<?>[]{AbstractPlayableSprite.class}, player);

        assertFalse(player.isOnObject(),
                "ROM sub_22F98 (sonic3k.asm:47723-47724) bclr Status_OnObj after setting Status_InAir");
        assertFalse(player.isJumping(),
                "ROM sub_22F98 clears jumping so jump release cannot cap the spring launch");
        assertFalse(player.isHurt(),
                "ROM sub_22F98 writes routine=2, ending hurt routine 4 when the spring launches");
    }

    @Test
    void downSpringRestoresControlAndClearsStatusOnObjAfterSettingAir() throws Exception {
        // ROM cite: sub_233CA (sonic3k.asm:48139-48140)
        //   bset #Status_InAir,status(a1)
        //   bclr #Status_OnObj,status(a1)
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x20, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setOnObject(true);
        player.setAir(false);
        player.setJumping(true);
        player.setHurt(true);

        invoke(spring, "applyDownSpring", new Class<?>[]{AbstractPlayableSprite.class}, player);

        assertFalse(player.isOnObject(),
                "ROM sub_233CA bclr Status_OnObj after setting Status_InAir for the down-spring trigger");
        assertFalse(player.isJumping(), "ROM sub_233CA clears jumping on a down-spring launch");
        assertFalse(player.isHurt(),
                "ROM sub_233CA writes routine=2, ending hurt routine 4 when the spring launches");
    }

    @Test
    void upDiagonalSpringRestoresControlAndClearsStatusOnObjAfterSettingAir() throws Exception {
        // ROM cite: sub_234E6 (sonic3k.asm:48213-48214)
        //   bset #Status_InAir,status(a1)
        //   bclr #Status_OnObj,status(a1)
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x30, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setOnObject(true);
        player.setAir(false);
        player.setJumping(true);
        player.setHurt(true);

        invoke(spring, "applyDiagonalSpring",
                new Class<?>[]{AbstractPlayableSprite.class, boolean.class}, player, true);

        assertFalse(player.isOnObject(),
                "ROM sub_234E6 bclr Status_OnObj after setting Status_InAir for diagonal-up springs");
        assertFalse(player.isJumping(), "ROM diagonal-spring triggers clear jumping");
        assertFalse(player.isHurt(),
                "ROM sub_234E6 writes routine=2, ending hurt routine 4 when the spring launches");
    }

    @Test
    void downDiagonalSpringRestoresControlWhenLaunchingHurtPlayer() throws Exception {
        // ROM cite: sub_23624 (sonic3k.asm:48306-48310) mirrors the
        // up-diagonal tail's Status_InAir/routine=2 transition.
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.SPRING, 0x40, 0, false, 0));
        spring.setServices(new TestObjectServices().withGameState(new GameStateManager())
                .withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setHurt(true);

        invoke(spring, "applyDiagonalSpring",
                new Class<?>[]{AbstractPlayableSprite.class, boolean.class}, player, false);

        assertFalse(player.isHurt(),
                "ROM sub_23624 writes routine=2, ending hurt routine 4 on a down-diagonal launch");
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static int intField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] argTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, argTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
