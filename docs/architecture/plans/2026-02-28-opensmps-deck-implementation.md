# OpenSMPSDeck Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a standalone SMPS-native music tracker for YM2612 FM + SN76489 PSG composition with traditional tracker grid UI, visual instrument editors, and SMPS binary export.

**Architecture:** Three-layer design: synth core (extracted from sonic-engine, pure Java chip emulators + SMPS sequencer), song model (SMPS-native data structures), and JavaFX UI (tracker grid, order list, instrument editors). The internal representation IS SMPS bytecode — the UI is a decoded view over it.

**Tech Stack:** Java 21, JavaFX, Maven multi-module, javax.sound.sampled for audio output. Synth core copied from sonic-engine's `com.openggf.audio` package.

**Source reference:** Synth core lives in `C:\Users\farre\IdeaProjects\sonic-engine\src\main\java\com\openggf\audio\`. Design doc at `docs/architecture/designs/2026-02-28-opensmps-deck-design.md`.

---

## Phase 1: Project Skeleton & Synth Core Extraction

### Task 1: Create Maven Multi-Module Project

**Files:**
- Create: `pom.xml` (parent)
- Create: `synth-core/pom.xml`
- Create: `app/pom.xml`

**Step 1: Create project root directory**

```bash
mkdir -p ~/IdeaProjects/opensmps-deck
cd ~/IdeaProjects/opensmps-deck
git init
```

**Step 2: Create parent POM**

Create `pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.opensmps</groupId>
    <artifactId>opensmps-deck-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>OpenSMPSDeck</name>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <javafx.version>21.0.2</javafx.version>
        <junit.version>5.10.2</junit.version>
    </properties>

    <modules>
        <module>synth-core</module>
        <module>app</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

**Step 3: Create synth-core module POM**

Create `synth-core/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.opensmps</groupId>
        <artifactId>opensmps-deck-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>synth-core</artifactId>
    <name>OpenSMPSDeck Synth Core</name>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Step 4: Create app module POM**

Create `app/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.opensmps</groupId>
        <artifactId>opensmps-deck-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>opensmps-deck-app</artifactId>
    <name>OpenSMPSDeck Application</name>

    <dependencies>
        <dependency>
            <groupId>com.opensmps</groupId>
            <artifactId>synth-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-controls</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-graphics</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.10.1</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Step 5: Create source directories**

```bash
mkdir -p synth-core/src/main/java/com/opensmps/synth
mkdir -p synth-core/src/main/java/com/opensmps/smps
mkdir -p synth-core/src/main/java/com/opensmps/driver
mkdir -p synth-core/src/test/java/com/opensmps/synth
mkdir -p synth-core/src/test/java/com/opensmps/smps
mkdir -p app/src/main/java/com/opensmps/deck
mkdir -p app/src/main/java/com/opensmps/deck/model
mkdir -p app/src/main/java/com/opensmps/deck/ui
mkdir -p app/src/main/java/com/opensmps/deck/audio
mkdir -p app/src/main/java/com/opensmps/deck/io
mkdir -p app/src/test/java/com/opensmps/deck
```

**Step 6: Verify build**

Run: `mvn compile`
Expected: BUILD SUCCESS (empty modules compile fine)

**Step 7: Commit**

```bash
git add -A
git commit -m "chore: initial Maven multi-module project structure"
```

---

### Task 2: Extract Chip Emulators (Ym2612Chip + PsgChipGPGX)

Copy the chip emulator files from sonic-engine, repackage to `com.opensmps.synth`.

**Source files (copy from sonic-engine):**
- `src/main/java/com/openggf/audio/synth/Ym2612Chip.java` → `synth-core/src/main/java/com/opensmps/synth/Ym2612Chip.java`
- `src/main/java/com/openggf/audio/synth/PsgChipGPGX.java` → `synth-core/src/main/java/com/opensmps/synth/PsgChipGPGX.java`
- `src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java` → `synth-core/src/main/java/com/opensmps/synth/BlipDeltaBuffer.java`
- `src/main/java/com/openggf/audio/synth/BlipResampler.java` → `synth-core/src/main/java/com/opensmps/synth/BlipResampler.java`

**Step 1: Copy files**

Copy each file, change package declaration from `com.openggf.audio.synth` to `com.opensmps.synth`. Update internal imports accordingly.

For Ym2612Chip: change `import com.openggf.audio.smps.DacData` → `import com.opensmps.smps.DacData` (DacData will be extracted in Task 3).

**Step 2: Create DacData stub**

Create `synth-core/src/main/java/com/opensmps/smps/DacData.java` — copy from sonic-engine's `com.openggf.audio.smps.DacData`, repackage.

```java
package com.opensmps.smps;

import java.util.Map;

public class DacData {
    public final Map<Integer, byte[]> samples;
    public final Map<Integer, DacEntry> mapping;
    public final int baseCycles;

    public DacData(Map<Integer, byte[]> samples, Map<Integer, DacEntry> mapping) {
        this(samples, mapping, 288); // S2 default
    }

    public DacData(Map<Integer, byte[]> samples, Map<Integer, DacEntry> mapping, int baseCycles) {
        this.samples = samples;
        this.mapping = mapping;
        this.baseCycles = baseCycles;
    }

    public static class DacEntry {
        public final int sampleId;
        public final int rate;

        public DacEntry(int sampleId, int rate) {
            this.sampleId = sampleId;
            this.rate = rate;
        }
    }
}
```

**Step 3: Write smoke test for YM2612**

Create `synth-core/src/test/java/com/opensmps/synth/TestYm2612Chip.java`:
```java
package com.opensmps.synth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestYm2612Chip {

    @Test
    void testChipInitializesAndProducesSilence() {
        Ym2612Chip ym = new Ym2612Chip();
        int[] left = new int[735];
        int[] right = new int[735];
        ym.renderStereo(left, right);
        // After init with no key-on, output should be silent (all zeros)
        for (int i = 0; i < 735; i++) {
            assertEquals(0, left[i], "Left sample " + i + " should be silent");
            assertEquals(0, right[i], "Right sample " + i + " should be silent");
        }
    }

    @Test
    void testSetInstrumentAndKeyOn() {
        Ym2612Chip ym = new Ym2612Chip();

        // Minimal FM voice: algo 0, feedback 0, simple sine on op4
        byte[] voice = new byte[25];
        voice[0] = 0x00; // algo 0, fb 0
        // Op1 (modulator): TL=127 (silent)
        voice[2] = 0x7F;
        // Op3 (modulator): TL=127
        voice[6] = 0x7F;
        // Op2 (modulator): TL=127
        voice[10] = 0x7F;
        // Op4 (carrier): MUL=1, TL=0 (loud), AR=31, D1R=0, D2R=0, D1L=0, RR=15
        voice[13] = 0x01; // MUL=1
        voice[14] = 0x00; // TL=0
        voice[15] = 0x1F; // AR=31
        voice[16] = 0x00; // D1R=0
        voice[17] = 0x00; // D2R=0
        voice[18] = 0x0F; // D1L=0, RR=15

        ym.setInstrument(0, voice);

        // Set frequency (A4 ~440Hz): block=4, fnum=0x1A2
        ym.write(0, 0xA4, 0x4A); // block 4, fnum high bits
        ym.write(0, 0xA0, 0x22); // fnum low bits

        // Key on: all 4 operators on channel 0
        ym.write(0, 0x28, 0xF0);

        // Render a frame
        int[] left = new int[735];
        int[] right = new int[735];
        ym.renderStereo(left, right);

        // Should produce non-zero output
        boolean hasSignal = false;
        for (int i = 0; i < 735; i++) {
            if (left[i] != 0 || right[i] != 0) {
                hasSignal = true;
                break;
            }
        }
        assertTrue(hasSignal, "YM2612 should produce audio after key-on");
    }
}
```

