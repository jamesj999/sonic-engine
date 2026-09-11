# LWJGL Runtime-Presentation Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate `LWJGLAudioBackend` to route all production presentation PCM through `StreamBackedDeterministicAudioRuntime`, retiring the backend-private `pcmHistory`/`reverseCursor` duplicate.

**Architecture:** Flip `supportsDeterministicRuntimePresentation()` to true so `AudioManager` installs a real runtime for the LWJGL backend. Delete backend-private ring/cursor state. `fillBuffer()` drains via the runtime. Stream startup is driven from `updateStream()` only (no embedded `startStream()` from command handlers). `updateStream`'s "do we need to refill?" predicate gains a runtime-side activity check so it idles correctly when nothing is playing.

**Tech Stack:** Java 21, JUnit Jupiter (5.x), LWJGL OpenAL backend, Maven (with `-Dmse=relaxed` default via `.mvn/maven.config`). PowerShell when invoking `mvn -Dtest=...` so the `.` in the FQCN isn't reinterpreted.

**Reference spec:** `docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md`.

---

## Task 1: Add `hasActivePresentation()` to the runtime interface

**Files:**
- Modify: `src/main/java/com/openggf/audio/runtime/DeterministicAudioRuntime.java`
- Test: `src/test/java/com/openggf/audio/runtime/TestDeterministicAudioRuntimeDefaults.java` (new)

- [ ] **Step 1: Write failing test for default false**

Create the file with:

```java
package com.openggf.audio.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TestDeterministicAudioRuntimeDefaults {

    @Test
    void noOpRuntimeReportsNoActivePresentationByDefault() {
        assertFalse(NoOpDeterministicAudioRuntime.INSTANCE.hasActivePresentation());
    }
}
```

- [ ] **Step 2: Run test, expect compile failure (method does not exist)**

Run: `mvn "-Dtest=TestDeterministicAudioRuntimeDefaults" test`
Expected: compilation error — `hasActivePresentation` cannot be resolved.

- [ ] **Step 3: Add the default to the interface**

In `DeterministicAudioRuntime.java`, add immediately after the `clearSfxStream` default (around line 60):

```java
    default boolean hasActivePresentation() {
        return false;
    }
```

- [ ] **Step 4: Run test, expect PASS**

Run: `mvn "-Dtest=TestDeterministicAudioRuntimeDefaults" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/runtime/DeterministicAudioRuntime.java \
        src/test/java/com/openggf/audio/runtime/TestDeterministicAudioRuntimeDefaults.java
git commit -m "$(cat <<'EOF'
feat: add DeterministicAudioRuntime.hasActivePresentation default

Default returns false. Override in StreamBackedDeterministicAudioRuntime
to surface FIFO data, bound streams, reverse cursor, and crossfade as
active presentation states. Used by LWJGLAudioBackend.updateStream to
avoid spinning OpenAL refills when nothing is actually playing.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture item 11).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Implement `hasActivePresentation()` in StreamBackedDeterministicAudioRuntime

**Files:**
- Modify: `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java`
- Test: `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntime.java` (existing)

- [ ] **Step 1: Write failing tests for each activity branch**

Append to `TestStreamBackedDeterministicAudioRuntime.java`:

```java
    @Test
    void hasActivePresentation_falseWhenIdle() {
        AudioFrameClock clock = new AudioFrameClock(120, 60);
        AudioOutputFifo fifo = new AudioOutputFifo(120);
        PcmHistoryRing history = new PcmHistoryRing(120);
        StreamBackedDeterministicAudioRuntime runtime =
                new StreamBackedDeterministicAudioRuntime(clock, fifo, history, 4);

        assertFalse(runtime.hasActivePresentation());
    }

    @Test
    void hasActivePresentation_trueWhenMusicStreamBound() {
        AudioFrameClock clock = new AudioFrameClock(120, 60);
        AudioOutputFifo fifo = new AudioOutputFifo(120);
        StreamBackedDeterministicAudioRuntime runtime =
                new StreamBackedDeterministicAudioRuntime(clock, fifo);

        runtime.setMusicStream(new SilentStream());

        assertTrue(runtime.hasActivePresentation());
    }

    @Test
    void hasActivePresentation_trueWhenSfxStreamBound() {
        AudioFrameClock clock = new AudioFrameClock(120, 60);
        AudioOutputFifo fifo = new AudioOutputFifo(120);
        StreamBackedDeterministicAudioRuntime runtime =
                new StreamBackedDeterministicAudioRuntime(clock, fifo);

        runtime.setSfxStream(new SilentStream());

        assertTrue(runtime.hasActivePresentation());
    }

    @Test
    void hasActivePresentation_trueWhenFifoHasData() {
        AudioFrameClock clock = new AudioFrameClock(120, 60);
        AudioOutputFifo fifo = new AudioOutputFifo(120);
        StreamBackedDeterministicAudioRuntime runtime =
                new StreamBackedDeterministicAudioRuntime(clock, fifo);

        fifo.write(new short[]{1, 2, 3, 4}, 2);

        assertTrue(runtime.hasActivePresentation());
    }

    @Test
    void hasActivePresentation_trueWhileReverseCursorActive() {
        AudioFrameClock clock = new AudioFrameClock(120, 60);
        AudioOutputFifo fifo = new AudioOutputFifo(120);
        PcmHistoryRing history = new PcmHistoryRing(8);
        StreamBackedDeterministicAudioRuntime runtime =
                new StreamBackedDeterministicAudioRuntime(clock, fifo, history);
        history.write(new short[]{1, 2, 3, 4}, 2);

        runtime.beginReversePresentation();

        assertTrue(runtime.hasActivePresentation());

        runtime.endReversePresentation();
        assertFalse(runtime.hasActivePresentation());
    }

    @Test
    void hasActivePresentation_trueDuringReleaseCrossfade() {
        AudioFrameClock clock = new AudioFrameClock(120, 60);
        AudioOutputFifo fifo = new AudioOutputFifo(120);
        PcmHistoryRing history = new PcmHistoryRing(8);
        StreamBackedDeterministicAudioRuntime runtime =
                new StreamBackedDeterministicAudioRuntime(clock, fifo, history, 4);
        history.write(new short[]{10, 20, 30, 40}, 2);

        runtime.beginReversePresentation();
        runtime.drainPcm(new short[4], 2);
        runtime.endReversePresentation();

        assertTrue(runtime.hasActivePresentation());
    }
