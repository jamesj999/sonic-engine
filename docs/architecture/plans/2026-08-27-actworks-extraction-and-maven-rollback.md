# Actworks Extraction and Direct-Maven Rollback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the reusable lifecycle tooling in public `OpenGGF/Actworks`, remove it and its enforcement from OpenGGF, restore direct Maven with one `target/` tree per worktree, disable the exact-owned host integration, and reclaim proved-safe generated storage.

**Architecture:** A path-filtered Git export from reviewed commit `27b9840a8137e7cbf30b2fb4ee5be8eedf263e6c` creates Actworks before any OpenGGF deletion. Actworks owns Slipmat, worktree lifecycle, and an explicitly incubating copy of the OpenGGF Maven coordinator; cowtree stays external. OpenGGF then removes the protocol and uses standard Maven-derived output paths, followed by baseline comparison, host cutover, and conservative cleanup.

**Tech Stack:** Git fast-export/fast-import, Python 3 standard library, Java 21, Bash, PowerShell, Maven 3.9, GitHub CLI, GitHub Actions YAML, systemd user units.

**Spec:** `docs/architecture/designs/2026-08-27-actworks-extraction-and-maven-rollback.md`

## Global Constraints

- The Actworks public remote is a hard gate: do not delete OpenGGF source before the remote is pushed and verified.
- Use GPL-3.0 for Actworks because the extracted OpenGGF source is GPL-3.0.
- Keep cowtree in its existing repository; link it, never vendor it.
- Use source commit `27b9840a8137e7cbf30b2fb4ee5be8eedf263e6c` for reviewed extracted implementations.
- OpenGGF canonical commands are raw `mvn package`, `mvn test`, focused `-Dtest=...`, and `mvn -Dmse=off -Pguards test -B` on JDK 21.
- OpenGGF ordinary output must remain under `${project.build.directory}` (`target/` by default).
- Preserve per-Surefire-fork LWJGL extraction under `target/test-tmp`.
- Never rewrite factual historical changelog or trace-frontier evidence merely because it names the old wrapper.
- Keep `AGENTS.md` and `CLAUDE.md` identical and every `.agents/skills` / `.claude/skills` pair identical.
- Never delete dirty, unmerged, active, leased, locked, unreadable, unknown, capture-bearing, or promoted-artifact paths.
- Never mutate unknown host configuration; exact ownership/content proofs precede timer, helper, or client-config removal.
- Use `apply_patch` for file edits; mechanical `git mv` and formatting commands are allowed.
- Never bypass hooks with `--no-verify`.

Before running commands, set `OPENGGF_ROOT` to the main OpenGGF checkout,
`MIGRATION_WORKTREE` to this implementation worktree, `ACTWORKS_STAGE` to a
new task-owned staging directory, and `PROJECTS_ROOT` to the parent directory
that will hold the durable Actworks clone. Use the configured
`AGENT_SCRATCH_ROOT`; do not encode a user-home path in repository files.

---

### Task 1: Record the pre-rollback integration baseline

**Files:**
- Create: `docs/architecture/validation/2026-08-27-actworks-maven-rollback-baseline.md`
- Create: `docs/architecture/validation/2026-08-27-actworks-maven-ordinary-red-set.txt`
- Create: `docs/architecture/validation/2026-08-27-actworks-maven-guards-red-set.txt`

**Interfaces:**
- Consumes: clean `develop` at the current remote head; Java 21; the current
  enforced session workflow.
- Produces: the exact raw-Maven rejection plus ordinary and guards red sets for
  Task 9 comparison.

- [ ] **Step 1: Verify the baseline worktree and JVM**

Run from the clean main OpenGGF workspace:

```bash
git fetch origin
git pull --ff-only origin develop
git status --short --branch
mvn -v
```

Expected: main workspace is clean and Maven reports Java 21.

- [ ] **Step 2: Prove and record the current raw-Maven rejection**

```bash
mvn -Dmse=off test -B
```

Expected before rollback: Maven exits during
`openggf-session-validate-guard` with `missing session identity properties`.
Record the exit and bounded error text; this establishes the active behavior
being removed and is not a test-suite baseline.

The main workspace is the read-only execution baseline. Write the comparison
files into the migration worktree with these exact destinations:

```bash
MIGRATION_VALIDATION="$MIGRATION_WORKTREE/docs/architecture/validation"
mkdir -p "$MIGRATION_VALIDATION"
```

- [ ] **Step 3: Run one final ordinary baseline through the old wrapper**

```bash
tools/testing/test-session.sh -- mvn -Dmse=off test -B
pwsh -NoProfile -File tools/testing/Compare-SurefireRedSet.ps1 \
  -ReportsPath "$BASELINE_ARTIFACT_ROOT/surefire-reports" \
  -WriteActualPath "$MIGRATION_VALIDATION/2026-08-27-actworks-maven-ordinary-red-set.txt"
```

