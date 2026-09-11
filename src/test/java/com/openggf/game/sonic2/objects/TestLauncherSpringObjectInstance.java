package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLauncherSpringObjectInstance {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_2);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void launcherSpringUsesSolidObjectAlwaysOffscreenBypass() {
        LauncherSpringObjectInstance spring = new LauncherSpringObjectInstance(
                new ObjectSpawn(0x1070, 0x06F0, Sonic2ObjectIds.LAUNCHER_SPRING, 0, 0, false, 0),
                "LauncherSpring");

        assertTrue(spring.bypassesOffscreenSolidGate(),
                "Obj85 calls SolidObject_Always_SingleCharacter for both players");
    }

    @Test
    void nativeP2QuerySidekickCompressesAndLaunchesWhenRawSidekickListIsEmpty() {
        LauncherSpringObjectInstance spring = new LauncherSpringObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.LAUNCHER_SPRING, 0, 0, false, 0),
                "LauncherSpring");
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x1200, (short) 0x1000);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x1000, (short) 0x1000);
        spring.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        spring.onSolidContact(sidekick, new SolidContact(true, false, false, true, false), 0);
        sidekick.setJumpInputPressed(true);
        spring.update(0, main);
        sidekick.setJumpInputPressed(false);
        spring.update(1, main);
        spring.update(2, main);

        assertTrue(sidekick.getYSpeed() < 0,
                "Obj85 native P2 slot must be processed through ObjectPlayerQuery NATIVE_P1_P2");
    }

    @Test
    void traceDebugDetailsUsesNativeP2QueryWhenRawSidekickListIsEmpty() {
        LauncherSpringObjectInstance spring = new LauncherSpringObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, Sonic2ObjectIds.LAUNCHER_SPRING, 0, 0, false, 0),
                "LauncherSpring");
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x1200, (short) 0x1000);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x1000, (short) 0x1000);
        spring.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        spring.onSolidContact(sidekick, new SolidContact(true, false, false, true, false), 0);

        String details = spring.traceDebugDetails();

        assertTrue(details.contains("p2state=1"),
                "Trace diagnostics should resolve native P2 from ObjectPlayerQuery, not raw sidekicks()");
        assertTrue(details.contains("p2solid=false"),
                "Captured native P2 should report the per-player non-solid state");
    }

    @Test
    void verticalTailsRecaptureKeepsRomStandingRadiusSeatBeforeObj85RollRadiusWrite() {
        LauncherSpringObjectInstance spring = new LauncherSpringObjectInstance(
                new ObjectSpawn(0x1070, 0x06F0, Sonic2ObjectIds.LAUNCHER_SPRING, 0, 0, false, 0),
                "LauncherSpring");
        Tails tails = new Tails("tails", (short) 0x1070, (short) 0x0600);
        tails.setRolling(true);
        tails.setAir(true);
        tails.capturePrePhysicsSnapshot();
        tails.setAir(false);
        tails.setCentreY((short) 0x06C1);
        tails.setYSpeed((short) 0);

        spring.onSolidContact(tails, new SolidContact(true, false, false, true, false), 0);

        assertEquals(0x06C0, tails.getCentreY() & 0xFFFF,
                "S2 Obj85 runs SolidObject_Landed before writing Tails y_radius=$0E");
    }

    @Test
    void diagonalCapturePreservesRomYWriteWhenTailsAlreadyHasRollSizedHeight() {
        LauncherSpringObjectInstance spring = new LauncherSpringObjectInstance(
                new ObjectSpawn(0x21A1, 0x0448, Sonic2ObjectIds.LAUNCHER_SPRING, 0x81, 0, false, 0),
                "LauncherSpring");
        Tails tails = new Tails("tails", (short) 0x21B4, (short) 0x0430);
        tails.setHeight(28);
        tails.setYSpeed((short) 0);

        spring.onSolidContact(tails, new SolidContact(true, false, false, true, false), 0);

        assertEquals(0x0435, tails.getCentreY() & 0xFFFF,
                "S2 Obj85 diagonal capture writes y_pos(a1)=y_pos(a0)-$13 before setting y_radius=$E");
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
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
}
