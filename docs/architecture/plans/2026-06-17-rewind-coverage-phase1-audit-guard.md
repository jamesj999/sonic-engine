# Rewind Coverage — Phase 1 (Audit + Static Guard) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make rewind-coverage gaps **visible and measurable** — a programmatic analyzer that enumerates every spawnable gameplay object and flags whether it is rewind-recreatable, whether it has uncaptured `final` scalar state, and whether it holds un-id'd live object references — surfaced through the existing audit tool and a report-only CI guard.

**Architecture:** A read-only `RewindCoverageAnalyzer` (pure reflection over the object registries + `GenericFieldCapturer` eligibility rules) produces a `RewindCoverageReport`. The existing `RewindFieldInventoryTool` gains a sub-command to print it. A JUnit test (`TestRewindCoverageGuard`) runs the analyzer in **report-only** mode against a committed baseline of known gaps, so the true scope is captured in-repo and any *new* gap is caught, without yet failing the build on the existing backlog. No engine behavior changes in Phase 1.

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), Maven, the existing `com.openggf.game.rewind` + `com.openggf.tools.rewind` packages.

## Global Constraints

- JUnit 5 / Jupiter only — no JUnit 4 imports, rules, or runners.
- Source files end with a newline.
- Phase 1 is **read-only / additive**: no change to capture, restore, or any object's runtime behavior. The guard is **report-only** (never fails on the existing backlog) until a later phase flips it.
- Per-commit trailer block required (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`), each `updated` or `n/a`. Phase 1 touches `src/main` (the analyzer/tool) and `src/test`; it is `feat`-class but adds a dev/CI tool, not engine behavior — set `Changelog: n/a: dev tooling, no runtime behavior change` (or `updated` + stage `CHANGELOG.md` if you prefer to note it).
- Work in the worktree `.claude/worktrees/aiz2-rewind-fix` (branch may be renamed for this initiative — see Task 0).
- Build/test invocation (PowerShell): quote `-D` props, e.g. `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.TestRewindCoverageGuard" test`. MSE prints a project-wide `total=`; trust the per-class `Tests run:` line.
- Reference spec: `docs/architecture/designs/2026-06-17-rewind-object-coverage-architecture-design.md` (this plan implements §3.5 + §3.7, report-only slice).

---

### Task 0: Branch + baseline

**Files:**
- None (git only).

**Interfaces:**
- Produces: a clean branch based on `develop` for the coverage initiative, with a green baseline.

- [ ] **Step 1: Decide the branch.** This initiative is broader than the AIZ2 fix branch. From the worktree, create a dedicated branch off the current tip (which already contains the merged `develop` + the AIZ2 fixes + the spec):

```bash
cd .claude/worktrees/aiz2-rewind-fix
git checkout -b feature/ai-rewind-coverage-audit
```

- [ ] **Step 2: Confirm baseline compiles.**

Run: `mvn -q -Dmse=relaxed -DskipTests test-compile`
Expected: `MSE:OK ... errors=0`

- [ ] **Step 3: Locate the seams the analyzer needs (read-only orientation, no edits).** Open and skim, noting exact signatures into a scratch note:
  - `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java` — the field-eligibility predicate (`isDefaultObjectValueField` and the `final`-rejection at ~line 383) and how it decides a scalar field is captured. This is the source of truth for "is this field captured?".
  - `src/main/java/com/openggf/game/rewind/schema/DefaultObjectRewindPolicies.java` — `STRUCTURAL_OBJECT_FIELD_NAMES` and the object-ref field set (what counts as a live object reference).
  - `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`, `Sonic2ObjectRegistry`, `Sonic1ObjectRegistry`, and their `AbstractObjectRegistry` base — how factories register (`factories.put(id, ...)`) and `dynamicRewindCodecs()`.
  - `src/main/java/com/openggf/tools/rewind/RewindFieldInventoryTool.java` — its `main`/sub-command shape, to add a `--coverage` mode.
  - `src/test/java/com/openggf/tools/rewind/TestRewindFieldInventoryTool.java` — the test pattern for the tool.

Record the exact method names/signatures you find; later tasks reference them. If any named symbol differs, use the real one and keep the plan's intent.

---

### Task 1: `RewindCoverageAnalyzer` skeleton + spawnable-class enumeration

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/coverage/RewindCoverageAnalyzer.java`
- Create: `src/main/java/com/openggf/game/rewind/coverage/RewindCoverageReport.java`
- Create: `src/main/java/com/openggf/game/rewind/coverage/ObjectCoverage.java`
- Test: `src/test/java/com/openggf/game/rewind/coverage/TestRewindCoverageAnalyzer.java`

**Interfaces:**
- Produces:
  - `record ObjectCoverage(String className, boolean isLayoutSpawnable, boolean isDynamicSpawnable, boolean hasRecreatePath, java.util.List<String> uncapturedFinalScalarFields, java.util.List<String> unIdObjectRefFields)` with:
    - `boolean isCovered()` = `hasRecreatePath && uncapturedFinalScalarFields.isEmpty() && unIdObjectRefFields.isEmpty()`.
    - **`java.util.List<String> gapKeys()`** — the stable, per-reason keys this class contributes (empty when covered). **The baseline tracks these, NOT class names**, so a new gap on an already-baselined class is still caught:
      - `className + "#recreate"` when `!hasRecreatePath`.
      - `className + "#finalScalar#" + field` for each entry in `uncapturedFinalScalarFields`.
      - `className + "#objectRef#" + field` for each entry in `unIdObjectRefFields`.
  - `record RewindCoverageReport(java.util.List<ObjectCoverage> objects)` with:
    - `java.util.SortedSet<String> gapKeys()` — the union of every object's `gapKeys()` (sorted; this is what the guard/baseline compare).
    - `String render()` (stable, sorted-by-className multi-line text; show each class with its gap keys).
  - `final class RewindCoverageAnalyzer` with `static RewindCoverageReport analyze(com.openggf.game.GameId game)` and `static RewindCoverageReport analyzeAll()`.

- [ ] **Step 1: Write the failing test** — enumeration is non-empty and includes a known object.

```java
package com.openggf.game.rewind.coverage;

import com.openggf.game.GameId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestRewindCoverageAnalyzer {
    @Test
    void enumeratesSpawnableObjectsForS3k() {
        RewindCoverageReport report = RewindCoverageAnalyzer.analyze(GameId.S3K);
        assertFalse(report.objects().isEmpty(), "must enumerate S3K spawnable objects");
        assertTrue(report.objects().stream()
                .anyMatch(o -> o.className().endsWith("AizLrzRockObjectInstance")),
                "AizLrzRock is a known S3K spawnable object");
    }
}
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer" test`
Expected: FAIL — `RewindCoverageAnalyzer` does not exist (compile error).

- [ ] **Step 3: Implement the records + the enumeration via classpath scanning.** `ObjectCoverage` and `RewindCoverageReport` as specified above.

  **Enumeration is classpath-scan-based, NOT registry-map-based.** `AbstractObjectRegistry.factories` is `protected` and its values are `ObjectFactory` lambdas that do not reveal the concrete object class without invoking them (which has construction side effects) — so we cannot read classes out of the factory map. Instead, `RewindCoverageAnalyzer.analyze(game)` scans the classpath for **concrete (`!Modifier.isAbstract`) subclasses of `com.openggf.level.objects.AbstractObjectInstance`** under the game-relevant packages (`com.openggf.game.sonic1|2|3k..`, `com.openggf.level.objects..`), filtered to the requested `GameId` by package, and excluding obvious non-gameplay/test doubles via a small allowlist of package prefixes. This uniformly covers layout objects, dynamic objects, AND runtime child-spawned classes (bombs, boss children) in one pass — there is no separate "child-spawn enumeration" step.

  Reuse the project's existing class-scanning utility rather than adding a dependency: search `src/test` for how guard tests enumerate classes (e.g. `TestObjectServicesMigrationGuard`, `TestNoServicesInObjectConstructors`) and mirror that scanner. If the scanner lives in test scope only, add a minimal `com.openggf.game.rewind.coverage.ObjectClasspathScan` helper modeled on it (main scope, so the tool can use it too).

  For Phase 1 Step 3, set `hasRecreatePath = true` and the field lists empty (safe defaults; Tasks 2–4 fill them). Sort objects by `className()` for stable output.

> Note: this scan-based enumeration subsumes what was previously a separate child-spawn task — every concrete `AbstractObjectInstance` is found regardless of how it is spawned.

- [ ] **Step 4: Run it to verify it passes.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer" test`
Expected: PASS — `Tests run: 1, Failures: 0`.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/openggf/game/rewind/coverage/ src/test/java/com/openggf/game/rewind/coverage/
git commit -m "feat(rewind): coverage analyzer skeleton + spawnable enumeration"
```

(Fill the trailer block: `Changelog: n/a: dev tooling, no runtime behavior change`, rest `n/a`.)

---

### Task 2: Uncaptured `final`-scalar detection

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/coverage/RewindCoverageAnalyzer.java`
- Test: `src/test/java/com/openggf/game/rewind/coverage/TestRewindCoverageAnalyzer.java`

**Interfaces:**
- Consumes: `GenericFieldCapturer`'s field-eligibility logic (Task 0 note). If it exposes a public/package predicate, call it; if the logic is private, add a *narrow* package-visible static helper on `GenericFieldCapturer` (e.g. `static boolean isCaptureEligibleScalar(java.lang.reflect.Field f)`) that returns the same decision — do not duplicate the rules.
- Produces: `ObjectCoverage.uncapturedFinalScalarFields` populated.

- [ ] **Step 1: Write the failing test** — an object with a known `final` non-transient scalar field is flagged.

```java
@Test
void flagsUncapturedFinalScalarField() {
    RewindCoverageReport report = RewindCoverageAnalyzer.analyze(GameId.S3K);
    // Pick a class you confirmed in Task 0 that still has a final scalar (e.g. one NOT yet
    // un-finaled by the AIZ2 work). Replace ClassNameWithFinalScalar + fieldName accordingly.
    ObjectCoverage cov = report.objects().stream()
            .filter(o -> o.className().endsWith("ClassNameWithFinalScalar"))
            .findFirst().orElseThrow();
    assertTrue(cov.uncapturedFinalScalarFields().contains("fieldName"),
            "final non-transient scalar must be reported as uncaptured");
}
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer#flagsUncapturedFinalScalarField" test`
Expected: FAIL — list is empty (detection not implemented).

- [ ] **Step 3: Implement detection.** For each enumerated class, reflect its declared fields (walk up the superclass chain to `AbstractObjectInstance`). A field is an uncaptured-final-scalar gap when it is `Modifier.isFinal`, a primitive or enum (a "scalar"), **not** `@RewindTransient`/`@RewindDeferred`, **not** classified structural/transient by the **public audit policy API** `com.openggf.game.rewind.schema.RewindPolicyRegistry.policyForAudit(field)` (the analyzer package cannot see the package-private `DefaultObjectRewindPolicies.STRUCTURAL_OBJECT_FIELD_NAMES`; use `policyForAudit` — if that exact method is absent, add a small public/package-approved audit helper on `RewindPolicyRegistry` that returns the same decision rather than duplicating or illegally accessing internals), and `GenericFieldCapturer`'s eligibility (per Task 0) would otherwise capture it but the capturer skips it solely because it is `final`. Record the field name.

- [ ] **Step 4: Run it to verify it passes.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer#flagsUncapturedFinalScalarField" test`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "feat(rewind): coverage analyzer flags uncaptured final scalar fields"
```

---

### Task 3: Recreate-path detection (dynamic objects without a codec)

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/coverage/RewindCoverageAnalyzer.java`
- Test: `src/test/java/com/openggf/game/rewind/coverage/TestRewindCoverageAnalyzer.java`

**Interfaces:**
- Produces: `ObjectCoverage.hasRecreatePath`, `isLayoutSpawnable`, `isDynamicSpawnable` populated.

- [ ] **Step 1: Write the failing test** — a dynamic-only object that has a codec is `hasRecreatePath == true`; assert a covered one (e.g. `AizBattleshipInstance`, which has a codec on this branch) is true.

```java
@Test
void dynamicObjectWithCodecHasRecreatePath() {
    RewindCoverageReport report = RewindCoverageAnalyzer.analyze(GameId.S3K);
    ObjectCoverage cov = report.objects().stream()
            .filter(o -> o.className().endsWith("AizBattleshipInstance"))
            .findFirst().orElseThrow();
    assertTrue(cov.isDynamicSpawnable());
    assertTrue(cov.hasRecreatePath(), "battleship has a dynamic rewind codec on this branch");
}
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer#dynamicObjectWithCodecHasRecreatePath" test`
Expected: FAIL (default `hasRecreatePath` from Task 1 may already be `true`; if so, first change the Task-1 default to `false` for dynamics so this test meaningfully drives the logic, then it fails until Step 3).

- [ ] **Step 3: Implement.** Build the set of class names that have a **dynamic recreate codec** by unioning **both** codec sources `ObjectManager` actually consults:
  - `com.openggf.level.objects.ObjectRewindDynamicCodecs.sharedCodecs()` — animals, lost rings, shields, invincibility stars, explosions, skid dust (shared across all games; missing these would false-report them as gaps), and
  - each game registry's `dynamicRewindCodecs()`.
  Collect every codec's `className()` (and for instance-based codecs that match by `supports(instance)` rather than a fixed name, record the concrete type they construct — see each codec's `className()`).

  Then for each enumerated class: `hasRecreatePath = true` if a codec exists for its exact class name in that union. `isDynamicSpawnable = true` for these.

  **Layout caveat (Phase 1 over-approximation, intentional):** statically distinguishing a layout object (recreated by the registry/placement path from its spawn, needing no codec) from a dynamic object that is *missing* a codec is not possible without a registry audit surface (the factory map is opaque — Task 1). So in Phase 1, a class with no codec is reported with a `#recreate` gap key even if it is in fact layout-recreated. These are captured **once** in the committed baseline (Task 7) and never fail the build (report-only). Closing this false-positive set — by adding a registry audit API that declares layout-spawnable classes — is explicitly deferred to the Phase 2 plan (which introduces the recreate contract anyway). Document this caveat in the analyzer's Javadoc.

