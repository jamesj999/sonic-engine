package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLbzTriggerBridgeInstance {

    @BeforeEach
    void resetTriggers() {
        Sonic3kLevelTriggerManager.reset();
    }

    @Test
    void registryRoutesS3klSlot14ToLbzTriggerBridge() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        ObjectInstance bridge = registry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE, 0, 0, false, 0));

        assertFalse(bridge instanceof PlaceholderObjectInstance,
                "S3KL slot $14 is Obj_LBZTriggerBridge and must not remain a placeholder");
        assertEquals("LBZTriggerBridge", bridge.getName());
        assertInstanceOf(SolidObjectProvider.class, bridge,
                "Obj_LBZTriggerBridge calls SolidObjectFull2 while render_flags bit 7 is set");
    }

    @Test
    void registryKeepsSklSlot14AsUpdraft() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance updraft = registry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.UPDRAFT, 0, 0, false, 0));

        assertFalse(updraft instanceof PlaceholderObjectInstance);
        assertEquals("Updraft", updraft.getName(),
                "S3K object slot $14 is zone-set-specific; SKL/MHZ must keep Obj_Updraft");
    }

    @Test
    void subtypeZeroInitializesRightVerticalBridgePiece() {
        LbzTriggerBridgeInstance bridge = new LbzTriggerBridgeInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE, 0x00, 0, false, 0));

        assertEquals(0x1848, bridge.getX());
        assertEquals(0x0600, bridge.getY());
        assertEquals(0, bridge.currentRoutineForTest());
        assertEquals(0, bridge.currentMappingFrameForTest());

        SolidObjectParams params = bridge.getSolidParams();
        assertEquals(0x13, params.halfWidth(),
                "SolidObjectFull2 receives width_pixels + $B, so $08 becomes $13");
        assertEquals(0x40, params.airHalfHeight());
        assertEquals(0x41, params.groundHalfHeight());
    }

    @Test
    void activeTriggerStartsBridgeInSecondPosition() {
        Sonic3kLevelTriggerManager.setAll(0x0C);

        LbzTriggerBridgeInstance bridge = new LbzTriggerBridgeInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE, 0x0C, 0, false, 0));

        assertEquals(0x1800, bridge.getX());
        assertEquals(0x0648, bridge.getY());
        assertEquals(0, bridge.currentRoutineForTest());
        assertEquals(0x08, bridge.currentMappingFrameForTest());
    }

    @Test
    void childSubtypeUsesSecondRoutineAndMappingFrame() {
        LbzTriggerBridgeInstance bridge = new LbzTriggerBridgeInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE, 0x4C, 0, false, 0),
                true,
                8);

        assertEquals(0x1800, bridge.getX());
        assertEquals(0x0648, bridge.getY());
        assertEquals(0x04, bridge.currentRoutineForTest());
        assertEquals(0x10, bridge.currentMappingFrameForTest());
        assertEquals(8, bridge.currentTimerForTest());
    }

    @Test
    void triggerOpenAdvancesSevenFramesThenExpires() {
        LbzTriggerBridgeInstance bridge = new LbzTriggerBridgeInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE, 0x0C, 0, false, 0));

        Sonic3kLevelTriggerManager.setAll(0x0C);
        bridge.update(0, null);

        assertEquals(2, bridge.currentRoutineForTest());
        assertEquals(1, bridge.currentMappingFrameForTest());
        assertEquals(7, bridge.currentTimerForTest());

        for (int i = 0; i < 7; i++) {
            bridge.update(i + 1, null);
        }

        assertEquals(0x7FFF, bridge.getX(), "loc_2609E moves the old bridge piece off-screen");
        assertEquals(8, bridge.currentMappingFrameForTest());
    }

    @Test
    void triggerCloseAdvancesToHoldRoutineAfterSevenFrames() {
        Sonic3kLevelTriggerManager.setAll(0x0C);
        LbzTriggerBridgeInstance bridge = new LbzTriggerBridgeInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE, 0x4C, 0, false, 0),
                true,
                1);

        bridge.update(0, null);
        assertEquals(6, bridge.currentRoutineForTest());

        Sonic3kLevelTriggerManager.clearAll(0x0C);
        bridge.update(1, null);

        assertEquals(8, bridge.currentRoutineForTest());
        assertEquals(0x10, bridge.currentMappingFrameForTest());
        assertEquals(7, bridge.currentTimerForTest());

        for (int i = 0; i < 7; i++) {
            bridge.update(i + 2, null);
        }

        assertEquals(0x7FFF, bridge.getX(), "loc_2609E also moves the replaced closing piece off-screen");
        assertEquals(0x17, bridge.currentMappingFrameForTest());
    }

    @Test
    void lbzPlanIncludesTriggerBridgeLevelArt() {
        var plan = Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_LBZ, 0);

        var bridge = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_TRIGGER_BRIDGE))
                .findFirst().orElse(null);

        assertNotNull(bridge, "Obj_LBZTriggerBridge uses resident LBZ misc art");
        assertEquals(Sonic3kConstants.MAP_LBZ_TRIGGER_BRIDGE_ADDR, bridge.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ_MISC, bridge.artTileBase());
        assertEquals(2, bridge.palette());
    }

    @Test
    void profileMarksLbzTriggerBridgeImplementedForS3klOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();

        assertTrue(profile.getImplementedIds().contains(Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE));
        assertFalse(Sonic3kObjectProfile.SHARED_IMPLEMENTED_IDS.contains(Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE),
                "Slot $14 is Updraft in SKL and must not be marked shared");
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
