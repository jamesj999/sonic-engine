# ROM Location Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Centralize production game-to-ROM path selection with typed
provenance while preserving `RomManager` compatibility and trace-tool failure
semantics.

**Architecture:** A filesystem-neutral `RomLocationResolver` maps a data-layer
`RomGame` identity and
configuration to an immutable location carrying exact configured text,
normalized target, provenance, and a non-enforcing fingerprint policy.
`RomManager` retains lifecycle/error ownership; a package-private trace-tool
adapter owns strict metadata and blank-configuration failures.

**Tech Stack:** Java 21, Maven, JUnit Jupiter.

## Global Constraints

- Runtime assets remain ROM-only.
- Production selects the configured nonblank path even when it does not exist;
  it never searches for an existing fallback.
- Filesystem existence, opening, game detection, and fingerprints remain
  consumer-owned.
- Relative paths remain current-working-directory-relative.
- Preserve exact raw configured strings in compatibility diagnostics.
- Preserve `RomManager.resolveRomForGame(String)` null/unknown-to-S2 behavior.
- Do not migrate JUnit, master-title UI, SoundTest, HeadlessGameBoot, or generic
  object/disassembly tools.
- Use red-green-refactor TDD for new behavior. For behavior-preserving
  migrations, record green characterization before and after the refactor; do
  not invent behavior solely to force a RED. Use JDK 21.

---

### Task 1: Add the typed filesystem-neutral resolver

**Files:**

- Create: `src/main/java/com/openggf/data/RomLocation.java`
- Create: `src/main/java/com/openggf/data/RomGame.java`
- Create: `src/main/java/com/openggf/data/RomLocationSource.java`
- Create: `src/main/java/com/openggf/data/RomFingerprintPolicy.java`
- Create: `src/main/java/com/openggf/data/RomLocationResolver.java`
- Modify: `src/main/java/com/openggf/game/GameId.java`
- Create: `src/test/java/com/openggf/data/TestRomLocationResolver.java`
- Create: `src/test/java/com/openggf/game/TestGameIdRomGame.java`

**Interfaces:**

- Produces:
  `RomLocation(RomGame, String, Path, RomLocationSource, RomFingerprintPolicy)`
- Produces:
  `RomLocationResolver(SonicConfigurationService, Path)`
- Produces:
  `static RomLocationResolver forCurrentWorkingDirectory(SonicConfigurationService)`
- Produces: `Optional<RomLocation> resolve(RomGame)`
- Produces: `RomLocation explicit(RomGame, Path)`
- Produces: `GameId.romGame()`

- [ ] **Step 1: Write failing location-record tests**

Test that the record rejects null fields and retains literal values without
filesystem access:

```java
RomLocation location = new RomLocation(
        RomGame.S1,
        "./missing/../sonic.gen",
        Path.of("/workspace/sonic.gen"),
        RomLocationSource.CONFIGURATION,
        RomFingerprintPolicy.NONE);
assertEquals("./missing/../sonic.gen", location.configuredValue());
assertEquals(Path.of("/workspace/sonic.gen"), location.resolvedPath());
```

The mutation caught is losing exact configured text or performing eager I/O.

- [ ] **Step 2: Run the record test and verify RED**

Run:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.data.TestRomLocationResolver,com.openggf.game.TestGameIdRomGame \
  test
```

Expected: test compilation fails because the new types do not exist.

- [ ] **Step 3: Implement the records/enums only**

Add the public record with a compact constructor using
`Objects.requireNonNull`. Add `CONFIGURATION` / `EXPLICIT_OVERRIDE` and `NONE`
enums. Do not add file or digest methods.

- [ ] **Step 4: Add failing resolver mapping tests**

Use a real isolated `SonicConfigurationService` instance or the smallest
existing test configuration seam. Set literal values for all three ROM keys
and assert:

- `S1`, `S2`, and `S3K` read only their matching keys;
- blank values return `Optional.empty()`;
- a missing relative path is still returned, absolute and normalized against
  the injected working directory;
- an absolute value remains absolute after normalization;
- a relative injected working directory is itself converted to an absolute,
  normalized base;
- `explicit` records `Path.toString()` and `EXPLICIT_OVERRIDE`;
- every result carries `NONE`;
- null inputs fail; and
- changing `user.dir` after resolver construction does not alter that resolver.

Also test that every `GameId` maps to its same-named `RomGame`, so the
runtime-to-data conversion has one guarded owner.

- [ ] **Step 5: Run resolver tests and verify RED**

Run the Step 2 command. Expected: compilation fails because
`RomLocationResolver` and `GameId.romGame()` do not exist.

- [ ] **Step 6: Implement minimal resolver**

Use an exhaustive `RomGame` switch to map to
`SonicConfiguration.SONIC_1_ROM`, `SONIC_2_ROM`, and `SONIC_3K_ROM`.
Treat `null`, empty, and whitespace-only values as blank. Preserve the original
nonblank String; normalize only `resolvedPath`.

Add the sole exhaustive `GameId -> RomGame` conversion as `GameId.romGame()`.

- [ ] **Step 7: Run resolver and architecture tests**

Run:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.data.TestRomLocationResolver,com.openggf.game.TestGameIdRomGame,com.openggf.tests.TestArchUnitTestRules,com.openggf.tests.TestArchUnitRules \
  test
```

