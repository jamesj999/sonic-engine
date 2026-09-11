package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.camera.Camera;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused locked-on-ROM coverage for {@code Obj_CutsceneButton} subtypes 4 and 6. */
@RequiresRom(SonicGame.SONIC_3K)
class TestCnz2CutsceneButtonInstance {

    @Test
    void realLayoutButtonWaitsForKnucklesToEnterNativeWidthThenShakesLiveCamera() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        GameServices.camera().setX((short) 0x46E0);
        GameServices.camera().setY((short) 0x0A00);
        ObjectManager objects = GameServices.level().getObjectManager();

        objects.reset(0x46E0);
        objects.update(0x46E0, fixture.sprite(), List.of(fixture.sprite()), 0);

        Cnz2CutsceneButtonInstance button = objects.getActiveObjects().stream()
                .filter(Cnz2CutsceneButtonInstance.class::isInstance)
                .map(Cnz2CutsceneButtonInstance.class::cast)
                .findFirst().orElseThrow();
        SongFadeTransitionInstance transition = objects.getActiveObjects().stream()
                .filter(SongFadeTransitionInstance.class::isInstance)
                .map(SongFadeTransitionInstance.class::cast)
                .findFirst().orElseThrow();
        assertFalse(button.isPressedForTest(),
                "the lower button SST slot runs before CutsceneKnux_CNZ2B publishes _unkFAA4");
        assertFalse(booleanField(transition, "fadeStarted"),
                "the music transition must wait for the following pass when the button can press");

        objects.update(0x46E0, fixture.sprite(), List.of(fixture.sprite()), 1);

        assertFalse(button.isPressedForTest(),
                "the placed dx=$20 lies outside Check_InMyRange's -$18 + $30 width");
        assertTrue(booleanField(transition, "fadeStarted"),
                "Obj_Song_Fade_Transition begins independently while Knuckles approaches the button");
        CutsceneKnucklesCnz2BInstance knuckles = objects.getActiveObjects().stream()
                .filter(CutsceneKnucklesCnz2BInstance.class::isInstance)
                .map(CutsceneKnucklesCnz2BInstance.class::cast)
                .findFirst().orElseThrow();
        setIntField(knuckles, "currentX", button.getX() + 0x17);
        setIntField(knuckles, "currentY", button.getY());

        objects.update(0x46E0, fixture.sprite(), List.of(fixture.sprite()), 2);