- [ ] **Step 4: Run it to verify it passes.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer#dynamicObjectWithCodecHasRecreatePath" test`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "feat(rewind): coverage analyzer detects recreate path (codec presence)"
```

---

### Task 4: Un-id'd live object-reference field detection

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/coverage/RewindCoverageAnalyzer.java`
- Test: `src/test/java/com/openggf/game/rewind/coverage/TestRewindCoverageAnalyzer.java`

**Interfaces:**
- Produces: `ObjectCoverage.unIdObjectRefFields` populated.

- [ ] **Step 1: Write the failing test** — an object holding a non-transient reference to another `ObjectInstance` (not captured as an id) is flagged. Use a class confirmed in Task 0 (e.g. an AIZ boss child holding a `boss`/`parent` ref that is `@RewindTransient` today → it should NOT be flagged; pick one that holds a ref that is neither transient nor id-captured, or assert the transient one is NOT flagged).

```java
@Test
void transientBossRefIsNotFlaggedAsUnIdRef() {
    RewindCoverageReport report = RewindCoverageAnalyzer.analyze(GameId.S3K);
    ObjectCoverage cov = report.objects().stream()
            .filter(o -> o.className().endsWith("AizEndBossArmChild"))
            .findFirst().orElseThrow();
    // boss/parent ref fields are @RewindTransient (structural) today, so they are NOT gaps.
    assertTrue(cov.unIdObjectRefFields().isEmpty());
}
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer#transientBossRefIsNotFlaggedAsUnIdRef" test`
Expected: FAIL until the rule is implemented (or trivially pass if list defaults empty — in that case add a positive-case assertion on a class that DOES hold a non-transient un-id ref, confirmed in Task 0, so the rule is exercised).

