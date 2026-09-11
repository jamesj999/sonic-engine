# ArchUnit Adoption Evaluation

Evaluation of replacing/augmenting `TestArchitecturalReviewGuard.java` (substring guard) with ArchUnit.

## Feasibility

**Verdict: feasible, no blockers.**

- Current ArchUnit coordinate: `com.tngtech.archunit:archunit-junit5:1.3.0` (Oct 2024). Supports Java 21 bytecode (uses ASM 9.x) and JUnit Jupiter 5.x via `@AnalyzeClasses` + `@ArchTest`. Compatible with `org.junit.jupiter:junit-jupiter:5.10.3` (pom.xml:424).
- **Dep footprint:** archunit core ~5.0 MB, archunit-junit5 ~30 KB, transitive ASM ~340 KB, SLF4J API ~70 KB. Net add ~5.5 MB to test classpath.
- **Conflict risk vs existing test deps (pom.xml:427-432):**
  - Mockito agent (`-javaagent`, pom.xml:17) is a bytecode-instrumentation hook — ArchUnit analyzes class files via ASM offline, no agent or runtime hook. No conflict.
  - No SLF4J binding is currently on the test classpath; ArchUnit will log via SLF4J no-op, which is fine.
- **Surefire interaction:** `forkCount=4`, `reuseForks=true` (pom.xml:317-318). ArchUnit's `ClassFileImporter` caches imported classes statically per JVM, so reuse benefits hold within a fork.

## Rule translations (current 8)

| # | Current rule (file:line) | ArchUnit DSL | Strength |
|---|---|---|---|
| 1 | `combinedLevelAnimationManagersExposeRewindSnapshots` (TestArchitecturalReviewGuard.java:50) | `classes().that().haveSimpleName("Sonic2LevelAnimationManager").or(...Sonic3k...).should().implement(RewindSnapshottable.class)` | **Equivalent** — already a reflection check, no improvement |
| 2 | `objectServicesInterfaceDoesNotReachBackToGlobalGameServices` (:56) | `noClasses().that().resideInAPackage("..level.objects..").and().haveSimpleName("ObjectServices").should().dependOnClassesThat().haveFullyQualifiedName("com.openggf.game.GameServices")` | **Stronger** — catches `Class.forName`, reflection, transitive usage that substring misses |
| 3 | `sharedCheckpointStateDoesNotDependOnSonic3kConcreteEvents` (:64) | `noClasses().that().haveSimpleName("CheckpointState").should().dependOnClassesThat().haveSimpleName("Sonic3kLevelEventManager")` | **Stronger** — bytecode-level, not source-text |
| 4 | `sharedLevelLayerDoesNotImportSonic3kZoneConstants` (:71) | `noClasses().that().haveSimpleName("LevelManager").or(...LevelRenderer...).should().dependOnClassesThat().haveSimpleName("Sonic3kZoneIds")` | **Stronger** |
| 5 | `sonicOneAndTwoModulesDoNotNameS3kDataSelectImplementation` (:82) | `noClasses().that().haveSimpleName("Sonic1GameModule").or(...Sonic2GameModule...).should().dependOnClassesThat().haveSimpleName("S3kDataSelectManager")` | **Stronger** |
| 6 | `sharedDataSelectLayerDoesNotImportGameSpecificDelegate` (:91) | `noClasses().that().haveSimpleName("CrossGameDataSelectPresentations").should().dependOnClassesThat().resideInAPackage("com.openggf.game.sonic3k..")` | **Stronger** — substring rule misses non-import references (`Class.forName`, FQN literals) |
| 7 | `pomDoesNotKeepJUnit4OrVintageOnTestClasspath` (:104) | (no equivalent — ArchUnit analyzes bytecode, not XML) | **Weaker — keep as substring** |
| 8 | `traceReplayQuarantineIsExplicitAndNotGameSpecific` (:112) | (no equivalent — pom.xml content check) | **Weaker — keep as substring** |

Rules 7 and 8 are XML/build assertions; substring is the right tool. Rules 1-6 translate cleanly with strict gain.

## Proposed new rules

Sample violation counts via Grep on `src/main/java`:

