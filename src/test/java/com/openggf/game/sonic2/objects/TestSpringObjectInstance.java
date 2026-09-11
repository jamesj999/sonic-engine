package com.openggf.game.sonic2.objects;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSpringObjectInstance {

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

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
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
        SpringObjectInstance vertical = new SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x00, 0, false, 0),
                "TestSpring");
        SpringObjectInstance horizontal = new SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x10, 0, false, 0),
                "TestSpring");

        SolidRoutineProfile verticalProfile = vertical.getSolidRoutineProfile();
        SolidRoutineProfile horizontalProfile = horizontal.getSolidRoutineProfile();

        assertEquals(SolidRoutineKind.FULL_SOLID, verticalProfile.kind());
        assertTrue(verticalProfile.inclusiveRightEdge());
        assertTrue(verticalProfile.bypassesOffscreenSolidGate());
        assertEquals(SolidRoutineKind.FULL_SOLID, horizontalProfile.kind());
        assertTrue(horizontalProfile.inclusiveRightEdge());
        assertTrue(horizontalProfile.bypassesOffscreenSolidGate());
    }

    @Test
    void upSpringRightEdgeContactSetsPushAtInclusiveSolidObjectBoundary() {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, 0x41, 0x00, 0, false, 0),
                "TestSpring");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());

        ObjectManager manager = buildManager(spring);
        TestableSprite player = new TestableSprite("tails");
        player.setWidth(18);
        player.setHeight(30);
        player.setAir(false);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setCentreX((short) 0x021B);
        player.setCentreY((short) 0x0100);

        manager.update(0, player, List.of(), 0, false, true, true);
        manager.update(0, player, List.of(), 1, false, true, true);

        assertTrue(player.getPushing(),
                "Obj41_Up uses SolidObject_cont's bhi right-edge gate, so relX == 2*d1 still pushes");
    }

    @Test
    void upSpringPositionNudgePreservesYSubpixel() throws Exception {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x00, 0, false, 0),
                "TestSpring");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());
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
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x00, 0, false, 0),
                "TestSpring");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());
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
    void downSpringPreservesGSpeedWhenSubtypeDoesNotOverrideInertia() throws Exception {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x20, 0, false, 0),
                "DownSpring");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setXSpeed((short) 0x0240);
        player.setGSpeed((short) 0x0B54);

        invoke(spring, "applyDownSpring", new Class<?>[]{AbstractPlayableSprite.class}, player);

        assertEquals(0x0240, player.getXSpeed() & 0xFFFF,
                "Down spring without subtype bit 7 should leave xSpeed unchanged");
        assertEquals(0x0B54, player.getGSpeed() & 0xFFFF,
                "ROM down spring leaves inertia untouched unless subtype bit 0 overrides it");
    }

    @Test
    void diagonalUpSpringWaitsForRomXThresholdWhenUnflipped() throws Exception {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, 0x41, 0x30, 0, false, 0),
                "DiagSpring");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setAir(false);
        player.setGSpeed((short) 0x0274);
        player.setCentreX((short) 0x01FC); // springX - 4: ROM still returns
        player.setCentreY((short) 0x0100);
        player.setSubpixelRaw(0x1234, 0x5678);

        invoke(spring, "applyCheckpointContact",
                new Class<?>[]{AbstractPlayableSprite.class, PlayerSolidContactResult.class},
                player, standingContact());

        assertEquals(0x01FC, player.getCentreX() & 0xFFFF);
        assertEquals(0x0100, player.getCentreY() & 0xFFFF);
        assertEquals(0x1234, player.getXSubpixelRaw());
        assertEquals(0x5678, player.getYSubpixelRaw());
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertFalse(player.getAir());

        player.setCentreXPreserveSubpixel((short) 0x01FD); // springX - 3: ROM launches

        invoke(spring, "applyCheckpointContact",
                new Class<?>[]{AbstractPlayableSprite.class, PlayerSolidContactResult.class},
                player, standingContact());

        assertEquals(0x01F7, player.getCentreX() & 0xFFFF);
        assertEquals(0x0106, player.getCentreY() & 0xFFFF);
        assertEquals(0x1234, player.getXSubpixelRaw(),
                "ROM addq/subi.w x_pos preserves X subpixel fraction");
        assertEquals(0x5678, player.getYSubpixelRaw(),
                "ROM addq.w y_pos preserves Y subpixel fraction");
        assertEquals(0x1000, player.getXSpeed() & 0xFFFF);
        assertEquals(0xF000, player.getYSpeed() & 0xFFFF);
        assertEquals(0x0274, player.getGSpeed() & 0xFFFF,
                "Diagonal spring launch leaves inertia unchanged unless subtype bit 0 overrides it");
        assertTrue(player.getAir());
    }

    @Test
    void diagonalUpSpringUsesMirroredRomXThresholdWhenFlipped() throws Exception {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, 0x41, 0x30, 0x01, false, 0),
                "DiagSpringFlipped");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setAir(false);
        player.setGSpeed((short) 0x0274);
        player.setCentreX((short) 0x0205); // springX + 5: ROM still returns
        player.setCentreY((short) 0x0100);
        player.setSubpixelRaw(0xAAAA, 0xBBBB);

        invoke(spring, "applyCheckpointContact",
                new Class<?>[]{AbstractPlayableSprite.class, PlayerSolidContactResult.class},
                player, standingContact());

        assertEquals(0x0205, player.getCentreX() & 0xFFFF);
        assertEquals(0x0100, player.getCentreY() & 0xFFFF);
        assertEquals(0xAAAA, player.getXSubpixelRaw());
        assertEquals(0xBBBB, player.getYSubpixelRaw());
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertFalse(player.getAir());

        player.setCentreXPreserveSubpixel((short) 0x0204); // springX + 4: ROM launches

        invoke(spring, "applyCheckpointContact",
                new Class<?>[]{AbstractPlayableSprite.class, PlayerSolidContactResult.class},
                player, standingContact());

        assertEquals(0x020A, player.getCentreX() & 0xFFFF);
        assertEquals(0x0106, player.getCentreY() & 0xFFFF);
        assertEquals(0xAAAA, player.getXSubpixelRaw());
        assertEquals(0xBBBB, player.getYSubpixelRaw());
        assertEquals(0xF000, player.getXSpeed() & 0xFFFF);
        assertEquals(0xF000, player.getYSpeed() & 0xFFFF);
        assertEquals(0x0274, player.getGSpeed() & 0xFFFF);
        assertTrue(player.getAir());
    }

    @Test
    void horizontalSpringPositionNudgePreservesXSubpixel() throws Exception {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, 0x41, 0x10, 0, false, 0),
                "HorizontalSpring");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());
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
    void diagonalSpringManualCheckpointUsesTopLandingWidthForNewStanding() {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, 0x41, 0x30, 0x01, false, 0),
                "DiagSpringFlipped");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());

        ObjectManager manager = buildManager(spring);

        TestableSprite outerEdge = buildDiagonalLandingProbe(spring, 10);
        int outerEdgeY = outerEdge.getCentreY() & 0xFFFF;
        manager.update(0, outerEdge, List.of(), 0, false, true, false);

        assertEquals(0x01EF, outerEdge.getCentreX() & 0xFFFF,
                "SolidObject_Landed re-reads width_pixels, so relX=10 must miss the new-standing zone");
        assertEquals(outerEdgeY, outerEdge.getCentreY() & 0xFFFF);
        assertEquals(0x0000, outerEdge.getXSpeed() & 0xFFFF);
        assertEquals(0x0100, outerEdge.getYSpeed() & 0xFFFF);
        assertEquals(0x0B54, outerEdge.getGSpeed() & 0xFFFF);
        assertTrue(outerEdge.getAir());

        TestableSprite innerEdge = buildDiagonalLandingProbe(spring, 11);
        int innerEdgeY = innerEdge.getCentreY() & 0xFFFF;
        manager.update(0, innerEdge, List.of(), 0, false, true, false);

        assertEquals(0x01F6, innerEdge.getCentreX() & 0xFFFF,
                "Diagonal spring launch should use the player's actual landed X without an extra entry nudge");
        assertEquals(innerEdgeY + 6, innerEdge.getCentreY() & 0xFFFF,
                "Diagonal spring launch should apply the ROM +6 centre-Y nudge from the contacted slope surface");
        assertEquals(0x4400, innerEdge.getXSubpixelRaw(),
                "Diagonal spring launch preserves x_sub because the ROM uses word-sized x_pos writes");
        assertEquals(0x5500, innerEdge.getYSubpixelRaw(),
                "Diagonal spring launch preserves y_sub because the ROM uses word-sized y_pos writes");
        assertEquals(0xF000, innerEdge.getXSpeed() & 0xFFFF);
        assertEquals(0xF000, innerEdge.getYSpeed() & 0xFFFF);
        assertEquals(0x0000, innerEdge.getGSpeed() & 0xFFFF,
                "Integrated diagonal spring launches keep the inertia produced by the landing path before trigger");
        assertTrue(innerEdge.getAir());
    }

    @Test
    void diagonalGroundedCatchAppliesSolidObjectLandedYBeforeLaunch() {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, 0x41, 0x32, 0x01, false, 0),
                "DiagSpringFlipped");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());

        ObjectManager manager = buildManager(spring);
        TestableSprite player = new TestableSprite("sonic");
        player.setWidth(20);
        player.setHeight(32);
        player.setAir(false);
        player.setXSpeed((short) 0x08C9);
        player.setGSpeed((short) 0x08C9);
        player.setYSpeed((short) 0);
        int relX = 13;
        int relY = 5;
        player.setCentreX((short) ((spring.getSpawn().x() - spring.getSolidParams().halfWidth() + relX) & 0xFFFF));
        player.setCentreY((short) groundedDiagonalCatchCentreYForRelY(player, spring, relX, relY));
        player.setSubpixelRaw(0x2200, 0x3300);

        int expectedLaunchY = solidObjectLandedCentreY(player, relY) + 6;
        manager.update(0, player, List.of(), 0, false, true, false);
        manager.update(0, player, List.of(), 1, false, true, false);

        assertEquals(expectedLaunchY, player.getCentreY() & 0xFFFF,
                "SlopedSolid_cont must run SolidObject_Landed's Y correction before Obj41_DiagonallyUp adds 6");
        assertEquals(0xF600, player.getXSpeed() & 0xFFFF);
        assertEquals(0xF600, player.getYSpeed() & 0xFFFF);
        assertEquals(0x2200, player.getXSubpixelRaw());
        assertEquals(0x3300, player.getYSubpixelRaw());
        assertTrue(player.getAir());
    }

    @Test
    void diagonalUpSpringRequiresStandingBitBeforeThresholdLaunch() throws Exception {
        SpringObjectInstance spring = new SpringObjectInstance(
                new ObjectSpawn(0x0200, 0x0100, 0x41, 0x38, 0x00, false, 0),
                "DiagSpringUnflipped");
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());
        invoke(spring, "ensureInitialized");

        TestableSprite player = new TestableSprite("sonic");
        player.setAir(false);
        player.setCentreX((short) 0x0220);
        player.setCentreY((short) 0x00F0);

        PlayerSolidContactResult topButNotStanding = new PlayerSolidContactResult(
                ContactKind.TOP,
                false,
                false,
                false,
                false,
                PreContactState.ZERO,
                new PostContactState((short) 0, (short) 0, false, false, false),
                0);

        invoke(spring, "applyCheckpointContact",
                new Class<?>[]{AbstractPlayableSprite.class, PlayerSolidContactResult.class},
                player, topButNotStanding);

        assertEquals(0x0000, player.getXSpeed() & 0xFFFF,
                "Obj41_DiagonallyUp only launches after SlopedSolid_SingleCharacter sets the standing bit");
        assertEquals(0x0000, player.getYSpeed() & 0xFFFF);
        assertFalse(player.getAir());
    }

    private static PlayerSolidContactResult standingContact() {
        return new PlayerSolidContactResult(
                ContactKind.TOP,
                true,
                true,
                false,
                false,
                PreContactState.ZERO,
                new PostContactState((short) 0, (short) 0, false, true, false),
                0);
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] argTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, argTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static int diagonalLandingCentreY(AbstractPlayableSprite player, SpringObjectInstance spring, int relX) {
        int halfWidth = spring.getSolidParams().halfWidth();
        int width2 = halfWidth * 2;
        int sampleX = width2 - relX - 1;
        sampleX >>= 1;
        int slopeSample = (byte) spring.getSlopeData()[sampleX];
        int slopeOffset = slopeSample - spring.getSlopeBaseline();
        int baseY = spring.getSpawn().y() - slopeOffset;
        int maxTop = spring.getSolidParams().groundHalfHeight() + player.getYRadius();
        return baseY - maxTop - 1;
    }

    private static TestableSprite buildDiagonalLandingProbe(SpringObjectInstance spring, int relX) {
        TestableSprite player = new TestableSprite("sonic");
        player.setWidth(20);
        player.setHeight(20);
        player.setAir(true);
        player.setYSpeed((short) 0x0100);
        player.setGSpeed((short) 0x0B54);
        player.setCentreX((short) ((spring.getSpawn().x() - spring.getSolidParams().halfWidth() + relX) & 0xFFFF));
        player.setCentreY((short) diagonalLandingCentreY(player, spring, relX));
        player.setSubpixelRaw(0x4400, 0x5500);
        return player;
    }

    private static int groundedDiagonalCatchCentreYForRelY(AbstractPlayableSprite player,
            SpringObjectInstance spring, int relX, int relY) {
        int halfWidth = spring.getSolidParams().halfWidth();
        int width2 = halfWidth * 2;
        int sampleX = width2 - relX - 1;
        sampleX >>= 1;
        int slopeSample = (byte) spring.getSlopeData()[sampleX];
        int slopeOffset = slopeSample - spring.getSlopeBaseline();
        int baseY = spring.getSpawn().y() - slopeOffset;
        int verticalOverlapCompensation = player.getYRadius() + spring.getSolidParams().groundHalfHeight();
        return baseY - 4 - verticalOverlapCompensation + relY;
    }

    private static int solidObjectLandedCentreY(AbstractPlayableSprite player, int relY) {
        return (player.getCentreY() & 0xFFFF) - relY + 3;
    }

    private static ObjectManager buildManager(ObjectInstance instance) {
        ObjectRegistry registry = new ObjectRegistry() {
            @Override
            public ObjectInstance create(ObjectSpawn spawn) {
                return instance;
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

        ObjectManager objectManager = new ObjectManager(List.of(), registry, 0, null, null);
        objectManager.reset(0);
        objectManager.addDynamicObject(instance);
        return objectManager;
    }
}