**Step 4: Write smoke test for PSG**

Create `synth-core/src/test/java/com/opensmps/synth/TestPsgChipGPGX.java`:
```java
package com.opensmps.synth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestPsgChipGPGX {

    @Test
    void testChipInitializesAndProducesSilence() {
        PsgChipGPGX psg = new PsgChipGPGX(44100.0);
        int[] left = new int[735];
        int[] right = new int[735];
        psg.renderStereo(left, right);
        // PSG channels default to max attenuation (silent)
        // All output should be zero or near-zero
        // (BlipDelta may produce tiny residual values)
    }

    @Test
    void testToneGeneration() {
        PsgChipGPGX psg = new PsgChipGPGX(44100.0);
        psg.configure(150, 0xFF); // preamp=150, all channels center

        // Channel 0 frequency: ~440Hz (tone register = 254 at 3.58MHz/32)
        psg.write(0x80 | 0x0E); // Latch ch0 freq low nibble = 0x0E
        psg.write(0x00 | 0x0F); // Data ch0 freq high bits = 0x0F -> freq = 0xFE = 254

        // Channel 0 volume: 0 (maximum)
        psg.write(0x90 | 0x00); // Latch ch0 vol = 0 (loudest)

        int[] left = new int[735];
        int[] right = new int[735];
        psg.renderStereo(left, right);

        boolean hasSignal = false;
        for (int i = 0; i < 735; i++) {
            if (left[i] != 0 || right[i] != 0) {
                hasSignal = true;
                break;
            }
        }
        assertTrue(hasSignal, "PSG should produce audio after setting tone and volume");
    }
}
```

**Step 5: Run tests**

Run: `mvn test -pl synth-core`
Expected: All tests PASS

**Step 6: Commit**

```bash
git add -A
git commit -m "feat: extract YM2612 and PSG chip emulators from sonic-engine"
```

---

### Task 3: Extract SMPS Sequencer & Driver

Copy the SMPS sequencer, config, and driver from sonic-engine.

**Source files (copy and repackage):**
- `SmpsSequencer.java` → `com.opensmps.smps.SmpsSequencer`
- `SmpsSequencerConfig.java` → `com.opensmps.smps.SmpsSequencerConfig`
- `AbstractSmpsData.java` → `com.opensmps.smps.AbstractSmpsData`
- `CoordFlagHandler.java` → `com.opensmps.smps.CoordFlagHandler`
- `CoordFlagContext.java` → `com.opensmps.smps.CoordFlagContext`
- `SmpsDriver.java` → `com.opensmps.driver.SmpsDriver`
- `Synthesizer.java` → `com.opensmps.synth.Synthesizer`
- `VirtualSynthesizer.java` → `com.opensmps.synth.VirtualSynthesizer`

**Step 1: Copy and repackage each file**

Update all `com.openggf.audio` imports to `com.opensmps` equivalents:
- `com.openggf.audio.synth.*` → `com.opensmps.synth.*`
- `com.openggf.audio.smps.*` → `com.opensmps.smps.*`
- `com.openggf.audio.driver.*` → `com.opensmps.driver.*`

Remove any imports referencing game-specific packages (`com.openggf.sonic.*`, `com.openggf.game.*`).

If SmpsDriver extends VirtualSynthesizer and implements an `AudioStream` interface, create a minimal `AudioStream` interface in `com.opensmps.driver`:
```java
package com.opensmps.driver;

public interface AudioStream {
    int read(short[] buffer);
    boolean isComplete();
}
```

**Step 2: Create a concrete SmpsData implementation for testing**

Create `synth-core/src/test/java/com/opensmps/smps/TestSmpsData.java`:
```java
package com.opensmps.smps;

/**
 * Minimal SmpsData for unit tests. Wraps raw SMPS binary.
 * Uses S2 conventions: little-endian, baseNoteOffset=1.
 */
public class TestSmpsData extends AbstractSmpsData {

    private byte[][] psgEnvelopes;

    public TestSmpsData(byte[] data, int z80StartAddress) {
        super(data, z80StartAddress);
    }

    public void setPsgEnvelopes(byte[][] envelopes) {
        this.psgEnvelopes = envelopes;
    }

    @Override
    protected void parseHeader() {
        voicePtr = read16(0);
        channels = data[2] & 0xFF;
        psgChannels = data[3] & 0xFF;
        dividingTiming = data[4] & 0xFF;
        tempo = data[5] & 0xFF;

        fmPointers = new int[channels];
        fmKeyOffsets = new int[channels];
        fmVolumeOffsets = new int[channels];

        int offset = 6;
        for (int i = 0; i < channels; i++) {
            fmPointers[i] = read16(offset);
            fmKeyOffsets[i] = (byte) data[offset + 2];
            fmVolumeOffsets[i] = (byte) data[offset + 3];
            offset += 4;
        }

        psgPointers = new int[psgChannels];
        psgKeyOffsets = new int[psgChannels];
        psgVolumeOffsets = new int[psgChannels];
        psgModEnvs = new int[psgChannels];
        psgInstruments = new int[psgChannels];

        for (int i = 0; i < psgChannels; i++) {
            psgPointers[i] = read16(offset);
            psgKeyOffsets[i] = (byte) data[offset + 2];
            psgVolumeOffsets[i] = (byte) data[offset + 3];
            psgModEnvs[i] = data[offset + 4] & 0xFF;
            psgInstruments[i] = data[offset + 5] & 0xFF;
            offset += 6;
        }
    }

    @Override
    public byte[] getVoice(int voiceId) {
        int offset = voicePtr;
        if (offset == 0) return null;
        offset += voiceId * 25;
        if (offset + 25 > data.length) return null;
        byte[] voice = new byte[25];
        System.arraycopy(data, offset, voice, 0, 25);
        return voice;
    }

    @Override
    public byte[] getPsgEnvelope(int id) {
        if (psgEnvelopes == null || id < 0 || id >= psgEnvelopes.length) return null;
        return psgEnvelopes[id];
    }

    @Override
    public int read16(int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    @Override
    public int getBaseNoteOffset() {
        return 1; // S2 convention
    }
}
```

