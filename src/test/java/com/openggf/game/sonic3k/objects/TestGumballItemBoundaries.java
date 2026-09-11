package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused boundary tests for {@link GumballItemObjectInstance}'s machine-ejected
 * (GUMBALL_EJECT) motion path: the half-open {@code Check_PlayerInRange} proximity
 * box (ROM word_610F0, sonic3k.asm:127793-127794), the dock-eligibility gate
 * (ROM loc_60EE0/loc_60EFC, sonic3k.asm:127609-127624), and the removal of the
 * engine-only "pushedPlayer" single-trigger latch for the self-polled path
 * (see class-level ROM citation on {@code update()} in GumballItemObjectInstance).
 * <p>
 * These lock in exact edge behavior that a full trace replay would only
 * incidentally exercise at whatever pixel positions the recorded BK2 trace
 * happens to land on.
 */
class TestGumballItemBoundaries {

    private static final int ITEM_X = 0x100;
    private static final int ITEM_Y = 0x180;

    @Test
    void proximityBox_dxAtRadius_isHalfOpen_doesNotTrigger() {
        // Player exactly PROXIMITY_RADIUS (0x18=24) to the right: dx == 24, the
        // exclusive upper bound of [-24,24) — must NOT trigger (ROM half-open box).
        GumballItemObjectInstance item = new GumballItemObjectInstance(
                new ObjectSpawn(ITEM_X, ITEM_Y, 0xEB, 2, 0, false, 0), 0, true);
        AbstractPlayableSprite player = mockPlayerAtCentre(ITEM_X + 24, ITEM_Y);

        item.setServices(new TestObjectServices());
        item.update(0, player);

        verify(player, never()).addRings(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void proximityBox_dxJustInsideRadius_triggers() {
        // dx == 23, one px inside the half-open box — must trigger.
        GumballItemObjectInstance item = new GumballItemObjectInstance(
                new ObjectSpawn(ITEM_X, ITEM_Y, 0xEB, 2, 0, false, 0), 0, true);
        AbstractPlayableSprite player = mockPlayerAtCentre(ITEM_X + 23, ITEM_Y);

        item.setServices(new TestObjectServices());
        item.update(0, player);

        verify(player, times(1)).addRings(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void dockEligibility_yEqualsParentPlus15_notYetEligible() {
        // motionState.y - parentY == 15 < DOCK_ELIGIBLE_THRESHOLD(0x10=16) -> not eligible,
        // so even a player sitting inside the proximity box must not collect the reward.
        int parentY = ITEM_Y - 15;
        GumballItemObjectInstance item = new GumballItemObjectInstance(
                new ObjectSpawn(ITEM_X, ITEM_Y, 0xEB, 2, 0, false, 0), 0, true, () -> parentY);
        AbstractPlayableSprite player = mockPlayerAtCentre(ITEM_X, ITEM_Y);

        item.setServices(new TestObjectServices());
        item.update(0, player);

        verify(player, never()).addRings(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void dockEligibility_yEqualsParentPlus16_isEligible() {
        // motionState.y - parentY == 16 == DOCK_ELIGIBLE_THRESHOLD -> eligible.
        int parentY = ITEM_Y - 16;
        GumballItemObjectInstance item = new GumballItemObjectInstance(
                new ObjectSpawn(ITEM_X, ITEM_Y, 0xEB, 2, 0, false, 0), 0, true, () -> parentY);
        AbstractPlayableSprite player = mockPlayerAtCentre(ITEM_X, ITEM_Y);

        item.setServices(new TestObjectServices());
        item.update(0, player);

        verify(player, times(1)).addRings(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void pushBall_rePollsAndPushesAgain_afterPlayerLeavesAndReentersRange() {
        // ROM sub_610E0/loc_6115C: loc_6116E's clr.b collision_property(a0) only clears
        // the STANDARD touch/collision-response-list latch, never consulted by this
        // self-polled path, so a still-alive push ball must push again on any later
        // frame the player re-enters the box -- no engine-only "already pushed" gate.
        GumballItemObjectInstance item = new GumballItemObjectInstance(
                new ObjectSpawn(ITEM_X, ITEM_Y, 0xEB, 4, 0, false, 0), 0, true);
        item.setServices(new TestObjectServices());

        AbstractPlayableSprite inRange = mockPlayerAtCentre(ITEM_X, ITEM_Y);
        AbstractPlayableSprite outOfRange = mockPlayerAtCentre(ITEM_X + 1000, ITEM_Y);

        item.update(0, inRange);   // window 1: push fires
        item.update(1, outOfRange); // out of range: no push
        item.update(2, inRange);   // window 2: push fires again

        verify(inRange, times(2)).setXSpeed(org.mockito.ArgumentMatchers.anyShort());
    }

    private static AbstractPlayableSprite mockPlayerAtCentre(int centreX, int centreY) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) centreX);
        when(player.getCentreY()).thenReturn((short) centreY);
        return player;
    }
}
