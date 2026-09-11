package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kLoadBootstrap;
import com.openggf.game.sonic3k.Sonic3kZoneFeatureProvider;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.graphics.RenderPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.openggf.level.scroll.M68KMath.packScrollWords;

public class TestSonic3kZoneFeatureProvider {

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void forestFrontPhaseMovesPlayerBucketWithoutOwningPathPriority() {
        TestZoneFeatureProvider provider = new TestZoneFeatureProvider();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        FakeAizEvents aizEvents = new FakeAizEvents();
        aizEvents.setForestFrontPhaseActive(true);
        provider.setAizEvents(aizEvents);

        // Obj_PathSwap subtype $22 at x=$3F68 sets this before the late forest.
        player.setHighPriority(true);
        provider.update(player, 0x44D0, Sonic3kZoneIds.ZONE_AIZ);

        assertTrue(player.isHighPriority(), "The forest override must preserve Obj_PathSwap priority");
        assertEquals(RenderPriority.MIN, player.getPriorityBucket(),
                "AIZ forest handoff should also move Sonic into the front display bucket");
    }

    @Test
    public void forestFrontBucketReleaseWaitsUntilHurtStateEnds() {
        TestZoneFeatureProvider provider = new TestZoneFeatureProvider();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        FakeAizEvents aizEvents = new FakeAizEvents();
        provider.setAizEvents(aizEvents);

        player.setHighPriority(true);
        aizEvents.setForestFrontPhaseActive(true);
        provider.update(player, 0x44D0, Sonic3kZoneIds.ZONE_AIZ);
        assertTrue(player.isHighPriority());

        player.setHurt(true);
        aizEvents.setForestFrontPhaseActive(false);
        provider.update(player, 0x4670, Sonic3kZoneIds.ZONE_AIZ);
        assertEquals(RenderPriority.MIN, player.getPriorityBucket(),
                "The forest bucket should not clear while hurt/death priority is still valid");

        player.setHurt(false);
        provider.update(player, 0x4670, Sonic3kZoneIds.ZONE_AIZ);
        assertTrue(player.isHighPriority(),
                "Releasing the forest bucket must retain the late path switch's art_tile priority");
        assertEquals(RenderPriority.PLAYER_DEFAULT, player.getPriorityBucket(),
                "Forced forest display bucket should reset once the protected state ends");
    }

    @Test
    public void aizMinibossResultsDoNotTakeOwnershipFromPathSwitcher() {
        TestZoneFeatureProvider provider = new TestZoneFeatureProvider();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        FakeAizEvents aizEvents = new FakeAizEvents();
        provider.setAizEvents(aizEvents);

        // Obj_PathSwap subtype $22 at x=$10E0 sets art_tile's high-priority bit
        // when Sonic crosses to its right side.
        player.setHighPriority(true);
        aizEvents.setBossFlag(true);
        provider.update(player, 0x10E0, Sonic3kZoneIds.ZONE_AIZ);
        assertTrue(player.isHighPriority());
        assertEquals(RenderPriority.PLAYER_DEFAULT, player.getPriorityBucket(),
                "The AIZ miniboss should leave path-switch priority under Obj_PathSwap ownership");

        aizEvents.setBossFlag(false);
        GameServices.gameState().setEndOfLevelActive(true);
        provider.update(player, 0x10E0, Sonic3kZoneIds.ZONE_AIZ);
        GameServices.gameState().setEndOfLevelActive(false);
        provider.update(player, 0x10E0, Sonic3kZoneIds.ZONE_AIZ);

        assertTrue(player.isHighPriority(),
                "Ending the miniboss/results sequence must not clear Obj_PathSwap's art_tile priority bit");
        assertEquals(RenderPriority.PLAYER_DEFAULT, player.getPriorityBucket());
    }

    @Test
    public void forestFrontPhaseAlsoMovesCpuSidekickBucketWithoutOwningPathPriority() {
        TestZoneFeatureProvider provider = new TestZoneFeatureProvider();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        sidekick.setCpuControlled(true);
        sidekick.setHighPriority(true);
        GameServices.sprites().addSprite(sidekick, "tails");

        FakeAizEvents aizEvents = new FakeAizEvents();
        aizEvents.setForestFrontPhaseActive(true);
        provider.setAizEvents(aizEvents);

        provider.update(player, 0x44D0, Sonic3kZoneIds.ZONE_AIZ);

        assertTrue(sidekick.isHighPriority(),
                "The forest override must preserve CPU Tails's Obj_PathSwap priority");
        assertEquals(RenderPriority.MIN, sidekick.getPriorityBucket(),
                "AIZ forest handoff should also move CPU Tails into the front display bucket");
    }

