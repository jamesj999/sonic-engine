package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizCollapsingLogBridgeObjectInstance {

    @Test
    void bothBridgeSubtypesRejectTheExactTopSolidBoundary() {
        AizCollapsingLogBridgeObjectInstance normal = bridge(0x08);
        AizCollapsingLogBridgeObjectInstance fire = bridge(0x88);

        assertTrue(normal.rejectsZeroDistanceTopSolidLanding(null));
        assertTrue(fire.rejectsZeroDistanceTopSolidLanding(null));
    }

    @Test
    void collapseKnockoffPublishesNativePreviousAnimationByte() {
        AizCollapsingLogBridgeObjectInstance bridge = bridge(0x08);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0, (short) 0);
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        player.getAnimationManager().publishPreviousAnimationId(Sonic3kAnimationIds.WALK.id());

        bridge.publishKnockOffAnimationState(player);

        assertEquals(Sonic3kAnimationIds.WALK.id(), player.getAnimationId(),
                "sub_2AF9C leaves anim untouched");
        assertEquals(Sonic3kAnimationIds.RUN.id(),
                player.getAnimationManager().captureRewindState().lastAnimationId(),
                "sub_2AF9C writes prev_anim=Run after releasing the rider");
    }

    @Test
    void fireBridgeDropsRiderTrackingWhenSolidTopClearsStanding() {
        AizCollapsingLogBridgeObjectInstance bridge = bridge(0x88);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0, (short) 0);
        PlayerSolidContactResult standing = new PlayerSolidContactResult(
                ContactKind.TOP, true, false, false, false, null, null, 0);
        PlayerSolidContactResult released = new PlayerSolidContactResult(
                ContactKind.NONE, false, true, false, false, null, null, 0);

        bridge.recordStandingPlayer(player, standing);
        assertTrue(bridge.isTrackingStandingPlayer(player));

        bridge.recordStandingPlayer(player, released);

        assertEquals(false, bridge.isTrackingStandingPlayer(player),
                "SolidObjectTop's cleared parent standing bit must remove the stale fire-bridge rider");
    }

    private static AizCollapsingLogBridgeObjectInstance bridge(int subtype) {
        return new AizCollapsingLogBridgeObjectInstance(
                new ObjectSpawn(0x1AE5, 0x04F8, 0x2C, subtype, 0, false, 0));
    }
}