Set `BASELINE_ARTIFACT_ROOT` from the wrapper's terminal manifest. Expected:
valid start/end markers, the command's exact exit, and a sorted red set. This
is the final ordinary wrapper allocation; do not rerun it merely for logging.

- [ ] **Step 4: Run one final guards baseline through the old wrapper**

```bash
tools/testing/test-session.sh -- mvn -Dmse=off -Pguards test -B
pwsh -NoProfile -File tools/testing/Compare-SurefireRedSet.ps1 \
  -ReportsPath "$BASELINE_GUARDS_ARTIFACT_ROOT/surefire-reports" \
  -WriteActualPath "$MIGRATION_VALIDATION/2026-08-27-actworks-maven-guards-red-set.txt"
```

Set `BASELINE_GUARDS_ARTIFACT_ROOT` from that run's terminal manifest.
Expected: guard reports and exact red set even if the known baseline is red.

- [ ] **Step 5: Write the baseline validation record**

Create the record directly in the migration worktree. Copy the observed commit,
Java line, test totals, exit statuses, and absolute output root into these
fields; do not stage the record until every field contains observed evidence:

```markdown
# Actworks Maven rollback baseline

- Commit: the exact `develop` commit printed by `git rev-parse HEAD`
- JVM: the exact Java line printed by `mvn -v`
- Raw pre-rollback command: `mvn -Dmse=off test -B`
- Raw pre-rollback result: the observed session-guard rejection and exit status
- Ordinary command: `tools/testing/test-session.sh -- mvn -Dmse=off test -B`
- Ordinary result: the observed tests, failures, errors, skipped, and exit status
- Ordinary red set: `2026-08-27-actworks-maven-ordinary-red-set.txt`
- Guards command: `tools/testing/test-session.sh -- mvn -Dmse=off -Pguards test -B`
- Guards result: the observed tests, failures, errors, skipped, and exit status
- Guards red set: `2026-08-27-actworks-maven-guards-red-set.txt`
- Ordinary run id, manifest, log, and artifact root: the exact marker values
- Guards run id, manifest, log, and artifact root: the exact marker values
```

- [ ] **Step 6: Commit the baseline evidence on the migration branch**

```bash
git add docs/architecture/validation/2026-08-27-actworks-maven-rollback-baseline.md \
  docs/architecture/validation/2026-08-27-actworks-maven-ordinary-red-set.txt \
  docs/architecture/validation/2026-08-27-actworks-maven-guards-red-set.txt
git commit -m "test: record direct Maven rollback baseline"
```

Use all required policy trailers; all documentation-map values are `n/a`
unless another mapped file is staged.

---

### Task 2: Export reviewed history into a local Actworks repository

**Files:**
- Create repository: `$ACTWORKS_STAGE/repo`
- Import from OpenGGF paths listed below.

**Interfaces:**
- Consumes: OpenGGF Git object database and reviewed lifecycle commit.
- Produces: a filtered Git repository whose imported branch resolves to the reviewed commit's selected-file state.

- [ ] **Step 1: Create an explicit staging directory**

```bash
mkdir -p "$ACTWORKS_STAGE"
git init --initial-branch=main "$ACTWORKS_STAGE/repo"
```

Expected: only the exact staging path is created; do not use `/tmp`.

- [ ] **Step 2: Export selected history with stock Git**

Run from OpenGGF:

```bash
git fast-export --use-done-feature bugfix/ai-session-lifecycle-safety -- \
  LICENSE \
  tools/agent-scratch tools/test_agent_scratch.py \
  tools/worktree-lifecycle tools/test_worktree_lifecycle.py \
  tools/testing/TestSessionCoordinator.java \
  tools/testing/TestSessionCoordinatorSelfTest.java \
  tools/testing/TestSessionGuardSelfTest.java \
  tools/testing/TestSessionProcessHarness.java \
  tools/testing/test-session.sh tools/testing/test-session.ps1 \
  tools/testing/run-session-process-harness.sh \
  tools/testing/run-session-process-harness.ps1 \
  tools/testing/fixtures/session-guard/pom.xml \
  tools/testing/README.md \
  docs/architecture/designs/2026-08-14-agent-scratch-storage-design.md \
  docs/architecture/designs/2026-08-23-test-session-isolation-design.md \
  docs/architecture/designs/2026-08-26-session-storage-and-worktree-lifecycle-safety.md \
  docs/architecture/plans/2026-08-14-agent-scratch-storage-plan.md \
  docs/architecture/plans/2026-08-23-test-session-isolation.md \
  docs/architecture/plans/2026-08-26-session-storage-safety.md \
  docs/architecture/plans/2026-08-26-worktree-lifecycle-safety.md \
  docs/architecture/validation/2026-08-23-test-session-isolation.md \
| git -C "$ACTWORKS_STAGE/repo" fast-import
```

