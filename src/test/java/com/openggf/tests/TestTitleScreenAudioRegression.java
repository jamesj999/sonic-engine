package com.openggf.tests;

import com.openggf.audio.AudioBackend;
import com.openggf.audio.AudioManager;
import com.openggf.audio.ChannelType;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.control.InputHandler;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.game.sonic2.titlescreen.TitleScreenManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
public class TestTitleScreenAudioRegression {

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        TitleScreenManager.getInstance().reset();
    }

    private static final class CountingBackend implements AudioBackend {
        int musicPlayCalls = 0;
        int sparkleSfxCalls = 0;

        @Override
        public void init() {
        }

        @Override
        public void setAudioProfile(GameAudioProfile profile) {
        }

        @Override
        public void playMusic(int musicId) {
            musicPlayCalls++;
        }

        @Override
        public void playSmps(AbstractSmpsData data, DacData dacData) {
            musicPlayCalls++;
        }

        @Override
        public void playSfxSmps(AbstractSmpsData data, DacData dacData) {
            playSfxSmps(data, dacData, 1.0f);
        }

        @Override
        public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch) {
            if (data != null && data.getId() == Sonic2Sfx.SPARKLE.id) {
                sparkleSfxCalls++;
            }
        }

        @Override
        public void playSfx(String sfxName) {
        }

        @Override
        public void playSfx(String sfxName, float pitch) {
        }

        @Override
        public void stopPlayback() {
        }

        @Override
        public void stopAllSfx() {
        }

        @Override
        public void fadeOutMusic(int steps, int delay) {
        }

        @Override
        public void toggleMute(ChannelType type, int channel) {
        }

        @Override
        public void toggleSolo(ChannelType type, int channel) {
        }

        @Override
        public boolean isMuted(ChannelType type, int channel) {
            return false;
        }

        @Override
        public boolean isSoloed(ChannelType type, int channel) {
            return false;
        }

        @Override
        public void setSpeedShoes(boolean enabled) {
        }

        @Override
        public void restoreMusic() {
        }

        @Override
        public void endMusicOverride(int musicId) {
        }

        @Override
        public void update() {
        }

        @Override
        public void destroy() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }
    }

    @Test
    void titleScreenResetStopsActiveSegaPcm() throws Exception {
        AudioManager audioManager = AudioManager.getInstance();
        CountingBackend backend = new CountingBackend();
        audioManager.setBackend(backend);
        audioManager.setAudioProfile(new Sonic2AudioProfile());
        audioManager.setRom(GameServices.rom().getRom());
        audioManager.playSegaPcm();
        audioManager.presentFrame(
                com.openggf.audio.presentation.PresentationMode.SILENT);
        assertNotNull(audioManager.captureLogicalSnapshot()
                .presentation().smpsSession().segaPcmTransport());

        TitleScreenManager.getInstance().reset();
        audioManager.presentFrame(
                com.openggf.audio.presentation.PresentationMode.SILENT);

        assertEquals(null, audioManager.captureLogicalSnapshot()
                        .presentation().smpsSession().segaPcmTransport(),
                "Reset must stop any active SEGA PCM before returning to another title/game");
    }

    @Test
    void testTitleMusicLoadsFromRom() throws Exception {
        Rom rom = GameServices.rom().getRom();

        Sonic2SmpsLoader loader = new Sonic2SmpsLoader(rom);
        AbstractSmpsData titleMusic = loader.loadMusic(Sonic2Music.TITLE.id);
        assertNotNull(titleMusic, "Title music should load from ROM");
    }

    @Test
    void testSparkleSfxCompletesQuickly() throws Exception {
        Rom rom = GameServices.rom().getRom();

        Sonic2SmpsLoader loader = new Sonic2SmpsLoader(rom);
        AbstractSmpsData sparkle = loader.loadSfx(Sonic2Sfx.SPARKLE.id);
        assertNotNull(sparkle, "Sparkle SFX should load from ROM");

        DacData dacData = loader.loadDacData();
        SmpsSequencer seq = new SmpsSequencer(sparkle, dacData, Sonic2SmpsSequencerConfig.CONFIG);
        seq.setSfxMode(true);

        int ticks = 0;
        while (!seq.isComplete() && ticks < 1000) {
            seq.advance(750);
            ticks++;
        }

        assertTrue(ticks < 240, "Sparkle SFX should complete in under ~4 seconds");
    }

    @Test
    void testTitleScreenTriggersSparkleAndMusicExpectedCounts() throws Exception {
        Rom rom = GameServices.rom().getRom();

        AudioManager audioManager = AudioManager.getInstance();
        CountingBackend backend = new CountingBackend();
        audioManager.setBackend(backend);
        audioManager.setAudioProfile(new Sonic2AudioProfile());
        audioManager.setRom(rom);

        TitleScreenManager title = TitleScreenManager.getInstance();
        title.initialize();

        InputHandler input = new InputHandler();
        for (int i = 0; i < 900; i++) {
            title.update(input);
            input.update();
            // S2 requests are written to the driver mailbox at ingress and only
            // become commands at the frame's forward presentation boundary, so
            // the loop has to present the way the game loop does.
            audioManager.presentFrame(
                    com.openggf.audio.presentation.PresentationMode.FORWARD);
        }

        long musicCommands = audioManager.commandTimeline().entries().stream()
                .map(com.openggf.audio.rewind.AudioTimelineEntry::command)
                .filter(com.openggf.audio.rewind.AudioCommand.PlayMusic.class::isInstance)
                .count();
        long sparkleCommands = audioManager.commandTimeline().entries().stream()
                .map(com.openggf.audio.rewind.AudioTimelineEntry::command)
                .filter(com.openggf.audio.rewind.AudioCommand.PlaySfx.class::isInstance)
                .map(com.openggf.audio.rewind.AudioCommand.PlaySfx.class::cast)
                .filter(command -> command.sfxId() == Sonic2Sfx.SPARKLE.id)
                .count();
        assertEquals(1, musicCommands, "Title music should be triggered once");
        assertEquals(10, sparkleCommands,
                "Title sparkle SFX should trigger exactly 10 times (init + 9 positions)");
        assertEquals(0, backend.musicPlayCalls);
        assertEquals(0, backend.sparkleSfxCalls);
    }
}
