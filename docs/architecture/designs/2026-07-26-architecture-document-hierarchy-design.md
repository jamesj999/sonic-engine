# Architecture Document Hierarchy Design

## Purpose

OpenGGF's architecture documents currently span two overlapping trees:
`docs/superpowers`, which reflects a tool's default output path, and `docs/plans`,
which predates that convention. The repository should own its document taxonomy
instead of exposing the name or directory defaults of whichever agent workflow
created an artifact.

This migration establishes `docs/architecture` as the single home for design,
planning, research, and validation documents.

## Canonical hierarchy

The canonical directories are:

- `docs/architecture/designs` for approved designs, specifications, and
  architectural decisions;
- `docs/architecture/plans` for implementation plans, delivery plans, work
  ledgers, and execution diaries;
- `docs/architecture/research` for investigations and supporting research that
  has not become a design;
- `docs/architecture/validation` for validation reports, baselines, checklists,
  and recorded results.

The existing architecture reference documents remain directly under
`docs/architecture`. They describe the current system rather than a dated
proposal or execution artifact.

## Migration

The existing trees map as follows:

| Existing location | Canonical location |
|---|---|
| `docs/superpowers/specs` | `docs/architecture/designs` |
| `docs/superpowers/plans` | `docs/architecture/plans` |
| `docs/superpowers/research` | `docs/architecture/research` |
| `docs/superpowers/validation` | `docs/architecture/validation` |

Files in the legacy `docs/plans` directory are classified by purpose rather than
moved as an undifferentiated group:

- design and specification documents move to `designs`;
- implementation plans, delivery documents, ledgers, and diaries move to
  `plans`;
- benchmark baselines and result records move to `validation`;
- exploratory material moves to `research`.

Git-aware moves preserve file history. The four currently untracked
design-and-plan artifacts under `docs/superpowers` are included in the
migration and committed with the resulting hierarchy.

## References and concurrent work

Documentation and agent-guidance references are rewritten to their canonical
paths. The migration does not modify test files or concurrently edited source
files owned by the other active agent. Any stale references found exclusively
in those files are reported so they can be reconciled without mixing the two
agents' work.

References to Superpowers skill names remain valid when they identify a workflow
or skill. The prohibition applies to repository paths and document ownership:
no document is stored under a directory named after Superpowers.

## Forward policy

`AGENTS.md` and its required mirror, `CLAUDE.md`, receive a hard documentation
placement rule:

- agent skills may prescribe a default output location, but OpenGGF's canonical
  paths override that default;
- agents must write designs, plans, research, and validation artifacts to the
  matching `docs/architecture` subdirectory;
- agents must never create or recreate `docs/superpowers`;
- agents must classify relevant artifacts before staging and must not leave
  generated architecture documents untracked.

`docs/architecture/README.md` documents the same taxonomy for human
contributors and links to each category.

## Verification

The migration is complete when:

1. all tracked and relevant untracked artifacts from both legacy trees exist in
   the canonical hierarchy;
2. neither `docs/superpowers` nor the top-level `docs/plans` directory remains;
3. documentation and agent guidance contain no stale legacy-path references;
4. `AGENTS.md` and `CLAUDE.md` remain identical;
5. Git reports all migrated architecture artifacts as tracked or staged;
6. unrelated concurrent changes remain unstaged and unmodified by this work.
