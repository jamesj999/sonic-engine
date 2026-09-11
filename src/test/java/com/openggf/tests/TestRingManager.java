package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.game.rules.GameRules;
import com.openggf.game.ShieldType;
import com.openggf.game.rewind.snapshot.RingSnapshot;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PlayerCapabilityRules;
import com.openggf.game.rules.RingRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingFrame;
import com.openggf.level.rings.RingFramePiece;
import com.openggf.level.rings.LostRingObjectInstance;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.physics.Sensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tests.rules.SonicGame;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TestRingManager {
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
    public void testRingCollectionAndSparkleLifecycle() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);
        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);

        ringManager.update(0, player, 0);

        assertTrue(ringManager.isCollected(spawn));
        assertEquals(1, player.getRingCount());
        assertEquals(0, ringManager.getSparkleStartFrame(spawn));
        assertTrue(ringManager.isRenderable(spawn, 1));

        assertFalse(ringManager.isRenderable(spawn, 2));
        assertFalse(ringManager.isCollectedAndSparkleDone(spawn, 2),
                "Ani_Ring's afRoutine has not reached Ring_Delete until the next object update");
        assertTrue(ringManager.isCollectedAndSparkleDone(spawn, 3));
    }

    @Test
    public void testCollectedRingsPersistOffscreen() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);
        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);

        ringManager.update(0, player, 0);

        assertTrue(ringManager.isCollected(spawn));

        ringManager.update(10000, player, 1);
        assertTrue(ringManager.isCollected(spawn));
        assertEquals(1, player.getRingCount());

        ringManager.update(0, player, 2);
        assertTrue(ringManager.isCollected(spawn));
        assertEquals(1, player.getRingCount());
    }

    @Test
    public void testS1RingCollectionUsesTouchWindowInsteadOfSpriteBounds() {
        RingSpawn spawn = new RingSpawn(0x0098, 0x0248);
        RingManager ringManager = buildRingManagerWithSpinPiece(List.of(spawn),
                new RingFramePiece(-16, -16, 4, 4, 0, false, false, 0));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 0x0087, (short) 0x025B);
        player.setRolling(false);

        ringManager.update(0, player, 0);

        assertFalse(ringManager.isCollected(spawn),
                "Ring should not collect at the MZ1 frame-71 trace position");
        assertEquals(0, player.getRingCount());

        player.setCentreX((short) 0x008B);
        player.setCentreY((short) 0x024F);

        ringManager.update(0, player, 1);

        assertTrue(ringManager.isCollected(spawn),
                "Ring should collect once the ROM touch window overlaps");
        assertEquals(1, player.getRingCount());
    }

    @Test
    public void testS3kNormalStageRingUsesSixPixelTouchHalfSize() {
        RingSpawn spawn = new RingSpawn(116, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);
        player.useGameRules(GameRules.SONIC_3K);

        ringManager.collectStageRings(player, 0);

        assertFalse(ringManager.isCollected(spawn),
                "S3K Test_Ring_Collisions normal path uses d1=6/d6=$C, not the attracted-ring 8px object radius");
        assertEquals(0, player.getRingCount());

        player.setCentreX((short) 102);

        ringManager.collectStageRings(player, 1);

        assertTrue(ringManager.isCollected(spawn));
        assertEquals(1, player.getRingCount());
    }

    @Test
    public void testDuplicatePlacedRingsAtSameCoordinateCollectIndependently() {
        RingSpawn first = new RingSpawn(100, 100);
        RingSpawn second = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(first, second));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);

        ringManager.collectStageRings(player, 0);

        assertTrue(ringManager.isCollected(first));
        assertTrue(ringManager.isCollected(second));
        assertEquals(2, player.getRingCount());
    }

    @Test
    public void testS3kStageRingSweepSkipsDuringHighPostHitInvulnerability() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);
        player.useGameRules(GameRules.SONIC_3K);
        player.setInvulnerableFrames(90);

        ringManager.collectStageRings(player, 0);

        assertFalse(ringManager.isCollected(spawn),
                "S3K Test_Ring_Collisions returns while invulnerability_timer >= 90");
        assertEquals(0, player.getRingCount());

        player.setInvulnerableFrames(89);
        ringManager.collectStageRings(player, 1);

        assertTrue(ringManager.isCollected(spawn));
        assertEquals(1, player.getRingCount());
    }

    @Test
    public void testCanonicalCoordinateLookupKeepsFirstDuplicateAcrossReplacement() {
        RingSpawn first = new RingSpawn(100, 100);
        RingSpawn second = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(first, second));

        assertSame(first, ringManager.resolveCanonicalSpawn(100, 100));

        RingSpawn replacement = new RingSpawn(100, 100);
        ringManager.resyncSpawnList(List.of(replacement));

        assertSame(replacement, ringManager.resolveCanonicalSpawn(100, 100));
        assertNull(ringManager.resolveCanonicalSpawn(200, 200));
    }

    @Test
    public void testCanonicalCoordinateIndexResolvesDistinctKeysAcrossNearCapacityCollisionChain() {
        List<RingSpawn> collisions = new ArrayList<>();
        int targetBucket = -1;
        for (int x = 1; collisions.size() < 4; x++) {
            int y = x * 17;
            int bucket = coordinateHash(x, y) & 7;
            if (targetBucket < 0) targetBucket = bucket;
            if (bucket == targetBucket) collisions.add(new RingSpawn(x, y));
        }
        RingManager ringManager = buildRingManager(collisions);

        for (RingSpawn spawn : collisions) {
            assertSame(spawn, ringManager.resolveCanonicalSpawn(spawn.x(), spawn.y()));
        }
        assertNull(ringManager.resolveCanonicalSpawn(0x7FFF, 0x7FFE));
    }

    private static int coordinateHash(int x, int y) {
        long key = ((long) x << 32) ^ (y & 0xFFFF_FFFFL);
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdl;
        key ^= key >>> 33;
        return (int) key;
    }

    @Test
    public void testStageRingCollectionUsesTypedRingRules() {
        RingSpawn spawn = new RingSpawn(120, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TypedOnlyRulesSprite player = new TypedOnlyRulesSprite((short) 100, (short) 100,
                customRingCollisionRules(12));

        ringManager.collectStageRings(player, 0);

        assertTrue(ringManager.isCollected(spawn),
                "Ring geometry should come from typed ring rules");
        assertEquals(1, player.getRingCount());
    }

    @Test
    public void testLightningAttractionUsesTypedRingRules() {
        RingSpawn spawn = new RingSpawn(175, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TypedOnlyRulesSprite player = new TypedOnlyRulesSprite((short) 100, (short) 100,
                customRingCollisionRules(12));
        player.giveShield(ShieldType.LIGHTNING);

        ringManager.update(0, player, 0);

        assertTrue(ringManager.isCollected(spawn),
                "Lightning attraction geometry should come from typed ring rules");
    }

    @Test
    public void testS3kRawRingWindowExcludesRecordAtUpperPointer() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
        RingSpawn spawn = new RingSpawn(0x0248, 0x0100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0x0100);

        TestPlayableSprite player = new TestPlayableSprite((short) 0x0208, (short) 0x0100);
        player.useGameRules(GameRules.SONIC_3K);
        player.giveShield(ShieldType.LIGHTNING);

        ringManager.update(0x0100, player, 0);

        assertFalse(ringManager.isCollected(spawn),
                "Ring_end_addr_ROM is exclusive when a record equals camera_x-$8+$150");

        ringManager.update(0x0101, player, 1);

        assertTrue(ringManager.isCollected(spawn),
                "Advancing the camera one pixel should bring the boundary record into the native window");
    }

    @Test
    public void testLightningAttractionAllocatesOneRingPerPlayerTouchPass() {
        RingSpawn first = new RingSpawn(140, 100);
        RingSpawn second = new RingSpawn(164, 100);
        RingManager ringManager = buildRingManager(List.of(first, second));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);
        player.useGameRules(GameRules.SONIC_3K);
        player.giveShield(ShieldType.LIGHTNING);

        ringManager.update(0, player, 0);

        assertTrue(ringManager.isCollected(first));
        assertFalse(ringManager.isCollected(second),
                "Test_Ring_Collisions returns after allocating one Obj_Attracted_Ring");
        assertEquals(1, ringManager.capture().attractedRings().length);

        ringManager.update(0, player, 1);

        assertTrue(ringManager.isCollected(second));
        assertEquals(2, ringManager.capture().attractedRings().length);
    }

    @Test
    public void testLightningAttractionUsesTypedCapabilityRulesWithoutLegacyGameRules() {
        RingSpawn spawn = new RingSpawn(169, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TypedOnlyRulesSprite player = new TypedOnlyRulesSprite((short) 100, (short) 100,
                gameRulesWithLightningShieldCapability(true));
        player.giveShield(ShieldType.LIGHTNING);

        ringManager.update(0, player, 0);

        assertTrue(ringManager.isCollected(spawn),
                "Typed player capability rules should enable lightning attraction without a legacy game rules");
    }

    @Test
    public void testLightningAttractionDisabledByTypedCapabilityRulesWithoutLegacyGameRules() {
        RingSpawn spawn = new RingSpawn(169, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TypedOnlyRulesSprite player = new TypedOnlyRulesSprite((short) 100, (short) 100,
                gameRulesWithLightningShieldCapability(false));
        player.giveShield(ShieldType.LIGHTNING);

        ringManager.update(0, player, 0);

        assertFalse(ringManager.isCollected(spawn),
                "Typed player capability rules should disable lightning attraction without a legacy game rules");
    }

    @Test
    public void testS1ObjectTouchPlacedRingSkipsDuringHighPostHitInvulnerability() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);
        player.useGameRules(GameRules.SONIC_1);
        player.setInvulnerableFrames(90);

        assertFalse(ringManager.collectPlacedRing(spawn, player, 0),
                "S1 ReactToItem .preventRingCollect returns while flashtime >= 90");
        assertFalse(ringManager.isCollected(spawn));
        assertEquals(0, player.getRingCount());

        player.setInvulnerableFrames(89);

        assertTrue(ringManager.collectPlacedRing(spawn, player, 1),
                "S1 Obj25 should collect once flashtime drops below 90");
        assertTrue(ringManager.isCollected(spawn));
        assertEquals(1, player.getRingCount());
        assertEquals(2, ringManager.getSparkleStartFrame(spawn),
                "S1 Obj25 Ring_Sparkle starts on the next object execution after ReactToItem collection");
        assertFalse(ringManager.isCollectedAndSparkleDone(spawn, 4),
                "Ring_Delete has not run while the deferred Obj25 sparkle cadence is still one frame short");
        assertTrue(ringManager.isCollectedAndSparkleDone(spawn, 5),
                "Ring_Delete frees the Obj25 slot after Ani_Ring's afRoutine frame has displayed");
    }

    @Test
    public void testCpuSidekickObjectControlledRecoveryCannotCollectStageRings() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TestPlayableSprite tails = new TestPlayableSprite((short) 100, (short) 100);
        tails.setCpuControlled(true);
        tails.setObjectControlled(true);
        tails.setControlLocked(true);

        ringManager.collectStageRings(tails, 0);

        assertFalse(ringManager.isCollected(spawn),
                "CPU Tails recovery flight keeps object_control set and must not collect stage rings");
        assertEquals(0, tails.getRingCount());
    }

    @Test
    public void testNativeBit7ObjectControlSuppressesStageRingCollection() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);
        ObjectControlState.nativeBit7FullControl().applyTo(player);

        ringManager.collectStageRings(player, 0);

        assertFalse(ringManager.isCollected(spawn),
                "ROM skips TouchResponse/Test_Ring_Collisions while object_control bit 7 suppresses touch");
        assertEquals(0, player.getRingCount());

        ObjectControlState.none().applyTo(player);
        ringManager.collectStageRings(player, 1);

        assertTrue(ringManager.isCollected(spawn));
        assertEquals(1, player.getRingCount());
    }

    @Test
    public void testNativeLowBitObjectControlAllowsStageRingCollection() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);
        ObjectControlState.nativeBits0To6CpuAllowedMovementActive().applyTo(player);

        ringManager.collectStageRings(player, 0);

        assertTrue(ringManager.isCollected(spawn),
                "ROM object_control bits 0-6 do not suppress Test_Ring_Collisions");
        assertEquals(1, player.getRingCount());
    }

    @Test
    public void testLateRingManagerUpdateCanSkipStageRingCollection() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRingManager(List.of(spawn));
        ringManager.reset(0);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);

        ringManager.update(0, player, 0, false);

        assertFalse(ringManager.isCollected(spawn),
                "LevelManager's late post-object update must not run a second placed-ring sweep");
        assertEquals(0, player.getRingCount());

        ringManager.update(0, player, 1, true);

        assertTrue(ringManager.isCollected(spawn));
        assertEquals(1, player.getRingCount());
    }

    // NOTE: legacy per-ring lost-ring lifecycle (collected-ring sparkle expiry and
    // on-expiry slot release) was retired with the legacy LostRingPool.updatePhysics
    // loop — per-ring physics/lifetime now runs in the object exec loop
    // (LostRingObjectInstance). Per-ring round-trip is covered by
    // com.openggf.level.rings.TestLostRingRewindGenericRestore; the object-loop expiry/slot
    // release lands with the Stage-5 object physics relocation. updateLostRingPhysics
    // now only advances the shared decelerating spin (Ring_spill_anim_*).

    @Test
    public void testLostRingSpawnReservesDynamicSlots() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(List.of(), levelManager);
        setField(levelManager, "ringManager", ringManager);

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);

        ringManager.spawnLostRings(player, 3, 0);

        assertEquals(3, objectManager.getAllocatedSlotCount(),
                "Spilled lost rings should reserve real dynamic slots while active");
    }

    @Test
    public void testS3kLightningAttractedRingReservesSlotThroughSparkle() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(List.of(), levelManager);
        setField(levelManager, "ringManager", ringManager);
        RingSnapshot base = ringManager.capture();
        int reservedSlot = objectManager.allocateDynamicSlot();
        objectManager.releaseDynamicSlot(reservedSlot);
        ringManager.restore(new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 0, 100, 100, 0, 0, 0, 0,
                                0, reservedSlot, false, -1)
                }));

        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);
        player.useGameRules(GameRules.SONIC_3K);
        player.giveShield(ShieldType.LIGHTNING);

        ringManager.prepareAttractedRingTouchSnapshot();
        ringManager.collectAttractedRing(player, 0);
        ringManager.update(0, player, 0);

        assertEquals(1, player.getRingCount());
        assertEquals(1, objectManager.getAllocatedSlotCount(),
                "S3K Obj_Attracted_Ring keeps its SST slot for loc_1A920 sparkle after collection");

        ringManager.update(0, player, 1);
        assertEquals(1, objectManager.getAllocatedSlotCount(),
                "Attracted-ring sparkle should keep occupying the dynamic slot before Ani_RingSparkle finishes");

        ringManager.update(0, player, 2);
        assertEquals(1, objectManager.getAllocatedSlotCount(),
                "The second sparkle mapping keeps the attracted-ring slot occupied");

        ringManager.update(0, player, 3);
        assertEquals(1, objectManager.getAllocatedSlotCount(),
                "Animate_Sprite retains each mapping for delay+1 object passes");

        ringManager.update(0, player, 4);
        assertEquals(1, objectManager.getAllocatedSlotCount(),
                "$FC only advances the attracted ring to its delete routine");

        ringManager.update(0, player, 5);
        assertEquals(0, objectManager.getAllocatedSlotCount(),
                "The object pass after $FC releases the attracted-ring slot");
    }

    @Test
    public void testAttractedRingRestoreRereservesObjectSlot() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(List.of(), levelManager);
        setField(levelManager, "ringManager", ringManager);
        RingSnapshot base = ringManager.capture();

        int reservedSlot = objectManager.allocateDynamicSlot();
        objectManager.releaseDynamicSlot(reservedSlot);

        RingSnapshot snapshot = new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 0, 0x200, 0x180, 0, 0, 0, 0,
                                0, reservedSlot, false, -1)
                });

        ringManager.restore(snapshot);

        assertEquals(1, objectManager.getAllocatedSlotCount(),
                "Restoring an active Obj_Attracted_Ring snapshot must also restore its SST slot reservation");
        assertFalse(reservedSlot == objectManager.allocateDynamicSlot(),
                "The restored attracted-ring slot must not be reallocated to the next dynamic object");
    }

    @Test
    public void testS3kAttractedRingBecomesBouncingRingWhenLightningShieldIsLost() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);

        RingManager ringManager = buildRingManagerWithLevelManager(List.of(), levelManager);
        setField(levelManager, "ringManager", ringManager);
        RingSnapshot base = ringManager.capture();
        int reservedSlot = objectManager.allocateDynamicSlot();
        objectManager.releaseDynamicSlot(reservedSlot);
        ringManager.restore(new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                7,
                0x1234,
                2,
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 0, 100, 100, 0x1200, 0x3400, 0, 0,
                                0, reservedSlot, false, -1)
                }));

        TestPlayableSprite player = new TestPlayableSprite((short) 120, (short) 100);
        player.useGameRules(GameRules.SONIC_3K);
        ringManager.update(0, player, 10);

        assertEquals(0, ringManager.capture().attractedRings().length,
                "loc_1A88C stops running Obj_Attracted_Ring after the shield is gone");
        List<LostRingObjectInstance> bouncingRings =
                objectManager.activeObjectsOfType(LostRingObjectInstance.class);
        assertEquals(1, bouncingRings.size());
        LostRingObjectInstance bouncingRing = bouncingRings.getFirst();
        assertEquals(reservedSlot, bouncingRing.getSlotIndex(),
                "The ROM changes the existing SST code pointer instead of allocating another slot");
        assertEquals(0x30, bouncingRing.getXVelForTest(),
                "AttractedRing_Move runs before the shield-loss conversion");
        assertEquals((100 << 8) | 0x42, bouncingRing.getXSubpixelForTest(),
                "The conversion must preserve the attracted ring's fixed-point position");
        assertEquals(0xFF, ringManager.getSpillAnimationState().counter());
        assertEquals(0x1234, ringManager.getSpillAnimationState().accum(),
                "The ROM restarts only Ring_spill_anim_counter");
        assertEquals(2, ringManager.getSpillAnimationState().frame());
    }

    @Test
    public void testS3kAttractedRingUsesTouchResponseBoundsForCollection() {
        RingManager ringManager = buildRingManager(List.of());
        RingSnapshot base = ringManager.capture();
        RingSnapshot snapshot = new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 0, 0x0A4F, 0x0C5D, 0, 0, 0, 0,
                                0, -1, false, -1)
                });
        ringManager.restore(snapshot);

        TestPlayableSprite player = new TestPlayableSprite((short) 0x0A4A, (short) 0x0C4B);
        player.useGameRules(GameRules.SONIC_3K);
        player.giveShield(ShieldType.LIGHTNING);
        player.setRolling(true);

        ringManager.prepareAttractedRingTouchSnapshot();
        ringManager.collectAttractedRing(player, 2388);
        ringManager.update(0x0A00, player, 2388);

        assertEquals(0, player.getRingCount(),
                "S3K collision_flags $47 uses TouchResponse bounds; this frame-2388 geometry is near but not overlapping");
        assertTrue(ringManager.capture().attractedRings()[0].active(),
                "The attracted ring should remain active until the ROM touch box overlaps");
    }

    @Test
    public void testS3kAttractedRingCanBeCollectedBySidekick() {
        RingManager ringManager = buildRingManager(List.of());
        RingSnapshot base = ringManager.capture();
        ringManager.restore(new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 0, 0x0200, 0x0200, 0, 0, 0, 0,
                                0, -1, false, -1)
                }));

        TestPlayableSprite main = new TestPlayableSprite((short) 0x0100, (short) 0x0100);
        TestPlayableSprite sidekick = new TestPlayableSprite((short) 0x0200, (short) 0x0200);
        sidekick.setCpuControlled(true);
        GameServices.sprites().addSprite(sidekick);

        ringManager.prepareAttractedRingTouchSnapshot();
        ringManager.collectAttractedRing(main, 0);
        ringManager.collectAttractedRing(sidekick, 0);
        ringManager.update(0, main, 0);

        assertEquals(1, sidekick.getRingCount(),
                "S3K collision-response passes let P2 collect an attracted ring");
        assertTrue(ringManager.capture().attractedRings()[0].collected());
    }

    @Test
    public void testS3kAttractedRingKeepsUnsignedDirectionAfterPositionWordWrap() {
        RingManager ringManager = buildRingManager(List.of());
        RingSnapshot base = ringManager.capture();
        ringManager.restore(new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 0, 0x0200, 0x0001, 0, 0, 0, 0xFE00,
                                0, -1, false, -1)
                }));

        TestPlayableSprite player = new TestPlayableSprite((short) 0x0200, (short) 0x0100);
        player.useGameRules(GameRules.SONIC_3K);
        player.giveShield(ShieldType.LIGHTNING);

        ringManager.update(0, player, 0);
        RingSnapshot.AttractedRingEntry wrapped = ringManager.capture().attractedRings()[0];
        assertEquals(0xFFFF, wrapped.y() & 0xFFFF,
                "MoveSprite2 stores the wrapped high position as a native word");

        ringManager.update(0, player, 1);
        RingSnapshot.AttractedRingEntry continued = ringManager.capture().attractedRings()[0];
        assertEquals(0xFE90, continued.yVel() & 0xFFFF,
                "unsigned bhs treats wrapped $FFFF as beyond player $0100 and keeps accelerating upward");
    }

    @Test
    public void testS3kAttractedRingTargetsPlayerTouchPhaseBeforeLaterObjectCarry() {
        RingManager ringManager = buildRingManager(List.of());
        RingSnapshot base = ringManager.capture();
        ringManager.restore(new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 0, 0x0100, 0x0100, 0, 0, 0, 0,
                                0, -1, false, -1)
                }));

        TestPlayableSprite player = new TestPlayableSprite((short) 0x0080, (short) 0x0100);
        player.useGameRules(GameRules.SONIC_3K);
        player.giveShield(ShieldType.LIGHTNING);

        ringManager.prepareAttractedRingTouchSnapshot();
        ringManager.attractStageRings(player);
        player.setCentreX((short) 0x0180); // Simulate a later-slot platform carry.
        ringManager.update(0, player, 0, false);

        RingSnapshot.AttractedRingEntry moved = ringManager.capture().attractedRings()[0];
        assertEquals(0xFF40, moved.xVel() & 0xFFFF,
                "Obj_Attracted_Ring must target Player 1's player-slot X before later object slots carry him");
        assertEquals(0x00FF, moved.x() & 0xFFFF);
    }

    @Test
    public void testAttractedRingTargetPhaseFollowsNativeSstSlotOrder() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        setField(levelManager, "objectManager", objectManager);
        RingManager ringManager = buildRingManagerWithLevelManager(List.of(), levelManager);
        RingSnapshot base = ringManager.capture();
        ringManager.restore(new RingSnapshot(
                base.collected(),
                base.sparkleTimers(),
                base.placementCursorIndex(),
                base.placementLastCameraX(),
                base.lostRingActiveCount(),
                base.spillAnimCounter(),
                base.spillAnimAccum(),
                base.spillAnimFrame(),
                base.lostRingFrameCounter(),
                base.lostRings(),
                new RingSnapshot.AttractedRingEntry[] {
                        new RingSnapshot.AttractedRingEntry(
                                true, 0, 0x0100, 0x0100, 0, 0, 0, 0,
                                0, 39, false, -1),
                        new RingSnapshot.AttractedRingEntry(
                                true, 1, 0x0100, 0x0120, 0, 0, 0, 0,
                                1, 42, false, -1)
                }));
        assertEquals(2, ringManager.capture().attractedRings().length);

        Field xField = ObjectManager.class.getDeclaredField("playerCentreXAtSlotStart");
        Field yField = ObjectManager.class.getDeclaredField("playerCentreYAtSlotStart");
        Field validField = ObjectManager.class.getDeclaredField("playerCentreAtSlotStartValid");
        xField.setAccessible(true);
        yField.setAccessible(true);
        validField.setAccessible(true);
        int[] xBySlot = (int[]) xField.get(objectManager);
        int[] yBySlot = (int[]) yField.get(objectManager);
        boolean[] validBySlot = (boolean[]) validField.get(objectManager);
        xBySlot[7] = 0x0080;
        yBySlot[7] = 0x0100;
        validBySlot[7] = true;
        xBySlot[10] = 0x0180;
        yBySlot[10] = 0x0120;
        validBySlot[10] = true;

        TestPlayableSprite player = new TestPlayableSprite((short) 0x0180, (short) 0x0120);
        player.useGameRules(GameRules.SONIC_3K);
        player.giveShield(ShieldType.LIGHTNING);
        ringManager.update(0, player, 0, false);

        RingSnapshot.AttractedRingEntry[] moved = ringManager.capture().attractedRings();
        assertEquals(0xFF40, moved[0].xVel() & 0xFFFF,
                "slot 7 must target Player 1 before a later carrier moves him");
        assertEquals(0x0030, moved[1].xVel() & 0xFFFF,
                "slot 10 must target Player 1 after the earlier carrier moves him");
    }

    private RingManager buildRingManager(List<RingSpawn> spawns) {
        return buildRingManagerWithSpinPiece(spawns, new RingFramePiece(0, 0, 1, 1, 0, false, false, 0));
    }

    private RingManager buildRingManagerWithLevelManager(List<RingSpawn> spawns, LevelManager levelManager) {
        return buildRingManagerWithLevelManagerAndSpinPiece(spawns, levelManager,
                new RingFramePiece(0, 0, 1, 1, 0, false, false, 0));
    }

    private RingManager buildRingManagerWithSpinPiece(List<RingSpawn> spawns, RingFramePiece piece) {
        return buildRingManagerWithLevelManagerAndSpinPiece(spawns, null, piece);
    }

    private RingManager buildRingManagerWithLevelManagerAndSpinPiece(List<RingSpawn> spawns,
                                                                     LevelManager levelManager,
                                                                     RingFramePiece piece) {
        Pattern pattern = new Pattern();
        pattern.setPixel(0, 0, (byte) 1);

        RingFrame frame = new RingFrame(List.of(piece));
        List<RingFrame> frames = new ArrayList<>();
        frames.add(frame);
        frames.add(frame);
        frames.add(frame);

        Pattern[] patterns = new Pattern[16];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = pattern;
        }

        RingSpriteSheet spriteSheet = new RingSpriteSheet(patterns, frames, 1, 1, 1, 2);
        RingManager ringManager = new RingManager(
                spawns,
                spriteSheet,
                levelManager,
                null,
                GameServices.audio());
        ringManager.ensurePatternsCached(GraphicsManager.getInstance(), 0);
        return ringManager;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private GameRules customRingCollisionRules(int ringCollisionHalfSize) {
        GameRules source = GameRules.SONIC_3K;
        RingRules ringRules = source.ring();
        RingRules customRingRules = new RingRules(
                ringRules.ringFloorCheckMask(),
                ringRules.ringFloorCheckCounterPhase(),
                ringRules.ringFloorProbeRequiresRenderFlag(),
                ringRules.lostRingBoundaryChecksOnlyOnProbeCadence(),
                ringRules.lostRingRenderYMargin(),
                ringCollisionHalfSize,
                ringCollisionHalfSize,
                ringRules.stageRingsUseObjectTouchCollection(),
                ringRules.stageRingSweepUsesRawCameraWindow());
        return new GameRules(
                source.playerMovement(),
                source.playerCapability(),
                source.collision(),
                source.playerAnimation(),
                source.camera(),
                customRingRules,
                source.objectInteraction(),
                source.sidekickCpu(),
                source.powerUp(),
                source.drowningBubble());
    }

    private GameRules gameRulesWithLightningShieldCapability(boolean enabled) {
        GameRules base = GameRules.SONIC_3K;
        PlayerCapabilityRules capability = base.playerCapability();
        PlayerCapabilityRules playerCapability = new PlayerCapabilityRules(
                capability.spindashEnabled(),
                capability.spindashSpeedTable(),
                capability.elementalShieldsEnabled(),
                capability.instaShieldEnabled(),
                capability.tailsFlightEnabled(),
                capability.jumpRepressClearsRollJumpBeforeAbility(),
                enabled,
                capability.superSpindashSpeedTable());
        return new GameRules(
                base.playerMovement(),
                playerCapability,
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
    }

    private static final class NoOpObjectRegistry implements ObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "noop";
        }
    }

    private static class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite(short x, short y) {
            super("TEST", x, y);
            setWidth(16);
            setHeight(32);
            setCentreX(x);
            setCentreY(y);
        }

        private void useGameRules(GameRules featureSet) {
            super.setGameRulesForTest(featureSet);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 0;
            runHeight = 0;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        private int ringCount = 0;

        @Override
        public void addRings(int delta) {
            ringCount += delta;
        }

        @Override
        public int getRingCount() {
            return ringCount;
        }

        @Override
        public void setRingCount(int ringCount) {
            this.ringCount = ringCount;
        }

        @Override
        public void draw() {

        }
    }

    private static final class TypedOnlyRulesSprite extends TestPlayableSprite {
        private final GameRules gameRules;

        private TypedOnlyRulesSprite(short x, short y, GameRules gameRules) {
            super(x, y);
            this.gameRules = gameRules;
        }

        @Override
        public GameRules getGameRules() {
            return gameRules;
        }
    }
}


