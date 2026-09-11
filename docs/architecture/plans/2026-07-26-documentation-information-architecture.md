# Documentation Information Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every repository-owned documentation artifact a semantic home, eliminate dumping-ground and parallel topic folders, and leave a navigable, policy-enforced documentation tree.

**Architecture:** `docs/README.md` is the entry point. Current references and dated engineering artifacts live under `docs/architecture`, active state under `docs/status`, project history and direction under `docs/project`, contributor material under `docs/guide`, and workflow support under `docs/agent-workflow`. Domain names such as audio, testing, trace, performance, and S3K zones appear only as useful subfolders inside purpose-based categories.

**Tech Stack:** Markdown, Git, POSIX shell, `rg`, `find`, repository policy hooks.

## Global Constraints

- Preserve historical material unless it is one of the two verified superseded duplicates.
- Delete only `docs/archive/KNOWN_DISCREPANCIES.md` and `docs/archive/sonic2_rev01_checkpoints.md`.
- Never stage or overwrite another agent's in-progress changes.
- Keep `AGENTS.md` and `CLAUDE.md` identical and stage them together.
- Move tracked files with `git mv` to preserve recognizable history.
- Include the untracked archive audio assets and untracked testing audit artifacts only after confirming their ownership and intended classification.
- Update references to every moved tracked path.
- Do not create `archive`, `misc`, `notes`, tool-named, or loose-root dumping grounds.
- The migration remains incomplete while any classified file is deferred for concurrent ownership.

---

### Task 1: Preflight ownership and create the destination taxonomy

**Files:**
- Create: `docs/README.md`
- Create: `docs/architecture/audits/`
- Create: `docs/architecture/audits/s3k-zones/`
- Create: `docs/architecture/audits/testing/`
- Create: `docs/architecture/research/audio/`
- Create: `docs/architecture/research/s3k/`
- Create: `docs/architecture/research/s3k-zones/`
- Create: `docs/architecture/research/trace/`
- Create: `docs/architecture/validation/performance/`
- Create: `docs/architecture/validation/s3k-zones/`
- Create: `docs/architecture/s3k-zones/`
- Create: `docs/project/`
- Create: `docs/status/`

**Interfaces:**
- Consumes: `docs/architecture/designs/2026-07-26-documentation-information-architecture-design.md`.
- Produces: Destination directories, a documentation index, and an explicit list of files safe to migrate.

- [ ] **Step 1: Record all concurrent documentation changes**

Run:

```bash
git status --short --untracked-files=all -- '*.md' '*.txt' '*.tsv' '*.lua'
```

Record every dirty path that appears in the migration mapping. Do not move or
stage those paths until their owning work is committed or the owner explicitly
hands them over.

- [ ] **Step 2: Check destination collisions**

The new category directories (`audits`, `project`, `status`, and the domain
subdirectories) must not exist before this migration. Check every destination
that would land in an already-existing directory:

```bash
test ! -e docs/agent-workflow/support-options.md
test ! -e docs/architecture/singleton-lifecycle.md
test ! -e docs/architecture/designs/s3k-level-event-design.md
test ! -e docs/architecture/plans/trace-remediation.md
test ! -e docs/architecture/plans/cpz-boss-implementation.md
test ! -e docs/architecture/plans/ehz-boss-parity-fix.md
test ! -e docs/architecture/plans/optimization.md
test ! -e docs/architecture/plans/consolidation.md
test ! -e docs/architecture/plans/rewind-gap-fixes.md
test ! -e docs/architecture/research/aiz-intro-scroll.md
test ! -e docs/architecture/research/result-screen-rendering.md
test ! -e docs/architecture/validation/boss-framework-ehz.md
test ! -e docs/architecture/validation/constants-step-3.md
test ! -e docs/guide/contributing/headless-testing.md
test ! -e docs/guide/cross-referencing/sonic2-rev01-checkpoints.md
```

Expected: every check exits 0. Stop on a collision; never overwrite.

- [ ] **Step 3: Create the documentation index**

Create `docs/README.md` with links and short descriptions for:

