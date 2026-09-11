package com.openggf.game.sonic1.audio.smps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@RequiresRom(SonicGame.SONIC_1)
class TestSonic1BreakItemSfxOnset {

    @Test
    void breakItemFm5OnsetMatchesTheDriverVisibleRegisterOrder() {
        Sonic1SmpsLoader loader = new Sonic1SmpsLoader(TestEnvironment.currentRom());
        AbstractSmpsData data = loader.loadSfx(0xC1);
        RecordingObserver observer = new RecordingObserver();
        SmpsDriver driver = SmpsDriverTestAccess.create(44_100, observer);

        SmpsSequencer sfx = new SmpsSequencer(data, loader.loadDacData(), driver,
                () -> {}, Sonic1SmpsSequencerConfig.CONFIG);
        assertEquals(List.of(), observer.events,
                "constructing the ROM-backed C1 SFX must be chip-write-free");
        driver.addSequencer(sfx, true);
        SmpsDriverTestAccess.read(driver, new short[2_000], 2_000);

        int noteOn = observer.events.indexOf("YM:0:28:F5");
        assertTrue(noteOn >= 0, "C1 FM5 note-on was not observed");
        assertEquals(List.of(
                "PSG:DF", "PSG:FF",
                "YM:1:B1:3C",
                "YM:1:31:0F", "YM:1:39:01", "YM:1:35:03", "YM:1:3D:01",
                "YM:1:51:1F", "YM:1:59:1F", "YM:1:55:1F", "YM:1:5D:1F",
                "YM:1:61:19", "YM:1:69:12", "YM:1:65:19", "YM:1:6D:0E",
                "YM:1:71:05", "YM:1:79:12", "YM:1:75:00", "YM:1:7D:0F",
                "YM:1:81:0F", "YM:1:89:7F", "YM:1:85:FF", "YM:1:8D:FF",
                "YM:1:41:00", "YM:1:49:80", "YM:1:45:00", "YM:1:4D:80",
                "YM:1:B5:C0",
                "YM:0:28:05", "YM:1:A5:24", "YM:1:A1:3C", "YM:0:28:F5"),
                observer.events.subList(0, noteOn + 1));
    }

    private static final class RecordingObserver implements ChipWriteObserver {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onYm2612Write(int port, int register, int value) {
            events.add("YM:%d:%02X:%02X".formatted(port, register, value));
        }

        @Override
        public void onPsgWrite(int value) {
            events.add("PSG:%02X".formatted(value));
        }
    }
}
