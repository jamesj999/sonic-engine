package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Cnz2CutsceneButtonInstance;
import com.openggf.game.sonic3k.objects.CnzWaterLevelButtonInstance;
import com.openggf.game.sonic3k.objects.CnzWaterLevelCorkFloorInstance;
import com.openggf.game.sonic3k.objects.CorkFloorObjectInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2AInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.events.S3kCnzEventWriteSupport;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Headless coverage for the CNZ water-helper objects added in Task 7.
 *
 * <p>CNZ Act 2 is the only Sonic/Tails route with live water state, so these
 * tests deliberately load Act 2 and assert against the real
 * {@link com.openggf.level.WaterSystem} target values instead of only checking
 * event-local mirrors.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzWaterHelpersHeadless {

    @AfterEach
    void tearDown() {
        CutsceneKnucklesCnz2AInstance.clearActiveInstanceForTests();
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    /**
     * First Knuckles encounter: when the cutscene Knuckles reaches the cutscene
     * button (Obj_CutsceneButton subtype 4 -> loc_65C78), the button raises the
     * CNZ Act 2 water target to {@code $350} through the real WaterSystem and
     * arms the follow-up water-level button.
     */
    @Test
    void firstEncounterButtonRaisesWaterTargetTo350() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        // Place the cutscene Knuckles on top of the button so the proximity box hits.
        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1E00, 0x0338, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        CutsceneKnucklesCnz2AInstance.setActiveInstanceForTests(knuckles);

        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x1E00, 0x0338, Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 0));
        button.setServices(services);
        button.update(0, fixture.sprite());

        assertEquals(0x0350,
                GameServices.water().getWaterLevelTarget(Sonic3kZoneIds.ZONE_CNZ, 1),
                "loc_65C78 sets Target_water_level=$350 through the real WaterSystem");
    }

    @Test
    void firstEncounterCutscenePressesButtonFromRomLayoutPath() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();
        GameServices.camera().setX((short) 0x1D00);
        GameServices.camera().setY((short) 0x0280);

        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1DE0, 0x032C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        knuckles.setServices(services);
        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x1E00, 0x0338, Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 0));
        button.setServices(services);

        for (int frame = 0; frame < 420; frame++) {
            knuckles.update(frame, fixture.sprite());
            button.update(frame, fixture.sprite());
            GameServices.water().update();
            if (GameServices.water().getWaterLevelTarget(Sonic3kZoneIds.ZONE_CNZ, 1) == 0x0350) {
                return;
            }
        }

        assertEquals(0x0350,
                GameServices.water().getWaterLevelTarget(Sonic3kZoneIds.ZONE_CNZ, 1),
                "CutsceneKnux_CNZ2A should press Obj_CutsceneButton subtype $04 from the real CNZ2 layout positions");
    }

    @Test
    void corkFloorWrapperObservesBrokenChildAndStartsWaterRecede() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzWaterLevelCorkFloorInstance helper = new CnzWaterLevelCorkFloorInstance(
                new ObjectSpawn(0x1A60, 0x04E0, Sonic3kObjectIds.CNZ_WATER_LEVEL_CORK_FLOOR, 1, 0, false, 0));
        helper.setServices(services);
        helper.update(0, fixture.sprite());
        CorkFloorObjectInstance child = helper.getCorkFloorForTest();
        assertNotNull(child, "Obj_CNZWaterLevelCorkFloor should spawn and watch the real CorkFloor child");
        child.forceBreakForTest();
        helper.update(1, fixture.sprite());

        assertEquals(0x0958,
                GameServices.water().getWaterLevelTarget(Sonic3kZoneIds.ZONE_CNZ, 1),
                "Obj_CNZWaterLevelCorkFloor should observe the real CorkFloor child breaking "
                        + "and start the $0958 recede path before the player reaches the $1E8F/$05E0 room");
    }

    /**
     * ROM anchors:
     * {@code Obj_CNZWaterLevelCorkFloor} raises {@code Target_water_level} to
     * {@code $0958} once the linked cork floor is gone, while
     * {@code Obj_CNZWaterLevelButton} checks the armed flag and then raises the
     * same target to {@code $0A58}. Task 7 keeps those writes explicit through
     * the CNZ bridge so later teleporter/boss work can observe the same state.
     */
    @Test
    void waterHelpersRaiseTargetWaterToRomHeights() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        DefaultObjectServices services = TestEnvironment.objectServices();

        CnzWaterLevelCorkFloorInstance corkFloor = new CnzWaterLevelCorkFloorInstance(
                new ObjectSpawn(0x4880, 0x0A20, Sonic3kObjectIds.CNZ_WATER_LEVEL_CORK_FLOOR, 0, 0, false, 0));
        corkFloor.setServices(services);
        corkFloor.forceFloorReleasedForTest();
        corkFloor.update(0, fixture.sprite());

        assertEquals(0x0958,
                GameServices.water().getWaterLevelTarget(Sonic3kZoneIds.ZONE_CNZ, 1));
        assertFalse(S3kCnzEventWriteSupport.isWaterButtonArmed(services),
                "Obj_CNZWaterLevelCorkFloor writes _unkFAA2, not the button's _unkFAA3 arm flag");

        // loc_65CC2 (the earlier cutscene button) owns the distinct _unkFAA3 write.
        S3kCnzEventWriteSupport.setWaterButtonArmed(services, true);

        CnzWaterLevelButtonInstance button = new CnzWaterLevelButtonInstance(
                new ObjectSpawn(0x4A40, 0x0A38, Sonic3kObjectIds.CNZ_WATER_LEVEL_BUTTON, 0, 0, false, 0));
        button.setServices(services);
        assertFalse(button.isPersistent(),
                "loc_65DD0 tail-calls Sprite_OnScreen_Test, so the placement button unloads outside $280");
        button.forcePressedForTest();
        button.update(1, fixture.sprite());

        assertEquals(0x0A58,
                GameServices.water().getWaterLevelTarget(Sonic3kZoneIds.ZONE_CNZ, 1));
    }

    /**
     * Task 7 replaces Task 6's placeholder-backed water-helper factories with
     * real CNZ helper objects. Keeping this assertion in the same targeted test
     * command means the registry contract goes red before production changes are
     * written.
     */
    @Test
    void registryCreatesConcreteCnzWaterHelpers() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        ObjectInstance cork = registry.create(
                new ObjectSpawn(0x4880, 0x0A20, Sonic3kObjectIds.CNZ_WATER_LEVEL_CORK_FLOOR, 0, 0, false, 0));
        ObjectInstance button = registry.create(
                new ObjectSpawn(0x4A40, 0x0A38, Sonic3kObjectIds.CNZ_WATER_LEVEL_BUTTON, 0, 0, false, 0));

        assertInstanceOf(CnzWaterLevelCorkFloorInstance.class, cork);
        assertInstanceOf(CnzWaterLevelButtonInstance.class, button);
    }
}