```text
agent-workflow
architecture
assets
changelog
guide
project
status
```

The architecture entry must link its `designs`, `plans`, `research`, `audits`,
and `validation` categories and explain the question answered by each.

- [ ] **Step 4: Update the architecture index**

Extend `docs/architecture/README.md` with:

```markdown
- [Audits](audits/) contain point-in-time assessments, inventories, reviews,
  and gap analyses.
```

Document that domain subfolders may exist within a category but must not
replace purpose-based classification.

- [ ] **Step 5: Verify the index content**

Run:

```bash
rg -n 'agent-workflow|architecture|project|status|Designs|Plans|Research|Audits|Validation' docs/README.md docs/architecture/README.md
```

Expected: every category appears in the appropriate index.

### Task 2: Classify loose Markdown from `docs/`

**Files:**
- Move: all Markdown currently directly under `docs/` except the new `README.md`.

**Interfaces:**
- Consumes: Destination taxonomy from Task 1.
- Produces: No loose root Markdown and purpose-classified active documents.

- [ ] **Step 1: Move workflow and current architecture references**

Use these exact mappings:

```text
docs/agent-workflow/support-options.md
  → docs/agent-workflow/support-options.md
docs/SINGLETON_LIFECYCLE.md
  → docs/architecture/singleton-lifecycle.md
```

- [ ] **Step 2: Move architecture audits**

Use these exact mappings:

```text
docs/CLAUDE_ARCHUNIT_EVALUATION.md
  → docs/architecture/audits/claude-archunit-evaluation.md
docs/GPT_ARCHUNIT_EVALUATION.md
  → docs/architecture/audits/gpt-archunit-evaluation.md
docs/PROPOSAL_C_ARCHUNIT_EVALUATION.md
  → docs/architecture/audits/proposal-c-archunit-evaluation.md
docs/PROPOSAL_G_ARCHUNIT_EVALUATION.md
  → docs/architecture/audits/proposal-g-archunit-evaluation.md
docs/doc-gap-audit-2026-05-30.md
  → docs/architecture/audits/2026-05-30-documentation-gap.md
docs/doc-gap-audit-2026-06-12.md
  → docs/architecture/audits/2026-06-12-documentation-gap.md
docs/opus-branch-review.md
  → docs/architecture/audits/opus-common-utility-branch-review.md
docs/architecture/audits/release-architecture-review-issues.md
  → docs/architecture/audits/release-architecture-review-issues.md
```

- [ ] **Step 3: Move the trace remediation plan**

```text
docs/TRACE_REMEDIATION_PLAN.md
  → docs/architecture/plans/trace-remediation.md
```

- [ ] **Step 4: Move project-history documents**

```text
docs/AI_JOURNEY.md
  → docs/project/ai-journey.md
docs/project/development-timeline.md
  → docs/project/development-timeline.md
docs/project/release-readiness-roadmap.md
  → docs/project/release-readiness-roadmap.md
```

- [ ] **Step 5: Move active status ledgers**

```text
docs/BUGLIST.md
  → docs/status/bug-list.md
docs/status/s3k-bug-list.md
  → docs/status/s3k-bug-list.md
docs/BUGLIST_SPECIAL_STAGES.md
  → docs/status/special-stage-bug-list.md
docs/KNOWN_BUGS.md
  → docs/status/known-bugs.md
docs/status/known-discrepancies.md
  → docs/status/known-discrepancies.md
docs/status/s3k-known-bugs.md
  → docs/status/s3k-known-bugs.md
docs/S3K_KNOWN_DISCREPANCIES.md
  → docs/status/s3k-known-discrepancies.md
docs/status/trace-frontier-log.md
  → docs/status/trace-frontier-log.md
```

- [ ] **Step 6: Move game and zone references**

```text
docs/AIZ-INTRO.md
  → docs/architecture/s3k-zones/aiz-intro.md
docs/CNZ_OBJECT_PRIORITY_AUDIT.md
  → docs/architecture/audits/s3k-zones/cnz-object-priority.md
docs/CNZ_WATER_BADNIK_CONSTANT_AUDIT.md
  → docs/architecture/audits/s3k-zones/cnz-water-badnik-constants.md
docs/sonic2_rev01_checkpoints.md
  → docs/guide/cross-referencing/sonic2-rev01-checkpoints.md
```

