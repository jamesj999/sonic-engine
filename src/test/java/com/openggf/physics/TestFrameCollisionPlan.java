package com.openggf.physics;

import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFrameCollisionPlan {

    @Test
    void playableFrameNamesTerrainThenSolidsWithTraceRecording() {
        FrameCollisionPlan plan = FrameCollisionPlan.playableFrame();

        assertTrue(plan.runsTerrainProbes());
        assertTrue(plan.runsSolidObjectResolution());
        assertFalse(plan.runsPostResolutionGroundMode());
        assertTrue(plan.recordsTrace());
        assertEquals(List.of(
                        FrameCollisionPlan.Phase.TERRAIN_PROBES,
                        FrameCollisionPlan.Phase.SOLID_OBJECT_RESOLUTION),
                plan.orderedPhases());
    }

    @Test
    void terrainOnlyNamesOnlyTerrainProbes() {
        FrameCollisionPlan plan = FrameCollisionPlan.terrainOnly();

        assertTrue(plan.runsTerrainProbes());
        assertFalse(plan.runsSolidObjectResolution());
        assertFalse(plan.runsPostResolutionGroundMode());
        assertFalse(plan.recordsTrace());
        assertEquals(List.of(FrameCollisionPlan.Phase.TERRAIN_PROBES), plan.orderedPhases());
    }

    @Test
    void objectResolutionOnlyNamesOnlySolidObjectResolution() {
        FrameCollisionPlan plan = FrameCollisionPlan.objectResolutionOnly();

        assertFalse(plan.runsTerrainProbes());
        assertTrue(plan.runsSolidObjectResolution());
        assertFalse(plan.runsPostResolutionGroundMode());
        assertFalse(plan.recordsTrace());
        assertEquals(List.of(FrameCollisionPlan.Phase.SOLID_OBJECT_RESOLUTION), plan.orderedPhases());
    }

    @Test
    void objectResolutionPlanDelegatesToObjectManagerWithMovementFlags() {
        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        ObjectManager objectManager = Mockito.mock(ObjectManager.class);
        AbstractPlayableSprite sprite = newTestSprite();

        collisionSystem.setObjectManager(objectManager);
        collisionSystem.runSolidObjectResolution(
                FrameCollisionPlan.objectResolutionOnly(), sprite, true, true);

        Mockito.verify(objectManager).updateSolidContacts(sprite, true, true);
    }

    @Test
    void riddenDropOnFloorFlushWallFallbackRequiresRiderOnProbedSide() {
        ObjectInstance f3317Obj30 = Mockito.mock(ObjectInstance.class);
        Mockito.when(f3317Obj30.getX()).thenReturn(0x1760);
        ObjectInstance f4422Obj30 = Mockito.mock(ObjectInstance.class);
        Mockito.when(f4422Obj30.getX()).thenReturn(0x1920);

        assertTrue(CollisionSystem.isRiderOnGroundWallProbeSide(
                newTestSpriteAt(0x170A), f3317Obj30, 0x40),
                "HTZ2 f3317 has Tails left of Obj30 x=$1760 while CalcRoomInFront probes the left wall");
        assertFalse(CollisionSystem.isRiderOnGroundWallProbeSide(
                newTestSpriteAt(0x19BA), f4422Obj30, 0x40),
                "HTZ2 f4422 has Tails right of Obj30 x=$1920, and ROM returns d1=0 for the left-wall probe");
        assertTrue(CollisionSystem.isRiderOnGroundWallProbeSide(
                newTestSpriteAt(0x19BA), f4422Obj30, 0xC0),
                "A right-wall probe uses the mirrored leading side");
    }

    @Test
    void terrainPlayableApisExposePlanAwareOverloads() {
        assertPlanAwareCollisionMethod("resolveGroundWallCollision", AbstractPlayableSprite.class);
        assertPlanAwareCollisionMethod("resolveGroundAttachment",
                AbstractPlayableSprite.class, int.class, BooleanSupplier.class);
        assertPlanAwareCollisionMethod("resolveAirCollision",
                AbstractPlayableSprite.class, Consumer.class);
        assertPlanAwareCollisionMethod("resolveAirCollision",
                AbstractPlayableSprite.class, Consumer.class, boolean.class);
    }

    @Test
    void terrainPlayableApisRejectObjectResolutionOnlyPlans() {
        CollisionSystem collisionSystem = new CollisionSystem(new EmptyTerrainCollisionManager());
        AbstractPlayableSprite sprite = newTestSprite();

        assertThrows(IllegalArgumentException.class,
                () -> collisionSystem.resolveGroundWallCollision(FrameCollisionPlan.objectResolutionOnly(), sprite));
        assertThrows(IllegalArgumentException.class,
                () -> collisionSystem.resolveGroundAttachment(
                        FrameCollisionPlan.objectResolutionOnly(), sprite, 14, () -> false));
        assertThrows(IllegalArgumentException.class,
                () -> collisionSystem.resolveAirCollision(
                        FrameCollisionPlan.objectResolutionOnly(), sprite, ignored -> { }));
    }

    @Test
    void legacyStepDelegatesThroughPlayableFramePlan() {
        CollisionSystem collisionSystem = new CollisionSystem(new EmptyTerrainCollisionManager());
        RecordingCollisionTrace trace = new RecordingCollisionTrace();
        AbstractPlayableSprite sprite = newTestSprite();

        collisionSystem.setTrace(trace);
        collisionSystem.step(sprite, new Sensor[0], new Sensor[0]);

        assertEquals(List.of(
                        CollisionEvent.EventType.TERRAIN_PROBES_START,
                        CollisionEvent.EventType.TERRAIN_PROBES_COMPLETE,
                        CollisionEvent.EventType.SOLID_CONTACTS_START,
                        CollisionEvent.EventType.SOLID_CONTACTS_COMPLETE),
                trace.getEvents().stream().map(CollisionEvent::type).toList());
    }

    private static Method assertPlanAwareCollisionMethod(String name, Class<?>... trailingParameterTypes) {
        Class<?>[] parameterTypes = new Class<?>[trailingParameterTypes.length + 1];
        parameterTypes[0] = FrameCollisionPlan.class;
        System.arraycopy(trailingParameterTypes, 0, parameterTypes, 1, trailingParameterTypes.length);
        return assertDoesNotThrow(() -> CollisionSystem.class.getDeclaredMethod(name, parameterTypes));
    }

    private static AbstractPlayableSprite newTestSprite() {
        return newTestSpriteAt(0);
    }

    private static AbstractPlayableSprite newTestSpriteAt(int x) {
        return new AbstractPlayableSprite("sonic", (short) 0, (short) 0) {
            {
                setCentreX((short) x);
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
        };
    }

    private static final class EmptyTerrainCollisionManager extends TerrainCollisionManager {
        @Override
        public SensorResult[] getSensorResult(Sensor[] sensors) {
            return new SensorResult[0];
        }
    }
}
