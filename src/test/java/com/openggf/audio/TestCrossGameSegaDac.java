package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real boot commands must reach the shared DAC, through the production presentation path. */
class TestCrossGameSegaDac {
    @AfterEach
    void resetAudio() {
        AudioManager audio = AudioManager.getInstance();
        audio.destroy();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @Nested
    @RequiresRom(SonicGame.SONIC_1)
    class Sonic1 {
        @Test void chant() throws Exception { verifyChant(new Sonic1AudioProfile(), 0xE1); }
    }

    @Nested
    @RequiresRom(SonicGame.SONIC_2)
    class Sonic2 {
        @Test void chant() throws Exception { verifyChant(new Sonic2AudioProfile(), 0xFA); }
    }

    @Nested
    @RequiresRom(SonicGame.SONIC_3K)
    class Sonic3k {
        @Test void chant() throws Exception { verifyChant(new Sonic3kAudioProfile(), 0xFF); }
    }

    private void verifyChant(GameAudioProfile profile, int command) throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.destroy();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setAudioProfile(profile);
        var rom = GameServices.rom().getRom();
        audio.setRom(rom);
        byte[] expected = profile.loadSegaPcm(rom);
        ByteArrayOutputStream writes = new ByteArrayOutputStream();
        audio.setChipWriteObserver(new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
                if (port == 0 && register == 0x2A) writes.write(value);
            }
            @Override
            public void onPsgWrite(int value) { }
        });
        try (var capture = audio.beginLiveCaptureAudio(60)) {
            audio.playMusic(command);
            short[] packet = new short[capture.maxStereoFramesPerPacket() * 2];
            int peak = 0;
            for (int frame = 0; frame < 120; frame++) {
                audio.presentFrame(PresentationMode.FORWARD);
                int frames = capture.drainPresentationFrame(packet);
                for (int i = 0; i < frames * 2; i++) peak = Math.max(peak, Math.abs((int) packet[i]));
                assertNull(audio.captureLogicalSnapshot().presentation().rawPcmVoiceId(),
                        "boot PCM must not bypass the chip as a standalone voice");
            }
            assertArrayEquals(expected, writes.toByteArray(), "each ROM byte reaches the DAC exactly once");
            assertTrue(peak > 500 && peak < 4500, "audible single-channel DAC level: " + peak);
            System.out.println("SEGA_DAC " + profile.getClass().getSimpleName()
                    + " bytes=" + writes.size() + " peak=" + peak);
        }
    }
}