- [ ] **Step 3: Implement.** A field is an un-id object-ref gap when its type is assignable to `com.openggf.level.objects.ObjectInstance` (or a known sidekick/player type), it is non-static, and it is **not** `@RewindTransient` and **not** classified structural by `RewindPolicyRegistry.policyForAudit(field)` (same public audit API as Task 2 — do not touch the package-private structural set directly) and **not** captured via an object-ref codec/id. (In Phase 1 there is no id-capture yet, so the only non-gaps are transient/structural refs; everything else is reported — this is exactly the backlog Phase 2 will close.)

- [ ] **Step 4: Run it to verify it passes.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer#transientBossRefIsNotFlaggedAsUnIdRef" test`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "feat(rewind): coverage analyzer flags un-id'd object-reference fields"
```

---

### Task 5: Enumeration-completeness assertion (scan covers child-spawned classes)

Enumeration is already classpath-scan-based (Task 1), so runtime child-spawned classes are included by construction. This task **locks that in** with a completeness assertion guarding the scan's package allowlist — a child-spawned class with no registry factory must still appear.

**Files:**
- Test: `src/test/java/com/openggf/game/rewind/coverage/TestRewindCoverageAnalyzer.java`

**Interfaces:**
- Consumes: the Task 1 scan.

- [ ] **Step 1: Write the test** — a known child-spawned class (no registry factory) is present.

```java
@Test
void enumerationIncludesRuntimeChildSpawnedClasses() {
    RewindCoverageReport report = RewindCoverageAnalyzer.analyze(GameId.S3K);
    assertTrue(report.objects().stream()
            .anyMatch(o -> o.className().endsWith("AizShipBombInstance")),
            "child-spawned classes must be enumerated by the classpath scan");
    assertTrue(report.objects().stream()
            .anyMatch(o -> o.className().endsWith("AizEndBossArmChild")),
            "boss-child classes must be enumerated by the classpath scan");
}
```

- [ ] **Step 2: Run it.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageAnalyzer#enumerationIncludesRuntimeChildSpawnedClasses" test`
Expected: PASS (the Task 1 scan already includes these). If it FAILS, the scan's package allowlist is too narrow — widen it in `RewindCoverageAnalyzer`/`ObjectClasspathScan` to cover the `objects` subpackages, then re-run.

