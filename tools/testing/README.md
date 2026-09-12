# Testing utilities

OpenGGF uses Maven directly. Build and test output stays below the current
worktree's `target/` directory.

Install the repository hooks once per worktree:

```bash
tools/testing/install-hooks.sh
```

PowerShell uses `tools/testing/install-hooks.ps1`.

## Test categories

Use Python 3.11+ and Java 21. The runner uses the existing Maven defaults and exclusions;
it does not move tests, alter CI/release coverage, or remove slow tests from a selected category.

```bash
python3 tools/testing/run_categories.py --list
# Compact plan: categories, candidate counts and changed-path reasons.
python3 tools/testing/run_categories.py --base develop
# Validate a finished change against its actual integration base, including local edits.
python3 tools/testing/run_categories.py --base develop --run
# Add coverage for a semantic dependency beyond the path rules.
python3 tools/testing/run_categories.py --base develop --category audio --run
# Focused development; not a substitute for the change-based delivery command.
python3 tools/testing/run_categories.py --category physics --run
# Full ordinary suite and guards, including for release preparation.
python3 tools/testing/run_categories.py --category all --run
```

Use the actual integration branch or pinned base SHA instead of `develop` where appropriate.
The diff includes committed, staged, unstaged, deleted, renamed and untracked files. A rename
checks both old and new paths. Explicit categories are additive: they cannot override an
unknown change's full-suite fallback. Without `--run`, no Maven process starts. Add `--json` only when the complete class
inventory is needed; default plans cap changed-path detail at 50 entries to keep output bounded.

| Category | Coverage |
|---|---|
| `common` | Shared and unclassified ordinary tests; included in every category run |
| `physics` | Movement, collision, sensors, solid objects and playable sprites |
| `gameplay` | Objects, bosses, zone events, stages and game flow |
| `audio` | Audio drivers, synthesis, presentation and audio tooling, including exhaustive oracles |
| `rendering` | Graphics, palettes, scrolling, sprite art and viewports |
| `rewind` | Rewind, recording, playback and save state |
| `content` | ROM decoding, compression and resource loading |
| `mods` | Mod API, creator content and SDK tools |
| `network` | Networking and presence integration |
| `tooling` | Editor, configuration, diagnostics, launchers and tool tests |

Ownership lives in `test-categories.json`. Package rules select a primary category; class-name
rules add cross-cutting categories for legacy locations. Recognized game/zone names
assign otherwise unowned legacy tests to gameplay without pulling audio-owned S2
oracles into gameplay. Categories overlap. New ordinary
classes without a narrower owner fall into `common`, so naming changes cannot silently drop
them. Candidate counts include Maven-discovered helper/abstract classes, not just executed tests.
The union covers the ordinary source inventory; Maven's existing tags and exclusions still apply.

Change rules are deliberately narrower than test ownership. Physics changes also select
related gameplay, rendering and rewind tests. Audio changes also select rewind and tooling.
Game-specific object changes select gameplay, physics, rendering, rewind and content. Ordinary
documentation changes select tooling, so updating a changelog does not force audio sweeps.
Shared clocks, game-loop code, ROM pipelines, configuration, resources, build files, test
infrastructure and unclassified changes select **all** ordinary categories. A new narrow rule
must document its dependent categories and include a selector regression test. The selector is
an explicit workload policy, not a proof of semantic independence: agents must add categories
or choose `all` when a change crosses these boundaries (for example, changing an object's audio
request contract). Do not add a rule just to avoid an inconvenient failing test.

Change-based runs (`--base`) and `--category all` include the full `guards` profile in its
own Maven invocation. Focused category runs omit guards to avoid repeating them during
iteration; add `--guards` to request them explicitly. Trace
replay, native graphics, diagnostics, performance profiles and TraceChaser integration retain
their existing commands and prerequisites. Gameplay category coverage does not replace affected
replay fixtures or the domain skill's required checks. A category run is never a full trace sweep.

The runner discovers existing root `.gen` files by the documented SHA-1 identities and passes
absolute ROM paths. It never creates ROM links or copies. Missing ROMs still require inspecting
skips; a successful exit alone does not establish ROM-backed coverage.