        assertTrue(button.isPressedForTest());
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.getCnzEvents().update(1, 2);
        assertEquals(0, manager.getCnzEvents().getScreenShakeOffsetY(),
                "the press frame renders the previous Screen_shake_offset sample");
        manager.getCnzEvents().update(1, 3);
        assertEquals(-5, manager.getCnzEvents().getScreenShakeOffsetY(),
                "ShakeScreen_Setup's first sample becomes visible on the following frame");
        GameServices.level().update();
        assertEquals(-5, GameServices.camera().getShakeOffsetY(),
                "the live parallax pipeline must propagate the button sample to the camera");
        assertEquals((GameServices.camera().getY() & 0xFFFF) - 5,
                GameServices.parallax().getVscrollFactorFG() & 0xFFFF,
                "Plane A must use the same shake sample as the camera and sprites");
    }

    @Test
    void subtype6RealLayoutPlacementStartsReleasedThenPressesAndShakesInRange() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        GameServices.camera().setX((short) 0x45C0);
        GameServices.camera().setY((short) 0x0A00);
        CutsceneKnucklesCnz2BInstance knuckles = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x47A0, 0x0A2C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x10, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());
        knuckles.update(0, null);
        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x4780, 0x0A38,
                        Sonic3kObjectIds.CUTSCENE_BUTTON, 6, 0, false, 0));
        button.setServices(TestEnvironment.objectServices());

        button.update(0, null);

        assertEquals(0x0A3C, button.getY(), "Obj_CutsceneButton setup adds four pixels to y_pos");
        assertFalse(button.isPressedForTest(),
                "word_65C48 stores start=-$18,width=$30, so the real dx=$20 begins released");
        setIntField(knuckles, "currentX", button.getX() + 0x17);
        setIntField(knuckles, "currentY", button.getY());
        button.update(1, null);
        assertTrue(button.isPressedForTest());
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kCNZEvents events = manager.getCnzEvents();
        events.update(1, 1);
        assertEquals(0, events.getScreenShakeOffsetY(),
                "loc_65CAC writes Screen_shake_flag=$14 after the current visible sample");
        events.update(1, 2);
        assertEquals(-5, events.getScreenShakeOffsetY(),
                "the next frame consumes ShakeScreen_Setup index $13");
    }

    @AfterEach
    void tearDown() {
        CutsceneKnucklesCnz2AInstance.clearActiveInstanceForTests();
        CutsceneKnucklesCnz2BInstance.clearActiveInstanceForTests();
        SessionManager.clear();
    }

    @Test
    void subtype4PressesFromRomHalfOpenProximityRangeWithoutImpactHandshake() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1E00, 0x033C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        CutsceneKnucklesCnz2AInstance.setActiveInstanceForTests(knuckles);
        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x1E00, 0x0338,
                        Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 0));
        button.setServices(TestEnvironment.objectServices());

        button.update(0, fixture.sprite());

        assertTrue(button.isPressedForTest(),
                "loc_65C04 uses Check_InMyRange alone; there is no second-landing handshake");
        assertEquals(0x0350,
                GameServices.water().getWaterLevelTarget(Sonic3kZoneIds.ZONE_CNZ, 1));
    }

    @Test
    void proximityBoxIncludesNegativeEdgeAndExcludesPositiveEdge() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x1E00, 0x0338,
                        Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 0));
        button.setServices(TestEnvironment.objectServices());
        CutsceneKnucklesCnz2AInstance.setActiveInstanceForTests(
                new CutsceneKnucklesCnz2AInstance(new ObjectSpawn(
                        0x1E18, 0x033C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0)));

        button.update(0, fixture.sprite());

        assertFalse(button.isPressedForTest(),
                "start=-$18 plus width=$30 makes dx=+$18 the excluded positive edge");

        CutsceneKnucklesCnz2AInstance.setActiveInstanceForTests(
                new CutsceneKnucklesCnz2AInstance(new ObjectSpawn(
                        0x1E17, 0x033C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0)));
        button.update(1, fixture.sprite());

        assertTrue(button.isPressedForTest(), "dx=+$17 is inside Check_InMyRange");
    }

    @Test
    void proximityBoxIncludesNegativeEdge() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x1E00, 0x0338,
                        Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 0));
        button.setServices(TestEnvironment.objectServices());

        CutsceneKnucklesCnz2AInstance.setActiveInstanceForTests(
                new CutsceneKnucklesCnz2AInstance(new ObjectSpawn(
                        0x1DE8, 0x033C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0)));
        button.update(0, fixture.sprite());

        assertTrue(button.isPressedForTest(), "dx=-$18 is included by Check_InMyRange");
    }

    @Test
    void subtype6MutatesNativeTubeColumnThroughRuntimePipelineAndStaysPressed() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        GameServices.camera().setX((short) 0x45C0);
        GameServices.camera().setY((short) 0x0720);
        CutsceneKnucklesCnz2BInstance knuckles = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x4780, 0x072C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 16, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());
        knuckles.update(0, fixture.sprite());
        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x4780, 0x0728,
                        Sonic3kObjectIds.CUTSCENE_BUTTON, 6, 0, false, 0));
        button.setServices(TestEnvironment.objectServices());

        button.update(0, fixture.sprite());

        int[] expected = {0x14, 0x0F, 0x0F, 0x88};
        for (int row = 0; row < expected.length; row++) {
            assertEquals(expected[row],
                    GameServices.level().getCurrentLevel().getMap().getValue(0, 0x8E, 14 + row) & 0xFF,
                    "loc_65CAC layer-0 layout byte mismatch at row " + (14 + row));
        }
        assertTrue(GameServices.zoneLayoutMutationPipeline().isEmpty(),
                "the button applies its atomic gameplay mutation immediately");
        long tubesAfterPress = liveTubeCount();
        assertTrue(tubesAfterPress >= 2);

        button.update(1, fixture.sprite());

        assertEquals(tubesAfterPress, liveTubeCount(),
                "the cleared respawn entry prevents the pressed action replaying on backtrack polls");
        assertFalse(button.isPersistent(),
                "pressed loc_65C50 uses Sprite_CheckDelete instead of remaining permanently resident");
        assertTrue(button.isPressedForTest());

        Cnz2CutsceneButtonInstance freshLevelObject = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x4780, 0x0728,
                        Sonic3kObjectIds.CUTSCENE_BUTTON, 6, 0, false, 0));
        assertFalse(freshLevelObject.isPressedForTest(),
                "a full level restart creates fresh object RAM; ordinary backtracking keeps the live pressed object");
    }

    @Test
    void pressedButtonUnloadsOffscreenAndNeverRespawnsUnpressed() {
        Camera camera = new Camera();
        camera.setX((short) 0x4780);
        ObjectSpawn spawn = new ObjectSpawn(
                0x4780, 0x0728, Sonic3kObjectIds.CUTSCENE_BUTTON, 6, 0, true, 0);
        ObjectManager[] holder = new ObjectManager[1];
        TrackingButtonRegistry registry = new TrackingButtonRegistry();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
        };
        ObjectManager manager = new ObjectManager(
                List.of(spawn), registry, 0, null, null, null, camera, services);
        manager.enablePermanentDestroyLatch();
        holder[0] = manager;
        setSecondCutsceneActive(new CutsceneKnucklesCnz2BInstance(new ObjectSpawn(
                0x4780, 0x072C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 16, 0, false, 0)));

        manager.reset(0x4780);
        manager.update(0x4780, null, List.of(), 0);
        Cnz2CutsceneButtonInstance pressed = manager.getActiveObjects().stream()
                .filter(Cnz2CutsceneButtonInstance.class::isInstance)
                .map(Cnz2CutsceneButtonInstance.class::cast)
                .findFirst().orElseThrow();
        assertTrue(pressed.isPressedForTest());
        assertFalse(pressed.isPersistent(), "loc_65C50 changes to Sprite_CheckDelete after press");

        camera.setX((short) 0);
        manager.update(0, null, List.of(), 1);
        assertFalse(manager.getActiveObjects().contains(pressed),
                "pressed button must unload once its native X range is offscreen");

        camera.setX((short) 0x4780);
        manager.update(0x4780, null, List.of(), 2);
        manager.update(0x4780, null, List.of(), 3);

        assertEquals(1, registry.createCount,
                "cleared respawn_addr keeps the pressed layout entry permanently latched");
        assertTrue(manager.getActiveObjects().stream()
                .noneMatch(Cnz2CutsceneButtonInstance.class::isInstance));
    }

    private static final class TrackingButtonRegistry implements ObjectRegistry {
        private int createCount;

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            createCount++;
            return new Cnz2CutsceneButtonInstance(spawn);
        }

        @Override public void reportCoverage(List<ObjectSpawn> spawns) { }
        @Override public String getPrimaryName(int objectId) { return "CutsceneButtonCNZ2"; }
    }

    private static void setSecondCutsceneActive(CutsceneKnucklesCnz2BInstance instance) {
        try {
            var field = CutsceneKnucklesCnz2BInstance.class.getDeclaredField("activeInstance");
            field.setAccessible(true);
            field.set(null, instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long liveTubeCount() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(CnzVacuumTubeInstance.class::isInstance)
                .filter(object -> !object.isDestroyed())
                .count();
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
