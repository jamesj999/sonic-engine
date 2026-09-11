# Audio Rewind Tier 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Tier 0 audio rewind support so internal rewind restore/replay does not emit live audio side effects or mutate durable audio intent, while adding a clear paused frame-step seam for later deterministic audio.

**Architecture:** Tier 0 introduces a small replay-suppression scope at the audio command boundary, wraps rewind seek/segment replay with that scope, and adds conservative presentation cleanup on rewind release. It deliberately does not claim command replay, SMPS snapshots, chip snapshots, or deterministic sample output.

**Tech Stack:** Java 17, JUnit 5, existing `AudioManager`/`AudioBackend`, existing `RewindController`/`SegmentCache`, Maven Surefire.

**Execution Status:** Implemented in the current workspace on 2026-05-08. Focused Tier 0 tests, adjacent audio/rewind regression tests, backend-bypass scan, and `mvn -DskipTests package` passed. Full-suite green is not claimed because broader Maven output still reports unrelated pre-existing S3K/bootstrap/object-service/singleton-guard failures.

---

## File Structure

Create:

- `src/main/java/com/openggf/audio/rewind/AudioReplayReason.java`
  Enum describing why an internal replay scope is active.

- `src/main/java/com/openggf/audio/rewind/AudioPresentationPolicy.java`
  Enum describing conservative presentation cleanup policies after restore/release.

- `src/main/java/com/openggf/audio/rewind/AudioReplayScope.java`
  AutoCloseable replay scope returned by `AudioManager`.

- `src/test/java/com/openggf/audio/AudioTestFixtures.java`
  Shared audio test doubles for command and backend-call tests.

- `src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java`
  Unit tests for scope nesting, command suppression, ring alternation, setup allowance, and cleanup policy.

- `src/test/java/com/openggf/game/rewind/TestRewindControllerAudioSuppression.java`
  Integration-style unit tests proving `seekTo` and `stepBackward` suppress replay-stepper audio.

- `src/test/java/com/openggf/audio/TestAudioBackendBypassGuard.java`
  Scanner test preventing new gameplay `getBackend()` bypasses outside the reviewed allowlist.

Modify:

- `src/main/java/com/openggf/audio/AudioManager.java`
  Add replay scope state, guard methods, explicit frame-step hook, presentation policy hook, and manager-level restore/speed methods.

- `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
  Route fade-in restore through `AudioManager.restoreMusic()` instead of direct backend access.

- `src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java`
  Route restore-to-previous command through `GameServices.audio().restoreMusic()`.

- `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java`
  Route cutscene music restoration through `services().audioManager().restoreMusic()`.

- `src/main/java/com/openggf/level/LevelManager.java`
  Route speed-shoes reset through `AudioManager.setSpeedShoes(false)`.

- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java`
  Route direct speed multiplier changes through `GameServices.audio().setSpeedMultiplier(...)`.

- `src/main/java/com/openggf/game/rewind/RewindController.java`
  Wrap `seekTo` and `stepBackward` restore/replay work in audio replay scopes.

- `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
  Add release/clear presentation cleanup hooks.

- `src/main/java/com/openggf/TraceSessionLauncher.java`
  Add trace rewind release and teardown presentation cleanup hooks.

- `src/main/java/com/openggf/GameLoop.java`
  Replace paused frame-step audio polling with `advancePausedFrameStepAudio()`.

- `AUDIO_REWIND_BLUEPRINT.tmp.txt`
  Mark Tier 0 plan status and clarify the exact claims Tier 0 makes.

---

### Task 1: Add Audio Replay Types And Red AudioManager Tests

**Files:**
- Create: `src/main/java/com/openggf/audio/rewind/AudioReplayReason.java`
- Create: `src/main/java/com/openggf/audio/rewind/AudioPresentationPolicy.java`
- Create: `src/main/java/com/openggf/audio/rewind/AudioReplayScope.java`
- Create: `src/test/java/com/openggf/audio/AudioTestFixtures.java`
- Create: `src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`

- [ ] **Step 1: Add the replay enum**

Create `src/main/java/com/openggf/audio/rewind/AudioReplayReason.java`:

```java
package com.openggf.audio.rewind;

public enum AudioReplayReason {
    SEEK,
    STEP_BACKWARD,
    SEGMENT_EXPANSION
}
```

- [ ] **Step 2: Add the presentation policy enum**

Create `src/main/java/com/openggf/audio/rewind/AudioPresentationPolicy.java`:

```java
package com.openggf.audio.rewind;

public enum AudioPresentationPolicy {
    SUPPRESSED_INTERNAL_RESTORE,
    STOP_TRANSIENT_SFX_RESYNC_MUSIC,
    STOP_ALL_PRESENTATION
}
```

- [ ] **Step 3: Add the scope interface**

Create `src/main/java/com/openggf/audio/rewind/AudioReplayScope.java`:

```java
package com.openggf.audio.rewind;

