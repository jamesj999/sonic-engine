# Architecture Document Hierarchy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate OpenGGF's designs, implementation plans, research, and validation records beneath `docs/architecture` and prevent agent workflows from recreating tool-named document trees.

**Architecture:** Preserve the four existing artifact categories while making the repository, rather than Superpowers, their owner. Move files with Git-aware operations, classify the older mixed `docs/plans` contents by purpose, rewrite documentation references, and encode the canonical placement policy in both mirrored agent-guidance files.

**Tech Stack:** Markdown, Git, POSIX shell, `rg`, `find`, Maven repository policy hooks.

## Global Constraints

- Canonical directories are `docs/architecture/designs`, `docs/architecture/plans`, `docs/architecture/research`, and `docs/architecture/validation`.
- Existing architecture reference documents remain directly under `docs/architecture`.
- Never create or retain `docs/superpowers` or the top-level `docs/plans`.
- Superpowers skill names may remain where they identify a workflow; only its repository document paths are prohibited.
- Keep `AGENTS.md` and `CLAUDE.md` identical and stage them together.
- Include the four untracked 2026-07-25 design and plan artifacts in the migration.
- Do not modify test files or source files being edited by the concurrent agent.
- Preserve unrelated working-tree changes and stage only files in this migration.

---

### Task 1: Establish the canonical taxonomy and agent policy

**Files:**
- Create: `docs/architecture/README.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: The approved hierarchy in `docs/architecture/designs/2026-07-26-architecture-document-hierarchy-design.md`.
- Produces: Human and agent-facing rules defining the only valid locations for architecture artifacts.

- [ ] **Step 1: Create the architecture index**

Create `docs/architecture/README.md` with:

```markdown
# Architecture documentation

This directory owns OpenGGF's architectural reference material and dated
engineering artifacts.

## Current architecture

The Markdown files directly in this directory describe the current engine and
its architectural policies.

## Dated artifacts

- [Designs](designs/) contain approved designs, specifications, and
  architectural decisions.
- [Plans](plans/) contain implementation plans, delivery plans, work ledgers,
  and execution diaries.
- [Research](research/) contains investigations and supporting research that
  has not become a design.
- [Validation](validation/) contains validation reports, baselines,
  checklists, and recorded results.

These project paths override the default output paths prescribed by agent
skills. Do not create tool-named document directories such as
`docs/superpowers`, and do not recreate the legacy top-level `docs/plans`
directory.
```

- [ ] **Step 2: Add the hard placement rule to `AGENTS.md`**

Insert this rule in the documentation/commit-policy guidance:

```markdown
- **Architecture artifact placement.** Designs, specifications, implementation
  plans, research notes, validation reports, and similar agent-generated
  engineering artifacts live under the matching `docs/architecture/`
  subdirectory described in
  [docs/architecture/README.md](docs/architecture/README.md). These repository
  paths override skill defaults. Never create `docs/superpowers` or recreate
  the top-level `docs/plans` directory. Before finishing, stage every relevant
  artifact created for the task; do not leave designs or plans untracked.