Expected: all tests pass.

- [ ] **Step 8: Commit Task 1**

Commit only Task 1 files:

```text
feat: add typed ROM location resolver
```

Update `CHANGELOG.md` or use the repository-approved justified
`Changelog: n/a: <reason>` trailer for behavior-neutral internal plumbing.

---

### Task 2: Migrate RomManager while preserving compatibility

**Files:**

- Modify: `src/main/java/com/openggf/data/RomManager.java`
- Modify: `src/test/java/com/openggf/data/TestRomManagerMissingRomLogging.java`
- Create: `src/test/java/com/openggf/data/TestRomManagerLocationResolution.java`
- Test: `src/test/java/com/openggf/game/TestPowerUpGraphicsRegression.java`

**Interfaces:**

- Consumes:
  `RomLocationResolver.forCurrentWorkingDirectory(GameServices.configuration())`
- Retains:
  `public static String RomManager.resolveRomForGame(String gameId)`
- Retains `getSecondaryRom(String)` cache keys and lifecycle.

- [ ] **Step 1: Add compatibility-forwarder characterization tests**

With distinct literal config strings, assert:

- `s1` and `S1` return the exact S1 string;
- `s3k` and case variants return the exact S3K string;
- `s2`, null, unknown, and other strings return the exact S2 string; and
- a null/missing configuration entry is exposed as the exact empty string
  returned by `SonicConfigurationService`; and
- whitespace-only configuration is returned verbatim by the legacy forwarder.

The mutation caught is making the legacy String API strict or normalized.

- [ ] **Step 2: Run location tests and verify the behavioral baseline**

Run:

```bash
mvn -Dmse=off -Dtest=com.openggf.data.TestRomManagerLocationResolution test
```

The class is new, so compilation initially fails. Add only test setup needed to
exercise the existing public method; the existing implementation must then
pass these characterization cases before migration. This task is a
behavior-preserving refactor whose new resolver behavior already completed a
true RED/GREEN cycle in Task 1; do not invent a failing compatibility
expectation.

- [ ] **Step 3: Add active-ROM resolution characterization tests**

Use temporary working directories and an always-created readable byte file;
`RomManager.getRom()` performs no header/fingerprint validation, so no real ROM
or `RomTestUtils` assumption is needed. Pin:

- a relative configured path resolves under current `user.dir`;
- closing the manager, changing `user.dir`, and reopening uses the new working
  directory rather than a captured singleton directory;
- a missing relative path throws
  `ROM file does not exist: <exact configured string>`;
- `isConfiguredRomMissing` still recognizes the failure; and
- blank configuration retains
  `ROM filename not configured (DEFAULT_ROM not set or per-game ROM key empty)`.

- [ ] **Step 4: Run active-ROM characterization before migration**

Run:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.data.TestRomManagerLocationResolution,com.openggf.data.TestRomManagerMissingRomLogging \
  test