Each run starts in `target/category-tests/<run-id>/`. The plan retains the tested head,
working-tree fingerprint and selected source classes; command arrays record the exact Maven
arguments. `results.json` records totals, skipped cases and failure details, and `status.json`
distinguishes passed, failed and incomplete runs. Diagnostic lists are capped at 1,000 records
per kind with explicit omitted counts; messages/stacks are bounded excerpts. Inspect skips,
omissions and domain coverage before delivery. The runner checks exit status and nonempty
execution and rejects a changed working tree at completion. It does not cache or authenticate
passes for Git hooks, or claim that every candidate source produced a report.

### Cost, prerequisites and stopping

Run `python3 tools/testing/run_categories.py --base <pinned-base> --preflight` in the
same environment as the intended test command. Every `--run` also performs these checks:
Maven must use Java 21; selections with guards require executable Lua 5.4 (`LUA_BIN`,
default `lua`) and `pwsh` on PATH. Failures are collected before any test lane starts.
This checks tool launches, not ROM completeness, native display access or every fixture
prerequisite. On macOS use the known working native display/service permissions from the
first graphics launch, especially after a documented sandbox failure.

Full selections and selections of at least 500 candidate classes print a cost warning.
The September normalization run measured about 24 minutes ordinary and 10 minutes guards;
this is historical context, not a prediction. `--max-minutes` defaults to **40 minutes total
across Maven lanes**, not 40 minutes each. There is also a **10-minute no-output timeout**.
Both terminate the Maven process tree and report incomplete validation. Compilation consumes
the same budget. Tool probes have separate 20-second limits. A timeout never authorizes
silently increasing the budget or restarting the suite.

Validation is budgeted for the **whole user-requested delivery**, independently of
commit boundaries. Start it once, before implementation, and use the printed immutable
base through the entire delivery:

```bash
python3 tools/testing/run_categories.py --start-task 20260912-example --base <pre-task-commit>
python3 tools/testing/run_categories.py --task-status
# Focused category invocations are timed automatically:
python3 tools/testing/run_categories.py --category physics --run
# After a directly launched focused Maven or matched baseline check, record its elapsed time:
python3 tools/testing/run_categories.py --record-minutes 1.5 --record-kind focused
python3 tools/testing/run_categories.py --record-minutes 0.5 --record-kind baseline
# Once implementation is complete, run ONE combined change-based selection:
python3 tools/testing/run_categories.py --base <pinned-start-commit> --run
# Only on delivery or cancellation:
python3 tools/testing/run_categories.py --finish-task
```

The task receipt and exclusive lock live under the repository's **shared Git directory**,
`openggf-validation/task.json` and `task.lock`, so linked worktrees use the same accounting.
One active task is supported per repository. Commits, worktrees, changed bases and
`--repeat-reason` cannot grant another broad attempt. The receipt stores only identity,
pinned base, cumulative elapsed seconds, externally recorded focused/baseline time and
attempt/outcome counts; it is not a pass cache. The previous worktree-local broad receipt
remains a small outcome summary and does not authorize execution.

The default task ceiling is **40 minutes including focused and baseline checks**.
`--max-minutes` may choose a smaller task budget at creation; it cannot create a budget
above 40 minutes. During execution it is an invocation ceiling, further reduced by the
remaining task time. Compilation and interrupted runs count. A first failed/interrupted
broad attempt still consumes the one-broad allowance. Plan the aggregate cost before
launching; report mandatory coverage that cannot fit instead of starting an unaffordable
run. Use focused checks after the combined selection for task-caused regressions and
bounded matched attribution; keep unrelated failures explicit. Never restart the full
suite merely because another planned item or commit is ready.

`--repeat-reason` is explanation only: there is no self-service broad-repeat or task-budget
override. Finishing/restarting a task to evade the limit is prohibited. A new task identity
requires a new user request or an explicitly user-authorized exception for material changes
made after combined validation, or corrected prerequisites that invalidated it. Red results
and continuing the original plan do not qualify. The CLI cannot authenticate user intent
or observe raw Maven launched elsewhere; agents must record that elapsed time and must not
use raw commands, receipt edits/deletion or fabricated task identities as bypasses.
CI/release gates remain unchanged; incomplete or partial coverage never certifies a pass.

