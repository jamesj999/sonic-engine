# ArchUnit Implementation Blueprint

Date: 2026-05-11
Scope: ArchUnit adoption roadmap, architecture-rule functionality, rollout policy
Status: Blueprint (no implementation)

This blueprint consolidates `docs/architecture/audits/proposal-c-archunit-evaluation.md` and
`docs/architecture/audits/proposal-g-archunit-evaluation.md` into a staged roadmap for adding
ArchUnit to the engine. Both evaluations reach the same core recommendation:
adopt ArchUnit as a test-only dependency in two stages, first translating the
existing bytecode-suitable guard rules, then expanding into broader package and
service-boundary rules once current intentional exceptions are named.

---

## Requirements

### Goals

1. **Adopt ArchUnit safely** as a test-only dependency without mixing tooling
   adoption with architectural cleanup.
2. **Replace source-substring architecture checks where bytecode dependency
   analysis is a better fit**, while keeping source/XML guards for invariants
   ArchUnit cannot express.
3. **Create a roadmap for broader architecture enforcement** around object
   service boundaries, shared-vs-game-specific packages, per-game coupling, and
   JUnit 5 usage.
4. **Make architectural exceptions explicit** through allowlists, frozen rules,
   or documented cleanup decisions before strict enforcement blocks feature work.
5. **Keep parity confidence first**: architecture rules should prevent unsafe
   coupling regressions without forcing broad behavior-changing refactors.

### Non-goals

- Implementing ArchUnit in this blueprint PR.
- Refactoring current S3K-to-S1/S2 UI or data-select reuse immediately.
- Removing all `GameServices` usage from non-object code.
- Replacing source guards for `pom.xml`, trace-replay profile structure, comments,
  unused imports, or string-only reflection references.
- Writing a custom method-call rule for `getInstance()` in the first rollout.
- Expanding the roadmap into a full clean-architecture rewrite.

### Constraints

- Use `com.tngtech.archunit:archunit-junit5:1.4.2` with test scope.
- Keep JUnit 5 / Jupiter only; do not introduce JUnit 4 or Vintage.
- Preserve the existing Maven/Surefire setup, including property-driven fork
  count and `reuseForks=true`.
- Do not rely on ArchUnit for XML assertions, source-text assertions, comments,
  unused imports, or string-only reflection references.
- New architecture rules must have clear ownership, exception policy, and
  verification commands before merge.
- Follow the branch and commit trailer policy in `AGENTS.md`.
- If `FreezingArchRule` is used, pin the freeze-store path explicitly instead of
  accepting ArchUnit's default location.

### Acceptance criteria

- AC1: A first implementation PR can add ArchUnit and port the six existing
  bytecode-suitable architecture guards without requiring architectural cleanup.
- AC2: The two existing build/source configuration guards remain enforced outside
  ArchUnit.
- AC3: A second implementation PR has a clear rule-expansion plan, including
  allowlist or freeze decisions for known current exceptions.
- AC4: Each proposed rule has a named intent, expected enforcement scope, known
  blind spots, and a verification command.
- AC5: The roadmap explains how to avoid blocking normal gameplay and parity work
  on intentional legacy bridges.
- AC6: The final state gives maintainers one obvious place to add future
  architecture rules.

### Assumptions

- A1: ArchUnit `1.4.2` remains the preferred version from the evaluations.
- A2: The current eight checks in `TestArchitecturalReviewGuard` remain the
  starting rule set.
- A3: S3K reuse of S1/S2 UI and data-select assets is currently intentional until
  the project chooses a shared package extraction.
- A4: Object-system bridge classes such as `DefaultObjectServices`,
  `BootstrapObjectServices`, object registries, and `AbstractObjectInstance`
  need explicit exception handling rather than ordinary violation treatment.
- A5: Build-time overhead is acceptable if rules are consolidated into a small
  number of ArchUnit test classes using a shared `@AnalyzeClasses` configuration.

### Risks

- R1: **Over-broad rules** could block unrelated gameplay work. Mitigation: stage
  expansion, freeze or allowlist known bridges, and document each exception.
- R2: **False confidence** if source/XML checks are deleted wholesale. Mitigation:
  keep non-bytecode guards in a separate configuration guard.
