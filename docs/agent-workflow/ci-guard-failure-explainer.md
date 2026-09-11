# CI Guard Failure Explainer

This document maps each architectural / CI guard test in OpenGGF to: what it enforces,
the failure symptom you will see, and the **correct fix**. It exists to make guard
failures actionable without weakening the guard baselines.

It is written for an external agent with no chat context. All paths are repo-relative to
`C:/Users/farre/IdeaProjects/sonic-engine`.

## Running all the guards

```bash
mvn -Dmse=off -Pguards test
```

The `guards` profile selects every `Test*Guard*` class and nothing else. It is
source-only, needs no ROM, and takes about a minute. Use it before pushing anything
structural.

It exists because the guards used to run only in the default profile: the trace
profiles select `**/tests/trace/**` and skip them, and CI's default `test` job is
skipped on push, which is how work lands on develop. A guard could therefore be red
for weeks while every gate reported green -- `TestS1S2PlcComparisonOnlyGuard` was.
CI now runs `-Pguards` on pushes to develop and master, and
`TestBuildToolingGuard.everyGuardTestClassIsSelectedByTheGuardsProfile` fails if a
guard class on disk is not selected by the profile.

Three naming conventions are recognised, and a guard following any of them is picked
up automatically:

| Convention | Example |
|---|---|
| `Test*Guard*` | `TestS1S2PlcComparisonOnlyGuard` |
| `TestNo*` (prohibition guards, hard rules 5 and 6) | `TestNoServicesInObjectConstructors` |
| `TestArchUnit*` | `TestArchUnitRules` |

Name a new guard to match one of them. If you cannot, add an explicit `<include>` to
the profile *and* teach `isGuardTestClassName` about it -- the meta-test only knows
about guards it can recognise, so a guard named outside all three conventions is
still invisible to it. That is the one residual gap in this mechanism.

## How to read a guard failure

1. Find the failing test name in this doc.
2. Read **Enforces** to understand the invariant you violated.
3. Apply the **Correct fix**. Fix the *source*, not the test.
4. Re-run the single guard before re-running the suite.

Run a single guard (PowerShell — quote the `-Dtest` selector):

```powershell
mvn "-Dtest=com.openggf.tests.TestNoServicesInObjectConstructors" test
```