Expected: fast-import succeeds without exporting engine source, ROMs, trace
fixtures, disassemblies, or gameplay assets.

- [ ] **Step 3: Point `main` at the imported reviewed branch**

```bash
git -C "$ACTWORKS_STAGE/repo" \
  branch -f main refs/heads/bugfix/ai-session-lifecycle-safety
git -C "$ACTWORKS_STAGE/repo" checkout main
git -C "$ACTWORKS_STAGE/repo" \
  branch -D bugfix/ai-session-lifecycle-safety
```

Expected: `main` is checked out and the imported implementation matches the
selected paths at OpenGGF commit `27b9840a8...`.

- [ ] **Step 4: Verify the filtered boundary**

```bash
git -C "$ACTWORKS_STAGE/repo" ls-files \
  | rg '^(src/main|src/test/resources/traces|docs/[s1k2].*disasm|.*\.gen$)' && exit 1 || true
git -C "$ACTWORKS_STAGE/repo" fsck --full
```

Expected: no forbidden path is printed and `git fsck` succeeds.

---

### Task 3: Restructure and validate Actworks components

**Files:**
- Create: `README.md`, `CONTRIBUTING.md`, `.gitignore`
- Create: `docs/provenance/openggf-extraction.md`
- Create: `docs/architecture/README.md`
- Move: `tools/agent-scratch` -> `slipmat/bin/agent-scratch`
- Move: `tools/test_agent_scratch.py` -> `slipmat/tests/test_agent_scratch.py`
- Move: `tools/worktree-lifecycle` -> `lifecycle/bin/worktree-lifecycle`
- Move: `tools/test_worktree_lifecycle.py` -> `lifecycle/tests/test_worktree_lifecycle.py`
- Move coordinator sources/scripts/fixtures into `incubator/openggf-maven-session/`
- Move selected design/plan/validation records into component documentation.
- Modify moved Python tests and coordinator launch scripts for new paths.

**Interfaces:**
- Consumes: filtered repository from Task 2.
- Produces: tested Actworks layout and executable component entry points.

- [ ] **Step 1: Move files mechanically**

Use `mkdir -p` followed by `git mv` to produce exactly:

```text
slipmat/bin/agent-scratch
slipmat/tests/test_agent_scratch.py
lifecycle/bin/worktree-lifecycle
lifecycle/tests/test_worktree_lifecycle.py
incubator/openggf-maven-session/src/*.java
incubator/openggf-maven-session/bin/test-session.sh
incubator/openggf-maven-session/bin/test-session.ps1
incubator/openggf-maven-session/bin/run-session-process-harness.sh
incubator/openggf-maven-session/bin/run-session-process-harness.ps1
incubator/openggf-maven-session/tests/fixtures/session-guard/pom.xml
```

Move the three Java test/harness classes to the incubator `src/` directory
beside `TestSessionCoordinator.java`; they are standalone sources, not a Maven
module.

- [ ] **Step 2: Write failing relocated-path tests**

Change the Python test constants to reference the not-yet-created relative
entry points:

```python
HELPER = pathlib.Path(__file__).resolve().parents[1] / "bin" / "agent-scratch"
```

and:

```python
HELPER = pathlib.Path(__file__).resolve().parents[1] / "bin" / "worktree-lifecycle"
```

Run:

```bash
python3 slipmat/tests/test_agent_scratch.py
python3 lifecycle/tests/test_worktree_lifecycle.py
```

Expected: fail until imports/cache locations and repository-root assumptions
are corrected for the new layout.

- [ ] **Step 3: Update component-local path discovery**

Make Slipmat source installation/templates resolve from its own component root,
not an OpenGGF checkout. Make lifecycle tests locate `lifecycle/bin`. Update
incubator shell and PowerShell launchers so Java sources resolve from their
own `src/` directory and fixtures from `tests/fixtures`.

The extracted code must not contain an operational dependency on an OpenGGF
checkout. Literal `OpenGGF` is permitted only in incubator naming, provenance,
and historical documents.

- [ ] **Step 4: Add repository documentation**

`README.md` must state:

```markdown
# Actworks

Actworks is an experimental OpenGGF organisation toolset for agent worktree
and session lifecycle research. Slipmat and lifecycle are preserved for future
development; the OpenGGF Maven coordinator is incubating source, not a
recommended production workflow. cowtree remains in its own repository and is
not vendored here.
```

Add GPL-3.0, component commands, support status, and the actual cowtree
repository URL. Discover it first with elevated `gh repo list OpenGGF` and do
not write the README until the repository identity is verified.

`docs/provenance/openggf-extraction.md` records source repository, branch,
commit `27b9840a8...`, original/new path map, extraction method, and exclusions.

- [ ] **Step 5: Run all Actworks validation**