- R3: **Drift between rule intent and implementation** if ArchUnit rules are
  scattered. Mitigation: centralize rules in one architecture test package/class
  family with names that describe the invariant.
- R4: **Freezing becomes permanent debt** if snapshots are added without owners.
  Mitigation: every freeze records why it exists, what would remove it, and where
  it is documented.
- R5: **CI cost surprises** if each rule imports classes separately. Mitigation:
  share one `@AnalyzeClasses(packages = "com.openggf", cacheMode = FOREVER)`
  setup where possible.

---

## Exploration Synthesis

### Evaluation agreement

Both proposal documents recommend the same adoption shape:

- ArchUnit is feasible and low risk as a test-only dependency.
- Version `1.4.2` is preferred.
- Java 21, Mockito, shaded ASM, and the current Surefire configuration do not
  present known blockers.
- Six of the eight current architecture checks translate cleanly to ArchUnit.
- Two checks should remain as source/XML guards.
- Broader package-boundary rules belong in a second stage after exception policy
  is explicit.

### Current rule split

| Current invariant | Roadmap decision |
|---|---|
| S2/S3K animation managers expose rewind snapshots | Port to ArchUnit; no source-text companion needed |
| `ObjectServices` does not depend on `GameServices` | Port to ArchUnit; keep or consciously retire source-text comment/string enforcement |
| Shared `CheckpointState` avoids concrete S3K event manager dependency | Port to ArchUnit; keep or consciously retire source-text comment/string enforcement |
| Shared level layer avoids `Sonic3kZoneIds` dependency | Port to ArchUnit; keep or consciously retire source-text comment/string enforcement |
| S1/S2 modules avoid concrete S3K data-select manager dependency | Port to ArchUnit; keep or consciously retire source-text comment/string enforcement |
| Shared data-select layer avoids S3K delegate dependency | Port to ArchUnit; keep or consciously retire source-text comment/string enforcement |
| POM has no JUnit 4 or Vintage dependency | Keep source/XML guard |
| Trace replay quarantine profile remains explicit and canonical | Keep source/XML guard |

The existing `TestArchitecturalReviewGuard` deliberately treats forbidden names
in comments and string literals as failures. ArchUnit will not preserve that
surface because it reads compiled class dependencies. PR 1 must therefore make an
explicit choice for each ported substring rule:

- retain a narrow source-text companion guard when the forbidden identifier itself
  is considered documentation debt or a reflection hazard; or
- remove the substring guard and state in the PR summary that comment/string
  enforcement has intentionally been traded for bytecode dependency enforcement.

Do not describe such a retained source-text companion as duplicate enforcement.
It protects a different surface than ArchUnit.

### Known exception areas for expansion

- Object-related packages still include legitimate bridge and registry classes
  that touch `GameServices`.
- Shared layers have current game-specific dependencies such as water and power-up
  bridge cases that need provider inversion or freeze decisions.
- Per-game slice rules would catch intentional S3K reuse of S1/S2 menu,
  data-select, and special-stage assets.
- A `getInstance()` rule is not worth early custom-rule complexity while current
  call sites appear bootstrap-owned.

### Build impact

The evaluations measured or estimated a cold class import cost of a few seconds.
The roadmap should treat this as acceptable, with the following controls:

- keep ArchUnit rules in a small number of test classes;
- use a shared `@AnalyzeClasses` setup;
- avoid many separate import configurations;
- validate with a PR 1 smoke run under normal `mvn test`.

---

## Architecture Decision

### Decision 1: two-stage adoption

Adopt ArchUnit in two implementation PRs.

**PR 1 is adoption and faithful translation only.** It adds the dependency,
creates the consolidated ArchUnit test class, ports the six bytecode-suitable
rules, and keeps the two source/XML checks.

**PR 2 is rule expansion.** It adds broader package-boundary and service-boundary
rules only after the project decides whether each known violation is a cleanup
target, allowlisted bridge, or frozen baseline.

This keeps a low-risk tooling change separate from higher-judgment architecture
enforcement.

### Decision 2: centralize architecture tests by enforcement mechanism

Use two clear test homes:

- `TestArchUnitRules` for bytecode/class dependency rules.
- `TestBuildConfigurationGuard` for XML/build-profile checks.
- `TestSourceTextArchitectureGuard` or the retained
  `TestArchitecturalReviewGuard` for intentionally source-text-level checks,
  including comments and string literals.