- [ ] **Step 3: Commit.**

```bash
git add -A
git commit -m "test(rewind): assert coverage scan enumerates child-spawned classes"
```

---

### Task 6: Audit-tool sub-command (`RewindFieldInventoryTool --coverage`)

**Files:**
- Modify: `src/main/java/com/openggf/tools/rewind/RewindFieldInventoryTool.java`
- Test: `src/test/java/com/openggf/tools/rewind/TestRewindFieldInventoryTool.java`

**Interfaces:**
- Consumes: `RewindCoverageAnalyzer.analyzeAll()`, `RewindCoverageReport.render()`.
- Produces: a `--coverage` mode that prints the rendered report and a summary line `coverage: <covered>/<total> covered, <gaps> gaps`.

- [ ] **Step 1: Write the failing test** — invoking the tool's coverage entry returns a non-empty rendered report containing the summary line.

```java
@Test
void coverageModeRendersSummary() {
    String out = RewindFieldInventoryTool.renderCoverageReport(); // new static, returns the text
    assertTrue(out.contains("coverage:"), "must include a coverage summary line");
    assertTrue(out.contains("gaps"), "must report gap count");
}
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.tools.rewind.TestRewindFieldInventoryTool#coverageModeRendersSummary" test`
Expected: FAIL — method missing.