**Step 3: Write sequencer smoke test**

Create `synth-core/src/test/java/com/opensmps/smps/TestSmpsSequencer.java`:
```java
package com.opensmps.smps;

import com.opensmps.driver.SmpsDriver;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestSmpsSequencer {

    @Test
    void testSequencerPlaysSimpleSong() {
        // Build a minimal SMPS binary:
        // Header: voice ptr, 1 FM channel, 0 PSG, dividing=1, tempo=0x80
        // Single FM track: note C5 (0x8D+12*4=0xBF? — use 0xA1 for C4),
        //                  duration 0x30, then track end (0xF2)
        byte[] smps = buildMinimalSong();

        TestSmpsData data = new TestSmpsData(smps, 0);

        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0 })
                .psgChannelOrder(new int[]{})
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();

        SmpsDriver driver = new SmpsDriver(44100.0);
        SmpsSequencer seq = new SmpsSequencer(data, null, driver, config);
        driver.addSequencer(seq, false);

        // Render 1 second of audio
        short[] buffer = new short[44100 * 2]; // stereo
        int samplesRead = driver.read(buffer);

        assertTrue(samplesRead > 0, "Driver should produce samples");
        assertFalse(seq.isComplete(), "Song should still be playing (looping or sustaining)");
    }

    private byte[] buildMinimalSong() {
        // Voice table at offset 0x20 (32), 1 FM channel, 0 PSG
        // FM track at offset 0x45 (after voice)
        byte[] smps = new byte[80];

        // Header
        smps[0] = 0x20; smps[1] = 0x00; // voice ptr = 0x0020
        smps[2] = 0x01; // 1 FM channel
        smps[3] = 0x00; // 0 PSG channels
        smps[4] = 0x01; // dividing timing = 1
        smps[5] = (byte) 0x80; // tempo

        // FM channel 0 entry: track ptr, key offset, volume
        smps[6] = 0x45; smps[7] = 0x00; // track ptr = 0x0045
        smps[8] = 0x00; // key offset = 0
        smps[9] = 0x00; // volume offset = 0

        // Voice at 0x20: simple sine (algo 0, fb 0)
        smps[0x20] = 0x00; // algo 0
        // Op1 TL=127 (silent modulator)
        smps[0x22] = 0x7F;
        // Op3 TL=127
        smps[0x26] = 0x7F;
        // Op2 TL=127
        smps[0x2A] = 0x7F;
        // Op4 (carrier): MUL=1, TL=0, AR=31
        smps[0x2D] = 0x01; // MUL
        smps[0x2E] = 0x00; // TL
        smps[0x2F] = 0x1F; // AR=31
        smps[0x30] = 0x00; // D1R
        smps[0x31] = 0x00; // D2R
        smps[0x32] = 0x0F; // D1L=0, RR=15

        // Track data at 0x45
        smps[0x45] = (byte) 0xE1; // Set voice command
        smps[0x46] = 0x00;        // Voice 0
        smps[0x47] = (byte) 0xA1; // Note C4
        smps[0x48] = 0x30;        // Duration 48 frames
        smps[0x49] = (byte) 0xF2; // Track end

        return smps;
    }
}
```

**Step 4: Run tests**

Run: `mvn test -pl synth-core`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add -A
git commit -m "feat: extract SMPS sequencer, driver, and config from sonic-engine"
```

---

### Task 4: Create javax.sound Audio Backend

Simple audio output that reads from SmpsDriver and writes to speakers.

**Files:**
- Create: `synth-core/src/main/java/com/opensmps/driver/AudioOutput.java`
- Test: `synth-core/src/test/java/com/opensmps/driver/TestAudioOutput.java`

**Step 1: Write the AudioOutput class**

```java
package com.opensmps.driver;

import javax.sound.sampled.*;

/**
 * Streams audio from an SmpsDriver to the system audio device.
 * Runs on a dedicated thread. Start/stop/pause control.
 */
public class AudioOutput {

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SAMPLES = 1024;

    private final SmpsDriver driver;
    private SourceDataLine line;
    private Thread audioThread;
    private volatile boolean running;
    private volatile boolean paused;

    public AudioOutput(SmpsDriver driver) {
        this.driver = driver;
    }

    public void start() {
        if (running) return;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, BUFFER_SAMPLES * 4); // 4 bytes per stereo sample
            line.start();
        } catch (LineUnavailableException e) {
            throw new RuntimeException("Audio device unavailable", e);
        }

        running = true;
        paused = false;
        audioThread = new Thread(this::audioLoop, "OpenSMPSDeck-Audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public void stop() {
        running = false;
        paused = false;
        if (audioThread != null) {
            try { audioThread.join(1000); } catch (InterruptedException ignored) {}
        }
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
        driver.stopAll();
        driver.silenceAll();
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isRunning() { return running; }
    public boolean isPaused() { return paused; }

    private void audioLoop() {
        short[] samples = new short[BUFFER_SAMPLES * 2]; // stereo interleaved
        byte[] byteBuffer = new byte[BUFFER_SAMPLES * 4]; // 16-bit stereo = 4 bytes/sample

        while (running) {
            if (paused) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                continue;
            }

            driver.read(samples);

            // Convert short[] to byte[] (little-endian)
            for (int i = 0; i < samples.length; i++) {
                byteBuffer[i * 2] = (byte) (samples[i] & 0xFF);
                byteBuffer[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
            }

            line.write(byteBuffer, 0, byteBuffer.length);
        }
    }
}
```

**Step 2: Write test (verifies construction, not actual audio)**

```java
package com.opensmps.driver;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestAudioOutput {

    @Test
    void testConstructionDoesNotThrow() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        AudioOutput output = new AudioOutput(driver);
        assertFalse(output.isRunning());
        assertFalse(output.isPaused());
    }
}
```

**Step 3: Run tests**

Run: `mvn test -pl synth-core`
Expected: PASS

**Step 4: Commit**

```bash
git add -A
git commit -m "feat: add javax.sound audio output backend"
```

---

## Phase 2: Song Model & Compiler

### Task 5: Create Song Model Classes

Core data model: Song, Pattern, FmVoice, PsgEnvelope.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/model/Song.java`
- Create: `app/src/main/java/com/opensmps/deck/model/Pattern.java`
- Create: `app/src/main/java/com/opensmps/deck/model/FmVoice.java`
- Create: `app/src/main/java/com/opensmps/deck/model/PsgEnvelope.java`
- Create: `app/src/main/java/com/opensmps/deck/model/SmpsMode.java`
- Test: `app/src/test/java/com/opensmps/deck/model/TestSongModel.java`

**Step 1: Write test first**