Changes confined to the Python runner/tests and this prose guidance use the Python safety
suite below plus actual tool preflight. Changes to selection policy, POM, Java, workflows
or hooks still require their normal change-based validation. This avoids launching tens
of thousands of engine tests to verify timeout, subprocess and retention behavior.

### Automatic storage cleanup

Diagnostics are temporary, not a run history. After inspecting `results.json` (including
skip reasons and failures) and any relevant log tail, acknowledge consumption:

```bash
python3 tools/testing/run_categories.py --acknowledge <run-id>
```

This deletes the **entire run directory**, including summaries, plans and commands, without
running Maven. It takes the same lock as validation and refuses cleanup while a runner
owns that lock. Only exact runner IDs are accepted, never paths or symlinked directories.
Acknowledgment leaves both the task accounting and broad-attempt receipt intact.
A repeated acknowledgment is harmless. Agents must acknowledge consumed results before
delivery; there is no background process that can detect when a human has read a file.

- Success logs and raw XML are deleted immediately after extracting results. The compact
  results remain temporarily so skips can be inspected before acknowledgment.
- Failed/interrupted results include rolling log tails until acknowledged. Each Maven
  invocation keeps two chunks of at most **2 MiB each** while running. Read `.log.1` before
  `.log`. Raw XML is deleted after summary extraction.
- Before launching another run, the runner deletes all unacknowledged, recognized prior
  run directories, including legacy retained runs. This also cleans abandoned diagnostics;
  it never follows symlinks or prunes unrelated target contents.
- Only an explicit `--keep-diagnostics` on `--run` retains a run across later launches.
  Use it only at the user's request. Opt-in retention remains bounded to **two runs /
  100 MiB**, and acknowledgment deletes these folders too. Raw XML and temporary fixture
  data are still removed; this flag retains bounded logs and JSON, not complete build output.
- Each invocation uses its own `openggf.test.tmpdir` and `TMPDIR`/`TMP`/`TEMP`, deleted after
  Maven's process tree exits, including on handled interruption. After a force-killed runner,
  confirm its processes exited before removing a stale lock. The next launch removes leftovers.

The only persistent default artifact is the single overwritten
`target/category-tests-last-broad.json` receipt (normally under 16 KiB). It keeps the tested
revision/fingerprint, retry reason, status and lane counts, not logs, skip reasons or failure
payloads. Acknowledgment preserves it, so deleting diagnostics cannot bypass retry controls.
The temporary storage needed by an active test is not limited by the retention budget.
Build caches, ROMs, unrelated target folders and other worktrees are not pruned. Do not copy
local logs into another archive to evade cleanup. Dedicated release/partition evidence uses
its existing explicit evidence workflow below.

`target/category-tests.lock` prevents two category runners in one worktree. Raw Maven bypasses
this lock: never start it concurrently in that worktree. Do not retry a run while its predecessor
is still active.

The selector and retention tests run in the CI smoke job. Run them locally after changes:

```bash
python3 -m unittest discover -s tools/testing -p 'test_run_categor*.py'
```

## Complete Surefire outcome inventories

The PowerShell utilities in this directory export, validate, partition, and
compare complete Surefire outcome inventories:

- `Export-SurefireOutcomeInventory.ps1` converts one or more report roots into
  an ordinal-sorted TSV.
- `Compare-SurefireOutcomeInventory.ps1` compares candidate outcomes with one
  or more parent inventories.
- `New-SurefirePartitionMap.ps1` creates deterministic class partitions for a
  suite that cannot complete as one monolithic run.
- `Test-SurefireOutcomeInventory.ps1` exercises the inventory contract.

An inventory source file contains one fully qualified selected top-level class
per line. A testcase belongs to that root when its XML `classname` is exactly
the root or begins with the exact `root + '$'` boundary. Duplicate testcase
identities are fatal unless a reviewed cardinality file declares the exact
identity, count, and reason.

An authenticated explicit-source export is atomic. Retain all of these
artifacts from the run being exported:

- the ordinal-sorted top-level source-class inventory;
- its exact ordinal-bijective slash-path selector file, supplied to Maven by
  one canonical absolute `surefire.includesFile` argument;
- the exact Maven argument vector, one argument per line;
- the effective POM generated with the same profiles and property overrides;
  and
