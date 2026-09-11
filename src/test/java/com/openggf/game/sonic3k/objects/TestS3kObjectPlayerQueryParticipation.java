package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kObjectPlayerQueryParticipation {

    @Test
    void cnzCorkFloorExposesNativeWidthForObjectEdgeBalance() {
        TestObjectServices services = new TestObjectServices() {
            @Override
            public int romZoneId() {
                return com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ;
            }
        };
        CorkFloorObjectInstance floor = ObjectConstructionContext.construct(services,
                () -> new CorkFloorObjectInstance(new ObjectSpawn(
                        0x0100, 0x0200, 0x2A, 1, 0, false, 0)));

        assertEquals(0x20, floor.getBalanceWidthPixels(),
                "CNZ Obj_CorkFloor sets width_pixels=$20; balance must not use the shared $10 default");
    }

    @Test
    void iczSlopedCorkFloorRideExitPreservesGroundedTerrainHandoff() {
        TestObjectServices services = new TestObjectServices() {
            @Override
            public int romZoneId() {
                return com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_ICZ;
            }
        };
        CorkFloorObjectInstance slopedFloor = ObjectConstructionContext.construct(services,
                () -> new CorkFloorObjectInstance(new ObjectSpawn(
                        0x27B0, 0x0208, 0x2A, 0, 0, false, 0)));
        CorkFloorObjectInstance smallFullSolid = ObjectConstructionContext.construct(services,
                () -> new CorkFloorObjectInstance(new ObjectSpawn(
                        0x27B0, 0x0208, 0x2A, 0x10, 0, false, 0)));

        assertFalse(slopedFloor.forceAirOnRideExit(),
                "sub_1DDC6 clears OnObj without setting InAir so terrain can take over next frame");
        assertTrue(smallFullSolid.forceAirOnRideExit(),
                "ICZ subtype bit 4 uses SolidObjectFull and its ordinary airborne ride exit");
    }

    @Test
    void aizCollapsingLogBridgeUsesPlayerQueryParticipantsForSidekickStanding() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x0100, 0x0200);
        TestablePlayableSprite sidekick = player("tails_p2", 0x0100, 0x0200);
        sidekick.setCpuControlled(true);

        TestAizBridge bridge = new TestAizBridge(new ObjectSpawn(
                0x0100, 0x0200, 0x2C, 0, 0, false, 0));
        bridge.batch = new SolidCheckpointBatch(bridge, Map.of(sidekick, standingContact(2)));
        bridge.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        bridge.update(0, main);

        assertTrue(readBoolean(bridge, "collapseArmedByStanding"),
                "AIZ collapsing log bridge should consume query-only sidekick standing state");
    }

    @Test
    void corkFloorUsesPlayerQueryParticipantsForSidekickRollBreak() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x0100, 0x0200);
        TestablePlayableSprite sidekick = player("tails_p2", 0x0100, 0x0200);
        sidekick.setCpuControlled(true);

        TestCorkFloor floor = new TestCorkFloor(new ObjectSpawn(
                0x0100, 0x0200, 0x2A, 1, 0, false, 0));
        floor.batch = new SolidCheckpointBatch(floor, Map.of(sidekick, standingContact(2)));
        floor.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));
        int launchY = sidekick.getCentreY();

        floor.update(0, main);

        assertTrue(readBoolean(floor, "broken"),
                "Cork floor should consume query-only sidekick roll-break state");
        assertTrue(sidekick.getAir(),
                "Roll-breaking query-only sidekick should receive the launch handoff");
        assertEquals(launchY, sidekick.getCentreY(),
                "Cork-floor roll launch must preserve ROM y_pos while changing rolling dimensions");
    }

    @Test
    void aizRockSideBreakEjectsOtherStandingPlayerAndExposesRoutineWord() throws Exception {
        TestablePlayableSprite main = player("sonic", 0x00E0, 0x0200);
        TestablePlayableSprite sidekick = player("tails_p2", 0x0100, 0x01E0);
        sidekick.setCpuControlled(true);
        sidekick.setOnObject(true);
        sidekick.setAir(false);

        TestAizRock rock = new TestAizRock(new ObjectSpawn(
                0x0100, 0x0200, 0x05, 0x04, 0, false, 0));
        rock.batch = new SolidCheckpointBatch(rock, Map.of(
                main, sideBreakingContact(),
                sidekick, standingContact(0)));
        rock.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        rock.update(0, main);

        assertTrue(readBoolean(rock, "breaking"), "rolling side contact should break the AIZ rock");
        assertTrue(sidekick.getAir(), "sub_1FF1E ejects the non-breaking standing sidekick");
        assertFalse(sidekick.isOnObject(), "sub_1FF1E clears the sidekick's Status_OnObj bit");
        assertEquals(0x0001, rock.romObjectCodePointerHighWord(),
                "Tails_CPU_interact samples the intact rock routine in ROM bank $0001");
    }

    private static TestablePlayableSprite player(String code, int x, int y) {
        return new TestablePlayableSprite(code, (short) x, (short) y);
    }

    private static PlayerSolidContactResult standingContact(int preContactAnimationId) {
        return new PlayerSolidContactResult(
                ContactKind.TOP,
                true,
                false,
                false,
                false,
                new PreContactState((short) 0, (short) 0x180, preContactAnimationId == 2,
                        preContactAnimationId),
                PostContactState.ZERO,
                0);
    }

    private static PlayerSolidContactResult sideBreakingContact() {
        return new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                true,
                true,
                false,
                new PreContactState((short) 0x500, (short) 0, true, 2),
                PostContactState.ZERO,
                0);
    }

    private static boolean readBoolean(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static final class TestAizBridge extends AizCollapsingLogBridgeObjectInstance {
        private SolidCheckpointBatch batch;

        private TestAizBridge(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            return batch;
        }
    }

    private static final class TestCorkFloor extends CorkFloorObjectInstance {
        private SolidCheckpointBatch batch;

        private TestCorkFloor(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            return batch;
        }
    }

    private static final class TestAizRock extends AizLrzRockObjectInstance {
        private SolidCheckpointBatch batch;

        private TestAizRock(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            return batch;
        }
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final ObjectPlayerQuery playerQuery;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.playerQuery = new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
            withCamera(new Camera());
            withConfiguration(SonicConfigurationService.getInstance());
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return playerQuery;
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }
}
