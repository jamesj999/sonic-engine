package com.openggf.tests.physics;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.rules.GameRules;
import com.openggf.level.LevelManager;
import com.openggf.game.GroundMode;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rules.GameRules;
import com.openggf.physics.*;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.graphics.GLCommand;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import org.mockito.Mockito;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CollisionSystem behavior and trace recording.
 * These tests verify collision pipeline ordering and edge cases
 * to ensure consolidation doesn't change behavior.
 */
public class CollisionSystemTest {

    private CollisionSystem collisionSystem;
    private RecordingCollisionTrace trace;

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
        GameServices.collision().resetState();
        collisionSystem = GameServices.collision();
        trace = new RecordingCollisionTrace();
        collisionSystem.setTrace(trace);
    }

    @Test
    public void testTraceRecordsTerrainProbeEvents() {
        AbstractPlayableSprite player = Mockito.mock(AbstractPlayableSprite.class);
        // Drive a real probe with two live sensors. terrainProbes records one
        // TERRAIN_PROBE_RESULT per pooled slot (named "<type>_<index>") when the
        // trace is active. The pooled buffer holds MAX_SENSORS (6) slots, so the
        // two live sensors are followed by null-result slots.
        Sensor sensorA = new FixedSensor(player, Direction.DOWN, 5);
        Sensor sensorB = new FixedSensor(player, Direction.DOWN, 8);

        SensorResult[] results = collisionSystem.terrainProbes(
                player, new Sensor[] { sensorA, sensorB }, "ground");

        assertEquals(5, results[0].distance());
        assertEquals(8, results[1].distance());

        List<CollisionEvent> events = trace.getEvents();
        assertEquals(results.length, events.size(),
                "terrainProbes must record one probe-result event per pooled slot");
        // The first two events carry the live sensor results.
        assertEquals(CollisionEvent.EventType.TERRAIN_PROBE_RESULT, events.get(0).type());
        assertEquals("ground_0", events.get(0).description());
        assertEquals(5, events.get(0).distance());
        assertTrue(events.get(0).flag1(), "live sensor result must mark a hit");
        assertEquals(CollisionEvent.EventType.TERRAIN_PROBE_RESULT, events.get(1).type());
        assertEquals("ground_1", events.get(1).description());
        assertEquals(8, events.get(1).distance());
        // Remaining pooled slots are empty -> null-result probe events.
        assertEquals("ground_2", events.get(2).description());
        assertEquals(Integer.MAX_VALUE, events.get(2).distance());
        assertFalse(events.get(2).flag1(), "empty slot result must not mark a hit");
    }

    @Test
    public void testNoOpTraceIsDefault() {
        GameServices.collision().resetState();
        CollisionSystem fresh = GameServices.collision();

        assertNotNull(fresh.getTrace());
        assertEquals(NoOpCollisionTrace.INSTANCE, fresh.getTrace());
    }

    @Test
    public void airborneCollisionResetsStaleWallModeBeforeWorldSpaceProbes() {
        AbstractPlayableSprite player = Mockito.mock(AbstractPlayableSprite.class);
        Mockito.when(player.getAir()).thenReturn(true);
        Mockito.when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        Mockito.when(player.getGroundMode()).thenReturn(GroundMode.LEFTWALL);
        Mockito.when(player.getXSpeed()).thenReturn((short) 0x0350);
        Mockito.when(player.getYSpeed()).thenReturn((short) 0x0172);
        Sensor[] sensors = new Sensor[] {
                new FixedSensor(player, Direction.DOWN, 5),
                new FixedSensor(player, Direction.DOWN, 5)
        };
        Mockito.when(player.getGroundSensors()).thenReturn(sensors);
        Mockito.when(player.getCeilingSensors()).thenReturn(sensors);
        Mockito.when(player.getPushSensors()).thenReturn(sensors);

        collisionSystem.resolveAirCollision(player, ignored -> { });

        Mockito.verify(player).setGroundMode(GroundMode.GROUND);
    }

    @Test
    public void testHasStandingContactDelegatesToLatestSnapshot() {
        TrackingObjectManager objectManager = new TrackingObjectManager(true, 12);
        collisionSystem.setObjectManager(objectManager);

        AbstractPlayableSprite player = Mockito.mock(AbstractPlayableSprite.class);

        assertTrue(collisionSystem.hasStandingContact(player));
        assertEquals(1, objectManager.latestStandingCalls);
        assertEquals(0, objectManager.fallbackStandingCalls);
    }

    @Test
    public void testHasStandingContactIgnoresUpwardMotionBeforeDelegation() {
        TrackingObjectManager objectManager = new TrackingObjectManager(true, 12);
        collisionSystem.setObjectManager(objectManager);

        AbstractPlayableSprite player = Mockito.mock(AbstractPlayableSprite.class);
        Mockito.when(player.getYSpeed()).thenReturn((short) -1);

        assertFalse(collisionSystem.hasStandingContact(player));
        assertEquals(0, objectManager.latestStandingCalls);
        assertEquals(0, objectManager.fallbackStandingCalls);
    }

    @Test
    public void testClearRidingObjectInvalidatesStandingSnapshot() {
        TrackingObjectManager objectManager = new TrackingObjectManager(true, 12);
        collisionSystem.setObjectManager(objectManager);

        AbstractPlayableSprite player = Mockito.mock(AbstractPlayableSprite.class);

        assertTrue(collisionSystem.hasStandingContact(player));
        collisionSystem.clearRidingObject(player);
        assertFalse(collisionSystem.hasStandingContact(player));
        assertEquals(2, objectManager.latestStandingCalls);
        assertEquals(1, objectManager.clearRidingCalls);
    }

    @Test
    public void testGetHeadroomDistanceDelegatesToLatestSnapshot() {
        TrackingObjectManager objectManager = new TrackingObjectManager(true, 12);
        collisionSystem.setObjectManager(objectManager);

        AbstractPlayableSprite player = Mockito.mock(AbstractPlayableSprite.class);

        assertEquals(12, collisionSystem.getHeadroomDistance(player, 0x40));
        assertEquals(1, objectManager.latestHeadroomCalls);
        assertEquals(0, objectManager.fallbackHeadroomCalls);
    }

    @Test
    public void testJumpHeadroomUsesTerrainOnly() {
        TrackingObjectManager objectManager = new TrackingObjectManager(true, 0);
        collisionSystem.setObjectManager(objectManager);

        AbstractPlayableSprite player = Mockito.mock(AbstractPlayableSprite.class);
        Sensor clearCeiling = new FixedSensor(player, Direction.UP, 10);
        Mockito.when(player.getCeilingSensors()).thenReturn(new Sensor[]{clearCeiling, clearCeiling});
        Mockito.when(player.getPushSensors()).thenReturn(new Sensor[]{clearCeiling, clearCeiling});

        assertTrue(collisionSystem.hasEnoughHeadroom(player, 0x00),
                "Sonic_Jump/Tails_Jump gate on CalcRoomOverHead terrain distance only "
                        + "(S3K sonic3k.asm:23300-23307,28531-28538)");
        assertEquals(0, objectManager.latestHeadroomCalls,
                "Object headroom snapshots must not participate in the ROM jump gate");
    }

    @Test
    public void testCalcRoomOverHeadCeilingProbeDoesNotPreApplyRomNibbleFlip() {
        // ROM Sonic_FindCeiling / Sonic_CheckCeiling applies eori.w #$F to the
        // top-edge probe Y exactly ONCE before FindFloor scans upward. The engine's
        // Direction.UP terrain path (GroundSensor.scanVertical -> verticalTileLookupY
        // for wrapped rows, and the UP branch of calculateVerticalDistance which is
        // already the post-flip `origY - (checkBase + metric)` form) owns that single
        // flip. The CalcRoomOverHead ceiling probe therefore must NOT also pre-apply
        // the flip as a probe `dy` offset, or it gets DOUBLE-applied. This was the
        // S1 SYZ2 f1088 jump-block bug: with a probe-level dy=-15 the ceiling distance
        // for tile 0x0093 col 6 came out as 3 (< the 6px Sonic_Jump gate), but the ROM
        // (BizHawk Sonic_FindCeiling hook 0x156CE/0x156D2 at SYZ2 bk2 f1088: obX=0x074F,
        // obW=9, left probe col 6) returns 8 — exactly what the UP path computes with
        // dy=0. So the probe passes the plain top-edge Y (dy=0) and lets scanVertical
        // own the flip. The flip itself is verified at its real home in
        // testGroundSensorVerticalLookupAppliesSingleCeilingNibbleFlip below.
        AbstractPlayableSprite player = Mockito.mock(AbstractPlayableSprite.class);
        Mockito.when(player.getCentreX()).thenReturn((short) 0x0893);
        Mockito.when(player.getCentreY()).thenReturn((short) 0x07A2);
        Mockito.when(player.getXRadius()).thenReturn((short) 9);
        Mockito.when(player.getYRadius()).thenReturn((short) 19);
        Mockito.when(player.getLrbSolidBit()).thenReturn((byte) 0x0E);

        Object[] probes = describeCalcRoomOverHeadProbes(player, 0x80);

        assertEquals(2, probes.length);
        assertEquals(Direction.UP, readProbeDirection(probes[0], "globalDirection"));
        assertEquals(9, readProbeInt(probes[0], "worldOffsetX"));
        assertEquals(-19, readProbeInt(probes[0], "worldOffsetY"));
        assertEquals(0, readProbeInt(probes[0], "dx"));
        assertEquals(0, readProbeInt(probes[0], "dy"),
                "Ceiling probe must not pre-apply the eori.w #$F flip; scanVertical owns it");
        assertEquals(0x0E, readProbeInt(probes[0], "solidityBit"));

        assertEquals(Direction.UP, readProbeDirection(probes[1], "globalDirection"));
        assertEquals(-9, readProbeInt(probes[1], "worldOffsetX"));
        assertEquals(-19, readProbeInt(probes[1], "worldOffsetY"));
        assertEquals(0, readProbeInt(probes[1], "dy"),
                "Ceiling probe must not pre-apply the eori.w #$F flip; scanVertical owns it");
    }

    @Test
    public void testGroundSensorVerticalLookupAppliesSingleCeilingNibbleFlip() {
        // The eori.w #$F ceiling flip is REAL ROM behavior (Sonic_CheckCeiling /
        // Sonic_FindCeiling). It is not deleted by the SYZ2 fix, only relocated to the
        // single place that owns it: GroundSensor.verticalTileLookupY, which transforms a
        // wrapped (negative) UP probe row with `(y ^ 0x0F) & 0x07FF`. DOWN is untouched.
        // y = -1 (top-edge probe that wrapped above the level) -> (0xFFFF ^ 0x0F) & 0x7FF
        // = 0x07F0; the single flip is applied here so the ceiling probe upstream stays
        // dy=0 (testCalcRoomOverHeadCeilingProbeDoesNotPreApplyRomNibbleFlip).
        assertEquals(0x07F0, invokeVerticalTileLookupY((short) -1, Direction.UP),
                "UP wrapped row gets the single eori.w #$F flip (Sonic_CheckCeiling)");
        assertEquals(0x07FF, invokeVerticalTileLookupY((short) -16, Direction.UP),
                "eori.w #$F flips only the low nibble of the wrapped UP row");
        assertEquals(-1, invokeVerticalTileLookupY((short) -1, Direction.DOWN),
                "DOWN floor probe must not receive the ceiling flip");
        assertEquals(0x0575, invokeVerticalTileLookupY((short) 0x0575, Direction.UP),
                "Non-wrapped positive UP rows are untouched here; the flip is folded into "
                        + "the UP calculateVerticalDistance form, never double-applied");
    }

    @Test
    public void testNullSpriteHandledGracefully() {
        collisionSystem.step(null, new Sensor[0], new Sensor[0]);
        assertTrue(trace.getEvents().isEmpty());
    }

    @Test
    public void testTraceCanBeCleared() {
        trace.onTerrainProbesStart(100, 200, false);
        assertFalse(trace.getEvents().isEmpty());

        trace.clear();
        assertTrue(trace.getEvents().isEmpty());
    }

    @Test
    public void testRecordingTraceComparison_identical() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, false);
        other.onTerrainProbesStart(100, 200, false);

        trace.onTerrainProbesComplete(100, 200, (byte) 0);
        other.onTerrainProbesComplete(100, 200, (byte) 0);

        List<String> differences = trace.compareWith(other);
        assertTrue(differences.isEmpty(), "Identical traces should have no differences");
    }

    @Test
    public void testRecordingTraceComparison_different() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, false);
        other.onTerrainProbesStart(150, 200, false); // Different X

        List<String> differences = trace.compareWith(other);
        assertFalse(differences.isEmpty(), "Different traces should have differences");
    }

    @Test
    public void testRecordingTraceComparison_toleratesSmallDifferences() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, false);
        other.onTerrainProbesStart(101, 200, false); // 1 pixel diff

        List<String> differences = trace.compareWith(other);
        assertTrue(differences.isEmpty(), "1-pixel difference should be tolerated");
    }

    @Test
    public void testCollisionEventFactoryMethods() {
        CollisionEvent simple = CollisionEvent.simple(CollisionEvent.EventType.TERRAIN_PROBES_START, "test");
        assertEquals(CollisionEvent.EventType.TERRAIN_PROBES_START, simple.type());
        assertEquals("test", simple.description());

        CollisionEvent position = CollisionEvent.position(CollisionEvent.EventType.SOLID_CONTACTS_START, "pos", 50, 100);
        assertEquals(50, position.x());
        assertEquals(100, position.y());
    }

    @Test
    public void testCollisionEventSensorFactory_nullResult() {
        CollisionEvent event = CollisionEvent.sensor("ground_A", null);
        assertEquals(CollisionEvent.EventType.TERRAIN_PROBE_RESULT, event.type());
        assertEquals(Integer.MAX_VALUE, event.distance());
        assertFalse(event.flag1()); // hit flag
    }

    @Test
    public void testCollisionEventSensorFactory_withResult() {
        SensorResult result = new SensorResult((byte) 45, (byte) 10, 123, Direction.DOWN);
        CollisionEvent event = CollisionEvent.sensor("ground_B", result);

        assertEquals(CollisionEvent.EventType.TERRAIN_PROBE_RESULT, event.type());
        assertEquals("ground_B", event.description());
        assertEquals(10, event.distance());
        assertEquals((byte) 45, event.angle());
        assertTrue(event.flag1()); // hit flag true for valid result
    }

    @Test
    public void testSolidContactEventsRecorded() {
        trace.onSolidContactsStart(100, 200);
        trace.onSolidCandidate("Platform", 120, 220, true);
        trace.onSolidContactsComplete(true, 100, 218);

        List<CollisionEvent> events = trace.getEvents();
        assertEquals(3, events.size());
        assertEquals(CollisionEvent.EventType.SOLID_CONTACTS_START, events.get(0).type());
        assertEquals(CollisionEvent.EventType.SOLID_CANDIDATE, events.get(1).type());
        assertEquals(CollisionEvent.EventType.SOLID_CONTACTS_COMPLETE, events.get(2).type());
    }

    @Test
    public void testSolidCandidateEventDetails() {
        trace.onSolidCandidate("MovingPlatform", 200, 300, false);

        List<CollisionEvent> events = trace.getEvents();
        assertEquals(1, events.size());
        CollisionEvent event = events.get(0);
        assertEquals("MovingPlatform", event.description());
        assertEquals(200, event.x());
        assertEquals(300, event.y());
        assertFalse(event.flag1()); // contacted = false
    }

    @Test
    public void testSolidResolvedEventRecorded() {
        trace.onSolidResolved(null, true, false);

        List<CollisionEvent> events = trace.getEvents();
        assertEquals(1, events.size());
        CollisionEvent event = events.get(0);
        assertEquals(CollisionEvent.EventType.SOLID_RESOLVED, event.type());
        assertTrue(event.flag1());  // standing
        assertFalse(event.flag2()); // pushing
    }

    @Test
    public void testPostAdjustmentEventsRecorded() {
        trace.onPostAdjustment("headroom_check", 100, 95);

        List<CollisionEvent> events = trace.getEvents();
        assertEquals(1, events.size());
        CollisionEvent event = events.get(0);
        assertEquals(CollisionEvent.EventType.POST_ADJUSTMENT, event.type());
        assertEquals("headroom_check", event.description());
        assertEquals(100, event.x()); // beforeValue
        assertEquals(95, event.y());  // afterValue
        assertEquals(-5, event.distance()); // delta
    }

    @Test
    public void testPostAdjustmentPositiveDelta() {
        trace.onPostAdjustment("ground_snap", 50, 60);

        CollisionEvent event = trace.getEvents().get(0);
        assertEquals(10, event.distance()); // delta = 60 - 50
    }

    @Test
    public void testTerrainProbesCompleteRecordsAngle() {
        trace.onTerrainProbesComplete(150, 250, (byte) 0x40);

        List<CollisionEvent> events = trace.getEvents();
        assertEquals(1, events.size());
        CollisionEvent event = events.get(0);
        assertEquals(CollisionEvent.EventType.TERRAIN_PROBES_COMPLETE, event.type());
        assertEquals((byte) 0x40, event.angle());
    }

    @Test
    public void testTerrainProbesStartRecordsInAirFlag() {
        trace.onTerrainProbesStart(100, 200, true);

        CollisionEvent event = trace.getEvents().get(0);
        assertTrue(event.flag1()); // inAir = true
    }

    @Test
    public void testSolidContactsCompleteRecordsRidingFlag() {
        trace.onSolidContactsComplete(true, 100, 200);

        CollisionEvent event = trace.getEvents().get(0);
        assertTrue(event.flag1()); // ridingObject = true
    }

    @Test
    public void testCompareWithEventCountMismatch_extraEvents() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, false);
        trace.onTerrainProbesComplete(100, 200, (byte) 0);
        other.onTerrainProbesStart(100, 200, false);

        List<String> differences = trace.compareWith(other);
        assertEquals(1, differences.size());
        assertTrue(differences.get(0).contains("Extra event"));
    }

    @Test
    public void testCompareWithEventCountMismatch_missingEvents() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, false);
        other.onTerrainProbesStart(100, 200, false);
        other.onTerrainProbesComplete(100, 200, (byte) 0);

        List<String> differences = trace.compareWith(other);
        assertEquals(1, differences.size());
        assertTrue(differences.get(0).contains("Missing event"));
    }

    @Test
    public void testCompareWithAngleMismatch() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesComplete(100, 200, (byte) 0x00);
        other.onTerrainProbesComplete(100, 200, (byte) 0x40);

        List<String> differences = trace.compareWith(other);
        assertFalse(differences.isEmpty(), "Angle mismatch should be reported");
    }

    @Test
    public void testCompareWithFlagMismatch() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, true);
        other.onTerrainProbesStart(100, 200, false);

        List<String> differences = trace.compareWith(other);
        assertFalse(differences.isEmpty(), "Flag mismatch should be reported");
    }

    @Test
    public void testNoOpTraceGetEventsReturnsEmpty() {
        List<CollisionEvent> events = NoOpCollisionTrace.INSTANCE.getEvents();
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    public void testNoOpTraceClearDoesNotThrow() {
        NoOpCollisionTrace.INSTANCE.clear();
        assertTrue(NoOpCollisionTrace.INSTANCE.getEvents().isEmpty(),
                "no-op trace must record nothing, even after clear()");
    }

    @Test
    public void testNoOpTraceMethodsDoNotThrow() {
        NoOpCollisionTrace noop = NoOpCollisionTrace.INSTANCE;
        noop.onTerrainProbesStart(0, 0, false);
        noop.onTerrainProbeResult("test", null);
        noop.onTerrainProbesComplete(0, 0, (byte) 0);
        noop.onSolidContactsStart(0, 0);
        noop.onSolidCandidate("test", 0, 0, false);
        noop.onSolidResolved(null, false, false);
        noop.onSolidContactsComplete(false, 0, 0);
        noop.onPostAdjustment("test", 0, 0);
        assertTrue(noop.getEvents().isEmpty(),
                "no-op trace must drop every recorded event");
    }

    @Test
    public void testSetTraceNullDefaultsToNoOp() {
        collisionSystem.setTrace(null);
        assertEquals(NoOpCollisionTrace.INSTANCE, collisionSystem.getTrace());
    }

    @Test
    public void testHasStandingContact_noObjectManager() {
        assertFalse(collisionSystem.hasStandingContact(null));
    }

    @Test
    public void testGetHeadroomDistance_noObjectManager() {
        assertEquals(Integer.MAX_VALUE, collisionSystem.getHeadroomDistance(null, 0));
    }

    @Test
    public void testIsRidingObject_noObjectManager() {
        assertFalse(collisionSystem.isRidingObject(null));
    }

    @Test
    public void testClearRidingObject_noObjectManager() {
        // Null-safety contract: clearing with no object manager is a safe no-op
        // and leaves the riding query reporting no riding object.
        assertDoesNotThrow(() -> collisionSystem.clearRidingObject(null));
        assertFalse(collisionSystem.isRidingObject(null));
    }

    @Test
    public void testMultipleSensorResultsRecorded() {
        SensorResult result1 = new SensorResult((byte) 0, (byte) 5, 1, Direction.DOWN);
        SensorResult result2 = new SensorResult((byte) 10, (byte) 8, 2, Direction.DOWN);

        trace.onTerrainProbeResult("ground_A", result1);
        trace.onTerrainProbeResult("ground_B", result2);

        List<CollisionEvent> events = trace.getEvents();
        assertEquals(2, events.size());
        assertEquals("ground_A", events.get(0).description());
        assertEquals("ground_B", events.get(1).description());
        assertEquals(5, events.get(0).distance());
        assertEquals(8, events.get(1).distance());
    }

    @Test
    public void testEventSequencePreserved() {
        trace.onTerrainProbesStart(100, 200, false);
        trace.onTerrainProbeResult("ground_A", null);
        trace.onTerrainProbesComplete(100, 200, (byte) 0);
        trace.onSolidContactsStart(100, 200);
        trace.onSolidContactsComplete(false, 100, 200);
        trace.onPostAdjustment("headroom", 100, 100);

        List<CollisionEvent> events = trace.getEvents();
        assertEquals(6, events.size());
        assertEquals(CollisionEvent.EventType.TERRAIN_PROBES_START, events.get(0).type());
        assertEquals(CollisionEvent.EventType.TERRAIN_PROBE_RESULT, events.get(1).type());
        assertEquals(CollisionEvent.EventType.TERRAIN_PROBES_COMPLETE, events.get(2).type());
        assertEquals(CollisionEvent.EventType.SOLID_CONTACTS_START, events.get(3).type());
        assertEquals(CollisionEvent.EventType.SOLID_CONTACTS_COMPLETE, events.get(4).type());
        assertEquals(CollisionEvent.EventType.POST_ADJUSTMENT, events.get(5).type());
    }

    @Test
    public void testCollisionEventRecordAccessors() {
        CollisionEvent event = new CollisionEvent(
            CollisionEvent.EventType.SOLID_CANDIDATE,
            "Platform",
            100, 200, 15, (byte) 0x20, true, false
        );

        assertEquals(CollisionEvent.EventType.SOLID_CANDIDATE, event.type());
        assertEquals("Platform", event.description());
        assertEquals(100, event.x());
        assertEquals(200, event.y());
        assertEquals(15, event.distance());
        assertEquals((byte) 0x20, event.angle());
        assertTrue(event.flag1());
        assertFalse(event.flag2());
    }

    @Test
    public void testCollisionEventToString() {
        CollisionEvent event = CollisionEvent.simple(CollisionEvent.EventType.TERRAIN_PROBES_START, "test");
        String str = event.toString();
        assertNotNull(str);
        assertTrue(str.contains("TERRAIN_PROBES_START"));
    }

    @Test
    public void testDistanceToleranceInComparison() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        SensorResult result1 = new SensorResult((byte) 0, (byte) 10, 1, Direction.DOWN);
        SensorResult result2 = new SensorResult((byte) 0, (byte) 11, 1, Direction.DOWN);

        trace.onTerrainProbeResult("ground_A", result1);
        other.onTerrainProbeResult("ground_A", result2);

        List<String> differences = trace.compareWith(other);
        assertTrue(differences.isEmpty(), "1-pixel distance difference should be tolerated");
    }

    @Test
    public void testDistanceExceedsTolerance() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        SensorResult result1 = new SensorResult((byte) 0, (byte) 10, 1, Direction.DOWN);
        SensorResult result2 = new SensorResult((byte) 0, (byte) 15, 1, Direction.DOWN);

        trace.onTerrainProbeResult("ground_A", result1);
        other.onTerrainProbeResult("ground_A", result2);

        List<String> differences = trace.compareWith(other);
        assertFalse(differences.isEmpty(), "5-pixel distance difference should not be tolerated");
    }

    @Test
    public void testEventTypeEnumValues() {
        CollisionEvent.EventType[] types = CollisionEvent.EventType.values();
        assertEquals(10, types.length);
        assertNotNull(CollisionEvent.EventType.valueOf("TERRAIN_PROBES_START"));
        assertNotNull(CollisionEvent.EventType.valueOf("TERRAIN_PROBE_RESULT"));
        assertNotNull(CollisionEvent.EventType.valueOf("TERRAIN_PROBES_COMPLETE"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_CONTACTS_START"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_CANDIDATE"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_RESOLVED"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_CHECKPOINT_START"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_CHECKPOINT_RESULT"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_CONTACTS_COMPLETE"));
        assertNotNull(CollisionEvent.EventType.valueOf("POST_ADJUSTMENT"));
    }

    @Test
    public void testEmptyTraceComparison() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();
        List<String> differences = trace.compareWith(other);
        assertTrue(differences.isEmpty(), "Two empty traces should have no differences");
    }

    @Test
    public void testResetStateDoesNotChangeInstance() {
        CollisionSystem first = GameServices.collision();
        first.resetState();
        CollisionSystem second = GameServices.collision();
        assertSame(first, second, "resetState() should not change instance");
    }

    @Test
    public void testSameInstanceReturned() {
        CollisionSystem first = GameServices.collision();
        CollisionSystem second = GameServices.collision();
        assertSame(first, second, "Without reset, same instance should be returned");
    }

    @Test
    public void testGroundSensorDefaultLevelManagerTracksRuntimeRecreation() {
        GroundSensor.setLevelManager(null);

        GameplayModeContext firstGameplayMode = TestEnvironment.activeGameplayMode();
        LevelManager firstLevelManager = invokeGroundSensorLevelManager();
        assertSame(firstGameplayMode.getLevelManager(), firstLevelManager, "GroundSensor should resolve the current gameplay LevelManager");

        SessionManager.clear();
        TestEnvironment.activeGameplayMode();

        try {
            GameplayModeContext secondGameplayMode = TestEnvironment.activeGameplayMode();
            LevelManager secondLevelManager = invokeGroundSensorLevelManager();

            assertSame(secondGameplayMode.getLevelManager(), secondLevelManager, "GroundSensor should resolve the recreated gameplay LevelManager");
            assertNotSame(firstLevelManager, secondLevelManager, "GroundSensor should not retain the destroyed gameplay LevelManager");
        } finally {
            GroundSensor.setLevelManager(null);
        }
    }

    @Test
    public void testCalcRoomInFrontProbeOnFloorMovingRightUsesRightWallProbe() {
        Object probe = describeCalcRoomInFrontProbe(0x00, (short) 0x400);

        assertEquals(0xC0, readProbeInt(probe, "mode"), "Expected right-wall mode dispatch");
        assertEquals(Direction.RIGHT, readProbeDirection(probe, "globalDirection"));
        assertEquals(10, readProbeInt(probe, "offsetX"));
        assertEquals(0, readProbeInt(probe, "offsetY"));
        assertEquals(8, readProbeInt(probe, "dynamicYOffset"));
    }

    @Test
    public void testCalcRoomInFrontProbeOnRightWallMovingUpUsesCeilingProbe() {
        Object probe = describeCalcRoomInFrontProbe(0xC0, (short) 0x400);

        assertEquals(0x80, readProbeInt(probe, "mode"), "Expected ceiling-mode dispatch from right wall");
        assertEquals(Direction.UP, readProbeDirection(probe, "globalDirection"));
        assertEquals(0, readProbeInt(probe, "offsetX"));
        assertEquals(-10, readProbeInt(probe, "offsetY"));
        assertEquals(0, readProbeInt(probe, "dynamicYOffset"));
    }

    @Test
    public void testCalcRoomInFrontProbeOnCeilingMovingLeftUsesRightWallProbe() {
        Object probe = describeCalcRoomInFrontProbe(0x80, (short) -0x400);

        assertEquals(0xC0, readProbeInt(probe, "mode"), "Expected right-wall mode dispatch from ceiling");
        assertEquals(Direction.RIGHT, readProbeDirection(probe, "globalDirection"));
        assertEquals(10, readProbeInt(probe, "offsetX"));
        assertEquals(0, readProbeInt(probe, "offsetY"));
        assertEquals(8, readProbeInt(probe, "dynamicYOffset"));
    }

    @Test
    public void testCalcRoomInFrontProbeOnLeftWallMovingDownUsesCeilingProbe() {
        Object probe = describeCalcRoomInFrontProbe(0x40, (short) -0x400);

        assertEquals(0x80, readProbeInt(probe, "mode"), "Expected ceiling-mode dispatch from left wall");
        assertEquals(Direction.UP, readProbeDirection(probe, "globalDirection"));
        assertEquals(0, readProbeInt(probe, "offsetX"));
        assertEquals(-10, readProbeInt(probe, "offsetY"));
        assertEquals(0, readProbeInt(probe, "dynamicYOffset"));
    }

    @Test
    public void oddRightWallZeroDistanceUsesCurrentCardinalFallbackInsteadOfStaleAlternate() {
        GameRulesCollisionTestSprite player = newCollisionTestSprite();
        player.setGameRules(GameRules.SONIC_3K);
        player.setGroundMode(GroundMode.RIGHTWALL);
        player.setAngle((byte) 0xC0);

        SensorResult selectedOddWall = new SensorResult((byte) 0xFF, (byte) 0, 0x72, Direction.RIGHT);
        SensorResult previousAlternate = new SensorResult((byte) 0xB4, (byte) 1, 0x8C, Direction.RIGHT);
        SensorResult currentAlternate = new SensorResult((byte) 0xCC, (byte) 1, 0x8D, Direction.RIGHT);

        invokeSelectSensorWithAngle(player, selectedOddWall, previousAlternate);
        assertEquals(0xC0, player.getAngle() & 0xFF,
                "First odd-angle frame should snap to the current right-wall cardinal angle");

        invokeSelectSensorWithAngle(player, selectedOddWall, currentAlternate);

        assertEquals(0xC0, player.getAngle() & 0xFF,
                "S3K Player_Angle has no cross-frame alternate-angle cache; a zero-distance odd right-wall "
                        + "sensor snaps from the current angle to the current cardinal quadrant");
    }

    @Test
    public void floorLipOddSensorUsesRomCardinalFallbackInsteadOfAlternateSlope() {
        AbstractPlayableSprite player = newCollisionTestSprite();
        player.setGroundMode(GroundMode.GROUND);
        player.setCentreX((short) 0x0100);
        player.setCentreY((short) 0x0400);

        SensorResult selectedOddFloor = new SensorResult((byte) 0xFF, (byte) 0, 0x10, Direction.DOWN);
        SensorResult alternateSlope = new SensorResult((byte) 0x08, (byte) 3, 0x11, Direction.DOWN);

        SensorResult result = invokeSelectSensorWithAngle(player, selectedOddFloor, alternateSlope);

        assertSame(selectedOddFloor, result,
                "Player_Angle keeps the lower-distance floor sensor even when its angle is odd");
        assertEquals(0x00, player.getAngle() & 0xFF,
                "Odd floor angles use the ROM cardinal fallback; the farther alternate slope must not be borrowed");
    }

    @Test
    public void typedCollisionRuleDisablesRightWallDeepProbePreservationForS3k() throws Exception {
        GameRulesCollisionTestSprite player = newCollisionTestSprite();
        player.setGameRules(GameRules.SONIC_1);
        GameRules base = GameRules.SONIC_1;
        setGameRulesForTest(player, new GameRules(
                base.playerMovement(),
                base.playerCapability(),
                GameRules.SONIC_3K.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble()));

        assertFalse(invokePreservesRightWallPenetrationOnDeepProbe(player),
                "S3K non-AIZ deep right-wall probes retain Player_Angle's selected angle without the AIZ timer");
    }

    @Test
    public void collisionRuleUsesDefaultWhenTypedCollisionGroupMissing() throws Exception {
        GameRulesCollisionTestSprite player = newCollisionTestSprite();
        player.setGameRules(GameRules.SONIC_3K);
        GameRules base = GameRules.SONIC_3K;
        setGameRulesForTest(player, new GameRules(
                base.playerMovement(),
                base.playerCapability(),
                null,
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble()));

        assertFalse(invokePreservesRightWallPenetrationOnDeepProbe(player),
                "A null CollisionRules group should not recreate removed feature-set collision rules");
    }

    private static Object describeCalcRoomInFrontProbe(int angle, short gSpeed) {
        try {
            Method method = CollisionSystem.class.getDeclaredMethod(
                    "describeCalcRoomInFrontProbe", int.class, short.class);
            method.setAccessible(true);
            return method.invoke(null, angle, gSpeed);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke describeCalcRoomInFrontProbe", e);
        }
    }

    private SensorResult invokeSelectSensorWithAngle(AbstractPlayableSprite player,
                                                     SensorResult rightSensor,
                                                     SensorResult leftSensor) {
        try {
            Method method = CollisionSystem.class.getDeclaredMethod(
                    "selectSensorWithAngle", AbstractPlayableSprite.class, SensorResult.class, SensorResult.class);
            method.setAccessible(true);
            return (SensorResult) method.invoke(collisionSystem, player, rightSensor, leftSensor);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke selectSensorWithAngle", e);
        }
    }

    private boolean invokePreservesRightWallPenetrationOnDeepProbe(AbstractPlayableSprite player) {
        try {
            Method method = CollisionSystem.class.getDeclaredMethod(
                    "preservesRightWallPenetrationOnDeepProbe", AbstractPlayableSprite.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(collisionSystem, player);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke preservesRightWallPenetrationOnDeepProbe", e);
        }
    }

    private static void setGameRulesForTest(AbstractPlayableSprite player, GameRules rules) throws Exception {
        Field field = AbstractPlayableSprite.class.getDeclaredField("gameRules");
        field.setAccessible(true);
        field.set(player, rules);
    }

    private static GameRulesCollisionTestSprite newCollisionTestSprite() {
        return new GameRulesCollisionTestSprite();
    }

    private static Object[] describeCalcRoomOverHeadProbes(AbstractPlayableSprite player, int quadrant) {
        try {
            Method method = CollisionSystem.class.getDeclaredMethod(
                    "describeCalcRoomOverHeadProbes", AbstractPlayableSprite.class, int.class);
            method.setAccessible(true);
            return (Object[]) method.invoke(null, player, quadrant);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke describeCalcRoomOverHeadProbes", e);
        }
    }

    private static LevelManager invokeGroundSensorLevelManager() {
        try {
            Method method = GroundSensor.class.getDeclaredMethod("getLevelManager");
            method.setAccessible(true);
            return (LevelManager) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke GroundSensor.getLevelManager", e);
        }
    }

    private static int invokeVerticalTileLookupY(short y, Direction direction) {
        try {
            Method method = GroundSensor.class.getDeclaredMethod(
                    "verticalTileLookupY", short.class, Direction.class, int.class);
            method.setAccessible(true);
            return ((Number) method.invoke(null, y, direction, 0x07FF)).intValue();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke GroundSensor.verticalTileLookupY", e);
        }
    }

    private static int readProbeInt(Object probe, String accessor) {
        try {
            Method method = probe.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return ((Number) method.invoke(probe)).intValue();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed reading probe accessor " + accessor, e);
        }
    }

    private static Direction readProbeDirection(Object probe, String accessor) {
        try {
            Method method = probe.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return (Direction) method.invoke(probe);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed reading probe accessor " + accessor, e);
        }
    }

    private static final class GameRulesCollisionTestSprite extends AbstractPlayableSprite {
        private GameRulesCollisionTestSprite() {
            super("collision-test", (short) 0, (short) 0);
        }

        private void setGameRules(GameRules rules) {
            super.setGameRulesForTest(rules);
        }

        @Override
        protected void defineSpeeds() {
        }

        @Override
        protected void createSensorLines() {
        }

        @Override
        public void draw() {
        }
    }

    private static final class TrackingObjectManager extends com.openggf.level.objects.ObjectManager {
        private final boolean standingSnapshot;
        private final int headroomSnapshot;
        private boolean standingSnapshotCleared;
        int latestStandingCalls;
        int latestHeadroomCalls;
        int fallbackStandingCalls;
        int fallbackHeadroomCalls;
        int clearRidingCalls;

        private TrackingObjectManager(boolean standingSnapshot, int headroomSnapshot) {
            super(List.of(), new ObjectRegistry() {
                @Override
                public ObjectInstance create(ObjectSpawn spawn) {
                    return null;
                }

                @Override
                public void reportCoverage(List<ObjectSpawn> spawns) {
                }

                @Override
                public String getPrimaryName(int objectId) {
                    return "Test";
                }
            }, 0, null, null);
            this.standingSnapshot = standingSnapshot;
            this.headroomSnapshot = headroomSnapshot;
        }

        public boolean latestStandingSnapshot(PlayableEntity player) {
            latestStandingCalls++;
            return standingSnapshot && !standingSnapshotCleared;
        }

        public int latestHeadroomSnapshot(PlayableEntity player, int hexAngle) {
            latestHeadroomCalls++;
            return headroomSnapshot;
        }

        @Override
        public void clearRidingObject(PlayableEntity player) {
            clearRidingCalls++;
            standingSnapshotCleared = true;
        }

        @Override
        public boolean hasStandingContact(PlayableEntity player) {
            fallbackStandingCalls++;
            return false;
        }

        @Override
        public int getHeadroomDistance(PlayableEntity player, int hexAngle) {
            fallbackHeadroomCalls++;
            return Integer.MAX_VALUE;
        }
    }

    private static final class FixedSensor extends Sensor {
        private final int distance;

        private FixedSensor(AbstractPlayableSprite sprite, Direction direction, int distance) {
            super(sprite, direction, (byte) 0, (byte) 0, true);
            this.distance = distance;
        }

        @Override
        protected SensorResult doScan(short dx, short dy) {
            return new SensorResult((byte) 0, (byte) distance, 0, direction);
        }
    }
}
