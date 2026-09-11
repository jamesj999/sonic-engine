# Trace v5 replacement freeze — replacement capture boundary

## Frozen identity

This freeze supersedes the pre-capture artifact bound to the earlier
development baseline. It authorizes scratch candidate work only; it does not
authorize installation or deletion of committed fixtures.

| Identity | Value |
| --- | --- |
| Reviewed source commit | `93f0abb81dc216f5ab6fee998db75e9eb4410379` |
| Development baseline | `3573af57be947284a1f8398c7b4b4e05a8b12f14` |
| Source diff base commit | `36be0aa44e4e1db9d2d586fff984e52ffd4fe053` |
| Source diff SHA-256 | `127f9ef3cd8269dac419625a737ec4d56b7e9b66e0e57a5262208e008f33ff05` |
| Diff definition | `git diff --full-index --binary 36be0aa44e4e1db9d2d586fff984e52ffd4fe053..93f0abb81dc216f5ab6fee998db75e9eb4410379 \| sha256sum` |
| Native harness | `tools/bizhawk-headless/bin/Release/BizHawk.Headless.Gpgx.exe` |
| Native harness SHA-256 | `1320f95be8f8210a1a1b6f5ef5c6dcff9a5abeca91507e397f7ad1b38b106bb0` |
| Native harness size | `359936` bytes |
| Native test SHA-256 | `9c62ad733ad08a8db875093316a5abb8588b8b6b636d18fa8d6f6ad7da591fc1` |
| Native test size | `620032` bytes |

The native harness was rebuilt from this replacement source boundary after the
first 35 matrix rows captured successfully but the 67-segment Knuckles row
exposed a valid same-frame Kos module retirement plus append whose shifted head
remained busy. The recorder now validates the shifted prefix, permits that
ROM-proven busy state, retires one completion, and reconciles the new tail; its
focused contract covers that exact ROM ordering. The build returned exit 0;
Mono reported only the known .NET Framework 4.8 toolset warning.

## Reconciled upstream work

The isolated branch incorporates the latest `origin/develop` visual-run wins
before freezing. The merge conflicts in `CHANGELOG.md` and the launcher test
were reconciled by retaining both trace-v5 and visual-run records, migrating
positive visual tests to generated strict-v5 fixtures, and keeping the
production visual admission implementation unchanged.

Strict catalog discovery now skips rejected predecessor traces rather than
introducing a legacy compatibility path. The catalog contract itself runs on
a generated v5 run. The retained predecessor runs remain available for the
Task 9 regeneration matrix.

## Verification

Focused Java selection (v5 parsing, recorder contracts, divergence reports,
catalogs, dynamic art, timing, special-stage row publication, replay clocks,
and the merged visual launcher) passed:

```text
Tests run: 265, Failures: 0, Errors: 0, Skipped: 7
```

The seven skips are the two intentional recorder-contract skips, three
intentional parser fixture skips, and two ROM-dependent timing skips.

The complete Python testing directory passed:

```text
python3 -m unittest discover -s tools/testing -p 'test_*.py'
Ran 42 tests — OK
```

The native no-gate suite passed with exit 0 after the replacement rebuild. With ROM
variables absent it reported only expected ROM-dependent skips. The real-ROM
credits gate was run against the verified S1 REV01 ROM and passed:

```text
PASS S1 credits captures twice with deterministic logical evidence
```

This exercises two independent all-eight captures, deterministic candidate
comparison, raw-host sidecar equality, and first-divergence binding.

The installed fixture inventory passed both filesystem and Git-index checks.
`git diff --name-only -- src/test/resources/traces` remained empty throughout
the re-entry, merge, and diagnostic captures. Rows 1–35 of the first batch
passed; row 36 was preserved as a diagnostic batch after the recorder correction
and passed strict v5 validation with all 67 segment directories present. No
installed fixture has been regenerated, deleted, or modified.

The final phase-D batch was then recaptured from the frozen source identity as
one serial 36-row run. Every row passed, including the 67-segment Knuckles
super-emerald run. Its assembled scratch candidate contains 981 files (266
metadata, 266 physics, 266 auxiliary, 139 timing, seven manifests, and 37
static inputs) and has inventory aggregate
`04851c0a146eeb101a0ce0d76c78ba9c861a4eb3d6c9ff50612c84112d868790` after
deterministic `gzip -9 -n` publication compression and the standalone S3K
special-stage/bonus publication mappings.
`validate_trace_v5.py` passed against that candidate. The two independent
movie-free credits captures are byte-identical after decompression for all
eight physics and auxiliary pairs.

The eight candidate credits replays ran through the fixture-root override:
seven are green. LZ3 exposes one existing engine-side animation discrepancy
(first error frame 156, candidate `player_animation_id=00`, engine `0F`; 15
animation-only errors). The native writer reads the ROM's `$D01C` byte directly;
the candidate is therefore retained as measured evidence rather than
normalized. S3K complete-run replay still stops at the previously frozen
hardware-timing frontiers (`unsupported-held-row-POST` and the MGZ direct
completion), before gameplay comparison; those rows are unchanged in kind from
the pre-capture baseline.

## Publication closure

The candidate is installed and validated. The exact archive/deletion/rename
transaction is recorded in
`2026-08-04-trace-v5-publication-manifest.md`. The eight canonical S1 credits
directories were archived as predecessor evidence and were not deleted; true
obsolete files are listed separately in that manifest. Capture output is no
longer pending outside the installed fixture tree.
