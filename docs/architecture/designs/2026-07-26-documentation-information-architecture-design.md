# Documentation Information Architecture Design

## Purpose

OpenGGF's repository-owned documentation has grown through several conventions.
Although designs and plans now have canonical homes, 26 Markdown files remain
loose in `docs/`, `docs/archive` mixes unrelated artifact types, and audio
research lives in a separate debugging tree without an index.

This cleanup gives every maintained document a purpose-based home, eliminates
dumping-ground directories, and makes the intended structure discoverable to
contributors and agents.

## Classification model

`docs/architecture` owns dated engineering artifacts as well as current
architecture references. Its dated categories answer distinct questions:

| Category | Question |
|---|---|
| `designs` | What should be built? |
| `plans` | How will it be built? |
| `research` | How does the system or source material work? |
| `audits` | What state is the project currently in? |
| `validation` | Did an implementation meet its contract? |

An audit is a point-in-time assessment, inventory, review, or gap analysis.
Validation is evidence against a defined behavior or implementation contract.
This distinction is based on purpose, not filename.

Current architecture references remain directly under `docs/architecture`.

## Top-level documentation map

Create `docs/README.md` as the entry point for repository documentation. It
describes and links the maintained subject trees:

- `agent-workflow` for agent procedures and runbooks;
- `architecture` for current architecture and dated engineering artifacts;
- `assets` for presentation assets;
- `changelog` for detailed release history;
- `guide` for contributor, player, and cross-referencing guides;
- `project` for project history, roadmap, and process direction;
- `status` for active bug, discrepancy, trace-frontier, and release-state
  ledgers.

No Markdown file remains loose directly under `docs/` except `README.md`.
Topic names do not form a parallel top-level taxonomy. Performance, rewind,
testing, trace, audio, and game/zone material is classified by purpose within
architecture, guide, status, or agent-workflow.

## Loose `docs/` classification

### Agent workflow

- `AGENT_WORKFLOW_SUPPORT_OPTIONS.md` moves to `agent-workflow/`.

### Current architecture

- `SINGLETON_LIFECYCLE.md` moves to `architecture/`.

### Architecture audits

- the four ArchUnit evaluation/proposal documents;
- both documentation-gap audits;
- `opus-branch-review.md`;
- `release-architecture-review-issues.md`.

### Architecture plans

- `TRACE_REMEDIATION_PLAN.md`.

### Project history and direction

- `AI_JOURNEY.md`;
- `DEVELOPMENT_TIMELINE.md`;
- `RELEASE_READINESS_ROADMAP.md`.

### Status ledgers

- the three `BUGLIST*.md` files;
- `KNOWN_BUGS.md`;
- `KNOWN_DISCREPANCIES.md`;
- `S3K_KNOWN_BUGS.md`;
- `S3K_KNOWN_DISCREPANCIES.md`;
- `TRACE_FRONTIER_LOG.md`.

These are active ledgers, not dated audits, because contributors update them as
the current state changes.

### Game and zone references

- `AIZ-INTRO.md` moves to `architecture/s3k-zones/` as a maintained technical
  reference;
- both CNZ audit documents move to `architecture/audits/s3k-zones/`;
- `sonic2_rev01_checkpoints.md` moves to `guide/cross-referencing/`.

## Archive elimination

`docs/archive` has no continuing role. Historical value does not by itself make
a document obsolete, so useful files are classified by purpose:

### Designs

- `S3K_Level_Event_Plan.md`.

### Plans

- `CPZ_BOSS_IMPLEMENTATION_PLAN.md`;
- `EHZBossFixPlan.md`;
- `OPTIMIZATION_PLAN.md`;
- `consolidation_plan.md`.

### Research

- `AIZ_INTRO_SCROLL_INVESTIGATION.md`;
- `result_screen_bug_troubleshooting.md`.

### Audio research

- `YM2612_DISCREPANCIES.md`;
- `signpost_sfx_debug_diary.md`;
- the untracked `YM2612.java.example.txt`;
- the untracked `bizhawk_signpost_debug.lua`.

### Audits

- `collision_docs_consolidation_notes.md`;
- `player-sprites-progress.md`.

### Validation

- `BOSS_VALIDATION_SUMMARY.md`;
- `STEP3_CONSTANTS_VALIDATION.md`.

