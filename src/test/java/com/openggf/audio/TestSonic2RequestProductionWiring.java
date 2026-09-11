package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2SoundRequestPipeline;
import com.openggf.game.sonic2.audio.Sonic2SoundRequestService;
import com.openggf.data.Rom;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpawn;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2RequestProductionWiring {
    private Rom rom;

    @Test
    void placedRingAcquisitionUsesSecondaryMailboxWhileOrdinaryRawB5UsesPrimary() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null,
                "Sonic 2 REV01 ROM is required for production request resolution");
        rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));
        audio.setRom(rom);
        List<Sonic2SoundRequestService.Event> observed = new ArrayList<>();
        Sonic2AudioProfile profile = new Sonic2AudioProfile(observed::add);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        RingSpawn spawn = new RingSpawn(100, 100);
        RingManager rings = new RingManager(List.of(spawn), null, null, null, audio);
        rings.reset(0);

        assertTrue(rings.collectPlacedRing(spawn,
                new Sonic("sonic", (short) 100, (short) 100), 0));
        audio.presentFrame(PresentationMode.FORWARD);

        Sonic2SoundRequestService.Transfer collected = observed.stream()
                .filter(Sonic2SoundRequestService.Transfer.class::isInstance)
                .map(Sonic2SoundRequestService.Transfer.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Sonic2SoundRequestPipeline.SourceSlot.SFX1,
                collected.sourceMailbox());
        assertEquals(1, collected.physicalSlot());
        assertEquals(0xB5, collected.rawRequestId());

        observed.clear();
        assertTrue(audio.playSfx(0xB5));
        audio.presentFrame(PresentationMode.FORWARD);

        Sonic2SoundRequestService.Transfer raw = observed.stream()
                .filter(Sonic2SoundRequestService.Transfer.class::isInstance)
                .map(Sonic2SoundRequestService.Transfer.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Sonic2SoundRequestPipeline.SourceSlot.SFX0,
                raw.sourceMailbox());
        assertEquals(0, raw.physicalSlot());
        assertEquals(0xB5, raw.rawRequestId());
    }

    /**
     * Installs the Sonic 2 ROM. The S2 pipeline resolves a request against its
     * ROM-backed sample and rejects the whole request when the sample cannot
     * be read, so a mailbox test without a ROM observes nothing at all.
     */
    private void installRom(AudioManager audio) {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null,
                "Sonic 2 REV01 ROM is required for production request resolution");
        rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));
        audio.setRom(rom);
    }

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        if (rom != null) {
            rom.close();
        }
    }

    @Test
    void baseS2IngressDefersUntilForwardPresentationAndRingResolvesOnce() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        installRom(audio);
        audio.setAudioProfile(new Sonic2AudioProfile());
        audio.setSoundMap(new Sonic2AudioProfile().getSoundMap());

        assertTrue(audio.playSfx(0xB5));
        assertEquals(0, audio.commandTimeline().entryCount(),
                "S2 ingress must write the source mailbox without immediate playback");

        audio.presentFrame(PresentationMode.SILENT);
        assertEquals(0, audio.commandTimeline().entryCount(),
                "silent presentation must not consume S2 mailbox work");

        audio.presentFrame(PresentationMode.FORWARD);
        List<AudioCommand> commands = audio.commandTimeline().entries().stream()
                .map(entry -> entry.command()).toList();
        assertEquals(1, commands.size());
        assertEquals(0xCE, ((AudioCommand.PlaySfx) commands.getFirst()).sfxId(),
                "raw B5 is resolved only by the S2 pipeline");

        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(1, audio.commandTimeline().entryCount(),
                "one outer boundary cannot service the same mailbox twice");
    }

    @Test
    void nonS2ProfileRetainsImmediateIngress() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(
                new AudioTestFixtures.StubSmpsLoader()));

        audio.playMusic(0x82);

        assertEquals(1, audio.commandTimeline().entryCount());
    }

    @Test
    void logicalSnapshotRestoresPendingMailboxWork() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        installRom(audio);
        audio.setAudioProfile(new Sonic2AudioProfile());
        audio.setSoundMap(new Sonic2AudioProfile().getSoundMap());

        assertTrue(audio.playSfx(0xB5));
        AudioLogicalSnapshot pending = audio.captureLogicalSnapshot();

        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(1, audio.commandTimeline().entryCount());

        audio.restoreLogicalSnapshot(pending);
        audio.presentFrame(PresentationMode.FORWARD);

        assertEquals(2, audio.commandTimeline().entryCount(),
                "restored request work must replay once after the historical command");
        assertEquals(0xCE, ((AudioCommand.PlaySfx) audio.commandTimeline()
                .entries().getLast().command()).sfxId());
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(2, audio.commandTimeline().entryCount(),
                "restored request work must not replay twice");
    }

    @Test
    void reverseDoesNotConsumeAndSameFrameSoundWritesOverwrite() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        installRom(audio);
        Sonic2AudioProfile profile = new Sonic2AudioProfile();
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());

        assertTrue(audio.playSfx(0xA0));
        assertTrue(audio.playSfx(0xB5));
        audio.presentFrame(PresentationMode.REVERSE);
        assertEquals(0, audio.commandTimeline().entryCount(),
                "reverse presentation must not consume the mailbox");

        audio.presentFrame(PresentationMode.FORWARD);
        List<AudioCommand> commands = audio.commandTimeline().entries().stream()
                .map(entry -> entry.command()).toList();
        assertEquals(1, commands.size());
        assertEquals(0xCE, ((AudioCommand.PlaySfx) commands.getFirst()).sfxId(),
                "the later raw write must overwrite SFX0 before the bridge runs");
    }

    @Test
    void unmappedS2GameSoundRetainsImmediateFallbackRoute() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        Sonic2AudioProfile profile = new Sonic2AudioProfile();
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());

        audio.playSfx(GameSound.FIRE_SHIELD);

        assertEquals(1, audio.commandTimeline().entryCount());
        AudioCommand.PlaySfx command = (AudioCommand.PlaySfx) audio
                .commandTimeline().entries().getFirst().command();
        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME, command.route());
    }

    @Test
    void standaloneNamedSfxRetainsImmediateRoute() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        Sonic2AudioProfile profile = new Sonic2AudioProfile();
        audio.setAudioProfile(profile);

        audio.playSfx("standalone-cue");

        assertEquals(1, audio.commandTimeline().entryCount());
        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME,
                ((AudioCommand.PlaySfx) audio.commandTimeline().entries()
                        .getFirst().command()).route());
    }
}
