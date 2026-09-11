# Actworks extraction and direct-Maven rollback

## Status

Approved in discussion on 2026-08-27. This document defines one coordinated
migration: preserve the reusable agent lifecycle tooling in a new public
`OpenGGF/Actworks` repository, then remove that tooling and its enforcement
from OpenGGF and return OpenGGF builds to ordinary Maven output under
`target/`.

The extraction is a hard ordering gate. OpenGGF must not delete the source
until the public Actworks remote contains the extracted commits and the remote
heads have been verified.

## Incident and decision

The managed test-session system was introduced to isolate concurrent agents,
retain certifying evidence, and compact reproducible output. In practice it
created multiple durable output roots per worktree and allowed legacy
project-local `.openggf` sessions to accumulate alongside managed scratch.
During this incident:

- managed scratch reached approximately 264.8 GB;
- OpenGGF worktrees reached approximately 265.3 GB;
- individual `.openggf` trees reached tens of gigabytes; and
- root free space continued to fall after repeated terminal compaction.

The operational cost now exceeds the benefit for OpenGGF. Repeated Maven runs
should reuse one conventional `target/` tree per worktree, and removing a
worktree should naturally remove its build output.

The lifecycle work remains potentially useful outside the game engine. It is
therefore extracted rather than discarded.

## Goals

1. Create a public GPL-3.0 `OpenGGF/Actworks` repository.
2. Preserve the reviewed Slipmat, worktree-lifecycle, and Maven-session source,
   tests, design rationale, and useful Git provenance.
3. Keep cowtree in its existing repository and document it as an external
   constituent tool.
4. Remove the extracted implementations and all active enforcement from
   OpenGGF.
5. Make direct Maven the canonical OpenGGF build and test interface.
6. Keep all ordinary test/build/report/diagnostic output within the current
   worktree's `target/` directory.
7. Update CI and release workflows to use static `target/` paths.
8. Reclaim existing generated session and obsolete-worktree storage without
   deleting unknown, dirty, unmerged, active, or promoted work.
9. Record Actworks/Slipmat reconsideration as a v0.8 project ask, not a v0.6
   or v0.7 engine deliverable.

## Non-goals

- Generalising the extracted OpenGGF Maven coordinator during this migration.
- Vendoring, renaming, or moving cowtree.
- Making Actworks or Slipmat part of the OpenGGF build.
- Preserving the current test-session system as an opt-in OpenGGF mode.
- Rewriting historical changelog, trace-frontier, or completed investigation
  entries that factually describe earlier session-wrapper runs.
- Deleting captures, distributions, promoted artifacts, unknown filesystem
  content, dirty worktrees, or unmerged branches during storage recovery.
- Changing gameplay, trace comparison semantics, ROM policy, or engine timing.

## Repository boundaries

### Actworks

The initial repository layout is:

```text
Actworks/
  README.md
  LICENSE
  slipmat/
    bin/
    tests/
    docs/
  lifecycle/
    bin/
    tests/
    docs/
  incubator/
    openggf-maven-session/
      src/
      bin/
      tests/
      docs/
  docs/
    architecture/
    provenance/
```

`slipmat` owns the extracted managed allocation, retention, capacity,
compaction, and status code currently implemented by `agent-scratch`.

`lifecycle` owns the reviewed read-only worktree audit and proved-safe Git
retirement code from `bugfix/ai-session-lifecycle-safety`.

`incubator/openggf-maven-session` preserves the Java coordinator, POSIX and
PowerShell wrappers, process harnesses, fixtures, and OpenGGF-specific design
material. Incubator status is explicit: this is preserved source and research,
not a supported Actworks interface and not a recommended OpenGGF workflow.

Actworks links to cowtree's existing repository as an external tool. No cowtree
source is copied into Actworks.

### OpenGGF

OpenGGF retains only game-engine build/test behavior. It does not call,
install, verify, or document Actworks, Slipmat, `agent-scratch`, the Maven
session coordinator, or the worktree-lifecycle CLI as required development
infrastructure. The roadmap may link to Actworks as a deferred v0.8 project;
that link is informational, not a build dependency.

## Source and history preservation

The authoritative extraction source is local commit
`27b9840a8137e7cbf30b2fb4ee5be8eedf263e6c` on
`bugfix/ai-session-lifecycle-safety`. It contains the independently reviewed
versions that were intentionally not integrated while the earlier
`develop`-to-`next` merge was active.