```bash
python3 -m py_compile slipmat/bin/agent-scratch slipmat/tests/test_agent_scratch.py \
  lifecycle/bin/worktree-lifecycle lifecycle/tests/test_worktree_lifecycle.py
python3 slipmat/tests/test_agent_scratch.py
python3 lifecycle/tests/test_worktree_lifecycle.py
bash incubator/openggf-maven-session/bin/run-session-process-harness.sh
```

Also compile/run the coordinator self-test using the command documented in the
relocated incubator README. Expected: every suite passes and no test writes to
`/tmp` or an OpenGGF checkout.

- [ ] **Step 6: Commit the Actworks restructuring**

```bash
git add -A
git diff --cached --check
git commit -m "feat: establish Actworks lifecycle toolset"
```

Expected: commit includes the moves, path fixes, repository docs, provenance,
and no generated cache/build output.

---

### Task 4: Create and verify public `OpenGGF/Actworks`

**Files:**
- External remote: `https://github.com/OpenGGF/Actworks`
- Local durable clone: `$PROJECTS_ROOT/Actworks`

**Interfaces:**
- Consumes: clean tested Actworks `main` from Task 3 and elevated GitHub auth.
- Produces: public remote URL and verified remote head; unlocks OpenGGF deletion.

- [ ] **Step 1: Reverify elevated authentication and absence of the repository**

```bash
gh auth status
gh api repos/OpenGGF/Actworks
```

Expected before creation: auth succeeds; repository lookup returns 404. If it
exists, stop and inspect ownership/content rather than overwriting it.

- [ ] **Step 2: Create and push the public repository**

```bash
gh repo create OpenGGF/Actworks --public \
  --source "$ACTWORKS_STAGE/repo" \
  --remote origin --push
```

Expected: the repository is public and `main` is pushed.

- [ ] **Step 3: Verify remote visibility and exact head**

```bash
gh repo view OpenGGF/Actworks --json nameWithOwner,isPrivate,url,defaultBranchRef
git -C "$ACTWORKS_STAGE/repo" fetch origin
git -C "$ACTWORKS_STAGE/repo" \
  rev-parse main origin/main
```

Expected: `isPrivate=false`, default branch is `main`, and local/remote hashes
match.

- [ ] **Step 4: Create the durable local clone**

```bash
git clone https://github.com/OpenGGF/Actworks.git "$PROJECTS_ROOT/Actworks"
git -C "$PROJECTS_ROOT/Actworks" status --short --branch
```

Expected: clean clone tracking `origin/main`.

---

### Task 5: Define the OpenGGF direct-Maven contract with failing guards

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: baseline files from Task 1 and durable Actworks remote from Task 4.
- Produces: guard expectations that reject wrapper/session coupling and require target-local output.

- [ ] **Step 1: Replace session-owned output assertions with target-owned assertions**

In `TestBuildToolingGuard`, replace
`generatedOutputInventoryMustRemainSessionOwned` with
`generatedOutputInventoryMustRemainBelowMavenBuildDirectory`. Assert that the
POM omits `openggf.build.directory`, uses Maven's default build directory (or
the literal `${project.basedir}/target`), and retains these derived paths:

```java
assertEquals("${project.build.directory}/test-tmp", property(pom, "openggf.test.tmpdir"));
assertEquals("${project.build.directory}/surefire-reports", property(pom, "openggf.surefire.reports"));
assertFalse(pom.contains("<openggf.build.directory>"));
```

Then add negative source assertions that `pom.xml` does not contain a
session-guard execution or a build-directory command-line override contract.

- [ ] **Step 2: Replace documentation/workflow session assertions**

Rename `supportedDocumentationMustUseSessionsAndExplicitHookBootstrap` to
`supportedDocumentationMustUseDirectMavenAndExplicitHookBootstrap`. Require
the four canonical raw commands in AGENTS/CLAUDE and reject
`tools/testing/test-session` plus `agent-scratch` in active guidance.

Replace `ciAndReleaseMavenJobsMustUseCoordinatorManifests` with
`ciAndReleaseMavenJobsMustUseDirectMavenAndTargetPaths`. Require raw Maven
commands and reject `test-session.sh`, `manifest`, and wrapper output keys in
`.github/workflows/ci.yml` and `release.yml`.

