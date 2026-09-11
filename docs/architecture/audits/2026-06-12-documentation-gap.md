# Documentation Gap Audit - 2026-06-12

## Scope

This audit checked high-signal player, contributor, agent, roadmap, bug-ledger, and trace-frontier
documentation after the S3K route work moved beyond early AIZ/HCZ bring-up.

Files reviewed:

- `README.md`
- `ROADMAP.md`
- `CONTRIBUTING.md`
- `AGENTS.md`
- `AGENTS_S3K.md`
- `docs/guide/playing/game-status.md`
- `docs/guide/contributing/adding-zones.md`
- `docs/status/known-bugs.md`
- `docs/status/s3k-known-bugs.md`
- `docs/S3K_KNOWN_DISCREPANCIES.md`
- `docs/status/s3k-bug-list.md`
- `docs/status/trace-frontier-log.md`
- `docs/agent-workflow/runbooks/runbook-s3k-zone-feature.md`

## Updated In This Pass

- `README.md` no longer describes S3K as mostly AIZ/early-HCZ coverage. It now names the opened
  AIZ/HCZ/CNZ/MGZ/ICZ/MHZ/LBZ route slices and calls FBZ/later zones the main content frontier.
- `ROADMAP.md` now frames v0.6 as S3K playable-slice parity plus release readiness, with
  complete-run trace frontiers as the parity ledger.
- `docs/guide/playing/game-status.md` now describes S3K as near-complete vertical-slice coverage
  rather than early-game expansion, and updates the editor capability summary.
- `CONTRIBUTING.md`, `AGENTS.md`, `AGENTS_S3K.md`, and
  `docs/guide/contributing/adding-zones.md` now direct contributors toward route blockers,
  complete-run trace frontiers, and release gates rather than broad first-pass zone bring-up.
- `docs/status/known-bugs.md` no longer claims there is no committed S3K BK2-derived fixture; the open
  issue is now the remaining pre-v3 fallback and release trace-gate clarity.
- `docs/status/s3k-known-bugs.md` now carries a 2026-06-12 note that old dedicated-trace frame entries
  are historical context and that `docs/status/trace-frontier-log.md` is the current frontier source.
- `docs/status/s3k-bug-list.md` is explicitly marked as a historical 2026-03-25 working list.

## Current S3K Documentation Position

The documentation should now consistently present S3K as:

- the main current delivery focus;
- near-complete across opened vertical slices rather than merely early-game;
- not yet a polished full-game route;
- driven by complete-run trace frontiers, route blockers, release gates, and visual validation;
- still carrying the largest content gap in FBZ and later zones, plus active parity blockers in
  sidekick CPU, object lifetime/order, ring/hurt handoffs, boss/event transitions, and rewind state.

## Remaining Cleanup

- `docs/status/s3k-known-bugs.md` is still too large and mixes fixed historical trace notes with open
  blockers. It should be split later into:
  - current open S3K blockers;
  - fixed trace investigations;
  - archived dedicated-trace notes superseded by complete-run traces.
- `docs/status/trace-frontier-log.md` is the right canonical ledger, but it is hard to skim. A generated or
  manually maintained "current frontier table" near the top would make release review easier.
- Older plan/spec files under `docs/architecture/plans/` and
  `docs/architecture/designs/` remain historical by design. They were not edited because changing
  old implementation plans can erase useful context.
- `S3K_OBJECT_CHECKLIST.md` may lag behind current object status in places. Because it is a large
  generated/curated checklist, it should be reconciled with registry/test evidence in a separate
  pass rather than guessed from docs.