| Rule | ArchUnit DSL | Confidence | Current violations |
|---|---|---|---|
| Object packages must not call `GameServices.*` | `noClasses().that().resideInAnyPackage("..level.objects..", "..game.sonic1.objects..", "..game.sonic2.objects..", "..game.sonic3k.objects..").should().accessClassesThat().haveFullyQualifiedName("com.openggf.game.GameServices")` with exclusions for `AbstractObjectInstance`, `*ObjectRegistry`, `BootstrapObjectServices`, `DefaultObjectServices` (legitimate bridges) | **Medium-high** — CLAUDE.md mandates `services()` over `getInstance()` for object code | **3 `GameServices.` accesses** in game-specific object packages: `Sonic2ObjectRegistry.java:364`, `AizIntroTerrainSwap.java:181-182` (the registry call and mutation-pipeline plumbing may be legitimate — needs case-by-case review). Note: bytecode-based ArchUnit will NOT flag unused `import com.openggf.game.GameServices;` in ~5 sonic1 object files (e.g. `SYZBossSpike.java:3`, `Sonic1JunctionObjectInstance.java:14`), unlike the substring guard. |
| Shared `com.openggf.level..` must not depend on `com.openggf.game.sonic[123k]..` | `noClasses().that().resideInAPackage("com.openggf.level..").should().dependOnClassesThat().resideInAnyPackage("..game.sonic1..", "..game.sonic2..", "..game.sonic3k..")` | **High** | **6 imports**: `level/WaterSystem.java:10` (Sonic1Constants), `level/objects/DefaultPowerUpSpawner.java:10-14` (S1 splash + S3K shields) — both look like real architectural leaks that should be inverted via providers |
| Per-game packages must not cross-depend | `noClasses().that().resideInAPackage("..game.sonic3k..").should().dependOnClassesThat().resideInAPackage("..game.sonic2..")` (symmetric variants) | **High** | **4 imports** sonic3k → sonic2: `Sonic3kLevelSelectManager` (MenuBackgroundAnimator), `HostEmeraldPaletteBuilder` (Sonic2SpecialStageConstants), `S3kDataSelectPresentation` (S2DataSelectImageCacheManager, S2SelectedSlotPreviewLoader). 0 sonic2→sonic3k, 0 sonic1→others. The 4 sonic3k→sonic2 leaks reflect S3K reusing S2 UI assets — likely intentional but should be acknowledged via a shared `..menu..` package or `@SuppressArchitectureViolation` rather than silent imports |
| Tests must not reference `org.junit.*` (JUnit 4) | `noClasses().that().resideInAPackage("..tests..", "com.openggf..").should().dependOnClassesThat().resideInAPackage("org.junit").andShould().not().resideInAPackage("org.junit.jupiter..")` | **High** | **0** — enforced by pom already; ArchUnit makes it a per-class enforcement |
| Non-bootstrap code must not call `getInstance()` | Hard to express precisely in ArchUnit (no method-name predicate without custom condition) | **Low** | **4 occurrences** all in `EngineContext.java` — likely the intended bootstrap site; not worth a rule |

## Build-time impact

ArchUnit imports classes lazily on first use via ASM. Empirically: 1 import of `com.openggf..` (~1500-2000 classes) takes 2-5s on warm JVM. With `@AnalyzeClasses(cacheMode = FOREVER)` (default), reused within a fork. Cost per fork: **~3s cold, ~0s warm**. With `forkCount=4`, total adds: **~12s wall clock** to a ~19s CI run (pom.xml:69, measured 1933 tests at 19s).

Mitigation: put all ArchUnit tests in one class with shared `@AnalyzeClasses`. CI profile (`forkCount=1`, pom.xml:83) pays the cost only once.

## Recommended PR shape

**Option (b): two PRs.**

1. **PR 1 — adoption + translation:** Add ArchUnit dep, translate rules 1-6 to ArchUnit, keep rules 7-8 as substring (pom XML), delete the file-substring versions of 1-6. Net result: same invariants, stronger enforcement.
2. **PR 2 — expansion + cleanup:** Add the new rules above, fix violations as needed. The `level → sonic1/sonic3k` leaks in `WaterSystem` and `DefaultPowerUpSpawner` (~6 imports) and the `sonic3k → sonic2` UI-asset reuse (~4 imports) need code or provider-interface changes before the rules go green — too much scope for a single adoption PR.

Rejected:
- **(a)** Bundles risky cleanup with low-risk tooling adoption. Failures in cleanup would force reverts of the tooling.
- **(c)** Keeping substring guards alongside their ArchUnit equivalents means two enforcement layers for the same rule — drift risk.

## Risks / open questions

- ArchUnit on **Mockito-instrumented classes:** Mockito agent rewrites at load time, but ArchUnit reads class files directly from disk/JAR — no interference, but worth confirming with a smoke test.
- **Bridge classes** (`AbstractObjectInstance:5`, `DefaultObjectServices:9`, `BootstrapObjectServices:7`) legitimately access `GameServices` — the rule needs an explicit allow-list or freeze-set. ArchUnit's `FreezingArchRule` (built-in) handles this without per-class annotations.
- The `sonic3k → sonic2` UI-asset reuse may be **intentional** (S3K menu uses S2 backgrounds). Decide before the rule lands: extract a `com.openggf.game.menu.shared` package, or freeze the 4 known violations.
- **AGENTS.md/CLAUDE.md trailer policy** requires `Skills: updated` etc. for any commit on non-master branches — adoption PR will need to touch `.agents/skills/` and `.claude/skills/` or carry `n/a` trailers honestly.