The extraction uses a path-filtered Git export rather than a snapshot-only
copy. It includes only the selected lifecycle files and their relevant
commits, avoiding ROMs, game assets, engine source, trace fixtures, and
unrelated OpenGGF history. A subsequent Actworks restructuring commit moves
the preserved paths into the repository layout above and records:

- the OpenGGF source repository;
- the source branch and exact commit;
- original paths and new paths;
- the extraction date;
- the GPL-3.0 licensing basis; and
- any file deliberately excluded as OpenGGF-only.

If path-filtered export cannot be made exact with stock Git, the fallback is a
reviewed component-by-component snapshot with an explicit provenance manifest.
The fallback must not claim full history preservation.

## OpenGGF direct-Maven contract

The canonical commands become:

```bash
mvn package
mvn test
mvn -Dtest=TestCollisionLogic test
mvn -Dmse=off -Pguards test -B
```

PowerShell uses the same Maven commands, quoting `-D...` arguments where
needed. A raw Maven lifecycle run is ordinary and acceptable evidence; there
is no separate certifying wrapper classification.

OpenGGF removes:

- `tools/agent-scratch` and its tests;
- `tools/worktree-lifecycle` and its tests;
- `TestSessionCoordinator` and its self/process harnesses;
- POSIX and PowerShell `test-session` wrappers;
- the session-guard fixture and coordinator-only launcher scripts;
- wrapper-only guards, marker contracts, manifest contracts, lease contracts,
  helper-freshness requirements, and raw-Maven prohibitions; and
- active documentation that requires managed scratch or session manifests.

Historical release notes and trace-frontier entries remain factual records.
Dedicated lifecycle designs, plans, and validation records move to Actworks;
OpenGGF may retain one concise migration record pointing to the new remote.

## Maven output layout

The POM stops accepting an external session build root. Standard Maven
`${project.build.directory}` is the sole build root and resolves to `target/`
unless Maven itself is explicitly configured otherwise.

Surefire reports, trace reports, diagnostics, distributions, and packaged
artifacts derive from `${project.build.directory}`. Test JVM temporary files
and LWJGL extraction remain below `target/test-tmp`, with a distinct
per-Surefire-fork LWJGL child to prevent native-library collisions. This
isolation is local to the reusable target tree and does not create durable
session directories.

The rollback removes coordinator-only `openggf.*` build-root override
properties. Properties that are genuine engine/test inputs rather than output
relocation remain unchanged.

## CI and release workflows

Every workflow invocation changes from the session wrapper to the equivalent
raw Maven command. Dynamic wrapper exports are replaced with explicit paths
below `$GITHUB_WORKSPACE/target`, including:

- Surefire reports;
- trace reports;
- diagnostics;
- packaged JAR/native/universal artifacts; and
- any release verification inputs currently read through wrapper outputs.

Workflow scripts must not search `.openggf`, managed scratch, session
manifests, or wrapper-exported run roots after the rollback. Artifact upload
and failure-summary behavior remains intact.

## Documentation and policy updates

The migration updates together:

- `AGENTS.md` and `CLAUDE.md`;
- README build/test and workflow links;
- contributor setup and testing guides;
- active trace-replay skills and their mirrors;
- active agent-workflow runbooks that prescribe wrapper commands;
- CI/release documentation;
- `CHANGELOG.0.6.md` with the operational rollback; and
- `ROADMAP.md` with the v0.8 Actworks/Slipmat ask.

The policy hook trailers follow the existing documentation map. The root agent
documents and mirrored skills remain byte-identical where required.

## Host cutover

Repository rollback alone is insufficient because previous installation wrote
a user-wide helper, systemd units, and Claude/Codex environment settings. After
Actworks is remotely durable and OpenGGF no longer invokes the harness, the
migration disables that active host integration:

- stop and disable only the exact owned `agent-scratch-prune.timer` unit;
- remove only generated unit/environment files whose content and ownership
  match the installed OpenGGF templates;
- remove `$HOME/.local/bin/agent-scratch` only when its identity and digest
  match a known extracted source version;
- remove only the managed `AGENT_SCRATCH_ROOT`, `OGGF_SCRATCH_ROOT`, and
  helper-injected temporary-directory keys/roots from Claude and Codex config,
  preserving all unrelated user settings; and
- leave the managed scratch data itself in place until the proved-safe storage
  recovery pass classifies it.

The cutover reports every mismatched or unknown host file and leaves it
untouched. Existing agent processes retain their inherited environment until
they exit; the migration verifies fresh non-agent shells/config state and does
not claim it can rewrite another live process's environment.

## Migration sequence

1. Record raw-Maven OpenGGF baseline evidence on the current integration
   commit before changing the workflow.