### Superseded duplicates

- `archive/KNOWN_DISCREPANCIES.md` is deleted because the maintained status
  ledger contains the original material and substantial later additions;
- `archive/sonic2_rev01_checkpoints.md` is deleted because the maintained copy
  differs by only a small later correction.

After classification and duplicate removal, `docs/archive` is removed.

## Audio research

The existing `docs/audio-debug` tree moves to
`docs/architecture/research/audio`. Its recordings, saved reference pages,
images, raw captures, scripts, and code excerpts are supporting research
artifacts rather than runtime assets.

Add `docs/architecture/research/audio/README.md` describing the collection and
distinguishing external reference material, engine captures, analysis images,
and investigation notes. The migrated archive audio documents and assets join
this collection. The old `docs/audio-debug` path is removed.

## Existing topic-folder classification

The cleanup also eliminates topic folders whose contents fit the purpose-based
taxonomy.

### Performance

All four `docs/performance` documents are measured baselines or result reports.
They move to `docs/architecture/validation/performance`.

### Rewind

- `real-gaps.md` is an actively maintained gap ledger and moves to
  `docs/status/rewind-gaps.md`;
- `real-gaps-fixplan.md` moves to `docs/architecture/plans`.

### Testing

- `headless-testing.md` is current contributor documentation and moves to
  `docs/guide/contributing`;
- `debug-trace-test-audit.md` moves to `docs/architecture/audits/testing`;
- its TSV inventory and exclusion-list evidence move beside it as supporting
  audit assets.

### Trace

`s2-ss-init-timeline.md` is an investigation record and moves to
`docs/architecture/research/trace`.

### S3K general research

`s3k/game-mode-constants.md` is ROM and runtime research and moves to
`docs/architecture/research/s3k`.

### S3K zones

The `docs/s3k-zones` tree is split by purpose:

- zone `*-analysis.md` documents move to
  `docs/architecture/research/s3k-zones`;
- post-workstream baselines and trace-divergence baselines, including their
  supporting `.d` evidence, move to
  `docs/architecture/validation/s3k-zones`;
- regression notes and point-in-time priority/constant audits move to
  `docs/architecture/audits/s3k-zones`;
- the current AIZ intro technical reference remains a maintained architecture
  reference under `docs/architecture/s3k-zones`.

This removes `docs/s3k` and `docs/s3k-zones` without losing the useful zone
grouping inside each artifact category.

### Prompts

Reusable prompts are workflow support rather than architecture artifacts.
`docs/prompts` moves to `docs/agent-workflow/prompts`.

After migration, the eliminated topic trees are `audio-debug`, `performance`,
`prompts`, `rewind`, `s3k`, `s3k-zones`, `testing`, and `trace`.

## Reference integrity

All tracked references to moved files are rewritten, including:

- Markdown links and path examples;
- agent guidance;
- changelog and status references;
- source and test comments that name documentation paths, provided the file is
  not concurrently owned by another agent.

Historical prose may describe a former layout when that fact matters, but it
must not link readers to a path that no longer exists.

If a concurrently edited file contains a stale path, the migration records the
exact file and line for follow-up instead of staging another agent's changes.

## Forward policy

`AGENTS.md` and `CLAUDE.md` remain identical and point agents to
`docs/README.md`. Their documentation-placement rule is expanded:

- select the semantic category before creating an artifact;
- use `architecture/audits` for point-in-time assessments;
- use `architecture/research/audio` for audio investigations and supporting
  assets;
- do not create loose files in `docs/`;
- do not create generic `archive`, `misc`, `notes`, or tool-named dumping
  grounds;
- stage relevant documentation assets before finishing a task.

## Verification

The cleanup is complete when:

1. `docs/README.md` exists and links every maintained documentation category;
2. no Markdown file except `README.md` remains directly under `docs/`;
3. `docs/archive` and `docs/audio-debug` no longer exist;
4. every retained archive item exists in its classified destination and the
   two verified duplicates are removed;
5. the two previously untracked audio-debug artifacts are tracked in the audio
   research collection;
6. tracked references do not point to moved or deleted paths;
7. `AGENTS.md` and `CLAUDE.md` remain identical;
8. the eliminated topic folders listed above no longer exist;
9. unrelated concurrent changes remain unstaged and unmodified.