Avoid accidental duplicate enforcement, but allow narrow overlap when the
source-text rule intentionally protects comments, unused imports, or string
literals. In that case, name the source-text test after the source-level hazard
and name the ArchUnit test after the bytecode dependency invariant.

### Decision 3: explicit exception policy

Every intentional violation in PR 2 must choose one policy:

- **Allowlist** for permanent bridge classes that are part of the architecture.
- **Freeze** for known debt that should not grow but cannot be removed in the
  rule-introduction PR.
- **Refactor first** when a violation is small, local, and safe to remove before
  enforcing the rule.

Frozen rules must be documented in `docs/status/known-discrepancies.md` or a dedicated
architecture note if the exception is architectural rather than gameplay-visible.

### Decision 4: prefer broad invariants with narrow exclusions

Rules should state the architecture the project wants, then exclude only named
cases. Avoid encoding today's package quirks as the desired design. For example,
"object packages do not access `GameServices` directly" is the invariant;
`DefaultObjectServices` and registries are exceptions, not the model.

---

## Feature Design

### PR 1: adoption and translation

**Behavior**

- Maven resolves ArchUnit during tests only.
- A consolidated ArchUnit test imports `com.openggf` classes once per fork and
  enforces the six ported invariants.
- Existing XML/source guards remain green and continue protecting the POM and
  trace-replay profile.

**Implementation shape**

- Add dependency:

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.4.2</version>
    <scope>test</scope>
</dependency>
```

- Add `src/test/java/com/openggf/tests/TestArchUnitRules.java`.
- Use:

```java
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;