public interface AudioReplayScope extends AutoCloseable {
    @Override
    void close();
}
```

- [ ] **Step 4: Add temporary AudioManager API stubs**

Modify `src/main/java/com/openggf/audio/AudioManager.java` so the tests compile before behavior is implemented:

```java
import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.audio.rewind.AudioReplayReason;
import com.openggf.audio.rewind.AudioReplayScope;
```

Add these methods near `resetRingSound()`:

```java
public AudioReplayScope beginRewindReplay(int fromFrame, int targetFrame, AudioReplayReason reason) {
    return () -> {};
}

public boolean isRewindReplaySuppressed() {
    return false;
}

public void afterRewindRestore(int frame, AudioPresentationPolicy policy) {
}

public void advancePausedFrameStepAudio() {
    update();
}

public void restoreMusic() {
    if (backend != null) {
        backend.restoreMusic();
    }
}

public void setSpeedShoes(boolean enabled) {
    if (backend != null) {
        backend.setSpeedShoes(enabled);
    }
}

public void setSpeedMultiplier(int multiplier) {
    if (backend != null) {
        backend.setSpeedMultiplier(multiplier);
    }
}
```

- [ ] **Step 5: Add shared test fixtures**

Create `src/test/java/com/openggf/audio/AudioTestFixtures.java`:

```java
package com.openggf.audio;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.data.Rom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AudioTestFixtures {
    static final DacData EMPTY_DAC = new DacData(
            Collections.emptyMap(), Collections.emptyMap(), 288);

    private AudioTestFixtures() {
    }

    static final class RecordingAudioBackend extends NullAudioBackend {
        final List<String> calls = new ArrayList<>();

        int totalCalls() {
            return calls.size();
        }

        void clear() {
            calls.clear();
        }

        @Override public void playMusic(int musicId) { calls.add("playMusic:" + musicId); }
        @Override public void playSmps(AbstractSmpsData data, DacData dacData) { calls.add("playSmps:" + data); }
        @Override public void playSmps(AbstractSmpsData data, DacData dacData, SmpsSequencerConfig config, boolean forceOverride) { calls.add("playSmpsOverride:" + data + ":" + forceOverride); }
        @Override public void playSfxSmps(AbstractSmpsData data, DacData dacData) { calls.add("playSfxSmps:" + data); }
        @Override public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch) { calls.add("playSfxSmpsPitch:" + data + ":" + pitch); }
        @Override public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch, SmpsSequencerConfig config) { calls.add("playSfxSmpsConfig:" + data + ":" + pitch); }
        @Override public void playSfx(String sfxName) { calls.add("playSfx:" + sfxName); }
        @Override public void playSfx(String sfxName, float pitch) { calls.add("playSfxPitch:" + sfxName + ":" + pitch); }
        @Override public void stopPlayback() { calls.add("stopPlayback"); }
        @Override public void stopAllSfx() { calls.add("stopAllSfx"); }
        @Override public void fadeOutMusic(int steps, int delay) { calls.add("fadeOutMusic:" + steps + ":" + delay); }
        @Override public void setSpeedShoes(boolean enabled) { calls.add("setSpeedShoes:" + enabled); }
        @Override public void setSpeedMultiplier(int multiplier) { calls.add("setSpeedMultiplier:" + multiplier); }
        @Override public void changeMusicTempo(int newDividingTiming) { calls.add("changeMusicTempo:" + newDividingTiming); }
        @Override public void restoreMusic() { calls.add("restoreMusic"); }
        @Override public void endMusicOverride(int musicId) { calls.add("endMusicOverride:" + musicId); }
        @Override public void update() { calls.add("update"); }
        @Override public void pause() { calls.add("pause"); }
        @Override public void resume() { calls.add("resume"); }
    }

    static final class StubSmpsData extends AbstractSmpsData {
        private final String name;

        StubSmpsData(String name) {
            super(new byte[0], 0);
            this.name = name;
        }

        @Override protected void parseHeader() {}
        @Override public byte[] getVoice(int voiceId) { return new byte[0]; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[0]; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
        @Override public String toString() { return name; }
    }

    static final class StubSmpsLoader implements SmpsLoader {
        final Map<Integer, AbstractSmpsData> musicResults = new HashMap<>();
        final Map<Integer, AbstractSmpsData> sfxResults = new HashMap<>();
        final Map<String, AbstractSmpsData> namedSfxResults = new HashMap<>();

        @Override public AbstractSmpsData loadMusic(int musicId) { return musicResults.get(musicId); }
        @Override public AbstractSmpsData loadSfx(int sfxId) { return sfxResults.get(sfxId); }
        @Override public AbstractSmpsData loadSfx(String sfxName) { return namedSfxResults.get(sfxName); }
        @Override public DacData loadDacData() { return EMPTY_DAC; }
    }

    static class StubAudioProfile implements GameAudioProfile {
        private final SmpsLoader loader;
        private final int speedOn;
        private final int speedOff;
        private final SpeedMode speedMode;

        StubAudioProfile(SmpsLoader loader) {
            this(loader, -1, -1, SpeedMode.TEMPO_SWAP);
        }

        StubAudioProfile(SmpsLoader loader, int speedOn, int speedOff, SpeedMode speedMode) {
            this.loader = loader;
            this.speedOn = speedOn;
            this.speedOff = speedOff;
            this.speedMode = speedMode;
        }

        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() { return null; }
        @Override public int getSpeedShoesOnCommandId() { return speedOn; }
        @Override public int getSpeedShoesOffCommandId() { return speedOff; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public SpeedMode getSpeedMode() { return speedMode; }
        @Override public Map<GameSound, Integer> getSoundMap() { return Map.of(); }
    }
}
```

- [ ] **Step 6: Write red suppression tests**

Create `src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java`:

```java
package com.openggf.audio;

import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.audio.rewind.AudioReplayReason;
import com.openggf.audio.rewind.AudioReplayScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.*;

class TestAudioManagerRewindSuppression {
    private AudioManager audio;
    private AudioTestFixtures.RecordingAudioBackend backend;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        backend = new AudioTestFixtures.RecordingAudioBackend();
        audio.setBackend(backend);
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
    }

    @Test
    void suppressesPlaybackCommandsInsideReplayScope() {
        AudioTestFixtures.StubSmpsLoader loader = new AudioTestFixtures.StubSmpsLoader();
        loader.musicResults.put(1, new AudioTestFixtures.StubSmpsData("music"));
        loader.sfxResults.put(2, new AudioTestFixtures.StubSmpsData("sfx"));
        loader.namedSfxResults.put("JUMP", new AudioTestFixtures.StubSmpsData("jump"));
        audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(loader, 0xF0, 0xF1, GameAudioProfile.SpeedMode.FRAME_MULTIPLY));
        audio.setRom(null);
        audio.setSoundMap(new EnumMap<>(GameSound.class));

        try (AudioReplayScope ignored = audio.beginRewindReplay(10, 4, AudioReplayReason.SEEK)) {
            audio.playMusic(1);
            audio.playMusic(0xF0);
            audio.playMusic(0xF1);
            audio.playSfx("JUMP");
            audio.playSfx(GameSound.JUMP);
            audio.playSfx(2);
            audio.playDonorSfx("s3k", 7);
            audio.playDonorMusic("s3k", 9);
            audio.fadeOutMusic();
            audio.fadeOutMusic(8, 2);
            audio.stopAllSfx();
            audio.stopMusic();
            audio.endMusicOverride(5);
            audio.changeMusicTempo(6);
            audio.restoreMusic();
            audio.setSpeedShoes(false);
            audio.setSpeedMultiplier(1);
        }

        assertEquals(0, backend.totalCalls(), "suppressed replay must not dispatch live backend commands");
    }

    @Test
    void suppressionScopeNestsUntilOuterScopeCloses() {
        AudioReplayScope outer = audio.beginRewindReplay(8, 4, AudioReplayReason.STEP_BACKWARD);
        AudioReplayScope inner = audio.beginRewindReplay(4, 3, AudioReplayReason.SEGMENT_EXPANSION);

        assertTrue(audio.isRewindReplaySuppressed());
        inner.close();
        assertTrue(audio.isRewindReplaySuppressed(), "inner close must not disable outer suppression");
        audio.playSfx("INNER_CLOSED");
        assertEquals(0, backend.totalCalls());

        outer.close();
        assertFalse(audio.isRewindReplaySuppressed());
        audio.playSfx("AUDIBLE");
        assertEquals(1, backend.totalCalls());
    }

    @Test
    void replayScopeCloseIsIdempotent() {
        AudioReplayScope scope = audio.beginRewindReplay(5, 2, AudioReplayReason.SEEK);
        scope.close();
        scope.close();

        assertFalse(audio.isRewindReplaySuppressed());
        audio.playSfx("AUDIBLE");
        assertEquals(1, backend.totalCalls());
    }

    @Test
    void suppressedRingSoundDoesNotAdvanceRingAlternation() {
        audio.setSoundMap(new EnumMap<>(GameSound.class));

        try (AudioReplayScope ignored = audio.beginRewindReplay(3, 1, AudioReplayReason.SEEK)) {
            audio.playSfx(GameSound.RING);
        }

        audio.playSfx(GameSound.RING);

        assertEquals(1, backend.totalCalls());
        assertTrue(backend.calls.get(0).contains("RING_LEFT"),
                "first audible ring after suppressed replay must still be left");
    }

    @Test
    void suppressionDoesNotBlockSetupState() {
        AudioTestFixtures.StubSmpsLoader loader = new AudioTestFixtures.StubSmpsLoader();
        loader.sfxResults.put(0x90, new AudioTestFixtures.StubSmpsData("jump"));

        try (AudioReplayScope ignored = audio.beginRewindReplay(3, 1, AudioReplayReason.SEEK)) {
            audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(loader));
            audio.setRom(null);
            EnumMap<GameSound, Integer> map = new EnumMap<>(GameSound.class);
            map.put(GameSound.JUMP, 0x90);
            audio.setSoundMap(map);
        }

        audio.playSfx(GameSound.JUMP);

        assertEquals(1, backend.totalCalls());
        assertTrue(backend.calls.get(0).contains("jump"));
    }

    @Test
    void afterRestorePoliciesUseConservativePresentationCleanup() {
        audio.afterRewindRestore(7, AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
        assertEquals(0, backend.totalCalls());

        audio.afterRewindRestore(7, AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);
        assertEquals(java.util.List.of("stopAllSfx", "restoreMusic"), backend.calls);

        backend.clear();
        audio.afterRewindRestore(7, AudioPresentationPolicy.STOP_ALL_PRESENTATION);
        assertEquals(java.util.List.of("stopAllSfx", "stopPlayback"), backend.calls);
    }

    @Test
    void pausedFrameStepHookDelegatesToLegacyUpdateForTier0() {
        audio.advancePausedFrameStepAudio();

        assertEquals(java.util.List.of("update"), backend.calls);
    }
}
```

- [ ] **Step 7: Run the red test**

Run:

```bash
mvn -Dtest=com.openggf.audio.TestAudioManagerRewindSuppression test
```

Expected: compilation succeeds and at least these tests fail because the stubbed methods do not suppress or track replay state:

```text
suppressesPlaybackCommandsInsideReplayScope
suppressionScopeNestsUntilOuterScopeCloses
suppressedRingSoundDoesNotAdvanceRingAlternation
afterRestorePoliciesUseConservativePresentationCleanup
```

- [ ] **Step 8: Commit the red test if working in a commit-driven session**

```bash
git add src/main/java/com/openggf/audio/rewind src/test/java/com/openggf/audio/AudioTestFixtures.java src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java src/main/java/com/openggf/audio/AudioManager.java
git commit -m "test: define audio rewind suppression contract"
```

---

### Task 2: Implement AudioManager Tier 0 Suppression

**Files:**
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Test: `src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java`

- [ ] **Step 1: Add replay state fields**

In `AudioManager`, add fields beside `ringLeft`:

```java
private int rewindReplaySuppressionDepth;
```

- [ ] **Step 2: Replace the replay stubs with real scope handling**

Replace the stub methods from Task 1 with:

```java
public AudioReplayScope beginRewindReplay(int fromFrame, int targetFrame, AudioReplayReason reason) {
    rewindReplaySuppressionDepth++;
    return new AudioReplayScope() {
        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (rewindReplaySuppressionDepth > 0) {
                rewindReplaySuppressionDepth--;
            }
        }
    };
}

