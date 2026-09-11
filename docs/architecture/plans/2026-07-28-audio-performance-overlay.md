# Audio Performance Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore accurate aggregate audio timing and guarantee that the audio row appears in the six-row performance overlay legend.

**Architecture:** Measure the authoritative outer-frame presentation and device-pump boundary in `Engine`, then credit the elapsed interval through `PerformanceProfiler.recordSectionTime()` so the active `update` section gives up the same time. Keep the panel at six rows, but use a pure selection helper that substitutes `audio` for the sixth row when audio would otherwise rank lower.

**Tech Stack:** Java 21, JUnit 5/Jupiter, Maven.

## Global Constraints

- Runtime audio behavior and scheduling must not change.
- The displayed metric is one aggregate section named exactly `audio`.
- The performance legend remains limited to six rows.
- When present in the snapshot, `audio` must appear exactly once in the legend.
- Do not double-count audio time inside the surrounding `update` section.
- Tests use JUnit 5/Jupiter only.

---

### Task 1: Profile the Authoritative Outer-Frame Audio Boundary

**Files:**
- Modify: `src/test/java/com/openggf/TestGameLoopAudioPresentationModes.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`

**Interfaces:**
- Consumes: `PerformanceProfiler.recordSectionTime(String name, long elapsedNanos)`.
- Produces: `Engine.presentOuterAudioFrame(...)` credits all presentation and device-pump work to the `audio` section.

- [ ] **Step 1: Write the failing accounting regression test**

Add a test beside the existing real-boundary presentation tests. Use the real singleton profiler and the existing real `loop` fixture:

```java
@Test
void outerAudioBoundaryCreditsAggregateAudioMetric() {
    PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    Map<String, Long> samples = new HashMap<>();
    AtomicLong frameNanos = new AtomicLong();
    AtomicLong probeWork = new AtomicLong();
    try {
        profiler.reset();
        profiler.setEnabled(true);
        profiler.setAllocationTrackingEnabled(false);
        profiler.setSampleSink(new FrameSampleSink() {
            @Override
            public void frameSample(String section, long elapsedNanos) {
                samples.put(section, elapsedNanos);
            }

            @Override
            public void frameComplete(long elapsedNanos) {
                frameNanos.set(elapsedNanos);
            }
        });
        profiler.beginFrame();
        profiler.beginSection("update");
        loop.setAudioPresentationProbe(ignored -> {
            for (int i = 0; i < 100_000; i++) {
                probeWork.incrementAndGet();
            }
        });

        Engine.presentOuterAudioFrame(loop, false, false);

        profiler.endSection("update");
        profiler.endFrame();
        assertTrue(samples.getOrDefault("audio", 0L) > 0L);
        assertTrue(samples.getOrDefault("update", 0L) > 0L);
        assertTrue(samples.get("audio") + samples.get("update")
                <= frameNanos.get());
        assertEquals(100_000L, probeWork.get());
    } finally {
        profiler.setSampleSink(null);
        profiler.setAllocationTrackingEnabled(true);
        profiler.reset();
    }
}
```

This catches removal, misplacement, and double-counting of the aggregate credit
at the real production boundary. Keep the existing fixture’s real
`AudioManager` behavior; do not assert calls on a profiler mock. Import
`FrameSampleSink`, `HashMap`, `Map`, and `AtomicLong`.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn "-Dtest=TestGameLoopAudioPresentationModes#outerAudioBoundaryCreditsAggregateAudioMetric" test
```

Expected: FAIL because `samples.getOrDefault("audio", 0L) > 0L` is false on the current implementation.

- [ ] **Step 3: Move timing to the real work**

In `Engine.presentOuterAudioFrame(...)`, measure the entire existing boundary and credit it in a `finally` block:

```java
long audioStartNanos = System.nanoTime();
try {
    Objects.requireNonNull(loop, "loop").presentOuterFrame(
            modalPicker, frameStepRequested);
    GameServices.audio().update();
} finally {
    GameServices.profiler().recordSectionTime(
            "audio", System.nanoTime() - audioStartNanos);
}
```

The `finally` preserves timing-accounting closure if presentation throws. `recordSectionTime()` carves this interval out of the active `update` section without nesting profiler sections.

Remove the `profiler.beginSection("audio")` and `profiler.endSection("audio")` calls from both `GameLoop.updateNonGameplayAudio(...)` and `GameLoop.advanceGameplayAudioFrameForTick(...)`. Preserve `audioUpdatedThisStep = true` and every control-flow condition unchanged.

- [ ] **Step 4: Run focused boundary and profiler tests and verify GREEN**

Run:

```bash
mvn "-Dtest=TestGameLoopAudioPresentationModes,com.openggf.tests.TestPerformanceProfilerGating,com.openggf.debug.TestPerformanceProfilerSampleSink" test
```

Expected: PASS.

- [ ] **Step 5: Commit the timing change**

Stage only the task files and commit with the repository-required trailers:

```bash
git add src/main/java/com/openggf/Engine.java \
  src/main/java/com/openggf/GameLoop.java \
  src/test/java/com/openggf/TestGameLoopAudioPresentationModes.java
