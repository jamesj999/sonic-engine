package com.openggf.tests;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rules.GameRules;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczCrushingColumnObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.Sensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kIczCrushingColumnObject {

    @Test
    void registryCreatesIczCrushingColumnInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x1800, 0x0700, Sonic3kObjectIds.ICZ_CRUSHING_COLUMN, 1, 0, false, 0));

        assertInstanceOf(IczCrushingColumnObjectInstance.class, instance);
    }

    @Test
    void objectUsesRomSolidDimensionsAndCeilingMapping() {
        IczCrushingColumnObjectInstance column = create(1);

        SolidObjectParams params = column.getSolidParams();
        assertEquals(0x2B, params.halfWidth());
        assertEquals(0x70, params.airHalfHeight());
        assertEquals(0x70, params.groundHalfHeight());
        assertEquals(0x0C, column.getMappingFrameForTesting());
        assertEquals(Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN, column.getArtKeyForTesting());
        assertEquals(5, column.getPriorityBucket());
        assertTrue(column.hasBottomDecorationForTesting());
        assertTrue(column.usesInstanceSolidStateLatchKey());
        assertTrue(column.usesInclusiveRightEdge());
    }

    @Test
    void nativeInitDispatchDoesNotRunSubtypeMovement() {
        IczCrushingColumnObjectInstance column =
                new IczCrushingColumnObjectInstance(spawn(6));

        column.update(0, null);

        assertEquals(0x0700, column.getY());
        assertEquals(0x0C, column.getRoutineByteForTesting());

        column.update(1, null);

        assertEquals(0x06FF, column.getY());
    }

    @Test
    void subtypeOneStartsCrushingUpwardWhenStoodOnThenReturnsToNativeEndpoint() {
        TestableColumn column = new TestableColumn(spawn(1));
        PlayableEntity player = mock(PlayableEntity.class);
        column.update(-1, player);

        column.onSolidContact(player, standingContact(), 0);
        column.update(0, player);

        assertEquals(0x0C, column.getRoutineByteForTesting());
        column.update(1, player);
        assertEquals(-0x20, column.getYVelocityForTesting());

        column.ceilingDistance = -3;
        column.update(2, player);

        assertEquals(0x12, column.getRoutineByteForTesting());
        assertEquals(0x0700 - 3 - 1, column.getY());
        assertEquals(0xA000, column.getYSubpixelForTesting(),
                "loc_8A540 corrects the integer y_pos word without clearing its subpixel word");

        for (int frame = 3; frame <= 35; frame++) {
            column.update(frame, player);
        }
        assertEquals(0x16, column.getRoutineByteForTesting());

        for (int frame = 36; frame < 300 && column.getY() < 0x0700; frame++) {
            column.update(frame, player);
        }

        assertEquals(0x06FF, column.getY(),
                "loc_8A5C8 resets before writing the terminal spawn-Y step");
        assertEquals(0x02, column.getRoutineByteForTesting());
        assertEquals(0x5F, column.getTimerForTesting());
    }

    @Test
    void subtypeOneTopLandingTriggersWhenOnlyTallColumnTopIsOnScreen() {
        TestEnvironment.resetAll();
        try {
            IczCrushingColumnObjectInstance column = new IczCrushingColumnObjectInstance(
                    new ObjectSpawn(0x0064, 0x012C, Sonic3kObjectIds.ICZ_CRUSHING_COLUMN, 1, 0, false, 0));

            assertTrue(column.isWithinSolidContactBounds(),
                    "ObjDat_ICZCrushingColumn height $70 should keep the visible top eligible for SolidObjectFull");

            ObjectManager manager = buildManager(column);
            column.snapshotPreUpdatePosition();

            TestPlayableSprite player = new TestPlayableSprite();
            player.useGameRules(GameRules.SONIC_3K);
            player.setWidth(28);
            player.setHeight(38);
            player.setCentreX((short) 0x0064);
            player.setCentreY((short) 0x00AB);
            player.setYSpeed((short) 0x0100);
            player.setAir(true);

            manager.updateSolidContacts(player);
            column.update(0, player);
            column.update(1, player);

            assertFalse(player.getAir(),
                    "Subtype 1 should receive a real SolidObjectFull standing contact when the column top is on screen");
            assertTrue(player.isOnObject(),
                    "Landing on the visible top should attach Sonic to the ICZ crushing column");
            assertEquals(0x0C, column.getRoutineByteForTesting(),
                    "Subtype 1 should switch from standing wait to upward crush after top landing");
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void subtypeOneRetainsVelocityForSecondCrushCycleTiming() {
        TestableColumn column = new TestableColumn(spawn(1));
        PlayableEntity player = mock(PlayableEntity.class);
        column.update(-1, player);

        column.onSolidContact(player, standingContact(), 0);
        column.update(0, player);
        column.update(1, player);

        column.ceilingDistance = -3;
        column.update(2, player);

        for (int frame = 3; frame <= 35; frame++) {
            column.update(frame, player);
        }
        for (int frame = 36; frame < 300 && column.getY() < 0x0700; frame++) {
            column.update(frame, player);
        }

        assertEquals(0x06FF, column.getY());
        assertEquals(-0x40, column.getYVelocityForTesting());

        column.ceilingDistance = 1;
        column.ceilingImpactY = 0x06FE;
        column.onSolidContact(player, standingContact(), 300);
        column.update(300, player);

        column.update(301, player);
        assertEquals(0x0C, column.getRoutineByteForTesting());
        assertEquals(-0x60, column.getYVelocityForTesting(),
                "the preserved impact fraction keeps the first second-cycle tick within y_pos $06FF");

        column.update(302, player);
        assertEquals(0x12, column.getRoutineByteForTesting());
        assertEquals(-0x80, column.getYVelocityForTesting(),
                "the second tick carries the preserved fraction across the $06FE ceiling threshold");
    }

    @Test
    void subtypeTwoWaitsRomTimerBeforeCeilingCrush() {
        IczCrushingColumnObjectInstance column = create(2);
        PlayableEntity player = mock(PlayableEntity.class);

        assertEquals(0x04, column.getRoutineByteForTesting());
        for (int frame = 0; frame < 31; frame++) {
            column.update(frame, player);
            assertEquals(0x04, column.getRoutineByteForTesting());
        }

        column.update(31, player);
        assertEquals(0x0C, column.getRoutineByteForTesting());
    }

    @Test
    void subtypeThreeUsesPlayerSideBeforeFastFloorCrushAndWaitsToReturn() {
        TestableColumn column = new TestableColumn(spawn(3));
        PlayableEntity player = mock(PlayableEntity.class);
        column.update(-1, player);
        when(player.getCentreX()).thenReturn((short) 0x1750);

        column.update(0, player);
        assertEquals(0x06, column.getRoutineByteForTesting());

        when(player.getCentreX()).thenReturn((short) 0x1828);
        column.update(1, player);
        assertEquals(0x10, column.getRoutineByteForTesting());

        column.floorDistance = 4;
        column.update(2, player);
        assertEquals(0x0708, column.getY());

        column.floorDistance = -5;
        column.update(3, player);
        assertEquals(0x12, column.getRoutineByteForTesting());
        assertEquals(0x0710 - 5, column.getY());

        for (int frame = 4; frame <= 36; frame++) {
            column.update(frame, player);
        }
        assertEquals(0x18, column.getRoutineByteForTesting());

        when(player.getCentreX()).thenReturn((short) 0x1900);
        column.update(37, player);
        assertEquals(0x18, column.getRoutineByteForTesting());

        when(player.getCentreX()).thenReturn((short) 0x1800);
        column.update(38, player);
        assertEquals(0x14, column.getRoutineByteForTesting());

        for (int frame = 39; frame <= 49; frame++) {
            column.update(frame, player);
        }
        assertEquals(0x0701, column.getY(),
                "loc_8A5AC resets before writing the terminal spawn-Y step");
        assertEquals(0x06, column.getRoutineByteForTesting());
        assertEquals(0x5F, column.getTimerForTesting());
    }

    @Test
    void subtypeFiveDeletesForNonKnucklesButStaysForKnuckles() {
        IczCrushingColumnObjectInstance nonKnuckles = create(5);
        PlayableEntity sonic = mock(PlayableEntity.class);

        nonKnuckles.update(0, sonic);
        assertTrue(nonKnuckles.isDestroyed());

        IczCrushingColumnObjectInstance knucklesOnly = create(5);
        PlayableEntity knuckles = new Knuckles("knuckles", (short) 0, (short) 0);

        knucklesOnly.update(0, knuckles);
        assertFalse(knucklesOnly.isDestroyed());
    }

    @Test
    void renderDrawsCeilingColumnAndBottomDecoration() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        TestableColumn column = new TestableColumn(spawn(1), renderer);

        column.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderer).drawFrameIndex(0x0C, 0x1800, 0x0700, false, false, 2);
        verify(renderer).drawFrameIndex(0x0D, 0x1800, 0x07B0, false, false, 2);
    }

    @Test
    void profileMarksIczCrushingColumnImplementedOnlyForS3kl() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();

        assertTrue(profile.getImplementedIds().contains(Sonic3kObjectIds.ICZ_CRUSHING_COLUMN));
    }

    private static IczCrushingColumnObjectInstance create(int subtype) {
        IczCrushingColumnObjectInstance column =
                new IczCrushingColumnObjectInstance(spawn(subtype));
        column.update(-1, null);
        return column;
    }

    private static ObjectSpawn spawn(int subtype) {
        return new ObjectSpawn(0x1800, 0x0700, Sonic3kObjectIds.ICZ_CRUSHING_COLUMN, subtype, 0, false, 0);
    }

    private static SolidContact standingContact() {
        return new SolidContact(true, false, false, true, false);
    }

    private static ObjectManager buildManager(ObjectInstance instance) {
        ObjectRegistry registry = new ObjectRegistry() {
            @Override
            public ObjectInstance create(ObjectSpawn spawn) {
                return instance;
            }

            @Override
            public void reportCoverage(List<ObjectSpawn> spawns) {
                // No-op for tests.
            }

            @Override
            public String getPrimaryName(int objectId) {
                return "ICZCrushingColumn";
            }
        };

        ObjectManager manager = new ObjectManager(List.of(), registry, 0, null, null,
                null, null, new StubObjectServices());
        manager.reset(0);
        manager.addDynamicObject(instance);
        return manager;
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite() {
            super("TEST", (short) 0, (short) 0);
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
            rollHeight = 28;
            runHeight = 38;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void draw() {
            // No-op for tests.
        }
    }

    private static final class TestableColumn extends IczCrushingColumnObjectInstance {
        private final PatternSpriteRenderer renderer;
        private int ceilingDistance = 1;
        private int ceilingImpactY = Integer.MIN_VALUE;
        private int floorDistance = 1;

        private TestableColumn(ObjectSpawn spawn) {
            this(spawn, null);
        }

        private TestableColumn(ObjectSpawn spawn, PatternSpriteRenderer renderer) {
            super(spawn);
            this.renderer = renderer;
            setServices(new StubObjectServices() {
                @Override
                public void playSfx(int soundId) {
                    assertEquals(Sonic3kSfx.MECHA_LAND.id, soundId);
                }
            });
        }

        @Override
        protected TerrainCheckResult checkCeilingDistance() {
            int distance = getY() <= ceilingImpactY ? -1 : ceilingDistance;
            return new TerrainCheckResult(distance, (byte) 0, 0);
        }

        @Override
        protected TerrainCheckResult checkFloorDistance() {
            return new TerrainCheckResult(floorDistance, (byte) 0, 0);
        }

        @Override
        protected PatternSpriteRenderer getRenderer(String artKey) {
            assertEquals(Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN, artKey);
            return renderer;
        }
    }
}
