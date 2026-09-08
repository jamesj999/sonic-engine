---
name: orchestrate-blueprint-feature
description: Turn a broad OpenGGF feature blueprint into coordinated delivery when independent workstreams need shared design and integration.
---

# Blueprint feature delivery

Identify the requested outcome, acceptance criteria, existing owners, and the
few decisions that block implementation. Reuse an adequate supplied design or
plan. For a small change, work directly; this skill does not require a team or
a separate artifact for every phase.

For substantial work, keep a concise plan under the appropriate
`docs/architecture/` subdirectory. Capture boundaries, dependencies, risky
assumptions, file ownership, and verification. Choose existing runtime owners
and the smallest accurate per-game extension rather than inventing frameworks
for a hypothetical future migration.

Delegate only concrete, independent work that can run alongside useful local
work. Use available agent capacity and inherit session model preferences;
no particular model, sidecar, or number of reviewers is required. Give workers
their objective, relevant context, owned files/worktree, dependencies, and
verification command. Avoid overlapping edits; sequence changes to shared
contracts before their consumers. Concurrent build/test work needs separate
worktrees with outputs in each `target/`.

For object/runtime migrations, inventory existing callers and guard coverage
before changing the shared contract. Resolve ownership of control/participation,
slot lifetime, collision, native coordinates, and rewind state where affected.
For ROM parity work, use the applicable domain skill; trace failures route to
`trace-replay-bug-fixing`, and queue loading to `plc-system`/`s3k-plc-system`.
These are conditional references, not mandatory reading for every blueprint.

Implement and verify against acceptance criteria. Choose tests that establish
behavior and meaningful regression protection; documentation or reversible
low-impact edits do not need invented tests. Independent review is useful for
high-risk boundaries and integration, without requiring repeated “green”
self-review rituals. Fix blocking findings and make unresolved scope explicit.

Follow root `AGENTS.md` for documentation, branch safety, baseline comparisons,
merge, push, and cleanup. Use the current main-workspace branch as the integration
base unless the user specifies otherwise. Existing authorization persists;
this skill adds no final approval gate. Report the delivered behavior, important
design decisions, test evidence, reconciled conflicts, and remaining limitations.