- [ ] **Step 7: Verify the loose root**

Run:

```bash
find docs -maxdepth 1 -type f -name '*.md' -printf '%f\n'
```

Expected: only `README.md`. If concurrently owned files were deferred, list
them explicitly and leave this task in progress.

### Task 3: Eliminate `docs/archive`

**Files:**
- Move: 13 retained tracked Markdown files.
- Move: 2 relevant untracked audio-debug assets.
- Delete: 2 verified superseded tracked duplicates.

**Interfaces:**
- Consumes: Architecture category directories.
- Produces: An absent `docs/archive` directory with every valuable artifact retained.

- [ ] **Step 1: Move the archived design and plans**

```text
docs/archive/S3K_Level_Event_Plan.md
  → docs/architecture/designs/s3k-level-event-design.md
docs/archive/CPZ_BOSS_IMPLEMENTATION_PLAN.md
  → docs/architecture/plans/cpz-boss-implementation.md
docs/archive/EHZBossFixPlan.md
  → docs/architecture/plans/ehz-boss-parity-fix.md
docs/archive/OPTIMIZATION_PLAN.md
  → docs/architecture/plans/optimization.md
docs/archive/consolidation_plan.md
  → docs/architecture/plans/consolidation.md
```

- [ ] **Step 2: Move archived general research**

```text
docs/archive/AIZ_INTRO_SCROLL_INVESTIGATION.md
  → docs/architecture/research/aiz-intro-scroll.md
docs/archive/result_screen_bug_troubleshooting.md
  → docs/architecture/research/result-screen-rendering.md
```

- [ ] **Step 3: Move archived audio research**

```text
docs/archive/YM2612_DISCREPANCIES.md
  → docs/architecture/research/audio/ym2612-discrepancies.md
docs/archive/signpost_sfx_debug_diary.md
  → docs/architecture/research/audio/signpost-sfx-debug-diary.md
docs/archive/YM2612.java.example.txt
  → docs/architecture/research/audio/YM2612.java.example.txt
docs/archive/bizhawk_signpost_debug.lua
  → docs/architecture/research/audio/bizhawk-signpost-debug.lua
```

The first two are tracked Git moves. The latter two are untracked artifacts;
move them only after the ownership preflight confirms they belong to this
documentation cleanup.

- [ ] **Step 4: Move archived audits and validation**

```text
docs/archive/collision_docs_consolidation_notes.md
  → docs/architecture/audits/collision-documentation-consolidation.md
docs/archive/player-sprites-progress.md
  → docs/architecture/audits/player-sprite-progress.md
docs/archive/BOSS_VALIDATION_SUMMARY.md
  → docs/architecture/validation/boss-framework-ehz.md
docs/archive/STEP3_CONSTANTS_VALIDATION.md
  → docs/architecture/validation/constants-step-3.md
```

- [ ] **Step 5: Delete only the verified duplicates**

Run:

```bash
git rm docs/archive/KNOWN_DISCREPANCIES.md
git rm docs/archive/sonic2_rev01_checkpoints.md
```

The maintained replacements are `docs/status/known-discrepancies.md` and
`docs/guide/cross-referencing/sonic2-rev01-checkpoints.md`.

- [ ] **Step 6: Remove and verify the archive**

Run:

```bash
rmdir docs/archive
test ! -e docs/archive
```

Expected: both commands exit 0.

### Task 4: Classify existing topic folders

**Files:**
- Move: `docs/audio-debug`, `docs/performance`, `docs/prompts`, `docs/rewind`, `docs/s3k`, `docs/s3k-zones`, `docs/testing`, and `docs/trace`.

**Interfaces:**
- Consumes: Purpose-based category directories.
- Produces: No parallel topic-folder taxonomy.

- [ ] **Step 1: Move the complete audio research collection**