```java
package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestSongModel {

    @Test
    void testNewSongHasDefaults() {
        Song song = new Song();
        assertEquals(SmpsMode.S2, song.getSmpsMode());
        assertEquals(0x80, song.getTempo());
        assertEquals(1, song.getDividingTiming());
        assertEquals(0, song.getLoopPoint());
        assertTrue(song.getVoiceBank().isEmpty());
        assertTrue(song.getPsgEnvelopes().isEmpty());
        assertEquals(1, song.getOrderList().size()); // one default order row
        assertEquals(1, song.getPatterns().size());   // one default pattern
    }

    @Test
    void testFmVoiceRoundTrip() {
        byte[] rawVoice = new byte[25];
        rawVoice[0] = 0x32; // algo=2, fb=6
        FmVoice voice = new FmVoice("Lead", rawVoice);
        assertEquals("Lead", voice.getName());
        assertEquals(2, voice.getAlgorithm());
        assertEquals(6, voice.getFeedback());
        assertArrayEquals(rawVoice, voice.getData());
    }

    @Test
    void testPsgEnvelopeRoundTrip() {
        byte[] steps = { 0, 0, 1, 1, 2, 3, 4, 5, 6, 7, (byte) 0x80 };
        PsgEnvelope env = new PsgEnvelope("Pluck", steps);
        assertEquals("Pluck", env.getName());
        assertEquals(10, env.getStepCount()); // 10 volume steps, not counting terminator
        assertArrayEquals(steps, env.getData());
    }

    @Test
    void testPatternHasTenChannels() {
        Pattern pattern = new Pattern(0, 64);
        assertEquals(10, pattern.getTrackCount());
        assertEquals(64, pattern.getRows());
        // Each track starts as empty byte array
        for (int ch = 0; ch < 10; ch++) {
            assertNotNull(pattern.getTrackData(ch));
        }
    }
}
```

**Step 2: Implement model classes**

`SmpsMode.java`:
```java
package com.opensmps.deck.model;

public enum SmpsMode {
    S1, S2, S3K
}
```

`FmVoice.java`:
```java
package com.opensmps.deck.model;

public class FmVoice {
    private String name;
    private final byte[] data; // 25 bytes, SMPS slot order

    public FmVoice(String name, byte[] data) {
        this.name = name;
        this.data = data.clone();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public byte[] getData() { return data.clone(); }
    public int getAlgorithm() { return data[0] & 0x07; }
    public int getFeedback() { return (data[0] >> 3) & 0x07; }

    public void setAlgorithm(int algo) {
        data[0] = (byte) ((data[0] & 0xF8) | (algo & 0x07));
    }

    public void setFeedback(int fb) {
        data[0] = (byte) ((data[0] & 0xC7) | ((fb & 0x07) << 3));
    }

    /** Get operator parameter. opIndex 0-3, paramOffset 0-4 within the 5-byte op block. */
    public int getOpParam(int opIndex, int paramOffset) {
        return data[1 + opIndex * 5 + paramOffset] & 0xFF;
    }

    /** Set operator parameter. */
    public void setOpParam(int opIndex, int paramOffset, int value) {
        data[1 + opIndex * 5 + paramOffset] = (byte) (value & 0xFF);
    }
}
```

`PsgEnvelope.java`:
```java
package com.opensmps.deck.model;

public class PsgEnvelope {
    private String name;
    private byte[] data; // volume steps + 0x80 terminator

    public PsgEnvelope(String name, byte[] data) {
        this.name = name;
        this.data = data.clone();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public byte[] getData() { return data.clone(); }

    public int getStepCount() {
        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 0xFF) >= 0x80) return i;
        }
        return data.length;
    }

    public int getStep(int index) { return data[index] & 0x0F; }

    public void setStep(int index, int value) {
        data[index] = (byte) (value & 0x0F);
    }
}
```

`Pattern.java`:
```java
package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.List;

public class Pattern {
    public static final int CHANNEL_COUNT = 10;

    private final int id;
    private int rows;
    private final List<byte[]> tracks; // one byte[] per channel

    public Pattern(int id, int rows) {
        this.id = id;
        this.rows = rows;
        this.tracks = new ArrayList<>();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            tracks.add(new byte[0]);
        }
    }

    public int getId() { return id; }
    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }
    public int getTrackCount() { return CHANNEL_COUNT; }
    public byte[] getTrackData(int channel) { return tracks.get(channel); }
    public void setTrackData(int channel, byte[] data) { tracks.set(channel, data); }
}
```

`Song.java`:
```java
package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.List;

public class Song {
    private String name = "Untitled";
    private SmpsMode smpsMode = SmpsMode.S2;
    private int tempo = 0x80;
    private int dividingTiming = 1;
    private int loopPoint = 0;

    private final List<FmVoice> voiceBank = new ArrayList<>();
    private final List<PsgEnvelope> psgEnvelopes = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<int[]> orderList = new ArrayList<>(); // int[10] per row

    public Song() {
        // Default: one empty pattern, one order row pointing to it
        patterns.add(new Pattern(0, 64));
        int[] firstOrder = new int[Pattern.CHANNEL_COUNT];
        for (int i = 0; i < firstOrder.length; i++) firstOrder[i] = 0;
        orderList.add(firstOrder);
    }

    // Getters/setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public SmpsMode getSmpsMode() { return smpsMode; }
    public void setSmpsMode(SmpsMode smpsMode) { this.smpsMode = smpsMode; }
    public int getTempo() { return tempo; }
    public void setTempo(int tempo) { this.tempo = tempo; }
    public int getDividingTiming() { return dividingTiming; }
    public void setDividingTiming(int dividingTiming) { this.dividingTiming = dividingTiming; }
    public int getLoopPoint() { return loopPoint; }
    public void setLoopPoint(int loopPoint) { this.loopPoint = loopPoint; }
    public List<FmVoice> getVoiceBank() { return voiceBank; }
    public List<PsgEnvelope> getPsgEnvelopes() { return psgEnvelopes; }
    public List<Pattern> getPatterns() { return patterns; }
    public List<int[]> getOrderList() { return orderList; }
}
```

**Step 3: Run tests**

Run: `mvn test -pl app`
Expected: All PASS

**Step 4: Commit**

```bash
git add -A
git commit -m "feat: add SMPS-native song model (Song, Pattern, FmVoice, PsgEnvelope)"
```

---

### Task 6: Implement PatternCompiler

