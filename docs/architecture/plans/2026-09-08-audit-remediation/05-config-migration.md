# 05 — Retain legacy configuration when YAML persistence fails

Medium; low complexity; estimated 1–2 hours.

## Problem

saveConfig logs and swallows IOException, while migration unconditionally renames config.json to a backup. A failed YAML write causes the next startup to use defaults despite a recoverable legacy configuration.

## Owners and evidence

Owner: `configuration/SonicConfigurationService.java`; existing configuration migration and sparse-config tests. Reproduction: nonempty directory named config.yaml plus a legacy JSON selecting s1; first load s1, failed save/rename, second load s2.

## Implementation steps

1. Add a deterministic failure/restart regression using an isolated configuration directory. Assert legacy JSON remains usable and no migration-success message/state is claimed after persistence failure.
2. Make internal persistence success observable; migrate/rename only after successful YAML replacement. Preserve public caller compatibility where practical.
3. Preserve unique backup naming, sparse configuration, atomic writes and existing successful migration behavior.
4. Verify transient read failures, failed writes and successful reload. Never operate on the main workspace config or a generated shared config symlink.

## Acceptance

A failed migration leaves the legacy source at its original readable path; a second startup restores the same user values. Successful migration still creates usable YAML and an intact backup.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.
