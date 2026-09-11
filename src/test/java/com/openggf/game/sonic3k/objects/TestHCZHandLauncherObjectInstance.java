package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestHCZHandLauncherObjectInstance {

    @Test
    void landingCheckpointIsConsumedOnFollowingObjectDispatch() {
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x0200, (short) 0x0100);
        ProbeHandLauncher launcher = buildLauncher(player);

        while (launcher.getYOffset() > 0x18) {
            launcher.update(0, player);
        }
        assertFalse(player.isObjectControlled(),
                "sub_30CE0 runs before the current SolidObjectTop landing pass");

        launcher.update(1, player);
        assertTrue(player.isObjectControlled(),
                "the following object dispatch consumes the retained standing bit");
    }

    @Test
    void topSolidUsesLiteralRomD3Height() {
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x0200, (short) 0x0100);
        ProbeHandLauncher launcher = buildLauncher(player);

        assertEquals(0x11, launcher.getSolidParams().airHalfHeight());
        assertEquals(0x11, launcher.getSolidParams().groundHalfHeight());
        assertTrue(launcher.rejectsZeroDistanceTopSolidLanding(),
                "SolidObjectTop accepts negative overlap but rejects the exact d0=0 boundary");
        assertFalse(launcher.usesPlatformObjectLandingSnap(),
                "SolidObjectTop keeps its relative landing result instead of PlatformObject's absolute snap");
    }

    @Test
    void grabbedPlayerUsesNativeBitZeroObjectControlPolicy() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0200, (short) 0x0100);
        player.setSubpixelRaw(0xF700, 0);
        ProbeHandLauncher launcher = buildLauncher(player);

        updateUntilGrabbed(launcher, player);

        assertTrue(player.isObjectControlled());
        assertTrue(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertEquals(0xF700, player.getXSubpixelRaw());
    }

    @Test
    void jumpEscapeClearsObjectControlPolicy() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0200, (short) 0x0100);
        ProbeHandLauncher launcher = buildLauncher(player);
        updateUntilGrabbed(launcher, player);

        player.setJumpInputPressed(true);
        launcher.update(20, player);

        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
    }

    @Test
    void automaticLaunchReleaseClearsObjectControlPolicy() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0200, (short) 0x0100);
        ProbeHandLauncher launcher = buildLauncher(player);
        updateUntilGrabbed(launcher, player);

        for (int frame = 20; frame < 180 && player.isObjectControlled(); frame++) {
            launcher.update(frame, player);
        }

        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
    }

    @Test
    void nativeP2QuerySidekickCanBeGrabbedWhenRawSidekickListIsEmpty() {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x0100);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x0200, (short) 0x0100);
        ProbeHandLauncher launcher = new ProbeHandLauncher(
                new ObjectSpawn(0x0208, 0x0100, 0x3A, 0, 0, false, 0));
        launcher.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));
        launcher.setCheckpointBatch(standingBatch(launcher, sidekick));

        updateUntilGrabbed(launcher, main, sidekick);

        assertTrue(sidekick.isObjectControlled(),
                "HCZ hand launcher has only native P1/P2 grab slots, so P2 must come from ObjectPlayerQuery NATIVE_P1_P2");
    }

    @Test
    void nonSpriteQueryMainDoesNotHideNativeP2Sidekick() {
        PlayableEntity nonSpriteMain = mock(PlayableEntity.class);
        TestablePlayableSprite updatePlayer = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x0100);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x0200, (short) 0x0100);
        ProbeHandLauncher launcher = new ProbeHandLauncher(
                new ObjectSpawn(0x0208, 0x0100, 0x3A, 0, 0, false, 0));
        launcher.setServices(new QueryOnlyPlayerServices(nonSpriteMain, List.of(sidekick)));
        launcher.setCheckpointBatch(standingBatch(launcher, sidekick));

        updateUntilGrabbed(launcher, updatePlayer, sidekick);

        assertTrue(sidekick.isObjectControlled(),
                "Non-sprite query participants must not consume the native P2 slot");
    }

    @Test
    void extendedSidekickDoesNotShareNativeP2GrabSlot() {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x0100);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0100, (short) 0x0100);
        TestablePlayableSprite extendedSidekick = new TestablePlayableSprite("knuckles", (short) 0x0200, (short) 0x0100);
        ProbeHandLauncher launcher = new ProbeHandLauncher(
                new ObjectSpawn(0x0208, 0x0100, 0x3A, 0, 0, false, 0));
        launcher.setServices(new QueryOnlyPlayerServices(main, List.of(nativeP2, extendedSidekick)));
        launcher.setCheckpointBatch(standingBatch(launcher, extendedSidekick));

        for (int frame = 0; frame < 16 && !extendedSidekick.isObjectControlled(); frame++) {
            launcher.update(frame, main);
        }

        assertFalse(extendedSidekick.isObjectControlled(),
                "Additional engine sidekicks need separate grab state before they can participate");
    }

    private static ProbeHandLauncher buildLauncher(TestablePlayableSprite player) {
        ObjectSpawn spawn = new ObjectSpawn(0x0208, 0x0100, 0x3A, 0, 0, false, 0);
        ProbeHandLauncher launcher = new ProbeHandLauncher(spawn);
        launcher.setServices(new TestObjectServices());
        launcher.setCheckpointBatch(standingBatch(launcher, player));
        return launcher;
    }

    private static void updateUntilGrabbed(ProbeHandLauncher launcher, TestablePlayableSprite player) {
        for (int frame = 0; frame < 16 && !player.isObjectControlled(); frame++) {
            launcher.update(frame, player);
        }
        assertTrue(player.isObjectControlled(), "Hand launcher should grab player from standing checkpoint");
    }

    private static void updateUntilGrabbed(
            ProbeHandLauncher launcher,
            TestablePlayableSprite updatePlayer,
            TestablePlayableSprite grabbedPlayer) {
        for (int frame = 0; frame < 16 && !grabbedPlayer.isObjectControlled(); frame++) {
            launcher.update(frame, updatePlayer);
        }
        assertTrue(grabbedPlayer.isObjectControlled(), "Hand launcher should grab native P2 from standing checkpoint");
    }

    private static SolidCheckpointBatch standingBatch(
            HCZHandLauncherObjectInstance launcher,
            TestablePlayableSprite player) {
        PlayerSolidContactResult result = new PlayerSolidContactResult(
                ContactKind.TOP,
                true,
                false,
                false,
                false,
                PreContactState.ZERO,
                new PostContactState((short) 0, (short) 0, false, true, false),
                0);
        return new SolidCheckpointBatch(launcher, Map.of(player, result));
    }

    private static final class ProbeHandLauncher extends HCZHandLauncherObjectInstance {
        private SolidCheckpointBatch checkpointBatch = new SolidCheckpointBatch(this, Map.of());

        private ProbeHandLauncher(ObjectSpawn spawn) {
            super(spawn);
        }

        private void setCheckpointBatch(SolidCheckpointBatch checkpointBatch) {
            this.checkpointBatch = checkpointBatch;
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            return checkpointBatch;
        }
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