- [ ] **Step 3: Implement.** Add `static String renderCoverageReport()` that calls `RewindCoverageAnalyzer.analyzeAll()`, appends the summary line, and returns it. Wire a `--coverage` arg in `main` that prints it. Keep existing modes untouched.

- [ ] **Step 4: Run it to verify it passes.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.tools.rewind.TestRewindFieldInventoryTool#coverageModeRendersSummary" test`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "feat(tools): RewindFieldInventoryTool --coverage report"
```

---

### Task 7: Report-only coverage guard + committed baseline

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/coverage/TestRewindCoverageGuard.java`
- Create: `src/test/resources/rewind/coverage-baseline.txt` (committed snapshot of current gaps)

**Interfaces:**
- Consumes: `RewindCoverageAnalyzer.analyzeAll()`, `RewindCoverageReport.gapKeys()`/`render()`.
- Produces: a green, report-only guard that fails **only on regressions vs. the baseline** (a *new* gap KEY), not on the existing backlog. Because the baseline stores per-reason gap keys (`class#recreate`, `class#finalScalar#field`, `class#objectRef#field`), a *new* gap on an already-listed class — e.g. a newly-added uncaptured `final` field — is still caught.

- [ ] **Step 1: Write the test** (it will pass once the baseline is generated — this guard is green by construction).

