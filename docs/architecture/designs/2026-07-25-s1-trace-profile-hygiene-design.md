# S1 Trace Profile Hygiene Design

## Goal

Restore a meaningful green S1 trace profile by fixing terminal-state expectation
ownership and separating active MZ1 regression guards from known-red diagnostic probes.

## Terminal expectation

`TracePlaybackProfile` must not infer a movie's terminal mode from the game. Add an optional
typed `expected_movie_end_mode` field to `run_manifest.json`. The only wire values are
lowercase `level` and `title_screen`; unknown values fail closed. Absence maps internally
to `UNSPECIFIED` for S1, S2, and S3K, skips remaining movie-tail replay, and performs no
terminal-mode assertion.

The short S1 GHZ maze manifest declares `LEVEL`; the full S1 completion manifest declares
`TITLE_SCREEN`. The chain walker consumes only this semantic manifest value after replaying
remaining movie rows. Remove the game-wide S1 terminal-title boolean and its tests.

For a declared endpoint, `tailStart > movie.frameCount()` is a diagnostic failure.
`tailStart == movie.frameCount()` replays zero rows but still asserts the declared mode.

This is fixture metadata, not trace physics/aux data: no engine state is hydrated, no
comparator is loosened, and no run/frame-name predicate is introduced.

The endpoint must be recorder-owned and reproducible. The S1 Lua recorder samples final
`v_gamemode` only at true movie completion (`$0C` → `level`, `$04` → `title_screen`) and
omits the field for hard-stop captures. The native `S1RunCaptureRunner` and
`S1RunManifestWriter`/shared writer emit the same nullable semantic value; S2 and S3K pass
null until equivalent source mapping exists. Update the S1 run-mode behavior specification,
bump the Lua recorder version, add writer/capture tests, run native/Lua differential byte
gates, and regenerate the committed manifest through the recorder path. Do not hand-edit
captured metadata or alter physics/aux payloads.

## MZ1 regression ownership

First replace `TestS1Mz1SlotLayoutRegression`'s bespoke `SharedLevel`, manual VBlank,
manual oscillator, and frame-zero setup with `TraceReplaySessionBootstrap`, including the
S1 native object prelude required because this fixture starts at gameplay counter 1.
Re-run and classify all fourteen ROM-backed methods only after canonical bootstrap.

- retain every passing ROM-backed assertion in a suite-selected regression class;
- move every still-failing slot/lifetime probe to a class named `Debug*Probe`, so the trace
  profile excludes it while preserving explicit direct-run diagnostics;
- remove the stale reflective `usedSlots` test. `ObjectManager.usedSlots` ceased to exist
  after the `SlotAllocator` migration, and replacing it with a derived rewind snapshot
  would be tautological rather than testing allocator ownership.

The standard and complete-run MZ1 trace replay tests remain selected and unchanged. Any
known-red probes must be listed individually in the audit/frontier log with current first
failure evidence; exclusion is ownership correction, not a claim that parity is fixed.
Exact green/red counts are outcomes of canonical remeasurement, not design inputs.

## Testing

Use TDD:

1. parser tests for absent, `LEVEL`, `TITLE_SCREEN`, and invalid terminal values;
2. walker tests proving absence skips tail replay/assertion, declared `LEVEL` and
   `TITLE_SCREEN` endpoints, `tailStart > frameCount` failure, and equality still asserting;
3. recorder/writer/differential tests for movie completion versus hard stop;
4. the focused committed S1 maze chain;
5. the external completion chain using
   `-Dopenggf.trace.s1.run.dir=/home/farrell/code/projects/OpenGGF/tools/bizhawk/trace_output.s1-complete-emeralds-backup`,
   verifying its 10,943-row tail reaches `TITLE_SCREEN`;
6. canonical-bootstrap MZ1 remeasurement, focused selected guards, and direct diagnostics;
7. full S1 trace group, then resume S2, S3K, and complete-profile audit.

The external completion bundle is required local evidence but remains untracked. Committed
unit/walker/recorder tests must cover `TITLE_SCREEN` semantics so CI does not depend on
that external directory.

## Constraints

- ROM/disassembly and recorded metadata remain the source of truth.
- No game, route, fixture-name, or frame-number carve-outs.
- No trace-to-engine hydration, tolerance changes, or physics/aux regeneration.
- Update `docs/status/trace-frontier-log.md` with moved profile status and retained diagnostic
  frontiers.

## Success Criteria

- Independent design, plan, and implementation reviews are green.
- S1 trace profile has zero failures/errors.
- Terminal assertions are exclusively manifest-owned.
- Canonically bootstrapped MZ1 passing guards remain selected; any still-red probes remain
  directly runnable and excluded by explicit diagnostic naming.
