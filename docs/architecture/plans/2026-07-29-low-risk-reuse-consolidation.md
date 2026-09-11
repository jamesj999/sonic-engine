# Low-Risk Reuse Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` or `superpowers:executing-plans` to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Replace duplicated trace-file I/O, ROM-header detection
orchestration, and HUD static-art assembly with narrow shared implementations
while preserving all public game-specific entry points and supported-input
behavior.

**Architecture:** Add one neutral utility or template per duplicated algorithm.
Keep parsing, game matching policy, and game-owned HUD policy at their existing
owners. Preserve public wrappers and verify every extraction with explicit
cross-game compatibility tests.

**Tech Stack:** Java 21, JUnit 5, Mockito, Maven.

## Global constraints

- Runtime assets remain ROM-only; this work does not add or move asset bytes.
- Shared code must not branch on game or zone identity.
- Preserve domestic-first detector reads, current normalization, detector
  priorities, S1 exclusions, and all S3K aliases.
- Preserve S1 HUD null semantics, S2 donor palette semantics, S3K empty flash
  mappings, and `Pattern` identity/order.
- Trace utilities remain comparison/file-format infrastructure and must not
  acquire gameplay or hardware-timing authority.
- Use JDK 21 and JUnit Jupiter only.
- Follow red-green-refactor: no production extraction before its new test has
  failed for the expected missing API or behavior.
- Stage only files listed by the active task plus required documentation.

---

### Task 1: Neutral trace-file utility

**Files:**

- Create: `src/main/java/com/openggf/trace/TraceFiles.java`
- Create: `src/test/java/com/openggf/trace/TraceFilesTest.java`
- Modify: `src/main/java/com/openggf/trace/TraceData.java`
- Modify: `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- Modify: `src/main/java/com/openggf/trace/SpecialStageTraceData.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageTraceData.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/S3kSpecialStageTraceData.java`
- Modify only where semantics match:
  `src/test/java/com/openggf/tests/trace/TestTraceReplayStartPositionPolicy.java`
- Modify only where semantics match:
  `src/test/java/com/openggf/tests/trace/TestTraceAnimationRecorderContract.java`

**Interfaces:**

- Produces:
  `TraceFiles.resolve(Path directory, String fileName) -> Path`
- Produces:
  `TraceFiles.openReader(Path path) -> BufferedReader throws IOException`
- Preserves:
  `TraceData.resolveTraceFile` and `TraceData.openTraceReader` as deprecated
  forwarding methods.

- [ ] **Step 1: Write the failing utility and forwarding-contract tests**

Create `TraceFilesTest` using `@TempDir`. Its tests must:

```java
@Test
void resolvePrefersRegularPlainFileOverGzip(@TempDir Path dir) throws Exception {
    Path plain = Files.writeString(dir.resolve("physics.csv"), "plain");
    writeGzip(dir.resolve("physics.csv.gz"), "gzip");
    assertEquals(plain, TraceFiles.resolve(dir, "physics.csv"));
}

@Test
void resolveFallsBackToRegularGzipFile(@TempDir Path dir) throws Exception {
    Path gzip = dir.resolve("physics.csv.gz");
    writeGzip(gzip, "gzip");
    assertEquals(gzip, TraceFiles.resolve(dir, "physics.csv"));
}

@Test
void resolveRejectsDirectoriesAndMissingFiles(@TempDir Path dir) throws Exception {
    Files.createDirectory(dir.resolve("physics.csv"));
    assertNull(TraceFiles.resolve(dir, "physics.csv"));
    assertNull(TraceFiles.resolve(dir, "missing.csv"));
}

@Test
void openReaderReadsPlainAndGzipAsUtf8(@TempDir Path dir) throws Exception {
    String expected = "Sonic – ソニック";
    Path plain = Files.writeString(dir.resolve("plain.csv"), expected, UTF_8);
    Path gzip = dir.resolve("gzip.csv.gz");
    writeGzip(gzip, expected);
    try (BufferedReader plainReader = TraceFiles.openReader(plain);
         BufferedReader gzipReader = TraceFiles.openReader(gzip)) {
        assertEquals(expected, plainReader.readLine());
        assertEquals(expected, gzipReader.readLine());
    }
}

@Test
void malformedGzipThrowsIOException(@TempDir Path dir) throws Exception {
    Path malformed = Files.writeString(dir.resolve("bad.csv.gz"), "not gzip");
    assertThrows(IOException.class, () -> TraceFiles.openReader(malformed));
}