git commit -m "fix(debug): profile unified audio presentation" \
  -m "Changelog: n/a: changelog entry staged with the completed overlay visibility change
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Use these exact trailers:

```text
Changelog: n/a: changelog entry staged with the completed overlay visibility change
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

---

### Task 2: Guarantee Audio Legend Visibility

**Files:**
- Modify: `src/test/java/com/openggf/debug/TestPerformancePanelRenderer.java`
- Modify: `src/main/java/com/openggf/debug/PerformancePanelRenderer.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `ProfileSnapshot.getSectionsSortedByTime()`.
- Produces: package-visible `List<SectionStats> legendSections(ProfileSnapshot snapshot)` on `PerformancePanelRenderer`; it returns the renderer's reusable selection containing at most six time-descending entries and `audio` exactly once whenever supplied. `render(...)` consumes this same method.

- [ ] **Step 1: Write failing deterministic selection tests**

Add three tests that construct a renderer with mocked graphics/text
dependencies, populate real snapshots with literal rolling sums, and call the
same package-visible `legendSections(ProfileSnapshot)` method used by
`render(...)`:

```java
@Test
void legendIncludesAudioWhenItRanksBelowSixth() {
    ProfileSnapshot snapshot = snapshot(Map.of(
            "render", 9_000_000L, "update", 8_000_000L,
            "physics", 7_000_000L, "sprites", 6_000_000L,
            "collision", 5_000_000L, "input", 4_000_000L,
            "audio", 1_000_000L));

    List<SectionStats> selected = renderer().legendSections(snapshot);

    assertEquals(List.of("render", "update", "physics", "sprites", "collision", "audio"),
            selected.stream().map(SectionStats::name).toList());
}

@Test
void legendDoesNotDuplicateAudioWhenAlreadyInTopSix() {
    ProfileSnapshot snapshot = snapshot(Map.of(
            "render", 9_000_000L, "audio", 8_000_000L,
            "update", 7_000_000L, "physics", 6_000_000L,
            "sprites", 5_000_000L, "input", 4_000_000L,
            "collision", 3_000_000L));

    List<SectionStats> selected = renderer().legendSections(snapshot);
    assertEquals(List.of("render", "audio", "update", "physics", "sprites", "input"),
            selected.stream().map(SectionStats::name).toList());
}

@Test
void legendKeepsOrdinaryTopSixWhenAudioIsAbsent() {
    ProfileSnapshot snapshot = snapshot(Map.of(
            "render", 9_000_000L, "update", 8_000_000L,
            "physics", 7_000_000L, "sprites", 6_000_000L,
            "collision", 5_000_000L, "input", 4_000_000L,
            "timers", 3_000_000L));

    List<SectionStats> selected = renderer().legendSections(snapshot);
    assertEquals(List.of("render", "update", "physics", "sprites", "collision", "input"),
            selected.stream().map(SectionStats::name).toList());
}

private static ProfileSnapshot snapshot(Map<String, Long> rollingSums) {
    ProfileSnapshot snapshot = new ProfileSnapshot();
    snapshot.populate(rollingSums, 1, new float[] { 16.67f }, 0, 1, 16_670_000L);
    return snapshot;
}

private static PerformancePanelRenderer renderer() {
    return new PerformancePanelRenderer(320, 224,
            mock(PixelFontTextRenderer.class),
            mock(GraphicsManager.class),
            PerformanceProfiler.getInstance());
}
```

- [ ] **Step 2: Run the renderer tests and verify RED**

Run:

```bash
mvn "-Dtest=com.openggf.debug.TestPerformancePanelRenderer" test
```

Expected: test compilation fails because `legendSections(ProfileSnapshot)` does not exist.

- [ ] **Step 3: Implement allocation-reusing legend selection**

Add a reusable renderer field:

```java
private final List<SectionStats> legendSections = new ArrayList<>(6);
```

Add the package-visible production selection seam:

```java
List<SectionStats> legendSections(ProfileSnapshot snapshot) {
    List<SectionStats> sortedSections = snapshot.getSectionsSortedByTime();
    legendSections.clear();
    int limit = Math.min(6, sortedSections.size());
    int audioIndex = -1;
    for (int i = 0; i < sortedSections.size(); i++) {
        if ("audio".equals(sortedSections.get(i).name())) {
            audioIndex = i;
            break;
        }
    }
    if (audioIndex < 0 || audioIndex < limit || limit < 6) {
        for (int i = 0; i < limit; i++) {
            legendSections.add(sortedSections.get(i));
        }
        return legendSections;
    }
    for (int i = 0; i < 5; i++) {
        legendSections.add(sortedSections.get(i));
    }
    legendSections.add(sortedSections.get(audioIndex));
    return legendSections;
}
```

