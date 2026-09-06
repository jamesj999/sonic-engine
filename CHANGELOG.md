# Changelog

OpenGGF keeps one changelog file per release so that release notes remain
readable and historical versions can be referenced directly.

## Release files

- [0.6 prerelease / current development snapshot](CHANGELOG.0.6.md)
- [0.5.20260411](CHANGELOG.0.5.md)
- [0.4.20260304](CHANGELOG.0.4.md)
- [0.3.20260206](CHANGELOG.0.3.md)
- [0.2.20260117](CHANGELOG.0.2.md)
- [0.1.20260110](CHANGELOG.0.1.md)
- [0.05](CHANGELOG.0.05.md)
- [0.01](CHANGELOG.0.01.md)

## 0.6 release documentation

The current snapshot makes rewind checkpoints share spent hardware-timing job
snapshots instead of re-cloning them; see the
[0.6 development history](CHANGELOG.0.6.md#06-development-history-mid-july-2026--present-newest-first).

The current snapshot includes the three games' ROM-backed Continue screens;
see the [0.6 development history](CHANGELOG.0.6.md#06-development-history-mid-july-2026--present-newest-first).

- [Release Summary](docs/changelog/v0.6-release-summary.md) — polished copy for
  the website and GitHub release page, including the measured validation status
  and known limitations. `RELEASE_NOTES_v0.6.prerelease.md` is a pointer to it.
- [Detailed 0.6 development ledger](docs/changelog/v0.6-prerelease-detailed.md) —
  engineering notes and trace-frontier history.
- [Trace frontier log](docs/status/trace-frontier-log.md) — current replay
  evidence and remaining parity work.

The 0.6 snapshot is not yet a final release: human end-to-end gameplay and
audio QA remain outstanding, and the release summary records the remaining
automated limitations. Trace replay is held to the no-regression policy in
[docs/status/trace-scope-release-6.md](docs/status/trace-scope-release-6.md)
rather than a hard all-green gate.