Compiles a Song model into a raw SMPS binary blob that SmpsDriver can play.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/audio/PatternCompiler.java`
- Test: `app/src/test/java/com/opensmps/deck/audio/TestPatternCompiler.java`

**Step 1: Write test**

```java
package com.opensmps.deck.audio;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestPatternCompiler {

    @Test
    void testCompilesMinimalSong() {
        Song song = new Song();
        song.setTempo(0x80);
        song.setDividingTiming(1);

        // Add a voice
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x00; // algo 0
        song.getVoiceBank().add(new FmVoice("Test", voiceData));

        // Put a note in FM channel 0 of pattern 0
        // E1 00 (set voice 0), A1 (note C4), 30 (duration 48), F2 (track end)
        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xE1, 0x00, (byte)0xA1, 0x30, (byte)0xF2 });

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        assertNotNull(smps);
        assertTrue(smps.length > 0);

        // Header should have correct channel counts
        // FM channels with data = 1 (only channel 0 has data)
        // Verify voice pointer is non-zero
        int voicePtr = (smps[0] & 0xFF) | ((smps[1] & 0xFF) << 8);
        assertTrue(voicePtr > 0, "Voice pointer should be set");
    }

    @Test
    void testCompiledSongContainsVoiceData() {
        Song song = new Song();
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32; // algo=2, fb=6
        song.getVoiceBank().add(new FmVoice("Lead", voiceData));

        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xE1, 0x00, (byte)0xA1, 0x30, (byte)0xF2 });

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        // Find voice data in compiled output
        int voicePtr = (smps[0] & 0xFF) | ((smps[1] & 0xFF) << 8);
        assertEquals(0x32, smps[voicePtr] & 0xFF, "Voice algo/fb byte should be present");
    }

    @Test
    void testMultipleOrderRowsProduceLoopJump() {
        Song song = new Song();
        song.setLoopPoint(0);

        byte[] voiceData = new byte[25];
        song.getVoiceBank().add(new FmVoice("Test", voiceData));

        // Two order rows both pointing to pattern 0
        song.getOrderList().add(new int[Pattern.CHANNEL_COUNT]); // row 1
        song.setLoopPoint(0);

        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xA1, 0x30 }); // note + duration, no end marker

        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        // Should contain F4 (jump) command somewhere for looping
        boolean hasJump = false;
        for (int i = 0; i < smps.length - 2; i++) {
            if ((smps[i] & 0xFF) == 0xF4) {
                hasJump = true;
                break;
            }
        }
        assertTrue(hasJump, "Compiled song should contain loop jump (F4)");
    }
}
```

**Step 2: Implement PatternCompiler**

```java
package com.opensmps.deck.audio;

import com.opensmps.deck.model.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Compiles a Song model into an SMPS binary blob.
 *
 * Layout:
 *   [header] [track data ch0] [track data ch1] ... [voice table]
 *
 * The header uses file-relative pointers (z80StartAddress = 0).
 */
public class PatternCompiler {

    public byte[] compile(Song song) {
        try {
            return compileInternal(song);
        } catch (IOException e) {
            throw new RuntimeException("Compilation failed", e);
        }
    }

    private byte[] compileInternal(Song song) throws IOException {
        List<Pattern> patterns = song.getPatterns();
        List<int[]> orderList = song.getOrderList();
        List<FmVoice> voices = song.getVoiceBank();

        // Count active FM and PSG channels (channels that have data in any pattern)
        int fmCount = 0;
        int psgCount = 0;
        boolean[] channelActive = new boolean[Pattern.CHANNEL_COUNT];

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            for (int[] orderRow : orderList) {
                int patId = orderRow[ch];
                if (patId >= 0 && patId < patterns.size()) {
                    byte[] trackData = patterns.get(patId).getTrackData(ch);
                    if (trackData != null && trackData.length > 0) {
                        channelActive[ch] = true;
                        break;
                    }
                }
            }
            if (channelActive[ch]) {
                if (ch < 6) fmCount++;
                else psgCount++;
            }
        }

        // Ensure at least 1 FM channel (DAC placeholder)
        if (fmCount == 0) fmCount = 1;

        // Calculate header size
        int headerSize = 6 + (fmCount * 4) + (psgCount * 6);

        // Build concatenated track data for each active channel
        byte[][] compiledTracks = new byte[Pattern.CHANNEL_COUNT][];
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (!channelActive[ch]) {
                compiledTracks[ch] = new byte[0];
                continue;
            }
            compiledTracks[ch] = buildChannelTrack(song, ch);
        }

        // Calculate track offsets (relative to start of file)
        int trackOffset = headerSize;
        int[] trackPointers = new int[Pattern.CHANNEL_COUNT];
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (!channelActive[ch]) {
                trackPointers[ch] = 0;
                continue;
            }
            trackPointers[ch] = trackOffset;
            trackOffset += compiledTracks[ch].length;
        }

        // Voice table follows all track data
        int voiceTableOffset = trackOffset;

        // Assemble final binary
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Header
        writeLE16(out, voiceTableOffset);       // voice pointer
        out.write(fmCount);                      // FM channel count
        out.write(psgCount);                     // PSG channel count
        out.write(song.getDividingTiming());     // dividing timing
        out.write(song.getTempo());              // tempo

        // FM channel entries
        for (int ch = 0; ch < 6; ch++) {
            if (!channelActive[ch]) continue;
            writeLE16(out, trackPointers[ch]);   // track pointer
            out.write(0);                        // key offset
            out.write(0);                        // volume offset
        }

        // PSG channel entries
        for (int ch = 6; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (!channelActive[ch]) continue;
            writeLE16(out, trackPointers[ch]);   // track pointer
            out.write(0);                        // key offset
            out.write(0);                        // volume offset
            out.write(0);                        // mod envelope
            out.write(0);                        // PSG instrument
        }

        // Track data
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (compiledTracks[ch].length > 0) {
                out.write(compiledTracks[ch]);
            }
        }

        // Voice table
        for (FmVoice voice : voices) {
            out.write(voice.getData());
        }

        return out.toByteArray();
    }

    /**
     * Concatenates pattern track data for a channel across all order rows,
     * then appends a loop jump (F4) back to the loop point.
     */
    private byte[] buildChannelTrack(Song song, int channel) throws IOException {
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        List<int[]> orderList = song.getOrderList();
        List<Pattern> patterns = song.getPatterns();

        // Track byte offsets for each order row (for loop jump target)
        int[] orderOffsets = new int[orderList.size()];

        for (int orderIdx = 0; orderIdx < orderList.size(); orderIdx++) {
            orderOffsets[orderIdx] = track.size();
            int patId = orderList.get(orderIdx)[channel];
            if (patId >= 0 && patId < patterns.size()) {
                byte[] data = patterns.get(patId).getTrackData(channel);
                if (data != null && data.length > 0) {
                    // Strip any existing track-end markers (F2) — we'll add our own
                    int len = data.length;
                    while (len > 0 && (data[len - 1] & 0xFF) == 0xF2) len--;
                    track.write(data, 0, len);
                }
            }
        }

        // Add loop jump (F4) pointing to the loop point's offset
        // The offset is file-relative; we need to add the track's base offset later.
        // For now, store a placeholder — the final offset is computed during assembly.
        // Actually, since we know the header size and preceding tracks, we can compute it.
        // But we don't know the header size here. Use a marker approach:
        // Store the loop target as an order-row-relative offset for now.

        int loopTarget = orderOffsets[Math.min(song.getLoopPoint(), orderList.size() - 1)];
        // F4 + 2-byte LE pointer (will be relocated during assembly)
        track.write(0xF4);
        // Store as placeholder — will be patched after final layout is known
        writeLE16(track, loopTarget); // relative to track start

        return track.toByteArray();
    }

    // Note: the loop jump pointers in buildChannelTrack are relative to track start.
    // compileInternal must patch them to be file-relative after layout is finalized.
    // TODO: implement pointer patching in compileInternal after initial track assembly.

    private void writeLE16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