```

- [ ] **Step 3: Mirror the rule into `CLAUDE.md`**

Copy the complete resulting `AGENTS.md` content to `CLAUDE.md`, preserving the
repository's requirement that the files remain identical.

- [ ] **Step 4: Verify the policy files**

Run:

```bash
cmp AGENTS.md CLAUDE.md
rg -n 'Architecture artifact placement|docs/architecture/README.md' AGENTS.md CLAUDE.md
```

Expected: `cmp` exits 0 and both files contain the new rule.

### Task 2: Move the category-owned Superpowers artifacts

**Files:**
- Move: `docs/architecture/designs/*` → `docs/architecture/designs/`
- Move: `docs/architecture/plans/*` → `docs/architecture/plans/`
- Move: `docs/architecture/research/*` → `docs/architecture/research/`
- Move: `docs/architecture/validation/*` → `docs/architecture/validation/`

**Interfaces:**
- Consumes: The canonical directory definitions from Task 1.
- Produces: All 349 legacy Superpowers artifacts in repository-owned locations, including the four untracked files.

- [ ] **Step 1: Check for destination collisions**

Run:

```bash
for pair in \
  "specs designs" \
  "plans plans" \
  "research research" \
  "validation validation"
do
  set -- $pair
  comm -12 \
    <(find "docs/superpowers/$1" -maxdepth 1 -type f -printf '%f\n' | sort) \
    <(find "docs/architecture/$2" -maxdepth 1 -type f -printf '%f\n' | sort)
done
```

Expected: no output. If the newly written hierarchy plan appears as a
collision, exclude only that exact destination-owned file; never overwrite it.

- [ ] **Step 2: Move tracked files with Git-aware operations**

For each category, move every path reported by `git ls-files` from its legacy
directory to the matching canonical directory. Use `git mv` on tracked files
so history remains recognizable:

```bash
git ls-files -z docs/superpowers/specs |
  xargs -0 -I{} git mv "{}" docs/architecture/designs/
git ls-files -z docs/superpowers/plans |
  xargs -0 -I{} git mv "{}" docs/architecture/plans/
git ls-files -z docs/superpowers/research |
  xargs -0 -I{} git mv "{}" docs/architecture/research/
git ls-files -z docs/superpowers/validation |
  xargs -0 -I{} git mv "{}" docs/architecture/validation/
```

- [ ] **Step 3: Move the four untracked artifacts**

Move these exact untracked files with ordinary `mv`; they will be staged from
their canonical destinations in Task 5:

```bash
mv docs/architecture/designs/2026-07-25-debug-trace-test-audit-design.md docs/architecture/designs/
mv docs/architecture/designs/2026-07-25-s1-trace-profile-hygiene-design.md docs/architecture/designs/
mv docs/architecture/plans/2026-07-25-debug-trace-test-audit.md docs/architecture/plans/
mv docs/architecture/plans/2026-07-25-s1-trace-profile-hygiene.md docs/architecture/plans/
```

Confirm afterward that they appear at:

```text
docs/architecture/designs/2026-07-25-debug-trace-test-audit-design.md
docs/architecture/designs/2026-07-25-s1-trace-profile-hygiene-design.md
docs/architecture/plans/2026-07-25-debug-trace-test-audit.md
docs/architecture/plans/2026-07-25-s1-trace-profile-hygiene.md
```

- [ ] **Step 4: Remove empty legacy directories**

Run:

```bash
rmdir docs/superpowers/specs docs/superpowers/plans
rmdir docs/superpowers/research docs/superpowers/validation
rmdir docs/superpowers
```

Expected: all commands succeed because every artifact was moved.

- [ ] **Step 5: Verify artifact counts**

Run:

```bash
find docs/architecture/designs -maxdepth 1 -type f | wc -l
find docs/architecture/plans -maxdepth 1 -type f | wc -l
find docs/architecture/research -maxdepth 1 -type f | wc -l
find docs/architecture/validation -maxdepth 1 -type f | wc -l
```

Expected before the legacy `docs/plans` migration: at least 130 designs
(129 legacy specs plus this design), 207 plans (206 legacy plans plus this
plan), 8 research files, and 6 validation files.

### Task 3: Classify and move the legacy `docs/plans` artifacts

**Files:**
- Move: every `*-design.md` in `docs/plans/` → `docs/architecture/designs/`
- Move: the four benchmark baseline/result files → `docs/architecture/validation/`
- Move: all remaining files in `docs/plans/` → `docs/architecture/plans/`

**Interfaces:**
- Consumes: The purpose-based taxonomy from Task 1.
- Produces: A single classified hierarchy with no top-level `docs/plans`.

- [ ] **Step 1: Check all legacy files against the classification**

Run:

```bash
find docs/plans -maxdepth 1 -type f -printf '%f\n' | sort
```

Classify filenames ending in `-design.md` as designs. Classify these exact
validation records as validation:

```text
audio-memory-benchmark-baseline-2026-04-17.md
audio-memory-benchmark-step-results-2026-04-17.md
audio-top-wins-baseline-2026-04-17.md
audio-top-wins-step-results-2026-04-17.md
```

All remaining files are implementation plans, delivery documents, ledgers, or
execution diaries and therefore belong in `docs/architecture/plans`.

- [ ] **Step 2: Check for filename collisions**

Compare each classification group against its destination with `comm -12`.

Expected: no output. Stop rather than overwrite if a collision is discovered.

- [ ] **Step 3: Move designs and validation records**

Run:

```bash
git mv docs/plans/*-design.md docs/architecture/designs/
git mv docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md docs/architecture/validation/
git mv docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md docs/architecture/validation/
git mv docs/architecture/validation/audio-top-wins-baseline-2026-04-17.md docs/architecture/validation/
git mv docs/architecture/validation/audio-top-wins-step-results-2026-04-17.md docs/architecture/validation/
```

- [ ] **Step 4: Move the remaining plan artifacts**

Run:

```bash
git mv docs/plans/* docs/architecture/plans/
rmdir docs/plans
```

Expected: the move and directory removal succeed.

### Task 4: Rewrite documentation references

**Files:**
- Modify: Markdown and text documentation containing `docs/superpowers/...` or top-level `docs/plans/...` references.
- Exclude: `src/**`, test files, and concurrent agent-owned files.

**Interfaces:**
- Consumes: Final path mapping from Tasks 2 and 3.
- Produces: Documentation links that resolve to the canonical hierarchy.

- [ ] **Step 1: Rewrite category-owned path prefixes**

Apply these exact replacements to documentation and guidance files:

```text
docs/architecture/designs/      → docs/architecture/designs/
docs/architecture/plans/      → docs/architecture/plans/
docs/architecture/research/   → docs/architecture/research/
docs/architecture/validation/ → docs/architecture/validation/
```

Do not replace `superpowers:<skill-name>` references; those name valid skills.

- [ ] **Step 2: Rewrite legacy `docs/plans` references by destination**

For each moved legacy filename, replace its old complete path with the complete
new path determined in Task 3. Use complete-path replacement because the mixed
source directory maps to three destinations.

- [ ] **Step 3: Scan documentation for stale paths**

Run:

```bash
rg -n 'docs/superpowers(?:/|`)|docs/plans/' \
  AGENTS.md CLAUDE.md README.md CHANGELOG.md docs tools \
  --glob '*.md' --glob '*.txt'
```

Expected: only deliberate historical explanations in the new design and index
may mention the prohibited directory names. No link or instruction may point
to either legacy tree.

- [ ] **Step 4: Report concurrent-source references without editing them**

Run:

```bash
rg -n 'docs/superpowers/|docs/plans/' src
```

Record any results in the final handoff. Do not modify files owned by the other
agent during this migration.

### Task 5: Verify and commit the unified hierarchy

**Files:**
- Stage: all moves and documentation changes from Tasks 1–4.
- Exclude: all unrelated working-tree changes.

**Interfaces:**
- Consumes: Completed hierarchy and updated references.
- Produces: One reviewable migration commit with all architecture artifacts tracked.

- [ ] **Step 1: Verify forbidden directories are absent**

Run:

```bash
test ! -e docs/superpowers
test ! -e docs/plans
```

Expected: both commands exit 0.

- [ ] **Step 2: Verify Markdown hygiene and agent-doc mirroring**

Run:

```bash
cmp AGENTS.md CLAUDE.md
git diff --check
find docs/architecture -type f -name '*.md' -empty -print
```

Expected: `cmp` and `git diff --check` exit 0, and `find` prints nothing.

- [ ] **Step 3: Verify relevant artifacts are tracked or staged**

Run:

```bash
git status --short --untracked-files=all \
  AGENTS.md CLAUDE.md docs/architecture docs/superpowers docs/plans
```

Expected: every architecture artifact is represented by a rename, addition, or
modification; none of the four migrated 2026-07-25 artifacts remains untracked.

- [ ] **Step 4: Stage only the migration**

Run:

```bash
git add AGENTS.md CLAUDE.md docs/architecture
git add -u docs/superpowers docs/plans
git diff --cached --stat
git status --short
```

Inspect both outputs. The index must contain only the hierarchy migration and
guidance changes. Unrelated source, test, trace, IDE, tool, and ROM/mod changes
must remain unstaged.

- [ ] **Step 5: Run the repository commit policy**

Run:

```bash
.githooks/run-policy
```

Expected: PASS. Because `AGENTS.md` and `CLAUDE.md` are staged together, use
`Agent-Docs: updated` in the commit trailers.

- [ ] **Step 6: Commit the migration**

Run:

```bash
git commit -m "docs: unify architecture artifact hierarchy" \
  -m "Changelog: n/a: documentation hierarchy migration only" \
  -m "Guide: n/a" \
  -m "Known-Discrepancies: n/a" \
  -m "S3K-Known-Discrepancies: n/a" \
  -m "Agent-Docs: updated" \
  -m "Configuration-Docs: n/a" \
  -m "Skills: n/a"
```

- [ ] **Step 7: Verify the committed result**

Run:

```bash
git show --stat --oneline --summary HEAD
git status --short
test ! -e docs/superpowers
test ! -e docs/plans
```

Expected: the commit contains the migration and both agent guidance files;
unrelated concurrent changes remain in the working tree; both legacy
directories remain absent.
