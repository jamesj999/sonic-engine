# Proposal G: ArchUnit Adoption Evaluation

This proposal reconciles `docs/architecture/audits/gpt-archunit-evaluation.md` and
`docs/architecture/audits/claude-archunit-evaluation.md` into a single recommendation for
adopting ArchUnit in place of the source-substring portions of
`TestArchitecturalReviewGuard.java`.

## Executive Summary

ArchUnit adoption is feasible and low risk when introduced as a test-only
dependency. The first PR should translate the existing bytecode-suitable
architecture checks and leave XML/source-text guards in place for assertions
that ArchUnit cannot express well. Broader package-boundary rules should land
in a second PR after explicit allowlists or cleanup for current intentional
bridges.

Recommended path: **two PRs**.

1. Add ArchUnit and port the six class-dependency rules.
2. Add broader package-boundary rules after resolving or freezing known
   violations.

## Reconciled Feasibility

Verdict: **feasible, no blockers**.

Use:

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.4.2</version>
    <scope>test</scope>
</dependency>
```

Rationale:

- `1.4.2` is the newer ArchUnit JUnit 5 artifact identified by the GPT
  evaluation. Claude's `1.3.0` coordinate is stale.
- Java 21 is supported. ArchUnit added Java 21 class-file support before the
  currently recommended version.
- The dependency footprint is small for a test-only dependency. The measured
  local footprint from the GPT evaluation is about `4.470 MiB`.
- ASM conflict risk is low because ArchUnit relocates ASM under its own
  package namespace.
- Mockito should not conflict with ArchUnit. Mockito instruments classes at
  runtime through the configured Java agent, while ArchUnit reads class files.

Current repo facts checked against `pom.xml`:

- JUnit Jupiter is declared at `pom.xml:423`.
- Mockito is declared at `pom.xml:428`.
- Default Surefire fork count is property-driven from `pom.xml:20`.
- CI profile sets fork count to `1` at `pom.xml:83`.
- Surefire uses `reuseForks=true` at `pom.xml:318`.

## Existing Rule Translation

The existing architecture guard has eight checks. Six translate cleanly to
ArchUnit. Two should remain as source/XML checks.

| Current invariant | Recommendation | Notes |
|---|---|---|
| S2/S3K animation managers expose rewind snapshots | Port to ArchUnit | Equivalent to current reflection-level intent. |
| `ObjectServices` does not depend on `GameServices` | Port to ArchUnit | Stronger for real bytecode dependencies. |
| Shared `CheckpointState` does not depend on `Sonic3kLevelEventManager` | Port to ArchUnit | Stronger than substring matching. |
| `LevelManager` / `LevelRenderer` do not depend on `Sonic3kZoneIds` | Port to ArchUnit | Stronger than import scanning. |
| S1/S2 game modules do not depend on `S3kDataSelectManager` | Port to ArchUnit | Stronger than source-name scanning. |
| Shared data-select layer does not depend on S3K delegates | Port to ArchUnit | Stronger for compiled references. |
| POM has no JUnit 4 or Vintage dependency | Keep source/XML guard | ArchUnit does not inspect `pom.xml`. |
| Trace replay quarantine profile shape | Keep source/XML guard | This is build/profile configuration, not class architecture. |

Important caveat: ArchUnit does **not** reliably catch comments, unused imports,
POM regressions, or plain string references such as fully qualified names passed
to reflection APIs. It does catch compiled class dependencies that substring
guards can miss.

## Proposed PR 1

PR 1 should be a tooling and faithful-translation change only.

Scope:

- Add `com.tngtech.archunit:archunit-junit5:1.4.2` as a test dependency.
- Add one consolidated ArchUnit test class, for example
  `TestArchitectureRules`.
- Use `@AnalyzeClasses(packages = "com.openggf")`.
- Port the six bytecode-suitable rules.
- Keep the two XML/source checks in the existing guard or move them into a
  clearly named non-ArchUnit guard class.
- Remove only the source-substring implementations that are directly replaced.

This avoids bundling architectural cleanup with the dependency adoption.

## Proposed PR 2

PR 2 should add broader architecture rules after deciding how to handle known
exceptions.

Recommended new rules:

| Rule | Initial stance | Reason |
|---|---|---|
| Object packages should not access `GameServices` directly | Add with allowlist or freeze | Object code should use injected `ObjectServices`; bridge classes need explicit treatment. |
| Shared `com.openggf.level..` should not depend on game-specific packages | Add after cleanup or freeze | Current imports in `WaterSystem` and `DefaultPowerUpSpawner` appear to be real architectural leaks or provider-boundary candidates. |
| Per-game packages should not cross-depend | Add after exception decision | S3K currently reuses S1/S2 data-select and UI assets intentionally; this needs shared-package extraction or explicit freeze. |
| Tests should not reference JUnit 4 APIs | Add as supplemental guard | Current grep found no non-Jupiter `org.junit` imports. |
| Non-bootstrap code should not call `getInstance()` | Defer | Expressing this precisely requires a custom ArchUnit condition and current hits appear to be bootstrap-owned. |

## Current Violation Context

The two source evaluations used different counting scopes. The useful
reconciliation is:

- Raw `GameServices.` access under object-related packages is broader than the
  game-object cases alone because it includes shared bridge and manager classes.
- Current direct `GameServices.` uses include:
  - `AbstractObjectInstance`
  - `ObjectManager`
  - `DefaultObjectServices`
  - `BootstrapObjectServices`
  - `AbstractObjectRegistry`
  - `Sonic2ObjectRegistry`
  - `AizIntroTerrainSwap`
- Bridge classes such as `DefaultObjectServices` and `BootstrapObjectServices`
  should be allowlisted or frozen rather than treated as ordinary violations.
- The S3K-to-S1/S2 data-select and UI dependencies may be intentional cross-game
  donation. They should be acknowledged explicitly before enforcing a strict
  per-game slice rule.

## Build-Time Impact

Expected cost is acceptable.

The GPT evaluation measured:

- `2,454` production classes imported from `target/classes`.
- `3,054 ms` cold import.
- `1,600 ms` immediate second import.
- `3,981` classes including test classes, estimated at roughly `4.8 s` cold by
  size ratio.

With the default Surefire fork count of `4`, each fork may pay import cost once.
The CI profile uses fork count `1`, which keeps CI overhead lower if ArchUnit
tests run in that profile.

Mitigation:

- Keep ArchUnit rules in a small number of test classes.
- Share one `@AnalyzeClasses` configuration where possible.
- Avoid broad custom import options until needed.

## Risks And Decisions

Primary risk: **rule scope**, not dependency conflict.

Decisions needed before PR 2:

- Whether object-system bridge classes should be allowlisted or guarded with
  `FreezingArchRule`.
- Whether S3K reuse of S1/S2 data-select/menu assets should move to a shared
  package or remain as frozen known violations.
- Whether broader `game..` package restrictions should exclude bootstrap,
  registry, donation, and cross-game feature-provider packages.
- Whether source-string checks should remain in the same test class or a
  separate `TestBuildConfigurationGuard`.

## Final Recommendation

Adopt ArchUnit in two stages.

PR 1 should be deliberately boring: add the dependency, port the six dependency
rules, and keep the two build/source guards. PR 2 should introduce broader
architecture rules only after the project explicitly names the existing bridge
and cross-game donation exceptions.

