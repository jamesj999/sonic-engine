package com.openggf.game.sonic3k.objects;

import com.openggf.game.ThresholdTableWaterHandler;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Lbz2KnucklesDynamicWaterHandler;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.level.LevelData;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLbzPipePlugObjectInstance {
    private static final int PIPE_X = 0x1800;
    private static final int PIPE_Y = 0x0660;

    @Test
    void registryRoutesS3klSlot1bToLbzPipePlugOnlyForLbz() {
        ObjectSpawn spawn = spawnWithSubtype(0);

        ObjectInstance lbzObject = registryForZone(Sonic3kZoneIds.ZONE_LBZ).create(spawn);
        ObjectInstance lrzObject = registryForZone(Sonic3kZoneIds.ZONE_LRZ).create(spawn);

        assertInstanceOf(LbzPipePlugObjectInstance.class, lbzObject);
        assertEquals("LBZPipePlug", lbzObject.getName());
        assertInstanceOf(PlaceholderObjectInstance.class, lrzObject,
                "SKL slot $1B is Obj_LRZFireballLauncher, not the LBZ pipe plug");
        assertEquals("LRZFireballLauncher", lrzObject.getName());
    }

    @Test
    void profileMarksPipePlugImplementedForLbzOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        var lbz2 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_LAUNCH_BASE_2)
                .findFirst().orElseThrow();
        var mhz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst().orElseThrow();

        assertTrue(profile.getImplementedIds(lbz2).contains(Sonic3kObjectIds.LBZ_PIPE_PLUG));
        assertFalse(profile.getImplementedIds(mhz1).contains(Sonic3kObjectIds.LBZ_PIPE_PLUG),
                "Object ID $1B belongs to LRZFireballLauncher in the SKL set");
        assertFalse(Sonic3kObjectProfile.SHARED_IMPLEMENTED_IDS.contains(Sonic3kObjectIds.LBZ_PIPE_PLUG));
    }

    @Test
    void exposesRomLevelArtSolidExtentsAndInitialFrame() {
        LbzPipePlugObjectInstance plug = new LbzPipePlugObjectInstance(spawnWithSubtype(0));

        SolidObjectParams solid = plug.getSolidParams();

        assertEquals(Sonic3kObjectArtKeys.LBZ_PIPE_PLUG, plug.artKeyForTesting());
        assertEquals(0x1B, solid.halfWidth());
        assertEquals(0x20, solid.airHalfHeight());
        assertEquals(0x21, solid.groundHalfHeight());
        assertEquals(0x10, plug.getOnScreenHalfWidth());
        assertEquals(0x20, plug.getOnScreenHalfHeight());
        assertEquals(4, plug.getPriorityBucket());
        assertEquals(7, plug.mappingFrameForTesting());
    }

    @Test
    void updateDoesNotSelfDeleteWhenXIsInRomSpriteOnScreenRangeButYIsOutsideViewport() {
        AbstractObjectInstance.updateCameraBounds(PIPE_X - 0x80, 0, PIPE_X + 0x140, 0x00E0, 0);
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0);
        TestablePlayableSprite player = player("sonic", PIPE_X + 0x40, PIPE_Y);

        plug.update(0, player);

        assertFalse(plug.isDestroyed(),
                "Obj_LBZPipePlug ends in Sprite_OnScreen_Test, which is X-only in sonic3k.asm");
    }

    @Test
    void rollingPushAtVelocityThresholdBreaksPlugAndRestoresPlayerVelocity() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0);
        TestablePlayableSprite player = player("sonic", PIPE_X + 0x20, PIPE_Y);
        player.setAnimationId(2);
        player.setPushing(true);
        player.setXSpeed((short) 0x0480);
        player.setGSpeed((short) 0x0000);

        plug.onSolidContact(player, new SolidContact(false, true, false, false, true), 12);

        assertTrue(plug.isBrokenForTesting());
        assertEquals(PIPE_X + 0x24, player.getCentreX() & 0xFFFF,
                "ROM adds 4 to x_pos on right-side break before restoring x_vel");
        assertEquals(0x0480, player.getXSpeed() & 0xFFFF);
        assertEquals(0x0480, player.getGSpeed() & 0xFFFF);
        assertFalse(player.getPushing());
        assertEquals(List.of(com.openggf.game.sonic3k.audio.Sonic3kSfx.COLLAPSE.id), services.playedSfx);
        assertEquals(15, services.children.size(),
                "PipePlugSmashObject allocates 12 single-tile shards plus 3 new single-piece chunks; "
                        + "the original object becomes the fourth single-piece chunk");
    }

    @Test
    void updateBreaksFromPreSolidVelocityEvenWhenSideResolutionZeroedLiveXSpeed() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0);
        TestablePlayableSprite player = player("sonic", PIPE_X + 0x20, PIPE_Y);
        player.setAnimationId(2);
        player.setPushing(true);
        player.setXSpeed((short) 0x0000);
        player.setGSpeed((short) 0x0000);
        services.setCheckpointBatch(batch(plug, Map.of(player, pushingResult(0x0480, 0, 2))));

        plug.update(12, player);

        assertTrue(plug.isBrokenForTesting(),
                "Obj_LBZPipePlug saves x_vel before SolidObjectFull, so post-solid x_vel=0 must still break");
        assertEquals(0x0480, player.getXSpeed() & 0xFFFF);
        assertEquals(0x0480, player.getGSpeed() & 0xFFFF);
    }

    @Test
    void breakSideSelectionUsesPlayerPositionAfterRomPlusFourNudge() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0);
        TestablePlayableSprite player = player("sonic", PIPE_X - 2, PIPE_Y);
        player.setAnimationId(2);
        player.setPushing(true);
        player.setXSpeed((short) 0x0000);
        services.setCheckpointBatch(batch(plug, Map.of(player, pushingResult(0x0480, 0, 2))));

        plug.update(12, player);

        assertTrue(plug.isBrokenForTesting());
        assertEquals(PIPE_X + 2, player.getCentreX() & 0xFFFF,
                "ROM adds 4 before cmp.w x_pos(a1), so a player at objectX-2 breaks from the right");
    }

    @Test
    void rollingPushBelowVelocityThresholdDoesNotBreakPlug() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0);
        TestablePlayableSprite player = player("sonic", PIPE_X + 0x20, PIPE_Y);
        player.setAnimationId(2);
        player.setPushing(true);
        player.setXSpeed((short) 0x047F);

        plug.onSolidContact(player, new SolidContact(false, true, false, false, true), 12);

        assertFalse(plug.isBrokenForTesting());
        assertEquals(0, services.children.size());
    }

    @Test
    void nonzeroSubtypeSpawnsDelayedTunnelAndExhaustControlOnBreak() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0x16);
        TestablePlayableSprite player = player("sonic", PIPE_X - 0x20, PIPE_Y);
        player.setAnimationId(2);
        player.setPushing(true);
        player.setXSpeed((short) -0x0480);

        plug.onSolidContact(player, new SolidContact(false, true, false, false, true), 12);

        assertTrue(plug.isBrokenForTesting());
        assertEquals(PIPE_X - 0x24, player.getCentreX() & 0xFFFF,
                "ROM adds 4 then subtracts 8 when the player breaks from the left side");
        assertInstanceOf(AutomaticTunnelDelayedObjectInstance.class, services.children.get(0));
        assertInstanceOf(TunnelExhaustControlObjectInstance.class, services.children.get(1));
        assertEquals(PIPE_X, services.children.get(0).getX());
        assertEquals(PIPE_Y + 2, services.children.get(0).getY());
        assertEquals(PIPE_X, services.children.get(1).getX());
        assertEquals(PIPE_Y - 0x20, services.children.get(1).getY());
        assertEquals(17, services.children.size());
    }

    @Test
    void subtype1fSetsDestroyedWaterPathButNotWaterSpeedWithoutSuperSonicKnuxFlag() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0x1F);
        TestablePlayableSprite player = player("knuckles", PIPE_X + 0x20, PIPE_Y);
        player.setAnimationId(2);
        player.setPushing(true);
        player.setXSpeed((short) 0x0480);

        plug.onSolidContact(player, new SolidContact(false, true, false, false, true), 12);

        assertTrue(services.waterSystem.lbz2WaterHandler.isPipePlugDestroyed(),
                "subtype $1F sets ROM _unkF7C2 for the LBZ2 Knuckles water handler");
        assertEquals(0x0660, services.waterSystem.waterState.getTargetLevel());
        assertFalse(services.waterSystem.waterSpeedSet,
                "ROM only writes Water_speed = 2 when Super_Sonic_Knux_flag is nonzero");
        assertInstanceOf(TunnelExhaustControlObjectInstance.class, services.children.get(0));
        assertEquals(16, services.children.size(),
                "subtype $1F skips Obj_AutomaticTunnelDelayed but still creates the exhaust and fragments");
    }

    @Test
    void subtype1fSetsWaterSpeedWhenSuperSonicKnuxFlagIsActive() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0x1F);
        TestablePlayableSprite player = player("knuckles", PIPE_X + 0x20, PIPE_Y);
        player.setAnimationId(2);
        player.setPushing(true);
        player.setXSpeed((short) 0x0480);
        player.setSuperSonic(true);

        plug.onSolidContact(player, new SolidContact(false, true, false, false, true), 12);

        assertTrue(services.waterSystem.waterSpeedSet);
        assertEquals(2, services.waterSystem.waterStateSpeedForTesting());
    }

    @Test
    void p1BreakClearsP2PushingAndRestoresP2VelocityWhenP2WasAlsoPushingRoll() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0);
        TestablePlayableSprite p1 = player("sonic", PIPE_X + 0x20, PIPE_Y);
        TestablePlayableSprite p2 = player("tails", PIPE_X - 0x20, PIPE_Y);
        p1.setAnimationId(2);
        p1.setPushing(true);
        p1.setXSpeed((short) 0);
        p2.setAnimationId(2);
        p2.setPushing(true);
        p2.setXSpeed((short) 0);
        p2.setGSpeed((short) 0);
        services.setCheckpointBatch(batch(plug, Map.of(
                p1, pushingResult(0x0480, 0, 2),
                p2, pushingResult(-0x0480, 0, 2))));

        plug.update(12, p1);

        assertFalse(p1.getPushing());
        assertFalse(p2.getPushing());
        assertEquals(0xFB80, p2.getXSpeed() & 0xFFFF,
                "P1 trigger path restores P2 x_vel from the pre-SolidObjectFull snapshot");
        assertEquals(0xFB80, p2.getGSpeed() & 0xFFFF);
    }

    @Test
    void smashTurnsParentIntoSinglePieceLargeChunkAndCreatesTwelveSmallShardsPlusThreeLargeChildren() {
        RecordingServices services = new RecordingServices();
        LbzPipePlugObjectInstance plug = createPlug(services, 0);
        TestablePlayableSprite player = player("sonic", PIPE_X + 0x20, PIPE_Y);
        player.setAnimationId(2);
        player.setPushing(true);
        player.setXSpeed((short) 0x0480);

        plug.onSolidContact(player, new SolidContact(false, true, false, false, true), 12);

        assertEquals(15, services.children.size());
        assertEquals(12, services.children.stream()
                .filter(child -> mappingFrame(child) != 7)
                .count());
        assertEquals(3, services.children.stream()
                .filter(child -> mappingFrame(child) == 7)
                .count());
        assertEquals(0, framePieceIndex(plug),
                "ROM reuses the original object with mappings(a0) pointing at frame 7 piece 0");
        assertEquals(List.of(1, 2, 3), services.children.stream()
                .filter(child -> mappingFrame(child) == 7)
                .map(TestLbzPipePlugObjectInstance::framePieceIndex)
                .toList(),
                "ROM advances the mappings pointer by one 6-byte piece for each large child");
        assertEquals(0xFC00, motionVelocity(plug, "xVel") & 0xFFFF,
                "Original object is reused as the first large chunk and consumes word_276D8 velocity pair 13");
        assertEquals(0xFD40, motionVelocity(plug, "yVel") & 0xFFFF);
        assertEquals(7, plug.mappingFrameForTesting());
    }

    @Test
    void lbz2PlanIncludesPipePlugLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_LBZ, 1);

        Sonic3kPlcArtRegistry.LevelArtEntry pipePlug = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_PIPE_PLUG))
                .findFirst().orElse(null);

        assertNotNull(pipePlug, "Obj_LBZPipePlug uses resident LBZ2 misc art");
        assertEquals(Sonic3kConstants.MAP_LBZ_PIPE_PLUG_ADDR, pipePlug.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ2_MISC - 4, pipePlug.artTileBase());
        assertEquals(2, pipePlug.palette());
    }

    private static LbzPipePlugObjectInstance createPlug(RecordingServices services, int subtype) {
        LbzPipePlugObjectInstance plug = new LbzPipePlugObjectInstance(spawnWithSubtype(subtype));
        plug.setServices(services);
        return plug;
    }

    private static ObjectSpawn spawnWithSubtype(int subtype) {
        return new ObjectSpawn(PIPE_X, PIPE_Y, Sonic3kObjectIds.LBZ_PIPE_PLUG, subtype, 0, false, 0);
    }

    private static TestablePlayableSprite player(String code, int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite(code, (short) x, (short) y);
        player.setCentreX((short) x);
        player.setCentreY((short) y);
        return player;
    }

    private static Sonic3kObjectRegistry registryForZone(int zoneId) {
        return new Sonic3kObjectRegistry() {
            @Override
            protected int currentRomZoneId() {
                return zoneId;
            }
        };
    }

    private static SolidCheckpointBatch batch(ObjectInstance object,
            Map<? extends PlayableEntity, PlayerSolidContactResult> results) {
        IdentityHashMap<PlayableEntity, PlayerSolidContactResult> copy = new IdentityHashMap<>();
        copy.putAll(results);
        return new SolidCheckpointBatch(object, copy);
    }

    private static PlayerSolidContactResult pushingResult(int preXSpeed, int postXSpeed, int preAnimationId) {
        return new PlayerSolidContactResult(
                ContactKind.SIDE,
                false,
                false,
                true,
                false,
                new PreContactState((short) preXSpeed, (short) 0, false, preAnimationId),
                new PostContactState((short) postXSpeed, (short) 0, false, false, true),
                0);
    }

    private static int mappingFrame(AbstractObjectInstance instance) {
        try {
            Field field = instance.getClass().getDeclaredField("mappingFrame");
            field.setAccessible(true);
            return field.getInt(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int motionVelocity(Object instance, String fieldName) {
        try {
            Field motionField = instance.getClass().getDeclaredField("motion");
            motionField.setAccessible(true);
            Object motion = motionField.get(instance);
            Field velocityField = motion.getClass().getDeclaredField(fieldName);
            velocityField.setAccessible(true);
            return velocityField.getInt(motion);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int framePieceIndex(Object instance) {
        try {
            Field field = instance.getClass().getDeclaredField("framePieceIndex");
            field.setAccessible(true);
            return field.getInt(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingServices extends TestObjectServices {
        private final ObjectManager objectManager;
        private final List<AbstractObjectInstance> children = new ArrayList<>();
        private final List<Integer> playedSfx = new ArrayList<>();
        private final RecordingWaterSystem waterSystem = new RecordingWaterSystem();
        private SolidCheckpointBatch checkpointBatch;

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            when(objectManager.solidContacts()).thenReturn(
                    mock(com.openggf.level.objects.ObjectSolidContactController.class));
            doAnswer(invocation -> {
                children.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any(AbstractObjectInstance.class));
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }

        @Override
        public WaterSystem waterSystem() {
            return waterSystem;
        }

        @Override
        public ObjectSolidExecutionContext solidExecution() {
            SolidCheckpointBatch current = checkpointBatch == null
                    ? new SolidCheckpointBatch(null, Map.of())
                    : checkpointBatch;
            return new ObjectSolidExecutionContext(new FixedSolidExecutionRegistry(current),
                    current.object(), () -> current);
        }

        void setCheckpointBatch(SolidCheckpointBatch checkpointBatch) {
            this.checkpointBatch = checkpointBatch;
        }

    }

    private static final class FixedSolidExecutionRegistry implements SolidExecutionRegistry {
        private final SolidCheckpointBatch batch;

        private FixedSolidExecutionRegistry(SolidCheckpointBatch batch) {
            this.batch = batch;
        }

        @Override public void beginFrame(int frameCounter, List<? extends PlayableEntity> players) {}
        @Override public void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver) {}
        @Override public ObjectSolidExecutionContext currentObject() {
            return new ObjectSolidExecutionContext(this, batch.object(), () -> batch);
        }
        @Override public PlayerStandingState previousStanding(ObjectInstance object, PlayableEntity player) {
            return PlayerStandingState.NONE;
        }
        @Override public void publishCheckpoint(SolidCheckpointBatch batch) {}
        @Override public void endObject(ObjectInstance object) {}
        @Override public void finishFrame() {}
        @Override public void clearTransientState() {}
    }

    private static final class RecordingWaterSystem extends WaterSystem {
        private final Lbz2KnucklesDynamicWaterHandler lbz2WaterHandler =
                new Lbz2KnucklesDynamicWaterHandler(new ThresholdTableWaterHandler(List.of()));
        private final DynamicWaterState waterState = new DynamicWaterState(0x0700);
        private boolean waterSpeedSet;

        @Override
        public void setWaterLevelTarget(int zoneId, int actId, int targetY) {
            assertEquals(Sonic3kZoneIds.ZONE_LBZ, zoneId);
            assertEquals(1, actId);
            waterState.setTarget(targetY);
        }

        @Override
        public void setWaterSpeed(int zoneId, int actId, int speed) {
            assertEquals(Sonic3kZoneIds.ZONE_LBZ, zoneId);
            assertEquals(1, actId);
            waterSpeedSet = true;
            waterState.setSpeed(speed);
        }

        @Override
        public com.openggf.game.DynamicWaterHandler getDynamicWaterHandler(int zoneId, int actId) {
            assertEquals(Sonic3kZoneIds.ZONE_LBZ, zoneId);
            assertEquals(1, actId);
            return lbz2WaterHandler;
        }

        int waterStateSpeedForTesting() {
            try {
                Field field = WaterSystem.DynamicWaterState.class.getDeclaredField("speed");
                field.setAccessible(true);
                return field.getInt(waterState);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
