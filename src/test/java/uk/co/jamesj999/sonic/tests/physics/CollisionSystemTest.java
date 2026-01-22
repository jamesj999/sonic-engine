package uk.co.jamesj999.sonic.tests.physics;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.physics.*;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for CollisionSystem behavior and trace recording.
 * These tests verify collision pipeline ordering and edge cases
 * to ensure consolidation doesn't change behavior.
 */
public class CollisionSystemTest {

    private CollisionSystem collisionSystem;
    private RecordingCollisionTrace trace;

    @Before
    public void setUp() {
        CollisionSystem.resetInstance();
        collisionSystem = CollisionSystem.getInstance();
        trace = new RecordingCollisionTrace();
        collisionSystem.setTrace(trace);
    }

    @Test
    public void testTraceRecordsTerrainProbeEvents() {
        SensorResult[] results = collisionSystem.terrainProbes(null, new Sensor[0], "ground");

        List<CollisionEvent> events = trace.getEvents();
        assertNotNull(events);
    }

    @Test
    public void testNoOpTraceIsDefault() {
        CollisionSystem.resetInstance();
        CollisionSystem fresh = CollisionSystem.getInstance();

        assertNotNull(fresh.getTrace());
        assertEquals(NoOpCollisionTrace.INSTANCE, fresh.getTrace());
    }

    @Test
    public void testUnifiedPipelineDisabledByDefault() {
        assertFalse(collisionSystem.isUnifiedPipelineEnabled());
    }

    @Test
    public void testShadowModeDisabledByDefault() {
        assertFalse(collisionSystem.isShadowModeEnabled());
    }

    @Test
    public void testPipelineCanBeEnabled() {
        collisionSystem.setUnifiedPipelineEnabled(true);
        assertTrue(collisionSystem.isUnifiedPipelineEnabled());
    }

    @Test
    public void testShadowModeCanBeEnabled() {
        collisionSystem.setShadowModeEnabled(true);
        assertTrue(collisionSystem.isShadowModeEnabled());
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
        assertTrue("Identical traces should have no differences", differences.isEmpty());
    }

    @Test
    public void testRecordingTraceComparison_different() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, false);
        other.onTerrainProbesStart(150, 200, false); // Different X

        List<String> differences = trace.compareWith(other);
        assertFalse("Different traces should have differences", differences.isEmpty());
    }

    @Test
    public void testRecordingTraceComparison_toleratesSmallDifferences() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, false);
        other.onTerrainProbesStart(101, 200, false); // 1 pixel diff

        List<String> differences = trace.compareWith(other);
        assertTrue("1-pixel difference should be tolerated", differences.isEmpty());
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
        assertFalse("Angle mismatch should be reported", differences.isEmpty());
    }

    @Test
    public void testCompareWithFlagMismatch() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        trace.onTerrainProbesStart(100, 200, true);
        other.onTerrainProbesStart(100, 200, false);

        List<String> differences = trace.compareWith(other);
        assertFalse("Flag mismatch should be reported", differences.isEmpty());
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
        assertFalse(collisionSystem.isRidingObject());
    }

    @Test
    public void testClearRidingObject_noObjectManager() {
        collisionSystem.clearRidingObject();
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
        assertTrue("1-pixel distance difference should be tolerated", differences.isEmpty());
    }

    @Test
    public void testDistanceExceedsTolerance() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();

        SensorResult result1 = new SensorResult((byte) 0, (byte) 10, 1, Direction.DOWN);
        SensorResult result2 = new SensorResult((byte) 0, (byte) 15, 1, Direction.DOWN);

        trace.onTerrainProbeResult("ground_A", result1);
        other.onTerrainProbeResult("ground_A", result2);

        List<String> differences = trace.compareWith(other);
        assertFalse("5-pixel distance difference should not be tolerated", differences.isEmpty());
    }

    @Test
    public void testEventTypeEnumValues() {
        CollisionEvent.EventType[] types = CollisionEvent.EventType.values();
        assertEquals(8, types.length);
        assertNotNull(CollisionEvent.EventType.valueOf("TERRAIN_PROBES_START"));
        assertNotNull(CollisionEvent.EventType.valueOf("TERRAIN_PROBE_RESULT"));
        assertNotNull(CollisionEvent.EventType.valueOf("TERRAIN_PROBES_COMPLETE"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_CONTACTS_START"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_CANDIDATE"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_RESOLVED"));
        assertNotNull(CollisionEvent.EventType.valueOf("SOLID_CONTACTS_COMPLETE"));
        assertNotNull(CollisionEvent.EventType.valueOf("POST_ADJUSTMENT"));
    }

    @Test
    public void testEmptyTraceComparison() {
        RecordingCollisionTrace other = new RecordingCollisionTrace();
        List<String> differences = trace.compareWith(other);
        assertTrue("Two empty traces should have no differences", differences.isEmpty());
    }

    @Test
    public void testSingletonReset() {
        CollisionSystem first = CollisionSystem.getInstance();
        CollisionSystem.resetInstance();
        CollisionSystem second = CollisionSystem.getInstance();
        assertNotSame("After reset, new instance should be created", first, second);
    }

    @Test
    public void testSingletonSameInstance() {
        CollisionSystem first = CollisionSystem.getInstance();
        CollisionSystem second = CollisionSystem.getInstance();
        assertSame("Without reset, same instance should be returned", first, second);
    }
}