@Test
void traceDataForwardersDelegateToSharedContract(@TempDir Path dir) throws Exception {
    Path gzip = dir.resolve("physics.csv.gz");
    writeGzip(gzip, "forwarded");
    assertEquals(gzip, TraceData.resolveTraceFile(dir, "physics.csv"));
    try (BufferedReader reader = TraceData.openTraceReader(gzip)) {
        assertEquals("forwarded", reader.readLine());
    }
}
```

Use a private `writeGzip(Path, String)` helper with `GZIPOutputStream` and
UTF-8 bytes.

- [ ] **Step 2: Run the new test and verify RED**

Run:

```bash
mvn -Dmse=off "-Dtest=com.openggf.trace.TraceFilesTest" test
```

Expected: compilation fails because `TraceFiles` does not exist. This is the
required red state.

- [ ] **Step 3: Implement the minimal utility**

Create a final class with a private constructor:

```java
public static Path resolve(Path directory, String fileName) {
    Path plain = directory.resolve(fileName);
    if (Files.isRegularFile(plain)) {
        return plain;
    }
    Path gzip = directory.resolve(fileName + ".gz");
    return Files.isRegularFile(gzip) ? gzip : null;
}

public static BufferedReader openReader(Path path) throws IOException {
    if (!path.getFileName().toString().endsWith(".gz")) {
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }
    InputStream input = Files.newInputStream(path);
    try {
        return new BufferedReader(new InputStreamReader(
                new GZIPInputStream(input), StandardCharsets.UTF_8));
    } catch (IOException e) {
        input.close();
        throw e;
    }
}
```

- [ ] **Step 4: Verify utility GREEN**

Run the Step 2 command. Expected: all `TraceFilesTest` tests pass.

- [ ] **Step 5: Migrate production consumers and retain forwarders**

Replace matching private/static implementations with `TraceFiles.resolve` and
`TraceFiles.openReader`. In `TraceData`, keep:

```java
@Deprecated(forRemoval = false)
public static Path resolveTraceFile(Path traceDirectory, String fileName) {
    return TraceFiles.resolve(traceDirectory, fileName);
}

@Deprecated(forRemoval = false)
public static BufferedReader openTraceReader(Path path) throws IOException {
    return TraceFiles.openReader(path);
}
```

Update special-stage loaders to call `TraceFiles` directly. Update test-local
helpers only when they use plain-first resolution; retain and comment the
compressed-first fixture policy in `copyTraceFile`.

- [ ] **Step 6: Run focused trace tests**

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.trace.TraceFilesTest,com.openggf.trace.catalog.TraceCatalogTest,com.openggf.tests.trace.TestTraceDataParsing,com.openggf.tests.trace.TestTraceReplayStartPositionPolicy,com.openggf.tests.trace.TestTraceAnimationRecorderContract,com.openggf.tests.trace.TestS1SpecialStageTraceParsing,com.openggf.tests.trace.TestS3kSpecialStageTraceParsing,com.openggf.trace.timing.TestHardwareTimingAuthorityGuard" \
  test
```

Expected: zero failures and errors.

- [ ] **Step 7: Commit the trace extraction**

Stage the exact task files and commit:

```text
refactor: consolidate trace file readers

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 2: Shared ROM-header detector template

**Files:**

- Create: `src/main/java/com/openggf/game/AbstractHeaderNameRomDetector.java`
- Create: `src/test/java/com/openggf/game/TestHeaderNameRomDetectors.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1RomDetector.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2RomDetector.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kRomDetector.java`

**Interfaces:**

- Produces abstract hooks:
  `matchesNormalizedName(String) -> boolean` and `logger() -> Logger`.
- Produces protected final template method:
  `canHandleHeaderName(Rom) -> boolean`.
- Preserves all methods of the three public concrete detectors.
- Each concrete detector remains non-final and declares an
  `@Override public boolean canHandle(Rom)` forwarding method that is itself
  non-final.

- [ ] **Step 1: Write failing template tests**

Use Mockito `Rom` instances. Cover the common template with concrete detectors:

```java
@Test
void domesticMatchShortCircuitsInternationalRead() throws Exception {
    Rom rom = openRom("SONIC THE HEDGEHOG 2", "unused");
    assertTrue(new Sonic2RomDetector().canHandle(rom));
    verify(rom, never()).readInternationalName();
}

@Test
void internationalNameIsReadAfterDomesticMiss() throws Exception {
    Rom rom = openRom("OTHER", "SONIC THE HEDGEHOG 2");
    assertTrue(new Sonic2RomDetector().canHandle(rom));
}

