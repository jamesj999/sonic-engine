# ROM Location Resolver Validation

**Date:** 2026-07-29
**Feature:** `feature/ai-rom-location-resolver` at `8af4208a2`
**Comparison base:** detached `aa26f4494`

## Outcome

The typed ROM location resolver is accepted against its exact fork baseline.
The combined resolver, `RomManager`, trace-tool, compatibility, headless-boot,
and architecture matrix is green. The clean all-ROM comparison retains only
the same pre-existing `TestGameLoop` failure and error on both revisions. No
test that passed on the base failed on the feature branch.

The full Maven processes reached the repository's documented post-suite tail
after their Surefire XML inventories stopped changing. Only those two tails
were interrupted. The XML aggregates below are the authoritative clean-suite
results; they do not claim a normal Maven exit.

## Environment and ROM identity

`mvn -v` reported Maven 3.9.16 and Java 21.0.11 from
`/usr/lib/jvm/java-21-openjdk`.

| Game | ROM property and selected file | SHA-1 |
|---|---|---|
| Sonic 1 World REV01 | `sonic1.rom.path=/home/farrell/code/projects/OpenGGF/Sonic The Hedgehog (W) (REV01) [!].gen` | `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b` |
| Sonic 2 World REV01 | `sonic2.rom.path=/home/farrell/code/projects/OpenGGF/Sonic The Hedgehog 2 (W) (REV01) [!].gen` | `8bca5dcef1af3e00098666fd892dc1c2a76333f9` |
| Sonic 3&K locked-on | `s3k.rom.path=/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen` | `cfbf98c36c776677290a872547ac47c53d2761d6` |

## Focused verification

The exact JDK 21 command at feature commit `8af4208a2` was:

```bash
mvn -Dmse=off \
  '-Dsonic1.rom.path=/home/farrell/code/projects/OpenGGF/Sonic The Hedgehog (W) (REV01) [!].gen' \
  '-Dsonic2.rom.path=/home/farrell/code/projects/OpenGGF/Sonic The Hedgehog 2 (W) (REV01) [!].gen' \
  '-Ds3k.rom.path=/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen' \
  '-Dtest=com.openggf.data.TestRomLocationResolver,com.openggf.game.TestGameIdRomGame,com.openggf.data.TestRomManagerLocationResolution,com.openggf.data.TestRomManagerMissingRomLogging,com.openggf.game.TestPowerUpGraphicsRegression,com.openggf.tools.TestTraceToolRomLocations,com.openggf.tools.TraceCaptureToolArgsTest,com.openggf.tools.TestTraceBenchmarkToolArgs,com.openggf.tools.TraceCaptureSessionTest,com.openggf.tools.TestTraceCaptureUnifiedAudio,com.openggf.tests.TestArchUnitTestRules,com.openggf.tests.TestArchUnitRules' \
  test
```

Result: 102 tests, 0 failures, 0 errors, 0 skipped; Maven reported
`BUILD SUCCESS`.

The planned `com.openggf.tools.TestHeadlessGameBoot` class does not exist at
this base. `rg -l 'HeadlessGameBoot' src/test/java` identifies the applicable
tool-side integration coverage as
`com.openggf.tools.TestTraceCaptureUnifiedAudio`, which is included above.

## Clean same-ROM comparison

The detached base and feature worktrees each ran:

```bash
mvn -Dmse=off \
  '-Dsonic1.rom.path=/home/farrell/code/projects/OpenGGF/Sonic The Hedgehog (W) (REV01) [!].gen' \
  '-Dsonic2.rom.path=/home/farrell/code/projects/OpenGGF/Sonic The Hedgehog 2 (W) (REV01) [!].gen' \
  '-Ds3k.rom.path=/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen' \
  clean test
```

| Revision | Surefire XML reports | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| Base `aa26f4494` | 1,731 | 13,534 | 1 | 1 | 31 |
| Feature `8af4208a2` | 1,735 | 13,579 | 1 | 1 | 31 |

Both revisions have the identical failure/error set:

- `com.openggf.TestGameLoop#traceRealtimeRewindRunsBeforePlaybackInputBridge`
  fails because playback forced input is published before setup admission
  (`expected true`, `actual false`).