- the exact `OPENGGF_RUNTIME_INPUTS` value used for the run. It contains the
  canonical selector, Maven-argument inventory, and effective-POM paths exactly
  once for a direct capacity override, plus the reviewed repeated-identity
  cardinality file exactly once when that file is used.

Direct Maven may use an explicit `surefire.argLine` only as a capacity
override. A certifying invocation must contain exactly one explicit value for
each of `surefire.includesFile`, `surefire.argLine`, `surefire.forkCount`,
`surefire.reuseForks`, and `surefire.runOrder`; fork count and reuse must be `1`
and `true`, while run order must be `alphabetical`. The resolved arg line
must preserve exact CDS and Mockito-agent semantics, may retain macOS
`-XstartOnFirstThread`, and must end in the proven-sufficient `-Xmx3g` heap.
The raw Maven vector may instead carry exactly
`${test.cds.argLine} ${mockito.agent.argLine} -Xmx3g`, with the leading
`-XstartOnFirstThread` only when the effective project profile has that same
prefix. The effective execution must expand this canonical template before the
exporter authenticates its final tokens.
Maven may leave the exact `${settings.localRepository}` prefix unresolved in
the effective Mockito property, project argLine, or execution argLine. In that
case, pass the canonical physical repository directory separately as
`-MavenLocalRepositoryPath`. The exporter accepts that evidence only for the
exact `org/mockito/mockito-core/<version>/mockito-core-<version>.jar` suffix,
requires that exact jar to exist below the non-reparse repository path, and
substitutes only within each field's single expected Mockito `-javaagent:`
token before authenticating the resolved JVM arguments. The placeholder is
rejected in every other project or execution token, including temp and LWJGL
paths, and multiple occurrences in either field are rejected. The resolved
project and execution agents must match the same effective Mockito artifact
path and version. Omit this parameter when all effective Mockito paths are
already absolute. This is Maven-environment resolution evidence, not runtime
input selection, so its canonical path must occur zero times in
`OPENGGF_RUNTIME_INPUTS`, including normalized-equivalent forms.
The selected Surefire execution must prove the same resolved JVM arguments and
then exactly the target-local `java.io.tmpdir` and fork-local LWJGL extraction
properties. Any other raw `${...}` placeholder, every raw Surefire `@{...}`
placeholder, including the empty `${}` and `@{}` forms, or an unresolved
placeholder in the final authenticated tokens or paths fails closed, as do
duplicate or mismatched evidence, external temp paths, and shared LWJGL paths.
The 3-GiB value is proven sufficient by the recorded capacity run; it is not a
claim that 3 GiB is the minimum usable heap.
Unlike historical managed-session evidence, direct mode neither supplies nor
accepts invented adapter-owned `user.home`, LWJGL, session-root, or run-id
suffixes.

For example, this records a truthful one-fork/3-GiB direct invocation. The
class and selector inventories must already contain the complete selected
suite described above:

```powershell
$worktree = (Resolve-Path .).Path
$evidence = Join-Path $worktree 'target/surefire-inventory-evidence'
$classes = Join-Path $evidence 'ordinary-classes.txt'
$selector = (Resolve-Path (Join-Path $evidence 'ordinary.includes')).Path
$effectivePom = Join-Path $evidence 'ordinary-effective-pom.xml'
$argumentInventory = Join-Path $evidence 'ordinary-maven-arguments.txt'
$effectiveProjectArgLine = (& mvn -Dmse=off help:evaluate `
    -Dexpression=surefire.argLine -q -DforceStdout).Trim()