```

If `SilentStream` isn't already defined in the test file, add at the bottom:

```java
    private static final class SilentStream implements com.openggf.audio.AudioStream {
        @Override
        public void read(short[] buffer) {
            java.util.Arrays.fill(buffer, (short) 0);
        }

        @Override
        public boolean isComplete() {
            return false;
        }
    }
```

(Check whether the test file already imports these — adjust if needed.)

- [ ] **Step 2: Run tests, expect FAIL on the new tests**

Run: `mvn "-Dtest=TestStreamBackedDeterministicAudioRuntime" test`
Expected: existing tests pass; six new tests fail (default returns false unconditionally).

- [ ] **Step 3: Add override in `StreamBackedDeterministicAudioRuntime`**

Add after `endReversePresentation()` (around line 167):

```java
    @Override
    public boolean hasActivePresentation() {
        if (musicStream != null || sfxStream != null) {
            return true;
        }
        if (outputFifo.availableFrames() > 0) {
            return true;
        }
        if (reverseCursor != null) {
            return true;
        }
        return releaseCrossfadeRemaining > 0;
    }
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `mvn "-Dtest=TestStreamBackedDeterministicAudioRuntime" test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java \
        src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntime.java
git commit -m "$(cat <<'EOF'
feat: implement hasActivePresentation for StreamBacked runtime

Returns true when any of:
- music or SFX stream is bound,
- output FIFO has unread frames,
- reverse cursor is active,
- release crossfade is in progress.

Consumed by LWJGLAudioBackend.updateStream to keep its hasStream
predicate honest about whether OpenAL refills are still needed once
all gameplay-bound streams clear.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture item 11).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Extract `fillPresentationBuffer` and `isStreamStarted` test seams in LWJGLAudioBackend

These are pure refactors: no behavior change yet. Just expose the seams the later tests will use.

**Files:**
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`

- [ ] **Step 1: Locate the existing `fillBuffer` method (line 674)**

Read lines 674-734 to confirm current shape before editing.

- [ ] **Step 2: Extract `fillPresentationBuffer` and `isStreamStarted` (no semantic change)**

In `LWJGLAudioBackend.java`, replace the body of `fillBuffer` (lines 687-734) with:

```java
        int sampleRate;
        synchronized (streamLock) {
            beginProfileSection("audio.music_stream");
            try {
                fillPresentationBuffer(streamData, STREAM_BUFFER_SIZE);
                sampleRate = (int) Math.round(getStreamSampleRate());
            } finally {
                endProfileSection("audio.music_stream");
            }

            beginProfileSection("audio.sfx_stream");
            try {
                boolean runtimePresentation = runtimeProvidesPresentationPcm();
                if (!runtimePresentation && sfxStream != null) {
                    Arrays.fill(sfxStreamData, (short) 0);
                    sfxStream.read(sfxStreamData);

                    for (int i = 0; i < streamData.length; i++) {
                        int mixed = streamData[i] + sfxStreamData[i];
                        if (mixed > Short.MAX_VALUE)
                            mixed = Short.MAX_VALUE;
                        if (mixed < Short.MIN_VALUE)
                            mixed = Short.MIN_VALUE;
                        streamData[i] = (short) mixed;
                    }

                    if (sfxStream.isComplete()) {
                        sfxStream = null;
                    }
                }
                if (reverseCursor == null && pcmHistory != null) {
                    pcmHistory.write(streamData, STREAM_BUFFER_SIZE);
                }
            } finally {
                endProfileSection("audio.sfx_stream");
            }
        }
```

Then add the new package-private methods immediately after the closing brace of `fillBuffer`:

