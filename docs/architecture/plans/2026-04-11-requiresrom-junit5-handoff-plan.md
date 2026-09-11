
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.


> **Owner:** handoff to another agent
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** fix the `RequiresRomCondition` correctness bug from review item 2, then complete the branch-wide migration from JUnit 4 fixtures to JUnit 5 so ROM-backed tests use one canonical Jupiter path instead of custom setup or `RequiresRomRule`.

**Architecture:** ROM selection must happen before the gameplay runtime and world session are created. JUnit 4 and JUnit 5 fixture paths should share the same setup logic until the old rule can be deleted. The end state is Jupiter-based test infrastructure with `@RequiresRom` as the standard ROM-backed entry point and minimal bespoke setup in individual tests.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, legacy JUnit 4 compatibility during migration, OpenGGF test reset/runtime infrastructure.

---

## Current Audit

Worktree: `C:\Users\farre\IdeaProjects\sonic-engine\.worktrees\next-singleton-di`

Branch: `next`

Current known correctness bug:

- `RequiresRomRule` performs reset, ROM detection, runtime/session rebuild, then installs the ROM.
- `RequiresRomCondition` currently performs only `TestEnvironment.resetAll()`, ROM detection, and `RomManager.setRom(...)`.
- Because `resetAll()` already creates gameplay, Jupiter ROM tests can bind to the wrong game module/session before the requested ROM is installed.

Current migration scope snapshots:

```text
105 test files still reference @Rule / @ClassRule / RequiresRomRule
327 test files still reference JUnit 4 imports or runners
```

Representative bespoke ROM-setup users from review:

- `src/test/java/com/openggf/tests/Sonic2ObjectPlacementTest.java`
- `src/test/java/com/openggf/tests/NemesisReaderTest.java`
- `src/test/java/com/openggf/tests/TestTitleScreenAudioRegression.java`
- `src/test/java/com/openggf/game/TestInstaShieldVisual.java`
- `src/test/java/com/openggf/game/sonic3k/specialstage/TestS3kSpecialStageResultsArt.java`
- `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- `src/test/java/com/openggf/tests/trace/AbstractCreditsDemoTraceReplayTest.java`

Focused verification bundle for this plan:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.tests.rules.TestRequiresRomConditionParity,com.openggf.tests.rules.TestRequiresRom,com.openggf.tests.TestTitleScreenAudioRegression,com.openggf.game.TestInstaShieldVisual,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsArt" test
```

---

## Task 1: Make JUnit 4 and JUnit 5 Share One Correct ROM Setup Path

**Files:**
- Modify: `src/test/java/com/openggf/tests/rules/RequiresRomCondition.java`
- Modify: `src/test/java/com/openggf/tests/rules/RequiresRomRule.java`
- Modify: `src/test/java/com/openggf/tests/TestEnvironment.java`
- Create: `src/test/java/com/openggf/tests/rules/TestRequiresRomConditionParity.java`

- [ ] **Step 1: extract a shared ROM fixture helper**

Introduce a small helper used by both the JUnit 4 rule and the Jupiter condition/extension. It should own the exact sequence required for correctness:

1. full environment reset
2. detect and set the game module from the target ROM
3. destroy any gameplay runtime created during reset
4. clear session state if required
5. recreate gameplay/runtime with the selected module
6. install the ROM into `RomManager`

Avoid keeping two divergent copies of this sequence.

- [ ] **Step 2: add a parity test before changing behavior**

Create a targeted test proving the Jupiter path and the JUnit 4 path produce equivalent module/runtime state for at least one non-default ROM case, ideally Sonic 3K.

Expected failure mode before the fix: Jupiter path binds a mismatched module/session after reset.

- [ ] **Step 3: wire both rule and condition through the shared helper**

If the current `RequiresRomCondition` is really doing setup work rather than pure enable/disable logic, it may need to become or delegate to a proper Jupiter extension. Favor correctness and maintainability over preserving the current class split.

- [ ] **Step 4: run the parity-focused verification**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.tests.rules.TestRequiresRomConditionParity,com.openggf.tests.rules.TestRequiresRom" test
```

Expected: PASS.

---

## Task 2: Convert the Highest-Risk Bespoke ROM Tests First

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestTitleScreenAudioRegression.java`
- Modify: `src/test/java/com/openggf/game/TestInstaShieldVisual.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/specialstage/TestS3kSpecialStageResultsArt.java`
- Modify: `src/test/java/com/openggf/tests/NemesisReaderTest.java`
- Modify: `src/test/java/com/openggf/tests/Sonic2ObjectPlacementTest.java`

- [ ] **Step 1: replace ad hoc ROM probing and singleton setup with the canonical Jupiter fixture**

Use `@RequiresRom(...)` and Jupiter lifecycle annotations. Tests should stop calling:

- `RuntimeManager.createGameplay()` directly as setup boilerplate
- `RomManager.getInstance().setRom(...)` directly
- manual `GameModuleRegistry.detectAndSetModule(...)` sequences

unless a test is explicitly validating that infrastructure.

- [ ] **Step 2: keep assertions and test intent unchanged**

This phase is fixture migration, not behavior rewrite. Preserve assertions and only change setup/lifecycle code unless a test was relying on the broken fixture order.

