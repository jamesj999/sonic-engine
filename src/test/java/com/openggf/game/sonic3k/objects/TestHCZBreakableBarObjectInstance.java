package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHCZBreakableBarObjectInstance {

    @BeforeEach
    @AfterEach
    void resetBreakableBarWaterRushLatch() {
        HCZWaterRushObjectInstance.HCZBreakableBarState.setState(0);
    }

    @Test
    void captureUsesNativeBitZeroPolicyAndNonDestructiveReleaseClearsIt() {
        TestablePlayableSprite player = positionedPlayer("sonic");
        HCZBreakableBarObjectInstance bar = verticalBar(0x40);
        bar.setServices(new TestObjectServices());

        bar.update(0, player);

        assertNativeBitZeroControl(player);

        player.setJumpInputPressed(true);
        bar.update(1, player);

        assertNoObjectControl(player);
    }

    @Test
    void destructiveReleaseBreakClearsCapturedSidekickPolicy() {
        TestablePlayableSprite player = positionedPlayer("sonic");
        TestablePlayableSprite sidekick = positionedPlayer("tails");
        HCZBreakableBarObjectInstance bar = verticalBar(0x00);
        bar.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));

        bar.update(0, player);

        assertNativeBitZeroControl(player);
        assertNativeBitZeroControl(sidekick);

        player.setJumpInputPressed(true);
        bar.update(1, player);

        assertNoObjectControl(player);
        assertNoObjectControl(sidekick);
    }

    @Test
    void nativeP2CaptureUsesObjectPlayerQueryWhenRawSidekickListIsEmpty() {
        TestablePlayableSprite main = positionedAwayFromBar("sonic");
        TestablePlayableSprite nativeP2 = positionedPlayer("tails");
        HCZBreakableBarObjectInstance bar = verticalBar(0x40);
        bar.setServices(new QueryOnlyPlayerServices(main, List.of(nativeP2), List.of()));

        bar.update(0, main);

        assertNativeBitZeroControl(nativeP2);
    }

    @Test
    void verticalCaptureRejectsExactLowerXBoundary() {
        TestablePlayableSprite player = positionedPlayer("sonic");
        player.setCentreX((short) 0x0214);
        player.capturePrePhysicsSnapshot();
        HCZBreakableBarObjectInstance bar = verticalBar(0x40);
        bar.setServices(new TestObjectServices());

        bar.update(0, player);

        assertNoObjectControl(player);

        player.setCentreX((short) 0x0215);
        player.capturePrePhysicsSnapshot();
        bar.update(1, player);

        assertNativeBitZeroControl(player);
    }

    @Test
    void verticalCaptureUsesCurrentPostPhysicsPositionForObjectPhase() {
        TestablePlayableSprite player = positionedPlayer("sonic");
        player.setCentreX((short) 0x020E);
        player.setCentreY((short) 0x0200);
        player.capturePrePhysicsSnapshot();
        player.setCentreX((short) 0x0216);

        HCZBreakableBarObjectInstance bar = verticalBar(0x40);
        bar.setServices(new TestObjectServices());

        bar.update(0, player);

        assertNativeBitZeroControl(player);
    }

    @Test
    void capturePreservesGroundSpeedLikeRom() {
        TestablePlayableSprite player = positionedPlayer("sonic");
        player.setGSpeed((short) 0x0056);
        player.setSubpixelRaw(0xF900, 0x3C00);
        HCZBreakableBarObjectInstance bar = verticalBar(0x40);
        bar.setServices(new TestObjectServices());

        bar.update(0, player);
        bar.update(1, player);

        assertNativeBitZeroControl(player);
        assertEquals((short) 0x0056, player.getGSpeed());
        assertEquals(0xF900, player.getXSubpixelRaw());
        assertEquals(0x3C00, player.getYSubpixelRaw());
    }

    @Test
    void horizontalCaptureUsesRomCrossAxisBand() {
        TestablePlayableSprite player = positionedPlayer("sonic");
        player.setCentreX((short) 0x0200);
        player.setCentreY((short) 0x01EC);
        player.capturePrePhysicsSnapshot();
        HCZBreakableBarObjectInstance bar = horizontalBar(0xC0);
        bar.setServices(new TestObjectServices());

        bar.update(0, player);

        assertNoObjectControl(player);

        player.setCentreY((short) 0x01ED);
        player.capturePrePhysicsSnapshot();
        bar.update(1, player);

        assertNativeBitZeroControl(player);
    }

    @Test
    void horizontalCapturePreservesSubpixelsLikeRom() {
        TestablePlayableSprite player = positionedPlayer("sonic");
        player.setCentreX((short) 0x0200);
        player.setCentreY((short) 0x01ED);
        player.capturePrePhysicsSnapshot();
        player.setSubpixelRaw(0x1200, 0xE900);
        HCZBreakableBarObjectInstance bar = horizontalBar(0xC0);
        bar.setServices(new TestObjectServices());

        bar.update(0, player);
        player.setDirectionalInputPressed(false, false, false, true);
        bar.update(1, player);

        assertNativeBitZeroControl(player);
        assertEquals(0x1200, player.getXSubpixelRaw());
        assertEquals(0xE900, player.getYSubpixelRaw());
    }

    @Test
    void horizontalCaptureClampsRightEdgeFromCurrentPosition() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x0769);
        player.setCentreY((short) 0x057D);
        player.setXSpeed((short) 0x03F0);
        player.setYSpeed((short) 0xFFF0);
        player.setGSpeed((short) 0x0056);
        player.capturePrePhysicsSnapshot();
        player.setCentreX((short) 0x0771);

        HCZBreakableBarObjectInstance bar = horizontalBarAt(0x0758, 0x0590, 0x80);
        bar.setServices(new TestObjectServices());

        bar.update(1020, player);

        assertNativeBitZeroControl(player);
        assertEquals((short) 0x076C, player.getCentreX());
        assertEquals((short) 0x057C, player.getCentreY());
        assertEquals((short) 0, player.getXSpeed());
        assertEquals((short) 0, player.getYSpeed());
        assertEquals((short) 0x0056, player.getGSpeed());
    }

    @Test
    void extraSidekickDoesNotShareNativeP2CaptureSlot() {
        TestablePlayableSprite main = positionedAwayFromBar("sonic");
        TestablePlayableSprite nativeP2 = positionedAwayFromBar("tails");
        TestablePlayableSprite extraSidekick = positionedPlayer("knuckles");
        List<PlayableEntity> sidekicks = List.of(nativeP2, extraSidekick);
        HCZBreakableBarObjectInstance bar = verticalBar(0x40);
        bar.setServices(new QueryOnlyPlayerServices(main, sidekicks, sidekicks));

        bar.update(0, main);

        assertNoObjectControl(extraSidekick);
    }

    private static HCZBreakableBarObjectInstance verticalBar(int subtype) {
        return new HCZBreakableBarObjectInstance(
                new ObjectSpawn(0x0200, 0x0200, 0x36, subtype, 0, false, 0));
    }

    private static HCZBreakableBarObjectInstance horizontalBar(int subtype) {
        return new HCZBreakableBarObjectInstance(
                new ObjectSpawn(0x0200, 0x0200, 0x36, subtype, 0, false, 0));
    }

    private static HCZBreakableBarObjectInstance horizontalBarAt(int x, int y, int subtype) {
        return new HCZBreakableBarObjectInstance(
                new ObjectSpawn(x, y, 0x36, subtype, 0, false, 0));
    }

    private static TestablePlayableSprite positionedPlayer(String character) {
        TestablePlayableSprite player = new TestablePlayableSprite(character, (short) 0, (short) 0);
        player.setCentreX((short) 0x0215);
        player.setCentreY((short) 0x0200);
        player.capturePrePhysicsSnapshot();
        return player;
    }

    private static TestablePlayableSprite positionedAwayFromBar(String character) {
        TestablePlayableSprite player = new TestablePlayableSprite(character, (short) 0, (short) 0);
        player.setCentreX((short) 0x0100);
        player.setCentreY((short) 0x0100);
        player.capturePrePhysicsSnapshot();
        return player;
    }

    private static void assertNativeBitZeroControl(TestablePlayableSprite player) {
        assertTrue(player.isObjectControlled());
        assertTrue(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertFalse(player.isTouchResponseSuppressedByObjectControl());
    }

    private static void assertNoObjectControl(TestablePlayableSprite player) {
        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
        assertFalse(player.isTouchResponseSuppressedByObjectControl());
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;
        private final List<PlayableEntity> rawSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main,
                                        List<? extends PlayableEntity> queriedSidekicks,
                                        List<PlayableEntity> rawSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
            this.rawSidekicks = List.copyOf(rawSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return rawSidekicks;
        }
    }
}