```java
package com.openggf.game.rewind.coverage;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TestRewindCoverageGuard {
    private static final Path BASELINE =
            Path.of("src/test/resources/rewind/coverage-baseline.txt");

    @Test
    void noNewCoverageGapsBeyondBaseline() throws Exception {
        // Compare stable gap KEYS, not class names: class#recreate,
        // class#finalScalar#field, class#objectRef#field.
        Set<String> current = new TreeSet<>(RewindCoverageAnalyzer.analyzeAll().gapKeys());
        Set<String> baseline = new TreeSet<>(Files.readAllLines(BASELINE));
        baseline.removeIf(String::isBlank);

        Set<String> regressions = new TreeSet<>(current);
        regressions.removeAll(baseline);
        assertTrue(regressions.isEmpty(),
                "New rewind-coverage gap keys introduced (not in baseline):\n  "
                        + String.join("\n  ", regressions)
                        + "\nFix the object's rewind coverage, or (only if intentional) "
                        + "add the key(s) to " + BASELINE + ".");

        // Informational: surface gaps closed since baseline so the baseline can be tightened.
        Set<String> closed = new TreeSet<>(baseline);
        closed.removeAll(current);
        if (!closed.isEmpty()) {
            System.out.println("[rewind-coverage] gaps closed since baseline (tighten baseline):\n  "
                    + String.join("\n  ", closed));
        }
    }
}
```

- [ ] **Step 2: Generate the baseline.** Run the analyzer once and write its current gaps to the baseline file:

```bash
mvn -q -Dmse=relaxed -DskipTests test-compile
# one-off main or jshell; simplest is a throwaway @Test that prints gaps, or:
mvn -Dmse=off "-Dtest=com.openggf.tools.rewind.TestRewindFieldInventoryTool#coverageModeRendersSummary" test
```

Then capture the gap **keys** — `RewindCoverageAnalyzer.analyzeAll().gapKeys()` (e.g. `Foo#recreate`, `Bar#finalScalar#timer`), one per line, already sorted — into `src/test/resources/rewind/coverage-baseline.txt`. (Blank lines are ignored by the test; `#` is part of the key syntax, so do NOT use `#` as a comment marker — put notes in the commit message instead.)

- [ ] **Step 3: Run the guard to verify it passes (green by construction).**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageGuard" test`
Expected: PASS — `Tests run: 1, Failures: 0`. The baseline equals current gaps, so no regressions.