- [ ] **Step 3: Run the focused guard and prove RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tests.TestBuildToolingGuard test -B
```

Expected: failures name existing wrapper commands, session documentation,
coordinator workflow exports, or externally overridable output roots.

- [ ] **Step 4: Commit only the RED guard contract**

```bash
git add src/test/java/com/openggf/tests/TestBuildToolingGuard.java
git commit -m "test: require direct Maven workflow"
```

Use the required trailers; do not stage production/docs changes in this commit.

---

### Task 6: Remove OpenGGF lifecycle implementations and simplify Maven output

**Files:**
- Delete: `tools/agent-scratch`, `tools/test_agent_scratch.py`
- Delete: `tools/worktree-lifecycle`, `tools/test_worktree_lifecycle.py` if present on the rollback branch
- Delete: coordinator Java/self-test/process-harness/session-guard sources and fixtures
- Delete: `tools/testing/test-session.sh`, `tools/testing/test-session.ps1`
- Delete: `tools/testing/run-session-process-harness.sh`, `.ps1`
- Modify: `pom.xml`
- Modify: `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`

**Interfaces:**
- Consumes: RED contract from Task 5.
- Produces: direct Maven with all generated paths below `target/` and no extracted implementation.

- [ ] **Step 1: Remove coordinator/session build executions from the POM**

Delete Maven executions/profiles whose only purpose is session guarding,
coordinator compilation, or shared-output rejection. Preserve all gameplay,
trace, structural-guard, packaging, Mockito, and JDK enforcement.

- [ ] **Step 2: Collapse output properties onto the Maven build directory**

Use:

```xml
<openggf.test.tmpdir>${project.build.directory}/test-tmp</openggf.test.tmpdir>
<openggf.surefire.reports>${project.build.directory}/surefire-reports</openggf.surefire.reports>
<openggf.trace.reports>${project.build.directory}/trace-reports</openggf.trace.reports>
<openggf.test.diagnostics>${project.build.directory}/diagnostics</openggf.test.diagnostics>
<openggf.artifact.root>${project.build.directory}</openggf.artifact.root>
<openggf.distribution.root>${project.build.directory}</openggf.distribution.root>
```

Remove `openggf.build.directory` and every `-Dopenggf.build.directory` session
override path. Set `<build><directory>${project.basedir}/target</directory>` or
omit it to use Maven's exact default. Retain:

```xml
-Djava.io.tmpdir="${openggf.test.tmpdir}"
-Dorg.lwjgl.system.SharedLibraryExtractPath="${openggf.test.tmpdir}/lwjgl-${surefire.forkNumber}"
```

- [ ] **Step 3: Delete extracted files from OpenGGF**

Use `git rm` for the exact files listed in this task. Before deletion, compare
each implementation digest against the corresponding Actworks remote path and
write the digest mapping into the migration validation record. Do not delete a
file absent from Actworks unless the provenance record marks it intentionally
OpenGGF-only.

- [ ] **Step 4: Make focused guards GREEN**

Update remaining `TestBuildToolingGuard` helpers/constants so they inventory
only active OpenGGF build inputs and target-local output. Remove tests that
compile/run the deleted coordinator or inspect deleted wrapper scripts.

Run:

```bash
mvn -Dmse=off -Dtest=com.openggf.tests.TestBuildToolingGuard test -B
```

Expected: all focused guard tests pass.

- [ ] **Step 5: Commit implementation removal and POM rollback**

```bash
git add pom.xml src/test/java/com/openggf/tests/TestBuildToolingGuard.java
git add -u tools
git diff --cached --check
git commit -m "build: restore direct Maven test workflow"
```

Use `Changelog: n/a: release note follows in documentation commit` if the
policy requires a justification for the build fix.

---

### Task 7: Convert CI and release workflows to static target paths

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify/Test: `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`

**Interfaces:**
- Consumes: direct-Maven POM from Task 6.
- Produces: CI/release commands and artifact consumers independent of wrapper exports.

- [ ] **Step 1: Write/extend failing workflow assertions**

Require these literal command families:

```yaml
run: mvn -Dmse=off -Pguards test -B
run: mvn -Dmse=off test -B
run: mvn -Dmse=off package -Pnative -DskipTests -B
run: mvn -Dmse=off package -Puniversal-jar -DskipTests -B
```

Require report consumers to use `${{ github.workspace }}/target/...` or
`target/...`; reject `steps.<session>.outputs`, manifest paths, and
`tools/testing/test-session.sh`.

- [ ] **Step 2: Run focused test and prove RED**

```bash
mvn -Dmse=off -Dtest=com.openggf.tests.TestBuildToolingGuard test -B
```

Expected: workflow-specific failures.

- [ ] **Step 3: Replace every wrapper invocation and dynamic export**

Change each workflow Maven step to raw Maven. Replace report/artifact variables
with static target paths while preserving ROM arguments, profiles, warning
checks, test-count assertions, release smoke tests, and upload behavior.

- [ ] **Step 4: Verify workflow contract GREEN**

```bash
mvn -Dmse=off -Dtest=com.openggf.tests.TestBuildToolingGuard test -B
git diff --check -- .github/workflows/ci.yml .github/workflows/release.yml
```

Expected: focused guard passes and no wrapper/session export remains.

- [ ] **Step 5: Commit workflows**

```bash
git add .github/workflows/ci.yml .github/workflows/release.yml \
  src/test/java/com/openggf/tests/TestBuildToolingGuard.java