    @Test
    public void forestFrontOverrideOnlyAppliesInAct2() {
        TestZoneFeatureProvider provider = new TestZoneFeatureProvider();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        FakeAizEvents aizEvents = new FakeAizEvents();
        aizEvents.setForestFrontPhaseActive(true);
        provider.setAizEvents(aizEvents);
        provider.setFeatureActId(0);

        provider.update(player, 0x44D0, Sonic3kZoneIds.ZONE_AIZ);

        assertFalse(player.isHighPriority(), "AIZ1 should not inherit the AIZ2 forest-front priority override");
        assertEquals(RenderPriority.PLAYER_DEFAULT, player.getPriorityBucket(),
                "AIZ1 should keep the normal player display bucket");
    }

    @Test
    public void slotDisplayOriginUsesForegroundPlaneSpaceForSlotsPanel() throws Exception {
        TestZoneFeatureProvider provider = new TestZoneFeatureProvider();

        GameServices.camera().setX((short) 0x3C0);
        GameServices.camera().setY((short) 0x2F0);
        GameServices.parallax().getHScroll()[0] = packScrollWords((short) -0x360, (short) 0);

        var vscrollField = GameServices.parallax().getClass().getDeclaredField("vscrollFactorFG");
        vscrollField.setAccessible(true);
        vscrollField.setShort(GameServices.parallax(), (short) 0x3E0);

        assertEquals(0x360, provider.slotDisplayOriginX());
        assertEquals(0x3E0, provider.slotDisplayOriginY());
    }

    @Test
    public void s3kWaterlineSplitUsesRomWaterLevelDirectly() {
        Sonic3kZoneFeatureProvider provider = new Sonic3kZoneFeatureProvider();

        assertEquals(0.0f, provider.getWaterlineOffset(Sonic3kZoneIds.ZONE_AIZ, 1),
                "S3K Handle_Onscreen_Water_Height uses Water_level directly");
        assertEquals(0.0f, provider.getWaterlineOffset(Sonic3kZoneIds.ZONE_HCZ, 0),
                "S3K Handle_Onscreen_Water_Height uses Water_level directly");
        assertEquals(0.0f, provider.getWaterlineOffset(Sonic3kZoneIds.ZONE_CNZ, 1),
                "S3K Handle_Onscreen_Water_Height uses Water_level directly");
        assertEquals(0.0f, provider.getWaterlineOffset(Sonic3kZoneIds.ZONE_ICZ, 1),
                "S3K Handle_Onscreen_Water_Height uses Water_level directly");
        assertEquals(0.0f, provider.getWaterlineOffset(Sonic3kZoneIds.ZONE_LBZ, 1),
                "S3K Handle_Onscreen_Water_Height uses Water_level directly");
    }

    private static final class TestZoneFeatureProvider extends Sonic3kZoneFeatureProvider {
        private AizZoneRuntimeState aizState;
        private int featureZoneId = Sonic3kZoneIds.ZONE_AIZ;
        private int featureActId = 1;

        void setAizEvents(Sonic3kAIZEvents aizEvents) {
            this.aizState = aizEvents != null
                    ? new AizZoneRuntimeState(1, PlayerCharacter.SONIC_AND_TAILS, aizEvents)
                    : null;
        }

        void setFeatureActId(int featureActId) {
            this.featureActId = featureActId;
        }

        @Override
        protected AizZoneRuntimeState getAizState() {
            return aizState;
        }

        @Override
        protected int getFeatureZoneId() {
            return featureZoneId;
        }

        @Override
        protected int getFeatureActId() {
            return featureActId;
        }

        int slotDisplayOriginX() {
            return resolveSlotDisplayOriginX(GameServices.camera());
        }

        int slotDisplayOriginY() {
            return resolveSlotDisplayOriginY(GameServices.camera());
        }
    }

    private static final class FakeAizEvents extends Sonic3kAIZEvents {
        private boolean forestFrontPhaseActive;

        private FakeAizEvents() {
            super(Sonic3kLoadBootstrap.NORMAL);
        }

        void setForestFrontPhaseActive(boolean forestFrontPhaseActive) {
            this.forestFrontPhaseActive = forestFrontPhaseActive;
        }

        @Override
        public boolean isBattleshipForestFrontPhaseActive() {
            return forestFrontPhaseActive;
        }
    }
}
