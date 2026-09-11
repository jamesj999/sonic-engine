## Feasibility
Verdict: feasible, best as an added test dependency first. Current coordinate is `com.tngtech.archunit:archunit-junit5:1.4.2`; the ArchUnit user guide lists the same JUnit 5 convenience artifact, and Maven Central marks `1.4.2` current. Java 21 is covered: ArchUnit `1.1.0` added Java 21 support, and `1.4.2` now supports Java 26 class files.

Local dependency footprint after `mvn dependency:get`: total `4.470 MiB`: `archunit` `4.340 MiB`, `archunit-junit5-engine` `0.053 MiB`, `archunit-junit5-api` `0.006 MiB`, `archunit-junit5-engine-api` `0.004 MiB`, aggregate jar `0.001 MiB`, plus `slf4j-api` `0.067 MiB`. ASM is relocated inside `archunit` as `com/tngtech/archunit/thirdparty/org/objectweb/asm`, so conflict risk with Mockito or other test deps is low. Existing `pom.xml:267` uses JUnit Jupiter `5.10.3`; `pom.xml:277` uses Mockito `5.14.2`.

Sources: https://www.archunit.org/userguide/html/000_Index.html, https://central.sonatype.com/artifact/com.tngtech.archunit/archunit-junit5/versions, https://github.com/TNG/ArchUnit/releases/tag/v1.1.0, https://github.com/TNG/ArchUnit/releases/tag/v1.4.2

## Rule translations (current 8)
| rule | ArchUnit DSL | strength |
|---|---|---|
| S2/S3K animation managers implement rewind | `classes().that().haveFullyQualifiedNameMatching(".*(Sonic2LevelAnimationManager|Sonic3kLevelAnimationManager)").should().beAssignableTo(RewindSnapshottable.class)` | equivalent |
| `ObjectServices` no `GameServices` | `noClasses().that().haveFullyQualifiedName("com.openggf.level.objects.ObjectServices").should().dependOnClassesThat().areAssignableTo(GameServices.class)` | stronger for bytecode deps, weaker for comments |
| `CheckpointState` no S3K event manager | `noClasses().that().haveFullyQualifiedName("com.openggf.game.CheckpointState").should().dependOnClassesThat().haveSimpleName("Sonic3kLevelEventManager")` | stronger |
| `LevelManager`/`LevelRenderer` no S3K zone ids | `noClasses().that().haveFullyQualifiedNameMatching("com\\.openggf\\.level\\.(LevelManager|LevelRenderer)").should().dependOnClassesThat().haveSimpleName("Sonic3kZoneIds")` | stronger |
| S1/S2 modules no `S3kDataSelectManager` | `noClasses().that().haveFullyQualifiedNameMatching(".*Sonic[12]GameModule").should().dependOnClassesThat().haveSimpleName("S3kDataSelectManager")` | stronger |
| shared data-select no S3K delegate | `noClasses().that().haveFullyQualifiedName("com.openggf.game.dataselect.CrossGameDataSelectPresentations").should().dependOnClassesThat().resideInAPackage("com.openggf.game.sonic3k..")` | stronger for compile deps, weaker for reflective strings |
| pom has no JUnit 4/vintage | not ArchUnit; add `noClasses().should().dependOnClassesThat(<JUnit4 predicate>)` for compiled tests only | weaker |
| trace replay profile shape | not ArchUnit; keep XML/source guard | not applicable |

## Proposed new rules
| rule | ArchUnit DSL | confidence | current violation count |
|---|---|---|---|
| object packages do not access `GameServices` | `noClasses().that().resideInAnyPackage("com.openggf.level.objects..", "..sonic1.objects..", "..sonic2.objects..", "..sonic3k.objects..").should().accessClassesThat().areAssignableTo(GameServices.class)` | high, but allowlist services/bootstrap first | 29 hits in 7 files; samples `AbstractObjectInstance.java:397`, `AizIntroTerrainSwap.java:181`, `Sonic2ObjectRegistry.java:364` |
| shared `level..`/non-game-id `game..` do not depend on game packages | `noClasses().that().resideInAnyPackage("com.openggf.level..", "com.openggf.game..").and().resideOutsideOfPackages("..sonic1..", "..sonic2..", "..sonic3k..").should().dependOnClassesThat().resideInAnyPackage("..sonic1..", "..sonic2..", "..sonic3k..")` | medium; donor/bootstrap exceptions exist | 61 hits in 8 files; samples `WaterSystem.java:10`, `GameModuleRegistry.java:5`, `CrossGameFeatureProvider.java:15` |
| per-game packages do not depend on each other | `slices().matching("com.openggf.game.(sonic*)..").should().notDependOnEachOther()` | medium; data-select donation currently crosses games | 14 hits in 8 files; sample `Sonic1LavaTagObjectInstance.java:4`, `S2SelectedSlotPreviewLoader.java:4` |
| tests do not use JUnit 4 | `noClasses().that().resideInAPackage("com.openggf..").should().dependOnClassesThat(<org.junit but not org.junit.jupiter predicate>)` | high | 0 hits by `rg --pcre2` |
| singleton calls limited to bootstrap | custom `noClasses().that().resideOutsideOfPackage("com.openggf.game.session..").should(callMethod named "getInstance")` | medium; definitions must be excluded | 4 call lines, all `EngineContext.java:42-45` |

## Build-time impact
Measured with JShell importing `target/classes`: 2,454 classes, `12,840,814` bytes, cold import `3,054 ms`, immediate second import `1,600 ms`. Including test classes would be 3,981 classes and `20,031,873` bytes, roughly `4.8 s` cold by size ratio. Surefire uses 4 forks (`pom.xml:14`); worst case is one import per fork, about `12.2 s` aggregate CPU and about `3.1 s` wall if parallel. ArchUnit JUnit support caches imported classes between tests using the same `@AnalyzeClasses`, per fork/classloader.

## Recommended PR shape
Choose **(b)**: adoption plus faithful translation first, expansion later. The first PR should add ArchUnit and translate the six bytecode-suitable rules, while keeping the XML/source-string guards for `pom.xml` and trace profile checks. A second PR can tackle broader package rules after explicit allowlists for donor features, bootstrap singletons, and current shared-object rewind policies.

## Risks / open questions
ArchUnit will not catch comments, unused imports, POM regressions, or string-only reflection names. It will catch real bytecode dependencies the substring tests miss. The biggest rollout risk is not dependency conflict; it is rule scope, because existing documented architecture has intentional exceptions today.