2. Produce the path-filtered Actworks repository locally from the reviewed
   lifecycle source commit.
3. Restructure, document, and test all extracted components.
4. Create public `OpenGGF/Actworks`, push the extracted history, and verify the
   remote default branch and commit IDs. GitHub API authentication is currently
   invalid and must be refreshed before this gate can pass.
5. Implement the OpenGGF deletion and direct-Maven rollback in an isolated
   worktree based on the then-current `develop` branch.
6. Run raw Maven focused, ordinary, and fresh-JVM guard verification and
   compare with the raw baseline.
7. Integrate according to OpenGGF policy, rerun post-merge verification, and
   push only `develop`.
8. Verify the Actworks remote still contains every extracted source path and
   provenance record.
9. Perform the exact-owned host cutover and verify the timer/helper/config no
   longer activates managed test-session storage for new processes.
10. Remove completed extraction/rollback worktrees and fully merged local
   branches.
11. Perform the proved-safe storage recovery pass and report reclaimed and
    retained state.

OpenGGF source deletion never precedes step 4.

## Storage recovery boundary

The recovery pass begins with read-only inventories of processes, leases,
worktree registrations, Git state, and candidate bytes. It may remove only:

- exact generated test-session temporary/build-copy paths whose ownership and
  terminal state are authenticated;
- project-local `.openggf` session output proven generated, terminal, and free
  of promoted artifacts or captures; and
- clean, fully merged, inactive worktrees after a fresh pre-removal proof.

Dirty, unmerged, detached without confirmation, locked, leased, active,
unreadable, foreign-pointer, unknown, capture-bearing, or promoted-artifact
paths are retained and reported. No raw recursive deletion is performed from
an unresolved environment variable, glob, repository root, worktree root, or
managed-root path.

Because Btrfs reflinks make apparent size differ from physical allocation, the
report records both candidate apparent bytes and before/after filesystem free
bytes.

## Validation

### Actworks

- Slipmat Python compilation and complete unit suite.
- Worktree-lifecycle Python compilation and complete unit suite.
- Coordinator Java self-test.
- External process harness covering POSIX behavior available on the host.
- PowerShell source/contract checks where native PowerShell execution is not
  available.
- Executable modes, no-follow/race tests, documentation links, license, and
  provenance verification.
- Clean local status and clean remote clone verification.

### OpenGGF

- JDK 21 confirmed by `mvn -v`.
- Raw focused `TestBuildToolingGuard` run.
- Raw ordinary `mvn test` baseline/branch/merged comparison.
- Raw fresh-JVM `mvn -Dmse=off -Pguards test -B`
  baseline/branch/merged comparison.
- Focused tests for any guard or POM behavior changed by the rollback.
- CI/release workflow path checks.
- `git diff --check` and policy hooks.
- `AGENTS.md`/`CLAUDE.md` and skill-mirror equality.
- Searches proving active code, workflows, guidance, and guards no longer
  depend on the extracted tools or session protocol.

Pre-existing red baseline tests do not block the migration. Any new failure,
changed failure attributable to the migration, missing workflow artifact, or
loss of test execution does block it.

## Failure handling and rollback

- If Actworks cannot be created or pushed, retain all OpenGGF source and stop.
- If extracted tests fail, fix Actworks before altering OpenGGF.
- If raw Maven cannot reproduce the baseline, diagnose the output-path or
  workflow difference before deleting the wrapper.
- If OpenGGF integration or post-merge verification fails, do not push and do
  not remove the implementation worktree.
- If cleanup encounters a changed identity, live lease/process, unknown file,
  or partial operation, stop that candidate and retain/report it.
- Do not restore the test harness merely to make a migration test green;
  correct the direct-Maven path or report the blocker.

## Acceptance criteria

The migration is complete only when:

1. `OpenGGF/Actworks` is public and remotely contains the extracted components,
   license, documentation, provenance, and verified commits.
2. Cowtree remains external and is only linked/documented.
3. OpenGGF's active build, test, CI, release, agent, and contributor paths use
   direct Maven.
4. OpenGGF contains none of the extracted implementations or active protocol
   enforcement.
5. OpenGGF produces ordinary output under one `target/` tree per worktree.
6. Baseline comparison shows no migration-attributable regression.
7. Both repositories are pushed with clean worktrees and reported commit IDs.
8. Completed migration worktrees and local scaffold branches are removed.
9. Exact-owned user-wide test-harness activation is disabled; unknown host
   state is retained and reported rather than overwritten.
10. The storage sweep reports exact deletions, physical headroom recovered, and
   every retained blocker.