```java
    /**
     * Test seam for the music-presentation path. Drains the deterministic runtime
     * (or the legacy reverse cursor / backend-private stream while migration is
     * in flight). Package-private so {@code TestLwjglRuntimePresentationRoundTrip}
     * can exercise the drain without standing up OpenAL.
     */
    void fillPresentationBuffer(short[] target, int frames) {
        Arrays.fill(target, 0, frames * 2, (short) 0);
        boolean runtimePresentation = runtimeProvidesPresentationPcm();
        if (reverseCursor != null) {
            reverseCursor.readPrevious(target, frames);
        } else if (runtimePresentation) {
            deterministicAudioRuntime.drainPcm(target, frames);
            clearCompletedRuntimeSfxIfNeeded();
        } else if (currentStream != null) {
            currentStream.read(target);
        }
    }

    /** Test seam: whether the OpenAL streaming buffers have been allocated. */
    boolean isStreamStarted() {
        return streamBuffers != null;
    }
```

- [ ] **Step 3: Build and run all audio tests**

Run: `mvn "-Dtest=com.openggf.audio.**" test`
Expected: all tests pass. This is a pure refactor.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/audio/LWJGLAudioBackend.java
git commit -m "$(cat <<'EOF'
refactor: extract fillPresentationBuffer and isStreamStarted test seams

Pure refactor of LWJGLAudioBackend.fillBuffer. No semantic change; the
extracted methods are package-private hooks for the upcoming
TestLwjglRuntimePresentationRoundTrip. Both seams are referenced by
the migration spec (test seam discussion).

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture item 6).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add `requirePresentationRuntime` helper and attach-time assertion

**Files:**
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`

- [ ] **Step 1: Add helper and assertion**

In `LWJGLAudioBackend.java`, replace the existing `attachDeterministicAudioRuntime` (lines 943-949) with:

```java
    @Override
    public void attachDeterministicAudioRuntime(DeterministicAudioRuntime runtime) {
        synchronized (streamLock) {
            if (supportsDeterministicRuntimePresentation()
                    && !runtime.providesPresentationPcm()) {
                throw new IllegalStateException(
                        "LWJGLAudioBackend declares deterministic runtime presentation"
                                + " support but was attached a runtime that does not"
                                + " provide presentation PCM: "
                                + runtime.getClass().getName());
            }
            deterministicAudioRuntime = runtime;
            bindRuntimePresentationStreams();
        }
    }
```

Then add a private helper near the top of the class (after the field declarations):

```java
    private DeterministicAudioRuntime requirePresentationRuntime() {
        DeterministicAudioRuntime runtime = deterministicAudioRuntime;
        if (!runtime.providesPresentationPcm()) {
            throw new IllegalStateException(
                    "Presentation runtime required but attached runtime is "
                            + runtime.getClass().getName());
        }
        return runtime;
    }
```

(No call sites yet — adopt in Task 9 when collapsing conditionals. We add it now so the seam exists for that task to reference.)

- [ ] **Step 2: Build and run all tests**

Run: `mvn test`
Expected: all tests pass. Assertion is dormant — backend still returns false from `supportsDeterministicRuntimePresentation()`, so the guard's left side is false and short-circuits.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/audio/LWJGLAudioBackend.java
git commit -m "$(cat <<'EOF'
feat: add presentation-runtime assertion in attach + require helper

Assertion in attachDeterministicAudioRuntime fires when this backend
claims supportsDeterministicRuntimePresentation() but is attached a
runtime whose providesPresentationPcm() returns false. Dormant until
the supports flag is flipped in a later task.

requirePresentationRuntime() helper is added but not yet adopted; it
will replace scattered runtimeProvidesPresentationPcm() checks when
the legacy branches are deleted.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture items 5 and 9).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Flip `supportsDeterministicRuntimePresentation()` to true and extend the installation test

This activates the dormant assertion from Task 4. Both backend and existing tests have to keep working with the runtime path live.

**Files:**
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`
- Modify: `src/test/java/com/openggf/audio/TestAudioManagerRuntimeInstallation.java`

- [ ] **Step 1: Add failing assertion to TestAudioManagerRuntimeInstallation**

Append to `TestAudioManagerRuntimeInstallation.java` (before the closing brace of the class):

```java
    @Test
    void presentationBackendReceivesRuntimeThatProvidesPresentationPcm() {
        CapturingPresentationBackend backend = new CapturingPresentationBackend();

        AudioManager.getInstance().setBackend(backend);

        org.junit.jupiter.api.Assertions.assertTrue(
                backend.attachedRuntime.providesPresentationPcm(),
                "Backends that claim presentation support must receive a runtime"
                        + " whose providesPresentationPcm() == true");
    }
```

- [ ] **Step 2: Run the test, expect PASS already**

Run: `mvn "-Dtest=TestAudioManagerRuntimeInstallation" test`
Expected: PASS — the existing `CapturingPresentationBackend` test already exercises this path; the new assertion just makes the invariant explicit.

- [ ] **Step 3: Flip the flag**

In `LWJGLAudioBackend.java`, line 952-954, change:

```java
    @Override
    public boolean supportsDeterministicRuntimePresentation() {
        return false;
    }
```

to:

```java
    @Override
    public boolean supportsDeterministicRuntimePresentation() {
        return true;
    }
```