In `render(...)`, replace direct iteration over `snapshot.getSectionsSortedByTime()` and the loop-local count/break with:

```java
for (SectionStats section : legendSections(snapshot)) {
    // existing formatting and drawing body unchanged
}
```

Import `java.util.ArrayList`. Update `CHANGELOG.md` with a concise entry stating that the performance overlay once again reports unified audio cost and keeps it visible.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
mvn "-Dtest=com.openggf.debug.TestPerformancePanelRenderer,TestGameLoopAudioPresentationModes,com.openggf.debug.TestProfileSnapshot" test
```

Expected: PASS.

- [ ] **Step 5: Run policy and full regression verification**

Run:

```bash
mvn test
mvn package
```

Expected: both commands exit 0 with no JUnit failures or policy-guard failures.

- [ ] **Step 6: Commit the visibility fix and documentation**

```bash
git add src/main/java/com/openggf/debug/PerformancePanelRenderer.java \
  src/test/java/com/openggf/debug/TestPerformancePanelRenderer.java \
  CHANGELOG.md \
  docs/architecture/designs/2026-07-28-audio-performance-overlay-design.md \
  docs/architecture/plans/2026-07-28-audio-performance-overlay.md
git commit -m "fix(debug): keep audio visible in performance metrics" \
  -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Use these exact trailers:

```text
Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

---

### Task 3: Integrate, Reverify, and Push

**Files:**
- No source changes expected; conflict resolution may touch only files already in scope.

**Interfaces:**
- Consumes: completed `bugfix/ai-audio-performance-overlay-metric` commits.
- Produces: verified changes merged into and pushed from the main workspace's existing `develop` branch.

- [ ] **Step 1: Confirm both workspaces before integration**

```bash
git -C /home/farrell/code/projects/OpenGGF status --short
git -C /home/farrell/code/projects/OpenGGF branch --show-current
git -C /home/farrell/code/projects/OpenGGF diff --cached --name-only
git status --short
```

Confirm the main workspace remains on `develop`; preserve all pre-existing
dirty files. If the main workspace has any staged file, stop and report it
unless it is known in-scope. Confirm the audio worktree contains no uncommitted
in-scope files. Do not switch the main workspace branch.

- [ ] **Step 2: Update the integration baseline**

From the main workspace:

```bash
git fetch origin
git pull --ff-only origin develop
mvn test
```

Expected: fetch/pull succeed without overwriting user changes and the updated
`develop` baseline passes the full test suite. If the pull conflicts with a
dirty user file, stop integration and report the exact file rather than
stashing or overwriting it.

- [ ] **Step 3: Rebase the bugfix branch onto the updated baseline**

From the isolated worktree:

```bash
git rebase develop
mvn "-Dtest=com.openggf.debug.TestPerformancePanelRenderer,TestGameLoopAudioPresentationModes,com.openggf.debug.TestProfileSnapshot,com.openggf.tests.TestPerformanceProfilerGating,com.openggf.debug.TestPerformanceProfilerSampleSink" test
mvn test
mvn package
```

Expected: rebase succeeds and all focused/full verification passes.

- [ ] **Step 4: Merge into the main workspace branch**

In the main workspace, insert this exact bullet immediately after
`Highlights:` in the current `v0.6.prerelease` section:

```markdown
- **Audio performance metric restored (2026-07-28):** the performance overlay once again measures unified audio presentation and device pumping, and keeps the aggregate audio row visible within its six-line legend.
```

Before editing, verify that the intended README hunk has no overlapping
unstaged user modification. Then stage only `README.md` and start the merge
without committing:

```bash
git add README.md
git merge --no-ff --no-commit bugfix/ai-audio-performance-overlay-metric
git diff --cached --stat
git status --short
git commit -m "Merge branch 'bugfix/ai-audio-performance-overlay-metric' into develop"
```

The staged README update before `git merge` satisfies the repository's
non-`master`-into-`develop` policy. Inspect the staged merge before committing.
Preserve all unrelated user modifications and resolve upstream conflicts only
in the task's files.

- [ ] **Step 5: Run post-merge verification**

From the main workspace:

```bash
mvn test
mvn package
```

Expected: both commands exit 0 with no JUnit or policy failures.

- [ ] **Step 6: Push completed delivery**

```bash
git push origin bugfix/ai-audio-performance-overlay-metric
git push origin develop
```

Do not claim completion unless fetch, pull, baseline verification, merge,
post-merge verification, and both pushes succeed.
