package com.openggf.game.sonic2;

import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.RomTestUtils;
import com.openggf.data.Rom;
import com.openggf.level.LevelData;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.sonic2.timing.Sonic2PreBgmTimingModel;
import com.openggf.tools.audio.completerun.s2.S2ProductionRequestProjector;

import com.openggf.game.InitStep;
import com.openggf.game.StaticFixup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TestSonic2LevelInitProfile {
    private Rom audioRom;

    private final Sonic2LevelInitProfile profile =
            new Sonic2LevelInitProfile(new Sonic2LevelEventManager());

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
        if (audioRom != null) {
            audioRom.close();
        }
    }

    @Test
    public void teardownStepsMatchExpected14Steps() {
        List<InitStep> steps = profile.levelTeardownSteps();

        // 13 original teardown steps + 1 DebugOverlayManager reset = 14 total
        assertEquals(14, steps.size());

        // Verify ordering matches ROM teardown phases
        assertEquals("ResetAudio", steps.get(0).name());
        assertEquals("ResetCrossGameFeatures", steps.get(1).name());
        assertEquals("ResetS2LevelEvents", steps.get(2).name());
        assertEquals("ResetParallax", steps.get(3).name());
        assertEquals("ResetLevelManager", steps.get(4).name());
        assertEquals("ResetSprites", steps.get(5).name());
        assertEquals("ResetCollision", steps.get(6).name());
        assertEquals("ResetCamera", steps.get(7).name());
        assertEquals("ResetGraphics", steps.get(8).name());
        assertEquals("ResetFade", steps.get(9).name());
        assertEquals("ResetGameState", steps.get(10).name());
        assertEquals("ResetTimers", steps.get(11).name());
        assertEquals("ResetWater", steps.get(12).name());
        assertEquals("ResetDebugOverlay", steps.get(13).name());
    }

    @Test
    public void perTestResetOmitsAudioLevelManagerAndGraphics() {
        List<InitStep> steps = profile.perTestResetSteps();

        // Per-test reset: 12 operations (no audio, no level manager, no graphics).
        // ResetWater is followed by ReloadWater because per-test reset reuses
        // the loaded Level rather than running the full InitWater load step.
        assertEquals(12, steps.size());

        assertEquals("ResetS2LevelEvents", steps.get(0).name());
        assertEquals("ResetCrossGameFeatures", steps.get(1).name());
        assertEquals("ResetParallax", steps.get(2).name());
        assertEquals("ResetSprites", steps.get(3).name());
        assertEquals("ResetCollision", steps.get(4).name());
        assertEquals("ResetCamera", steps.get(5).name());
        assertEquals("ResetFade", steps.get(6).name());
        assertEquals("ResetGameState", steps.get(7).name());
        assertEquals("ResetTimers", steps.get(8).name());
        assertEquals("ResetWater", steps.get(9).name());
        assertEquals("ReloadWater", steps.get(10).name());
        assertEquals("ResetDebugOverlay", steps.get(11).name());
    }

    @Test
    public void postTeardownFixupsContainGroundSensorWiring() {
        List<StaticFixup> fixups = profile.postTeardownFixups();

        // GroundSensor no longer needs wiring â€” it resolves the active runtime level directly.
        assertEquals(0, fixups.size());
    }

    @Test
    public void levelLoadStepsContainsInitialPlcStepWithoutPostLoad() {
        List<InitStep> steps = profile.levelLoadSteps(new com.openggf.game.LevelLoadContext());
        assertEquals(16, steps.size());
        assertEquals("InitGameModule", steps.get(0).name());
        assertEquals("ConfigureAudio", steps.get(1).name());
        assertEquals("QueueLevelEntryFadeOut", steps.get(2).name());
        assertEquals("ScheduleLevelMusic", steps.get(3).name(),
                "the timing model must capture prior live state before load mutations");
        assertEquals("LoadLevelData", steps.get(4).name());
        assertEquals("InitBackgroundRenderer", steps.get(15).name());
    }

    @Test
    public void scheduledLevelMusicResolvesTheRomZoneNotTheProgressionIndex()
            throws Exception {
        // Chemical Plant is progression index 1 in Sonic2ZoneRegistry but ROM
        // zone 0x0D. ScheduleLevelMusic must resolve the timing model with the
        // ROM pair, because the model indexes the ROM level-data directory and
        // branches on Current_Zone/Current_Act. Feeding it the progression
        // index reads another zone's PLC and title-card data and silently
        // drops the CPZ act 2 water arm.
        java.io.File romFile = RomTestUtils.ensureSonic2RomAvailable();
        org.junit.jupiter.api.Assumptions.assumeTrue(romFile != null,
                "Sonic 2 REV01 ROM not available — skipping test");
        audioRom = new Rom();
        assertTrue(audioRom.open(romFile.getAbsolutePath()));

        int cpz2LevelIndex = LevelData.CHEMICAL_PLANT_2.getLevelIndex();
        int entry = Sonic2Constants.LEVEL_SELECT_ADDR + cpz2LevelIndex * 2;
        int romZoneId = Byte.toUnsignedInt(audioRom.readByte(entry));
        int romActId = Byte.toUnsignedInt(audioRom.readByte(entry + 1));
        assertEquals(Sonic2ZoneConstants.ROM_ZONE_CPZ, romZoneId,
                "the ROM LevelOrder table must supply CPZ's ROM zone id");
        assertEquals(1, romActId);
        assertNotEquals(romZoneId, 1,
                "the ROM zone id and the progression index must not coincide, "
                        + "otherwise this test cannot detect the confusion");

        var resolved = assertInstanceOf(Sonic2PreBgmTimingModel.Resolved.class,
                Sonic2PreBgmTimingModel.resolve(audioRom, romZoneId, romActId,
                        java.util.OptionalInt.empty(),
                        Sonic2PreBgmTimingModel.Region.NTSC, false));
        assertEquals(Sonic2PreBgmTimingModel.WaterPath.CPZ,
                resolved.evidence().levelEntry().waterPath(),
                "CPZ act 2 must select the underwater arm");

        // The same call with the progression index resolves a different zone's
        // work, so the confusion is silent rather than an error. The terminal
        // bucket is a whole row and can coincide by chance, so assert the
        // modelled work instead of the countdown it happens to land on.
        var wrong = assertInstanceOf(Sonic2PreBgmTimingModel.Resolved.class,
                Sonic2PreBgmTimingModel.resolve(audioRom, 1, romActId,
                        java.util.OptionalInt.empty(),
                        Sonic2PreBgmTimingModel.Region.NTSC, false));
        assertEquals(Sonic2PreBgmTimingModel.WaterPath.NONE,
                wrong.evidence().levelEntry().waterPath(),
                "the progression index silently drops CPZ's underwater arm");
        assertNotEquals(
                resolved.evidence().levelEntry().plcCalls(),
                wrong.evidence().levelEntry().plcCalls(),
                "the progression index walks another zone's PLC queue");
    }

    @Test
    public void levelEntryTransfersFadeOutThroughPlaySoundBeforeLaterRequests() {
        S2ProductionRequestProjector projector = new S2ProductionRequestProjector();
        Sonic2AudioProfile audioProfile = new Sonic2AudioProfile(projector);
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audioRom = new Rom();
        assertTrue(audioRom.open(requireSonic2Rom().getAbsolutePath()));
        audio.setRom(audioRom);
        audio.setAudioProfile(audioProfile);
        audio.setSoundMap(audioProfile.getSoundMap());

        List<InitStep> steps = profile.levelLoadSteps(new LevelLoadContext());
        int entryRequestIndex = stepIndex(steps, "QueueLevelEntryFadeOut");
        assertTrue(stepIndex(steps, "ConfigureAudio") < entryRequestIndex,
                "the production request service must exist before PlaySound writes SFX0");
        assertTrue(entryRequestIndex < stepIndex(steps, "ScheduleLevelMusic"),
                "Level-entry fade-out precedes the later level-music request");
        assertTrue(entryRequestIndex < stepIndex(steps, "QueueInitialPlcs"),
                "Level-entry PlaySound must precede ClearPLC and the initial PLC loads");

        steps.get(entryRequestIndex).execute();
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(1, projector.requests().size());
        assertEquals(0xF9, projector.requests().getFirst().nativeId());
        assertEquals(0, projector.requests().getFirst().queueSlot(),
                "PlaySound writes Sound_Queue.SFX0");

        steps.get(entryRequestIndex).execute();
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(1, projector.requests().size(),
                "the same level-entry boundary publishes F9 exactly once");

        assertTrue(audio.playSfx(0xA0));
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(List.of(0xF9, 0xA0), projector.requests().stream()
                .map(request -> request.nativeId()).toList(),
                "the route-independent Level entry request must stay ahead of later SFX");
    }

    private static int stepIndex(List<InitStep> steps, String name) {
        for (int i = 0; i < steps.size(); i++) {
            if (name.equals(steps.get(i).name())) {
                return i;
            }
        }
        fail("Missing level-load step: " + name);
        return -1;
    }

    @Test
    public void levelLoadStepsContains20Steps() {
        com.openggf.game.LevelLoadContext ctx = new com.openggf.game.LevelLoadContext();
        ctx.setIncludePostLoadAssembly(true);
        List<InitStep> steps = profile.levelLoadSteps(ctx);

        assertEquals(23, steps.size());

        // Original 12 ROM-aligned resource loading steps
        // (InitObjectManager + InitCameraBounds merged into InitObjectSystem)
        assertEquals("InitGameModule", steps.get(0).name());
        assertEquals("ConfigureAudio", steps.get(1).name());
        assertEquals("QueueLevelEntryFadeOut", steps.get(2).name());
        assertEquals("ScheduleLevelMusic", steps.get(3).name());
        assertEquals("LoadLevelData", steps.get(4).name());
        assertEquals("QueueInitialPlcs", steps.get(5).name());
        assertTrue(steps.stream().anyMatch(s -> s.name().equals("QueueInitialPlcs")),
                "S2 Level must queue its ROM primary, Std2, and selected life PLC before title-card admission");
        // Level_ClrRam zeroes RNG_seed after the level's LoadPLC calls
        // (docs/s2disasm/s2.asm:4802-4809).
        assertEquals("ResetRng", steps.get(6).name());
        assertEquals("InitAnimatedContent", steps.get(7).name());
        assertEquals("InitObjectSystem", steps.get(8).name());
        assertEquals("InitGameplayState", steps.get(9).name());
        assertEquals("InitRings", steps.get(10).name());
        assertEquals("InitZoneFeatures", steps.get(11).name());
        assertEquals("InitArt", steps.get(12).name());
        assertEquals("InitPlayerAndCheckpoint", steps.get(13).name());
        assertEquals("InitWater", steps.get(14).name());
        assertEquals("InitBackgroundRenderer", steps.get(15).name());

        // 7 post-load assembly steps (14-20)
        assertEquals("RestoreCheckpoint", steps.get(16).name());
        assertEquals("SpawnPlayer", steps.get(17).name());
        assertEquals("ResetPlayerState", steps.get(18).name());
        assertEquals("InitCamera", steps.get(19).name());
        assertEquals("InitLevelEvents", steps.get(20).name());
        assertEquals("SpawnSidekick", steps.get(21).name());
        assertEquals("RequestTitleCard", steps.get(22).name());
    }

    @Test
    public void teardownStepsAreImmutable() {
        try {
            profile.levelTeardownSteps().add(null);
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void levelLoadStepsAreImmutable() {
        try {
            com.openggf.game.LevelLoadContext ctx = new com.openggf.game.LevelLoadContext();
            ctx.setIncludePostLoadAssembly(true);
            profile.levelLoadSteps(ctx).add(null);
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    private static java.io.File requireSonic2Rom() {
        java.io.File romFile = RomTestUtils.ensureSonic2RomAvailable();
        org.junit.jupiter.api.Assumptions.assumeTrue(romFile != null,
                "Sonic 2 REV01 ROM not available — skipping test");
        return romFile;
    }
}