- [ ] **Step 4: Run all tests**

Run: `mvn test`
Expected: PASS. The backend now installs `StreamBackedDeterministicAudioRuntime` in production. Both code paths (legacy + runtime) still exist inside the backend; the legacy paths just won't get exercised in non-test contexts.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/LWJGLAudioBackend.java \
        src/test/java/com/openggf/audio/TestAudioManagerRuntimeInstallation.java
git commit -m "$(cat <<'EOF'
feat: enable deterministic runtime presentation for LWJGL backend

Flips supportsDeterministicRuntimePresentation() to true so
AudioManager installs StreamBackedDeterministicAudioRuntime for the
production backend. Backend-private pcmHistory/reverseCursor still
exist as fallback paths and are deleted in subsequent tasks; existing
audio paths continue to work because every call site already updates
both the legacy fields and the runtime in lock-step.

TestAudioManagerRuntimeInstallation extended to assert the installed
runtime is one that provides presentation PCM.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture item 1 and test #7).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Remove embedded `startStream()` from `playMusicSmps`, `playSmps`, and `playSfxSmps`

After this task, stream startup is `updateStream()`'s job exclusively.

**Files:**
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`

- [ ] **Step 1: Remove `startStream()` from `playMusicSmps`**

In `LWJGLAudioBackend.java`, locate the end of `playMusicSmps` (around line 331). Delete the `startStream();` call. The method should end with the closing brace after the synchronized block that sets `currentStream = smpsDriver;`.

- [ ] **Step 2: Remove `startStream()` from `playSmps`**

Around line 422. Delete the `startStream();` at the end of the method.

- [ ] **Step 3: Remove `startStream()`-like logic from `playSfxSmps`**

Around lines 524-530, delete:

```java
        // Ensure stream is running
        int queued = alGetSourcei(musicSource, AL_BUFFERS_QUEUED);
        if (queued == 0) {
            alSourceStop(musicSource);
            alSourcei(musicSource, AL_BUFFER, 0);
            startStream();
        }
```

`updateStream()` runs at the regular tick cadence and will pick this up.

- [ ] **Step 4: Run all tests**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/LWJGLAudioBackend.java
git commit -m "$(cat <<'EOF'
fix: defer stream startup to updateStream for runtime-replay safety

When the consuming runtime processes commands inside advanceFrame, it
can invoke AudioManager.replayTimelineCommand which calls backend
playSmps. If playSmps then calls startStream synchronously, startStream
prefills via fillBuffer which drains the runtime FIFO before this
frame's PCM has been produced — initial latency at best, reentrant
ownership bug at worst.

updateStream already has the if-not-started-then-start path, so
deferring is mechanically sufficient.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture item 10).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Tighten `updateStream`'s `hasStream` predicate via `hasActivePresentation`

**Files:**
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`

- [ ] **Step 1: Update `updateStream`**

In `LWJGLAudioBackend.java`, locate the `hasStream` block in `updateStream` (line 588-591). Replace:

```java
        boolean hasStream;
        synchronized (streamLock) {
            hasStream = currentStream != null || sfxStream != null || runtimeProvidesPresentationPcm();
        }
```

with:

```java
        boolean hasStream;
        synchronized (streamLock) {
            hasStream = currentStream != null
                    || sfxStream != null
                    || deterministicAudioRuntime.hasActivePresentation();
        }
```

- [ ] **Step 2: Run all tests**

Run: `mvn test`
Expected: PASS. Existing tests bound runtimes via `runtimeProvidesPresentationPcm`; `hasActivePresentation` defaults to false on `NoOpDeterministicAudioRuntime`, so test fixtures that pinned NoOp see hasStream become false earlier — which is the intended behavior (idle).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/audio/LWJGLAudioBackend.java
git commit -m "$(cat <<'EOF'
fix: drive updateStream hasStream off runtime activity, not capability

Before: hasStream = ... || runtimeProvidesPresentationPcm() — which,
post-migration, is permanently true. updateStream would keep refilling
OpenAL buffers with silence forever even when no music/SFX is active.

After: hasStream uses runtime.hasActivePresentation(), which only
reports true when there's actually something to drain (bound stream,
FIFO data, reverse cursor, release crossfade).

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture item 11).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Reshape `playSfxSmps` around `smpsDriver != null`