git commit -m "ci: run OpenGGF builds directly with Maven"
```

---

### Task 8: Update active OpenGGF guidance and roadmap

**Files:**
- Modify together: `AGENTS.md`, `CLAUDE.md`
- Modify: `README.md`, `ROADMAP.md`, `CHANGELOG.0.6.md`
- Modify: `docs/guide/contributing/dev-setup.md`, `docs/guide/contributing/testing.md`
- Modify/delete active harness sections in `docs/agent-workflow/README.md` and active runbooks
- Modify mirrored trace skills under `.agents/skills/` and `.claude/skills/`
- Move/delete dedicated lifecycle designs/plans/validation after confirming Actworks remote copies
- Create: `docs/architecture/validation/2026-08-27-actworks-extraction-and-maven-rollback.md`

**Interfaces:**
- Consumes: public Actworks URL/commit and direct-Maven workflow.
- Produces: one consistent active OpenGGF workflow and migration record.

- [ ] **Step 1: Replace active commands mechanically**

Replace active examples:

```text
tools/testing/test-session.sh -- mvn ...
tools/testing/test-session.ps1 -- mvn ...
```

with raw `mvn ...`. Remove marker, manifest, lease, gzip, managed-allocation,
and certifying/non-certifying language from active instructions.

- [ ] **Step 2: Rewrite the root test contract**

AGENTS/CLAUDE must say:

```markdown
OpenGGF uses Maven directly. Build and test output belongs below the current
worktree's `target/` directory. Do not redirect Maven build/report roots to a
shared or durable session directory. Parallel agents still use separate
worktrees; repeated runs in one worktree reuse its target tree.
```

Retain JDK 21, ROM properties, Surefire-fork LWJGL isolation, quiet-context
guidance, and the separate `-Pguards` invocation.

- [ ] **Step 3: Record release and roadmap decisions**

Add a concise `CHANGELOG.0.6.md` entry explaining the rollback to direct Maven
after session storage growth. Add a v0.8 `ROADMAP.md` item linking public
`OpenGGF/Actworks`, naming Slipmat/lifecycle and external cowtree, and stating
that OpenGGF has no dependency on it.

- [ ] **Step 4: Migrate dedicated lifecycle records**

After comparing Actworks remote copies, `git rm` the dedicated agent-scratch,
session-isolation, session-storage, and worktree-lifecycle design/plan/
validation records. Retain this migration design/plan/validation as the one
OpenGGF historical pointer to Actworks.

- [ ] **Step 5: Write migration validation record**

Include:

```markdown
- Actworks URL and remote commit
- OpenGGF source commit and rollback branch commit
- extracted-path digest map
- raw baseline commands/results
- branch comparison commands/results
- CI/release static target paths
- host cutover status
- cleanup before/after bytes and retained blockers
```

Add result sections only when evidence is available, and complete every result
section before final integration.

- [ ] **Step 6: Verify mirrors and active-reference boundary**

```bash
cmp AGENTS.md CLAUDE.md
diff -qr .agents/skills .claude/skills
git grep -n -E 'agent-scratch|tools/testing/test-session|TestSessionCoordinator|OPENGGF_TEST_RUN_|worktree-lifecycle' -- \
  AGENTS.md CLAUDE.md README.md ROADMAP.md .github pom.xml tools src/test \
  docs/guide docs/agent-workflow .agents/skills .claude/skills
```

Expected: no active dependency remains. Permitted results are only the v0.8
Actworks link and the explicit historical migration record.

- [ ] **Step 7: Commit documentation and policy updates**

```bash
git add AGENTS.md CLAUDE.md README.md ROADMAP.md CHANGELOG.0.6.md \
  docs/guide docs/agent-workflow docs/architecture .agents/skills .claude/skills
git diff --cached --check
git commit -m "docs: complete Actworks extraction boundary"
```

Use `Guide: updated`, `Agent-Docs: updated`, and `Skills: updated`; use the
required justified Changelog trailer according to the staged root/release
files.

---

### Task 9: Compare raw Maven branch results with baseline

**Files:**
- Modify: `docs/architecture/validation/2026-08-27-actworks-extraction-and-maven-rollback.md`

**Interfaces:**
- Consumes: baseline red sets and completed rollback branch.
- Produces: regression decision authorising or blocking integration.

- [ ] **Step 1: Run ordinary raw Maven**

```bash
mvn -Dmse=off test -B
pwsh -NoProfile -File tools/testing/Compare-SurefireRedSet.ps1 \
  -ReportsPath target/surefire-reports \
  -ExpectedPath docs/architecture/validation/2026-08-27-actworks-maven-ordinary-red-set.txt
```

Expected: exact baseline red-set match. If order-sensitive baseline tests vary,
run each differing identity focused and record evidence; do not silently alter
the expected file.

- [ ] **Step 2: Run fresh-JVM raw guards**

```bash
mvn -Dmse=off -Pguards test -B
pwsh -NoProfile -File tools/testing/Compare-SurefireRedSet.ps1 \
  -ReportsPath target/surefire-reports \
  -ExpectedPath docs/architecture/validation/2026-08-27-actworks-maven-guards-red-set.txt
