# User Recording Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement user-authored BK2 recording and in-engine playback, with per-frame comparison sidecars for desync detection, optional pause-on-desync and fast-forward playback, and non-blocking build-version mismatch warnings.

**Architecture:** Add a new `com.openggf.recording` feature layer that wraps the existing BK2 playback/debug infrastructure. Recording writes BizHawk-compatible BK2 files plus OpenGGF private zip entries. Playback uses `Bk2MovieLoader`, `PlaybackDebugManager.PlaybackFrameObserver`, and a recording-specific session controller rather than Trace Test Mode. Runtime integration stays thin in `GameLoop` and `MasterTitleScreen`.

**Tech Stack:** Java 21, Maven, JUnit 5, Jackson databind, existing `RecordingFrameDriver`, existing `PlaybackDebugManager`

**Spec:** `docs/architecture/designs/2026-06-29-user-recording-playback-design.md`

---

### Task 1: Add Build Identity Model and Prerelease Hash Resource

Build identity is needed before recording metadata and menu warnings. The committed source must not contain the commit hash; Maven generates it at build time.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources-filtered/version.properties`
- Modify: `src/main/java/com/openggf/version/AppVersion.java`
- Modify: `docs/architecture/designs/2026-06-29-user-recording-playback-design.md`
- Create: `src/main/java/com/openggf/version/BuildIdentity.java`
- Create: `src/test/java/com/openggf/version/TestBuildIdentity.java`

- [ ] **Step 1: Extend `version.properties` to carry generated metadata and align the spec**

Update `src/main/resources-filtered/version.properties`:

```properties
app.version=${project.version}
app.baseVersion=${project.version}
app.commit=${openggf.git.commit}
app.dirty=${openggf.git.dirty}
```

`app.version` remains the raw Maven version for legacy callers and fallback parsing. `AppVersion.get()` must not return the raw property directly after this task; it returns `AppVersion.identity().displayVersion()`.

Update the properties examples in `docs/architecture/designs/2026-06-29-user-recording-playback-design.md` to match this schema:

```properties
app.version=0.6.prerelease
app.baseVersion=0.6.prerelease
app.commit=84f1f269d
app.dirty=false
```

and state that the display value is computed by `AppVersion.identity().displayVersion()`.

- [ ] **Step 2: Generate `openggf.git.commit` and `openggf.git.dirty` in Maven**

Add an antrun execution before resource filtering that sets Maven properties by shelling out to Git. Use fallbacks so source archives still build:

```xml
<execution>
  <id>capture-git-build-identity</id>
  <phase>initialize</phase>
  <goals><goal>run</goal></goals>
  <configuration>
    <exportAntProperties>true</exportAntProperties>
    <target>
      <exec executable="git" dir="${project.basedir}" outputproperty="openggf.git.commit.raw" failonerror="false">
        <arg value="rev-parse"/>
        <arg value="--short=9"/>
        <arg value="HEAD"/>
      </exec>
      <exec executable="git" dir="${project.basedir}" outputproperty="openggf.git.dirty.raw" failonerror="false">
        <arg value="status"/>
        <arg value="--porcelain"/>
      </exec>
      <condition property="openggf.git.commit" value="${openggf.git.commit.raw}" else="">
        <isset property="openggf.git.commit.raw"/>
      </condition>
      <condition property="openggf.git.dirty" value="true" else="false">
        <length string="${openggf.git.dirty.raw}" when="greater" length="0"/>
      </condition>
    </target>
  </configuration>
</execution>
```

Place this in the existing `maven-antrun-plugin` declaration, before `install-git-hooks`.

- [ ] **Step 3: Add `BuildIdentity`**

Create `src/main/java/com/openggf/version/BuildIdentity.java`:

```java
package com.openggf.version;

public record BuildIdentity(String baseVersion, String commit, boolean dirty) {
    public String displayVersion() {
        if (!isPrerelease()) {
            return baseVersion;
        }
        String suffix = commit == null || commit.isBlank() ? "unknown" : commit;
        return baseVersion + "-" + suffix + (dirty ? "-dirty" : "");
    }

    public boolean isPrerelease() {
        return baseVersion != null && baseVersion.toLowerCase().contains("prerelease");
    }