Move all existing `docs/audio-debug/*` files to
`docs/architecture/research/audio/`, checking first for collisions with the
four archive audio files. Add
`docs/architecture/research/audio/README.md` that groups the collection into:

```text
saved external references
reference recordings
engine captures
analysis images and raw data
investigation notes and scripts
```

Remove `docs/audio-debug` after every file is moved.

- [ ] **Step 2: Move performance validation**

```text
docs/architecture/validation/performance/2026-06-11-baseline.md
  → docs/architecture/validation/performance/2026-06-11-baseline.md
docs/architecture/validation/performance/2026-06-11-results.md
  → docs/architecture/validation/performance/2026-06-11-results.md
docs/performance/2026-06-12-trace-baseline.md
  → docs/architecture/validation/performance/2026-06-12-trace-baseline.md
docs/performance/2026-07-performance-integration-report.md
  → docs/architecture/validation/performance/2026-07-integration-report.md
```

- [ ] **Step 3: Split rewind status and plan**

```text
docs/status/rewind-round-trip-gaps.md
  → docs/status/rewind-gaps.md
docs/architecture/plans/rewind-object-coverage-fixes.md
  → docs/architecture/plans/rewind-gap-fixes.md
```

- [ ] **Step 4: Split testing guide and audit evidence**

```text
docs/testing/headless-testing.md
  → docs/guide/contributing/headless-testing.md
docs/testing/debug-trace-test-audit.md
  → docs/architecture/audits/testing/debug-trace-tests.md
docs/testing/red-suite-inventory.tsv
  → docs/architecture/audits/testing/red-suite-inventory.tsv
docs/testing/unfinished-sk-zone-red-exclusions.txt
  → docs/architecture/audits/testing/unfinished-sk-zone-red-exclusions.txt
```

The debug-trace audit is currently untracked; include it only after the
ownership preflight confirms it belongs to this migration.

- [ ] **Step 5: Move trace and S3K research**

```text
docs/trace/s2-ss-init-timeline.md
  → docs/architecture/research/trace/s2-special-stage-init-timeline.md
docs/s3k/game-mode-constants.md
  → docs/architecture/research/s3k/game-mode-constants.md
```

- [ ] **Step 6: Classify S3K zone research**

Move every `docs/s3k-zones/*-analysis.md` file to
`docs/architecture/research/s3k-zones/` without renaming its basename.

- [ ] **Step 7: Classify S3K zone validation**

Use these mappings:

```text
docs/s3k-zones/cnz-post-workstream-c-baseline.md
  → docs/architecture/validation/s3k-zones/cnz-post-workstream-c.md
docs/architecture/validation/s3k-zones/cnz-post-workstream-d.md
  → docs/architecture/validation/s3k-zones/cnz-post-workstream-d.md
docs/s3k-zones/cnz-trace-divergence-baseline.md
  → docs/architecture/validation/s3k-zones/cnz-trace-divergence.md
docs/s3k-zones/cnz-trace-divergence-baseline.d
  → docs/architecture/validation/s3k-zones/cnz-trace-divergence.d
```

- [ ] **Step 8: Classify S3K zone audits**

```text
docs/s3k-zones/cnz-task7-regression-note.md
  → docs/architecture/audits/s3k-zones/cnz-task7-regression.md
```

- [ ] **Step 9: Move reusable prompts**

Move `docs/prompts/*` to `docs/agent-workflow/prompts/` without renaming
basenames.

- [ ] **Step 10: Remove empty topic directories**

Run:

```bash
rmdir docs/audio-debug docs/performance docs/prompts docs/rewind
rmdir docs/s3k docs/s3k-zones docs/testing docs/trace
```

Expected: all commands succeed. If a directory remains because a concurrently
owned file was deferred, list that file and keep this task in progress.

### Task 5: Update indexes, agent policy, and all references

**Files:**
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: repository-owned documentation and clean source comments that reference moved paths.

**Interfaces:**
- Consumes: Complete old-to-new mapping from Tasks 2–4.
- Produces: Discoverable categories and no stale tracked links.