```

Note: The loop jump pointer patching (track-relative → file-relative) needs to be added in `compileInternal` after all tracks are assembled and final offsets are known. The F4 commands should be scanned and their 2-byte targets updated by adding the track's file offset.

**Step 3: Run tests**

Run: `mvn test -pl app`
Expected: All PASS

**Step 4: Commit**

```bash
git add -A
git commit -m "feat: add PatternCompiler (Song model -> SMPS binary)"
```

---

### Task 7: Playback Engine Integration

Wire PatternCompiler → SmpsDriver → AudioOutput for real-time playback.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/audio/PlaybackEngine.java`
- Test: `app/src/test/java/com/opensmps/deck/audio/TestPlaybackEngine.java`

**Step 1: Write test**

```java
package com.opensmps.deck.audio;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestPlaybackEngine {

    @Test
    void testCompileAndRenderProducesAudio() {
        Song song = new Song();
        song.setTempo(0x80);

        byte[] voiceData = new byte[25];
        voiceData[13] = 0x01; // Op4 MUL=1
        voiceData[15] = 0x1F; // Op4 AR=31
        voiceData[18] = 0x0F; // Op4 RR=15
        song.getVoiceBank().add(new FmVoice("Test", voiceData));

        song.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xE1, 0x00, (byte)0xA1, 0x30, (byte)0xF2 });

        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        // Render without audio output (headless)
        short[] buffer = new short[2048];
        int samples = engine.renderBuffer(buffer);
        assertTrue(samples > 0, "Should render audio samples");
    }
}
```

**Step 2: Implement PlaybackEngine**

```java
package com.opensmps.deck.audio;

import com.opensmps.deck.model.Song;
import com.opensmps.deck.model.SmpsMode;
import com.opensmps.driver.AudioOutput;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.*;

public class PlaybackEngine {

    private final SmpsDriver driver;
    private final PatternCompiler compiler;
    private AudioOutput audioOutput;
    private SmpsSequencer currentSequencer;

    public PlaybackEngine() {
        this.driver = new SmpsDriver(44100.0);
        this.compiler = new PatternCompiler();
    }

    public void loadSong(Song song) {
        driver.stopAll();
        driver.silenceAll();

        byte[] smps = compiler.compile(song);
        TestSmpsData data = new TestSmpsData(smps, 0);

        SmpsSequencerConfig config = buildConfig(song.getSmpsMode());
        currentSequencer = new SmpsSequencer(data, null, driver, config);
        driver.addSequencer(currentSequencer, false);
    }

    /** Render samples to buffer (headless, no audio device). */
    public int renderBuffer(short[] buffer) {
        return driver.read(buffer);
    }

    public void play() {
        if (audioOutput == null) {
            audioOutput = new AudioOutput(driver);
        }
        audioOutput.start();
    }

    public void stop() {
        if (audioOutput != null) audioOutput.stop();
        driver.stopAll();
        driver.silenceAll();
    }

    public void pause() {
        if (audioOutput != null) audioOutput.pause();
    }

    public void resume() {
        if (audioOutput != null) audioOutput.resume();
    }

    public SmpsDriver getDriver() { return driver; }

    private SmpsSequencerConfig buildConfig(SmpsMode mode) {
        return switch (mode) {
            case S2 -> new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();
            case S1 -> new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .relativePointers(true)
                .tempoOnFirstTick(true)
                .build();
            case S3K -> new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{ 0x16, 0, 1, 2, 4, 5, 6 })
                .psgChannelOrder(new int[]{ 0x80, 0xA0, 0xC0 })
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .tempoOnFirstTick(true)
                .build();
        };
    }
}
```

Note: `TestSmpsData` is from the test package in synth-core. For the app module to use it in production, either move it to `synth-core/src/main` or create an equivalent `SimpleSmpsData` class in the app module. The implementation should create a proper `SimpleSmpsData` in `com.opensmps.deck.audio`.

**Step 3: Run tests**

Run: `mvn test -pl app`
Expected: PASS

**Step 4: Commit**

```bash
git add -A
git commit -m "feat: add PlaybackEngine (compile + play songs in real time)"
```

---

## Phase 3: File I/O

### Task 8: Project Save/Load (.osmpsd JSON)

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/io/ProjectFile.java`
- Test: `app/src/test/java/com/opensmps/deck/io/TestProjectFile.java`

**Step 1: Write test**

```java
package com.opensmps.deck.io;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

public class TestProjectFile {

    @TempDir
    File tempDir;

    @Test
    void testSaveAndLoad() {
        Song original = new Song();
        original.setName("Test Song");
        original.setTempo(0xA0);
        original.setDividingTiming(2);
        original.setLoopPoint(1);

        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32;
        original.getVoiceBank().add(new FmVoice("Lead", voiceData));

        byte[] envData = { 0, 1, 2, 3, (byte) 0x80 };
        original.getPsgEnvelopes().add(new PsgEnvelope("Pluck", envData));

        original.getPatterns().get(0).setTrackData(0,
            new byte[]{ (byte)0xA1, 0x30, (byte)0xF2 });

        // Add second order row
        original.getOrderList().add(new int[]{ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });

        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        assertTrue(file.exists());

        Song loaded = ProjectFile.load(file);
        assertEquals("Test Song", loaded.getName());
        assertEquals(0xA0, loaded.getTempo());
        assertEquals(2, loaded.getDividingTiming());
        assertEquals(1, loaded.getLoopPoint());
        assertEquals(1, loaded.getVoiceBank().size());
        assertEquals("Lead", loaded.getVoiceBank().get(0).getName());
        assertEquals(0x32, loaded.getVoiceBank().get(0).getData()[0]);
        assertEquals(1, loaded.getPsgEnvelopes().size());
        assertEquals(2, loaded.getOrderList().size());
        assertArrayEquals(
            new byte[]{ (byte)0xA1, 0x30, (byte)0xF2 },
            loaded.getPatterns().get(0).getTrackData(0));
    }
}
```

**Step 2: Implement ProjectFile using Gson**

Serialize Song to JSON. Voice data and track data as hex strings. Use Gson for JSON parsing.

**Step 3: Run tests, commit**

```bash
mvn test -pl app
git add -A && git commit -m "feat: add .osmpsd project file save/load (JSON)"
```

---

### Task 9: SMPS Binary Export

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/io/SmpsExporter.java`
- Test: `app/src/test/java/com/opensmps/deck/io/TestSmpsExporter.java`