S3K guards that touch ROM data also need the ROM path:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.TestSonic3kPlcArtRegistry" "-Ds3k.rom.path=s3k.gen" test
```

> ## DO NOT EXPAND BASELINES TO GO GREEN
>
> Several of these guards carry an allowlist / baseline (approved-access lists, ambient
> setup baselines, SAFE_FOR_SPAWN_DYNAMIC, allowed creator files). **Adding your new
> class/method/file to a baseline is NOT the default fix.** The default fix is to change
> the code so it satisfies the invariant.
>
> Only expand a baseline when the access is genuinely a documented bridge/framework
> boundary that cannot route through the normal path, and only with an inline comment
> stating the exact reason. An unjustified baseline entry defeats the guard for every
> future change and will be flagged in review. Never use `--no-verify` and never relax a
> guard's threshold/tolerance to pass.

---

## TestNoDirectMapMutationsInGameplay

- **File:** `src/test/java/com/openggf/game/mutation/TestNoDirectMapMutationsInGameplay.java`
- **Enforces:** All gameplay-path level tile edits route through `ZoneLayoutMutationPipeline`
  / a `LevelMutationSurface`, never a direct `getMap().setValue(...)`. This lets the rewind
  framework's copy-on-write intercept every mutation in one place. Scans gameplay packages:
  `game/sonic1|2|3k`, `level/objects`.
- **Failure symptom:** "Direct getMap().setValue() calls found in gameplay paths. All level
  mutations must route through ZoneLayoutMutationPipeline."
- **Correct fix:** Replace the direct call with a pipeline mutation, e.g.
  `services().zoneLayoutMutationPipeline()...` / a `LevelMutationSurface`. See `CLAUDE.md`
  "Level-mutation routing rule" and the `ZoneLayoutMutationPipeline` framework.
- **Exempt by design (do not extend lightly):** editor commands (`PlaceBlockCommand`,
  `DeriveChunkFromPatternsCommand`, `DeriveBlockFromChunksCommand`) and initial-layout
  decoders (`Sonic3kLevel.java`). New gameplay code does not belong on this list.

---

## TestObjectServicesMigrationGuard

- **File:** `src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java`
- **Enforces:** Object implementation classes access runtime dependencies through injected
  `ObjectServices` (`services()` / `tryServices()`), never via static singleton
  `getInstance()` or direct `GameServices.` access. Monitors the core runtime singletons
  (Camera, LevelManager, AudioManager, GameStateManager, SpriteManager, WaterSystem,
  FadeManager, GraphicsManager, plus per-game managers) and keeps an approved allowlist for
  a small set of bridge classes (e.g. `AbstractObjectInstance`, `DefaultObjectServices`,
  `ObjectManager`).
- **Failure symptom:** "Object implementation classes must access runtime dependencies
  through ObjectServices. Move the dependency behind services(), tryServices(),
  ObjectServices, or an approved bridge."
- **Correct fix:** Replace the `getInstance()` / `GameServices.` call inside object methods
  with `services().<accessor>()`. Per `CLAUDE.md`: **never call `getInstance()` from object
  code.** Only add to the approved-bridge allowlist if the class is a genuine framework
  bridge, with a documented line-level reason — not to silence a normal object.

---

## TestNoServicesInObjectConstructors

- **File:** `src/test/java/com/openggf/tests/TestNoServicesInObjectConstructors.java`
- **Enforces:** Object constructors must not call `services()`. Injection happens *after*
  construction (ThreadLocal context set by `ObjectManager`), so a constructor-time
  `services()` throws at runtime. The guard scans for: direct `services()` in constructors;
  indirect constructor chains reaching `services()` through overridable methods;
  `spawnDynamicObject(new X(...))` / `addDynamicObject(new X(...))` where `X`'s constructor
  reaches `services()`; and method calls on freshly constructed objects before registration.
- **Failure symptom:** "Object constructors must not call services()." and
  "Unsafe spawnDynamicObject(new ...) patterns found. ... Use spawnChild(() -> new X(...))
  or spawnFreeChild(() -> new X(...)) instead."
- **Correct fix (in order of preference):**
  1. Defer the `services()` call out of the constructor into `update()` or a lazy
     `ensureInitialized()` first-update pattern.
  2. Spawn children with `spawnChild(() -> new X(...))` or `spawnFreeChild(() -> new X(...))`
     instead of `spawnDynamicObject(new X(...))` / `addDynamicObject(new X(...))`.
  3. For framework-level runtime child creation only, wrap the `new`:
     ```java
     setConstructionContext(services());
     try { child = new Foo(...); } finally { clearConstructionContext(); }
     ```
- **Baseline note:** `SAFE_FOR_SPAWN_DYNAMIC` lists constructors proven not to call
  `services()`. Do not add a class there unless its constructor truly only stores fields.

---

## TestConstructionContextGuard

- **File:** `src/test/java/com/openggf/level/objects/TestConstructionContextGuard.java`
- **Enforces:** For object classes whose constructors *do* call `services()` (subclasses of
  `AbstractObjectInstance`, `BoxObjectInstance`, `ShieldObjectInstance`), every `new`
  call-site in non-constructor methods must be wrapped with
  `setConstructionContext()`/`clearConstructionContext()`, otherwise the child's
  constructor-time `services()` call throws. Allowed creator files: `ObjectManager.java`,
  `DefaultPowerUpSpawner.java`.
- **Failure symptom:** "MISSING CONSTRUCTION CONTEXT — These non-constructor methods create
  objects whose constructors call services(), but do not wrap with setConstructionContext()/
  clearConstructionContext(). The child's services() call will throw at runtime."
- **Correct fix (best first):** Refactor the child so its constructor no longer calls
  `services()` (then `TestNoServicesInObjectConstructors` is satisfied too). If the child
  legitimately needs construction-time context, wrap the call site:
  ```java
  setConstructionContext(services());
  try { child = new Foo(...); } finally { clearConstructionContext(); }
  ```
  Prefer `spawnChild(...)` where possible — it manages the context for you.

---

## TestTraceReplayInvariantGuard

- **File:** `src/test/java/com/openggf/tests/TestTraceReplayInvariantGuard.java`
- **Enforces:** Trace replay code **compares** trace rows; it never writes recorded state
  back into engine state (the comparison-only / read-only trace-data invariant). Also keeps
  the strict default tolerance, keeps trace parser/data/catalog classes engine-independent,
  and requires `*TraceReplay` tests to extend `AbstractTraceReplayTest` /
  `AbstractCreditsDemoTraceReplayTest`.
- **Failure symptom:** "Trace replay must compare trace rows, not write them back into
  engine state" or "Trace parser/data/catalog classes should stay read-only and
  engine-independent."
- **Correct fix:**
  - Never hydrate, e.g. do **not** write `player.setCentreX(trace.xPos())` or any setter
    fed from a trace snapshot/frame field. Let normal gameplay paths drive engine state;
    only read the trace to compare.
  - Keep trace parser/data/catalog classes free of engine dependencies (no `GameServices`,
    `ObjectManager`, `MutableLevel`, etc.).
  - Extend the shared replay base class for new `*TraceReplay` tests.
  - See the `trace-replay-bug-fixing` skill and `docs/status/trace-frontier-log.md`.

---

## TestTraceHydrateSwitchDefault

- **File:** `src/test/java/com/openggf/tests/trace/TestTraceHydrateSwitchDefault.java`
- **Enforces:** The diagnostic system property `oggf.trace.hydrate` stays unset/false in CI.
  Hydration runs are diagnostic-only and never count as green. Asserts
  `Boolean.getBoolean("oggf.trace.hydrate")` is `false`.
- **Failure symptom:** "oggf.trace.hydrate must remain unset in CI — hydration runs are
  diagnostic only and never count as green."
- **Correct fix:** Remove any code, surefire `<systemPropertyVariables>`, or CI config that
  sets `oggf.trace.hydrate=true`. Use hydration only locally for diagnosis; never commit it
  enabled. This guard is the safety net for the comparison-only invariant above.

---

## TestSonic3kPlcArtRegistry

- **File:** `src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java`
- **Enforces:** S3K art-registry entries are ROM-accurate and sane: zone art plan structure
  (standalone art, level art, per-act variants), correct badnik/object inclusion per zone/act,
  correct palette assignments, and mapping bounds (frame/piece counts, tile indices, offsets
  within sane limits — e.g. max frames, max pieces per frame, bounded offset radius). Also
  checks no duplicate keys across standalone + level art per zone/act.
- **Failure symptom:** Assertions on a missing art entry, wrong palette index, tile index
  out of bounds, frame count exceeded, piece count exceeded, or extreme offset.
- **Correct fix:** Update `Sonic3kPlcArtRegistry.getPlan()` (and the supporting art loaders):
  add the missing art key, correct the palette index, fix tile indices to fit the
  decompressed bank, or reduce piece/frame span to the ROM-accurate value.
  - **ROM-only rule:** asset bytes must come from the user ROM via the loader; do **not**
    read mapping/DPLC/art bytes from `docs/` disassembly.
  - **Prefer S&K-side addresses:** put `sonic3k.asm` (`< 0x200000`) offsets in
    `Sonic3kConstants.java`. Use an `s3.asm` reference only if the object has no S&K equivalent
    (rare; verify it). Verify with
    `RomOffsetFinder --game s3k`. See the `s3k-plc-system` and `s3k-disasm-guide` skills.

---

## TestPatternSpriteRendererCorruptionGuard

- **File:** `src/test/java/com/openggf/level/render/TestPatternSpriteRendererCorruptionGuard.java`
- **Enforces:** The sprite renderer rejects pathological mapping frames (excessive piece
  count) instead of rendering them, and logs the rejection only once per corrupt frame
  (no log spam). A frame with too many pieces (>= 81) must be suppressed; normal frames must
  still render.
- **Failure symptom:** A frame with 81 pieces renders without being suppressed, or the
  warning is not deduplicated on the second render.
- **Correct fix:** In `PatternSpriteRenderer.drawFrameIndex()`, guard `pieceCount > 80`: log
  once ("Suppressed suspicious sprite mapping frame pieceCount=..."), cache the rejection so
  repeat renders are silent, and skip drawing. If a *real* frame is being flagged, the bug is
  upstream — your mapping parse is wrong; fix the parser/offsets (see
  `TestSonic3kPlcArtRegistry`), do not raise the piece-count ceiling.

---

## TestJunit5MigrationGuard

- **File:** `src/test/java/com/openggf/tests/rules/TestJunit5MigrationGuard.java`
- **Enforces:** No JUnit 4 reintroduction. No `@Rule`, `@ClassRule`, `@RunWith`; no
  `org.junit.*` (JUnit 4) imports (`Test, Before, After, BeforeClass, AfterClass, Rule,
  ClassRule, Ignore, Assert, Assume`); no `org.junit.runner.*`; no legacy `RequiresRomRule`.
  Tests are JUnit 5 / Jupiter only (`CLAUDE.md`).
- **Failure symptom:** "Legacy rule usage in [file]" or "JUnit 4 import or runner usage in
  [file]".
- **Correct fix (1:1 migrations):**
  - `@Before` -> `@BeforeEach`, `@After` -> `@AfterEach`
  - `@BeforeClass` -> `@BeforeAll`, `@AfterClass` -> `@AfterAll`
  - `@Ignore` -> `@Disabled`
  - `org.junit.Assert` -> `org.junit.jupiter.api.Assertions`
  - `org.junit.Assume` -> `org.junit.jupiter.api.Assumptions`
  - `@Rule RequiresRom` / `RequiresRomRule` -> the `@RequiresRom` annotation on the method
    or class.
  - Remove `@RunWith` and any runner; use a Jupiter `@ExtendWith` extension (e.g.
    `SingletonResetExtension`) instead.

---

## TestArchitecturalReviewGuard

- **File:** `src/test/java/com/openggf/tests/TestArchitecturalReviewGuard.java`
- **Enforces:** Source-text build/test invariants not covered by ArchUnit bytecode rules:
  `pom.xml` has no `<groupId>junit</groupId>` (no JUnit 4 on the test classpath) and no
  `junit-vintage-engine`; `archunit.properties` sets
  `freeze.store.default.allowStoreUpdate=false` (frozen baselines do not auto-update); and
  the trace-replay quarantine Surefire exclusion `**/tests/trace/**/*.java` is present
  without game-specific hand-written exclusions.
- **Failure symptom:** Assertion on `pom.xml` or `archunit.properties` content.
- **Correct fix:** Remove the JUnit 4 dependency and the vintage engine from `pom.xml`; set
  the ArchUnit freeze-update flag to `false`; keep the generic trace quarantine pattern in
  the Surefire config. Do not flip `allowStoreUpdate` to `true` to silence an ArchUnit
  freeze failure — fix the violating code.

---

## TestArchitecturalSourceGuard

- **File:** `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java`
- **Enforces:** Source-level architecture rules: no game-id branches (`if`/`switch` on
  `getGameId`) in object code except a small set of approved files; no disassembly path
  literals (`docs/s1disasm/...`, etc.) in source; object-art field names must be
  game/zone-neutral (no zone-specific names); and player-coordinate hazard checks must use
  center coordinates, not top-left `getX/getY`.
- **Failure symptom:** Assertion naming the offending file and rule (game-id branch,
  disassembly literal, zone-specific art field, or top-left coordinate usage).
- **Correct fix:**
  - Replace game-name branching with the smallest accurate owner from
    `docs/architecture/per-game-rule-placement.md` — never `if (gameId == S1)`-style
    logic (`CLAUDE.md` physics rules).
  - Remove `docs/...disasm` path literals from source; disassembly is research-only.
  - Rename zone-specific art fields to be game-neutral.
  - Use `getCentreX()`/`getCentreY()` (or `NativePositionOps`) for ROM-position/collision
    code; top-left `getX/getY` is rendering-only (`CLAUDE.md` coordinate rules).

---

## TestBuildToolingGuard

- **File:** `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`
- **Enforces:** Correct Surefire + Mockito wiring in `pom.xml`: defines `mockito.version`,
  `mockito.agent.argLine`, `mockito.agent.path`, `test.cds.argLine`, `surefire.argLine`;
  `mockito.agent.argLine` contains `-javaagent:${mockito.agent.path}`; `mockito.agent.path`
  resolves `mockito-core-${mockito.version}.jar` (quoted for repo paths with spaces);
  `test.cds.argLine` is `-Xshare:off`; `surefire.argLine` threads both the CDS toggle and the
  Mockito agent; and the Surefire plugin uses `${surefire.argLine}`.
- **Failure symptom:** Missing property, mis-routed variable, CDS not disabled, or Mockito
  agent not preloaded as a javaagent.
- **Correct fix:** Define the required properties and wire the Surefire `<argLine>` to
  `${surefire.argLine}` exactly as the guard expects. This is a `pom.xml` edit, not a test
  edit.

---

## TestRuntimeSingletonGuard

- **File:** `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`
- **Enforces:** No null checks against the strict `GameServices` accessors (`camera`, `level`,
  `gameState`, `timers`, `rng`, `fade`, `sprites`, `collision`, `terrainCollision`,
  `parallax`, `water`, `bonusStage`, ...). Strict accessors are guaranteed non-null at valid
  call sites; null-testing them is a code smell. Also catches the local-variable form
  (`Camera cam = GameServices.camera(); if (cam == null)`).
- **Failure symptom:** "Found N null check(s) against strict GameServices accessors. Use
  GameServices.<name>OrNull() or remove the null check."
- **Correct fix:** If you genuinely need a nullable result, call the `...OrNull()` variant
  (e.g. `GameServices.levelOrNull()`) and null-check that. Otherwise remove the null check —
  the strict accessor is valid at that site.

---

## TestSingletonLifecycleGuard

- **File:** `src/test/java/com/openggf/tests/TestSingletonLifecycleGuard.java`
- **Enforces:** Test setup methods that bootstrap ambient gameplay mode (GameServices,
  managers, level, sprites) appear only in a documented baseline
  (`AMBIENT_GAMEPLAY_MODE_SETUP_BASELINE`), preventing accidental test pollution. Scans
  `setUp`/`setUpTest`/`setUpClass`/`setUpRuntime`/`resetCamera` for gameplay-mode bootstrap.
- **Failure symptom:** A new setup method initializes gameplay mode but is not in the baseline.
- **Correct fix (preferred):** Use the standard isolation fixtures
  (`@ExtendWith(SingletonResetExtension.class)` / `@FullReset`, `HeadlessTestRunner`,
  `StubObjectServices`) so your test does not hand-bootstrap ambient gameplay mode. Only add
  your class+method to `AMBIENT_GAMEPLAY_MODE_SETUP_BASELINE` if full gameplay-mode bootstrap
  is genuinely required, and document why — this is a baseline expansion (see warning above).

---

## Other guard tests (inventory)

These follow the same principle — fix the source to satisfy the invariant; do not relax the
guard. Read the test's javadoc/messages for the specific allowlist and remedy.

| Guard test | File | Enforces / correct-fix direction |
|---|---|---|
| TestGameModuleRegistryUsageGuard | `src/test/java/com/openggf/game/TestGameModuleRegistryUsageGuard.java` | `GameModuleRegistry` accessed only through registered APIs; route through the registered accessor, not ad-hoc access. |
| TestProductionAwtBlacklistGuard | `src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java` | No AWT/Swing in production paths; move AWT usage to tools/tests. |
| TestProductionSingletonClosureGuard | `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java` | Singletons not leaked through public APIs; pass dependencies explicitly. |
| TestZoneEventRuntimeAccessGuard | `src/test/java/com/openggf/game/TestZoneEventRuntimeAccessGuard.java` | Zone event runtime access goes through the owning boundary; expose ROM state there, not via ad-hoc singleton reads. |
| TestAudioBackendBypassGuard | `src/test/java/com/openggf/audio/TestAudioBackendBypassGuard.java` | Audio backend not bypassed in production; route through the backend, not the chip cores directly. |
| TestRewindArchitectureGuard | `src/test/java/com/openggf/game/rewind/TestRewindArchitectureGuard.java` | Rewind state-capture invariants; prefer central eligibility/codecs/policy over per-object overrides. |
| TestRewindTransientGuard | `src/test/java/com/openggf/game/rewind/TestRewindTransientGuard.java` | Transient fields excluded from rewind; annotate with `@RewindTransient`/`@RewindDeferred` as appropriate. |
| TestPlayableRuntimeAccessGuard | `src/test/java/com/openggf/sprites/playable/TestPlayableRuntimeAccessGuard.java` | Playable-sprite runtime access patterns; use the sanctioned accessors. |
| TestObjectPhysicsStandardizationGuard | `src/test/java/com/openggf/level/objects/TestObjectPhysicsStandardizationGuard.java` | Object physics standardization; prefer `ObjectControlState`, `NativePositionOps`, `ObjectLifetimeOps`, behavior profiles over raw setters / `setDestroyed(true)`. |

S3K zone-specific state/mutation guards (same principle — model ROM state, route writes
through the sanctioned bridge): `S3kAizTreeRuntimeStateGuard`, `S3kAizWriteBridgeGuard`,
`S3kHczPaletteOwnershipMigrationGuard`, `S3kRuntimeStateReadGuard`, `S3kTransitionBridgeGuard`
(under `src/test/java/com/openggf/game/sonic3k/...`).

---

## Cross-cutting rules these guards protect

- ROM-only runtime assets — never read asset bytes from `docs/` disassembly.
- S3K: prefer S&K-side (`sonic3k.asm`, `< 0x200000`); use an `s3.asm` reference only if no S&K equivalent exists (rare; verify).
- No carve-outs — model ROM state (object id/routine/status bits/event flags/profile), never
  branch on zone id/name, route, or frame number.
- No game-name `if/else` for physics divergences — use the smallest accurate owner from
  `docs/architecture/per-game-rule-placement.md`.
- Trace data is comparison-only — never hydrate/sync engine state from a trace.
- Object code uses injected `ObjectServices` via `services()`; never `getInstance()`; never
  `services()` in constructors.
- Player ROM positions use center coordinates (`getCentreX/Y` / `NativePositionOps`).
- Tests are JUnit 5 / Jupiter only.