$mavenLocalRepository = (& mvn -Dmse=off help:evaluate `
    -Dexpression=settings.localRepository -q -DforceStdout).Trim()
$mavenLocalRepository = (Resolve-Path -LiteralPath $mavenLocalRepository).Path
$macLauncher = if ($effectiveProjectArgLine.StartsWith(
        '-XstartOnFirstThread ', [StringComparison]::Ordinal)) {
    '-XstartOnFirstThread '
} else {
    ''
}
$capacityArgLine = $macLauncher +
    '${test.cds.argLine} ${mockito.agent.argLine} -Xmx3g'
$capacityProperties = @(
    '-Dmse=off'
    "-Dsurefire.argLine=$capacityArgLine"
    '-Dsurefire.forkCount=1'
    '-Dsurefire.reuseForks=true'
    '-Dsurefire.runOrder=alphabetical'
    "-Dsurefire.includesFile=$selector"
)
$mavenArguments = @($capacityProperties) + 'test'

New-Item -ItemType Directory -Force -Path $evidence | Out-Null
& mvn @capacityProperties help:effective-pom "-Doutput=$effectivePom"
[IO.File]::WriteAllLines($argumentInventory, $mavenArguments,
    [Text.UTF8Encoding]::new($false))
$argumentInventory = (Resolve-Path $argumentInventory).Path
$env:OPENGGF_RUNTIME_INPUTS = @(
    $selector, $argumentInventory, $effectivePom
) -join `
    [IO.Path]::PathSeparator
& mvn @mavenArguments

& ./tools/testing/Export-SurefireOutcomeInventory.ps1 `
    -SourceClassInventory $classes `
    -SelectorPatternInventory $selector `
    -MavenArgumentInventory $argumentInventory `
    -RuntimeInputs $env:OPENGGF_RUNTIME_INPUTS `
    -EffectivePomPath $effectivePom `
    -MavenLocalRepositoryPath $mavenLocalRepository `
    -ReportRoot (Join-Path $worktree 'target/surefire-reports') `
    -DirectMaven `
    -CanonicalWorktree $worktree `
    -OutputPath (Join-Path $evidence 'ordinary-outcomes.tsv')
```

Run the exporter from PowerShell so multiple report roots remain an array:

```powershell
& ./tools/testing/Export-SurefireOutcomeInventory.ps1 `
    -SourceClassInventory ./evidence/candidate-classes.txt `
    -ReportRoot @(
        ./target/surefire-reports
    ) `
    -DirectMaven `
    -CanonicalWorktree (Resolve-Path .).Path `
    -OutputPath ./evidence/candidate-outcomes.tsv
```

`-DirectMaven` makes expected-red reports usable without inventing coordinator
session values. It requires the explicit canonical worktree and accepts report
roots only at or below that worktree's `target/surefire-reports`; volatile
worktree paths are normalized to `<WORKTREE>`. Direct mode rejects symbolic
links and reparse points in the worktree/report ancestry and anywhere below a
report root before reading XML, so lexical containment cannot hide an external
report tree. Historical managed-session
evidence remains supported without `-DirectMaven`, where any red outcome still
requires the complete `CanonicalWorktree`, `SessionRoot`, and `RunId` provenance
set.

The export schema is:

```text
identity class method outcome red_kind exception_type normalized_message red_body_bytes red_body_sha256 report
```

Outcomes are `PASS`, `FAILURE`, `ERROR`, or `SKIPPED`. Red bodies have LF line
endings before their complete UTF-8 byte length and streaming SHA-256 are
recorded. Textual TSV values use the reversible escape layer implemented by
the exporter and validator.

Compare candidate and parent inventories with:

```powershell
& ./tools/testing/Compare-SurefireOutcomeInventory.ps1 `
    -ParentInventoryPath ./evidence/parent-outcomes.tsv `
    -CandidateInventoryPath ./evidence/candidate-outcomes.tsv `
    -OutputPath ./evidence/parent-candidate-comparison.tsv
```

If a monolithic suite cannot produce a complete inventory, create a stable
union map and run every non-empty per-tree selector:

```powershell
& ./tools/testing/New-SurefirePartitionMap.ps1 `
    -NextClassInventory ./evidence/next-classes.txt `
    -DevelopClassInventory ./evidence/develop-classes.txt `
    -CandidateClassInventory ./evidence/candidate-classes.txt `
    -SlotSize 75 `
    -OutputPath ./evidence/surefire-partitions.tsv
```

A partial monolithic run is not a suite result. Retain it as failed-run
evidence; only a complete monolithic inventory or complete deterministic
partition aggregate may be reported.

Run the literal fixture suite with:

```powershell
pwsh -NoProfile -File tools/testing/Test-SurefireOutcomeInventory.ps1
```

## Trace fixture validation

The remaining trace validation scripts in this directory validate committed
metadata, run manifests, timing sidecars, and fixture compression. They are
independent of the Maven launcher and continue to operate on caller-supplied
paths.