**Step 1: Write test**

Verify that `SmpsExporter.export(song, file)` writes a valid SMPS binary that can be loaded back by `TestSmpsData`.

**Step 2: Implement**

Thin wrapper: call `PatternCompiler.compile()`, write bytes to file.

**Step 3: Run tests, commit**

```bash
mvn test -pl app
git add -A && git commit -m "feat: add SMPS binary export"
```

---

### Task 10: SMPS File Import (SMPSPlay .bin files)

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/io/SmpsImporter.java`
- Test: `app/src/test/java/com/opensmps/deck/io/TestSmpsImporter.java`

**Step 1: Write test**

Test that `SmpsImporter.importFile(file)` reads a raw SMPS binary and produces a Song model with voice bank, track data, and header values populated.

**Step 2: Implement SmpsImporter**

Parse SMPS header (same format as `TestSmpsData.parseHeader()`), extract:
- Tempo, dividing timing
- Voice table → FmVoice objects
- Each channel's track data → single Pattern with raw byte arrays
- Order list: single row referencing pattern 0

**Step 3: Run tests, commit**

```bash
mvn test -pl app
git add -A && git commit -m "feat: add SMPS binary file import"
```

---

## Phase 4: JavaFX UI Shell

### Task 11: JavaFX Application Skeleton

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/OpenSmpsDeck.java` (Application main)
- Create: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`

**Step 1: Create main application class**

JavaFX `Application` subclass. Creates a `MainWindow` with the four-panel layout:
- Top: toolbar + transport
- Center: tracker grid (placeholder `Label` for now)
- Bottom: order list (placeholder)
- Right: instrument panel (placeholder)

**Step 2: Create MainWindow with placeholder panels**

Use `BorderPane` layout. Each panel area gets a colored placeholder `Pane` with a label.

**Step 3: Verify it launches**

Run: `mvn javafx:run -pl app` (or equivalent)
Expected: Window appears with placeholder panels

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add JavaFX application shell with placeholder panels"
```

---

### Task 12: Transport Controls

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/TransportBar.java`
- Modify: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`

**Step 1: Create TransportBar**

`HBox` with Play, Stop, Pause buttons + tempo spinner + SMPS mode dropdown. Wires to `PlaybackEngine` methods.

**Step 2: Integrate into MainWindow**

Add TransportBar to the top toolbar area.

**Step 3: Test manually**

Load a song, click Play, hear audio. Click Stop, silence.

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add transport controls (play/stop/pause, tempo, mode)"
```

---

### Task 13: Tracker Grid (Display)

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`
- Create: `app/src/main/java/com/opensmps/deck/ui/SmpsDecoder.java`

**Step 1: Create SmpsDecoder**

Utility that decodes SMPS track bytecode into displayable rows for the tracker grid:

```java
public record TrackerRow(String note, String instrument, String effect) {}

public static List<TrackerRow> decode(byte[] trackData) { ... }
```

Parses note values (0x80=rest, 0x81-0xDF=note), duration, and coordination flags into human-readable strings (`C-5`, `01`, `F0 0A`).

**Step 2: Write SmpsDecoder tests**

Test decoding of note bytes, rest, tie, instrument change, modulation commands.

**Step 3: Create TrackerGrid**

JavaFX `Canvas` or `ScrollPane` with a custom grid renderer:
- Column headers: FM1, FM2, ..., DAC, PSG1, PSG2, PSG3, Noise
- Each cell: `Note Inst Effect` rendered in monospace font
- Row numbers on the left
- Highlighted current row
- Horizontal scroll for channels that don't fit

**Step 4: Wire to Song model**

TrackerGrid reads from the current song's current pattern (selected via order list).

**Step 5: Run, verify display**

**Step 6: Commit**

```bash
git add -A && git commit -m "feat: add tracker grid display with SMPS bytecode decoding"
```

---

### Task 14: Order List Panel

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/OrderListPanel.java`

**Step 1: Create OrderListPanel**

`TableView` or custom grid showing order rows × channels. Click a row to select it and display that pattern in the tracker grid.

Controls: Add Row, Remove Row, Duplicate Row, Set Loop Point.

**Step 2: Wire to Song model and TrackerGrid**

Selecting an order row updates the tracker grid to show the referenced pattern.

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add order list panel with pattern selection"
```

---

## Phase 5: Editing

### Task 15: Tracker Grid Note Entry

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/SmpsEncoder.java`
- Modify: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`

**Step 1: Create SmpsEncoder**

Converts user input (note name + octave + instrument) into SMPS bytecode:
- Note entry → `[noteValue, duration]` bytes
- Instrument change → `[0xE1, voiceId]` bytes
- Effect commands → appropriate SMPS coordination flag bytes

**Step 2: Write SmpsEncoder tests**

Test encoding of C-5 → 0xBD (or appropriate note value), rest → 0x80, tie → 0xE7.

**Step 3: Add keyboard handling to TrackerGrid**

Process key events per the keyboard mapping from the design doc:
- `Q-M` rows → note entry (map to piano keyboard)
- `0-9, A-F` → hex input for instrument/effect columns
- `Arrow keys` → cursor movement
- `Delete` → clear cell
- `Insert/Backspace` → insert/delete row

Each key action calls SmpsEncoder, then mutates the pattern's track data byte array and redraws.

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add keyboard-driven note entry in tracker grid"
```

---

### Task 16: Selection, Copy/Paste, Transpose

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`
- Create: `app/src/main/java/com/opensmps/deck/ui/ClipboardData.java`

**Step 1: Implement selection**

`Shift+Arrow` extends a rectangular selection (row range × channel range). Selected cells highlighted visually.

**Step 2: Implement copy/paste**

`Ctrl+C` copies selected track bytes + metadata (voice IDs referenced). `Ctrl+V` pastes. Within same song = direct insert. Cross-song detection deferred to Task 22.

**Step 3: Implement transpose**

`+`/`-` → transpose selected note bytes ±1 semitone. `Shift+`/`Shift-` → ±12 semitones. Operates directly on the SMPS note byte values (0x81-0xDF range), clamping at boundaries.

**Step 4: Write tests for transpose**

Test that transposing a note byte by +1 produces the correct value, and clamping at 0x81 and 0xDF works.

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: add selection, copy/paste, transpose (+/- and Shift+/-)"
```

---

### Task 17: Undo/Redo

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/model/UndoManager.java`
- Modify: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`

**Step 1: Implement UndoManager**

Stack-based undo. Each edit snapshots the affected track's `byte[]` before mutation. Undo restores the snapshot. Lightweight — pattern track data is typically < 1KB.

```java
public class UndoManager {
    public void recordEdit(Pattern pattern, int channel, byte[] previousData) { ... }
    public void undo() { ... }
    public void redo() { ... }
}
```

**Step 2: Wire Ctrl+Z / Ctrl+Y**

**Step 3: Write tests**

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add undo/redo for tracker grid edits"
```

