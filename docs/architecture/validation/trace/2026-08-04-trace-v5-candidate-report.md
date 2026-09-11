# Trace v5 phase-D candidate report

## Capture and candidate identity

The phase-D batch was captured serially from the frozen recorder identity in
`2026-08-04-trace-v5-replacement-freeze.md`. All 36 matrix rows passed,
including the 67-segment S3K Knuckles super-emerald run. The assembled
candidate contains 981 files and its deterministic inventory aggregate is
`04851c0a146eeb101a0ce0d76c78ba9c861a4eb3d6c9ff50612c84112d868790` after
deterministic `gzip -9 -n` publication compression and the standalone S3K
special-stage/bonus publication mappings.

The strict v5 validator passed. The native no-gate suite passed
`468` tests with zero failures; the focused native busy-module append test is
part of that result. The Python tooling suite passed all `42` tests.

The candidate is now the installed v5 evidence set. The publication transaction
and its exact archive/deletion/rename actions are recorded in
`2026-08-04-trace-v5-publication-manifest.md`; no legacy compatibility reader
is retained.

## Predecessor comparison

The installed root is intentionally a legacy predecessor and therefore cannot
be passed to the strict v5-to-v5 comparator. The read-only comparison reports
the exact migration shape instead of adding a compatibility reader. For the
eight S1 credits fixtures, the candidate has the same 42-row field names and
the old 20-column fields were compared by the canonical map:

| Fixture | Old/new rows | Non-`v_framecount` mismatches | First non-clock mismatch |
| --- | ---: | ---: | --- |
| `credits_00_ghz1` | 535 / 535 | 0 | none |
| `credits_01_mz2` | 539 / 539 | 1,449 | row 119 `y`: `03F0` → `03EC` |
| `credits_02_syz3` | 535 / 536 | 1,290 | row 267 `y`: `05E4` → `05F4` |
| `credits_03_lz3` | 523 / 538 | 2,525 | row 4 `input`: `0008` → `0000` |
| `credits_04_slz3` | 538 / 540 | 963 | row 387 `x`: `106B` → `107B` |
| `credits_05_sbz1` | 539 / 542 | 2,159 | row 37 `input`: `0004` → `0000` |
| `credits_06_sbz2` | 535 / 535 | 0 | none |
| `credits_07_ghz1b` | 537 / 538 | 256 | row 501 `x`: `1D7B` → `1D77` |

All eight predecessors record `v_framecount=0000` for every row; the native
writer records the ROM's incremented `$FE04` value. The auxiliary streams also
retain their event evidence and disclose, rather than hide, the changed event
counts. The two independent native all-eight captures are decompressed-byte
identical for every physics and auxiliary stream.

The eight candidate replay classes are seven green and one classified red:
LZ3's first error is frame 156, `player_animation_id` expected `00` and engine
actual `0F` (15 animation-only errors). The native writer reads the ROM's
`$D01C` animation byte directly; this is an engine-side parity discrepancy, not
a recorder normalization opportunity. The candidate report keeps the measured
value and does not weaken the comparator.

## S3K timing boundary

Fresh complete-run replay reaches the same hardware-timing contract frontiers
already recorded before this capture. The compiler rejects held-row POST
admission when the recorded row is VBLANK-only: AIZ raw frame 6351/module 16,
HCZ 31361/module 82, CNZ 39940/module 152, ICZ 25280/module 178, LBZ 46114/
module 215, MHZ 28017/module 256, and MGZ 39274/module 121. The standalone MGZ
replay also retains its earlier direct-completion mismatch. No candidate data
is altered to make these rows executable; the hardware-timing authority still
admits only production-submitted work.

## Publication boundary

The candidate's eight canonical S1 credits directories are preserved as
predecessor evidence during publication. Legacy predecessor bytes are not a
supported runtime format and are never loaded by the v5 parser; the archived
copies exist only to satisfy the evidence-retention requirement and to make
the migration reversible for review.