- [ ] **Step 1: Expand the agent placement policy**

Update the mirrored policy to require:

```text
consult docs/README.md before creating documentation
classify by purpose, not topic or tool
use architecture/audits for point-in-time assessments
use architecture/research/audio for audio investigations and assets
do not create loose docs-root Markdown
do not create archive, misc, notes, or tool-named dumping grounds
stage relevant artifacts before finishing
```

- [ ] **Step 2: Synchronize agent guidance**

Copy the complete authoritative `AGENTS.md` content to `CLAUDE.md`, then run:

```bash
cmp AGENTS.md CLAUDE.md
```

Expected: exit 0.

- [ ] **Step 3: Rewrite complete old paths**

Generate a complete old-to-new mapping from Tasks 2–4 and replace exact path
strings throughout tracked repository text. Do not perform broad basename-only
replacement.

- [ ] **Step 4: Verify relative Markdown links**

For every modified Markdown file, resolve relative links from the file's new
parent directory. Update links whose target moved or whose relative depth
changed.

- [ ] **Step 5: Scan for stale folder references**

Run:

```bash
rg -n 'docs/(archive|audio-debug|performance|prompts|rewind|s3k-zones|s3k|testing|trace)/' \
  . --glob '*.md' --glob '*.txt' --glob '*.java' --glob '*.lua'
```

Expected: no instructional link or current path remains. Historical statements
in the approved design and implementation plan may name former paths.

- [ ] **Step 6: Report concurrently owned stale references**

If any result belongs to a file deferred by the preflight, record its exact
path and line. Do not stage that file.

### Task 6: Verify and commit the cleanup

**Files:**
- Stage: only the completed documentation cleanup.
- Exclude: every unrelated concurrent change.

**Interfaces:**
- Consumes: Completed migration and reference rewrites.
- Produces: Policy-compliant commits with no unclassified or untracked documentation assets.

- [ ] **Step 1: Verify the final directory contract**

Run:

```bash
test "$(find docs -maxdepth 1 -type f -name '*.md' -printf '%f\n')" = "README.md"
for path in archive audio-debug performance prompts rewind s3k s3k-zones testing trace
do
  test ! -e "docs/$path"
done
```

Expected: all checks pass.

- [ ] **Step 2: Verify tracking and content hygiene**

Run:

```bash
cmp AGENTS.md CLAUDE.md
git diff --check
find docs -type f -name '*.md' -empty -print
git status --short --untracked-files=all -- docs
```

Expected: mirrored guidance, no whitespace errors, no empty Markdown, and no
untracked documentation artifact within the migration scope.

- [ ] **Step 3: Verify scope before staging**

Review:

```bash
git diff --stat
git status --short --untracked-files=all
```

Confirm every migration file is accounted for and every unrelated concurrent
file remains excluded.

- [ ] **Step 4: Stage only the migration**

Stage explicit destination trees, moved-source deletions, indexes, and both
agent-guidance files. Never use `git add -A` in the shared dirty checkout.

- [ ] **Step 5: Verify the staged scope**

Run:

```bash
git diff --cached --check
git diff --cached --stat
git status --short --untracked-files=all
```

Expected: the index contains only the documentation migration. If any deferred
file prevents the final directory contract, do not claim or commit a complete
migration.

- [ ] **Step 6: Commit**

Use:

```bash
git commit -m "docs: organize repository documentation" \
  -m "Changelog: n/a: documentation organization only" \
  -m "Guide: updated" \
  -m "Known-Discrepancies: updated" \
  -m "S3K-Known-Discrepancies: updated" \
  -m "Agent-Docs: updated" \
  -m "Configuration-Docs: n/a" \
  -m "Skills: n/a"
```

The `updated` trailers require both moved discrepancy ledgers, guide changes,
and both agent-guidance files to be staged in the same commit.

- [ ] **Step 7: Run a post-commit audit**

Repeat Steps 1–2 against `HEAD`, confirm the migration commit contains no
source/test implementation changes, and list all unrelated working-tree
changes that remain.