@Test
void domesticReadFailureDoesNotAttemptInternational() throws Exception {
    Rom rom = mock(Rom.class);
    when(rom.isOpen()).thenReturn(true);
    when(rom.readDomesticName()).thenThrow(new IOException("header"));
    assertFalse(new Sonic2RomDetector().canHandle(rom));
    verify(rom, never()).readInternationalName();
}
```

Add parameterized or explicit tests for:

- null and closed ROMs;
- repeated whitespace/case normalization;
- S1 accepts S1 and rejects S2/S3;
- S2 accepts only the S2 phrase;
- S3K accepts all four current aliases;
- detector priorities remain 90/100/80;
- game names remain unchanged; and
- `createModule()` returns `Sonic1GameModule`, `Sonic2GameModule`, and
  `Sonic3kGameModule`.
- reflection via `getDeclaredMethod("canHandle", Rom.class)` confirms that all
  three concrete detectors still declare a public, non-final method.

The helper is:

```java
private static Rom openRom(String domestic, String international)
        throws IOException {
    Rom rom = mock(Rom.class);
    when(rom.isOpen()).thenReturn(true);
    when(rom.readDomesticName()).thenReturn(domestic);
    when(rom.readInternationalName()).thenReturn(international);
    return rom;
}
```

Include an assertion or architecture/source-shape check that all three detector
classes extend `AbstractHeaderNameRomDetector`; this is what makes the test red
before behavior-preserving migration.

- [ ] **Step 2: Run detector tests and verify RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.game.TestHeaderNameRomDetectors" test
```

Expected: compilation fails because `AbstractHeaderNameRomDetector` does not
exist.

- [ ] **Step 3: Implement the template**

Implement protected final `canHandleHeaderName` with the existing read/error
ordering. Provide a protected final normalization helper that uses the current
`toUpperCase().replaceAll("\\s+", " ").trim()` behavior. Log detector identity
at fine level for domestic/international success and miss, and warning on
`IOException`. Exact wording is not contractual.

- [ ] **Step 4: Convert the three concrete detectors**

Extend the template, implement `matchesNormalizedName` and `logger`, and remove
only duplicated orchestration/normalization. Each concrete detector declares
`@Override public boolean canHandle(Rom rom)` and forwards to
`canHandleHeaderName(rom)`; neither the class nor forwarding method is final.
Keep public class names, matching constants, priorities, module construction,
and game names.

- [ ] **Step 5: Run detector and architecture tests**

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.game.TestHeaderNameRomDetectors,com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalReviewGuard" \
  test
```

Expected: zero failures and errors.

- [ ] **Step 6: Commit the detector extraction**

Stage the exact task files and commit:

```text
refactor: consolidate ROM header detectors

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 3: Profile-driven HUD static-art builder

**Files:**

- Create: `src/main/java/com/openggf/level/objects/HudStaticArtFactory.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1HudStaticArtFactory.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2HudStaticArtFactory.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kHudStaticArtFactory.java`
- Expand: `src/test/java/com/openggf/game/TestHudStaticArtLivesFrameMappings.java`

**Interfaces:**

- Produces:
  `HudStaticArtFactory.create(Pattern[], Pattern[], Layout) -> HudStaticArt`.
- Produces the nested `Layout` record described in the design.
- Preserves all three game-local public `create` methods.

- [ ] **Step 1: Add failing shared-builder and parity tests**

Expand the existing test class. Import the new shared factory and add:

```java
@Test
void sharedBuilderConcatenatesPatternsWithoutReplacingInstances() {
    Pattern text = new Pattern();
    Pattern lives = new Pattern();
    HudStaticArt art = HudStaticArtFactory.create(
            new Pattern[] {text},
            new Pattern[] {lives},
            new HudStaticArtFactory.Layout(1, 0, 1, false));
    assertArrayEquals(new Pattern[] {text, lives}, art.patterns());
    assertSame(text, art.patterns()[0]);
    assertSame(lives, art.patterns()[1]);
}
```

Add exact assertions for:

- score pair indices `0,1,2,3,11`;
- debug score `0,1,2,3`;
- time `8,5,10,11`;
- rings `3,5,6,7,0`;
- each piece's `x`, `y`, dimensions, tile index, flips, and palette;
- S1 normal/flash palette 0 and lives-name palette 0;
- S2 normal palette 1, flash palette 0, and native/donor lives-name palette
  1/0;
