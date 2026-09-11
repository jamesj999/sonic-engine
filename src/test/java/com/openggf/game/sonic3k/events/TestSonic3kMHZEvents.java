package com.openggf.game.sonic3k.events;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.snapshot.LevelEventSnapshot;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.MhzShipPropellerInstance;
import com.openggf.game.sonic3k.objects.MhzShipSequenceControllerInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossArenaHelperInstance;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.sonic3k.scroll.SwScrlMhz;
import com.openggf.level.Level;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.openggf.level.scroll.M68KMath.negWord;
import static com.openggf.level.scroll.M68KMath.unpackBG;
import static com.openggf.level.scroll.M68KMath.unpackFG;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kMHZEvents {
    @Test
    void act1ScreenEventAppliesRomDynamicMinXAndMaxY() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .startPosition((short) 0x0800, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        camera.setMinX((short) 0);
        camera.setMaxY((short) 0);
        events.update(0, 0);

        assertEquals(0x00C0, camera.getMinX() & 0xFFFF,
                "MHZ1 sub_54B80 should clamp Camera_min_X_pos to $00C0 while player Y < $580");
        assertEquals(0x0AA0, camera.getMaxY() & 0xFFFF,
                "MHZ1_ScreenEvent should leave Camera_max_Y_pos at $0AA0 before the lower end path");

        fixture.sprite().setCentreY((short) 0x0580);
        fixture.sprite().setCentreX((short) 0x4100);
        events.update(0, 1);

        assertEquals(0x0000, camera.getMinX() & 0xFFFF,
                "MHZ1 sub_54B80 should reopen Camera_min_X_pos to 0 after player Y >= $580");
        assertEquals(0x0710, camera.getMaxY() & 0xFFFF,
                "MHZ1_ScreenEvent should lower Camera_max_Y_pos to $0710 once player X >= $4100");
    }

    @Test
    void act1LevelLoadPinsCameraAndMinXToRomMhzStart() {
        // ROM Get_LevelSizeStart loc_1BF1E (sonic3k.asm:38214-38225): MHZ1
        // (Current_zone_and_act==$0700) played as Sonic/Tails (Player_mode<3)
        // with Sonic 3 locked on (SK_alone_flag==0) overrides Camera_min_X_pos
        // to $00C0 and forces the initial Camera_X_pos to $00C0.
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        Camera camera = fixture.camera();
        assertEquals(0x00C0, camera.getMinX() & 0xFFFF,
                "MHZ1 Get_LevelSizeStart should set Camera_min_X_pos to $00C0 at level load");
        assertEquals(0x00C0, camera.getX() & 0xFFFF,
                "MHZ1 Get_LevelSizeStart should force the initial Camera_X_pos to $00C0 at level load");
    }

    @Test
    void act1LevelLoadKeepsLevelSizesMinXForKnuckles() {
        // ROM loc_1BF1E skips the $00C0 override when Player_mode >= 3
        // (Knuckles, cmpi.w #3 / bhs.s), leaving Camera_min_X_pos at MHZ1's
        // LevelSizes xstart of 0 (sonic3k.asm:38111,38217-38218).
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");

        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .build();

        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "MHZ1 level should load for Knuckles");
        assertEquals(0x0000, level.getMinX() & 0xFFFF,
                "MHZ1 loaded as Knuckles should keep the LevelSizes xstart of 0 (no $00C0 override)");
    }

    @Test
    void act1ScreenEventUsesRomKnucklesAloneMinX() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .startPosition((short) 0x0800, (short) 0x0700)
                .startPositionIsCentre()
                .build();
        GameServices.zoneRuntimeRegistry().install(new MhzZoneRuntimeState(0, PlayerCharacter.KNUCKLES));

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        camera.setMinX((short) 0);
        fixture.sprite().setCentreY((short) 0x0700);

        events.update(0, 0);

        assertEquals(0x0680, camera.getMinX() & 0xFFFF,
                "MHZ1 sub_54B80 should force Camera_min_X_pos to $0680 while SK_alone_flag is set");
    }

    @Test
    void act1ScreenEventArmsMinibossArenaAtRomCameraThreshold() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .startPosition((short) 0x4100, (short) 0x0580)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        camera.setX((short) 0x4298);
        camera.setY((short) 0x0710);

        events.update(0, 0);

        assertTrue(events.isBossFlag(),
                "MHZ1_ScreenEvent should set Events_bg+$00/Boss_flag when camera reaches the miniboss arena");
        assertEquals(0x08, events.getSpecialEventsRoutine(),
                "MHZ1_ScreenEvent should publish Special_events_routine=$08 for the miniboss arena");
        assertEquals(0x0710, camera.getMinY() & 0xFFFF,
                "MHZ1_ScreenEvent should lock Camera_min_Y_pos to $0710 at the miniboss arena");
    }

    @Test
    void act1MinibossSpecialEventLoopsArenaAtRomThreshold() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .startPosition((short) 0x4410, (short) 0x0710)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        camera.setX((short) 0x4400);
        events.setBossFlag(true);
        events.setSpecialEventsRoutine(0x08);
        RepeatShiftProbeObject probe = new RepeatShiftProbeObject(0x4308, 0x0710);
        GameServices.level().getObjectManager().addDynamicObject(probe);

        events.update(0, 1);

        assertEquals(0x0200, events.getLevelRepeatOffset(),
                "MHZ1 loc_54CB0 should publish Level_repeat_offset=$0200 at Camera_X_pos >= $4400");
        assertEquals(0x4200, camera.getX() & 0xFFFF,
                "loc_54CB0 subtracts $200 from Camera_X_pos when the loop threshold is reached");
        assertEquals(0x4200, camera.getMinX() & 0xFFFF,
                "loc_54CB0 mirrors the wrapped Camera_X_pos into Camera_min_X_pos");
        assertEquals(0x4298, camera.getMaxX() & 0xFFFF,
                "loc_54CB0 clamps Camera_max_X_pos back to the miniboss arena lock");
        assertEquals(0x4210, fixture.sprite().getCentreX() & 0xFFFF,
                "loc_54CB0 subtracts $200 from Player_1.x_pos during the loop wrap");
        assertEquals(0x4108, probe.getX(),
                "active level-space objects should shift back with the same $200 repeat offset");
    }

    @Test
    void act1MinibossRepeatSkipsObjectsWithoutExplicitRepeatParticipation() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .startPosition((short) 0x4410, (short) 0x0710)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        camera.setX((short) 0x4400);
        events.setBossFlag(true);
        events.setSpecialEventsRoutine(0x08);
        GameServices.level().getObjectManager().addDynamicObject(new SpawnlessDynamicProbeObject());

        events.update(0, 1);

        assertEquals(0x0200, events.getLevelRepeatOffset(),
                "MHZ1 loc_54CB0 should still publish Level_repeat_offset while spawnless helpers are present");
        assertEquals(0x4200, camera.getX() & 0xFFFF,
                "spawnless dynamic helpers must not abort the MHZ1 repeat wrap");
    }

    @Test
    void act1ScreenEventAllocatesMhzMinibossAtRomCameraThreshold() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .startPosition((short) 0x4100, (short) 0x0580)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        camera.setX((short) 0x4298);
        camera.setY((short) 0x0710);

        events.update(0, 0);

        long minibossCount = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(com.openggf.game.sonic3k.objects.MhzMinibossInstance.class::isInstance)
                .count();
        assertEquals(1, minibossCount,
                "MHZ1_ScreenEvent should AllocateObject and install Obj_MHZMiniboss when the camera reaches $4298/$0710");
    }

    @Test
    void act1BackgroundEventRequestsRomSeamlessAct2ReloadWhenResultsSignalArrives() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0)
                .startPosition((short) 0x4400, (short) 0x0710)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.signalActTransition();
        assertTrue(events.isActTransitionFlagActive(),
                "Obj_LevelResultsCreate should route Events_fg_5 through the MHZ event handler");

        events.update(0, 1);

        SeamlessLevelTransitionRequest request = GameServices.level().consumeSeamlessTransitionRequest();
        assertNotNull(request,
                "MHZ1_BackgroundEvent should request the seamless act reload when Events_fg_5 is set");
        assertEquals(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL, request.type(),
                "MHZ1_BackgroundEvent should reload the target level data like Current_zone_and_act=$0701");
        assertEquals(Sonic3kZoneIds.ZONE_MHZ, request.targetZone(),
                "MHZ1_BackgroundEvent should keep the current zone when moving to Act 2");
        assertEquals(1, request.targetAct(),
                "MHZ1_BackgroundEvent should request Mushroom Hill Act 2");
        assertEquals(-0x4200, request.playerOffsetX(),
                "MHZ1_BackgroundEvent should subtract $4200 from both players during the reload");
        assertEquals(-0x4200, request.cameraOffsetX(),
                "MHZ1_BackgroundEvent should subtract $4200 from camera X during the reload");
        assertEquals(0, request.playerOffsetY(),
                "MHZ1_BackgroundEvent should not vertically offset players during the reload");
        assertTrue(request.preserveMusic(),
                "MHZ1_BackgroundEvent should preserve the Act 2 music already started by the results path");
        assertTrue(request.showInLevelTitleCard(),
                "MHZ1_BackgroundEvent should request the Act 2 title card after the seamless reload");
        assertFalse(events.isActTransitionFlagActive(),
                "MHZ1_BackgroundEvent should clear Events_fg_5 after consuming the transition signal");
        assertFalse(events.isBossFlag(),
                "MHZ1_BackgroundEvent should clear Boss_flag after queuing the Act 2 reload");
        assertEquals(0, events.getSpecialEventsRoutine(),
                "MHZ1_BackgroundEvent should clear Special_events_routine after queuing the Act 2 reload");
    }

    @Test
    void act2ScreenInitLoadsGreenSeasonForLowerEarlyStart() throws IOException {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x0800, (short) 0x0600)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.GREEN, events.getSeasonPaletteMode(),
                "MHZ2_ScreenInit should choose Pal_MHZ1+$20 for X < $09C0 and Y >= $0600");
        assertFalse(events.isSeasonFlagSet(), "MHZ2_ScreenInit should clear _unkF7C1 for the green season");
        assertFalse(events.isAutumnTriggerFlagSet(),
                "MHZ2_ScreenInit should clear Events_bg+$04 for the green season");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ1_LINE3_ADDR);
    }

    @Test
    void act2ScreenInitLoadsAutumnSeasonForMiddleStart() throws IOException {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x1000, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN, events.getSeasonPaletteMode(),
                "MHZ2_ScreenInit should choose Pal_MHZ2+$20 for $09C0 <= X < $2940");
        assertTrue(events.isSeasonFlagSet(), "MHZ2_ScreenInit should set _unkF7C1 for autumn");
        assertTrue(events.isAutumnTriggerFlagSet(), "MHZ2_ScreenInit should set Events_bg+$04 for autumn");
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        assertEquals(events.getSeasonPaletteMode(), runtimeState.seasonPaletteMode());
        assertTrue(runtimeState.isSeasonFlagSet(),
                "MHZ runtime state should expose _unkF7C1 for object/event consumers");
        assertTrue(runtimeState.isAutumnTriggerFlagSet(),
                "MHZ runtime state should expose Events_bg+$04 for object/event consumers");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_LINE3_ADDR);
    }

    @Test
    void act2ScreenInitLoadsGoldSeasonForLateStart() throws IOException {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x2940, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.GOLD, events.getSeasonPaletteMode(),
                "MHZ2_ScreenInit should choose Pal_MHZ2Gold for X >= $2940");
        assertTrue(events.isSeasonFlagSet(), "MHZ2_ScreenInit should set _unkF7C1 for gold");
        assertFalse(events.isAutumnTriggerFlagSet(),
                "MHZ2_ScreenInit should leave Events_bg+$04 clear for gold");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_GOLD_ADDR);
    }

    @Test
    void act2SeasonTriggerEntersAutumnFromGreenAtRomRegionOne() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x0800, (short) 0x0600)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x09C0);
        fixture.sprite().setCentreY((short) 0x07E0);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN, events.getSeasonPaletteMode(),
                "sub_55008 region 1 should enter autumn once player X reaches $09C0");
        assertTrue(events.isSeasonFlagSet(), "loc_55098 should set _unkF7C1");
        assertTrue(events.isAutumnTriggerFlagSet(), "loc_55098 should set Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_LINE3_ADDR);
    }

    @Test
    void act2SeasonTriggerLeavesAutumnForGreenAtRomRegionZero() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x1000, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x0440);
        fixture.sprite().setCentreY((short) 0x0680);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.GREEN, events.getSeasonPaletteMode(),
                "sub_55008 region 0 should return to green when Events_bg+$04 is set and player Y reaches $0680");
        assertFalse(events.isSeasonFlagSet(), "loc_550A8 should clear _unkF7C1");
        assertFalse(events.isAutumnTriggerFlagSet(), "loc_550A8 should clear Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ1_LINE3_ADDR);
    }

    @Test
    void act2SeasonTriggerExcludesRomRegionZeroUpperYBoundary() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x1000, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x0440);
        fixture.sprite().setCentreY((short) 0x06C0);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN, events.getSeasonPaletteMode(),
                "sub_55008 enters a trigger only when player Y is below the region max; Y=$06C0 must stay autumn");
        assertTrue(events.isSeasonFlagSet(), "loc_550A8 must not run at the exclusive region boundary");
        assertTrue(events.isAutumnTriggerFlagSet(),
                "Events_bg+$04 must stay set when the exclusive Y boundary prevents the region from matching");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_LINE3_ADDR);
    }

    @Test
    void act2SeasonTriggerLeavesAutumnForGreenAtRomRegionOne() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x1000, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x09BF);
        fixture.sprite().setCentreY((short) 0x07E0);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.GREEN, events.getSeasonPaletteMode(),
                "sub_55008 region 1 should return to green when Events_bg+$04 is set and player X is below $09C0");
        assertFalse(events.isSeasonFlagSet(), "loc_550A8 should clear _unkF7C1");
        assertFalse(events.isAutumnTriggerFlagSet(), "loc_550A8 should clear Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ1_LINE3_ADDR);
    }

    @Test
    void act2SeasonTriggerLeavesAutumnForGoldAtRomRegionTwo() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x1000, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x2940);
        fixture.sprite().setCentreY((short) 0x02C0);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.GOLD, events.getSeasonPaletteMode(),
                "sub_55008 region 2 should enter gold once autumn player X reaches $2940");
        assertTrue(events.isSeasonFlagSet(), "loc_550B8 should leave _unkF7C1 set");
        assertFalse(events.isAutumnTriggerFlagSet(), "loc_550B8 should clear Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_GOLD_ADDR);
    }

    @Test
    void act2SeasonTriggerEntersAutumnFromGreenAtRomRegionTwo() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x0800, (short) 0x0600)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x293F);
        fixture.sprite().setCentreY((short) 0x02C0);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN, events.getSeasonPaletteMode(),
                "sub_55008 region 2 should enter autumn when Events_bg+$04 is clear and player X is below $2940");
        assertTrue(events.isSeasonFlagSet(), "loc_55098 should set _unkF7C1");
        assertTrue(events.isAutumnTriggerFlagSet(), "loc_55098 should set Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_LINE3_ADDR);
    }

    @Test
    void act2SeasonTriggerEntersAutumnFromGreenAtRomRegionZero() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x0800, (short) 0x0600)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x0440);
        fixture.sprite().setCentreY((short) 0x067F);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN, events.getSeasonPaletteMode(),
                "sub_55008 region 0 should enter autumn when Events_bg+$04 is clear and player Y is below $0680");
        assertTrue(events.isSeasonFlagSet(), "loc_55098 should set _unkF7C1");
        assertTrue(events.isAutumnTriggerFlagSet(), "loc_55098 should set Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_LINE3_ADDR);
    }

    @Test
    void act2SeasonTriggerLeavesAutumnForGoldAtRomRegionThree() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x1000, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x2B40);
        fixture.sprite().setCentreY((short) 0x0560);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.GOLD, events.getSeasonPaletteMode(),
                "sub_55008 region 3 should enter gold when Events_bg+$04 is set and player X reaches $2B40");
        assertTrue(events.isSeasonFlagSet(), "loc_550B8 should leave _unkF7C1 set");
        assertFalse(events.isAutumnTriggerFlagSet(), "loc_550B8 should clear Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_GOLD_ADDR);
    }

    @Test
    void act2SeasonTriggerEntersAutumnFromGreenAtRomRegionThree() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x0800, (short) 0x0600)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x2B3F);
        fixture.sprite().setCentreY((short) 0x0560);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN, events.getSeasonPaletteMode(),
                "sub_55008 region 3 should enter autumn when Events_bg+$04 is clear and player X is below $2B40");
        assertTrue(events.isSeasonFlagSet(), "loc_55098 should set _unkF7C1");
        assertTrue(events.isAutumnTriggerFlagSet(), "loc_55098 should set Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_LINE3_ADDR);
    }

    @Test
    void act2SeasonTriggerLeavesAutumnForGoldAtRomRegionFour() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x1000, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x2900);
        fixture.sprite().setCentreY((short) 0x0800);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.GOLD, events.getSeasonPaletteMode(),
                "sub_55008 region 4 should enter gold when Events_bg+$04 is set and player Y reaches $0800");
        assertTrue(events.isSeasonFlagSet(), "loc_550B8 should leave _unkF7C1 set");
        assertFalse(events.isAutumnTriggerFlagSet(), "loc_550B8 should clear Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_GOLD_ADDR);
    }

    @Test
    void act2SeasonTriggerEntersAutumnFromGreenAtRomRegionFour() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x0800, (short) 0x0600)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        fixture.sprite().setCentreX((short) 0x2900);
        fixture.sprite().setCentreY((short) 0x07FF);

        events.update(1, 1);

        assertEquals(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN, events.getSeasonPaletteMode(),
                "sub_55008 region 4 should enter autumn when Events_bg+$04 is clear and player Y is below $0800");
        assertTrue(events.isSeasonFlagSet(), "loc_55098 should set _unkF7C1");
        assertTrue(events.isAutumnTriggerFlagSet(), "loc_55098 should set Events_bg+$04");
        assertPaletteBlockMatchesRom(Sonic3kConstants.PAL_MHZ2_LINE3_ADDR);
    }

    @Test
    void act2ScreenEventAppliesEarlyRouteCameraBounds() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x0800, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        camera.setX((short) 0x037F);
        camera.setY((short) 0x0200);
        camera.setMinX((short) 0);
        camera.setMinY((short) 0);
        camera.setMaxY((short) 0);
        fixture.sprite().setCentreX((short) 0x0800);
        fixture.sprite().setCentreY((short) 0x05BF);

        events.update(1, 1);

        assertEquals(0x0380, camera.getMinX() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should clamp Camera_min_X_pos to $0380 while player Y < $05C0");
        assertEquals(0x0620, camera.getMinY() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should set Camera_min_Y_pos to $0620 while camera X < $0380");
        assertEquals(0x09A0, camera.getMaxY() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should use Camera_max_Y_pos $09A0 before the end slope");
    }

    @Test
    void act2ScreenEventReopensMiddleRouteCameraBounds() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x1000, (short) 0x05C0)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        camera.setX((short) 0x1000);
        camera.setMinX((short) 0x0380);
        camera.setMinY((short) 0x0620);
        fixture.sprite().setCentreY((short) 0x05C0);

        events.update(1, 1);

        assertEquals(0x0098, camera.getMinX() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should lower Camera_min_X_pos to $0098 once player Y >= $05C0");
        assertEquals(0x0000, camera.getMinY() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should reopen Camera_min_Y_pos between camera X $0380 and $35FF");
    }

    @Test
    void act2ScreenEventAppliesLateRouteTopAndBottomBounds() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3AC0, (short) 0x02FF)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        camera.setX((short) 0x3600);
        camera.setMinY((short) 0);
        camera.setMaxY((short) 0x09A0);
        fixture.sprite().setCentreX((short) 0x3AC0);
        fixture.sprite().setCentreY((short) 0x02FF);

        events.update(1, 1);

        assertEquals(0x01A8, camera.getMinY() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should set Camera_min_Y_pos to $01A8 once camera X >= $3600");
        assertEquals(0x0280, camera.getMaxY() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should lower Camera_max_Y_pos to $0280 at the final upper path");
        assertEquals(0x0280, camera.getMaxYTarget() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should mirror Camera_target_max_Y_pos to $0280");
    }

    @Test
    void act2ScreenEventLocksFinalGateAtExactRomCameraThreshold() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C90);
        camera.setY((short) 0x0280);
        camera.setMinX((short) 0);
        camera.setMinY((short) 0);

        events.update(1, 1);

        assertEquals(0x3C90, camera.getMinX() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should lock Camera_min_X_pos at exact camera X $3C90");
        assertEquals(0x0280, camera.getMinY() & 0xFFFF,
                "MHZ2_ScreenEvent routine $04 should lock Camera_min_Y_pos once camera Y >= $0280 at the gate");
    }

    @Test
    void act2BackgroundEventRoutine0StagesRomEndBossRedrawAtGate() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x36F0, (short) 0x0400)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        fixture.sprite().setCentreX((short) 0x3700);
        fixture.sprite().setCentreY((short) 0x0400);
        camera.setY((short) 0x0120);

        events.update(1, 1);

        assertEquals(0x04, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_551EE should advance Events_routine_bg to $04 when player crosses x=$3700 with y < $0500");
        assertEquals(0x0200, events.getEndBossArenaDrawPosition(),
                "loc_551EE should prime Draw_delayed_position to (Camera_Y_pos_BG_copy+$E0)&Camera_Y_pos_mask");
        assertEquals(0x000F, events.getEndBossArenaDrawRowCount(),
                "loc_551EE should prime Draw_delayed_rowcount=$000F for the bottom-up redraw");
        assertFalse(events.isEndBossCustomLayoutQueued(),
                "routine $00 should only stage the redraw; MHZ_Custom_Layout is copied later from routine $08");
    }

    @Test
    void act2BackgroundEventRoutine8QueuesCustomLayoutBeforeEndBossArena() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3700, (short) 0x0400)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        events.setAct2BackgroundRoutineForTest(0x08);
        camera.setX((short) 0x3E00);
        camera.setFrozen(false);

        events.update(1, 1);

        assertTrue(events.isEndBossCustomLayoutQueued(),
                "MHZ2 BackgroundEvent loc_5528A should queue MHZ_Custom_Layout and custom chunk/block/art loads");
        assertEquals(0x10, events.getAct2BackgroundRoutine(),
                "loc_5528A should add 8 to Events_routine_bg, entering the end-boss arena setup routine");
        assertFalse(events.isEndBossPillarArtQueued(),
                "loc_55312 should not queue ArtKosM_MHZEndBossPillar until Camera_X_pos reaches $3F00");
        assertFalse(camera.getFrozen(),
                "Scroll_lock should remain clear when loc_55312 branches to loc_55486 below the arena threshold");
    }

    @Test
    void act2BackgroundEventRoutine8AppliesRomCustomLayoutThroughMutationPipeline() throws IOException {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3700, (short) 0x0400)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x08);
        GameServices.camera().setX((short) 0x3E00);

        com.openggf.level.Map map = GameServices.level().getCurrentLevel().getMap();
        int[] changedCell = firstCustomLayoutDifference(map);
        assertTrue(changedCell[3] != changedCell[4],
                "test fixture must choose a cell where MHZ2 normal layout differs from MHZ_Custom_Layout");

        events.update(1, 1);
        GameServices.level().flushQueuedLayoutMutations();

        assertEquals(changedCell[4], map.getValue(changedCell[0], changedCell[1], changedCell[2]) & 0xFF,
                "MHZ2 loc_5528A copies MHZ_Custom_Layout into Level_layout_header before the end-boss arena setup");
    }

    @Test
    void act2BackgroundEventRoutine8AppliesRomCustomChunkBlockAndArtLoads() throws IOException {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3700, (short) 0x0400)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x08);
        GameServices.camera().setX((short) 0x3E00);

        Level level = GameServices.level().getCurrentLevel();
        ResourceLoader loader = new ResourceLoader(GameServices.rom().getRom());
        byte[] customBlocks16x16 = loader.loadSingle(
                LoadOp.kosinskiBase(Sonic3kConstants.MHZ_CUSTOM_BLOCKS_16X16_KOS_ADDR));
        byte[] customChunks128x128 = loader.loadSingle(
                LoadOp.kosinskiBase(Sonic3kConstants.MHZ_CUSTOM_CHUNKS_128X128_KOS_ADDR));
        byte[] customArt = loader.loadSingle(
                LoadOp.kosinskiMBase(Sonic3kConstants.MHZ_CUSTOM_ART_KOSM_ADDR));

        int chunkIndex = Sonic3kConstants.MHZ_CUSTOM_BLOCK_TABLE_DEST_OFFSET
                / com.openggf.level.Chunk.CHUNK_SIZE_IN_ROM;
        int blockIndex = Sonic3kConstants.MHZ_CUSTOM_CHUNK_TABLE_DEST_OFFSET
                / com.openggf.level.LevelConstants.BLOCK_SIZE_IN_ROM;
        Pattern expectedPattern = segaPattern(customArt, 0);

        assertFalse(chunkMatchesRawWords(level, chunkIndex, customBlocks16x16),
                "test fixture should start before the MHZ_16x16_Custom_Kos patch is applied");
        assertFalse(blockMatchesRawWords(level, blockIndex, customChunks128x128),
                "test fixture should start before the MHZ_128x128_Custom_Kos patch is applied");
        assertFalse(patternPixelsMatch(level.getPattern(Sonic3kConstants.MHZ_CUSTOM_ART_TILE), expectedPattern),
                "test fixture should start before ArtKosM_MHZ_Custom is loaded at tile $222");

        events.update(1, 1);
        GameServices.level().flushQueuedLayoutMutations();

        assertChunkMatchesRawWords(level, chunkIndex, customBlocks16x16,
                "loc_5528A should queue MHZ_16x16_Custom_Kos to Block_table+$B28");
        assertBlockMatchesRawWords(level, blockIndex, customChunks128x128,
                "loc_5528A should queue MHZ_128x128_Custom_Kos to Chunk_table+$2280");
        assertPatternPixelsMatch(level.getPattern(Sonic3kConstants.MHZ_CUSTOM_ART_TILE), expectedPattern,
                "loc_5528A should queue ArtKosM_MHZ_Custom to VRAM tile $222");
    }

    @Test
    void act2BackgroundEventRoutine8WaitsForAirborneMidHeightPlayerBeforeCustomLayout() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3700, (short) 0x0450)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x08);
        fixture.sprite().setCentreY((short) 0x0450);
        fixture.sprite().setAir(true);
        GameServices.camera().setX((short) 0x3F00);

        events.update(1, 1);

        assertFalse(events.isEndBossCustomLayoutQueued(),
                "MHZ2 loc_5527A should wait at routine $08 while Player_1 is airborne between y=$421 and y=$4FF");
        assertEquals(0x08, events.getAct2BackgroundRoutine(),
                "loc_552E0 should leave Events_routine_bg at $08 instead of copying MHZ_Custom_Layout");
        assertFalse(events.isEndBossArenaBackgroundActive(),
                "loc_552E0 should not fall through to loc_55312/end-boss arena setup on the wait path");
    }

    @Test
    void act2BackgroundEventRoutine8HighPlayerYStartsRomRedrawInsteadOfCustomLayout() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3700, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x08);
        fixture.sprite().setCentreY((short) 0x0500);
        GameServices.camera().setX((short) 0x3F00);

        events.update(1, 1);

        assertFalse(events.isEndBossCustomLayoutQueued(),
                "MHZ2 loc_55250 should not copy MHZ_Custom_Layout when Player_1+y_pos >= $0500");
        assertEquals(0x0C, events.getAct2BackgroundRoutine(),
                "loc_55250 should advance Events_routine_bg to $0C for the bottom-up redraw path");
        assertEquals(0x000F, events.getEndBossArenaDrawRowCount(),
                "loc_55250 should prime Draw_delayed_rowcount=$0F for the redraw path");
        assertFalse(events.isEndBossArenaBackgroundActive(),
                "loc_55250 should not fall through to loc_55312/end-boss arena setup in the same frame");
    }

    @Test
    void act2BackgroundEventRoutine8RunsShakeSetupWhileWaitingBelowArenaThreshold() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3700, (short) 0x0400)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x08);
        events.setScreenShakeFlagForTest(-1);
        events.setScreenShakeOffsetForTest(2);
        GameServices.camera().setX((short) 0x3E00);

        events.update(1, 3);

        assertEquals(2, events.getScreenShakeLastOffset(),
                "loc_55486 should preserve Screen_shake_offset into Screen_shake_last_offset before ticking shake");
        assertEquals(3, events.getScreenShakeOffset(),
                "negative Screen_shake_flag should use ScreenShakeArray2[Level_frame_counter & $3F]");
    }

    @Test
    void act2BackgroundEventArmsEndBossArenaAtRomCameraThreshold() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);
        camera.setFrozen(false);

        events.update(1, 1);

        assertTrue(events.isEndBossArenaBackgroundActive(),
                "MHZ2 BackgroundEvent loc_55312 should set Events_bg+$00 when Camera_X_pos reaches $3F00");
        assertTrue(camera.getFrozen(),
                "MHZ2 BackgroundEvent loc_55312 should set Scroll_lock for the end-boss arena");
        assertEquals(0x0C, events.getSpecialEventsRoutine(),
                "MHZ2 BackgroundEvent loc_55312 should publish Special_events_routine=$0C");
        assertEquals(0x01A0, events.getEndBossArenaDrawPosition(),
                "MHZ2 BackgroundEvent loc_55312 should set Draw_delayed_position=$01A0");
        assertEquals(0x0002, events.getEndBossArenaDrawRowCount(),
                "MHZ2 BackgroundEvent loc_55312 should set Draw_delayed_rowcount=2");
        assertEquals(0x14, events.getEventRoutine(),
                "MHZ2 BackgroundEvent loc_55312 should advance Events_routine_fg by four");
        assertEquals(0x10, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_55312 should stay on routine $10 until the delayed column draw completes");
        assertEquals(0x01, events.getEndBossArenaScrollDataByte(),
                "MHZ2 BackgroundEvent loc_55312 should copy the first byte from word_558E8 into Events_bg+$07");
        assertTrue(events.isEndBossPillarArtQueued(),
                "MHZ2 BackgroundEvent loc_55312 should queue ArtKosM_MHZEndBossPillar for the arena");
    }

    @Test
    void act2EndBossSpecialEventWrapsRepeatingArenaAtRomThreshold() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        try {
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                    .startPosition((short) 0x4300, (short) 0x0280)
                    .startPositionIsCentre()
                    .build();

            Sonic3kMHZEvents events = getMhzEvents();
            Camera camera = fixture.camera();
            events.setEventRoutine(0x14);
            events.setSpecialEventsRoutine(0x0C);
            camera.setX((short) 0x427C);
            camera.setMinX((short) 0x427C);
            camera.setMaxX((short) 0x427C);

            var sidekicks = GameServices.sprites().getSidekicks();
            assertFalse(sidekicks.isEmpty(), "Test must exercise the ROM Player_2+x_pos branch in loc_5564A");
            var sidekick = sidekicks.getFirst();
            sidekick.setCentreX((short) 0x4300);
            sidekick.setCentreY((short) 0x0280);

            events.update(1, 1);

            assertEquals(0x4080, camera.getX() & 0xFFFF,
                    "MHZ2 loc_5560C should subtract $0200 from Camera_X_pos once Camera_X_pos+4 reaches $4280");
            assertEquals(0x4080, camera.getMinX() & 0xFFFF,
                    "MHZ2 loc_5560C should keep Camera_min_X_pos locked to the repeated arena camera X");
            assertEquals(0x4080, camera.getMaxX() & 0xFFFF,
                    "MHZ2 loc_5560C should mirror Camera_max_X_pos to the wrapped camera X before boss defeat");
            assertEquals(0x4100, fixture.sprite().getCentreX() & 0xFFFF,
                    "MHZ2 loc_5560C should subtract $0200 from Player_1+x_pos during the arena repeat");
            assertEquals(0x4100, sidekick.getCentreX() & 0xFFFF,
                    "MHZ2 loc_5564A should also subtract $0200 from Player_2+x_pos during the arena repeat");
            assertEquals(0x02, events.getEndBossArenaScrollDataByte(),
                    "MHZ2 loc_5564A should increment Events_bg+$08 and reload Events_bg+$07 from word_558E8 on each wrap");
        } finally {
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldMainCharacter);
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, oldSidekickCharacter);
        }
    }

    @Test
    void act2EndBossRepeatOffsetRewindsWithLevelEventSnapshot() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x4300, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        events.setEventRoutine(0x14);
        events.setSpecialEventsRoutine(0x0C);
        camera.setX((short) 0x427C);
        camera.setMinX((short) 0x427C);
        camera.setMaxX((short) 0x427C);

        events.update(1, 1);
        assertEquals(0x0200, events.getLevelRepeatOffset(),
                "MHZ2 loc_5560C should publish Level_repeat_offset for same-frame boss object processing");

        LevelEventSnapshot snapshot = manager.capture();
        events.update(1, 2);
        assertEquals(0, events.getLevelRepeatOffset(),
                "MHZ clears Level_repeat_offset at the start of each event update");

        manager.restore(snapshot);
        assertEquals(0x0200, events.getLevelRepeatOffset(),
                "MHZ Level_repeat_offset must rewind so the boss sees the same same-frame arena-wrap delta");
    }

    @Test
    void act2EndBossSpecialEventClearsWaitAnimationDuringArenaClamp() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x4300, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        events.setEventRoutine(0x14);
        events.setSpecialEventsRoutine(0x0C);
        camera.setX((short) 0x427C);
        fixture.sprite().setAnimationId(5);

        events.update(1, 1);

        assertEquals(0, fixture.sprite().getAnimationId(),
                "MHZ2 sub_556B8 should clear anim=$05 before applying the arena player clamp");
    }

    @Test
    void act2EndBossSpecialEventOpensEscapeAfterBossRestoreSignal() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x4400, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        events.setEventRoutine(0x14);
        events.setSpecialEventsRoutine(0x0C);
        events.setEndBossArenaRestoreRequested(true);
        camera.setX((short) 0x441C);
        camera.setMinX((short) 0x441C);
        camera.setMaxX((short) 0x441C);
        camera.setFrozen(false);

        events.update(1, 1);

        assertEquals(0x4420, camera.getX() & 0xFFFF,
                "MHZ2 loc_5560C should clamp Camera_X_pos to $4420 once the restored arena reaches the escape edge");
        assertEquals(0x4420, camera.getMinX() & 0xFFFF,
                "MHZ2 loc_5560C should keep Camera_min_X_pos at the escape edge");
        assertEquals(0x45A0, camera.getMaxX() & 0xFFFF,
                "MHZ2 loc_5560C should open Camera_max_X_pos to $45A0 after the boss restore signal");
        assertTrue(camera.getFrozen(),
                "MHZ2 loc_5560C should set Scroll_lock when opening the escape");
        assertEquals(0, events.getSpecialEventsRoutine(),
                "MHZ2 loc_5560C should clear Special_events_routine after opening the escape");
    }

    @Test
    void act2BackgroundEventAdvancesToEndBossArenaObjectDrawAfterDelayedColumns() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);

        events.update(1, 1);
        events.update(1, 2);
        events.update(1, 3);
        events.update(1, 4);

        assertEquals(0x14, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_55380 should advance Events_routine_bg to $14 after the delayed bottom-up draw");
        assertEquals(0x0080, events.getEndBossArenaDrawPosition(),
                "MHZ2 BackgroundEvent loc_55380 should prime Draw_delayed_position=$0080 for the top-down arena draw");
        assertEquals(0x0002, events.getEndBossArenaDrawRowCount(),
                "MHZ2 BackgroundEvent loc_55380 should prime Draw_delayed_rowcount=2 for the top-down arena draw");
        assertTrue(events.isEndBossArenaForegroundRefreshActive(),
                "MHZ2 BackgroundEvent loc_55380 should set Events_bg+$01 when switching to routine $14");
    }

    @Test
    void act2BackgroundEventAllocatesEndBossArenaHelpersAfterTopDownDraw() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);

        events.update(1, 1);
        events.update(1, 2);
        events.update(1, 3);
        events.update(1, 4);
        events.update(1, 5);
        events.update(1, 6);
        events.update(1, 7);

        assertEquals(0x18, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_553B6 should advance Events_routine_bg to $18 after top-down draw completion");
        assertTrue(events.isEndBossArenaHScrollCleared(),
                "MHZ2 BackgroundEvent loc_553B6 should clear HScroll_table+$008 before helper allocation");
        assertEquals(1, events.getEndBossArenaPillarControllerCount(),
                "MHZ2 BackgroundEvent loc_553B6 should allocate one loc_556F8 pillar controller");
        assertEquals(1, events.getEndBossArenaTallSupportCount(),
                "MHZ2 BackgroundEvent loc_553B6 should allocate one loc_55732 tall arena support");
        assertEquals(6, events.getEndBossArenaSpikeHelperCount(),
                "MHZ2 BackgroundEvent loc_553B6 should allocate six loc_5577C spike helpers");
        assertArrayEquals(new int[] {2, 2, 1, 1, 0, 0}, events.getEndBossArenaSpikeTiersForTest(),
                "MHZ2 BackgroundEvent loc_553F0 should assign spike helper tiers from d1=2 down to 0");
        assertArrayEquals(new boolean[] {false, true, false, true, false, true},
                events.getEndBossArenaSpikeAlternateSidesForTest(),
                "MHZ2 BackgroundEvent loc_553F0 should set Events_bg side flag on every second spike helper");
    }

    @Test
    void act2BackgroundEventAllocatesRuntimeEndBossArenaHelperObjects() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);

        for (int frame = 1; frame <= 7; frame++) {
            events.update(1, frame);
        }

        List<MhzEndBossArenaHelperInstance> helpers = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzEndBossArenaHelperInstance.class::isInstance)
                .map(MhzEndBossArenaHelperInstance.class::cast)
                .toList();

        assertEquals(8, helpers.size(),
                "MHZ2 loc_553B6 should allocate one pillar, one support, and six loc_5577C spike helper objects");
        assertEquals(1, helpers.stream()
                        .filter(helper -> helper.getRole() == MhzEndBossArenaHelperInstance.Role.PILLAR)
                        .count(),
                "loc_556F8 should occupy one dynamic object slot");
        assertEquals(1, helpers.stream()
                        .filter(helper -> helper.getRole() == MhzEndBossArenaHelperInstance.Role.TALL_SUPPORT)
                        .count(),
                "loc_55732 should occupy one dynamic object slot");
        assertEquals(6, helpers.stream()
                        .filter(helper -> helper.getRole() == MhzEndBossArenaHelperInstance.Role.SPIKE)
                        .count(),
                "loc_553F0 should allocate six loc_5577C spike helper slots");
        assertTrue(helpers.stream().anyMatch(helper ->
                        helper.getRole() == MhzEndBossArenaHelperInstance.Role.PILLAR
                                && helper.getX() == 0x4238
                                && helper.getY() == 0x02F0
                                && helper.getPriorityBucket() == 1),
                "loc_556F8 should spawn the pillar at x_pos=$4238,y_pos=$02F0,priority=$80");
        assertTrue(helpers.stream().anyMatch(helper ->
                        helper.getRole() == MhzEndBossArenaHelperInstance.Role.TALL_SUPPORT
                                && helper.getY() == 0x0300
                                && helper.getMappingFrame() == 1
                                && helper.getPriorityBucket() == 7),
                "loc_55732 should spawn the tall support with mapping_frame=1,priority=$380");
    }

    @Test
    void act2EndBossArenaSetupLoadsRomPillarArtAtVramTile580() throws IOException {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x4300, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);

        Level level = GameServices.level().getCurrentLevel();
        ResourceLoader loader = new ResourceLoader(GameServices.rom().getRom());
        byte[] pillarArt = loader.loadSingle(
                LoadOp.kosinskiMBase(Sonic3kConstants.ART_KOSM_MHZ_END_BOSS_PILLAR_ADDR));
        Pattern expectedPattern = segaPattern(pillarArt, 0);

        assertFalse(patternPixelsMatch(level.getPattern(Sonic3kConstants.ART_TILE_MHZ_END_BOSS_PILLAR),
                        expectedPattern),
                "fixture should start before ArtKosM_MHZEndBossPillar is loaded at tile $580");

        events.update(1, 1);
        GameServices.level().flushQueuedLayoutMutations();

        assertPatternPixelsMatch(level.getPattern(Sonic3kConstants.ART_TILE_MHZ_END_BOSS_PILLAR), expectedPattern,
                "loc_5535A should queue ArtKosM_MHZEndBossPillar to ArtTile_MHZEndBossPillar ($580)");
    }

    @Test
    void act2EndBossArenaSpikeHelpersUseRomScrollDataYTable() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x4300, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);
        events.update(1, 1);
        events.update(1, 2);
        events.update(1, 3);
        events.update(1, 4);
        events.update(1, 5);
        events.update(1, 6);
        events.update(1, 7);

        camera.setX((short) 0x427C);
        events.update(1, 8);

        assertArrayEquals(new boolean[] {true, false, true, false, true, false},
                events.getEndBossArenaSpikeActiveForTest(),
                "loc_557DA should draw only non-alternate spike helpers while Events_bg+$06 is not 4");
        assertArrayEquals(new int[] {0x02FA, -1, 0x02F8, -1, 0x02F6, -1},
                events.getEndBossArenaSpikeYForTest(),
                "loc_557EC should index word_558C2 as Events_bg+$06*4 + spike tier");
    }

    @Test
    void act2EndBossArenaWrapTransfersShipSignalToSpikeDeletionFlag() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x4300, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);
        events.update(1, 1);
        events.update(1, 2);
        events.update(1, 3);
        events.update(1, 4);
        events.update(1, 5);
        events.update(1, 6);
        events.update(1, 7);
        events.applyShipControllerFrame(0x03E60000, 0);
        assertTrue(events.isShipControllerSignalFlagSet(),
                "loc_5583E should publish _unkFAA9 when the ship controller reaches its scroll-lock threshold");

        camera.setX((short) 0x427C);
        events.update(1, 8);

        assertFalse(events.isShipControllerSignalFlagSet(),
                "MHZ2 loc_55686 should clear _unkFAA9 after transferring it into Events_bg+$0A");
        assertArrayEquals(new boolean[] {false, false, false, false, false, false},
                events.getEndBossArenaSpikeActiveForTest(),
                "loc_557C8 should delete all arena spike helpers once Events_bg+$0A is nonzero");
    }

    @Test
    void act2EndBossArenaWrapOffsetsActiveLevelObjectsLikeRomSub54cf4() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x4300, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);
        events.update(1, 1);
        events.update(1, 2);
        events.update(1, 3);
        events.update(1, 4);
        events.update(1, 5);
        events.update(1, 6);
        events.update(1, 7);

        RepeatShiftProbeObject probe = new RepeatShiftProbeObject(0x4308, 0x02D0);
        GameServices.level().getObjectManager().addDynamicObject(probe);

        camera.setX((short) 0x427C);
        events.update(1, 8);

        assertEquals(0x4108, probe.getX(),
                "MHZ2 forced-scroll wrap should subtract $200 from active level-space object x_pos");
        assertEquals(0x02D0, probe.getY(),
                "MHZ2 forced-scroll wrap should leave object y_pos unchanged for d1=0");
    }

    @Test
    void act2BackgroundEventWaitsForEndBossSignalBeforeRestoreRedraw() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x18);

        events.update(1, 1);

        assertEquals(0x18, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_55424 should stay on routine $18 while Events_fg_5 is non-negative");

        events.setEndBossArenaRestoreRequested(true);
        events.update(1, 2);

        assertEquals(0x1C, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_55424 should advance Events_routine_bg to $1C once Events_fg_5 is negative");
        assertEquals(0x0180, events.getEndBossArenaDrawPosition(),
                "MHZ2 BackgroundEvent loc_55424 should prime Draw_delayed_position=$0180 for the restore redraw");
        assertEquals(0x0002, events.getEndBossArenaDrawRowCount(),
                "MHZ2 BackgroundEvent loc_55424 should prime Draw_delayed_rowcount=2 for the restore redraw");
    }

    @Test
    void act2BackgroundEventRestoreRedrawClearsForegroundRefreshAndPrimesFinalDraw() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x18);
        events.setEndBossArenaRestoreRequested(true);
        events.setEndBossArenaForegroundRefreshActiveForTest(true);

        events.update(1, 1);
        events.update(1, 2);
        events.update(1, 3);

        assertEquals(0x1C, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_5543A should stay on routine $1C until Draw_delayed_rowcount underflows");
        assertTrue(events.isEndBossArenaForegroundRefreshActive(),
                "MHZ2 BackgroundEvent loc_5543A should keep Events_bg+$01 set while the top-down redraw is still active");

        events.update(1, 4);

        assertEquals(0x20, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_5543A should advance Events_routine_bg to $20 after the restore redraw");
        assertFalse(events.isEndBossArenaForegroundRefreshActive(),
                "MHZ2 BackgroundEvent loc_5543A should clear Events_bg+$01 after the restore redraw");
        assertEquals(0x0280, events.getEndBossArenaDrawPosition(),
                "MHZ2 BackgroundEvent loc_5543A should prime Draw_delayed_position=$0280 for the final redraw");
        assertEquals(0x0002, events.getEndBossArenaDrawRowCount(),
                "MHZ2 BackgroundEvent loc_5543A should prime Draw_delayed_rowcount=2 for the final redraw");
    }

    @Test
    void act2BackgroundEventFinalRedrawClearsEndBossArenaAndForegroundRoutine() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = GameServices.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);

        events.update(1, 1);
        events.update(1, 2);
        events.update(1, 3);
        events.update(1, 4);
        events.update(1, 5);
        events.update(1, 6);
        events.update(1, 7);
        events.setEndBossArenaRestoreRequested(true);
        events.update(1, 8);
        events.update(1, 9);
        events.update(1, 10);
        events.update(1, 11);

        assertEquals(0x20, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_5545A should stay on routine $20 until Draw_delayed_rowcount underflows");
        assertTrue(events.isEndBossArenaBackgroundActive(),
                "MHZ2 BackgroundEvent loc_5545A should keep Events_bg+$00 set while the final redraw is active");

        events.update(1, 12);
        events.update(1, 13);
        events.update(1, 14);

        assertEquals(0x24, events.getAct2BackgroundRoutine(),
                "MHZ2 BackgroundEvent loc_5545A should advance Events_routine_bg to $24 after the final redraw");
        assertFalse(events.isEndBossArenaBackgroundActive(),
                "MHZ2 BackgroundEvent loc_5545A should clear Events_bg+$00 after the final redraw");
        assertEquals(0, events.getEventRoutine(),
                "MHZ2 BackgroundEvent loc_5545A should clear Events_routine_fg after the final redraw");
    }

    @Test
    void act2ShipTransitionFlagStartsRomShipSequence() throws IOException {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);

        events.update(1, 1);

        assertFalse(events.isShipTransitionFlagSet(),
                "MHZ2_ScreenEvent should clear Events_fg_4 when starting the ship sequence");
        assertEquals(0x10, events.getEventRoutine(),
                "MHZ2_ScreenEvent should fall through from routine $0C and advance to routine $10 once the ship setup completes");
        assertEquals(0x0320, events.getShipRedrawPosition(),
                "MHZ2_ScreenEvent should mirror Draw_delayed_position=$0320 for the ship redraw");
        assertEquals(0x000A, events.getShipRedrawRowCount(),
                "MHZ2_ScreenEvent should mirror Draw_delayed_rowcount=$000A for the ship redraw");
        assertTrue(events.isShipHIntActive(),
                "MHZ2_ScreenEvent should install the ship H-int handler after the redraw handoff");
        assertEquals(0x80, events.getShipHIntCounter(),
                "MHZ2_ScreenEvent should set H_int_counter=$80 for the ship effect");
        assertPaletteLineMatchesRom(
                GameServices.level().getCurrentLevel().getPalette(1),
                GameServices.rom().getRom().readBytes(Sonic3kConstants.PAL_MHZ2_SHIP_ADDR, 32),
                0);
    }

    @Test
    void act2ShipTransitionAllocatesControllerAndTwoPropellers() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);

        events.update(1, 1);

        List<MhzShipSequenceControllerInstance> controllers = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipSequenceControllerInstance.class::isInstance)
                .map(MhzShipSequenceControllerInstance.class::cast)
                .toList();
        List<MhzShipPropellerInstance> propellers = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipPropellerInstance.class::isInstance)
                .map(MhzShipPropellerInstance.class::cast)
                .toList();

        assertEquals(1, controllers.size(),
                "MHZ2 loc_54E9C should allocate one loc_5583E ship sequence controller");
        assertEquals(0x04C0, controllers.getFirst().getInitialSwingSpeed(),
                "MHZ2 loc_54E9C should initialize controller $30 to $04C0");
        assertEquals(0x4000, controllers.getFirst().getInitialShipMotion(),
                "MHZ2 loc_54E9C should initialize controller $3A to $4000");
        assertEquals(2, propellers.size(),
                "MHZ2 loc_54E9C should allocate two loc_55814 propeller sprites");
    }

    @Test
    void act2ShipPropellerArtUsesRomEndBossMiscMappingsAndAnimation() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        ObjectRenderManager renderManager = GameServices.level().getObjectRenderManager();
        ObjectSpriteSheet sheet = renderManager.getSheet(Sonic3kObjectArtKeys.MHZ_SHIP_PROPELLER);
        SpriteAnimationScript animation = renderManager
                .getAnimations(Sonic3kObjectArtKeys.MHZ_SHIP_PROPELLER)
                .getScript(0);

        assertNotNull(sheet, "MHZ2 ship propeller should load ArtKosM_MHZShipPropeller from ROM");
        assertEquals(8, sheet.getFrameCount(),
                "Map_MHZEndBossMisc should expose all eight misc frames; propellers animate frames 5-7");
        assertTrue(sheet.getPatterns().length >= 0x1B,
                "Propeller art must cover the highest tile used by frames 5-7 of Map_MHZEndBossMisc");
        assertNotNull(animation, "Ani_MHZEndPropellers should be registered with the propeller sheet");
        assertEquals(2, animation.delay(),
                "Ani_MHZEndPropellers byte_55A3E starts with delay $02");
        assertEquals(List.of(5, 6, 7), animation.frames(),
                "Ani_MHZEndPropellers should cycle the three propeller mapping frames");
    }

    @Test
    void act2ShipControllerPublishesRomMotionAndScrollLockSignal() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);
        events.update(1, 1);
        MhzShipSequenceControllerInstance controller = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipSequenceControllerInstance.class::isInstance)
                .map(MhzShipSequenceControllerInstance.class::cast)
                .findFirst()
                .orElseThrow();
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());

        controller.update(1, fixture.sprite());

        assertEquals(0x000040C0, controller.getMotionAccumulator(),
                "MHZ ship controller should start from loc_54E9C $3A=$4000, then add $C0 to object long $38 on its first frame");
        assertEquals(0x003FBF40, runtimeState.shipSecondaryBgCameraXFixed(),
                "MHZ ship controller should subtract the seeded object long $38 from _unkEE98 after the first frame");
        assertEquals(0x0085, runtimeState.shipEffectiveBgY(),
                "MHZ ship controller should publish _unkEE9C = _unkEEA2 + swing offset + 5");
        assertFalse(runtimeState.isShipScrollLockSet(),
                "MHZ ship controller should not set Scroll_lock until _unkEE98 high word reaches -$3E6");

        for (int frame = 2; frame <= 900; frame++) {
            controller.update(frame, fixture.sprite());
        }

        assertTrue(runtimeState.isShipScrollLockSet(),
                "MHZ ship controller should set Scroll_lock once _unkEE98 reaches the ROM threshold");
        assertTrue(events.isShipControllerSignalFlagSet(),
                "MHZ ship controller should set Events_fg_4 together with Scroll_lock at the ROM threshold");
    }

    @Test
    void act2ShipControllerUsesRomPostScrollLockRiseTiming() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);
        events.update(1, 1);
        MhzShipSequenceControllerInstance controller = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipSequenceControllerInstance.class::isInstance)
                .map(MhzShipSequenceControllerInstance.class::cast)
                .findFirst()
                .orElseThrow();
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());

        for (int frame = 1; frame <= 770; frame++) {
            controller.update(frame, fixture.sprite());
        }

        assertTrue(runtimeState.isShipControllerSignalFlagSet(),
                "loc_5583E should set Events_fg_4 when seeded _unkEE98 reaches the scroll-lock threshold");
        assertEquals(0x0085, runtimeState.shipEffectiveBgY(),
                "the $3A=$4000 seed makes loc_5583E reach the scroll-lock threshold as the swing returns to center");

        controller.update(771, fixture.sprite());
        assertEquals(0x0085, runtimeState.shipEffectiveBgY(),
                "the ROM still holds the centered offset on the next non-fourth frame");

        controller.update(772, fixture.sprite());
        assertEquals(0x0085, runtimeState.shipEffectiveBgY(),
                "loc_5583E increments $32 after using it when Level_frame_counter&3 == 0");

        controller.update(773, fixture.sprite());
        assertEquals(0x0086, runtimeState.shipEffectiveBgY(),
                "the one-pixel post-lock rise becomes visible on the following frame");
    }

    @Test
    void act2ShipActiveRoutinePublishesRomHScrollAndPropellerOffsets() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);
        events.update(1, 1);
        MhzShipSequenceControllerInstance controller = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipSequenceControllerInstance.class::isInstance)
                .map(MhzShipSequenceControllerInstance.class::cast)
                .findFirst()
                .orElseThrow();
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        fixture.camera().setX((short) 0x3C90);
        controller.update(1, fixture.sprite());

        events.update(1, 2);

        assertEquals(0x3C90, runtimeState.shipHScrollCameraCopy(),
                "MHZ2 sub_54F8C should mirror Camera_X_pos_copy into HScroll_table+$008");
        assertEquals(0x3EAF, runtimeState.shipPrimaryHScroll(),
                "MHZ2 sub_54F8C should publish Camera_X_pos_copy+$1E0+_unkEE98.w into HScroll_table");
        assertEquals(0x0065, runtimeState.shipPropellerOneX(),
                "MHZ2 sub_54FEC should derive propeller one X from $46B8 minus ship primary H-scroll");
        assertEquals(0x0165, runtimeState.shipPropellerTwoX(),
                "MHZ2 sub_54FEC should derive propeller two X from $45B8 minus ship primary H-scroll");
        assertEquals(0x00D3, runtimeState.shipPropellerY(),
                "MHZ2 sub_54F8C should publish -_unkEE9C+$158 for both propeller Y offsets");
    }

    @Test
    void act2ShipActiveRoutineAppliesScreenShakeOffsetToCameraCopyBeforeHScroll() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);
        events.update(1, 1);
        MhzShipSequenceControllerInstance controller = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipSequenceControllerInstance.class::isInstance)
                .map(MhzShipSequenceControllerInstance.class::cast)
                .findFirst()
                .orElseThrow();
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        fixture.camera().setX((short) 0x3C90);
        controller.update(1, fixture.sprite());
        events.setScreenShakeOffsetForTest(4);

        events.update(1, 2);

        assertEquals(0x3C94, runtimeState.shipHScrollCameraCopy(),
                "MHZ2_ScreenEvent should add Screen_shake_offset to Camera_X_pos_copy before sub_54F8C");
        assertEquals(0x3EB3, runtimeState.shipPrimaryHScroll(),
                "sub_54F8C should use the shaken Camera_X_pos_copy for the primary ship H-scroll word");
        assertEquals(0x0061, runtimeState.shipPropellerOneX(),
                "sub_54FEC should derive propeller one X from the shaken ship H-scroll word");
        assertEquals(0x0161, runtimeState.shipPropellerTwoX(),
                "sub_54FEC should derive propeller two X from the shaken ship H-scroll word");
    }

    @Test
    void act2ShipActiveRoutineCarriesObjectControlledPlayerByShipScrollDelta() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);
        events.update(1, 1);
        fixture.camera().setX((short) 0x3C90);
        events.applyShipControllerFrame(0x00C0, 0);
        events.update(1, 2);
        fixture.sprite().setCentreX((short) 0x4000);
        fixture.sprite().setCentreY((short) 0x0300);
        ObjectControlState.nativeBit7FullControl().applyTo(fixture.sprite());

        events.applyShipControllerFrame(0x00010000, 4);
        events.update(1, 3);

        assertEquals(0x4001, fixture.sprite().getCentreX() & 0xFFFF,
                "sub_54F8C should add old HScroll_table minus new ship H-scroll to object_control=$81 player x_pos");
        assertEquals(0x02FC, fixture.sprite().getCentreY() & 0xFFFF,
                "sub_54F8C should add old Events_bg+$0C minus new _unkEE9C to object_control=$81 player y_pos");
    }

    @Test
    void act2ShipScrollOverridesTopHIntRegionWithShipHScrollWord() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);
        events.update(1, 1);
        MhzShipSequenceControllerInstance controller = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipSequenceControllerInstance.class::isInstance)
                .map(MhzShipSequenceControllerInstance.class::cast)
                .findFirst()
                .orElseThrow();
        fixture.camera().setX((short) 0x3C90);
        controller.update(1, fixture.sprite());
        events.update(1, 2);
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        int[] hScroll = new int[M68KMath.VISIBLE_LINES];

        new SwScrlMhz().update(hScroll, 0x3C90, 0x0280, 2, 1);

        assertEquals(negWord(runtimeState.shipPrimaryHScroll()), unpackFG(hScroll[0]),
                "MHZ2 sub_5550C writes -HScroll_table into the first word of the H-int region");
        assertEquals(negWord(runtimeState.shipPrimaryHScroll()), unpackFG(hScroll[127]),
                "the ROM loop writes 128 scanlines for the ship H-int region");
        assertEquals(negWord(0x3C90), unpackFG(hScroll[128]),
                "lines below the H-int region should keep PlainDeformation's foreground camera scroll");
        assertEquals(unpackBG(hScroll[128]), unpackBG(hScroll[0]),
                "sub_5550C only replaces the first H-scroll word; BG keeps MHZ_Deform's normal value");
    }

    @Test
    void act2EndBossArenaScrollOverridesTopRegionWithBossBgScroll() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x10);
        fixture.camera().setX((short) 0x3F00);
        events.update(1, 1);
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        int[] hScroll = new int[M68KMath.VISIBLE_LINES];

        new SwScrlMhz().update(hScroll, 0x3F00, 0x0500, 1, 1);

        assertTrue(events.isEndBossArenaBackgroundActive(),
                "MHZ2 BackgroundEvent loc_55312 should set Events_bg+$00 at Camera_X_pos $3F00");
        assertEquals(negWord(runtimeState.publishedBgCameraX()), unpackFG(hScroll[0]),
                "sub_5550C loc_55534 writes -Camera_X_pos_BG_copy into the top foreground H-scroll word");
        assertEquals(negWord(runtimeState.publishedBgCameraX()), unpackFG(hScroll[47]),
                "the Events_bg+$00 loop covers the first 48 scanlines before the pillar foreground ramp");
        assertEquals(negWord(0x3F00), unpackFG(hScroll[48]),
                "scanlines below the Events_bg+$00 region should keep PlainDeformation's foreground scroll");
        assertEquals(unpackBG(hScroll[48]), unpackBG(hScroll[0]),
                "loc_55534 only replaces the first H-scroll word; BG keeps MHZ_Deform's normal value");
    }

    @Test
    void act2EndBossArenaForegroundRefreshStartsPillarHScrollRampAtCameraCopyMinus80() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setAct2BackgroundRoutineForTest(0x10);
        fixture.camera().setX((short) 0x3F00);
        events.update(1, 1);
        events.setEndBossArenaForegroundRefreshActiveForTest(true);
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        int[] hScroll = new int[M68KMath.VISIBLE_LINES];

        new SwScrlMhz().update(hScroll, 0x4080, 0x0500, 1, 1);

        assertTrue(events.isEndBossArenaForegroundRefreshActive(),
                "MHZ2 BackgroundEvent loc_55380 should set Events_bg+$01 for the pillar H-scroll ramp");
        assertEquals(negWord(0x4080 - 0x80), unpackBG(hScroll[0]),
                "sub_5550C loc_555A4 writes -(Camera_X_pos_copy-$80) to the first pillar ramp BG word");
        assertEquals(expectedMhzEndBossPillarRampBg(0x4080, 0x4080, 47), unpackBG(hScroll[47]),
                "sub_5550C loc_555B8 should advance the pillar BG H-scroll word by the ROM 16.16 step");
        assertEquals(negWord(runtimeState.publishedBgCameraX()), unpackFG(hScroll[0]),
                "Events_bg+$01 should not replace the foreground word written by the Events_bg+$00 top-region override");
        assertEquals(negWord(0x4080), unpackFG(hScroll[48]),
                "line 48 sits below the 48-line pillar ramp and keeps PlainDeformation's foreground scroll");
    }

    @Test
    void act2EndBossArenaTallSupportXTracksPillarHScrollTableSlot() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3F00, (short) 0x0500)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        Camera camera = fixture.camera();
        events.setEventRoutine(0x10);
        events.setAct2BackgroundRoutineForTest(0x10);
        camera.setX((short) 0x3F00);
        for (int frame = 1; frame <= 7; frame++) {
            events.update(1, frame);
        }
        MhzEndBossArenaHelperInstance tallSupport = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzEndBossArenaHelperInstance.class::isInstance)
                .map(MhzEndBossArenaHelperInstance.class::cast)
                .filter(helper -> helper.getRole() == MhzEndBossArenaHelperInstance.Role.TALL_SUPPORT)
                .findFirst()
                .orElseThrow();
        int[] hScroll = new int[M68KMath.VISIBLE_LINES];

        new SwScrlMhz().update(hScroll, 0x4080, 0x0500, 1, 1);
        tallSupport.update(1, fixture.sprite());

        assertEquals(expectedMhzEndBossArenaHelperX(0x4080, 47), tallSupport.getX(),
                "sub_5550C loc_555D2 should derive the tall support x_pos from H_scroll_buffer+$BE");
    }

    @Test
    void act2ShipPropellerObjectsFollowRuntimeOffsetsAndRomAnimation() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                .startPosition((short) 0x3C90, (short) 0x0280)
                .startPositionIsCentre()
                .build();

        Sonic3kMHZEvents events = getMhzEvents();
        events.setShipTransitionFlag(true);
        events.update(1, 1);
        MhzShipSequenceControllerInstance controller = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipSequenceControllerInstance.class::isInstance)
                .map(MhzShipSequenceControllerInstance.class::cast)
                .findFirst()
                .orElseThrow();
        List<MhzShipPropellerInstance> propellers = GameServices.level().getObjectManager()
                .getActiveObjects()
                .stream()
                .filter(MhzShipPropellerInstance.class::isInstance)
                .map(MhzShipPropellerInstance.class::cast)
                .sorted(Comparator.comparingInt(MhzShipPropellerInstance::getPropellerIndex))
                .toList();
        MhzZoneRuntimeState runtimeState =
                assertInstanceOf(MhzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());

        fixture.camera().setX((short) 0x3C90);
        controller.update(1, fixture.sprite());
        events.update(1, 2);
        propellers.forEach(propeller -> propeller.update(2, fixture.sprite()));

        assertEquals(2, propellers.size(),
                "MHZ2 ship sequence should keep two distinct propeller objects active");
        assertEquals(0, propellers.get(0).getPropellerIndex(),
                "first loc_55814 allocation should track the first propeller offset");
        assertEquals(1, propellers.get(1).getPropellerIndex(),
                "second loc_55814 allocation should track the second propeller offset");
        assertEquals(runtimeState.shipPropellerOneX(), propellers.get(0).getX(),
                "first propeller should mirror the ROM sub_54FEC X offset");
        assertEquals(runtimeState.shipPropellerTwoX(), propellers.get(1).getX(),
                "second propeller should mirror the ROM sub_54FEC X offset");
        assertEquals(runtimeState.shipPropellerY(), propellers.get(0).getY(),
                "first propeller should mirror the ROM sub_54F8C Y offset");
        assertEquals(runtimeState.shipPropellerY(), propellers.get(1).getY(),
                "second propeller should mirror the ROM sub_54F8C Y offset");
        assertEquals(5, propellers.get(0).getMappingFrame(),
                "loc_55814 should start Ani_MHZEndPropellers on mapping frame 5");
        assertEquals(5, propellers.get(1).getMappingFrame(),
                "both propellers run the same Ani_MHZEndPropellers script");
    }

    private static Sonic3kMHZEvents getMhzEvents() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kMHZEvents events = manager.getMhzEvents();
        assertNotNull(events, "MHZ events should be installed for MHZ levels");
        return events;
    }

    private static int[] firstCustomLayoutDifference(com.openggf.level.Map map) throws IOException {
        Rom rom = GameServices.rom().getRom();
        byte[] customLayout = rom.readBytes(Sonic3kConstants.MHZ_CUSTOM_LAYOUT_ADDR,
                Sonic3kConstants.LEVEL_LAYOUT_TOTAL_SIZE);
        int fgCols = segaWord(customLayout, 0);
        int bgCols = segaWord(customLayout, 2);
        int fgRows = segaWord(customLayout, 4);
        int bgRows = segaWord(customLayout, 6);

        int[] fg = firstCustomLayoutDifferenceInLayer(map, customLayout, 0, 8, fgCols, fgRows);
        if (fg != null) {
            return fg;
        }
        int[] bg = firstCustomLayoutDifferenceInLayer(map, customLayout, 1, 10, bgCols, bgRows);
        if (bg != null) {
            return bg;
        }
        throw new AssertionError("MHZ_Custom_Layout should differ from the normal MHZ2 layout in at least one cell");
    }

    private static int[] firstCustomLayoutDifferenceInLayer(com.openggf.level.Map map, byte[] layoutData,
                                                            int layer, int rowPtrOffset, int cols, int rows) {
        int maxRows = Math.min(rows, map.getHeight());
        int maxCols = Math.min(cols, map.getWidth());
        for (int row = 0; row < maxRows; row++) {
            int ptrPos = rowPtrOffset + row * 4;
            if (ptrPos + 1 >= layoutData.length) {
                break;
            }
            int rowDataAddr = decodeLayoutRowOffset(segaWord(layoutData, ptrPos));
            if (rowDataAddr < 0 || rowDataAddr >= layoutData.length) {
                continue;
            }
            for (int col = 0; col < maxCols; col++) {
                int srcIdx = rowDataAddr + col;
                if (srcIdx >= layoutData.length) {
                    break;
                }
                int current = map.getValue(layer, col, row) & 0xFF;
                int expected = layoutData[srcIdx] & 0xFF;
                if (current != expected) {
                    return new int[] {layer, col, row, current, expected};
                }
            }
        }
        return null;
    }

    private static int decodeLayoutRowOffset(int rowPointerWord) {
        int pointer = rowPointerWord & Sonic3kConstants.LEVEL_LAYOUT_ROW_POINTER_MASK;
        if (pointer == 0) {
            return -1;
        }
        if (pointer >= Sonic3kConstants.LEVEL_LAYOUT_RAM_BASE) {
            return pointer - Sonic3kConstants.LEVEL_LAYOUT_RAM_BASE;
        }
        return pointer;
    }

    private static boolean chunkMatchesRawWords(Level level, int chunkIndex, byte[] rawChunkData) {
        com.openggf.level.Chunk chunk = level.getChunk(chunkIndex);
        return (chunk.getPatternDesc(0, 0).get() == segaWord(rawChunkData, 0))
                && (chunk.getPatternDesc(1, 0).get() == segaWord(rawChunkData, 2))
                && (chunk.getPatternDesc(0, 1).get() == segaWord(rawChunkData, 4))
                && (chunk.getPatternDesc(1, 1).get() == segaWord(rawChunkData, 6));
    }

    private static boolean blockMatchesRawWords(Level level, int blockIndex, byte[] rawBlockData) {
        com.openggf.level.Block block = level.getBlock(blockIndex);
        for (int y = 0; y < block.getGridSide(); y++) {
            for (int x = 0; x < block.getGridSide(); x++) {
                int offset = (y * block.getGridSide() + x) * 2;
                if (block.getChunkDesc(x, y).get() != segaWord(rawBlockData, offset)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void assertChunkMatchesRawWords(Level level, int chunkIndex, byte[] rawChunkData, String message) {
        assertTrue(chunkMatchesRawWords(level, chunkIndex, rawChunkData), message);
    }

    private static void assertBlockMatchesRawWords(Level level, int blockIndex, byte[] rawBlockData, String message) {
        assertTrue(blockMatchesRawWords(level, blockIndex, rawBlockData), message);
    }

    private static Pattern segaPattern(byte[] rawPatternData, int offset) {
        Pattern pattern = new Pattern();
        pattern.fromSegaFormat(Arrays.copyOfRange(rawPatternData, offset, offset + Pattern.PATTERN_SIZE_IN_ROM));
        return pattern;
    }

    private static boolean patternPixelsMatch(Pattern actual, Pattern expected) {
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                if (actual.getPixel(x, y) != expected.getPixel(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void assertPatternPixelsMatch(Pattern actual, Pattern expected, String message) {
        assertTrue(patternPixelsMatch(actual, expected), message);
    }

    private static void assertPaletteBlockMatchesRom(int romAddr) throws IOException {
        Rom rom = GameServices.rom().getRom();
        Level level = GameServices.level().getCurrentLevel();
        byte[] expected = rom.readBytes(romAddr, 64);
        assertPaletteLineMatchesRom(level.getPalette(2), expected, 0);
        assertPaletteLineMatchesRom(level.getPalette(3), expected, 32);
    }

    private static void assertPaletteLineMatchesRom(Palette palette, byte[] expected, int offset) {
        assertColorWord(palette, 0, segaWord(expected, offset));
        assertColorWord(palette, 1, segaWord(expected, offset + 2));
        assertColorWord(palette, 15, segaWord(expected, offset + 30));
    }

    private static int segaWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF);
    }

    private static short expectedMhzEndBossPillarRampBg(int cameraX, int cameraXCopy, int line) {
        int step = expectedMhzEndBossPillarRampStep(cameraX);
        int accumulator = (negWord(cameraXCopy - 0x80) & 0xFFFF) << 16;
        for (int i = 0; i < line; i++) {
            accumulator += step;
        }
        return (short) (accumulator >> 16);
    }

    private static int expectedMhzEndBossArenaHelperX(int cameraX, int hScrollLine) {
        int hScrollWord = expectedMhzEndBossPillarRampBg(cameraX, cameraX, hScrollLine);
        return ((hScrollWord - 0x48) & 0x1FF) + cameraX;
    }

    private static int expectedMhzEndBossPillarRampStep(int cameraX) {
        int distance = (short) (cameraX - 0x4180);
        int scaled = distance * 0x5600;
        scaled += scaled;
        int scaledSwapped = swapWords(scaled);
        int deltaWord = (short) (distance - (short) scaledSwapped);
        deltaWord = (short) (deltaWord - 0x18);
        int magnitude = deltaWord << 16;
        boolean negative = magnitude < 0;
        if (negative) {
            magnitude = -magnitude;
        }
        int highQuotient = ((magnitude >>> 16) & 0xFFFF) / 0x30;
        int lowQuotient = (magnitude & 0xFFFF) / 0x30;
        int step = (highQuotient << 16) | lowQuotient;
        return negative ? -step : step;
    }

    private static int swapWords(int value) {
        return (value << 16) | ((value >>> 16) & 0xFFFF);
    }

    private static final class RepeatShiftProbeObject extends AbstractObjectInstance {
        private RepeatShiftProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0x00, 0, 0, false, 0), "RepeatShiftProbe");
        }

        @Override
        public boolean participatesInLevelRepeatOffset() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class SpawnlessDynamicProbeObject extends AbstractObjectInstance {
        private SpawnlessDynamicProbeObject() {
            super(null, "SpawnlessDynamicProbe");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