@AnalyzeClasses(packages = "com.openggf", cacheMode = CacheMode.FOREVER)
class TestArchUnitRules {
}
```

`@AnalyzeClasses(packages = "com.openggf")` recursively imports subpackages.
Inside ArchUnit DSL predicates, continue using ArchUnit package-pattern syntax
such as `com.openggf..` or `..sonic3k..` where `resideInAPackage` /
`resideInAnyPackage` expects patterns. Also note that Proposal C's
`ForCachingMode.FOREVER` spelling is wrong; the JUnit 5 enum to import is
`com.tngtech.archunit.junit.CacheMode`.

- Move or retain the two non-ArchUnit checks in a source/XML guard class.
- For the six ported rules, either retain narrow source-text companion checks for
  comment/string enforcement or document the deliberate loss of that surface.
- Remove only checks that are truly superseded by ArchUnit and no longer protect
  an intended source-text behavior.

**Acceptance tests**

- `mvn test -Dtest=TestArchUnitRules,TestArchitecturalReviewGuard`
- Full `mvn test` before merge if local ROM availability does not block the suite.

### PR 2: broader rule expansion

**Rule 1: object code uses object services**

Intent: object instances and game object packages should not reach back to the
global `GameServices` facade.

Initial stance: add with explicit bridge allowlist or freeze.

Expected exceptions:

- `AbstractObjectInstance`
- `DefaultObjectServices`
- `BootstrapObjectServices`
- object registries and construction/bootstrap classes

Follow-up target: investigate remaining direct object-package access and replace
with injected `ObjectServices` where local and safe.

Blind spots: comments, unused imports, string-only reflection, runtime service
lookup through indirection, and generated bytecode outside the imported package
scope.

**Rule 2: shared layers avoid game-specific packages**

Intent: shared `level..` and non-game-specific `game..` code should not compile
against `sonic1..`, `sonic2..`, or `sonic3k..` implementation packages.

Initial stance: add after freeze or small cleanup.

Known pressure points:

- water constants or behavior crossing into shared code;
- default power-up spawning with game-specific visuals or shields;
- registries and cross-game donation providers that may need exclusions.

Follow-up target: provider inversion or shared capability interfaces where
exceptions are not intended long-term.

Blind spots: comments, unused imports, string-only reflection, runtime-registered
providers, service-loader style discovery, and data-driven dependencies through
ROM/config tables.

**Rule 3: per-game packages do not cross-depend accidentally**

Intent: `sonic1`, `sonic2`, and `sonic3k` packages should not grow hidden
compile-time coupling.

Initial stance: freeze known S3K-to-S1/S2 donation and menu reuse, then enforce
no-new-crossing behavior.

Known pressure points:

- S3K level-select and data-select reuse of S1/S2 menu assets;
- S3K emerald and preview rendering paths that deliberately reuse older assets.

Follow-up target: extract stable shared menu/data-select asset helpers if the
reuse should become first-class architecture.

Blind spots: cross-game coupling through shared registries, reflective lookup,
string constants, copied assets with no class dependency, and runtime donation
contracts registered without direct compile-time references.

**Rule 4: tests stay on JUnit 5**

Intent: prevent new `org.junit` / JUnit 4 references at class level.

Initial stance: add immediately in PR 2 as a supplemental guard, while retaining
the POM-level no-JUnit4/no-Vintage check.

Known pressure points: none found in the evaluations.

Blind spots: test dependencies declared only in `pom.xml`, comments mentioning
JUnit 4, disabled source files outside imported test packages, and generated
test sources not imported by the rule.

**Deferred rule: non-bootstrap `getInstance()` calls**

Intent is valid, but early enforcement needs a custom method-call condition and
careful bootstrap boundaries. Defer until there are non-bootstrap violations or a
broader singleton cleanup wave.

---

## Roadmap

### Phase 0: blueprint and decision record

Primary tag: `confidence-first`

Deliverables:

- this blueprint;
- one implementation issue or plan split into PR 1 and PR 2;
- explicit statement that PR 1 is not allowed to include cleanup.

Exit criteria:

- maintainers agree with the two-stage rollout;
- documentation location for frozen architecture exceptions is chosen.

### Phase 1: ArchUnit adoption

Primary tag: `confidence-first`

Deliverables:

- test dependency in `pom.xml`;
- `TestArchUnitRules`;
- six ported current rules;
- retained source/XML guard for POM and trace-replay profile checks.

Verification:

```bash
mvn test -Dtest=TestArchUnitRules,TestArchitecturalReviewGuard
```

Exit criteria:

- PR passes without architecture cleanup;
- no accidental duplicate enforcement remains for the six ported rules; any
  retained source-text companion checks are named as comment/string hazard guards;
- test runtime impact is noted in the PR summary.

### Phase 2: exception classification

Primary tag: `confidence-first`

Deliverables:

- inventory of current violations for the four proposed expansion rules;
- classification of each violation as allowlist, freeze, or refactor-first;
- documented owner/rationale for each frozen baseline.
- this may ship as part of PR 2 if it is short, but if the inventory changes
  exception policy materially, split it into a docs/planning PR before adding
  enforcement.

Verification:

```bash
mvn test -Dtest=TestArchUnitRules
```

Exit criteria:

- every intentional exception is named;
- no broad rule is merged with unexplained failures hidden in implementation.
- maintainers have approved the classification before strict PR 2 rules are
  made blocking.

### Phase 3: expanded architecture rules

Primary tag: `elegance-first`

Deliverables:

- object-package service-boundary rule;
- shared-vs-game-specific package rule;
- per-game package slice rule;
- test JUnit 5 dependency rule;
- freeze or allowlist support where chosen in Phase 2.

Verification:

```bash
mvn test -Dtest=TestArchUnitRules,TestArchitecturalReviewGuard
```

Exit criteria:

- rules pass;
- known exceptions are frozen or allowlisted explicitly;
- no rule blocks intended cross-game donation without a documented migration path.

### Phase 4: cleanup waves

Primary tag: `elegance-first`

Deliverables are small follow-up PRs, not part of ArchUnit adoption:

- extract shared menu/data-select asset helpers if S3K reuse should be first-class;
- invert providers for shared level/game dependencies on per-game classes;
- shrink bridge allowlists where object code can receive injected services;
- revisit `getInstance()` enforcement once bootstrap boundaries are clearer.

Exit criteria:

- frozen violation counts only decrease;
- each cleanup has parity-appropriate tests.

---

## Implementation Plan

### T1: PR 1 dependency and test scaffold

Files:

- `pom.xml`
- `src/test/java/com/openggf/tests/TestArchUnitRules.java`

Tests first:

- add a small ArchUnit smoke rule that imports `com.openggf` under the existing
  Surefire argLine, proving ArchUnit class import coexists with the configured
  Mockito Java agent in CI;
- keep the smoke rule intentionally boring, for example a self-consistency rule
  over a known package, because its purpose is dependency/import compatibility,
  not architecture policy;
- run the targeted architecture tests.

Verification:

```bash
mvn test -Dtest=TestArchUnitRules
```

### T2: PR 1 current-rule translation

Files:

- `src/test/java/com/openggf/tests/TestArchUnitRules.java`
- `src/test/java/com/openggf/tests/TestArchitecturalReviewGuard.java`
  or `TestBuildConfigurationGuard.java`

Scope:

- port the six bytecode-suitable checks;
- keep the two source/XML checks;
- decide whether each ported substring check keeps a source-text companion for
  comment/string enforcement;
- document any intentional loss of source-text enforcement in the PR summary;
- delete only checks whose source-text behavior is no longer intended.

Verification:

```bash
mvn test -Dtest=TestArchUnitRules,TestArchitecturalReviewGuard
```

### T3: PR 2 violation inventory

Files:

- `src/test/java/com/openggf/tests/TestArchUnitRules.java`
- documentation file chosen for exception decisions

Scope:

- add candidate rules locally;
- record every violation and classify it;
- decide the freeze-store location if any rule uses `FreezingArchRule`;
- do not merge failing broad rules.

Verification:

```bash
mvn test -Dtest=TestArchUnitRules
```

### T4: PR 2 rule expansion

Files:

- `src/test/java/com/openggf/tests/TestArchUnitRules.java`
- exception documentation if freezing known debt

Scope:

- add object-service boundary rule;
- add shared-layer game-package boundary rule;
- add per-game slice rule;
- add JUnit 5 class-dependency rule;
- encode allowlists/frozen baselines from T3.
- if freezing, commit an `archunit.properties` choice for the store path, with a
  preferred location of `src/test/resources/archunit/frozen`.

Verification:

```bash
mvn test -Dtest=TestArchUnitRules,TestArchitecturalReviewGuard
```

### T5: end-to-end verification and review

Scope:

- run targeted architecture tests;
- run full test suite where environment allows;
- summarize runtime impact, changed files, exceptions, and follow-up cleanup.

Verification:

```bash
mvn test
```

### Commit trailer expectations

The implementation PRs must satisfy the tracked hook policy:

- PR 1 likely needs `Changelog: updated` if the test dependency is treated as a
  project-visible tooling change; otherwise the PR must explicitly justify
  `Changelog: n/a`.
- PR 1 should normally use `Guide: n/a`,
  `Known-Discrepancies: n/a`, `S3K-Known-Discrepancies: n/a`,
  `Agent-Docs: n/a`, `Configuration-Docs: n/a`, and `Skills: n/a` unless those
  files are changed.
- PR 2 needs `Known-Discrepancies: updated` if frozen architecture exceptions are
  documented in `docs/status/known-discrepancies.md`.
- If a dedicated architecture exception doc is added elsewhere, use the trailer
  values required by the actual staged files. Do not bypass `.githooks/run-policy`.

---

## Human Review Questions

1. Should frozen architectural exceptions be documented in
   `docs/status/known-discrepancies.md`, a new `docs/architecture/archunit-exceptions.md`,
   or both?
2. Should PR 2 prefer `FreezingArchRule` snapshots for current debt, or explicit
   allowlists with comments for each known exception?
3. If freezing is used, confirm `src/test/resources/archunit/frozen` as the
   baseline store path, or choose a different committed path before PR 2.
4. Should S3K-to-S1/S2 menu and data-select reuse be treated as intentional
   long-term architecture, or as debt to extract into a shared package?
5. Should the retained source/XML checks stay in `TestArchitecturalReviewGuard`,
   or be renamed to `TestBuildConfigurationGuard` when ArchUnit lands?
6. For the six rules ported in PR 1, should forbidden names in comments and
   string literals remain failing source-text hazards, or is bytecode-only
   enforcement sufficient?

---

## Recommendation

Proceed with the two-stage roadmap.

PR 1 should be deliberately small: add ArchUnit, port only the six existing
bytecode-suitable checks, and keep the two source/XML guards. PR 2 should add
broader rules only after exception policy is explicit. This gives the project
better architectural enforcement without weakening parity-focused development or
turning tooling adoption into a cleanup gate.