```

Expected: all compatibility/active tests pass on the pre-migration
implementation. Record this green baseline, then rerun the identical tests
after Step 5. Restore configuration, `user.dir`, and the singleton manager in
`finally`/test cleanup so the suite cannot inherit state.

- [ ] **Step 5: Migrate active ROM opening**

Resolve the configured default game through the legacy fallback-to-S2 mapping,
then use a fresh current-working-directory resolver. On empty resolution,
retain the exact existing IOException. Check and open `resolvedPath`; use
`configuredValue` in log/error messages.

- [ ] **Step 6: Add secondary-ROM characterization tests**

Pin S1/S2/S3K configured selection, relative-path resolution, existing blank
`No ROM configured for game: <id>` failure, and existing
`Failed to open secondary ROM: <configured string>` diagnostics. Do not assert
cache-key normalization; it is deferred. Run these cases before migration and
record them green, then rerun the identical cases after Step 7.

- [ ] **Step 7: Migrate secondary resolution and legacy forwarder**

Convert the legacy String to `RomGame` only after preserving fallback-to-S2 at
that compatibility boundary. `getSecondaryRom(String)` must continue to treat
unknown/null through the existing S2 path and retain the original String cache
key. The deprecated `resolveRomForGame` delegates the `RomGame` mapping but
returns the exact `configuredValue`.

- [ ] **Step 8: Run RomManager focused verification**

Run:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.data.TestRomLocationResolver,com.openggf.data.TestRomManagerLocationResolution,com.openggf.data.TestRomManagerMissingRomLogging,com.openggf.game.TestPowerUpGraphicsRegression \
  test
```

Expected: all tests pass.

- [ ] **Step 9: Commit Task 2**

Commit RomManager and its tests:

```text
refactor: route RomManager through ROM locations
```

Use a justified behavior-neutral Changelog trailer.

---

### Task 3: Migrate trace capture and benchmark tools

**Files:**

- Create: `src/main/java/com/openggf/tools/TraceToolRomLocations.java`
- Modify: `src/main/java/com/openggf/tools/TraceCaptureTool.java`
- Modify: `src/main/java/com/openggf/tools/TraceBenchmarkTool.java`
- Create: `src/test/java/com/openggf/tools/TestTraceToolRomLocations.java`
- Test: `src/test/java/com/openggf/tools/TraceCaptureToolArgsTest.java`
- Test: `src/test/java/com/openggf/tools/TestTraceBenchmarkToolArgs.java`
- Test: `src/test/java/com/openggf/tools/TraceCaptureSessionTest.java`

**Interfaces:**

- Produces package-private:
  `TraceToolRomLocations.resolve(String gameId, SonicConfigurationService configuration, Path workingDirectory): Path`
- Consumes `GameId.fromCode`, `GameId.romGame()`, and
  `RomLocationResolver`.

- [ ] **Step 1: Add failing strict-adapter tests**

With literal config values and an injected working directory, assert:

- `s1`, `s2`, and `s3k` return normalized resolved paths;
- a nonblank missing path is returned rather than replaced by a default;
- blank config throws
  `IllegalStateException("No ROM configured for game: " + gameId)`;
- unknown and null IDs preserve `GameId.fromCode` failures; and
- no filesystem read/open occurs.

- [ ] **Step 2: Run adapter tests and verify RED**

Run:

```bash
mvn -Dmse=off -Dtest=com.openggf.tools.TestTraceToolRomLocations test
```

Expected: compilation fails because the adapter does not exist.

- [ ] **Step 3: Implement the minimal adapter**

Parse with `GameId.fromCode`, convert it through `GameId.romGame()`, construct
the injected resolver, return the location's `resolvedPath`, and throw the
exact blank-configuration exception.

- [ ] **Step 4: Run adapter tests and verify GREEN**

Run the Step 2 command. Expected: all adapter tests pass.

- [ ] **Step 5: Characterize tool integration before migration**

Extend existing tests only where needed to pin:

- capture/benchmark still pass a nonblank missing path to the boot boundary;
- blank configuration fails at the adapter boundary;
- unknown trace metadata is rejected strictly; and
- benchmark reuses the identical resolved Path for every reboot.

If full boot setup makes a case unsuitable for a unit test, test the adapter
plus retain the existing session/boot integration tests; do not add production
injection solely for mocks.

- [ ] **Step 6: Replace direct RomManager string lookup**

Both tools call the adapter using `GameServices.configuration()` and the
current working directory. Remove obsolete `RomManager` and `Paths` imports.
Do not change trace selection, boot ownership, admission policy, or replay
setup.

- [ ] **Step 7: Run trace-tool focused verification**

Run:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.tools.TestTraceToolRomLocations,com.openggf.tools.TraceCaptureToolArgsTest,com.openggf.tools.TestTraceBenchmarkToolArgs,com.openggf.tools.TraceCaptureSessionTest,com.openggf.tools.TestHeadlessGameBoot \
  test