- [ ] **Step 3: run the targeted migrated tests**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.tests.TestTitleScreenAudioRegression,com.openggf.game.TestInstaShieldVisual,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsArt,com.openggf.tests.NemesisReaderTest,com.openggf.tests.Sonic2ObjectPlacementTest" test
```

Expected: PASS.

---

## Task 3: Migrate the `RequiresRomRule` Users by Cluster

**Files:**
- Modify: ROM-backed JUnit 4 tests that still use `@Rule`, `@ClassRule`, or `RequiresRomRule`
- Delete later: `src/test/java/com/openggf/tests/rules/RequiresRomRule.java` once unused

- [ ] **Step 1: convert by stable clusters instead of one-file churn**

Recommended order:

1. `com/openggf/game/sonic1/` ROM-backed art and palette tests
2. `com/openggf/game/sonic2/` ROM-backed loader and special-stage tests
3. `com/openggf/game/sonic3k/` palette, art, and diagnostics tests
4. `com/openggf/tests/` headless and placement ROM tests
5. `com/openggf/tests/trace/` abstract replay bases and subclasses

- [ ] **Step 2: solve the abstract trace replay base classes cleanly**

`AbstractTraceReplayTest` and `AbstractCreditsDemoTraceReplayTest` currently stay on JUnit 4 because of the ROM rule. Convert them to Jupiter without duplicating setup across subclasses. Prefer extension-friendly design over scattering static setup methods through each subclass.

- [ ] **Step 3: remove `RequiresRomRule` from each cluster only after the cluster passes**

Run focused Maven targets after each cluster. Do not batch-convert all 105 files without intermediate verification.

---

## Task 4: Finish the Broader JUnit 4 to JUnit 5 Migration

**Files:**
- Modify: remaining JUnit 4 tests across `src/test/java`

- [ ] **Step 1: convert lifecycle annotations and assertions mechanically where safe**

Typical mappings:

- `org.junit.Test` -> `org.junit.jupiter.api.Test`
- `@Before` -> `@BeforeEach`
- `@After` -> `@AfterEach`
- `@BeforeClass` -> `@BeforeAll`
- `@AfterClass` -> `@AfterAll`
- `org.junit.Assert.*` -> `org.junit.jupiter.api.Assertions.*`
- `Assume.*` -> `Assumptions.*`

- [ ] **Step 2: handle per-class lifecycle intentionally**

Where tests currently depend on static `@BeforeClass`, decide explicitly between:

- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` with non-static `@BeforeAll`
- keeping static `@BeforeAll`

Do not preserve JUnit 4 patterns by rote if Jupiter offers a cleaner fixture.

- [ ] **Step 3: eliminate runner/rule-era leftovers**

Remove obsolete imports, comments that explain staying on JUnit 4, and compatibility scaffolding once no longer needed.

---

## Task 5: Add Migration Guards and Remove the Old Rule

**Files:**
- Create: `src/test/java/com/openggf/tests/rules/TestJunit5MigrationGuard.java`
- Delete when ready: `src/test/java/com/openggf/tests/rules/RequiresRomRule.java`

- [ ] **Step 1: add a source guard for new regressions**

Create a guard test that fails on:

- new `RequiresRomRule` usage
- new `@Rule` / `@ClassRule` usage
- new JUnit 4 imports in migrated test packages

Keep the allowlist explicit if any JUnit 4 holdouts remain temporarily.

- [ ] **Step 2: delete `RequiresRomRule` only when there are zero users**

Verify with:

```powershell
rg -n "RequiresRomRule|@Rule|@ClassRule" src/test/java
```

Expected: no output before deleting the rule class.

- [ ] **Step 3: run the migration guard**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.tests.rules.TestJunit5MigrationGuard" test
```

Expected: PASS.

---

## Task 6: Final Verification

- [ ] **Step 1: rerun the focused correctness bundle**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.tests.rules.TestRequiresRomConditionParity,com.openggf.tests.rules.TestRequiresRom,com.openggf.tests.TestTitleScreenAudioRegression,com.openggf.game.TestInstaShieldVisual,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsArt" test
```

Expected: PASS.

- [ ] **Step 2: run the source scans**

```powershell
rg -n "RequiresRomRule|@Rule|@ClassRule" src/test/java
rg -n "import org\\.junit\\.(Test|Before|After|BeforeClass|AfterClass|Rule|ClassRule|Ignore|Assert)|@RunWith|org\\.junit\\.runner" src/test/java
```

Expected: no output, or only tightly documented temporary holdouts if the branch cannot finish all 327 JUnit 4 users in one pass.

- [ ] **Step 3: run the full suite**

```powershell
mvn -q -Dmse=off test
```

Expected: no new ROM-fixture or JUnit-migration regressions.

---

## Notes for Handoff

- Fix the `RequiresRomCondition` correctness issue first. Bulk JUnit 5 churn before that just spreads the broken fixture across more tests.
- Treat the 105 `RequiresRomRule` users and 327 JUnit 4 users as separate scopes. The first is infrastructure-coupled; the second is broader mechanical migration.
- Do not leave both a JUnit 4 rule and a Jupiter extension with duplicated ROM-setup logic. One shared helper or one shared extension path is the right end state.
