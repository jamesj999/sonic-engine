# 0.6 release-gate remediation

Base: `1f61f5746`. Brainpipe P6 coordinates CS integration and BG skip-policy work.
The [blocker assessment](../audits/2026-09-06-release-blockers.md) and
[release contract](../../status/trace-scope-release-6.md) define scope.

- [x] BG: extract explicit optional-skip policy with source-backed classifications,
  behavioral tests, and stale ROADMAP audio-prose correction. Required GL and ROM
  checks still execute; missing required evidence fails.
- [x] CS: add a bounded comparison of freshly produced baseline/candidate release
  trace evidence. Pin the baseline revision, retain direct Maven, require completed
  runs and compare identities, failures and existing structured trace reports.
  Unchanged known-red evidence may pass. Changed red evidence requires review;
  counts alone never certify no regression. Missing/skipped required tests, absent
  reports, crashes and errors fail. This does not revive rolling checkpoints or
  add any trace-to-gameplay authority.
- [x] CS: wire release workflow to both checks; preserve separate structural JVM,
  fixture preflight, release-only publication and platform artifact validation.
  Add behavioral negative controls and update focused workflow guards.
- [x] Both: focused checks and full ordinary, guard and trace profiles completed
  on isolated base/development trees; integrated `develop` verified again at
  `1514f4c9c`. Exact outcomes/skips and package smoke checks are in the
  [verification record](../validation/2026-09-06-release-gates.md).
  Push only `develop`, then remove fully accounted task trees/branches; those
  final operations are reported after completion in the delivery result.
- [x] Record fresh evidence and remaining human gameplay/listening/platform limits
  in release documentation. Release publication requires actual sign-off; automated
  remediation cannot stand in for human approval.
