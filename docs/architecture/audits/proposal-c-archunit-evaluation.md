# Proposal C: Hybrid ArchUnit Adoption Plan

Synthesizes `docs/architecture/audits/gpt-archunit-evaluation.md` and `docs/architecture/audits/claude-archunit-evaluation.md`. Both independently recommend the same shape ("two PRs, adoption first"); this proposal picks the strongest detail from each.

## Feasibility

**Verdict: feasible, no blockers.**

- Coordinate: `com.tngtech.archunit:archunit-junit5:1.4.2` (current on Maven Central). Java 21 support landed in ArchUnit 1.1.0; 1.4.2 covers Java 26 class files.
- Dependency footprint after `mvn dependency:get`: **4.470 MiB** total — `archunit` 4.340 MiB, `archunit-junit5-engine` 0.053 MiB, `archunit-junit5-api` 0.006 MiB, `archunit-junit5-engine-api` 0.004 MiB, aggregate jar 0.001 MiB, plus `slf4j-api` 0.067 MiB.
- ASM is shaded inside ArchUnit as `com/tngtech/archunit/thirdparty/org/objectweb/asm`, so no conflict with Mockito or other test deps.
- Mockito agent (`-javaagent`, pom.xml:17) does not interfere — Mockito rewrites at load time while ArchUnit reads class files directly via ASM offline.
- Surefire (`forkCount=4`, `reuseForks=true`; pom.xml:317-318) benefits from ArchUnit's `@AnalyzeClasses(cacheMode=FOREVER)` — imported classes are reused within a fork.

Existing test deps: JUnit Jupiter 5.10.3 (pom.xml:267), Mockito 5.14.2 (pom.xml:277). No SLF4J binding on the test classpath; ArchUnit will log via SLF4J no-op.

Sources: ArchUnit user guide, Maven Central, ArchUnit GitHub releases (v1.1.0, v1.4.2).

## Rule translations (current 8)

| # | Current rule (file:line) | ArchUnit DSL | Strength |
|---|---|---|---|
| 1 | `combinedLevelAnimationManagersExposeRewindSnapshots` (TestArchitecturalReviewGuard.java:50) | `classes().that().haveFullyQualifiedNameMatching(".*(Sonic2LevelAnimationManager|Sonic3kLevelAnimationManager)").should().beAssignableTo(RewindSnapshottable.class)` | Equivalent — already a bytecode/reflection check |
| 2 | `objectServicesInterfaceDoesNotReachBackToGlobalGameServices` (:56) | `noClasses().that().haveFullyQualifiedName("com.openggf.level.objects.ObjectServices").should().dependOnClassesThat().areAssignableTo(GameServices.class)` | Stronger for bytecode deps; weaker for comments and unused imports |
| 3 | `sharedCheckpointStateDoesNotDependOnSonic3kConcreteEvents` (:64) | `noClasses().that().haveFullyQualifiedName("com.openggf.game.CheckpointState").should().dependOnClassesThat().haveSimpleName("Sonic3kLevelEventManager")` | Stronger |
| 4 | `sharedLevelLayerDoesNotImportSonic3kZoneConstants` (:71) | `noClasses().that().haveFullyQualifiedNameMatching("com\\.openggf\\.level\\.(LevelManager|LevelRenderer)").should().dependOnClassesThat().haveSimpleName("Sonic3kZoneIds")` | Stronger |
| 5 | `sonicOneAndTwoModulesDoNotNameS3kDataSelectImplementation` (:82) | `noClasses().that().haveFullyQualifiedNameMatching(".*Sonic[12]GameModule").should().dependOnClassesThat().haveSimpleName("S3kDataSelectManager")` | Stronger |
| 6 | `sharedDataSelectLayerDoesNotImportGameSpecificDelegate` (:91) | `noClasses().that().haveFullyQualifiedName("com.openggf.game.dataselect.CrossGameDataSelectPresentations").should().dependOnClassesThat().resideInAPackage("com.openggf.game.sonic3k..")` | Stronger for compile deps; weaker for reflective string references |
| 7 | `pomDoesNotKeepJUnit4OrVintageOnTestClasspath` (:104) | Not ArchUnit — keep substring check on pom.xml | Different tool; ArchUnit can't read XML |
| 8 | `traceReplayQuarantineIsExplicitAndNotGameSpecific` (:112) | Not ArchUnit — keep XML/source guard | Different tool |

Rules 1–6 move to ArchUnit; rules 7–8 stay as substring (they assert against pom.xml content, not bytecode).

## Proposed new rules

| Rule | ArchUnit DSL | Confidence | Notes |
|---|---|---|---|
| Object packages do not access `GameServices` | `noClasses().that().resideInAnyPackage("com.openggf.level.objects..", "..sonic1.objects..", "..sonic2.objects..", "..sonic3k.objects..").should().accessClassesThat().areAssignableTo(GameServices.class)` | Medium-high with allowlist | Allowlist bridges: `AbstractObjectInstance`, `*ObjectRegistry`, `BootstrapObjectServices`, `DefaultObjectServices`. Expect ~29 bytecode-level hits on first run; ~3 "real leak" survivors after allowlist (`Sonic2ObjectRegistry.java:364`, `AizIntroTerrainSwap.java:181`). |
| Shared `level..` / non-game `game..` do not depend on per-game packages | `noClasses().that().resideInAnyPackage("com.openggf.level..", "com.openggf.game..").and().resideOutsideOfPackages("..sonic1..", "..sonic2..", "..sonic3k..").should().dependOnClassesThat().resideInAnyPackage("..sonic1..", "..sonic2..", "..sonic3k..")` | Medium | Known leaks: `WaterSystem.java:10` (Sonic1Constants), `DefaultPowerUpSpawner.java:10-14` (S1 splash + S3K shields), `GameModuleRegistry`, `CrossGameFeatureProvider`. Fix via providers or freeze. |
| Per-game packages do not cross-depend | `slices().matching("com.openggf.game.(sonic*)..").should().notDependOnEachOther()` | Medium | All known crossings are sonic3k→sonic2 UI-asset reuse: `Sonic3kLevelSelectManager` (MenuBackgroundAnimator), `HostEmeraldPaletteBuilder` (Sonic2SpecialStageConstants), `S3kDataSelectPresentation` (S2DataSelectImageCacheManager, S2SelectedSlotPreviewLoader). 0 sonic2→sonic3k, 0 sonic1→others. Extract a `..menu..` shared package or freeze. |
| Tests do not use JUnit 4 | `noClasses().that().resideInAPackage("com.openggf..").should().dependOnClassesThat().resideInAPackage("org.junit").andShould().not().resideInAPackage("org.junit.jupiter..")` | High | 0 current violations. Adds per-class enforcement alongside the pom.xml check. |
| Non-bootstrap code does not call `getInstance()` | Custom condition; method-call predicate not built-in | Low — skip | 4 current call sites, all in `EngineContext.java:42-45` (the intended bootstrap site). Not worth a rule. |