- `com.openggf.TestGameLoop#setupAdmissionPrecedesSeamlessBoundaryAndTraceCameraMutations`
  errors with `StringIndexOutOfBoundsException`:
  `Range [34669, -1) out of bounds for length 191573`.

The feature adds 45 represented tests with the same skip count. No
base-passing test regressed. The earlier branch-only
`TestSingletonLifecycleGuard` and
`TestS3kPenguinatorBadnik` failures are absent after the approved test-fixture
isolation fix at `8af4208a2`.

The clean runs regenerated
`docs/status/rewind-round-trip-gaps.md`; it was restored byte-for-byte in both
worktrees. The checkout hook also created untracked links for the locally
available disassembly trees under `docs/`; those links were classified as
hook-created and deliberately left alone.

## Validated policy

Production selection now has one typed, filesystem-neutral policy:

- `RomGame` selects exactly its corresponding configured value.
- Blank configuration returns no location; a nonblank configured value wins
  even when its target is missing.
- Relative values resolve against the working directory captured by the
  resolver, while absolute values remain absolute after normalization.
- `RomLocation` preserves the exact configured spelling and provenance while
  carrying `RomFingerprintPolicy.NONE`; it performs no existence, opening,
  content, or fingerprint validation.
- `RomManager` opens the normalized path but retains the raw configured text
  in compatibility diagnostics. Its deprecated string forwarder still maps
  null and unknown values to S2 and returns the exact raw configuration.

Trace capture and benchmark metadata is intentionally stricter. Both tools
parse through `GameId.fromCode`, convert through `GameId.romGame()`, and
resolve before allocating `HeadlessGameBoot`. Blank configuration fails fast
with `No ROM configured for game: <id>`; unknown metadata retains
`Unknown game: <id>`. A nonblank missing or unreadable path still reaches the
unchanged boot/opening boundary, with no default-file search.

## Deferred migrations

This tranche deliberately does not migrate:

- JUnit ROM discovery, caching, and conditions, which retain their
  property/environment/configuration/default first-existing search and
  skip/cache semantics;
- master-title availability, preview, display, and launch UI;
- `SoundTest`, whose explicit `--rom` precedence is a separate contract; or
- `ObjectDiscoveryTool`, `RomOffsetFinder`, and `RomArtIntakeTool`, whose
  positional overrides, generic properties, profiles, and multi-game skip
  behavior require separate designs.

Fingerprint enforcement is also deferred; `NONE` remains the compatibility
policy.

## Updated `develop` reconciliation

Before integration, `develop` advanced from `aa26f4494` to `91793401e` with
the S3K seamless-transition Kosinski work. Merging that baseline into the
feature produced one README conflict; both independent release bullets were
preserved in merge commit `d04e4fc17`.

The combined focused matrix was rerun at `d04e4fc17` and remained green:
102 tests, 0 failures, 0 errors, and 0 skipped.

A host-native clean same-ROM comparison was then run because sandboxed GLFW
tests skip rather than exercise the same paths:

| Revision | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Updated base `91793401e` | 13,561 | 1 | 3 | 13 |
| Reconciled feature `d04e4fc17` | 13,592 | 2 | 3 | 13 |

Both revisions have the original `TestGameLoop` failure/error and the same two
`TestS3kCnzVisualCapture` initial-epoch errors. The additional feature failure
was a nondeterministic
`TestS3kSignpostInstance#fallingDispatchSkipsExpiringCooldownThenAppliesBumpBeforeGravity`
result: it was absent from the preceding feature full-suite sample and passed
on an immediate focused rerun. `TestRequiresRom` also passed focused after two
separate clean samples exposed an intermittent four-fork LWJGL native
extraction crash. These outliers are recorded rather than counted as resolver
regressions: neither touches the changed production paths, both vary between
unchanged feature samples, and both pass focused on the reconciled tree.

The updated clean comparison therefore found no reproducible base-passing
regression attributable to this branch. The focused resolver/manager/tool
matrix is the deterministic integration gate; post-merge validation repeats
that matrix and the full same-ROM suite on `develop`.