- [ ] **Step 4: Sanity-check the guard bites.** Temporarily delete one line from the baseline, re-run, confirm the test FAILS naming that class, then restore the line.

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageGuard" test`
Expected (with a line removed): FAIL listing the removed class. Restore the baseline; re-run → PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/test/java/com/openggf/game/rewind/coverage/TestRewindCoverageGuard.java src/test/resources/rewind/coverage-baseline.txt
git commit -m "test(rewind): report-only coverage guard + committed gap baseline"
```

---

### Task 8: Wire into must-run + document

**Files:**
- Modify: `docs/status/trace-frontier-log.md` is NOT relevant; instead add a short note to `AGENTS_S3K.md` (or the rewind section of `CLAUDE.md`) pointing future authors at the guard.
- Modify: `docs/architecture/designs/2026-06-17-rewind-object-coverage-architecture-design.md` — mark Phase 1 status `implemented (report-only)`.

**Interfaces:**
- Produces: the guard is discoverable; the spec reflects Phase 1 done.

- [ ] **Step 1: Add a one-paragraph note** to the rewind section of `CLAUDE.md` and `AGENTS.md` (Agent-Docs are kept in sync): "Rewind coverage is audited by `RewindCoverageAnalyzer` and enforced report-only by `TestRewindCoverageGuard` against `src/test/resources/rewind/coverage-baseline.txt`. A new object that lacks a recreate path / has an uncaptured `final` scalar / holds an un-id'd object ref fails the guard; fix coverage or (only if intentional) add it to the baseline. Run `RewindFieldInventoryTool --coverage` for the full report."

- [ ] **Step 2: Update the spec** Phase 1 line to `implemented (report-only); baseline = N gaps`.

- [ ] **Step 3: Full focused test run.**

Run: `mvn -Dmse=off "-Dtest=com.openggf.game.rewind.coverage.*,com.openggf.tools.rewind.TestRewindFieldInventoryTool" test`
Expected: all green.

- [ ] **Step 4: Commit.**

```bash
git add -A
git commit -m "docs(rewind): document report-only coverage guard; mark spec Phase 1 done"
```

(`Agent-Docs: updated` — `AGENTS.md` + `CLAUDE.md` both staged.)

---

## Self-Review notes

- **Spec coverage:** This plan implements §3.5 (static guard — report-only slice) and §3.7 (audit tool extension). It does NOT implement §3.1–3.4 or §3.6 (those are Phases 2–5, separate plans). The guard is intentionally report-only here; flipping it to failing is the close of a later phase once the backlog is burned down.
- **Codex review (folded in):** (1) baseline tracks per-reason **gap keys** (`class#recreate`/`#finalScalar#field`/`#objectRef#field`), not class names, so new gaps on baselined classes are caught; (2) enumeration is **classpath-scan-based**, not factory-map-based (the `factories` map is `protected`, lambda-opaque); (3) recreate-path check unions **`ObjectRewindDynamicCodecs.sharedCodecs()` + registry codecs** (animals/rings/shields/explosions/skid-dust would otherwise false-report); (4) field/ref classification goes through the public **`RewindPolicyRegistry.policyForAudit(field)`** audit API, never the package-private `DefaultObjectRewindPolicies` internals.
- **Known soft spots requiring Task-0 confirmation:** the exact `GenericFieldCapturer` eligibility entry point and whether `RewindPolicyRegistry.policyForAudit` exists or needs a small public audit helper (Tasks 2/4); the classpath-scan utility to reuse (Task 1 — mirror an existing guard test like `TestObjectServicesMigrationGuard`); the no-ROM construction pattern for registries/analyzer (Task 1). These are flagged inline, not hand-waved — confirm against the cited files in Task 0 and use the real symbols.
- **Intentional Phase-1 limitation:** recreate-path detection over-approximates for layout objects (no codec ⇒ `#recreate` gap key, baselined once, never fails the build); the registry audit API that distinguishes layout-from-spawn objects is deferred to the Phase 2 plan. Report-only by design.
- **No behavior change:** every task is additive (new `coverage` package + tool mode + tests + baseline resource). Engine capture/restore untouched. Safe to land independently and leaves the tree green.