The four high-priority rules each need an allowlist or `FreezingArchRule` snapshot before they go green.

## Build-time impact

Measured with jshell importing `target/classes`: **2,454 classes, 12,840,814 bytes, cold import 3,054 ms, warm 1,600 ms.** Including test classes: 3,981 classes, 20,031,873 bytes — extrapolated **~4.8 s cold** by size ratio.

Surefire `forkCount=4` worst case: ~12.2 s aggregate CPU, ~3.1 s wall (parallel). With one combined `TestArchUnitRules` class using `@AnalyzeClasses(cacheMode=ForCachingMode.FOREVER)`, the cold cost is paid once per fork. CI profile (`forkCount=1`, pom.xml:83) pays it once total.

ArchUnit caches imported classes between tests using the same `@AnalyzeClasses`, per fork/classloader.

## Recommended PR shape

**Two PRs, adoption first.**

### PR 1 — adoption + translation

- Add `com.tngtech.archunit:archunit-junit5:1.4.2` to `pom.xml` under `<scope>test</scope>`.
- Create `src/test/java/com/openggf/tests/TestArchUnitRules.java` with `@AnalyzeClasses(packages="com.openggf..", cacheMode=ForCachingMode.FOREVER)`.
- Translate rules 1–6 from `TestArchitecturalReviewGuard.java:50–101` to ArchUnit `@ArchTest` methods.
- Keep rules 7–8 (`TestArchitecturalReviewGuard.java:104, 112`) — they assert against pom.xml XML content.
- Delete the six substring rules now superseded by ArchUnit (avoids two enforcement layers for the same invariant).
- Verify rules pass before merge: the source-text substring checks were strictly stricter than bytecode rules would be, so all six should pass on day one.
- Trailers expected: `Skills: n/a`, `Agent-Docs: n/a` unless ArchUnit guidance is added to `.agents/skills/` and `.claude/skills/`.

### PR 2 — expansion + cleanup

- Add the four high-priority new rules (skip the `getInstance` rule).
- Build the explicit bridge allowlist as ArchUnit `ArchCondition` predicates or `should().notBe(<allowlist>)`: `AbstractObjectInstance`, `*ObjectRegistry`, `BootstrapObjectServices`, `DefaultObjectServices`.
- For known intentional violations, use ArchUnit's `FreezingArchRule` to snapshot existing baselines:
  - sonic3k→sonic2 UI reuse: `Sonic3kLevelSelectManager`, `HostEmeraldPaletteBuilder`, `S3kDataSelectPresentation`.
  - level→sonic1/sonic3k leaks: `WaterSystem`, `DefaultPowerUpSpawner`.
- For each freeze, document the decision (leave frozen vs. plan to refactor via shared `..menu..` package or provider inversion) in `docs/status/known-discrepancies.md`.
- Trailers expected: `Known-Discrepancies: updated` for the freeze documentation; others as applicable.

### Rejected alternatives

- **Single mega-PR (option a):** bundles risky cleanup with low-risk tooling adoption. Cleanup failures would force a tooling revert.
- **Keep substring rules alongside ArchUnit equivalents (option c):** two enforcement layers for the same rule means drift risk and confusing failure modes.

## Risks / open questions

- **What ArchUnit can't catch:** unused imports, comments, POM regressions, string-only reflection (`Class.forName` with computed names), source-file metadata. The pom XML rules (7, 8) remain on substring for these reasons.
- **What bytecode adds vs. source-text:** transitive deps, inheritance, generic type usage, annotation references. First ArchUnit run will report more hits than the current substring guard does; that's expected — the allowlist/freeze work in PR 2 brings it back down.
- **Mockito agent compatibility:** safe in principle (ArchUnit reads class files offline, Mockito rewrites at load time). PR 1 should include a smoke test — one trivial ArchUnit rule asserting a known truth about `com.openggf..` — to confirm in CI.
- **Repo trailer policy (CLAUDE.md):** PR 1 likely needs honest `Skills: n/a` / `Agent-Docs: n/a` trailers. PR 2 likely needs `Known-Discrepancies: updated`. Don't bypass the policy with `--no-verify`.
- **Intentional architectural exceptions:** the sonic3k→sonic2 UI reuse is real and load-bearing (S3K menu reuses S2 assets). Freezing is the safe default for the rule rollout; the decision to refactor is out of scope for adoption.
- **Rule scope is the real risk, not deps.** The dependency footprint and build-time impact are both small. The risk is asserting more than the team intends and blocking PRs on architectural exceptions that were always supposed to be allowed.
