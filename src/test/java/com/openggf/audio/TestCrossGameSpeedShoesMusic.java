package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2SmpsConstants;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCrossGameSpeedShoesMusic {
    private AudioManager audio;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
    }

    @Test
    void nativeCommandsApplyAndClearEachGamesSpeedShoesMusicCadence() {
        verifyTempoSwap(
                Sonic1Music.GHZ.id,
                Sonic1SmpsConstants.CMD_SPEED_UP,
                Sonic1SmpsConstants.CMD_SLOW_DOWN,
                Sonic1SmpsSequencerConfig.CONFIG,
                Sonic1SmpsSequencerConfig.SPEED_UP_TEMPOS.get(Sonic1Music.GHZ.id));
        verifyTempoSwap(
                Sonic2Music.EMERALD_HILL.id,
                Sonic2SmpsConstants.CMD_SPEED_UP,
                Sonic2SmpsConstants.CMD_SLOW_DOWN,
                Sonic2SmpsSequencerConfig.CONFIG,
                Sonic2SmpsSequencerConfig.SPEED_UP_TEMPOS.get(Sonic2Music.EMERALD_HILL.id));
        verifyS3kFrameMultiplier();
    }

    /**
     * S3K has no speed-up/slow-down sound command: the 68k writes
     * {@code zTempoSpeedup} in Z80 RAM directly (sonic3k.asm:1519), and the
     * driver's E2h/E3h entries are {@code zStopAllSound} and
     * {@code zPSGSilenceAll} (Sound/Z80 Sound Driver.asm:1669-1670). The
     * engine models that direct write as {@link AudioManager#setSpeedMultiplier}
     * with the profile's {@code FRAME_MULTIPLY} value.
     */
    @Test
    void s3kDirectSpeedupWriteDrivesTheOuterFrameSpeedupTail() {
        int musicId = 0x01;
        installMusic(
                musicId,
                -1,
                -1,
                GameAudioProfile.SpeedMode.FRAME_MULTIPLY,
                Sonic3kSmpsSequencerConfig.CONFIG);
        audio.playMusic(musicId);
        audio.presentFrame(PresentationMode.SILENT);

        audio.setSpeedMultiplier(Sonic3kSmpsConstants.SPEED_MULTIPLIER_ON);
        audio.presentFrame(PresentationMode.FORWARD);

        assertEquals(6, musicSequencer().speedupTimeout(),
                "one S3K outer frame reaches both zDoSpeedUp tails");
    }

    private void verifyTempoSwap(
            int musicId, int speedUpCommand, int slowDownCommand,
            SmpsSequencerConfig config, int expectedFastTempo) {
        installMusic(musicId, speedUpCommand, slowDownCommand,
                GameAudioProfile.SpeedMode.TEMPO_SWAP, config);

        audio.playMusic(musicId);
        audio.presentFrame(PresentationMode.SILENT);
        SmpsSequencerSnapshot normal = musicSequencer();

        audio.playMusic(speedUpCommand);
        audio.presentFrame(PresentationMode.SILENT);
        SmpsSequencerSnapshot fast = musicSequencer();
        assertTrue(fast.speedShoes());
        assertEquals(expectedFastTempo, fast.tempoWeight());

        audio.playMusic(slowDownCommand);
        audio.presentFrame(PresentationMode.SILENT);
        SmpsSequencerSnapshot restored = musicSequencer();
        assertFalse(restored.speedShoes());
        assertEquals(normal.tempoWeight(), restored.tempoWeight());
    }

    private void verifyS3kFrameMultiplier() {
        int musicId = 0x01;
        installMusic(
                musicId,
                -1,
                -1,
                GameAudioProfile.SpeedMode.FRAME_MULTIPLY,
                Sonic3kSmpsSequencerConfig.CONFIG);

        audio.playMusic(musicId);
        audio.presentFrame(PresentationMode.SILENT);
        assertEquals(1, musicSequencer().speedMultiplier());

        // Direct zTempoSpeedup write (sonic3k.asm:1519); see the S3K test above.
        audio.setSpeedMultiplier(Sonic3kSmpsConstants.SPEED_MULTIPLIER_ON);
        audio.presentFrame(PresentationMode.SILENT);
        assertEquals(Sonic3kSmpsConstants.SPEED_MULTIPLIER_ON,
                musicSequencer().speedMultiplier());

        audio.setSpeedMultiplier(Sonic3kSmpsConstants.SPEED_MULTIPLIER_OFF);
        audio.presentFrame(PresentationMode.SILENT);
        assertEquals(1, musicSequencer().speedMultiplier());
    }

    private void installMusic(
            int musicId, int speedUpCommand, int slowDownCommand,
            GameAudioProfile.SpeedMode speedMode,
            SmpsSequencerConfig config) {
        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        AbstractSmpsData music = speedMode == GameAudioProfile.SpeedMode.FRAME_MULTIPLY
                ? new ProgramMusicData() : new AudioTestFixtures.StubSmpsData(
                "music-" + musicId);
        music.setId(musicId);
        loader.musicResults.put(musicId, music);
        audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(
                loader, speedUpCommand, slowDownCommand, speedMode) {
            @Override
            public SmpsSequencerConfig getSequencerConfig() {
                return config;
            }

            @Override
            public String presentationGameId() {
                return speedMode == GameAudioProfile.SpeedMode.FRAME_MULTIPLY
                        ? "s3k" : "base";
            }

            @Override
            public void configurePresentationCoordFlagHandlers(
                    SmpsCoordFlagHandlerOwner owner) {
                if (speedMode == GameAudioProfile.SpeedMode.FRAME_MULTIPLY) {
                    owner.register("s3k", Sonic3kCoordFlagHandler::new);
                }
            }
        });
        audio.setRom(new Rom());
    }

    private static final class ProgramMusicData extends AbstractSmpsData {
        private ProgramMusicData() {
            super(new byte[] {0, (byte) 0x81, 0x40}, 0);
            tempo = 0;
            channels = 1;
            fmPointers = new int[] {1};
            fmKeyOffsets = new int[] {0};
            fmVolumeOffsets = new int[] {0};
        }

        @Override protected void parseHeader() {
        }
        @Override public byte[] getVoice(int voiceId) {
            return new byte[25];
        }
        @Override public byte[] getPsgEnvelope(int id) {
            return new byte[0];
        }
        @Override public int read16(int offset) {
            return 0;
        }
        @Override public int getBaseNoteOffset() {
            return 0;
        }
    }

    private SmpsSequencerSnapshot musicSequencer() {
        return audio.captureLogicalSnapshot().presentation().smpsLogical()
                .sequencers().stream()
                .filter(entry -> !entry.sfx())
                .findFirst()
                .orElseThrow()
                .snapshot();
    }
}