public boolean isRewindReplaySuppressed() {
    return rewindReplaySuppressionDepth > 0;
}

private boolean suppressingRewindReplay() {
    return rewindReplaySuppressionDepth > 0;
}
```

- [ ] **Step 3: Implement presentation cleanup**

Replace `afterRewindRestore(...)` with:

```java
public void afterRewindRestore(int frame, AudioPresentationPolicy policy) {
    if (backend == null || policy == null) {
        return;
    }
    switch (policy) {
        case SUPPRESSED_INTERNAL_RESTORE -> {
        }
        case STOP_TRANSIENT_SFX_RESYNC_MUSIC -> {
            backend.stopAllSfx();
            backend.restoreMusic();
        }
        case STOP_ALL_PRESENTATION -> {
            backend.stopAllSfx();
            backend.stopPlayback();
        }
    }
}
```

- [ ] **Step 4: Implement manager-level backend methods with suppression**

Replace or add these methods:

```java
public void advancePausedFrameStepAudio() {
    update();
}

public void restoreMusic() {
    if (suppressingRewindReplay()) {
        return;
    }
    if (backend != null) {
        backend.restoreMusic();
    }
}

public void setSpeedShoes(boolean enabled) {
    if (suppressingRewindReplay()) {
        return;
    }
    if (backend != null) {
        backend.setSpeedShoes(enabled);
    }
}