- S3K normal palette 1 and empty time/rings flash frames;
- S1 returns null independently for null/empty text or lives;
- S2/S3K return bundles with empty pattern arrays for null inputs; and
- S2 donor selection changes only the lives-name palette.

- [ ] **Step 2: Run HUD tests and verify RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.game.TestHudStaticArtLivesFrameMappings" test
```

Expected: compilation fails because shared `HudStaticArtFactory` does not exist.

- [ ] **Step 3: Implement the shared builder**

Implement the `Layout` record and one `create` method. Apply the required-input
check before normalizing null arrays. Use the existing text row and lives-piece
geometry exactly. A null flash palette produces `new SpriteMappingFrame(List.of())`.

- [ ] **Step 4: Convert wrappers to policy delegates**

Use private static layout constants where the policy is fixed. S2 selects
between native and donor layouts from its existing boolean. Do not change
provider call sites or wrapper signatures.

- [ ] **Step 5: Run HUD provider and architecture tests**

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.game.TestHudStaticArtLivesFrameMappings,com.openggf.game.sonic1.TestSonic1LivesHudDonation,com.openggf.game.sonic2.TestSonic2LivesHudDonation,com.openggf.game.sonic3k.TestSonic3kLivesHudPaletteOverride,com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalReviewGuard" \
  test
```

Expected: zero failures and errors.

- [ ] **Step 6: Commit the HUD extraction**

Stage the exact task files and commit:

```text
refactor: consolidate HUD static art assembly

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 4: Documentation and tranche verification

**Files:**

- Stage:
  `docs/architecture/designs/2026-07-29-low-risk-reuse-consolidation.md`
- Stage:
  `docs/architecture/plans/2026-07-29-low-risk-reuse-consolidation.md`
- Create and stage:
  `docs/architecture/validation/2026-07-29-low-risk-reuse-consolidation.md`
- Modify: `CHANGELOG.md` only if required by the chosen final commit type
- Modify during integration: `README.md` release/change-log section

- [ ] **Step 1: Run the complete focused verification set**

Run the focused command from the design. The acceptance result is exactly one
known failure and zero errors: baseline `eb1b138c4` ran the comparable
pre-tranche set as 127 tests / 1 failure / 0 errors, and the completed tranche
after the detector override-compatibility correction ran 151 / 1 / 0. In both
cases the sole failure is
`TestTraceDataParsing.parsesRecordedRingFloorCheckCounterPhase`, which expects
`2` and receives `null` because the HCZ/MGZ metadata omit
`ring_floor_check_counter_phase`. Any additional focused failure or error is a
regression.

- [ ] **Step 2: Run the full JDK 21 suite**

```bash
mvn clean test
```

Run this in a clean detached worktree at `eb1b138c4` and in the tranche
worktree, then aggregate only the XML reports produced after the clean. This is
mandatory: non-clean `target/surefire-reports` can contain focused-run reports
and corrupt a full-suite aggregate. Both observed Maven processes hung after
printing final output; after confirming their XML reports were complete, they
were terminated and the XML result was used as the authoritative outcome.

The clean baseline produced 1,726 suites / 13,497 tests / 1 failure / 1 error /
35 skipped. Before the detector override-compatibility correction, the tranche
produced 1,728 / 13,521 / 1 / 1 / 35. The correction adds one reflection test,
so the next clean tranche sweep is expected to report 13,522 tests with the
same suite count. Both completed clean runs contain only the two observed
`TestGameLoop` failures:

- `traceRealtimeRewindRunsBeforePlaybackInputBridge`
- `setupAdmissionPrecedesSeamlessBoundaryAndTraceCameraMutations`

The suite-count/test-count growth is from this tranche's added tests. No new
failure or changed failure attributable to this tranche is acceptable. Record
the exact commands, complete-report methodology, and comparison in the
validation artifact.

- [ ] **Step 3: Run policy and diff checks**

```bash
git diff --check
git status --short
```

Confirm the disassembly links created by the worktree hook remain untracked and
unstaged. Stage the three architecture artifacts explicitly.

- [ ] **Step 4: Commit documentation**

```text
docs: record low-risk reuse consolidation

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

- [ ] **Step 5: Follow the repository integration workflow**

Fetch and fast-forward the main-workspace `develop` branch without overwriting
user changes. Record the updated baseline full-suite result. Rebase or merge
the development branch onto that updated baseline in its worktree if needed,
run focused and full tests there, update the `README.md` release/change-log
section for the merge, merge into main-workspace `develop`, rerun the full
suite, compare failures, push `develop`, then remove the clean worktree and
delete the fully merged local feature branch.
