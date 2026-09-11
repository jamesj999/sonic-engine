package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kFadeOutPsgSilence {
    private Rom rom;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedFadeClearsPresentationStateAndCannotRestoreAnAbandonedSong(boolean override) {
        AudioManager audio = install();
        audio.playMusic(Sonic3kMusic.KNUCKLES.id);
        for (int i = 0; i < 60; i++) audio.presentFrame(PresentationMode.FORWARD);
        if (override) {
            audio.playMusic(Sonic3kMusic.EXTRA_LIFE.id);
            audio.presentFrame(PresentationMode.FORWARD);
            assertFalse(audio.captureLogicalSnapshot().presentation().overrideStack().isEmpty());
        }
        audio.setSpeedMultiplier(8);
        audio.presentFrame(PresentationMode.FORWARD);
        // Short command parameters isolate terminal stop from the jingle's
        // later coordination-flag restore request.
        audio.fadeOutMusic(3, 1);
        for (int i = 0; i < 8; i++) audio.presentFrame(PresentationMode.FORWARD);

        var stopped = audio.captureLogicalSnapshot();
        // zDoMusicFadeOut jumps to zStopAllSound at zero, clearing its backup
        // area and tempo controls as well as the active tracks.
        assertNull(stopped.presentation().activeMusic());
        assertTrue(stopped.presentation().overrideStack().isEmpty());
        assertEquals(1, stopped.presentation().speedMultiplier());
        assertFalse(stopped.presentation().speedShoesEnabled());
        assertTrue(audio.shadowSmpsDriverSnapshotForTesting().sequencers().isEmpty());

        audio.playMusic(Sonic3kMusic.AIZ1.id);
        audio.presentFrame(PresentationMode.FORWARD);
        audio.restoreLogicalSnapshot(stopped);
        for (int i = 0; i < 4; i++) audio.presentFrame(PresentationMode.FORWARD);
        assertNull(audio.captureLogicalSnapshot().presentation().activeMusic());
        assertTrue(audio.shadowSmpsDriverSnapshotForTesting().sequencers().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyHeadlessBackendUsesTheSameHostFadeEffects(boolean withSfx) {
        install();
        var profile = new Sonic3kAudioProfile();
        var loader = profile.createSmpsLoader(rom);
        var backend = new HeadlessSmpsAudioBackend(
                com.openggf.configuration.SonicConfigurationService.getInstance(), null);
        List<Integer> writes = new ArrayList<>();
        backend.setAudioProfile(profile);
        backend.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) { }
            @Override public void onPsgWrite(int value) { writes.add(value); }
        });
        try {
            backend.playSmps(loader.loadMusic(Sonic3kMusic.KNUCKLES.id), loader.loadDacData());
            if (withSfx) {
                backend.playSfxSmps(loader.loadSfx(
                        com.openggf.game.sonic3k.audio.Sonic3kSfx.SPLASH.id), loader.loadDacData());
                assertTrue(backend.musicDriverForTesting().captureSnapshot().sequencers()
                        .stream().anyMatch(e -> e.sfx()));
            }
            writes.clear();
            backend.fadeOutMusic(0x28, 6);
            assertEquals(List.of(0x9f, 0xbf, 0xdf, 0xff), writes);
            assertTrue(backend.musicDriverForTesting().captureSnapshot().driverOwnedFade());
            assertEquals(withSfx, backend.musicDriverForTesting().captureSnapshot().sequencers()
                    .stream().anyMatch(e -> e.sfx()), "S3K fade does not stop active SFX");
        } finally {
            backend.destroy();
        }
    }

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        if (rom != null) rom.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {60, 120, 180, 240, 300, 360})
    void fadeSilencesSoundingPsgAtEntryRatherThanFreezingItsLastTone(int start) {
        AudioManager audio = install();
        int[] volumes = {15, 15, 15, 15};
        List<Integer> writes = new ArrayList<>();
        audio.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) { }
            @Override public void onPsgWrite(int value) {
                writes.add(value);
                if ((value & 0x90) == 0x90) volumes[(value >> 5) & 3] = value & 15;
            }
        });
        audio.playMusic(Sonic3kMusic.KNUCKLES.id);
        for (int i = 0; i < start; i++) audio.presentFrame(PresentationMode.FORWARD);
        assertTrue(Arrays.stream(volumes).anyMatch(v -> v < 15), "control must contain a sounding PSG channel");
        writes.clear();

        audio.fadeOutMusic();
        audio.presentFrame(PresentationMode.FORWARD);

        // zFadeOutMusic falls through zHaltDACPSG into zPSGSilenceAll;
        // the four physical volume latches are unconditional (Z80 driver:2307-2325).
        assertEquals(List.of(0x9f, 0xbf, 0xdf, 0xff), writes);
        assertArrayEquals(new int[]{15, 15, 15, 15}, volumes);
        var music = audio.shadowSmpsDriverSnapshotForTesting().sequencers().stream()
                .filter(e -> !e.sfx()).findFirst().orElseThrow();
        assertTrue(music.snapshot().tracks().stream()
                .filter(t -> t.type() == SmpsSequencer.TrackType.PSG).noneMatch(t -> t.active()));
        assertTrue(music.snapshot().tracks().stream()
                .anyMatch(t -> t.type() == SmpsSequencer.TrackType.FM && t.active()),
                "FM continues its fade; this is not a global stop");
        writes.clear();
        for (int i = 0; i < 119; i++) audio.presentFrame(PresentationMode.FORWARD);
        assertTrue(writes.isEmpty(), "halted PSG tracks must not restart during the fade");
    }

    private AudioManager install() {
        rom = new Rom();
        assertTrue(rom.open(RomTestUtils.ensureSonic3kRomAvailable().getAbsolutePath()));
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        Sonic3kAudioProfile profile = new Sonic3kAudioProfile();
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        return audio;
    }
}