public void setSpeedMultiplier(int multiplier) {
    if (suppressingRewindReplay()) {
        return;
    }
    if (backend != null) {
        backend.setSpeedMultiplier(multiplier);
    }
}
```

- [ ] **Step 5: Guard command entry points before durable intent mutation**

At the start of each command method, add the guard before any other behavior:

```java
if (suppressingRewindReplay()) {
    return;
}
```

Apply that form to:

```java
public void playMusic(int musicId)
public void playSfx(String sfxName, float pitch)
public void playSfx(GameSound sound, float pitch)
public void playDonorSfx(String donorGameId, int sfxId)
public void playDonorMusic(String donorGameId, int musicId)
public void endMusicOverride(int musicId)
public void changeMusicTempo(int newDividingTiming)
public void stopAllSfx()
public void stopMusic()
public void fadeOutMusic(int steps, int delay)
```

For `playSfx(int sfxId, float pitch)`, return `false` under suppression:

```java
if (suppressingRewindReplay()) {
    return false;
}
```

Do not add suppression to setup/lifecycle methods:

```java
setBackend
setAudioProfile
setRom
setSoundMap
registerDonorLoader
registerDonorSound
clearDonorAudio
destroy
pause
resume
```

- [ ] **Step 6: Route speed-shoes command handling through manager methods**

Inside `playMusic(int musicId)`, replace direct backend calls:

```java
backend.setSpeedMultiplier(audioProfile.getSpeedMultiplierValue());
backend.setSpeedShoes(true);
backend.setSpeedMultiplier(1);
backend.setSpeedShoes(false);
```

with:

```java
setSpeedMultiplier(audioProfile.getSpeedMultiplierValue());
setSpeedShoes(true);
setSpeedMultiplier(1);
setSpeedShoes(false);
```

- [ ] **Step 7: Reset replay state during AudioManager reset**

In `resetState()`, add:

```java
this.rewindReplaySuppressionDepth = 0;
```

- [ ] **Step 8: Run the AudioManager suppression test**

Run:

```bash
mvn -Dtest=com.openggf.audio.TestAudioManagerRewindSuppression test
```

Expected:

```text
Tests run: 7, Failures: 0, Errors: 0
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/openggf/audio/AudioManager.java src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java
git commit -m "feat: suppress audio commands during rewind replay"
```

---

### Task 3: Migrate Gameplay Backend Bypasses And Add Scanner Guard

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java`
- Create: `src/test/java/com/openggf/audio/TestAudioBackendBypassGuard.java`