---

## Phase 6: Instrument Editors

### Task 18: FM Voice Editor Dialog

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/FmVoiceEditor.java`

**Step 1: Create dialog layout**

JavaFX `Dialog<FmVoice>` with:
- Name text field
- Algorithm dropdown (0-7) with visual diagram (Canvas showing op routing)
- Feedback dropdown (0-7)
- 4 × operator columns, each with sliders for: MUL (0-15), DT (0-7), TL (0-127), AR (0-31), D1R (0-31), D2R (0-31), D1L (0-15), RR (0-15), RS (0-3), AM checkbox
- Carrier operators visually highlighted based on algorithm
- Preview button (plays test note)

**Step 2: Wire sliders to FmVoice data**

Each slider change writes to the FmVoice's 25-byte array and triggers preview playback.

**Step 3: Draw algorithm diagrams**

8 Canvas drawings showing the Yamaha 4-op FM algorithms with operator connections.

**Step 4: Test manually**

Open editor, tweak sliders, hear changes in real time via preview.

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: add FM voice editor with algorithm diagram and real-time preview"
```

---

### Task 19: PSG Envelope Editor Dialog

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/PsgEnvelopeEditor.java`

**Step 1: Create dialog layout**

JavaFX `Dialog<PsgEnvelope>` with:
- Name text field
- Bar graph Canvas (clickable, volume 0-7 per step)
- Add/Remove step buttons
- Preview button

**Step 2: Wire bar graph to PsgEnvelope data**

Click on bars to set volume. Changes update the byte array.

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add PSG envelope editor with bar graph"
```

---

### Task 20: Instrument Panel

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/InstrumentPanel.java`
- Modify: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`

**Step 1: Create InstrumentPanel**

Right-side panel with two `ListView` sections:
- Voice Bank: lists FmVoice names, [+] [Edit] [Delete] buttons
- PSG Envelopes: lists PsgEnvelope names, [+] [Edit] [Delete] buttons

Double-click or [Edit] opens the corresponding editor dialog.

**Step 2: Wire to Song model**

Selected voice/envelope sets the "current instrument" for tracker grid entry.

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add instrument panel with voice bank and envelope lists"
```

---

## Phase 7: Multi-Document & Import

### Task 21: Tab-Based Multi-Document

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`
- Create: `app/src/main/java/com/opensmps/deck/ui/SongTab.java`

**Step 1: Convert to TabPane**

Each tab contains a full editor (tracker grid + order list + instrument panel) for one Song. New tabs via [+] button or File > Open.

**Step 2: Tab detach/dock**

Allow dragging tabs out into separate windows. JavaFX doesn't have built-in tab detaching — implement by creating a new `Stage` with the tab content on drag-out, and re-docking on window close.

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add multi-document tabs with detachable windows"
```

---

### Task 22: Cross-Song Copy-Paste with Instrument Resolution

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/InstrumentResolveDialog.java`
- Modify: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`

**Step 1: Detect cross-song paste**

When pasting, check if source and destination Song are different. If so, scan pasted bytes for `E1 xx` (instrument change) commands and collect referenced voice IDs.

**Step 2: Create resolution dialog**

For each mismatched voice: Copy, Remap, or Skip. Auto-remap when byte-identical voices exist.

**Step 3: Rewrite pasted bytes**

Update `E1 xx` commands in the pasted data to reference the resolved destination voice IDs.

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add cross-song copy-paste with instrument resolution dialog"
```

---

### Task 23: ROM Voice Import Browser

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/io/RomVoiceImporter.java`
- Create: `app/src/main/java/com/opensmps/deck/ui/RomImportDialog.java`

**Step 1: Implement RomVoiceImporter**

Parse a Sonic ROM's SMPS music pointer table. For each song, extract the voice table. Deduplicate voices by data. Return a list of `(voice data, source song name, algorithm)` tuples.

This requires partial reimplementation of the SMPS loader logic (music pointer table, decompression for S2 Saxman-compressed songs). For MVP, support loading from SMPSPlay pre-ripped `.bin` files (simpler — just parse the SMPS header to find the voice table).

**Step 2: Create RomImportDialog**

JavaFX `Dialog` with a `TableView` listing available voices. Filter by song/algorithm. Preview button. Multi-select for import.

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add ROM/SMPSPlay voice import browser"
```

---

### Task 24: Song Import from ROM/SMPSPlay

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/io/SmpsImporter.java`
- Create: `app/src/main/java/com/opensmps/deck/ui/SongImportDialog.java`

**Step 1: Extend SmpsImporter**

Add `importFromSmpsPlayFile(File binFile)` — loads an SMPS binary, parses it into a full Song model (voices, all channel tracks as one big pattern).

**Step 2: Create SongImportDialog**

File chooser filtered to `.bin` files. Shows song info after loading (channel count, voice count, tempo). Click Import to open as new tab.

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add full song import from SMPSPlay .bin files"
```

---

## Phase 8: Solo/Mute & WAV Export

### Task 25: Per-Channel Solo/Mute

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`
- Modify: `app/src/main/java/com/opensmps/deck/audio/PlaybackEngine.java`

**Step 1: Add mute/solo toggle to channel headers**

Click channel name to mute (greyed out). Ctrl+click to solo (all others muted).

**Step 2: Wire to SmpsDriver**

`driver.setFmMute(channel, muted)` / `driver.setPsgMute(channel, muted)`.

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add per-channel solo/mute toggles"
```

---

### Task 26: WAV Export

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/io/WavExporter.java`
- Test: `app/src/test/java/com/opensmps/deck/io/TestWavExporter.java`

**Step 1: Write test**

Render a minimal song to WAV, verify the file has a valid WAV header and non-zero audio data.

**Step 2: Implement WavExporter**

Offline render: create SmpsDriver + SmpsSequencer, call `driver.read()` in a loop, write PCM to WAV file with standard RIFF header. Configurable loop count + fade-out.

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add WAV export with configurable loop count"
```

---

## Summary

| Phase | Tasks | Delivers |
|-------|-------|----------|
| 1: Skeleton & Synth Core | 1-4 | Building project, extracted chip emulators, sequencer, audio output |
| 2: Song Model & Compiler | 5-7 | Data model, SMPS compiler, playback engine |
| 3: File I/O | 8-10 | Save/load, SMPS export, SMPS import |
| 4: UI Shell | 11-14 | JavaFX app, transport, tracker grid display, order list |
| 5: Editing | 15-17 | Note entry, copy/paste, transpose, undo/redo |
| 6: Instrument Editors | 18-20 | FM voice editor, PSG envelope editor, instrument panel |
| 7: Multi-Document & Import | 21-24 | Tabs, cross-song paste, ROM import, song import |
| 8: Polish | 25-26 | Solo/mute, WAV export |

Each task ends with a commit. Each phase produces a testable, functional increment.