```

Expected: no new guard red identity. A removed harness guard may reduce the red
set only when the validation record attributes it to deleted functionality.

- [ ] **Step 3: Run focused build-tooling guard**

```bash
mvn -Dmse=off -Dtest=com.openggf.tests.TestBuildToolingGuard test -B
```

Expected: all focused tests pass.

- [ ] **Step 4: Record exact evidence and commit**

Update the migration validation record with commands, JVM, commit, counts,
red-set comparison, first differences, target size, and proof that no new
`.openggf` or managed session was created.

```bash
git add docs/architecture/validation/2026-08-27-actworks-extraction-and-maven-rollback.md
git commit -m "test: validate direct Maven rollback"
```

---

### Task 10: Integrate and push OpenGGF

**Files:**
- Main workspace `develop`
- Cleanup: migration worktree and local branch after success.

**Interfaces:**
- Consumes: verified rollback branch and current origin/develop.
- Produces: pushed OpenGGF develop commit with post-merge regression evidence.

- [ ] **Step 1: Refresh and record updated integration baseline**

```bash
git -C "$OPENGGF_ROOT" fetch origin
git -C "$OPENGGF_ROOT" pull --ff-only origin develop
git -C "$OPENGGF_ROOT" status --short --branch
```

If upstream moved after Task 1, rerun raw ordinary/guard baseline commands on
the updated integration head before merging.

- [ ] **Step 2: Merge into the main workspace without switching it**

Use a fast-forward when possible; otherwise perform the policy-required merge
and README release-summary update. Reconcile conflicts manually and preserve
unrelated upstream changes.

- [ ] **Step 3: Run post-merge raw verification**

```bash
mvn -Dmse=off test -B
mvn -Dmse=off -Pguards test -B
mvn -Dmse=off -Dtest=com.openggf.tests.TestBuildToolingGuard test -B
```

Compare both red sets with the refreshed integration baseline and record the
merged commit/results in the validation document. Amend or add a validation
commit as policy allows; do not push unrecorded regression evidence.

- [ ] **Step 4: Push only `develop`**

```bash
git fetch origin
git rev-list --left-right --count develop...origin/develop
git push origin develop
```

Expected: remote advances to the verified integration commit.

- [ ] **Step 5: Verify and clean completed OpenGGF scaffolding**

Confirm the migration worktree has no uncommitted/unmerged/user-authored
changes. Remove it with `git worktree remove`, delete the fully merged local
branch with `git branch -d`, and run `git worktree prune`.

Do not remove `session-lifecycle-safety` until Actworks remote provenance is
rechecked and its branch-specific source is no longer the only local copy.

---

### Task 11: Disable exact-owned host integration

**Files/State:**
- `$HOME/.local/bin/agent-scratch`
- `$HOME/.config/systemd/user/agent-scratch-prune.{service,timer}`
- `$HOME/.config/agent-scratch/environment`
- `$HOME/.codex/config.toml`
- `$HOME/.claude/settings.json`

**Interfaces:**
- Consumes: pushed Actworks and pushed direct-Maven OpenGGF.
- Produces: new processes no longer activate managed test-session storage.

- [ ] **Step 1: Inventory exact ownership without mutation**

Record file identities, modes, digests, unit state, and the exact managed keys
in Codex/Claude configuration. Compare helper/unit templates with the known
OpenGGF and Actworks extracted versions. Unknown/mismatched state is retained.

- [ ] **Step 2: Write a failing host-cutover dry-run test in Actworks**

Create `slipmat/bin/slipmat-host-cutover` and
`slipmat/tests/test_slipmat_host_cutover.py`. Implement the CLI contract
`slipmat-host-cutover audit --home PATH --json` and
`slipmat-host-cutover apply --home PATH --json`. Against fixture HOME
directories, the audit must report only:

```json
{
  "owned_helper": true,
  "owned_units": true,
  "managed_codex_keys": ["AGENT_SCRATCH_ROOT", "TMPDIR", "TMP", "TEMP"],
  "managed_claude_keys": ["AGENT_SCRATCH_ROOT", "CLAUDE_CODE_TMPDIR", "TMPDIR", "TMP", "TEMP"]
}
```

The implementation exposes `audit_host(home: pathlib.Path) -> dict` and
`apply_host(home: pathlib.Path) -> dict`. It accepts only helper/unit digests
listed in `slipmat/provenance/owned-host-files.json`, removes only the named
environment keys and exact Slipmat list entries, writes configuration
atomically, and refuses changed helper/unit/config fixtures without mutation.
Run the focused test RED before implementation, then GREEN after the
exact-owned cutover implementation.

- [ ] **Step 3: Push the Actworks cutover support before applying it**

Commit and push the tested migration command to `OpenGGF/Actworks`; verify
remote/local hashes match.

- [ ] **Step 4: Apply the cutover**

Use the tested Actworks command to disable/stop the exact owned timer, remove
exact owned generated unit/environment/helper files, and remove only managed
keys/list entries from Codex/Claude config. Do not delete the managed scratch
root. Report every retained mismatch.

- [ ] **Step 5: Verify fresh configuration state**

Verify systemd no longer loads/enables the unit, the installed helper is absent
only if exact-owned, unrelated config keys are byte/semantic-equivalent, and a
fresh non-agent shell/config inspection has no managed scratch injection.

Current live agents may retain inherited variables; report that explicitly.

---

### Task 12: Reclaim proved-safe session and worktree storage

**Files/State:**
- Managed root `$AGENT_SCRATCH_ROOT`
- OpenGGF registered worktrees and project-local `.openggf` output.
- Actworks cleanup audit/validation record.

**Interfaces:**
- Consumes: disabled harness, lifecycle audit tooling preserved in Actworks, no active allocation path.
- Produces: exact reclaimed bytes and retained-blocker inventory.

- [ ] **Step 1: Capture before-state**

```bash
df -B1 /
du -x -s -B1 "$AGENT_SCRATCH_ROOT"
du -x -s -B1 "$OPENGGF_ROOT/.worktrees"
git -C "$OPENGGF_ROOT" worktree list --porcelain
```

Also scan live process current directories and known lease namespaces. Do not
mutate during this step.

- [ ] **Step 2: Dry-run authenticated terminal compaction**

Run the extracted Actworks Slipmat compactor in dry-run mode against only its
fixed supported lanes. Record runs scanned, eligible runs, exact candidates,
candidate bytes, and skips. Apply only if the dry run has no unknown target and
the command revalidates identity immediately before deletion.

- [ ] **Step 3: Inventory project-local `.openggf` output without direct deletion**

Record each registered worktree's `.openggf` bytes and process/lease status.
This delivery does not add a trusted partial-tree deleter, so do not delete an
`.openggf` subtree directly. Reclaim those bytes only when Step 4 proves that
the containing worktree is a clean, merged, inactive retirement candidate.
Retain and report every `.openggf` tree in any worktree that cannot be retired
as a whole.

- [ ] **Step 4: Retire clean merged inactive worktrees**

Use Actworks lifecycle audit first. Apply retirement only to fresh
`CLEAN_MERGED` candidates without blockers, after scanning process current
directories. Retain all other classifications.

- [ ] **Step 5: Capture after-state and publish cleanup evidence**

Record:

```bash
df -B1 /
du -x -s -B1 "$AGENT_SCRATCH_ROOT"
du -x -s -B1 "$OPENGGF_ROOT/.worktrees"
```

Report apparent candidate bytes, actual reclaimed bytes, exact deleted
categories, recoverability, zero-candidate repeat dry runs, and retained
blockers. Commit/push the Actworks cleanup validation record.

- [ ] **Step 6: Remove obsolete lifecycle worktree while retaining its branch**

After verifying Actworks remote and clean durable clone contain source commit
provenance, confirm `bugfix/ai-session-lifecycle-safety` has no unique
uncommitted work. Remove its clean worktree. Retain the local branch because it
is intentionally unmerged; report it as preserved scaffolding unless the user
later gives explicit authorization to delete that unmerged ref.

---

### Task 13: Final cross-repository verification and report

**Files/State:**
- `OpenGGF/Actworks` remote/local clone
- `OpenGGF/OpenGGF` `origin/develop`
- Host activation state and disk inventory.

**Interfaces:**
- Consumes: all preceding tasks.
- Produces: final evidence-backed handoff.

- [ ] **Step 1: Verify Actworks remote clone**

In a fresh clone/read-only fetch, run Slipmat, lifecycle, coordinator self-test,
and process harness; verify public visibility, GPL, provenance, and clean status.

- [ ] **Step 2: Verify OpenGGF remote state**

Confirm `develop == origin/develop`, active reference searches are clean,
AGENTS/CLAUDE and skills mirrors match, raw focused guard is green, and no
extracted implementation remains tracked.

- [ ] **Step 3: Verify host and disk state**

Confirm no exact-owned timer/helper/config activation remains for new
processes, no cleanup command has remaining safe candidates, and capture final
`df -B1 /`.

- [ ] **Step 4: Report exact delivery**

Report:

- Actworks URL, public status, commits pushed, tests;
- OpenGGF commits pushed, raw baseline/branch/merged commands and outcomes;
- files/components removed and direct-Maven contract;
- GitHub/merge conflicts and their resolution;
- host cutover mutations and retained unknowns;
- worktrees/branches removed or retained;
- exact reclaimed physical bytes and unrecoverable generated deletions; and
- any unresolved state that prevents a completion claim.