- [ ] **Step 1: Migrate SmpsSequencer restore callback**

In `SmpsSequencer.handleFadeIn(...)`, replace:

```java
audioManager.getBackend().restoreMusic();
```

with:

```java
audioManager.restoreMusic();
```

- [ ] **Step 2: Migrate Sonic3kCoordFlagHandler restore callback**

In `Sonic3kCoordFlagHandler`, replace:

```java
GameServices.audio().getBackend().restoreMusic();
```

with:

```java
GameServices.audio().restoreMusic();
```

- [ ] **Step 3: Migrate AIZ miniboss cutscene restore callback**

In `AizMinibossCutsceneInstance`, replace:

```java
services().audioManager().getBackend().restoreMusic();
```

with:

```java
services().audioManager().restoreMusic();
```

- [ ] **Step 4: Migrate level speed-shoes reset**

In `LevelManager`, replace:

```java
audioManager.getBackend().setSpeedShoes(false);
```

with:

```java
audioManager.setSpeedShoes(false);
```

- [ ] **Step 5: Migrate S3K special-stage speed multiplier**

In `Sonic3kSpecialStageManager`, replace:

```java
GameServices.audio().getBackend().setSpeedMultiplier(tempo);
GameServices.audio().getBackend().setSpeedMultiplier(1);
```

with:

```java
GameServices.audio().setSpeedMultiplier(tempo);
GameServices.audio().setSpeedMultiplier(1);
```

- [ ] **Step 6: Add scanner guard test**

Create `src/test/java/com/openggf/audio/TestAudioBackendBypassGuard.java`:

```java
package com.openggf.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAudioBackendBypassGuard {
    @Test
    void gameplayCodeDoesNotBypassAudioManagerForBackendCommands() throws IOException {
        Path root = Path.of("src/main/java/com/openggf");
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(root)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                String normalized = path.toString().replace('\\', '/');
                if (normalized.contains("/audio/AudioManager.java")
                        || normalized.contains("/audio/debug/")
                        || normalized.contains("/audio/LWJGLAudioBackend.java")
                        || normalized.contains("/audio/NullAudioBackend.java")
                        || normalized.contains("/audio/AudioBackend.java")) {
                    continue;
                }

                String text = Files.readString(path);
                if (text.contains("GameServices.audio().getBackend()")
                        || text.contains("services().audioManager().getBackend()")
                        || text.contains("audioManager.getBackend()")) {
                    violations.add(normalized);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Gameplay audio commands must route through AudioManager so rewind suppression can observe them: "
                        + violations);
    }
}
```

- [ ] **Step 7: Run bypass guard and affected audio tests**

Run:

```bash
mvn -Dtest=com.openggf.audio.TestAudioBackendBypassGuard,com.openggf.audio.TestAudioManagerRewindSuppression test
```

Expected:

```text
Tests run: 8, Failures: 0, Errors: 0
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/audio/smps/SmpsSequencer.java src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java src/main/java/com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java src/main/java/com/openggf/level/LevelManager.java src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java src/test/java/com/openggf/audio/TestAudioBackendBypassGuard.java
git commit -m "refactor: route gameplay audio backend commands through manager"
```

---