```

If `TestHeadlessGameBoot` has a different actual class name, resolve it with
`rg` before implementation and record the exact replacement in the task
report.

- [ ] **Step 8: Commit Task 3**

Commit adapter/tool changes and tests:

```text
refactor: share trace tool ROM location policy
```

Use a justified behavior-neutral Changelog trailer except for the documented
blank/unknown fail-fast improvement, which must be reflected in the release
documentation if policy requires it.

---

### Task 4: Validate, document, review, and integrate

**Files:**

- Create:
  `docs/architecture/validation/2026-07-29-rom-location-resolver.md`
- Modify: `README.md`
- Modify:
  `src/test/java/com/openggf/data/TestRomManagerLocationResolution.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kPenguinatorBadnik.java`

- [ ] **Step 0: Remove clean-suite lifecycle/order regressions**

Use the already-red clean-suite evidence and focused reproductions:

- `TestSingletonLifecycleGuard` names
  `TestRomManagerLocationResolution#setUp` as a new ambient gameplay setup;
- Penguinator passes alone, but fails after an MHZ/SKL-loading class because
  its registry assertion inherits the active zone set; and
- Task 2 and Penguinator ran in different full-suite forks, ruling out direct
  production-state leakage from `RomManager`.

Apply the approved `@FullReset` plus `SingletonResetExtension` fixture to the
new RomManager test and the zone-set-dependent Penguinator registry test.
The existing full-suite XML and focused investigations supply the pre-fix RED
evidence. After the fix, run:

```bash
mvn -Dmse=off \
  -Dtest=TestSingletonLifecycleGuard#ambientGameplayModeSetupsDoNotGrowWithoutLifecycleTriage \
  test

mvn -Dmse=off -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Ds3k.rom.path="$S3K_ROM" \
  -Dtest=com.openggf.game.sonic3k.TestS3kMhzPatternAnimation,com.openggf.tests.TestS3kPenguinatorBadnik \
  test

mvn -Dmse=off \
  -Dtest=com.openggf.data.TestRomLocationResolver,com.openggf.data.TestRomManagerLocationResolution,com.openggf.data.TestRomManagerMissingRomLogging,com.openggf.game.TestPowerUpGraphicsRegression \
  test

mvn -Dmse=off -Dtest=com.openggf.tests.TestS3kPenguinatorBadnik test
```

Before the fix, the first command names the new RomManager `setUp`, and the
ordered second command returns a Penguinator placeholder after MHZ establishes
SKL state. After the fixtures are applied, every command is green. Commit only
these test-isolation fixes, then restart Steps 1 and 2 from scratch rather than
using the earlier branch run as final evidence.

- [ ] **Step 1: Run combined focused verification**

Run all new resolver, RomManager, trace-tool, compatibility, and architecture
tests with JDK 21, explicitly including
`com.openggf.game.TestGameIdRomGame`. Include the actual discovered
HeadlessGameBoot test class.
Record exact command, counts, failures, errors, skips, ROM properties, and
commit.

- [ ] **Step 2: Run clean same-ROM base and branch suites**

Create a disposable detached worktree at exact base `aa26f4494`. In both base
and feature worktrees run:

```bash
mvn -Dmse=off \
  -Dsonic1.rom.path="$S1_ROM" \
  -Dsonic2.rom.path="$S2_ROM" \
  -Ds3k.rom.path="$S3K_ROM" \
  clean test
```

Keep reports separate. If the documented idle Maven tail occurs after stable
Surefire XML, interrupt only that tail and aggregate XML. Restore generated
rewind reports and classify hook-created links.

- [ ] **Step 3: Write exact validation and release evidence**

Document the selected-path policy, intentional blank/unknown trace-tool
fail-fast behavior, deferred JUnit/UI/tool migrations, every test command,
exact baseline/branch failure identities, and whether any base-passing test
regressed. Add the required current-release README bullet.

- [ ] **Step 4: Run policy/diff checks and commit**

Run:

```bash
git diff --check
git diff --cached --check
git status --short
```

Commit only validation/README changes with correct trailers, then run:

```bash
git diff --check aa26f4494..HEAD
```

- [ ] **Step 5: Final review and integration**

Request a whole-branch review using the design, plan, validation, task ledger,
reports, and full diff. Fix all Critical/Important issues to green.

Fetch and fast-forward main-workspace `develop` without overwriting user
changes. If it moved, rerun a clean updated baseline and feature verification.
Merge without switching the main workspace, preserve its dirty rewind report
byte-for-byte, run the post-merge same-ROM full suite, compare against the
updated baseline, and push only `develop` when there is no new regression.
Remove only this task's clean worktrees, delete the fully merged feature
branch, and prune metadata.
