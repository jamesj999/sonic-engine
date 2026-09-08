import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

/** Listening stimulus through public playback, not a recording of the AIZ cutscene. */
public final class AizFadeListeningProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("<s3k-rom> <output.wav>");
        var audio = AudioManager.getInstance();
        SonicConfigurationService.getInstance().resetToDefaults();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        try (Rom rom = new Rom()) {
            if (!rom.open(args[0])) throw new IllegalArgumentException("Cannot open ROM");
            var profile = new Sonic3kAudioProfile();
            audio.setRom(rom);
            audio.setAudioProfile(profile);
            audio.setSoundMap(profile.getSoundMap());
            audio.beginCaptureMode(48_000, 60);
            audio.playMusic(Sonic3kMusic.KNUCKLES.id);
            var pcm = new ByteArrayOutputStream();
            short[] frame = new short[1600];
            for (int tick = 0; tick < 660; tick++) {
                if (tick == 240) audio.fadeOutMusic();
                if (tick == 540) audio.playMusic(Sonic3kMusic.AIZ1.id);
                audio.presentFrame(PresentationMode.FORWARD);
                int samples = audio.drainCaptureFrame(frame) * 2;
                for (int i = 0; i < samples; i++) {
                    pcm.write(frame[i] & 255);
                    pcm.write((frame[i] >>> 8) & 255);
                }
            }
            byte[] bytes = pcm.toByteArray();
            try (var input = new AudioInputStream(new ByteArrayInputStream(bytes),
                    new AudioFormat(48_000, 16, 2, true, false), bytes.length / 4)) {
                AudioSystem.write(input, AudioFileFormat.Type.WAVE, Path.of(args[1]).toFile());
            }
            System.out.println("frames=" + bytes.length / 4 + " fade=4s AIZ1=9s");
        } finally {
            audio.endCaptureMode();
            audio.resetState();
        }
    }
}