### Task 4: Wrap RewindController Replay Paths

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/RewindController.java`
- Create: `src/test/java/com/openggf/game/rewind/TestRewindControllerAudioSuppression.java`
- Test: `src/test/java/com/openggf/audio/AudioTestFixtures.java`

- [ ] **Step 1: Write red controller suppression tests**

Create `src/test/java/com/openggf/game/rewind/TestRewindControllerAudioSuppression.java`:

```java
package com.openggf.game.rewind;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.debug.playback.Bk2FrameInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindControllerAudioSuppression {
    private AudioManager audio;
    private AudioTestFixtures.RecordingAudioBackend backend;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        backend = new AudioTestFixtures.RecordingAudioBackend();
        audio.setBackend(backend);
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
    }

    @Test
    void seekToSuppressesAudioDuringInternalReplay() {
        RewindRegistry registry = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(20);
        EngineStepper stepper = in -> audio.playSfx("STEP");
        RewindController controller = new RewindController(registry, keyframes, inputs, stepper, 5);

        for (int i = 0; i < 8; i++) {
            controller.step();
        }
        assertEquals(8, backend.totalCalls());
        backend.clear();

        controller.seekTo(3);

        assertEquals(0, backend.totalCalls(), "seek replay must not emit live audio");
        assertEquals(3, controller.currentFrame());
    }

    @Test
    void stepBackwardSuppressesAudioDuringSegmentExpansion() {
        RewindRegistry registry = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(20);
        EngineStepper stepper = in -> audio.playSfx("STEP");
        RewindController controller = new RewindController(registry, keyframes, inputs, stepper, 5);

        for (int i = 0; i < 8; i++) {
            controller.step();
        }
        backend.clear();

        assertTrue(controller.stepBackward());

        assertEquals(0, backend.totalCalls(), "segment expansion must not emit live audio");
        assertEquals(7, controller.currentFrame());
    }

    @Test
    void recordExternalStepDoesNotEnterAudioSuppression() {
        RewindRegistry registry = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(20);
        AtomicInteger steps = new AtomicInteger();
        RewindController controller = new RewindController(
                registry,
                keyframes,
                inputs,
                in -> {
                    steps.incrementAndGet();
                    audio.playSfx("STEP");
                },
                5);

        assertTrue(controller.recordExternalStep());
        audio.playSfx("LIVE");

        assertEquals(1, backend.totalCalls(), "external live frame audio remains audible");
        assertEquals(0, steps.get(), "recordExternalStep must not invoke the stepper");
    }

    private static final class FakeInputSource implements InputSource {
        private final int frames;

        FakeInputSource(int frames) {
            this.frames = frames;
        }

        @Override public int frameCount() { return frames; }
        @Override public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "fake");
        }
    }
}
```

- [ ] **Step 2: Run the red controller test**

Run:

```bash
mvn -Dtest=com.openggf.game.rewind.TestRewindControllerAudioSuppression test
```

Expected: `seekToSuppressesAudioDuringInternalReplay` and `stepBackwardSuppressesAudioDuringSegmentExpansion` fail because `RewindController` does not open audio replay scopes yet.

- [ ] **Step 3: Import replay types in RewindController**

Modify `src/main/java/com/openggf/game/rewind/RewindController.java`:

```java
import com.openggf.audio.AudioManager;
import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.audio.rewind.AudioReplayReason;
```

- [ ] **Step 4: Wrap seekTo restore and replay**

Replace the body of `seekTo(int targetFrame)` with this structure:

```java
public void seekTo(int targetFrame) {
    if (targetFrame == currentFrame) return;
    if (targetFrame < earliestAvailableFrame()) {
        targetFrame = earliestAvailableFrame();
    }
    final int originalFrame = currentFrame;
    final int clampedTarget = targetFrame;
    var floor = keyframes.latestAtOrBefore(clampedTarget).orElseThrow(
            () -> new IllegalStateException(
                    "no keyframe at or before " + clampedTarget));
    try (var ignored = AudioManager.getInstance().beginRewindReplay(
            originalFrame, clampedTarget, AudioReplayReason.SEEK)) {
        segmentCache.invalidate();
        registry.restore(floor.snapshot());
        currentFrame = floor.frame();
        primeStepperAtFrame(currentFrame);
        while (currentFrame < clampedTarget) {
            Bk2FrameInput in = inputs.read(currentFrame + 1);
            engineStepper.step(in);
            currentFrame++;
        }
        keyframes.discardAfter(currentFrame);
        primeStepperAtFrame(currentFrame);
        AudioManager.getInstance().afterRewindRestore(
                currentFrame, AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
    }
}
```

- [ ] **Step 5: Wrap stepBackward restore and segment expansion**

Replace the body of `stepBackward()` with this structure:

```java
public boolean stepBackward() {
    if (currentFrame <= earliestAvailableFrame()) return false;
    int originalFrame = currentFrame;
    int target = currentFrame - 1;
    int keyframeFrame = (target / keyframeInterval) * keyframeInterval;
    final var floor = keyframes.latestAtOrBefore(keyframeFrame).orElseThrow();
    final int keyframeSnapshot = floor.frame();
    final var restoreSnapshot = floor.snapshot();
    final int[] pos = { currentFrame };
    try (var ignored = AudioManager.getInstance().beginRewindReplay(
            originalFrame, target, AudioReplayReason.STEP_BACKWARD)) {
        CompositeSnapshot snap = segmentCache.snapshotAt(
                target,
                restoreSnapshot,
                keyframeSnapshot,
                () -> {
                    registry.restore(restoreSnapshot);
                    pos[0] = keyframeSnapshot;
                    primeStepperAtFrame(pos[0]);
                },
                () -> {
                    Bk2FrameInput in = inputs.read(pos[0] + 1);
                    engineStepper.step(in);
                    pos[0]++;
                    return registry.capture();
                });
        registry.restore(snap);
        currentFrame = target;
        keyframes.discardAfter(currentFrame);
        primeStepperAtFrame(currentFrame);
        AudioManager.getInstance().afterRewindRestore(
                currentFrame, AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
    }
    return true;
}
```

- [ ] **Step 6: Run rewind tests**

Run:

```bash
mvn -Dtest=com.openggf.game.rewind.TestRewindController,com.openggf.game.rewind.TestRewindControllerAudioSuppression test
```

Expected:

```text
Failures: 0, Errors: 0
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/RewindController.java src/test/java/com/openggf/game/rewind/TestRewindControllerAudioSuppression.java
git commit -m "feat: suppress audio during rewind controller replay"
```

---

### Task 5: Add Live And Trace Rewind Release Cleanup

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Test: existing rewind/audio tests

- [ ] **Step 1: Add imports to LiveRewindManager**

In `LiveRewindManager.java`, add:

```java
import com.openggf.audio.AudioManager;
import com.openggf.audio.rewind.AudioPresentationPolicy;
```

- [ ] **Step 2: Add release cleanup helper**

Add this private method to `LiveRewindManager`:

```java
private void cleanupAudioAfterRealtimeRewind(AudioPresentationPolicy policy) {
    if (rewindController == null) {
        return;
    }
    AudioManager.getInstance().afterRewindRestore(rewindController.currentFrame(), policy);
}
```

- [ ] **Step 3: Call cleanup when live rewind key is released**

In `handleRealtimeRewindInput(...)`, replace:

```java
rewinding = false;
return false;
```

with:

```java
if (rewinding) {
    cleanupAudioAfterRealtimeRewind(AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);
}
rewinding = false;
return false;
```

- [ ] **Step 4: Call cleanup when clearing an active live rewind**

At the start of `clear()`, add:

```java
if (rewinding && rewindController != null) {
    cleanupAudioAfterRealtimeRewind(AudioPresentationPolicy.STOP_ALL_PRESENTATION);
}
```

- [ ] **Step 5: Add imports to TraceSessionLauncher**

In `TraceSessionLauncher.java`, add:

```java
import com.openggf.audio.rewind.AudioPresentationPolicy;
```

- [ ] **Step 6: Call cleanup when trace rewind is released**

In `handleRealtimeRewindInput(...)`, inside:

```java
if (realtimeRewinding) {
```

after `syncVisualRewindCursors(true);`, add:

```java
GameServices.audio().afterRewindRestore(
        rewindController.currentFrame(),
        AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);
```

- [ ] **Step 7: Call cleanup during trace teardown**

In `teardown()`, after `GameServices.playbackDebug().endSession();`, add:

```java
if (rewindController != null) {
    GameServices.audio().afterRewindRestore(
            rewindController.currentFrame(),
            AudioPresentationPolicy.STOP_ALL_PRESENTATION);
}
```

- [ ] **Step 8: Run targeted tests**

Run:

```bash
mvn -Dtest=com.openggf.audio.TestAudioManagerRewindSuppression,com.openggf.game.rewind.TestRewindControllerAudioSuppression test
```

Expected:

```text
Failures: 0, Errors: 0
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/LiveRewindManager.java src/main/java/com/openggf/TraceSessionLauncher.java
git commit -m "feat: clean audio presentation after rewind release"
```

---

### Task 6: Add Explicit Paused Frame-Step Audio Seam

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Test: `src/test/java/com/openggf/audio/TestAudioManagerRewindSuppression.java`

- [ ] **Step 1: Document Tier 0 frame-step behavior**

Add this comment above `AudioManager.advancePausedFrameStepAudio()`:

```java
/**
 * Tier 0 frame-step seam. This delegates to legacy backend polling for now.
 * Later tiers replace this with exactly one authoritative audio-frame advance.
 */
```

- [ ] **Step 2: Split GameLoop normal audio update from paused frame-step audio update**

In `GameLoop.stepInternal()`, replace:

```java
profiler.beginSection("audio");
audioManager.update();
profiler.endSection("audio");
```

with:

```java
profiler.beginSection("audio");
if (doFrameStep) {
    audioManager.advancePausedFrameStepAudio();
} else {
    audioManager.update();
}
profiler.endSection("audio");
```

- [ ] **Step 3: Run the existing Tier 0 audio tests**

Run:

```bash
mvn -Dtest=com.openggf.audio.TestAudioManagerRewindSuppression test
```

Expected:

```text
Failures: 0, Errors: 0
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/audio/AudioManager.java
git commit -m "feat: add explicit audio frame-step seam"
```

---

### Task 7: Run Tier 0 Verification Suite

**Files:**
- No source edits expected in this task.

- [ ] **Step 1: Run focused Tier 0 tests**

Run:

```bash
mvn -Dtest=com.openggf.audio.TestAudioManagerRewindSuppression,com.openggf.audio.TestAudioBackendBypassGuard,com.openggf.game.rewind.TestRewindControllerAudioSuppression test
```

Expected:

```text
Failures: 0, Errors: 0
```

- [ ] **Step 2: Run existing audio and rewind regression tests touched by this plan**

Run:

```bash
mvn -Dtest=com.openggf.audio.TestAudioManagerResetState,com.openggf.audio.TestDonorAudioRouting,com.openggf.game.rewind.TestRewindController,com.openggf.game.rewind.TestSegmentCache test
```

Expected:

```text
Failures: 0, Errors: 0
```

- [ ] **Step 3: Run source scanner command manually**

Run:

```bash
rg -n "getBackend\\(\\)\\.(restoreMusic|setSpeedShoes|setSpeedMultiplier)" src/main/java
```

Expected: no gameplay bypass results. Matches inside `AudioManager`, backend implementations, debug tools, or tests are acceptable only if the scanner test allowlist matches them.

- [ ] **Step 4: Run full tests if the focused suite is clean**

Run:

```bash
mvn test
```

Expected:

```text
BUILD SUCCESS
```

---

### Task 8: Update Blueprint And Close Tier 0 Planning Loop

**Files:**
- Modify: `AUDIO_REWIND_BLUEPRINT.tmp.txt`
- Modify: `AUDIO_REWIND_ORCHESTRATION_PHASE1.tmp.txt`

- [ ] **Step 1: Update the blueprint Tier 0 wording**

In `AUDIO_REWIND_BLUEPRINT.tmp.txt`, revise Tier 0 so it states:

```text
Tier 0: Suppression, Bypass Guardrails, And Frame-Step Seam

- During rewind restore/replay and segment-cache expansion, suppress live audio
  presentation side effects before durable audio intent is mutated.
- Migrate or classify gameplay-relevant direct backend bypasses.
- Add conservative post-restore presentation cleanup for live and trace rewind
  release.
- Add an explicit paused frame-step audio hook that delegates to legacy polling
  until the deterministic audio core exists.
- No SMPS, chip, command-replay, or sample parity guarantee.
```

- [ ] **Step 2: Add implementation-plan reference**

Add this line near the top-level planning/status section:

```text
Tier 0 implementation plan: docs/architecture/plans/2026-05-08-audio-rewind-tier0.md
```

- [ ] **Step 3: Add continuation loop note**

Add this continuation loop to the orchestration section:

```text
Current loop:

1. Complete Tier 0 implementation plan.
2. Self-review Tier 0 plan against bypass, durable-state, lifecycle, and test gaps.
3. Implement Tier 0 with tests.
4. Capture verification output and update blueprint status.
5. Dispatch/perform Tier 1 command timeline design review.
6. Write Tier 1 implementation plan only after Tier 0 verification is clean.
```

- [ ] **Step 4: Scan design artifacts for plan failures**

Run:

```bash
powershell -NoProfile -Command "$patterns = @('TO'+'DO','T'+'BD','place'+'holder','FIX'+'ME','implement '+'later','fill '+'in'); foreach ($p in $patterns) { rg -n $p AUDIO_REWIND_BLUEPRINT.tmp.txt AUDIO_REWIND_ORCHESTRATION_PHASE1.tmp.txt docs/architecture/plans/2026-05-08-audio-rewind-tier0.md }"
```

Expected: no matches.

- [ ] **Step 5: Commit docs**

```bash
git add AUDIO_REWIND_BLUEPRINT.tmp.txt AUDIO_REWIND_ORCHESTRATION_PHASE1.tmp.txt docs/architecture/plans/2026-05-08-audio-rewind-tier0.md
git commit -m "docs: plan tier 0 audio rewind suppression"
```

---

## Self-Review Checklist

- [ ] The plan guards `AudioManager.playSfx(GameSound.RING)` before `ringLeft` toggles.
- [ ] The plan wraps both `seekTo(...)` and `stepBackward(...)`, including restore and forward replay.
- [ ] The plan migrates direct gameplay backend bypasses for restore and speed commands.
- [ ] Tier 0 cleanup is described as conservative presentation cleanup, not exact historical music restoration.
- [ ] The paused frame-step hook is explicit but does not claim deterministic advancement in Tier 0.
- [ ] No OpenAL state is captured or treated as authoritative.
- [ ] No heavy audio state is added to `CompositeSnapshot` or `SegmentCache`.
- [ ] Tests assert durable state behavior, not only backend call counts.
- [ ] Later Tier 1/Tier 2 work is left as a separate design and implementation plan.
