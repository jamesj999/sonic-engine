package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestTensionBridgeBendTiming {

    @Test
    void triggeredCollapseCountdownSkipsSolidPassUntilRelease() throws Exception {
        Sonic3kLevelTriggerManager.reset();
        TensionBridgeObjectInstance bridge = new TensionBridgeObjectInstance(new ObjectSpawn(
                0x1000, 0x0788, Sonic3kObjectIds.TENSION_BRIDGE, 0x88, 0, false, 0x0788));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        setField(bridge, "collapseActive", true);

        assertTrue(bridge.suppressSlopeSampleThisFrame(player),
                "loc_3890C returns without sub_38A88 throughout the trigger-collapse countdown");
        assertTrue(bridge.defersAirborneRiderUnseatThisFrame(player),
                "the skipped solid pass must retain Status_OnObj until loc_38918 releases the rider");

        setField(bridge, "collapseActive", false);
        assertFalse(bridge.suppressSlopeSampleThisFrame(player));

        try {
            Sonic3kLevelTriggerManager.setAll(8);
            assertTrue(bridge.defersAirborneRiderUnseatThisFrame(player),
                    "the live trigger byte must suppress an earlier-slot solid checkpoint before bridge update");
        } finally {
            Sonic3kLevelTriggerManager.reset();
        }
    }

    @Test
    void bendUsesPriorContactSegmentBeforePublishingCurrentSegment() throws Exception {
        TensionBridgeObjectInstance bridge = new TensionBridgeObjectInstance(new ObjectSpawn(
                0x1000, 0x0788, Sonic3kObjectIds.TENSION_BRIDGE, 0x08, 0, false, 0x0788));
        ObjectManager objectManager = mock(ObjectManager.class);
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return objectManager; }
            @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_HCZ; }
            @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_HCZ; }
        };
        services.withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        bridge.setServices(services);
        when(objectManager.isAnyPlayerRiding(bridge)).thenReturn(true);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x1018); // current ROM segment 6
        setField(bridge, "playerOnBridge", true);
        setField(bridge, "playerSegmentIndex", 7); // prior contact segment
        setField(bridge, "depressionAngle", 0x40);

        bridge.update(0, player);

        assertEquals(-1, bridge.getSlopeData()[6 * 8],
                "first dispatch bends from prior segment 7 before sub_38A88 publishes segment 6");

        bridge.update(1, player);

        assertEquals(-4, bridge.getSlopeData()[6 * 8],
                "following dispatch consumes the segment 6 value published by the prior contact pass");
    }

    @Test
    void iczRopePublishesCurrentSegmentBeforeBending() throws Exception {
        TensionBridgeObjectInstance bridge = new TensionBridgeObjectInstance(new ObjectSpawn(
                0x1000, 0x0788, Sonic3kObjectIds.TENSION_BRIDGE, 0x88, 0, false, 0x0788));
        ObjectManager objectManager = mock(ObjectManager.class);
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return objectManager; }
            @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_ICZ; }
            @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_ICZ; }
        };
        services.withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        bridge.setServices(services);
        when(objectManager.isAnyPlayerRiding(bridge)).thenReturn(true);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 0x1018); // current ROM segment 6
        setField(bridge, "playerOnBridge", true);
        setField(bridge, "playerSegmentIndex", 7); // prior contact segment
        setField(bridge, "depressionAngle", 0x40);

        bridge.update(0, player);

        assertEquals(-22, bridge.getSlopeData()[6 * 8],
                "loc_38966 must run sub_38BD8 before sub_38D74 bends the ICZ rope");
    }

    @Test
    void playerTwoSegmentWalksSharedBendAnchorBeforeCalculation() throws Exception {
        TensionBridgeObjectInstance bridge = new TensionBridgeObjectInstance(new ObjectSpawn(
                0x1000, 0x0788, Sonic3kObjectIds.TENSION_BRIDGE, 0x08, 0, false, 0x0788));
        ObjectManager objectManager = mock(ObjectManager.class);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return objectManager; }
            @Override public List<com.openggf.game.PlayableEntity> sidekicks() { return List.of(sidekick); }
            @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_HCZ; }
            @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_HCZ; }
        };
        services.withPlayerQuery(new ObjectPlayerQuery(() -> null, () -> List.of(sidekick)));
        bridge.setServices(services);
        when(objectManager.isAnyPlayerRiding(bridge)).thenReturn(true);
        when(objectManager.isRidingObject(sidekick, bridge)).thenReturn(true);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        setField(bridge, "playerSegmentIndex", 7);
        setField(bridge, "sidekickSegmentIndex", 6);
        setField(bridge, "depressionAngle", 0x20);

        bridge.update(0, player);

        assertEquals(-3, bridge.getSlopeData()[6 * 8],
                "loc_387F6 must walk $3F toward Player 2's prior $3B before sub_38CC2");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