**Files:**
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`

- [ ] **Step 1: Locate `playSfxSmps` (around line 436)**

Read lines 436-523 to confirm current shape.

- [ ] **Step 2: Replace the mix-vs-standalone conditional**

Around line 473, replace:

```java
        if (smpsDriver != null && (currentStream == smpsDriver || runtimeProvidesPresentationPcm())) {
```

with:

```java
        if (smpsDriver != null) {
```

This intentionally narrows: SFX is mixed into the music driver when music is bound, otherwise the standalone SFX driver path runs. The `currentStream == smpsDriver` and runtime checks are noise now that all production playback flows through the runtime.

- [ ] **Step 3: Run all tests**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/audio/LWJGLAudioBackend.java
git commit -m "$(cat <<'EOF'
refactor: branch playSfxSmps on smpsDriver presence

Replaces the legacy currentStream == smpsDriver || runtime-presentation
disjunction with the clearer intent: if music is active (smpsDriver
non-null), mix the SFX sequencer into it; otherwise create or reuse
the standalone SFX driver.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture item 7).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Delete backend-private `pcmHistory` / `reverseCursor`; simplify `fillBuffer`; make reverse begin/end no-ops; remove `audio.sfx_stream` profile section

This is the biggest deletion task. After it, the backend has no presentation duplicate of its own.

**Files:**
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`

- [ ] **Step 1: Delete the field declarations**

Find and delete the `pcmHistory` and `reverseCursor` field declarations near the top of the class. They look like:

```java
    private PcmHistoryRing pcmHistory;
    private PcmHistoryRing.ReverseCursor reverseCursor;
```

- [ ] **Step 2: Delete the `pcmHistory` allocation in `init()`**

Around line 194, delete:

```java
            pcmHistory = new PcmHistoryRing(Math.max(STREAM_BUFFER_SIZE, deviceSampleRate * 10));
```

- [ ] **Step 3: Simplify `fillBuffer` and `fillPresentationBuffer`**

Replace the whole `fillBuffer` body with:

```java
        int sampleRate;
        synchronized (streamLock) {
            beginProfileSection("audio.music_stream");
            try {
                fillPresentationBuffer(streamData, STREAM_BUFFER_SIZE);
                sampleRate = (int) Math.round(getStreamSampleRate());
            } finally {
                endProfileSection("audio.music_stream");
            }
        }
```

Replace `fillPresentationBuffer` with:

```java
    /**
     * Test seam for the music-presentation path. Drains the deterministic
     * runtime, which is the sole source of presentation PCM post-migration.
     * Package-private so {@code TestLwjglRuntimePresentationRoundTrip} can
     * exercise the drain without standing up OpenAL.
     */
    void fillPresentationBuffer(short[] target, int frames) {
        Arrays.fill(target, 0, frames * 2, (short) 0);
        requirePresentationRuntime().drainPcm(target, frames);
        clearCompletedRuntimeSfxIfNeeded();
    }
```

- [ ] **Step 4: Make `beginReversePresentation` and `endReversePresentation` no-ops**

Around line 962-976, replace both methods with:

```java
    @Override
    public void beginReversePresentation() {
        // No-op: AudioManager.beginReverseAudioPresentation already invokes
        // deterministicAudioRuntime.beginReversePresentation independently.
        // The backend has no reverse-presentation state of its own
        // post-migration.
    }

    @Override
    public void endReversePresentation() {
        // No-op: symmetric with beginReversePresentation above.
    }
```

- [ ] **Step 5: Audit remaining `runtimeProvidesPresentationPcm()` call sites**

Find every remaining call to `runtimeProvidesPresentationPcm()` in `LWJGLAudioBackend.java`. For each, either:

- If the conditional always takes the runtime branch now, delete the conditional and keep only the runtime branch.
- If the call site exists outside the streamLock or in a code path where the runtime might not yet be attached (look for early init paths), leave it; otherwise simplify.

Targets to simplify based on the spec:
- `playMusicSmps` line 326-328 — collapse to unconditional `deterministicAudioRuntime.setMusicStream(smpsDriver);`
- `playSmps` line 355-357 — collapse to unconditional `deterministicAudioRuntime.clearSfxStream();`
- `playSmps` line 380-382 — collapse to unconditional `deterministicAudioRuntime.clearSfxStream();`
- `playSmps` line 417-419 — collapse to unconditional `deterministicAudioRuntime.setMusicStream(smpsDriver);`
- `stopStream` line 568-571 — collapse to unconditional `deterministicAudioRuntime.clearMusicStream(); deterministicAudioRuntime.flushPresentationFifo();`
- `playSfxSmps` line 518-520 — collapse to unconditional `deterministicAudioRuntime.setSfxStream(sfxDriver);`

Remove the `runtimeProvidesPresentationPcm` private method itself if no callers remain.

- [ ] **Step 6: Verify imports are clean**

Remove any now-unused imports (e.g., `PcmHistoryRing` import if no field referenced).

- [ ] **Step 7: Run all tests**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/audio/LWJGLAudioBackend.java
git commit -m "$(cat <<'EOF'
refactor: retire backend-private pcmHistory and reverse cursor

After this commit, LWJGLAudioBackend has no presentation duplicate of
its own:
- pcmHistory and reverseCursor fields deleted along with their init
  allocation and fillBuffer writes.
- fillPresentationBuffer drains the runtime directly, via the new
  requirePresentationRuntime helper.
- beginReversePresentation and endReversePresentation are now true
  no-ops; AudioManager.beginReverseAudioPresentation already calls the
  runtime side independently.
- The audio.sfx_stream profile section is removed: SFX mixing happens
  inside StreamBackedDeterministicAudioRuntime.advanceFrame; WAV one-
  shot cleanup runs from update() not fillBuffer; DirectBuffer prep is
  covered by audio.upload. Keeping the section under a changed meaning
  would silently rewrite profiler-timeline history.
- All runtimeProvidesPresentationPcm() conditionals collapsed.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(architecture items 2, 3, 4, 6, 7, 8 and profile-sections subsection).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Add the `ScriptedSmpsDriver` test double

This is the test infrastructure for Task 11's round-trip test. Implements `AudioStream` and emits a known sample pattern.

**Files:**
- Create: `src/test/java/com/openggf/audio/ScriptedAudioStream.java`

- [ ] **Step 1: Create the test double**

```java
package com.openggf.audio;

/**
 * Test double that emits a deterministic ramp through AudioStream.read.
 * Not an SmpsDriver — that would force test fixtures to construct sequencer
 * dependencies they don't need. The runtime accepts any AudioStream via
 * setMusicStream/setSfxStream.
 */
public final class ScriptedAudioStream implements AudioStream {
    private short next;
    private final short step;
    private boolean complete;

    public ScriptedAudioStream(short initial, short step) {
        this.next = initial;
        this.step = step;
    }

    @Override
    public void read(short[] buffer) {
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = next;
            next = (short) (next + step);
        }
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    public void markComplete() {
        complete = true;
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `mvn test-compile`
Expected: clean compile.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/audio/ScriptedAudioStream.java
git commit -m "$(cat <<'EOF'
test: add ScriptedAudioStream test double for runtime presentation tests

Deterministic ramp generator implementing AudioStream. Consumed by
TestLwjglRuntimePresentationRoundTrip in the next commit to assert PCM
routes through the runtime without depending on SmpsDriver startup
behavior or test-fixture wiring of sequencer dependencies.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(testing section, test #1).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Create `TestLwjglRuntimePresentationRoundTrip`

This file holds the routing/ownership tests from the spec. It tests the migrated backend by driving `AudioManager` through a `LWJGLAudioBackend` subclass that stubs out OpenAL.

**Files:**
- Create: `src/test/java/com/openggf/audio/TestLwjglRuntimePresentationRoundTrip.java`

- [ ] **Step 1: Sketch the headless backend fixture**

The test needs to instantiate something `LWJGLAudioBackend`-shaped without OpenAL. The simplest practical path: in the test file, define a subclass that overrides any methods that call into LWJGL/OpenAL native code. If `LWJGLAudioBackend.init()` cannot be made headless, the alternative is to test against a sibling that uses `StreamBackedDeterministicAudioRuntime` directly without going through `LWJGLAudioBackend`. **Pick whichever is mechanically easier given what the current `LWJGLAudioBackend` exposes.**

For the test file scaffolding below, assume a subclass strategy. If the subclass approach is infeasible because OpenAL methods are baked too deep into non-virtual code, replace `HeadlessLwjglBackend` with a sibling test fixture that wires `StreamBackedDeterministicAudioRuntime` directly to a `ScriptedAudioStream` and exposes the same `fillPresentationBuffer`/`isStreamStarted` semantics.

- [ ] **Step 2: Write the test file**

Create with this skeleton — fill in the fixture's exact implementation based on what works:

```java
package com.openggf.audio;

import com.openggf.audio.runtime.DeterministicAudioRuntime;
import com.openggf.audio.runtime.StreamBackedDeterministicAudioRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration acceptance tests for LWJGLAudioBackend → StreamBackedDeterministicAudioRuntime.
 * Exercises routing, ownership, and the startup-deferral invariant without
 * touching OpenAL.
 */
class TestLwjglRuntimePresentationRoundTrip {

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
    }

    @Test
    void forwardMusicProducesPcmThroughRuntimeWithoutStartupReentrancy() {
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        ScriptedAudioStream ramp = new ScriptedAudioStream((short) 0, (short) 1);
        backend.bindMusicStreamForTest(ramp);

        // Startup must be deferred to updateStream — playMusicSmps must not
        // have prefilled OpenAL itself.
        assertFalse(backend.isStreamStarted(),
                "Stream must not be started until updateStream runs");

        backend.tickUpdateStream();
        assertTrue(backend.isStreamStarted(),
                "updateStream must drive startup once a stream is bound");

        short[] buf = new short[16];
        backend.fillPresentationBuffer(buf, 4);
        // Expect the first 8 samples of the ramp (4 frames × 2 channels).
        for (short i = 0; i < 8; i++) {
            assertEquals(i, buf[i], "ramp sample " + i);
        }
    }

    @Test
    void sfxRoutesIntoMusicDriverWhenMusicActive() {
        // Strategy: assert post-call ownership invariants. sfxStream stays
        // null because SFX was mixed into the music driver instead of
        // creating a standalone driver.
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        backend.simulateActiveMusicForTest();

        backend.simulateSfxSmpsForTest();

        assertNull(backend.peekSfxStream(),
                "When music is active, SFX must mix into music driver, not create sfxStream");
    }

    @Test
    void standaloneSfxActivatesWhenNoMusic() {
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        // No music active.
        backend.simulateSfxSmpsForTest();

        assertNotNull(backend.peekSfxStream(),
                "With no music driver, SFX must create the standalone SFX driver");
    }

    @Test
    void reversePlaybackDrainsFromRuntimeRing() {
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        ScriptedAudioStream ramp = new ScriptedAudioStream((short) 100, (short) 1);
        backend.bindMusicStreamForTest(ramp);

        // Advance the runtime to fill its history ring with the ramp.
        backend.advanceRuntimeFramesForTest(8);

        AudioManager.getInstance().beginReverseAudioPresentation();
        short[] buf = new short[16];
        backend.fillPresentationBuffer(buf, 4);

        // Reverse cursor reads backwards. The most recent samples come first.
        // The exact values depend on samplesForNextFrame() and frames advanced;
        // assert at least that the buffer is non-zero (reverse cursor ran).
        boolean anyNonZero = false;
        for (short s : buf) {
            if (s != 0) { anyNonZero = true; break; }
        }
        assertTrue(anyNonZero, "Reverse drain must produce non-zero samples");

        AudioManager.getInstance().endReverseAudioPresentation();
    }

    @Test
    void restoreMusicRebindsDriverIntoRuntime() {
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        // Simulate music A, then override with music B, then restore.
        AudioStream driverA = backend.simulateMusicAForTest();
        backend.simulateOverrideMusicBForTest();
        backend.simulateRestoreMusicForTest();

        assertSame(driverA, backend.peekRuntimeMusicStream(),
                "restoreMusic must rebind A's driver into the runtime");
    }

    @Test
    void stopStream_explicitClearsRuntimeMusic() {
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        backend.simulateActiveMusicForTest();
        assertNotNull(backend.peekRuntimeMusicStream());

        backend.simulateStopStreamForTest();
        assertNull(backend.peekRuntimeMusicStream());
    }

    @Test
    void stopStream_nonOverrideMusicClearsStandaloneSfxDeliberately() {
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        backend.simulateStandaloneSfxForTest();
        assertNotNull(backend.peekSfxStream());

        backend.simulateNonOverrideMusicStartForTest();
        assertNull(backend.peekSfxStream(),
                "Starting non-override music must deliberately clear standalone SFX");
    }

    @Test
    void restoreMusicPreservesStandaloneSfx() {
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        backend.simulateMusicAForTest();
        backend.simulateOverrideMusicBForTest();
        backend.simulateStandaloneSfxForTest();

        AudioStream sfxBeforeRestore = backend.peekSfxStream();
        backend.simulateRestoreMusicForTest();

        assertSame(sfxBeforeRestore, backend.peekSfxStream(),
                "restoreMusic must preserve and rebind standalone SFX");
        assertSame(sfxBeforeRestore, backend.peekRuntimeSfxStream(),
                "restoreMusic must rebind standalone SFX into the runtime");
    }

    /**
     * Headless test fixture. Extends LWJGLAudioBackend if that's feasible
     * (overriding any methods that call OpenAL natives to no-ops), OR mimics
     * its public surface using StreamBackedDeterministicAudioRuntime directly
     * if subclassing isn't workable. Pick the cleanest approach during
     * implementation.
     *
     * Methods like simulateActiveMusicForTest, peekSfxStream, etc. exist
     * solely to give the test ownership/binding assertions a stable surface
     * that doesn't require reaching into private state.
     */
    private static final class HeadlessLwjglBackend extends NullAudioBackend {
        // Implementation TBD during execution: choose subclass-LWJGLAudioBackend
        // or direct-runtime-wiring based on which avoids OpenAL natives cleanly.
        // The methods called above must all be implemented.

        @Override
        public boolean supportsDeterministicRuntimePresentation() {
            return true;
        }

        @Override
        public int outputSampleRate() {
            return 240;
        }

        // ... the simulate*ForTest / peek* / tickUpdateStream / bindMusicStreamForTest
        // / fillPresentationBuffer / isStreamStarted / advanceRuntimeFramesForTest
        // methods are provided here using whatever wiring proves practical.
    }
}
```

**Important implementation note:** the `HeadlessLwjglBackend` is sketched. During execution, decide whether to:
- (Path A) Subclass `LWJGLAudioBackend` and override OpenAL-touching methods. Requires those methods be virtual / package-private. If `LWJGLAudioBackend.init()` is too OpenAL-coupled, this won't work cleanly.
- (Path B) Use `StreamBackedDeterministicAudioRuntime` directly and mimic the backend's orchestration in the test fixture itself. Loses some "tests the real LWJGLAudioBackend" fidelity but is mechanically simple.

Pick whichever produces a passing test suite without OpenAL natives or reflection. Document the choice in a code comment at the top of `HeadlessLwjglBackend`.

- [ ] **Step 3: Run the new test file**

Run: `mvn "-Dtest=TestLwjglRuntimePresentationRoundTrip" test`
Expected: all eight tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/openggf/audio/TestLwjglRuntimePresentationRoundTrip.java
git commit -m "$(cat <<'EOF'
test: add LWJGL runtime-presentation round-trip migration acceptance tests

Eight tests covering the routing, ownership, and startup-deferral
invariants the migration introduces: forward music produces PCM
through the runtime without startStream reentrancy, SFX routes into
the music driver when music is active and stands alone otherwise,
reverse playback drains from the runtime ring, restoreMusic rebinds
properly, and stopStream's three caller-path semantics are split out
and asserted independently.

The test uses a headless backend fixture documented at its top to
record the wiring choice (subclass vs direct runtime).

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(testing section, tests #1–6).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Add the real-SMPS smoke test

A single integration test confirming that with real S2 test SMPS data, music still routes end-to-end and produces non-silent PCM.

**Files:**
- Modify: `src/test/java/com/openggf/audio/TestLwjglRuntimePresentationRoundTrip.java`

- [ ] **Step 1: Add the smoke test**

Append to `TestLwjglRuntimePresentationRoundTrip.java`:

```java
    @Test
    void realSmpsDataProducesNonSilentPcmThroughRuntime() {
        // Smoke test: real SMPS data routes through the full path and
        // eventually produces audible samples. Does not assert PCM shape;
        // only "after enough frames, some sample is non-zero."
        HeadlessLwjglBackend backend = new HeadlessLwjglBackend();
        AudioManager.getInstance().setBackend(backend);

        backend.startRealSmpsMusicForTest();
        // SMPS startup can include rests/fades — pick N large enough to
        // outlast that. 240 frames at the test's outputSampleRate is ~1s.
        backend.advanceRuntimeFramesForTest(240);

        short[] buf = new short[backend.outputSampleRate() / 60 * 2];
        backend.tickUpdateStream();
        backend.fillPresentationBuffer(buf, buf.length / 2);

        boolean anyNonZero = false;
        for (short s : buf) {
            if (s != 0) { anyNonZero = true; break; }
        }
        assertTrue(anyNonZero, "Real SMPS data must produce audible samples through the runtime path");
    }
```

`HeadlessLwjglBackend.startRealSmpsMusicForTest()` should load and start a known-good piece of test SMPS data (e.g., a short canned SMPS file checked in under `src/test/resources/audio/` or built inline). If no suitable fixture exists in the repository, create a minimal one or use the existing test fixtures referenced from `TestStreamBackedDeterministicAudioRuntime` if any.

- [ ] **Step 2: Run the test**

Run: `mvn "-Dtest=TestLwjglRuntimePresentationRoundTrip#realSmpsDataProducesNonSilentPcmThroughRuntime" test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/audio/TestLwjglRuntimePresentationRoundTrip.java
git commit -m "$(cat <<'EOF'
test: real-SMPS smoke test for runtime presentation migration

Single end-to-end smoke confirming real SMPS data routes through
LWJGLAudioBackend -> StreamBackedDeterministicAudioRuntime ->
fillPresentationBuffer and eventually produces non-silent PCM. Does
not assert PCM shape; only that synthesis actually runs.

Spec: docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md
(testing section, real-SMPS smoke test).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Full test sweep and final verification

**Files:** none

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: all tests pass.

- [ ] **Step 2: If any test fails, investigate before fixing**

Use `superpowers:systematic-debugging`. Do not paper over failures with try/catch or test skips. If a previously-passing test now fails, find the root cause in the migration changes, fix the source, and re-run.

- [ ] **Step 3: Confirm no spec items left undone**

Cross-check against `docs/architecture/designs/2026-05-26-lwjgl-runtime-presentation-migration-design.md`. Every Files Touched entry should have a corresponding commit. Every test listed in the spec should exist.

- [ ] **Step 4: Final commit (if any docs need updating, e.g., a note in the spec confirming implementation status)**

If the spec status needs updating ("Approved" → "Implemented"), update it and commit. Otherwise no final commit needed.

---

## Self-Review

**Spec coverage:** Migration spec items 1–11 plus testing section all map to tasks above. Task 1 → spec item 11 prep (runtime interface). Task 2 → spec item 11 (runtime impl). Task 3 → spec architecture items 6 (fillPresentationBuffer seam) and the isStreamStarted seam. Task 4 → spec architecture items 5 (assertion) and 9 (requirePresentationRuntime). Task 5 → spec architecture item 1 (flip the switch) and testing spec test #7. Task 6 → spec architecture item 10 (no embedded startStream). Task 7 → spec architecture item 11 (hasStream predicate). Task 8 → spec architecture item 7 (playSfxSmps reshape). Task 9 → spec architecture items 2, 3, 4, 6, 7, 8, plus profile-section subsection and the `runtimeProvidesPresentationPcm` collapse from item 8. Tasks 10–12 → testing section tests #1–6 plus the real-SMPS smoke test. Task 13 → final acceptance gate.

**Placeholders:** Task 11 has a documented choice point (Path A vs Path B for the headless fixture) — that's an intentional implementation decision, not a placeholder. All other tasks contain the actual code to write.

**Type consistency:** `hasActivePresentation` is the chosen name in both Task 1 and Task 7. `fillPresentationBuffer` consistent across Tasks 3, 9, 11, 12. `isStreamStarted` consistent across Tasks 3 and 11. `requirePresentationRuntime` consistent across Tasks 4 and 9.

**Architectural note:** The shared mutable `SmpsDriver` concern raised by the external reviewer remains unaddressed in this plan because it belongs to the parked resynth spec (which has its own per-burst snapshot/restore design). The migration alone does not introduce or amplify the issue; pre-migration the same drivers were shared, and post-migration the path is just clearer.