    public boolean isCompatibleWith(BuildIdentity other) {
        if (other == null || dirty || other.dirty) {
            return false;
        }
        if (!isPrerelease() && !other.isPrerelease()) {
            return baseVersion.equals(other.baseVersion);
        }
        if (isBlank(commit) || isBlank(other.commit)) {
            return false;
        }
        return baseVersion.equals(other.baseVersion)
                && normalized(commit).equals(normalized(other.commit));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
```

- [ ] **Step 4: Extend `AppVersion`**

Keep `AppVersion.get()` as the display string API and add:

```java
public static BuildIdentity identity() {
    return IDENTITY;
}
```

`IDENTITY` is loaded from `/version.properties`. `AppVersion.get()` returns `identity().displayVersion()`. Official versions display as `0.6.1`; prerelease versions display as `0.6.prerelease-84f1f269d` or `0.6.prerelease-84f1f269d-dirty`.

- [ ] **Step 5: Test compatibility behavior**

`TestBuildIdentity` must cover:
- official same version compatible;
- official different version incompatible;
- prerelease same base and commit compatible;
- prerelease same base but different commit incompatible;
- any dirty side incompatible;
- missing prerelease commit incompatible.

Run: `mvn "-Dtest=com.openggf.version.TestBuildIdentity" test`

---

### Task 2: Define Recording Metadata, Launch Context, and JSON Codecs

These types are pure data and should be implemented before writer/catalog/runtime code.

**Files:**
- Create: `src/main/java/com/openggf/recording/RecordingLaunchContext.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingManifest.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingSidecarMetadata.java`
- Create: `src/main/java/com/openggf/recording/RecordingDeterminismMetadata.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingStopReason.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingJson.java`
- Create: `src/test/java/com/openggf/recording/TestUserRecordingManifestJson.java`

- [ ] **Step 1: Add launch context record**

Use explicit primitive/string fields so the manifest is stable:

```java
package com.openggf.recording;

import java.util.List;

public record RecordingLaunchContext(
        String gameId,
        int zone,
        int act,
        String mainCharacter,
        List<String> sidekickCharacters,
        boolean debugToolsEnabled,
        String launchRoute
) {}
```

`launchRoute` starts as `"current-act-fresh-start"` for live recording and `"manifest"` for playback rebuilt from a movie.

- [ ] **Step 2: Add manifest record**

```java
package com.openggf.recording;

import com.openggf.version.BuildIdentity;

import java.time.Instant;

public record UserRecordingManifest(
        int schemaVersion,
        String movieName,
        BuildIdentity engineIdentity,
        RecordingLaunchContext launchContext,
        UserRecordingSidecarMetadata sidecar,
        RecordingDeterminismMetadata determinism,
        String jumpActionButton,
        int frameCount,
        UserRecordingStopReason stopReason,
        Instant createdAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
```

`jumpActionButton` must be `"A"` for this implementation.

- [ ] **Step 3: Add sidecar metadata record**

```java
package com.openggf.recording;

public record UserRecordingSidecarMetadata(
        int desyncLiteSchemaVersion,
        String sampleMode,
        Integer sampleInterval
) {
    public static final int CURRENT_DESYNC_LITE_SCHEMA_VERSION = 1;

    public static UserRecordingSidecarMetadata everyFrame() {
        return new UserRecordingSidecarMetadata(CURRENT_DESYNC_LITE_SCHEMA_VERSION, "every-frame", null);
    }
}
```

`sampleInterval` is reserved for future sparse modes and remains `null` for `"every-frame"`.

- [ ] **Step 4: Add deterministic-start metadata record**

```java
package com.openggf.recording;

public record RecordingDeterminismMetadata(
        Integer initialLevelFrameCounter,
        Long initialRngSeed
) {}
```

Set fields to `null` when the current runtime does not expose the value. Do not block recording on unavailable seed/counter fields.

- [ ] **Step 5: Add stop reasons**

```java
package com.openggf.recording;

public enum UserRecordingStopReason {
    USER_STOPPED,
    LEVEL_ENDED,
    MOVIE_ENDED,
    IO_ERROR,
    ABORTED_BEFORE_GAMEPLAY
}
```

- [ ] **Step 6: Add Jackson codec helper**

`UserRecordingJson` owns one configured `ObjectMapper` with `findAndRegisterModules()` disabled unless the project already registers Java time modules. If Java time support is missing, serialize `Instant` as ISO string through a manifest DTO inside this helper. Do not add a new dependency.

- [ ] **Step 7: Test roundtrip**

`TestUserRecordingManifestJson` creates a manifest with `BuildIdentity("0.6.prerelease", "abcdef123", false)`, writes JSON, reads it back, and asserts `jumpActionButton == "A"`, the sidekick list survives, `sidecar.desyncLiteSchemaVersion == 1`, `sidecar.sampleMode == "every-frame"`, and a JSON fixture containing reserved `sampleInterval` is tolerated.

Run: `mvn "-Dtest=com.openggf.recording.TestUserRecordingManifestJson" test`

---

### Task 3: Implement BK2 Writer with OpenGGF Sidecar Entries

Create a writer that produces BK2 files accepted by the existing loader and includes OpenGGF private entries in the zip.

**Files:**
- Create: `src/main/java/com/openggf/recording/UserRecordingWriter.java`
- Create: `src/main/java/com/openggf/recording/RecordedFrameInput.java`
- Modify: `src/test/java/com/openggf/tests/playback/TestBk2MovieLoader.java`
- Create: `src/test/java/com/openggf/recording/TestUserRecordingWriter.java`

- [ ] **Step 1: Add frame input record**

```java
package com.openggf.recording;

public record RecordedFrameInput(
        int frame,
        int p1InputMask,
        int p1ActionMask,
        boolean p1Start,
        int p2InputMask,
        int p2ActionMask,
        boolean p2Start
) {}
```

- [ ] **Step 2: Add writer constants**

`UserRecordingWriter` must write this exact LogKey:

```java
public static final String LOG_KEY =
        "#P1 Up|P1 Down|P1 Left|P1 Right|P1 Start|P1 A|P1 B|P1 C|"
      + "#P2 Up|P2 Down|P2 Left|P2 Right|P2 Start|P2 A|P2 B|P2 C|";
```

Frame rows use three grouped fields:

```text
|....SABC|....SABC|
```

Use `.` for unpressed columns. Map collapsed jump to `A` only; `B` and `C` stay unpressed for engine-authored recordings.

- [ ] **Step 3: Write the zip**

`UserRecordingWriter.write(Path bk2Path, UserRecordingManifest manifest, List<RecordedFrameInput> inputs, List<DesyncLiteFrame> sidecarFrames)` writes:
- `Header.txt` with at least `Author: OpenGGF`, `GameName: <gameId>`, `Frames: <frameCount>`;
- `Input Log.txt` with `[Input]`, `LogKey:<LOG_KEY>`, one row per frame, `[/Input]`;
- `OpenGGF/manifest.json`;
- `OpenGGF/desync-lite.jsonl`.

Use UTF-8 and `ZipOutputStream`. Write to `*.tmp`, then move atomically with `Files.move(tmp, bk2Path, REPLACE_EXISTING, ATOMIC_MOVE)` and retry without `ATOMIC_MOVE` on unsupported filesystems.

- [ ] **Step 4: Extend loader test for writer LogKey**

Add a test to `TestBk2MovieLoader` that loads an input log using the exact writer LogKey and asserts:
- P1 A sets `p1ActionMask == 0x01` and `INPUT_JUMP`;
- P1 B/C remain clear for writer-produced rows;
- P2 right and P2 A are parsed independently.

- [ ] **Step 5: Add writer roundtrip test**

`TestUserRecordingWriter` writes a two-frame BK2, opens it with `ZipFile`, verifies both private entries exist, then loads it with `Bk2MovieLoader`.

Run:

```powershell
mvn "-Dtest=com.openggf.tests.playback.TestBk2MovieLoader,com.openggf.recording.TestUserRecordingWriter" test
```

---

### Task 4: Add Desync-Lite Snapshot and Verifier

The sidecar is comparison-only. It must not hydrate gameplay state.

**Files:**
- Create: `src/main/java/com/openggf/recording/DesyncLiteFrame.java`
- Create: `src/main/java/com/openggf/recording/RecordingMainPlayerResolver.java`
- Create: `src/main/java/com/openggf/recording/DesyncLiteSnapshotter.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingVerifier.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingVerificationResult.java`
- Create: `src/test/java/com/openggf/recording/TestUserRecordingVerifier.java`

- [ ] **Step 1: Add per-frame schema**

```java
package com.openggf.recording;

public record DesyncLiteFrame(
        int frame,
        int p1CentreX,
        int p1CentreY,
        int p1XSpeed,
        int p1YSpeed,
        int p1Inertia,
        int p1Status,
        int p1Animation,
        int cameraX,
        int cameraY,
        int timerFrames,
        int timerSeconds,
        int timerMinutes,
        int ringCount,
        int score
) {}
```

Plan for sparse versions by keeping the frame field authoritative and making verifier lookup frame-index based, not list-index based.

- [ ] **Step 2: Snapshot from live state**

`DesyncLiteSnapshotter.capture(int movieFrame)` reads:
- main playable sprite through `RecordingMainPlayerResolver.resolve(GameServices.configuration(), GameServices.sprites())`;
- `getCentreX()` and `getCentreY()`, never `getX()` or `getY()`;
- camera from `GameServices.camera()`;
- timers/rings/score from existing `GameStateManager` and timer services.

If one field is not directly exposed, add a narrow getter on the owning manager rather than reading private fields by reflection.

Add `RecordingMainPlayerResolver` so snapshotter, verifier, and smoke harness use the same player lookup:

```java
package com.openggf.recording;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

public final class RecordingMainPlayerResolver {
    private RecordingMainPlayerResolver() {
    }

    public static AbstractPlayableSprite resolve(SonicConfigurationService configService,
                                                 SpriteManager spriteManager) {
        String mainCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
        Sprite sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            return playable;
        }
        throw new IllegalStateException("Main playable sprite not available for code: " + mainCode);
    }
}
```

- [ ] **Step 3: Verifier result model**

`UserRecordingVerificationResult` should include:

```java
public record UserRecordingVerificationResult(
        boolean clean,
        int comparedFrames,
        int firstMismatchFrame,
        String firstMismatchField,
        String expectedValue,
        String actualValue
) {
    public static UserRecordingVerificationResult clean(int comparedFrames) {
        return new UserRecordingVerificationResult(true, comparedFrames, -1, "", "", "");
    }
}
```

- [ ] **Step 4: Verifier integrates with playback observer**

`UserRecordingVerifier` stores expected frames by `frame`. It exposes:

```java
public PlaybackDebugManager.PlaybackFrameObserver observer();
public UserRecordingVerificationResult result();
public boolean hasMismatch();
```

`afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped)` captures live state after each gameplay frame and compares to the expected `DesyncLiteFrame` for that movie frame. `shouldSkipGameplayTick` returns `false`; user recordings do not encode trace lag frames.

Verifier live capture must call the same `RecordingMainPlayerResolver` path as `DesyncLiteSnapshotter` so recording and playback compare the same player identity.

- [ ] **Step 5: Test comparison logic**

Use a package-private constructor or static helper that compares two `DesyncLiteFrame` values without `GameServices`. Test:
- identical frames are clean;
- a center-X mismatch reports `p1CentreX` and the correct frame;
- missing sparse frame is ignored for now;
- later mismatches do not overwrite the first mismatch.

Run: `mvn "-Dtest=com.openggf.recording.TestUserRecordingVerifier" test`

---

### Task 5: Add Recording Catalog and Version Warning Classification

The master-title menu depends on catalog entries that are loadable without fully starting playback.

**Files:**
- Create: `src/main/java/com/openggf/recording/UserRecordingCatalog.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingEntry.java`
- Create: `src/main/java/com/openggf/recording/RecordingVersionWarning.java`
- Create: `src/test/java/com/openggf/recording/TestUserRecordingCatalog.java`

- [ ] **Step 1: Add catalog entry model**

```java
package com.openggf.recording;

import java.nio.file.Path;
import java.time.Instant;

public record UserRecordingEntry(
        Path path,
        String displayName,
        UserRecordingManifest manifest,
        int frameCount,
        Instant modifiedAt,
        RecordingVersionWarning versionWarning,
        String loadError
) {
    public boolean isLoadable() {
        return loadError == null || loadError.isBlank();
    }
}
```

- [ ] **Step 2: Add warning enum**

```java
package com.openggf.recording;

public enum RecordingVersionWarning {
    NONE,
    MISSING_METADATA,
    OFFICIAL_VERSION_MISMATCH,
    PRERELEASE_BUILD_MISMATCH,
    DIRTY_BUILD
}
```

- [ ] **Step 3: Scan recordings by game**

`UserRecordingCatalog.scan(String gameId, BuildIdentity currentIdentity)` looks under `recordings/<game-id>/*.bk2`, reads `OpenGGF/manifest.json`, uses `Bk2MovieLoader` for `frameCount`, sorts newest first, and returns entries with `loadError` instead of throwing for a bad file.

- [ ] **Step 4: Classify warning text**

Classification rules:
- missing manifest or missing identity: `MISSING_METADATA`;
- either dirty: `DIRTY_BUILD`;
- both official and base versions differ: `OFFICIAL_VERSION_MISMATCH`;
- either prerelease and `BuildIdentity.isCompatibleWith` is false: `PRERELEASE_BUILD_MISMATCH`;
- otherwise `NONE`.

- [ ] **Step 5: Test catalog behavior**

Use `@TempDir` and writer-produced BK2s. Assert:
- newest file is first;
- official same version has no warning;
- official mismatch warns amber;
- prerelease same base and commit has no warning;
- prerelease different commit warns amber;
- malformed BK2 produces one non-loadable entry.

Run: `mvn "-Dtest=com.openggf.recording.TestUserRecordingCatalog" test`

---

### Task 6: Add Playback Options and Runtime Policy Controller

Keep target-frame, pause-on-desync, and fast-forward decisions testable without OpenGL.

**Files:**
- Create: `src/main/java/com/openggf/recording/UserRecordingPlaybackOptions.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingPlaybackState.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingPlaybackController.java`
- Create: `src/test/java/com/openggf/recording/TestUserRecordingPlaybackController.java`

- [ ] **Step 1: Add options record**

```java
package com.openggf.recording;

public record UserRecordingPlaybackOptions(
        int targetFrame,
        boolean pauseOnDesync,
        boolean fastForward
) {
    public static UserRecordingPlaybackOptions defaults(int movieFrameCount) {
        return new UserRecordingPlaybackOptions(movieFrameCount - 1, true, false);
    }
}
```

`targetFrame` is clamped by the menu to `0..movieFrameCount - 1`.

- [ ] **Step 2: Add state enum**

```java
package com.openggf.recording;

public enum UserRecordingPlaybackState {
    PLAYING,
    PAUSED_AT_TARGET,
    PAUSED_ON_DESYNC,
    PAUSED_AT_COMPLETION,
    STOPPED
}
```

- [ ] **Step 3: Add controller**

`UserRecordingPlaybackController.afterFrame(int currentMovieFrame, boolean desync, boolean levelEnded, boolean movieEnded)` returns the next `UserRecordingPlaybackState`.

Rules:
- if `pauseOnDesync && desync`: `PAUSED_ON_DESYNC`;
- else if `levelEnded || movieEnded`: `PAUSED_AT_COMPLETION`;
- else if `currentMovieFrame >= targetFrame`: `PAUSED_AT_TARGET`;
- else `PLAYING`.

- [ ] **Step 4: Test precedence**

`TestUserRecordingPlaybackController` asserts desync beats target, completion beats target when no desync, target pauses exactly on the requested frame, and fast-forward does not change stop classification.

Run: `mvn "-Dtest=com.openggf.recording.TestUserRecordingPlaybackController" test`

---

### Task 7: Add Determinism Smoke Harness Before UI Wiring

This is the Step-0 gate from the spec. If it fails, stop feature work and fix determinism before menus.

**Files:**
- Create: `src/test/java/com/openggf/recording/TestUserRecordingDeterminismSmoke.java`
- Create: `src/test/java/com/openggf/recording/UserRecordingSmokeHarness.java`

- [ ] **Step 1: Build reusable smoke harness**

`UserRecordingSmokeHarness` should:
- use `@FullReset` or `SingletonResetExtension`;
- open a fresh gameplay session for one configured game/zone/act with a ROM path from system properties;
- drive 120 frames with `RecordingFrameDriver`;
- record `RecordedFrameInput` and `DesyncLiteFrame` every frame;
- write a temporary BK2;
- rebuild from `RecordingLaunchContext`;
- load the BK2 with `Bk2MovieLoader`;
- replay through `RecordingFrameDriver.stepFrameFromRecording()`;
- verify clean through `UserRecordingVerifier`.

This gate proves the shared core frame driver can record and replay a fresh-start window deterministically. It is necessary but not sufficient for the final runtime wrapper because `GameLoop.updateLevelMode` also owns mode routing, title cards, rendering suppression, and transitions; Task 11 and Task 12 close that integration coverage.

- [ ] **Step 2: Make ROM-dependent test skip cleanly**

If no matching ROM path exists, use JUnit `Assumptions.assumeTrue` with a clear message. Prefer S3K when `-Ds3k.rom.path` is present, otherwise S2, then S1.

- [ ] **Step 3: Run focused smoke**

Run with an available ROM:

```powershell
mvn "-Dtest=com.openggf.recording.TestUserRecordingDeterminismSmoke" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

Do not start Task 8 until this passes or the failure is understood and fixed.

---

### Task 8: Add Active Recording Session and HUD Model

This is the runtime recording lifecycle, without master-title menu integration yet.

**Files:**
- Create: `src/main/java/com/openggf/recording/UserRecordingSession.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingHudState.java`
- Create: `src/main/java/com/openggf/recording/UserRecordingHud.java`
- Create: `src/test/java/com/openggf/recording/TestUserRecordingSession.java`

- [ ] **Step 1: Add session class**

`UserRecordingSession` owns:
- mode: recording or playback;
- `RecordingLaunchContext`;
- output BK2 path for recording;
- current movie frame;
- buffered `RecordedFrameInput`;
- buffered `DesyncLiteFrame`;
- stop reason;
- optional verifier and playback controller.

It exposes:

```java
public void beforeLevelFrame(InputHandler input);
public void afterLevelFrame();
public void requestStop(UserRecordingStopReason reason);
public boolean isActive();
public UserRecordingHudState hudState();
```

- [ ] **Step 2: Capture live inputs**

Use configured P1/P2 keys from `SonicConfigurationService`. For P1/P2 jump, record action bit `A` when the collapsed engine jump input is pressed. Do not set B or C in engine-authored rows.

For Sonic+Tails or other CPU-sidekick configurations, sidekick determinism comes from P1 input plus deterministic sidekick AI. The P2 lane records explicit P2 controller input only; an empty P2 lane is not a sidekick-recording bug.

- [ ] **Step 3: Finalize recordings**

On stop, write the BK2 with `UserRecordingWriter`. If zero gameplay frames were recorded, keep the file only when stop reason is not `ABORTED_BEFORE_GAMEPLAY`; otherwise delete the temporary file.

- [ ] **Step 4: Add HUD state**

`UserRecordingHudState` contains:

```java
public record UserRecordingHudState(
        boolean visible,
        String primaryText,
        String secondaryText,
        int frame,
        boolean amberWarning,
        boolean redWarning
) {}
```

`UserRecordingHud` renders top-left text and a 1-second hold bar using existing `PixelFont` or debug text helpers. The hold prompt text is exactly: `Hold Shift+Record for 1 Sec to Begin Recording`.

- [ ] **Step 5: Unit-test lifecycle**

Use pure fakes for writer, snapshotter, and input capture:
- start session, add two frames, stop by user, writer receives frameCount 2;
- IO failure sets stop reason `IO_ERROR`;
- HUD state switches from recording to stopped.

Run: `mvn "-Dtest=com.openggf.recording.TestUserRecordingSession" test`

---

### Task 9: Add Session Launcher and Fresh-Start Reinitialization

The launcher bridges current live setup to a fresh current-act recording or manifest-driven playback.

**Files:**
- Create: `src/main/java/com/openggf/recording/UserRecordingSessionLauncher.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Review: `src/main/java/com/openggf/game/launch/LaunchProfile.java`
- Create: `src/test/java/com/openggf/recording/TestUserRecordingSessionLauncher.java`

- [ ] **Step 1: Capture current launch context**

`UserRecordingSessionLauncher.captureCurrentLaunchContext()` reads:
- current `GameModule` game id;
- current zone and act from `GameServices.level()`;
- current main character and configured sidekicks from `SonicConfigurationService`;
- current debug-tools flags for the active game;
- route `"current-act-fresh-start"`.

- [ ] **Step 2: Restart current act fresh**

Add a narrow method to `GameLoop` if none exists:

```java
public void restartFromRecordingLaunchContext(RecordingLaunchContext context) {
    // Set module/config profile, rebuild gameplay session, load zone/act from act start.
}
```

Use the existing title launch/start-level path rather than mutating live sprite/camera state. This must create a new `GameplayModeContext`.

- [ ] **Step 3: Start recording after restart**

`beginRecordingFromCurrentLevel()`:
- rejects non-`LEVEL` mode;
- rejects active Trace Test playback/trace sessions;
- captures context;
- restarts level from context;
- arms `UserRecordingSession` at frame 0.

- [ ] **Step 4: Start playback from manifest**

`beginPlayback(UserRecordingEntry entry, UserRecordingPlaybackOptions options)`:
- reads manifest;
- rebuilds launch context from manifest;
- loads `Bk2Movie`;
- loads `OpenGGF/desync-lite.jsonl`;
- starts `PlaybackDebugManager.startSession(movie, 0)`;
- attaches `UserRecordingVerifier.observer()`;
- creates playback session state.

- [ ] **Step 5: Test launcher guards**

Unit tests should cover:
- non-LEVEL record request is rejected;
- trace/test mode guard rejects recording;
- playback uses manifest context, not current live context;
- record path is `recordings/<game-id>/<game-id>-<zone-act>-YYYY-MM-DD-HHMMSS.bk2`.

Run: `mvn "-Dtest=com.openggf.recording.TestUserRecordingSessionLauncher" test`

---

### Task 10: Add Master-Title Recordings Menu

The menu is available only on the normal master-title game-select screen, not Test Mode Trace Picker.

**Files:**
- Create: `src/main/java/com/openggf/recording/menu/UserRecordingMenu.java`
- Create: `src/main/java/com/openggf/recording/menu/UserRecordingMenuState.java`
- Modify: `src/main/java/com/openggf/game/MasterTitleScreen.java`
- Create: `src/test/java/com/openggf/recording/menu/TestUserRecordingMenu.java`

- [ ] **Step 1: Build text-list menu model**

Follow `TestModeTracePicker` style. The menu needs:
- selected recording list;
- info panel values: filename, frame count, created-at, engine version, launch context;
- options: `pauseOnDesync`, `fastForward`, `targetFrame`;
- warning text when `UserRecordingEntry.versionWarning != NONE`.

- [ ] **Step 2: Input behavior**

When a game entry is highlighted and `Shift+Record` is pressed:
- if `TEST_MODE_ENABLED` is false, open recordings menu for that `gameId`;
- if true, do nothing because Test Mode owns the trace picker.

Inside menu:
- Up/Down changes selected recording;
- Left/Right changes `targetFrame` by 60 frames, clamped;
- Enter prompts for target frame entry;
- `P` toggles pause-on-desync;
- `F` toggles fast-forward;
- Enter on a recording starts playback with options;
- Escape closes menu.

- [ ] **Step 3: Numeric prompt**

Implement a small prompt state in `UserRecordingMenuState` that accepts digits, Backspace, Enter, and Escape. Clamp entered value to `0..frameCount - 1`.

- [ ] **Step 4: Rendering**

Use existing `PixelFont`. Amber warning text is non-blocking and rendered for:
- build mismatch;
- dirty build;
- missing metadata.

Do not display instructional text about features beyond concise control hints already present in the menu.

- [ ] **Step 5: Tests**

`TestUserRecordingMenu` should assert:
- menu opens for normal master title and selected game;
- menu does not open when Test Mode is enabled;
- target frame prompt clamps to movie length;
- toggles update options;
- amber warning state appears for version mismatch but still allows playback.

Run: `mvn "-Dtest=com.openggf.recording.menu.TestUserRecordingMenu" test`

---

### Task 11: Wire GameLoop Recording Controls, Pause, and Fast-Forward

This connects the runtime feature to live frame stepping and rendering.

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/resources/config.yaml`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/graphics/pipeline/UiRenderPipeline.java` or the current HUD rendering owner if `UiRenderPipeline` is not the right insertion point
- Create: `src/test/java/com/openggf/recording/TestUserRecordingControls.java`

- [ ] **Step 1: Add record key config**

Add `SonicConfiguration.RECORDING_RECORD_KEY`, catalog path `debug.recording.recordKey`, and a default that does not conflict with playback, pause, frame-step, rewind, or trace keys. If a conflict is found locally, choose `GLFW_KEY_F9`.

- [ ] **Step 2: Implement 1-second hold**

In `GameLoop`, when in `LEVEL` mode and no trace/debug surface owns input:
- `Shift+Record` held for 60 consecutive frames shows the red top-left prompt and hold bar;
- at 60 frames, call `UserRecordingSessionLauncher.beginRecordingFromCurrentLevel()`;
- release before 60 frames cancels.

Plain `Record` while recording stops the active recording.

- [ ] **Step 3: Feed recording session**

For active recording:
- call `session.beforeLevelFrame(inputHandler)` before gameplay update;
- call `session.afterLevelFrame()` immediately after the `LevelFrameStep.executeWithPause` call in `GameLoop.updateLevelMode`;
- stop on level end, act transition, or explicit user stop.

- [ ] **Step 4: Playback pause behavior**

For recording playback:
- when controller state becomes `PAUSED_AT_TARGET`, `PAUSED_ON_DESYNC`, or `PAUSED_AT_COMPLETION`, pause the engine loop with rendering resumed;
- normal unpause lets the player take over from the paused state;
- call `PlaybackDebugManager.endSession()` when takeover begins so forced input stops.

- [ ] **Step 5: Fast-forward behavior**

When playback options have `fastForward == true`, skip normal scene rendering while playback is running. Render only a frame counter overlay such as `PLAYBACK FF 1234/4567`.

The fast-forward pump must be bounded. Advance multiple gameplay frames per outer loop only up to a fixed frame budget or time budget, then yield back to the main loop so GLFW/window events, Escape, pause, and stop input remain responsive. Do not implement fast-forward as an unbounded tight loop inside one event-poll interval.

End fast-forward and resume normal rendering when:
- pause-on-desync trips;
- target frame is reached;
- movie ends;
- level ends.

The engine still consumes input and advances gameplay as fast as possible during fast-forward.

- [ ] **Step 6: Tests**

Use a fake launcher/session around `GameLoop` through package-private constructor injection. Assert:
- hold reaches launcher at exactly 60 frames;
- releasing at 59 frames does not start;
- plain record stops active recording;
- fast-forward render-suppression flag clears on desync and completion.

Run: `mvn "-Dtest=com.openggf.recording.TestUserRecordingControls" test`

---

### Task 12: Full Integration Verification and Documentation

Finish by running the focused tests first, then a broader build, and update user-facing docs.

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `CONFIGURATION.md`
- Review: `README.md` for a user-facing debug-controls section; update it only if that section exists.
- Review: `docs/architecture/designs/2026-06-29-user-recording-playback-design.md`; update it only if implementation discovers a decision that changes the approved design.

- [ ] **Step 1: Run focused recording suite**

```powershell
mvn "-Dtest=com.openggf.version.TestBuildIdentity,com.openggf.recording.Test*,com.openggf.tests.playback.TestBk2MovieLoader" test
```

If wildcard class selection does not match package names under Surefire, run the listed classes explicitly.

- [ ] **Step 2: Run compile and high-risk existing tests**

```powershell
mvn compile
mvn "-Dtest=com.openggf.testmode.TestModeTracePickerTest,com.openggf.tests.playback.TestBk2MovieLoader" test
```

- [ ] **Step 3: Run ROM-backed smoke when a ROM is available**

```powershell
mvn "-Dtest=com.openggf.recording.TestUserRecordingDeterminismSmoke" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

If no local ROM exists, record the skip in the final implementation summary.

- [ ] **Step 4: Run package**

```powershell
mvn package
```

- [ ] **Step 5: Manual runtime checklist**

With a local ROM:
- start any game to `LEVEL`;
- hold `Shift+Record` for one second and confirm the level restarts from act start;
- play for several seconds;
- press plain `Record` and confirm a BK2 appears under `recordings/<game-id>/`;
- restart to master title;
- highlight the same game and press `Shift+Record`;
- select the movie, set target frame, enable pause-on-desync and fast-forward;
- start playback and confirm fast-forward displays only frame counter;
- confirm target-frame pause resumes rendering and lets player take over;
- temporarily edit manifest version and confirm amber mismatch warning appears but playback remains allowed.

- [ ] **Step 6: Documentation**

Update:
- `CHANGELOG.md` with the new recording/playback feature;
- `CONFIGURATION.md` for `debug.recording.recordKey`;
- `README.md` only if the project has a user-facing controls section for debug tooling.

Commit with trailers required by `.githooks`.

---

### Implementation Guardrails

- Do not add trace fixture generation from user recordings.
- Do not hydrate gameplay state from `OpenGGF/desync-lite.jsonl`.
- Do not branch recording logic by zone, route, or frame number.
- Use `getCentreX()` and `getCentreY()` for player position sidecar fields.
- Keep Test Mode Trace Picker ownership intact: no recordings menu in test mode.
- Keep `Record` separate from playback pause/step/rewind/debug keys.
- Map collapsed engine jump to BK2 `A` for now; leave B/C clear in writer output.
- Keep sidecar frame-index based so sparse sidecars can be added later without changing verifier APIs.
